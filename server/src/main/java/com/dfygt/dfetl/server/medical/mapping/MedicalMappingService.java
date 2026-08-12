package com.dfygt.dfetl.server.medical.mapping;

import com.dfygt.dfetl.server.dto.SourceDataSourceDto;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.medical.MedicalContractSnapshotCodec;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalDatasetContractService;
import com.dfygt.dfetl.server.medical.MedicalFormatException;
import com.dfygt.dfetl.server.medical.MedicalRegistryDataItemException;
import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckFinding;
import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckOptions;
import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckPlan;
import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckRequest;
import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckService;
import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckSeverity;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapterResolver;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.service.SourceDataSourceService;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 医共体 contract-driven 映射 API 编排服务。
 */
@Service
@RequiredArgsConstructor
public class MedicalMappingService {

    private final SourceDataSourceService sourceDataSourceService;
    private final MedicalDatasetContractService contractService;
    private final SourceDialectAdapterResolver dialectAdapterResolver;
    private final MedicalPrecheckService precheckService;

    @Autowired(required = false)
    private SyncTaskRepository syncTaskRepository;

    public MedicalMappingPrecheckResult precheck(MedicalMappingPrecheckRequest request) {
        validateSingleRequest(request);
        SourceDataSourceDto datasource = sourceDataSourceService.findById(request.sourceDatasourceId());
        MedicalDatasetContract contract;
        try {
            contract = resolveContractOrThrow(request.sourceObject(), request.datasetCode());
        } catch (MedicalRegistryDataItemException ex) {
            return missingDataItemResult(
                    request.sourceDatasourceId(), request.sourceSchema(), request.sourceObject(), ex);
        } catch (MedicalFormatException ex) {
            return unsupportedFormatResult(
                    request.sourceDatasourceId(), request.sourceSchema(), request.sourceObject(), null, ex);
        }
        List<ColumnInfo> sourceColumns = sourceDataSourceService.listColumns(
                request.sourceDatasourceId(), request.sourceSchema(), request.sourceObject());
        return buildPrecheckResult(
                request.sourceDatasourceId(),
                request.sourceSchema(),
                request.sourceObject(),
                datasource,
                contract,
                sourceColumns,
                request.fieldMapping(),
                request.options());
    }

    /**
     * 任务执行门禁必须使用任务冻结契约，并执行与 Reader 相同的 hash/drift 校验。
     */
    public MedicalMappingPrecheckResult precheckForTask(
            SyncTask task,
            MedicalMappingPrecheckRequest request) {
        validateSingleRequest(request);
        SourceDataSourceDto datasource = sourceDataSourceService.findById(request.sourceDatasourceId());
        MedicalDatasetContract contract =
                MedicalContractSnapshotCodec.resolveForTask(task, contractService, null);
        List<ColumnInfo> sourceColumns = sourceDataSourceService.listColumns(
                request.sourceDatasourceId(), request.sourceSchema(), request.sourceObject());
        return buildPrecheckResult(
                request.sourceDatasourceId(),
                request.sourceSchema(),
                request.sourceObject(),
                datasource,
                contract,
                sourceColumns,
                request.fieldMapping(),
                request.options());
    }

    public MedicalMappingPlanResponse plan(MedicalMappingBatchPlanRequest request) {
        validateBatchRequest(request);
        SourceDataSourceDto datasource = sourceDataSourceService.findById(request.sourceDatasourceId());
        List<MedicalMappingPrecheckResult> results = new ArrayList<>();
        for (String sourceObject : resolveSourceObjects(request)) {
            String normalizedSourceObject = normalizeSourceObject(sourceObject);
            Optional<MedicalDatasetContract> contract;
            try {
                contract = contractService.matchBySourceObject(normalizedSourceObject);
            } catch (MedicalRegistryDataItemException ex) {
                results.add(missingDataItemResult(
                        request.sourceDatasourceId(), request.sourceSchema(), normalizedSourceObject, ex));
                continue;
            } catch (MedicalFormatException ex) {
                results.add(unsupportedFormatResult(
                        request.sourceDatasourceId(), request.sourceSchema(), normalizedSourceObject, null, ex));
                continue;
            }
            if (contract.isEmpty()) {
                results.add(unmatchedResult(
                        request.sourceDatasourceId(),
                        request.sourceSchema(),
                        normalizedSourceObject));
                continue;
            }
            List<MedicalPrecheckFinding> formatFindings = precheckService.validateContractFormats(contract.get());
            if (formatFindings.stream().anyMatch(
                    finding -> finding.severity() == MedicalPrecheckSeverity.BLOCKER)) {
                results.add(blockedFormatResult(
                        request.sourceDatasourceId(), request.sourceSchema(), normalizedSourceObject,
                        contract.get(), formatFindings));
                continue;
            }
            if (hasExistingTask(request.sourceDatasourceId(), request.sourceSchema(), normalizedSourceObject)) {
                results.add(existingResult(
                        request.sourceDatasourceId(),
                        request.sourceSchema(),
                        normalizedSourceObject,
                        contract.get()));
                continue;
            }
            Map<String, String> fieldMapping = request.fieldMappings() == null
                    ? Map.of()
                    : request.fieldMappings().getOrDefault(normalizedSourceObject, Map.of());
            List<ColumnInfo> sourceColumns = sourceDataSourceService.listColumns(
                    request.sourceDatasourceId(), request.sourceSchema(), normalizedSourceObject);
            results.add(buildPrecheckResult(
                    request.sourceDatasourceId(),
                    request.sourceSchema(),
                    normalizedSourceObject,
                    datasource,
                    contract.get(),
                    sourceColumns,
                    fieldMapping,
                    request.options()));
        }
        return summarize(results);
    }

