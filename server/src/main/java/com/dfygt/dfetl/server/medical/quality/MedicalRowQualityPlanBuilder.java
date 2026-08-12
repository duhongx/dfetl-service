package com.dfygt.dfetl.server.medical.quality;

import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.medical.MedicalFormatParser;
import com.dfygt.dfetl.server.medical.MedicalTemporalRule;
import com.dfygt.dfetl.server.medical.precheck.MedicalValueDomainCheckMode;
import com.dfygt.dfetl.server.medical.precheck.MedicalValueDomainRule;
import com.dfygt.dfetl.server.medical.rule.MedicalNumericIssueSqlRule;
import com.dfygt.dfetl.server.medical.rule.MedicalRequiredIssueSqlRule;
import com.dfygt.dfetl.server.medical.rule.MedicalStringIssueSqlRule;
import com.dfygt.dfetl.server.medical.rule.MedicalTemporalIssueSqlRule;
import com.dfygt.dfetl.server.medical.rule.MedicalValueDomainIssueSqlRule;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于医共体契约生成源端行级质量分流 SQL。
 */
@Component
public class MedicalRowQualityPlanBuilder {

    public MedicalRowQualityPlan build(MedicalRowQualityRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("医共体行级质量请求不能为空");
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
        Map<String, String> mapping = normalizeMapping(request.fieldMapping());
        List<MedicalFieldContract> fields = orderedFields(contract);
        Map<String, String> resolvedColumns = resolvedColumns(fields, sourceIndex, mapping);
        String source = qualifySource(request.sourceSchema(), request.sourceObject(), adapter);
        String basePredicate = basePredicate(request.baseWhere());
        boolean materializeIssueSource = "POSTGRESQL".equalsIgnoreCase(adapter.dialect());
        String issueSource = materializeIssueSource ? "dfetl_precheck_source" : source;
        String issueBasePredicate = materializeIssueSource ? "1=1" : basePredicate;

        List<String> pkColumns = contract.primaryKeys().stream()
                .map(key -> resolvedColumns.get(normalize(key)))
                .filter(column -> column != null && !column.isBlank())
                .toList();

        List<String> blockingTypes = new ArrayList<>();
        List<String> blockingQueries = new ArrayList<>();
        List<String> validExclusionPredicates = new ArrayList<>();
        List<ValueDomainWarningCandidate> warningCandidates = new ArrayList<>();
        String pkNullPredicate = pkColumns.stream()
                .map(column -> adapter.isBlank(quote(column, adapter)))
                .collect(Collectors.joining(" OR "));
        if (!pkNullPredicate.isBlank()) {
            blockingTypes.add("PRIMARY_KEY_NULL");
            validExclusionPredicates.add("/* PRIMARY_KEY_NULL */ (" + pkNullPredicate + ")");
            blockingQueries.add(rowProblemSelect(
                    "PRIMARY_KEY_NULL",
                    "BLOCKER",
                    issueSource,
                    issueBasePredicate + " AND (" + pkNullPredicate + ")",
                    pkColumns,
                    adapter));
        }

        for (MedicalFieldContract field : fields) {
            if (!field.primaryKey()) {
                continue;
            }
            String sourceColumn = resolvedColumns.get(normalize(field.code()));
            if (sourceColumn == null || sourceColumn.isBlank()) {
                continue;
            }
            String expression = "src." + quote(sourceColumn, adapter);
            String placeholderPredicate = adapter.trim(adapter.castToText(expression)) + " = '-'";
            blockingTypes.add("PRIMARY_KEY_PLACEHOLDER");
            validExclusionPredicates.add("/* PRIMARY_KEY_PLACEHOLDER:" + escapeLiteral(field.code())
                    + " */ (" + placeholderPredicate + ")");
            blockingQueries.add(fieldProblemSelect(
                    field,
                    sourceColumn,
                    issueSource,
                    issueBasePredicate + " AND " + placeholderPredicate,
                    "PRIMARY_KEY_PLACEHOLDER",
                    "BLOCKER",
                    "主键禁止 '-'",
                    pkColumns,
                    expression,
                    "NULL",
                    adapter));
        }

        if (!pkColumns.isEmpty()) {
            blockingTypes.add("PRIMARY_KEY_DUPLICATE");
            validExclusionPredicates.add("/* PRIMARY_KEY_DUPLICATE */ src.dfetl_pk_duplicate_count > 1");
            String partitionBy = pkColumns.stream()
                    .map(column -> "src." + quote(column, adapter))
                    .collect(Collectors.joining(", "));
            String duplicateSql = "SELECT 'PRIMARY_KEY_DUPLICATE' AS error_type, 'BLOCKER' AS severity, "
                    + nullFieldIssueColumns()
                    + pkJsonExpression(pkColumns, adapter) + " AS business_pk_json, "
                    + rowHashExpression(pkColumns, adapter) + " AS source_row_hash, "
                    + rawRowJsonExpression() + " AS raw_row_json "
                    + "FROM (SELECT src.*, COUNT(*) OVER (PARTITION BY " + partitionBy
                    + ") AS dfetl_pk_duplicate_count FROM " + issueSource + " src WHERE " + issueBasePredicate
                    + ") src WHERE src.dfetl_pk_duplicate_count > 1";
            blockingQueries.add(duplicateSql);
        }

        List<String> warningTypes = new ArrayList<>();
        List<String> warningQueries = new ArrayList<>();
        for (MedicalFieldContract field : fields) {
            String sourceColumn = resolvedColumns.get(normalize(field.code()));
            if (sourceColumn != null) {
                String expression = "src." + quote(sourceColumn, adapter);
                MedicalRequiredIssueSqlRule requiredRule = MedicalRequiredIssueSqlRule.from(field, expression, adapter);
                if (requiredRule.required()) {
                    blockingTypes.add(requiredRule.issueType());
                    validExclusionPredicates.add("/* " + requiredRule.issueType() + ":"
                            + escapeLiteral(field.code()) + " */ (" + requiredRule.predicate() + ")");
                    blockingQueries.add(fieldProblemSelect(
                            field,
                            sourceColumn,
                            issueSource,
                            issueBasePredicate + " AND " + requiredRule.predicate(),
                            requiredRule.issueType(),
                            "BLOCKER",
                            requiredRule.standardRule(),
                            pkColumns,
                            expression,
                            "NULL",
                            adapter));
                }
            }
            String type = normalize(field.sdvType());
            if (sourceColumn != null && ("D".equals(type) || "DT".equals(type))) {
                String expression = "src." + quote(sourceColumn, adapter);
                MedicalTemporalIssueSqlRule temporalRule = MedicalTemporalIssueSqlRule.from(field, expression, adapter);
                blockingTypes.add(temporalRule.issueType());
                validExclusionPredicates.add("/* " + temporalRule.issueType() + ":"
                        + escapeLiteral(field.code()) + " */ (" + temporalRule.invalidPredicate() + ")");
                blockingQueries.add(fieldProblemSelect(
                        field,
                        sourceColumn,
                        issueSource,
                        issueBasePredicate + " AND " + temporalRule.invalidPredicate(),
                        temporalRule.issueType(),
                        "BLOCKER",
                        temporalRule.standardRule(),
                        pkColumns,
                        expression,
                        temporalRule.convertedExpression(),
                        adapter));
            }
            if (sourceColumn != null) {
                String expression = "src." + quote(sourceColumn, adapter);
                MedicalStringIssueSqlRule stringRule = MedicalStringIssueSqlRule.from(field, expression, adapter);
                if (stringRule.stringCapacityCheck()) {
                    blockingTypes.add(stringRule.issueType());
                    validExclusionPredicates.add("/* " + stringRule.issueType() + ":"
                            + escapeLiteral(field.code()) + " */ (" + stringRule.predicate() + ")");
                    blockingQueries.add(fieldProblemSelect(
                            field,
                            sourceColumn,
                            issueSource,
                            issueBasePredicate + " AND " + stringRule.predicate(),
                            stringRule.issueType(),
                            "BLOCKER",
                            stringRule.standardRule(),
                            pkColumns,
                            expression,
                            expression,
                            adapter));
                }
            }
            if (field.primaryKey() || !"N".equals(type)) {
                addValueDomainCheck(
                        field,
                        issueSource,
                        issueBasePredicate,
                        pkColumns,
                        resolvedColumns,
                        adapter,
                        request.valueDomainRulesByField(),
                        blockingTypes,
                        blockingQueries,
                        validExclusionPredicates,
                        warningCandidates);
                continue;
            }
            if (sourceColumn == null) {
                continue;
            }
            String expression = "src." + quote(sourceColumn, adapter);
            MedicalNumericIssueSqlRule numericRule = MedicalNumericIssueSqlRule.from(field, expression, adapter);
            String invalidNumberPredicate = numericRule.invalidNumberPredicate();
            String capacityExceededPredicate = numericRule.capacityExceededPredicate();

            blockingTypes.add("INVALID_NUMBER");
            validExclusionPredicates.add("/* INVALID_NUMBER:" + escapeLiteral(field.code())
                    + " */ (" + invalidNumberPredicate + ")");
            blockingQueries.add(fieldProblemSelect(
                    field,
                    sourceColumn,
                    issueSource,
                    issueBasePredicate + " AND " + invalidNumberPredicate,
                    "INVALID_NUMBER",
                    "BLOCKER",
                    field.format(),
                    pkColumns,
                    expression,
                    "NULL",
                    adapter));

            blockingTypes.add("TARGET_NUMERIC_CAPACITY_EXCEEDED");
            validExclusionPredicates.add("/* TARGET_NUMERIC_CAPACITY_EXCEEDED:"
                    + escapeLiteral(field.code()) + " */ (" + capacityExceededPredicate + ")");
            blockingQueries.add(fieldProblemSelect(
                    field,
                    sourceColumn,
                    issueSource,
                    issueBasePredicate + " AND " + capacityExceededPredicate,
                    "TARGET_NUMERIC_CAPACITY_EXCEEDED",
                    "BLOCKER",
                    field.format(),
                    pkColumns,
                    expression,
                    "NULL",
                    adapter));

            addValueDomainCheck(
                    field,
                    issueSource,
                    issueBasePredicate,
                    pkColumns,
                    resolvedColumns,
                    adapter,
                    request.valueDomainRulesByField(),
                    blockingTypes,
                    blockingQueries,
                    validExclusionPredicates,
                    warningCandidates);
        }

        appendWarningQueries(
                warningCandidates,
                issueSource,
                issueBasePredicate,
                pkColumns,
                validExclusionPredicates,
                adapter,
                warningTypes,
                warningQueries);
        String validSourceQuery = validSourceQuery(
                source, basePredicate, pkColumns, validExclusionPredicates, fields, resolvedColumns, adapter);
        return new MedicalRowQualityPlan(
                issueQuery(blockingQueries, materializeIssueSource, source, basePredicate),
                issueQuery(warningQueries, materializeIssueSource, source, basePredicate),
                validSourceQuery,
                List.copyOf(blockingTypes),
                List.copyOf(warningTypes));
    }

