package com.dfygt.dfetl.server.dto;

import java.util.List;
import java.util.Map;

/** 数据预检工作台分页结果；状态计数基于同一组非状态筛选条件计算。 */
public record DfetlPrecheckWorkspacePageDto(
        List<DfetlPrecheckWorkspaceRowDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Map<String, Long> statusCounts) {
}
