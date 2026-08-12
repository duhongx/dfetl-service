package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.ValidationGoalSummaryDto;
import com.dfygt.dfetl.server.dto.ValidationRunDto;
import com.dfygt.dfetl.server.entity.EtlVerifyDiff;
import com.dfygt.dfetl.server.service.DiffFieldCsvExportService;
import com.dfygt.dfetl.server.service.RepairService;
import com.dfygt.dfetl.server.service.TaskValidationRunService;
import com.dfygt.dfetl.server.service.ValidationGoalSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/sync-task/{taskId}/validation")
@RequiredArgsConstructor
public class TaskValidationController {

    private final TaskValidationRunService service;
    private final DiffFieldCsvExportService diffFieldCsvExportService;
    private final ValidationGoalSummaryService validationGoalSummaryService;

    @GetMapping("/summary")
    public ApiResponse<ValidationGoalSummaryDto> summary(@PathVariable Long taskId,
                                                         @RequestParam(defaultValue = "FULL") String goal) {
        return ApiResponse.ok(validationGoalSummaryService.getSummary(taskId, goal));
    }

    @GetMapping("/runs")
    public ApiResponse<List<ValidationRunDto>> listRuns(@PathVariable Long taskId) {
        return ApiResponse.ok(service.listRuns(taskId));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<ValidationRunDto> getRunDetail(@PathVariable Long taskId,
                                                      @PathVariable Long runId) {
        return ApiResponse.ok(service.getRunDetail(taskId, runId));
    }

    @GetMapping("/runs/{runId}/diffs")
    public ApiResponse<Page<EtlVerifyDiff>> listRunDiffs(@PathVariable Long taskId,
                                                         @PathVariable Long runId,
                                                         @RequestParam(required = false) String diffType,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.listRunDiffs(taskId, runId, diffType, PageRequest.of(page, size)));
    }

    @PostMapping("/runs/{runId}/repair")
    public ApiResponse<RepairService.RepairReport> repairRun(@PathVariable Long taskId,
                                                             @PathVariable Long runId,
                                                             @RequestParam(defaultValue = "false") boolean forceDelete,
                                                             @RequestParam(defaultValue = "false") boolean dryRun,
                                                             @RequestParam(required = false) Integer maxRows) {
        return ApiResponse.ok(service.repairRun(taskId, runId, forceDelete, dryRun, maxRows));
    }

    @GetMapping("/runs/{runId}/diff/fields/export.csv")
    public ResponseEntity<StreamingResponseBody> exportRunDiffFieldsCsv(@PathVariable Long taskId,
                                                                        @PathVariable Long runId) {
        return diffFieldCsvExportService.exportByRunId(taskId, runId);
    }

    /** 获取某次校验的分片详情（用于工作台展示校验过程） */
    @GetMapping("/runs/{runId}/chunks")
    public ApiResponse<List<Map<String, Object>>> listRunChunks(@PathVariable Long taskId,
                                                                 @PathVariable Long runId,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(service.listRunChunks(taskId, runId,
                pageRequest(page, size, Sort.by(Sort.Direction.ASC, "chunkNo"))));
    }

    private PageRequest pageRequest(int page, int size, Sort sort) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        return PageRequest.of(safePage, safeSize, sort);
    }

    /**
     * spec validation-workbench-redesign · Task P1-3.1
     * Validates: Requirement 2 (AC 1, 2, 3) + Correctness Property 2
     *
     * <p>差异分类计数：把 5 种 diffType 折叠为「目标库少了行 / 字段值对不上 / 目标库多了行」3 类。
     *
     * <p>响应：HTTP 200 + JSON {@code {INSERT_MISSING_COUNT, UPDATE_DIFF_COUNT,
     * DELETE_MISSING_COUNT, TOTAL}}（4 个非负整数 + 加和守恒）。
     *
     * <p>错误：taskId 不存在 → HTTP 404 / TASK_NOT_FOUND；
     * runId 不存在或不归属 taskId → HTTP 404 / RUN_NOT_FOUND（由全局 @ControllerAdvice 统一转换）。
     */
    @GetMapping("/runs/{runId}/diff-summary")
    public ApiResponse<com.dfygt.dfetl.server.dto.ValidationRunDiffSummaryDto> diffSummary(
            @PathVariable Long taskId,
            @PathVariable Long runId) {
        return ApiResponse.ok(validationGoalSummaryService.getDiffSummary(taskId, runId));
    }

    /**
     * spec validation-workbench-redesign Phase 2：历史比对（diff-of-diffs）。
     *
     * <p>对比当前 runId 与 compareTo runId 的差异行变化：哪些是新增的、哪些已修复、哪些不变。
     *
     * @param runId     当前 run（通常是最新一次校验）
     * @param compareTo 对比 run（通常是上一次校验）
     */
    @GetMapping("/runs/{runId}/diff-changes")
    public ApiResponse<com.dfygt.dfetl.server.dto.DiffChangesDto> diffChanges(
            @PathVariable Long taskId,
            @PathVariable Long runId,
            @RequestParam Long compareTo) {
        return ApiResponse.ok(validationGoalSummaryService.getDiffChanges(taskId, runId, compareTo));
    }
}
