package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.common.JdbcConnectionPoolManager;
import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.quality.TargetWriteContract;
import com.dfygt.dfetl.server.repository.DfetlPrecheckRunRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.NoSuchElementException;

/** 在 Doris 内集合式执行预检规则，不把问题明细搬回 dfetl JVM。 */
@Service
public class DorisPrecheckValidationService {

    private final DfetlPrecheckRunRepository runRepository;
    private final TargetDataSourceRepository targetRepository;
    private final AesUtil aesUtil;
    private final JdbcConnectionPoolManager connectionPoolManager;
    private final DorisPrecheckRuleCompiler ruleCompiler;
    private final int fieldsPerBatch;

    public DorisPrecheckValidationService(
            DfetlPrecheckRunRepository runRepository,
            TargetDataSourceRepository targetRepository,
            AesUtil aesUtil,
            JdbcConnectionPoolManager connectionPoolManager,
            DorisPrecheckRuleCompiler ruleCompiler,
            @Value("${dfetl.data-precheck.doris.fields-per-rule-batch:10}") int fieldsPerBatch) {
        this.runRepository = runRepository;
        this.targetRepository = targetRepository;
        this.aesUtil = aesUtil;
        this.connectionPoolManager = connectionPoolManager;
        this.ruleCompiler = ruleCompiler;
        if (fieldsPerBatch <= 0) {
            throw new IllegalArgumentException("fieldsPerBatch 必须大于 0");
        }
        this.fieldsPerBatch = fieldsPerBatch;
    }

    public DfetlPrecheckRun validate(
            Long runId,
            Long targetDataSourceId,
            DorisPrecheckTableSpec tableSpec,
            MedicalDatasetContract contract,
            TargetWriteContract targetContract) {
        DfetlPrecheckRun run = requireRun(runId);
        if (!"VALIDATING".equals(run.getStatus())) {
            throw new IllegalStateException("只有 VALIDATING 运行可以执行 Doris 规则校验: " + run.getStatus());
        }
        verifyStagingTable(run, tableSpec);

        try {
            TargetDataSource target = requireTarget(targetDataSourceId);
            DorisPrecheckRuleCompiler.CompiledPlan plan = ruleCompiler.compile(
                    runId, tableSpec, contract, targetContract, fieldsPerBatch);
            try (Connection connection = openConnection(target);
                 Statement statement = connection.createStatement()) {
                clearRunArtifacts(statement, runId, tableSpec);
                executeIssueBatches(statement, run, plan);
                assertNotCancelled(run);
                run.setStage("FINALIZING");
                run.setProgressPercent((short) 90);
                runRepository.save(run);
                statement.executeUpdate(summaryInsertSql(runId, tableSpec));
                Metrics metrics = loadMetrics(statement, runId, tableSpec);
                if (metrics.issueCount() != metrics.summaryIssueCount()) {
                    throw new IllegalStateException(
                            "Doris 预检汇总数量与问题明细不一致: issues=" + metrics.issueCount()
                                    + ", summary=" + metrics.summaryIssueCount());
                }
                assertRowCountIntegrity(run, metrics);
                complete(run, metrics);
                return runRepository.save(run);
            }
        } catch (PrecheckCancelledException e) {
            run.setStatus("CANCELLED");
            run.setStage("COMPLETED");
            run.setProgressPercent((short) 100);
            if (run.getFinishedAt() == null) {
                run.setFinishedAt(Instant.now());
            }
            return runRepository.save(run);
        } catch (Exception e) {
            fail(run, e);
            return runRepository.save(run);
        }
    }

    private void clearRunArtifacts(
            Statement statement,
            Long runId,
            DorisPrecheckTableSpec tableSpec) throws Exception {
        statement.execute("SET delete_without_partition = true");
        statement.executeUpdate("DELETE FROM " + qualified(tableSpec.database(), "precheck_summary")
                + " WHERE `run_id` = " + runId);
        statement.executeUpdate("DELETE FROM " + qualified(tableSpec.database(), "precheck_issue")
                + " WHERE `run_id` = " + runId);
    }

    private void executeIssueBatches(
            Statement statement,
            DfetlPrecheckRun run,
            DorisPrecheckRuleCompiler.CompiledPlan plan) throws Exception {
        int total = plan.batches().size();
        if (total == 0) {
            return;
        }
        for (int index = 0; index < total; index++) {
            assertNotCancelled(run);
            statement.executeUpdate(plan.batches().get(index).insertSql());
            short progress = (short) (65 + Math.max(1, ((index + 1) * 20 / total)));
            run.setProgressPercent((short) Math.min(progress, 85));
            runRepository.save(run);
        }
    }

    private void assertNotCancelled(DfetlPrecheckRun run) {
        DfetlPrecheckRun latest = runRepository.findById(run.getId())
                .orElseThrow(() -> new NoSuchElementException("预检运行不存在: " + run.getId()));
        if (!"CANCELLED".equals(latest.getStatus())) {
            return;
        }
        run.setStatus("CANCELLED");
        run.setStage("COMPLETED");
        run.setProgressPercent((short) 100);
        run.setFinishedAt(latest.getFinishedAt());
        run.setErrorMessage(latest.getErrorMessage());
        throw new PrecheckCancelledException();
    }

    private String summaryInsertSql(Long runId, DorisPrecheckTableSpec tableSpec) {
        String issue = qualified(tableSpec.database(), "precheck_issue");
        return "INSERT INTO " + qualified(tableSpec.database(), "precheck_summary")
                + " (`run_id`, `created_at`, `field_code`, `error_type`, `severity`, "
                + "`issue_count`, `affected_rows`) "
                + "SELECT `run_id`, NOW(), COALESCE(`field_code`, '__ROW__'), `error_type`, `severity`, "
                + "COUNT(*), COUNT(DISTINCT `row_id`) FROM " + issue
                + " WHERE `run_id` = " + runId
                + " GROUP BY `run_id`, COALESCE(`field_code`, '__ROW__'), `error_type`, `severity`";
    }

