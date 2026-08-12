package com.dfygt.dfetl.server.medical.rule;

import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;

/**
 * 医共体标准非空字段严格预检/执行分流共用 SQL 规则。
 */
public record MedicalRequiredIssueSqlRule(
        boolean required,
        String issueType,
        String predicate,
        String standardRule) {

    public static final String REQUIRED_FIELD_NULL = "REQUIRED_FIELD_NULL";

    public static MedicalRequiredIssueSqlRule from(
            MedicalFieldContract field,
            String expression,
            SourceDialectAdapter adapter) {
        if (field == null) {
            throw new IllegalArgumentException("医共体字段契约不能为空");
        }
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("医共体字段表达式不能为空");
        }
        if (adapter == null) {
            throw new IllegalArgumentException("源库方言适配器不能为空");
        }
        boolean required = !field.primaryKey() && field.notNull();
        return new MedicalRequiredIssueSqlRule(
                required,
                REQUIRED_FIELD_NULL,
                required ? adapter.isBlank(expression) : "",
                field.format());
    }
}
