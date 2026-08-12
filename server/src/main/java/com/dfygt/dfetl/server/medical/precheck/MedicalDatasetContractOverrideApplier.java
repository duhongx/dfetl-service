package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将当前 dfetl_field 运行配置覆盖到最新医共体 contract。
 */
@Component
public class MedicalDatasetContractOverrideApplier {

    public MedicalDatasetContract apply(
            MedicalDatasetContract contract,
            List<MedicalDatasetFieldOverride> overrides) {
        if (contract == null || overrides == null || overrides.isEmpty()) {
            return contract;
        }
        Map<String, MedicalDatasetFieldOverride> overrideByField = new LinkedHashMap<>();
        for (MedicalDatasetFieldOverride override : overrides) {
            String fieldCode = normalize(override == null ? null : override.fieldCode());
            if (!fieldCode.isBlank()) {
                overrideByField.putIfAbsent(fieldCode, override);
            }
        }
        if (overrideByField.isEmpty()) {
            return contract;
        }
        List<MedicalFieldContract> fields = contract.fields() == null ? List.of() : contract.fields().stream()
                .map(field -> applyFieldOverride(field, overrideByField.get(normalize(field.code()))))
                .filter(field -> field != null && overrideByField.containsKey(normalize(field.code())))
                .toList();
        List<String> primaryKeys = fields.stream()
                .filter(MedicalFieldContract::primaryKey)
                .map(MedicalFieldContract::code)
                .toList();
        return new MedicalDatasetContract(
                contract.datasetCode(),
                contract.datasetName(),
                contract.version(),
                contract.targetTable(),
                fields,
                primaryKeys,
                contract.incrementalField(),
                contract.deleteField());
    }

    private static MedicalFieldContract applyFieldOverride(
            MedicalFieldContract field,
            MedicalDatasetFieldOverride override) {
        if (field == null || override == null) {
            return null;
        }
        return new MedicalFieldContract(
                firstNonBlank(override.fieldCode(), field.code()),
                firstNonBlank(override.fieldName(), field.name()),
                firstNonBlank(override.standardType(), field.sdvType()),
                firstNonBlank(override.standardFormat(), field.format()),
                field.order(),
                override.primaryKey() == null ? field.primaryKey() : override.primaryKey(),
                override.requiredByStandard() == null ? field.notNull() : override.requiredByStandard(),
                firstNonBlank(override.targetColumn(), field.dorisColumn()),
                firstNonBlank(override.targetType(), field.dorisType()),
                firstNonBlank(override.valueDomainCode(), field.valueDomainCode()));
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
