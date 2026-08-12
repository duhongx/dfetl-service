package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.JdbcConnectionPoolManager;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalDatasetContractService;
import com.dfygt.dfetl.server.medical.source.MedicalSourceSelectBuilder;
import com.dfygt.dfetl.server.medical.source.MedicalSourceSelectPlan;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapterResolver;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import com.dfygt.dfetl.server.repository.TaskValidationConfigRepository;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffRepository;
import com.dfygt.dfetl.server.service.validation.ValidationJdbcHelper;
import com.dfygt.dfetl.server.service.validation.ValidationWhereBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 校验任务执行器。
 * <ul>
 *   <li>ROW_COUNT：对每张关联表执行 SELECT COUNT(*)，比较源端与目标端行数</li>
 *   <li>CHECKSUM ：调用 ChecksumService 逐分片 hash 对比，写入 etl_verify_chunk / etl_verify_diff</li>
 * </ul>
 *
 * <p>spec validation-table-consolidation · Step 10：
 * 写入路径已完全切换为 validation_run 表，双写兼容期代码已移除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValidationRunner {

    private final ValidationRunRepository validationRunRepository;
    private final TaskExecutionRepository executionRepo;
    private final SyncTaskRepository syncTaskRepo;
    private final SourceDataSourceRepository sourceDsRepo;
    private final SourceDataSourceService sourceDataSourceService;
    private final TargetDataSourceRepository targetDsRepo;
    private final AesUtil aesUtil;
    private final ChecksumService checksumService;
    private final GlobalSettingsService globalSettingsService;

    // spec 033：自动 Repair 依赖（使用 @Lazy 避免与 RepairService 循环依赖）
    private final TaskValidationConfigRepository taskValidationConfigRepository;
    private final EtlVerifyDiffRepository etlVerifyDiffRepository;
    private final TargetTableResolver targetTableResolver;
    private final TargetFieldResolver targetFieldResolver;
    private final SourceTableResolver sourceTableResolver;
    private final DialectQuoteHelper dialectQuoteHelper;
    private final WhereClauseBuilder whereClauseBuilder;
    private final ValidationWhereBuilder validationWhereBuilder;
    private final ValidationJdbcHelper validationJdbcHelper;
    private final ValidationRunService validationRunService;
    private final JdbcConnectionPoolManager connectionPoolManager;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired(required = false)
    @Lazy
    private MedicalDatasetContractService medicalContractService;

    @Autowired(required = false)
    @Lazy
    private MedicalSourceSelectBuilder medicalSourceSelectBuilder;

    @Autowired(required = false)
    @Lazy
    private SourceDialectAdapterResolver sourceDialectAdapterResolver;
    @Autowired @Lazy
    private RepairService repairService;
    @Autowired @Lazy
    private ValidationDispatchService validationDispatchService;
    @Autowired @Lazy
    private AlertEvaluatorService alertEvaluatorService;

    /** revalidate 最大重试次数（防止持续 DIFF 导致无限循环）。 */
    private static final int MAX_REVALIDATE_ATTEMPTS = 1;

    // ══════════════════════════════════════════════════════════════════════════
    //   spec validation-table-consolidation · Step 6
    //   主入口改为接收 ValidationRun，写入路径切换为 validation_run 表
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 异步执行校验（由虚拟线程调用，不阻塞主线程）。
     * 执行完成后更新 validation_run 的结果字段。
     */
    public void runAsync(ValidationRun run, TaskValidationConfig config) {
        Thread.ofVirtual().name("validator-" + run.getId()).start(() -> doRun(run, config, 0, true));
    }

    /**
     * 同步执行单次校验（供测试、门控和修复后复查使用）。
     *
     * <p>同步调用方必须在当前调用链中决定是否重试，不能再派生后台 revalidate；
     * 否则门控可能已经判定失败，后台复检却随后改为一致，造成任务终态和水位不一致。
     */
    public void run(ValidationRun run, TaskValidationConfig config) {
        doRun(run, config, 0, false);
    }

    // ── 核心执行 ──────────────────────────────────────────────────────────────

    private void doRun(
            ValidationRun run,
            TaskValidationConfig config,
            int attempt,
            boolean allowAsyncRevalidate) {
        long startMs = System.currentTimeMillis();
        log.info("ValidationRunner start: runId={} method={} attempt={}", run.getId(), run.getMethod(), attempt);

        try {
            String method = normalizeMethod(run.getMethod());
            if ("ROW_COUNT".equals(method)) {
                runRowCount(run, config);
            } else if ("CHECKSUM".equals(method) || "ROW_COUNT_CHECKSUM".equals(method) || "ALL".equals(method)) {
                runChecksum(run, config);
            } else if ("SAMPLE".equals(method)) {
                updateResult(run, null, null, null,
                        System.currentTimeMillis() - startMs, "ERROR",
                        "Unsupported validation method: SAMPLE");
            } else {
                updateResult(run, null, null, null,
                        System.currentTimeMillis() - startMs, "ERROR",
                        "Unsupported validation method: " + method);
            }
            // 校验完成后检查是否需要自动重试（仅当结果为 DIFF 且未超过最大重试次数）
            if (allowAsyncRevalidate) {
                if (attempt < MAX_REVALIDATE_ATTEMPTS) {
                    checkAndScheduleRevalidate(run, attempt + 1);
                } else {
                    // spec 033：revalidate 耗尽仍 DIFF → 试图自动 Repair
                    maybeAutoRepair(run, config);
                }
            }
        } catch (Exception e) {
            log.error("ValidationRunner failed: runId={}", run.getId(), e);
            updateResult(run, null, null, null,
                    System.currentTimeMillis() - startMs, "ERROR", formatError(e));
        }
    }

    // ── CHECKSUM 实现（委托给 ChecksumService）────────────────────────────────

    private String normalizeMethod(String method) {
        return method == null || method.isBlank()
                ? "ROW_COUNT"
                : method.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private void runChecksum(ValidationRun run, TaskValidationConfig config) {
        if (run.getTaskId() == null) {
            log.warn("ValidationRunner CHECKSUM: no taskId for runId={}", run.getId());
            updateResult(run, null, null, null, 0L, "ERROR", "validation_run.task_id 为空，无法定位同步任务");
            return;
        }
        long startMs = System.currentTimeMillis();
        try {
            // SQL 持久化：在调用 checksumService 前记录基础查询模板
            persistChecksumSql(run);

            java.time.Instant winStart = run.getWindowStart();
            java.time.Instant winEnd   = run.getWindowEnd();
            String checksumAlgo = config != null ? config.getChecksumAlgo() : null;
            // spec validation-workbench-redesign · Task P1-5.2：预先创建带 trigger_type 的 ValidationRun。
            // ChecksumService.verify 内部会再调 getOrCreate（旧 6-arg），但因 (taskId, legacyExecId)
            // 已存在，会走 findByTaskIdAndLegacyExecId 分支重用本次记录、不覆盖 trigger_type。
            WatermarkService.WindowContext preCheckWindow = checksumWindowOrNull(run);
            String preScope;
            java.time.Instant preStart;
            java.time.Instant preEnd;
            if (preCheckWindow != null) {
                preScope = isFullScope(preCheckWindow) ? "FULL" : "WINDOW";
                preStart = preCheckWindow.windowStart();
                preEnd = preCheckWindow.windowEnd();
            } else {
                preScope = (winStart == null && winEnd == null) ? "FULL" : "WINDOW";
                preStart = winStart;
                preEnd = winEnd;
            }
            validationRunService.getOrCreate(run.getTaskId(), run.getLegacyExecId(), "CHECKSUM", preScope,
                    preStart, preEnd, normalizeTriggerType(run.getTriggerType()));

            ChecksumService.VerifyReport report;
            WatermarkService.WindowContext checksumWindow = checksumWindowOrNull(run);
            if (checksumWindow != null) {
                report = checksumService.verify(run.getTaskId(), run.getLegacyExecId(), null, checksumWindow, checksumAlgo);
            } else {
                report = checksumService.verify(run.getTaskId(), run.getLegacyExecId(), null, winStart, winEnd, checksumAlgo);
            }
            long durationMs = System.currentTimeMillis() - startMs;
            String status = report.diffCount() == 0 ? "CONSISTENT" : "DIFF";
            log.info("ValidationRunner CHECKSUM complete: runId={} chunks={} matched={} diff={} status={} {}ms",
                    run.getId(), report.totalChunks(), report.matchedChunks(), report.diffCount(), status, durationMs);
            updateResult(run, report.sourceCount(), report.targetCount(), report.diffCount(), durationMs, status, null);

            // spec 069：检测目标端 _etl_job_id NULL 历史行，给非阻塞口径告警（不改变 status）
            SyncTask checksumTask = syncTaskRepo.findById(run.getTaskId()).orElse(null);
            if (checksumTask != null) {
                applyScopeWarning(run, detectScopeNullWarning(run, checksumTask, resolveTablePairs(run, config, checksumTask)));
            }
        } catch (Exception e) {
            log.error("ValidationRunner CHECKSUM failed: runId={}", run.getId(), e);
            updateResult(run, null, null, null, System.currentTimeMillis() - startMs, "ERROR", formatError(e));
        }
    }

    /**
     * 记录 ChecksumService 使用的源端和目标端基础查询模板（不含逐分片 chunk 边界）。
     * 格式：SELECT columns FROM schema.table WHERE condition
     */
    private void persistChecksumSql(ValidationRun run) {
        try {
            SyncTask task = syncTaskRepo.findById(run.getTaskId()).orElse(null);
            if (task == null || task.getViewNames() == null || task.getViewNames().isEmpty()) {
                return;
            }
            // Checksum 仅支持单表
            String configuredSrcTable = task.getViewNames().get(0);
            SourceDataSource srcDs = task.getSourceDataSourceId() == null ? null
                    : sourceDsRepo.findById(task.getSourceDataSourceId()).orElse(null);
            if (srcDs == null) return;

            SourceTableResolver.SourceRelation sourceRelation =
                    sourceTableResolver.resolveRequired(task, srcDs, configuredSrcTable);
            String srcDialect = sourceRelation.dialect();
            String srcSchema = sourceRelation.schema();
            String srcTable = sourceRelation.table();
            String srcQualified = dialectQuoteHelper.qualifyTable(srcDialect, srcSchema, srcTable);

            // 源端 WHERE 条件（与 ChecksumService 使用相同逻辑）
            WatermarkService.WindowContext checksumWindow = checksumWindowOrNull(run);
            WatermarkService.WindowContext effectiveWindow = checksumWindow != null
                    ? checksumWindow
                    : new WatermarkService.WindowContext("FULL", null, null, null, null);
            List<String> srcCols = sourceDataSourceService
                    .listColumns(task.getSourceDataSourceId(), srcSchema, srcTable)
                    .stream()
                    .map(SourceDataSourceService.ColumnInfo::columnName)
                    .toList();
            String sourceWhere = validationWhereBuilder.buildSourceWhere(
                    effectiveWindow, task, srcDialect, srcTable, srcCols);

            // 目标端表名和 WHERE 条件
            String tgtDb = null;
            if (task.getTargetDataSourceId() != null) {
                var tds = targetDsRepo.findById(task.getTargetDataSourceId()).orElse(null);
                if (tds != null) tgtDb = tds.getDbName();
            }
            String tgtTable = targetTableResolver.resolve(task, srcTable);
            String tgtQualified = dialectQuoteHelper.qualifyTable("DORIS", tgtDb, tgtTable);
            String targetWhere = validationWhereBuilder.buildTargetWindowWhere(
                    effectiveWindow, task, srcTable, "DORIS");
            targetWhere = ValidationWhereBuilder.appendDorisDeleteSignFilter(task, targetWhere);
            // spec 069：多机构共表目标侧任务范围过滤
            targetWhere = validationWhereBuilder.appendTenantScopeFilter(task, targetWhere);

            // 构建查询模板
            String columnsPlaceholder = "columns";
            String sourceTemplate = "SELECT %s FROM %s".formatted(columnsPlaceholder, srcQualified);
            if (sourceWhere != null && !sourceWhere.isEmpty()) {
                sourceTemplate += " WHERE " + sourceWhere;
            }
            String targetTemplate = "SELECT %s FROM %s".formatted(columnsPlaceholder, tgtQualified);
            if (targetWhere != null && !targetWhere.isEmpty()) {
                targetTemplate += " WHERE " + targetWhere;
            }

            // 直接更新当前 ValidationRun 记录（无需再通过 legacyExecId 查找）
            run.setSourceSql(ValidationJdbcHelper.truncateSql(
                    ValidationJdbcHelper.sanitizeSqlForPersistence(sourceTemplate), 10000));
            run.setTargetSql(ValidationJdbcHelper.truncateSql(
                    ValidationJdbcHelper.sanitizeSqlForPersistence(targetTemplate), 10000));
            run.setSourceWhere(ValidationJdbcHelper.truncateSql(
                    ValidationJdbcHelper.sanitizeSqlForPersistence(sourceWhere), 10000));
            run.setTargetWhere(ValidationJdbcHelper.truncateSql(
                    ValidationJdbcHelper.sanitizeSqlForPersistence(targetWhere), 10000));
            validationRunRepository.save(run);
        } catch (Exception e) {
            log.warn("persistChecksumSql failed: runId={}: {}", run.getId(), e.getMessage());
        }
    }

    // ── 自动重试（revalidate）────────────────────────────────────────────────

    private void checkAndScheduleRevalidate(ValidationRun run, int nextAttempt) {
        try {
            // 重新读状态（runChecksum/runRowCount 已更新）
            ValidationRun fresh = validationRunRepository.findById(run.getId()).orElse(null);
            if (fresh == null || !"DIFF".equals(fresh.getStatus())) return;
            var policy = globalSettingsService.getValidationPolicy();
            if (!policy.revalidate()) return;

            long delayMs = Math.max(0L, (long) policy.revalidateDelay() * 1000);
            log.info("ValidationRunner: scheduling revalidate for runId={} attempt={} after {}s",
                    run.getId(), nextAttempt, policy.revalidateDelay());

            Thread.ofVirtual().name("revalidator-" + run.getId()).start(() -> {
                try {
                    if (delayMs > 0) Thread.sleep(delayMs);
                    // 使用 ValidationDispatchService 的事务方法加悲观锁检查，
                    // 避免绕过 dispatchTriggered 的并发防护
                    Long taskId = run.getTaskId();
                    if (taskId == null) return;
                    boolean acquired = validationDispatchService
                            .tryLockAndMarkRunningForRevalidate(taskId, run.getId());
                    if (!acquired) {
                        log.warn("ValidationRunner revalidate: SKIPPED runId={} - "
                                + "lock not acquired or status changed", run.getId());
                        return;
                    }
                    ValidationRun retry = validationRunRepository.findById(run.getId()).orElse(null);
                    if (retry == null) return;
                    TaskValidationConfig reloadedConfig = retry.getTaskId() != null
                            ? taskValidationConfigRepository.findByTaskId(retry.getTaskId()).orElse(null)
                            : null;
                    doRun(retry, reloadedConfig, nextAttempt, true);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("Revalidate failed: runId={}", run.getId(), e);
                }
            });
        } catch (Exception e) {
            log.warn("checkAndScheduleRevalidate failed: runId={}: {}", run.getId(), e.getMessage());
        }
    }

    // ── spec 033：DIFF 自动 Repair ───────────────────────────────────────────

    private void maybeAutoRepair(ValidationRun run, TaskValidationConfig config) {
        try {
            ValidationRun fresh = validationRunRepository.findById(run.getId()).orElse(null);
            if (fresh == null || !"DIFF".equals(fresh.getStatus())) return;
            if (fresh.getTaskId() == null) return;

            TaskValidationConfig cfg = config != null
                    ? config
                    : taskValidationConfigRepository.findByTaskId(fresh.getTaskId()).orElse(null);
            if (cfg == null || !Boolean.TRUE.equals(cfg.getEnabled())
                    || !Boolean.TRUE.equals(cfg.getAutoRepair())) {
                return;
            }
            long maxRows = cfg.getAutoRepairMaxRows() != null ? cfg.getAutoRepairMaxRows() : 1000L;
            long pending = etlVerifyDiffRepository.countByValidationRunIdAndRepairStatus(
                    fresh.getId(), "PENDING");
            if (pending == 0) return;
            if (pending > maxRows) {
                log.warn("AutoRepair: runId={} pending={} > maxRows={}，跳过自动修复（请人工核对）",
                        fresh.getId(), pending, maxRows);
                return;
            }
            log.info("AutoRepair: runId={} pending={} 启动自动修复（dryRun=false, forceDelete=false）",
                    fresh.getId(), pending);
            // 异步执行，避免阻塞校验线程
            Thread.ofVirtual().name("auto-repair-" + fresh.getId()).start(() -> {
                try {
                    // spec validation-workbench-redesign · Task P1-6.2：自动 Repair 来源 = AUTO
                    var report = repairService.repairRun(fresh.getTaskId(), fresh.getId(), false, false, (int) maxRows, "AUTO");
                    log.info("AutoRepair done: runId={} status={} loaded={} failed={}",
                            fresh.getId(), report.status(), report.loadedRows(), report.failedRows());
                } catch (Exception e) {
                    log.error("AutoRepair failed: runId={}", fresh.getId(), e);
                }
            });
        } catch (Exception e) {
            log.warn("maybeAutoRepair failed: runId={}: {}", run.getId(), e.getMessage());
        }
    }

    // ── ROW_COUNT 实现 ────────────────────────────────────────────────────────

    private void runRowCount(ValidationRun run, TaskValidationConfig config) throws Exception {
        if (run.getTaskId() == null) {
            log.warn("ValidationRunner: no taskId for runId={}", run.getId());
            updateResult(run, null, null, null, 0L, "ERROR",
                    "validation_run.task_id 为空，无法定位同步任务");
            return;
        }

        long startMs = System.currentTimeMillis();

        // 1. 获取关联的同步任务
        SyncTask task = syncTaskRepo.findById(run.getTaskId()).orElse(null);
        if (task == null) {
            log.warn("ValidationRunner: SyncTask {} not found", run.getTaskId());
            updateResult(run, null, null, null, 0L, "ERROR",
                    "SyncTask not found: " + run.getTaskId());
            return;
        }
        applyExecutionMedicalValidSourceQuery(run, task);

        // 2. 获取源端数据源连接信息
        SourceDataSource srcDs = task.getSourceDataSourceId() == null ? null
                : sourceDsRepo.findById(task.getSourceDataSourceId()).orElse(null);
        if (srcDs == null) {
            log.warn("ValidationRunner: SourceDataSource not found for task {}", task.getId());
            updateResult(run, null, null, null, 0L, "ERROR",
                    "SourceDataSource not found for task: " + task.getId());
            return;
        }

        // 3. 确定要校验的表
        List<TablePair> tablePairs = resolveTablePairs(run, config, task);
        if (tablePairs.isEmpty()) {
            log.warn("ValidationRunner: no tables to validate for runId={}", run.getId());
            updateResult(run, 0L, 0L, 0L, System.currentTimeMillis() - startMs, "CONSISTENT");
            return;
        }

        // 创建 ValidationRun 记录（与 CHECKSUM 路径一致）
        // spec validation-workbench-redesign · Task P1-5.2：传入 ValidationRun.triggerType
        WatermarkService.WindowContext effectiveWindow = validationWindow(run, task);
        String scope = isFullScope(effectiveWindow) ? "FULL" : "WINDOW";
        validationRunService.getOrCreate(run.getTaskId(), run.getLegacyExecId(), "ROW_COUNT", scope,
                effectiveWindow.windowStart(), effectiveWindow.windowEnd(),
                normalizeTriggerType(run.getTriggerType()));

        // 4. 目标端：Doris，通过 MySQL 协议连接 FE
        String targetJdbcUrl = validationJdbcHelper.buildDorisJdbcUrl(task);
        String targetUser;
        String targetPass;
        String tgtDb = null;
        if (task.getTargetDataSourceId() != null) {
            var tds = targetDsRepo.findById(task.getTargetDataSourceId()).orElse(null);
            targetUser = tds != null ? tds.getUsername() : "root";
            targetPass = tds != null ? aesUtil.decrypt(tds.getPasswordEnc()) : "";
            if (tds != null) tgtDb = tds.getDbName();
        } else {
            targetUser = "root";
            targetPass = "";
        }

        // 5. 逐表 COUNT(*)：逐表判定一致性，避免多表累加抵消（表A少100 + 表B多100 误判一致）
        long totalSrc = 0;
        long totalTgt = 0;
        // 逐表容差判定结果：任一表 DIFF 则整体 DIFF
        boolean anyTableDiff = false;
        int checkedTableCount = 0;
        StringBuilder perTableDiffDetail = new StringBuilder();
        // 容差配置（在循环外读取一次，循环内按单表 srcCnt 计算百分比容差）
        long absoluteTolerance = config != null && config.getToleranceRows() != null ? config.getToleranceRows() : 0L;
        double globalPct = globalSettingsService.getValidationPolicy().rowTolerance();
        double taskPct = config != null && config.getTolerancePct() != null
                ? config.getTolerancePct().doubleValue()
                : 0.0;
        String srcJdbcUrl = validationJdbcHelper.buildSourceJdbcUrl(srcDs);
        String srcPass = aesUtil.decrypt(srcDs.getPasswordEnc());
        // 源/目标方言与 schema 解析
        String srcDialect = srcDs.getType() == null ? "MYSQL" : srcDs.getType().toUpperCase();
        boolean customSqlMode = isCustomSql(task);
        // 目标 Doris 库名已在上方从 TargetDataSource 获取

        // SQL 持久化：收集各表的源端/目标端 SQL 和 WHERE 条件
        StringBuilder sourceSqlBuilder = new StringBuilder();
        StringBuilder targetSqlBuilder = new StringBuilder();
        StringBuilder sourceWhereBuilder = new StringBuilder();
        StringBuilder targetWhereBuilder = new StringBuilder();

        for (TablePair pair : tablePairs) {
            try {
                String tgtQualified = dialectQuoteHelper.qualifyTable("DORIS", tgtDb, pair.targetTable());
                WatermarkService.WindowContext validationWindow = validationWindow(run, task);
                String tgtWindowSql = validationWhereBuilder.buildTargetWindowWhere(validationWindow, task, pair.sourceTable(), "DORIS");
                // spec 063：Doris MERGE 软删除时，目标侧排除 __doris_delete_sign__ = 1 的已删除行
                tgtWindowSql = ValidationWhereBuilder.appendDorisDeleteSignFilter(task, tgtWindowSql);
                // spec 069：多机构共表目标侧任务范围过滤（_etl_job_id = taskId），与源端口径对齐
                tgtWindowSql = validationWhereBuilder.appendTenantScopeFilter(task, tgtWindowSql);
                long srcCnt;
                String srcSqlForLog;
                String srcWhereForLog = "";
                if (customSqlMode) {
                    List<String> sourceColumns = sourceDataSourceService
                            .listCustomSqlColumns(task.getSourceDataSourceId(), task.getCustomSql())
                            .stream()
                            .map(SourceDataSourceService.ColumnInfo::columnName)
                            .toList();
                    String srcCountSql = validationWhereBuilder.buildCustomSqlCountSql(validationWindow, task, srcDialect, sourceColumns);
                    srcSqlForLog = srcCountSql;
                    srcWhereForLog = srcCountSql; // CUSTOM_SQL 模式下整条 SQL 即为 WHERE 信息

                    // 收集 SQL 信息（在 countRows 调用前）
                    if (sourceSqlBuilder.length() > 0) sourceSqlBuilder.append("\n");
                    sourceSqlBuilder.append(srcSqlForLog);
                    if (targetSqlBuilder.length() > 0) targetSqlBuilder.append("\n");
                    targetSqlBuilder.append(tgtQualified)
                            .append(tgtWindowSql.isEmpty() ? "" : " WHERE " + tgtWindowSql);
                    if (sourceWhereBuilder.length() > 0) sourceWhereBuilder.append("\n");
                    sourceWhereBuilder.append(pair.sourceTable()).append(": ").append(srcWhereForLog);
                    if (targetWhereBuilder.length() > 0) targetWhereBuilder.append("\n");
                    targetWhereBuilder.append(pair.targetTable()).append(": ").append(tgtWindowSql);

                    srcCnt = countRowsSql(srcJdbcUrl, srcDs.getUsername(), srcPass, srcCountSql, "源端CUSTOM_SQL", srcDs);
                } else {
                    // Bug Fix：表名必须 schema-qualified，否则在不同连接默认 schema/database 下会查错表
                    SourceTableResolver.SourceRelation sourceRelation =
                            sourceTableResolver.resolveRequired(task, srcDs, pair.sourceTable());
                    String srcQualified = dialectQuoteHelper.qualifyTable(
                            sourceRelation.dialect(), sourceRelation.schema(), sourceRelation.table());
                    // 源端复用执行链路 TABLE_VIEW WHERE：staticFilter + per-table filter + window
                    List<SourceDataSourceService.ColumnInfo> sourceColumnInfos = sourceDataSourceService
                            .listColumns(task.getSourceDataSourceId(), sourceRelation.schema(), sourceRelation.table());
                    List<String> sourceColumns = sourceColumnInfos.stream()
                            .map(SourceDataSourceService.ColumnInfo::columnName)
                            .toList();
                    String srcWindowSql = validationWhereBuilder.buildSourceWhere(
                            validationWindow, task, sourceRelation.dialect(), sourceRelation.table(), sourceColumns);
                    boolean keyAwareRowCount = isUpsertKeyAwareRowCount(run, task);
                    List<String> sourceKeyColumns = keyAwareRowCount
                            ? resolveSourceKeyColumns(task, sourceColumnInfos)
                            : List.of();
                    srcSqlForLog = buildRowCountSourceSql(
                            task, srcDs, sourceRelation, srcWindowSql, sourceColumnInfos,
                            keyAwareRowCount, sourceKeyColumns);
                    srcWhereForLog = srcWindowSql;

                    // 收集 SQL 信息（在 countRows 调用前）
                    if (sourceSqlBuilder.length() > 0) sourceSqlBuilder.append("\n");
                    sourceSqlBuilder.append(srcSqlForLog);
                    if (targetSqlBuilder.length() > 0) targetSqlBuilder.append("\n");
                    targetSqlBuilder.append(tgtQualified)
                            .append(tgtWindowSql.isEmpty() ? "" : " WHERE " + tgtWindowSql);
                    if (sourceWhereBuilder.length() > 0) sourceWhereBuilder.append("\n");
                    sourceWhereBuilder.append(pair.sourceTable()).append(": ").append(srcWhereForLog);
                    if (targetWhereBuilder.length() > 0) targetWhereBuilder.append("\n");
                    targetWhereBuilder.append(pair.targetTable()).append(": ").append(tgtWindowSql);

                    srcCnt = countRowsSqlWithTimeout(srcJdbcUrl, srcDs.getUsername(), srcPass,
                            srcSqlForLog, "源端", srcDs);
                }
                boolean keyAwareRowCount = isUpsertKeyAwareRowCount(run, task);
                List<String> targetKeyColumns = keyAwareRowCount ? task.getUpsertKeys() : List.of();
                long tgtCnt = countRows(targetJdbcUrl, targetUser, targetPass, tgtQualified,
                        tgtWindowSql, "目标端", keyAwareRowCount, targetKeyColumns);
                totalSrc += srcCnt;
                totalTgt += tgtCnt;
                checkedTableCount++;

                // ── 逐表一致性判定（P1 修复：不再用累加总数判定，避免跨表抵消）──
                long tableDiff = Math.abs(srcCnt - tgtCnt);
                long tableTolerance = effectiveRowTolerance(srcCnt, absoluteTolerance, globalPct, taskPct);
                boolean tableConsistent = tableDiff <= tableTolerance;
                if (!tableConsistent) {
                    anyTableDiff = true;
                    if (perTableDiffDetail.length() > 0) perTableDiffDetail.append("; ");
                    perTableDiffDetail.append(pair.sourceTable())
                            .append("(src=").append(srcCnt)
                            .append(",tgt=").append(tgtCnt)
                            .append(",diff=").append(tableDiff)
                            .append(",tol=").append(tableTolerance).append(")");
                }
                log.info("ValidationRunner table={} src={} tgt={} diff={} tol={} consistent={} (srcSql={} tgtSql={}{})",
                        pair.sourceTable(), srcCnt, tgtCnt, tableDiff, tableTolerance, tableConsistent,
                        srcSqlForLog,
                        tgtQualified, tgtWindowSql.isEmpty() ? "" : " WHERE " + tgtWindowSql);
            } catch (SQLException e) {
                String sanitizedUrl = ValidationJdbcHelper.sanitizeJdbcUrlForLog(srcJdbcUrl);
                log.error("ValidationRunner: countRows FAILED taskId={} runId={} table={} url={} sql=[see previous per-table log]",
                        run.getTaskId(), run.getId(), pair.sourceTable(), sanitizedUrl, e);
                // P2 修复：ERROR 时不写入残缺的部分累计值（避免运维误读为全部表行数），
                // sourceRows/targetRows 置 null 表示「未完成、数据不可信」，errorMsg 标注已完成表数。
                String errorMsg = "countRows failed on table [%s] (已完成 %d/%d 张表，结果不完整): %s"
                        .formatted(pair.sourceTable(), checkedTableCount, tablePairs.size(), e.getMessage());
                long durationMsOnError = System.currentTimeMillis() - startMs;
                updateResult(run, null, null, null, durationMsOnError, "ERROR", errorMsg);
                // 输出结构化完成日志（异常中断）
                log.info("ValidationRunner.runRowCount COMPLETE: taskId={} runId={} method={} "
                       + "windowType={} tablePairs={} checkedTables={} sourceRows=N/A targetRows=N/A diffRows=N/A durationMs={}",
                       run.getTaskId(), run.getId(), run.getMethod(),
                       run.getWindowType(), tablePairs.size(), checkedTableCount, durationMsOnError);
                break; // 跳过当前表对后续操作；ERROR 已写入，不保留部分累计
            }
        }

        // SQL 持久化：将收集的 SQL 信息脱敏、截断后写入 ValidationRun
        persistRowCountSql(run, sourceSqlBuilder, targetSqlBuilder, sourceWhereBuilder, targetWhereBuilder);

        // 如果已在 catch 中设置了 ERROR 状态并 break，则不再重复更新结果
        // 检查当前状态：如果已经是 ERROR，说明 catch 块已处理
        ValidationRun freshCheck = validationRunRepository.findById(run.getId()).orElse(run);
        if ("ERROR".equals(freshCheck.getStatus())) {
            return;
        }

        long diffRows = Math.abs(totalSrc - totalTgt);
        long durationMs = System.currentTimeMillis() - startMs;

        // P1 修复：status 基于逐表判定（anyTableDiff），而非累加总数。
        // 累加 diffRows 仅用于汇总展示；判定一致性必须"每张表都在容差内"。
        // 单表场景下 anyTableDiff 与"abs(totalSrc-totalTgt) <= tolerance"等价，多表场景避免跨表抵消。
        long effectiveTolerance = effectiveRowTolerance(totalSrc, absoluteTolerance, globalPct, taskPct);
        String status = anyTableDiff ? "DIFF" : "CONSISTENT";
        // 若逐表均一致但累加 diff 仍 > 0（极少见：单表自身在容差内但累加放大），保持 CONSISTENT，
        // 因为每张表都已通过单表容差判定。

        log.info("ValidationRunner complete: runId={} checkedTables={} src={} tgt={} aggDiff={} "
                + "tol(abs={},gPct{}%,tPct{}%,effAgg={}) anyTableDiff={} status={} {}ms{}",
                run.getId(), checkedTableCount, totalSrc, totalTgt, diffRows,
                absoluteTolerance, globalPct, taskPct, effectiveTolerance, anyTableDiff, status, durationMs,
                anyTableDiff ? " perTableDiff=[" + perTableDiffDetail + "]" : "");

        // 结构化完成日志（正常完成）。逐表差异明细已记录在上方 complete 日志的 perTableDiff 中，
        // 不写入 ValidationRun.errorMsg（errorMsg 语义是"执行错误"，DIFF 是正常校验结果，避免前端误判为出错）。
        log.info("ValidationRunner.runRowCount COMPLETE: taskId={} runId={} method={} "
               + "windowType={} tablePairs={} checkedTables={} sourceRows={} targetRows={} diffRows={} status={} durationMs={}{}",
               run.getTaskId(), run.getId(), run.getMethod(),
               run.getWindowType(), tablePairs.size(), checkedTableCount, totalSrc, totalTgt, diffRows, status, durationMs,
               anyTableDiff ? " perTableDiff=[" + perTableDiffDetail + "]" : "");

        updateResult(run, totalSrc, totalTgt, diffRows, durationMs, status);

        // spec 069：检测目标端 _etl_job_id NULL 历史行，给非阻塞口径告警（不改变 status）
        applyScopeWarning(run, detectScopeNullWarning(run, task, tablePairs));
    }

    /**
     * 计算行数校验的有效容差（绝对行数 / 任务百分比 / 全局百分比三者取 max）。
     * 百分比容差基于传入的基准行数（单表判定时传单表 srcCnt，汇总时传 totalSrc）。
     *
     * @param baseRows  百分比容差的基准行数（通常是源端行数）
     */
    static long effectiveRowTolerance(long baseRows, long absoluteTolerance, double globalPct, double taskPct) {
        long pctAllowedGlobal = globalPct > 0 && baseRows > 0
                ? (long) Math.ceil(baseRows * globalPct / 100.0)
                : 0L;
        long pctAllowedTask = taskPct > 0 && baseRows > 0
                ? (long) Math.ceil(baseRows * taskPct / 100.0)
                : 0L;
        return Math.max(absoluteTolerance, Math.max(pctAllowedGlobal, pctAllowedTask));
    }

    /**
     * 将 runRowCount 收集的 SQL 信息脱敏、截断后持久化到 ValidationRun 记录。
     */
    private void persistRowCountSql(ValidationRun run,
                                    StringBuilder sourceSqlBuilder,
                                    StringBuilder targetSqlBuilder,
                                    StringBuilder sourceWhereBuilder,
                                    StringBuilder targetWhereBuilder) {
        try {
            String rawSourceSql = sourceSqlBuilder.toString();
            String rawTargetSql = targetSqlBuilder.toString();
            String rawSourceWhere = sourceWhereBuilder.toString();
            String rawTargetWhere = targetWhereBuilder.toString();
            // 1. 脱敏
            rawSourceSql = ValidationJdbcHelper.sanitizeSqlForPersistence(rawSourceSql);
            rawTargetSql = ValidationJdbcHelper.sanitizeSqlForPersistence(rawTargetSql);
            rawSourceWhere = ValidationJdbcHelper.sanitizeSqlForPersistence(rawSourceWhere);
            rawTargetWhere = ValidationJdbcHelper.sanitizeSqlForPersistence(rawTargetWhere);
            // 2. 截断
            run.setSourceSql(ValidationJdbcHelper.truncateSql(rawSourceSql, 10000));
            run.setTargetSql(ValidationJdbcHelper.truncateSql(rawTargetSql, 10000));
            run.setSourceWhere(ValidationJdbcHelper.truncateSql(rawSourceWhere, 10000));
            run.setTargetWhere(ValidationJdbcHelper.truncateSql(rawTargetWhere, 10000));
            validationRunRepository.save(run);
        } catch (Exception e) {
            log.warn("persistRowCountSql failed: runId={}: {}", run.getId(), e.getMessage());
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private record TablePair(String sourceTable, String targetTable) {}

    private List<TablePair> resolveTablePairs(ValidationRun run, TaskValidationConfig config, SyncTask task) {
        List<String> scopedTables = parseConfiguredTables(config);
        if (!scopedTables.isEmpty()) {
            return scopedTables.stream()
                    .map(targetTable -> {
                        String sourceTable = targetTableResolver.resolveSourceForTarget(task, targetTable);
                        return new TablePair(sourceTable, targetTableResolver.resolve(task, sourceTable));
                    })
                    .toList();
        }

        if (task.getViewNames() != null && !task.getViewNames().isEmpty()) {
            return task.getViewNames().stream()
                    .map(sourceTable -> new TablePair(sourceTable, targetTableResolver.resolve(task, sourceTable)))
                    .toList();
        }
        return List.of();
    }

    private List<String> parseConfiguredTables(TaskValidationConfig config) {
        if (config != null && config.getTargetTables() != null && !config.getTargetTables().isBlank()) {
            return Arrays.stream(config.getTargetTables().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        return List.of();
    }

    private String resolveSourceSchema(SyncTask task, SourceDataSource source) {
        return sourceTableResolver.resolveSchemaRequired(task, source);
    }

    String buildSourceWhere(ValidationRun run, SyncTask task, String dialect, String sourceTable) {
        return validationWhereBuilder.buildSourceWhere(validationWindow(run, task), task, dialect, sourceTable);
    }

    String buildSourceWhere(WatermarkService.WindowContext window, SyncTask task, String dialect, String sourceTable) {
        return validationWhereBuilder.buildSourceWhere(window, task, dialect, sourceTable);
    }

    String buildSourceWhere(ValidationRun run, SyncTask task, String dialect, String sourceTable,
                            List<String> sourceColumns) {
        return validationWhereBuilder.buildSourceWhere(validationWindow(run, task), task, dialect, sourceTable, sourceColumns);
    }

    String buildSourceWhere(WatermarkService.WindowContext window, SyncTask task, String dialect, String sourceTable,
                            List<String> sourceColumns) {
        return validationWhereBuilder.buildSourceWhere(window, task, dialect, sourceTable, sourceColumns);
    }

    String buildCustomSqlCountSql(ValidationRun run, SyncTask task, String dialect) {
        return validationWhereBuilder.buildCustomSqlCountSql(validationWindow(run, task), task, dialect);
    }

    String buildCustomSqlCountSql(WatermarkService.WindowContext window, SyncTask task, String dialect) {
        return validationWhereBuilder.buildCustomSqlCountSql(window, task, dialect);
    }

    String buildCustomSqlCountSql(WatermarkService.WindowContext window, SyncTask task, String dialect,
                                  List<String> sourceColumns) {
        return validationWhereBuilder.buildCustomSqlCountSql(window, task, dialect, sourceColumns);
    }

    private WatermarkService.WindowContext validationWindow(ValidationRun run, SyncTask task) {
        if (isFullWindowType(run)) {
            return new WatermarkService.WindowContext("FULL", null, null, null, null);
        }
        if (isIdRangeIncrementalTask(task)) {
            WatermarkService.WindowContext idWindow = idRangeWindowOrNull(run);
            if (idWindow == null) {
                idWindow = executionWindowOrNull(run);
            }
            if (idWindow == null) {
                throw new IllegalStateException(
                        "ID_RANGE 校验缺少 windowStartId/windowEndId，无法与 execution window 对齐");
            }
            return idWindow;
        }
        if (run == null || (run.getWindowStart() == null && run.getWindowEnd() == null)) {
            return new WatermarkService.WindowContext("FULL", null, null, null, null);
        }
        return new WatermarkService.WindowContext(
                "INCREMENT",
                run.getWindowStart(),
                run.getWindowEnd(),
                null,
                null
        );
    }

    private WatermarkService.WindowContext idRangeWindowOrNull(ValidationRun run) {
        if (run == null || isFullWindowType(run) || run.getWindowEndId() == null) {
            return null;
        }
        return new WatermarkService.WindowContext(
                "INCREMENT",
                null,
                null,
                run.getWindowStartId(),
                run.getWindowEndId()
        );
    }

    private WatermarkService.WindowContext checksumWindowOrNull(ValidationRun run) {
        if (run != null && "FULL".equalsIgnoreCase(run.getWindowType())) {
            return null;
        }
        if (run != null && "FULL_THEN_INCREMENT".equalsIgnoreCase(run.getWindowType())) {
            return new WatermarkService.WindowContext(
                    "FULL_THEN_INCREMENT",
                    run.getWindowStart(),
                    run.getWindowEnd(),
                    run.getWindowStartId(),
                    run.getWindowEndId()
            );
        }
        // 优先使用 ValidationRun 自身记录的窗口（含回看窗口调整后的值）
        if (run != null && run.getWindowStart() != null) {
            return new WatermarkService.WindowContext(
                    "INCREMENT",
                    run.getWindowStart(),
                    run.getWindowEnd(),
                    run.getWindowStartId(),
                    run.getWindowEndId()
            );
        }
        WatermarkService.WindowContext idWindow = idRangeWindowOrNull(run);
        if (idWindow != null) {
            return idWindow;
        }
        WatermarkService.WindowContext executionWindow = executionWindowOrNull(run);
        if (executionWindow != null) {
            return executionWindow;
        }
        return null;
    }

    private WatermarkService.WindowContext executionWindowOrNull(ValidationRun run) {
        if (run == null || run.getExecutionId() == null) {
            return null;
        }
        TaskExecution execution = executionRepo.findById(run.getExecutionId()).orElse(null);
        if (execution == null) {
            return null;
        }
        if (run.getTaskId() != null && execution.getTaskId() != null
                && !run.getTaskId().equals(execution.getTaskId())) {
            throw new IllegalStateException(
                    "validation_run.executionId 不属于当前校验任务，executionId=" + run.getExecutionId()
                            + ", runId=" + run.getId()
                            + ", taskId=" + run.getTaskId()
                            + ", execution.taskId=" + execution.getTaskId());
        }
        if ("FULL_THEN_INCREMENT".equalsIgnoreCase(execution.getWindowType())) {
            return new WatermarkService.WindowContext(
                    "FULL_THEN_INCREMENT",
                    execution.getWindowStart(),
                    execution.getWindowEnd(),
                    execution.getWindowStartId(),
                    execution.getWindowEndId()
            );
        }
        if (execution.getWindowEndId() == null
                && execution.getWindowStart() == null
                && execution.getWindowEnd() == null) {
            return null;
        }
        return new WatermarkService.WindowContext(
                "INCREMENT",
                execution.getWindowStart(),
                execution.getWindowEnd(),
                execution.getWindowStartId(),
                execution.getWindowEndId()
        );
    }

    private boolean isFullWindowType(ValidationRun run) {
        if (run == null || run.getWindowType() == null) {
            return false;
        }
        return "FULL".equalsIgnoreCase(run.getWindowType())
                || "FULL_THEN_INCREMENT".equalsIgnoreCase(run.getWindowType());
    }

    private boolean isFullScope(WatermarkService.WindowContext window) {
        return window == null || "FULL".equals(window.windowType())
                || (window.windowStart() == null && window.windowEnd() == null
                    && window.windowStartId() == null && window.windowEndId() == null);
    }

    private boolean isIdRangeIncrementalTask(SyncTask task) {
        return task != null
                && "INCREMENTAL".equalsIgnoreCase(task.getDataScope())
                && "ID_RANGE".equalsIgnoreCase(task.getIncrementMode());
    }

    // ── spec 069：存量 _etl_job_id NULL 历史行口径告警 ──────────────────────────

    /**
     * spec 069 §6.3 / §7.1 / 验收点4：检测目标端是否存在 {@code _etl_job_id IS NULL} 的历史行。
     * <p>
     * 目标表补 {@code _etl_job_id} 列后，多机构上线前写入的历史旧行该列为 NULL，
     * 任务范围过滤 {@code `_etl_job_id` = {taskId}} 会漏掉它们，校验可能假报「目标少行」。
     * 检测到 NULL 行数 &gt; 0 时返回非阻塞口径警告文案（写入 {@code ValidationRun.scopeWarning}），
     * 引导运维清空重采；无 NULL 行或过滤未生效（_etl_job_id 未启用 / 历史任务）时返回 null。
     * <p>
     * 探测失败（连接异常等）不抛出，仅记日志返回 null——口径告警是辅助提示，不应阻断校验主流程。
     *
     * @return 口径警告文案，或 null（无需告警）
     */
    private String detectScopeNullWarning(ValidationRun run, SyncTask task, List<TablePair> tablePairs) {
        // 仅当任务范围过滤实际生效时才探测（过滤未生效则不存在「被漏算」问题）
        if (!validationWhereBuilder.isTenantScopeFilterActive(task)) {
            return null;
        }
        if (tablePairs == null || tablePairs.isEmpty()) {
            return null;
        }
        // CUSTOM_SQL 任务目标端仍是 Doris 表，同样适用；此处按目标表逐表探测
        try {
            String targetJdbcUrl = validationJdbcHelper.buildDorisJdbcUrl(task);
            String targetUser = "root";
            String targetPass = "";
            String tgtDb = null;
            if (task.getTargetDataSourceId() != null) {
                var tds = targetDsRepo.findById(task.getTargetDataSourceId()).orElse(null);
                if (tds != null) {
                    targetUser = tds.getUsername();
                    targetPass = aesUtil.decrypt(tds.getPasswordEnc());
                    tgtDb = tds.getDbName();
                }
            }
            long totalNull = 0;
            StringBuilder perTable = new StringBuilder();
            for (TablePair pair : tablePairs) {
                String tgtQualified = dialectQuoteHelper.qualifyTable("DORIS", tgtDb, pair.targetTable());
                long nullRows;
                try {
                    nullRows = countRows(targetJdbcUrl, targetUser, targetPass, tgtQualified,
                            ValidationWhereBuilder.ETL_JOB_ID_NULL_PREDICATE, "目标端_etl_job_id_NULL探测");
                } catch (Exception e) {
                    // 单表探测失败不阻断（例如目标表尚未建好）：记日志后跳过该表
                    log.warn("ValidationRunner: _etl_job_id NULL 探测失败 runId={} table={}: {}",
                            run.getId(), pair.targetTable(), e.getMessage());
                    continue;
                }
                if (nullRows > 0) {
                    totalNull += nullRows;
                    if (perTable.length() > 0) perTable.append(", ");
                    perTable.append(pair.targetTable()).append("=").append(nullRows);
                }
            }
            if (totalNull > 0) {
                String warning = ("目标表存在 _etl_job_id 为空的历史行（共 %d 行：%s），"
                        + "按任务范围过滤会漏算这些行，校验结果可能偏少。建议清空目标表重采。")
                        .formatted(totalNull, perTable);
                log.warn("ValidationRunner: runId={} 口径告警 - {}", run.getId(), warning);
                return warning;
            }
            return null;
        } catch (Exception e) {
            log.warn("ValidationRunner: _etl_job_id NULL 口径探测整体失败 runId={}: {}",
                    run.getId(), e.getMessage());
            return null;
        }
    }

    /** spec 069：把口径警告写回 ValidationRun.scopeWarning（非阻塞，失败不影响校验结果）。 */
    private void applyScopeWarning(ValidationRun run, String warning) {
        if (warning == null || warning.isBlank()) {
            return;
        }
        try {
            ValidationRun fresh = validationRunRepository.findById(run.getId()).orElse(null);
            if (fresh == null) return;
            String capped = warning.length() > 2000 ? warning.substring(0, 2000) : warning;
            fresh.setScopeWarning(capped);
            fresh.setUpdatedAt(LocalDateTime.now());
            validationRunRepository.save(fresh);
        } catch (Exception e) {
            log.warn("applyScopeWarning failed: runId={}: {}", run.getId(), e.getMessage());
        }
    }

    String buildRowCountSourceSql(
            SyncTask task,
            SourceDataSource source,
            SourceTableResolver.SourceRelation sourceRelation,
            String whereClause,
            List<SourceDataSourceService.ColumnInfo> sourceColumns) {
        return buildRowCountSourceSql(task, source, sourceRelation, whereClause, sourceColumns,
                false, List.of());
    }

    String buildRowCountSourceSql(
            SyncTask task,
            SourceDataSource source,
            SourceTableResolver.SourceRelation sourceRelation,
            String whereClause,
            List<SourceDataSourceService.ColumnInfo> sourceColumns,
            boolean distinctKeys,
            List<String> keyColumns) {
        MedicalRowCountOptions medical = medicalRowCountOptions(task);
        if (medical.datasetCode() != null) {
            if (medical.validSourceQuery() != null) {
                String validSourceQuery = stripTrailingSemicolon(medical.validSourceQuery());
                if (distinctKeys) {
                    List<String> projectedKeys = task.getUpsertKeys() == null
                            ? List.of()
                            : task.getUpsertKeys().stream()
                                    .filter(key -> key != null && !key.isBlank())
                                    .map(key -> key.trim().toLowerCase(Locale.ROOT))
                                    .distinct()
                                    .toList();
                    if (projectedKeys.isEmpty()) {
                        throw new IllegalArgumentException("UPSERT gate 缺少有效 upsertKeys");
                    }
                    String keyProjection = projectedKeys.stream()
                            .map(key -> dialectQuoteHelper.quoteColumn(sourceRelation.dialect(), key))
                            .collect(java.util.stream.Collectors.joining(", "));
                    return "SELECT COUNT(*) FROM (SELECT DISTINCT " + keyProjection
                            + " FROM (" + validSourceQuery + ") dfetl_valid_source_rows) "
                            + "dfetl_valid_source_count";
                }
                return "SELECT COUNT(*) FROM (" + validSourceQuery + ") dfetl_valid_source_count";
            }
            if (distinctKeys) {
                return buildDistinctKeyCountSql(sourceRelation, sourceRelation.dialect(),
                        whereClause, keyColumns, sourceColumns);
            }
            // 行数校验不需要执行完整的 contract-driven 投影；否则大视图 COUNT 会退化为
            // 全量类型转换，导致 AUTO_COUNT/ValidationRunner 受 query_timeout 影响。
            String qualified = dialectQuoteHelper.qualifyTable(
                    sourceRelation.dialect(), sourceRelation.schema(), sourceRelation.table());
            return "SELECT COUNT(*) FROM " + qualified
                    + (whereClause == null || whereClause.isBlank() ? "" : " WHERE " + whereClause);
        }
        String qualified = dialectQuoteHelper.qualifyTable(
                sourceRelation.dialect(), sourceRelation.schema(), sourceRelation.table());
        if (distinctKeys) {
            return buildDistinctKeyCountSql(sourceRelation, sourceRelation.dialect(),
                    whereClause, keyColumns, sourceColumns);
        }
        return "SELECT COUNT(*) FROM " + qualified
                + (whereClause == null || whereClause.isBlank() ? "" : " WHERE " + whereClause);
    }

    private long countRows(String jdbcUrl, String user, String password, String qualifiedTable,
                           String whereClause, String label) throws Exception {
        return countRows(jdbcUrl, user, password, qualifiedTable, whereClause, label,
                false, List.of());
    }

    private long countRows(String jdbcUrl, String user, String password, String qualifiedTable,
                           String whereClause, String label, boolean distinctKeys,
                           List<String> keyColumns) throws Exception {
        // qualifiedTable 已由 qualifyTable() 按方言 quote 完成，直接拼入 SQL
        String sql;
        if (distinctKeys) {
            String dialect = "DORIS";
            String keyProjection = keyColumns.stream()
                    .map(key -> dialectQuoteHelper.quoteColumn(dialect, key))
                    .collect(java.util.stream.Collectors.joining(", "));
            if (keyProjection.isBlank()) {
                throw new IllegalArgumentException("UPSERT gate 缺少有效 upsertKeys");
            }
            sql = "SELECT COUNT(*) FROM (SELECT DISTINCT " + keyProjection + " FROM "
                    + qualifiedTable
                    + (whereClause == null || whereClause.isEmpty() ? "" : " WHERE " + whereClause)
                    + ") dfetl_gate_keys";
        } else {
            sql = "SELECT COUNT(*) FROM " + qualifiedTable;
            if (whereClause != null && !whereClause.isEmpty()) {
                sql += " WHERE " + whereClause;
            }
        }
        return countRowsSql(jdbcUrl, user, password, sql, label);
    }

    private String buildDistinctKeyCountSql(SourceTableResolver.SourceRelation relation,
                                             String dialect,
                                             String whereClause,
                                             List<String> keyColumns,
                                             List<SourceDataSourceService.ColumnInfo> sourceColumns) {
        List<String> resolvedKeys = RequiredColumnResolver.resolveUnique(
                keyColumns,
                sourceColumns.stream().map(SourceDataSourceService.ColumnInfo::columnName).toList(),
                "UPSERT gate");
        String projection = resolvedKeys.stream()
                .map(key -> dialectQuoteHelper.quoteColumn(dialect, key))
                .collect(java.util.stream.Collectors.joining(", "));
        if (projection.isBlank()) {
            throw new IllegalArgumentException("UPSERT gate 缺少源端可解析的 upsertKeys");
        }
        String qualified = dialectQuoteHelper.qualifyTable(
                relation.dialect(), relation.schema(), relation.table());
        return "SELECT COUNT(*) FROM (SELECT DISTINCT " + projection + " FROM " + qualified
                + (whereClause == null || whereClause.isBlank() ? "" : " WHERE " + whereClause)
                + ") dfetl_gate_keys";
    }

    boolean isUpsertKeyAwareRowCount(ValidationRun run, SyncTask task) {
        return run != null
                && "ROW_COUNT".equals(normalizeMethod(run.getMethod()))
                && task != null
                && "UPSERT".equalsIgnoreCase(task.getSyncMode())
                && task.getUpsertKeys() != null
                && task.getUpsertKeys().stream().anyMatch(key -> key != null && !key.isBlank());
    }

    private List<String> resolveSourceKeyColumns(
            SyncTask task, List<SourceDataSourceService.ColumnInfo> sourceColumns) {
        return RequiredColumnResolver.resolveUnique(
                task.getUpsertKeys(),
                sourceColumns.stream().map(SourceDataSourceService.ColumnInfo::columnName).toList(),
                "UPSERT gate");
    }

    private long countRowsSql(String jdbcUrl, String user, String password, String sql, String label) throws Exception {
        // 通过连接池获取连接（多表大任务的 2N 次连接降为池化复用，见 ETL_RISK_REGISTER「数据源连接池统一」）
        try (Connection conn = connectionPoolManager.getConnection(jdbcUrl, user, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (Exception e) {
            log.error("countRows failed: label={} sql={} url={}", label, sql, ValidationJdbcHelper.sanitizeJdbcUrlForLog(jdbcUrl), e);
            throw e;   // 让调用方感知失败，避免 -1 被累加成错误行数
        }
    }

    /**
     * 源端普通表/视图 COUNT 使用连接池时也必须应用数据源 query_timeout。
     * 不能让 ValidationRunner 的 COUNT 路径无限等待，而 AUTO_COUNT 路径却在同一 SQL
     * 上按 datasource timeout 失败，造成两条校验链路语义不一致。
     */
    private long countRowsSqlWithTimeout(String jdbcUrl, String user, String password,
                                         String sql, String label, SourceDataSource sourceDs) throws Exception {
        try (Connection conn = connectionPoolManager.getConnection(jdbcUrl, user, password);
             Statement stmt = conn.createStatement()) {
            SourceDataSourceService.applyQueryTimeout(stmt, sourceDs);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            log.error("countRows failed: label={} sql={} url={}", label, sql,
                    ValidationJdbcHelper.sanitizeJdbcUrlForLog(jdbcUrl), e);
            throw e;
        }
    }

    private long countRowsSql(String jdbcUrl, String user, String password, String sql, String label,
                              SourceDataSource customSqlDataSource) throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            SourceDataSourceService.prepareCustomSqlConnection(conn);
            try (Statement stmt = conn.createStatement()) {
                SourceDataSourceService.applyQueryTimeout(stmt, customSqlDataSource);
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        } catch (Exception e) {
            log.error("countRows failed: label={} sql={} url={}", label, sql, ValidationJdbcHelper.sanitizeJdbcUrlForLog(jdbcUrl), e);
            throw e;
        }
    }

    private boolean isCustomSql(SyncTask task) {
        return task != null && "CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode());
    }

    private MedicalRowCountOptions medicalRowCountOptions(SyncTask task) {
        String dc = task == null ? null : task.getDataCharacteristics();
        if (dc == null || dc.isBlank()) {
            return MedicalRowCountOptions.empty();
        }
        try {
            Map<String, Object> values = OBJECT_MAPPER.readValue(dc, new TypeReference<Map<String, Object>>() {});
            Object mode = values.get("medicalMappingMode");
            boolean contractDriven = mode != null && "CONTRACT_DRIVEN".equalsIgnoreCase(mode.toString());
            if (!contractDriven) {
                return MedicalRowCountOptions.empty();
            }
            Object datasetCode = values.get("matchedDatasetCode");
            if (datasetCode == null || datasetCode.toString().isBlank()) {
                throw new IllegalStateException("医共体 contract-driven 任务缺少 matchedDatasetCode");
            }
            Object compatibilityMode = values.get("compatibilityMode");
            Object validSourceQuery = values.get("medicalValidSourceQuery");
            return new MedicalRowCountOptions(
                    datasetCode.toString().trim().toUpperCase(Locale.ROOT),
                    compatibilityMode == null ? null : compatibilityMode.toString().trim(),
                    parseStringMap(values.get("fieldMapping")),
                    validSourceQuery == null || validSourceQuery.toString().isBlank()
                            ? null
                            : validSourceQuery.toString().trim());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (dc.contains("CONTRACT_DRIVEN")) {
                throw new IllegalStateException("医共体 contract-driven 任务 dataCharacteristics 不是合法 JSON: "
                        + e.getMessage(), e);
            }
            return MedicalRowCountOptions.empty();
        }
    }

    private static Map<String, String> parseStringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().toString();
            String val = entry.getValue().toString();
            if (!key.isBlank() && !val.isBlank()) {
                mapped.put(key, val);
            }
        }
        return mapped;
    }

    private void applyExecutionMedicalValidSourceQuery(ValidationRun run, SyncTask task) {
        if (medicalRowCountOptions(task).validSourceQuery() != null) {
            return;
        }
        Long executionId = run == null ? null : run.getExecutionId();
        if (executionId == null && run != null) {
            executionId = run.getLegacyExecId();
        }
        if (executionId == null) {
            return;
        }
        TaskExecution execution = executionRepo.findById(executionId).orElse(null);
        String validSourceQuery = execution == null ? null : execution.getMedicalValidSourceQuery();
        if (validSourceQuery == null || validSourceQuery.isBlank()) {
            return;
        }
        task.setDataCharacteristics(withMedicalValidSourceQuery(task.getDataCharacteristics(), validSourceQuery));
    }

    private static String withMedicalValidSourceQuery(String dataCharacteristics, String validSourceQuery) {
        try {
            Map<String, Object> values = dataCharacteristics == null || dataCharacteristics.isBlank()
                    ? new LinkedHashMap<>()
                    : OBJECT_MAPPER.readValue(dataCharacteristics, new TypeReference<LinkedHashMap<String, Object>>() {});
            values.put("medicalValidSourceQuery", validSourceQuery);
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("医共体问题行分流 SQL 写入 ValidationRunner dataCharacteristics 失败: "
                    + e.getMessage(), e);
        }
    }

    private static String stripTrailingSemicolon(String sql) {
        String stripped = sql == null ? "" : sql.trim();
        while (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1).trim();
        }
        return stripped;
    }

    private record MedicalRowCountOptions(
            String datasetCode,
            String compatibilityMode,
            Map<String, String> fieldMapping,
            String validSourceQuery) {

        static MedicalRowCountOptions empty() {
            return new MedicalRowCountOptions(null, null, Map.of(), null);
        }
    }

    /**
     * 当 ValidationRun 上挂载了 windowStart（来自 spec 048 显式 WINDOW 或 spec 022 自动触发增量）时，
     * 按 task.incrementalField 生成方言相关的 WHERE 子句。无窗口时返回空串（=全表 COUNT）。
     */
    String buildTargetWindowWhere(WatermarkService.WindowContext window, SyncTask task, String sourceTable, String dialect) {
        return validationWhereBuilder.buildTargetWindowWhere(window, task, sourceTable, dialect);
    }

    /**
     * 将 schema + table 按方言 quote 并拼接成 schema-qualified 的合法 SQL 标识符。
     * <p>必须保持与同步/Checksum 路径一致的表定位逻辑，避免行数校验查错表（缺省 schema、ORA-00942、search_path 漂移等）。
     */
    private String qualifyTable(String dialect, String schema, String table) {
        if (table == null || !table.matches("[\\w$#.]+")) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }
        if (schema != null && !schema.isBlank() && !schema.matches("[\\w$#]+")) {
            throw new IllegalArgumentException("Invalid schema name: " + schema);
        }
        // table 已可能含点（如 "schema.tbl"），优先尊重
        if (table.contains(".")) return table;
        return dialectQuoteHelper.qualifyTable(dialect, schema, table);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   spec validation-table-consolidation · Step 10
    //   updateResult：写入 validation_run（双写已移除）
    // ══════════════════════════════════════════════════════════════════════════

    private void updateResult(ValidationRun run, Long srcRows, Long tgtRows,
                              Long diffRows, Long durationMs, String status) {
        updateResult(run, srcRows, tgtRows, diffRows, durationMs, status, null);
    }

    private static final java.util.concurrent.ConcurrentHashMap<Long, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    private void updateResult(ValidationRun run, Long srcRows, Long tgtRows,
                              Long diffRows, Long durationMs, String status, String errorMsg) {
        // 按 runId 粒度加锁，避免全局串行化
        Object lock = LOCKS.computeIfAbsent(run.getId(), k -> new Object());
        synchronized (lock) {
            try {
                // 重新加载避免 detached entity
                ValidationRun fresh = validationRunRepository.findById(run.getId()).orElse(run);
                fresh.setSourceRows(srcRows);
                fresh.setTargetRows(tgtRows);
                fresh.setDiffRows(diffRows);
                fresh.setDurationMs(durationMs);
                fresh.setStatus(status);
                fresh.setLastRunAt(Instant.now());
                fresh.setUpdatedAt(LocalDateTime.now());
                if (errorMsg != null) {
                    fresh.setErrorMsg(errorMsg.length() > 2000 ? errorMsg.substring(0, 2000) : errorMsg);
                } else if ("CONSISTENT".equals(status) || "DIFF".equals(status)) {
                    // 成功完成时清空旧的错误信息
                    fresh.setErrorMsg(null);
                }
                validationRunRepository.save(fresh);
                triggerValidationResultAlert(fresh);
            } catch (Exception e) {
                log.error("updateResult failed: runId={}", run.getId(), e);
            } finally {
                LOCKS.remove(run.getId());
            }
        }
    }

    private void triggerValidationResultAlert(ValidationRun run) {
        if (alertEvaluatorService == null || run == null || run.getTaskId() == null || run.getExecutionId() == null) {
            return;
        }
        if (!isValidationTerminalStatus(run.getStatus())) {
            return;
        }
        try {
            SyncTask task = syncTaskRepo.findById(run.getTaskId()).orElse(null);
            TaskExecution exec = executionRepo.findById(run.getExecutionId()).orElse(null);
            if (task == null || exec == null) {
                return;
            }
            alertEvaluatorService.evaluateValidationResult(task, exec);
        } catch (Exception e) {
            log.warn("ValidationRunner: validation_result alert evaluation failed runId={}: {}",
                    run.getId(), e.getMessage());
        }
    }

    private boolean isValidationTerminalStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case "CONSISTENT", "DIFF", "ERROR", "FAILED", "SKIPPED", "CANCELLED" -> true;
            default -> false;
        };
    }

    /** 把异常格式化为可读的错误描述（exception 类名 + message + 根因 cause）。 */
    private static String formatError(Throwable e) {
        if (e == null) return "unknown error";
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName()).append(": ");
        sb.append(e.getMessage() == null ? "(no message)" : e.getMessage());
        Throwable cause = e.getCause();
        int depth = 0;
        while (cause != null && depth++ < 3) {
            sb.append("\n  caused by ").append(cause.getClass().getSimpleName())
              .append(": ").append(cause.getMessage() == null ? "(no message)" : cause.getMessage());
            cause = cause.getCause();
        }
        return sb.toString();
    }

    /**
     * Spec 063：Doris MERGE 软删除时，目标侧 WHERE 追加 {@code `__doris_delete_sign__` = 0}，
     * 排除 MOW 模式下标记为已删除但物理仍存在的行，避免行数/checksum 不一致。
     *
     * @deprecated 使用 {@link ValidationWhereBuilder#appendDorisDeleteSignFilter(SyncTask, String)} 替代
     */
    @Deprecated
    static String appendDorisDeleteSignFilter(SyncTask task, String tgtWhere) {
        return ValidationWhereBuilder.appendDorisDeleteSignFilter(task, tgtWhere);
    }

    /**
     * spec validation-workbench-redesign · Task P1-5.2：把 ValidationTask.triggerType 规范化为
     * ValidationRun.trigger_type 合法枚举之一。NULL/不识别值降级为 MANUAL。
     */
    private static String normalizeTriggerType(String triggerType) {
        if (triggerType == null || triggerType.isBlank()) return "MANUAL";
        String upper = triggerType.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (upper) {
            case "AUTO", "AUTO_COUNT", "MANUAL", "MANUAL_FULL", "DRIFT", "GATE", "MANUAL_REPAIR_RECHECK" -> upper;
            default -> "MANUAL";
        };
    }
}
