package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.InstitutionDatasetRouteDto;
import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.medical.precheck.DfetlPrecheckGateService;
import com.dfygt.dfetl.server.repository.DfetlDatasetRepository;
import com.dfygt.dfetl.server.repository.InstitutionDatasetRouteRepository;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class InstitutionDatasetRouteService {

    private final InstitutionDatasetRouteRepository routeRepository;
    private final InstitutionRepository institutionRepository;
    private final DfetlDatasetRepository datasetRepository;
    private final SourceDataSourceRepository sourceRepository;
    private final TargetDataSourceRepository targetRepository;
    private final InstitutionDatasetRouteValidationService validationService;
    private final DfetlPrecheckGateService precheckGateService;

    @Transactional(readOnly = true)
    public List<InstitutionDatasetRouteDto> list(Long institutionId, Long datasetId) {
        return routeRepository.findAll(Sort.by(Sort.Direction.ASC, "institutionId", "datasetId", "id"))
                .stream()
                .filter(route -> institutionId == null || institutionId.equals(route.getInstitutionId()))
                .filter(route -> datasetId == null || datasetId.equals(route.getDatasetId()))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public InstitutionDatasetRouteDto get(Long id) {
        return toDto(getOrThrow(id));
    }

    @Transactional
    public InstitutionDatasetRouteDto create(InstitutionDatasetRouteDto request) {
        References references = validateReferences(request);
        InstitutionDatasetRoute route = new InstitutionDatasetRoute();
        copyEditable(request, route, references.dataset());
        route.setEnabled(false);
        route.setValidationStatus("PENDING");
        route.setRouteRevision(1L);
        return toDto(routeRepository.save(route));
    }

    @Transactional
    public InstitutionDatasetRouteDto update(Long id, InstitutionDatasetRouteDto request) {
        InstitutionDatasetRoute route = getOrThrow(id);
        References references = validateReferences(request);
        String before = editableSignature(route);
        copyEditable(request, route, references.dataset());
        if (!before.equals(editableSignature(route))) {
            route.setRouteRevision(nextRevision(route.getRouteRevision()));
            resetValidation(route);
        }
        return toDto(routeRepository.save(route));
    }

    @Transactional
    public InstitutionDatasetRouteDto validate(Long id) {
        InstitutionDatasetRoute route = getOrThrow(id);
        DfetlDataset dataset = activeDataset(route.getDatasetId());
        validateStoredReferences(route);
        InstitutionDatasetRouteValidationService.Result result = validationService.validate(route);
        route.setEnabled(false);
        route.setValidationStatus(result.passed() ? "PASSED" : "FAILED");
        route.setValidationSummary(result.summary());
        route.setValidationDetailsJson(result.detailsJson());
        route.setLastValidatedAt(Instant.now());
        route.setValidatedContractHash(dataset.getContractHash());
        route.setValidatedRouteRevision(route.getRouteRevision());
        return toDto(routeRepository.save(route));
    }

    @Transactional
    public InstitutionDatasetRouteDto enable(Long id) {
        InstitutionDatasetRoute route = getOrThrow(id);
        DfetlDataset dataset = activeDataset(route.getDatasetId());
        validateStoredReferences(route);
        if (!"PASSED".equals(route.getValidationStatus())
                || route.getLastValidatedAt() == null
                || !Objects.equals(route.getValidatedRouteRevision(), route.getRouteRevision())
                || !Objects.equals(route.getValidatedContractHash(), dataset.getContractHash())) {
            throw new IllegalStateException("路由未通过当前版本校验，不能启用");
        }
        if (routeRepository.existsByInstitutionIdAndDatasetIdAndEnabledTrueAndIdNot(
                route.getInstitutionId(), route.getDatasetId(), route.getId())) {
            throw new IllegalStateException("同一机构和数据集已存在启用路由");
        }
        route.setEnabled(true);
        return toDto(routeRepository.save(route));
    }

    @Transactional
    public InstitutionDatasetRouteDto disable(Long id) {
        InstitutionDatasetRoute route = getOrThrow(id);
        route.setEnabled(false);
        return toDto(routeRepository.save(route));
    }

    @Transactional
    public void delete(Long id) {
        InstitutionDatasetRoute route = getOrThrow(id);
        if (Boolean.TRUE.equals(route.getEnabled())) {
            throw new IllegalStateException("启用中的路由不能删除，请先停用");
        }
        routeRepository.delete(route);
    }

    private References validateReferences(InstitutionDatasetRouteDto request) {
        if (request == null) {
            throw new IllegalArgumentException("机构数据集路由不能为空");
        }
        Institution institution = institutionRepository.findById(requiredId(request.getInstitutionId(), "institutionId"))
                .orElseThrow(() -> new NoSuchElementException("机构不存在: " + request.getInstitutionId()));
        if (!Boolean.TRUE.equals(institution.getEnabled())) {
            throw new IllegalArgumentException("机构已停用: " + request.getInstitutionId());
        }
        DfetlDataset dataset = activeDataset(requiredId(request.getDatasetId(), "datasetId"));
        SourceDataSource source = sourceRepository.findById(requiredId(request.getSourceDatasourceId(), "sourceDatasourceId"))
                .orElseThrow(() -> new NoSuchElementException("源数据源不存在: " + request.getSourceDatasourceId()));
        if (!Objects.equals(source.getInstitutionId(), institution.getId())) {
            throw new IllegalArgumentException("源数据源不属于所选机构");
        }
        requireNormal(source.getStatus(), "源数据源");
        TargetDataSource target = targetRepository.findById(requiredId(request.getTargetDatasourceId(), "targetDatasourceId"))
                .orElseThrow(() -> new NoSuchElementException("目标数据源不存在: " + request.getTargetDatasourceId()));
        requireNormal(target.getStatus(), "目标数据源");
        return new References(institution, dataset, source, target);
    }

    private void validateStoredReferences(InstitutionDatasetRoute route) {
        InstitutionDatasetRouteDto request = new InstitutionDatasetRouteDto();
        request.setInstitutionId(route.getInstitutionId());
        request.setDatasetId(route.getDatasetId());
        request.setSourceDatasourceId(route.getSourceDatasourceId());
        request.setTargetDatasourceId(route.getTargetDatasourceId());
        validateReferences(request);
    }

    private DfetlDataset activeDataset(Long id) {
        DfetlDataset dataset = datasetRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("标准数据集不存在: " + id));
        if (!"ACTIVE".equals(dataset.getDatasetStatus())) {
            throw new IllegalArgumentException("标准数据集已作废: " + id);
        }
        return dataset;
    }

    private static void copyEditable(
            InstitutionDatasetRouteDto request,
            InstitutionDatasetRoute route,
            DfetlDataset dataset) {
        route.setInstitutionId(requiredId(request.getInstitutionId(), "institutionId"));
        route.setDatasetId(requiredId(request.getDatasetId(), "datasetId"));
        route.setSourceDatasourceId(requiredId(request.getSourceDatasourceId(), "sourceDatasourceId"));
        route.setSourceSchema(required(request.getSourceSchema(), "sourceSchema"));
        route.setSourceObject(required(request.getSourceObject(), "sourceObject"));
        route.setSourceObjectType(upperDefault(request.getSourceObjectType(), "VIEW"));
        route.setTargetDatasourceId(requiredId(request.getTargetDatasourceId(), "targetDatasourceId"));
        route.setTargetTable(defaultText(request.getTargetTable(), dataset.getDatasetCode().toLowerCase(Locale.ROOT)));
    }

    private InstitutionDatasetRouteDto toDto(InstitutionDatasetRoute route) {
        InstitutionDatasetRouteDto dto = new InstitutionDatasetRouteDto();
        dto.setId(route.getId());
        dto.setInstitutionId(route.getInstitutionId());
        institutionRepository.findById(route.getInstitutionId()).ifPresent(institution -> {
            dto.setInstitutionCode(institution.getCode());
            dto.setInstitutionName(institution.getName());
        });
        dto.setDatasetId(route.getDatasetId());
        datasetRepository.findById(route.getDatasetId()).ifPresent(dataset -> {
            dto.setDatasetCode(dataset.getDatasetCode());
            dto.setDatasetName(dataset.getDatasetName());
        });
        dto.setSourceDatasourceId(route.getSourceDatasourceId());
        sourceRepository.findById(route.getSourceDatasourceId()).ifPresent(source ->
                dto.setSourceDatasourceName(source.getName()));
        dto.setSourceSchema(route.getSourceSchema());
        dto.setSourceObject(route.getSourceObject());
        dto.setSourceObjectType(route.getSourceObjectType());
        dto.setTargetDatasourceId(route.getTargetDatasourceId());
        targetRepository.findById(route.getTargetDatasourceId()).ifPresent(target ->
                dto.setTargetDatasourceName(target.getName()));
        dto.setTargetTable(route.getTargetTable());
        dto.setEnabled(route.getEnabled());
        dto.setValidationStatus(route.getValidationStatus());
        dto.setValidationSummary(route.getValidationSummary());
        dto.setValidationDetailsJson(route.getValidationDetailsJson());
        dto.setLastValidatedAt(route.getLastValidatedAt());
        dto.setValidatedContractHash(route.getValidatedContractHash());
        dto.setValidatedRouteRevision(route.getValidatedRouteRevision());
        dto.setRouteRevision(route.getRouteRevision());
        dto.setCreatedAt(route.getCreatedAt());
        dto.setUpdatedAt(route.getUpdatedAt());
        return dto;
    }

    private InstitutionDatasetRoute getOrThrow(Long id) {
        return routeRepository.findById(requiredId(id, "id"))
                .orElseThrow(() -> new NoSuchElementException("机构数据集路由不存在: " + id));
    }

    private static void resetValidation(InstitutionDatasetRoute route) {
        route.setEnabled(false);
        route.setValidationStatus("PENDING");
        route.setValidationSummary(null);
        route.setValidationDetailsJson(null);
        route.setLastValidatedAt(null);
        route.setValidatedContractHash(null);
        route.setValidatedRouteRevision(null);
    }

    private static String editableSignature(InstitutionDatasetRoute route) {
        return String.join("\u001f",
                Objects.toString(route.getInstitutionId(), ""),
                Objects.toString(route.getDatasetId(), ""),
                Objects.toString(route.getSourceDatasourceId(), ""),
                Objects.toString(route.getSourceSchema(), ""),
                Objects.toString(route.getSourceObject(), ""),
                Objects.toString(route.getSourceObjectType(), ""),
                Objects.toString(route.getTargetDatasourceId(), ""),
                Objects.toString(route.getTargetTable(), ""));
    }

    private static void requireNormal(String status, String label) {
        if (!"NORMAL".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException(label + "状态不是 NORMAL");
        }
    }

    private static Long requiredId(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " 必须为正整数");
        }
        return value;
    }

    private static long nextRevision(Long revision) {
        return revision == null ? 1L : revision + 1L;
    }

    private static String upperDefault(String value, String defaultValue) {
        return defaultText(value, defaultValue).toUpperCase(Locale.ROOT);
    }

    private static String defaultText(String value, String defaultValue) {
        String normalized = trimToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private static String required(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record References(
            Institution institution,
            DfetlDataset dataset,
            SourceDataSource source,
            TargetDataSource target) {
    }
}
