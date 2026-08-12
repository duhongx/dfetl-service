package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.dto.TaskExecutionDto;
import com.dfygt.dfetl.server.dto.TaskStatsDto;
import com.dfygt.dfetl.server.engine.ExecutionResult;
import com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelRestClient;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskExecutionService {

    private static final String RECONCILE_REQUIRED = ExecutionResult.STATUS_RECONCILE_REQUIRED;
    private static final String MANUAL_PROBE_PREFIX = "[manual-reconcile-probe]";
    private static final int ERROR_MSG_MAX_CHARS = 8000;
    private static final int RECONCILE_NOTE_MAX_CHARS = 4000;

    private final TaskExecutionRepository executionRepository;
    private final SyncTaskRepository syncTaskRepository;
    private final ValidationRunRepository validationRunRepository;
    private final ObjectProvider<SeaTunnelRestClient> seaTunnelRestClient;
    private final ExecutionCancellationService executionCancellationService;

    /**
     * 分页查询执行历史（监控页面）
     */
    public Page<TaskExecutionDto> findAll(Pageable pageable) {
        return findAll(null, pageable);
    }

    /**
     * 分页查询执行历史，可按状态过滤。
     */
    public Page<TaskExecutionDto> findAll(String status, Pageable pageable) {
        String normalizedStatus = normalizeStatus(status);
        Page<TaskExecution> page = normalizedStatus == null
                ? executionRepository.findAll(pageable)
                : executionRepository.findByStatus(normalizedStatus, pageable);
        // 批量加载任务名
        Map<Long, String> taskNames = loadTaskNames(page);
        return page.map(e -> toDto(e, taskNames.get(e.getTaskId())));
    }

    /**
     * 查询所有需要人工核对的执行记录。
     */
    public Page<TaskExecutionDto> findReconcileRequired(Pageable pageable) {
        return findReconcileRequired("false", pageable);
    }

    /**
     * 查询需要人工核对的执行记录。handled=false 默认只看未处理；handled=all 查看全部。
     */
    public Page<TaskExecutionDto> findReconcileRequired(String handled, Pageable pageable) {
        String normalized = handled == null || handled.isBlank() ? "false" : handled.trim().toLowerCase();
        if ("all".equals(normalized)) {
            return findAll(RECONCILE_REQUIRED, pageable);
        }
        boolean handledFlag;
        if ("true".equals(normalized)) {
            handledFlag = true;
        } else if ("false".equals(normalized)) {
            handledFlag = false;
        } else {
            throw new IllegalArgumentException("handled must be true, false, or all");
        }
        Page<TaskExecution> page = executionRepository.findByStatusAndReconcileHandled(
                RECONCILE_REQUIRED, handledFlag, pageable);
        Map<Long, String> taskNames = loadTaskNames(page);
        return page.map(e -> toDto(e, taskNames.get(e.getTaskId())));
    }

    /**
     * 按任务分页查询执行历史（任务详情页 → 批次历史）
     */
    public Page<TaskExecutionDto> findByTask(Long taskId, Pageable pageable) {
        Page<TaskExecution> page = executionRepository.findByTaskId(taskId, pageable);
        String taskName = syncTaskRepository.findById(taskId).map(SyncTask::getName).orElse("");
        return page.map(e -> toDto(e, taskName));
    }

    /**
     * 查询单次执行详情
     */
    public TaskExecutionDto findById(Long id) {
        TaskExecution e = executionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("TaskExecution not found: " + id));
        String taskName = syncTaskRepository.findById(e.getTaskId())
                .map(SyncTask::getName).orElse("");
        return toDto(e, taskName);
    }

    /**
     * 手动重新探测 RECONCILE_REQUIRED 的 SeaTunnel job。
     *
     * <p>该动作只记录最新观察结果，不自动提交 watermark、不自动改 SUCCESS、
     * 不触发 snapshot / auto validation。真实 job 若已 SUCCESS，也必须先走人工数据核对或重新校验。
     */
    @Transactional
    public TaskExecutionDto probeReconcile(Long id) {
        TaskExecution e = executionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("TaskExecution not found: " + id));
        if (!RECONCILE_REQUIRED.equals(e.getStatus())) {
            throw new IllegalStateException("Only RECONCILE_REQUIRED executions can be manually probed");
        }

        String jobId = e.getEngineJobId();
        String note;
        if (jobId == null || jobId.isBlank()) {
            note = manualProbeNote("UNKNOWN", null,
                    "missing engineJobId; manual platform/SeaTunnel log check required");
        } else {
            SeaTunnelRestClient client = seaTunnelRestClient.getIfAvailable();
            if (client == null) {
                note = manualProbeNote("UNKNOWN", null,
                        "SeaTunnel REST client is not enabled; manual SeaTunnel check required jobId=" + jobId);
            } else {
                try {
                    Optional<SeaTunnelRestClient.JobInfo> info = client.getJobInfo(jobId);
                    if (info.isPresent()) {
                        SeaTunnelRestClient.JobInfo job = info.get();
                        String mapped = blankToNull(job.mappedStatus());
                        String raw = blankToNull(job.jobStatus());
                        String status = mapped != null ? mapped : raw;
                        String advice = reconcileAdvice(status);
                        note = manualProbeNote(status == null ? "UNKNOWN" : status, raw, advice);
                    } else {
                        note = manualProbeNote("UNKNOWN", null,
                                "SeaTunnel job-info unavailable; keep RECONCILE_REQUIRED and verify Doris manually jobId=" + jobId);
                    }
                } catch (Exception ex) {
                    log.warn("Manual reconcile probe failed exec={} jobId={} err={}", id, jobId, ex.getMessage());
                    note = manualProbeNote("UNKNOWN", null,
                            "SeaTunnel job-info query failed; keep RECONCILE_REQUIRED and verify manually");
                }
            }
        }

        e.setReconcileLastProbedAt(Instant.now());
        e.setReconcileLastProbeResult(note);
        e.setErrorMsg(ExecutionErrorSanitizer.sanitize(appendLine(e.getErrorMsg(), note)));
        executionRepository.save(e);

        String taskName = syncTaskRepository.findById(e.getTaskId())
                .map(SyncTask::getName).orElse("");
        return toDto(e, taskName);
    }

    /**
     * 人工标记 RECONCILE_REQUIRED 已处理。
     *
     * <p>该动作只关闭运维待办，不改变 execution 状态、不提交 watermark、不触发成功后动作。
     */
    @Transactional
    public TaskExecutionDto markReconcileHandled(Long id, String note, String operator) {
        TaskExecution e = findReconcileExecution(id);
        String normalizedNote = requireNote(note);
        e.setReconcileHandled(true);
        e.setReconcileHandledAt(Instant.now());
        e.setReconcileHandledBy(normalizeOperator(operator));
        e.setReconcileNote(normalizedNote);
        executionRepository.save(e);
        log.info("TaskExecutionService: reconcile handled exec={} operator={}", id, e.getReconcileHandledBy());
        return toDtoWithTaskName(e);
    }

    /**
     * 重新打开已处理的 RECONCILE_REQUIRED 待办。
     */
    @Transactional
    public TaskExecutionDto reopenReconcile(Long id, String note, String operator) {
        TaskExecution e = findReconcileExecution(id);
        String by = normalizeOperator(operator);
        String normalizedNote = normalizeOptionalNote(note);
        String reopenLine = "reopened by " + by + " at=" + Instant.now()
                + (normalizedNote == null ? "" : ": " + normalizedNote);
        e.setReconcileHandled(false);
        e.setReconcileHandledAt(null);
        e.setReconcileHandledBy(null);
        e.setReconcileNote(appendLine(e.getReconcileNote(), reopenLine));
        executionRepository.save(e);
        log.info("TaskExecutionService: reconcile reopened exec={} operator={}", id, by);
        return toDtoWithTaskName(e);
    }

    /** 取消正在执行的任务，统一执行远端 stop、终态确认和状态回写。 */
    public void cancel(Long id) {
        executionCancellationService.cancelExecution(id);
    }

    /**
     * 任务执行统计（任务详情页顶部卡片）
     */
    public TaskStatsDto getTaskStats(Long taskId) {
        long total = executionRepository.countByTaskId(taskId);
        long success = executionRepository.countByTaskIdAndStatus(taskId, "SUCCESS");
        long failed = executionRepository.countByTaskIdAndStatus(taskId, "FAILED");
        long reconcileRequired = executionRepository.countByTaskIdAndStatus(taskId, RECONCILE_REQUIRED);
        long reconcileUnhandled = executionRepository.countByTaskIdAndStatusAndReconcileHandled(taskId, RECONCILE_REQUIRED, false);
        long reconcileHandled = executionRepository.countByTaskIdAndStatusAndReconcileHandled(taskId, RECONCILE_REQUIRED, true);
        double rate = total > 0 ? Math.round(success * 1000.0 / total) / 10.0 : 0.0;
        return new TaskStatsDto(total, success, failed, reconcileRequired,
                reconcileRequired, reconcileUnhandled, reconcileHandled, rate);
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private Map<Long, String> loadTaskNames(Page<TaskExecution> page) {
        return page.getContent().stream()
                .map(TaskExecution::getTaskId)
                .distinct()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> syncTaskRepository.findById(id).map(SyncTask::getName).orElse("")
                ));
    }

    private TaskExecutionDto toDto(TaskExecution e, String taskName) {
        TaskExecutionDto dto = new TaskExecutionDto();
        dto.setId(e.getId());
        dto.setTaskId(e.getTaskId());
        dto.setTaskName(taskName);
        dto.setBatchNo(e.getBatchNo());
        dto.setTriggeredBy(e.getTriggeredBy());
        dto.setWorkerNode(e.getWorkerNode());
        dto.setStatus(e.getStatus());
        dto.setSnapshotSyncType(e.getSnapshotSyncType());
        dto.setSnapshotSyncMode(e.getSnapshotSyncMode());
        dto.setSnapshotViewNames(e.getSnapshotViewNames());
        dto.setWindowStart(e.getWindowStart());
        dto.setWindowEnd(e.getWindowEnd());
        dto.setWindowStartId(e.getWindowStartId());
        dto.setWindowEndId(e.getWindowEndId());
        dto.setWindowType(e.getWindowType());
        dto.setReadRows(e.getReadRows());
        dto.setWriteRows(e.getWriteRows());
        dto.setFailedRows(e.getFailedRows());
        dto.setSourceRowsTotal(e.getSourceRowsTotal());
        dto.setValidSourceRows(e.getValidSourceRows());
        dto.setExcludedRows(e.getExcludedRows());
        dto.setWarningRows(e.getWarningRows());
        dto.setEngineReadRows(e.getEngineReadRows());
        dto.setEngineWriteRows(e.getEngineWriteRows());
        dto.setBytesWritten(e.getBytesWritten());
        dto.setSpeedMbS(e.getSpeedMbS());
        dto.setChannelCount(e.getChannelCount());
        dto.setStartedAt(e.getStartedAt());
        dto.setFinishedAt(e.getFinishedAt());
        dto.setDurationMs(e.getDurationMs());
        dto.setErrorMsg(ExecutionErrorSanitizer.sanitize(e.getErrorMsg()));
        dto.setCreatedAt(e.getCreatedAt());
        dto.setExecutorType(e.getExecutorType());
        dto.setEngineJobId(e.getEngineJobId());
        dto.setReconcileHandled(Boolean.TRUE.equals(e.getReconcileHandled()));
        dto.setReconcileHandledAt(e.getReconcileHandledAt());
        dto.setReconcileHandledBy(e.getReconcileHandledBy());
        dto.setReconcileNote(e.getReconcileNote());
        dto.setReconcileLastProbedAt(e.getReconcileLastProbedAt());
        dto.setReconcileLastProbeResult(e.getReconcileLastProbeResult());
        enrichReconcileFields(dto, e);
        return dto;
    }

    private void enrichReconcileFields(TaskExecutionDto dto, TaskExecution e) {
        boolean reconcileRequired = RECONCILE_REQUIRED.equals(e.getStatus());
        String safeError = ExecutionErrorSanitizer.sanitize(e.getErrorMsg());
        dto.setReconcileRequired(reconcileRequired);
        dto.setWatermarkCommitted(reconcileRequired ? Boolean.FALSE : null);
        dto.setReconcileReason(extractReconcileReason(safeError));
        dto.setLastProbeStatus(extractLastProbeStatus(safeError));
        dto.setStopResult(extractStopResult(safeError));
        dto.setValidationStatus(validationRunRepository.findFirstByExecutionIdOrderByIdDesc(e.getId())
                .map(ValidationRun::getStatus)
                .orElse(null));
        if (reconcileRequired) {
            dto.setOperationAdvice("SeaTunnel 终态未确认，平台不会提交 watermark，也不会触发成功后动作。请先重新探测 job 状态，并按 runbook 做 Doris 数据核对或重新校验。");
        }
    }

    private TaskExecution findReconcileExecution(Long id) {
        TaskExecution e = executionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("TaskExecution not found: " + id));
        if (!RECONCILE_REQUIRED.equals(e.getStatus())) {
            throw new IllegalStateException("Only RECONCILE_REQUIRED executions can be handled");
        }
        return e;
    }

    private TaskExecutionDto toDtoWithTaskName(TaskExecution e) {
        String taskName = syncTaskRepository.findById(e.getTaskId())
                .map(SyncTask::getName).orElse("");
        return toDto(e, taskName);
    }

    private String requireNote(String note) {
        String normalized = note == null ? "" : note.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("reconcile handling note is required");
        }
        validateNoteLength(normalized);
        return normalized;
    }

    private String normalizeOptionalNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String normalized = note.trim();
        validateNoteLength(normalized);
        return normalized;
    }

    private void validateNoteLength(String note) {
        if (note.length() > RECONCILE_NOTE_MAX_CHARS) {
            throw new IllegalArgumentException("reconcile handling note is too long, max " + RECONCILE_NOTE_MAX_CHARS + " characters");
        }
    }

    private String normalizeOperator(String operator) {
        return operator == null || operator.isBlank() ? "operator" : operator.trim();
    }

    private String manualProbeNote(String mappedStatus, String rawStatus, String advice) {
        StringBuilder sb = new StringBuilder(MANUAL_PROBE_PREFIX)
                .append(" at=").append(Instant.now())
                .append(" mappedStatus=").append(mappedStatus == null ? "UNKNOWN" : mappedStatus);
        if (rawStatus != null && !rawStatus.isBlank()) {
            sb.append(" rawStatus=").append(rawStatus);
        }
        sb.append("; advice=").append(advice);
        return sb.toString();
    }

    private String reconcileAdvice(String status) {
        String normalized = normalizeStatus(status);
        if ("SUCCESS".equals(normalized)) {
            return "SeaTunnel job is SUCCESS, but watermark was not committed; run validation or manually verify Doris before deciding rerun/recovery";
        }
        if ("FAILED".equals(normalized) || "CANCELLED".equals(normalized)) {
            return "SeaTunnel job is terminal failure/cancelled; keep watermark unchanged and rerun after checking target data";
        }
        if ("RUNNING".equals(normalized) || "SCHEDULED".equals(normalized)) {
            return "SeaTunnel job is still non-terminal; keep RECONCILE_REQUIRED and consider stop/check Doris before rerun";
        }
        return "SeaTunnel job status is unknown; keep RECONCILE_REQUIRED and perform manual check";
    }

    private String appendLine(String oldValue, String line) {
        String merged = oldValue == null || oldValue.isBlank() ? line : oldValue + "\n" + line;
        if (merged.length() <= ERROR_MSG_MAX_CHARS) {
            return merged;
        }
        return merged.substring(merged.length() - ERROR_MSG_MAX_CHARS);
    }

    private String extractReconcileReason(String errorMsg) {
        if (errorMsg == null || errorMsg.isBlank()) return null;
        int idx = errorMsg.indexOf("RECONCILE_REQUIRED:");
        if (idx >= 0) {
            return clipAtLineOrSemicolon(errorMsg.substring(idx));
        }
        return clipAtLineOrSemicolon(errorMsg);
    }

    private String extractLastProbeStatus(String errorMsg) {
        if (errorMsg == null || errorMsg.isBlank()) return null;
        String latestManualProbe = latestLineContaining(errorMsg, MANUAL_PROBE_PREFIX);
        String status = valueAfterKey(latestManualProbe, "mappedStatus=");
        if (status != null) return status;
        status = valueAfterKey(errorMsg, "lastStatus=");
        if (status != null) return status;
        status = valueAfterKey(errorMsg, "terminal status=");
        if (status != null) return status;
        status = valueAfterKey(errorMsg, "status=");
        return status;
    }

    private String extractStopResult(String errorMsg) {
        if (errorMsg == null || errorMsg.isBlank()) return null;
        int failed = errorMsg.indexOf("SeaTunnel force stop failed");
        if (failed >= 0) {
            return clipAtLineOrSemicolon(errorMsg.substring(failed));
        }
        int requested = errorMsg.indexOf("SeaTunnel stop requested");
        if (requested >= 0) {
            return clipAtLineOrSemicolon(errorMsg.substring(requested));
        }
        int skipped = errorMsg.indexOf("SeaTunnel force stop skipped");
        if (skipped >= 0) {
            return clipAtLineOrSemicolon(errorMsg.substring(skipped));
        }
        return null;
    }

    private static String latestLineContaining(String text, String needle) {
        String latest = null;
        for (String line : text.split("\\R")) {
            if (line.contains(needle)) latest = line;
        }
        return latest;
    }

    private static String valueAfterKey(String text, String key) {
        if (text == null) return null;
        int idx = text.lastIndexOf(key);
        if (idx < 0) return null;
        String tail = text.substring(idx + key.length()).trim();
        if (tail.isEmpty()) return null;
        int end = 0;
        while (end < tail.length()) {
            char c = tail.charAt(end);
            if (Character.isWhitespace(c) || c == ';' || c == ',') break;
            end++;
        }
        return end == 0 ? null : tail.substring(0, end);
    }

    private static String clipAtLineOrSemicolon(String value) {
        if (value == null) return null;
        int lineEnd = value.indexOf('\n');
        int semicolon = value.indexOf(';');
        int end;
        if (lineEnd >= 0 && semicolon >= 0) end = Math.min(lineEnd, semicolon);
        else end = Math.max(lineEnd, semicolon);
        String clipped = end >= 0 ? value.substring(0, end) : value;
        return clipped.length() > 500 ? clipped.substring(0, 500) : clipped.trim();
    }

    private static String normalizeStatus(String status) {
        String normalized = blankToNull(status);
        return normalized == null ? null : normalized.toUpperCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
