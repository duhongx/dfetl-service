package com.dfygt.dfetl.server.dto;

public record DfetlPrecheckSummaryDto(
        String fieldCode,
        String errorType,
        String severity,
        Long issueCount,
        Long affectedRows) {
}
