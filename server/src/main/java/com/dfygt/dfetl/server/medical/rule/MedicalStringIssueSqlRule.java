package com.dfygt.dfetl.server.medical.rule;

import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 医共体字符串字段 Doris 物理容量预检/执行分流共用 SQL 规则。
 */
public record MedicalStringIssueSqlRule(
        boolean stringCapacityCheck,
        String issueType,
        Integer capacity,
        String predicate,
        String standardRule) {

    public static final String FIELD_TOO_LONG = "FIELD_TOO_LONG";

    private static final Pattern VARCHAR_TYPE = Pattern.compile("(?i)VARCHAR\\s*\\(\\s*(\\d+)\\s*\\)");

    public static MedicalStringIssueSqlRule from(
            MedicalFieldContract field,
            String expression,
            SourceDialectAdapter adapter) {
        if (field == null) {
            throw new IllegalArgumentException("医共体字段契约不能为空");
        }
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("医共体字符串字段表达式不能为空");
        }
        if (adapter == null) {
            throw new IllegalArgumentException("源库方言适配器不能为空");
        }
        Integer capacity = isStringType(field.sdvType()) ? varcharByteCapacity(field.dorisType()) : null;
        if (capacity == null) {
            return new MedicalStringIssueSqlRule(false, FIELD_TOO_LONG, null, "", "");
        }
        String predicate = "NOT (" + adapter.isBlank(expression) + ") AND "
                + adapter.byteLength(adapter.castToText(expression)) + " > " + capacity;
        return new MedicalStringIssueSqlRule(
                true,
                FIELD_TOO_LONG,
                capacity,
                predicate,
                "Doris " + field.dorisType());
    }

    private static boolean isStringType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        return "S1".equals(normalized) || "S2".equals(normalized) || "S3".equals(normalized)
                || "L".equals(normalized) || "BY".equals(normalized);
    }

    private static Integer varcharByteCapacity(String dorisType) {
        Matcher matcher = VARCHAR_TYPE.matcher(dorisType == null ? "" : dorisType.trim());
        if (!matcher.matches()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }
}