    /**
     * PostgreSQL 复杂视图必须只展开一次。每条规则继续复用同一问题列合同，但都读取
     * MATERIALIZED CTE，避免 N 个字段把底层业务视图重复执行 N 次。
     */
    private static String issueQuery(
            List<String> queries,
            boolean materializeSource,
            String source,
            String basePredicate) {
        if (queries == null || queries.isEmpty()) {
            return "";
        }
        String union = String.join("\nUNION ALL\n", queries);
        if (!materializeSource) {
            return union;
        }
        return "WITH dfetl_precheck_source AS MATERIALIZED (SELECT * FROM " + source
                + " src WHERE " + basePredicate + ")\n" + union;
    }

    private static String rowProblemSelect(
            String errorType,
            String severity,
            String source,
            String predicate,
            List<String> pkColumns,
            SourceDialectAdapter adapter) {
        return "SELECT '" + errorType + "' AS error_type, '" + severity + "' AS severity, "
                + nullFieldIssueColumns()
                + pkJsonExpression(pkColumns, adapter) + " AS business_pk_json, "
                + rowHashExpression(pkColumns, adapter) + " AS source_row_hash, "
                + rawRowJsonExpression() + " AS raw_row_json "
                + "FROM " + source + " src WHERE " + predicate;
    }

