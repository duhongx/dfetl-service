package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.ValidationPolicy;
import com.dfygt.dfetl.server.entity.SyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * spec 022：任务执行成功后，按全局策略自动创建并异步执行一条 ValidationRun。
 *
 * <p>触发条件：{@link ValidationPolicy#autoEnabled()} == true
 * 且 {@link ValidationPolicy#trigger()} == "after_sync"。
 *
 * <p>异常一律 swallow + log，避免影响主任务状态。
 *
 * <p>spec validation-table-consolidation · Step 9：
 * 已确认本组件通过 {@link ValidationDispatchService} 间接写入 {@code validation_run}，
 * 无直接引用 {@code ValidationTaskRepository}，RUNNING 状态查询由 Dispatch 层悲观锁保护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoValidationTrigger {

    private final GlobalSettingsService settingsService;
    private final TaskValidationConfigService configService;
    private final ValidationDispatchService dispatchService;

    public void onExecutionSuccess(SyncTask task, Long executionId) {
        onExecutionSuccess(task, executionId, (WatermarkService.WindowContext) null);
    }

    /**
     * Spec 048：带增量窗口的触发入口。
     * 当 {@code config.checksumScope == "WINDOW"} 且 {@code windowStart != null} 时，
     * 将 windowStart/End 记录在 ValidationRun 供 ValidationRunner 传给 ChecksumService。
     */
    public void onExecutionSuccess(SyncTask task, Long executionId,
                                   Instant windowStart, Instant windowEnd) {
        WatermarkService.WindowContext window = windowStart == null && windowEnd == null
                ? null
                : new WatermarkService.WindowContext("INCREMENT", windowStart, windowEnd, null, null);
        onExecutionSuccess(task, executionId, window);
    }

    /**
     * ID_RANGE 需要完整 WindowContext，不能只传 windowStart/windowEnd。
     */
    public void onExecutionSuccess(SyncTask task, Long executionId,
                                   WatermarkService.WindowContext window) {
        try {
            ValidationPolicy policy = settingsService.getValidationPolicy();

            // spec 031：先加载任务级配置；config.enabled=true 时其字段优先生效
            var config = task.getId() != null
                    ? configService.findEntityByTaskId(task.getId()).orElse(null)
                    : null;
            boolean configActive = config != null && Boolean.TRUE.equals(config.getEnabled());

            // 触发开关决策：任务级 autoTrigger 为空时继承全局 autoEnabled
            boolean triggerOn = configActive
                    ? (config.getAutoTrigger() != null ? Boolean.TRUE.equals(config.getAutoTrigger()) : policy.autoEnabled())
                    : policy.autoEnabled();
            if (!triggerOn) return;
            // 触发时机仅按全局（after_sync），任务级暂不引入新维度
            if (!"after_sync".equals(policy.trigger())) return;

            // method 决策：configActive 且 config.method 非空 → 用 config.method（已大写）；否则用 policy.method
            String method = configActive && config.getMethod() != null && !config.getMethod().isBlank()
                    ? config.getMethod().toUpperCase()
                    : toMethod(policy.method());
            method = normalizeSupportedMethod(task, method);

            // spec 048：WINDOW 模式时记录增量窗口
            // 改进：增量任务（windowStart != null）默认按窗口校验，避免与历史全量数据对比导致永远 DIFF
            //   - WINDOW 模式（用户显式配）：照旧
            //   - 任务有 windowStart 且 incrementalField 已配：默认按窗口（ROW_COUNT 与 CHECKSUM 通用）
            boolean explicitWindow = configActive && "WINDOW".equalsIgnoreCase(config.getChecksumScope());
            boolean incrementalWindowAvailable = hasValidationWindow(task, window);
            WatermarkService.WindowContext effectiveWindow;
            boolean checksumMethod = requiresChecksum(method);
            if (explicitWindow) {
                if (window == null || !hasValidationWindow(task, window)) {
                    // 用户配置了"仅本次增量窗口"但当前执行没有增量窗口（如全量同步），跳过触发
                    log.debug("AutoValidationTrigger: skip taskId={} - checksumScope=WINDOW but no incremental window available",
                            task.getId());
                    return;
                }
                effectiveWindow = window;
            } else if (configActive && checksumMethod) {
                // 任务级 checksumScope=FULL 必须表示全量；不能因为执行批次有增量窗口而改写为 WINDOW。
                effectiveWindow = null;
            } else if (incrementalWindowAvailable) {
                effectiveWindow = window;
            } else {
                effectiveWindow = null;
            }

            // 校验回看窗口：基于执行窗口扩展
            // windowStart = min(executionWindowStart, now - lookbackHours)
            // windowEnd 保持 executionWindowEnd，禁止校验/修复本次执行尚未同步的数据
            if (effectiveWindow != null && effectiveWindow.windowStart() != null) {
                Integer taskLookback = configActive ? config.getValidationLookbackHours() : null;
                int lookbackHours = taskLookback != null ? taskLookback : policy.lookbackHours();
                if (lookbackHours > 0) {
                    Instant now = Instant.now();
                    Instant lookbackStart = now.minus(lookbackHours, java.time.temporal.ChronoUnit.HOURS);
                    Instant expandedStart = effectiveWindow.windowStart().isBefore(lookbackStart)
                            ? effectiveWindow.windowStart()
                            : lookbackStart;
                    effectiveWindow = new WatermarkService.WindowContext(
                            effectiveWindow.windowType(),
                            expandedStart,
                            effectiveWindow.windowEnd(),
                            effectiveWindow.windowStartId(),
                            effectiveWindow.windowEndId()
                    );
                }
            }

            dispatchService.dispatchTriggered(task, executionId, "AUTO", method, effectiveWindow, config);
        } catch (Exception e) {
            log.warn("AutoValidationTrigger failed for task={} exec={}: {}",
                    task.getId(), executionId, e.getMessage());
        }
    }

    private boolean hasValidationWindow(SyncTask task, WatermarkService.WindowContext window) {
        if (task == null || window == null) {
            return false;
        }
        if ("CUSTOM_WINDOW".equalsIgnoreCase(window.windowType())) {
            return window.windowStart() != null || window.windowEnd() != null;
        }
        if (!"INCREMENT".equalsIgnoreCase(window.windowType())) {
            return false;
        }
        if (task.getIncrementalField() == null || task.getIncrementalField().isBlank()) {
            return false;
        }
        if ("ID_RANGE".equalsIgnoreCase(task.getIncrementMode())) {
            return window.windowEndId() != null;
        }
        return window.windowStart() != null || window.windowEnd() != null;
    }

    /** 前端策略值（row_count/checksum）映射为 ValidationRun.method 大写枚举 */
    static String toMethod(String policyMethod) {
        if (policyMethod == null || policyMethod.isBlank()) return "ROW_COUNT";
        return switch (policyMethod.toLowerCase()) {
            case "checksum" -> "CHECKSUM";
            case "row_count_checksum", "all" -> "ROW_COUNT_CHECKSUM";
            case "row_count" -> "ROW_COUNT";
            case "sample" -> throw new IllegalArgumentException("SAMPLE 校验未实现，请使用 ROW_COUNT 或 CHECKSUM");
            default -> throw new IllegalArgumentException("Unsupported validation method: " + policyMethod);
        };
    }

    private String normalizeSupportedMethod(SyncTask task, String method) {
        String normalized = method == null || method.isBlank() ? "ROW_COUNT" : method.toUpperCase();
        if (task != null && "CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode())
                && ("CHECKSUM".equals(normalized)
                || "ROW_COUNT_CHECKSUM".equals(normalized)
                || "ALL".equals(normalized))) {
            throw new IllegalArgumentException("CUSTOM_SQL 任务不支持 " + normalized
                    + " 自动校验，请改用 ROW_COUNT 或表/视图源");
        }
        if (task != null && ("CHECKSUM".equals(normalized)
                || "ROW_COUNT_CHECKSUM".equals(normalized)
                || "ALL".equals(normalized))
                && !hasChecksumKey(task)) {
            throw new IllegalArgumentException("任务缺少 splitPk/upsertKeys，不能自动触发 " + normalized
                    + " 校验，请配置比对键或改用 ROW_COUNT");
        }
        return normalized;
    }

    private boolean hasChecksumKey(SyncTask task) {
        if (task == null) {
            return false;
        }
        if (task.getSplitPk() != null && !task.getSplitPk().isBlank()) {
            return true;
        }
        return task.getUpsertKeys() != null && task.getUpsertKeys().stream()
                .anyMatch(key -> key != null && !key.isBlank());
    }

    private boolean requiresChecksum(String method) {
        return "CHECKSUM".equals(method)
                || "ROW_COUNT_CHECKSUM".equals(method)
                || "ALL".equals(method);
    }
}
