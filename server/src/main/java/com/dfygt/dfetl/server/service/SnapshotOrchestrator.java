package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TaskSnapshotKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Spec 020.2：快照对账自动调度编排。
 *
 * <p>职责：
 * <ul>
 *   <li>任务执行成功后自动 capture（若 {@code snapshotAutoCapture=true}）</li>
 *   <li>按 {@code snapshotAutoDetectCron} Quartz 触发自动 detect</li>
 *   <li>detect 后差集比例 ≤ {@code snapshotDeleteMaxRatio} 且 {@code snapshotAutoApply=true} 时自动 apply</li>
 *   <li>否则记录 WARN 日志，等待人工调用现有 {@code POST /apply-deletes}</li>
 * </ul>
 *
 * <p>v1 不引入 task_execution.execution_kind，仅靠 server.log 与 task_snapshot_key 表反查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotOrchestrator {

    private final SnapshotDeleteService snapshotDeleteService;
    private final SyncTaskRepository syncTaskRepo;
    private final TaskSnapshotKeyRepository snapshotRepo;

    /** 单表/多表执行成功路径的统一回调点（DfetlExecutorService 调用）。*/
    public void onTaskExecutionSucceeded(SyncTask task, Long executionId) {
        if (task == null || executionId == null) return;
        if (!Boolean.TRUE.equals(task.getSnapshotAutoCapture())) {
            return;
        }
        // enableSnapshotDelete 在 validateDto 中已是 snapshotAutoCapture=true 的前置校验，此处兜底
        if (!Boolean.TRUE.equals(task.getEnableSnapshotDelete())) {
            log.warn("SnapshotOrchestrator.capture skipped: task={} snapshotAutoCapture=true 但 enableSnapshotDelete=false",
                    task.getId());
            return;
        }
        // spec 036：snapshot capture 频控
        Integer interval = task.getSnapshotCaptureIntervalMinutes();
        if (interval != null && interval > 0) {
            try {
                java.time.LocalDateTime last = snapshotRepo.findMaxCapturedAt(task.getId());
                if (last != null) {
                    long minutesSince = java.time.Duration.between(last, java.time.LocalDateTime.now()).toMinutes();
                    if (minutesSince < interval) {
                        log.info("SnapshotOrchestrator.capture skipped (throttled): task={} lastCaptureAgo={}min < interval={}min",
                                task.getId(), minutesSince, interval);
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("SnapshotOrchestrator.capture throttle check failed task={}: {}", task.getId(), e.getMessage());
            }
        }
        try {
            int captured = snapshotDeleteService.capture(task.getId(), executionId);
            log.info("SnapshotOrchestrator.capture taskId={} executionId={} captured={}",
                    task.getId(), executionId, captured);
        } catch (Exception e) {
            // 不阻断主链路：capture 失败仅记日志，下次有快照时 detect 自然跳过
            log.warn("SnapshotOrchestrator.capture failed task={} exec={}: {}",
                    task.getId(), executionId, e.getMessage());
        }
    }

    /** Quartz 触发的自动 detect（+ 条件 apply）。任何异常仅记日志，不向上抛。*/
    public void runScheduledDetect(Long taskId) {
        SyncTask task = syncTaskRepo.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("SnapshotOrchestrator.detect: task {} not found", taskId);
            return;
        }
        if (!Boolean.TRUE.equals(task.getEnableSnapshotDelete())) {
            log.debug("SnapshotOrchestrator.detect skipped: task={} enableSnapshotDelete=false", taskId);
            return;
        }
        List<Long> execIdsDesc = snapshotRepo.findExecutionIdsDesc(taskId);
        if (execIdsDesc == null || execIdsDesc.size() < 2) {
            log.info("SnapshotOrchestrator.detect taskId={} 跳过：快照执行数 {} < 2", taskId,
                    execIdsDesc == null ? 0 : execIdsDesc.size());
            return;
        }
        Long currExecId = execIdsDesc.get(0);
        Long prevExecId = execIdsDesc.get(1);

        List<String> deleted;
        try {
            deleted = snapshotDeleteService.detect(taskId, currExecId, prevExecId);
        } catch (Exception e) {
            log.warn("SnapshotOrchestrator.detect failed task={} curr={} prev={}: {}",
                    taskId, currExecId, prevExecId, e.getMessage());
            return;
        }
        int deletedCnt = deleted == null ? 0 : deleted.size();
        if (deletedCnt == 0) {
            snapshotDeleteService.recordDetectHistory(taskId, prevExecId, currExecId, 0,
                    "NO_DELETES", "调度检测未发现源端删除差集");
            log.info("SnapshotOrchestrator.detect taskId={} curr={} prev={} 无差集", taskId, currExecId, prevExecId);
            return;
        }
        int prevSize = Math.max(1, snapshotRepo.findKeyValues(taskId, prevExecId).size());
        BigDecimal ratio = new BigDecimal(deletedCnt)
                .divide(new BigDecimal(prevSize), 4, RoundingMode.HALF_UP);

        BigDecimal maxRatio = task.getSnapshotDeleteMaxRatio() != null
                ? task.getSnapshotDeleteMaxRatio() : new BigDecimal("0.0500");
        boolean withinThreshold = ratio.compareTo(maxRatio) <= 0;

        if (!Boolean.TRUE.equals(task.getSnapshotAutoApply())) {
            snapshotDeleteService.recordDetectHistory(taskId, prevExecId, currExecId, deletedCnt,
                    "DETECTED", "调度检测发现 " + deletedCnt + " 个源端已删除 key，autoApply=false");
            log.info("SnapshotOrchestrator.detect taskId={} deleted={} prev={} ratio={} (autoApply=false, 待人工 apply)",
                    taskId, deletedCnt, prevSize, ratio.toPlainString());
            return;
        }
        if (!withinThreshold) {
            snapshotDeleteService.recordDetectHistory(taskId, prevExecId, currExecId, deletedCnt,
                    "FUSED", "调度检测发现 " + deletedCnt + " 个源端已删除 key，删除比例 "
                            + ratio.toPlainString() + " > maxRatio=" + maxRatio.toPlainString());
            log.warn("SnapshotOrchestrator.detect taskId={} deleted={} prev={} ratio={} > maxRatio={}，"
                            + "已熔断，请人工核对后调用 POST /api/sync-task/{}/snapshot/apply-deletes",
                    taskId, deletedCnt, prevSize, ratio.toPlainString(), maxRatio.toPlainString(), taskId);
            return;
        }
        try {
            SnapshotDeleteService.ApplyDeleteResult r =
                    snapshotDeleteService.applyDeletes(taskId, prevExecId, currExecId, false);
            log.info("SnapshotOrchestrator.apply taskId={} deleted={} ratio={} result={} loaded={} filtered={} label={}",
                    taskId, deletedCnt, ratio.toPlainString(),
                    r.result(), r.loadedRows(), r.filteredRows(), r.label());
        } catch (Exception e) {
            log.error("SnapshotOrchestrator.apply failed task={} curr={} prev={}: {}",
                    taskId, currExecId, prevExecId, e.getMessage(), e);
        }
    }
}
