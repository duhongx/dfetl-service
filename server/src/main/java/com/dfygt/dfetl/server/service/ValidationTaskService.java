package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.ValidationTaskDto;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 校验任务查询服务。
 *
 * <p>spec validation-table-consolidation · Step 7：
 * 改造为从 {@code validation_run} 表读取数据，替代原 {@code validation_task} 表。
 * Controller 端点路径、参数、响应格式保持完全不变。
 */
@Service
@RequiredArgsConstructor
public class ValidationTaskService {

    private final ValidationRunRepository validationRunRepo;
    private final SyncTaskRepository syncTaskRepo;
    private final TaskExecutionRepository executionRepository;
    private final TaskValidationConfigService configService;
    private final ValidationDispatchService dispatchService;
    private final EffectiveValidationMethodResolver methodResolver;

    /** 分页查询（可按 status 过滤） */
    public Page<ValidationTaskDto> findAll(String status, Pageable pageable) {
        return findAll(status, null, pageable);
    }

    /**
     * 分页查询（status / search 联合过滤）。
     * 只取每个 task_id 的最新一条 validation_run 记录。
     */
    public Page<ValidationTaskDto> findAll(String status, String search, Pageable pageable) {
        boolean hasStatus = status != null && !status.isBlank();
        boolean hasSearch = search != null && !search.isBlank();

        Page<Object[]> page = validationRunRepo.findAllLatestPerTask(
                hasStatus ? status : null,
                hasSearch ? search.trim() : null,
                pageable);

        return page.map(row -> {
            ValidationRun r = (ValidationRun) row[0];
            SyncTask s = row[1] != null ? (SyncTask) row[1] : null;
            return toDto(r, s);
        });
    }

