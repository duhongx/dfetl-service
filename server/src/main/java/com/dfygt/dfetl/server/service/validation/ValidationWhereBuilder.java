package com.dfygt.dfetl.server.service.validation;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.service.CustomSqlQueryBuilder;
import com.dfygt.dfetl.server.service.DialectQuoteHelper;
import com.dfygt.dfetl.server.service.SourceDataSourceService;
import com.dfygt.dfetl.server.service.TargetFieldResolver;
import com.dfygt.dfetl.server.service.ValidationSourceFilterBuilder;
import com.dfygt.dfetl.server.service.WatermarkService;
import com.dfygt.dfetl.server.service.WhereClauseBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 校验 WHERE 条件构建器。
 *
 * <p>从 {@link com.dfygt.dfetl.server.service.ValidationRunner} 提取的 WHERE 条件构建逻辑，
 * 包含 buildSourceWhere（4 个重载）、buildCustomSqlCountSql（3 个重载）、
 * buildTargetWindowWhere 和 appendDorisDeleteSignFilter 方法。
 */
@Component
@RequiredArgsConstructor
public class ValidationWhereBuilder {

    private final WhereClauseBuilder whereClauseBuilder;
    private final DialectQuoteHelper dialectQuoteHelper;
    private final SourceDataSourceService sourceDataSourceService;
    private final TargetFieldResolver targetFieldResolver;
    private final com.dfygt.dfetl.server.service.EtlSystemFieldsService etlSystemFieldsService;

    // ── buildSourceWhere 系列 ─────────────────────────────────────────────────

    /** 基础 WHERE：接受 WindowContext 参数 */
    public String buildSourceWhere(WatermarkService.WindowContext window, SyncTask task,
                                   String dialect, String sourceTable) {
        return whereClauseBuilder.build(task, dialect, window, sourceTable);
    }

    /** 含列级过滤的 WHERE（WindowContext 参数） */
    public String buildSourceWhere(WatermarkService.WindowContext window, SyncTask task, String dialect,
                                   String sourceTable, List<String> sourceColumns) {
        return ValidationSourceFilterBuilder.buildEffectiveSourceWhere(
                task,
                dialect,
                whereClauseBuilder.build(task, dialect, window, sourceTable),
                sourceColumns,
                whereClauseBuilder,
                dialectQuoteHelper);
    }

    // ── buildCustomSqlCountSql 系列 ──────────────────────────────────────────

    /** CUSTOM_SQL 模式的 COUNT SQL（WindowContext 参数） */
    public String buildCustomSqlCountSql(WatermarkService.WindowContext window, SyncTask task, String dialect) {
        return buildCustomSqlCountSql(window, task, dialect, List.of());
    }

    /** CUSTOM_SQL 模式的 COUNT SQL（含列过滤） */
    public String buildCustomSqlCountSql(WatermarkService.WindowContext window, SyncTask task, String dialect,
                                         List<String> sourceColumns) {
        if (!isCustomSql(task)) {
            throw new IllegalArgumentException("仅 CUSTOM_SQL 模式可构造自定义 SQL count");
        }
        String where = ValidationSourceFilterBuilder.buildEffectiveSourceWhere(
                task,
                dialect,
                whereClauseBuilder.build(task, dialect, window),
                sourceColumns,
                whereClauseBuilder,
                dialectQuoteHelper);
        return CustomSqlQueryBuilder.countSql(dialect, task.getCustomSql(), where);
    }

    // ── buildTargetWindowWhere ────────────────────────────────────────────────

