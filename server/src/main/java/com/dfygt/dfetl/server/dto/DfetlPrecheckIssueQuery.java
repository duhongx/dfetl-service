package com.dfygt.dfetl.server.dto;

/** Doris 预检问题筛选；排序固定为 row_id/field_code/error_type，避免任意 SQL 标识符。 */
public record DfetlPrecheckIssueQuery(
        String businessPk,
        String fieldCode,
        String errorType,
        String severity,
        int page,
        int size) {

    public DfetlPrecheckIssueQuery {
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), 500);
        businessPk = trimToNull(businessPk);
        fieldCode = trimToNull(fieldCode);
        errorType = trimToNull(errorType);
        severity = trimToNull(severity);
        if (severity != null && !"BLOCKER".equalsIgnoreCase(severity)
                && !"WARNING".equalsIgnoreCase(severity)) {
            throw new IllegalArgumentException("severity 只支持 BLOCKER 或 WARNING");
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