    private List<String> resolveSourceObjects(MedicalMappingBatchPlanRequest request) {
        if (request.sourceObjects() != null && !request.sourceObjects().isEmpty()) {
            return distinctNormalized(request.sourceObjects());
        }
        LinkedHashSet<String> sourceObjects = new LinkedHashSet<>();
        for (SourceDataSourceService.TableInfo table : sourceDataSourceService.listTables(
                request.sourceDatasourceId(), request.sourceSchema())) {
            if (isMedicalCandidate(table.tableName())) {
                sourceObjects.add(table.tableName().trim());
            }
        }
        return List.copyOf(sourceObjects);
    }

    private MedicalMappingPrecheckResult buildPrecheckResult(
            Long sourceDatasourceId,
            String sourceSchema,
            String sourceObject,
            SourceDataSourceDto datasource,
            MedicalDatasetContract contract,
            List<ColumnInfo> sourceColumns,
            Map<String, String> fieldMapping,
            MedicalPrecheckOptions options) {
        SourceDialectAdapter adapter = dialectAdapterResolver.resolve(datasource.getType());
        MedicalPrecheckPlan plan = precheckService.buildPlan(new MedicalPrecheckRequest(
                sourceSchema,
                sourceObject,
                contract,
                sourceColumns,
                adapter,
                fieldMapping == null ? Map.of() : fieldMapping,
                options == null ? MedicalPrecheckOptions.defaults() : options));
        return new MedicalMappingPrecheckResult(
                sourceDatasourceId,
                sourceSchema,
                sourceObject,
                plan.hasBlockers() ? MedicalMappingPrecheckStatus.BLOCKED : MedicalMappingPrecheckStatus.READY,
                contract,
                plan.findings(),
                plan.checks());
    }

    private MedicalDatasetContract resolveContractOrThrow(String sourceObject, String datasetCode) {
        if (datasetCode != null && !datasetCode.isBlank()) {
            return contractService.loadByDatasetCode(datasetCode.trim());
        }
        return contractService.matchBySourceObject(sourceObject)
                .orElseThrow(() -> new IllegalArgumentException("未匹配医共体数据集: " + sourceObject));
    }

    private static MedicalMappingPrecheckResult unmatchedResult(
            Long sourceDatasourceId,
            String sourceSchema,
            String sourceObject) {
        return new MedicalMappingPrecheckResult(
                sourceDatasourceId,
                sourceSchema,
                sourceObject,
                MedicalMappingPrecheckStatus.UNMATCHED,
                null,
                List.of(new MedicalPrecheckFinding(
                        MedicalPrecheckSeverity.BLOCKER,
                        "NO_MATCHED_DATASET",
                        sourceObject,
                        "未匹配医共体数据集: " + sourceObject)),
                List.of());
    }

    private static MedicalMappingPrecheckResult unsupportedFormatResult(
            Long sourceDatasourceId,
            String sourceSchema,
            String sourceObject,
            MedicalDatasetContract contract,
            MedicalFormatException exception) {
        String field = exception.field() == null || exception.field().isBlank()
                ? sourceObject
                : exception.field();
        return blockedFormatResult(
                sourceDatasourceId,
                sourceSchema,
                sourceObject,
                contract,
                List.of(new MedicalPrecheckFinding(
                        MedicalPrecheckSeverity.BLOCKER,
                        "UNSUPPORTED_MEDICAL_FORMAT",
                        field,
                        exception.getMessage())));
    }