    private Metrics loadMetrics(
            Statement statement,
            Long runId,
            DorisPrecheckTableSpec tableSpec) throws Exception {
        String raw = tableSpec.qualifiedRawTable();
        String issue = qualified(tableSpec.database(), "precheck_issue");
        String summary = qualified(tableSpec.database(), "precheck_summary");
        String sql = "SELECT "
                + "(SELECT COUNT(*) FROM " + raw + " WHERE `run_id` = " + runId + ") AS checked_rows, "
                + "(SELECT COUNT(*) FROM " + issue + " WHERE `run_id` = " + runId + ") AS issue_count, "
                + "(SELECT COUNT(DISTINCT `row_id`) FROM " + issue
                + " WHERE `run_id` = " + runId + ") AS affected_rows, "
                + "(SELECT COUNT(DISTINCT `row_id`) FROM " + issue
                + " WHERE `run_id` = " + runId + " AND `severity` = 'BLOCKER') AS blocker_rows, "
                + "(SELECT COUNT(DISTINCT `row_id`) FROM " + issue
                + " WHERE `run_id` = " + runId + " AND `severity` = 'WARNING') AS warning_rows, "
                + "(SELECT COALESCE(SUM(`issue_count`), 0) FROM " + summary
                + " WHERE `run_id` = " + runId + ") AS summary_issue_count";
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Doris 未返回预检统计结果");
            }
            return new Metrics(
                    resultSet.getLong("checked_rows"),
                    resultSet.getLong("issue_count"),
                    resultSet.getLong("affected_rows"),
                    resultSet.getLong("blocker_rows"),
                    resultSet.getLong("warning_rows"),
                    resultSet.getLong("summary_issue_count"));
        }
    }

    private void complete(DfetlPrecheckRun run, Metrics metrics) {
        run.setCheckedRows(metrics.checkedRows());
        run.setScannedRows(metrics.checkedRows());
        run.setIssueCount(metrics.issueCount());
        run.setBlockerRows(metrics.blockerRows());
        run.setWarningRows(metrics.warningRows());
        run.setPassedRows(Math.max(0L, metrics.checkedRows() - metrics.affectedRows()));
        run.setStatus(metrics.issueCount() == 0 ? "PASSED" : "HAS_ERRORS");
        run.setStage("COMPLETED");
        run.setProgressPercent((short) 100);
        run.setErrorMessage(null);
        run.setFinishedAt(Instant.now());
    }

    private void assertRowCountIntegrity(DfetlPrecheckRun run, Metrics metrics) {
        Long sourceRows = run.getSourceRows();
        Long loadedRows = run.getLoadedRows();
        if (sourceRows == null || loadedRows == null
                || sourceRows.longValue() != loadedRows.longValue()
                || loadedRows.longValue() != metrics.checkedRows()) {
            throw new IllegalStateException(
                    "Doris 预检行数不完整: sourceRows=" + sourceRows
                            + ", loadedRows=" + loadedRows
                            + ", checkedRows=" + metrics.checkedRows());
        }
    }

    private void fail(DfetlPrecheckRun run, Exception exception) {
        run.setStatus("FAILED");
        run.setStage("COMPLETED");
        run.setFinishedAt(Instant.now());
        String detail = exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        run.setErrorMessage(ExecutionErrorSanitizer.sanitize(detail));
    }

    private DfetlPrecheckRun requireRun(Long runId) {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 必须大于 0");
        }
        return runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("预检运行不存在: " + runId));
    }

    private TargetDataSource requireTarget(Long targetDataSourceId) {
        if (targetDataSourceId == null || targetDataSourceId <= 0) {
            throw new IllegalArgumentException("targetDataSourceId 必须大于 0");
        }
        TargetDataSource target = targetRepository.findById(targetDataSourceId)
                .orElseThrow(() -> new NoSuchElementException("目标数据源不存在: " + targetDataSourceId));
        if (!"NORMAL".equals(target.getStatus())) {
            throw new IllegalStateException("目标数据源状态不可用于预检: " + target.getStatus());
        }
        return target;
    }

    private Connection openConnection(TargetDataSource target) throws Exception {
        int port = target.getFePort() == null ? 9030 : target.getFePort();
        String url = "jdbc:mysql://" + target.getFeHost() + ":" + port + "/" + target.getDbName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        return connectionPoolManager.getConnection(
                url, target.getUsername(), aesUtil.decrypt(target.getPasswordEnc()));
    }

    private void verifyStagingTable(DfetlPrecheckRun run, DorisPrecheckTableSpec tableSpec) {
        if (tableSpec == null) {
            throw new IllegalArgumentException("Doris 预检表规格不能为空");
        }
        String expected = tableSpec.database() + "." + tableSpec.rawTable();
        if (run.getStagingTable() == null || !expected.equalsIgnoreCase(run.getStagingTable())) {
            throw new IllegalStateException("预检运行暂存表与字段合同不一致: expected=" + expected);
        }
    }

    private static String qualified(String database, String table) {
        return "`" + database + "`.`" + table + "`";
    }

    private record Metrics(
            long checkedRows,
            long issueCount,
            long affectedRows,
            long blockerRows,
            long warningRows,
            long summaryIssueCount) {
    }

    private static final class PrecheckCancelledException extends RuntimeException {
    }
}
