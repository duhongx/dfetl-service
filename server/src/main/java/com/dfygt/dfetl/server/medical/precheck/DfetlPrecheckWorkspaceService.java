package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.dto.DfetlPrecheckRunDto;
import com.dfygt.dfetl.server.dto.DfetlPrecheckWorkspacePageDto;
import com.dfygt.dfetl.server.dto.DfetlPrecheckWorkspaceRowDto;
import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.repository.DfetlDatasetRepository;
import com.dfygt.dfetl.server.repository.DfetlPrecheckRunRepository;
import com.dfygt.dfetl.server.repository.InstitutionDatasetRouteRepository;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 为数据预检工作台批量组装路由、标准数据集和最近一次全量运行。 */
@Service
@RequiredArgsConstructor
public class DfetlPrecheckWorkspaceService {

    public static final String NOT_CHECKED = "NOT_CHECKED";
    public static final String RUNNING = "RUNNING";
    public static final String HAS_ERRORS = "HAS_ERRORS";
    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    private static final List<String> STATUSES = List.of(
            NOT_CHECKED, RUNNING, HAS_ERRORS, PASSED, FAILED);

    private final InstitutionDatasetRouteRepository routeRepository;
    private final DfetlDatasetRepository datasetRepository;
    private final InstitutionRepository institutionRepository;
    private final SourceDataSourceRepository sourceDataSourceRepository;
    private final DfetlPrecheckRunRepository runRepository;

    @Transactional(readOnly = true)
    public DfetlPrecheckWorkspacePageDto search(
            String search,
            Long routeId,
            Long institutionId,
            Long sourceDatasourceId,
            String sourceSchema,
            String status,
            int page,
            int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);

        List<InstitutionDatasetRoute> routes = routeRepository.findAll();
        List<Long> datasetIds = distinct(routes.stream()
                .map(InstitutionDatasetRoute::getDatasetId).toList());
        List<Long> institutionIds = distinct(routes.stream()
                .map(InstitutionDatasetRoute::getInstitutionId).toList());
        List<Long> sourceIds = distinct(routes.stream()
                .map(InstitutionDatasetRoute::getSourceDatasourceId).toList());
        List<Long> routeIds = distinct(routes.stream()
                .map(InstitutionDatasetRoute::getId).toList());

        if (routeIds.isEmpty()) {
            return new DfetlPrecheckWorkspacePageDto(
                    List.of(), safePage, safeSize, 0L, 0, Map.copyOf(emptyCounts(0)));
        }

        Map<Long, DfetlDataset> datasets = index(datasetRepository.findAllById(datasetIds), DfetlDataset::getId);
        Map<Long, Institution> institutions = index(
                institutionRepository.findAllById(institutionIds), Institution::getId);
        Map<Long, SourceDataSource> sources = index(
                sourceDataSourceRepository.findAllById(sourceIds), SourceDataSource::getId);
        Map<Long, DfetlPrecheckRun> latestRuns = index(
                runRepository.findLatestRouteFullByRouteIds(routeIds), DfetlPrecheckRun::getRouteId);