    private static String nullFieldIssueColumns() {
        return "CAST(NULL AS text) AS field_code, "
                + "CAST(NULL AS text) AS field_name, "
                + "CAST(NULL AS text) AS source_column, "
                + "CAST(NULL AS text) AS target_column, "
                + "CAST(NULL AS text) AS standard_rule, "
                + "CAST(NULL AS text) AS value_domain_code, "
                + "CAST(NULL AS text) AS value_domain_mode, "
                + "CAST(NULL AS integer) AS value_domain_allowed_count, "
                + "CAST(NULL AS text) AS raw_value, "
                + "CAST(NULL AS text) AS normalized_value, ";
    }

    private static String fieldProblemSelect(
            MedicalFieldContract field,
            String sourceColumn,
            String source,
            String predicate,
            String errorType,
            String severity,
            String standardRule,
            List<String> pkColumns,
            String rawExpression,
            String normalizedExpression,
            SourceDialectAdapter adapter) {
        return fieldProblemSelect(
                field,
                sourceColumn,
                source,
                predicate,
                errorType,
                severity,
                standardRule,
                null,
                null,
                null,
                pkColumns,
                rawExpression,
                normalizedExpression,
                adapter);
    }

    private static String fieldProblemSelect(
            MedicalFieldContract field,
            String sourceColumn,
            String source,
            String predicate,
            String errorType,
            String severity,
            String standardRule,
            String valueDomainCode,
            String valueDomainMode,
            Integer valueDomainAllowedCount,
            List<String> pkColumns,
            String rawExpression,
            String normalizedExpression,
            SourceDialectAdapter adapter) {
        return "SELECT '" + errorType + "' AS error_type, '" + severity + "' AS severity, "
                + "'" + escapeLiteral(field.code()) + "' AS field_code, "
                + "'" + escapeLiteral(field.name()) + "' AS field_name, "
                + nullableLiteral(sourceColumn, "text") + " AS source_column, "
                + nullableLiteral(field.dorisColumn(), "text") + " AS target_column, "
                + "'" + escapeLiteral(standardRule) + "' AS standard_rule, "
                + nullableLiteral(valueDomainCode, "text") + " AS value_domain_code, "
                + nullableLiteral(valueDomainMode, "text") + " AS value_domain_mode, "
                + (valueDomainAllowedCount == null
                ? "CAST(NULL AS integer)" : valueDomainAllowedCount) + " AS value_domain_allowed_count, "
                + adapter.castToText(rawExpression) + " AS raw_value, "
                + adapter.castToText(normalizedExpression) + " AS normalized_value, "
                + pkJsonExpression(pkColumns, adapter) + " AS business_pk_json, "
                + rowHashExpression(pkColumns, adapter) + " AS source_row_hash, "
                + rawRowJsonExpression() + " AS raw_row_json "
                + "FROM " + source + " src WHERE " + predicate + " /* " + escapeLiteral(standardRule) + " */";
    }

