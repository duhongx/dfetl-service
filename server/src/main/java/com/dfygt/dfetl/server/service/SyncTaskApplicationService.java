package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.SyncTaskDto;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 内部 JWT 和外部 HMAC 创建入口共用的唯一任务创建应用服务。 */
@Service
@RequiredArgsConstructor
public class SyncTaskApplicationService {

    private final InstitutionDatasetRouteResolver routeResolver;
    private final DatasetTaskSnapshotAssembler snapshotAssembler;
    private final SyncTaskService syncTaskService;
    private final SyncTaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SyncTaskDto createInternal(TaskCreateIntent intent) {
        return create(intent);
    }

    @Transactional
    public SyncTaskDto createExternal(TaskCreateIntent intent) {
        return create(intent);
    }

    @Transactional(readOnly = true)
    public Optional<Long> findExistingTaskId(Long institutionId, Long datasetId) {
        validateIdentity(institutionId, datasetId);
        return taskRepository.findByInstitutionIdIn(Set.of(institutionId)).stream()
                .filter(task -> hasDatasetIdentity(task, datasetId))
                .map(SyncTask::getId)
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<SyncTaskDto> findExistingTask(Long institutionId, Long datasetId) {
        return findExistingTaskId(institutionId, datasetId).map(syncTaskService::findById);
    }

    @Transactional
    public Long deleteExistingTask(Long institutionId, Long datasetId) {
        Long taskId = findExistingTaskId(institutionId, datasetId)
                .orElseThrow(() -> new IllegalStateException(
                        "TASK_NOT_FOUND: institutionId=" + institutionId + ", datasetId=" + datasetId));
        syncTaskService.delete(taskId);
        return taskId;
    }

    private SyncTaskDto create(TaskCreateIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("任务创建意图不能为空");
        }
        assertTaskAbsent(intent.getInstitutionId(), intent.getDatasetId());
        ResolvedDatasetRoute resolved = routeResolver.resolve(
                intent.getInstitutionId(), intent.getDatasetId(), intent.getRouteId());
        SyncTaskDto snapshot = snapshotAssembler.assemble(resolved, intent);
        return syncTaskService.createResolvedSnapshot(snapshot);
    }

    private void assertTaskAbsent(Long institutionId, Long datasetId) {
        validateIdentity(institutionId, datasetId);
        for (SyncTask task : taskRepository.findByInstitutionIdIn(Set.of(institutionId))) {
            if (hasDatasetIdentity(task, datasetId)) {
                throw new IllegalStateException("TASK_EXISTS: taskId=" + task.getId()
                        + ", institutionId=" + institutionId + ", datasetId=" + datasetId
                        + "；请先删除旧任务再重建");
            }
        }
    }

    private static void validateIdentity(Long institutionId, Long datasetId) {
        if (institutionId == null || institutionId <= 0) {
            throw new IllegalArgumentException("institutionId 必须为正整数");
        }
        if (datasetId == null || datasetId <= 0) {
            throw new IllegalArgumentException("datasetId 必须为正整数");
        }
    }

    private boolean hasDatasetIdentity(SyncTask task, Long datasetId) {
        if (task.getDataCharacteristics() == null || task.getDataCharacteristics().isBlank()) {
            return false;
        }
        try {
            Map<String, Object> values = objectMapper.readValue(
                    task.getDataCharacteristics(), new TypeReference<>() {});
            Object value = values.get("standardDatasetId");
            if (value instanceof Number number) {
                return datasetId.equals(number.longValue());
            }
            return value != null && datasetId.toString().equals(value.toString());
        } catch (Exception ignored) {
            return false;
        }
    }
}