        String normalizedSearch = normalize(search);
        String normalizedSchema = normalize(sourceSchema);
        List<DfetlPrecheckWorkspaceRowDto> candidates = new ArrayList<>();
        for (InstitutionDatasetRoute route : routes) {
            DfetlDataset dataset = datasets.get(route.getDatasetId());
            Institution institution = institutions.get(route.getInstitutionId());
            SourceDataSource source = sources.get(route.getSourceDatasourceId());
            if (!isEligible(route, dataset, source)
                    || (routeId != null && !routeId.equals(route.getId()))
                    || (institutionId != null && !institutionId.equals(route.getInstitutionId()))
                    || (sourceDatasourceId != null
                    && !sourceDatasourceId.equals(route.getSourceDatasourceId()))
                    || (!normalizedSchema.isEmpty()
                    && !normalize(route.getSourceSchema()).equals(normalizedSchema))
                    || !matchesSearch(route, dataset, normalizedSearch)) {
                continue;
            }
            DfetlPrecheckRun latestRun = latestRuns.get(route.getId());
            candidates.add(toRow(route, dataset, institution, source, latestRun));
        }
        candidates.sort(Comparator
                .comparing(DfetlPrecheckWorkspaceRowDto::sourceSchema,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(DfetlPrecheckWorkspaceRowDto::sourceObject,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(DfetlPrecheckWorkspaceRowDto::institutionName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        Map<String, Long> counts = emptyCounts(candidates.size());
        for (DfetlPrecheckWorkspaceRowDto row : candidates) {
            counts.computeIfPresent(row.workspaceStatus(), (key, value) -> value + 1L);
        }

        String normalizedStatus = normalizeStatus(status);
        List<DfetlPrecheckWorkspaceRowDto> filtered = normalizedStatus == null
                ? candidates
                : candidates.stream()
                        .filter(row -> normalizedStatus.equals(row.workspaceStatus()))
                        .toList();
        long requestedOffset = (long) safePage * safeSize;
        int from = (int) Math.min(requestedOffset, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        int totalPages = filtered.isEmpty() ? 0 : (filtered.size() + safeSize - 1) / safeSize;
        return new DfetlPrecheckWorkspacePageDto(
                List.copyOf(filtered.subList(from, to)), safePage, safeSize,
                filtered.size(), totalPages, Map.copyOf(counts));
    }

    private static boolean isEligible(
            InstitutionDatasetRoute route, DfetlDataset dataset, SourceDataSource source) {
        return route.getId() != null
                && dataset != null
                && "ACTIVE".equalsIgnoreCase(dataset.getDatasetStatus())
                && normalize(dataset.getDatasetCode()).startsWith("ods_yl_")
                && source != null
                && "POSTGRESQL".equalsIgnoreCase(source.getType())
                && "VIEW".equalsIgnoreCase(route.getSourceObjectType());
    }

    private static boolean matchesSearch(
            InstitutionDatasetRoute route, DfetlDataset dataset, String search) {
        if (search.isEmpty()) {
            return true;
        }
        String qualifiedView = normalize(route.getSourceSchema()) + "." + normalize(route.getSourceObject());
        return qualifiedView.contains(search)
                || normalize(dataset.getDatasetCode()).contains(search)
                || normalize(dataset.getDatasetName()).contains(search);
    }

    private static DfetlPrecheckWorkspaceRowDto toRow(
            InstitutionDatasetRoute route,
            DfetlDataset dataset,
            Institution institution,
            SourceDataSource source,
            DfetlPrecheckRun latestRun) {
        return new DfetlPrecheckWorkspaceRowDto(
                route.getId(), route.getInstitutionId(),
                institution == null ? null : institution.getCode(),
                institution == null ? null : institution.getName(),
                dataset.getId(), dataset.getDatasetCode(), dataset.getDatasetName(),
                source.getId(), source.getName(), route.getSourceSchema(), route.getSourceObject(),
                route.getValidationStatus(), route.getValidationSummary(), route.getValidationDetailsJson(),
                null, workspaceStatus(latestRun),
                latestRun == null ? null : DfetlPrecheckRunDto.from(latestRun));
    }

    private static String workspaceStatus(DfetlPrecheckRun latestRun) {
        if (latestRun == null || "CANCELLED".equalsIgnoreCase(latestRun.getStatus())) {
            return NOT_CHECKED;
        }
        return switch (normalize(latestRun.getStatus())) {
            case "pending", "loading", "validating" -> RUNNING;
            case "has_errors" -> HAS_ERRORS;
            case "passed" -> PASSED;
            case "failed" -> FAILED;
            default -> NOT_CHECKED;
        };
    }

    private static Map<String, Long> emptyCounts(int all) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ALL", (long) all);
        STATUSES.forEach(status -> counts.put(status, 0L));
        return counts;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        String value = status.trim().toUpperCase(Locale.ROOT);
        return STATUSES.contains(value) ? value : null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<Long> distinct(List<Long> ids) {
        return new ArrayList<>(ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private static <T> Map<Long, T> index(List<T> values, Function<T, Long> key) {
        return values.stream().collect(Collectors.toMap(
                key, Function.identity(), (first, ignored) -> first, LinkedHashMap::new));
    }
}