    private static void addValueDomainCheck(
            MedicalFieldContract field,
            String source,
            String basePredicate,
            List<String> pkColumns,
            Map<String, String> resolvedColumns,
            SourceDialectAdapter adapter,
            Map<String, MedicalValueDomainRule> valueDomainRulesByField,
            List<String> blockingTypes,
            List<String> blockingQueries,
            List<String> validExclusionPredicates,
            List<ValueDomainWarningCandidate> warningCandidates) {
        MedicalValueDomainRule rule = valueDomainRule(valueDomainRulesByField, field.code());
        if (rule == null) {
            return;
        }
        String sourceColumn = resolvedColumns.get(normalize(field.code()));
        if (sourceColumn == null) {
            return;
        }
        String expression = "src." + quote(sourceColumn, adapter);
        if (rule.mode() == MedicalValueDomainCheckMode.WARN_ONLY) {
            warningCandidates.add(new ValueDomainWarningCandidate(field, sourceColumn, expression, rule));
            return;
        }
        if (!rule.strictBlock() && !rule.actualInvalidBlock()) {
            return;
        }
        MedicalValueDomainIssueSqlRule valueDomainIssue =
                MedicalValueDomainIssueSqlRule.from(rule, expression, adapter);
        if (!valueDomainIssue.blocking()) {
            return;
        }
        blockingTypes.add(valueDomainIssue.issueType());
        validExclusionPredicates.add("/* " + valueDomainIssue.issueType() + ":"
                + escapeLiteral(field.code()) + " */ (" + valueDomainIssue.predicate() + ")");
        blockingQueries.add(fieldProblemSelect(
                field,
                sourceColumn,
                source,
                basePredicate + " AND " + valueDomainIssue.predicate(),
                valueDomainIssue.issueType(),
                "BLOCKER",
                "值域编码",
                rule.domainId(),
                rule.mode().name(),
                rule.allowedCodeCount(),
                pkColumns,
                expression,
                expression,
                adapter));
    }

