package com.dfygt.dfetl.server.medical;

import java.util.List;

/**
 * 数据集字段对比结果。
 */
public record DatasetCompareResult(
        String datasetCode,
        String datasetName,
        int registryFieldCount,
        int sourceFieldCount,
        List<FieldCompareEntry> fields,
        Summary summary
) {
    public record FieldCompareEntry(
            String name,
            boolean inRegistry,
            boolean inSource,
            String registryType,
            String sourceType,
            boolean pkInRegistry,
            boolean notNullInRegistry,
            String status
    ) {}

    public record Summary(
            int matched,
            int missingInSource,
            int extraInSource
    ) {}
}
