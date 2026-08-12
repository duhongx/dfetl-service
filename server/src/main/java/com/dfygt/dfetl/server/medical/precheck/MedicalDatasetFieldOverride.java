package com.dfygt.dfetl.server.medical.precheck;

/**
 * dfetl_field 对最新医共体字段定义的运行期覆盖项。
 */
public record MedicalDatasetFieldOverride(
        String fieldCode,
        String fieldName,
        String sourceColumn,
        String targetColumn,
        String targetType,
        String standardType,
        String standardFormat,
        String standardVersion,
        Boolean primaryKey,
        Boolean upsertKey,
        Boolean requiredByStandard,
        String valueDomainCode,
        String valueDomainSource,
        String valueDomainVersion,
        String valueDomainMode
) {
}
