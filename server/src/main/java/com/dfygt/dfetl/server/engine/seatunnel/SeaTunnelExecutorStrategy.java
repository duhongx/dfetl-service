package com.dfygt.dfetl.server.engine.seatunnel;

import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.engine.ExecutionResult;
import com.dfygt.dfetl.server.engine.ExecutorStrategy;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import com.dfygt.dfetl.server.service.WatermarkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SeaTunnel Zeta Cluster 执行策略（spec 015b + 015c 监控）。
 *
 * <p>当前 server 仅保留 cluster REST 提交路径：构造 JSON 作业配置，提交到 Zeta REST，
 * 获取 jobId 后由 {@link SeaTunnelLifecycleProbe} 异步轮询回写状态和指标。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "dfetl.executor.seatunnel", name = "enabled", havingValue = "true")
public class SeaTunnelExecutorStrategy implements ExecutorStrategy {

    private static final int STOP_CONFIRM_ATTEMPTS = 10;
    private static final long STOP_CONFIRM_INTERVAL_MS = 3000L;

    private final SeaTunnelConfBuilder confBuilder;
    private final SeaTunnelRestClient restClient;
    private final SeaTunnelLifecycleProbe lifecycleProbe;
    private final TaskExecutionRepository executionRepo;
    private final SeaTunnelProperties props;
    /**
     * spec validation-workbench-redesign · Task P1-9.1：L1 ROW_COUNT 哨兵记录。
     * 同步成功后把 srcCount/tgtCount 写入 validation_run，trigger_type=AUTO_COUNT，
     * 工作台执行历史 Tab 即可展示「行数哨兵」记录。
     */
    private final com.dfygt.dfetl.server.service.ValidationRunService validationRunService;

    private final Executor asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public String type() {
        return "SEATUNNEL_CLUSTER";
    }

