package com.dfygt.dfetl.server.external.service;

import com.dfygt.dfetl.server.dto.SyncTaskDto;
import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.external.dto.ExternalDatasetTaskIdentityRequest;
import com.dfygt.dfetl.server.external.dto.ExternalDatasetTaskItemResponse;
import com.dfygt.dfetl.server.external.dto.ExternalDatasetTaskOperationResponse;
import com.dfygt.dfetl.server.external.dto.ExternalSyncTaskListResponse;
import com.dfygt.dfetl.server.external.dto.ExternalSyncTaskRequest;
import com.dfygt.dfetl.server.external.entity.ExternalTaskBatchRequest;
import com.dfygt.dfetl.server.external.repository.ExternalTaskBatchRequestRepository;
import com.dfygt.dfetl.server.repository.DfetlDatasetRepository;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.service.InstitutionDatasetRouteResolver;
import com.dfygt.dfetl.server.service.SyncTaskApplicationService;
import com.dfygt.dfetl.server.service.TaskCreateIntent;
import com.dfygt.dfetl.server.service.TaskExecutionQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/** 医共体外部 API 的标准数据集任务规划和创建服务。 */
@Service
@RequiredArgsConstructor
public class ExternalDatasetTaskService {

    private static final String READY = "READY";

    private final InstitutionRepository institutionRepository;
    private final DfetlDatasetRepository datasetRepository;
    private final InstitutionDatasetRouteResolver routeResolver;
    private final SyncTaskApplicationService applicationService;
    private final TaskExecutionQueue executionQueue;
    private final ExternalTaskBatchRequestRepository requestRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ExternalSyncTaskListResponse plan(ExternalSyncTaskRequest request) {
        PreparedRequest prepared = prepare(request);
        List<ExternalDatasetTaskItemResponse> items = inspect(prepared);
        return response(prepared.requestId(), summarizePlan(items), false, items);
    }

    @Transactional
    public ExternalSyncTaskListResponse create(ExternalSyncTaskRequest request) {
        PreparedRequest prepared = prepare(request);
        String hash = requestHash(request);
        requestRepository.acquireRequestLock(prepared.requestId());
        var stored = requestRepository.findByExternalBatchId(prepared.requestId());
        if (stored.isPresent()) {
            ExternalTaskBatchRequest record = stored.get();
            if (!hash.equals(record.getRequestHash())) {
                throw new IllegalStateException("IDEMPOTENCY_CONFLICT: requestId 已被不同请求使用");
            }
            return readStored(record);
        }

        List<ExternalDatasetTaskItemResponse> inspected = inspect(prepared);
        if ("ALL_OR_NOTHING".equals(prepared.failurePolicy())
                && inspected.stream().anyMatch(item -> !READY.equals(item.status()))) {
            ExternalSyncTaskListResponse blocked = response(
                    prepared.requestId(), "BLOCKED", false,
                    inspected.stream().map(ExternalDatasetTaskService::notCreatedForAtomicPolicy).toList());
            store(prepared, request, hash, blocked);
            return blocked;
        }

        List<ExternalDatasetTaskItemResponse> results = new ArrayList<>(inspected.size());
        for (ExternalDatasetTaskItemResponse item : inspected) {
            if (!READY.equals(item.status())) {
                results.add(item);
                continue;
            }
            DfetlDataset dataset = datasetRepository.findFirstByDatasetCodeIgnoreCase(item.datasetCode())
                    .orElseThrow();
            TaskCreateIntent intent = new TaskCreateIntent();
            intent.setInstitutionId(prepared.institution().getId());
            intent.setDatasetId(dataset.getId());
            intent.setName(prepared.institution().getCode() + "-" + dataset.getDatasetCode());
            try {
                SyncTaskDto task = applicationService.createExternal(intent);
                if (prepared.runAfterCreate()) {
                    try {
                        executionQueue.submit(task.getId(), "EXTERNAL_API");
                        results.add(item(dataset.getDatasetCode(), "RUN_SUBMITTED", task.getId(), null, null));
                    } catch (RuntimeException ex) {
                        results.add(item(dataset.getDatasetCode(), "RUN_FAILED", task.getId(),
                                errorCode(ex), ex.getMessage()));
                    }
                } else {
                    results.add(item(dataset.getDatasetCode(), "CREATED", task.getId(), null, null));
                }
            } catch (RuntimeException ex) {
                if ("ALL_OR_NOTHING".equals(prepared.failurePolicy())) {
                    throw ex;
                }
                results.add(item(dataset.getDatasetCode(), "BLOCKED", null, errorCode(ex), ex.getMessage()));
            }
        }
        ExternalSyncTaskListResponse result = response(
                prepared.requestId(), summarizeCreate(results), false, results);
        store(prepared, request, hash, result);
        return result;
    }

