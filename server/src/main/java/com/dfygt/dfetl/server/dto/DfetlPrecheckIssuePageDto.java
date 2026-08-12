package com.dfygt.dfetl.server.dto;

import java.util.List;

public record DfetlPrecheckIssuePageDto(
        List<DfetlPrecheckIssueDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public DfetlPrecheckIssuePageDto {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
