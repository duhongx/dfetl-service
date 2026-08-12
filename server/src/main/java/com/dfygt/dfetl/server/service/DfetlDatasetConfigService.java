package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.DfetlDatasetDto;
import com.dfygt.dfetl.server.dto.DfetlFieldDto;
import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.DfetlField;
import com.dfygt.dfetl.server.entity.DfetlMessagePolicy;
import com.dfygt.dfetl.server.entity.DfetlSyncPolicy;
import com.dfygt.dfetl.server.entity.DfetlValidationPolicy;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.repository.DfetlDatasetRepository;
import com.dfygt.dfetl.server.repository.DfetlFieldRepository;
import com.dfygt.dfetl.server.repository.DfetlMessagePolicyRepository;
import com.dfygt.dfetl.server.repository.DfetlSyncPolicyRepository;
import com.dfygt.dfetl.server.repository.DfetlValidationPolicyRepository;
import com.dfygt.dfetl.server.repository.InstitutionDatasetRouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 有效 ODS_YL_ 标准模型、分表策略摘要和机构路由预检概况查询。 */
@Service
@RequiredArgsConstructor
public class DfetlDatasetConfigService {

    private static final String REQUIRED_PREFIX = "ODS_YL_";

    private final DfetlDatasetRepository datasetRepository;
    private final DfetlFieldRepository fieldRepository;
    private final DfetlSyncPolicyRepository syncRepository;
    private final DfetlValidationPolicyRepository validationRepository;
    private final DfetlMessagePolicyRepository messageRepository;
    private final InstitutionDatasetRouteRepository routeRepository;

    @Transactional(readOnly = true)
    public List<DfetlDatasetDto> list() {
        return list(null);
    }