    @Transactional(readOnly = true)
    public ExternalDatasetTaskOperationResponse lookup(ExternalDatasetTaskIdentityRequest request) {
        DatasetIdentity identity = resolveIdentity(request);
        return applicationService.findExistingTask(identity.institution().getId(), identity.dataset().getId())
                .map(task -> operation(identity, "TASK_EXISTS", task.getId(), task.getName(), task.getStatus(),
                        "任务已存在"))
                .orElseGet(() -> operation(identity, "TASK_NOT_FOUND", null, null, null, "任务不存在"));
    }

    public ExternalDatasetTaskOperationResponse run(ExternalDatasetTaskIdentityRequest request) {
        DatasetIdentity identity = resolveIdentity(request);
        SyncTaskDto task = applicationService.findExistingTask(
                        identity.institution().getId(), identity.dataset().getId())
                .orElseThrow(() -> new IllegalStateException("TASK_NOT_FOUND: 请先创建任务"));
        executionQueue.submit(task.getId(), "EXTERNAL_API");
        return operation(identity, "RUN_SUBMITTED", task.getId(), task.getName(), task.getStatus(),
                "任务已提交执行队列");
    }

    public ExternalDatasetTaskOperationResponse delete(ExternalDatasetTaskIdentityRequest request) {
        DatasetIdentity identity = resolveIdentity(request);
        SyncTaskDto task = applicationService.findExistingTask(
                        identity.institution().getId(), identity.dataset().getId())
                .orElseThrow(() -> new IllegalStateException("TASK_NOT_FOUND: 无可删除任务"));
        applicationService.deleteExistingTask(identity.institution().getId(), identity.dataset().getId());
        return operation(identity, "DELETED", task.getId(), task.getName(), task.getStatus(),
                "任务及运行配置已删除");
    }

