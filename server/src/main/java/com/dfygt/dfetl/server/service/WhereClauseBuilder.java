package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalContractSnapshotCodec;
import com.dfygt.dfetl.server.medical.MedicalDatasetContractService;
import com.dfygt.dfetl.server.medical.source.MedicalSourceSelectBuilder;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapterResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 根据源库类型 + 任务配置，安全生成 Reader 的 WHERE 子句（不含 WHERE 关键字）。
 *
 * <p>安全策略：
 * <ul>
 *   <li>incrementalField 白名单：仅允许 [a-zA-Z_][a-zA-Z0-9_]{0,63}</li>
 *   <li>staticFilter 黑名单：拒绝含 SELECT/INSERT/UPDATE/DELETE/DROP/EXEC/UNION/--//* 的值</li>
 *   <li>时间值直接格式化为字面量，不接受用户输入的动态值拼入 SQL</li>
 * </ul>
 */
@Slf4j
@Component
public class WhereClauseBuilder {

    private static final Pattern FIELD_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");
    private static final Pattern WHERE_PREFIX = Pattern.compile("(?i)^\\s*where\\s+");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private static final Set<String> FILTER_BLACKLIST = Set.of(
            "select", "insert", "update", "delete", "drop", "exec", "execute", "union", "--", "/*", ";"
    );

    private final DialectQuoteHelper dialectQuoteHelper;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    @Lazy
    private SourceDataSourceService sourceDataSourceService;

    @Autowired(required = false)
    @Lazy
    private MedicalDatasetContractService medicalContractService;

    @Autowired(required = false)
    @Lazy
    private MedicalSourceSelectBuilder medicalSourceSelectBuilder;

    @Autowired(required = false)
    @Lazy
    private SourceDialectAdapterResolver sourceDialectAdapterResolver;

    public WhereClauseBuilder() {
        this(new DialectQuoteHelper(), new ObjectMapper());
    }

    public WhereClauseBuilder(DialectQuoteHelper dialectQuoteHelper) {
        this(dialectQuoteHelper, new ObjectMapper());
    }

