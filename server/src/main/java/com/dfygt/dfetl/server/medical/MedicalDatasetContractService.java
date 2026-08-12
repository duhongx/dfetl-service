package com.dfygt.dfetl.server.medical;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 将医共体注册表读取模型转换为同步链路使用的不可变字段契约。
 */
@Service
@RequiredArgsConstructor
public class MedicalDatasetContractService {

    private static final String INCREMENTAL_FIELD = "XIUGAISJ";
    private static final String DELETE_FIELD = "ZUOFEIBZ";

    private final MedicalRegistryReader registryReader;
    private final DatasetMatcher datasetMatcher;
    private final SdvTypeMappingPolicy sdvTypeMappingPolicy;

    public Optional<MedicalDatasetContract> matchBySourceObject(String sourceObjectName) {
        return datasetMatcher.match(sourceObjectName, registryReader.loadDatasets())
                .map(MatchResult::dataset)
                .map(this::toContract);
    }

    public MedicalDatasetContract loadByDatasetCode(String datasetCode) {
        if (datasetCode == null || datasetCode.isBlank()) {
            throw new IllegalArgumentException("医共体数据集代码不能为空");
        }
        return registryReader.loadDatasets().stream()
                .filter(dataset -> datasetCode.equalsIgnoreCase(dataset.shujujdm()))
                .findFirst()
                .map(this::toContract)
                .orElseThrow(() -> new IllegalArgumentException("医共体数据集不存在: " + datasetCode));
    }

    private MedicalDatasetContract toContract(DatasetDefinition dataset) {
        List<FieldDefinition> orderedFields = dataset.fields().stream()
                .sorted(Comparator.comparingInt(field -> field.shunxuhao() == null ? 0 : field.shunxuhao()))
                .toList();
        List<MedicalFieldContract> fields = orderedFields.stream()
                .map(this::toFieldContract)
                .toList();
        List<String> primaryKeys = orderedFields.stream()
                .filter(FieldDefinition::primaryKey)
                .map(FieldDefinition::ziduandm)
                .toList();

        return new MedicalDatasetContract(
                dataset.shujujdm(),
                dataset.shujujmc(),
                dataset.banben(),
                dataset.shujujdm().toLowerCase(Locale.ROOT),
                fields,
                primaryKeys,
                findFieldCode(orderedFields, INCREMENTAL_FIELD).orElse(null),
                findFieldCode(orderedFields, DELETE_FIELD).orElse(null)
        );
    }

    private MedicalFieldContract toFieldContract(FieldDefinition field) {
        String dorisType;
        try {
            dorisType = sdvTypeMappingPolicy.mapToDorisType(
                    field.sdvType(),
                    field.biaoshigs(),
                    field.primaryKey());
        } catch (MedicalFormatException ex) {
            throw ex.withField(field.ziduandm());
        }
        return new MedicalFieldContract(
                field.ziduandm(),
                field.ziduanmc(),
                field.sdvType(),
                field.biaoshigs(),
                field.shunxuhao(),
                field.primaryKey(),
                field.notNull(),
                field.ziduandm().toLowerCase(Locale.ROOT),
                dorisType,
                field.valueDomainCode()
        );
    }

    private static Optional<String> findFieldCode(List<FieldDefinition> fields, String code) {
        return fields.stream()
                .map(FieldDefinition::ziduandm)
                .filter(fieldCode -> code.equalsIgnoreCase(fieldCode))
                .findFirst();
    }
}
