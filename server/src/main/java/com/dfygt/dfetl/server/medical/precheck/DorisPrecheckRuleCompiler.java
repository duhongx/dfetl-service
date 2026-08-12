package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.common.IdentifierSanitizer;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.medical.MedicalFormatParser;
import com.dfygt.dfetl.server.medical.MedicalNumericRule;
import com.dfygt.dfetl.server.medical.MedicalTemporalTextPolicy;
import com.dfygt.dfetl.server.medical.SdvTypeMappingPolicy;
import com.dfygt.dfetl.server.medical.quality.TargetWriteContract;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 把正式字段转换、Doris 物理合同和已确认的数据合同规则编译为集合式问题 SQL。 */
@Component
public class DorisPrecheckRuleCompiler {

    private static final String ISSUE_COLUMNS = "(`run_id`, `created_at`, `row_id`, `row_hash`, "
            + "`business_pk`, `field_code`, `error_type`, `severity`, `raw_value`, "
            + "`normalized_value`, `standard_rule`, `error_message`)";
    private static final String LEXICAL_DECIMAL = "^-?[0-9]+([.][0-9]+)?$";
    private static final Set<String> TEXT_TYPES = Set.of("VARCHAR", "CHAR", "STRING", "TEXT");
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "TINYINT", "SMALLINT", "INT", "INTEGER", "BIGINT", "LARGEINT", "DECIMAL", "NUMERIC");

    /** 保留构造参数以维持 Spring 装配入口；类型解析统一使用正式的 MedicalFormatParser。 */
    public DorisPrecheckRuleCompiler(SdvTypeMappingPolicy ignored) {
    }

    public CompiledPlan compile(
            Long runId,
            DorisPrecheckTableSpec tableSpec,
            MedicalDatasetContract contract,
            TargetWriteContract targetContract,
            int fieldsPerBatch) {
        requirePositive(runId, "runId");
        if (tableSpec == null) {
            throw new IllegalArgumentException("Doris 预检表规格不能为空");
        }
        if (contract == null || contract.fields() == null || contract.fields().isEmpty()) {
            throw new IllegalArgumentException("医共体字段合同不能为空");
        }
        if (targetContract == null) {
            throw new IllegalArgumentException("Doris 目标写入合同不能为空");
        }
        if (fieldsPerBatch <= 0) {
            throw new IllegalArgumentException("fieldsPerBatch 必须大于 0");
        }
        validateTargetTable(contract, targetContract);

        List<MedicalFieldContract> fields = contract.fields().stream()
                .sorted(Comparator.comparingInt(field -> field.order() == null ? 0 : field.order()))
                .toList();
        List<RuleBatch> batches = new ArrayList<>();
        RuleBatch duplicateBatch = compilePrimaryKeyDuplicateRule(
                runId, tableSpec, contract, fields);
        if (duplicateBatch != null) {
            batches.add(duplicateBatch);
        }
        int batchIndex = batches.size();
        for (int start = 0; start < fields.size(); start += fieldsPerBatch) {
            List<MedicalFieldContract> batchFields =
                    fields.subList(start, Math.min(start + fieldsPerBatch, fields.size()));
            List<String> selects = new ArrayList<>();
            for (MedicalFieldContract field : batchFields) {
                TargetWriteContract.PhysicalColumn physical = requirePhysicalColumn(
                        targetContract, tableSpec, field);
                selects.addAll(compileFieldRules(runId, tableSpec, contract, field, physical));
            }
            if (!selects.isEmpty()) {
                String insertSql = "INSERT INTO " + qualified(tableSpec.database(), "precheck_issue")
                        + " " + ISSUE_COLUMNS + "\n" + String.join("\nUNION ALL\n", selects);
                batches.add(new RuleBatch(
                        batchIndex++,
                        batchFields.stream().map(MedicalFieldContract::code).toList(),
                        insertSql));
            }
        }
        return new CompiledPlan(List.copyOf(batches));
    }

    private RuleBatch compilePrimaryKeyDuplicateRule(
            Long runId,
            DorisPrecheckTableSpec tableSpec,
            MedicalDatasetContract contract,
            List<MedicalFieldContract> fields) {
        List<MedicalFieldContract> primaryKeys = resolvePrimaryKeys(contract, tableSpec, fields);
        if (primaryKeys.isEmpty()) {
            return null;
        }
        List<String> partitionColumns = primaryKeys.stream()
                .map(field -> "src." + quoted(field.dorisColumn()))
                .toList();
        String alias = "duplicate_row";
        String businessPk = businessPk(contract, alias);
        String primaryKeyRule = primaryKeys.stream()
                .map(MedicalFieldContract::code)
                .collect(Collectors.joining(",", "PRIMARY KEY (", ")"));
        String select = "SELECT " + runId + ", NOW(), " + alias + ".`row_id`, "
                + alias + ".`row_hash`, " + businessPk
                + ", NULL, 'PRIMARY_KEY_DUPLICATE', 'BLOCKER', " + businessPk + ", " + businessPk
                + ", '" + escapeLiteral(primaryKeyRule) + "', "
                + "'主键组合重复，同一组合存在多条源记录'"
                + " FROM (SELECT src.*, COUNT(*) OVER (PARTITION BY "
                + String.join(", ", partitionColumns)
                + ") AS dfetl_pk_duplicate_count FROM " + tableSpec.qualifiedRawTable()
                + " src WHERE src.`run_id` = " + runId + ") " + alias
                + " WHERE " + alias + ".dfetl_pk_duplicate_count > 1";
        String insertSql = "INSERT INTO " + qualified(tableSpec.database(), "precheck_issue")
                + " " + ISSUE_COLUMNS + "\n" + select;
        return new RuleBatch(
                0,
                primaryKeys.stream().map(MedicalFieldContract::code).toList(),
                insertSql);
    }

    private List<MedicalFieldContract> resolvePrimaryKeys(
            MedicalDatasetContract contract,
            DorisPrecheckTableSpec tableSpec,
            List<MedicalFieldContract> fields) {
        if (contract.primaryKeys() == null || contract.primaryKeys().isEmpty()) {
            return List.of();
        }
        Map<String, MedicalFieldContract> byCode = fields.stream()
                .collect(Collectors.toMap(
                        field -> normalize(field.code()),
                        field -> field,
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<MedicalFieldContract> result = new ArrayList<>();
        for (String key : contract.primaryKeys()) {
            MedicalFieldContract field = byCode.get(normalize(key));
            if (field == null) {
                throw new IllegalStateException("医共体主键合同缺少字段定义: " + key);
            }
            String normalizedColumn = DorisPrecheckTableSpec.normalizeBusinessColumn(
                    field.dorisColumn());
            if (!tableSpec.businessColumns().contains(normalizedColumn)) {
                throw new IllegalStateException("预检暂存表缺少主键字段: " + field.dorisColumn());
            }
            result.add(field);
        }
        return List.copyOf(result);
    }

    private List<String> compileFieldRules(
            Long runId,
            DorisPrecheckTableSpec tableSpec,
            MedicalDatasetContract contract,
            MedicalFieldContract field,
            TargetWriteContract.PhysicalColumn physical) {
        String column = "src." + quoted(field.dorisColumn());
        String standardType = normalize(field.sdvType());
        String physicalType = baseType(physical.dataType());
        List<String> rules = new ArrayList<>();
        boolean valueRequired = field.primaryKey() || field.notNull();

        if (valueRequired) {
            rules.add(fieldIssueSelect(
                    runId, tableSpec, contract, field, blank(column),
                    field.primaryKey() ? "PRIMARY_KEY_NULL" : "REQUIRED_FIELD_NULL",
                    field.primaryKey() ? "主键字段不能为空或空白" : "标准非空字段不能为空或空白",
                    column, "NULL"));

            if (field.primaryKey() && isTextStandard(standardType)) {
                rules.add(fieldIssueSelect(
                        runId, tableSpec, contract, field,
                        "NOT (" + blank(column) + ") AND TRIM(" + column + ") = '-'",
                        "PRIMARY_KEY_PLACEHOLDER",
                        "文本主键不能使用 - 作为占位值",
                        column,
                        column));
            }

            if ("N".equals(standardType)) {
                rules.add(fieldIssueSelect(
                        runId, tableSpec, contract, field,
                        invalidNumber(field, column),
                        "INVALID_NUMBER",
                        "非空数值不符合医共体数值格式，正式转换结果为 NULL",
                        column,
                        "NULL"));
            }

            if ("D".equals(standardType) || "DT".equals(standardType)) {
                TemporalRule temporal = temporalRule(standardType, field.format(), column);
                rules.add(fieldIssueSelect(
                        runId, tableSpec, contract, field,
                        "NOT (" + blank(column) + ") AND " + temporal.expression() + " IS NULL",
                        temporal.errorType(),
                        "非空时间不符合正式 Reader 的格式或真实日历规则",
                        column,
                        "NULL"));
            }
        }

        if (TEXT_TYPES.contains(physicalType) && isTextStandard(standardType)
                && physical.characterCapacity() != null) {
            rules.add(fieldIssueSelect(
                    runId, tableSpec, contract, field,
                    column + " IS NOT NULL AND LENGTH(" + column + ") > " + physical.characterCapacity(),
                    "FIELD_BYTE_LENGTH_EXCEEDED",
                    "正式文本超过 Doris 物理容量 " + physical.characterCapacity() + " 字节",
                    column,
                    column));
        }

        if (valueRequired && NUMERIC_TYPES.contains(physicalType) && "N".equals(standardType)) {
            addPhysicalNumericOverflowRules(
                    rules, runId, tableSpec, contract, field, physical, column);
        }
        return rules;
    }

    private String invalidNumber(MedicalFieldContract field, String column) {
        MedicalNumericRule standard = MedicalFormatParser.requireNumeric(
                field.sdvType(), field.format());
        String text = "TRIM(" + column + ")";
        String lexical = text + " REGEXP '" + LEXICAL_DECIMAL + "'";
        String capacity = text + " REGEXP '" + escapeLiteral(standard.regexPattern()) + "'";
        return "NOT (" + blank(column) + ") AND (NOT (" + lexical + ") OR NOT (" + capacity + "))";
    }

    private void addPhysicalNumericOverflowRules(
            List<String> rules,
            Long runId,
            DorisPrecheckTableSpec tableSpec,
            MedicalDatasetContract contract,
            MedicalFieldContract field,
            TargetWriteContract.PhysicalColumn physical,
            String column) {
        MedicalNumericRule standard = MedicalFormatParser.requireNumeric(field.sdvType(), field.format());
        MedicalNumericRule target = physicalNumericCapacity(physical);
        String text = "TRIM(" + column + ")";
        String lexical = text + " REGEXP '" + LEXICAL_DECIMAL + "'";
        String standardCapacity = text + " REGEXP '" + escapeLiteral(standard.regexPattern()) + "'";
        String targetCapacity = text + " REGEXP '" + escapeLiteral(target.regexPattern()) + "'";
        String validFormalValue = "NOT (" + blank(column) + ") AND (" + lexical + ") AND ("
                + standardCapacity + ")";
        if (target.integerDigits() < standard.integerDigits() || target.scale() < standard.scale()) {
            rules.add(fieldIssueSelect(
                    runId, tableSpec, contract, field,
                    validFormalValue + " AND NOT (" + targetCapacity + ")",
                    "TARGET_NUMERIC_CAPACITY_EXCEEDED",
                    "正式数值超过 Doris " + physical.dataType() + " 物理容量",
                    column,
                    "NULL"));
        }

        NumericRange range = integerRange(physical.dataType());
        if (range != null) {
            String decimalValue = "CAST(" + text + " AS DECIMAL(38,0))";
            rules.add(fieldIssueSelect(
                    runId, tableSpec, contract, field,
                    validFormalValue + " AND (" + targetCapacity + ") AND ("
                            + decimalValue + " < " + range.minimum() + " OR "
                            + decimalValue + " > " + range.maximum() + ")",
                    "TARGET_NUMERIC_RANGE_EXCEEDED",
                    "正式数值超过 Doris " + physical.dataType() + " 有符号范围",
                    column,
                    "NULL"));
        }
    }

    private MedicalNumericRule physicalNumericCapacity(TargetWriteContract.PhysicalColumn physical) {
        Integer precision = physical.numericPrecision();
        Integer scale = physical.numericScale();
        if (precision == null || scale == null || precision - scale <= 0) {
            throw new IllegalStateException("Doris 数值列容量元数据缺失或无效: " + physical.name());
        }
        return new MedicalNumericRule(1, precision - scale, scale);
    }

    private TargetWriteContract.PhysicalColumn requirePhysicalColumn(
            TargetWriteContract targetContract,
            DorisPrecheckTableSpec tableSpec,
            MedicalFieldContract field) {
        String normalized = IdentifierSanitizer.requireValid(
                normalizeIdentifier(field.dorisColumn()), "precheckTargetColumn");
        if (!tableSpec.businessColumns().contains(normalized)) {
            throw new IllegalStateException("预检暂存表缺少合同字段: " + field.dorisColumn());
        }
        TargetWriteContract.PhysicalColumn physical = targetContract.column(field.dorisColumn());
        if (physical == null) {
            throw new IllegalStateException("Doris 目标写入合同缺少字段: " + field.dorisColumn());
        }
        return physical;
    }

    private String fieldIssueSelect(
            Long runId,
            DorisPrecheckTableSpec tableSpec,
            MedicalDatasetContract contract,
            MedicalFieldContract field,
            String predicate,
            String errorType,
            String message,
            String rawValue,
            String normalizedValue) {
        return "SELECT " + runId + ", NOW(), src.`row_id`, src.`row_hash`, "
                + businessPk(contract, "src") + ", '" + escapeLiteral(field.code()) + "', '"
                + escapeLiteral(errorType) + "', 'BLOCKER', " + rawValue + ", " + normalizedValue
                + ", '" + escapeLiteral(field.format()) + "', '" + escapeLiteral(message) + "'"
                + " FROM " + tableSpec.qualifiedRawTable() + " src"
                + " WHERE src.`run_id` = " + runId + " AND (" + predicate + ")";
    }

    private String businessPk(MedicalDatasetContract contract, String alias) {
        if (contract.primaryKeys() == null || contract.primaryKeys().isEmpty()) {
            return "NULL";
        }
        Map<String, MedicalFieldContract> fields = contract.fields().stream()
                .collect(Collectors.toMap(
                        field -> normalize(field.code()),
                        field -> field,
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<String> values = contract.primaryKeys().stream()
                .map(key -> fields.get(normalize(key)))
                .filter(field -> field != null)
                .map(field -> "COALESCE(" + alias + "." + quoted(field.dorisColumn()) + ", '<NULL>')")
                .toList();
        return values.isEmpty() ? "NULL" : "CONCAT_WS('|', " + String.join(", ", values) + ")";
    }

    private NumericRange integerRange(String dataType) {
        return switch (baseType(dataType)) {
            case "TINYINT" -> new NumericRange("-128", "127");
            case "SMALLINT" -> new NumericRange("-32768", "32767");
            case "INT", "INTEGER" -> new NumericRange("-2147483648", "2147483647");
            case "BIGINT" -> new NumericRange("-9223372036854775808", "9223372036854775807");
            default -> null;
        };
    }

    private TemporalRule temporalRule(String type, String format, String column) {
        String normalizedFormat = normalize(format);
        if ("D".equals(type) && "D8".equals(normalizedFormat)) {
            return new TemporalRule(temporalConversion(column), "INVALID_DATE");
        }
        if ("DT".equals(type) && "D8".equals(normalizedFormat)) {
            return new TemporalRule(temporalConversion(column), "INVALID_DATETIME");
        }
        if ("DT".equals(type) && "DT15".equals(normalizedFormat)) {
            return new TemporalRule(temporalConversion(column), "INVALID_DATETIME");
        }
        throw new IllegalStateException("不支持的医共体时间合同: " + type + "/" + format);
    }

    private String temporalConversion(String column) {
        String text = "TRIM(" + column + ")";
        String localBase = "SUBSTRING(REPLACE(" + text + ", 'T', ' '), 1, 19)";
        return "CASE"
                + strictTemporalBranch(text, MedicalTemporalTextPolicy.COMPACT_DATE, text, "%Y%m%d")
                + strictTemporalBranch(text, MedicalTemporalTextPolicy.DASHED_DATE, text, "%Y-%m-%d")
                + strictTemporalBranch(text, MedicalTemporalTextPolicy.COMPACT_DATETIME,
                text, "%Y%m%d%H%i%s")
                + strictTemporalBranch(text, MedicalTemporalTextPolicy.LOCAL_DATETIME,
                localBase, "%Y-%m-%d %H:%i:%s")
                + strictTemporalBranch(text, MedicalTemporalTextPolicy.OFFSET_DATETIME,
                localBase, "%Y-%m-%d %H:%i:%s")
                + " ELSE NULL END";
    }

    private String strictTemporalBranch(
            String text,
            String pattern,
            String normalized,
            String format) {
        String parsed = "STR_TO_DATE(" + normalized + ", '" + format + "')";
        return " WHEN " + text + " REGEXP '" + escapeLiteral(pattern) + "'"
                + " AND DATE_FORMAT(" + parsed + ", '" + format + "') = " + normalized
                + " THEN " + parsed;
    }

    private static void validateTargetTable(
            MedicalDatasetContract contract,
            TargetWriteContract targetContract) {
        if (contract.targetTable() == null || targetContract.table() == null
                || !contract.targetTable().equalsIgnoreCase(targetContract.table())) {
            throw new IllegalStateException("预检合同目标表与 Doris 物理合同不一致");
        }
    }

    private static boolean isTextStandard(String type) {
        return Set.of("S1", "S2", "S3", "L", "BY").contains(type);
    }

    private static String baseType(String type) {
        if (type == null) {
            return "";
        }
        int parenthesis = type.indexOf('(');
        return normalize(parenthesis < 0 ? type : type.substring(0, parenthesis));
    }

    private static String blank(String expression) {
        return expression + " IS NULL OR TRIM(" + expression + ") = ''";
    }

    private static String qualified(String database, String table) {
        return quoted(database) + "." + quoted(table);
    }

    private static String quoted(String identifier) {
        return "`" + IdentifierSanitizer.requireValid(
                normalizeIdentifier(identifier), "dorisPrecheckIdentifier") + "`";
    }

    private static String normalizeIdentifier(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String escapeLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static Long requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
        return value;
    }

    public record CompiledPlan(List<RuleBatch> batches) {
        public CompiledPlan {
            batches = batches == null ? List.of() : List.copyOf(batches);
        }
    }

    public record RuleBatch(int index, List<String> fieldCodes, String insertSql) {
        public RuleBatch {
            fieldCodes = fieldCodes == null ? List.of() : List.copyOf(fieldCodes);
        }
    }

    private record TemporalRule(String expression, String errorType) {
    }

    private record NumericRange(String minimum, String maximum) {
    }
}
