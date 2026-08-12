package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.DfetlField;
import com.dfygt.dfetl.server.entity.DfetlSyncPolicy;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import com.dfygt.dfetl.server.repository.DfetlDatasetRepository;
import com.dfygt.dfetl.server.repository.DfetlFieldRepository;
import com.dfygt.dfetl.server.repository.DfetlSyncPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/** 从 DFETL 已同步快照构造预检和执行共同使用的不可变字段契约。 */
@Service
@RequiredArgsConstructor
public class DfetlContractSnapshotService {

    private final DfetlDatasetRepository datasetRepository;
    private final DfetlFieldRepository fieldRepository;
    private final DfetlSyncPolicyRepository syncPolicyRepository;

    @Transactional(readOnly = true)
    public MedicalDatasetContract load(Long datasetId, String targetTable) {
        DfetlDataset dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new NoSuchElementException("标准数据集不存在: " + datasetId));
        if (!"ACTIVE".equals(dataset.getDatasetStatus())) {
            throw new IllegalStateException("标准数据集已作废，不能预检: " + dataset.getDatasetCode());
        }
        List<DfetlField> activeFields = fieldRepository.findByDatasetIdOrderByFieldOrderAscIdAsc(datasetId)
                .stream()
                .filter(field -> "ACTIVE".equals(field.getFieldStatus()))
                .toList();
        if (activeFields.isEmpty()) {
            throw new IllegalStateException("标准数据集没有有效字段: " + dataset.getDatasetCode());
        }
        DfetlSyncPolicy policy = syncPolicyRepository.findByDatasetId(datasetId).orElse(null);
        List<MedicalFieldContract> fields = activeFields.stream().map(this::toContract).toList();
        List<String> primaryKeys = fields.stream()
                .filter(MedicalFieldContract::primaryKey)
                .map(MedicalFieldContract::code)
                .toList();
        String version = activeFields.stream()
                .map(DfetlField::getStandardVersion)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
        return new MedicalDatasetContract(
                dataset.getDatasetCode().toUpperCase(Locale.ROOT),
                dataset.getDatasetName(),
                version,
                required(targetTable, "目标表"),
                fields,
                primaryKeys,
                policy == null ? null : policy.getIncrementalField(),
                null);
    }

    private MedicalFieldContract toContract(DfetlField field) {
        return new MedicalFieldContract(
                field.getFieldCode(),
                field.getFieldName(),
                field.getStandardType(),
                field.getStandardFormat(),
                field.getFieldOrder(),
                Boolean.TRUE.equals(field.getPrimaryKey()),
                Boolean.TRUE.equals(field.getRequiredByStandard()),
                required(field.getTargetFieldCode(), "目标字段").toLowerCase(Locale.ROOT),
                required(field.getDorisType(), "Doris 类型"),
                field.getValueDomainCode());
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + "不能为空");
        }
        return value.trim();
    }
}
