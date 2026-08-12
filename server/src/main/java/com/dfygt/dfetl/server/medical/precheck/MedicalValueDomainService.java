package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;
import com.dfygt.dfetl.server.medical.MedicalRegistryConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 医共体值域读取服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MedicalValueDomainService {

    private static final String VALUE_DOMAIN_TABLE = "jy_wssy_zhiyu";
    private static final String VALUE_DOMAIN_CODE_TABLE = "jy_wssy_zhiyubm_mapping";
    private static final int STRICT_BLOCK_CODE_LIMIT = 1_000;
    private static final int ACTUAL_INVALID_CODE_LIMIT = 1_000;
    private static final int ACTUAL_DISTINCT_SCAN_LIMIT = 50_000;
    private static final int ACTUAL_CODE_REGISTRY_BATCH_SIZE = 500;

    private final MedicalRegistryConfig registryConfig;

    public Map<String, Set<String>> allowedCodesByField(String datasetCode) {
        Map<String, MedicalValueDomainRule> rules = rulesByField(datasetCode);
        if (rules.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (MedicalValueDomainRule rule : rules.values()) {
            if (rule.strictBlock()) {
                result.put(normalize(rule.fieldCode()), rule.allowedCodes());
            }
        }
        return result;
    }

    public Map<String, MedicalValueDomainRule> rulesByField(String datasetCode) {
        return rulesByField(datasetCode, List.of());
    }

    public Map<String, MedicalValueDomainRule> rulesByField(
            String datasetCode,
            List<MedicalDatasetFieldOverride> fieldOverrides) {
        if (datasetCode == null || datasetCode.isBlank()
                || !registryConfig.isEnabled() || !registryConfig.isConfigured()) {
            return Map.of();
        }
        try (Connection connection = registryConfig.openConnection()) {
            Map<String, String> domainByField = fieldDomains(connection, datasetCode);
            Map<String, ValueDomainOverride> overrideByField = valueDomainOverrides(fieldOverrides);
            applyValueDomainOverrides(domainByField, overrideByField);
            if (domainByField.isEmpty() && overrideByField.isEmpty()) {
                return Map.of();
            }
            ValueDomainColumns columns = inspectValueDomainColumns(connection);
            if (columns == null) {
                log.warn("[MedicalValueDomain] 无法识别值域编码表列，按告警记录 datasetCode={}", datasetCode);
                return unresolvedColumnRules(domainByField, overrideByField);
            }
            Map<String, MedicalValueDomainRule> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : domainByField.entrySet()) {
                String fieldCode = normalize(entry.getKey());
                String domainId = entry.getValue();
                ValueDomainOverride override = overrideByField.get(fieldCode);
                if (override != null && override.mode() == MedicalValueDomainCheckMode.DISABLED) {
                    continue;
                }
                MedicalValueDomainRule rule = buildRule(connection, columns, fieldCode, domainId, override);
                if (rule != null) {
                    result.put(fieldCode, rule);
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("读取医共体值域失败: " + e.getMessage(), e);
        }
    }

    private static Map<String, MedicalValueDomainRule> unresolvedColumnRules(
            Map<String, String> domainByField,
            Map<String, ValueDomainOverride> overrideByField) {
        if (domainByField == null || domainByField.isEmpty()) {
            return Map.of();
        }
        Map<String, MedicalValueDomainRule> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : domainByField.entrySet()) {
            String fieldCode = normalize(entry.getKey());
            String domainId = blankToNull(entry.getValue());
            if (fieldCode.isBlank() || domainId == null) {
                continue;
            }
            ValueDomainOverride override = overrideByField == null ? null : overrideByField.get(fieldCode);
            String overrideReason = override == null
                    ? ""
                    : "；dfetl_field 覆盖"
                    + (override.source() == null ? "" : " source=" + override.source())
                    + (override.version() == null ? "" : " version=" + override.version());
            result.put(fieldCode, new MedicalValueDomainRule(
                    fieldCode,
                    domainId,
                    MedicalValueDomainCheckMode.WARN_ONLY,
                    0,
                    Set.of(),
                    "值域编码表列无法识别，先记录警告，不静默通过" + overrideReason));
        }
        return result;
    }

    private MedicalValueDomainRule buildRule(
            Connection connection,
            ValueDomainColumns columns,
            String fieldCode,
            String domainId,
            ValueDomainOverride override) throws SQLException {
        int codeCount = allowedCodeCount(connection, columns, domainId);
        MedicalValueDomainCheckMode configuredMode = override == null ? null : override.mode();
        String source = override == null ? null : override.source();
        String version = override == null ? null : override.version();
        String overrideReason = override == null
                ? ""
                : "；dfetl_field 覆盖"
                + (source == null ? "" : " source=" + source)
                + (version == null ? "" : " version=" + version);
        if (codeCount <= 0) {
            return new MedicalValueDomainRule(
                    fieldCode,
                    domainId,
                    MedicalValueDomainCheckMode.WARN_ONLY,
                    0,
                    Set.of(),
                    "值域编码未查到明细，先记录警告，不静默通过" + overrideReason);
        }
        if (configuredMode == MedicalValueDomainCheckMode.WARN_ONLY
                || configuredMode == MedicalValueDomainCheckMode.FORMAT_ONLY) {
            return new MedicalValueDomainRule(
                    fieldCode,
                    domainId,
                    MedicalValueDomainCheckMode.WARN_ONLY,
                    codeCount,
                    Set.of(),
                    "dfetl_field 指定 " + configuredMode + "，值域不作为写入阻断" + overrideReason);
        }
        if (configuredMode == MedicalValueDomainCheckMode.ACTUAL_INVALID_BLOCK) {
            return new MedicalValueDomainRule(
                    fieldCode,
                    domainId,
                    MedicalValueDomainCheckMode.ACTUAL_DISTINCT_CHECK,
                    codeCount,
                    Set.of(),
                    "dfetl_field 指定 ACTUAL_INVALID_BLOCK，按源端当前窗口实际非法编码阻断" + overrideReason);
        }
        if (codeCount <= STRICT_BLOCK_CODE_LIMIT) {
            Set<String> codes = allowedCodes(connection, columns, domainId);
            if (!codes.isEmpty()) {
                return new MedicalValueDomainRule(
                        fieldCode,
                        domainId,
                        MedicalValueDomainCheckMode.STRICT_BLOCK,
                        codes.size(),
                        codes,
                        "小值域，执行 SQL 阻断校验" + overrideReason);
            }
        }
        return new MedicalValueDomainRule(
                fieldCode,
                domainId,
                MedicalValueDomainCheckMode.ACTUAL_DISTINCT_CHECK,
                codeCount,
                Set.of(),
                "大值域编码数量 " + codeCount + " 超过 " + STRICT_BLOCK_CODE_LIMIT
                        + "，不拼接巨大 IN，按当前窗口 distinct 与值域明细比对" + overrideReason);
    }

    private static Map<String, ValueDomainOverride> valueDomainOverrides(
            List<MedicalDatasetFieldOverride> fieldOverrides) {
        if (fieldOverrides == null || fieldOverrides.isEmpty()) {
            return Map.of();
        }
        Map<String, ValueDomainOverride> result = new LinkedHashMap<>();
        for (MedicalDatasetFieldOverride override : fieldOverrides) {
            String fieldCode = normalize(override == null ? null : override.fieldCode());
            if (fieldCode.isBlank()) {
                continue;
            }
            String domainId = blankToNull(override.valueDomainCode());
            MedicalValueDomainCheckMode mode = parseMode(override.valueDomainMode());
            String source = blankToNull(override.valueDomainSource());
            String version = blankToNull(override.valueDomainVersion());
            if (domainId != null || mode != null || source != null || version != null) {
                result.put(fieldCode, new ValueDomainOverride(domainId, mode, source, version));
            }
        }
        return result;
    }

    private static void applyValueDomainOverrides(
            Map<String, String> domainByField,
            Map<String, ValueDomainOverride> overrideByField) {
        if (overrideByField == null || overrideByField.isEmpty()) {
            return;
        }
        for (Map.Entry<String, ValueDomainOverride> entry : overrideByField.entrySet()) {
            String fieldCode = entry.getKey();
            ValueDomainOverride override = entry.getValue();
            if (override.mode() == MedicalValueDomainCheckMode.DISABLED) {
                domainByField.remove(fieldCode);
            } else if (override.domainId() != null) {
                domainByField.put(fieldCode, override.domainId());
            }
        }
    }

    public Map<String, MedicalValueDomainRule> resolveActualInvalidBlocks(
            Connection sourceConnection,
            SourceDialectAdapter adapter,
            String sourceSchema,
            String sourceObject,
            MedicalDatasetContract contract,
            List<ColumnInfo> sourceColumns,
            Map<String, String> fieldMapping,
            String baseWhere,
            Map<String, MedicalValueDomainRule> rulesByField) {
        if (rulesByField == null || rulesByField.isEmpty()) {
            return Map.of();
        }
        boolean hasActualDistinctCheck = rulesByField.values().stream()
                .anyMatch(rule -> rule != null && rule.mode() == MedicalValueDomainCheckMode.ACTUAL_DISTINCT_CHECK);
        if (!hasActualDistinctCheck) {
            return rulesByField;
        }
        if (sourceConnection == null) {
            throw new IllegalArgumentException("sourceConnection 不能为空");
        }
        if (adapter == null) {
            throw new IllegalArgumentException("源库方言适配器不能为空");
        }
        Map<String, String> resolvedColumns = resolvedColumns(contract, sourceColumns, fieldMapping);
        Map<String, MedicalValueDomainRule> resolved = new LinkedHashMap<>(rulesByField);
        try (Connection registryConnection = registryConfig.openConnection()) {
            ValueDomainColumns valueDomainColumns = inspectValueDomainColumns(registryConnection);
            if (valueDomainColumns == null) {
                return rulesByField;
            }
            String source = qualifySource(sourceSchema, sourceObject, adapter);
            String predicate = basePredicate(baseWhere);
            for (Map.Entry<String, MedicalValueDomainRule> entry : rulesByField.entrySet()) {
                MedicalValueDomainRule rule = entry.getValue();
                if (rule == null || rule.mode() != MedicalValueDomainCheckMode.ACTUAL_DISTINCT_CHECK) {
                    continue;
                }
                String fieldCode = normalize(rule.fieldCode());
                String sourceColumn = resolvedColumns.get(fieldCode);
                if (sourceColumn == null || sourceColumn.isBlank()) {
                    continue;
                }
                Set<String> actualCodes = actualDistinctCodes(
                        sourceConnection, adapter, source, predicate, sourceColumn);
                if (actualCodes.isEmpty()) {
                    continue;
                }
                Set<String> allowedCodes = allowedCodesForActualCodes(
                        registryConnection,
                        valueDomainColumns,
                        rule.domainId(),
                        actualCodes);
                Set<String> invalidCodes = actualCodes.stream()
                        .filter(code -> !allowedCodes.contains(code))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                if (invalidCodes.size() > ACTUAL_INVALID_CODE_LIMIT) {
                    throw new IllegalStateException("大值域实际非法编码数量超过限制: field=" + rule.fieldCode()
                            + ", domainId=" + rule.domainId()
                            + ", invalidCount=" + invalidCodes.size()
                            + ", limit=" + ACTUAL_INVALID_CODE_LIMIT);
                }
                if (!invalidCodes.isEmpty()) {
                    resolved.put(fieldCode, new MedicalValueDomainRule(
                            rule.fieldCode(),
                            rule.domainId(),
                            MedicalValueDomainCheckMode.ACTUAL_INVALID_BLOCK,
                            rule.allowedCodeCount(),
                            invalidCodes,
                            "大值域，按源端当前窗口实际非法编码阻断，invalidCount=" + invalidCodes.size()));
                }
            }
            return resolved;
        } catch (SQLException e) {
            throw new IllegalStateException("解析医共体大值域实际非法编码失败: " + e.getMessage(), e);
        }
    }

    private Map<String, String> fieldDomains(Connection connection, String datasetCode) throws SQLException {
        String datasetTable = sanitize(registryConfig.getDatasetTable());
        String fieldTable = sanitize(registryConfig.getFieldTable());
        String dataItemTable = sanitize(registryConfig.getDataItemTable());
        String sql = "SELECT COALESCE(NULLIF(x.shujuxbsf, ''), NULLIF(f.ziduandm, '')) AS field_code, "
                + "COALESCE(NULLIF(CAST(f.zhiyuid AS CHAR), ''), NULLIF(CAST(x.zhiyuid AS CHAR), '')) AS zhiyuid "
                + "FROM " + datasetTable + " d "
                + "JOIN " + fieldTable + " f ON f.shujujid=d.shujujid AND f.zuofeibz=0 "
                + "LEFT JOIN " + dataItemTable + " x ON x.shujuxid=f.shujuxid AND x.zuofeibz=0 "
                + "WHERE d.zuofeibz=0 AND UPPER(d.shujujdm)=UPPER('" + escapeLiteral(datasetCode) + "') "
                + "AND COALESCE(NULLIF(CAST(f.zhiyuid AS CHAR), ''), NULLIF(CAST(x.zhiyuid AS CHAR), '')) IS NOT NULL";
        Map<String, String> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String fieldCode = rs.getString("field_code");
                String valueDomainId = rs.getString("zhiyuid");
                if (fieldCode != null && !fieldCode.isBlank()
                        && valueDomainId != null && !valueDomainId.isBlank()) {
                    result.put(normalize(fieldCode), valueDomainId.trim());
                }
            }
        }
        return result;
    }

    private ValueDomainColumns inspectValueDomainColumns(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + VALUE_DOMAIN_CODE_TABLE + " LIMIT 1")) {
            ResultSetMetaData metaData = rs.getMetaData();
            Set<String> columns = new LinkedHashSet<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                columns.add(normalize(metaData.getColumnName(i)));
            }
            String mappingDomainIdColumn = firstExisting(columns, "ZHIYUID", "ZHIJIBSF", "ZHIYUBSF", "VALUE_DOMAIN_ID");
            String codeColumn = firstExisting(columns,
                    "BM", "DAIMA", "DAIMACODE", "DAIMA_CODE", "CODE",
                    "ZHIYUBM", "BMDM", "BIANMA", "ZHI");
            String typeColumn = firstExisting(columns, "TYPE", "LEIXING");
            ValueDomainMainColumns mainColumns = inspectValueDomainMainColumns(connection, mappingDomainIdColumn);
            if (mappingDomainIdColumn == null || mainColumns == null || codeColumn == null) {
                return null;
            }
            return new ValueDomainColumns(mappingDomainIdColumn.toLowerCase(Locale.ROOT),
                    mainColumns.domainIdColumn(),
                    mainColumns.statusColumn(),
                    codeColumn.toLowerCase(Locale.ROOT),
                    typeColumn == null ? null : typeColumn.toLowerCase(Locale.ROOT));
        }
    }

    private ValueDomainMainColumns inspectValueDomainMainColumns(
            Connection connection,
            String preferredDomainIdColumn) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM " + VALUE_DOMAIN_TABLE + " LIMIT 1")) {
            ResultSetMetaData metaData = rs.getMetaData();
            Set<String> columns = new LinkedHashSet<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                columns.add(normalize(metaData.getColumnName(i)));
            }
            String domainIdColumn = null;
            if (preferredDomainIdColumn != null && columns.contains(normalize(preferredDomainIdColumn))) {
                domainIdColumn = preferredDomainIdColumn;
            }
            if (domainIdColumn == null) {
                domainIdColumn = firstExisting(columns, "ZHIYUID", "ZHIJIBSF", "ZHIYUBSF", "VALUE_DOMAIN_ID");
            }
            String statusColumn = firstExisting(columns, "STATUS", "ZHUANGTAI");
            if (domainIdColumn == null) {
                return null;
            }
            return new ValueDomainMainColumns(
                    domainIdColumn.toLowerCase(Locale.ROOT),
                    statusColumn == null ? null : statusColumn.toLowerCase(Locale.ROOT));
        }
    }

    private Set<String> allowedCodes(
            Connection connection,
            ValueDomainColumns columns,
            String valueDomainId) throws SQLException {
        String sql = "SELECT DISTINCT CAST(m." + columns.codeColumn() + " AS CHAR) AS code_value "
                + valueDomainFromWhere(columns, valueDomainId);
        Set<String> result = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String code = rs.getString("code_value");
                if (code != null && !code.isBlank()) {
                    result.add(code.trim());
                }
            }
        }
        return result;
    }

    private Set<String> allowedCodesForActualCodes(
            Connection connection,
            ValueDomainColumns columns,
            String valueDomainId,
            Set<String> actualCodes) throws SQLException {
        if (actualCodes == null || actualCodes.isEmpty()) {
            return Set.of();
        }
        List<String> values = new ArrayList<>(actualCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .toList());
        if (values.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (int start = 0; start < values.size(); start += ACTUAL_CODE_REGISTRY_BATCH_SIZE) {
            int end = Math.min(values.size(), start + ACTUAL_CODE_REGISTRY_BATCH_SIZE);
            List<String> batch = values.subList(start, end);
            String sql = "SELECT DISTINCT CAST(m." + columns.codeColumn() + " AS CHAR) AS code_value "
                    + valueDomainFromWhere(columns, valueDomainId)
                    + " AND CAST(m." + columns.codeColumn() + " AS CHAR) IN (" + sqlLiterals(batch) + ")";
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    String code = rs.getString("code_value");
                    if (code != null && !code.isBlank()) {
                        result.add(code.trim());
                    }
                }
            }
        }
        return result;
    }

    private Set<String> actualDistinctCodes(
            Connection connection,
            SourceDialectAdapter adapter,
            String source,
            String basePredicate,
            String sourceColumn) throws SQLException {
        String expression = "src." + adapter.quoteIdentifier(sourceColumn);
        String normalized = adapter.trim(adapter.castToText(expression));
        String sql = "SELECT DISTINCT " + normalized + " AS code_value "
                + "FROM " + source + " src WHERE " + basePredicate
                + " AND NOT (" + adapter.isBlank(expression) + ") "
                + "LIMIT " + (ACTUAL_DISTINCT_SCAN_LIMIT + 1);
        Set<String> result = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String code = rs.getString("code_value");
                if (code != null && !code.isBlank()) {
                    result.add(code.trim());
                    if (result.size() > ACTUAL_DISTINCT_SCAN_LIMIT) {
                        throw new IllegalStateException("大值域源端实际 distinct 编码数量超过扫描限制: column="
                                + sourceColumn + ", limit=" + ACTUAL_DISTINCT_SCAN_LIMIT);
                    }
                }
            }
        }
        return result;
    }

    private int allowedCodeCount(
            Connection connection,
            ValueDomainColumns columns,
            String valueDomainId) throws SQLException {
        String sql = "SELECT COUNT(DISTINCT CAST(m." + columns.codeColumn() + " AS CHAR)) AS code_count "
                + valueDomainFromWhere(columns, valueDomainId);
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt("code_count") : 0;
        }
    }

    private String valueDomainFromWhere(ValueDomainColumns columns, String valueDomainId) {
        String typePredicate = columns.typeColumn() == null
                ? ""
                : " AND (m." + columns.typeColumn() + " IS NULL OR CAST(m." + columns.typeColumn() + " AS CHAR) IN ('1','2'))";
        String statusPredicate = columns.mainStatusColumn() == null
                ? ""
                : " AND (z." + columns.mainStatusColumn() + " IS NULL OR CAST(z." + columns.mainStatusColumn() + " AS CHAR)='2')";
        return "FROM " + VALUE_DOMAIN_CODE_TABLE + " m "
                + "LEFT JOIN " + VALUE_DOMAIN_TABLE + " z ON z." + columns.mainDomainIdColumn()
                + "=m." + columns.mappingDomainIdColumn() + " "
                + "WHERE m." + columns.mappingDomainIdColumn() + "='" + escapeLiteral(valueDomainId) + "'"
                + statusPredicate
                + typePredicate
                + " AND m." + columns.codeColumn() + " IS NOT NULL"
                + " AND CAST(m." + columns.codeColumn() + " AS CHAR)<>''";
    }

    private static String firstExisting(Set<String> columns, String... candidates) {
        List<String> values = List.of(candidates);
        for (String value : values) {
            if (columns.contains(value)) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, String> resolvedColumns(
            MedicalDatasetContract contract,
            List<ColumnInfo> sourceColumns,
            Map<String, String> fieldMapping) {
        if (contract == null || contract.fields() == null || contract.fields().isEmpty()) {
            return Map.of();
        }
        Map<String, String> sourceIndex = sourceColumnIndex(sourceColumns);
        Map<String, String> mapping = normalizeMapping(fieldMapping);
        Map<String, String> resolved = new LinkedHashMap<>();
        for (MedicalFieldContract field : contract.fields()) {
            String mapped = firstNonBlank(mapping.get(normalize(field.code())), mapping.get(normalize(field.dorisColumn())));
            String sourceColumn = mapped == null ? sourceIndex.get(normalize(field.code())) : sourceIndex.get(normalize(mapped));
            if (sourceColumn != null) {
                resolved.put(normalize(field.code()), sourceColumn);
                resolved.put(normalize(field.dorisColumn()), sourceColumn);
            }
        }
        return resolved;
    }

    private static Map<String, String> sourceColumnIndex(List<ColumnInfo> sourceColumns) {
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            return Map.of();
        }
        Map<String, String> index = new LinkedHashMap<>();
        for (ColumnInfo column : sourceColumns) {
            if (column != null && column.columnName() != null && !column.columnName().isBlank()) {
                index.putIfAbsent(normalize(column.columnName()), column.columnName());
            }
        }
        return index;
    }

    private static Map<String, String> normalizeMapping(Map<String, String> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                normalized.put(normalize(entry.getKey()), entry.getValue().trim());
            }
        }
        return normalized;
    }

    private static String qualifySource(String sourceSchema, String sourceObject, SourceDialectAdapter adapter) {
        if (sourceSchema == null || sourceSchema.isBlank()) {
            return adapter.quoteIdentifier(sourceObject);
        }
        return adapter.quoteIdentifier(sourceSchema) + "." + adapter.quoteIdentifier(sourceObject);
    }

    private static String basePredicate(String baseWhere) {
        if (baseWhere == null || baseWhere.isBlank()) {
            return "1=1";
        }
        return "(" + baseWhere.trim() + ")";
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static MedicalValueDomainCheckMode parseMode(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return MedicalValueDomainCheckMode.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的值域校验模式: " + value, e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String sanitize(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "";
        }
        return identifier.replaceAll("[^a-zA-Z0-9_]", "");
    }

    private static String escapeLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String sqlLiterals(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "''";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> "'" + escapeLiteral(value.trim()) + "'")
                .collect(Collectors.joining(", "));
    }

    private record ValueDomainColumns(
            String mappingDomainIdColumn,
            String mainDomainIdColumn,
            String mainStatusColumn,
            String codeColumn,
            String typeColumn) {
    }

    private record ValueDomainMainColumns(String domainIdColumn, String statusColumn) {
    }

    private record ValueDomainOverride(
            String domainId,
            MedicalValueDomainCheckMode mode,
            String source,
            String version) {
    }
}
