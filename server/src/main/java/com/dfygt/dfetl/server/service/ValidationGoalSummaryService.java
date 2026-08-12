package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.ValidationGoalSummaryDto;
import com.dfygt.dfetl.server.entity.EtlVerifyChunk;
import com.dfygt.dfetl.server.entity.EtlVerifyDiff;
import com.dfygt.dfetl.server.entity.SnapshotApplyHistory;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.EtlVerifyChunkRepository;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffRepository;
import com.dfygt.dfetl.server.repository.SnapshotApplyHistoryRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TaskSnapshotKeyRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import com.dfygt.dfetl.server.repository.TaskValidationConfigRepository;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 校验目标摘要服务（FULL / UPDATE / DELETE）。
 *
 * <p>spec validation-workbench-redesign Phase 1: deleteSummary 改为读 {@code snapshot_apply_history}
 * 最近一条记录，不再实时跑差集查询，避免大快照（10 万级）+ 并发写事务时把 HikariCP 连接池占满。
 * 实时差集留给「立即检测」按钮（snapshotApi.applyDeletes dryRun）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationGoalSummaryService {

    private final SyncTaskRepository syncTaskRepository;
    private final TaskValidationConfigRepository configRepository;
    private final ValidationRunRepository validationRunRepository;
    private final TaskExecutionRepository executionRepository;
    private final EtlVerifyChunkRepository chunkRepository;
    private final EtlVerifyDiffRepository diffRepository;
    private final TaskSnapshotKeyRepository snapshotKeyRepository;
    private final SnapshotApplyHistoryRepository applyHistoryRepository;
    private final EffectiveValidationMethodResolver effectiveValidationMethodResolver;

    /** spec validation-workbench-redesign · Requirement 1 AC 7：diffRows 统计失败的兜底错误码。 */
    static final String ERR_DIFF_COUNT_FAILED = "DIFF_COUNT_FAILED";

    /** diff-of-diffs 最大 JVM PK 扫描行数；超过后返回 count-only 截断响应，避免大表差异打爆内存。 */
    static final long DIFF_CHANGES_PK_SCAN_LIMIT = 10_000L;

    /**
     * 工作台目标摘要入口。
     *
     * <p>spec validation-workbench-redesign · Requirement 1 AC 8：使用 {@code READ_COMMITTED}
     * 只读事务，保证本次响应中的 diffRows 与未来分类聚合查询基于同一一致性快照，避免与
     * Repair 流程并发写入产生「diffRows ≠ 分类计数加和」。
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = 30)
    public ValidationGoalSummaryDto getSummary(Long taskId, String goal) {
        String normalizedGoal = normalizeGoal(goal);
        if ("DELETE".equals(normalizedGoal)) {
            return deleteSummary(taskId);
        }
        return validationRunSummary(taskId, normalizedGoal);
    }

    /**
     * spec validation-workbench-redesign · Task P1-3.1
     * Validates: Requirement 2 (AC 1, 2, 3) + Correctness Property 2
     *
     * <p>差异分类计数：把 5 种 diffType 折叠为 3 类业务故事，并保证加和守恒。
     *
     * @param taskId 同步任务 id（用于 P1-3.1 端点的归属校验，不直接参与查询）
     * @param runId  validation_run.id
     * @return 4 个非负整数计数 + 加和守恒
     * @throws java.util.NoSuchElementException 如果 runId 在 validation_run 中不存在或不归属 taskId
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = 30)
    public com.dfygt.dfetl.server.dto.ValidationRunDiffSummaryDto getDiffSummary(Long taskId, Long runId) {
        // 归属校验：runId 必须存在且 taskId 与之匹配
        ValidationRun run = validationRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("ValidationRun not found: " + runId));
        if (!java.util.Objects.equals(run.getTaskId(), taskId)) {
            throw new NoSuchElementException("ValidationRun " + runId + " does not belong to task " + taskId);
        }

        // 同一只读事务内执行分组聚合；折叠 5 种 diffType 为 3 类业务故事
        long insertMissing = 0;
        long updateDiff = 0;
        long deleteMissing = 0;

        for (Object[] row : diffRepository.countGroupByDiffTypeForRun(runId)) {
            String type = (String) row[0];
            Long count = (Long) row[1];
            if (type == null || count == null) continue;
            switch (type) {
                case "INSERT_MISSING", "ROW_AUDIT_MISSING" -> insertMissing += count;
                case "UPDATE_DIFF", "ROW_AUDIT_MISMATCH" -> updateDiff += count;
                case "DELETE_MISSING" -> deleteMissing += count;
                default -> log.debug("getDiffSummary: unknown diffType={} runId={}", type, runId);
            }
        }
        long total = insertMissing + updateDiff + deleteMissing;
        return new com.dfygt.dfetl.server.dto.ValidationRunDiffSummaryDto(
                insertMissing, updateDiff, deleteMissing, total);
    }

    private ValidationGoalSummaryDto validationRunSummary(Long taskId, String goal) {
        Optional<TaskValidationConfig> configOpt = configRepository.findByTaskId(taskId);
        TaskValidationConfig config = configOpt.orElse(null);
        boolean taskConfigured = config != null;
        boolean effectiveEnabled = (config != null && Boolean.TRUE.equals(config.getEnabled()))
                || effectiveValidationMethodResolver.resolveEffectiveEnabled(config);
        if (!effectiveEnabled) {
            return base(goal, taskConfigured, false,
                    taskConfigured ? "DISABLED" : "NOT_CONFIGURED",
                    taskConfigured
                            ? "%s规则已禁用".formatted(goalLabel(goal))
                            : "%s规则未配置".formatted(goalLabel(goal)),
                    taskConfigured
                            ? "需要校验时先启用规则。"
                            : "先配置任务规则或开启全局自动校验。");
        }

        Optional<ValidationRun> runOpt = validationRunRepository.findByTaskIdOrderByIdDesc(taskId).stream()
                .filter(run -> runMatchesGoal(run, goal))
                .findFirst();
        if (runOpt.isEmpty()) {
            ValidationGoalSummaryDto dto = base(goal, taskConfigured, true, "NOT_RUN",
                    "%s还没有执行记录".formatted(goalLabel(goal)),
                    "FULL".equals(goal)
                            ? "先执行一次全量校验，确认全量同步后的数据是否一致。"
                            : "选择时间窗口执行修改校验，确认后续修改是否同步。");
            dto.setCanRerun(true);
            return dto;
        }

        ValidationRun run = runOpt.get();
        ValidationGoalSummaryDto statusSummary = summaryForNonTerminalRun(
                taskId, goal, run, taskConfigured);
        if (statusSummary != null) {
            return statusSummary;
        }

        // spec validation-workbench-redesign · Requirement 1 AC 1/4/7：
        // diffRows 单一权威源 = etl_verify_diff.count(validation_run_id == runId)。
        // 不再依赖 validation_run.diffRows 与 etl_verify_chunk.matched 双口径，避免与差异明细打架。
        long diffRows;
        try {
            diffRows = diffRepository.countByValidationRunId(run.getId());
            // ROW_COUNT 模式不产生 etl_verify_diff 记录，回退到 validation_run.diffRows
            if (diffRows == 0 && run.getDiffRows() != null && run.getDiffRows() > 0) {
                diffRows = run.getDiffRows();
            }
        } catch (RuntimeException ex) {
            // 包括 QueryTimeoutException（5000ms 超时）、JDBC 连接异常、SQL 解析异常等。
            // 不得用 0 静默掩盖：返回带 errorCode 的失败响应，由前端在摘要卡片局部展示「统计失败，可重试」。
            log.warn("validationRunSummary diff count failed taskId={} runId={}: {}",
                    taskId, run.getId(), ex.toString());
            ValidationGoalSummaryDto failed = base(goal, taskConfigured, true, "FAILED",
                    "%s摘要统计失败".formatted(goalLabel(goal)),
                    "差异行数读取失败，可点击重试；若持续失败请联系运维查看后端日志。");
            failed.setErrorCode(ERR_DIFF_COUNT_FAILED);
            failed.setLastRunId(run.getId());
            failed.setLastRunAt(coalesce(run.getUpdatedAt(), run.getCreatedAt()));
            failed.setDiffRows(null);
            failed.setCanRerun(true);
            applyMedicalDiversionSummary(run, failed);
            return failed;
        }

        // 行数（sourceRows / targetRows）继续从分片汇总，仅作展示用，不参与差异判定。
        List<EtlVerifyChunk> chunks = chunkRepository.findByValidationRunIdOrderByChunkNoAsc(run.getId());
        String terminalStatus = normalize(run.getStatus());
        boolean hasDiff = "DIFF".equals(terminalStatus) || diffRows > 0;

        ValidationGoalSummaryDto dto = base(goal, taskConfigured, true,
                hasDiff ? "DIFF" : "SUCCESS",
                hasDiff
                        ? "%s发现差异".formatted(goalLabel(goal))
                        : "%s数据一致".formatted(goalLabel(goal)),
                hasDiff
                        ? "先查看差异明细，建议先预演修复，确认影响范围后再执行确认修复。"
                        : "无需处理，按配置频率继续校验即可。");
        dto.setLastRunId(run.getId());
        dto.setLastRunAt(coalesce(run.getUpdatedAt(), run.getCreatedAt()));
        Long sourceRows = sumSourceRows(chunks);
        Long targetRows = sumTargetRows(chunks);
        dto.setSourceRows(sourceRows != null ? sourceRows : run.getSourceRows());
        dto.setTargetRows(targetRows != null ? targetRows : run.getTargetRows());
        applyMedicalDiversionSummary(run, dto);
        dto.setDiffRows(diffRows);
        dto.setCanRerun(true);
        // P2-1：区分"原始差异总数"和"待修复差异数"
        long pendingDiffRows = diffRepository.countByValidationRunIdAndRepairStatus(run.getId(), "PENDING");
        dto.setPendingDiffRows(pendingDiffRows);
        long actionableRepairRows = countActionableRepairRows(run.getId());
        dto.setCanRepair(actionableRepairRows > 0);
        dto.setCanExport(hasDiff);
        if (pendingDiffRows > 0 && actionableRepairRows == 0) {
            dto.setNextAction("当前待处理差异均为目标库多行，普通修复会跳过；请切换到数据删除校验，走快照删除检测或专家确认流程。");
        }
        return dto;
    }

    private long countActionableRepairRows(Long runId) {
        long actionable = 0L;
        for (Object[] row : diffRepository.countGroupByDiffTypeAndRepairStatusForRun(runId, "PENDING")) {
            String type = (String) row[0];
            Long count = (Long) row[1];
            if (type == null || count == null) {
                continue;
            }
            switch (type) {
                case EtlVerifyDiff.TYPE_INSERT_MISSING,
                     EtlVerifyDiff.TYPE_UPDATE_DIFF,
                     EtlVerifyDiff.TYPE_ROW_AUDIT_MISSING,
                     EtlVerifyDiff.TYPE_ROW_AUDIT_MISMATCH -> actionable += count;
                default -> {
                    // DELETE_MISSING 由 Snapshot 删除检测链路处理，普通 Repair 默认跳过。
                }
            }
        }
        return actionable;
    }

    private ValidationGoalSummaryDto summaryForNonTerminalRun(
            Long taskId, String goal, ValidationRun run, boolean taskConfigured) {
        String status = normalize(run.getStatus());
        if (status.isBlank() || "CONSISTENT".equals(status) || "DIFF".equals(status)) {
            return null;
        }
        String summaryStatus = switch (status) {
            case "RUNNING" -> "RUNNING";
            case "PENDING" -> "PENDING";
            case "ERROR", "FAILED" -> "FAILED";
            default -> null;
        };
        if (summaryStatus == null) {
            return null;
        }
        String message = switch (summaryStatus) {
            case "RUNNING" -> "%s正在执行".formatted(goalLabel(goal));
            case "PENDING" -> "%s等待执行".formatted(goalLabel(goal));
            default -> "%s执行失败".formatted(goalLabel(goal));
        };
        String nextAction = switch (summaryStatus) {
            case "RUNNING" -> "等待本次校验完成后刷新结果。";
            case "PENDING" -> "等待校验调度执行；如果长时间未开始，请查看执行历史。";
            default -> "查看执行历史中的错误信息，修复后重新发起校验。";
        };
        ValidationGoalSummaryDto dto = base(
                goal, taskConfigured, true, summaryStatus, message, nextAction);
        dto.setLastRunId(run.getId());
        dto.setLastRunAt(coalesce(run.getUpdatedAt(), run.getCreatedAt()));
        dto.setSourceRows(run.getSourceRows());
        dto.setTargetRows(run.getTargetRows());
        applyMedicalDiversionSummary(run, dto);
        dto.setDiffRows(run.getDiffRows());
        dto.setCanRerun(!"RUNNING".equals(summaryStatus));
        dto.setCanRepair(false);
        dto.setCanExport(false);
        if ("FAILED".equals(summaryStatus)) {
            dto.setErrorCode("VALIDATION_RUN_ERROR");
        }
        return dto;
    }

    private void applyMedicalDiversionSummary(ValidationRun run, ValidationGoalSummaryDto dto) {
        if (run == null || run.getExecutionId() == null || dto == null) {
            return;
        }
        TaskExecution execution = executionRepository.findById(run.getExecutionId()).orElse(null);
        if (execution == null) {
            return;
        }
        dto.setSourceRowsTotal(execution.getSourceRowsTotal());
        dto.setValidSourceRows(execution.getValidSourceRows());
        dto.setExcludedRows(execution.getExcludedRows());
        dto.setWarningRows(execution.getWarningRows());
    }

    /**
     * DELETE 目标摘要：读 snapshot_apply_history 最近一条，不再实时跑 countDeletedKeys。
     *
     * <p>历史记录的 detectedKeys 字段就是当时计算出的差集大小；如果用户想看实时差集，
     * 应在前端点「立即检测」按钮（走 snapshotApi.applyDeletes dryRun=true，
     * 该接口用 SnapshotDeleteService.detect()，已加 task 级 synchronized 锁，不会和摘要并发）。
     */
    private ValidationGoalSummaryDto deleteSummary(Long taskId) {
        SyncTask task = syncTaskRepository.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + taskId));
        boolean snapshotConfigured = Boolean.TRUE.equals(task.getEnableSnapshotDelete());
        boolean softDeleteConfigured = StringUtils.hasText(task.getSoftDeleteField());
        boolean configured = snapshotConfigured || softDeleteConfigured;
        if (!configured) {
            return base("DELETE", false, false, "NOT_CONFIGURED",
                    "数据删除校验规则未配置",
                    "先到同步任务详情的校验配置页配置删除校验规则。");
        }

        // 用快照表的轻查询拿"最近一次 capture 时间"（idx_tsk_task_exec 走索引，毫秒级）
        LocalDateTime lastCaptureAt;
        try {
            lastCaptureAt = snapshotKeyRepository.findMaxCapturedAt(taskId);
        } catch (RuntimeException e) {
            log.warn("deleteSummary findMaxCapturedAt failed taskId={}: {}", taskId, e.getMessage());
            lastCaptureAt = null;
        }

        // 读最近一条 apply 历史作为摘要数据；这条记录是 SnapshotDeleteService.applyDeletes/dry-run 的产物
        Optional<SnapshotApplyHistory> lastHistOpt = applyHistoryRepository.findFirstByTaskIdOrderByCreatedAtDesc(taskId);

        String status;
        String message;
        String nextAction;
        Long deletedKeys = null;
        LocalDateTime lastDiffAt = null;
        String lastApplyStatus = null;
        String lastApplyError = null;

        if (lastHistOpt.isEmpty()) {
            status = "NOT_RUN";
            message = "删除检测还没有执行记录";
            nextAction = "点击「立即检测」执行一次 Dry-Run，确认源端是否有删除差集。";
        } else {
            SnapshotApplyHistory hist = lastHistOpt.get();
            deletedKeys = (long) hist.getDetectedKeys();
            lastDiffAt = hist.getCreatedAt();
            lastApplyStatus = hist.getResult();
            lastApplyError = hist.getMessage();
            String resultUpper = hist.getResult() == null ? "" : hist.getResult().toUpperCase(Locale.ROOT);
            if ("FAILED".equals(resultUpper)) {
                status = "FAILED";
                message = "上次删除检测/应用失败：" + (hist.getMessage() == null ? "未知错误" : hist.getMessage());
                nextAction = "查看历史详情排查失败原因，再点击「立即检测」重试。";
            } else if ("FUSED".equals(resultUpper)) {
                status = "FUSED";
                message = "上次删除检测已熔断：" + (hist.getMessage() == null ? "删除比例超过阈值" : hist.getMessage());
                nextAction = "请人工核对删除差集，确认源端批量删除原因后再走专家处理流程。";
            } else if ("OK".equals(resultUpper) && !hist.isDryRun() && deletedKeys > 0) {
                status = "APPLIED_NEEDS_RECHECK";
                message = "上次已 apply %d 个源端已删除 key".formatted(deletedKeys);
                nextAction = "已 apply。点击「立即检测」可再次确认当前差集。";
            } else if (deletedKeys > 0) {
                status = "DIFF";
                message = "上次检测发现 %d 个源端已删除 key".formatted(deletedKeys);
                nextAction = hist.isDryRun()
                        ? "上次为 Dry-Run 试跑，确认无误后正式 apply 删除。"
                        : "已 apply。点击「立即检测」可再次确认当前差集。";
            } else {
                status = "SUCCESS";
                message = "上次检测未发现源端删除差集";
                nextAction = "无需处理；点击「立即检测」可重新计算最新差集。";
            }
        }

        ValidationGoalSummaryDto dto = base("DELETE", true, true, status, message, nextAction);
        dto.setSnapshotCaptureEnabled(Boolean.TRUE.equals(task.getSnapshotAutoCapture()));
        dto.setLastSnapshotCaptureAt(lastCaptureAt);
        dto.setLastSnapshotDiffAt(lastDiffAt);
        dto.setDeletedKeys(deletedKeys);
        dto.setApplyEnabled(Boolean.TRUE.equals(task.getSnapshotAutoApply()));
        dto.setLastApplyStatus(lastApplyStatus);
        dto.setLastApplyError(lastApplyError);
        dto.setCanRerun(true);
        dto.setCanRepair(false);
        dto.setCanExport(false);
        return dto;
    }

    private boolean runMatchesGoal(ValidationRun run, String goal) {
        String mode = normalize(run.getMode());
        String scope = normalize(run.getScope());
        String windowType = normalize(run.getWindowType());
        boolean initialFullWindow = "FULL".equals(windowType)
                || "FULL_THEN_INCREMENT".equals(windowType);
        if ("FULL".equals(goal)) {
            return ("FULL".equals(scope) || initialFullWindow)
                    && !mode.contains("SNAPSHOT") && !mode.contains("DELETE");
        }
        // UPDATE 目标：匹配所有非 FULL 的增量校验
        return !"FULL".equals(scope) && !initialFullWindow
                && !mode.contains("SNAPSHOT") && !mode.contains("DELETE");
    }

    private Long sumSourceRows(List<EtlVerifyChunk> chunks) {
        if (chunks.isEmpty()) return null;
        List<EtlVerifyChunk> withSource = chunks.stream()
                .filter(chunk -> chunk.getSourceCount() != null)
                .toList();
        if (withSource.isEmpty()) return null;
        return withSource.stream().mapToLong(EtlVerifyChunk::getSourceCount).sum();
    }

    private Long sumTargetRows(List<EtlVerifyChunk> chunks) {
        if (chunks.isEmpty()) return null;
        List<EtlVerifyChunk> withTarget = chunks.stream()
                .filter(chunk -> chunk.getTargetCount() != null)
                .toList();
        if (withTarget.isEmpty()) return null;
        return withTarget.stream().mapToLong(EtlVerifyChunk::getTargetCount).sum();
    }

    private ValidationGoalSummaryDto base(String goal,
                                          boolean configured,
                                          boolean enabled,
                                          String status,
                                          String message,
                                          String nextAction) {
        ValidationGoalSummaryDto dto = new ValidationGoalSummaryDto();
        dto.setGoal(goal);
        dto.setConfigured(configured);
        dto.setEnabled(enabled);
        dto.setStatus(status);
        dto.setDiffRows(0L);
        dto.setMessage(message);
        dto.setNextAction(nextAction);
        return dto;
    }

    private String normalizeGoal(String goal) {
        String normalized = normalize(goal);
        if ("UPDATE".equals(normalized) || "DELETE".equals(normalized)) {
            return normalized;
        }
        return "FULL";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String goalLabel(String goal) {
        return switch (goal) {
            case "UPDATE" -> "数据修改校验";
            case "DELETE" -> "数据删除校验";
            default -> "数据全量校验";
        };
    }

    private LocalDateTime coalesce(LocalDateTime first, LocalDateTime second) {
        return first != null ? first : second;
    }

    /**
     * spec validation-workbench-redesign Phase 2：历史比对（diff-of-diffs）。
     *
     * <p>对比 currentRunId 与 compareToRunId 的差异行 PK 集合：
     * <ul>
     *   <li>newDiffs = currentPKs ∖ comparePKs（本次新增的差异）</li>
     *   <li>fixedDiffs = comparePKs ∖ currentPKs（已修复的差异）</li>
         *   <li>changedTypeDiffs = 同一 PK 两次都有差异，但 diffType 发生变化</li>
         *   <li>unchangedCount = 两次都有且 diffType 相同的差异数量</li>
         * </ul>
         *
         * <p>最多返回 200 条 newDiffs / fixedDiffs / changedTypeDiffs，超出截断。
         */
    @Transactional(readOnly = true, timeout = 30)
    public com.dfygt.dfetl.server.dto.DiffChangesDto getDiffChanges(Long taskId, Long currentRunId, Long compareToRunId) {
        // 归属校验
        ValidationRun currentRun = validationRunRepository.findById(currentRunId)
                .orElseThrow(() -> new NoSuchElementException("ValidationRun not found: " + currentRunId));
        if (!java.util.Objects.equals(currentRun.getTaskId(), taskId)) {
            throw new NoSuchElementException("ValidationRun " + currentRunId + " does not belong to task " + taskId);
        }
        ValidationRun compareRun = validationRunRepository.findById(compareToRunId)
                .orElseThrow(() -> new NoSuchElementException("ValidationRun not found: " + compareToRunId));
        if (!java.util.Objects.equals(compareRun.getTaskId(), taskId)) {
            throw new NoSuchElementException("ValidationRun " + compareToRunId + " does not belong to task " + taskId);
        }

        long currentTotalCount = diffRepository.countByValidationRunId(currentRunId);
        long compareTotalCount = diffRepository.countByValidationRunId(compareToRunId);
        if (currentTotalCount > DIFF_CHANGES_PK_SCAN_LIMIT || compareTotalCount > DIFF_CHANGES_PK_SCAN_LIMIT) {
            return new com.dfygt.dfetl.server.dto.DiffChangesDto(
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    0L,
                    currentTotalCount,
                    compareTotalCount,
                    true);
        }

        // 拉两次 run 的 PK 集合（仅限小集合；大集合上方返回 count-only，避免全量 PK 进 JVM）
        List<String> currentPKs = diffRepository.findPkValuesByValidationRunId(currentRunId);
        List<String> comparePKs = diffRepository.findPkValuesByValidationRunId(compareToRunId);

        java.util.Set<String> currentSet = new java.util.HashSet<>(currentPKs);
        java.util.Set<String> compareSet = new java.util.HashSet<>(comparePKs);

        // newDiffs = current ∖ compare
        List<String> newPKs = currentPKs.stream()
                .filter(pk -> !compareSet.contains(pk))
                .distinct()
                .toList();

        // fixedDiffs = compare ∖ current
        List<String> fixedPKs = comparePKs.stream()
                .filter(pk -> !currentSet.contains(pk))
                .distinct()
                .toList();

        // common = current ∩ compare；共同 PK 还要继续比较 diffType，避免把类型变化误判为未变。
        List<String> commonPKs = currentPKs.stream()
                .filter(compareSet::contains)
                .distinct()
                .toList();

        boolean truncated = newPKs.size() > 200 || fixedPKs.size() > 200;
        int maxItems = 200;

        // 查 newDiffs 的 diffType
        List<String> newPKsTruncated = newPKs.size() > maxItems ? newPKs.subList(0, maxItems) : newPKs;
        List<com.dfygt.dfetl.server.dto.DiffChangesDto.DiffChangeItem> newDiffs = new java.util.ArrayList<>();
        if (!newPKsTruncated.isEmpty()) {
            var rows = diffRepository.findByValidationRunIdAndPkValueIn(currentRunId, newPKsTruncated);
            for (var row : rows) {
                newDiffs.add(new com.dfygt.dfetl.server.dto.DiffChangesDto.DiffChangeItem(
                        row.getPkValue(), row.getDiffType()));
            }
        }

        // 查 fixedDiffs 的 diffType
        List<String> fixedPKsTruncated = fixedPKs.size() > maxItems ? fixedPKs.subList(0, maxItems) : fixedPKs;
        List<com.dfygt.dfetl.server.dto.DiffChangesDto.DiffChangeItem> fixedDiffs = new java.util.ArrayList<>();
        if (!fixedPKsTruncated.isEmpty()) {
            var rows = diffRepository.findByValidationRunIdAndPkValueIn(compareToRunId, fixedPKsTruncated);
            for (var row : rows) {
                fixedDiffs.add(new com.dfygt.dfetl.server.dto.DiffChangesDto.DiffChangeItem(
                        row.getPkValue(), row.getDiffType()));
            }
        }

        List<com.dfygt.dfetl.server.dto.DiffChangesDto.DiffTypeChangeItem> changedTypeDiffs = new java.util.ArrayList<>();
        long unchangedCount = 0L;
        if (!commonPKs.isEmpty()) {
            var currentTypeByPk = diffTypeByPk(currentRunId, commonPKs);
            var compareTypeByPk = diffTypeByPk(compareToRunId, commonPKs);
            int changedTypeCount = 0;
            for (String pk : commonPKs) {
                String currentType = currentTypeByPk.get(pk);
                String compareType = compareTypeByPk.get(pk);
                if (java.util.Objects.equals(currentType, compareType)) {
                    unchangedCount++;
                } else {
                    changedTypeCount++;
                    if (changedTypeDiffs.size() < maxItems) {
                        changedTypeDiffs.add(new com.dfygt.dfetl.server.dto.DiffChangesDto.DiffTypeChangeItem(
                                pk, compareType, currentType));
                    }
                }
            }
            truncated = truncated || changedTypeCount > maxItems;
        }

        return new com.dfygt.dfetl.server.dto.DiffChangesDto(
                newDiffs, fixedDiffs, changedTypeDiffs, unchangedCount,
                currentSet.size(), compareSet.size(), truncated);
    }

    private java.util.Map<String, String> diffTypeByPk(Long validationRunId, List<String> pkValues) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        if (pkValues == null || pkValues.isEmpty()) {
            return result;
        }
        var rows = diffRepository.findByValidationRunIdAndPkValueIn(validationRunId, pkValues);
        for (var row : rows) {
            result.putIfAbsent(row.getPkValue(), row.getDiffType());
        }
        return result;
    }
}
