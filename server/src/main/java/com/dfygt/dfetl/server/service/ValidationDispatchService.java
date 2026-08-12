package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * 校验派单服务。
 *
 * <p>spec validation-table-consolidation · Step 5：
 * 改造为直接创建 {@link ValidationRun} 替代原 ValidationTask，
 * 所有写入路径统一指向 validation_run 表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationDispatchService {

    private final ValidationRunRepository validationRunRepo;
    private final ValidationRunner validationRunner;
    private final SyncTaskRepository syncTaskRepo;

    /**
     * 触发式派单（兼容旧签名：windowStart/windowEnd 直接传入）。
     */
    @Transactional
    public ValidationRun dispatchTriggered(SyncTask task,
                                           Long executionId,
                                           String triggerType,
                                           String method,
                                           Instant windowStart,
                                           Instant windowEnd,
                                           TaskValidationConfig config) {
        WatermarkService.WindowContext window = windowStart == null && windowEnd == null
                ? null
                : new WatermarkService.WindowContext("INCREMENT", windowStart, windowEnd, null, null);
        return dispatchTriggered(task, executionId, triggerType, method, window, config);
    }

    /**
     * 触发式派单（完整 WindowContext 版本）。
     *
     * <p>使用悲观锁保证同一 taskId 同时只有一个 RUNNING 校验。
     */
    @Transactional
    public ValidationRun dispatchTriggered(SyncTask task,
                                           Long executionId,
                                           String triggerType,
                                           String method,
                                           WatermarkService.WindowContext window,
                                           TaskValidationConfig config) {
        Long taskId = requireTaskId(task);
        lockTask(taskId);
        List<ValidationRun> running = validationRunRepo.findRunningByTaskIdForUpdate(taskId);
        if (!running.isEmpty()) {
            log.warn("ValidationDispatch: SKIPPED taskId={} triggerType={} executionId={} "
                   + "- RUNNING validation exists (lockedIds={})",
                    taskId, triggerType, executionId,
                    running.stream().map(ValidationRun::getId).toList());
            return null;
        }
        String normalizedMethod = normalizeMethodForTriggeredTask(task, method);

        ValidationRun run = new ValidationRun();
        run.setName(buildTriggeredName(task, triggerType));
        run.setTaskId(taskId);
        run.setExecutionId(executionId);
        run.setLegacyExecId(executionId != null ? executionId : ValidationRunService.nextSyntheticLegacyExecId());
        run.setTriggerType(triggerType);
        run.setStatus("RUNNING");
        run.setMethod(normalizedMethod);
        run.setMode(normalizedMethod);
        run.setScope(window == null || isFullScope(window) ? "FULL" : "WINDOW");
        run.setTablesText("");
        copyWindow(run, window, task);
        run.setUpdatedAt(LocalDateTime.now());
        ValidationRun saved = validationRunRepo.save(run);
        runAfterCommit(saved, config);
        return saved;
    }

    /**
     * Revalidate 专用：在事务内使用悲观锁检查是否已有 RUNNING 任务，
     * 如果没有则将指定 ValidationRun 状态更新为 RUNNING。
     *
     * @return true 表示成功获取锁并更新状态；false 表示已有 RUNNING 任务，应跳过
     */
    @Transactional
    public boolean tryLockAndMarkRunningForRevalidate(Long taskId, Long validationId) {
        lockTask(taskId);
        List<ValidationRun> running = validationRunRepo.findRunningByTaskIdForUpdate(taskId);
        if (!running.isEmpty()) {
            log.warn("ValidationDispatch revalidate: SKIPPED taskId={} validationId={} "
                   + "- RUNNING validation exists (lockedIds={})",
                    taskId, validationId,
                    running.stream().map(ValidationRun::getId).toList());
            return false;
        }
        ValidationRun retry = validationRunRepo.findById(validationId).orElse(null);
        if (retry == null || !"DIFF".equals(retry.getStatus())) {
            return false;
        }
        retry.setStatus("RUNNING");
        retry.setUpdatedAt(LocalDateTime.now());
        validationRunRepo.save(retry);
        return true;
    }

    /**
     * 手动派单。
     *
     * <p>接收前端传入的 ValidationRun（已填充 taskId、method 等基础字段），
     * 设置 triggerType=MANUAL、status=RUNNING 后保存并异步执行。
     */
    @Transactional
    public ValidationRun dispatchManual(ValidationRun validationRun, TaskValidationConfig config) {
        Long taskId = validationRun.getTaskId();
        SyncTask lockedTask = taskId == null ? null : lockTask(taskId);
        if (taskId != null && !validationRunRepo.findRunningByTaskIdForUpdate(taskId).isEmpty()) {
            throw new IllegalStateException("已存在 RUNNING 校验任务，taskId=" + taskId);
        }
        validationRun.setTriggerType("MANUAL");
        validationRun.setStatus("RUNNING");
        if (config != null && config.getMethod() != null && !config.getMethod().isBlank()) {
            validationRun.setMethod(normalizeMethodForManualTask(lockedTask, config.getMethod()));
        }
        // 确保 mode 与 method 一致
        if (validationRun.getMethod() != null) {
            validationRun.setMode(validationRun.getMethod());
        }
        // 确保 scope 有值
        if (validationRun.getScope() == null || validationRun.getScope().isBlank()) {
            validationRun.setScope("FULL");
        }
        // 确保 legacyExecId 有值（NOT NULL 约束）
        if (validationRun.getLegacyExecId() == null) {
            validationRun.setLegacyExecId(ValidationRunService.nextSyntheticLegacyExecId());
        }
        validationRun.setUpdatedAt(LocalDateTime.now());
        ValidationRun saved = validationRunRepo.save(validationRun);
        runAfterCommit(saved, config);
        return saved;
    }

    @Transactional
    public ValidationRun startManualChecksumRun(Long taskId,
                                                Long legacyExecId,
                                                String scope,
                                                Instant windowStart,
                                                Instant windowEnd) {
        lockTask(taskId);
        List<ValidationRun> running = validationRunRepo.findRunningByTaskIdForUpdate(taskId);
        if (!running.isEmpty()) {
            throw new IllegalStateException("已存在 RUNNING 校验任务，请稍后再试");
        }

        Long runExecId = legacyExecId != null ? legacyExecId : ValidationRunService.nextSyntheticLegacyExecId();
        String normalizedScope = normalizeScope(scope, windowStart, windowEnd);
        java.util.Optional<ValidationRun> existingOpt = validationRunRepo.findByTaskIdAndLegacyExecId(taskId, runExecId);
        if (legacyExecId != null && existingOpt.isPresent() && isTerminalRun(existingOpt.get())) {
            throw new IllegalStateException("legacyExecId=" + legacyExecId
                    + " 已对应终态 ValidationRun，direct CHECKSUM 不允许复用历史 run；"
                    + "请不传 execId 创建新 run，或使用 resumeFromRunId 做断点续跑");
        }
        ValidationRun run = existingOpt.orElseGet(ValidationRun::new);
        run.setTaskId(taskId);
        run.setLegacyExecId(runExecId);
        run.setTriggerType("MANUAL");
        run.setStatus("RUNNING");
        run.setMethod("CHECKSUM");
        run.setMode("CHECKSUM");
        run.setScope(normalizedScope);
        run.setWindowStart(windowStart);
        run.setWindowEnd(windowEnd);
        run.setUpdatedAt(LocalDateTime.now());
        return validationRunRepo.save(run);
    }

    private boolean isTerminalRun(ValidationRun run) {
        String status = run == null || run.getStatus() == null ? "" : run.getStatus().trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "CONSISTENT", "DIFF", "ERROR", "FAILED", "CANCELLED", "SKIPPED" -> true;
            default -> false;
        };
    }

    private Long requireTaskId(SyncTask task) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("SyncTask.taskId 不能为空");
        }
        return task.getId();
    }

    private String buildTriggeredName(SyncTask task, String triggerType) {
        String baseName = task.getName() == null ? "task-" + task.getId() : task.getName();
        return baseName + "-" + triggerType.toLowerCase(Locale.ROOT);
    }

    private SyncTask lockTask(Long taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        return syncTaskRepo.findByIdForUpdate(taskId)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + taskId));
    }

    private void runAfterCommit(ValidationRun run, TaskValidationConfig config) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    validationRunner.runAsync(run, config);
                }
            });
        } else {
            validationRunner.runAsync(run, config);
        }
    }

    private String normalizeScope(String scope, Instant windowStart, Instant windowEnd) {
        if (scope != null && !scope.isBlank()) {
            return scope.trim().toUpperCase(Locale.ROOT);
        }
        return windowStart != null || windowEnd != null ? "WINDOW" : "FULL";
    }

    private String normalizeMethod(String method) {
        return method == null || method.isBlank() ? "ROW_COUNT" : method.toUpperCase(Locale.ROOT);
    }

    private String normalizeMethodForTriggeredTask(SyncTask task, String method) {
        String normalized = normalizeMethod(method);
        if (isUnsupportedChecksum(task, normalized)) {
            throw new IllegalArgumentException("当前任务不支持 " + normalized
                    + " 校验：CUSTOM_SQL 或未配置 splitPk/upsertKeys 时只能使用 ROW_COUNT");
        }
        return normalized;
    }

    private String normalizeMethodForManualTask(SyncTask task, String method) {
        String normalized = normalizeMethod(method);
        if (isUnsupportedChecksum(task, normalized)) {
            throw new IllegalArgumentException("当前任务不支持 " + normalized
                    + " 校验：CUSTOM_SQL 或未配置 splitPk/upsertKeys 时只能使用 ROW_COUNT");
        }
        return normalized;
    }

    private boolean isUnsupportedChecksum(SyncTask task, String method) {
        if (!requiresChecksum(method)) {
            return false;
        }
        if (task != null && "CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode())) {
            return true;
        }
        return !hasChecksumKey(task);
    }

    private boolean requiresChecksum(String method) {
        return "CHECKSUM".equals(method)
                || "ROW_COUNT_CHECKSUM".equals(method)
                || "ALL".equals(method);
    }

    private boolean hasChecksumKey(SyncTask task) {
        if (task == null) {
            return false;
        }
        if (task.getSplitPk() != null && !task.getSplitPk().isBlank()) {
            return true;
        }
        return task.getUpsertKeys() != null && task.getUpsertKeys().stream()
                .anyMatch(key -> key != null && !key.isBlank());
    }

    /**
     * 判断窗口是否为全量范围。
     */
    private static boolean isFullScope(WatermarkService.WindowContext window) {
        if (window == null) return true;
        return window.windowStart() == null
                && window.windowEnd() == null
                && window.windowStartId() == null
                && window.windowEndId() == null;
    }

    static void copyWindow(ValidationRun run, WatermarkService.WindowContext window) {
        copyWindow(run, window, null);
    }

    static void copyWindow(ValidationRun run, WatermarkService.WindowContext window, SyncTask task) {
        if (run == null || window == null) {
            return;
        }
        run.setWindowType(validationWindowType(window, task));
        run.setWindowStart(window.windowStart());
        run.setWindowEnd(window.windowEnd());
        run.setWindowStartId(window.windowStartId());
        run.setWindowEndId(window.windowEndId());
    }

    private static String validationWindowType(WatermarkService.WindowContext window, SyncTask task) {
        if (window == null || !"INCREMENT".equalsIgnoreCase(window.windowType()) || task == null) {
            return window == null ? null : window.windowType();
        }
        if ("ID_RANGE".equalsIgnoreCase(task.getIncrementMode())) {
            return "ID_RANGE";
        }
        if ("TIME_FIELD".equalsIgnoreCase(task.getIncrementMode())
                || window.windowStart() != null || window.windowEnd() != null) {
            return "TIME_FIELD";
        }
        return window.windowType();
    }
}
