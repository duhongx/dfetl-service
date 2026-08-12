package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.DashboardStatsDto;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private static final String RECONCILE_REQUIRED = "RECONCILE_REQUIRED";

    private final SourceDataSourceRepository sourceRepo;
    private final TargetDataSourceRepository targetRepo;
    private final SyncTaskRepository taskRepo;
    private final TaskExecutionRepository execRepo;

    @GetMapping("/stats")
    public ApiResponse<DashboardStatsDto> stats() {
        // ── 数据源统计 ───────────────────────────────────────────────────────
        long sourceCount = sourceRepo.count();
        long targetCount = targetRepo.count();

        // ── 任务统计（按 status 分组）──────────────────────────────────────────
        List<SyncTask> allTasks = taskRepo.findAll();
        long taskTotal   = allTasks.size();
        long taskEnabled = allTasks.stream().filter(t -> "ENABLED".equals(t.getStatus())).count();
        long taskDisabled = allTasks.stream().filter(t -> "DISABLED".equals(t.getStatus())).count();
        long taskFailed  = allTasks.stream().filter(t -> "FAILED".equals(t.getStatus())).count();

        // 当前 RUNNING 的执行记录
        long execRunning = execRepo.countByStatus("RUNNING");
        long execReconcileRequired = execRepo.countByStatus(RECONCILE_REQUIRED);
        long reconcileRequiredHandled = execRepo.countByStatusAndReconcileHandled(RECONCILE_REQUIRED, true);
        long reconcileRequiredUnhandled = execRepo.countByStatusAndReconcileHandled(RECONCILE_REQUIRED, false);

        // ── 今日执行统计 ──────────────────────────────────────────────────────
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long execTodayTotal   = execRepo.countByCreatedAtAfter(todayStart);
        long execTodaySuccess = execRepo.countByCreatedAtAfterAndStatus(todayStart, "SUCCESS");
        long execTodayFailed  = execRepo.countByCreatedAtAfterAndStatus(todayStart, "FAILED");
        long execTodayReconcileRequired = execRepo.countByCreatedAtAfterAndStatus(todayStart, RECONCILE_REQUIRED);

        // ── 最近 8 条执行记录（含任务名） ─────────────────────────────────────
        List<TaskExecution> recentList = execRepo.findAll(
                PageRequest.of(0, 8, Sort.by("id").descending())
        ).getContent();

        // 批量查询涉及的 task names
        List<Long> taskIds = recentList.stream().map(TaskExecution::getTaskId).distinct().toList();
        Map<Long, String> taskNameMap = taskRepo.findAllById(taskIds).stream()
                .collect(Collectors.toMap(SyncTask::getId, SyncTask::getName));

        List<DashboardStatsDto.RecentExecItem> recentExecutions = recentList.stream()
                .map(e -> DashboardStatsDto.RecentExecItem.builder()
                        .id(e.getId())
                        .taskId(e.getTaskId())
                        .taskName(taskNameMap.getOrDefault(e.getTaskId(), "—"))
                        .status(e.getStatus())
                        .batchNo(e.getBatchNo())
                        .startedAt(e.getStartedAt() != null ? e.getStartedAt().toString() : null)
                        .durationMs(e.getDurationMs())
                        .readRows(e.getReadRows())
                        .writeRows(e.getWriteRows())
                        .build())
                .toList();

        DashboardStatsDto dto = DashboardStatsDto.builder()
                .sourceCount(sourceCount)
                .targetCount(targetCount)
                .taskTotal(taskTotal)
                .taskEnabled(taskEnabled)
                .taskDisabled(taskDisabled)
                .taskFailed(taskFailed)
                .execRunning(execRunning)
                .execReconcileRequired(execReconcileRequired)
                .reconcileRequiredTotal(execReconcileRequired)
                .reconcileRequiredUnhandled(reconcileRequiredUnhandled)
                .reconcileRequiredHandled(reconcileRequiredHandled)
                .execTodayTotal(execTodayTotal)
                .execTodaySuccess(execTodaySuccess)
                .execTodayFailed(execTodayFailed)
                .execTodayReconcileRequired(execTodayReconcileRequired)
                .recentExecutions(recentExecutions)
                .build();

        return ApiResponse.ok(dto);
    }
}
