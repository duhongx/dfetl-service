package com.dfygt.dfetl.server.medical.mapping;

import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckCheck;
import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckFinding;

import java.util.List;

/**
 * 单个源对象的医共体映射预检结果。
 */
public record MedicalMappingPrecheckResult(
        Long sourceDatasourceId,
        String sourceSchema,
        String sourceObject,
        MedicalMappingPrecheckStatus status,
        MedicalDatasetContract contract,
        List<MedicalPrecheckFinding> findings,
        List<MedicalPrecheckCheck> checks
) {}
