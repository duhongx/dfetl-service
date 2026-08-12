package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.engine.ExecutionResult;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import com.dfygt.dfetl.server.service.publish.MessagePublishTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * SeaTunnel 成功后的统一收尾入口，供正常执行和启动恢复共同使用。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionSuccessFinalizationService {

    private final SyncTaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final WatermarkService watermarkService;
    private final ValidationGateService validationGateService;
    private final AlertEvaluatorService alertEvaluatorService;
    private final SnapshotOrchestrator snapshotOrchestrator;
    private final AutoValidationTrigger autoValidationTrigger;
    private final MessagePublishTrigger messagePublishTrigger;

    /**
     * @return true 表示成功收尾；false 表示 Validation Gate 已把本次执行改为 FAILED。
     */
    public boolean finalizeSuccessfulExecution(
            SyncTask task, TaskExecution execution, WatermarkService.WindowContext window) {
        boolean dirtySuccess = ExecutionResult.STATUS_SUCCESS_WITH_DIRTY_ROWS
                .equals(execution.getStatus())
                || (execution.getExcludedRows() != null && execution.getExcludedRows() > 0);

        if (!dirtySuccess && shouldCommitWatermark(window) && !passesGate(task, execution, window)) {
            execution.setStatus("FAILED");
            execution.setErrorMsg("Validation gate blocked watermark commit");
            executionRepository.save(execution);
            updateTaskTerminal(task, "FAILED");
            taskRepository.save(task);
            alertEvaluatorService.evaluate(task, execution);
            return false;
        }

        String finalStatus = dirtySuccess
                ? ExecutionResult.STATUS_SUCCESS_WITH_DIRTY_ROWS
                : "SUCCESS";
        updateTaskTerminal(task, finalStatus);

        if (!dirtySuccess && shouldCommitWatermark(window)) {
            watermarkService.commit(task, window);
        } else {
            taskRepository.save(task);
        }
        alertEvaluatorService.evaluate(task, execution);

        if (!dirtySuccess) {
            snapshotOrchestrator.onTaskExecutionSucceeded(task, execution.getId());
            autoValidationTrigger.onExecutionSuccess(task, execution.getId(), window);
            triggerMessagePublishSafely(task, execution.getId(), window);
        }
        return true;
    }

    /** 启动恢复使用 execution 快照重建任务和窗口，再进入同一收尾链路。 */
    public boolean finalizeRecoveredExecution(TaskExecution execution) {
        SyncTask task = taskRepository.findById(execution.getTaskId()).orElse(null);
        if (task == null) {
            log.warn("ExecutionFinalization: task not found for recovered exec={} taskId={}",
                    execution.getId(), execution.getTaskId());
            return false;
        }
        return finalizeSuccessfulExecution(task, execution, reconstructWindow(execution));
    }

    private boolean passesGate(
            SyncTask task, TaskExecution execution, WatermarkService.WindowContext window) {
        try {
            return validationGateService.checkAndBlock(task, execution.getId(), window);
        } catch (Exception e) {
            log.error("ExecutionFinalization: validation gate exception task={} exec={}, fail-closed: {}",
                    task.getId(), execution.getId(), e.getMessage(), e);
            return false;
        }
    }

    private void updateTaskTerminal(SyncTask task, String status) {
        task.setLastRunTime(LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()));
        task.setLastRunStatus(status);
    }

    private boolean shouldCommitWatermark(WatermarkService.WindowContext window) {
        return window != null
                && ("INCREMENT".equals(window.windowType()) || window.isInitialFullSync());
    }

    private WatermarkService.WindowContext reconstructWindow(TaskExecution execution) {
        if (execution.getWindowType() == null) return null;
        return new WatermarkService.WindowContext(
                execution.getWindowType(), execution.getWindowStart(), execution.getWindowEnd(),
                execution.getWindowStartId(), execution.getWindowEndId());
    }

    private void triggerMessagePublishSafely(
            SyncTask task, Long executionId, WatermarkService.WindowContext window) {
        try {
            boolean incremental = isIncrementalPublishWindow(window);
            String dataScope = incremental ? "INCREMENTAL" : "FULL";
            Instant start = incremental ? window.windowStart() : null;
            Instant end = incremental ? window.windowEnd() : null;
            messagePublishTrigger.preparePublishRun(
                    task.getId(), executionId, dataScope, start, end);
            messagePublishTrigger.onSyncSuccess(
                    task.getId(), executionId, dataScope, start, end);
        } catch (Exception e) {
            log.warn("ExecutionFinalization: message publish dispatch failed task={} exec={}: {}",
                    task.getId(), executionId, e.getMessage());
        }
    }

    private boolean isIncrementalPublishWindow(WatermarkService.WindowContext window) {
        if (window == null || window.windowType() == null) return false;
        if ("INCREMENT".equalsIgnoreCase(window.windowType())) {
            return window.windowStart() != null || window.windowEnd() != null
                    || window.windowStartId() != null || window.windowEndId() != null;
        }
        return "CUSTOM_WINDOW".equalsIgnoreCase(window.windowType())
                && (window.windowStart() != null || window.windowEnd() != null);
    }
}
