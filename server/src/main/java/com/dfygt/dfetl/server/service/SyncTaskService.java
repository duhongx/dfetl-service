package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.SyncTaskDto;
import com.dfygt.dfetl.server.dto.TaskViewConfigDto;
import com.dfygt.dfetl.server.engine.ExecutionResult;
import com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelProperties;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.MessagePublishConfig;
import com.dfygt.dfetl.server.entity.TaskValidationConfig;
import com.dfygt.dfetl.server.entity.TaskViewConfig;
import com.dfygt.dfetl.server.medical.mapping.MedicalMappingBatchPlanRequest;
import com.dfygt.dfetl.server.medical.MedicalContractSnapshotCodec;
import com.dfygt.dfetl.server.medical.mapping.MedicalMappingPlanResponse;
import com.dfygt.dfetl.server.medical.mapping.MedicalMappingPrecheckResult;
import com.dfygt.dfetl.server.medical.mapping.MedicalMappingPrecheckStatus;
import com.dfygt.dfetl.server.medical.mapping.MedicalMappingService;
import com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckFinding;
import com.dfygt.dfetl.server.repository.DirtyRecordRepository;
import com.dfygt.dfetl.server.repository.EtlVerifyChunkRepository;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffFieldRepository;
import com.dfygt.dfetl.server.repository.EtlVerifyDiffRepository;
import com.dfygt.dfetl.server.repository.MessagePublishConfigRepository;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.dfygt.dfetl.server.repository.TaskChunkRepository;
import com.dfygt.dfetl.server.repository.TaskExecutionRepository;
import com.dfygt.dfetl.server.repository.TaskSnapshotKeyRepository;
import com.dfygt.dfetl.server.repository.TaskValidationConfigRepository;
import com.dfygt.dfetl.server.repository.TaskViewConfigRepository;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import com.dfygt.dfetl.server.scheduler.model.ScheduleConfig;
import com.dfygt.dfetl.server.scheduler.service.CronExpressionService;
import com.dfygt.dfetl.server.scheduler.service.ScheduleConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SyncTaskService {

    private final SyncTaskRepository repository;
    private final ValidationRunRepository validationRunRepository;
    private final EtlVerifyDiffFieldRepository verifyDiffFieldRepository;
    private final EtlVerifyDiffRepository verifyDiffRepository;
    private final EtlVerifyChunkRepository verifyChunkRepository;
    private final MessagePublishConfigRepository messagePublishConfigRepository;
    private final QuartzSchedulerService quartzSchedulerService;
    private final WhereClauseBuilder whereClauseBuilder;
    private final SourceDataSourceService sourceDataSourceService;
    private final SeaTunnelProperties seaTunnelProperties;
    private final TaskExecutionRepository executionRepo;
    private final TaskChunkRepository chunkRepo;
    private final DirtyRecordRepository dirtyRecordRepo;
    private final TaskSnapshotKeyRepository snapshotKeyRepo;
    private final TaskViewConfigRepository viewConfigRepo;
    private final TaskValidationConfigRepository validationConfigRepo;
    private final SourceDataSourceRepository sourceRepo;
    private final TargetDataSourceRepository targetRepo;
    private final GlobalSettingsService globalSettingsService;
    private final ScheduleConfigService scheduleConfigService;
    private final InstitutionService institutionService;
    private final TaskValidationConfigApplyService validationConfigApplyService;
    private final SharedTargetTableGuard sharedTargetTableGuard;
    private final ScheduleFailureRecorder scheduleFailureRecorder;
    private final com.dfygt.dfetl.server.engine.doris.DorisTypeMappingPolicy dorisTypeMappingPolicy =
            new com.dfygt.dfetl.server.engine.doris.DorisTypeMappingPolicy();

    @Autowired(required = false)
    private com.dfygt.dfetl.server.engine.doris.DorisTypeMappingRuleService dorisTypeMappingRuleService;

    @Autowired(required = false)
    private MedicalMappingService medicalMappingService;

    @Autowired(required = false)
    private com.dfygt.dfetl.server.external.repository.ExternalTaskRequestRepository externalTaskRequestRepository;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<SyncTaskDto> findAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    /**
     * 按机构 ID 过滤同步任务。
     *
     * <p>当 {@code includeChildren=true} 时，先通过
     * {@link InstitutionService#getDescendantIds(Long)} 取以 {@code institutionId} 为根的整棵
     * 子树（含自身）的 ID 集合，然后用 {@link SyncTaskRepository#findByInstitutionIdIn(java.util.Collection)}
     * 做 {@code IN} 过滤；否则只匹配 {@code institutionId} 自身。
     *
     * <p>对应设计文档 Property 7 "任务机构过滤与子机构包含"
     * （Validates: Requirements 3.3, 3.4, 4.2）。
     *
     * @param institutionId 必填；为空时调用方应改用 {@link #findAll()}
     * @param includeChildren true=含子机构（子树根 + 后代）；false=仅当前机构
     * @return 过滤后的任务 DTO 列表，顺序由 JPA 默认实现决定（不做显式排序）
     */
    public List<SyncTaskDto> listByInstitution(Long institutionId, boolean includeChildren) {
        if (institutionId == null) {
            throw new IllegalArgumentException("institutionId 不能为空");
        }
        Set<Long> ids = includeChildren
                ? institutionService.getDescendantIds(institutionId) // 含自身
                : Set.of(institutionId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return repository.findByInstitutionIdIn(ids).stream().map(this::toDto).toList();
    }

    /**
     * 分页变体：spec institution-management 任务 15.2，按机构维度查询任务时下推分页 / 排序到 DB，
     * 防止机构关联任务规模上升后一次性内存加载阻塞服务。
     *
     * <p>排序与 {@link #findPage(int, int, String, String, String)} 保持一致，
     * 按 {@code createdAt} 倒序，便于前端展示与单元测试断言。
     *
     * <p>对应设计文档 Property 7 「任务机构过滤与子机构包含」
     * （Validates: Requirements 3.3, 3.4, 4.2）。
     *
     * @param institutionId   必填；为空时调用方应改用 {@link #findAll()}
     * @param includeChildren true=含子机构（子树根 + 后代）；false=仅当前机构
     * @param pageable        必填；调用方负责构造（已 clamp page/size）
     * @return 分页结果（含 totalElements 等元数据）
     */
    public Page<SyncTaskDto> listByInstitution(Long institutionId, boolean includeChildren, Pageable pageable) {
        if (institutionId == null) {
            throw new IllegalArgumentException("institutionId 不能为空");
        }
        if (pageable == null) {
            throw new IllegalArgumentException("pageable 不能为空");
        }
        Set<Long> ids = includeChildren
                ? institutionService.getDescendantIds(institutionId) // 含自身
                : Set.of(institutionId);
        if (ids.isEmpty()) {
            return Page.empty(pageable);
        }
        return repository.findByInstitutionIdIn(ids, pageable).map(this::toDto);
    }

    /**
     * spec 041：服务端分页 + 多条件过滤。
     * 排序固定按 createdAt DESC（兼容前端旧的客户端排序行为）。
     */
    public Page<SyncTaskDto> findPage(int page, int size,
                                      String status, String syncType,
                                      String search) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<SyncTask> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (syncType != null && !syncType.isBlank()) {
                ps.add(cb.equal(root.get("syncType"), syncType));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.like(cb.lower(root.get("name")), like));
            }
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
        return repository.findAll(spec, pageable).map(this::toDto);
    }

    public SyncTaskDto findById(Long id) {
        return toDto(getOrThrow(id));
    }

    public boolean existsByName(String name) {
        return repository.existsByName(name);
    }

    @Transactional
    public SyncTaskDto create(SyncTaskDto dto) {
        return createInternal(dto);
    }

    /** 只接受由 DatasetTaskSnapshotAssembler 生成的最终任务快照。 */
    @Transactional
    public SyncTaskDto createResolvedSnapshot(SyncTaskDto dto) {
        Map<String, Object> values = parseDataCharacteristics(dto == null ? null : dto.getDataCharacteristics());
        if (!"STANDARD_DATASET_ROUTE".equals(values.get("fillSource"))
                || values.get("standardDatasetId") == null
                || values.get("institutionDatasetRouteId") == null
                || !hasSnapshotValue(values, "standardContractHash")
                || values.get("routeRevision") == null) {
            throw new IllegalArgumentException(
                    "统一创建核心只接受包含 standardContractHash、routeRevision 的完整静态路由快照");
        }
        return createInternal(dto);
    }

    private static boolean hasSnapshotValue(Map<String, Object> values, String field) {
        Object value = values.get(field);
        return value != null && !value.toString().isBlank();
    }

    private SyncTaskDto createInternal(SyncTaskDto dto) {
        validateDto(dto, true, null);

        // ── 机构继承（spec institution-management 任务 4.2）──
        // dto.institutionId 显式提供时使用 dto 值；为空且 sourceDataSourceId 非空时
        // 从 source_data_source.institution_id 继承（仍允许结果为 null，对应数据源未关联机构）
        if (dto.getInstitutionId() == null && dto.getSourceDataSourceId() != null) {
            sourceRepo.findById(dto.getSourceDataSourceId()).ifPresent(src -> {
                if (src.getInstitutionId() != null) {
                    dto.setInstitutionId(src.getInstitutionId());
                    log.info("SyncTaskService.create: inherited institutionId={} from sourceDataSourceId={}",
                            src.getInstitutionId(), src.getId());
                }
            });
        }

        // ── 普通创建入口：一次只创建一个源端对象；跨机构/数据源批量创建走批量模板入口 ──
        List<String> tables = dto.getViewNames() != null ? dto.getViewNames() : List.of();
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("必须选择至少一个源端表/视图");
        }

        List<Long> createdTaskIds = new ArrayList<>();
        SyncTaskDto firstResult = null;
        boolean multiTable = tables.size() > 1;
        for (String tableName : tables) {
            SyncTaskDto result = persistSingleSourceTask(dto, tableName, multiTable);
            if (result.getId() != null) {
                createdTaskIds.add(result.getId());
            }
            if (firstResult == null) {
                firstResult = result;
            }
        }

        if (firstResult == null) {
            throw new IllegalStateException("未创建任何同步任务");
        }
        firstResult.setCreatedTaskIds(createdTaskIds);
        log.info("SyncTaskService.create: sourceObjects={} created tasks={}", tables, createdTaskIds);
        return firstResult;
    }

    private SyncTaskDto persistSingleSourceTask(
            SyncTaskDto dto,
            String tableName,
            boolean multiTable) {
        String userBaseName = (dto.getName() != null && !dto.getName().isBlank()) ? dto.getName().trim() : null;

        SyncTask entity = new SyncTask();
        String dataCharacteristics = enrichMedicalContractCharacteristics(dto, tableName);
        copyToEntity(dto, entity);
        entity.setDataCharacteristics(dataCharacteristics);
        entity.setViewNames(List.of(tableName));
        entity.setTargetTableMap(filterJsonMapForTable(dto.getTargetTableMap(), tableName));
        entity.setFilterConditionMap(filterJsonMapForTable(dto.getFilterConditionMap(), tableName));
        if (userBaseName != null) {
            entity.setName(multiTable ? userBaseName + "-" + tableName : userBaseName);
        } else {
            entity.setName(buildAutoName(entity));
        }

        SyncTask saved = repository.save(entity);

        applyViewConfigsForNewTask(dto, saved, tableName);
        applyValidationConfigForNewTask(dto, saved);
        applyMessageConfigForNewTask(dto, saved);

        SyncTaskDto result = toDto(saved);
        result.setCreatedTaskIds(saved.getId() == null ? List.of() : List.of(saved.getId()));
        // 同步 Quartz（有 cron 且 ENABLED）— 放到事务提交后，避免业务回滚但 Quartz 已注册。
        // 返回 DTO 也交给 afterCommit 回填，避免调度失败时 API 仍暴露旧的 ENABLED 成功态。
        scheduleQuartzAfterCommit(saved, result);
        log.info("SyncTaskService.create: sourceObject={} created task={}", tableName, saved.getId());
        return result;
    }

    private String enrichMedicalContractCharacteristics(
            SyncTaskDto dto,
            String tableName) {
        if (medicalMappingService == null || dto == null || tableName == null || tableName.isBlank()
                || isCustomSql(dto) || dto.getSourceDataSourceId() == null) {
            return dto == null ? null : dto.getDataCharacteristics();
        }
        if (isDfetlDatasetConfigGeneratedTask(dto)) {
            return dto.getDataCharacteristics();
        }
        MedicalMappingPlanResponse plan;
        try {
            plan = medicalMappingService.plan(new MedicalMappingBatchPlanRequest(
                    dto.getSourceDataSourceId(),
                    dto.getSourceSchema(),
                    List.of(tableName),
                    Map.of(),
                    null));
        } catch (Exception e) {
            log.warn("SyncTaskService.create: medical contract plan skipped for sourceObject={} err={}",
                    tableName, e.getMessage());
            return dto.getDataCharacteristics();
        }
        if (plan == null || plan.results() == null || plan.results().isEmpty()) {
            return dto.getDataCharacteristics();
        }
        MedicalMappingPrecheckResult result = plan.results().get(0);
        if (result == null || result.contract() == null
                || result.status() == MedicalMappingPrecheckStatus.UNMATCHED
                || result.status() == MedicalMappingPrecheckStatus.EXISTING) {
            return dto.getDataCharacteristics();
        }
        if (result.status() == MedicalMappingPrecheckStatus.BLOCKED) {
            throw new IllegalArgumentException("医共体预检阻断创建: sourceObject=" + tableName
                    + " blockers=" + blockerSummary(result.findings()));
        }
        throw new IllegalArgumentException("STANDARD_DATASET_ROUTE_REQUIRED: dataset="
                + result.contract().datasetCode()
                + ", 医共体数据集任务必须先同步标准模型、配置并启用机构路由"
                + "，再通过 POST /api/sync-task 提交 institutionId + datasetId");
    }

    private boolean isDfetlDatasetConfigGeneratedTask(SyncTaskDto dto) {
        Map<String, Object> values = parseDataCharacteristics(dto == null ? null : dto.getDataCharacteristics());
        Object fillSource = values.get("fillSource");
        if (fillSource != null && "STANDARD_DATASET_ROUTE".equalsIgnoreCase(fillSource.toString())) {
            return values.get("standardDatasetId") != null
                    && values.get("institutionDatasetRouteId") != null;
        }
        return false;
    }

    private Map<String, Object> parseDataCharacteristics(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            log.warn("SyncTaskService.create: dataCharacteristics JSON invalid, medical metadata will overwrite it: {}",
                    e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private List<String> ignoredSourceFields(List<MedicalPrecheckFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        return findings.stream()
                .filter(finding -> finding != null && "EXTRA_SOURCE_FIELD".equals(finding.code()))
                .map(MedicalPrecheckFinding::field)
                .filter(field -> field != null && !field.isBlank())
                .toList();
    }

    private String blockerSummary(List<MedicalPrecheckFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return "[]";
        }
        return findings.stream()
                .filter(finding -> finding != null && finding.severity() == com.dfygt.dfetl.server.medical.precheck.MedicalPrecheckSeverity.BLOCKER)
                .map(finding -> finding.code() + ":" + finding.field())
                .toList()
                .toString();
    }

    private void ensureInitialValidationRun(SyncTask saved) {
        if (saved.getId() == null || validationRunRepository.existsByTaskId(saved.getId())) {
            return;
        }
        com.dfygt.dfetl.server.entity.ValidationRun vr = new com.dfygt.dfetl.server.entity.ValidationRun();
        vr.setName(saved.getName());
        vr.setTaskId(saved.getId());
        vr.setMethod("ROW_COUNT");
        vr.setMode("ROW_COUNT");
        vr.setScope("FULL");
        vr.setLegacyExecId(0L);
        vr.setStatus("PENDING");
        validationRunRepository.save(vr);
    }

    private void applyValidationConfigForNewTask(SyncTaskDto dto, SyncTask saved) {
        if (dto.getValidationConfig() != null) {
            validationConfigApplyService.saveForNewTask(saved.getId(), dto.getValidationConfig());
        } else if (globalSettingsService.isEnforceValidation()
                && !validationConfigRepo.findByTaskId(saved.getId()).isPresent()) {
            // spec 047：若全局开启「强制校验配置」且任务未配置 → 自动注入一份 enabled=true 默认配置
            TaskValidationConfig cfg = new TaskValidationConfig();
            cfg.setTaskId(saved.getId());
            cfg.setEnabled(true);
            cfg.setMethod(null);
            cfg.setAutoTrigger(null);
            validationConfigRepo.save(cfg);
            log.info("SyncTaskService.create: enforce-validation auto-injected default config for task={}", saved.getId());
        }
    }

    private void applyViewConfigsForNewTask(SyncTaskDto dto, SyncTask saved, String tableName) {
        if (dto.getViewConfigs() == null || dto.getViewConfigs().isEmpty() || saved.getId() == null) return;
        for (TaskViewConfigDto item : dto.getViewConfigs()) {
            if (item == null || item.getViewName() == null
                    || !item.getViewName().equalsIgnoreCase(tableName)) continue;
            TaskViewConfig entity = new TaskViewConfig();
            entity.setTaskId(saved.getId());
            entity.setViewName(tableName);
            entity.setFieldMappings(item.getFieldMappings());
            entity.setDorisDdl(item.getDorisDdl());
            viewConfigRepo.save(entity);
        }
    }

    private void applyMessageConfigForNewTask(SyncTaskDto dto, SyncTask saved) {
        var input = dto.getMessagePublishConfig();
        if (input == null || saved.getId() == null) return;
        String topic = input.getTopic();
        if (topic == null || topic.isBlank() || input.getMessageType() == null || input.getMessageType().isBlank()) {
            throw new IllegalArgumentException("消息配置 topic / messageType 不能为空");
        }
        for (MessagePublishConfig other : messagePublishConfigRepository.findByChannel(topic)) {
            if (!input.getMessageType().equals(other.getMessageType())) {
                throw new IllegalArgumentException("Topic '" + topic + "' 已绑定不同 routeKey");
            }
        }
        MessagePublishConfig entity = new MessagePublishConfig();
        entity.setTaskId(saved.getId());
        entity.setEnabled(input.isEnabled());
        entity.setChannel(input.getChannel() == null || input.getChannel().isBlank() ? topic : input.getChannel());
        entity.setMessageType(input.getMessageType());
        entity.setTopic(topic);
        entity.setMessageKeyTemplate(input.getMessageKeyTemplate());
        entity.setFullSyncMode(input.getFullSyncMode() == null || input.getFullSyncMode().isBlank()
                ? "SKIP" : input.getFullSyncMode().toUpperCase(Locale.ROOT));
        entity.setRateLimit(input.getRateLimit());
        entity.setPageSize(input.getPageSize() == null ? 1000 : input.getPageSize());
        entity.setSourceSystem(input.getSourceSystem());
        entity.setTenantId(input.getTenantId());
        entity.setFieldMappingJson(input.getFieldMappingJson());
        messagePublishConfigRepository.save(entity);
    }

    @Transactional
    public SyncTaskDto update(Long id, SyncTaskDto dto) {
        // RUNNING 守卫：防止管理操作绕过执行链路的并发防护，与运行中的 execution 产生数据竞态
        assertNoRunningExecution(id, "update");
        SyncTask entity = getOrThrow(id);
        if (dto.getName() == null || dto.getName().isBlank()) {
            dto.setName(entity.getName());
        }
        validateDto(dto, false, id);
        validateInitialWatermarkUpdate(entity, dto);
        copyToEntity(dto, entity);
        SyncTask saved = repository.save(entity);
        SyncTaskDto result = toDto(saved);
        // Quartz 操作放到事务提交后执行：JDBC JobStore 是独立事务，
        // 与业务事务不一致会造成 split-brain（事务回滚但 Quartz 已变 / 反之）。
        scheduleQuartzAfterCommit(saved, result);
        return result;
    }

    @Transactional
    public SyncTaskDto disableSchedule(Long id) {
        return disableScheduleInternal(id);
    }

    private SyncTaskDto disableScheduleInternal(Long id) {
        SyncTask entity = getOrThrow(id);
        entity.setStatus("DISABLED");
        SyncTask saved = repository.save(entity);
        SyncTaskDto result = toDto(saved);
        scheduleQuartzAfterCommit(saved, result);
        return result;
    }

    @Transactional
    public SyncTaskDto enableSchedule(Long id) {
        SyncTask entity = getOrThrow(id);
        entity.setStatus("ENABLED");
        SyncTask saved = repository.save(entity);
        SyncTaskDto result = toDto(saved);
        scheduleQuartzAfterCommit(saved, result);
        return result;
    }

    @Transactional
    public void delete(Long id) {
        // RUNNING 守卫：禁止删除正在执行的任务，避免 task_execution 被删但 SeaTunnel job 仍写 Doris
        assertNoRunningExecution(id, "delete");
        SyncTask entity = getOrThrow(id);
        // 1. 校验域子表必须在 validation_run 之前显式清理，不能依赖环境中偶然存在的级联约束。
        verifyDiffFieldRepository.deleteByTaskId(id);
        verifyDiffRepository.deleteByTaskId(id);
        verifyChunkRepository.deleteByTaskId(id);
        // 2. 获取所有 execution IDs（task_chunk 通过 execution_id FK 引用）
        List<Long> execIds = executionRepo.findIdByTaskId(id);
        // 3. 先删 task_chunk（FK → task_execution.id）
        if (!execIds.isEmpty()) {
            chunkRepo.deleteByExecutionIdIn(execIds);
        }
        // 4. 删 dirty_record（FK → task_execution.id AND sync_task.id，必须在 task_execution 之前）
        dirtyRecordRepo.deleteByTaskId(id);
        // 5. 删 task_execution（FK → sync_task.id）
        executionRepo.deleteByTaskId(id);
        // 6. 删 task_snapshot_key
        snapshotKeyRepo.deleteByTaskId(id);
        // 7. 删 task_view_config
        viewConfigRepo.deleteByTaskId(id);
        // 8. 删 task_validation_config
        validationConfigRepo.deleteByTaskId(id);
        // 9. 删 validation_run
        validationRunRepository.deleteByTaskId(id);
        // 10. 消息发布配置属于任务配置；发送记录/日志作为审计数据保留。
        messagePublishConfigRepository.deleteByTaskId(id);
        // 11. 删外部 API 幂等/审计记录（FK → sync_task.id）
        if (externalTaskRequestRepository != null) {
            externalTaskRequestRepository.deleteByTaskId(id);
        }
        // 12. 最终删除 sync_task
        repository.deleteById(id);
        // 13. Quartz 调度清理放到事务提交后执行（独立事务，避免业务回滚造成 split-brain）
        deleteQuartzAfterCommit(id);
        log.info("SyncTaskService.delete: taskId={} and {} executions removed", id, execIds.size());
    }

    /**
     * 校验任务是否可安全执行管理操作。
     * RUNNING 或尚未人工关闭的 RECONCILE_REQUIRED 都会被拒绝，避免数据库状态看似终止、
     * 但远端 SeaTunnel job 仍可能写入时修改任务、水位或目标表。
     *
     * <p>注意：本检查基于数据库的 task_execution 表，对多 server 实例可见；
     * 但 {@code TaskExecutionQueue.activeTaskIds} 是 JVM 本地的，跨实例不可见——
     * 这与现有「同步/校验并发防护仍是 JVM 本地级别」P2 同源，治理在另一专项。
     */
    public void assertNoRunningExecution(Long taskId, String operation) {
        long running = executionRepo.countByTaskIdAndStatus(taskId, "RUNNING");
        if (running > 0) {
            throw new IllegalStateException(
                    "任务存在 RUNNING 执行记录，拒绝 " + operation + " 操作：taskId=" + taskId
                            + "，请先等待执行完成或调用 cancel 接口");
        }
        long unresolved = executionRepo.countByTaskIdAndStatusAndReconcileHandled(
                taskId, ExecutionResult.STATUS_RECONCILE_REQUIRED, false);
        if (unresolved > 0) {
            throw new IllegalStateException(
                    "任务存在未处理的 RECONCILE_REQUIRED 执行记录，拒绝 " + operation
                            + " 操作：taskId=" + taskId
                            + "，请先确认远端 SeaTunnel job 已终止并关闭人工核对待办");
        }
        int runningValidation = validationRunRepository.countRunningByTaskId(taskId);
        if (runningValidation > 0) {
            throw new IllegalStateException(
                    "任务存在 validation RUNNING 记录，拒绝 " + operation + " 操作：taskId=" + taskId
                            + "，请先等待校验完成");
        }
    }

    /**
     * 把 Quartz 调度同步操作注册到当前事务的 AFTER_COMMIT 回调中。
     * <p>JDBC JobStore 使用独立事务，与 Spring 业务事务不在同一边界；
     * 如果在事务内同步 Quartz，业务事务回滚时 Quartz 已经变更，造成 split-brain。
     * <p>没有活动事务时（如手动调用），直接同步执行——保证向后兼容。
     */
    private void scheduleQuartzAfterCommit(SyncTask task, SyncTaskDto result) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    syncQuartz(task);
                    syncScheduleStateToDto(task, result);
                }
            });
        } else {
            syncQuartz(task);
            syncScheduleStateToDto(task, result);
        }
    }

    /**
     * 把 Quartz 调度删除注册到当前事务的 AFTER_COMMIT 回调中。
     */
    private void deleteQuartzAfterCommit(Long taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        quartzSchedulerService.deleteTask(taskId);
                    } catch (Exception e) {
                        log.warn("deleteQuartzAfterCommit: failed to delete taskId={}", taskId, e);
                    }
                    // 任务删除路径不会再经过 TaskValidationConfigService.delete，必须显式清理 drift-watch。
                    quartzSchedulerService.deleteDriftWatch(taskId);
                    quartzSchedulerService.deleteSnapshotDetect(taskId);
                }
            });
        } else {
            try {
                quartzSchedulerService.deleteTask(taskId);
            } catch (Exception e) {
                log.warn("deleteQuartzAfterCommit: failed to delete taskId={}", taskId, e);
            }
            quartzSchedulerService.deleteDriftWatch(taskId);
            quartzSchedulerService.deleteSnapshotDetect(taskId);
        }
    }

    // ── private helpers ────────────────────────────────────────────────

    /** 根据任务的 schedule 和 status 同步 Quartz 注册状态 */
    private void syncQuartz(SyncTask task) {
        // spec 053：优先使用新的 cronExpression；向后兼容旧 schedule 字段
        String cron = task.getCronExpression();
        if (cron == null || cron.isBlank()) cron = task.getSchedule();
        boolean enabled = "ENABLED".equals(task.getStatus());
        if (cron != null && !cron.isBlank() && enabled) {
            try {
                quartzSchedulerService.scheduleTask(task.getId(), cron);
            } catch (Exception e) {
                log.warn("syncQuartz: failed to schedule taskId={} cron={}", task.getId(), cron, e);
                markScheduleFailed(task, "sync-task", e);
                return;
            }
        } else {
            // 无 cron 或 disabled，确保 Quartz 中没有残留
            try {
                quartzSchedulerService.deleteTask(task.getId());
            } catch (Exception e) {
                log.warn("syncQuartz: failed to delete taskId={}", task.getId(), e);
                markScheduleFailed(task, "sync-task-delete", e);
                return;
            }
        }
        // spec 020.2：同步 snapshot detect 调度（与业务 cron 独立 group）
        String detectCron = task.getSnapshotAutoDetectCron();
        boolean detectActive = enabled
                && Boolean.TRUE.equals(task.getSnapshotAutoCapture())
                && detectCron != null && !detectCron.isBlank();
        if (detectActive) {
            try {
                quartzSchedulerService.scheduleSnapshotDetect(task.getId(), detectCron);
            } catch (Exception e) {
                log.warn("syncQuartz: failed to schedule snapshot-detect taskId={} cron={}",
                        task.getId(), detectCron, e);
                markScheduleFailed(task, "snapshot-detect", e);
            }
        } else {
            quartzSchedulerService.deleteSnapshotDetect(task.getId());
        }
    }

    private void markScheduleFailed(SyncTask task, String scheduleType, Exception e) {
        SyncTask persisted = scheduleFailureRecorder.markFailed(
                task.getId(), scheduleType, e == null ? null : e.getMessage());
        // afterCommit 回调仍需同步返回 DTO 所引用的 detached entity 内存态。
        task.setStatus(persisted.getStatus());
        task.setLastRunStatus(persisted.getLastRunStatus());
        task.setAlertStatus(persisted.getAlertStatus());
        task.setUpdatedAt(persisted.getUpdatedAt());
        log.warn("syncQuartz: taskId={} marked FAILED due to {} schedule failure: {}",
                task.getId(), scheduleType, e.getMessage());
    }

    private void syncScheduleStateToDto(SyncTask task, SyncTaskDto dto) {
        if (task == null || dto == null) {
            return;
        }
        dto.setStatus(task.getStatus());
        dto.setLastRunStatus(task.getLastRunStatus());
        dto.setAlertStatus(task.getAlertStatus());
        dto.setUpdatedAt(task.getUpdatedAt());
    }

    private SyncTask getOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + id));
    }

    /** 业务规则校验（创建 + 更新通用）*/
    private void validateDto(SyncTaskDto dto,
                             boolean allowMultiTableCreationSplit,
                             Long currentTaskId) {
        normalizeSourceMode(dto);
        normalizeEnumFields(dto);
        validateEnumDomains(dto);
        validateFastStaticGuards(dto);
        validateSourceDataSourceExists(dto);
        validateTargetDataSourceExists(dto);
        // 0. 静态门禁之后做元数据探测：自动纠正 objectType + 列存在性校验。
        //    需早于 VIEW 能力门禁，让规则 6 能用到纠正后的 objectType。
        validateAgainstSourceMetadata(dto);
        validateViewAcceptance(dto);
        // 0.1 viewNames 字符集白名单校验 — 防御性，避免后续任何 SQL 拼接路径成为注入面。
        // 元数据探测的 listColumns 失败仅是表象阻断，根因是字符集未校验；这里集中收口。
        // CUSTOM_SQL 模式的逻辑表名由后端生成，跳过校验。
        if (!"CUSTOM_SQL".equalsIgnoreCase(dto.getSourceMode())
                && dto.getViewNames() != null) {
            for (String view : dto.getViewNames()) {
                if (view == null || view.isBlank()) continue;
                if (!com.dfygt.dfetl.server.common.IdentifierSanitizer.isValid(view)) {
                    throw new IllegalArgumentException(
                            "源端表名/视图名格式非法，仅允许 [A-Za-z_][A-Za-z0-9_]{0,127}：" + view);
                }
            }
        }
        // 1. staticFilter 安全校验
        if (!whereClauseBuilder.isStaticFilterSafe(dto.getStaticFilter())) {
            throw new IllegalArgumentException("静态过滤条件包含不允许的关键字（SELECT/DROP/EXEC 等）");
        }
        if (!whereClauseBuilder.isFilterConditionMapSafe(dto.getFilterConditionMap())) {
            throw new IllegalArgumentException("filterConditionMap 包含不允许的关键字或解析失败");
        }
        validateFilterConditionMapKeys(dto);
        // 2. incrementalField 格式校验
        if (!whereClauseBuilder.isFieldNameSafe(dto.getIncrementalField())) {
            throw new IllegalArgumentException("增量字段名格式非法，只允许字母、数字、下划线，且首字符为字母或下划线");
        }
        // 3. TRUNCATE + INCREMENTAL 互斥
        if ("TRUNCATE".equals(dto.getSyncMode()) && "INCREMENTAL".equals(dto.getDataScope())) {
            throw new IllegalArgumentException("TRUNCATE 模式不支持 INCREMENTAL 数据范围");
        }
        // 4. INCREMENTAL 必须填写 incrementalField
        if ("INCREMENTAL".equals(dto.getDataScope())
                && (dto.getIncrementalField() == null || dto.getIncrementalField().isBlank())) {
            throw new IllegalArgumentException("INCREMENTAL 模式必须填写增量字段（incrementalField）");
        }
        validateCustomWindowOptions(dto);
        validateMultiTableIncrementalDto(dto, allowMultiTableCreationSplit);
        // 5. UPSERT 必须填写 upsertKeys
        if ("UPSERT".equals(dto.getSyncMode())
                && (dto.getUpsertKeys() == null || dto.getUpsertKeys().isEmpty())) {
            throw new IllegalArgumentException("UPSERT 模式必须填写 upsertKeys");
        }
        // 6. 源对象为 VIEW 时的能力门禁
        String objType = dto.getSourceObjectType();
        if ("VIEW".equals(objType)) {
            if (dto.getSplitPk() != null && !dto.getSplitPk().isBlank()) {
                throw new IllegalArgumentException("视图源不支持 splitPk 分片，请清空该字段或将源对象类型改为 TABLE / MATERIALIZED_VIEW");
            }
            if ("INCREMENTAL".equals(dto.getDataScope()) && "ID_RANGE".equals(dto.getIncrementMode())) {
                throw new IllegalArgumentException("视图源不支持 ID_RANGE 增量（MAX(id) 在聚合视图上不单调），请改为 TIME_FIELD");
            }
        }
        // 7. softDeleteField 字段名安全校验
        if (dto.getSoftDeleteField() != null && !dto.getSoftDeleteField().isBlank()
                && !whereClauseBuilder.isFieldNameSafe(dto.getSoftDeleteField())) {
            throw new IllegalArgumentException("软删除字段名格式非法");
        }
        // 7.1 targetTableMap JSON 格式校验 + 源表归属校验
        if (dto.getTargetTableMap() != null && !dto.getTargetTableMap().isBlank()) {
            try {
                Map<String, String> map = new ObjectMapper()
                        .readValue(dto.getTargetTableMap(), new TypeReference<Map<String, String>>() {});
                java.util.Set<String> viewSet = dto.getViewNames() != null
                        ? new java.util.HashSet<>(dto.getViewNames())
                        : java.util.Set.of();
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    String srcKey = entry.getKey();
                    String tgtVal = entry.getValue();
                    if (srcKey == null || srcKey.isBlank()) {
                        throw new IllegalArgumentException("targetTableMap 中源表名不能为空");
                    }
                    if (tgtVal == null || tgtVal.isBlank()) {
                        throw new IllegalArgumentException("targetTableMap 中目标表名不能为空: " + srcKey);
                    }
                    if (!tgtVal.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                        throw new IllegalArgumentException("targetTableMap 中目标表名格式非法: " + tgtVal);
                    }
                    // P2-2：源表 key 必须属于当前任务的 viewNames
                    if (!viewSet.isEmpty() && !viewSet.contains(srcKey)) {
                        throw new IllegalArgumentException("targetTableMap 中源表 '" + srcKey + "' 不在当前任务的 viewNames 中");
                    }
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("targetTableMap JSON 格式错误: " + e.getMessage());
            }
        }
        validateEffectiveTargetTables(dto);
        sharedTargetTableGuard.assertTruncateSafe(
                currentTaskId,
                dto.getTargetDataSourceId(),
                dto.getViewNames(),
                dto.getTargetTableMap(),
                dto.getSyncMode());
        // 8. enableDorisMerge 前提：UPSERT + UNIQUE_KEY（softDeleteField 可选，留空则不检测删除）
        if (Boolean.TRUE.equals(dto.getEnableDorisMerge())) {
            if (!"UPSERT".equals(dto.getSyncMode())) {
                throw new IllegalArgumentException("enableDorisMerge 仅在 syncMode=UPSERT 时可用");
            }
            String model = dto.getDorisTableModel();
            if (model != null && !model.isBlank() && !"UNIQUE_KEY".equals(model)) {
                throw new IllegalArgumentException("enableDorisMerge 仅支持 dorisTableModel=UNIQUE_KEY（当前为 " + model + "）");
            }
        }
        // 9. sequenceCol 字段名白名单
        if (dto.getSequenceCol() != null && !dto.getSequenceCol().isBlank()
                && !whereClauseBuilder.isFieldNameSafe(dto.getSequenceCol())) {
            throw new IllegalArgumentException("sequenceCol 字段名格式非法");
        }
        // 10. partialColumns 仅在 UPSERT 生效
        if (Boolean.TRUE.equals(dto.getPartialColumns()) && !"UPSERT".equals(dto.getSyncMode())) {
            throw new IllegalArgumentException("partialColumns 仅在 syncMode=UPSERT 时生效");
        }
        // 11. lookbackSeconds 不能为负
        if (dto.getLookbackSeconds() != null && dto.getLookbackSeconds() < 0) {
            throw new IllegalArgumentException("lookbackSeconds 不能为负数");
        }
        validateIncrementalWatermarkOptions(dto);
        // 12. spec 020.2：snapshot 自动调度字段
        if (Boolean.TRUE.equals(dto.getSnapshotAutoCapture())
                && !Boolean.TRUE.equals(dto.getEnableSnapshotDelete())) {
            throw new IllegalArgumentException("snapshotAutoCapture=true 必须先启用 enableSnapshotDelete");
        }
        if (dto.getSnapshotAutoDetectCron() != null && !dto.getSnapshotAutoDetectCron().isBlank()) {
            String c = dto.getSnapshotAutoDetectCron().trim();
            try {
                String quartzCron = QuartzSchedulerService.normalizeCronExpression(c);
                if (!org.quartz.CronExpression.isValidExpression(quartzCron)) {
                    throw new IllegalArgumentException("snapshotAutoDetectCron 不是合法的 cron 表达式: " + c);
                }
            } catch (IllegalArgumentException ie) {
                throw ie;
            } catch (Exception e) {
                throw new IllegalArgumentException("snapshotAutoDetectCron 解析失败: " + e.getMessage());
            }
        }
        if (Boolean.TRUE.equals(dto.getSnapshotAutoApply())
                && !Boolean.TRUE.equals(dto.getSnapshotAutoCapture())) {
            throw new IllegalArgumentException("snapshotAutoApply=true 必须先启用 snapshotAutoCapture");
        }
        if (dto.getSnapshotDeleteMaxRatio() != null) {
            java.math.BigDecimal r = dto.getSnapshotDeleteMaxRatio();
            if (r.signum() <= 0 || r.compareTo(java.math.BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("snapshotDeleteMaxRatio 必须在 (0,1] 范围");
            }
        }
        // 14. enableSnapshotDelete 前提：upsertKeys 非空 + 仅 1 列
        if (Boolean.TRUE.equals(dto.getEnableSnapshotDelete())) {
            if (isCustomSql(dto)) {
                throw new IllegalArgumentException("自定义 SQL 模式暂不支持 enableSnapshotDelete");
            }
            List<String> keys = dto.getUpsertKeys();
            if (keys == null || keys.isEmpty()) {
                throw new IllegalArgumentException("enableSnapshotDelete 需同时填写 upsertKeys");
            }
            if (keys.size() != 1) {
                throw new IllegalArgumentException("enableSnapshotDelete MVP 仅支持单列主键，当前为 " + keys.size() + " 列");
            }
            if (!whereClauseBuilder.isFieldNameSafe(keys.get(0))) {
                throw new IllegalArgumentException("upsertKeys 列名格式非法: " + keys.get(0));
            }
        }
        // 15. executorType 收敛为单一 cluster 策略 + SeaTunnel 启用前置
        String et = dto.getExecutorType();
        if (et != null && !et.isBlank()) {
            if (!"SEATUNNEL_CLUSTER".equals(et)) {
                throw new IllegalArgumentException("当前仅支持 executorType=SEATUNNEL_CLUSTER，收到: " + et);
            }
            if (!seaTunnelProperties.enabled()) {
                throw new IllegalArgumentException(
                        "SeaTunnel 执行器未启用：请在 application.yml 中设置 dfetl.executor.seatunnel.enabled=true 并完成 seatunnel/install.sh");
            }
        }
    }

    private void validateTargetDataSourceExists(SyncTaskDto dto) {
        if (dto == null || dto.getTargetDataSourceId() == null) {
            throw new IllegalArgumentException("targetDataSourceId 不能为空");
        }
        if (targetRepo.findById(dto.getTargetDataSourceId()).isEmpty()) {
            throw new IllegalArgumentException("TargetDataSource not found: " + dto.getTargetDataSourceId());
        }
    }

    private void validateSourceDataSourceExists(SyncTaskDto dto) {
        if (dto == null || dto.getSourceDataSourceId() == null) {
            throw new IllegalArgumentException("sourceDataSourceId 不能为空");
        }
        if (sourceRepo.findById(dto.getSourceDataSourceId()).isEmpty()) {
            throw new IllegalArgumentException("SourceDataSource not found: " + dto.getSourceDataSourceId());
        }
    }

    private void validateFastStaticGuards(SyncTaskDto dto) {
        if (dto.getSoftDeleteField() != null && !dto.getSoftDeleteField().isBlank()
                && !whereClauseBuilder.isFieldNameSafe(dto.getSoftDeleteField())) {
            throw new IllegalArgumentException("软删除字段名格式非法");
        }
        if (dto.getSequenceCol() != null && !dto.getSequenceCol().isBlank()
                && !whereClauseBuilder.isFieldNameSafe(dto.getSequenceCol())) {
            throw new IllegalArgumentException("sequenceCol 字段名格式非法");
        }
        if (Boolean.TRUE.equals(dto.getEnableSnapshotDelete())) {
            if (isCustomSql(dto)) {
                throw new IllegalArgumentException("自定义 SQL 模式暂不支持 enableSnapshotDelete");
            }
            List<String> keys = dto.getUpsertKeys();
            if (keys == null || keys.isEmpty()) {
                throw new IllegalArgumentException("enableSnapshotDelete 需同时填写 upsertKeys");
            }
            if (keys.size() != 1) {
                throw new IllegalArgumentException("enableSnapshotDelete MVP 仅支持单列主键，当前为 " + keys.size() + " 列");
            }
            if (!whereClauseBuilder.isFieldNameSafe(keys.get(0))) {
                throw new IllegalArgumentException("upsertKeys 列名格式非法: " + keys.get(0));
            }
        }
    }

    private void validateEffectiveTargetTables(SyncTaskDto dto) {
        if (dto.getViewNames() == null || dto.getViewNames().isEmpty()) {
            return;
        }
        Map<String, String> targetMap = Map.of();
        if (dto.getTargetTableMap() != null && !dto.getTargetTableMap().isBlank()) {
            try {
                targetMap = new ObjectMapper()
                        .readValue(dto.getTargetTableMap(), new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("targetTableMap JSON 格式错误: " + e.getMessage());
            }
        }
        for (String viewName : dto.getViewNames()) {
            if (viewName == null || viewName.isBlank()) {
                continue;
            }
            String targetTable = targetMap.getOrDefault(viewName, viewName);
            String normalized = targetTable.toLowerCase(Locale.ROOT);
            if (!com.dfygt.dfetl.server.common.IdentifierSanitizer.isValid(normalized)) {
                throw new IllegalArgumentException("目标表名格式非法: " + targetTable
                        + "；CUSTOM_SQL 逻辑名不能直接作为 Doris 表名时，请通过 targetTableMap 映射到安全表名");
            }
        }
    }

    private String filterJsonMapForTable(String json, String tableName) {
        if (json == null || json.isBlank() || tableName == null || tableName.isBlank()) {
            return null;
        }
        try {
            Map<String, String> map = OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, String>>() {});
            String value = map.get(tableName);
            if (value == null) {
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(tableName)) {
                        value = entry.getValue();
                        break;
                    }
                }
            }
            if (value == null || value.isBlank()) {
                return null;
            }
            return OBJECT_MAPPER.writeValueAsString(Map.of(tableName, value));
        } catch (Exception e) {
            throw new IllegalArgumentException("表级配置 JSON 格式错误: " + e.getMessage(), e);
        }
    }

    private void validateMultiTableIncrementalDto(SyncTaskDto dto, boolean allowMultiTableCreationSplit) {
        List<String> tables = dto.getViewNames() == null ? List.of() : dto.getViewNames();
        if (allowMultiTableCreationSplit || tables.size() <= 1
                || !"INCREMENTAL".equalsIgnoreCase(dto.getDataScope())) {
            return;
        }
        if (Boolean.TRUE.equals(dto.getInitialFullSync())
                && !Boolean.TRUE.equals(dto.getInitialFullSyncDone())) {
            throw new IllegalArgumentException("multi-table FULL_THEN_INCREMENT sync is not supported");
        }
        if ("ID_RANGE".equalsIgnoreCase(dto.getIncrementMode())) {
            throw new IllegalArgumentException("multi-table ID_RANGE incremental sync is not supported");
        }
        throw new IllegalArgumentException("multi-table TIME_FIELD incremental sync is not supported");
    }

    private void normalizeSourceMode(SyncTaskDto dto) {
        String mode = dto.getSourceMode();
        if (mode == null || mode.isBlank()) mode = "TABLE_VIEW";
        mode = mode.trim().toUpperCase(Locale.ROOT);
        if (!"TABLE_VIEW".equals(mode) && !"CUSTOM_SQL".equals(mode)) {
            throw new IllegalArgumentException("sourceMode 仅支持 TABLE_VIEW / CUSTOM_SQL");
        }
        dto.setSourceMode(mode);
        if (!"CUSTOM_SQL".equals(mode)) return;

        String sql = CustomSqlValidator.requireReadOnlySelect(dto.getCustomSql());
        dto.setCustomSql(sql);
        String logicalName = dto.getCustomSqlName();
        if (logicalName == null || logicalName.isBlank()) {
            logicalName = "custom_sql";
        }
        logicalName = sanitize(logicalName.trim());
        if (logicalName.isBlank()) {
            throw new IllegalArgumentException("自定义 SQL 名称不能为空");
        }
        dto.setCustomSqlName(logicalName);
        dto.setViewNames(List.of(logicalName));
        dto.setSourceObjectType("CUSTOM_SQL");
        if (dto.getSplitPk() != null && !dto.getSplitPk().isBlank()) {
            throw new IllegalArgumentException("自定义 SQL 模式暂不支持 splitPk 分片");
        }
        if ("INCREMENTAL".equalsIgnoreCase(dto.getDataScope())
                && "ID_RANGE".equalsIgnoreCase(dto.getIncrementMode())) {
            throw new IllegalArgumentException("CUSTOM_SQL 模式暂不支持 ID_RANGE 增量，请改为 TIME_FIELD 或表/视图源");
        }
    }

    private void normalizeEnumFields(SyncTaskDto dto) {
        if (dto == null) {
            return;
        }
        dto.setSyncMode(normalizeEnum(dto.getSyncMode()));
        dto.setDataScope(normalizeEnum(dto.getDataScope()));
        dto.setIncrementMode(normalizeEnum(dto.getIncrementMode()));
        dto.setUpperBoundStrategy(normalizeEnum(dto.getUpperBoundStrategy()));
        dto.setDorisTableModel(normalizeEnum(dto.getDorisTableModel()));
        dto.setStatus(normalizeEnum(dto.getStatus()));
        dto.setVersionStatus(normalizeEnum(dto.getVersionStatus()));
        dto.setAlertStatus(normalizeEnum(dto.getAlertStatus()));
        dto.setExecutorType(normalizeEnum(dto.getExecutorType()));
        dto.setSourceObjectType(normalizeEnum(dto.getSourceObjectType()));
        dto.setWriterType(normalizeEnum(dto.getWriterType()));
        dto.setShardStrategy(normalizeEnum(dto.getShardStrategy()));
    }

    private String normalizeEnum(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private void validateEnumDomains(SyncTaskDto dto) {
        validateAllowedEnum("syncMode", dto.getSyncMode(), Set.of("TRUNCATE", "APPEND", "UPSERT"));
        validateAllowedEnum("dataScope", dto.getDataScope(), Set.of("FULL", "INCREMENTAL", "CUSTOM_WINDOW"));
        validateAllowedEnum("incrementMode", dto.getIncrementMode(), Set.of("TIME_FIELD", "ID_RANGE"));
        validateAllowedEnum("upperBoundStrategy", dto.getUpperBoundStrategy(),
                Set.of("CURRENT_TIME", "DELAY_MINUTES"));
        validateAllowedEnum("sourceObjectType", dto.getSourceObjectType(),
                Set.of("TABLE", "VIEW", "MATERIALIZED_VIEW", "CUSTOM_SQL"));
        validateAllowedEnum("executorType", dto.getExecutorType(), Set.of("SEATUNNEL_CLUSTER"));
    }

    private void validateAllowedEnum(String field, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw new IllegalArgumentException(field + " 不支持值 " + value + "，允许值: " + allowed);
        }
    }

    private void validateFilterConditionMapKeys(SyncTaskDto dto) {
        String json = dto.getFilterConditionMap();
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Map<String, String> filters = OBJECT_MAPPER.readValue(
                    json, new TypeReference<Map<String, String>>() {});
            if (filters.isEmpty()) {
                throw new IllegalArgumentException("filterConditionMap 不能为空对象");
            }
            Set<String> sourceObjects = dto.getViewNames() == null
                    ? Set.of()
                    : dto.getViewNames().stream()
                    .filter(name -> name != null && !name.isBlank())
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            Set<String> normalizedKeys = new HashSet<>();
            for (Map.Entry<String, String> entry : filters.entrySet()) {
                String key = entry.getKey();
                String filter = entry.getValue();
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("filterConditionMap 中源表名不能为空");
                }
                String normalizedKey = key.toLowerCase(Locale.ROOT);
                if (!normalizedKeys.add(normalizedKey)) {
                    throw new IllegalArgumentException("filterConditionMap 存在大小写冲突的重复源表: " + key);
                }
                if (!sourceObjects.contains(normalizedKey)) {
                    throw new IllegalArgumentException(
                            "filterConditionMap 中源表 '" + key + "' 不在当前任务的 viewNames 中");
                }
                if (filter == null || filter.isBlank()) {
                    throw new IllegalArgumentException("filterConditionMap 中过滤条件不能为空: " + key);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("filterConditionMap JSON 格式错误: " + e.getMessage(), e);
        }
    }

    /**
     * 后端权威执行视图准入，防止前端请求失败或直接 REST 调用绕过 C/D 档约束。
     */
    private void validateViewAcceptance(SyncTaskDto dto) {
        if (isCustomSql(dto) || dto.getViewNames() == null || dto.getViewNames().isEmpty()) {
            return;
        }
        boolean hasTableLike = false;
        boolean hasViewLike = false;
        for (String sourceObject : dto.getViewNames()) {
            if (sourceObject == null || sourceObject.isBlank()) {
                continue;
            }
            SourceDataSourceService.ViewAcceptance acceptance;
            try {
                acceptance = sourceDataSourceService.evaluateViewAcceptance(
                        dto.getSourceDataSourceId(), dto.getSourceSchema(), sourceObject);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "[table=" + sourceObject + "] View Acceptance 评估失败，拒绝保存: " + e.getMessage(), e);
            }
            String tier = acceptance == null || acceptance.tier() == null
                    ? null
                    : acceptance.tier().trim().toUpperCase(Locale.ROOT);
            if (tier == null || tier.isBlank()) {
                throw new IllegalArgumentException(
                        "[table=" + sourceObject + "] View Acceptance 未返回可验证档位，拒绝保存");
            }
            switch (tier) {
                case "A" -> hasTableLike = true;
                case "B" -> hasViewLike = true;
                case "C" -> {
                    hasViewLike = true;
                    if (!"FULL".equals(dto.getDataScope())) {
                        throw new IllegalArgumentException(
                                "[table=" + sourceObject + "] C 档视图仅支持 dataScope=FULL");
                    }
                }
                case "D" -> throw new IllegalArgumentException(
                        "[table=" + sourceObject + "] D 档视图不允许创建同步任务");
                default -> throw new IllegalArgumentException(
                        "[table=" + sourceObject + "] 未知 View Acceptance 档位: " + tier);
            }
        }
        if (hasTableLike && hasViewLike && dto.getViewNames().size() > 1) {
            throw new IllegalArgumentException(
                    "一次创建请求不能混合 TABLE/MATERIALIZED_VIEW 与普通 VIEW；请按源对象分别创建任务");
        }
        if (hasViewLike) {
            dto.setSourceObjectType("VIEW");
        }
    }

    private boolean isCustomSql(SyncTaskDto dto) {
        return dto != null && "CUSTOM_SQL".equalsIgnoreCase(dto.getSourceMode());
    }

    private void validateInitialWatermarkUpdate(SyncTask existing, SyncTaskDto incoming) {
        if (existing == null || incoming == null) {
            return;
        }
        if (!"ID_RANGE".equalsIgnoreCase(existing.getIncrementMode())) {
            return;
        }
        if (!hasRunState(existing)) {
            return;
        }
        String current = normalizeNullable(existing.getInitialWatermark());
        if (current == null) {
            return;
        }
        String requestedRaw = incoming.getInitialWatermark();
        if (requestedRaw == null) {
            return;
        }
        String requested = normalizeNullable(requestedRaw);
        if (current.equals(requested)) {
            return;
        }
        throw new IllegalArgumentException(
                "已运行的 ID_RANGE 任务不能通过普通 update 修改 initialWatermark，请使用 resetWatermark 接口调整水位");
    }

    private boolean hasRunState(SyncTask task) {
        return task.getLastRunTime() != null
                || task.getLastRunStatus() != null
                || task.getIncrementalCheckpoint() != null
                || Boolean.TRUE.equals(task.getInitialFullSyncDone());
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validateIncrementalWatermarkOptions(SyncTaskDto dto) {
        if (!"INCREMENTAL".equalsIgnoreCase(dto.getDataScope())) {
            return;
        }
        String incrementMode = dto.getIncrementMode() == null || dto.getIncrementMode().isBlank()
                ? "TIME_FIELD"
                : dto.getIncrementMode().trim().toUpperCase(Locale.ROOT);

        if ("APPEND".equalsIgnoreCase(dto.getSyncMode())
                && dto.getLookbackSeconds() != null
                && dto.getLookbackSeconds() > 0) {
            throw new IllegalArgumentException(
                    "APPEND 模式不支持 lookbackSeconds > 0：回看窗口会重复追加数据，请改用 UPSERT 或关闭回看");
        }
        if ("APPEND".equalsIgnoreCase(dto.getSyncMode())
                && Boolean.TRUE.equals(dto.getInitialFullSync())) {
            throw new IllegalArgumentException(
                    "APPEND 模式不支持 initialFullSync=true：首次全量未按准水位加上界，后续增量会重复追加数据，请改用 UPSERT");
        }

        String initialWatermark = dto.getInitialWatermark();
        if (initialWatermark == null || initialWatermark.isBlank()) {
            return;
        }
        String trimmed = initialWatermark.trim();
        try {
            if ("ID_RANGE".equals(incrementMode)) {
                long value = Long.parseLong(trimmed);
                if (value < 0) {
                    throw new NumberFormatException("negative ID watermark");
                }
            } else {
                java.time.Instant.parse(trimmed);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "initialWatermark 格式非法：" + incrementMode
                            + " 模式下应为 "
                            + ("ID_RANGE".equals(incrementMode) ? "整数 ID" : "ISO-8601 Instant，例如 2026-01-01T00:00:00Z"));
        }
    }

    private void validateCustomWindowOptions(SyncTaskDto dto) {
        if (!"CUSTOM_WINDOW".equalsIgnoreCase(dto.getDataScope())) {
            return;
        }
        if (dto.getIncrementalField() == null || dto.getIncrementalField().isBlank()) {
            throw new IllegalArgumentException("CUSTOM_WINDOW 模式必须填写 incrementalField");
        }
        String incrementMode = dto.getIncrementMode() == null || dto.getIncrementMode().isBlank()
                ? "TIME_FIELD"
                : dto.getIncrementMode().trim().toUpperCase(Locale.ROOT);
        if (!"TIME_FIELD".equals(incrementMode)) {
            throw new IllegalArgumentException("CUSTOM_WINDOW 模式仅支持 TIME_FIELD，不支持 " + incrementMode);
        }
        if (dto.getCustomWindowStart() == null || dto.getCustomWindowEnd() == null) {
            throw new IllegalArgumentException(
                    "CUSTOM_WINDOW 模式必须同时填写 customWindowStart 和 customWindowEnd，避免退化为全表读取");
        }
        if (!dto.getCustomWindowStart().isBefore(dto.getCustomWindowEnd())) {
            throw new IllegalArgumentException(
                    "CUSTOM_WINDOW 窗口非法：customWindowStart must be before customWindowEnd");
        }
    }

    /**
     * spec 019：探测源表/视图元数据，做两件事：
     *  - 字段名必须真实存在（incrementalField/softDeleteField/sequenceCol/splitPk/upsertKeys）
     *  - 若用户留默认 sourceObjectType=TABLE 但元数据 TABLE_TYPE=VIEW，自动纠正为 VIEW
     * 字段枚举失败必须 fail-fast：SeaTunnel 配置生成已禁止 SELECT * fallback，
     * 保存阶段若继续放行，会把可预见的字段 alias 风险推迟到执行期失败。
     */
    private void validateAgainstSourceMetadata(SyncTaskDto dto) {
        if (dto.getSourceDataSourceId() == null) return;
        if (isCustomSql(dto)) {
            validateCustomSqlColumns(dto);
            return;
        }
        if (dto.getViewNames() == null || dto.getViewNames().isEmpty()) return;
        String resolvedSchema = sourceRepo.findById(dto.getSourceDataSourceId())
                .map(source -> SourceSchemaResolver.resolveRequired(dto.getSourceSchema(), source))
                .orElseThrow(() -> new IllegalArgumentException(
                        "SourceDataSource not found: " + dto.getSourceDataSourceId()));
        dto.setSourceSchema(resolvedSchema);

        // 1) objectType 自动纠正（任务级标量，遍历视图列表，命中第一个 VIEW 即修正后退出）
        if ("TABLE".equals(dto.getSourceObjectType())) {
            try {
                List<SourceDataSourceService.TableInfo> tables =
                        sourceDataSourceService.listTables(dto.getSourceDataSourceId(), resolvedSchema);
                outer:
                for (String t : dto.getViewNames()) {
                    if (t == null || t.isBlank()) continue;
                    for (SourceDataSourceService.TableInfo info : tables) {
                        if (!t.equalsIgnoreCase(info.tableName())) continue;
                        String type = info.tableType();
                        if (type != null && type.toUpperCase(Locale.ROOT).contains("VIEW")) {
                            log.info("validateDto: auto-correct sourceObjectType TABLE -> VIEW (table={}, metaType={})", t, type);
                            dto.setSourceObjectType("VIEW");
                            break outer;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("validateDto: listTables probe failed (datasourceId={}, schema={}): {}",
                        dto.getSourceDataSourceId(), resolvedSchema, e.getMessage());
            }
        }

        // 2) 逐表列存在性校验。任意一张表未通过即抛错（带 [table=xxx] 前缀）。
        //    字段探测失败不再吞掉，避免保存后执行期才因无法构造小写 alias 失败。
        for (String table : dto.getViewNames()) {
            if (table == null || table.isBlank()) continue;
            Set<String> columns;
            List<SourceDataSourceService.ColumnInfo> columnList;
            try {
                columns = new HashSet<>();
                columnList = sourceDataSourceService.listColumns(dto.getSourceDataSourceId(), resolvedSchema, table);
                for (SourceDataSourceService.ColumnInfo c : columnList) {
                    columns.add(c.columnName().toLowerCase(Locale.ROOT));
                }
            } catch (Exception e) {
                log.warn("validateDto: listColumns probe failed (datasourceId={}, {}.{}): {}",
                        dto.getSourceDataSourceId(), resolvedSchema, table, e.getMessage());
                throw new IllegalArgumentException("[table=" + table + "] 源表字段探测失败，"
                        + "无法构建小写字段 alias；请检查数据源连通性、schema/table 权限和字段元数据: "
                        + e.getMessage(), e);
            }
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("[table=" + table + "] 源表字段列表为空，"
                        + "无法构建小写字段 alias");
            }

            // 字段名大小写折叠冲突检测
            Map<String, List<String>> lowerToOriginals = new HashMap<>();
            for (SourceDataSourceService.ColumnInfo col : columnList) {
                if (col.columnName() != null) {
                    lowerToOriginals.computeIfAbsent(col.columnName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(col.columnName());
                }
            }
            List<String> conflicts = lowerToOriginals.entrySet().stream()
                    .filter(e -> e.getValue().size() > 1)
                    .map(e -> e.getValue().stream().collect(Collectors.joining(", ", "[", "]")) + " → " + e.getKey())
                    .toList();
            if (!conflicts.isEmpty()) {
                throw new IllegalArgumentException("[table=" + table + "] 源端字段存在大小写冲突: "
                        + String.join("; ", conflicts));
            }

            String prefix = "[table=" + table + "] ";
            requireColumn(columns, dto.getIncrementalField(), prefix + "incrementalField");
            requireColumn(columns, dto.getSoftDeleteField(),  prefix + "softDeleteField");
            requireColumn(columns, dto.getSequenceCol(),      prefix + "sequenceCol");
            requireColumn(columns, dto.getSplitPk(),          prefix + "splitPk");
            if (dto.getUpsertKeys() != null) {
                for (String key : dto.getUpsertKeys()) {
                    requireColumn(columns, key, prefix + "upsertKeys[" + key + "]");
                }
            }
            validateMedicalViewSemantics(dto, columnList, prefix);
        }
    }

    private void validateCustomSqlColumns(SyncTaskDto dto) {
        List<SourceDataSourceService.ColumnInfo> cols =
                sourceDataSourceService.listCustomSqlColumns(dto.getSourceDataSourceId(), dto.getCustomSql());
        Set<String> columns = new HashSet<>();
        for (SourceDataSourceService.ColumnInfo c : cols) {
            if (c.columnName() != null) columns.add(c.columnName().toLowerCase(Locale.ROOT));
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("自定义 SQL 未返回任何字段");
        }
        // 字段名大小写折叠冲突检测
        Map<String, List<String>> lowerToOriginals = new HashMap<>();
        for (SourceDataSourceService.ColumnInfo col : cols) {
            if (col.columnName() != null) {
                lowerToOriginals.computeIfAbsent(col.columnName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(col.columnName());
            }
        }
        List<String> conflicts = lowerToOriginals.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getValue().stream().collect(Collectors.joining(", ", "[", "]")) + " → " + e.getKey())
                .toList();
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException("源端字段存在大小写冲突: " + String.join("; ", conflicts));
        }
        String prefix = "[customSql=" + dto.getCustomSqlName() + "] ";
        requireColumn(columns, dto.getIncrementalField(), prefix + "incrementalField");
        requireColumn(columns, dto.getSoftDeleteField(), prefix + "softDeleteField");
        requireColumn(columns, dto.getSequenceCol(), prefix + "sequenceCol");
        if (dto.getUpsertKeys() != null) {
            for (String key : dto.getUpsertKeys()) {
                requireColumn(columns, key, prefix + "upsertKeys[" + key + "]");
            }
        }
    }

    private void requireColumn(Set<String> columnsLower, String fieldValue, String displayName) {
        if (fieldValue == null || fieldValue.isBlank()) return;
        if (!columnsLower.contains(fieldValue.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(displayName + " 指向的列 '" + fieldValue + "' 在源表/视图中不存在");
        }
    }

    private void validateMedicalViewSemantics(
            SyncTaskDto dto,
            List<SourceDataSourceService.ColumnInfo> columns,
            String prefix) {
        if (requiresTimeFieldWindow(dto)) {
            SourceDataSourceService.ColumnInfo inc = findColumn(columns, dto.getIncrementalField());
            if (inc != null
                    && !isTemporalColumn(sourceDialect(dto), inc)
                    && !isContractDrivenMedicalStringTimeField(dto, inc)) {
                throw new IllegalArgumentException(prefix + "incrementalField '" + dto.getIncrementalField()
                        + "' 类型不是日期时间类型，当前类型=" + inc.dataType()
                        + "；普通 TIME_FIELD 增量必须使用 DATE/DATETIME/TIMESTAMP 兼容字段，"
                        + "医共体合约驱动任务可使用 varchar/text 时间字符串并由 dfetl-server 安全解析");
            }
        }

        boolean isView = "VIEW".equalsIgnoreCase(dto.getSourceObjectType())
                || "MATERIALIZED_VIEW".equalsIgnoreCase(dto.getSourceObjectType());
        if (isView && "UNIQUE_KEY".equalsIgnoreCase(dto.getDorisTableModel())
                && !hasConfiguredBusinessKey(dto)
                && columns.stream().noneMatch(SourceDataSourceService.ColumnInfo::primaryKey)) {
            throw new IllegalArgumentException(prefix
                    + "视图源没有业务 key，不能创建 UNIQUE_KEY/MOW 表；请配置 upsertKeys，或使用 DUPLICATE_KEY + ROW_COUNT 校验");
        }
    }

    private boolean requiresTimeFieldWindow(SyncTaskDto dto) {
        return dto != null
                && "TIME_FIELD".equalsIgnoreCase(dto.getIncrementMode())
                && dto.getIncrementalField() != null
                && !dto.getIncrementalField().isBlank();
    }

    private SourceDataSourceService.ColumnInfo findColumn(
            List<SourceDataSourceService.ColumnInfo> columns,
            String name) {
        if (columns == null || name == null || name.isBlank()) {
            return null;
        }
        for (SourceDataSourceService.ColumnInfo column : columns) {
            if (column != null && column.columnName() != null && column.columnName().equalsIgnoreCase(name)) {
                return column;
            }
        }
        return null;
    }

    private boolean isTemporalColumn(String dialect, SourceDataSourceService.ColumnInfo column) {
        var descriptor = com.dfygt.dfetl.server.engine.doris.DorisTypeMappingPolicy.SourceTypeDescriptor.fromColumn(
                dialect, column, true);
        var mapped = dorisTypeMappingRuleService != null
                ? dorisTypeMappingRuleService.recommend(descriptor)
                : dorisTypeMappingPolicy.recommend(descriptor);
        String type = mapped.recommendedDorisType();
        if (type == null) return false;
        String upper = type.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("DATETIME") || upper.startsWith("DATE") || upper.startsWith("TIMESTAMP");
    }

    private boolean isContractDrivenMedicalStringTimeField(
            SyncTaskDto dto,
            SourceDataSourceService.ColumnInfo column) {
        if (!isContractDrivenMedicalTask(dto) || column == null || column.dataType() == null) {
            return false;
        }
        String type = column.dataType().toUpperCase(Locale.ROOT);
        return type.contains("CHAR")
                || type.contains("TEXT")
                || type.contains("STRING")
                || type.contains("CLOB");
    }

    private boolean isContractDrivenMedicalTask(SyncTaskDto dto) {
        Map<String, Object> values = parseDataCharacteristics(dto == null ? null : dto.getDataCharacteristics());
        Object mode = values.get("medicalMappingMode");
        Object datasetCode = values.get("matchedDatasetCode");
        return mode != null
                && "CONTRACT_DRIVEN".equalsIgnoreCase(mode.toString())
                && datasetCode != null
                && !datasetCode.toString().isBlank();
    }

    private String sourceDialect(SyncTaskDto dto) {
        if (dto == null || dto.getSourceDataSourceId() == null) {
            return null;
        }
        try {
            var source = sourceDataSourceService.findById(dto.getSourceDataSourceId());
            return source == null ? null : source.getType();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasConfiguredBusinessKey(SyncTaskDto dto) {
        return dto != null
                && dto.getUpsertKeys() != null
                && dto.getUpsertKeys().stream().anyMatch(key -> key != null && !key.isBlank());
    }

    /** 自动名：{源库别名}-{目标库别名}-{表名}-{时间戳}，不依赖任务 ID，可在 save 之前调用 */
    private String buildAutoName(SyncTask task) {
        String srcName = sourceRepo.findById(task.getSourceDataSourceId())
                .map(s -> s.getName() != null ? s.getName() : "src")
                .orElse("src");
        String tgtName = targetRepo.findById(task.getTargetDataSourceId())
                .map(t -> t.getName() != null ? t.getName() : "tgt")
                .orElse("tgt");
        String table = (task.getViewNames() != null && !task.getViewNames().isEmpty())
                ? task.getViewNames().get(0) : "table";
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        return sanitize(srcName + "-" + tgtName + "-" + table + "-" + ts);
    }

    /** 名称中非法字符替换为 '_' */
    private String sanitize(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_\\-\u4e00-\u9fa5]", "_");
    }

    private void copyToEntity(SyncTaskDto dto, SyncTask entity) {
        entity.setName(dto.getName());
        entity.setInstitutionId(dto.getInstitutionId());
        entity.setSyncType(dto.getSyncType());
        entity.setSourceDataSourceId(dto.getSourceDataSourceId());
        entity.setTargetDataSourceId(dto.getTargetDataSourceId());
        if (dto.getSourceMode() != null)      entity.setSourceMode(dto.getSourceMode());
        if (dto.getSourceSchema() != null)    entity.setSourceSchema(dto.getSourceSchema());
        entity.setCustomSql(dto.getCustomSql());
        entity.setCustomSqlName(dto.getCustomSqlName());
        entity.setViewNames(dto.getViewNames());
        if (dto.getSyncMode() != null)        entity.setSyncMode(dto.getSyncMode());
        if (dto.getDataScope() != null)       entity.setDataScope(dto.getDataScope());
        entity.setIncrementalField(dto.getIncrementalField());
        entity.setUpsertKeys(dto.getUpsertKeys());
        if (dto.getBatchSize() != null)       entity.setBatchSize(dto.getBatchSize());
        if (dto.getParallelism() != null)     entity.setParallelism(dto.getParallelism());
        entity.setShardCount(dto.getShardCount());
        if (dto.getShardStrategy() != null)   entity.setShardStrategy(dto.getShardStrategy());
        if (dto.getRateLimit() != null)       entity.setRateLimit(dto.getRateLimit());
        entity.setSchedule(dto.getSchedule());
        entity.setScheduleLabel(dto.getScheduleLabel());
        // spec 053：优先采用 scheduleConfig，后端权威重算 cronExpression / scheduleDescription
        applyScheduleConfig(entity, dto);
        if (dto.getStatus() != null)          entity.setStatus(dto.getStatus());
        if (dto.getVersionStatus() != null)   entity.setVersionStatus(dto.getVersionStatus());
        if (dto.getVersion() != null)         entity.setVersion(dto.getVersion());
        if (dto.getAlertStatus() != null)     entity.setAlertStatus(dto.getAlertStatus());
        // 新增字段
        if (dto.getIncrementMode() != null)           entity.setIncrementMode(dto.getIncrementMode());
        if (dto.getUpperBoundStrategy() != null)      entity.setUpperBoundStrategy(dto.getUpperBoundStrategy());
        if (dto.getUpperBoundDelayMinutes() != null)  entity.setUpperBoundDelayMinutes(dto.getUpperBoundDelayMinutes());
        if (dto.getInitialWatermark() != null)        entity.setInitialWatermark(dto.getInitialWatermark());
        if (dto.getInitialFullSync() != null)         entity.setInitialFullSync(dto.getInitialFullSync());
        // initialFullSyncDone 不允许前端直接设置，只能由 WatermarkService 推进；
        // 但重置水位接口会主动清零
        if (dto.getWriterType() != null)              entity.setWriterType(dto.getWriterType());
        // dorisTableModel：未传时不覆盖（修复 A7：之前会把已有 AGGREGATE/DUPLICATE 强写为 UNIQUE_KEY）
        if (dto.getDorisTableModel() != null)         entity.setDorisTableModel(dto.getDorisTableModel());
        entity.setStaticFilter(dto.getStaticFilter());
        entity.setFilterConditionMap(dto.getFilterConditionMap());
        entity.setTargetTableMap(dto.getTargetTableMap());
        entity.setDataCharacteristics(dto.getDataCharacteristics());
        entity.setCustomWindowStart(dto.getCustomWindowStart());
        entity.setCustomWindowEnd(dto.getCustomWindowEnd());
        if (dto.getExecutorType() != null)            entity.setExecutorType(dto.getExecutorType());
        entity.setSplitPk(dto.getSplitPk());
        if (dto.getSourceObjectType() != null)        entity.setSourceObjectType(dto.getSourceObjectType());
        entity.setSoftDeleteField(dto.getSoftDeleteField());
        if (dto.getSoftDeleteActiveValue() != null)   entity.setSoftDeleteActiveValue(dto.getSoftDeleteActiveValue());
        if (dto.getEnableDorisMerge() != null)        entity.setEnableDorisMerge(dto.getEnableDorisMerge());
        if (dto.getDeleteSignValue() != null)         entity.setDeleteSignValue(dto.getDeleteSignValue());
        entity.setSequenceCol(dto.getSequenceCol());
        if (dto.getPartialColumns() != null)          entity.setPartialColumns(dto.getPartialColumns());
        if (dto.getLookbackSeconds() != null)         entity.setLookbackSeconds(dto.getLookbackSeconds());
        if (dto.getEnableSnapshotDelete() != null)    entity.setEnableSnapshotDelete(dto.getEnableSnapshotDelete());
        // spec 020.2
        if (dto.getSnapshotAutoCapture() != null)     entity.setSnapshotAutoCapture(dto.getSnapshotAutoCapture());
        entity.setSnapshotAutoDetectCron(dto.getSnapshotAutoDetectCron());
        if (dto.getSnapshotAutoApply() != null)       entity.setSnapshotAutoApply(dto.getSnapshotAutoApply());
        if (dto.getSnapshotDeleteMaxRatio() != null)  entity.setSnapshotDeleteMaxRatio(dto.getSnapshotDeleteMaxRatio());
        if (dto.getSnapshotCaptureIntervalMinutes() != null) entity.setSnapshotCaptureIntervalMinutes(dto.getSnapshotCaptureIntervalMinutes());
    }

    private SyncTaskDto toDto(SyncTask e) {
        SyncTaskDto dto = new SyncTaskDto();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setInstitutionId(e.getInstitutionId());
        dto.setSyncType(e.getSyncType());
        dto.setSourceDataSourceId(e.getSourceDataSourceId());
        dto.setTargetDataSourceId(e.getTargetDataSourceId());
        dto.setSourceMode(e.getSourceMode());
        dto.setSourceSchema(e.getSourceSchema());
        dto.setCustomSql(e.getCustomSql());
        dto.setCustomSqlName(e.getCustomSqlName());
        dto.setViewNames(e.getViewNames());
        dto.setSyncMode(e.getSyncMode());
        dto.setDataScope(e.getDataScope());
        dto.setIncrementalField(e.getIncrementalField());
        dto.setUpsertKeys(e.getUpsertKeys());
        dto.setBatchSize(e.getBatchSize());
        dto.setParallelism(e.getParallelism());
        dto.setShardCount(e.getShardCount());
        dto.setShardStrategy(e.getShardStrategy());
        dto.setRateLimit(e.getRateLimit());
        dto.setSchedule(e.getSchedule());
        dto.setScheduleLabel(e.getScheduleLabel());
        // spec 053
        dto.setCronExpression(e.getCronExpression());
        dto.setScheduleConfig(e.getScheduleConfig());
        dto.setScheduleDescription(e.getScheduleDescription());
        dto.setScheduleTimezone(e.getScheduleTimezone());
        dto.setStatus(e.getStatus());
        dto.setVersionStatus(e.getVersionStatus());
        dto.setVersion(e.getVersion());
        dto.setLastRunTime(e.getLastRunTime());
        dto.setLastRunStatus(e.getLastRunStatus());
        dto.setAlertStatus(e.getAlertStatus());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        // 新增字段
        dto.setIncrementMode(e.getIncrementMode());
        dto.setUpperBoundStrategy(e.getUpperBoundStrategy());
        dto.setUpperBoundDelayMinutes(e.getUpperBoundDelayMinutes());
        dto.setInitialWatermark(e.getInitialWatermark());
        dto.setInitialFullSync(e.getInitialFullSync());
        dto.setInitialFullSyncDone(e.getInitialFullSyncDone());
        dto.setWriterType(e.getWriterType());
        dto.setDorisTableModel(e.getDorisTableModel());
        dto.setStaticFilter(e.getStaticFilter());
        dto.setFilterConditionMap(e.getFilterConditionMap());
        dto.setTargetTableMap(e.getTargetTableMap());
        dto.setDataCharacteristics(e.getDataCharacteristics());
        dto.setCustomWindowStart(e.getCustomWindowStart());
        dto.setCustomWindowEnd(e.getCustomWindowEnd());
        dto.setIncrementalCheckpoint(e.getIncrementalCheckpoint());
        dto.setExecutorType(e.getExecutorType());
        dto.setSplitPk(e.getSplitPk());
        dto.setSourceObjectType(e.getSourceObjectType());
        dto.setSoftDeleteField(e.getSoftDeleteField());
        dto.setSoftDeleteActiveValue(e.getSoftDeleteActiveValue());
        dto.setEnableDorisMerge(e.getEnableDorisMerge());
        dto.setDeleteSignValue(e.getDeleteSignValue());
        dto.setSequenceCol(e.getSequenceCol());
        dto.setPartialColumns(e.getPartialColumns());
        dto.setLookbackSeconds(e.getLookbackSeconds());
        dto.setEnableSnapshotDelete(e.getEnableSnapshotDelete());
        // spec 020.2
        dto.setSnapshotAutoCapture(e.getSnapshotAutoCapture());
        dto.setSnapshotAutoDetectCron(e.getSnapshotAutoDetectCron());
        dto.setSnapshotAutoApply(e.getSnapshotAutoApply());
        dto.setSnapshotDeleteMaxRatio(e.getSnapshotDeleteMaxRatio());
        dto.setSnapshotCaptureIntervalMinutes(e.getSnapshotCaptureIntervalMinutes());
        if (e.getId() != null) {
            dto.setViewConfigs(viewConfigRepo.findByTaskId(e.getId()).stream().map(config -> {
                TaskViewConfigDto item = new TaskViewConfigDto();
                item.setViewName(config.getViewName());
                item.setFieldMappings(config.getFieldMappings());
                item.setDorisDdl(config.getDorisDdl());
                return item;
            }).toList());
            messagePublishConfigRepository.findByTaskId(e.getId()).ifPresent(config -> {
                var item = new com.dfygt.dfetl.server.dto.MessagePublishConfigDto();
                item.setTaskId(config.getTaskId());
                item.setEnabled(config.isEnabled());
                item.setChannel(config.getChannel());
                item.setMessageType(config.getMessageType());
                item.setTopic(config.getTopic());
                item.setMessageKeyTemplate(config.getMessageKeyTemplate());
                item.setFullSyncMode(config.getFullSyncMode());
                item.setRateLimit(config.getRateLimit());
                item.setPageSize(config.getPageSize());
                item.setSourceSystem(config.getSourceSystem());
                item.setTenantId(config.getTenantId());
                item.setFieldMappingJson(config.getFieldMappingJson());
                dto.setMessagePublishConfig(item);
            });
        }
        return dto;
    }

    /**
     * spec 053 - 把 dto 中的 scheduleConfig（JSON 字符串）反序列化、按规则重算 cronExpression 与中文描述，
     * 并写回 entity。
     *
     * <p>规则：
     * <ul>
     *   <li>dto.scheduleConfig 非空 → 解析后 toCron() 重算（后端为权威）</li>
     *   <li>dto.scheduleConfig 为空但 dto.cronExpression 非空 → 直接使用，并反解析回 ScheduleConfig 持久化</li>
     *   <li>都为空 → 兼容旧字段 dto.schedule</li>
     * </ul>
     */
    private void applyScheduleConfig(SyncTask entity, SyncTaskDto dto) {
        String tz = dto.getScheduleTimezone() == null || dto.getScheduleTimezone().isBlank()
                ? CronExpressionService.DEFAULT_TIMEZONE
                : dto.getScheduleTimezone();
        entity.setScheduleTimezone(tz);

        ScheduleConfig parsed = scheduleConfigService.fromJson(dto.getScheduleConfig());
        if (parsed != null && parsed.getMode() != null) {
            if (parsed.getTimezone() == null || parsed.getTimezone().isBlank()) parsed.setTimezone(tz);
            String cron = scheduleConfigService.toCron(parsed);
            parsed.setCronExpression(cron);
            parsed.setDescription(scheduleConfigService.describe(parsed));
            entity.setScheduleConfig(scheduleConfigService.toJson(parsed));
            entity.setScheduleDescription(parsed.getDescription());
            entity.setCronExpression(cron);
            return;
        }

        // 仅传了 cronExpression（高级用户或前端未升级）
        String cron = dto.getCronExpression();
        if (cron == null || cron.isBlank()) cron = dto.getSchedule();
        if (cron != null && !cron.isBlank()) {
            ScheduleConfig fb = scheduleConfigService.fromCron(cron, tz);
            entity.setScheduleConfig(scheduleConfigService.toJson(fb));
            entity.setScheduleDescription(fb.getDescription());
            entity.setCronExpression(cron);
        } else {
            // 完全没有调度
            entity.setScheduleConfig(null);
            entity.setScheduleDescription("手动触发，不自动执行");
            entity.setCronExpression(null);
        }
    }
}
