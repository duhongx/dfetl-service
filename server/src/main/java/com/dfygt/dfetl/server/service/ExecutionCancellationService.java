package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.engine.ExecutionResult;
import com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelRestClient;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 统一协调任务级和 execution 级取消请求。
 *
 * <p>远端 stop 请求成功不等于作业已经停止。只有 job-info 明确确认远端进入
 * CANCELLED/FAILED，平台才把本次用户操作记为 CANCELLED；其他无法确认的情况都进入
 * RECONCILE_REQUIRED，禁止把未知远端终态伪装成已取消。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionCancellationService {

    static final List<String> CANCELLABLE_STATUSES = List.of("RUNNING", "PENDING");
    private static final String CANCELLED = "CANCELLED";
    private static final int ERROR_MSG_MAX_CHARS = 8000;

    private final TaskExecutionRepository executionRepository;
    private final ObjectProvider<SeaTunnelRestClient> seaTunnelRestClientProvider;
    private final TaskExecutionQueue executionQueue;
    private final ExecutionCancellationRegistry cancellationRegistry;

    /**
     * 取消某个任务最新的 RUNNING/PENDING execution。没有活动 execution 时返回 empty，保持任务入口幂等。
     */
    @Transactional
    public Optional<CancellationResult> cancelLatestForTask(Long taskId) {
        Optional<CancellationResult> result = executionRepository
                .findTopByTaskIdAndStatusInOrderByIdDesc(taskId, CANCELLABLE_STATUSES)
                .map(this::cancelLockedExecution);
        if (result.isPresent()) {
            signalAfterCommit(taskId);
            return result;
        }
        if (executionQueue.cancelTask(taskId)) {
            return Optional.of(new CancellationResult(
                    taskId, -1L, null, CANCELLED,
                    "User cancellation completed: queued/retry-wait task removed before SeaTunnel submission"));
        }
        return Optional.empty();
    }

    /**
     * 按 execution id 取消。终态 execution 不允许再次取消。
     */
    @Transactional
    public CancellationResult cancelExecution(Long executionId) {
        TaskExecution execution = executionRepository.findByIdForCancellation(executionId)
                .orElseThrow(() -> new NoSuchElementException("TaskExecution not found: " + executionId));
        CancellationResult result = cancelLockedExecution(execution);
        signalAfterCommit(execution.getTaskId());
        return result;
    }

    private CancellationResult cancelLockedExecution(TaskExecution execution) {
        if (!CANCELLABLE_STATUSES.contains(execution.getStatus())) {
            throw new IllegalStateException("Only RUNNING/PENDING executions can be cancelled");
        }

        String engineJobId = blankToNull(execution.getEngineJobId());
        if (engineJobId == null) {
            return complete(execution, CANCELLED,
                    "User cancellation completed: no engineJobId; execution was not submitted to SeaTunnel");
        }

        SeaTunnelRestClient client;
        try {
            client = seaTunnelRestClientProvider.getIfAvailable();
        } catch (RuntimeException ex) {
            return reconcile(execution,
                    "SeaTunnel REST client lookup failed: " + safeMessage(ex));
        }
        if (client == null) {
            return reconcile(execution, "SeaTunnel REST client unavailable");
        }

        SeaTunnelRestClient.StopResult stopResult;
        try {
            stopResult = client.stopJob(engineJobId, true);
        } catch (RuntimeException ex) {
            return reconcile(execution, "SeaTunnel stop failed: " + safeMessage(ex));
        }
        if (stopResult == null || !stopResult.success()) {
            String reason = stopResult == null ? "empty stop response" : blankToUnknown(stopResult.errorMsg());
            return reconcile(execution, "SeaTunnel stop failed: " + reason);
        }

        Optional<SeaTunnelRestClient.JobInfo> jobInfo;
        try {
            jobInfo = client.getJobInfo(engineJobId);
        } catch (RuntimeException ex) {
            return reconcile(execution,
                    "SeaTunnel stop accepted but confirmation query failed: " + safeMessage(ex));
        }
        if (jobInfo == null || jobInfo.isEmpty()) {
            return reconcile(execution,
                    "SeaTunnel stop accepted but stop not confirmed: job-info unavailable");
        }

        String remoteStatus = normalizedRemoteStatus(jobInfo.orElseThrow());
        if (CANCELLED.equals(remoteStatus) || "FAILED".equals(remoteStatus)) {
            return complete(execution, CANCELLED,
                    "User cancellation completed: confirmed remoteStatus=" + remoteStatus
                            + " jobId=" + engineJobId);
        }
        return reconcile(execution,
                "SeaTunnel stop accepted but stop not confirmed: remoteStatus=" + remoteStatus
                        + " jobId=" + engineJobId);
    }

    private CancellationResult reconcile(TaskExecution execution, String reason) {
        return complete(execution, ExecutionResult.STATUS_RECONCILE_REQUIRED,
                "User cancellation requested; RECONCILE_REQUIRED: " + reason);
    }

    private CancellationResult complete(TaskExecution execution, String status, String detail) {
        execution.setStatus(status);
        execution.setFinishedAt(Instant.now());
        execution.setErrorMsg(ExecutionErrorSanitizer.sanitize(
                appendError(execution.getErrorMsg(), detail)));
        if (ExecutionResult.STATUS_RECONCILE_REQUIRED.equals(status)) {
            execution.setReconcileHandled(false);
            execution.setReconcileHandledAt(null);
            execution.setReconcileHandledBy(null);
        }
        executionRepository.save(execution);
        log.info("Execution cancellation completed: taskId={} executionId={} engineJobId={} status={} detail={}",
                execution.getTaskId(), execution.getId(), execution.getEngineJobId(), status, detail);
        return new CancellationResult(
                execution.getTaskId(), execution.getId(), blankToNull(execution.getEngineJobId()), status, detail);
    }

    private void signalAfterCommit(Long taskId) {
        Runnable signal = () -> {
            cancellationRegistry.request(taskId);
            executionQueue.cancelTask(taskId);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    signal.run();
                }
            });
        } else {
            signal.run();
        }
    }

    private String normalizedRemoteStatus(SeaTunnelRestClient.JobInfo info) {
        String mapped = normalizeStatus(info.mappedStatus());
        if (mapped != null) {
            return mapped;
        }
        String raw = normalizeStatus(info.jobStatus());
        if ("CANCELED".equals(raw)) {
            return CANCELLED;
        }
        return raw == null ? "UNKNOWN" : raw;
    }

    private String appendError(String oldValue, String detail) {
        String merged = oldValue == null || oldValue.isBlank() ? detail : oldValue + "\n" + detail;
        if (merged.length() <= ERROR_MSG_MAX_CHARS) {
            return merged;
        }
        return merged.substring(merged.length() - ERROR_MSG_MAX_CHARS);
    }

    private static String normalizeStatus(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String blankToUnknown(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? "unknown error" : normalized;
    }

    private static String safeMessage(Throwable throwable) {
        return blankToUnknown(throwable.getMessage());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record CancellationResult(
            Long taskId,
            Long executionId,
            String engineJobId,
            String status,
            String detail) {
    }
}
