package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.DfetlDatasetImportResultDto;
import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.DfetlField;
import com.dfygt.dfetl.server.medical.DatasetDefinition;
import com.dfygt.dfetl.server.medical.FieldDefinition;
import com.dfygt.dfetl.server.medical.MedicalRegistryReader;
import com.dfygt.dfetl.server.medical.SdvTypeMappingPolicy;
import com.dfygt.dfetl.server.repository.DfetlDatasetRepository;
import com.dfygt.dfetl.server.repository.DfetlFieldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 将医共体模型表同步为 dfetl_dataset / dfetl_field 当前标准快照。 */
@Service
@RequiredArgsConstructor
public class DfetlDatasetImportService {

    private final MedicalRegistryReader registryReader;
    private final DfetlDatasetRepository datasetRepository;
    private final DfetlFieldRepository fieldRepository;
    private final SdvTypeMappingPolicy typeMappingPolicy;
    private final DfetlPolicyService policyService;

    @Transactional
    public DfetlDatasetImportResultDto synchronizeFromMedicalRegistry() {
        List<DatasetDefinition> definitions = registryReader.loadDatasets();
        validateDefinitions(definitions);

        List<DfetlDataset> existingDatasets = datasetRepository.findAll();
        Map<String, DfetlDataset> existingByMedicalId = existingDatasets.stream()
                .filter(dataset -> hasText(dataset.getMedicalDatasetId()))
                .collect(Collectors.toMap(
                        dataset -> dataset.getMedicalDatasetId().trim(),
                        dataset -> dataset,
                        (left, right) -> {
                            throw new IllegalStateException("重复的 medicalDatasetId: " + left.getMedicalDatasetId());
                        },
                        LinkedHashMap::new));

        DfetlDatasetImportResultDto result = new DfetlDatasetImportResultDto();
        result.setTotalCount(definitions.size());
        Set<String> seenMedicalIds = new HashSet<>();
        Instant syncedAt = Instant.now();

        for (DatasetDefinition definition : definitions) {
            String medicalDatasetId = required(definition.shujujid(), "medicalDatasetId");
            seenMedicalIds.add(medicalDatasetId);
            DfetlDataset dataset = existingByMedicalId.get(medicalDatasetId);
            boolean created = dataset == null;
            if (created) {
                dataset = new DfetlDataset();
                dataset.setMedicalDatasetId(medicalDatasetId);
            }

            String previousHash = dataset.getContractHash();
            String previousStatus = dataset.getDatasetStatus();
            String nextHash = contractHash(definition);
            applyStandardDataset(dataset, definition, nextHash, syncedAt);
            boolean changed = created
                    || !Objects.equals(previousHash, nextHash)
                    || !"ACTIVE".equals(previousStatus);
            DfetlDataset saved = datasetRepository.save(dataset);
            synchronizeFields(saved, definition);
            policyService.initializeMissing(saved, definition.fields());
            if (created) {
                result.setCreatedCount(result.getCreatedCount() + 1);
            } else if (changed) {
                result.setUpdatedCount(result.getUpdatedCount() + 1);
            } else {
                result.setUnchangedCount(result.getUnchangedCount() + 1);
            }
        }

        for (DfetlDataset dataset : existingDatasets) {
            if (!seenMedicalIds.contains(dataset.getMedicalDatasetId())
                    && !"VOID".equals(dataset.getDatasetStatus())) {
                dataset.setDatasetStatus("VOID");
                dataset.setLastSyncedAt(syncedAt);
                datasetRepository.save(dataset);
                result.setVoidedCount(result.getVoidedCount() + 1);
            }
        }
        return result;
    }

