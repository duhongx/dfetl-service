package com.dfygt.dfetl.server.medical.quality;

public record MedicalDirtyFieldIssue(
        String fieldCode,
        String fieldName,
        String sourceColumn,
        String targetColumn,
        String errorType,
        String standardRule,
        String rawValue,
        String normalizedValue,
        String message,
        String severity,
        String valueDomainCode,
        String valueDomainMode,
        Integer valueDomainAllowedCount
) {

    public MedicalDirtyFieldIssue(
            String fieldCode,
            String fieldName,
            String sourceColumn,
            String targetColumn,
            String errorType,
            String standardRule,
            String rawValue,
            String normalizedValue,
            String message,
            String severity) {
        this(fieldCode, fieldName, sourceColumn, targetColumn, errorType, standardRule,
                rawValue, normalizedValue, message, severity, null, null, null);
    }
}
