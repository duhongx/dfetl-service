package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.DfetlPrecheckRun;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.quality.TargetWriteContract;
import com.dfygt.dfetl.server.medical.quality.TargetWriteContractService;
import com.dfygt.dfetl.server.repository.DfetlPrecheckRunRepository;
import com.dfygt.dfetl.server.repository.DfetlDatasetRepository;
import com.dfygt.dfetl.server.repository.InstitutionDatasetRouteRepository;
import com.dfygt.dfetl.server.service.DfetlContractSnapshotService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Map;
import java.util.NoSuchElementException;

/** 将最新路由全量预检结果解析成任务创建和启动共用的门禁快照。 */
@Service
@RequiredArgsConstructor
public class DfetlPrecheckGateService {

    private final DfetlPrecheckRunRepository runRepository;
    private final DfetlContractSnapshotService contractService;
    private final TargetWriteContractService targetContractService;
    private final InstitutionDatasetRouteRepository routeRepository;
    private final DfetlDatasetRepository datasetRepository;
    private final ObjectMapper objectMapper;

    public GateSnapshot requireLatestPassed(
            InstitutionDatasetRoute route,
            DfetlDataset dataset) {
        if (route == null || route.getId() == null || route.getId() <= 0) {
            throw new IllegalArgumentException("机构数据集路由不能为空");
        }
        if (dataset == null || !Objects.equals(route.getDatasetId(), dataset.getId())) {
            throw new IllegalArgumentException("机构数据集路由与标准数据集不匹配");
        }
        if (dataset.getContractHash() == null || dataset.getContractHash().isBlank()
                || route.getRouteRevision() == null || route.getRouteRevision() <= 0) {
            throw blocked(route.getId(), "contractHash/routeRevision 缺失");
        }

        MedicalDatasetContract contract = contractService.load(
                dataset.getId(), route.getTargetTable());
        TargetWriteContract targetContract = targetContractService.resolve(
                route.getTargetDatasourceId(), route.getTargetTable(), contract);
        String currentTargetHash = PrecheckTargetSchemaHasher.hash(targetContract);
        DfetlPrecheckRun latest = runRepository
                .findFirstByRouteIdAndRunTypeOrderByCreatedAtDescIdDesc(
                        route.getId(), "ROUTE_FULL")
                .orElseThrow(() -> blocked(route.getId(), "不存在全量预检运行"));

        if (!"PASSED".equals(latest.getStatus())) {
            throw blocked(route.getId(), "latestStatus=" + latest.getStatus());
        }
        if (!Objects.equals(dataset.getId(), latest.getDatasetId())) {
            throw blocked(route.getId(), "datasetId 已变化");
        }
        if (!Objects.equals(dataset.getContractHash(), latest.getContractHash())) {
            throw blocked(route.getId(), "contractHash 已变化");
        }
        if (!Objects.equals(route.getRouteRevision(), latest.getRouteRevision())) {
            throw blocked(route.getId(), "routeRevision 已变化");
        }
        if (!Objects.equals(currentTargetHash, latest.getTargetSchemaHash())) {
            throw blocked(route.getId(), "targetSchemaHash 已变化");
        }
        return new GateSnapshot(
                latest.getId(),
                latest.getContractHash(),
                latest.getRouteRevision(),
                latest.getTargetSchemaHash());
    }

    public GateSnapshot requireTaskStartAllowed(SyncTask task) {
        if (task == null || task.getDataCharacteristics() == null
                || task.getDataCharacteristics().isBlank()) {
            throw taskBlocked("任务缺少标准数据集快照");
        }
        Map<String, Object> snapshot;
        try {
            snapshot = objectMapper.readValue(
                    task.getDataCharacteristics(), new TypeReference<>() {});
        } catch (Exception e) {
            throw taskBlocked("任务快照 JSON 非法");
        }
        if (!"STANDARD_DATASET_ROUTE".equals(text(snapshot.get("fillSource")))) {
            throw taskBlocked("任务不是标准数据集路由快照");
        }
        Long routeId = requiredLong(snapshot, "institutionDatasetRouteId");
        Long datasetId = requiredLong(snapshot, "standardDatasetId");
        String contractHash = requiredText(snapshot, "standardContractHash");
        Long routeRevision = requiredLong(snapshot, "routeRevision");
        String targetSchemaHash = requiredText(snapshot, "targetSchemaHash");
        requiredLong(snapshot, "precheckRunId");

        InstitutionDatasetRoute route = routeRepository.findById(routeId)
                .orElseThrow(() -> new NoSuchElementException(
                        "DATA_PRECHECK_GATE_BLOCKED: 路由不存在: " + routeId));
        DfetlDataset dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new NoSuchElementException(
                        "DATA_PRECHECK_GATE_BLOCKED: 标准数据集不存在: " + datasetId));
        if (!Objects.equals(route.getDatasetId(), datasetId)
                || !Objects.equals(route.getInstitutionId(), task.getInstitutionId())) {
            throw taskBlocked("任务快照与当前路由身份不一致");
        }
        if (!Boolean.TRUE.equals(route.getEnabled())) {
            throw taskBlocked("当前路由未启用");
        }
        if (!"ACTIVE".equals(dataset.getDatasetStatus())) {
            throw taskBlocked("标准数据集已作废");
        }
        if (!"PASSED".equals(route.getValidationStatus())
                || route.getLastValidatedAt() == null
                || !Objects.equals(route.getValidatedContractHash(), dataset.getContractHash())
                || !Objects.equals(route.getValidatedRouteRevision(), route.getRouteRevision())) {
            throw taskBlocked("当前路由静态校验已失效");
        }
        if (!Objects.equals(contractHash, dataset.getContractHash())) {
            throw taskBlocked("任务快照 standardContractHash 已失效");
        }
        if (!Objects.equals(routeRevision, route.getRouteRevision())) {
            throw taskBlocked("任务快照 routeRevision 已失效");
        }

        GateSnapshot current;
        try {
            current = requireLatestPassed(route, dataset);
        } catch (IllegalStateException e) {
            throw taskBlocked(e.getMessage());
        }
        if (!Objects.equals(targetSchemaHash, current.targetSchemaHash())) {
            throw taskBlocked("任务快照 targetSchemaHash 已失效");
        }
        return current;
    }

    private static IllegalStateException blocked(Long routeId, String reason) {
        return new IllegalStateException(
                "DATA_PRECHECK_REQUIRED: routeId=" + routeId + ", reason=" + reason);
    }

    private static IllegalStateException taskBlocked(String reason) {
        return new IllegalStateException("DATA_PRECHECK_GATE_BLOCKED: " + reason);
    }

    private static Long requiredLong(Map<String, Object> values, String key) {
        Object value = values.get(key);
        try {
            Long result = value instanceof Number number
                    ? number.longValue() : Long.valueOf(text(value));
            if (result <= 0) {
                throw new NumberFormatException();
            }
            return result;
        } catch (Exception e) {
            throw taskBlocked("任务快照缺少有效 " + key);
        }
    }

    private static String requiredText(Map<String, Object> values, String key) {
        String value = text(values.get(key));
        if (value.isBlank()) {
            throw taskBlocked("任务快照缺少有效 " + key);
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    public record GateSnapshot(
            Long runId,
            String contractHash,
            Long routeRevision,
            String targetSchemaHash) {
    }
}
