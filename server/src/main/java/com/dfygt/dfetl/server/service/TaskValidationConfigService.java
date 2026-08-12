package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.TaskValidationConfigDto;
import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TaskValidationConfigService {

    private final TaskValidationProfileService profileService;
    private final DriftWatchScheduler driftWatchScheduler;
    private final com.dfygt.dfetl.server.repository.SyncTaskRepository syncTaskRepository;
    private final SyncTaskService syncTaskService;
    private final TaskValidationConfigApplyService applyService;

    /** 查询指定任务的校验配置；任务不存在直接 404，任务存在但配置不存在才返回默认值。 */
    public TaskValidationConfigDto getByTaskId(Long taskId) {
        if (!syncTaskRepository.existsById(taskId)) {
            throw new java.util.NoSuchElementException("SyncTask not found: " + taskId);
        }
        return profileService.getByTaskId(taskId);
    }

    /** 保存（upsert）校验配置 */
    public TaskValidationConfigDto save(Long taskId, TaskValidationConfigDto dto) {
        // 任务存在性校验：避免对不存在的 taskId 写入孤儿配置
        if (!syncTaskRepository.existsById(taskId)) {
            throw new java.util.NoSuchElementException("SyncTask not found: " + taskId);
        }
        // RUNNING 守卫：跑动期间改 method 等关键配置会让校验链路读到不一致状态
        syncTaskService.assertNoRunningExecution(taskId, "updateValidationConfig");
        return applyService.saveForNewTask(taskId, dto);
    }

    /** 删除配置（重置为默认值） */
    public void delete(Long taskId) {
        if (!syncTaskRepository.existsById(taskId)) {
            throw new java.util.NoSuchElementException("SyncTask not found: " + taskId);
        }
        syncTaskService.assertNoRunningExecution(taskId, "deleteValidationConfig");
        profileService.delete(taskId);
        driftWatchScheduler.delete(taskId);
    }

    public Optional<TaskValidationConfig> findEntityByTaskId(Long taskId) {
        return profileService.findEntityByTaskId(taskId);
    }
}