    public static String requestHash(ExternalSyncTaskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        List<String> codes = normalizedCodes(request.getDatasetCodes()).stream().sorted().toList();
        String canonical = normalized(request.getYiLiaoJgDm()) + "\n"
                + String.join("\n", codes) + "\n"
                + Boolean.TRUE.equals(request.getRunAfterCreate()) + "\n"
                + normalizedPolicy(request.getFailurePolicy());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法计算请求摘要", ex);
        }
    }

    private PreparedRequest prepare(ExternalSyncTaskRequest request) {
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        if (request.getYiLiaoJgDm() == null || request.getYiLiaoJgDm().isBlank()) {
            throw new IllegalArgumentException("yiLiaoJgDm 不能为空");
        }
        List<String> codes = normalizedCodes(request.getDatasetCodes());
        if (codes.isEmpty() || codes.size() > 500) {
            throw new IllegalArgumentException("datasetCodes 数量必须为 1..500");
        }
        Institution institution = institutionRepository.findByCode(request.getYiLiaoJgDm().trim())
                .orElseThrow(() -> new NoSuchElementException("INSTITUTION_NOT_FOUND: " + request.getYiLiaoJgDm()));
        if (!Boolean.TRUE.equals(institution.getEnabled())) {
            throw new IllegalStateException("INSTITUTION_DISABLED: " + request.getYiLiaoJgDm());
        }
        return new PreparedRequest(
                request.getRequestId().trim(), institution, codes,
                Boolean.TRUE.equals(request.getRunAfterCreate()), normalizedPolicy(request.getFailurePolicy()));
    }

    private DatasetIdentity resolveIdentity(ExternalDatasetTaskIdentityRequest request) {
        if (request == null || request.yiLiaoJgDm() == null || request.yiLiaoJgDm().isBlank()
                || request.datasetCode() == null || request.datasetCode().isBlank()) {
            throw new IllegalArgumentException("yiLiaoJgDm 和 datasetCode 不能为空");
        }
        Institution institution = institutionRepository.findByCode(request.yiLiaoJgDm().trim())
                .orElseThrow(() -> new NoSuchElementException("INSTITUTION_NOT_FOUND: " + request.yiLiaoJgDm()));
        if (!Boolean.TRUE.equals(institution.getEnabled())) {
            throw new IllegalStateException("INSTITUTION_DISABLED: " + request.yiLiaoJgDm());
        }
        DfetlDataset dataset = datasetRepository.findFirstByDatasetCodeIgnoreCase(request.datasetCode().trim())
                .orElseThrow(() -> new NoSuchElementException("DATASET_NOT_FOUND: " + request.datasetCode()));
        return new DatasetIdentity(institution, dataset);
    }

    private List<ExternalDatasetTaskItemResponse> inspect(PreparedRequest prepared) {
        List<ExternalDatasetTaskItemResponse> items = new ArrayList<>(prepared.datasetCodes().size());
        for (String requestedCode : prepared.datasetCodes()) {
            var datasetOptional = datasetRepository.findFirstByDatasetCodeIgnoreCase(requestedCode);
            if (datasetOptional.isEmpty()) {
                items.add(item(requestedCode, "BLOCKED", null, "DATASET_NOT_FOUND", "标准数据集不存在"));
                continue;
            }
            DfetlDataset dataset = datasetOptional.get();
            if (!"ACTIVE".equalsIgnoreCase(dataset.getDatasetStatus())) {
                items.add(item(dataset.getDatasetCode(), "BLOCKED", null, "DATASET_VOID", "标准数据集已作废"));
                continue;
            }
            var taskId = applicationService.findExistingTaskId(prepared.institution().getId(), dataset.getId());
            if (taskId.isPresent()) {
                items.add(item(dataset.getDatasetCode(), "TASK_EXISTS", taskId.get(), "TASK_EXISTS",
                        "任务已存在，请先删除后再重建"));
                continue;
            }
            try {
                routeResolver.resolve(prepared.institution().getId(), dataset.getId(), null);
                items.add(item(dataset.getDatasetCode(), READY, null, null, null));
            } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException ex) {
                items.add(item(dataset.getDatasetCode(), "BLOCKED", null, errorCode(ex), ex.getMessage()));
            }
        }
        return items;
    }

    private void store(PreparedRequest prepared, ExternalSyncTaskRequest request, String hash,
                       ExternalSyncTaskListResponse response) {
        ExternalTaskBatchRequest record = new ExternalTaskBatchRequest();
        record.setExternalBatchId(prepared.requestId());
        record.setYiLiaoJgDm(prepared.institution().getCode());
        record.setBusinessCode("MEDICAL");
        record.setRequestHash(hash);
        record.setStatus(response.status());
        record.setFailurePolicy(prepared.failurePolicy());
        record.setTotalCount(response.totalCount());
        record.setCreatedCount(response.createdCount());
        record.setExistingCount(response.taskExistsCount());
        record.setFailedCount(response.blockedCount() + response.runFailedCount());
        try {
            record.setRequestBody(objectMapper.writeValueAsString(request));
            record.setResultBody(objectMapper.writeValueAsString(response));
        } catch (Exception ex) {
            throw new IllegalStateException("外部请求审计序列化失败", ex);
        }
        requestRepository.save(record);
    }

    private ExternalSyncTaskListResponse readStored(ExternalTaskBatchRequest record) {
        if (record.getResultBody() == null || record.getResultBody().isBlank()) {
            throw new IllegalStateException("IDEMPOTENCY_IN_PROGRESS: requestId 正在处理或结果不可用");
        }
        try {
            ExternalSyncTaskListResponse result = objectMapper.readValue(
                    record.getResultBody(), ExternalSyncTaskListResponse.class);
            return new ExternalSyncTaskListResponse(
                    result.requestId(), result.status(), result.totalCount(), result.createdCount(),
                    result.runSubmittedCount(), result.taskExistsCount(), result.blockedCount(),
                    result.runFailedCount(), true, result.items());
        } catch (Exception ex) {
            throw new IllegalStateException("幂等结果读取失败", ex);
        }
    }

    private static ExternalDatasetTaskItemResponse notCreatedForAtomicPolicy(ExternalDatasetTaskItemResponse item) {
        if (READY.equals(item.status())) {
            return item(item.datasetCode(), "BLOCKED", null, "ALL_OR_NOTHING_BLOCKED",
                    "同批次存在不可创建的数据集，本批次未创建任何任务");
        }
        return item;
    }

    private static ExternalSyncTaskListResponse response(String requestId, String status, boolean idempotent,
                                                         List<ExternalDatasetTaskItemResponse> items) {
        int created = count(items, "CREATED");
        int submitted = count(items, "RUN_SUBMITTED");
        return new ExternalSyncTaskListResponse(
                requestId, status, items.size(), created + submitted, submitted,
                count(items, "TASK_EXISTS"), count(items, "BLOCKED"), count(items, "RUN_FAILED"),
                idempotent, List.copyOf(items));
    }

    private static int count(List<ExternalDatasetTaskItemResponse> items, String status) {
        return (int) items.stream().filter(item -> status.equals(item.status())).count();
    }

    private static String summarizePlan(List<ExternalDatasetTaskItemResponse> items) {
        return items.stream().allMatch(item -> READY.equals(item.status())) ? "READY" : "BLOCKED";
    }

    private static String summarizeCreate(List<ExternalDatasetTaskItemResponse> items) {
        boolean succeeded = items.stream().anyMatch(item -> "CREATED".equals(item.status())
                || "RUN_SUBMITTED".equals(item.status()));
        boolean exceptional = items.stream().anyMatch(item -> "BLOCKED".equals(item.status())
                || "RUN_FAILED".equals(item.status()) || "TASK_EXISTS".equals(item.status()));
        if (succeeded && exceptional) {
            return "PARTIAL_SUCCESS";
        }
        if (succeeded) {
            return "SUCCESS";
        }
        return "BLOCKED";
    }

    private static ExternalDatasetTaskItemResponse item(
            String code, String status, Long taskId, String errorCode, String message) {
        return new ExternalDatasetTaskItemResponse(code, status, taskId, errorCode, message);
    }

    private static ExternalDatasetTaskOperationResponse operation(
            DatasetIdentity identity, String status, Long taskId, String taskName,
            String taskStatus, String message) {
        return new ExternalDatasetTaskOperationResponse(
                identity.institution().getCode(), identity.dataset().getDatasetCode(), status,
                taskId, taskName, taskStatus, message);
    }

    private static List<String> normalizedCodes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("datasetCodes 不能包含空值");
            }
            String code = value.trim().toUpperCase(Locale.ROOT);
            if (!result.add(code)) {
                throw new IllegalArgumentException("datasetCodes 不能重复: " + code);
            }
        }
        return List.copyOf(result);
    }

    private static String normalizedPolicy(String value) {
        String policy = value == null || value.isBlank() ? "BEST_EFFORT" : value.trim().toUpperCase(Locale.ROOT);
        if (!"BEST_EFFORT".equals(policy) && !"ALL_OR_NOTHING".equals(policy)) {
            throw new IllegalArgumentException("failurePolicy 仅支持 BEST_EFFORT 或 ALL_OR_NOTHING");
        }
        return policy;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String errorCode(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        }
        int separator = message.indexOf(':');
        return (separator > 0 ? message.substring(0, separator) : message)
                .trim().replace(' ', '_').toUpperCase(Locale.ROOT);
    }

    private record PreparedRequest(
            String requestId,
            Institution institution,
            List<String> datasetCodes,
            boolean runAfterCreate,
            String failurePolicy) {
    }

    private record DatasetIdentity(Institution institution, DfetlDataset dataset) {
    }
}
