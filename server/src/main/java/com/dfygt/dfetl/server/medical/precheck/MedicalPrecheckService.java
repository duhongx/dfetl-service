package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.medical.MedicalFormatParser;
import com.dfygt.dfetl.server.medical.MedicalStorageNumericPolicy;
import com.dfygt.dfetl.server.medical.MedicalTemporalRule;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 医共体 contract-driven 预检计划生成服务。
 *
 * <p>本服务只生成结构化检查项和方言化 SQL，不直接连接客户源库执行。</p>
 */
@Service
public class MedicalPrecheckService {

    private static final Pattern VARCHAR_TYPE = Pattern.compile("(?i)VARCHAR\\s*\\(\\s*(\\d+)\\s*\\)");

    public MedicalPrecheckPlan buildPlan(MedicalPrecheckRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("预检请求不能为空");
        }
        MedicalDatasetContract contract = request.contract();
        SourceDialectAdapter adapter = request.dialectAdapter();
        if (contract == null) {
            throw new IllegalArgumentException("医共体数据集契约不能为空");
        }
        if (adapter == null) {
            throw new IllegalArgumentException("源库方言适配器不能为空");
        }
        if (request.sourceObject() == null || request.sourceObject().isBlank()) {
            throw new IllegalArgumentException("源对象不能为空");
        }

        Map<String, String> sourceIndex = sourceColumnIndex(request.sourceColumns());
        Map<String, ColumnInfo> sourceMetadataIndex = sourceColumnMetadataIndex(request.sourceColumns());
        Map<String, String> mapping = normalizeMapping(request.fieldMapping());
        List<MedicalFieldContract> fields = orderedFields(contract);
        Set<String> usedSourceColumns = new LinkedHashSet<>();
        List<MedicalPrecheckFinding> formatFindings = validateContractFormats(contract);
        Set<String> invalidFormatFields = formatFindings.stream()
                .map(MedicalPrecheckFinding::field)
                .filter(field -> field != null && !field.isBlank())
                .map(MedicalPrecheckService::normalize)
                .collect(Collectors.toSet());
        List<MedicalPrecheckFinding> findings = new ArrayList<>(formatFindings);
        List<MedicalPrecheckCheck> checks = new ArrayList<>();
        String qualifiedSource = qualifySource(request.sourceSchema(), request.sourceObject(), adapter);
        MedicalPrecheckOptions options = request.options() == null
                ? MedicalPrecheckOptions.defaults()
                : request.options();
        String boundedSource = boundedSource(qualifiedSource, adapter, options.normalizedMaxScanRows());

        Map<String, String> resolvedColumns = new LinkedHashMap<>();
        for (MedicalFieldContract field : fields) {
            String sourceColumn = resolveSourceColumn(field, sourceIndex, mapping);
            if (sourceColumn == null) {
                findings.add(new MedicalPrecheckFinding(
                        MedicalPrecheckSeverity.BLOCKER,
                        "MISSING_STANDARD_FIELD",
                        field.code(),
                        "标准字段缺失: " + field.code()));
                continue;
            }
            resolvedColumns.put(field.code().toUpperCase(Locale.ROOT), sourceColumn);
            usedSourceColumns.add(normalize(sourceColumn));
            if (field.primaryKey()) {
                addTemporalSourceTypeFinding(
                        field, sourceColumn, sourceMetadataIndex, findings);
            }
        }

        for (String extra : ignoredSourceFields(request.sourceColumns(), usedSourceColumns)) {
            findings.add(new MedicalPrecheckFinding(
                    MedicalPrecheckSeverity.WARNING,
                    "EXTRA_SOURCE_FIELD",
                    extra,
                    "标准外字段忽略: " + extra));
        }

        addPrimaryKeyNullCheck(contract, resolvedColumns, adapter, boundedSource, options, checks);
        if (options.duplicatePrimaryKeyCheck()) {
            addPrimaryKeyDuplicateCheck(contract, resolvedColumns, adapter, boundedSource, options, checks);
        }
        for (MedicalFieldContract field : fields) {
            if (invalidFormatFields.contains(normalize(field.code()))) {
                continue;
            }
            String sourceColumn = resolvedColumns.get(field.code().toUpperCase(Locale.ROOT));
            if (sourceColumn == null) {
                continue;
            }
            addFieldChecks(field, sourceColumn, adapter, boundedSource, options, checks);
        }
        addIncrementalFieldCheck(
                contract, resolvedColumns, adapter, boundedSource, options, invalidFormatFields, checks);

