package com.dfygt.dfetl.server.dto;

import java.util.Locale;

public record DfetlPrecheckExportRequest(
        String format,
        String businessPk,
        String fieldCode,
        String errorType,
        String severity) {

    public DfetlPrecheckExportRequest {
        format = format == null ? "CSV" : format.trim().toUpperCase(Locale.ROOT);
        if (!"CSV".equals(format) && !"XLSX".equals(format)) {
            throw new IllegalArgumentException("format 只支持 CSV 或 XLSX");
        }
    }

    public DfetlPrecheckIssueQuery toIssueQuery() {
        return new DfetlPrecheckIssueQuery(
                businessPk, fieldCode, errorType, severity, 0, 500);
    }
}