    private static void appendWarningQueries(
            List<ValueDomainWarningCandidate> warningCandidates,
            String source,
            String basePredicate,
            List<String> pkColumns,
            List<String> validExclusionPredicates,
            SourceDialectAdapter adapter,
            List<String> warningTypes,
            List<String> warningQueries) {
        if (warningCandidates == null || warningCandidates.isEmpty()) {
            return;
        }
        String blockingExclusion = exclusionPredicate(validExclusionPredicates);
        String warningSource = source;
        String warningBasePredicate = basePredicate;
        if (pkColumns != null && !pkColumns.isEmpty()) {
            String partitionBy = pkColumns.stream()
                    .map(column -> "src." + quote(column, adapter))
                    .collect(Collectors.joining(", "));
            warningSource = "(SELECT src.*, COUNT(*) OVER (PARTITION BY " + partitionBy
                    + ") AS dfetl_pk_duplicate_count FROM " + source + " src WHERE " + basePredicate + ")";
            warningBasePredicate = "1=1";
        }
        for (ValueDomainWarningCandidate candidate : warningCandidates) {
            String nonBlankPredicate = "NOT (" + adapter.isBlank(candidate.expression()) + ")";
            String predicate = warningBasePredicate + " AND " + nonBlankPredicate
                    + (blockingExclusion.isBlank() ? "" : " AND NOT (" + blockingExclusion + ")");
            warningTypes.add(MedicalValueDomainIssueSqlRule.INVALID_VALUE_DOMAIN);
            warningQueries.add(fieldProblemSelect(
                    candidate.field(),
                    candidate.sourceColumn(),
                    warningSource,
                    predicate,
                    MedicalValueDomainIssueSqlRule.INVALID_VALUE_DOMAIN,
                    "WARNING",
                    firstNonBlank(candidate.rule().reason(), "值域编码告警"),
                    candidate.rule().domainId(),
                    candidate.rule().mode().name(),
                    candidate.rule().allowedCodeCount(),
                    pkColumns,
                    candidate.expression(),
                    candidate.expression(),
                    adapter));
        }
    }

    private static String nullableLiteral(String value, String sqlType) {
        return value == null || value.isBlank()
                ? "CAST(NULL AS " + sqlType + ")"
                : "'" + escapeLiteral(value) + "'";
    }

    private static String validSourceQuery(
            String source,
            String basePredicate,
            List<String> pkColumns,
            List<String> validExclusionPredicates,
            List<MedicalFieldContract> fields,
            Map<String, String> resolvedColumns,
            SourceDialectAdapter adapter) {
        String projections = standardProjections(fields, resolvedColumns, adapter);
        String exclusion = exclusionPredicate(validExclusionPredicates);
        if (pkColumns.isEmpty()) {
            return "SELECT " + projections + " FROM " + source + " src WHERE " + basePredicate
                    + (exclusion.isBlank() ? "" : " AND NOT (" + exclusion + ")");
        }
        String partitionBy = pkColumns.stream()
                .map(column -> "src." + quote(column, adapter))
                .collect(Collectors.joining(", "));
        return "SELECT " + projections + " FROM (SELECT src.*, COUNT(*) OVER (PARTITION BY " + partitionBy
                + ") AS dfetl_pk_duplicate_count FROM " + source + " src WHERE " + basePredicate
                + ") src" + (exclusion.isBlank() ? "" : " WHERE NOT (" + exclusion + ")");
    }

