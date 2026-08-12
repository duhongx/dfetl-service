package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.repository.DfetlDatasetRepository;
import com.dfygt.dfetl.server.repository.DfetlFieldRepository;
import com.dfygt.dfetl.server.repository.InstitutionDatasetRouteRepository;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class InstitutionDatasetRouteResolver {

    private final InstitutionRepository institutionRepository;
    private final DfetlDatasetRepository datasetRepository;
    private final InstitutionDatasetRouteRepository routeRepository;
    private final SourceDataSourceRepository sourceRepository;
    private final TargetDataSourceRepository targetRepository;
    private final DfetlFieldRepository fieldRepository;
    private final com.dfygt.dfetl.server.medical.precheck.DfetlPrecheckGateService precheckGateService;

    @Transactional(
            readOnly = true,
            noRollbackFor = {
                    IllegalArgumentException.class,
                    IllegalStateException.class,
                    NoSuchElementException.class
            })
    public ResolvedDatasetRoute resolve(Long institutionId, Long datasetId, Long routeId) {
        Institution institution = institutionRepository.findById(requiredId(institutionId, "institutionId"))
                .orElseThrow(() -> new NoSuchElementException("INSTITUTION_NOT_FOUND: " + institutionId));
        if (!Boolean.TRUE.equals(institution.getEnabled())) {
            throw new IllegalStateException("INSTITUTION_DISABLED: " + institutionId);
        }
        DfetlDataset dataset = datasetRepository.findById(requiredId(datasetId, "datasetId"))
                .orElseThrow(() -> new NoSuchElementException("DATASET_NOT_FOUND: " + datasetId));
        if (!"ACTIVE".equals(dataset.getDatasetStatus())) {
            throw new IllegalStateException("DATASET_VOID: " + datasetId);
        }

        InstitutionDatasetRoute route = routeId == null
                ? routeRepository.findByInstitutionIdAndDatasetIdAndEnabledTrue(institutionId, datasetId)
                        .orElseThrow(() -> new IllegalStateException(
                                "ROUTE_NOT_FOUND: institutionId=" + institutionId + ", datasetId=" + datasetId))
                : routeRepository.findById(requiredId(routeId, "routeId"))
                        .orElseThrow(() -> new NoSuchElementException("ROUTE_NOT_FOUND: " + routeId));
        if (!Objects.equals(route.getInstitutionId(), institutionId)
                || !Objects.equals(route.getDatasetId(), datasetId)) {
            throw new IllegalStateException("ROUTE_IDENTITY_MISMATCH: " + route.getId());
        }
        if (!Boolean.TRUE.equals(route.getEnabled()) || !"PASSED".equals(route.getValidationStatus())) {
            throw new IllegalStateException("ROUTE_NOT_ENABLED: " + route.getId());
        }
        if (route.getLastValidatedAt() == null
                || !Objects.equals(route.getValidatedContractHash(), dataset.getContractHash())
                || !Objects.equals(route.getValidatedRouteRevision(), route.getRouteRevision())) {
            throw new IllegalStateException("ROUTE_STALE: " + route.getId());
        }

        SourceDataSource source = sourceRepository.findById(route.getSourceDatasourceId())
                .orElseThrow(() -> new NoSuchElementException("SOURCE_DATASOURCE_NOT_FOUND: " + route.getSourceDatasourceId()));
        if (!Objects.equals(source.getInstitutionId(), institutionId)
                || !"NORMAL".equalsIgnoreCase(source.getStatus())) {
            throw new IllegalStateException("SOURCE_DATASOURCE_UNAVAILABLE: " + source.getId());
        }
        TargetDataSource target = targetRepository.findById(route.getTargetDatasourceId())
                .orElseThrow(() -> new NoSuchElementException("TARGET_DATASOURCE_NOT_FOUND: " + route.getTargetDatasourceId()));
        if (!"NORMAL".equalsIgnoreCase(target.getStatus())) {
            throw new IllegalStateException("TARGET_DATASOURCE_UNAVAILABLE: " + target.getId());
        }
        var fields = fieldRepository.findByDatasetIdOrderByFieldOrderAscIdAsc(datasetId).stream()
                .filter(field -> "ACTIVE".equalsIgnoreCase(field.getFieldStatus()))
                .toList();
        if (fields.isEmpty()) {
            throw new IllegalStateException("DATASET_FIELDS_EMPTY: " + datasetId);
        }
        return new ResolvedDatasetRoute(
                institution, dataset, route, source, target, fields);
    }

    private static Long requiredId(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " 必须为正整数");
        }
        return value;
    }
}
