package com.dfygt.dfetl.server.medical.precheck;

/**
 * 需要执行 SQL 才能得到结果的预检检查项。
 */
public record MedicalPrecheckCheck(
        MedicalPrecheckSeverity severity,
        String code,
        String field,
        String message,
        String sql,
        int timeoutSeconds
) {
    public MedicalPrecheckCheck(
            MedicalPrecheckSeverity severity,
            String code,
            String field,
            String message,
            String sql) {
        this(severity, code, field, message, sql, MedicalPrecheckOptions.defaults().normalizedTimeoutSeconds());
    }
}
