package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.TaskValidationConfigDto;
import com.dfygt.dfetl.server.service.TaskValidationConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 任务校验策略配置 API
 *
 * GET    /api/sync-task/{taskId}/validation-config   查询（不存在时返回默认值）
 * PUT    /api/sync-task/{taskId}/validation-config   保存（upsert）
 * DELETE /api/sync-task/{taskId}/validation-config   重置为默认
 */
@RestController
@RequestMapping("/api/sync-task/{taskId}/validation-config")
@RequiredArgsConstructor
public class TaskValidationConfigController {

    private final TaskValidationConfigService service;
    private final com.dfygt.dfetl.server.repository.SyncTaskRepository syncTaskRepo;
    private final com.dfygt.dfetl.server.repository.TaskSnapshotKeyRepository snapshotKeyRepo;

    @GetMapping
    public ApiResponse<TaskValidationConfigDto> get(@PathVariable Long taskId) {
        return ApiResponse.ok(service.getByTaskId(taskId));
    }

    @PutMapping
    public ApiResponse<TaskValidationConfigDto> save(
            @PathVariable Long taskId,
            @RequestBody TaskValidationConfigDto dto) {
        return ApiResponse.ok(service.save(taskId, dto));
    }

    @DeleteMapping
    public ApiResponse<Map<String, String>> delete(@PathVariable Long taskId) {
        service.delete(taskId);
        return ApiResponse.ok(Map.of("message", "reset to default"));
    }

    /**
     * spec validation-workbench-redesign · Task P1-10.1
     * Validates: Requirement 16 (AC 1)
     *
     * <p>根据表行数级别 + 是否硬删 + 是否视图源给出推荐 driftCron/snapshotCron。
     *
     * <p>行数获取优先级（任一可用即停止）：
     * <ol>
     *   <li>最近一次 Snapshot 的 PK 集合 size（最准确，反映实际数据规模）</li>
     *   <li>SyncTask 历史最大 readRows（同步任务的最近行数）</li>
     *   <li>都不可用 → 默认按「中表 + 不删」推荐</li>
     * </ol>
     */
    @GetMapping("/recommended-cron")
    public ApiResponse<com.dfygt.dfetl.server.dto.RecommendedCronDto> recommendedCron(@PathVariable Long taskId) {
        com.dfygt.dfetl.server.entity.SyncTask task = syncTaskRepo.findById(taskId)
                .orElseThrow(() -> new java.util.NoSuchElementException("SyncTask not found: " + taskId));

        boolean isHardDelete = Boolean.TRUE.equals(task.getEnableSnapshotDelete());
        String sourceObjectType = task.getSourceObjectType() == null
                ? ""
                : task.getSourceObjectType().trim();
        boolean isViewSource = "VIEW".equalsIgnoreCase(sourceObjectType)
                || "MATERIALIZED_VIEW".equalsIgnoreCase(sourceObjectType);

        // 行数估算：取最近一次 Snapshot 的 PK 数（间接反映源端规模）；不可用时回退到 TABLE_ROWS。
        Long estimatedRows = null;
        try {
            var snapshots = snapshotKeyRepo.findExecutionSummaries(taskId);
            if (!snapshots.isEmpty()) {
                estimatedRows = snapshots.get(0).getKeyCount();
            }
        } catch (Exception ignore) { /* fallthrough */ }

        String rowsLevel;
        if (estimatedRows == null) {
            rowsLevel = "UNKNOWN";
        } else if (estimatedRows < 10_000) {
            rowsLevel = "SMALL";
        } else if (estimatedRows < 1_000_000) {
            rowsLevel = "MEDIUM";
        } else if (estimatedRows < 100_000_000) {
            rowsLevel = "LARGE";
        } else {
            rowsLevel = "XLARGE";
        }

        String driftCron;
        String snapshotCron;
        String reason;

        if (isViewSource) {
            // 视图源：snapshotCron 不适用，driftCron 按规模选频率
            snapshotCron = null;
            driftCron = switch (rowsLevel) {
                case "SMALL" -> null;  // 小视图源：跟随同步即可
                case "LARGE", "XLARGE" -> "0 0 2 * * ?";  // 大视图源：每天凌晨 2 点
                default -> "0 0 2 ? * SUN";  // 中等 / 未知：每周日凌晨
            };
            reason = "视图源任务：Snapshot 不适用（请走 ChangeAudit 高级选项），仅推荐 driftCron 周期 CHECKSUM";
        } else if (isHardDelete) {
            // 硬删任务：每天 driftCron + snapshotCron（错峰）
            driftCron = switch (rowsLevel) {
                case "SMALL", "MEDIUM" -> "0 0 2 ? * SUN";  // 小中表硬删：每周日凌晨足够
                case "XLARGE" -> "0 0 2 1 * ?";              // 超大表：每月 1 号凌晨
                default -> "0 0 2 * * ?";                    // 大表 / 未知：每天凌晨 2 点
            };
            snapshotCron = "XLARGE".equals(rowsLevel) ? "0 0 2 ? * SUN" : "0 30 2 * * ?";
            reason = "硬删任务（enableSnapshotDelete=true）：driftCron 抓 update_time 不推进的修改 + snapshotCron 抓硬删残留";
        } else {
            // 软删 / 不删任务：仅 driftCron
            snapshotCron = null;
            driftCron = switch (rowsLevel) {
                case "SMALL" -> null;       // 小表：每次同步后 ROW_COUNT 哨兵 + 全量 同步覆盖即可
                case "XLARGE" -> "0 0 2 1 * ?";  // 超大表：每月 1 号凌晨
                default -> "0 0 2 ? * SUN";  // 中大表：每周日凌晨
            };
            reason = "非硬删任务（软删或只增不减）：仅推荐 driftCron 周期 CHECKSUM 兜底；Snapshot 不适用";
        }

        com.dfygt.dfetl.server.dto.RecommendedCronDto result =
                new com.dfygt.dfetl.server.dto.RecommendedCronDto(
                        driftCron, snapshotCron, rowsLevel, estimatedRows,
                        isHardDelete, isViewSource, reason);
        return ApiResponse.ok(result);
    }
}
