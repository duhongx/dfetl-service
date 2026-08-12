package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;

import java.util.List;
import java.util.Map;

/**
 * 医共体预检计划生成请求。
 */
public record MedicalPrecheckRequest(
        String sourceSchema,
        String sourceObject,
        MedicalDatasetContract contract,
        List<ColumnInfo> sourceColumns,
        SourceDialectAdapter dialectAdapter,
        Map<String, String> fieldMapping,
        MedicalPrecheckOptions options
) {}
