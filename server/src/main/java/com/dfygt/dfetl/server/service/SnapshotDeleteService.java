package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.dto.SnapshotApplyHistoryDto;
import com.dfygt.dfetl.server.dto.SnapshotExecutionDto;
import com.dfygt.dfetl.server.dto.SourceDataSourceDto;
import com.dfygt.dfetl.server.engine.doris.DorisStreamLoadClient;
import com.dfygt.dfetl.server.engine.doris.StreamLoadResult;
import com.dfygt.dfetl.server.entity.SnapshotApplyHistory;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.entity.TaskSnapshotKey;
import com.dfygt.dfetl.server.repository.SnapshotApplyHistoryRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.dfygt.dfetl.server.repository.TaskSnapshotKeyRepository;
import com.dfygt.dfetl.server.service.sql.SqlLiteralEncoder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spec 020：源端主键集合快照 + 跨次差集删除检测。
 *
 * <p>设计取舍（MVP）：
 *  - 仅支持单列主键（upsertKeys 解析后必须 size=1）
 *  - capture 用 SELECT ... FROM <schema>.<table> 一次性拉所有 PK，按批 insert
 *  - detect 直接靠 JPA 查 prev/curr 两次后内存差集（key_value 是 VARCHAR(500)，量级在十万以内可控）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotDeleteService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int INSERT_BATCH_SIZE = 1000;

    /** P0-2: 按 taskId 粒度的并发锁，防止 capture/applyDeletes 并发冲突 */
    private static final ConcurrentHashMap<Long, Object> TASK_LOCKS = new ConcurrentHashMap<>();

    private Object lockFor(Long taskId) {
        return TASK_LOCKS.computeIfAbsent(taskId, k -> new Object());
    }

    private final SyncTaskRepository syncTaskRepo;
    private final TaskSnapshotKeyRepository snapshotRepo;
    private final SourceDataSourceService sourceDataSourceService;
    private final WhereClauseBuilder whereClauseBuilder;
    private final DialectQuoteHelper dialectQuoteHelper;
    private final TargetDataSourceRepository targetDataSourceRepo;
    private final AesUtil aesUtil;
    private final DorisStreamLoadClient streamLoadClient;
    private final TargetTableResolver targetTableResolver;
    private final SnapshotApplyHistoryRepository applyHistoryRepo;
    private final SourceTableResolver sourceTableResolver;
    private final SourceDataSourceRepository sourceDataSourceRepository;

    // ── capture ──────────────────────────────────────────────────────────

    @Transactional
    public int capture(Long taskId, Long executionId) {
        synchronized (lockFor(taskId)) {
        SyncTask task = getOrThrow(taskId);
        if (executionId == null) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        String pkCol = resolveSinglePk(task);
        if (task.getViewNames() == null || task.getViewNames().size() != 1) {
            throw new IllegalArgumentException("snapshot capture 仅支持单表/单视图（viewNames size=1）");
        }
        String table = task.getViewNames().get(0);
        // P2 修复：schema 解析复用 SourceTableResolver.resolveSchemaRequired，与同步/校验链路一致
        // （含 Oracle username fallback），避免 Oracle 任务 sourceSchema 留空时快照功能不可用。
        SourceDataSource sourceEntity = sourceDataSourceRepository.findById(task.getSourceDataSourceId())
                .orElseThrow(() -> new NoSuchElementException(
                        "SourceDataSource not found: " + task.getSourceDataSourceId()));
        String schema = sourceTableResolver.resolveSchemaRequired(task, sourceEntity);
        if (!whereClauseBuilder.isFieldNameSafe(table)) {
            throw new IllegalArgumentException("源对象名格式非法: " + table);
        }
        if (!whereClauseBuilder.isFieldNameSafe(schema)) {
            throw new IllegalArgumentException("sourceSchema 格式非法: " + schema);
        }

        // 先清掉同 task+execution 的旧快照，幂等
        snapshotRepo.deleteByTaskAndExecution(taskId, executionId);

        // 构建有效 WHERE 条件：staticFilter + per-table filterConditionMap + 软删除排除
        // P1-7: 将 srcType 提前解析，避免 buildCaptureWhere 内重复调用
        String srcType = resolveSourceDbType(task);
        String whereClause = buildCaptureWhere(task, table, srcType, schema);

        String quotedSchema = dialectQuoteHelper.quoteIdentifier(srcType, schema);
        String quotedTable = dialectQuoteHelper.quoteIdentifier(srcType, table);
        String quotedPk = dialectQuoteHelper.quoteColumn(srcType, pkCol);
        String sql = "SELECT " + quotedPk + " FROM " + quotedSchema + "." + quotedTable;
        if (!whereClause.isBlank()) {
            sql += " WHERE " + whereClause;
        }
        log.info("SnapshotDelete.capture taskId={} executionId={} sql={}", taskId, executionId, sql);

        int total = 0;
        try (Connection conn = sourceDataSourceService.openConnection(task.getSourceDataSourceId());
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setFetchSize(5000);
            try (ResultSet rs = ps.executeQuery()) {
            List<TaskSnapshotKey> buffer = new ArrayList<>(INSERT_BATCH_SIZE);
            while (rs.next()) {
                String v = rs.getString(1);
                if (v == null) continue;
                TaskSnapshotKey k = new TaskSnapshotKey();
                k.setTaskId(taskId);
                k.setExecutionId(executionId);
                k.setKeyValue(v);
                buffer.add(k);
                if (buffer.size() >= INSERT_BATCH_SIZE) {
                    snapshotRepo.saveAll(buffer);
                    total += buffer.size();
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                snapshotRepo.saveAll(buffer);
                total += buffer.size();
            }
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("snapshot capture 失败: " + e.getMessage(), e);
        }
        log.info("SnapshotDelete.capture taskId={} executionId={} captured={}", taskId, executionId, total);
        return total;
        } // end synchronized
    }

    // ── detect ───────────────────────────────────────────────────────────

    /**
     * 计算 prev∖curr 差集，即"上次有这次没有"的 key 集合。
     *
     * <p>大小写敏感语义（multi-tenant-correctness-hardening Requirement 1）：
     * <ul>
     *   <li>AC1：使用 {@link TaskSnapshotKey#getKeyValue()} 的原始字符串值比较，
     *       不做任何 {@code toLowerCase} / {@code toUpperCase} / Unicode normalize 折叠。</li>
     *   <li>AC2-AC3：[A001, xyz] vs [a001, xyz] → [A001]；
     *       [BillNo-001] vs [billno-001] → [BillNo-001]。</li>
     *   <li>AC5：返回的被删 key 集合保留原始大小写，由 {@link #applyDeletes} 原值透传到
     *       Doris Stream Load {@code merge_type=DELETE} 的 JSON 主键字段。</li>
     * </ul>
     *
     * <p>P1 修复（2026-06-02）背景：原先 {@code toLowerCase} 比较会导致大小写敏感主键
     * （如病案号 A001 vs a001 是不同记录）漏删/误删——prev 有 A001（已删）、curr 有 a001（仍存）时，
     * {@code toLowerCase} 会误判 A001 仍存而漏删，applyDeletes 直接物化 DELETE 使误判转化为 Doris 数据错误。
     * 大小写敏感是 ETL 主键比对的正确默认。
     *
     * <p>未来若引入 {@code task.snapshotKeyCaseInsensitive} 开关（本 spec 不引入，见 R1 AC6），
     * 默认仍为大小写敏感；仅当显式置 true 时，才在比较分支中使用 {@link java.util.Locale#ROOT}
     * 的 {@code toLowerCase} 退化为大小写不敏感比较。
     */
    public List<String> detect(Long taskId, Long currExecId, Long prevExecId) {
        if (currExecId == null || prevExecId == null) {
            throw new IllegalArgumentException("currExecId 与 prevExecId 都不能为空");
        }
        // P0-1: 使用数据库层 countDeletedKeys 获取数量，但 detect 需要返回具体 key 列表给前端展示
        // 仍用内存差集，但通过 findKeyValues 的 JPA 查询自带 fetchSize 优化
        Set<String> currKeys = new HashSet<>();
        for (String v : snapshotRepo.findKeyValues(taskId, currExecId)) {
            if (v != null) currKeys.add(v);
        }
        List<String> deleted = new ArrayList<>();
        for (String v : snapshotRepo.findKeyValues(taskId, prevExecId)) {
            if (v == null) continue;
            if (!currKeys.contains(v)) {
                deleted.add(v);
            }
        }
        log.info("SnapshotDelete.detect taskId={} prev={} curr={} deletedKeys={}",
                taskId, prevExecId, currExecId, deleted.size());
        return deleted;
    }

    public List<String> detectAndRecord(Long taskId, Long currExecId, Long prevExecId) {
        List<String> deleted = detect(taskId, currExecId, prevExecId);
        String result = deleted.isEmpty() ? "NO_DELETES" : "DETECTED";
        String message = deleted.isEmpty()
                ? "手动检测未发现源端删除差集"
                : "手动检测发现 " + deleted.size() + " 个源端已删除 key";
        recordDetectHistory(taskId, prevExecId, currExecId, deleted.size(), result, message);
        return deleted;
    }

    public void recordDetectHistory(Long taskId, Long prevExecId, Long currExecId,
                                    int detectedKeys, String result, String message) {
        ApplyDeleteResult historyResult = new ApplyDeleteResult(
                Math.max(0, detectedKeys), 0L, 0L,
                result == null || result.isBlank() ? "DETECTED" : result,
                null,
                message == null || message.isBlank() ? "Success" : message);
        saveApplyHistory(taskId, prevExecId, currExecId, true, historyResult);
    }

    public List<SnapshotExecutionDto> listExecutions(Long taskId) {
        return snapshotRepo.findExecutionSummaries(taskId).stream()
                .map(row -> new SnapshotExecutionDto(row.getExecutionId(), row.getCapturedAt(), row.getKeyCount(), "snapshot"))
                .toList();
    }

    public List<SnapshotApplyHistoryDto> listApplyHistory(Long taskId) {
        return applyHistoryRepo.findTop20ByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(h -> new SnapshotApplyHistoryDto(
                        h.getId(),
                        h.getPrevExecutionId(),
                        h.getCurrExecutionId(),
                        h.isDryRun(),
                        h.getDetectedKeys(),
                        h.getLoadedRows(),
                        h.getFilteredRows(),
                        h.getResult(),
                        h.getMessage(),
                        h.getCreatedAt()))
                .toList();
    }

    public String exportDiffCsv(Long taskId, Long prevExecId, Long currExecId) {
        StringBuilder csv = new StringBuilder("task_id,prev_execution_id,curr_execution_id,pk_value\n");
        for (String key : detect(taskId, currExecId, prevExecId)) {
            csv.append(taskId).append(',')
                    .append(prevExecId).append(',')
                    .append(currExecId).append(',')
                    .append(escapeCsv(key)).append('\n');
        }
        return csv.toString();
    }

    // ── apply deletes (spec 020.1) ───────────────────────────────────────

    /**
     * 把 detect() 算出的被删 key 通过 Doris Stream Load (merge_type=DELETE) 物化删除。
     * dryRun=true 时仅返回结果，不发送任何 HTTP 请求。
     */
    public ApplyDeleteResult applyDeletes(Long taskId, Long prevExecId, Long currExecId, boolean dryRun) {
        synchronized (lockFor(taskId)) {
        SyncTask task = getOrThrow(taskId);
        String pkCol = resolveSinglePk(task);
        String table = resolveTargetTable(task);

        List<String> keys = detect(taskId, currExecId, prevExecId);
        if (dryRun || keys.isEmpty()) {
            String reason = dryRun ? "DRY_RUN" : "NO_DELETES";
            ApplyDeleteResult result = new ApplyDeleteResult(keys.size(), 0L, 0L, reason, null, "Success");
            saveApplyHistory(taskId, prevExecId, currExecId, dryRun, result);
            return result;
        }
        assertApplyWithinSnapshotDeleteThreshold(task, taskId, prevExecId, currExecId, keys.size());
        try {
            List<String> deleteColumns = snapshotDeleteColumns(pkCol);

            TargetDataSource tgt = targetDataSourceRepo.findById(task.getTargetDataSourceId())
                    .orElseThrow(() -> new NoSuchElementException("TargetDataSource not found: " + task.getTargetDataSourceId()));
            if (tgt.getDbName() == null || !whereClauseBuilder.isFieldNameSafe(tgt.getDbName())) {
                throw new IllegalArgumentException("\u76EE\u6807\u5E93\u540D\u683C\u5F0F\u975E\u6CD5: " + tgt.getDbName());
            }

            // \u6784\u9020 JSON body
            List<Map<String, String>> rows = new ArrayList<>(keys.size());
            for (String k : keys) {
                if (k.indexOf('\n') >= 0 || k.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("key_value \u542B\u6362\u884C\u5B57\u7B26\uFF0C\u62D2\u7EDD\u63D0\u4EA4");
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put(pkCol, k);
                if (deleteColumns.stream().anyMatch("_etl_job_id"::equalsIgnoreCase)
                        && !"_etl_job_id".equalsIgnoreCase(pkCol)) {
                    row.put("_etl_job_id", String.valueOf(taskId));
                }
                rows.add(row);
            }
            byte[] body = OBJECT_MAPPER.writeValueAsBytes(rows);

            String label = "snap_del_" + taskId + "_" + currExecId + "_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("label", label);
            headers.put("format", "json");
            headers.put("strip_outer_array", "true");
            headers.put("columns", String.join(", ", deleteColumns));
            headers.put("merge_type", "DELETE");

            String pwd = aesUtil.decrypt(tgt.getPasswordEnc());
            log.info("SnapshotDelete.applyDeletes taskId={} table={}.{} keys={} label={}",
                    taskId, tgt.getDbName(), table, keys.size(), label);
            StreamLoadResult r = streamLoadClient.put(
                    tgt.getFeHost(), tgt.getStreamLoadPort(),
                    tgt.getDbName(), table,
                    tgt.getUsername(), pwd,
                    headers, body);
            boolean complete = isCompleteStreamLoad(r, keys.size());
            ApplyDeleteResult result = new ApplyDeleteResult(keys.size(),
                    r.numberLoadedRows(), r.numberFilteredRows(),
                    complete ? "OK" : "FAILED", r.label(), streamLoadResultMessage(r, keys.size()));
            saveApplyHistory(taskId, prevExecId, currExecId, false, result);
            return result;
        } catch (RuntimeException re) {
            saveApplyHistory(taskId, prevExecId, currExecId, false,
                    new ApplyDeleteResult(keys.size(), 0L, 0L, "FAILED", null, re.getMessage()));
            throw re;
        } catch (Exception e) {
            saveApplyHistory(taskId, prevExecId, currExecId, false,
                    new ApplyDeleteResult(keys.size(), 0L, 0L, "FAILED", null, e.getMessage()));
            throw new RuntimeException("applyDeletes \u5931\u8D25: " + e.getMessage(), e);
        }
        } // end synchronized
    }

    private void assertApplyWithinSnapshotDeleteThreshold(SyncTask task, Long taskId, Long prevExecId,
                                                          Long currExecId, int deletedKeys) {
        int prevSize = Math.max(1, snapshotRepo.findKeyValues(taskId, prevExecId).size());
        BigDecimal ratio = new BigDecimal(deletedKeys)
                .divide(new BigDecimal(prevSize), 4, RoundingMode.HALF_UP);
        BigDecimal maxRatio = task.getSnapshotDeleteMaxRatio() != null
                ? task.getSnapshotDeleteMaxRatio() : new BigDecimal("0.0500");
        if (ratio.compareTo(maxRatio) <= 0) {
            return;
        }
        String message = "snapshot delete 已熔断：删除比例 " + ratio.toPlainString()
                + " > maxRatio=" + maxRatio.toPlainString()
                + "，请人工核对后再走专家处理流程";
        saveApplyHistory(taskId, prevExecId, currExecId, false,
                new ApplyDeleteResult(deletedKeys, 0L, 0L, "FUSED", null, message));
        throw new IllegalStateException(message);
    }

    /** 委托 {@link TargetTableResolver} 统一解析目标表名（lower-case + 白名单校验）。 */
    String resolveTargetTable(SyncTask task) {
        if (task.getViewNames() == null || task.getViewNames().size() != 1) {
            throw new IllegalArgumentException("snapshot apply-deletes \u4EC5\u652F\u6301\u5355\u8868/\u5355\u89C6\u56FE");
        }
        return targetTableResolver.resolve(task, task.getViewNames().get(0));
    }

    private List<String> snapshotDeleteColumns(String pkCol) {
        if ("_etl_job_id".equalsIgnoreCase(pkCol)) {
            return List.of(pkCol);
        }
        return List.of(pkCol, "_etl_job_id");
    }

    private void saveApplyHistory(Long taskId, Long prevExecId, Long currExecId, boolean dryRun, ApplyDeleteResult result) {
        SnapshotApplyHistory h = new SnapshotApplyHistory();
        h.setTaskId(taskId);
        h.setPrevExecutionId(prevExecId);
        h.setCurrExecutionId(currExecId);
        h.setDryRun(dryRun);
        h.setDetectedKeys(result.detectedKeys());
        h.setLoadedRows(result.loadedRows());
        h.setFilteredRows(result.filteredRows());
        h.setResult(result.result());
        h.setLabel(result.label());
        h.setMessage(result.message());
        applyHistoryRepo.save(h);
    }

    /** apply-deletes \u8FD4\u56DE\u7ED3\u679C\u3002*/
    public record ApplyDeleteResult(
            int detectedKeys,
            long loadedRows,
            long filteredRows,
            String result,   // OK / DRY_RUN / NO_DELETES / FAILED
            String label,
            String message
    ) {}

    // ── prune ────────────────────────────────────────────────────────────

    @Transactional
    public int prune(Long taskId, int keepLastN) {
        if (keepLastN < 1) keepLastN = 1;
        List<Long> all = snapshotRepo.findExecutionIdsDesc(taskId);
        if (all.size() <= keepLastN) return 0;
        List<Long> toDelete = all.subList(keepLastN, all.size());
        int n = snapshotRepo.deleteByTaskAndExecutions(taskId, toDelete);
        log.info("SnapshotDelete.prune taskId={} keepLastN={} pruned executions={} rows={}",
                taskId, keepLastN, toDelete.size(), n);
        return n;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private SyncTask getOrThrow(Long id) {
        return syncTaskRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + id));
    }

    /**
     * 构建 capture 的有效 WHERE 条件（不含 WHERE 关键字）。
     *
     * <p>组合顺序：staticFilter + per-table filterConditionMap + 软删除排除。
     * 复用 WhereClauseBuilder 处理 staticFilter 和 filterConditionMap，
     * 然后追加软删除排除条件（enableDorisMerge + softDeleteField）。
     *
     * @param srcType P1-7: 由调用方传入，避免重复查询
     */
    String buildCaptureWhere(SyncTask task, String table, String srcType) {
        return buildCaptureWhere(task, table, srcType, task.getSourceSchema());
    }

    String buildCaptureWhere(SyncTask task, String table, String srcType, String resolvedSchema) {
        // 复用 WhereClauseBuilder：传 null window 表示不加增量窗口条件
        String baseWhere = whereClauseBuilder.build(task, srcType, null, table);

        // 追加软删除排除条件
        if (ValidationSourceFilterBuilder.hasDorisMergeSoftDelete(task)) {
            String softDeleteField = task.getSoftDeleteField().trim();
            if (!whereClauseBuilder.isFieldNameSafe(softDeleteField)) {
                throw new IllegalArgumentException("softDeleteField 格式非法: " + softDeleteField);
            }
            // P2-14: 校验 softDeleteField 是否存在于源表
            List<SourceDataSourceService.ColumnInfo> srcCols;
            try {
                srcCols = sourceDataSourceService.listColumns(
                        task.getSourceDataSourceId(), resolvedSchema, table);
            } catch (Exception e) {
                throw new IllegalStateException("snapshot capture 校验 softDeleteField 失败: " + e.getMessage(), e);
            }
            boolean fieldExists = srcCols.stream()
                    .anyMatch(c -> c.columnName() != null && c.columnName().equalsIgnoreCase(softDeleteField));
            if (!fieldExists) {
                log.warn("SnapshotDelete.capture: softDeleteField '{}' not found in source columns, skipping soft-delete filter",
                        softDeleteField);
                return baseWhere == null ? "" : baseWhere;
            }

            String quotedField = dialectQuoteHelper.quoteColumn(srcType, softDeleteField);
            String deleteSignValue = task.getDeleteSignValue() == null || task.getDeleteSignValue().isBlank()
                    ? "1"
                    : task.getDeleteSignValue().trim();
            String softDeleteCondition = "(" + quotedField + " IS NULL OR " + quotedField + " <> "
                    + SqlLiteralEncoder.encode(deleteSignValue) + ")";
            if (baseWhere == null || baseWhere.isBlank()) {
                return softDeleteCondition;
            }
            return "(" + baseWhere + ") AND " + softDeleteCondition;
        }
        return baseWhere == null ? "" : baseWhere;
    }

    /** 向后兼容：无 srcType 参数时自动解析 */
    String buildCaptureWhere(SyncTask task, String table) {
        return buildCaptureWhere(task, table, resolveSourceDbType(task));
    }

    private boolean isCompleteStreamLoad(StreamLoadResult result, int expectedRows) {
        return result.success()
                && result.numberFilteredRows() == 0
                && result.numberLoadedRows() >= expectedRows;
    }

    private String streamLoadResultMessage(StreamLoadResult result, int expectedRows) {
        return result.status() + " - " + result.message()
                + " (loaded=" + result.numberLoadedRows()
                + ", filtered=" + result.numberFilteredRows()
                + ", expected=" + expectedRows + ")";
    }

    /** 解析源库方言类型（MYSQL / POSTGRESQL / ORACLE / SQLSERVER）。 */
    private String resolveSourceDbType(SyncTask task) {
        SourceDataSourceDto ds = sourceDataSourceService.findById(task.getSourceDataSourceId());
        String type = ds.getType();
        return (type == null || type.isBlank()) ? "MYSQL" : type.toUpperCase();
    }

    /** 从 task.upsertKeys 中取唯一列名，要求恰好一列且字段名安全。 */
    String resolveSinglePk(SyncTask task) {
        List<String> keys = task.getUpsertKeys();
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("snapshot delete 需要 upsertKeys 配置主键列");
        }
        if (keys.size() != 1) {
            throw new IllegalArgumentException("snapshot delete MVP 仅支持单列主键，当前 upsertKeys=" + keys);
        }
        String col = keys.get(0);
        if (!whereClauseBuilder.isFieldNameSafe(col)) {
            throw new IllegalArgumentException("upsertKeys 列名格式非法: " + col);
        }
        return col;
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!quote) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
