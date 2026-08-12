package com.dfygt.dfetl.server.medical.precheck;

/**
 * 不需要执行 SQL 即可确认的预检发现。
 */
public record MedicalPrecheckFinding(
        MedicalPrecheckSeverity severity,
        String code,
        String field,
        String message
) {}
