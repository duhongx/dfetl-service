package com.dfygt.dfetl.server.medical.mapping;

import java.util.List;

/**
 * 批量医共体映射计划响应。
 */
public record MedicalMappingPlanResponse(
        int total,
        int ready,
        int blocked,
        int unmatched,
        int existing,
        List<MedicalMappingPrecheckResult> results
) {}