    private static String exclusionPredicate(List<String> validExclusionPredicates) {
        return validExclusionPredicates == null || validExclusionPredicates.isEmpty()
                ? ""
                : validExclusionPredicates.stream()
                .map(predicate -> "(" + predicate + ")")
                .collect(Collectors.joining(" OR "));
    }

    private static String standardProjections(
            List<MedicalFieldContract> fields,
            Map<String, String> resolvedColumns,
            SourceDialectAdapter adapter) {
        return fields.stream()
                .map(field -> {
                    String sourceColumn = resolvedColumns.get(normalize(field.code()));
                    if (sourceColumn == null) {
                        return null;
                    }
                    String expression = "src." + quote(sourceColumn, adapter);
                    return fieldExpression(field, expression, adapter)
                            + " AS " + adapter.quoteIdentifier(field.dorisColumn());
                })
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(", "));
    }

    private static String fieldExpression(
            MedicalFieldContract field,
            String sourceExpression,
            SourceDialectAdapter adapter) {
        String type = normalize(field.sdvType());
        return switch (type) {
            case "DT", "D" -> adapter.safeTemporal(
                    sourceExpression,
                    MedicalTemporalRule.require(type, field.format()));
            case "N" -> adapter.safeDecimal(
                    sourceExpression,
                    MedicalFormatParser.requireNumeric(type, field.format()));
            default -> adapter.castToText(sourceExpression);
        };
    }

    private static String pkJsonExpression(List<String> pkColumns, SourceDialectAdapter adapter) {
        if (pkColumns.isEmpty()) {
            return "'{}'::jsonb";
        }
        return "jsonb_build_object(" + pkColumns.stream()
                .map(column -> "'" + escapeLiteral(column) + "', " + adapter.castToText("src." + quote(column, adapter)))
                .collect(Collectors.joining(", ")) + ")";
    }

    private static String rowHashExpression(List<String> pkColumns, SourceDialectAdapter adapter) {
        if (pkColumns.isEmpty()) {
            return "md5(CAST(to_jsonb(src) AS text))";
        }
        return "md5(concat_ws('|', " + pkColumns.stream()
                .map(column -> "COALESCE(" + adapter.castToText("src." + quote(column, adapter)) + ", '<NULL>')")
                .collect(Collectors.joining(", ")) + "))";
    }

    private static String rawRowJsonExpression() {
        return "to_jsonb(src) - 'dfetl_pk_duplicate_count'";
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

    private static Map<String, String> resolvedColumns(
            List<MedicalFieldContract> fields,
            Map<String, String> sourceIndex,
            Map<String, String> mapping) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (MedicalFieldContract field : fields) {
            String mapped = firstNonBlank(mapping.get(normalize(field.code())), mapping.get(normalize(field.dorisColumn())));
            String sourceColumn = mapped == null ? sourceIndex.get(normalize(field.code())) : sourceIndex.get(normalize(mapped));
            if (sourceColumn != null) {
                resolved.put(normalize(field.code()), sourceColumn);
                resolved.put(normalize(field.dorisColumn()), sourceColumn);
            }
        }
        return resolved;
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

    private static MedicalValueDomainRule valueDomainRule(
            Map<String, MedicalValueDomainRule> rules,
            String fieldCode) {
        if (rules == null || rules.isEmpty() || fieldCode == null || fieldCode.isBlank()) {
            return null;
        }
        MedicalValueDomainRule direct = rules.get(normalize(fieldCode));
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, MedicalValueDomainRule> entry : rules.entrySet()) {
            if (normalize(entry.getKey()).equals(normalize(fieldCode))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String quote(String column, SourceDialectAdapter adapter) {
        return adapter.quoteIdentifier(column);
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

    private static String escapeLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private record ValueDomainWarningCandidate(
            MedicalFieldContract field,
            String sourceColumn,
            String expression,
            MedicalValueDomainRule rule) {
    }
}