    private static MedicalMappingPrecheckResult missingDataItemResult(
            Long sourceDatasourceId,
            String sourceSchema,
            String sourceObject,
            MedicalRegistryDataItemException exception) {
        return blockedFormatResult(
                sourceDatasourceId,
                sourceSchema,
                sourceObject,
                null,
                List.of(new MedicalPrecheckFinding(
                        MedicalPrecheckSeverity.BLOCKER,
                        "MEDICAL_REGISTRY_DATA_ITEM_MISSING",
                        exception.fieldId(),
                        exception.getMessage())));
    }

    private static MedicalMappingPrecheckResult blockedFormatResult(
            Long sourceDatasourceId,
            String sourceSchema,
            String sourceObject,
            MedicalDatasetContract contract,
            List<MedicalPrecheckFinding> findings) {
        return new MedicalMappingPrecheckResult(
                sourceDatasourceId,
                sourceSchema,
                sourceObject,
                MedicalMappingPrecheckStatus.BLOCKED,
                contract,
                List.copyOf(findings),
                List.of());
    }

    private static MedicalMappingPrecheckResult existingResult(
            Long sourceDatasourceId,
            String sourceSchema,
            String sourceObject,
            MedicalDatasetContract contract) {
        return new MedicalMappingPrecheckResult(
                sourceDatasourceId,
                sourceSchema,
                sourceObject,
                MedicalMappingPrecheckStatus.EXISTING,
                contract,
                List.of(new MedicalPrecheckFinding(
                        MedicalPrecheckSeverity.INFO,
                        "TASK_ALREADY_EXISTS",
                        sourceObject,
                        "同步任务已存在: " + sourceObject)),
                List.of());
    }

    private static MedicalMappingPlanResponse summarize(List<MedicalMappingPrecheckResult> results) {
        int ready = 0;
        int blocked = 0;
        int unmatched = 0;
        int existing = 0;
        for (MedicalMappingPrecheckResult result : results) {
            if (result.status() == MedicalMappingPrecheckStatus.READY) {
                ready++;
            } else if (result.status() == MedicalMappingPrecheckStatus.BLOCKED) {
                blocked++;
            } else if (result.status() == MedicalMappingPrecheckStatus.UNMATCHED) {
                unmatched++;
            } else if (result.status() == MedicalMappingPrecheckStatus.EXISTING) {
                existing++;
            }
        }
        return new MedicalMappingPlanResponse(results.size(), ready, blocked, unmatched, existing, List.copyOf(results));
    }

    private static void validateSingleRequest(MedicalMappingPrecheckRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (request.sourceDatasourceId() == null) {
            throw new IllegalArgumentException("sourceDatasourceId 不能为空");
        }
        normalizeSourceObject(request.sourceObject());
    }

    private static void validateBatchRequest(MedicalMappingBatchPlanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (request.sourceDatasourceId() == null) {
            throw new IllegalArgumentException("sourceDatasourceId 不能为空");
        }
    }

    private static String normalizeSourceObject(String sourceObject) {
        if (sourceObject == null || sourceObject.isBlank()) {
            throw new IllegalArgumentException("sourceObject 不能为空");
        }
        return sourceObject.trim();
    }

    private static List<String> distinctNormalized(List<String> sourceObjects) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String sourceObject : sourceObjects) {
            normalized.add(normalizeSourceObject(sourceObject));
        }
        return List.copyOf(normalized);
    }

    private static boolean isMedicalCandidate(String sourceObject) {
        if (sourceObject == null || sourceObject.isBlank()) {
            return false;
        }
        String normalized = sourceObject.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("v_yl_")
                && !normalized.endsWith("__raw_ts")
                && !normalized.endsWith("_bak");
    }

    private boolean hasExistingTask(Long sourceDatasourceId, String sourceSchema, String sourceObject) {
        if (syncTaskRepository == null) {
            return false;
        }
        return syncTaskRepository.findAll().stream()
                .filter(task -> Objects.equals(task.getSourceDataSourceId(), sourceDatasourceId))
                .filter(task -> schemaMatches(task.getSourceSchema(), sourceSchema))
                .anyMatch(task -> containsSourceObject(task, sourceObject));
    }

    private static boolean schemaMatches(String taskSchema, String requestedSchema) {
        if (requestedSchema == null || requestedSchema.isBlank()) {
            return true;
        }
        return taskSchema != null && taskSchema.trim().equalsIgnoreCase(requestedSchema.trim());
    }

    private static boolean containsSourceObject(SyncTask task, String sourceObject) {
        if (task.getViewNames() == null || sourceObject == null) {
            return false;
        }
        return task.getViewNames().stream()
                .filter(Objects::nonNull)
                .anyMatch(viewName -> viewName.trim().equalsIgnoreCase(sourceObject.trim()));
    }
}
