package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.entity.EtlVerifyDiff;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.engine.checksum.HashCodec;
import com.dfygt.dfetl.server.engine.checksum.ChecksumProperties;
import com.dfygt.dfetl.server.engine.checksum.RowNormalizer;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import com.dfygt.dfetl.server.service.validation.ValidationWhereBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Spec 050 — 增量窗口行级变更核查（Row-Level Change Audit）。
 *
 * <p>与 {@link ChecksumService} 分片 hash 不同：本服务按 <b>PK 集合</b> 逐行比对，
 * 能精确给出"哪几行缺失/不一致"，适合小窗口（万级行以内）的审计诊断。
 *
 * <p>执行流程：
 * <ol>
 *   <li>源端：{@code SELECT pkCols, rowHash FROM src WHERE incField >= start AND incField < end ORDER BY pk LIMIT maxRows}</li>
 *   <li>目标端：{@code SELECT pkCols, rowHash FROM tgt WHERE pk IN (srcPkList)}</li>
 *   <li>按 PK 对比 hash → 分类：SYNCED / MISSING / MISMATCH</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeAuditService {

    /** 单次审计最多扫描源端行数，防止大窗口打爆内存。 */
    private static final int MAX_ROWS = 50_000;

    /** IN 子句每批最大 PK 数（JDBC IN list 过长影响性能）。 */
    private static final int PK_BATCH_SIZE = 1_000;

    private final SyncTaskRepository syncTaskRepo;
    private final TargetDataSourceRepository targetDsRepo;
    private final SourceDataSourceService sourceDataSourceService;
    private final SourceDataSourceRepository sourceDataSourceRepository;
    private final WhereClauseBuilder whereClauseBuilder;
    private final ChecksumProperties props;
    private final AesUtil aesUtil;
    private final EtlVerifyDiffRepository diffRepo;
    private final ValidationRunService validationRunService;
    private final ValidationRunRepository validationRunRepository;
    private final com.dfygt.dfetl.server.common.JdbcConnectionPoolManager connectionPoolManager;

    // ── spec change-audit-resolver-alignment：与主校验链路同源 ──────────────
    /** Bug 1：复用 SnapshotDeleteService / ValidationRunner 的 schema 解析（含 Oracle username fallback）。 */
    private final SourceTableResolver sourceTableResolver;
    /** Bug 2：复用主校验链路的目标表名解析（targetTableMap JSON → 源表名 fallback）。 */
    private final TargetTableResolver targetTableResolver;
    /** Bug 3：复用 spec 069 任务范围过滤（`_etl_job_id` = task.id）。 */
    private final ValidationWhereBuilder validationWhereBuilder;
    private final TargetFieldResolver targetFieldResolver;
    private final DialectQuoteHelper dialectQuoteHelper;

    // ── 结果 records ─────────────────────────────────────────────────────────

    /**
     * 单行审计结果。
     *
     * @param pkValue  主键值（多列时用逗号拼接）
     * @param status   SYNCED | MISSING | MISMATCH
     * @param srcHash  源端 row hash（MISSING 时为空）
     * @param tgtHash  目标端 row hash（MISMATCH 时与 srcHash 不同）
     */
    public record AuditRow(String pkValue, String status, String srcHash, String tgtHash) {}

    /**
     * 整体汇总结果。
     *
     * @param execId           本次行级核查使用的 verify exec_id（写入 etl_verify_diff，前端钻取时使用）
     * @param sourceRows       源端窗口内总行数
     * @param syncedCount      已同步且一致行数
     * @param missingCount     目标端缺失行数
     * @param mismatchCount    目标端存在但 hash 不一致行数
     * @param truncated        源端超过 {@value MAX_ROWS} 行时为 true，结果仅代表样本
     * @param missingPkSample  最多 50 条缺失 PK（供快速排查）
     * @param mismatchPkSample 最多 50 条不一致 PK
     */
    public record AuditReport(
            long runId,
            long execId,
            long sourceRows,
            long syncedCount,
            long missingCount,
            long mismatchCount,
            boolean truncated,
            List<String> missingPkSample,
            List<String> mismatchPkSample
    ) {}

    // ── 主入口 ────────────────────────────────────────────────────────────────

    /**
     * 对给定任务的增量窗口 [windowStart, windowEnd) 执行行级变更核查。
     *
     * @param taskId      同步任务 ID
     * @param windowStart 窗口起点（含），不可为 null
     * @param windowEnd   窗口终点（不含），null 表示无上界
     */
    public AuditReport audit(Long taskId, Instant windowStart, Instant windowEnd) {
        if (windowStart == null) {
            throw new IllegalArgumentException("windowStart 不可为空");
        }

        // Spec 057：分配独立 execId，与 ChecksumService 窗口模式同样使用负值时间戳
        // 写入 etl_verify_diff 后，前端可通过 GET /sync-task/{taskId}/checksum/{execId}/diffs 拉取明细
        long execId = -System.currentTimeMillis();
        // spec validation-workbench-redesign · Task P1-5.2：ChangeAudit 默认是用户手动触发的「逐行核查」
        Long validationRunId = validationRunService
                .getOrCreate(taskId, execId, "ROW_AUDIT", "WINDOW", windowStart, windowEnd, "MANUAL")
                .getId();

        // ── run 状态回写：执行前置 RUNNING（任务级并发保护 + 工作台「执行中」可见）──
        // 修复 ETL_RISK_REGISTER 2026-05-27 P1：ChangeAudit 创建 run 后从不回写状态，
        // 导致逐行核查 run 永久停留在 PENDING，工作台无法表达执行中/一致/不一致/出错。
        long startMs = System.currentTimeMillis();
        markRunStatus(validationRunId, "RUNNING", null, null, null, null);

        try {
            AuditReport report = executeAudit(taskId, windowStart, windowEnd, execId, validationRunId);
            // 终态：missing + mismatch > 0 → DIFF，否则 CONSISTENT
            long diffRows = report.missingCount() + report.mismatchCount();
            String status = diffRows > 0 ? "DIFF" : "CONSISTENT";
            markRunStatus(validationRunId, status, report.sourceRows(), diffRows,
                    System.currentTimeMillis() - startMs, null);
            return report;
        } catch (RuntimeException ex) {
            // 任何失败（配置非法 / 源端或目标端读取失败）都回写 ERROR，保留错误信息便于排查
            markRunStatus(validationRunId, "ERROR", null, null,
                    System.currentTimeMillis() - startMs, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 行级核查核心逻辑（不含 run 创建与状态回写，由 {@link #audit} 包裹生命周期）。
     */
    private AuditReport executeAudit(Long taskId, Instant windowStart, Instant windowEnd,
                                     long execId, Long validationRunId) {
        SyncTask task = syncTaskRepo.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + taskId));
        if ("ID_RANGE".equalsIgnoreCase(task.getIncrementMode())) {
            throw new IllegalArgumentException(
                    "ID_RANGE 任务不能使用 ChangeAudit 手工时间窗口核查；请基于 TaskExecution 的 ID 窗口执行 CHECKSUM 续跑或复查");
        }

        SourceDataSource srcDs = sourceDataSourceRepository.findById(task.getSourceDataSourceId())
                .orElseThrow(() -> new NoSuchElementException(
                        "SourceDataSource not found: " + task.getSourceDataSourceId()));

        if (task.getViewNames() == null || task.getViewNames().isEmpty()) {
            throw new IllegalArgumentException("任务未配置 viewNames");
        }
        if (task.getViewNames().size() != 1) {
            throw new IllegalArgumentException("ChangeAudit v1 仅支持单表/单视图任务");
        }
        String srcTable = task.getViewNames().get(0);
        if (!whereClauseBuilder.isFieldNameSafe(srcTable)) {
            throw new IllegalArgumentException("非法源表名: " + srcTable);
        }
        SourceTableResolver.SourceRelation sourceRelation =
                sourceTableResolver.resolveRequired(task, srcDs, srcTable);
        String srcSchema = sourceRelation.schema();
        srcTable = sourceRelation.table();
        String srcDbType = sourceRelation.dialect();

        String incField = task.getIncrementalField();
        if (incField == null || incField.isBlank()) {
            throw new IllegalArgumentException("任务未配置 incrementalField，无法执行窗口行级核查");
        }
        if (!whereClauseBuilder.isFieldNameSafe(incField)) {
            throw new IllegalArgumentException("非法 incrementalField: " + incField);
        }

        // 2. 主键列：行级核查必须使用业务 upsertKeys；复合键当前 v1 明确拒绝，避免按第一列错行比对。
        String pkCol = resolvePrimaryKey(task);

        // 3. 列清单（过滤掉与 pkCol 同名的列，避免 SELECT 中出现重复列导致 ORA-00960）
        List<String> allSourceCols = sourceDataSourceService
                .listColumns(task.getSourceDataSourceId(), srcSchema, srcTable)
                .stream()
                .map(c -> c.columnName())
                .toList();
        List<String> srcCols = allSourceCols.stream()
                .filter(c -> !c.equalsIgnoreCase(pkCol))
                .toList();
        if (srcCols.isEmpty()) {
            throw new IllegalStateException("源表无可用列");
        }
        for (String c : srcCols) {
            if (!whereClauseBuilder.isFieldNameSafe(c)) {
                throw new IllegalArgumentException("非法列名: " + c);
            }
        }

        WatermarkService.WindowContext auditWindow =
                new WatermarkService.WindowContext("INCREMENT", windowStart, windowEnd, null, null);
        String sourceWhere = validationWhereBuilder.buildSourceWhere(
                auditWindow, task, srcDbType, srcTable, allSourceCols);

        TargetDataSource tgt = targetDsRepo.findById(task.getTargetDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("TargetDataSource not found"));
        // Bug 2：目标表名解析复用 TargetTableResolver（含 targetTableMap JSON 改名映射）。
        String tgtTable = targetTableResolver.resolve(task, srcTable);

        HashCodec codec = new HashCodec(props.defaultAlgo());
        RowNormalizer normalizer = new RowNormalizer();

        log.info("ChangeAudit.audit taskId={} incField={} window=[{},{})", taskId, incField, windowStart, windowEnd);

        // 4. Step1：从源端拉取窗口内行（pk → hash），最多 MAX_ROWS 行
        Map<String, String> srcMap;
        boolean truncated;
        try (Connection srcConn = sourceDataSourceService.openConnection(task.getSourceDataSourceId())) {
            srcMap = fetchRows(srcConn, srcSchema, srcTable, pkCol, srcCols,
                    sourceWhere, normalizer, codec, MAX_ROWS, srcDbType);
            truncated = srcMap.size() == MAX_ROWS;
        } catch (Exception e) {
            throw new RuntimeException("读取源端数据失败: " + e.getMessage(), e);
        }

        long sourceRows = srcMap.size();
        if (sourceRows == 0) {
            return new AuditReport(validationRunId, execId, 0, 0, 0, 0, false, List.of(), List.of());
        }

        // 5. Step2：在目标端按 PK 批量查找对应行（pk → hash）
        List<String> tgtCols = targetFieldResolver.resolveTargetColumnsSameNameOnly(
                task, srcTable, srcCols, "ChangeAudit 目标端字段回读");
        String tgtPkCol = targetFieldResolver.resolveTargetColumnSameNameOnly(
                task, srcTable, pkCol, "ChangeAudit 目标端 PK 回读");
        Map<String, String> tgtMap;
        try (Connection tgtConn = openDorisConn(tgt)) {
            // Bug 3：fetchRowsByPks 签名加 task，内部 SQL 通过 appendTenantScopeFilter 收口任务范围。
            tgtMap = fetchRowsByPks(tgtConn, tgt.getDbName(), tgtTable, tgtPkCol,
                    tgtCols, srcMap.keySet(), normalizer, codec, task);
        } catch (Exception e) {
            throw new RuntimeException("读取目标端数据失败: " + e.getMessage(), e);
        }

        // 6. 比对
        long synced = 0, missing = 0, mismatch = 0;
        List<String> missingPks = new ArrayList<>();
        List<String> mismatchPks = new ArrayList<>();
        List<EtlVerifyDiff> diffsToPersist = new ArrayList<>();

        for (Map.Entry<String, String> e : srcMap.entrySet()) {
            String pk = e.getKey();
            String srcHash = e.getValue();
            String tgtHash = tgtMap.get(pk);
            if (tgtHash == null) {
                missing++;
                if (missingPks.size() < 50) missingPks.add(pk);
                diffsToPersist.add(buildDiff(taskId, execId, validationRunId, pk,
                        EtlVerifyDiff.TYPE_ROW_AUDIT_MISSING, srcHash, null));
            } else if (!srcHash.equals(tgtHash)) {
                mismatch++;
                if (mismatchPks.size() < 50) mismatchPks.add(pk);
                diffsToPersist.add(buildDiff(taskId, execId, validationRunId, pk,
                        EtlVerifyDiff.TYPE_ROW_AUDIT_MISMATCH, srcHash, tgtHash));
            } else {
                synced++;
            }
        }

        // Spec 057：把不一致 PK 写入 etl_verify_diff，统一进入「校验工作台 → 不一致明细」表格
        if (!diffsToPersist.isEmpty()) {
            try {
                diffRepo.saveAll(diffsToPersist);
                log.info("ChangeAudit.audit taskId={} execId={} persisted {} diffs to etl_verify_diff",
                        taskId, execId, diffsToPersist.size());
            } catch (Exception ex) {
                // 持久化失败不阻塞返回结果，仅记录警告
                log.warn("ChangeAudit.audit taskId={} execId={} 写入 etl_verify_diff 失败: {}",
                        taskId, execId, ex.getMessage(), ex);
            }
        }

        log.info("ChangeAudit.audit taskId={} execId={} sourceRows={} synced={} missing={} mismatch={} truncated={}",
                taskId, execId, sourceRows, synced, missing, mismatch, truncated);

        return new AuditReport(validationRunId, execId, sourceRows, synced, missing, mismatch, truncated, missingPks, mismatchPks);
    }

    /**
     * 回写 ValidationRun 状态（RUNNING / CONSISTENT / DIFF / ERROR）。
     * <p>与 {@link ValidationRunner} 的回写语义一致：重新加载避免 detached entity，
     * 成功终态清空旧 errorMsg，ERROR 时截断错误信息至 2000 字符。
     * 回写失败不抛出，避免影响核查主结果返回。
     */
    private void markRunStatus(Long runId, String status, Long sourceRows, Long diffRows,
                               Long durationMs, String errorMsg) {
        try {
            ValidationRun run = validationRunRepository.findById(runId).orElse(null);
            if (run == null) {
                log.warn("ChangeAudit.markRunStatus runId={} not found, skip status={}", runId, status);
                return;
            }
            run.setStatus(status);
            run.setMethod("ROW_AUDIT");
            if (sourceRows != null) run.setSourceRows(sourceRows);
            if (diffRows != null) run.setDiffRows(diffRows);
            if (durationMs != null) run.setDurationMs(durationMs);
            run.setLastRunAt(Instant.now());
            run.setUpdatedAt(LocalDateTime.now());
            if (errorMsg != null) {
                run.setErrorMsg(errorMsg.length() > 2000 ? errorMsg.substring(0, 2000) : errorMsg);
            } else if ("CONSISTENT".equals(status) || "DIFF".equals(status)) {
                run.setErrorMsg(null);
            }
            validationRunRepository.save(run);
        } catch (Exception e) {
            log.warn("ChangeAudit.markRunStatus failed runId={} status={}: {}", runId, status, e.getMessage());
        }
    }

    /** 构造单行 EtlVerifyDiff（chunkNo=0 表示行级核查整窗口）。 */
    private EtlVerifyDiff buildDiff(Long taskId, long execId, Long validationRunId, String pkValue,
                                    String diffType, String srcHash, String tgtHash) {
        EtlVerifyDiff d = new EtlVerifyDiff();
        d.setTaskId(taskId);
        d.setExecId(execId);
        d.setValidationRunId(validationRunId);
        d.setChunkNo(0);
        d.setPkValue(pkValue);
        d.setDiffType(diffType);
        d.setSourceHash(srcHash);
        d.setTargetHash(tgtHash);
        d.setRepairStatus(EtlVerifyDiff.REPAIR_PENDING);
        return d;
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    /**
     * 从源端查询窗口内行，返回 pk → rowHash 映射。
     * SQL：SELECT pk, col1, col2, ... FROM schema.table WHERE <validationWhereBuilder sourceWhere> ORDER BY pk
     *       LIMIT maxRows（MySQL/PG）or FETCH FIRST maxRows ROWS ONLY（Oracle）
     *
     * <p>注意：cols 不应包含 pkCol，否则 Oracle 会报 ORA-00960（命名含糊）。
     */
    private Map<String, String> fetchRows(Connection conn, String schema, String table,
                                          String pkCol, List<String> cols,
                                          String sourceWhere,
                                          RowNormalizer normalizer, HashCodec codec,
                                          int maxRows, String dbType) throws Exception {
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(dialectQuoteHelper.quoteColumn(dbType, pkCol)).append(", ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(dialectQuoteHelper.quoteColumn(dbType, cols.get(i)));
        }
        sb.append(" FROM ").append(dialectQuoteHelper.qualifyTable(dbType, schema, table));
        if (sourceWhere != null && !sourceWhere.isBlank()) {
            sb.append(" WHERE ").append(sourceWhere);
        }
        sb.append(" ORDER BY ").append(dialectQuoteHelper.quoteColumn(dbType, pkCol));
        if ("ORACLE".equals(dbType)) {
            sb.append(" FETCH FIRST ").append(maxRows).append(" ROWS ONLY");
        } else {
            sb.append(" LIMIT ").append(maxRows);
        }

        Map<String, String> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    String pk = rs.getString(1);
                    List<Object> values = new ArrayList<>(colCount - 1);
                    for (int i = 2; i <= colCount; i++) {
                        values.add(rs.getObject(i));
                    }
                    String hash = codec.hash(normalizer.normalizeRow(values));
                    result.put(pk, hash);
                }
            }
        }
        return result;
    }

    /**
     * 从目标端按 PK 批量查找，返回 pk → rowHash 映射。
     * 分批执行 IN 查询，每批不超过 PK_BATCH_SIZE。
     *
     * <p>spec change-audit-resolver-alignment · Bug 3：通过 {@link ValidationWhereBuilder#appendTenantScopeFilter}
     * 在 IN 子句之外追加 {@code AND `_etl_job_id` = task.id}，与主校验链路同口径。
     * 当 {@code _etl_job_id} 系统字段未启用（spec 069 之前的历史任务）时退化为旧无过滤行为。
     */
    private Map<String, String> fetchRowsByPks(Connection conn, String dbName, String table,
                                               String pkCol, List<String> cols,
                                               java.util.Set<String> pks,
                                               RowNormalizer normalizer, HashCodec codec,
                                               SyncTask task) throws Exception {
        List<String> pkList = new ArrayList<>(pks);
        Map<String, String> result = new HashMap<>();

        for (int offset = 0; offset < pkList.size(); offset += PK_BATCH_SIZE) {
            List<String> batch = pkList.subList(offset, Math.min(offset + PK_BATCH_SIZE, pkList.size()));
            String placeholders = "?,".repeat(batch.size());
            placeholders = placeholders.substring(0, placeholders.length() - 1);

            // Bug 3：先构造 baseWhere（pkCol IN (...)），再走 appendTenantScopeFilter 收口
            // task.id 数值字面量直接拼到 SQL（与 spec 069 一致，BIGINT 无注入风险）。
            String quotedPkCol = dialectQuoteHelper.quoteColumn("DORIS", pkCol);
            String baseWhere = quotedPkCol + " IN (" + placeholders + ")";
            String finalWhere = ValidationWhereBuilder.appendDorisDeleteSignFilter(task, baseWhere);
            finalWhere = validationWhereBuilder.appendTenantScopeFilter(task, finalWhere);

            StringBuilder sb = new StringBuilder("SELECT ");
            sb.append(quotedPkCol).append(", ");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(dialectQuoteHelper.quoteColumn("DORIS", cols.get(i)));
            }
            sb.append(" FROM ")
                    .append(dialectQuoteHelper.quoteIdentifier("DORIS", dbName))
                    .append('.')
                    .append(dialectQuoteHelper.quoteIdentifier("DORIS", table));
            sb.append(" WHERE ").append(finalWhere);

            try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
                for (int i = 0; i < batch.size(); i++) {
                    ps.setString(i + 1, batch.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    while (rs.next()) {
                        String pk = rs.getString(1);
                        List<Object> values = new ArrayList<>(colCount - 1);
                        for (int i = 2; i <= colCount; i++) {
                            values.add(rs.getObject(i));
                        }
                        String hash = codec.hash(normalizer.normalizeRow(values));
                        result.put(pk, hash);
                    }
                }
            }
        }
        return result;
    }

    /** 解析主键列名（单列）：优先 upsertKeys；缺省时才退回 splitPk。 */
    private String resolvePrimaryKey(SyncTask task) {
        List<String> keys = task.getUpsertKeys();
        if (keys != null && !keys.isEmpty()) {
            if (keys.size() > 1) {
                throw new IllegalArgumentException("ChangeAudit v1 暂不支持复合主键，请使用 CHECKSUM/diff 链路校验复合主键任务");
            }
            String first = keys.get(0);
            if (!whereClauseBuilder.isFieldNameSafe(first)) {
                throw new IllegalArgumentException("非法列名: " + first);
            }
            return first;
        }
        String pk = task.getSplitPk();
        if (pk == null || pk.isBlank()) {
            throw new IllegalArgumentException("ChangeAudit 需要 upsertKeys 或 splitPk 配置主键列");
        }
        if (!whereClauseBuilder.isFieldNameSafe(pk)) {
            throw new IllegalArgumentException("非法 splitPk: " + pk);
        }
        return pk;
    }

    private Connection openDorisConn(TargetDataSource tgt) throws Exception {
        String url = "jdbc:mysql://" + tgt.getFeHost() + ":" + tgt.getFePort() + "/" + tgt.getDbName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        return connectionPoolManager.getConnection(url, tgt.getUsername(), aesUtil.decrypt(tgt.getPasswordEnc()));
    }
}
