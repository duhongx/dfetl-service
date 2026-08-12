package com.dfygt.dfetl.server.medical.quality;

import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.precheck.MedicalValueDomainRule;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;

import java.util.List;
import java.util.Map;

public record MedicalRowQualityRequest(
        String sourceSchema,
        String sourceObject,
        MedicalDatasetContract contract,
        List<ColumnInfo> sourceColumns,
        SourceDialectAdapter dialectAdapter,
        Map<String, String> fieldMapping,
        String baseWhere,
        Map<String, MedicalValueDomainRule> valueDomainRulesByField
) {

    public MedicalRowQualityRequest(
            String sourceSchema,
            String sourceObject,
            MedicalDatasetContract contract,
            List<ColumnInfo> sourceColumns,
            SourceDialectAdapter dialectAdapter,
            Map<String, String> fieldMapping,
            String baseWhere) {
        this(sourceSchema, sourceObject, contract, sourceColumns, dialectAdapter, fieldMapping, baseWhere, Map.of());
    }
}
