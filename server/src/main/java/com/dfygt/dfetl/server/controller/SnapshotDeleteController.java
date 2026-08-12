package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.SnapshotApplyHistoryDto;
import com.dfygt.dfetl.server.dto.SnapshotExecutionDto;
import com.dfygt.dfetl.server.service.SnapshotDeleteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spec 020：源端主键快照与差集删除检测的人工/调度入口。
 *
 * <p>注意：本期不自动接入 engine 执行生命周期，由调用方按需触发。
 */
@RestController
@RequestMapping("/api/sync-task/{taskId}/snapshot")
@RequiredArgsConstructor
public class SnapshotDeleteController {

    private final SnapshotDeleteService snapshotDeleteService;

    /** 抓取一次源端主键快照，返回写入条数。 */
    @PostMapping("/capture")
    public ApiResponse<Integer> capture(@PathVariable Long taskId,
                                        @RequestParam Long executionId) {
        return ApiResponse.ok(snapshotDeleteService.capture(taskId, executionId));
    }

    /** 比对两次执行的快照，返回 prev∖curr 的 key 列表（被删除）。 */
    @GetMapping("/diff")
    public ApiResponse<List<String>> diff(@PathVariable Long taskId,
                                          @RequestParam Long prev,
                                          @RequestParam Long curr) {
        return ApiResponse.ok(snapshotDeleteService.detectAndRecord(taskId, curr, prev));
    }

    /** 返回当前任务已采集的快照执行记录，用于前端选择 prev/curr。 */
    @GetMapping("/executions")
    public ApiResponse<List<SnapshotExecutionDto>> executions(@PathVariable Long taskId) {
        return ApiResponse.ok(snapshotDeleteService.listExecutions(taskId));
    }

    /** 返回最近 Dry-Run / apply 记录。 */
    @GetMapping("/apply-history")
    public ApiResponse<List<SnapshotApplyHistoryDto>> applyHistory(@PathVariable Long taskId) {
        return ApiResponse.ok(snapshotDeleteService.listApplyHistory(taskId));
    }

    /** 导出 prev∖curr 删除 key 差集。 */
    @GetMapping("/diff/export.csv")
    public ResponseEntity<StreamingResponseBody> exportDiffCsv(@PathVariable Long taskId,
                                                               @RequestParam Long prev,
                                                               @RequestParam Long curr) {
        String csv = snapshotDeleteService.exportDiffCsv(taskId, prev, curr);
        String filename = "snapshot_delete_diff_task" + taskId + "_" + prev + "_" + curr + ".csv";
        StreamingResponseBody body = outputStream -> outputStream.write(csv.getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(body);
    }

    /** 仅保留最近 keepLastN 个 execution 的快照，返回删除的快照行数。 */
    @DeleteMapping
    public ApiResponse<Integer> prune(@PathVariable Long taskId,
                                      @RequestParam(defaultValue = "5") Integer keepLastN) {
        return ApiResponse.ok(snapshotDeleteService.prune(taskId, keepLastN));
    }

    /** spec 020.1：把 diff 结果通过 Doris Stream Load merge_type=DELETE 物化删除。 */
    @PostMapping("/apply-deletes")
    public ApiResponse<SnapshotDeleteService.ApplyDeleteResult> applyDeletes(
            @PathVariable Long taskId,
            @RequestParam Long prev,
            @RequestParam Long curr,
            @RequestParam(defaultValue = "false") Boolean dryRun) {
        return ApiResponse.ok(snapshotDeleteService.applyDeletes(taskId, prev, curr, Boolean.TRUE.equals(dryRun)));
    }
}
