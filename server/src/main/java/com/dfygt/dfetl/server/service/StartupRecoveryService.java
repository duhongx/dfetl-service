package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.engine.ExecutionResult;
import com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelRestClient;
import com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelLifecycleProbe;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 应用启动时恢复残留的 RUNNING/INTERRUPTED 状态 TaskExecution 记录。
 * <p>
 * 替代原 StartupCleanupService 的简单标记逻辑，增加 SeaTunnel REST 查询：
 * <ul>
 *   <li>有 engineJobId → 查询 SeaTunnel 获取真实终态并回写</li>
 *   <li>无 engineJobId → 标记 INTERRUPTED（从未提交到引擎）</li>
 *   <li>SeaTunnel 仍在 RUNNING → 保留原状态，log.warn 提示人工介入</li>
 *   <li>恢复为 SUCCESS 且窗口类型为 INCREMENT/FULL_THEN_INCREMENT → 经 Validation Gate 放行后提交水位线</li>
 * </ul>
 *
 * <p>并发安全：写回 TaskExecution 前重读并校验状态仍为 RUNNING/INTERRUPTED（乐观检查），
 * 避免与重启瞬间已恢复的正常调度/LifecycleProbe 并发覆盖。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StartupRecoveryService {

    private final TaskExecutionRepository executionRepository;
    private final ObjectProvider<SeaTunnelRestClient> restClientProvider;
    private final ObjectProvider<SeaTunnelLifecycleProbe> lifecycleProbeProvider;
    private final ExecutionSuccessFinalizationService finalizationService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleExecutions() {
        List<TaskExecution> staleExecutions = executionRepository
                .findByStatusIn(List.of("RUNNING", "INTERRUPTED"));

        log.info("StartupRecovery: found {} stale execution records", staleExecutions.size());
        if (staleExecutions.isEmpty()) return;

        int recoveredSuccess = 0;
        int recoveredFailed = 0;
        int unrecoverable = 0;

        for (TaskExecution exec : staleExecutions) {
            RecoveryResult result = recoverSingle(exec);
            switch (result) {
                case SUCCESS -> recoveredSuccess++;
                case FAILED, CANCELLED -> recoveredFailed++;
                case UNRECOVERABLE -> unrecoverable++;
                case SKIPPED -> { /* still RUNNING on SeaTunnel, left as-is */ }
            }
        }

        log.info("StartupRecovery: complete — recoveredSuccess={}, recoveredFailed={}, unrecoverable={}",
                recoveredSuccess, recoveredFailed, unrecoverable);
    }

    private RecoveryResult recoverSingle(TaskExecution exec) {
        // 并发互斥：写回前重读最新状态，若已被正常调度/探针更新为非 RUNNING/INTERRUPTED 则跳过，
        // 避免覆盖刚刚由其他链路写入的终态。
        TaskExecution fresh = executionRepository.findById(exec.getId()).orElse(null);
        if (fresh == null) {
            log.warn("StartupRecovery: exec={} disappeared before recovery, skip", exec.getId());
            return RecoveryResult.SKIPPED;
        }
        String currentStatus = fresh.getStatus();
        if (!"RUNNING".equals(currentStatus) && !"INTERRUPTED".equals(currentStatus)) {
            log.info("StartupRecovery: exec={} already transitioned to {} by another path, skip",
                    exec.getId(), currentStatus);
            return RecoveryResult.SKIPPED;
        }
        exec = fresh;

        String engineJobId = exec.getEngineJobId();

        // Case 1: no engineJobId — job was never submitted to SeaTunnel
        if (engineJobId == null || engineJobId.isBlank()) {
            exec.setStatus("INTERRUPTED");
            exec.setErrorMsg("Job was never submitted to SeaTunnel (no engineJobId)");
            exec.setFinishedAt(Instant.now());
            executionRepository.save(exec);
            log.info("StartupRecovery: exec={} marked INTERRUPTED (no engineJobId)", exec.getId());
            return RecoveryResult.UNRECOVERABLE;
        }

        // Case 2: has engineJobId — query SeaTunnel REST
        SeaTunnelRestClient client = restClientProvider.getIfAvailable();
        if (client == null) {
            exec.setStatus("INTERRUPTED");
            exec.setErrorMsg("Recovery failed: SeaTunnel REST client not available");
            exec.setFinishedAt(Instant.now());
            executionRepository.save(exec);
            log.warn("StartupRecovery: exec={} jobId={} SeaTunnel REST client not available",
                    exec.getId(), engineJobId);
            return RecoveryResult.UNRECOVERABLE;
        }

        Optional<SeaTunnelRestClient.JobInfo> jobInfoOpt;
        try {
            jobInfoOpt = client.getJobInfo(engineJobId);
        } catch (Exception e) {
            exec.setStatus("INTERRUPTED");
            exec.setErrorMsg(ExecutionErrorSanitizer.sanitize(
                    "Recovery failed: SeaTunnel REST error: " + e.getMessage()));
            exec.setFinishedAt(Instant.now());
            executionRepository.save(exec);
            log.warn("StartupRecovery: exec={} jobId={} REST failed: {}",
                    exec.getId(), engineJobId, ExecutionErrorSanitizer.sanitize(e.getMessage()));
            return RecoveryResult.UNRECOVERABLE;
        }

        if (jobInfoOpt.isEmpty()) {
            exec.setStatus("INTERRUPTED");
            exec.setErrorMsg("Recovery failed: SeaTunnel returned empty for jobId=" + engineJobId);
            exec.setFinishedAt(Instant.now());
            executionRepository.save(exec);
            log.warn("StartupRecovery: exec={} jobId={} SeaTunnel returned empty response",
                    exec.getId(), engineJobId);
            return RecoveryResult.UNRECOVERABLE;
        }

        SeaTunnelRestClient.JobInfo info = jobInfoOpt.get();
        String mappedStatus = info.mappedStatus();

        // Case 3: SeaTunnel job still RUNNING — restore local RUNNING state and reattach lifecycle probe
        if ("RUNNING".equals(mappedStatus) || "SCHEDULED".equals(mappedStatus)) {
            exec.setStatus("RUNNING");
            exec.setEngineReadRows(info.readRows());
            exec.setEngineWriteRows(info.writeRows());
            exec.setFinishedAt(null);
            exec.setErrorMsg("Recovered on startup: SeaTunnel job still " + mappedStatus
                    + ", lifecycle probe reattached");
            executionRepository.save(exec);
            reattachLifecycleProbe(exec.getId(), engineJobId);
            log.warn("StartupRecovery: exec={} jobId={} still {} on SeaTunnel, "
                    + "marked RUNNING and reattached lifecycle probe",
                    exec.getId(), engineJobId, mappedStatus);
            return RecoveryResult.SKIPPED;
        }

        // Case 4: Terminal state — update TaskExecution
        String originalStatus = exec.getStatus();
        String newStatus = applyTerminalRecovery(exec, info);

        log.info("StartupRecovery: exec={} jobId={} recovered {} → {}",
                exec.getId(), engineJobId, originalStatus, newStatus);

        if (isSuccessfulStatus(newStatus)) {
            return finalizationService.finalizeRecoveredExecution(exec)
                    ? RecoveryResult.SUCCESS
                    : RecoveryResult.FAILED;
        }
        return "CANCELLED".equals(newStatus) ? RecoveryResult.CANCELLED : RecoveryResult.FAILED;
    }

    private void reattachLifecycleProbe(Long executionId, String engineJobId) {
        SeaTunnelLifecycleProbe probe = lifecycleProbeProvider == null ? null : lifecycleProbeProvider.getIfAvailable();
        if (probe == null) {
            log.warn("StartupRecovery: lifecycle probe unavailable, cannot reattach exec={} jobId={}",
                    executionId, engineJobId);
            return;
        }
        probe.trackAsync(executionId, engineJobId)
                .whenComplete((infoOpt, err) -> {
                    if (err != null) {
                        log.warn("StartupRecovery: reattached lifecycle probe failed exec={} jobId={}: {}",
                                executionId, engineJobId, err.getMessage());
                        return;
                    }
                    if (infoOpt == null || infoOpt.isEmpty() || !infoOpt.get().isTerminal()) {
                        return;
                    }
                    executionRepository.findById(executionId).ifPresent(fresh -> {
                        String newStatus = applyTerminalRecovery(fresh, infoOpt.get());
                        if (isSuccessfulStatus(newStatus)) {
                            finalizationService.finalizeRecoveredExecution(fresh);
                        }
                        log.info("StartupRecovery: reattached lifecycle probe closed exec={} jobId={} status={}",
                                executionId, engineJobId, newStatus);
                    });
                });
    }

    private String applyTerminalRecovery(TaskExecution exec, SeaTunnelRestClient.JobInfo info) {
        String newStatus = mapToExecutionStatus(info.mappedStatus());
        if ("SUCCESS".equals(newStatus)
                && exec.getExcludedRows() != null
                && exec.getExcludedRows() > 0) {
            newStatus = ExecutionResult.STATUS_SUCCESS_WITH_DIRTY_ROWS;
        }
        exec.setStatus(newStatus);
        exec.setEngineReadRows(info.readRows());
        exec.setEngineWriteRows(info.writeRows());
        exec.setFinishedAt(info.finishTime() > 0
                ? Instant.ofEpochMilli(info.finishTime())
                : Instant.now());
        if (info.errorMsg() != null && !info.errorMsg().isBlank()) {
            exec.setErrorMsg(ExecutionErrorSanitizer.sanitize(info.errorMsg()));
        } else if ("SUCCESS".equals(newStatus)) {
            exec.setErrorMsg(null);
        }
        executionRepository.save(exec);
        return newStatus;
    }

    private boolean isSuccessfulStatus(String status) {
        return "SUCCESS".equals(status)
                || ExecutionResult.STATUS_SUCCESS_WITH_DIRTY_ROWS.equals(status);
    }

    private String mapToExecutionStatus(String mappedStatus) {
        if (mappedStatus == null) return "INTERRUPTED";
        return switch (mappedStatus) {
            case "SUCCESS" -> "SUCCESS";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> "INTERRUPTED";
        };
    }

    private enum RecoveryResult {
        SUCCESS, FAILED, CANCELLED, UNRECOVERABLE, SKIPPED
    }
}