    @Autowired
    public WhereClauseBuilder(DialectQuoteHelper dialectQuoteHelper, ObjectMapper objectMapper) {
        this.dialectQuoteHelper = dialectQuoteHelper;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据 WindowContext 生成最终 WHERE 字符串。
     *
     * @param task    同步任务
     * @param srcType 源库类型（MYSQL / POSTGRESQL / ORACLE / SQLSERVER）
     * @param window  由 WatermarkService 计算的窗口上下文
     * @return WHERE 子句字符串（不含 WHERE 关键字），为空字符串时 reader 全表扫描
     */
    public String build(SyncTask task, String srcType, WatermarkService.WindowContext window) {
        return build(task, srcType, window, defaultTableName(task));
    }

    /**
     * 根据当前源表生成 TABLE_VIEW 的最终 WHERE 字符串。
     *
     * <p>组合顺序固定为：staticFilter -> filterConditionMap[currentTable] -> incremental window。
     */
    public String build(SyncTask task, String srcType, WatermarkService.WindowContext window, String currentTable) {
        if (isCustomSql(task) && hasConfiguredTableFilters(task)) {
            throw new UnsupportedOperationException(
                    "CUSTOM_SQL 模式暂不支持 filterConditionMap 过滤，请将过滤条件写入 customSql 或 staticFilter");
        }
        String staticPart = buildStaticPart(task.getStaticFilter());
        if (isCustomSql(task) && staticPart != null && !staticPart.isBlank()) {
            log.debug("WhereClauseBuilder: CUSTOM_SQL 模式下 staticFilter 将追加到外层 WHERE，"
                    + "请确保引用的列名与子查询输出列名一致（注意大小写）");
        }
        String tablePart = buildTableFilterPart(task, currentTable);
        String dynamicPart = buildDynamicPart(task, srcType, window, currentTable);

        List<String> parts = new ArrayList<>(3);
        addPart(parts, staticPart);
        addPart(parts, tablePart);
        addPart(parts, dynamicPart);
        return joinParts(parts);
    }

    private void addPart(List<String> parts, String part) {
        if (part != null && !part.isBlank()) {
            parts.add(part.trim());
        }
    }

    private String joinParts(List<String> parts) {
        if (parts.isEmpty()) {
            return "";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return parts.stream()
                .map(part -> "(" + part + ")")
                .reduce((left, right) -> left + " AND " + right)
                .orElse("");
    }

    // ── static filter ───────────────────────────────────────────────────────

    private String buildStaticPart(String staticFilter) {
        if (staticFilter == null || staticFilter.isBlank()) {
            return "";
        }
        String filter = stripWherePrefix(staticFilter);
        if (!isStaticFilterSafe(filter)) {
            throw new IllegalArgumentException("staticFilter contains unsafe SQL");
        }
        return filter;
    }

    private String buildTableFilterPart(SyncTask task, String currentTable) {
        if (task == null || isCustomSql(task) || task.getFilterConditionMap() == null
                || task.getFilterConditionMap().isBlank()) {
            return "";
        }
        String table = currentTable;
        if (table == null || table.isBlank()) {
            table = defaultTableName(task);
        }
        if (table == null || table.isBlank()) {
            return "";
        }
        Map<String, String> filterMap = parseFilterConditionMap(task.getFilterConditionMap());
        String filter = filterMap.get(table);
        if (filter == null) {
            for (Map.Entry<String, String> entry : filterMap.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(table)) {
                    filter = entry.getValue();
                    break;
                }
            }
        }
        String stripped = stripWherePrefix(filter);
        if (!stripped.isBlank() && !isStaticFilterSafe(stripped)) {
            throw new IllegalArgumentException(
                    "filterConditionMap for table '" + table + "' contains unsafe SQL");
        }
        return stripped;
    }

    private Map<String, String> parseFilterConditionMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("WhereClauseBuilder: filterConditionMap parse failed: {}", e.getMessage());
            throw new IllegalArgumentException("filterConditionMap 解析失败: " + e.getMessage(), e);
        }
    }

    private boolean hasConfiguredTableFilters(SyncTask task) {
        if (task == null || task.getFilterConditionMap() == null || task.getFilterConditionMap().isBlank()) {
            return false;
        }
        return parseFilterConditionMap(task.getFilterConditionMap()).values().stream()
                .anyMatch(value -> value != null && !value.isBlank());
    }

    private String stripWherePrefix(String condition) {
        if (condition == null || condition.isBlank()) {
            return "";
        }
        return WHERE_PREFIX.matcher(condition.trim()).replaceFirst("").trim();
    }

    private String defaultTableName(SyncTask task) {
        if (task == null || task.getViewNames() == null || task.getViewNames().isEmpty()) {
            return null;
        }
        return task.getViewNames().get(0);
    }

    private boolean isCustomSql(SyncTask task) {
        return task.getSourceMode() != null && "CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode());
    }

    // ── dynamic window ──────────────────────────────────────────────────────

    private String buildDynamicPart(
            SyncTask task,
            String srcType,
            WatermarkService.WindowContext window,
            String currentTable) {
        if (window == null || window.windowType() == null) {
            return "";
        }
        return switch (window.windowType()) {
            case "FULL" -> "";
            case "INCREMENT" -> buildIncrementCondition(task, srcType, window, currentTable);
            case "CUSTOM_WINDOW" -> buildIncrementCondition(task, srcType, window, currentTable);
            default -> "";
        };
    }

    private String buildIncrementCondition(
            SyncTask task,
            String srcType,
            WatermarkService.WindowContext window,
            String currentTable) {
        String field = task.getIncrementalField();
        String schema = task.getSourceSchema();
        String table = currentTable;
        if (table == null || table.isBlank()) {
            table = defaultTableName(task);
        }

        // 映射回 JDBC 原始大小写（CUSTOM_SQL 模式下跳过，因为 viewNames 是逻辑名而非物理表名）
        if (!isCustomSql(task) && sourceDataSourceService != null && task.getSourceDataSourceId() != null
                && task.getViewNames() != null && !task.getViewNames().isEmpty()) {
            try {
                var source = sourceDataSourceService.findById(task.getSourceDataSourceId());
                schema = SourceSchemaResolver.resolveRequired(task, source);
            } catch (Exception e) {
                log.warn("WhereClauseBuilder: resolve source schema failed taskId={} datasourceId={}: {}",
                        task.getId(), task.getSourceDataSourceId(), e.getMessage());
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalArgumentException("无法解析源端 schema: " + e.getMessage(), e);
            }
            field = sourceDataSourceService.resolveOriginalColumnName(
                    task.getSourceDataSourceId(), schema, table, field);
        }

        String mode = task.getIncrementMode();

        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException(
                    "incrementalField is required for scoped incremental window");
        }
        if (!FIELD_PATTERN.matcher(field).matches()) {
            throw new IllegalArgumentException(
                    "incrementalField is unsafe: " + field);
        }

        if ("ID_RANGE".equals(mode)) {
            return buildIdRangeCondition(field, srcType, window);
        }
        // 默认 TIME_FIELD
        String timeExpression = medicalTimeFieldExpression(task, srcType, schema, table, field);
        if (timeExpression == null) {
            timeExpression = quoteField(field, srcType);
        }
        return buildTimeCondition(timeExpression, srcType, window);
    }

    private String buildTimeCondition(String expression, String srcType, WatermarkService.WindowContext window) {
        StringBuilder sb = new StringBuilder();

        if (window.windowStart() != null) {
            String wsStr = TS_FMT.format(window.windowStart());
            sb.append(buildTimeGe(expression, wsStr, srcType));
        }
        if (window.windowEnd() != null) {
            String weStr = TS_FMT.format(window.windowEnd());
            if (sb.length() > 0) sb.append(" AND ");
            sb.append(buildTimeLt(expression, weStr, srcType));
        }
        return sb.toString();
    }

    private String buildIdRangeCondition(String field, String srcType, WatermarkService.WindowContext window) {
        String quotedField = quoteField(field, srcType);
        StringBuilder sb = new StringBuilder();
        if (window.windowStartId() != null) {
            sb.append(quotedField).append(" > ").append(window.windowStartId());
        }
        if (window.windowEndId() != null) {
            if (sb.length() > 0) sb.append(" AND ");
            sb.append(quotedField).append(" <= ").append(window.windowEndId());
        }
        return sb.toString();
    }

    // ── dialect helpers ─────────────────────────────────────────────────────

    private String quoteField(String field, String srcType) {
        return dialectQuoteHelper.quoteColumn(srcType, field);
    }

    private String buildTimeGe(String quotedField, String tsStr, String srcType) {
        return switch (srcType.toUpperCase()) {
            case "ORACLE" ->
                quotedField + " >= TO_TIMESTAMP('" + tsStr + "','YYYY-MM-DD HH24:MI:SS')";
            default ->
                quotedField + " >= '" + tsStr + "'";
        };
    }

    private String buildTimeLt(String quotedField, String tsStr, String srcType) {
        return switch (srcType.toUpperCase()) {
            case "ORACLE" ->
                quotedField + " < TO_TIMESTAMP('" + tsStr + "','YYYY-MM-DD HH24:MI:SS')";
            default ->
                quotedField + " < '" + tsStr + "'";
        };
    }

    private String medicalTimeFieldExpression(
            SyncTask task,
            String srcType,
            String schema,
            String table,
            String field) {
        MedicalWhereOptions medical = medicalWhereOptions(task);
        if (medical.datasetCode() == null) {
            return null;
        }
        if (isCustomSql(task)) {
            return null;
        }
        if (medicalContractService == null || medicalSourceSelectBuilder == null
                || sourceDialectAdapterResolver == null || sourceDataSourceService == null) {
            throw new IllegalStateException("医共体 contract-driven 任务缺少 WHERE 表达式构建依赖");
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("医共体增量窗口缺少源对象");
        }
        MedicalDatasetContract contract = MedicalContractSnapshotCodec.resolveForTask(
                task, medicalContractService, objectMapper);
        SourceDialectAdapter adapter = sourceDialectAdapterResolver.resolve(srcType, medical.compatibilityMode());
        return medicalSourceSelectBuilder.buildFieldExpression(
                contract,
                field,
                sourceDataSourceService.listColumns(task.getSourceDataSourceId(), schema, table),
                adapter,
                medical.fieldMapping());
    }

    private MedicalWhereOptions medicalWhereOptions(SyncTask task) {
        String dc = task == null ? null : task.getDataCharacteristics();
        if (dc == null || dc.isBlank()) {
            return MedicalWhereOptions.empty();
        }
        try {
            Map<String, Object> values = objectMapper.readValue(dc, new TypeReference<Map<String, Object>>() {});
            Object mode = values.get("medicalMappingMode");
            boolean contractDriven = mode != null && "CONTRACT_DRIVEN".equalsIgnoreCase(mode.toString());
            if (!contractDriven) {
                return MedicalWhereOptions.empty();
            }
            Object datasetCode = values.get("matchedDatasetCode");
            if (datasetCode == null || datasetCode.toString().isBlank()) {
                throw new IllegalStateException("医共体 contract-driven 任务缺少 matchedDatasetCode");
            }
            Object compatibilityMode = values.get("compatibilityMode");
            return new MedicalWhereOptions(
                    datasetCode.toString().trim().toUpperCase(Locale.ROOT),
                    compatibilityMode == null ? null : compatibilityMode.toString().trim(),
                    parseStringMap(values.get("fieldMapping")));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (dc.contains("CONTRACT_DRIVEN")) {
                throw new IllegalStateException("医共体 contract-driven 任务 dataCharacteristics 不是合法 JSON: "
                        + e.getMessage(), e);
            }
            return MedicalWhereOptions.empty();
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

    private record MedicalWhereOptions(
            String datasetCode,
            String compatibilityMode,
            Map<String, String> fieldMapping) {

        static MedicalWhereOptions empty() {
            return new MedicalWhereOptions(null, null, Map.of());
        }
    }

    // ── 公开：staticFilter 合法性校验（供 SyncTaskService 保存前调用）──────────

    /**
     * 检查 staticFilter 是否包含危险关键字。
     *
     * @return true 表示合法，false 表示包含危险内容
     */
    public boolean isStaticFilterSafe(String staticFilter) {
        if (staticFilter == null || staticFilter.isBlank()) {
            return true;
        }
        // NFKC 归一化：把全角/半角、Unicode 同形字符（如西里尔 ѕ → s）规范化，
        // 防止 ｓelect / ѕelect 等同形字符绕过黑名单关键字匹配。
        String normalized = java.text.Normalizer.normalize(staticFilter, java.text.Normalizer.Form.NFKC);
        String lower = stripWherePrefix(normalized).toLowerCase();
        for (String keyword : FILTER_BLACKLIST) {
            if (containsBlacklistedKeyword(lower, keyword)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查 filterConditionMap 是否可解析且每个表级过滤条件都满足 staticFilter 等价安全规则。
     */
    public boolean isFilterConditionMapSafe(String filterConditionMap) {
        if (filterConditionMap == null || filterConditionMap.isBlank()) {
            return true;
        }
        try {
            Map<String, String> filters = parseFilterConditionMap(filterConditionMap);
            for (Map.Entry<String, String> entry : filters.entrySet()) {
                String condition = stripWherePrefix(entry.getValue());
                if (!condition.isBlank() && !isStaticFilterSafe(condition)) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean containsBlacklistedKeyword(String lowerFilter, String keyword) {
        if (lowerFilter == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        if (keyword.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch) && ch != '_')) {
            return lowerFilter.contains(keyword);
        }
        Pattern pattern = Pattern.compile("(?i)(^|[^a-z0-9_])" + Pattern.quote(keyword) + "([^a-z0-9_]|$)");
        return pattern.matcher(lowerFilter).find();
    }

    /**
     * 检查增量字段名是否满足白名单。
     */
    public boolean isFieldNameSafe(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return true;
        }
        return FIELD_PATTERN.matcher(fieldName).matches();
    }
}