    /** 查询单条（按 validation_run.id） */
    public ValidationTaskDto findById(Long id) {
        ValidationRun r = validationRunRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("ValidationTask not found: " + id));
        SyncTask syncTask = r.getTaskId() != null
                ? syncTaskRepo.findById(r.getTaskId()).orElse(null)
                : null;
        return toDto(r, syncTask);
    }

    /**
     * 按同步任务查询校验记录，用于工作台 taskId 深链打开。
     * 不存在则自动创建一条默认 PENDING 状态的 ValidationRun，避免深链跳转 404。
     */
    @Transactional
    public ValidationTaskDto findByTaskId(Long taskId) {
        SyncTask syncTask = syncTaskRepo.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + taskId));

        ValidationRun r = validationRunRepo.findFirstByTaskIdOrderByIdDesc(taskId).orElseGet(() -> {
            // 自动创建默认校验记录（method=ROW_COUNT, status=PENDING）
            ValidationRun vr = new ValidationRun();
            vr.setName(syncTask.getName());
            vr.setTaskId(taskId);
            vr.setMethod("ROW_COUNT");
            vr.setMode("ROW_COUNT");
            vr.setScope("FULL");
            vr.setLegacyExecId(0L);
            vr.setTablesText("");
            vr.setStatus("PENDING");
            vr.setUpdatedAt(LocalDateTime.now());
            return validationRunRepo.save(vr);
        });
        return toDto(r, syncTask);
    }

    /** 创建：必须绑定到已存在的同步任务 */
    @Transactional
    public ValidationTaskDto create(ValidationTaskDto dto) {
        if (dto.getTaskId() == null) {
            throw new IllegalArgumentException("校验任务必须关联同步任务（taskId 不能为空）");
        }
        SyncTask syncTask = syncTaskRepo.findById(dto.getTaskId())
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + dto.getTaskId()));
        // 每个同步任务只允许一条校验记录
        if (validationRunRepo.existsByTaskId(dto.getTaskId())) {
            throw new IllegalStateException("同步任务 " + dto.getTaskId() + " 已有对应校验任务");
        }
        ValidationRun r = new ValidationRun();
        r.setName(dto.getName() != null ? dto.getName() : syncTask.getName());
        r.setTaskId(dto.getTaskId());
        r.setExecutionId(dto.getExecutionId());
        r.setLegacyExecId(dto.getExecutionId() != null ? dto.getExecutionId() : 0L);
        r.setTriggerType(dto.getTriggerType() != null ? dto.getTriggerType() : "MANUAL");
        String method = dto.getMethod() != null ? dto.getMethod().toUpperCase() : "ROW_COUNT";
        r.setMethod(method);
        r.setMode(method);
        r.setScope("FULL");
        r.setTablesText("");  // 空则从同步任务 viewNames 读取
        r.setStatus("PENDING");
        r.setWindowStart(dto.getWindowStart());
        r.setWindowEnd(dto.getWindowEnd());
        r.setWindowType(dto.getWindowType());
        r.setWindowStartId(dto.getWindowStartId());
        r.setWindowEndId(dto.getWindowEndId());
        r.setUpdatedAt(LocalDateTime.now());
        r = validationRunRepo.save(r);
        return toDto(r, syncTask);
    }

    /** 删除 */
    @Transactional
    public void delete(Long id) {
        if (!validationRunRepo.existsById(id)) {
            throw new NoSuchElementException("ValidationTask not found: " + id);
        }
        validationRunRepo.deleteById(id);
    }

    /** 触发校验：先将状态置为 RUNNING，然后通过虚拟线程异步执行校验逻辑 */
    @Transactional
    public ValidationTaskDto run(Long id) {
        ValidationRun r = validationRunRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("ValidationTask not found: " + id));

        // 加载对应的校验策略配置（可能为 null，由 ValidationRunner 使用默认容忍值 0）
        TaskValidationConfig config = r.getTaskId() != null
                ? configService.findEntityByTaskId(r.getTaskId()).orElse(null)
                : null;

        ValidationRun dispatched;
        if (r.getTaskId() != null) {
            SyncTask syncTask = syncTaskRepo.findById(r.getTaskId())
                    .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + r.getTaskId()));
            String method = methodResolver.resolveManualMethod(syncTask, config, r.getMethod());
            dispatched = dispatchService.dispatchTriggered(
                    syncTask,
                    null,
                    "MANUAL",
                    method,
                    windowForFreshRun(r),
                    config);
            if (dispatched == null) {
                throw new IllegalStateException("已存在 RUNNING 校验任务，请稍后再试");
            }
        } else {
            dispatched = dispatchService.dispatchManual(r, config);
        }

        SyncTask syncTask = dispatched.getTaskId() != null
                ? syncTaskRepo.findById(dispatched.getTaskId()).orElse(null)
                : null;
        return toDto(dispatched, syncTask);
    }

    /**
     * 为所有尚无校验记录的同步任务自动创建（存量补齐）。
     * @return 新增条数
     */
    @Transactional
    public int syncWithSyncTasks() {
        List<Long> allTaskIds = syncTaskRepo.findAll().stream()
                .map(SyncTask::getId)
                .toList();
        int created = 0;
        for (Long taskId : allTaskIds) {
            if (!validationRunRepo.existsByTaskId(taskId)) {
                SyncTask st = syncTaskRepo.findById(taskId).orElse(null);
                if (st == null) continue;
                ValidationRun vr = new ValidationRun();
                vr.setName(st.getName());
                vr.setTaskId(st.getId());
                vr.setLegacyExecId(0L);
                vr.setMethod("ROW_COUNT");
                vr.setMode("ROW_COUNT");
                vr.setScope("FULL");
                vr.setTablesText("");  // 从 viewNames 自动读取
                vr.setStatus("PENDING");
                vr.setUpdatedAt(LocalDateTime.now());
                validationRunRepo.save(vr);
                created++;
            }
        }
        return created;
    }

    /**
     * 触发全量校验（走 dispatchTriggered 路径，有悲观锁并发保护）。
     * 前端"重新全量校验"按钮调用此方法，避免绕过 ValidationDispatchService 的并发防护。
     * 按钮明确标注"全表 CHECKSUM"，因此强制使用 CHECKSUM 方法，不从配置读取。
     */
    @Transactional
    public ValidationTaskDto triggerFullValidation(Long taskId) {
        SyncTask syncTask = syncTaskRepo.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + taskId));
        TaskValidationConfig config = configService.findEntityByTaskId(taskId).orElse(null);

        // 前端按钮明确标注"全表 CHECKSUM"，强制使用 CHECKSUM 方法
        String method = "CHECKSUM";

        // 校验当前任务是否支持 CHECKSUM（需要 splitPk 或 upsertKeys，且不能是 CUSTOM_SQL 模式）
        if ("CUSTOM_SQL".equalsIgnoreCase(syncTask.getSourceMode())) {
            throw new IllegalStateException(
                    "当前任务不支持 CHECKSUM 校验（需要配置 splitPk 或 upsertKeys，且不能是自定义 SQL 模式）");
        }
        boolean hasKey = (syncTask.getSplitPk() != null && !syncTask.getSplitPk().isBlank())
                || (syncTask.getUpsertKeys() != null && syncTask.getUpsertKeys().stream()
                        .anyMatch(k -> k != null && !k.isBlank()));
        if (!hasKey) {
            throw new IllegalStateException(
                    "当前任务不支持 CHECKSUM 校验（需要配置 splitPk 或 upsertKeys，且不能是自定义 SQL 模式）");
        }

        WatermarkService.WindowContext fullWindow = new WatermarkService.WindowContext("FULL", null, null, null, null);
        ValidationRun vr = dispatchService.dispatchTriggered(syncTask, null, "MANUAL_FULL", method, fullWindow, config);
        if (vr == null) {
            throw new IllegalStateException("已存在 RUNNING 校验任务，请稍后再试");
        }
        return toDto(vr, syncTask);
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private WatermarkService.WindowContext windowForFreshRun(ValidationRun run) {
        boolean hasWindow = run.getWindowStart() != null || run.getWindowEnd() != null
                || run.getWindowStartId() != null || run.getWindowEndId() != null;
        if (!hasWindow) {
            return new WatermarkService.WindowContext("FULL", null, null, null, null);
        }
        String windowType = run.getWindowType() != null && !run.getWindowType().isBlank()
                ? run.getWindowType()
                : (run.getScope() != null && !run.getScope().isBlank() ? run.getScope() : "WINDOW");
        return new WatermarkService.WindowContext(
                windowType,
                run.getWindowStart(),
                run.getWindowEnd(),
                run.getWindowStartId(),
                run.getWindowEndId());
    }

    /**
     * 将 ValidationRun 转为 DTO；表名优先用 syncTask.viewNames，为空时回退到空列表。
     *
     * <p>spec validation-table-consolidation · Step 7
     * Validates: Requirement 6.1, 6.6
     */
    private ValidationTaskDto toDto(ValidationRun r, SyncTask syncTask) {
        ValidationTaskDto dto = new ValidationTaskDto();
        dto.setId(r.getId());
        dto.setName(r.getName());
        dto.setTaskId(r.getTaskId());
        dto.setTaskName(syncTask != null ? syncTask.getName() : "");
        dto.setMethod(r.getMethod());

        // 1:1 设计：校验表始终取关联同步任务的 viewNames
        List<String> tables;
        if (syncTask != null && syncTask.getViewNames() != null && !syncTask.getViewNames().isEmpty()) {
            tables = syncTask.getViewNames();
        } else {
            tables = List.of();
        }
        dto.setTables(tables);

        dto.setStatus(r.getStatus());
        dto.setSourceRows(r.getSourceRows());
        dto.setTargetRows(r.getTargetRows());
        applyMedicalDiversionSummary(r, dto);
        dto.setDiffRows(r.getDiffRows());
        dto.setDurationMs(r.getDurationMs());
        dto.setLastRunAt(r.getLastRunAt());
        dto.setExecutionId(r.getExecutionId());
        dto.setTriggerType(r.getTriggerType());
        dto.setWindowStart(r.getWindowStart());
        dto.setWindowEnd(r.getWindowEnd());
        dto.setWindowType(r.getWindowType());
        dto.setWindowStartId(r.getWindowStartId());
        dto.setWindowEndId(r.getWindowEndId());
        dto.setCreatedAt(r.getCreatedAt());
        dto.setErrorMsg(r.getErrorMsg());
        return dto;
    }

    private void applyMedicalDiversionSummary(ValidationRun run, ValidationTaskDto dto) {
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
}
