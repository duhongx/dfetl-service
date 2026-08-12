package com.dfygt.dfetl.server.medical.rule;

import com.dfygt.dfetl.server.medical.precheck.MedicalValueDomainRule;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 医共体值域字段预检/执行分流共用 SQL 规则。
 */
public record MedicalValueDomainIssueSqlRule(
        boolean blocking,
        String issueType,
        String domainPredicate,
        String predicate) {

    public static final String INVALID_VALUE_DOMAIN = "INVALID_VALUE_DOMAIN";

    public static MedicalValueDomainIssueSqlRule from(
            MedicalValueDomainRule rule,
            String expression,
            SourceDialectAdapter adapter) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("医共体值域字段表达式不能为空");
        }
        if (adapter == null) {
            throw new IllegalArgumentException("源库方言适配器不能为空");
        }
        if (rule == null || (!rule.strictBlock() && !rule.actualInvalidBlock())) {
            return new MedicalValueDomainIssueSqlRule(false, INVALID_VALUE_DOMAIN, "", "");
        }
        String trimmed = adapter.trim(adapter.castToText(expression));
        String domainPredicate = rule.actualInvalidBlock()
                ? trimmed + " IN (" + sqlLiterals(rule.allowedCodes()) + ")"
                : "NOT (" + trimmed + " IN (" + sqlLiterals(rule.allowedCodes()) + "))";
        return new MedicalValueDomainIssueSqlRule(
                true,
                INVALID_VALUE_DOMAIN,
                domainPredicate,
                "NOT (" + adapter.isBlank(expression) + ") AND " + domainPredicate);
    }

    private static String sqlLiterals(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return "''";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .sorted()
                .map(value -> "'" + escapeLiteral(value.trim()) + "'")
                .collect(Collectors.joining(", "));
    }

    private static String escapeLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