    private void synchronizeFields(DfetlDataset dataset, DatasetDefinition definition) {
        if (dataset.getId() == null) {
            throw new IllegalStateException("保存标准数据集后未返回 id");
        }
        List<DfetlField> existingFields = fieldRepository
                .findByDatasetIdOrderByFieldOrderAscIdAsc(dataset.getId());
        Map<String, DfetlField> existingByMedicalId = new HashMap<>();
        for (DfetlField field : existingFields) {
            if (hasText(field.getMedicalFieldId())) {
                existingByMedicalId.put(field.getMedicalFieldId().trim(), field);
            }
        }

        List<DfetlField> synchronizedFields = new ArrayList<>();
        Set<String> seenFieldIds = new HashSet<>();
        List<FieldDefinition> definitions = definition.fields() == null ? List.of() : definition.fields();
        for (FieldDefinition fieldDefinition : definitions.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(field -> field.shunxuhao() == null ? 0 : field.shunxuhao()))
                .toList()) {
            String medicalFieldId = required(fieldDefinition.ziduanid(), "medicalFieldId");
            seenFieldIds.add(medicalFieldId);
            DfetlField field = existingByMedicalId.getOrDefault(medicalFieldId, new DfetlField());
            field.setDatasetId(dataset.getId());
            field.setMedicalFieldId(medicalFieldId);
            String fieldCode = requiredUpper(fieldDefinition.ziduandm(), "fieldCode");
            field.setFieldCode(fieldCode);
            field.setTargetFieldCode(fieldCode.toLowerCase(Locale.ROOT));
            field.setFieldName(trimToNull(fieldDefinition.ziduanmc()));
            field.setFieldOrder(fieldDefinition.shunxuhao());
            field.setStandardType(upperToNull(fieldDefinition.sdvType()));
            field.setStandardFormat(trimToNull(fieldDefinition.biaoshigs()));
            field.setDorisType(typeMappingPolicy.mapToDorisType(
                    fieldDefinition.sdvType(), fieldDefinition.biaoshigs(), fieldDefinition.primaryKey()));
            field.setPrimaryKey(fieldDefinition.primaryKey());
            field.setRequiredByStandard(fieldDefinition.notNull());
            field.setValueDomainCode(trimToNull(fieldDefinition.valueDomainCode()));
            field.setStandardVersion(trimToNull(definition.banben()));
            field.setFieldStatus("ACTIVE");
            synchronizedFields.add(field);
        }
        for (DfetlField field : existingFields) {
            if (!seenFieldIds.contains(field.getMedicalFieldId())) {
                field.setFieldStatus("VOID");
                synchronizedFields.add(field);
            }
        }
        if (!synchronizedFields.isEmpty()) {
            fieldRepository.saveAll(synchronizedFields);
        }
    }

    private static void applyStandardDataset(
            DfetlDataset dataset,
            DatasetDefinition definition,
            String contractHash,
            Instant syncedAt) {
        dataset.setMedicalDatasetId(required(definition.shujujid(), "medicalDatasetId"));
        dataset.setDatasetCode(requiredUpper(definition.shujujdm(), "datasetCode"));
        dataset.setDatasetName(trimToNull(definition.shujujmc()));
        dataset.setContractHash(contractHash);
        dataset.setDatasetStatus("ACTIVE");
        dataset.setLastSyncedAt(syncedAt);
    }

    private String contractHash(DatasetDefinition definition) {
        StringBuilder value = new StringBuilder();
        append(value, "SDV_DORIS_V1");
        append(value, definition.shujujid());
        append(value, upperToNull(definition.shujujdm()));
        append(value, definition.shujujmc());
        append(value, definition.banben());
        List<FieldDefinition> fields = definition.fields() == null ? List.of() : definition.fields();
        fields.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparingInt((FieldDefinition field) -> field.shunxuhao() == null ? 0 : field.shunxuhao())
                        .thenComparing(field -> Objects.toString(field.ziduanid(), "")))
                .forEach(field -> {
                    append(value, field.ziduanid());
                    append(value, upperToNull(field.ziduandm()));
                    append(value, field.ziduanmc());
                    append(value, upperToNull(field.sdvType()));
                    append(value, field.biaoshigs());
                    append(value, field.shunxuhao());
                    append(value, field.primaryKey());
                    append(value, field.notNull());
                    append(value, field.valueDomainCode());
                    append(value, requiredUpper(field.ziduandm(), "fieldCode").toLowerCase(Locale.ROOT));
                    append(value, typeMappingPolicy.mapToDorisType(
                            field.sdvType(), field.biaoshigs(), field.primaryKey()));
                });
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("计算医共体标准契约摘要失败", exception);
        }
    }

    private static void validateDefinitions(List<DatasetDefinition> definitions) {
        if (definitions == null) {
            throw new IllegalStateException("医共体标准数据集读取结果不能为空");
        }
        if (definitions.isEmpty()) {
            throw new IllegalStateException("医共体标准数据集为空，拒绝将本地标准快照批量作废");
        }
        Set<String> datasetIds = new HashSet<>();
        Set<String> datasetCodes = new HashSet<>();
        for (DatasetDefinition definition : definitions) {
            if (definition == null) {
                throw new IllegalStateException("医共体标准数据集定义不能包含 null");
            }
            String id = required(definition.shujujid(), "medicalDatasetId");
            String code = requiredUpper(definition.shujujdm(), "datasetCode");
            if (!code.startsWith("ODS_YL_")) {
                throw new IllegalStateException("标准数据集编码必须以 ODS_YL_ 开头: " + code);
            }
            if (!datasetIds.add(id)) {
                throw new IllegalStateException("医共体标准数据集 ID 重复: " + id);
            }
            if (!datasetCodes.add(code)) {
                throw new IllegalStateException("医共体标准数据集编码重复: " + code);
            }
            if (definition.fields() == null || definition.fields().isEmpty()) {
                throw new IllegalStateException("医共体标准数据集无有效字段: " + code);
            }
            Set<String> fieldIds = new HashSet<>();
            for (FieldDefinition field : definition.fields() == null ? List.<FieldDefinition>of() : definition.fields()) {
                if (field == null) {
                    continue;
                }
                String fieldId = required(field.ziduanid(), "medicalFieldId");
                if (!fieldIds.add(fieldId)) {
                    throw new IllegalStateException("医共体标准字段 ID 重复: " + code + "/" + fieldId);
                }
            }
        }
    }

    private static void append(StringBuilder value, Object part) {
        value.append(part == null ? "" : String.valueOf(part).trim()).append('\n');
    }

    private static String requiredUpper(String value, String field) {
        return required(value, field).toUpperCase(Locale.ROOT);
    }

    private static String required(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalStateException(field + " 不能为空");
        }
        return normalized;
    }

    private static String upperToNull(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