    @Override
    public CompletableFuture<ExecutionResult> execute(
            SyncTask task,
            WatermarkService.WindowContext window,
            TaskExecution exec
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("SeaTunnelExecutorStrategy: starting task={} exec={}", task.getId(), exec.getId());

                SeaTunnelConfBuilder.SourceCountResult sourceCount =
                        confBuilder.countSourceRowsWithDiagnostic(task, window);
                long srcCount = sourceCount.rows();
                String srcCountError = sourceCount.errorMessage();
                if (srcCount >= 0) {
                    log.info("SeaTunnelExecutorStrategy: [源端数据量] task={} table={} count={}",
                            task.getId(),
                            task.getViewNames() != null ? String.join(",", task.getViewNames()) : "?",
                            srcCount);
                }

                Map<String, Object> jobMap = confBuilder.buildJobMap(task, window, exec.getId());
                // 保存 job 配置到文件并打印到日志
                try {
                    // 凭据脱敏：日志和落盘文件都不应该包含明文密码（日志平台 + 磁盘文件会沉淀凭据）。
                    // 提交到 SeaTunnel REST 仍使用原始 jobMap（在下方 submitJob 调用）。
                    Map<String, Object> redactedJobMap = JobMapRedactor.redact(jobMap);
                    String jobJson = new com.fasterxml.jackson.databind.ObjectMapper()
                            .writerWithDefaultPrettyPrinter().writeValueAsString(redactedJobMap);
                    log.info("SeaTunnelExecutorStrategy: job config task={} exec={}\n{}", task.getId(), exec.getId(), jobJson);
                    // 保存到 /opt/dfetl-server/jobs/ 目录
                    java.nio.file.Path jobDir = java.nio.file.Path.of("jobs");
                    java.nio.file.Files.createDirectories(jobDir);
                    java.nio.file.Path jobFile = jobDir.resolve("task_" + task.getId() + "_exec_" + exec.getId() + ".json");
                    java.nio.file.Files.writeString(jobFile, jobJson);
                } catch (Exception e) {
                    log.warn("SeaTunnelExecutorStrategy: failed to save job config: {}", e.getMessage());
                }
                SeaTunnelRestClient.SubmitResult submitResult = restClient.submitJob(jobMap);
                if (submitResult.success()) {
                    String jobId = submitResult.jobId();
                    exec.setEngineJobId(jobId);
                    executionRepo.save(exec);
                    log.info("SeaTunnelExecutorStrategy: REST-submitted jobId={} exec={}", jobId, exec.getId());
                    CompletableFuture<Optional<SeaTunnelRestClient.JobInfo>> probeFuture =
                            lifecycleProbe.trackAsync(exec.getId(), jobId);
                    Optional<SeaTunnelRestClient.JobInfo> finalInfo;
                    try {
                        finalInfo = probeFuture.get(probeWaitTimeoutMinutes(), TimeUnit.MINUTES);
                    } catch (TimeoutException probeTimeout) {
                        log.warn("SeaTunnelExecutorStrategy: probe timeout exec={} jobId={}",
                                exec.getId(), jobId);
                        StopClosure stopClosure = requestForceStopAndConfirm(jobId, "probe timeout");
                        String msg = "SeaTunnel job probe timeout: jobId=" + jobId
                                + "; " + stopClosure.message();
                        return failedResult(0, 0, 0, msg, stopClosure.requiresReconcile());
                    } catch (InterruptedException probeInterrupted) {
                        Thread.currentThread().interrupt();
                        String msg = "SeaTunnel job probe interrupted: jobId=" + jobId;
                        log.warn("SeaTunnelExecutorStrategy: probe interrupted exec={} jobId={}",
                                exec.getId(), jobId);
                        return new ExecutionResult(-1, 0, 0, 0, msg);
                    } catch (ExecutionException probeFailed) {
                        String detail = probeFailed.getCause() != null
                                ? probeFailed.getCause().getMessage()
                                : probeFailed.getMessage();
                        String msg = ExecutionErrorSanitizer.sanitize("SeaTunnel job probe failed: " + detail);
                        log.warn("SeaTunnelExecutorStrategy: probe failed exec={} jobId={} err={}",
                                exec.getId(), jobId, detail);
                        StopClosure stopClosure = requestForceStopAndConfirm(jobId, "probe failure");
                        msg = msg + "; " + stopClosure.message();
                        return failedResult(0, 0, 0, msg, stopClosure.requiresReconcile());
                    }

                    long readRows  = finalInfo.map(SeaTunnelRestClient.JobInfo::readRows).orElse(0L);
                    long writeRows = finalInfo.map(SeaTunnelRestClient.JobInfo::writeRows).orElse(0L);
                    String errMsg  = finalInfo.map(SeaTunnelRestClient.JobInfo::errorMsg)
                            .map(lifecycleProbe::formatErrorMessage)
                            .orElse(null);
                    String mapped  = finalInfo.map(SeaTunnelRestClient.JobInfo::mappedStatus).orElse(null);

                    long tgtCount = confBuilder.countTargetRows(task);
                    String autoCountScope = resolveAutoCountScope(window);
                    boolean targetCountScoped = "WINDOW".equals(autoCountScope);
                    long tgtWindowCount = targetCountScoped
                            ? confBuilder.countTargetRows(task, window)
                            : tgtCount;
                    // 增量窗口统计失败时不能退回目标全表，否则会把源窗口行数与目标历史总量混算。
                    long comparableTargetCount = targetCountScoped ? tgtWindowCount : tgtCount;
                    String targetCountError = targetCountScoped && tgtWindowCount < 0
                            ? "TARGET_WINDOW_COUNT_FAILED: could not resolve target rows for incremental window"
                            : null;
                    log.info("SeaTunnelExecutorStrategy: [执行结果] task={} exec={} status={} " +
                            "源端={} SeaTunnel读取={} SeaTunnel写入={} 目标端实际={}",
                            task.getId(), exec.getId(), mapped, srcCount, readRows, writeRows, tgtCount);
                    if (comparableTargetCount >= 0 && writeRows > 0 && comparableTargetCount != writeRows) {
                        log.warn("SeaTunnelExecutorStrategy: [数据不一致] task={} SeaTunnel写入={} 但目标端同口径实际={}，" +
                                "可能原因：TRUNCATE未生效/2PC回滚/Doris去重",
                                task.getId(), writeRows, comparableTargetCount);
                    }

                    // spec validation-workbench-redesign · Task P1-9.1 / Requirement 5 (AC 1, 5)
                    // L1 ROW_COUNT 哨兵：同步完成（不论成功/失败）后写一条 trigger_type=AUTO_COUNT 的
                    // validation_run 记录，diffRows = max(0, srcCount - tgtCount)。
                    // 失败时也写，便于运维在执行历史看到「行数哨兵」记录、再决定是否一键 CHECKSUM。
                    // 任何异常都不得阻断同步主流程（Requirement 5 AC 5）。
                    try {
                        if ("UPSERT".equalsIgnoreCase(task.getSyncMode())) {
                            log.info("SeaTunnelExecutorStrategy: skip AUTO_COUNT sentinel for UPSERT "
                                            + "task={} exec={}; key-aware ROW_COUNT validation owns this comparison",
                                    task.getId(), exec.getId());
                        } else if (exec.getExcludedRows() != null && exec.getExcludedRows() > 0) {
                            log.info("SeaTunnelExecutorStrategy: skip AUTO_COUNT sentinel for task={} exec={} "
                                            + "because medical excludedRows={}",
                                    task.getId(), exec.getId(), exec.getExcludedRows());
                        } else {
                            Long srcRows = srcCount >= 0 ? srcCount : null;
                            Long tgtRows = comparableTargetCount >= 0 ? comparableTargetCount : null;
                            long diffRows = (srcRows != null && tgtRows != null)
                                    ? Math.max(0L, Math.abs(srcRows - tgtRows))
                                    : 0L;
                            // legacyExecId 用 -execId 防止与真实 task_execution 冲突
                            long sentinelExecId = -exec.getId() * 1000L - 1L;
                            var run = validationRunService.getOrCreate(
                                    task.getId(), sentinelExecId, "ROW_COUNT", autoCountScope,
                                    window != null ? window.windowStart() : null,
                                    window != null ? window.windowEnd() : null,
                                    "AUTO_COUNT");
                            run.setMethod("ROW_COUNT");
                            run.setExecutionId(exec.getId());
                            run.setWindowType(window != null ? window.windowType() : null);
                            run.setSourceRows(srcRows);
                            run.setTargetRows(tgtRows);
                            run.setDiffRows(diffRows);
                            run.setLastRunAt(java.time.Instant.now());
                            if (srcRows != null && tgtRows != null) {
                                run.setStatus(diffRows == 0L ? "CONSISTENT" : "DIFF");
                                run.setErrorMsg(null);
                            } else {
                                run.setStatus("ERROR");
                                String detail = srcCountError != null ? srcCountError
                                        : targetCountError != null ? targetCountError
                                        : "AUTO_COUNT sentinel could not resolve source or target row count";
                                run.setErrorMsg(detail);
                            }
                            validationRunService.save(run);
                            log.info("SeaTunnelExecutorStrategy: [行数哨兵] task={} runId={} src={} tgt={} diff={}",
                                    task.getId(), run.getId(), srcRows, tgtRows, diffRows);
                        }
                    } catch (Exception sentinelErr) {
                        log.warn("SeaTunnelExecutorStrategy: [行数哨兵 写入失败] task={} err={} ；不阻断主流程",
                                task.getId(), sentinelErr.getMessage());
                    }

                    SeaTunnelJobOutcome outcome = evaluateJobOutcome(jobId, finalInfo, errMsg);
                    String outcomeError = outcome.errorMsg();
                    boolean requiresReconcile = false;
                    if (outcome.shouldStopJob()) {
                        StopClosure stopClosure = requestForceStopAndConfirm(jobId, "non-terminal status");
                        outcomeError = outcomeError + "; " + stopClosure.message();
                        requiresReconcile = stopClosure.requiresReconcile();
                    }
                    long businessReadRows = srcCount >= 0 ? srcCount : readRows;
                    long businessWriteRows = comparableTargetCount >= 0
                            ? comparableTargetCount
                            : writeRows;
                    if (outcome.exitCode() == 0) {
                        return ExecutionResult.withEngineMetrics(
                                0,
                                businessReadRows,
                                businessWriteRows,
                                0L,
                                outcomeError,
                                readRows,
                                writeRows);
                    }
                    // SeaTunnel 指标会把 connector 内部重试的每次尝试累加，不能作为业务行数。
                    // 失败时优先使用同一执行范围的源/目标计数；原始引擎指标单独保留。
                    long actualFailed;
                    if (comparableTargetCount >= 0) {
                        actualFailed = Math.max(0L, businessReadRows - businessWriteRows);
                    } else if (writeRows > 0 && writeRows == readRows) {
                        businessWriteRows = 0L;
                        actualFailed = businessReadRows;
                    } else {
                        actualFailed = Math.min(businessReadRows, Math.max(0L, readRows - writeRows));
                    }
                    return failedResult(
                            businessReadRows,
                            businessWriteRows,
                            actualFailed,
                            outcomeError,
                            outcome.exitCode() != 0 && requiresReconcile,
                            readRows,
                            writeRows);
                }
                log.warn("SeaTunnelExecutorStrategy: REST submit failed exec={} err={}",
                        exec.getId(), submitResult.errorMsg());
                return new ExecutionResult(-1, 0, 0, 0, submitFailureMessage(submitResult));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("SeaTunnelExecutorStrategy: failed task={} exec={} errType={} err={}",
                        task.getId(), exec.getId(), e.getClass().getSimpleName(),
                        ExecutionErrorSanitizer.sanitize(e.getMessage()));
                return new ExecutionResult(-1, 0, 0, 0,
                        ExecutionErrorSanitizer.sanitize(e.getMessage()));
            }
        }, asyncExecutor);
    }

    private String resolveAutoCountScope(WatermarkService.WindowContext window) {
        if (window == null || window.windowType() == null) {
            return "FULL";
        }
        return switch (window.windowType().trim().toUpperCase(java.util.Locale.ROOT)) {
            case "FULL", "FULL_THEN_INCREMENT" -> "FULL";
            case "INCREMENT", "CUSTOM_WINDOW" -> "WINDOW";
            default -> window.hasScopedWindow() ? "WINDOW" : "FULL";
        };
    }

    private long probeWaitTimeoutMinutes() {
        SeaTunnelProperties.Probe probe = props == null ? null : props.probe();
        int configured = probe == null ? 720 : probe.maxPollMinutes();
        return Math.max(1L, configured + 1L);
    }

    private SeaTunnelJobOutcome evaluateJobOutcome(
            String jobId,
            Optional<SeaTunnelRestClient.JobInfo> finalInfo,
            String formattedError) {
        if (finalInfo.isEmpty()) {
            return SeaTunnelJobOutcome.failAndStop("SeaTunnel job status unknown: no job info for jobId=" + jobId);
        }
        SeaTunnelRestClient.JobInfo info = finalInfo.get();
        String mapped = normalize(info.mappedStatus());
        String raw = normalize(info.jobStatus());
        String displayStatus = mapped != null ? mapped : raw;

        if ("SUCCESS".equals(mapped)) {
            return SeaTunnelJobOutcome.success();
        }
        if (displayStatus == null) {
            return SeaTunnelJobOutcome.failAndStop("SeaTunnel job status unknown: jobId=" + jobId);
        }
        if ("FAILED".equals(mapped) || "CANCELLED".equals(mapped)) {
            String detail = formattedError != null && !formattedError.isBlank()
                    ? ": " + formattedError
                    : "";
            return SeaTunnelJobOutcome.fail(
                    "SeaTunnel job failed or cancelled: status=" + displayStatus + detail);
        }
        if ("RUNNING".equals(mapped)) {
            return SeaTunnelJobOutcome.failAndStop("SeaTunnel job status is still RUNNING: jobId=" + jobId);
        }
        if ("SCHEDULED".equals(mapped)
                || "SUBMITTED".equals(mapped)
                || "PENDING".equals(mapped)
                || "STARTING".equals(mapped)
                || "CREATED".equals(mapped)) {
            return SeaTunnelJobOutcome.failAndStop(
                    "SeaTunnel job did not reach success terminal state: status=" + displayStatus);
        }
        return SeaTunnelJobOutcome.failAndStop(
                "SeaTunnel job did not reach success terminal state: status=" + displayStatus);
    }

    private ExecutionResult failedResult(
            long readRows,
            long writeRows,
            long failedRows,
            String errorMsg,
            boolean requiresReconcile) {
        return failedResult(
                readRows, writeRows, failedRows, errorMsg, requiresReconcile, readRows, writeRows);
    }

    private ExecutionResult failedResult(
            long readRows,
            long writeRows,
            long failedRows,
            String errorMsg,
            boolean requiresReconcile,
            long engineReadRows,
            long engineWriteRows) {
        if (requiresReconcile) {
            return ExecutionResult.reconcileRequired(
                    readRows, writeRows, failedRows, errorMsg, engineReadRows, engineWriteRows);
        }
        return ExecutionResult.withEngineMetrics(
                -1, readRows, writeRows, failedRows, errorMsg, engineReadRows, engineWriteRows);
    }

    private StopClosure requestForceStopAndConfirm(String jobId, String reason) {
        if (jobId == null || jobId.isBlank()) {
            return StopClosure.reconcileRequired(
                    "SeaTunnel force stop skipped: missing jobId reason=" + reason
                            + "; RECONCILE_REQUIRED: cannot confirm SeaTunnel job termination without jobId");
        }
        SeaTunnelRestClient.StopResult stop = restClient.stopJob(jobId, true);
        if (stop == null || !stop.success()) {
            String detail = stop == null ? "stop result unavailable" : stop.errorMsg();
            return StopClosure.reconcileRequired(
                    "SeaTunnel force stop failed: jobId=" + jobId + " reason=" + reason
                            + (detail == null || detail.isBlank() ? "" : " detail=" + detail)
                            + "; RECONCILE_REQUIRED: stop-job request failed; manual SeaTunnel job check required");
        }

        String returnedJobId = stop.jobId();
        StringBuilder message = new StringBuilder("SeaTunnel stop requested: originalJobId=")
                .append(jobId)
                .append(" returnedJobId=")
                .append(returnedJobId)
                .append(" reason=")
                .append(reason);
        if (!jobId.equals(returnedJobId)) {
            message.append("; stop-job returned a different jobId");
        }

        StopConfirmation confirmation = confirmStopped(jobId);
        message.append("; ").append(confirmation.message());
        return new StopClosure(message.toString(), confirmation.requiresReconcile());
    }

    private StopConfirmation confirmStopped(String jobId) {
        String lastStatus = null;
        for (int attempt = 1; attempt <= STOP_CONFIRM_ATTEMPTS; attempt++) {
            Optional<SeaTunnelRestClient.JobInfo> info;
            try {
                info = restClient.getJobInfo(jobId);
            } catch (Exception e) {
                return StopConfirmation.reconcileRequired(
                        "RECONCILE_REQUIRED: stop confirmation query failed for jobId=" + jobId
                                + " err=" + e.getMessage());
            }
            if (info == null) {
                info = Optional.empty();
            }
            if (info.isPresent()) {
                String mapped = normalize(info.get().mappedStatus());
                String raw = normalize(info.get().jobStatus());
                lastStatus = mapped != null ? mapped : raw;
                if ("FAILED".equals(mapped) || "CANCELLED".equals(mapped)) {
                    return StopConfirmation.closed(
                            "SeaTunnel stop confirmed terminal status=" + mapped + " jobId=" + jobId);
                }
                if ("SUCCESS".equals(mapped)) {
                    return StopConfirmation.reconcileRequired(
                            "RECONCILE_REQUIRED: SeaTunnel job reached SUCCESS after stop request; "
                                    + "manual validation and watermark decision required jobId=" + jobId);
                }
            }
            if (attempt < STOP_CONFIRM_ATTEMPTS && !sleepBeforeNextStopConfirmation(jobId)) {
                return StopConfirmation.reconcileRequired(
                        "RECONCILE_REQUIRED: stop confirmation interrupted for jobId=" + jobId);
            }
        }
        String displayStatus = lastStatus == null || lastStatus.isBlank() ? "UNKNOWN" : lastStatus;
        return StopConfirmation.reconcileRequired(
                "RECONCILE_REQUIRED: SeaTunnel stop not confirmed stopped jobId=" + jobId
                        + " lastStatus=" + displayStatus);
    }

    private boolean sleepBeforeNextStopConfirmation(String jobId) {
        try {
            TimeUnit.MILLISECONDS.sleep(STOP_CONFIRM_INTERVAL_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("SeaTunnelExecutorStrategy: stop confirmation interrupted jobId={}", jobId);
            return false;
        }
    }

    private String submitFailureMessage(SeaTunnelRestClient.SubmitResult submitResult) {
        if (submitResult == null) {
            return "SeaTunnel REST submit failed: submit result unavailable";
        }
        String msg = submitResult.errorMsg();
        if (msg != null && !msg.isBlank()) {
            return msg;
        }
        String jobId = submitResult.jobId();
        if (jobId == null || jobId.isBlank()) {
            return "SeaTunnel REST response missing jobId";
        }
        return "SeaTunnel REST submit failed";
    }

    private static String normalize(String status) {
        return status == null || status.isBlank() ? null : status.trim().toUpperCase();
    }

    private record SeaTunnelJobOutcome(int exitCode, String errorMsg, boolean shouldStopJob) {
        static SeaTunnelJobOutcome success() {
            return new SeaTunnelJobOutcome(0, null, false);
        }

        static SeaTunnelJobOutcome fail(String errorMsg) {
            return new SeaTunnelJobOutcome(-1, errorMsg, false);
        }

        static SeaTunnelJobOutcome failAndStop(String errorMsg) {
            return new SeaTunnelJobOutcome(-1, errorMsg, true);
        }
    }

    private record StopClosure(String message, boolean requiresReconcile) {
        static StopClosure reconcileRequired(String message) {
            return new StopClosure(message, true);
        }
    }

    private record StopConfirmation(String message, boolean requiresReconcile) {
        static StopConfirmation closed(String message) {
            return new StopConfirmation(message, false);
        }

        static StopConfirmation reconcileRequired(String message) {
            return new StopConfirmation(message, true);
        }
    }

    @Override
    public void streamLogs(SyncTask task, SseEmitter emitter) {
        // 查找该任务最近一次执行记录，取 engineJobId 推送 SeaTunnel 进度
        executionRepo.findTopByTaskIdOrderByIdDesc(task.getId()).ifPresentOrElse(exec -> {
            String status = exec.getStatus();
            boolean terminal = "SUCCESS".equals(status)
                    || "FAILED".equals(status)
                    || "CANCELLED".equals(status)
                    || ExecutionResult.STATUS_RECONCILE_REQUIRED.equals(status);
            // 已结束的执行：直接回放最终状态 + 错误信息，避免去 SeaTunnel REST 上拉一个早已不存在的 jobId（会抖出"服务端异常"）
            if (terminal) {
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data("[历史执行] exec=" + exec.getId() + " status=" + status
                                    + " 读取=" + (exec.getReadRows() == null ? "-" : exec.getReadRows())
                                    + " 写入=" + (exec.getWriteRows() == null ? "-" : exec.getWriteRows())));
                    String err = exec.getErrorMsg();
                    if (err != null && !err.isBlank()) {
                        // 错误信息按行送出，前端逐行渲染更可读
                        for (String line : err.split("\\r?\\n")) {
                            emitter.send(SseEmitter.event().name("message").data(line));
                        }
                    }
                    emitter.send(SseEmitter.event().name("done").data(status));
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
                return;
            }
            String jobId = exec.getEngineJobId();
            if (jobId == null || jobId.isBlank()) {
                sendAndComplete(emitter, "error",
                        "No SeaTunnel jobId for task " + task.getId() + " (executor=" + exec.getExecutorType() + ")");
                return;
            }
            lifecycleProbe.streamProgress(exec.getId(), jobId, emitter);
        }, () -> sendAndComplete(emitter, "error", "No execution found for task " + task.getId()));
    }

    private static void sendAndComplete(SseEmitter emitter, String eventName, String msg) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(msg));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
