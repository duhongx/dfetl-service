package com.dfygt.dfetl.server.dto;

public record DfetlPrecheckIssueDto(
        String rowId,
        String rowHash,
        String businessPk,
        String fieldCode,
        String errorType,
        String severity,
        String rawValue,
        String normalizedValue,
        String standardRule,
        String errorMessage) {
}