    @Transactional(readOnly = true)
    public List<DfetlDatasetDto> list(String search) {
        Map<Long, List<DfetlField>> fields = fieldRepository.findAll().stream()
                .filter(DfetlDatasetConfigService::isActive)
                .collect(Collectors.groupingBy(DfetlField::getDatasetId));
        Map<Long, DfetlSyncPolicy> syncPolicies = byDataset(syncRepository.findAll(), DfetlSyncPolicy::getDatasetId);
        Map<Long, DfetlValidationPolicy> validationPolicies = byDataset(validationRepository.findAll(), DfetlValidationPolicy::getDatasetId);
        Map<Long, DfetlMessagePolicy> messagePolicies = byDataset(messageRepository.findAll(), DfetlMessagePolicy::getDatasetId);
        Map<Long, List<InstitutionDatasetRoute>> routes = routeRepository.findAll().stream()
                .collect(Collectors.groupingBy(InstitutionDatasetRoute::getDatasetId));

        return datasetRepository.findAll(Sort.by(Sort.Direction.ASC, "datasetCode", "id")).stream()
                .filter(DfetlDatasetConfigService::isVisible)
                .filter(dataset -> matchesSearch(dataset, search))
                .map(dataset -> toDto(dataset, List.of(), fields.getOrDefault(dataset.getId(), List.of()),
                        syncPolicies.get(dataset.getId()), validationPolicies.get(dataset.getId()),
                        messagePolicies.get(dataset.getId()), routes.getOrDefault(dataset.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public DfetlDatasetDto get(Long id) {
        DfetlDataset dataset = getOrThrow(id);
        if (!isVisible(dataset)) {
            throw new NoSuchElementException("有效 ODS_YL_ 标准数据集不存在: " + id);
        }
        List<DfetlField> fields = fieldRepository.findByDatasetIdOrderByFieldOrderAscIdAsc(id).stream()
                .filter(DfetlDatasetConfigService::isActive).toList();
        return toDto(dataset, fields, fields,
                syncRepository.findByDatasetId(id).orElse(null),
                validationRepository.findByDatasetId(id).orElse(null),
                messageRepository.findByDatasetId(id).orElse(null),
                routeRepository.findByDatasetIdOrderByIdAsc(id));
    }

    private DfetlDataset getOrThrow(Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("id 必须为正整数");
        return datasetRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("标准数据集不存在: " + id));
    }

    private static DfetlDatasetDto toDto(DfetlDataset entity, List<DfetlField> detailFields,
                                         List<DfetlField> summaryFields, DfetlSyncPolicy sync,
                                         DfetlValidationPolicy validation, DfetlMessagePolicy message,
                                         List<InstitutionDatasetRoute> routes) {
        DfetlDatasetDto dto = new DfetlDatasetDto();
        dto.setId(entity.getId());
        dto.setMedicalDatasetId(entity.getMedicalDatasetId());
        dto.setDatasetCode(entity.getDatasetCode());
        dto.setDatasetName(entity.getDatasetName());
        dto.setContractHash(entity.getContractHash());
        dto.setDatasetStatus(entity.getDatasetStatus());
        dto.setLastSyncedAt(entity.getLastSyncedAt());
        dto.setFieldCount(summaryFields.size());
        dto.setPrimaryKeyCount((int) summaryFields.stream().filter(field -> Boolean.TRUE.equals(field.getPrimaryKey())).count());
        dto.setSyncPolicy(sync == null ? null : DfetlPolicyService.toDto(sync));
        dto.setValidationPolicy(validation == null ? null : DfetlPolicyService.toDto(validation));
        dto.setMessagePolicy(message == null ? null : DfetlPolicyService.toDto(message));
        dto.setRouteCount(routes.size());
        dto.setPassedRouteCount(countRoutes(routes, "PASSED"));
        dto.setFailedRouteCount(countRoutes(routes, "FAILED"));
        dto.setPendingRouteCount(routes.size() - dto.getPassedRouteCount() - dto.getFailedRouteCount());
        dto.setFields(detailFields.stream().map(DfetlDatasetConfigService::toDto).toList());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private static DfetlFieldDto toDto(DfetlField entity) {
        DfetlFieldDto dto = new DfetlFieldDto();
        dto.setId(entity.getId()); dto.setDatasetId(entity.getDatasetId());
        dto.setMedicalFieldId(entity.getMedicalFieldId()); dto.setFieldCode(entity.getFieldCode());
        dto.setTargetFieldCode(entity.getTargetFieldCode()); dto.setFieldName(entity.getFieldName());
        dto.setFieldOrder(entity.getFieldOrder()); dto.setStandardType(entity.getStandardType());
        dto.setStandardFormat(entity.getStandardFormat()); dto.setDorisType(entity.getDorisType());
        dto.setPrimaryKey(entity.getPrimaryKey()); dto.setRequiredByStandard(entity.getRequiredByStandard());
        dto.setValueDomainCode(entity.getValueDomainCode()); dto.setStandardVersion(entity.getStandardVersion());
        dto.setFieldStatus(entity.getFieldStatus()); dto.setCreatedAt(entity.getCreatedAt()); dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private static boolean isVisible(DfetlDataset dataset) {
        return dataset != null && "ACTIVE".equalsIgnoreCase(dataset.getDatasetStatus())
                && upper(dataset.getDatasetCode()).startsWith(REQUIRED_PREFIX);
    }

    private static boolean isActive(DfetlField field) {
        return field != null && "ACTIVE".equalsIgnoreCase(field.getFieldStatus());
    }

    private static boolean matchesSearch(DfetlDataset dataset, String search) {
        if (search == null || search.isBlank()) return true;
        String needle = upper(search);
        return upper(dataset.getDatasetCode()).contains(needle) || upper(dataset.getDatasetName()).contains(needle);
    }

    private static int countRoutes(List<InstitutionDatasetRoute> routes, String status) {
        return (int) routes.stream().filter(route -> status.equalsIgnoreCase(route.getValidationStatus())).count();
    }

    private static <T> Map<Long, T> byDataset(List<T> values, Function<T, Long> id) {
        return values.stream().collect(Collectors.toMap(id, Function.identity(), (left, right) -> right));
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
