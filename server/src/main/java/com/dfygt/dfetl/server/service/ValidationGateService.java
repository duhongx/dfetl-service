package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * spec 022：failBlock 校验门控。
 *
 * <p>当全局策略 {@code validation_fail_block = true} 时，增量水位提交前先同步执行
 * 行数校验；仅结果为 CONSISTENT 才放行。策略关闭时放行；策略开启后若无法确认
 * 校验通过，必须 fail-closed，避免在校验链路异常时推进水位。
 *
 * <p>注意：同步阻塞执行，预计耗时与 ROW_COUNT 相当（秒级）。若任务为 Checksum
 * 模式可能耗时较长，生产环境建议仅对行数校验方式开启 failBlock。
 *
 * <p>spec validation-table-consolidation · Step 8：
 * 写入路径改为 validation_run 表，移除对 ValidationTask / ValidationTaskRepository 的直接引用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationGateService {

    /** 在该窗口内复用最近的 GATE 结果，避免频繁提交水位时冲击 validation_run 表。 */
    private static final long GATE_REUSE_WINDOW_SEC = 300;   // 5 分钟

    private final GlobalSettingsService globalSettingsService;
    private final ValidationRunRepository validationRunRepo;
    private final ValidationRunner validationRunner;
    private final TaskValidationConfigService configService;

    /**
     * 检查是否允许提交水位。
     *
     * @param task      当前同步任务
     * @param execId    本次执行 ID（写入校验任务关联）
     * @return {@code true} = 允许提交水位；{@code false} = 校验失败，阻断水位
     */
    public boolean checkAndBlock(SyncTask task, Long execId) {
        return checkAndBlock(task, execId, null);
    }

    public boolean checkAndBlock(SyncTask task, Long execId, WatermarkService.WindowContext window) {
        try {
            var policy = globalSettingsService.getValidationPolicy();
            var config = findTaskConfig(task);
            boolean failBlockOn = isFailBlockEnabled(config, policy);
            if (!failBlockOn) return true;
            if (!policy.autoEnabled()) {
                log.warn("ValidationGate: blockOnFail is enabled while global auto validation is disabled; "
                                + "running synchronous gate validation task={} exec={}",
                        task.getId(), execId);
            }

            // Bug3：复用近期 GATE 结果避免重复创建临时任务。
            // 带窗口的增量校验不能跨窗口复用，否则会让水位提交依赖上一批次的校验结果。
            if (canReuseRecentGate(window)) {
                Instant since = Instant.now().minusSeconds(GATE_REUSE_WINDOW_SEC);
                var recent = validationRunRepo
                        .findFirstByTaskIdAndTriggerTypeAndLastRunAtAfterOrderByLastRunAtDesc(task.getId(), "GATE", since);
                if (recent.isPresent()) {
                    String s = recent.get().getStatus();
                    boolean pass = "CONSISTENT".equals(s);
                    log.info("ValidationGate: reuse recent GATE result task={} runId={} status={} canCommit={}",
                            task.getId(), recent.get().getId(), s, pass);
                    return pass;
                }
            }

            log.info("ValidationGate: running pre-commit validation for task={} exec={}", task.getId(), execId);

            // 创建 ValidationRun 记录（行数校验；GATE 的复检必须在当前收尾链路内同步完成）
            ValidationRun run = new ValidationRun();
            run.setName("gate-" + task.getId() + "-" + execId);
            run.setTaskId(task.getId());
            run.setExecutionId(execId);
            run.setLegacyExecId(gateLegacyExecId(execId));
            run.setTriggerType("GATE");
            run.setStatus("RUNNING");
            run.setMethod("ROW_COUNT");        // 门控只用行数校验（速度快，不阻塞过久）
            run.setMode("ROW_COUNT");
            run.setScope(window == null || !hasScopedWindow(window) ? "FULL" : "WINDOW");
            run.setTablesText("");
            ValidationDispatchService.copyWindow(run, window, task);
            run.setUpdatedAt(LocalDateTime.now());
            run = validationRunRepo.save(run);

            String status = runGateAttempt(run, config);
            if ("DIFF".equals(status) && policy.revalidate()) {
                status = runSynchronousRevalidate(run, config, policy.revalidateDelay());
            }

            boolean pass = "CONSISTENT".equals(status);
            log.info("ValidationGate result: task={} exec={} status={} canCommit={}",
                    task.getId(), execId, status, pass);
            return pass;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("ValidationGate interrupted for task={} exec={}, fail-closed and blocking commit",
                    task.getId(), execId, e);
            return false;
        } catch (Exception e) {
            log.error("ValidationGate exception for task={} exec={}, fail-closed and blocking commit: {}",
                    task.getId(), execId, e.getMessage(), e);
            return false;
        }
    }

    private String runGateAttempt(ValidationRun run, TaskValidationConfig config) {
        validationRunner.run(run, config);
        return validationRunRepo.findById(run.getId())
                .map(ValidationRun::getStatus)
                .orElse("ERROR");
    }

    private String runSynchronousRevalidate(
            ValidationRun run,
            TaskValidationConfig config,
            int delaySeconds) throws InterruptedException {
        int safeDelaySeconds = Math.max(0, delaySeconds);
        log.info("ValidationGate: waiting {}s before synchronous revalidate runId={} task={} exec={}",
                safeDelaySeconds, run.getId(), run.getTaskId(), run.getExecutionId());
        if (safeDelaySeconds > 0) {
            Thread.sleep(safeDelaySeconds * 1000L);
        }

        ValidationRun retry = validationRunRepo.findById(run.getId()).orElse(null);
        if (retry == null) {
            log.error("ValidationGate: validation run disappeared before synchronous revalidate runId={}",
                    run.getId());
            return "ERROR";
        }
        retry.setStatus("RUNNING");
        retry.setErrorMsg(null);
        retry.setUpdatedAt(LocalDateTime.now());
        retry = validationRunRepo.save(retry);

        String status = runGateAttempt(retry, config);
        log.info("ValidationGate: synchronous revalidate completed runId={} task={} exec={} status={}",
                retry.getId(), retry.getTaskId(), retry.getExecutionId(), status);
        return status;
    }

    public boolean isFailBlockEnabled(SyncTask task) {
        return isFailBlockEnabled(findTaskConfig(task), globalSettingsService.getValidationPolicy());
    }

    private TaskValidationConfig findTaskConfig(SyncTask task) {
        return task != null && task.getId() != null
                ? configService.findEntityByTaskId(task.getId()).orElse(null)
                : null;
    }

    private boolean isFailBlockEnabled(
            TaskValidationConfig config,
            com.dfygt.dfetl.server.dto.ValidationPolicy policy) {
        // spec 031：任务级覆盖；blockOnFail 为空时继承全局 failBlock
        boolean configActive = config != null && Boolean.TRUE.equals(config.getEnabled());
        if (!configActive || config.getBlockOnFail() == null) {
            return policy.failBlock();
        }
        return Boolean.TRUE.equals(config.getBlockOnFail());
    }

    private boolean hasScopedWindow(WatermarkService.WindowContext window) {
        return window != null && window.hasScopedWindow();
    }

    private boolean canReuseRecentGate(WatermarkService.WindowContext window) {
        if (window == null) {
            return true;
        }
        if (window.isInitialFullSync() || window.isIncrementalWindow()) {
            return false;
        }
        return !hasScopedWindow(window);
    }

    private long gateLegacyExecId(Long execId) {
        if (execId == null) {
            return -System.currentTimeMillis();
        }
        return execId > 0 ? -execId : execId - 1;
    }
}
