package com.dfygt.dfetl.server.medical.mapping;

import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckOptions;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * 批量源对象的医共体 contract-driven 计划请求。
 */
public record MedicalMappingBatchPlanRequest(
        @NotNull Long sourceDatasourceId,
        String sourceSchema,
        List<String> sourceObjects,
        Map<String, Map<String, String>> fieldMappings,
        MedicalPrecheckOptions options
) {}
