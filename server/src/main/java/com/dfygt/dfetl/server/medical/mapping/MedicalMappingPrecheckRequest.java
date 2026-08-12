package com.dfygt.dfetl.server.medical.mapping;

import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckOptions;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 单个源对象的医共体 contract-driven 预检请求。
 */
public record MedicalMappingPrecheckRequest(
        @NotNull Long sourceDatasourceId,
        String sourceSchema,
        @NotBlank String sourceObject,
        String datasetCode,
        Map<String, String> fieldMapping,
        MedicalPrecheckOptions options
) {}
