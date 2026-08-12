package com.dfygt.dfetl.server.medical;

import java.util.List;

/**
 * 医共体数据集契约，是 contract-driven 同步任务的字段真源。
 */
public record MedicalDatasetContract(
        String datasetCode,
        String datasetName,
        String version,
        String targetTable,
        List<MedicalFieldContract> fields,
        List<String> primaryKeys,
        String incrementalField,
        String deleteField
) {}
