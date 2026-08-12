package com.dfygt.dfetl.server.medical.rule;

import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.medical.MedicalFormatParser;
import com.dfygt.dfetl.server.medical.MedicalNumericRule;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;

/**
 * 医共体 N 字段严格预检/执行分流共用 SQL 规则。
 */
public record MedicalNumericIssueSqlRule(
        MedicalNumericRule numericRule,
        String nonBlankPredicate,
        String lexicalPredicate,
        String capacityPredicate,
        String invalidNumberPredicate,
        String capacityExceededPredicate) {

    public static MedicalNumericIssueSqlRule from(
            MedicalFieldContract field,
            String expression,
            SourceDialectAdapter adapter) {
        if (field == null) {
            throw new IllegalArgumentException("医共体字段契约不能为空");
        }
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("医共体数值字段表达式不能为空");
        }
        if (adapter == null) {
            throw new IllegalArgumentException("源库方言适配器不能为空");
        }
        MedicalNumericRule numericRule = MedicalFormatParser.requireNumeric(field.sdvType(), field.format());
        String nonBlank = "NOT (" + adapter.isBlank(expression) + ")";
        String lexical = adapter.lexicalDecimalPredicate(expression);
        String capacity = adapter.decimalCapacityPredicate(expression, numericRule);
        return new MedicalNumericIssueSqlRule(
                numericRule,
                nonBlank,
                lexical,
                capacity,
                nonBlank + " AND NOT (" + lexical + ")",
                nonBlank + " AND (" + lexical + ") AND NOT (" + capacity + ")");
    }
}
