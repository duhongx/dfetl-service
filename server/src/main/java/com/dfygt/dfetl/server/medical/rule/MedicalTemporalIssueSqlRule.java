package com.dfygt.dfetl.server.medical.rule;

import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.medical.MedicalTemporalRule;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;

import java.util.Locale;

/**
 * 医共体 D/DT 字段严格预检/执行分流共用 SQL 规则。
 */
public record MedicalTemporalIssueSqlRule(
        boolean temporal,
        String issueType,
        String convertedExpression,
        String invalidPredicate,
        String standardRule) {

    public static final String INVALID_DATE = "INVALID_DATE";
    public static final String INVALID_DATETIME = "INVALID_DATETIME";

    public static MedicalTemporalIssueSqlRule from(
            MedicalFieldContract field,
            String expression,
            SourceDialectAdapter adapter) {
        if (field == null) {
            throw new IllegalArgumentException("医共体字段契约不能为空");
        }
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("医共体时间字段表达式不能为空");
        }
        if (adapter == null) {
            throw new IllegalArgumentException("源库方言适配器不能为空");
        }
        String type = field.sdvType() == null ? "" : field.sdvType().trim().toUpperCase(Locale.ROOT);
        if (!"D".equals(type) && !"DT".equals(type)) {
            return new MedicalTemporalIssueSqlRule(false, "", "", "", field.format());
        }
        String converted = adapter.safeTemporal(expression, MedicalTemporalRule.require(type, field.format()));
        String issueType = "D".equals(type) ? INVALID_DATE : INVALID_DATETIME;
        String invalidPredicate = "NOT (" + adapter.isBlank(expression) + ") AND (" + converted + ") IS NULL";
        return new MedicalTemporalIssueSqlRule(true, issueType, converted, invalidPredicate, field.format());
    }
}
