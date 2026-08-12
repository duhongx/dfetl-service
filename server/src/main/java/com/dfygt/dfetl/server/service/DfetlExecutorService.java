package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.config.retry.RetryConfigProperties;
import com.dfygt.dfetl.server.engine.ExecutionResult;
import com.dfygt.dfetl.server.engine.ExecutorRouter;
import com.dfygt.dfetl.server.engine.ExecutorStrategy;
import com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelRestClient;
import com.dfygt.dfetl.server.entity.DirtyRecord;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.medical.quality.MedicalDirtyExecutionResult;
import com.dfygt.dfetl.server.medical.quality.MedicalDirtyExecutionService;
import com.dfygt.dfetl.server.medical.precheck.DfetlPrecheckGateService;
import com.dfygt.dfetl.server.repository.DirtyRecordRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import com.dfygt.dfetl.server.service.publish.MessagePublishTrigger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 调用执行引擎执行 ETL 任务。
 * 通过 ExecutorRouter 分发到对应引擎策略（SeaTunnel Local / Cluster）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DfetlExecutorService {

    private final SyncTaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final DirtyRecordRepository dirtyRecordRepository;
    private final WatermarkService watermarkService;
    private final AlertEvaluatorService alertEvaluatorService;
    private final ExecutorRouter executorRouter;
    private final com.dfygt.dfetl.server.engine.doris.DorisTableEnsurer dorisTableEnsurer;
    private final SnapshotOrchestrator snapshotOrchestrator;
    private final AutoValidationTrigger autoValidationTrigger;
    private final GlobalSettingsService globalSettingsService;
    private final ValidationGateService validationGateService;
    private final SharedTargetTableGuard sharedTargetTableGuard;
    private final RecollectPreflightService recollectPreflightService;
    private final DfetlPrecheckGateService precheckGateService;
    private final ObjectProvider<MedicalDirtyExecutionService> medicalDirtyExecutionService;
    private final ExecutionCancellationRegistry cancellationRegistry;
    private final MessagePublishTrigger messagePublishTrigger;
    private final RetryConfigProperties retryConfigProperties;
    private final ShutdownState shutdownState;
    private final ObjectProvider<SeaTunnelRestClient> seaTunnelRestClient;
    private final ExecutionSuccessFinalizationService successFinalizationService;

    private static final DateTimeFormatter BATCH_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final ObjectMapper MEDICAL_DIRTY_OBJECT_MAPPER = new ObjectMapper();

    private final Executor asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 异步执行同步任务，返回 CompletableFuture 包含退出码。
     */
    public CompletableFuture<Integer> runAsync(Long taskId, String triggeredBy) {
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
            cancellationRegistry.registerRunner(taskId, Thread.currentThread());
            if (cancellationRegistry.isRequested(taskId)) {
                log.info("DfetlExecutorService: task={} cancelled before execution started", taskId);
                return 1;
            }
            SyncTask task = taskRepository.findById(taskId).orElse(null);
            if (task == null) {
                if (isSchedulerTrigger(triggeredBy)) {
                    log.warn("DfetlExecutorService: skip stale scheduler trigger for missing task={}", taskId);
                    return 0;
                }
                throw new NoSuchElementException("SyncTask not found: " + taskId);
            }
            if (isSchedulerTrigger(triggeredBy) && !isSchedulerRunnable(task)) {
                log.warn("DfetlExecutorService: skip scheduler trigger for task={} status={} cronExpression={} schedule={}",
                        taskId, task.getStatus(), task.getCronExpression(), task.getSchedule());
                return 0;
            }

            Optional<TaskExecution> blockingExecution = findUnclosedExecution(taskId);
            if (blockingExecution.isPresent()) {
                TaskExecution previous = blockingExecution.get();
                String message = "previous SeaTunnel job is not closed: exec=" + previous.getId()
                        + " status=" + previous.getStatus()
                        + " engineJobId=" + previous.getEngineJobId();
                log.warn("DfetlExecutorService: block new execution for task={} because {}", taskId, message);
                return failBeforeEngineExecution(task, triggeredBy, message);
            }

            List<String> tableList = task.getViewNames() != null ? task.getViewNames() : List.of();
            try {
                validateMultiTableIncrementalSupported(task, tableList);
            } catch (Exception e) {
                log.error("DfetlExecutorService: task {} rejected before window compute: {}",
                        taskId, e.getMessage());
                return failBeforeEngineExecution(task, triggeredBy, normalizeErrorMessage(e));
            }

            if (!isStandardDatasetRouteTask(task)) {
                try {
                    recollectPreflightService.assertMedicalDataReady(task);
                } catch (Exception e) {
                    log.error("DfetlExecutorService: medical dynamic precheck failed for task {}: {}",
                            taskId, e.getMessage(), e);
                    return failBeforeEngineExecution(task, triggeredBy, normalizeErrorMessage(e));
                }
            }

            // 1. 计算增量窗口。ID_RANGE 的 MAX(id) 探测失败必须 fail-closed，不能退化 FULL。
            WatermarkService.WindowContext window;
            try {
                window = watermarkService.computeWindow(task);
            } catch (Exception e) {
                log.error("DfetlExecutorService: compute window failed for task {}: {}",
                        taskId, e.getMessage(), e);
                return failBeforeEngineExecution(task, triggeredBy, normalizeErrorMessage(e));
            }

            try {
                validateAppendInitialFullSyncCompatible(task, window);
                validateAppendBlockingGateCompatible(task, window);
            } catch (Exception e) {
                log.error("DfetlExecutorService: task {} rejected before engine execution: {}",
                        taskId, e.getMessage(), e);
                return failBeforeEngineExecution(task, triggeredBy, normalizeErrorMessage(e));
            }

            // 2. 路由到执行引擎
            ExecutorStrategy strategy = executorRouter.route(task);

            // ── 多表拆分：SeaTunnel 每次只能提交单表，多表时自动拆分为多个 TaskExecution ──
            if (tableList.size() > 1 && strategy.type().startsWith("SEATUNNEL")) {
                log.info("DfetlExecutorService: multi-table split task={} tables={} engine={}",
                        taskId, tableList, strategy.type());
                return runMultiTable(task, tableList, window, strategy, triggeredBy);
            }

            // 3. 单表路径：创建执行记录（PENDING）
            String batchNo = LocalDateTime.now().format(BATCH_FMT) + "_" + taskId;
            TaskExecution exec = new TaskExecution();
            exec.setTaskId(taskId);
            exec.setBatchNo(batchNo);
            exec.setTriggeredBy(triggeredBy);
            exec.setStatus("PENDING");
            exec.setSnapshotSyncType(task.getSyncType());
            exec.setSnapshotSyncMode(task.getSyncMode());
            exec.setSnapshotViewNames(task.getViewNames() != null
                    ? String.join(",", task.getViewNames()) : null);
            exec.setWindowType(window.windowType());
            exec.setWindowStart(window.windowStart());
            exec.setWindowEnd(window.windowEnd());
            exec.setWindowStartId(window.windowStartId());
            exec.setWindowEndId(window.windowEndId());
            exec.setExecutorType(task.getExecutorType() != null ? task.getExecutorType() : "SEATUNNEL_CLUSTER");
            exec = executionRepository.save(exec);

            Instant startedAt = Instant.now();
            exec.setStatus("RUNNING");
            exec.setStartedAt(startedAt);
            executionRepository.save(exec);

            MedicalDirtyExecutionResult dirtyDiversion;
            boolean targetPreparedForWriteSafety = false;
            try {
                if (isStandardDatasetRouteTask(task)) {
                    sharedTargetTableGuard.assertTruncateSafe(task);
                    dorisTableEnsurer.ensureTargetTables(task);
                    targetPreparedForWriteSafety = true;
                }
                dirtyDiversion = prepareMedicalDirtyDiversionIfRequired(task, window, exec);
            } catch (Exception e) {
                String errorMsg = normalizeErrorMessage(e);
                Instant finishedAt = Instant.now();
                log.error("DfetlExecutorService: medical dirty diversion failed for task={} exec={}: {}",
                        taskId, exec.getId(), errorMsg, e);
                exec.setStatus("FAILED");
                exec.setFinishedAt(finishedAt);
                exec.setDurationMs(finishedAt.toEpochMilli() - startedAt.toEpochMilli());
                exec.setReadRows(0L);
                exec.setWriteRows(0L);
                exec.setFailedRows(0L);
                exec.setErrorMsg(errorMsg);
                executionRepository.save(exec);
                task.setLastRunTime(LocalDateTime.ofInstant(finishedAt, java.time.ZoneId.systemDefault()));
                task.setLastRunStatus("FAILED");
                taskRepository.save(task);
                alertEvaluatorService.evaluate(task, exec);
                return -1;
            }
            if (dirtyDiversion.validSourceQuery() != null && !dirtyDiversion.validSourceQuery().isBlank()) {
                exec.setMedicalValidSourceQuery(dirtyDiversion.validSourceQuery());
                task.setDataCharacteristics(withMedicalValidSourceQuery(
                        task.getDataCharacteristics(), dirtyDiversion.validSourceQuery()));
            }
            if (dirtyDiversion.applied()) {
                exec.setExcludedRows(Math.max(0L, dirtyDiversion.excludedRows()));
                exec.setWarningRows(Math.max(0L, dirtyDiversion.warningRows()));
                exec.setFailedRows(Math.max(0L, dirtyDiversion.excludedRows()));
                executionRepository.save(exec);
            }

            if (cancellationRegistry.isRequested(taskId)) {
                exec.setStatus("CANCELLED");
                exec.setFinishedAt(Instant.now());
                exec.setErrorMsg("User cancelled before SeaTunnel submission");
                executionRepository.save(exec);
                return finishCancelledFlow(task, exec, "CANCELLED");
            }

            ExecutionResult result;
            try {
                // 4. 确保目标 Doris 表存在（不存在则自动建表）；失败时保留本次执行记录，便于前端展示原因
                if (!targetPreparedForWriteSafety) {
                    sharedTargetTableGuard.assertTruncateSafe(task);
                    dorisTableEnsurer.ensureTargetTables(task);
                }
                // 5. 委托执行引擎
                result = strategy.execute(task, window, exec).get();
            } catch (Exception e) {
                log.error("Task execution failed for task {} exec={} errType={} err={}",
                        taskId, exec.getId(), e.getClass().getSimpleName(), normalizeErrorMessage(e));
                result = new ExecutionResult(-1, 0, 0, 0, normalizeErrorMessage(e));
            }
            result = normalizeFailedRowsAsFailure(result);

            TaskExecution externallyFinished = cancellationTerminal(exec.getId()).orElse(null);
            if (externallyFinished != null) {
                return finishCancelledFlow(task, externallyFinished, externallyFinished.getStatus());
            }

            // 记录脏数据摘要：执行失败或引擎返回 failedRows>0 时，至少沉淀一条可追踪记录。
            if (result.exitCode() != 0 || result.failedRows() > 0) {
                saveDirtyRecordSummary(taskId, exec.getId(), result);
            }

            Instant finishedAt = Instant.now();
            long durationMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();

            int finalExitCode = result.exitCode();
            String finalErrorMsg = result.exitCode() != 0
                    ? ExecutionErrorSanitizer.sanitize(result.errorMsg())
                    : null;

            // 更新首次执行记录
            String firstExecStatus = dirtyAwareFinalStatus(finalExitCode, result, dirtyDiversion);
            exec.setStatus(firstExecStatus);
            exec.setFinishedAt(finishedAt);
            exec.setDurationMs(durationMs);
            exec.setReadRows(result.readRows());
            exec.setWriteRows(result.writeRows());
            exec.setFailedRows(result.failedRows() + dirtyDiversion.excludedRows());
            applyMedicalDiversionSummary(exec, result, dirtyDiversion);
            exec.setEngineReadRows(result.engineReadRows());
            exec.setEngineWriteRows(result.engineWriteRows());
            if (finalExitCode != 0) {
                exec.setErrorMsg(finalErrorMsg);
            } else if (dirtyDiversion.hasDirtyRows()) {
                exec.setErrorMsg("医共体问题行分流：excludedRows=" + dirtyDiversion.excludedRows()
                        + ", warningRows=" + dirtyDiversion.warningRows());
            }
            executionRepository.save(exec);

            // ── 自动重试循环（仅单表路径）──
            // 重试在同一 CompletableFuture 内完成，不释放 semaphore 槽位
            int retryCount = 0;
            while (finalExitCode != 0
                    && !cancellationRegistry.isRequested(taskId)
                    && shouldAutoRetry(task, result, retryCount)) {
                // 停机感知：执行线程不在 TaskExecutionQueue.waitingThreads 中，
                // initiateShutdown() 不会 interrupt 本线程，必须显式检查停机标志，
                // 否则停机窗口内仍会拉起新的远程 SeaTunnel 作业，造成状态漂移。
                if (shutdownState.isShuttingDown()) {
                    log.warn("[Retry:TaskExecution:{}] shutdown in progress, aborting auto-retry", taskId);
                    break;
                }

                int intervalSeconds = resolveRetryIntervalSeconds(task);
                int maxRetries = resolveMaxRetries(task);
                log.info("[Retry:TaskExecution:{}] auto-retry attempt {}/{}, waiting {}s",
                        taskId, retryCount + 1, maxRetries, intervalSeconds);

                // 检查中断标志（停机信号）
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("[Retry:TaskExecution:{}] interrupted (shutdown), aborting retry", taskId);
                    break;
                }

                try {
                    Thread.sleep(intervalSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[Retry:TaskExecution:{}] sleep interrupted (shutdown), aborting retry", taskId);
                    break;
                }

                // sleep 结束后再次确认未进入停机，避免在 sleep 期间收到停机信号后仍提交新作业
                if (shutdownState.isShuttingDown()) {
                    log.warn("[Retry:TaskExecution:{}] shutdown signaled during retry wait, aborting auto-retry", taskId);
                    break;
                }

                retryCount++;

                // 创建新的 TaskExecution 记录
                String retryBatchNo = LocalDateTime.now().format(BATCH_FMT) + "_" + taskId + "_retry" + retryCount;
                TaskExecution retryExec = new TaskExecution();
                retryExec.setTaskId(taskId);
                retryExec.setBatchNo(retryBatchNo);
                retryExec.setTriggeredBy("AUTO_RETRY");
                retryExec.setStatus("RUNNING");
                retryExec.setSnapshotSyncType(task.getSyncType());
                retryExec.setSnapshotSyncMode(task.getSyncMode());
                retryExec.setSnapshotViewNames(task.getViewNames() != null
                        ? String.join(",", task.getViewNames()) : null);
                retryExec.setWindowType(window.windowType());
                retryExec.setWindowStart(window.windowStart());
                retryExec.setWindowEnd(window.windowEnd());
                retryExec.setWindowStartId(window.windowStartId());
                retryExec.setWindowEndId(window.windowEndId());
                retryExec.setExecutorType(task.getExecutorType() != null ? task.getExecutorType() : "SEATUNNEL_CLUSTER");
                Instant retryStartedAt = Instant.now();
                retryExec.setStartedAt(retryStartedAt);
                retryExec = executionRepository.save(retryExec);

                // 重新执行
                ExecutionResult retryResult;
                MedicalDirtyExecutionResult retryDiversion = MedicalDirtyExecutionResult.empty();
                boolean retryTargetPreparedForWriteSafety = false;
                try {
                    if (isStandardDatasetRouteTask(task)) {
                        sharedTargetTableGuard.assertTruncateSafe(task);
                        dorisTableEnsurer.ensureTargetTables(task);
                        retryTargetPreparedForWriteSafety = true;
                    }
                    retryDiversion = prepareMedicalDirtyDiversionIfRequired(task, window, retryExec);
                    if (retryDiversion.validSourceQuery() != null
                            && !retryDiversion.validSourceQuery().isBlank()) {
                        retryExec.setMedicalValidSourceQuery(retryDiversion.validSourceQuery());
                        task.setDataCharacteristics(withMedicalValidSourceQuery(
                                task.getDataCharacteristics(), retryDiversion.validSourceQuery()));
                        executionRepository.save(retryExec);
                    }
                    if (!retryTargetPreparedForWriteSafety) {
                        sharedTargetTableGuard.assertTruncateSafe(task);
                        dorisTableEnsurer.ensureTargetTables(task);
                    }
                    retryResult = strategy.execute(task, window, retryExec).get();
                } catch (Exception e) {
                    log.error("[Retry:TaskExecution:{}] retry {} failed errType={} err={}",
                            taskId, retryCount, e.getClass().getSimpleName(), normalizeErrorMessage(e));
                    retryResult = new ExecutionResult(-1, 0, 0, 0, normalizeErrorMessage(e));
                }
                retryResult = normalizeFailedRowsAsFailure(retryResult);

                TaskExecution externallyFinishedRetry = cancellationTerminal(retryExec.getId()).orElse(null);
                if (externallyFinishedRetry != null) {
                    return finishCancelledFlow(task, externallyFinishedRetry, externallyFinishedRetry.getStatus());
                }

                // 记录脏数据摘要
                if (retryResult.exitCode() != 0 || retryResult.failedRows() > 0) {
                    saveDirtyRecordSummary(taskId, retryExec.getId(), retryResult);
                }

                // 更新重试执行记录
                Instant retryFinishedAt = Instant.now();
                long retryDurationMs = retryFinishedAt.toEpochMilli() - retryStartedAt.toEpochMilli();
                String retryStatus = dirtyAwareFinalStatus(retryResult.exitCode(), retryResult, retryDiversion);
                retryExec.setStatus(retryStatus);
                retryExec.setFinishedAt(retryFinishedAt);
                retryExec.setDurationMs(retryDurationMs);
                retryExec.setReadRows(retryResult.readRows());
                retryExec.setWriteRows(retryResult.writeRows());
                retryExec.setFailedRows(retryResult.failedRows() + retryDiversion.excludedRows());
                applyMedicalDiversionSummary(retryExec, retryResult, retryDiversion);
                retryExec.setEngineReadRows(retryResult.engineReadRows());
                retryExec.setEngineWriteRows(retryResult.engineWriteRows());
                if (retryDiversion.validSourceQuery() != null && !retryDiversion.validSourceQuery().isBlank()) {
                    retryExec.setMedicalValidSourceQuery(retryDiversion.validSourceQuery());
                }
                if (retryResult.exitCode() != 0) {
                    retryExec.setErrorMsg(ExecutionErrorSanitizer.sanitize(retryResult.errorMsg()));
                } else if (retryDiversion.hasDirtyRows()) {
                    retryExec.setErrorMsg("医共体问题行分流：excludedRows=" + retryDiversion.excludedRows()
                            + ", warningRows=" + retryDiversion.warningRows());
                }
                executionRepository.save(retryExec);

                // 更新最终结果
                finalExitCode = retryResult.exitCode();
                finalErrorMsg = retryResult.exitCode() != 0
                        ? ExecutionErrorSanitizer.sanitize(retryResult.errorMsg())
                        : null;
                exec = retryExec; // 指向最新的执行记录，用于后续后置动作
                result = retryResult;
                dirtyDiversion = retryDiversion;
            }

            if (cancellationRegistry.isRequested(taskId)) {
                String status = cancellationTerminal(exec.getId())
                        .map(TaskExecution::getStatus)
                        .orElse("CANCELLED");
                return finishCancelledFlow(task, exec, status);
            }

            if (finalExitCode == 0) {
                return successFinalizationService.finalizeSuccessfulExecution(task, exec, window)
                        ? 0
                        : -1;
            }

            String finalStatus = dirtyAwareFinalStatus(finalExitCode, result, dirtyDiversion);
            task.setLastRunTime(LocalDateTime.ofInstant(Instant.now(), java.time.ZoneId.systemDefault()));
            task.setLastRunStatus(finalStatus);
            taskRepository.save(task);
            alertEvaluatorService.evaluate(task, exec);
            return finalExitCode;
        }, asyncExecutor);
        return future.whenComplete((ignored, error) -> cancellationRegistry.clear(taskId));
    }

    /**
     * 多表拆分执行：每张表独立创建 TaskExecution，顺序提交 SeaTunnel。
     * 全部成功返回 0，有失败返回 -1。
     */
    private int runMultiTable(
            SyncTask task,
            List<String> tableList,
            WatermarkService.WindowContext window,
            ExecutorStrategy strategy,
            String triggeredBy) {
        long taskId = task.getId();
        // 能力提示：自动重试当前仅在单表路径实现，多表任务任一子表瞬态失败不会自动重试。
        // 若任务配置了重试次数，明确记录 WARN，避免配置静默失效造成误解。
        if (resolveMaxRetries(task) > 0) {
            log.warn("DfetlExecutorService: task={} configured retryMaxAttempts={} but multi-table path "
                            + "does NOT support auto-retry; sub-table transient failures will NOT be retried",
                    taskId, resolveMaxRetries(task));
        }
        int overallExit = 0;
        boolean overallRequiresReconcile = false;
        List<SuccessfulChildExecution> successfulChildren = new ArrayList<>();
        for (int idx = 0; idx < tableList.size(); idx++) {
            if (cancellationRegistry.isRequested(taskId)) {
                overallExit = -1;
                break;
            }
            String tableName = tableList.get(idx);
            // 为每张表构造内存副本（不持久化），仅覆盖 viewNames
            SyncTask singleTask = shallowCopy(task, tableName);

            // batchNo: yyyyMMdd_HHmmss_taskId_idx（使用索引避免表名截断后重复）
            String batchNo = LocalDateTime.now().format(BATCH_FMT) + "_" + taskId + "_" + idx;
            TaskExecution subExec = new TaskExecution();
            subExec.setTaskId(taskId);
            subExec.setBatchNo(batchNo);
            subExec.setTriggeredBy(triggeredBy);
            subExec.setStatus("RUNNING");
            subExec.setSnapshotSyncType(task.getSyncType());
            subExec.setSnapshotSyncMode(task.getSyncMode());
            subExec.setSnapshotViewNames(tableName);
            subExec.setWindowType(window.windowType());
            subExec.setWindowStart(window.windowStart());
            subExec.setWindowEnd(window.windowEnd());
            subExec.setWindowStartId(window.windowStartId());
            subExec.setWindowEndId(window.windowEndId());
            subExec.setExecutorType(task.getExecutorType() != null ? task.getExecutorType() : "SEATUNNEL_CLUSTER");
            Instant startedAt = Instant.now();
            subExec.setStartedAt(startedAt);
            subExec = executionRepository.save(subExec);

            ExecutionResult result;
            try {
                sharedTargetTableGuard.assertTruncateSafe(singleTask);
                dorisTableEnsurer.ensureTargetTables(singleTask);
                result = strategy.execute(singleTask, window, subExec).get();
            } catch (Exception e) {
                log.error("Multi-table exec failed task={} table={} exec={} errType={} err={}",
                        taskId, tableName, subExec.getId(), e.getClass().getSimpleName(),
                        normalizeErrorMessage(e));
                result = new ExecutionResult(-1, 0, 0, 0, normalizeErrorMessage(e));
            }
            result = normalizeFailedRowsAsFailure(result);

            TaskExecution externallyFinished = cancellationTerminal(subExec.getId()).orElse(null);
            if (externallyFinished != null) {
                task.setLastRunTime(LocalDateTime.ofInstant(Instant.now(), java.time.ZoneId.systemDefault()));
                task.setLastRunStatus(externallyFinished.getStatus());
                taskRepository.save(task);
                return -1;
            }

            if (result.exitCode() != 0 || result.failedRows() > 0) {
                saveDirtyRecordSummary(taskId, subExec.getId(), result);
            }

            Instant finishedAt = Instant.now();
            String subStatus = finalStatusFor(result.exitCode(), result);
            subExec.setStatus(subStatus);
            subExec.setFinishedAt(finishedAt);
            subExec.setDurationMs(finishedAt.toEpochMilli() - startedAt.toEpochMilli());
            subExec.setReadRows(result.readRows());
            subExec.setWriteRows(result.writeRows());
            subExec.setFailedRows(result.failedRows());
            subExec.setEngineReadRows(result.engineReadRows());
            subExec.setEngineWriteRows(result.engineWriteRows());
            if (result.exitCode() != 0) {
                subExec.setErrorMsg(ExecutionErrorSanitizer.sanitize(result.errorMsg()));
            }
            executionRepository.save(subExec);

            alertEvaluatorService.evaluate(task, subExec);

            if (result.exitCode() == 0) {
                successfulChildren.add(new SuccessfulChildExecution(singleTask, subExec.getId()));
            }

            if (result.requiresReconcile()) {
                overallRequiresReconcile = true;
                overallExit = result.exitCode();
                break;
            }
            if (result.exitCode() != 0) overallExit = result.exitCode();
        }

        // 更新任务最后运行状态
        String overallStatus = cancellationRegistry.isRequested(taskId)
                ? "CANCELLED"
                : overallRequiresReconcile
                ? ExecutionResult.STATUS_RECONCILE_REQUIRED
                : (overallExit == 0) ? "SUCCESS" : "FAILED";
        task.setLastRunTime(LocalDateTime.ofInstant(Instant.now(), java.time.ZoneId.systemDefault()));
        task.setLastRunStatus(overallStatus);

        // 多表全量→增量：整批成功后统一 Gate 检查 + 提交水位
        if (!cancellationRegistry.isRequested(taskId)
                && overallExit == 0 && shouldCommitWatermark(window)) {
            // 使用最后一个成功子表的 execId 进行 Gate 检查
            Long lastExecId = successfulChildren.isEmpty() ? null
                    : successfulChildren.get(successfulChildren.size() - 1).executionId();
            boolean canCommit = checkValidationGate(task, lastExecId, window);
            if (canCommit) {
                watermarkService.commit(task, window);
            } else {
                log.warn("DfetlExecutorService: validation gate blocked multi-table task={}, watermark NOT committed",
                        task.getId());
                overallExit = -1;
                overallStatus = "FAILED";
                task.setLastRunStatus(overallStatus);
                taskRepository.save(task);
            }
        } else {
            taskRepository.save(task);
        }

        // success-only 后置动作必须等待整批所有子表最终成功；任一子表失败或 Gate 阻断均不触发。
        if (!cancellationRegistry.isRequested(taskId) && overallExit == 0) {
            for (SuccessfulChildExecution child : successfulChildren) {
                snapshotOrchestrator.onTaskExecutionSucceeded(child.task(), child.executionId());
                autoValidationTrigger.onExecutionSuccess(child.task(), child.executionId(), window);
            }
            // 消息发布：多表整批成功后异步触发，使用最后一个子表的 execId
            Long lastExecIdForPublish = successfulChildren.isEmpty() ? null
                    : successfulChildren.get(successfulChildren.size() - 1).executionId();
            if (lastExecIdForPublish != null) {
                triggerMessagePublishSafely(task, lastExecIdForPublish, window);
            }
        }

        return overallExit;
    }

    private void validateMultiTableIncrementalSupported(SyncTask task, List<String> tableList) {
        if (tableList == null || tableList.size() <= 1) {
            return;
        }
        if (!"INCREMENTAL".equalsIgnoreCase(task.getDataScope())) {
            return;
        }
        if (Boolean.TRUE.equals(task.getInitialFullSync())
                && !Boolean.TRUE.equals(task.getInitialFullSyncDone())) {
            throw new IllegalStateException("multi-table FULL_THEN_INCREMENT sync is not supported");
        }
        if ("ID_RANGE".equalsIgnoreCase(task.getIncrementMode())) {
            throw new IllegalStateException("multi-table ID_RANGE incremental sync is not supported");
        }
        throw new IllegalStateException("multi-table TIME_FIELD incremental sync is not supported");
    }

    private record SuccessfulChildExecution(SyncTask task, Long executionId) {}

    private boolean shouldCommitWatermark(WatermarkService.WindowContext window) {
        return window != null && ("INCREMENT".equals(window.windowType()) || window.isInitialFullSync());
    }

    private void validateAppendInitialFullSyncCompatible(SyncTask task, WatermarkService.WindowContext window) {
        if (!isAppendWriteMode(task) || window == null || !window.isInitialFullSync()) {
            return;
        }
        throw new IllegalStateException("APPEND syncMode 不能与 initialFullSync/FULL_THEN_INCREMENT 同时用于水位提交："
                + "首次全量未按准水位加上界会导致下一轮增量重复追加，请改用 UPSERT");
    }

    private void validateAppendBlockingGateCompatible(SyncTask task, WatermarkService.WindowContext window) {
        if (!shouldCommitWatermark(window) || !isAppendWriteMode(task)) {
            return;
        }
        if (validationGateService.isFailBlockEnabled(task)) {
            throw new IllegalStateException("APPEND syncMode 不能与 validation blockOnFail/failBlock 同时用于水位提交："
                    + "SeaTunnel 写入成功后再阻断水位会导致下一轮重复追加，请改用 UPSERT 或关闭 blockOnFail");
        }
    }

    /**
     * 触发消息发布。
     * <p>
     * 派发原则：
     * <ul>
     *   <li>窗口类型 INCREMENT 且窗口可用：作为 INCREMENTAL 派发，传递 windowStart/windowEnd</li>
     *   <li>其他情况（FULL / FULL_THEN_INCREMENT / CUSTOM_WINDOW 等）：作为 FULL 派发，窗口传 null</li>
     * </ul>
     * <p>
     * 防御性 try-catch：{@link MessagePublishTrigger#onSyncSuccess} 已是 {@code @Async}
     * 且方法内部捕获所有异常，但调用层仍兜底，防止 Spring 代理失效（如自调用）时异常逃逸到主流程。
     */
    private void triggerMessagePublishSafely(SyncTask task, Long executionId,
                                             WatermarkService.WindowContext window) {
        try {
            String dataScope;
            Instant publishWindowStart;
            Instant publishWindowEnd;
            if (isIncrementalPublishWindow(window)) {
                dataScope = "INCREMENTAL";
                publishWindowStart = window.windowStart();
                publishWindowEnd = window.windowEnd();
            } else {
                // FULL / FULL_THEN_INCREMENT（首次全量阶段）/ CUSTOM_WINDOW / 其他
                // 都按全量发布派发；MessagePublishTrigger 内部按 fullSyncMode 决定是否真正发送
                dataScope = "FULL";
                publishWindowStart = null;
                publishWindowEnd = null;
            }
            messagePublishTrigger.preparePublishRun(
                    task.getId(), executionId, dataScope, publishWindowStart, publishWindowEnd);
            messagePublishTrigger.onSyncSuccess(
                    task.getId(), executionId, dataScope, publishWindowStart, publishWindowEnd);
        } catch (Exception e) {
            log.warn("DfetlExecutorService: messagePublishTrigger.onSyncSuccess threw "
                            + "for task={} exec={}, ignored to protect sync flow: {}",
                    task.getId(), executionId, e.getMessage());
        }
    }

    private boolean isIncrementalPublishWindow(WatermarkService.WindowContext window) {
        if (window == null || window.windowType() == null) {
            return false;
        }
        if ("INCREMENT".equalsIgnoreCase(window.windowType())) {
            return window.windowStart() != null
                    || window.windowEnd() != null
                    || window.windowStartId() != null
                    || window.windowEndId() != null;
        }
        return "CUSTOM_WINDOW".equalsIgnoreCase(window.windowType())
                && (window.windowStart() != null || window.windowEnd() != null);
    }

    private MedicalDirtyExecutionResult prepareMedicalDirtyDiversion(
            SyncTask task,
            WatermarkService.WindowContext window,
            TaskExecution execution) {
        MedicalDirtyExecutionService service = medicalDirtyExecutionService == null
                ? null
                : medicalDirtyExecutionService.getIfAvailable();
        if (service == null) {
            return MedicalDirtyExecutionResult.empty();
        }
        MedicalDirtyExecutionResult result = service.prepare(task, window, execution);
        return result == null ? MedicalDirtyExecutionResult.empty() : result;
    }

    private MedicalDirtyExecutionResult prepareMedicalDirtyDiversionIfRequired(
            SyncTask task,
            WatermarkService.WindowContext window,
            TaskExecution execution) {
        if (isStandardDatasetRouteTask(task)) {
            MedicalDirtyExecutionService service = medicalDirtyExecutionService == null
                    ? null
                    : medicalDirtyExecutionService.getIfAvailable();
            if (service == null) {
                throw new IllegalStateException("标准数据集写安全服务未启用");
            }
            MedicalDirtyExecutionResult result = service.prepareWriteSafety(task, window, execution);
            return result == null ? MedicalDirtyExecutionResult.empty() : result;
        }
        return prepareMedicalDirtyDiversion(task, window, execution);
    }

    private String dirtyAwareFinalStatus(
            int finalExitCode,
            ExecutionResult result,
            MedicalDirtyExecutionResult dirtyDiversion) {
        if (finalExitCode == 0 && dirtyDiversion != null && dirtyDiversion.hasExcludedRows()) {
            return ExecutionResult.STATUS_SUCCESS_WITH_DIRTY_ROWS;
        }
        return finalStatusFor(finalExitCode, result);
    }

    private void applyMedicalDiversionSummary(
            TaskExecution exec,
            ExecutionResult result,
            MedicalDirtyExecutionResult dirtyDiversion) {
        if (exec == null || dirtyDiversion == null || !dirtyDiversion.applied()) {
            return;
        }
        long validRows = result == null ? 0L : Math.max(0L, result.readRows());
        long excludedRows = Math.max(0L, dirtyDiversion.excludedRows());
        long warningRows = Math.max(0L, dirtyDiversion.warningRows());
        exec.setValidSourceRows(validRows);
        exec.setExcludedRows(excludedRows);
        exec.setWarningRows(warningRows);
        exec.setSourceRowsTotal(validRows + excludedRows);
    }

    private String finalStatusFor(int finalExitCode, ExecutionResult result) {
        if (finalExitCode != 0 && result != null && result.requiresReconcile()) {
            return ExecutionResult.STATUS_RECONCILE_REQUIRED;
        }
        return finalExitCode == 0 ? "SUCCESS" : "FAILED";
    }

    private static String withMedicalValidSourceQuery(String dataCharacteristics, String validSourceQuery) {
        try {
            Map<String, Object> values = dataCharacteristics == null || dataCharacteristics.isBlank()
                    ? new LinkedHashMap<>()
                    : MEDICAL_DIRTY_OBJECT_MAPPER.readValue(
                            dataCharacteristics,
                            new TypeReference<LinkedHashMap<String, Object>>() {});
            values.put("medicalValidSourceQuery", validSourceQuery);
            return MEDICAL_DIRTY_OBJECT_MAPPER.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("医共体问题行分流 SQL 写入 dataCharacteristics 失败: " + e.getMessage(), e);
        }
    }

    private static boolean isStandardDatasetRouteTask(SyncTask task) {
        if (task == null || task.getDataCharacteristics() == null
                || task.getDataCharacteristics().isBlank()) {
            return false;
        }
        try {
            Map<String, Object> values = MEDICAL_DIRTY_OBJECT_MAPPER.readValue(
                    task.getDataCharacteristics(),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            Object fillSource = values.get("fillSource");
            Object datasetId = values.get("standardDatasetId");
            Object routeId = values.get("institutionDatasetRouteId");
            return fillSource != null
                    && "STANDARD_DATASET_ROUTE".equalsIgnoreCase(fillSource.toString())
                    && datasetId != null
                    && !datasetId.toString().isBlank()
                    && routeId != null
                    && !routeId.toString().isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    private ExecutionResult normalizeFailedRowsAsFailure(ExecutionResult result) {
        if (result == null) {
            return new ExecutionResult(-1, 0, 0, 0, "ExecutionResult is null");
        }
        if (result.exitCode() == 0 && result.failedRows() > 0) {
            String msg = result.errorMsg();
            String dirtyMsg = "SeaTunnel job returned failedRows=" + result.failedRows()
                    + "，存在写入失败或被过滤数据，不能按成功推进水位";
            if (msg == null || msg.isBlank()) {
                msg = dirtyMsg;
            } else if (!msg.contains("failedRows=")) {
                msg = msg + "；" + dirtyMsg;
            }
            return new ExecutionResult(
                    -1,
                    result.readRows(),
                    result.writeRows(),
                    result.failedRows(),
                    msg,
                    result.executionStatus(),
                    result.engineReadRows(),
                    result.engineWriteRows());
        }
        return result;
    }

    private boolean isSchedulerTrigger(String triggeredBy) {
        return "SCHEDULER".equalsIgnoreCase(triggeredBy);
    }

    private boolean isSchedulerRunnable(SyncTask task) {
        if (task == null || !"ENABLED".equals(task.getStatus())) {
            return false;
        }
        String cron = task.getCronExpression();
        if (cron == null || cron.isBlank()) {
            cron = task.getSchedule();
        }
        return cron != null && !cron.isBlank();
    }

    private Optional<TaskExecution> findUnclosedExecution(Long taskId) {
        for (String status : List.of("RUNNING", "INTERRUPTED", ExecutionResult.STATUS_RECONCILE_REQUIRED)) {
            List<TaskExecution> executions = executionRepository.findByTaskIdAndStatus(taskId, status);
            if (executions == null || executions.isEmpty()) {
                continue;
            }
            for (TaskExecution exec : executions) {
                if (shouldBlockNewSubmission(exec)) {
                    return Optional.of(exec);
                }
            }
        }
        return Optional.empty();
    }

    private boolean shouldBlockNewSubmission(TaskExecution exec) {
        if (exec == null) {
            return false;
        }
        String status = exec.getStatus();
        if ("RUNNING".equals(status)) {
            return true;
        }
        if (ExecutionResult.STATUS_RECONCILE_REQUIRED.equals(status)) {
            return !Boolean.TRUE.equals(exec.getReconcileHandled());
        }
        if (!"INTERRUPTED".equals(status)) {
            return false;
        }
        String jobId = exec.getEngineJobId();
        if (jobId == null || jobId.isBlank()) {
            return false;
        }
        SeaTunnelRestClient client = seaTunnelRestClient == null ? null : seaTunnelRestClient.getIfAvailable();
        if (client == null) {
            return true;
        }
        try {
            Optional<SeaTunnelRestClient.JobInfo> info = client.getJobInfo(jobId);
            if (info.isEmpty()) {
                return true;
            }
            String mapped = info.get().mappedStatus();
            return "RUNNING".equals(mapped) || "SCHEDULED".equals(mapped) || mapped == null;
        } catch (Exception e) {
            log.warn("DfetlExecutorService: cannot probe previous SeaTunnel job exec={} jobId={}, block new submission: {}",
                    exec.getId(), jobId, e.getMessage());
            return true;
        }
    }

    private int failBeforeEngineExecution(SyncTask task, String triggeredBy, String errorMsg) {
        Long taskId = task.getId();
        Instant now = Instant.now();
        String batchNo = LocalDateTime.now().format(BATCH_FMT) + "_" + taskId;
        TaskExecution exec = new TaskExecution();
        exec.setTaskId(taskId);
        exec.setBatchNo(batchNo);
        exec.setTriggeredBy(triggeredBy);
        exec.setStatus("FAILED");
        exec.setSnapshotSyncType(task.getSyncType());
        exec.setSnapshotSyncMode(task.getSyncMode());
        exec.setSnapshotViewNames(task.getViewNames() != null
                ? String.join(",", task.getViewNames()) : null);
        exec.setExecutorType(task.getExecutorType() != null ? task.getExecutorType() : "SEATUNNEL_CLUSTER");
        exec.setStartedAt(now);
        exec.setFinishedAt(now);
        exec.setDurationMs(0L);
        exec.setReadRows(0L);
        exec.setWriteRows(0L);
        exec.setFailedRows(0L);
        exec.setErrorMsg(ExecutionErrorSanitizer.sanitize(errorMsg));
        executionRepository.save(exec);

        task.setLastRunTime(LocalDateTime.ofInstant(now, java.time.ZoneId.systemDefault()));
        task.setLastRunStatus("FAILED");
        taskRepository.save(task);
        alertEvaluatorService.evaluate(task, exec);
        return -1;
    }

    private boolean checkValidationGate(
            SyncTask task,
            Long executionId,
            WatermarkService.WindowContext window) {
        try {
            return validationGateService.checkAndBlock(task, executionId, window);
        } catch (Exception e) {
            log.error("DfetlExecutorService: validation gate exception for task={} exec={}, fail-closed: {}",
                    task.getId(), executionId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 创建 SyncTask 的内存浅拷贝，仅覆盖 viewNames 为单表。
     * 该副本不会被 JPA 持久化，仅用于传递给执行引擎读取配置。
     */
    private SyncTask shallowCopy(SyncTask src, String singleTable) {
        SyncTask copy = new SyncTask();
        copy.setId(src.getId());
        copy.setName(src.getName());
        copy.setSourceDataSourceId(src.getSourceDataSourceId());
        copy.setTargetDataSourceId(src.getTargetDataSourceId());
        copy.setSourceSchema(src.getSourceSchema());
        copy.setViewNames(List.of(singleTable));
        copy.setSyncType(src.getSyncType());
        copy.setSyncMode(src.getSyncMode());
        copy.setDataScope(src.getDataScope());
        copy.setIncrementalField(src.getIncrementalField());
        copy.setUpsertKeys(src.getUpsertKeys());
        copy.setBatchSize(src.getBatchSize());
        copy.setParallelism(src.getParallelism());
        copy.setShardCount(src.getShardCount());
        copy.setShardStrategy(src.getShardStrategy());
        copy.setRateLimit(src.getRateLimit());
        copy.setStaticFilter(src.getStaticFilter());
        copy.setFilterConditionMap(src.getFilterConditionMap());
        copy.setTargetTableMap(src.getTargetTableMap());
        copy.setCustomWindowStart(src.getCustomWindowStart());
        copy.setCustomWindowEnd(src.getCustomWindowEnd());
        copy.setExecutorType(src.getExecutorType());
        copy.setSplitPk(src.getSplitPk());
        copy.setSourceObjectType(src.getSourceObjectType());
        copy.setIncrementMode(src.getIncrementMode());
        copy.setUpperBoundStrategy(src.getUpperBoundStrategy());
        copy.setUpperBoundDelayMinutes(src.getUpperBoundDelayMinutes());
        copy.setInitialWatermark(src.getInitialWatermark());
        copy.setInitialFullSync(src.getInitialFullSync());
        copy.setInitialFullSyncDone(src.getInitialFullSyncDone());
        copy.setSoftDeleteField(src.getSoftDeleteField());
        copy.setSoftDeleteActiveValue(src.getSoftDeleteActiveValue());
        copy.setEnableDorisMerge(src.getEnableDorisMerge());
        copy.setDeleteSignValue(src.getDeleteSignValue());
        copy.setSequenceCol(src.getSequenceCol());
        copy.setPartialColumns(src.getPartialColumns());
        copy.setWriterType(src.getWriterType());
        copy.setDorisTableModel(src.getDorisTableModel());
        copy.setSourceMode(src.getSourceMode());
        copy.setCustomSql(src.getCustomSql());
        copy.setCustomSqlName(src.getCustomSqlName());
        copy.setLookbackSeconds(src.getLookbackSeconds());
        copy.setVersion(src.getVersion());
        copy.setDataCharacteristics(src.getDataCharacteristics());
        return copy;
    }

    // ── 自动重试辅助方法 ──

    /**
     * 判断是否应该自动重试。
     * 条件：任务配置了重试 AND 当前重试次数未达上限 AND 错误为瞬态 AND 写入语义可安全重试。
     * <p>APPEND（纯追加）写入非幂等：若上次失败已部分写入 Doris，重试会重复插入，因此禁止自动重试。
     * TRUNCATE/UPSERT 幂等，可安全重试。
     */
    private boolean shouldAutoRetry(SyncTask task, ExecutionResult result, int currentRetryCount) {
        if (cancellationRegistry.isRequested(task.getId())) {
            return false;
        }
        // 远端终态未知时禁止再次提交作业。结构化状态必须先于 timeout/connection 等
        // 错误文本判断，否则同一远端作业仍在运行时可能启动第二个作业。
        if (result != null && result.requiresReconcile()) {
            log.warn("[Retry:TaskExecution:{}] engine status requires reconciliation, auto-retry skipped",
                    task.getId());
            return false;
        }
        int maxRetries = resolveMaxRetries(task);
        if (maxRetries <= 0) return false;
        if (currentRetryCount >= maxRetries) return false;
        if (isAppendWriteMode(task)) {
            log.warn("[Retry:TaskExecution:{}] APPEND write mode is non-idempotent, auto-retry skipped "
                    + "to avoid duplicate inserts", task.getId());
            return false;
        }
        return result != null && isTransientError(result.errorMsg());
    }

    private Optional<TaskExecution> cancellationTerminal(Long executionId) {
        if (executionId == null) {
            return Optional.empty();
        }
        return executionRepository.findById(executionId)
                .filter(execution -> "CANCELLED".equals(execution.getStatus())
                        || ExecutionResult.STATUS_RECONCILE_REQUIRED.equals(execution.getStatus()));
    }

    private int finishCancelledFlow(SyncTask task, TaskExecution execution, String status) {
        String terminal = ExecutionResult.STATUS_RECONCILE_REQUIRED.equals(status)
                ? ExecutionResult.STATUS_RECONCILE_REQUIRED
                : "CANCELLED";
        task.setLastRunTime(LocalDateTime.ofInstant(Instant.now(), java.time.ZoneId.systemDefault()));
        task.setLastRunStatus(terminal);
        taskRepository.save(task);
        if (execution != null) {
            alertEvaluatorService.evaluate(task, execution);
        }
        log.info("DfetlExecutorService: task={} stopped by cancellation terminal={}, watermark and success actions skipped",
                task.getId(), terminal);
        return -1;
    }

    /**
     * 判断任务是否为 APPEND（纯追加）写入语义。
     * <p>APPEND 重试存在重复插入风险，需禁止自动重试。
     */
    private boolean isAppendWriteMode(SyncTask task) {
        String syncMode = task.getSyncMode();
        return syncMode != null && "APPEND".equalsIgnoreCase(syncMode.trim());
    }

    private int resolveMaxRetries(SyncTask task) {
        if (task.getRetryMaxAttempts() != null) return task.getRetryMaxAttempts();
        return retryConfigProperties.getTaskExecution().getDefaultMaxAttempts();
    }

    private int resolveRetryIntervalSeconds(SyncTask task) {
        if (task.getRetryIntervalSeconds() != null) return task.getRetryIntervalSeconds();
        return retryConfigProperties.getTaskExecution().getDefaultIntervalSeconds();
    }

    /**
     * 判断错误是否为瞬态（可重试）。
     * <p>
     * 瞬态：连接拒绝/重置/超时/网络异常/断路器/服务不可用等。
     * 非瞬态：SQL 语法、表不存在、配置错误、validation gate、RECONCILE_REQUIRED 等确定性错误。
     * <p>
     * 注意：超时类错误（timeout）即使错误信息中含 "sql" 也应视为瞬态（如 "SQL execution timeout"），
     * 因此 timeout 关键词优先于非瞬态规则判定。
     */
    private boolean isTransientError(String errorMsg) {
        if (errorMsg == null || errorMsg.isBlank()) return false;
        String lower = errorMsg.toLowerCase();

        // 瞬态优先级最高的关键词：超时/连接类错误，即使同时含 "sql" 也应重试
        boolean transientStrong = lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("connection refused")
                || lower.contains("connection reset")
                || lower.contains("failed to connect")
                || lower.contains("connectexception")
                || lower.contains("network is unreachable")
                || lower.contains("网络异常")
                || lower.contains("circuit breaker")
                || lower.contains("unavailable");
        if (transientStrong) {
            return true;
        }

        // 非瞬态（确定性错误）：不重试
        if (lower.contains("sql") || lower.contains("syntax") || lower.contains("not exist")
                || lower.contains("配置错误") || lower.contains("validation gate")
                || lower.contains("reconcile_required")) {
            return false;
        }

        // 其余无法明确归类的错误，默认不重试，避免对确定性错误做无意义重试
        return false;
    }

    private String normalizeErrorMessage(Exception e) {
        String msg = e.getMessage();
        Throwable cause = e.getCause();
        while ((msg == null || msg.isBlank()) && cause != null) {
            msg = cause.getMessage();
            cause = cause.getCause();
        }
        String normalized = msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
        return ExecutionErrorSanitizer.sanitize(normalized);
    }

    private void saveDirtyRecordSummary(Long taskId, Long executionId, ExecutionResult result) {
        DirtyRecord dirty = new DirtyRecord();
        dirty.setTaskId(taskId);
        dirty.setExecutionId(executionId);
        dirty.setErrorType("WRITE_FAIL");
        dirty.setErrorMsg(ExecutionErrorSanitizer.sanitize(result.errorMsg()));
        dirty.setRawData("{\"exitCode\":" + result.exitCode()
                + ",\"readRows\":" + result.readRows()
                + ",\"writeRows\":" + result.writeRows()
                + ",\"failedRows\":" + result.failedRows()
                + ",\"engineReadRows\":" + result.engineReadRows()
                + ",\"engineWriteRows\":" + result.engineWriteRows() + "}");
        dirtyRecordRepository.save(dirty);
    }

    /**
     * 流式推送引擎日志（SSE）。
     */
    public void streamLogs(Long taskId, SseEmitter emitter) {
        SyncTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + taskId));
        ExecutorStrategy strategy = executorRouter.route(task);
        strategy.streamLogs(task, emitter);
    }
}
