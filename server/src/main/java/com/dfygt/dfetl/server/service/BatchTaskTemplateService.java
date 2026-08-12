package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.BatchConfigDiffDto;
import com.dfygt.dfetl.server.dto.BatchMonitorDto;
import com.dfygt.dfetl.server.dto.BatchTaskTemplateDto;
import com.dfygt.dfetl.server.dto.BatchTaskTemplateSourceDto;
import com.dfygt.dfetl.server.dto.SyncTaskDto;
import com.dfygt.dfetl.server.dto.TaskValidationConfigDto;
import com.dfygt.dfetl.server.entity.BatchTaskTemplate;
import com.dfygt.dfetl.server.entity.BatchTaskTemplateSource;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.BatchTaskTemplateRepository;
import com.dfygt.dfetl.server.repository.BatchTaskTemplateSourceRepository;
import com.dfygt.dfetl.server.repository.InstitutionRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 批量任务模板服务 — 区域医共体场景：统一配置后一键为多个数据源创建同步任务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchTaskTemplateService {

    private final BatchTaskTemplateRepository templateRepo;
    private final BatchTaskTemplateSourceRepository sourceRepo;
    private final SyncTaskService syncTaskService;
    private final SyncTaskRepository syncTaskRepo;
    private final TaskValidationConfigApplyService validationConfigApplyService;
    /** spec institution-management 任务 17：机构主表查询（按 code 解析旧字段） */
    private final InstitutionRepository institutionRepo;
    /** spec institution-management 任务 17.1：apply 时回填 source_data_source.institution_id */
    private final SourceDataSourceRepository sourceDataSourceRepo;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    // ── CRUD ────────────────────────────────────────────────────────────────────

    @Transactional
    public BatchTaskTemplateDto create(BatchTaskTemplateDto dto) {
        BatchTaskTemplate entity = new BatchTaskTemplate();
        copyDtoToEntity(dto, entity);
        entity = templateRepo.save(entity);

        // 保存关联数据源
        if (dto.getSources() != null) {
            for (BatchTaskTemplateSourceDto srcDto : dto.getSources()) {
                BatchTaskTemplateSource src = new BatchTaskTemplateSource();
                copySourceDtoToEntity(srcDto, src);
                src.setTemplateId(entity.getId());
                sourceRepo.save(src);
            }
        }

        log.info("BatchTaskTemplate created: id={}, name={}", entity.getId(), entity.getName());
        return toDto(entity);
    }

    @Transactional
    public BatchTaskTemplateDto update(Long id, BatchTaskTemplateDto dto) {
        BatchTaskTemplate entity = getOrThrow(id);
        copyDtoToEntity(dto, entity);
        entity = templateRepo.save(entity);
        log.info("BatchTaskTemplate updated: id={}", id);
        return toDto(entity);
    }

    @Transactional(readOnly = true)
    public BatchTaskTemplateDto get(Long id) {
        return toDto(getOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<BatchTaskTemplateDto> list() {
        return templateRepo.findAll().stream().map(this::toDto).toList();
    }

    @Transactional
    public void delete(Long id) {
        if (!templateRepo.existsById(id)) {
            throw new NoSuchElementException("BatchTaskTemplate not found: " + id);
        }
        // 禁用所有已创建的关联任务
        List<BatchTaskTemplateSource> sources = sourceRepo.findByTemplateId(id);
        for (BatchTaskTemplateSource source : sources) {
            if (source.getSyncTaskId() != null) {
                try {
                    syncTaskService.disableSchedule(source.getSyncTaskId());
                    log.info("BatchTaskTemplate.delete: disabled syncTask {} before deleting template {}",
                            source.getSyncTaskId(), id);
                } catch (NoSuchElementException e) {
                    log.warn("BatchTaskTemplate.delete: syncTask {} already deleted", source.getSyncTaskId());
                }
            }
        }
        sourceRepo.deleteByTemplateId(id);
        templateRepo.deleteById(id);
        log.info("BatchTaskTemplate deleted: id={}", id);
    }

    // ── 数据源管理 ──────────────────────────────────────────────────────────────

    @Transactional
    public BatchTaskTemplateSourceDto addSource(Long templateId, BatchTaskTemplateSourceDto dto) {
        getOrThrow(templateId);
        BatchTaskTemplateSource entity = new BatchTaskTemplateSource();
        copySourceDtoToEntity(dto, entity);
        entity.setTemplateId(templateId);
        entity = sourceRepo.save(entity);
        log.info("BatchTaskTemplateSource added: templateId={}, sourceId={}", templateId, entity.getId());
        return toSourceDto(entity);
    }

    @Transactional
    public void removeSource(Long templateId, Long sourceId) {
        BatchTaskTemplateSource src = sourceRepo.findById(sourceId)
                .orElseThrow(() -> new NoSuchElementException("BatchTaskTemplateSource not found: " + sourceId));
        if (!src.getTemplateId().equals(templateId)) {
            throw new IllegalArgumentException("Source " + sourceId + " does not belong to template " + templateId);
        }
        // 如果已关联任务，禁用该任务的调度
        if (src.getSyncTaskId() != null) {
            try {
                syncTaskService.disableSchedule(src.getSyncTaskId());
                log.info("BatchTaskTemplateSource.removeSource: disabled syncTask {} before removing source {}",
                        src.getSyncTaskId(), sourceId);
            } catch (NoSuchElementException e) {
                log.warn("BatchTaskTemplateSource.removeSource: syncTask {} already deleted", src.getSyncTaskId());
            }
        }
        sourceRepo.deleteById(sourceId);
        log.info("BatchTaskTemplateSource removed: templateId={}, sourceId={}", templateId, sourceId);
    }

    // ── 预览 ────────────────────────────────────────────────────────────────────

    /**
     * 预览：返回各数据源的连通性和字段匹配情况（简化版，后续可扩展）。
     */
    @Transactional(readOnly = true)
    public List<BatchTaskTemplateSourceDto> preview(Long templateId) {
        getOrThrow(templateId);
        List<BatchTaskTemplateSource> sources = sourceRepo.findByTemplateIdAndEnabled(templateId, true);
        return sources.stream().map(this::toSourceDto).toList();
    }

    // ── 批量创建任务（核心方法） ────────────────────────────────────────────────

    /**
     * 为模板下所有已启用且未创建任务的数据源批量创建同步任务。
     * 使用 flush + refresh 防止并发重复创建。
     *
     * @return 本次新创建的 sync_task ID 列表
     */
    @Transactional
    public List<Long> apply(Long templateId) {
        BatchTaskTemplate template = getOrThrow(templateId);
        List<BatchTaskTemplateSource> sources = sourceRepo.findByTemplateIdAndEnabled(templateId, true);
        List<Long> createdTaskIds = new ArrayList<>();

        for (BatchTaskTemplateSource source : sources) {
            // 重新从 DB 读取最新状态，防止并发创建
            BatchTaskTemplateSource freshSource = sourceRepo.findByIdForUpdate(source.getId()).orElse(null);
            if (freshSource == null || freshSource.getSyncTaskId() != null) {
                log.debug("BatchTaskTemplate.apply: source {} already has syncTaskId or deleted, skip",
                        source.getId());
                continue;
            }

            // ── spec institution-management 任务 17.1：回填 source_data_source.institution_id ──
            // 仅当数据源未关联机构（write-once 提升），避免覆盖既有归属。
            backfillSourceDataSourceInstitution(freshSource);

            // 构造 SyncTaskDto（已透传 institution_id）
            SyncTaskDto taskDto = buildSyncTaskDto(template, freshSource);

            // 调用 SyncTaskService.create
            SyncTaskDto created = syncTaskService.create(taskDto);

            // 回填 sync_task_id
            Long taskId = created.getId();
            freshSource.setSyncTaskId(taskId);
            sourceRepo.save(freshSource);

            // 创建校验配置
            createValidationConfig(template, taskId);

            createdTaskIds.add(taskId);
            log.info("BatchTaskTemplate.apply: created syncTask id={} for source={} (institution={}, institutionId={})",
                    taskId, freshSource.getSourceDatasourceId(),
                    freshSource.getInstitutionName(), freshSource.getInstitutionId());
        }

        log.info("BatchTaskTemplate.apply: templateId={}, created {} tasks", templateId, createdTaskIds.size());
        return createdTaskIds;
    }

    // ── 同步配置到已创建的任务 ──────────────────────────────────────────────────

    /**
     * 将模板配置同步到已创建的任务（更新调度、同步参数等）。
     * 注意：不覆盖任务名称，仅同步配置字段。
     *
     * @return 更新的任务 ID 列表
     */
    @Transactional
    public List<Long> syncConfig(Long templateId) {
        BatchTaskTemplate template = getOrThrow(templateId);
        List<BatchTaskTemplateSource> sources = sourceRepo.findByTemplateIdAndEnabled(templateId, true);
        List<Long> updatedTaskIds = new ArrayList<>();

        for (BatchTaskTemplateSource source : sources) {
            if (source.getSyncTaskId() == null) {
                continue;
            }

            try {
                SyncTaskDto taskDto = buildSyncTaskDtoForUpdate(template, source);
                syncTaskService.update(source.getSyncTaskId(), taskDto);
                updatedTaskIds.add(source.getSyncTaskId());
            } catch (NoSuchElementException e) {
                // 任务已被删除，清空关联
                log.warn("BatchTaskTemplate.syncConfig: syncTask {} not found (deleted?), clearing reference",
                        source.getSyncTaskId());
                source.setSyncTaskId(null);
                sourceRepo.save(source);
            }
        }

        log.info("BatchTaskTemplate.syncConfig: templateId={}, updated {} tasks", templateId, updatedTaskIds.size());
        return updatedTaskIds;
    }

    // ── 配置对比（Phase 3） ────────────────────────────────────────────────────

    /**
     * 对比模板配置与已创建任务的当前配置差异。
     */
    @Transactional(readOnly = true)
    public BatchConfigDiffDto configDiff(Long templateId) {
        BatchTaskTemplate template = getOrThrow(templateId);
        List<BatchTaskTemplateSource> sources = sourceRepo.findByTemplateIdAndEnabled(templateId, true);

        BatchConfigDiffDto result = new BatchConfigDiffDto();
        result.setTemplateId(templateId);
        result.setTemplateName(template.getName());

        List<BatchConfigDiffDto.TaskDiff> diffs = new ArrayList<>();
        int upToDateCount = 0;
        int pendingCount = 0;

        // 批量加载已创建的任务
        List<Long> taskIds = sources.stream()
                .map(BatchTaskTemplateSource::getSyncTaskId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, SyncTask> taskMap = syncTaskRepo.findAllById(taskIds).stream()
                .collect(Collectors.toMap(SyncTask::getId, Function.identity()));

        for (BatchTaskTemplateSource source : sources) {
            if (source.getSyncTaskId() == null) {
                pendingCount++;
                continue;
            }

            SyncTask task = taskMap.get(source.getSyncTaskId());
            if (task == null) {
                pendingCount++;
                continue;
            }

            List<BatchConfigDiffDto.FieldDiff> fieldDiffs = compareConfig(template, source, task);
            if (fieldDiffs.isEmpty()) {
                upToDateCount++;
            } else {
                BatchConfigDiffDto.TaskDiff diff = new BatchConfigDiffDto.TaskDiff();
                diff.setSourceId(source.getId());
                diff.setInstitutionName(source.getInstitutionName());
                diff.setSyncTaskId(source.getSyncTaskId());
                diff.setFields(fieldDiffs);
                diffs.add(diff);
            }
        }

        result.setDiffs(diffs);
        result.setUpToDateCount(upToDateCount);
        result.setPendingCount(pendingCount);
        return result;
    }

    /**
     * 选择性推送：只更新指定数据源关联的任务。
     *
     * @param sourceIds 要推送的 source ID 列表，为空则推送所有有差异的
     * @return 更新的任务 ID 列表
     */
    @Transactional
    public List<Long> syncConfigSelective(Long templateId, List<Long> sourceIds) {
        BatchTaskTemplate template = getOrThrow(templateId);
        List<BatchTaskTemplateSource> sources;

        if (sourceIds == null || sourceIds.isEmpty()) {
            // 推送所有
            sources = sourceRepo.findByTemplateIdAndEnabled(templateId, true);
        } else {
            sources = sourceRepo.findAllById(sourceIds).stream()
                    .filter(s -> s.getTemplateId().equals(templateId) && Boolean.TRUE.equals(s.getEnabled()))
                    .toList();
        }

        List<Long> updatedTaskIds = new ArrayList<>();
        for (BatchTaskTemplateSource source : sources) {
            if (source.getSyncTaskId() == null) {
                continue;
            }
            try {
                SyncTaskDto taskDto = buildSyncTaskDtoForUpdate(template, source);
                syncTaskService.update(source.getSyncTaskId(), taskDto);
                updatedTaskIds.add(source.getSyncTaskId());
            } catch (NoSuchElementException e) {
                log.warn("BatchTaskTemplate.syncConfigSelective: syncTask {} not found, clearing reference",
                        source.getSyncTaskId());
                source.setSyncTaskId(null);
                sourceRepo.save(source);
            }
        }

        log.info("BatchTaskTemplate.syncConfigSelective: templateId={}, sourceIds={}, updated {} tasks",
                templateId, sourceIds, updatedTaskIds.size());
        return updatedTaskIds;
    }

    // ── 批量监控面板（Phase 3） ──────────────────────────────────────────────────

    /**
     * 获取模板下所有任务的执行状态汇总。
     */
    @Transactional(readOnly = true)
    public BatchMonitorDto monitor(Long templateId) {
        BatchTaskTemplate template = getOrThrow(templateId);
        List<BatchTaskTemplateSource> sources = sourceRepo.findByTemplateId(templateId);

        BatchMonitorDto result = new BatchMonitorDto();
        result.setTemplateId(templateId);
        result.setTemplateName(template.getName());
        result.setTotalSources(sources.size());

        // 批量加载已创建的任务
        List<Long> taskIds = sources.stream()
                .map(BatchTaskTemplateSource::getSyncTaskId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, SyncTask> taskMap = syncTaskRepo.findAllById(taskIds).stream()
                .collect(Collectors.toMap(SyncTask::getId, Function.identity()));

        int createdTasks = 0;
        int pendingTasks = 0;
        int running = 0, success = 0, failed = 0, disabled = 0, reconcileRequired = 0;
        List<BatchMonitorDto.TaskStatus> taskStatuses = new ArrayList<>();

        for (BatchTaskTemplateSource source : sources) {
            BatchMonitorDto.TaskStatus ts = new BatchMonitorDto.TaskStatus();
            ts.setSourceId(source.getId());
            ts.setInstitutionName(source.getInstitutionName());
            ts.setInstitutionCode(source.getInstitutionCode());
            ts.setSyncTaskId(source.getSyncTaskId());

            if (source.getSyncTaskId() == null) {
                pendingTasks++;
                ts.setStatus("PENDING");
                taskStatuses.add(ts);
                continue;
            }

            SyncTask task = taskMap.get(source.getSyncTaskId());
            if (task == null) {
                pendingTasks++;
                ts.setStatus("DELETED");
                taskStatuses.add(ts);
                continue;
            }

            createdTasks++;
            ts.setTaskName(task.getName());
            ts.setStatus(task.getStatus());
            ts.setLastRunStatus(task.getLastRunStatus());
            ts.setLastRunTime(task.getLastRunTime());
            ts.setAlertStatus(task.getAlertStatus());
            if (task.getIncrementalCheckpoint() != null) {
                ts.setIncrementalCheckpoint(task.getIncrementalCheckpoint().toString());
            }

            // 统计
            String lastStatus = task.getLastRunStatus();
            if ("RUNNING".equals(lastStatus)) running++;
            else if ("SUCCESS".equals(lastStatus)) success++;
            else if ("FAILED".equals(lastStatus)) failed++;
            else if ("RECONCILE_REQUIRED".equals(lastStatus)) reconcileRequired++;

            if ("DISABLED".equals(task.getStatus())) disabled++;

            taskStatuses.add(ts);
        }

        result.setCreatedTasks(createdTasks);
        result.setPendingTasks(pendingTasks);

        BatchMonitorDto.StatusSummary summary = new BatchMonitorDto.StatusSummary();
        summary.setRunning(running);
        summary.setSuccess(success);
        summary.setFailed(failed);
        summary.setDisabled(disabled);
        summary.setReconcileRequired(reconcileRequired);
        result.setStatusSummary(summary);
        result.setTasks(taskStatuses);

        return result;
    }

    /**
     * 获取所有模板的监控概览（列表页用）。
     */
    @Transactional(readOnly = true)
    public List<BatchMonitorDto> monitorAll() {
        List<BatchTaskTemplate> templates = templateRepo.findAll();
        return templates.stream().map(t -> monitor(t.getId())).toList();
    }

    // ── 内部方法 ────────────────────────────────────────────────────────────────

    private BatchTaskTemplate getOrThrow(Long id) {
        return templateRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("BatchTaskTemplate not found: " + id));
    }

    /**
     * 根据模板 + 数据源构造 SyncTaskDto（用于创建新任务）。
     * 任务名格式：{模板名}-{机构名}-{时间戳}
     */
    private SyncTaskDto buildSyncTaskDto(BatchTaskTemplate template, BatchTaskTemplateSource source) {
        SyncTaskDto dto = buildSyncTaskDtoCommon(template, source);

        // 创建时生成任务名
        String institutionLabel = source.getInstitutionName() != null
                ? source.getInstitutionName()
                : String.valueOf(source.getSourceDatasourceId());
        String ts = LocalDateTime.now().format(TS_FMT);
        dto.setName(template.getName() + "-" + institutionLabel + "-" + ts);

        // 默认状态
        dto.setStatus("ENABLED");

        return dto;
    }

    /**
     * 根据模板 + 数据源构造 SyncTaskDto（用于更新已有任务）。
     * 不设置 name，避免覆盖已有任务名称。
     */
    private SyncTaskDto buildSyncTaskDtoForUpdate(BatchTaskTemplate template, BatchTaskTemplateSource source) {
        SyncTaskDto dto = buildSyncTaskDtoCommon(template, source);
        // 不设置 name — copyToEntity 中 entity.setName(null) 不会覆盖
        // 但 SyncTaskService.copyToEntity 会无条件 setName，所以需要读取当前名称
        SyncTask existing = syncTaskRepo.findById(source.getSyncTaskId()).orElse(null);
        if (existing != null) {
            dto.setName(existing.getName());
        }
        // 不改变任务状态
        dto.setStatus(existing != null ? existing.getStatus() : null);
        return dto;
    }

    /**
     * 公共构造逻辑：填充同步配置字段。
     */
    private SyncTaskDto buildSyncTaskDtoCommon(BatchTaskTemplate template, BatchTaskTemplateSource source) {
        SyncTaskDto dto = new SyncTaskDto();

        // ── spec institution-management 任务 17.1：透传 institution_id ──
        // 模板 source 上的 institutionId 直接写入新建任务；为空时由 SyncTaskService.create()
        // 走机构继承（从 source_data_source.institution_id 继承），保持单一归属规则。
        dto.setInstitutionId(source.getInstitutionId());

        // 同步类型
        dto.setSyncType("INCREMENTAL".equals(template.getDataScope()) ? "INCREMENTAL" : "FULL");
        dto.setDataScope(template.getDataScope());
        dto.setIncrementMode(template.getIncrementMode());
        dto.setIncrementalField(template.getIncrementalField());
        dto.setSyncMode(template.getSyncMode());

        // UPSERT keys — 兼容 JSON 数组和逗号分隔
        if (template.getUpsertKeys() != null && !template.getUpsertKeys().isBlank()) {
            dto.setUpsertKeys(parseUpsertKeys(template.getUpsertKeys()));
        }

        // 数据源
        dto.setSourceDataSourceId(source.getSourceDatasourceId());
        dto.setTargetDataSourceId(template.getTargetDatasourceId());

        // Schema：优先使用 source 级覆盖，否则用模板级
        String schema = source.getSourceSchema() != null ? source.getSourceSchema() : template.getSourceSchema();
        dto.setSourceSchema(schema);

        // 视图名
        dto.setViewNames(List.of(template.getViewName()));

        // 目标表映射：源视图 → 模板指定的目标表
        dto.setTargetTableMap("{\"" + template.getViewName() + "\":\"" + template.getTargetTable() + "\"}");

        // 并发
        dto.setParallelism(template.getParallelism());

        // batchSize 留空，执行期继承全局 etl.fetch_size。

        // 调度
        dto.setCronExpression(template.getCronExpression());

        // 过滤条件（机构级）
        dto.setStaticFilter(source.getStaticFilter());

        // Doris 配置
        dto.setDorisTableModel(template.getDorisTableModel());
        dto.setEnableDorisMerge(template.getEnableDorisMerge());
        dto.setSoftDeleteField(template.getSoftDeleteField());
        dto.setDeleteSignValue(template.getDeleteSignValue());
        dto.setSequenceCol(template.getSequenceCol());

        // 源对象类型：视图
        dto.setSourceObjectType("VIEW");
        dto.setSourceMode("TABLE_VIEW");

        dto.setExecutorType("SEATUNNEL_CLUSTER");

        return dto;
    }

    /**
     * 为新创建的任务注入校验配置。
     */
    private void createValidationConfig(BatchTaskTemplate template, Long taskId) {
        TaskValidationConfigDto cfg = new TaskValidationConfigDto();
        cfg.setEnabled(true);
        cfg.setMethod(template.getValidationMethod());
        cfg.setAutoTrigger(template.getAutoTrigger() != null ? template.getAutoTrigger() : true);
        cfg.setDriftCron(template.getValidationDriftCron());
        cfg.setValidationLookbackHours(template.getValidationLookbackHours());
        validationConfigApplyService.saveForNewTask(taskId, cfg);
    }

    /**
     * spec institution-management 任务 17.1：在 apply 时将模板 source 上的 institution_id
     * 回填到关联的 {@link SourceDataSource}（仅当数据源未关联机构）。
     *
     * <p>设计意图：
     * <ul>
     *   <li>write-once 提升 — 不覆盖已有归属，避免误改运维侧手工设置；</li>
     *   <li>模板 source 未携带 institution_id（旧数据）时直接跳过，不阻塞任务创建；</li>
     *   <li>数据源不存在或读取异常时记录 WARN 后继续，apply 主流程不应被卡住。</li>
     * </ul>
     */
    private void backfillSourceDataSourceInstitution(BatchTaskTemplateSource source) {
        Long institutionId = source.getInstitutionId();
        if (institutionId == null) {
            return;
        }
        Long sourceDsId = source.getSourceDatasourceId();
        if (sourceDsId == null) {
            return;
        }
        SourceDataSource ds = sourceDataSourceRepo.findById(sourceDsId).orElse(null);
        if (ds == null) {
            log.warn("BatchTaskTemplate.apply: sourceDatasourceId={} 在 source_datasource 表不存在，"
                    + "跳过 institution_id 回填", sourceDsId);
            return;
        }
        if (ds.getInstitutionId() != null) {
            // 已有归属，尊重既有数据，不覆盖（write-once 提升语义）
            return;
        }
        ds.setInstitutionId(institutionId);
        sourceDataSourceRepo.save(ds);
        log.info("BatchTaskTemplate.apply: 回填 source_datasource id={} institution_id={} (来自模板 source id={})",
                sourceDsId, institutionId, source.getId());
    }

    // ── DTO 转换 ────────────────────────────────────────────────────────────────

    private BatchTaskTemplateDto toDto(BatchTaskTemplate entity) {
        BatchTaskTemplateDto dto = new BatchTaskTemplateDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setTargetDatasourceId(entity.getTargetDatasourceId());
        dto.setTargetTable(entity.getTargetTable());
        dto.setViewName(entity.getViewName());
        dto.setSourceSchema(entity.getSourceSchema());
        dto.setDataScope(entity.getDataScope());
        dto.setIncrementMode(entity.getIncrementMode());
        dto.setIncrementalField(entity.getIncrementalField());
        dto.setSyncMode(entity.getSyncMode());
        dto.setUpsertKeys(entity.getUpsertKeys());
        dto.setParallelism(entity.getParallelism());
        dto.setCronExpression(entity.getCronExpression());
        dto.setValidationMethod(entity.getValidationMethod());
        dto.setValidationDriftCron(entity.getValidationDriftCron());
        dto.setValidationLookbackHours(entity.getValidationLookbackHours());
        dto.setAutoTrigger(entity.getAutoTrigger());
        dto.setDorisTableModel(entity.getDorisTableModel());
        dto.setEnableDorisMerge(entity.getEnableDorisMerge());
        dto.setSoftDeleteField(entity.getSoftDeleteField());
        dto.setDeleteSignValue(entity.getDeleteSignValue());
        dto.setSequenceCol(entity.getSequenceCol());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());

        // 加载关联数据源
        List<BatchTaskTemplateSource> sources = sourceRepo.findByTemplateId(entity.getId());
        dto.setSources(sources.stream().map(this::toSourceDto).toList());

        return dto;
    }

    private BatchTaskTemplateSourceDto toSourceDto(BatchTaskTemplateSource entity) {
        BatchTaskTemplateSourceDto dto = new BatchTaskTemplateSourceDto();
        dto.setId(entity.getId());
        dto.setTemplateId(entity.getTemplateId());
        dto.setSourceDatasourceId(entity.getSourceDatasourceId());
        dto.setSourceSchema(entity.getSourceSchema());
        dto.setStaticFilter(entity.getStaticFilter());
        dto.setInstitutionId(entity.getInstitutionId());
        dto.setInstitutionName(entity.getInstitutionName());
        dto.setInstitutionCode(entity.getInstitutionCode());
        dto.setEnabled(entity.getEnabled());
        dto.setSyncTaskId(entity.getSyncTaskId());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private void copyDtoToEntity(BatchTaskTemplateDto dto, BatchTaskTemplate entity) {
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setTargetDatasourceId(dto.getTargetDatasourceId());
        entity.setTargetTable(dto.getTargetTable());
        entity.setViewName(dto.getViewName());
        entity.setSourceSchema(dto.getSourceSchema());
        entity.setDataScope(dto.getDataScope());
        entity.setIncrementMode(dto.getIncrementMode());
        entity.setIncrementalField(dto.getIncrementalField());
        entity.setSyncMode(dto.getSyncMode());
        entity.setUpsertKeys(dto.getUpsertKeys());
        entity.setParallelism(dto.getParallelism() != null ? dto.getParallelism() : 1);
        entity.setCronExpression(dto.getCronExpression());
        entity.setValidationMethod(dto.getValidationMethod());
        entity.setValidationDriftCron(dto.getValidationDriftCron());
        entity.setValidationLookbackHours(dto.getValidationLookbackHours());
        entity.setAutoTrigger(dto.getAutoTrigger());
        entity.setDorisTableModel(dto.getDorisTableModel());
        entity.setEnableDorisMerge(dto.getEnableDorisMerge());
        entity.setSoftDeleteField(dto.getSoftDeleteField());
        entity.setDeleteSignValue(dto.getDeleteSignValue());
        entity.setSequenceCol(dto.getSequenceCol());
    }

    private void copySourceDtoToEntity(BatchTaskTemplateSourceDto dto, BatchTaskTemplateSource entity) {
        entity.setSourceDatasourceId(dto.getSourceDatasourceId());
        entity.setSourceSchema(dto.getSourceSchema());
        entity.setStaticFilter(dto.getStaticFilter());
        entity.setInstitutionName(dto.getInstitutionName());
        entity.setInstitutionCode(dto.getInstitutionCode());
        // spec institution-management 任务 17.2：优先使用 dto.institutionId；
        // 旧前端只传 institution_code 时，按 code 严格查找解析为机构 ID（未命中记 WARN，不抛异常以保兼容）。
        Long resolvedInstitutionId = resolveInstitutionId(dto);
        entity.setInstitutionId(resolvedInstitutionId);
        entity.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : true);
    }

    /**
     * 解析 institutionId — 兼容新旧两种前端：
     * <ol>
     *   <li>{@code dto.institutionId} 已显式提供 → 直接使用（前端选好的优先级最高）</li>
     *   <li>{@code dto.institutionId} 为空但 {@code dto.institutionCode} 非空 →
     *       严格按 code 查 {@link Institution}，命中则返回其 id；</li>
     *   <li>未命中 → 记录 WARN 并返回 null（旧字段保留在 entity 中，不阻塞保存）。</li>
     * </ol>
     */
    private Long resolveInstitutionId(BatchTaskTemplateSourceDto dto) {
        if (dto.getInstitutionId() != null) {
            return dto.getInstitutionId();
        }
        String code = dto.getInstitutionCode();
        if (code == null || code.isBlank()) {
            return null;
        }
        return institutionRepo.findByCode(code.trim())
                .map(Institution::getId)
                .orElseGet(() -> {
                    log.warn("BatchTaskTemplateService.resolveInstitutionId: 未在机构主表找到 code={} 对应记录，"
                            + "保留 institution_code/name 旧字段但 institution_id 置空", code);
                    return null;
                });
    }

    // ── 配置对比辅助方法 ────────────────────────────────────────────────────────

    /**
     * 对比模板配置与任务当前配置的差异。
     */
    private List<BatchConfigDiffDto.FieldDiff> compareConfig(
            BatchTaskTemplate template, BatchTaskTemplateSource source, SyncTask task) {
        List<BatchConfigDiffDto.FieldDiff> diffs = new ArrayList<>();

        // 同步模式
        diffField(diffs, "同步方式", "syncMode", template.getSyncMode(), task.getSyncMode());

        // 数据范围
        diffField(diffs, "数据范围", "dataScope", template.getDataScope(), task.getDataScope());

        // 增量模式
        diffField(diffs, "增量模式", "incrementMode", template.getIncrementMode(), task.getIncrementMode());

        // 增量字段
        diffField(diffs, "增量字段", "incrementalField", template.getIncrementalField(), task.getIncrementalField());

        // 并发数
        String tplParallelism = template.getParallelism() != null ? String.valueOf(template.getParallelism()) : null;
        String taskParallelism = task.getParallelism() != null ? String.valueOf(task.getParallelism()) : null;
        diffField(diffs, "并发数", "parallelism", tplParallelism, taskParallelism);

        // 调度频率
        diffField(diffs, "调度频率", "cronExpression", template.getCronExpression(), task.getCronExpression());

        // Schema（source 级覆盖优先）
        String expectedSchema = source.getSourceSchema() != null ? source.getSourceSchema() : template.getSourceSchema();
        diffField(diffs, "Schema", "sourceSchema", expectedSchema, task.getSourceSchema());

        // upsertKeys 对比
        String tplKeys = template.getUpsertKeys();
        List<String> tplKeyList = (tplKeys != null && !tplKeys.isBlank()) ? parseUpsertKeys(tplKeys) : List.of();
        List<String> taskKeyList = task.getUpsertKeys() != null ? task.getUpsertKeys() : List.of();
        if (!tplKeyList.equals(taskKeyList)) {
            BatchConfigDiffDto.FieldDiff diff = new BatchConfigDiffDto.FieldDiff();
            diff.setLabel("主键列");
            diff.setField("upsertKeys");
            diff.setTemplateValue(tplKeyList.isEmpty() ? null : String.join(",", tplKeyList));
            diff.setTaskValue(taskKeyList.isEmpty() ? null : String.join(",", taskKeyList));
            diffs.add(diff);
        }

        // Doris 表模型
        diffField(diffs, "Doris表模型", "dorisTableModel", template.getDorisTableModel(), task.getDorisTableModel());

        // 启用 Merge
        String tplMerge = template.getEnableDorisMerge() != null ? String.valueOf(template.getEnableDorisMerge()) : "false";
        String taskMerge = task.getEnableDorisMerge() != null ? String.valueOf(task.getEnableDorisMerge()) : "false";
        diffField(diffs, "启用MERGE", "enableDorisMerge", tplMerge, taskMerge);

        // 软删除字段
        diffField(diffs, "软删除字段", "softDeleteField", template.getSoftDeleteField(), task.getSoftDeleteField());

        // Sequence 列
        diffField(diffs, "Sequence列", "sequenceCol", template.getSequenceCol(), task.getSequenceCol());

        return diffs;
    }

    private void diffField(List<BatchConfigDiffDto.FieldDiff> diffs,
                           String label, String field, String templateValue, String taskValue) {
        String tpl = normalizeEmpty(templateValue);
        String task = normalizeEmpty(taskValue);
        if (!Objects.equals(tpl, task)) {
            BatchConfigDiffDto.FieldDiff diff = new BatchConfigDiffDto.FieldDiff();
            diff.setLabel(label);
            diff.setField(field);
            diff.setTemplateValue(tpl);
            diff.setTaskValue(task);
            diffs.add(diff);
        }
    }

    private String normalizeEmpty(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * 解析 upsertKeys — 兼容 JSON 数组 ["a","b"] 和逗号分隔 "a,b" 两种格式。
     */
    private List<String> parseUpsertKeys(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String trimmed = raw.trim();
        // 尝试 JSON 数组解析
        if (trimmed.startsWith("[")) {
            try {
                List<String> keys = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(trimmed,
                                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                return keys.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
            } catch (Exception e) {
                log.debug("parseUpsertKeys: JSON parse failed, fallback to comma-split: {}", trimmed);
            }
        }
        // 逗号分隔解析
        return Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
