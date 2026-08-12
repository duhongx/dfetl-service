package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.ReconcileHandleRequest;
import com.dfygt.dfetl.server.dto.TaskExecutionDto;
import com.dfygt.dfetl.server.dto.TaskStatsDto;
import com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelLifecycleProbe;
import com.dfygt.dfetl.server.entity.TaskChunk;
import com.dfygt.dfetl.server.repository.TaskChunkRepository;
import com.dfygt.dfetl.server.service.TaskExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
public class TaskExecutionController {

    private final TaskExecutionService service;
    private final TaskChunkRepository chunkRepository;
    private final org.springframework.beans.factory.ObjectProvider<SeaTunnelLifecycleProbe> seaTunnelProbe;

    /**
     * 全局执行历史（监控页面）
     * GET /api/executions?page=0&size=20
     */
    @GetMapping
    public ApiResponse<Page<TaskExecutionDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return ApiResponse.ok(service.findAll(status, pageable));
    }

    /**
     * 查询需要人工核对的 SeaTunnel 执行记录。
     * GET /api/executions/reconcile-required?page=0&size=20
     */
    @GetMapping("/reconcile-required")
    public ApiResponse<Page<TaskExecutionDto>> listReconcileRequired(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") String handled) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return ApiResponse.ok(service.findReconcileRequired(handled, pageable));
    }

    /**
     * 按任务查询执行历史（任务详情 → 批次历史）
     * GET /api/executions/task/{taskId}?page=0&size=10
     */
    @GetMapping("/task/{taskId}")
    public ApiResponse<Page<TaskExecutionDto>> listByTask(
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return ApiResponse.ok(service.findByTask(taskId, pageable));
    }

    /**
     * 批次历史（BatchDetailPage）
     * GET /api/executions/{taskId}/batches?page=0&size=20
     */
    @GetMapping("/{taskId}/batches")
    public ApiResponse<Page<TaskExecutionDto>> listBatches(
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return ApiResponse.ok(service.findByTask(taskId, pageable));
    }

    /**
     * 执行详情
     * GET /api/executions/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<TaskExecutionDto> get(@PathVariable Long id) {
        return ApiResponse.ok(service.findById(id));
    }

    /**
     * 查询单次执行的人工核对详情。
     * GET /api/executions/{id}/reconcile
     */
    @GetMapping("/{id}/reconcile")
    public ApiResponse<TaskExecutionDto> getReconcileDetail(@PathVariable Long id) {
        return ApiResponse.ok(service.findById(id));
    }

    /**
     * 手动重新探测 SeaTunnel job 状态。
     * POST /api/executions/{id}/reconcile/probe
     *
     * <p>只记录探测结果，不提交 watermark，不把 SUCCESS 直接视为平台成功。
     */
    @PostMapping("/{id}/reconcile/probe")
    public ApiResponse<TaskExecutionDto> probeReconcile(@PathVariable Long id) {
        return ApiResponse.ok(service.probeReconcile(id));
    }

    /**
     * 人工标记 RECONCILE_REQUIRED 已处理。
     * POST /api/executions/{id}/reconcile/mark-handled
     *
     * <p>只关闭人工待办，不提交 watermark，不改为 SUCCESS。
     */
    @PostMapping("/{id}/reconcile/mark-handled")
    public ApiResponse<TaskExecutionDto> markReconcileHandled(
            @PathVariable Long id,
            @RequestBody ReconcileHandleRequest request) {
        String note = request == null ? null : request.getNote();
        return ApiResponse.ok(service.markReconcileHandled(id, note, currentOperator()));
    }

    /**
     * 重新打开 RECONCILE_REQUIRED 人工待办。
     * POST /api/executions/{id}/reconcile/reopen
     */
    @PostMapping("/{id}/reconcile/reopen")
    public ApiResponse<TaskExecutionDto> reopenReconcile(
            @PathVariable Long id,
            @RequestBody(required = false) ReconcileHandleRequest request) {
        String note = request == null ? null : request.getNote();
        return ApiResponse.ok(service.reopenReconcile(id, note, currentOperator()));
    }

    /**
     * 任务执行统计（任务详情页顶部卡片）
     * GET /api/executions/task/{taskId}/stats
     */
    @GetMapping("/task/{taskId}/stats")
    public ApiResponse<TaskStatsDto> stats(@PathVariable Long taskId) {
        return ApiResponse.ok(service.getTaskStats(taskId));
    }

    /**
     * 取消执行
     * POST /api/executions/{id}/cancel
     */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        service.cancel(id);
        return ApiResponse.ok();
    }

    /**
     * 查询某次执行的所有分片列表
     * GET /api/executions/{id}/chunks
     */
    @GetMapping("/{id}/chunks")
    public ApiResponse<List<TaskChunk>> listChunks(@PathVariable Long id) {
        return ApiResponse.ok(chunkRepository.findByExecutionIdOrderByChunkNoAsc(id));
    }

    /**
     * 查询某次执行指定分片号的分片详情
     * GET /api/executions/{id}/chunks/{chunkNo}
     */
    @GetMapping("/{id}/chunks/{chunkNo}")
    public ApiResponse<TaskChunk> getChunk(
            @PathVariable Long id,
            @PathVariable Integer chunkNo) {
        return chunkRepository.findByExecutionIdOrderByChunkNoAsc(id).stream()
                .filter(c -> chunkNo.equals(c.getChunkNo()))
                .findFirst()
                .map(ApiResponse::ok)
                .orElseThrow(() -> new NoSuchElementException(
                        "Chunk #" + chunkNo + " not found in execution " + id));
    }

    /**
     * 执行日志流（SSE）
     * GET /api/executions/{id}/log/stream
     * 已完成的执行：返回存储的错误信息（FAILED）或完成摘要（SUCCESS）
     * 运行中的执行：返回提示信息（完整实时流需 /api/task/{taskId}/logs）
     */
    @GetMapping(value = "/{id}/log/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLog(@PathVariable Long id) {
        SseEmitter emitter = new SseEmitter(30_000L);
        TaskExecutionDto exec = service.findById(id);
        CompletableFuture.runAsync(() -> {
            try {
                String status = exec.getStatus();
                if ("RUNNING".equals(status) || "PENDING".equals(status)) {
                    emitter.send(SseEmitter.event()
                            .data("[execution " + id + " is " + status + "; use /api/task/" + exec.getTaskId() + "/logs for live stream]"));
                } else if (("FAILED".equals(status) || "RECONCILE_REQUIRED".equals(status))
                        && exec.getErrorMsg() != null) {
                    for (String line : exec.getErrorMsg().split("\n")) {
                        emitter.send(SseEmitter.event().data(line));
                    }
                    if ("RECONCILE_REQUIRED".equals(status)) {
                        emitter.send(SseEmitter.event().data(
                                "[RECONCILE_REQUIRED] SeaTunnel 终态未确认；watermark 未提交，请按 runbook 人工核对。"));
                    }
                } else {
                    emitter.send(SseEmitter.event()
                            .data("[" + status + "] readRows=" + exec.getReadRows()
                                    + " writeRows=" + exec.getWriteRows()
                                    + " durationMs=" + exec.getDurationMs()));
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * SeaTunnel 进度流（SSE，spec 015c）。
     * GET /api/executions/{id}/seatunnel/stream
     * <p>需要 task.executor_type=SEATUNNEL_* 且 task_execution.engine_job_id 已捕获。
     * 若 SeaTunnel 未启用（probe bean 不存在）→ 返回 404。
     */
    @GetMapping(value = "/{id}/seatunnel/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSeaTunnelProgress(@PathVariable Long id) {
        SseEmitter emitter = new SseEmitter(0L);            // 0 = no timeout (probe controls)
        SeaTunnelLifecycleProbe probe = seaTunnelProbe.getIfAvailable();
        if (probe == null) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("SeaTunnel executor is not enabled (dfetl.executor.seatunnel.enabled=false)"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        TaskExecutionDto exec = service.findById(id);
        String jobId = exec.getEngineJobId();
        if (jobId == null || jobId.isBlank()) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("execution has no engineJobId yet (executor=" + exec.getExecutorType() + ")"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        if ("RECONCILE_REQUIRED".equals(exec.getStatus())) {
            try {
                emitter.send(SseEmitter.event().name("reconcile_required")
                        .data("execution requires manual reconcile; use /api/executions/" + id + "/reconcile/probe for one-shot status probe"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }
        probe.streamProgress(id, jobId, emitter);
        return emitter;
    }

    private String currentOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return "operator";
        }
        return authentication.getName();
    }
}
