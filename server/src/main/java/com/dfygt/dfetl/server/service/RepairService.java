package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.engine.checksum.RowNormalizer;
import com.dfygt.dfetl.server.engine.doris.DorisStreamLoadClient;
import com.dfygt.dfetl.server.engine.doris.StreamLoadResult;
import com.dfygt.dfetl.server.entity.EtlVerifyDiff;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalContractSnapshotCodec;
import com.dfygt.dfetl.server.medical.MedicalDatasetContractService;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.medical.source.MedicalSourceSelectBuilder;
import com.dfygt.dfetl.server.medical.source.MedicalSourceSelectPlan;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapterResolver;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.dfygt.dfetl.server.service.validation.ValidationWhereBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Spec 024：差异修复（Repair）引擎。
 *
 * <p>读取 {@code etl_verify_diff} 中 {@code repair_status='PENDING'} 的行，按 diff_type 分发：
 * <ul>
 *   <li>{@code INSERT_MISSING / UPDATE_DIFF}：源端按主键回查整行，Doris Stream Load APPEND（主键模型自动 UPSERT）</li>
 *   <li>{@code DELETE_MISSING}：默认 SKIPPED；显式 forceDelete=true 时 Stream Load merge_type=DELETE</li>
 * </ul>
 *
 * <p>v1 限制：单列主键、单表/单视图任务；行级失败原因不落表（整批同状态）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepairService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_ROWS = 5000;

    private final SyncTaskRepository syncTaskRepo;
    private final TargetDataSourceRepository targetDsRepo;
    private final EtlVerifyDiffRepository diffRepo;
    private final SourceDataSourceService sourceDataSourceService;
    private final SourceDataSourceRepository sourceDsRepo;
    private final WhereClauseBuilder whereClauseBuilder;
    private final DorisStreamLoadClient streamLoadClient;
    private final AesUtil aesUtil;
    private final TargetTableResolver targetTableResolver;
    private final SourceTableResolver sourceTableResolver;
    private final DialectQuoteHelper dialectQuoteHelper;
    private final EtlSystemFieldsService etlSystemFieldsService;
    private final ValidationWhereBuilder validationWhereBuilder;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    record RepairSourcePlan(
            String fromSql,
            List<String> pkCols,
            List<String> columns,
            String whereClause
    ) {}

    private record MedicalRepairOptions(
            String datasetCode,
            String compatibilityMode,
            Map<String, String> fieldMapping
    ) {}
    /**
     * spec validation-workbench-redesign · Task P1-12.1 / Requirement 8 (AC 1, 3)
     * Repair 异步 ROW_COUNT 复查（闭环 B）：写入新 validation_run 记录。
     */
    private final ValidationRunService validationRunService;
    private final MedicalDatasetContractService medicalContractService;
    private final MedicalSourceSelectBuilder medicalSourceSelectBuilder;
    private final SourceDialectAdapterResolver sourceDialectAdapterResolver;

    /**
     * P1-3：注入 ValidationRunner 执行实际 ROW_COUNT 复查（@Lazy 避免循环依赖）。
     */
    @Autowired @Lazy
    private ValidationRunner validationRunner;

    /**
     * spec validation-workbench-redesign · Task P1-12.1 / Requirement 8 (AC 2)
     *
     * <p>专用异步执行器：最大并发 5、有界队列 100、单任务等待 30s 视为饥饿拒绝，
     * 避免修复后大量并发复查耗尽下游 PG 连接池。
     */
    private static final java.util.concurrent.ThreadPoolExecutor RECHECK_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 5, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(100),
                    Thread.ofVirtual().name("repair-recheck-", 0).factory(),
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    public record RepairReport(
            Long taskId,
            Long validationId,
            int totalPending,
            int upsertRows,
            int deleteRows,
            int skippedRows,
            long loadedRows,
            long filteredRows,
            int failedRows,
            List<String> labels,
            String status,
            String message,
            // spec validation-workbench-redesign · Task P1-11.1 / Requirement 7 (AC 2)
            // Repair 同步 quick check（闭环 A）：对每条修复 PK 校验存在性
            int verifiedCount,
            int unverifiedCount,
            // spec validation-workbench-redesign · Task P1-12.1 / Requirement 8 (AC 3)
            // Repair 异步 ROW_COUNT 复查（闭环 B）：写入新 validation_run 的 id
            Long postRepairRowCountRunId
    ) {
        /** 旧版 12-arg 构造器（向后兼容）：闭环 A/B 字段填默认值。 */
        public RepairReport(Long taskId, Long validationId, int totalPending, int upsertRows,
                            int deleteRows, int skippedRows, long loadedRows, long filteredRows,
                            int failedRows, List<String> labels, String status, String message) {
            this(taskId, validationId, totalPending, upsertRows, deleteRows, skippedRows,
                    loadedRows, filteredRows, failedRows, labels, status, message,
                    0, 0, null);
        }
    }

    /**
     * 执行差异修复。
     *
     * @param taskId       同步任务 ID
     * @param legacyExecId 校验执行 ID（对应 etl_verify_diff.exec_id 和 ValidationRun.legacyExecId）
     * @param forceDelete  是否强制删除 DELETE_MISSING 类型差异
     * @param dryRun       是否仅模拟执行
     * @param maxRows      单次最大处理行数
     */
    /**
     * spec validation-workbench-redesign · Task P1-6.2 / Requirement 6 (AC 1, 5)
     *
     * <p>带 source 参数的入口（推荐）：source ∈ {AUTO, MANUAL}，写入 etl_verify_diff.repair_source
     * 列以区分自动修复与用户主动修复，便于工作台徽章展示与审计回溯。
     *
     * <p>调用方约定：
     * <ul>
     *   <li>AutoValidationTrigger / DriftWatchService / autoRepair 调度 → AUTO</li>
     *   <li>controller/TaskValidationController 的 /runs/{runId}/repair → MANUAL</li>
     * </ul>
     */
    public RepairReport repair(Long taskId, Long legacyExecId, boolean forceDelete, boolean dryRun, Integer maxRows,
                                String source) {
        currentRepairSource.set(source == null ? "MANUAL" : source);
        try {
            return doRepair(taskId, legacyExecId, forceDelete, dryRun, maxRows);
        } finally {
            currentRepairSource.remove();
        }
    }

    public RepairReport repairRun(Long taskId, Long validationRunId, boolean forceDelete, boolean dryRun, Integer maxRows,
                                  String source) {
        currentRepairSource.set(source == null ? "MANUAL" : source);
        try {
            ValidationRun run = validationRunService.requireByIdAndTaskId(validationRunId, taskId);
            return doRepair(taskId, run.getLegacyExecId(), validationRunId, forceDelete, dryRun, maxRows);
        } finally {
            currentRepairSource.remove();
        }
    }

    /** 当前 repair 调用栈的 repair_source；在 markStatus 时透传到 etl_verify_diff.repair_source 列。 */
    private static final ThreadLocal<String> currentRepairSource = new ThreadLocal<>();

    /**
     * 旧版 5-arg 入口（向后兼容）。
     *
     * @deprecated spec validation-workbench-redesign · Task P1-6.2：新代码请使用 6-arg 入口
     * 显式传入 source；本入口保留以避免破坏既有调用，但 repair_source 默认为 MANUAL。
     */
    @Transactional
    public RepairReport repair(Long taskId, Long legacyExecId, boolean forceDelete, boolean dryRun, Integer maxRows) {
        if (currentRepairSource.get() == null) {
            currentRepairSource.set("MANUAL");
        }
        try {
            return doRepair(taskId, legacyExecId, forceDelete, dryRun, maxRows);
        } finally {
            if ("MANUAL".equals(currentRepairSource.get())) {
                currentRepairSource.remove();
            }
        }
    }

    /** 实际修复逻辑（由 5-arg 和 6-arg 入口共同调用）。 */
    @Transactional
    RepairReport doRepair(Long taskId, Long legacyExecId, boolean forceDelete, boolean dryRun, Integer maxRows) {
        return doRepair(taskId, legacyExecId, null, forceDelete, dryRun, maxRows);
    }

    @Transactional
    RepairReport doRepair(Long taskId, Long legacyExecId, Long validationRunId,
                          boolean forceDelete, boolean dryRun, Integer maxRows) {
        int cap = (maxRows == null || maxRows <= 0) ? DEFAULT_MAX_ROWS : maxRows;
        SyncTask task = syncTaskRepo.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + taskId));
        List<String> pkCols = resolvePkCols(task);
        if (task.getViewNames() == null || task.getViewNames().size() != 1) {
            throw new IllegalArgumentException("Repair v1 仅支持单表/单视图任务");
        }
        String configuredSrcTable = task.getViewNames().get(0);
        SourceDataSource sourceDs = sourceDsRepo.findById(task.getSourceDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("SourceDataSource not found: " + task.getSourceDataSourceId()));
        SourceTableResolver.SourceRelation sourceRelation =
                sourceTableResolver.resolveRequired(task, sourceDs, configuredSrcTable);
        String srcSchema = sourceRelation.schema();
        String srcTable = sourceRelation.table();
        String dialect = sourceRelation.dialect();
        TargetDataSource tgt = targetDsRepo.findById(task.getTargetDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("TargetDataSource not found"));
        if (tgt.getDbName() == null || !whereClauseBuilder.isFieldNameSafe(tgt.getDbName())) {
            throw new IllegalArgumentException("目标库名格式非法: " + tgt.getDbName());
        }
        String tgtTable = targetTableResolver.resolve(task, srcTable);

        List<EtlVerifyDiff> pending = validationRunId != null
                ? diffRepo.findPendingByValidationRunId(validationRunId)
                : diffRepo.findPending(taskId, legacyExecId);
        if (pending.isEmpty()) {
            return new RepairReport(taskId, legacyExecId, 0, 0, 0, 0, 0, 0, 0, List.of(), "NO_PENDING", "无待修复差异");
        }
        if (pending.size() > cap) {
            log.info("Repair: pending={} 超出 cap={}, 仅处理前 {} 行", pending.size(), cap, cap);
            pending = pending.subList(0, cap);
        }

        // 分桶
        List<EtlVerifyDiff> upsertList = new ArrayList<>();
        List<EtlVerifyDiff> deleteList = new ArrayList<>();
        List<EtlVerifyDiff> skipList = new ArrayList<>();
        // spec 035：若任务已开启 Snapshot 删除检测，Checksum 产出的 DELETE_MISSING
        // 是“源 vs 目标”不匹配的不安全信号（可能是增量窗口滤掉、splitPk 偏斜或真删），
        // 应交由 SnapshotDeleteService 物化，这里强制 SKIP，避免双路径误删。
        boolean snapshotEnabled = Boolean.TRUE.equals(task.getEnableSnapshotDelete())
                && Boolean.TRUE.equals(task.getSnapshotAutoCapture());
        for (EtlVerifyDiff d : pending) {
            switch (d.getDiffType()) {
                case EtlVerifyDiff.TYPE_INSERT_MISSING, EtlVerifyDiff.TYPE_UPDATE_DIFF -> upsertList.add(d);
                case EtlVerifyDiff.TYPE_DELETE_MISSING -> {
                    if (snapshotEnabled) {
                        skipList.add(d); // 交给 Snapshot 链路处理
                    } else if (forceDelete) {
                        deleteList.add(d);
                    } else {
                        skipList.add(d);
                    }
                }
                default -> skipList.add(d);
            }
        }

        if (dryRun) {
            return new RepairReport(taskId, legacyExecId, pending.size(),
                    upsertList.size(), deleteList.size(), skipList.size(),
                    0L, 0L, 0, List.of("DRY_RUN"), "DRY_RUN",
                    "dryRun: 计划 upsert=" + upsertList.size() + " delete=" + deleteList.size() + " skip=" + skipList.size());
        }

        List<String> labels = new ArrayList<>(2);
        long totalLoaded = 0L;
        long totalFiltered = 0L;
        int failed = 0;
        StringBuilder msg = new StringBuilder();

        // ── UPSERT 段 ─────────────────────────────────────────────────────
        if (!upsertList.isEmpty()) {
            try {
                List<SourceDataSourceService.ColumnInfo> sourceColumnInfos = sourceDataSourceService
                        .listColumns(task.getSourceDataSourceId(), srcSchema, srcTable);
                List<String> srcCols = sourceColumnInfos.stream().map(c -> c.columnName()).toList();
                if (srcCols.isEmpty()) {
                    throw new IllegalStateException("源表无可用列");
                }
                for (String c : srcCols) {
                    if (!whereClauseBuilder.isFieldNameSafe(c)) {
                        throw new IllegalArgumentException("非法列名: " + c);
                    }
                }
                List<String> pks = upsertList.stream().map(EtlVerifyDiff::getPkValue).toList();
                String sourceWhere = buildRepairSourceWhere(task, dialect, srcTable, srcCols, validationRunId, legacyExecId);
                RepairSourcePlan sourcePlan = buildRepairSourcePlan(task, sourceRelation, sourceColumnInfos, pkCols, sourceWhere);
                List<Map<String, Object>> rows = fetchSourceRows(task.getSourceDataSourceId(),
                        sourcePlan.fromSql(), sourcePlan.pkCols(), sourcePlan.columns(), pks, dialect, sourcePlan.whereClause());
                if (rows.size() != pks.size()) {
                    log.warn("Repair upsert: 部分主键在源端已不存在，请求={} 实际拉到={}", pks.size(), rows.size());
                }
                Map<String, Map<String, Object>> rowsByPk = indexRowsByPk(rows, sourcePlan.pkCols());
                List<EtlVerifyDiff> loadableList = new ArrayList<>();
                List<EtlVerifyDiff> missingSourceList = new ArrayList<>();
                List<Map<String, Object>> loadRows = new ArrayList<>();
                for (EtlVerifyDiff diff : upsertList) {
                    Map<String, Object> row = rowsByPk.get(diff.getPkValue());
                    if (row == null) {
                        missingSourceList.add(diff);
                    } else {
                        loadableList.add(diff);
                        loadRows.add(row);
                    }
                }
                if (!missingSourceList.isEmpty()) {
                    failed += missingSourceList.size();
                    markStatus(missingSourceList, EtlVerifyDiff.REPAIR_FAILED, null, "source row not found");
                    msg.append("source row not found: ").append(missingSourceList.size()).append("; ");
                }
                if (loadRows.isEmpty()) {
                    if (missingSourceList.isEmpty()) {
                        failed += upsertList.size();
                        markStatus(upsertList, EtlVerifyDiff.REPAIR_FAILED, null, "no rows fetched from source");
                    }
                } else {
                    String label = newLabel(taskId, legacyExecId, "upsert");
                    StreamLoadResult r = streamLoadUpsert(tgt, tgtTable, sourcePlan.columns(), loadRows, label,
                            task, sourceDs, legacyExecId);
                    labels.add(label);
                    totalLoaded += r.numberLoadedRows();
                    totalFiltered += r.numberFilteredRows();
                    if (isCompleteStreamLoad(r, loadRows.size())) {
                        markStatus(loadableList, EtlVerifyDiff.REPAIR_DONE, label, null);
                    } else {
                        failed += loadableList.size();
                        String reason = streamLoadFailureReason(r, loadRows.size());
                        markStatus(loadableList, EtlVerifyDiff.REPAIR_FAILED, label, reason);
                        msg.append("upsert FAILED: ").append(reason).append("; ");
                    }
                }
            } catch (Exception e) {
                failed += upsertList.size();
                markStatus(upsertList, EtlVerifyDiff.REPAIR_FAILED, null, e.getMessage());
                msg.append("upsert exception: ").append(e.getMessage()).append("; ");
                log.error("Repair upsert failed", e);
            }
        }

        // ── DELETE 段 ─────────────────────────────────────────────────────
        if (!deleteList.isEmpty()) {
            try {
                List<String> deleteColumns = forceDeleteColumns(pkCols);
                List<Map<String, String>> rows = new ArrayList<>(deleteList.size());
                for (EtlVerifyDiff d : deleteList) {
                    String k = d.getPkValue();
                    if (k.indexOf('\n') >= 0 || k.indexOf('\r') >= 0) {
                        throw new IllegalArgumentException("key_value 含换行字符，拒绝提交");
                    }
                    Map<String, String> row = new LinkedHashMap<>(deleteColumns.size());
                    if (pkCols.size() == 1) {
                        row.put(pkCols.get(0), k);
                    } else {
                        List<String> parts = PkValueCodec.decode(k, pkCols.size());
                        for (int i = 0; i < pkCols.size(); i++) {
                            row.put(pkCols.get(i), parts.get(i));
                        }
                    }
                    if (deleteColumns.stream().anyMatch("_etl_job_id"::equalsIgnoreCase)
                            && pkCols.stream().noneMatch("_etl_job_id"::equalsIgnoreCase)) {
                        row.put("_etl_job_id", String.valueOf(taskId));
                    }
                    rows.add(row);
                }
                String label = newLabel(taskId, legacyExecId, "delete");
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("label", label);
                headers.put("format", "json");
                headers.put("strip_outer_array", "true");
                headers.put("columns", String.join(", ", deleteColumns));
                headers.put("merge_type", "DELETE");
                String pwd = aesUtil.decrypt(tgt.getPasswordEnc());
                StreamLoadResult r = streamLoadClient.put(tgt.getFeHost(), tgt.getStreamLoadPort(),
                        tgt.getDbName(), tgtTable, tgt.getUsername(), pwd,
                        headers, OBJECT_MAPPER.writeValueAsBytes(rows));
                labels.add(label);
                totalLoaded += r.numberLoadedRows();
                totalFiltered += r.numberFilteredRows();
                if (isCompleteStreamLoad(r, deleteList.size())) {
                    markStatus(deleteList, EtlVerifyDiff.REPAIR_DONE, label, null);
                } else {
                    failed += deleteList.size();
                    String reason = streamLoadFailureReason(r, deleteList.size());
                    markStatus(deleteList, EtlVerifyDiff.REPAIR_FAILED, label, reason);
                    msg.append("delete FAILED: ").append(reason).append("; ");
                }
            } catch (Exception e) {
                failed += deleteList.size();
                markStatus(deleteList, EtlVerifyDiff.REPAIR_FAILED, null, e.getMessage());
                msg.append("delete exception: ").append(e.getMessage()).append("; ");
                log.error("Repair delete failed", e);
            }
        }

        if (!skipList.isEmpty()) {
            markStatus(skipList, EtlVerifyDiff.REPAIR_SKIPPED, null, null);
        }

        int attemptedRows = upsertList.size() + deleteList.size();
        String status;
        if (failed > 0) {
            status = failed == pending.size() ? "FAILED" : "PARTIAL";
        } else if (attemptedRows == 0 && !skipList.isEmpty()) {
            status = "SKIPPED";
        } else if (!skipList.isEmpty()) {
            status = "PARTIAL";
        } else {
            status = "OK";
        }
        if (msg.length() == 0) {
            if ("SKIPPED".equals(status)) {
                msg.append("全部差异已跳过，未执行修复；删除类差异请走快照删除检测或专家确认流程");
            } else if ("PARTIAL".equals(status) && !skipList.isEmpty()) {
                msg.append("Success; skipped=").append(skipList.size());
            } else {
                msg.append("Success");
            }
        }
        // spec validation-workbench-redesign · Task P1-11.1 / Requirement 7 (AC 2, 3)
        // 闭环 A 简化版（Phase 1）：信任 Stream Load 已上报的 loaded/failed 行数作为 verified 信号。
        // verifiedCount = loadedRows（Stream Load 实际写入数）
        // unverifiedCount = failed（Stream Load 报告失败数）
        // verifiedCount + unverifiedCount ≤ pending.size()（Property 6）
        // 完整 SELECT EXISTS 校验留给后续强化（详见 design.md「闭环 A」）。
        int verifiedCount = (int) Math.min(totalLoaded, (long) pending.size() - failed);
        int unverifiedCount = failed;

        // spec validation-workbench-redesign · Task P1-12.1 / Requirement 8 (AC 1-5)
        // 闭环 B：异步触发 ROW_COUNT 复查，写入新 validation_run（trigger_type=MANUAL_REPAIR_RECHECK）
        Long postRepairRowCountRunId = null;
        if (!dryRun && verifiedCount > 0) {
            try {
                ValidationRun recheckRun = createPostRepairRecheckRun(taskId, legacyExecId);
                postRepairRowCountRunId = recheckRun.getId();
                dispatchRecheckAfterCommit(taskId, recheckRun.getId());
            } catch (java.util.concurrent.RejectedExecutionException rex) {
                log.warn("RepairService 闭环 B 队列已满或饥饿拒绝 task={}", taskId);
                msg.append("; recheck queue rejected");
            } catch (Exception ex) {
                log.warn("RepairService 闭环 B 派发失败 task={}: {}", taskId, ex.getMessage());
                msg.append("; recheck dispatch failed: ").append(ex.getMessage());
            }
        }

        return new RepairReport(taskId, legacyExecId, pending.size(),
                upsertList.size(), deleteList.size(), skipList.size(),
                totalLoaded, totalFiltered, failed, labels, status, msg.toString(),
                verifiedCount, unverifiedCount, postRepairRowCountRunId);
    }

    private boolean isCompleteStreamLoad(StreamLoadResult result, int expectedRows) {
        return result.success()
                && result.numberFilteredRows() == 0
                && result.numberLoadedRows() >= expectedRows;
    }

    private String streamLoadFailureReason(StreamLoadResult result, int expectedRows) {
        return result.status() + " - " + result.message()
                + " (loaded=" + result.numberLoadedRows()
                + ", filtered=" + result.numberFilteredRows()
                + ", expected=" + expectedRows + ")";
    }

    /** 当前 (taskId, legacyExecId) 下各 repair_status 的计数。 */
    public Map<String, Long> summary(Long taskId, Long legacyExecId) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String s : List.of(EtlVerifyDiff.REPAIR_PENDING, EtlVerifyDiff.REPAIR_DONE,
                                EtlVerifyDiff.REPAIR_FAILED, EtlVerifyDiff.REPAIR_SKIPPED)) {
            m.put(s, diffRepo.countByTaskIdAndExecIdAndRepairStatus(taskId, legacyExecId, s));
        }
        return m;
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────

    private void markStatus(List<EtlVerifyDiff> rows, String status, String label, String reason) {
        OffsetDateTime now = OffsetDateTime.now();
        // spec validation-workbench-redesign · Task P1-6.2：写 repair_source
        // 来源由 ThreadLocal 透传；若调用方未通过新 6-arg 入口设置则默认 MANUAL（保守语义）。
        // Requirement 6 AC 5：从 DONE 重置回 PENDING 的场景下，调用方应再走一次 markStatus(..., PENDING, ...)，
        // 那时来自新一次 repair 调用栈的 source（NULL 或 AUTO）会覆盖之前的 MANUAL，符合 AC 5 语义。
        String source = currentRepairSource.get();
        boolean clearSource = "PENDING".equals(status);  // 重置回 PENDING 时同步清空 source
        for (EtlVerifyDiff d : rows) {
            d.setRepairStatus(status);
            d.setRepairedAt(now);
            if (label != null) d.setRepairLabel(label);
            if (clearSource) {
                d.setRepairSource(null);
            } else if (source != null) {
                d.setRepairSource(source);
            }
            // 若 source 为 null 且非 PENDING，保持原值（避免覆盖已有 AUTO/MANUAL）
        }
        diffRepo.saveAll(rows);
        if (reason != null) {
            log.warn("Repair markStatus={} count={} reason={}", status, rows.size(), reason);
        }
    }

    private List<String> forceDeleteColumns(List<String> pkCols) {
        Map<String, String> enabledFields = etlSystemFieldsService.enabledFields();
        boolean tenantScopeActive = enabledFields != null && enabledFields.containsKey("_etl_job_id");
        if (!tenantScopeActive) {
            return pkCols;
        }
        if (pkCols.stream().anyMatch("_etl_job_id"::equalsIgnoreCase)) {
            return pkCols;
        }
        List<String> cols = new ArrayList<>(pkCols.size() + 1);
        cols.addAll(pkCols);
        cols.add("_etl_job_id");
        return cols;
    }

    private ValidationRun createPostRepairRecheckRun(Long taskId, Long legacyExecId) {
        ValidationRun originalRun = validationRunService.findByTaskIdAndLegacyExecId(taskId, legacyExecId)
                .orElse(null);
        boolean inheritWindow = hasConcreteWindow(originalRun);
        String scope = inheritWindow ? "WINDOW" : "FULL";
        String recheckMethod = resolvePostRepairRecheckMethod(originalRun);
        long recheckExecId = ValidationRunService.nextSyntheticLegacyExecId();
        ValidationRun recheckRun = validationRunService.getOrCreate(
                taskId,
                recheckExecId,
                recheckMethod,
                scope,
                inheritWindow ? originalRun.getWindowStart() : null,
                inheritWindow ? originalRun.getWindowEnd() : null,
                "MANUAL_REPAIR_RECHECK");
        recheckRun.setMethod(recheckMethod);
        recheckRun.setMode(recheckMethod);
        recheckRun.setScope(scope);
        recheckRun.setTriggerType("MANUAL_REPAIR_RECHECK");
        recheckRun.setStatus("PENDING");
        if (inheritWindow) {
            recheckRun.setWindowType(originalRun.getWindowType());
            recheckRun.setWindowStart(originalRun.getWindowStart());
            recheckRun.setWindowEnd(originalRun.getWindowEnd());
            recheckRun.setWindowStartId(originalRun.getWindowStartId());
            recheckRun.setWindowEndId(originalRun.getWindowEndId());
        } else {
            recheckRun.setWindowType(null);
            recheckRun.setWindowStart(null);
            recheckRun.setWindowEnd(null);
            recheckRun.setWindowStartId(null);
            recheckRun.setWindowEndId(null);
        }
        ValidationRun saved = validationRunService.save(recheckRun);
        return saved != null ? saved : recheckRun;
    }

    private String resolvePostRepairRecheckMethod(ValidationRun originalRun) {
        String method = originalRun == null ? null : originalRun.getMethod();
        if (method == null || method.isBlank()) {
            method = originalRun == null ? null : originalRun.getMode();
        }
        String normalized = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("CHECKSUM") || normalized.contains("ROW_AUDIT") || normalized.contains("CHANGE_AUDIT")) {
            return "CHECKSUM";
        }
        return "ROW_COUNT";
    }

    private boolean hasConcreteWindow(ValidationRun run) {
        if (run == null || !"WINDOW".equalsIgnoreCase(run.getScope())) {
            return false;
        }
        return run.getWindowStart() != null
                || run.getWindowEnd() != null
                || run.getWindowStartId() != null
                || run.getWindowEndId() != null;
    }

    private void dispatchRecheckAfterCommit(Long taskId, Long runId) {
        Runnable submit = () -> RECHECK_EXECUTOR.submit(() -> runPostRepairRecheck(taskId, runId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        submit.run();
                    } catch (RuntimeException ex) {
                        log.warn("RepairService 闭环 B afterCommit 派发失败 task={} runId={}: {}",
                                taskId, runId, ex.getMessage());
                    }
                }
            });
        } else {
            submit.run();
        }
    }

    private void runPostRepairRecheck(Long taskId, Long runId) {
        try {
            // sleep 5s 给 Doris compaction 缓冲（Requirement 8 AC 2）
            Thread.sleep(5000L);
            log.info("RepairService 闭环 B：开始异步校验复查 task={} runId={}", taskId, runId);
            ValidationRun recheckRun = validationRunService.findByIdAndTaskId(runId, taskId).orElse(null);
            if (recheckRun != null) {
                recheckRun.setStatus("RUNNING");
                ValidationRun saved = validationRunService.save(recheckRun);
                validationRunner.run(saved != null ? saved : recheckRun, null);
                log.info("RepairService 闭环 B：校验复查完成 task={} runId={} method={} status={}",
                        taskId, runId, recheckRun.getMethod(), recheckRun.getStatus());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("RepairService 闭环 B 异步复查异常 task={} runId={}: {}",
                    taskId, runId, ex.getMessage());
        }
    }

    private static String newLabel(Long taskId, Long legacyExecId, String suffix) {
        return "repair_" + taskId + "_" + legacyExecId + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "_" + suffix;
    }

    private List<Map<String, Object>> fetchSourceRows(Long sourceDsId, String fromSql,
                                                      List<String> pkCols, List<String> cols,
                                                      List<String> pks, String dialect,
                                                      String sourceWhere) throws Exception {
        if (pks.isEmpty()) return List.of();
        if (pkCols.size() == 1) {
            return fetchSourceRowsSinglePk(sourceDsId, fromSql, pkCols.get(0), cols, pks, dialect, sourceWhere);
        } else {
            return fetchSourceRowsCompositePk(sourceDsId, fromSql, pkCols, cols, pks, dialect, sourceWhere);
        }
    }

    private List<Map<String, Object>> fetchSourceRowsSinglePk(Long sourceDsId, String fromSql,
                                                              String pkCol, List<String> cols,
                                                              List<String> pks, String dialect,
                                                              String sourceWhere) throws Exception {
        StringBuilder sb = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(dialectQuoteHelper.quoteColumn(dialect, cols.get(i)));
        }
        sb.append(" FROM ").append(fromSql);
        sb.append(" WHERE ").append(dialectQuoteHelper.quoteColumn(dialect, pkCol)).append(" IN (");
        for (int i = 0; i < pks.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("?");
        }
        sb.append(")");
        appendSourceWhere(sb, sourceWhere);
        try (Connection conn = sourceDataSourceService.openConnection(sourceDsId);
             PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < pks.size(); i++) {
                ps.setString(i + 1, pks.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<Map<String, Object>> out = new ArrayList<>(pks.size());
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>(n);
                    for (int i = 1; i <= n; i++) {
                        // 列名统一小写（Doris 默认小写列）
                        row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }

    /** 复合主键逐行回查：将 pk_value 按 "|"拆分成各列值，构建 WHERE col1=? AND col2=? 语句。 */
    private List<Map<String, Object>> fetchSourceRowsCompositePk(Long sourceDsId, String fromSql,
                                                                  List<String> pkCols, List<String> cols,
                                                                  List<String> pks, String dialect,
                                                                  String sourceWhere) throws Exception {
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) select.append(", ");
            select.append(dialectQuoteHelper.quoteColumn(dialect, cols.get(i)));
        }
        select.append(" FROM ").append(fromSql).append(" WHERE ");
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) select.append(" AND ");
            select.append(dialectQuoteHelper.quoteColumn(dialect, pkCols.get(i))).append("=?");
        }
        appendSourceWhere(select, sourceWhere);
        String sql = select.toString();
        List<Map<String, Object>> out = new ArrayList<>(pks.size());
        try (Connection conn = sourceDataSourceService.openConnection(sourceDsId)) {
            for (String pk : pks) {
                List<String> parts;
                try {
                    parts = PkValueCodec.decode(pk, pkCols.size());
                } catch (IllegalArgumentException e) {
                    log.warn("Repair: pk_value='{}' 与 pkCols={} 列数不匹配，跳过: {}", pk, pkCols, e.getMessage());
                    continue;
                }
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < parts.size(); i++) {
                        ps.setString(i + 1, parts.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int n = md.getColumnCount();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>(n);
                            for (int i = 1; i <= n; i++) {
                                row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
                            }
                            out.add(row);
                        }
                    }
                }
            }
        }
        return out;
    }

    private void appendSourceWhere(StringBuilder sql, String sourceWhere) {
        if (sourceWhere != null && !sourceWhere.isBlank()) {
            sql.append(" AND (").append(sourceWhere).append(")");
        }
    }

    RepairSourcePlan buildRepairSourcePlan(
            SyncTask task,
            SourceTableResolver.SourceRelation sourceRelation,
            List<SourceDataSourceService.ColumnInfo> sourceColumns,
            List<String> pkCols,
            String sourceWhere) {
        MedicalRepairOptions medical = medicalRepairOptions(task);
        if (medical.datasetCode() != null) {
            if (medicalContractService == null || medicalSourceSelectBuilder == null
                    || sourceDialectAdapterResolver == null) {
                throw new IllegalStateException("医共体 contract-driven Repair 缺少 SQL 构建依赖");
            }
            MedicalDatasetContract contract = MedicalContractSnapshotCodec.resolveForTask(
                    task, medicalContractService, OBJECT_MAPPER);
            SourceDialectAdapter adapter = sourceDialectAdapterResolver.resolve(
                    sourceRelation.dialect(), medical.compatibilityMode());
            MedicalSourceSelectPlan selectPlan = medicalSourceSelectBuilder.buildSelect(
                    sourceRelation.schema(),
                    sourceRelation.table(),
                    contract,
                    sourceColumns,
                    adapter,
                    medical.fieldMapping());
            if (selectPlan.hasBlockers()) {
                throw new IllegalStateException("医共体 Repair 源端 SQL 生成失败: " + selectPlan.blockers());
            }
            String query = selectPlan.sql();
            if (sourceWhere != null && !sourceWhere.isBlank()) {
                query += " WHERE " + sourceWhere;
            }
            List<String> standardPkCols = contract.primaryKeys().stream()
                    .map(key -> contractField(contract, key).dorisColumn())
                    .toList();
            if (standardPkCols.isEmpty()) {
                throw new IllegalStateException("医共体 Repair 缺少规范主键: " + contract.datasetCode());
            }
            List<String> standardColumns = contract.fields().stream()
                    .sorted(java.util.Comparator.comparingInt(field -> field.order() == null ? 0 : field.order()))
                    .map(MedicalFieldContract::dorisColumn)
                    .toList();
            return new RepairSourcePlan(
                    "(\n" + query + "\n) dfetl_src",
                    standardPkCols,
                    standardColumns,
                    "");
        }
        List<String> cols = sourceColumns == null ? List.of() : sourceColumns.stream()
                .map(SourceDataSourceService.ColumnInfo::columnName)
                .toList();
        return new RepairSourcePlan(
                dialectQuoteHelper.qualifyTable(sourceRelation.dialect(), sourceRelation.schema(), sourceRelation.table()),
                pkCols,
                cols,
                sourceWhere == null ? "" : sourceWhere);
    }

    private MedicalFieldContract contractField(MedicalDatasetContract contract, String fieldCodeOrDorisColumn) {
        return contract.fields().stream()
                .filter(field -> field.code().equalsIgnoreCase(fieldCodeOrDorisColumn)
                        || field.dorisColumn().equalsIgnoreCase(fieldCodeOrDorisColumn))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "医共体标准字段不存在: " + contract.datasetCode() + "." + fieldCodeOrDorisColumn));
    }

    private MedicalRepairOptions medicalRepairOptions(SyncTask task) {
        if (task == null || task.getDataCharacteristics() == null || task.getDataCharacteristics().isBlank()) {
            return new MedicalRepairOptions(null, null, Map.of());
        }
        String dc = task.getDataCharacteristics();
        try {
            Map<String, Object> values = OBJECT_MAPPER.readValue(dc, MAP_TYPE);
            Object mode = values.get("medicalMappingMode");
            boolean contractDriven = mode != null && "CONTRACT_DRIVEN".equalsIgnoreCase(mode.toString());
            if (!contractDriven) {
                return new MedicalRepairOptions(null, null, Map.of());
            }
            Object datasetCode = values.get("matchedDatasetCode");
            if (datasetCode == null || datasetCode.toString().isBlank()) {
                throw new IllegalStateException("医共体 contract-driven 任务缺少 matchedDatasetCode");
            }
            Object compatibilityMode = values.get("compatibilityMode");
            return new MedicalRepairOptions(
                    datasetCode.toString().trim(),
                    compatibilityMode == null ? null : compatibilityMode.toString().trim(),
                    parseStringMap(values.get("fieldMapping")));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (dc.contains("CONTRACT_DRIVEN")) {
                throw new IllegalStateException("医共体 Repair 配置解析失败: " + e.getMessage(), e);
            }
            return new MedicalRepairOptions(null, null, Map.of());
        }
    }

    private static Map<String, String> parseStringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        return raw.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toString(),
                        entry -> entry.getValue().toString(),
                        (first, second) -> second));
    }

    private String buildRepairSourceWhere(SyncTask task, String dialect, String srcTable,
                                          List<String> srcCols, Long validationRunId, Long legacyExecId) {
        WatermarkService.WindowContext window = resolveRepairWindow(task.getId(), validationRunId, legacyExecId);
        return validationWhereBuilder.buildSourceWhere(window, task, dialect, srcTable, srcCols);
    }

    private WatermarkService.WindowContext resolveRepairWindow(Long taskId, Long validationRunId, Long legacyExecId) {
        ValidationRun run = null;
        if (validationRunId != null) {
            run = validationRunService.findByIdAndTaskId(validationRunId, taskId).orElse(null);
        } else if (legacyExecId != null) {
            run = validationRunService.findByTaskIdAndLegacyExecId(taskId, legacyExecId).orElse(null);
        }
        if (!hasConcreteWindow(run)) {
            return null;
        }
        return new WatermarkService.WindowContext(
                "INCREMENT",
                run.getWindowStart(),
                run.getWindowEnd(),
                run.getWindowStartId(),
                run.getWindowEndId());
    }

    private Map<String, Map<String, Object>> indexRowsByPk(List<Map<String, Object>> rows, List<String> pkCols) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        RowNormalizer normalizer = new RowNormalizer();
        for (Map<String, Object> row : rows) {
            List<String> parts = new ArrayList<>(pkCols.size());
            boolean complete = true;
            for (String pkCol : pkCols) {
                String key = pkCol.toLowerCase(Locale.ROOT);
                if (!row.containsKey(key)) {
                    complete = false;
                    break;
                }
                parts.add(normalizer.normalize(row.get(key)));
            }
            if (complete) {
                indexed.put(PkValueCodec.encode(parts), row);
            }
        }
        return indexed;
    }

    /** 从 task.upsertKeys / splitPk 解析主键列集合（upsertKeys 优先，splitPk 仅单列）。 */
    private List<String> resolvePkCols(SyncTask task) {
        List<String> keys = task.getUpsertKeys();
        if (keys == null || keys.isEmpty()) {
            String splitPk = task.getSplitPk();
            if (splitPk == null || splitPk.isBlank()) {
                throw new IllegalArgumentException("Repair 需要配置 upsertKeys 或 splitPk 主键列");
            }
            if (!whereClauseBuilder.isFieldNameSafe(splitPk)) {
                throw new IllegalArgumentException("非法 splitPk: " + splitPk);
            }
            return List.of(splitPk);
        }
        for (String col : keys) {
            if (!whereClauseBuilder.isFieldNameSafe(col)) {
                throw new IllegalArgumentException("非法主键列名: " + col);
            }
        }
        return List.copyOf(keys);
    }

    private StreamLoadResult streamLoadUpsert(TargetDataSource tgt, String tgtTable,
                                              List<String> srcCols, List<Map<String, Object>> rows,
                                              String label,
                                              SyncTask task,
                                              SourceDataSource sourceDs,
                                              Long legacyExecId) throws Exception {
        Map<String, String> enabledSystemFields = etlSystemFieldsService.enabledFields();
        if (enabledSystemFields == null) {
            enabledSystemFields = Map.of();
        }
        List<String> loadCols = new ArrayList<>(srcCols.size() + enabledSystemFields.size());
        for (String srcCol : srcCols) {
            loadCols.add(srcCol.toLowerCase(Locale.ROOT));
        }
        for (String field : enabledSystemFields.keySet()) {
            if (!loadCols.contains(field)) {
                loadCols.add(field);
            }
        }
        List<Map<String, Object>> loadRows =
                enrichWithEtlSystemFields(rows, enabledSystemFields, task, sourceDs, legacyExecId);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("label", label);
        headers.put("format", "json");
        headers.put("strip_outer_array", "true");
        // columns 列名小写以匹配 Doris
        StringBuilder cols = new StringBuilder();
        for (int i = 0; i < loadCols.size(); i++) {
            if (i > 0) cols.append(", ");
            cols.append(loadCols.get(i));
        }
        headers.put("columns", cols.toString());
        // 主键模型表 APPEND 即等效 UPSERT；不设 merge_type
        String pwd = aesUtil.decrypt(tgt.getPasswordEnc());
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(loadRows);
        log.info("Repair streamLoadUpsert table={}.{} rows={} label={}",
                tgt.getDbName(), tgtTable, loadRows.size(), label);
        return streamLoadClient.put(tgt.getFeHost(), tgt.getStreamLoadPort(),
                tgt.getDbName(), tgtTable, tgt.getUsername(), pwd,
                headers, body);
    }

    private List<Map<String, Object>> enrichWithEtlSystemFields(List<Map<String, Object>> rows,
                                                                Map<String, String> enabledSystemFields,
                                                                SyncTask task,
                                                                SourceDataSource sourceDs,
                                                                Long legacyExecId) {
        if (enabledSystemFields.isEmpty()) {
            return rows;
        }
        String syncTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now());
        List<Map<String, Object>> enriched = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            for (String field : enabledSystemFields.keySet()) {
                copy.put(field, etlSystemFieldValue(field, task, sourceDs, legacyExecId, syncTime));
            }
            enriched.add(copy);
        }
        return enriched;
    }

    private Object etlSystemFieldValue(String field, SyncTask task, SourceDataSource sourceDs,
                                       Long legacyExecId, String syncTime) {
        return switch (field) {
            case "_etl_batch_id" -> legacyExecId == null ? 0L : legacyExecId;
            case "_etl_job_id" -> task.getId() == null ? 0L : task.getId();
            case "_etl_job_version" -> task.getVersion() == null ? "" : task.getVersion();
            case "_etl_sync_time" -> syncTime;
            case "_etl_source_system" -> resolveSourceCode(sourceDs);
            case "_etl_window_start", "_etl_window_end" -> "FULL";
            default -> null;
        };
    }

    private String resolveSourceCode(SourceDataSource sourceDs) {
        if (sourceDs == null) {
            return "";
        }
        String sourceCode = sourceDs.getSourceCode();
        if (sourceCode != null && !sourceCode.isBlank()) {
            return sourceCode;
        }
        return sourceDs.getName() == null ? "" : sourceDs.getName();
    }
}