    /** 目标端窗口 WHERE（含 ID_RANGE / TIME_FIELD 分支）。
     *  ID_RANGE 分支使用 windowStartId/windowEndId 构建 WHERE 条件，
     *  由 ValidationRunner.validationWindow() 从 ValidationRun 的 windowStartId/windowEndId 字段填充。
     */
    public String buildTargetWindowWhere(WatermarkService.WindowContext window, SyncTask task,
                                         String sourceTable, String dialect) {
        if (window == null || (!"INCREMENT".equals(window.windowType())
                && !"CUSTOM_WINDOW".equals(window.windowType()))) {
            return "";
        }
        String field = task.getIncrementalField();
        if (field == null || field.isBlank() || !field.matches("[\\w$#]+")) return "";
        String d = dialect == null ? "" : dialect.toUpperCase();
        String targetField;
        if ("DORIS".equals(d)) {
            targetField = targetFieldResolver.resolveTargetColumnSameNameOnly(
                    task, sourceTable, field, "ROW_COUNT 目标端窗口校验");
        } else {
            // 源端查询：映射回 JDBC 原始大小写
            targetField = resolveSourceIncrementalField(task, field);
        }
        String f = dialectQuoteHelper.quoteColumn(d, targetField);
        if ("ID_RANGE".equalsIgnoreCase(task.getIncrementMode())) {
            if (window.windowEndId() == null) {
                throw new IllegalStateException(
                        "ID_RANGE 校验缺少 windowStartId/windowEndId，无法与 execution window 对齐");
            }
            StringBuilder idWhere = new StringBuilder();
            if (window.windowStartId() != null) {
                idWhere.append(f).append(" > ").append(window.windowStartId()).append(" AND ");
            }
            idWhere.append(f).append(" <= ").append(window.windowEndId());
            return idWhere.toString();
        }
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.of("Asia/Shanghai"));
        String startStr = window.windowStart() != null ? fmt.format(window.windowStart()) : null;
        String endStr   = window.windowEnd() != null ? fmt.format(window.windowEnd()) : null;
        StringBuilder sb = new StringBuilder();
        if ("ORACLE".equals(d)) {
            if (startStr != null) {
                sb.append(f).append(" >= TO_TIMESTAMP('").append(startStr).append("','YYYY-MM-DD HH24:MI:SS')");
            }
            if (endStr != null) {
                if (sb.length() > 0) sb.append(" AND ");
                sb.append(f).append(" < TO_TIMESTAMP('").append(endStr).append("','YYYY-MM-DD HH24:MI:SS')");
            }
        } else {
            if (startStr != null) {
                sb.append(f).append(" >= '").append(startStr).append("'");
            }
            if (endStr != null) {
                if (sb.length() > 0) sb.append(" AND ");
                sb.append(f).append(" < '").append(endStr).append("'");
            }
        }
        return sb.toString();
    }

    // ── appendDorisDeleteSignFilter ──────────────────────────────────────────

    /**
     * spec 069：ETL 任务 ID 列名（Doris 目标列，全小写）。与 EtlSystemFieldsService 定义一致。
     */
    public static final String ETL_JOB_ID_COL = "_etl_job_id";

    /**
     * spec 069：多机构共表校验目标侧任务范围过滤。
     * <p>
     * 多个机构的同步任务写入同一张 Doris 汇聚表时，校验目标端 {@code COUNT(*)} 会数到所有机构的行，
     * 与源端（单机构库）口径不一致，必然假报 DIFF。此方法在目标端 WHERE 追加
     * {@code `_etl_job_id` = {taskId}}，把目标端收敛到"本任务写入的行"，与源端对齐。
     * <p>
     * 仅当 {@code _etl_job_id} 系统字段启用时才追加（D3 强制启用，但防御性检查以兼容
     * 历史上未启用 ETL 字段、目标表无此列的任务——此时不追加，避免查询报"列不存在"）。
     * {@code _etl_job_id} 为 BIGINT，值即 task.id，数值字面量不加引号。
     *
     * @param task     同步任务（提供 id）
     * @param tgtWhere 目标端已有 WHERE（窗口 + delete_sign）
     * @return 追加任务范围过滤后的 WHERE
     */
    public String appendTenantScopeFilter(SyncTask task, String tgtWhere) {
        if (!isTenantScopeFilterActive(task)) {
            return tgtWhere;
        }
        String scopeFilter = "`" + ETL_JOB_ID_COL + "` = " + task.getId();
        if (tgtWhere == null || tgtWhere.isEmpty()) {
            return scopeFilter;
        }
        return "(" + tgtWhere + ") AND " + scopeFilter;
    }

    /**
     * spec 069：判断目标侧任务范围过滤是否生效。
     * <p>
     * 仅当 {@code task.id} 非空且 {@code _etl_job_id} 系统字段启用（即目标表确有此列）时返回 true。
     * 与 {@link #appendTenantScopeFilter} 的追加条件一致，供校验链路判断是否需要做
     * 「存量 NULL 行口径告警」探测（见 spec §6.3 / §7.1 / 验收点4）。
     */
    public boolean isTenantScopeFilterActive(SyncTask task) {
        if (task == null || task.getId() == null) {
            return false;
        }
        return etlSystemFieldsService.enabledFields().containsKey(ETL_JOB_ID_COL);
    }

    /**
     * spec 069：目标端「存量 NULL 行」探测谓词。
     * <p>
     * 目标表补 {@code _etl_job_id} 列后，多机构上线前写入的历史旧行该列为 NULL，
     * 任务范围过滤 {@code `_etl_job_id` = {taskId}} 会漏掉它们，可能假报「目标少行」。
     * 校验链路用本谓词 {@code COUNT(*)} 探测 NULL 行数，>0 时给出非阻塞口径告警，引导清空重采。
     */
    public static final String ETL_JOB_ID_NULL_PREDICATE = "`" + ETL_JOB_ID_COL + "` IS NULL";

    /**
     * Doris MERGE 软删除时，目标侧 WHERE 追加 {@code `__doris_delete_sign__` = 0}，
     * 排除 MOW 模式下标记为已删除但物理仍存在的行，避免行数/checksum 不一致。
     */
    public static String appendDorisDeleteSignFilter(SyncTask task, String tgtWhere) {
        if (Boolean.TRUE.equals(task.getEnableDorisMerge())
                && task.getSoftDeleteField() != null
                && !task.getSoftDeleteField().isBlank()) {
            String deleteSignFilter = "`__doris_delete_sign__` = 0";
            if (tgtWhere == null || tgtWhere.isEmpty()) {
                return deleteSignFilter;
            } else {
                return "(" + tgtWhere + ") AND " + deleteSignFilter;
            }
        }
        return tgtWhere;
    }

    // ── 内部辅助方法 ─────────────────────────────────────────────────────────

    private boolean isCustomSql(SyncTask task) {
        return task != null && "CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode());
    }

    /** 将 incrementalField 映射回 JDBC 原始大小写（源端查询用） */
    private String resolveSourceIncrementalField(SyncTask task, String field) {
        if (task.getSourceDataSourceId() == null
                || task.getViewNames() == null || task.getViewNames().isEmpty()) {
            return field;
        }
        String schema = task.getSourceSchema();
        String table = task.getViewNames().get(0);
        return sourceDataSourceService.resolveOriginalColumnName(
                task.getSourceDataSourceId(), schema, table, field);
    }
}