        return new MedicalPrecheckPlan(List.copyOf(findings), List.copyOf(checks));
    }

    /**
     * 不读取源数据即可完成的格式契约门禁，供单表预检和 existing-task plan 共用。
     */
    public List<MedicalPrecheckFinding> validateContractFormats(MedicalDatasetContract contract) {
        if (contract == null) {
            return List.of();
        }
        List<MedicalPrecheckFinding> findings = new ArrayList<>();
        for (MedicalFieldContract field : orderedFields(contract)) {
            String type = normalize(field.sdvType());
            try {
                if ("N".equals(type)) {
                    MedicalFormatParser.requireNumeric(type, field.format());
                } else if ("D".equals(type) || "DT".equals(type)) {
                    MedicalTemporalRule.require(type, field.format());
                }
            } catch (IllegalArgumentException ex) {
                findings.add(new MedicalPrecheckFinding(
                        MedicalPrecheckSeverity.BLOCKER,
                        "UNSUPPORTED_MEDICAL_FORMAT",
                        field.code(),
                        "无法解析医共体字段格式: " + field.code()
                                + " (sdvType=" + field.sdvType()
                                + ", format=" + field.format() + "): " + ex.getMessage()));
            }
        }
        return List.copyOf(findings);
    }

    private static void addPrimaryKeyNullCheck(
            MedicalDatasetContract contract,
            Map<String, String> resolvedColumns,
            SourceDialectAdapter adapter,
            String boundedSource,
            MedicalPrecheckOptions options,
            List<MedicalPrecheckCheck> checks) {
        List<String> predicates = contract.primaryKeys().stream()
                .map(key -> resolvedColumns.get(key.toUpperCase(Locale.ROOT)))
                .filter(column -> column != null && !column.isBlank())
                .map(column -> adapter.isBlank(adapter.quoteIdentifier(column)))
                .toList();
        if (predicates.isEmpty()) {
            return;
        }
        checks.add(check(
                MedicalPrecheckSeverity.BLOCKER,
                "PRIMARY_KEY_NULL",
                String.join(",", contract.primaryKeys()),
                "主键字段存在空值",
                "SELECT COUNT(*) AS invalid_count FROM " + boundedSource
                        + " WHERE " + String.join(" OR ", predicates),
                options));
    }

    private static void addPrimaryKeyDuplicateCheck(
            MedicalDatasetContract contract,
            Map<String, String> resolvedColumns,
            SourceDialectAdapter adapter,
            String boundedSource,
            MedicalPrecheckOptions options,
            List<MedicalPrecheckCheck> checks) {
        List<String> keys = contract.primaryKeys().stream()
                .map(key -> resolvedColumns.get(key.toUpperCase(Locale.ROOT)))
                .filter(column -> column != null && !column.isBlank())
                .map(adapter::quoteIdentifier)
                .toList();
        if (keys.isEmpty()) {
            return;
        }
        checks.add(check(
                MedicalPrecheckSeverity.BLOCKER,
                "PRIMARY_KEY_DUPLICATE",
                String.join(",", contract.primaryKeys()),
                "主键字段存在重复值",
                applySampleLimit("SELECT " + String.join(", ", keys) + ", COUNT(*) AS duplicate_count FROM "
                        + boundedSource + " GROUP BY " + String.join(", ", keys)
                        + " HAVING COUNT(*) > 1", adapter, options.normalizedSampleLimit()),
                options));
    }

    private static void addFieldChecks(
            MedicalFieldContract field,
            String sourceColumn,
            SourceDialectAdapter adapter,
            String boundedSource,
            MedicalPrecheckOptions options,
            List<MedicalPrecheckCheck> checks) {
        if (!field.primaryKey()) {
            return;
        }
        String expression = adapter.quoteIdentifier(sourceColumn);
        String type = normalize(field.sdvType());
        Integer varcharByteCapacity = isStringType(type) ? varcharByteCapacity(field) : null;
        if (varcharByteCapacity != null) {
            checks.add(check(
                    MedicalPrecheckSeverity.BLOCKER,
                    "FIELD_TOO_LONG",
                    field.code(),
                    "字段超过 Doris VARCHAR 字节容量: " + field.code(),
                    sampleSql(expression, boundedSource,
                            adapter.byteLength(adapter.castToText(expression))
                                    + " > " + varcharByteCapacity,
                            adapter,
                            options),
                    options));
        }

        addPlaceholderChecks(field, expression, adapter, boundedSource, options, checks);
        if ("DT".equals(type)) {
            checks.add(check(
                    MedicalPrecheckSeverity.BLOCKER,
                    "INVALID_DATETIME",
                    field.code(),
                    "时间字段存在非法值: " + field.code(),
                    sampleSql(expression, boundedSource, invalidConvertedValue(
                            expression, safeTemporal(field, expression, adapter), adapter), adapter, options),
                    options));
        } else if ("D".equals(type)) {
            checks.add(check(
                    MedicalPrecheckSeverity.BLOCKER,
                    "INVALID_DATE",
                    field.code(),
                    "日期字段存在非法值: " + field.code(),
                    sampleSql(expression, boundedSource, invalidConvertedValue(
                            expression, safeTemporal(field, expression, adapter), adapter), adapter, options),
                    options));
        } else if ("N".equals(type)) {
            var numericRule = MedicalStorageNumericPolicy.require(type, field.format());
            String lexicalNumber = adapter.lexicalDecimalPredicate(expression);
            String withinTargetCapacity = adapter.decimalCapacityPredicate(expression, numericRule);
            String eligibleValue = nonBlankValue(expression, adapter);
            checks.add(check(
                    MedicalPrecheckSeverity.BLOCKER,
                    "INVALID_NUMBER",
                    field.code(),
                    "数值字段存在非法值: " + field.code(),
                    sampleSql(expression, boundedSource,
                            eligibleValue + " AND NOT (" + lexicalNumber + ")",
                            adapter,
                            options),
                    options));
            checks.add(check(
                    MedicalPrecheckSeverity.BLOCKER,
                    "TARGET_NUMERIC_CAPACITY_EXCEEDED",
                    field.code(),
                    "数值超过目标 DECIMAL(" + numericRule.precision() + "," + numericRule.scale()
                            + ") 的无损存储容量: " + field.code(),
                    sampleSql(expression, boundedSource,
                            eligibleValue + " AND (" + lexicalNumber + ")"
                                    + " AND NOT (" + withinTargetCapacity + ")",
                            adapter,
                            options),
                    options));
        }
    }

    private static void addPlaceholderChecks(
            MedicalFieldContract field,
            String expression,
            SourceDialectAdapter adapter,
            String boundedSource,
            MedicalPrecheckOptions options,
            List<MedicalPrecheckCheck> checks) {
        String placeholder = dashPlaceholder(expression, adapter);
        if (field.primaryKey()) {
            checks.add(check(
                    MedicalPrecheckSeverity.BLOCKER,
                    "PRIMARY_KEY_PLACEHOLDER",
                    field.code(),
                    "主键字段不能使用 '-': " + field.code(),
                    sampleSql(expression, boundedSource, placeholder, adapter, options),
                    options));
        }
    }

    private static void addIncrementalFieldCheck(
            MedicalDatasetContract contract,
            Map<String, String> resolvedColumns,
            SourceDialectAdapter adapter,
            String boundedSource,
            MedicalPrecheckOptions options,
            Set<String> invalidFormatFields,
            List<MedicalPrecheckCheck> checks) {
        if (contract.incrementalField() == null || contract.incrementalField().isBlank()) {
            return;
        }
        String sourceColumn = resolvedColumns.get(contract.incrementalField().toUpperCase(Locale.ROOT));
        if (sourceColumn == null || sourceColumn.isBlank()) {
            return;
        }
        String expression = adapter.quoteIdentifier(sourceColumn);
        MedicalFieldContract incrementalField = orderedFields(contract).stream()
                .filter(field -> normalize(field.code()).equals(normalize(contract.incrementalField()))
                        || normalize(field.dorisColumn()).equals(normalize(contract.incrementalField())))
                .findFirst()
                .orElse(null);
        if (incrementalField == null
                || invalidFormatFields.contains(normalize(incrementalField.code()))
                || !isTemporal(incrementalField)) {
            return;
        }
        checks.add(check(
                MedicalPrecheckSeverity.BLOCKER,
                "INCREMENTAL_FIELD_INVALID",
                contract.incrementalField(),
                "增量字段存在不可解析值: " + contract.incrementalField(),
                sampleSql(expression, boundedSource, invalidConvertedValueIncludingPlaceholder(
                        expression, safeTemporal(incrementalField, expression, adapter), adapter), adapter, options),
                options));
    }

    private static boolean isTemporal(MedicalFieldContract field) {
        String type = normalize(field.sdvType());
        return "D".equals(type) || "DT".equals(type);
    }

    private static String safeTemporal(
            MedicalFieldContract field,
            String expression,
            SourceDialectAdapter adapter) {
        return adapter.safeTemporal(
                expression,
                MedicalTemporalRule.require(field.sdvType(), field.format()));
    }

    private static String baseSampleSql(
            String expression,
            String boundedSource,
            String predicate) {
        return "SELECT " + expression + " AS sample_value FROM " + boundedSource
                + " WHERE " + predicate;
    }

    private static String sampleSql(
            String expression,
            String boundedSource,
            String predicate,
            SourceDialectAdapter adapter,
            MedicalPrecheckOptions options) {
        return applySampleLimit(baseSampleSql(expression, boundedSource, predicate), adapter, options.normalizedSampleLimit());
    }

    private static String boundedSource(String qualifiedSource, SourceDialectAdapter adapter, int maxScanRows) {
        String dialect = adapter.dialect() == null ? "" : adapter.dialect().toUpperCase(Locale.ROOT);
        if ("ORACLE".equals(dialect)) {
            return "(SELECT * FROM " + qualifiedSource + " WHERE ROWNUM <= " + maxScanRows + ") precheck_scope";
        }
        if ("SQLSERVER".equals(dialect)) {
            return "(SELECT TOP " + maxScanRows + " * FROM " + qualifiedSource + ") precheck_scope";
        }
        return "(SELECT * FROM " + qualifiedSource + " LIMIT " + maxScanRows + ") precheck_scope";
    }

    private static String applySampleLimit(String sql, SourceDialectAdapter adapter, int limit) {
        String dialect = adapter.dialect() == null ? "" : adapter.dialect().toUpperCase(Locale.ROOT);
        if ("ORACLE".equals(dialect)) {
            return "SELECT * FROM (" + sql + ") WHERE ROWNUM <= " + limit;
        }
        if ("SQLSERVER".equals(dialect)) {
            return sql.replaceFirst("(?i)^SELECT\\s+", "SELECT TOP " + limit + " ");
        }
        return sql + " LIMIT " + limit;
    }

    private static String invalidConvertedValue(
            String originalExpression,
            String convertedExpression,
            SourceDialectAdapter adapter) {
        return nonBlankValue(originalExpression, adapter)
                + " AND (" + convertedExpression + ") IS NULL";
    }

    private static String invalidConvertedValueIncludingPlaceholder(
            String originalExpression,
            String convertedExpression,
            SourceDialectAdapter adapter) {
        return "NOT " + adapter.isBlank(originalExpression) + " AND (" + convertedExpression + ") IS NULL";
    }

    private static String nonBlankValue(String expression, SourceDialectAdapter adapter) {
        return "NOT " + adapter.isBlank(expression);
    }

    private static String dashPlaceholder(String expression, SourceDialectAdapter adapter) {
        return adapter.trim(adapter.castToText(expression)) + " = '-'";
    }

    private static boolean isStringType(String type) {
        return "S1".equals(type) || "S2".equals(type) || "S3".equals(type);
    }

    private static MedicalPrecheckCheck check(
            MedicalPrecheckSeverity severity,
            String code,
            String field,
            String message,
            String sql,
            MedicalPrecheckOptions options) {
        return new MedicalPrecheckCheck(
                severity,
                code,
                field,
                message,
                sql,
                options.normalizedTimeoutSeconds());
    }

    private static Integer varcharByteCapacity(MedicalFieldContract field) {
        Matcher matcher = VARCHAR_TYPE.matcher(field.dorisType() == null ? "" : field.dorisType().trim());
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private static List<MedicalFieldContract> orderedFields(MedicalDatasetContract contract) {
        if (contract.fields() == null) {
            return List.of();
        }
        return contract.fields().stream()
                .sorted(Comparator.comparingInt(field -> field.order() == null ? 0 : field.order()))
                .toList();
    }

    private static Map<String, String> sourceColumnIndex(List<ColumnInfo> sourceColumns) {
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            return Map.of();
        }
        Map<String, String> index = new LinkedHashMap<>();
        for (ColumnInfo column : sourceColumns) {
            if (column == null || column.columnName() == null || column.columnName().isBlank()) {
                continue;
            }
            index.putIfAbsent(normalize(column.columnName()), column.columnName());
        }
        return index;
    }

    private static Map<String, ColumnInfo> sourceColumnMetadataIndex(List<ColumnInfo> sourceColumns) {
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            return Map.of();
        }
        Map<String, ColumnInfo> index = new LinkedHashMap<>();
        for (ColumnInfo column : sourceColumns) {
            if (column == null || column.columnName() == null || column.columnName().isBlank()) {
                continue;
            }
            index.putIfAbsent(normalize(column.columnName()), column);
        }
        return index;
    }

    private static void addTemporalSourceTypeFinding(
            MedicalFieldContract field,
            String sourceColumn,
            Map<String, ColumnInfo> sourceMetadataIndex,
            List<MedicalPrecheckFinding> findings) {
        if (!isTemporal(field) || sourceColumn == null || sourceMetadataIndex == null) {
            return;
        }
        ColumnInfo source = sourceMetadataIndex.get(normalize(sourceColumn));
        if (source == null || !isNumericPhysicalType(source.dataType())) {
            return;
        }
        findings.add(new MedicalPrecheckFinding(
                MedicalPrecheckSeverity.BLOCKER,
                "SOURCE_TYPE_MISMATCH",
                field.code(),
                "医共体 " + normalize(field.sdvType()) + " 字段不能由数值物理类型承载: "
                        + field.code() + " (sourceType=" + source.dataType() + ")"));
    }

    private static boolean isNumericPhysicalType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return false;
        }
        String type = dataType.trim().toUpperCase(Locale.ROOT);
        return type.matches("^(TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT|LARGEINT"
                + "|DECIMAL|DECIMALV2|DECIMALV3|NUMERIC|NUMBER|FLOAT|DOUBLE|DOUBLE PRECISION"
                + "|REAL|MONEY|SMALLMONEY|SERIAL|BIGSERIAL)(\\s|\\(|$).*");
    }

    private static Map<String, String> normalizeMapping(Map<String, String> fieldMapping) {
        if (fieldMapping == null || fieldMapping.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fieldMapping.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            normalized.put(normalize(entry.getKey()), entry.getValue().trim());
        }
        return normalized;
    }

    private static String resolveSourceColumn(
            MedicalFieldContract field,
            Map<String, String> sourceColumnIndex,
            Map<String, String> fieldMapping) {
        String mapped = firstNonBlank(
                fieldMapping.get(normalize(field.code())),
                fieldMapping.get(normalize(field.dorisColumn())));
        if (mapped != null) {
            return sourceColumnIndex.get(normalize(mapped));
        }
        return sourceColumnIndex.get(normalize(field.code()));
    }

    private static List<String> ignoredSourceFields(List<ColumnInfo> sourceColumns, Set<String> usedSourceColumns) {
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            return List.of();
        }
        return sourceColumns.stream()
                .filter(column -> column != null && column.columnName() != null && !column.columnName().isBlank())
                .filter(column -> !usedSourceColumns.contains(normalize(column.columnName())))
                .map(ColumnInfo::columnName)
                .collect(Collectors.toList());
    }

    private static String qualifySource(
            String sourceSchema,
            String sourceObject,
            SourceDialectAdapter adapter) {
        if (sourceSchema == null || sourceSchema.isBlank()) {
            return adapter.quoteIdentifier(sourceObject);
        }
        return adapter.quoteIdentifier(sourceSchema) + "." + adapter.quoteIdentifier(sourceObject);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
