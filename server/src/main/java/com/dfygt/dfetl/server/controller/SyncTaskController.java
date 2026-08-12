package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.common.IdentifierSanitizer;
import com.dfygt.dfetl.server.common.TargetTableMapParser;
import com.dfygt.dfetl.server.dto.DorisSchemaPreviewRequest;
import com.dfygt.dfetl.server.dto.DorisSchemaPreviewResponse;
import com.dfygt.dfetl.server.dto.SyncTaskDto;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalDatasetContractService;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.service.DfetlExecutorService;
import com.dfygt.dfetl.server.service.DorisSchemaPreviewService;
import com.dfygt.dfetl.server.service.ExecutionCancellationService;
import com.dfygt.dfetl.server.service.SharedTargetTableGuard;
import com.dfygt.dfetl.server.service.RecollectService;
import com.dfygt.dfetl.server.service.SyncTaskService;
import com.dfygt.dfetl.server.service.SyncTaskApplicationService;
import com.dfygt.dfetl.server.service.TaskCreateIntent;
import com.dfygt.dfetl.server.service.TaskExecutionQueue;
import com.dfygt.dfetl.server.service.TargetTableResolver;
import com.dfygt.dfetl.server.service.WatermarkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Locale;

@RestController
@RequestMapping("/api/sync-task")
@RequiredArgsConstructor
public class SyncTaskController {

    private final SyncTaskService taskService;
    private final SyncTaskApplicationService taskApplicationService;
    private final DfetlExecutorService executorService;
    private final TaskExecutionQueue executionQueue;
    private final WatermarkService watermarkService;
    private final SyncTaskRepository syncTaskRepository;
    private final com.dfygt.dfetl.server.engine.seatunnel.SeaTunnelConfBuilder seaTunnelConfBuilder;
    private final DorisSchemaPreviewService dorisSchemaPreviewService;
    private final com.dfygt.dfetl.server.repository.TaskViewConfigRepository viewConfigRepo;
    private final com.dfygt.dfetl.server.service.SourceDataSourceService sourceDataSourceService;
    private final com.dfygt.dfetl.server.repository.TargetDataSourceRepository targetDataSourceRepository;
    private final com.dfygt.dfetl.server.repository.SourceDataSourceRepository sourceDataSourceRepository;
    private final TargetTableResolver targetTableResolver;
    private final com.dfygt.dfetl.server.common.AesUtil aesUtil;
    private final ExecutionCancellationService executionCancellationService;
    private final SharedTargetTableGuard sharedTargetTableGuard;
    private final MedicalDatasetContractService medicalDatasetContractService;
    private final RecollectService recollectService;

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * field-mapping 短窗口去重缓存：在 5 秒内复用上次结果，防止前端 burst 刷新打爆 Doris 连接。
     * 不做长 TTL：保留"实时对比"语义，5 秒过期后会重新拉取。
     * Key = taskId（每个任务一份），Value = 上次响应数据 + 时间戳。
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, FieldMappingCacheEntry> fieldMappingCache
            = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FIELD_MAPPING_CACHE_TTL_MS = 5_000L;

    private record FieldMappingCacheEntry(
            java.util.List<java.util.Map<String, Object>> data, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > FIELD_MAPPING_CACHE_TTL_MS;
        }
    }

    @GetMapping
    public ApiResponse<List<SyncTaskDto>> list() {
        return ApiResponse.ok(taskService.findAll());
    }

    /**
     * spec 041：服务端分页 + 多条件过滤。
     * 返回 Spring Page 序列化形态：{ content, totalElements, number, size, ... }
     * 与 web/src/api/task.ts 中已存在的 PageResult<T> 接口直接对齐。
     */
    @GetMapping("/page")
    public ApiResponse<Page<SyncTaskDto>> page(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String syncType,
            @RequestParam(required = false) String search) {
        return ApiResponse.ok(taskService.findPage(page, size, status, syncType, search));
    }

    @GetMapping("/{id}")
    public ApiResponse<SyncTaskDto> get(@PathVariable Long id) {
        return ApiResponse.ok(taskService.findById(id));
    }

    @PostMapping
    public ApiResponse<SyncTaskDto> create(@RequestBody @Valid TaskCreateIntent intent) {
        return ApiResponse.ok(taskApplicationService.createInternal(intent));
    }

    @PostMapping("/preview-doris-schema")
    public ApiResponse<DorisSchemaPreviewResponse> previewDorisSchema(@RequestBody DorisSchemaPreviewRequest request) {
        return ApiResponse.ok(dorisSchemaPreviewService.preview(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SyncTaskDto> update(
            @PathVariable Long id,
            @RequestBody @Valid SyncTaskDto dto) {
        return ApiResponse.ok(taskService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return ApiResponse.ok();
    }

    /** 触发异步执行（走 Semaphore 有界队列）*/
    @PostMapping("/{id}/run")
    public ApiResponse<Map<String, Object>> run(@PathVariable Long id) {
        SyncTask task = syncTaskRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + id));
        executionQueue.submit(id, "MANUAL");
        return ApiResponse.ok(Map.of(
                "taskId", id,
                "status", "submitted",
                "queued", executionQueue.getQueueLength()
        ));
    }

    private Map<String, Object> parseDataCharacteristics(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 取消正在运行的任务执行。
     * <p>
     * 操作：找到该任务最新的 RUNNING/PENDING execution，并交给统一取消协调器。
     * 有 engineJobId 时只有 SeaTunnel 明确确认 CANCELLED/FAILED 才写 CANCELLED；
     * stop 失败或终态无法确认时写 RECONCILE_REQUIRED。
     * <p>
     * 返回：
     * <ul>
     *   <li>status=cancelled — 未提交远端，或远端已确认停止</li>
     *   <li>status=reconcile_required — 远端停止或终态无法确认，需要人工核对</li>
     *   <li>status=no_running — 没有正在运行的 execution（幂等，不抛错）</li>
     * </ul>
     */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id) {
        if (!syncTaskRepository.existsById(id)) {
            throw new NoSuchElementException("SyncTask not found: " + id);
        }
        var cancellation = executionCancellationService.cancelLatestForTask(id);
        if (cancellation.isEmpty()) {
            return ApiResponse.ok(Map.of(
                    "taskId", id,
                    "status", "no_running"
            ));
        }
        var result = cancellation.orElseThrow();
        return ApiResponse.ok(Map.of(
                "taskId", id,
                "executionId", result.executionId(),
                "engineJobId", result.engineJobId() == null ? "" : result.engineJobId(),
                "status", result.status().toLowerCase(java.util.Locale.ROOT),
                "stopResult", result.detail()
        ));
    }

    /**
     * 重采：清空目标表数据后重新全量采集。
     * @param mode TRUNCATE（保留表结构，适合字段无变化）或 DROP_RECREATE（删表重建，适合字段有变化）
     */
    @PostMapping("/{id}/recollect")
    public ApiResponse<Map<String, Object>> recollect(@PathVariable Long id,
                                                       @RequestParam(defaultValue = "TRUNCATE") String mode) {
        String normalizedMode = mode == null ? "TRUNCATE" : mode.trim().toUpperCase(Locale.ROOT);
        if (!"TRUNCATE".equals(normalizedMode) && !"DROP_RECREATE".equals(normalizedMode)) {
            throw new IllegalArgumentException("recollect mode 仅支持 TRUNCATE / DROP_RECREATE");
        }
        SyncTask task = syncTaskRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + id));
        RecollectService.RecollectResult result = recollectService.recollect(id, normalizedMode);
        return ApiResponse.ok(Map.of(
                "taskId", result.taskId(),
                "mode", result.mode(),
                "status", result.status(),
                "tablesCleared", result.tablesCleared()
        ));
    }

    /** 停用后续调度：只影响 cron 自动触发，不取消当前正在运行的 execution。 */
    @PostMapping("/{id}/disable-schedule")
    public ApiResponse<SyncTaskDto> disableSchedule(@PathVariable Long id) {
        return ApiResponse.ok(taskService.disableSchedule(id));
    }

    /** 启用后续调度：恢复 cron 自动触发；无 cron 的任务仍可手动运行。 */
    @PostMapping("/{id}/enable-schedule")
    public ApiResponse<SyncTaskDto> enableSchedule(@PathVariable Long id) {
        return ApiResponse.ok(taskService.enableSchedule(id));
    }

    /** 查询任务当前水位和推算下次窗口 */
    @GetMapping("/{id}/last-watermark")
    public ApiResponse<Map<String, Object>> lastWatermark(@PathVariable Long id) {
        SyncTask task = syncTaskRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + id));
        WatermarkService.WindowContext window = watermarkService.computeWindow(task);
        return ApiResponse.ok(Map.of(
                "taskId", id,
                "incrementalCheckpoint", task.getIncrementalCheckpoint() != null
                        ? task.getIncrementalCheckpoint().toString() : "",
                "nextWindowType", window.windowType(),
                "nextWindowStart", window.windowStart() != null ? window.windowStart().toString() : "",
                "nextWindowEnd", window.windowEnd() != null ? window.windowEnd().toString() : ""
        ));
    }

    /**
     * 重置增量水位。
     * <p>根据任务的 {@code incrementMode} 区分解析：
     * <ul>
     *   <li>TIME_FIELD（默认）：value 必须是 ISO-8601 时间戳，写入 incrementalCheckpoint</li>
     *   <li>ID_RANGE：value 必须是数字字符串，写入 initialWatermark</li>
     *   <li>value 为空：清空两个水位字段（下次执行走全量）</li>
     * </ul>
     * 解析失败抛 400，避免静默吞错。
     */
    @PostMapping("/{id}/reset-watermark")
    public ApiResponse<Void> resetWatermark(
            @PathVariable Long id,
            @RequestParam(required = false) String value) {
        SyncTask task = syncTaskRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + id));
        // RUNNING 守卫：避免与运行中 execution 的水位推进逻辑产生竞态
        taskService.assertNoRunningExecution(id, "resetWatermark");
        if (value == null || value.isBlank()) {
            task.setIncrementalCheckpoint(null);
            task.setInitialWatermark(null);
        } else if ("ID_RANGE".equals(task.getIncrementMode())) {
            // ID_RANGE：value 必须可解析为数字
            try {
                Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "ID_RANGE 任务的水位必须是数字字符串：value=" + value);
            }
            task.setInitialWatermark(value.trim());
        } else {
            // TIME_FIELD：value 必须可解析为 ISO-8601 时间戳
            try {
                task.setIncrementalCheckpoint(Instant.parse(value));
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "TIME_FIELD 任务的水位必须是 ISO-8601 时间戳：value=" + value);
            }
            task.setInitialWatermark(value);
        }
        syncTaskRepository.save(task);
        return ApiResponse.ok();
    }

    /**
     * 重置「一键全量→增量」的初始化状态。
     * 用于：用户希望重新跑一次首次全量（例如目标表被清空，或源端结构调整后重灌）。
     * 操作：initialFullSyncDone=false + initialWatermark=null + incrementalCheckpoint=null
     */
    @PostMapping("/{id}/reset-initial-full-sync")
    public ApiResponse<Void> resetInitialFullSync(@PathVariable Long id) {
        SyncTask task = syncTaskRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + id));
        // RUNNING 守卫：避免重置首次全量状态时与运行中 execution 产生竞态
        taskService.assertNoRunningExecution(id, "resetInitialFullSync");
        task.setInitialFullSyncDone(false);
        task.setInitialWatermark(null);
        task.setIncrementalCheckpoint(null);
        syncTaskRepository.save(task);
        return ApiResponse.ok();
    }

    /** SSE 实时日志流 */
    @GetMapping(value = "/{id}/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable Long id) {
        // 前置存在性校验：未知 taskId 直接 404，避免占用一个 600s 的 SSE 连接
        if (!syncTaskRepository.existsById(id)) {
            throw new NoSuchElementException("SyncTask not found: " + id);
        }
        SseEmitter emitter = new SseEmitter(600_000L);
        executorService.streamLogs(id, emitter);
        return emitter;
    }

    /**
     * 查询任务源端执行行集行数（COUNT(*)）。
     * 用于监控页显示"源端数据量"，语义与 SeaTunnel execution count 保持一致。
     * 返回：{ tableName, rowCount }
     */
    @GetMapping("/{id}/source-count")
    public ApiResponse<Map<String, Object>> sourceCount(@PathVariable Long id) {
        SyncTask task = syncTaskRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + id));
        boolean customSqlMode = "CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode());
        String tableName;
        if (customSqlMode) {
            tableName = task.getCustomSqlName() == null || task.getCustomSqlName().isBlank()
                    ? "custom_sql" : task.getCustomSqlName();
        } else {
            tableName = task.getViewNames() == null || task.getViewNames().isEmpty()
                    ? "" : task.getViewNames().get(0);
        }
        if (task.getViewNames() == null || task.getViewNames().isEmpty()) {
            return ApiResponse.ok(Map.of("tableName", "", "rowCount", -1L));
        }
        WatermarkService.WindowContext window = watermarkService.computeWindow(task);
        long count;
        try {
            count = seaTunnelConfBuilder.countSourceRowsStrict(task, window);
        } catch (UnsupportedOperationException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return ApiResponse.ok(Map.of("tableName", tableName, "rowCount", count));
    }

    /**
     * 预览 Reader 实际拼出的 SELECT SQL（含 WHERE/窗口）。
     * <p>用于在 UI 上排查"增量任务为何读取 0 行"——把 windowStart/End 与最终 SQL 一起返回。
     * <p>支持 mode：
     * <ul>
     *   <li>{@code current}（默认）：按任务当前配置（含已存水位）计算下次执行时的 SQL</li>
     *   <li>{@code full}：按 FULL 窗口预览（不含增量过滤）</li>
     * </ul>
     */
    @GetMapping("/{id}/preview-sql")
    public ApiResponse<Map<String, Object>> previewSql(@PathVariable Long id,
                                                       @RequestParam(required = false, defaultValue = "current") String mode) {
        SyncTask task = syncTaskRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + id));
        WatermarkService.WindowContext window = "full".equalsIgnoreCase(mode)
                ? new WatermarkService.WindowContext("FULL", null, null, null, null)
                : watermarkService.computeWindow(task);
        String sql = seaTunnelConfBuilder.previewReaderSql(task, window);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("mode", mode);
        body.put("windowType", window.windowType());
        body.put("windowStart", window.windowStart());
        body.put("windowEnd", window.windowEnd());
        body.put("windowStartId", window.windowStartId());
        body.put("windowEndId", window.windowEndId());
        body.put("incrementalField", task.getIncrementalField());
        body.put("incrementalCheckpoint", task.getIncrementalCheckpoint());
        body.put("sql", sql);
        return ApiResponse.ok(body);
    }

    /**
     * 获取任务已保存的字段映射配置（task_view_config）。
     */
    @GetMapping("/{id}/view-config")
    public ApiResponse<java.util.List<java.util.Map<String, Object>>> getViewConfig(@PathVariable Long id) {
        // 任务存在性校验：未知 ID 返回 404 而不是空数组（与 fieldMapping / streamLogs 保持一致）
        if (!syncTaskRepository.existsById(id)) {
            throw new NoSuchElementException("SyncTask not found: " + id);
        }
        var configs = viewConfigRepo.findByTaskId(id);
        if (configs == null || configs.isEmpty()) {
            return ApiResponse.ok(java.util.List.of());
        }
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var cfg : configs) {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("viewName", cfg.getViewName());
            entry.put("dorisDdl", cfg.getDorisDdl());
            try {
                if (cfg.getFieldMappings() != null && !cfg.getFieldMappings().isBlank()) {
                    entry.put("fieldMappings", om.readValue(cfg.getFieldMappings(), java.util.List.class));
                } else {
                    entry.put("fieldMappings", java.util.List.of());
                }
            } catch (Exception e) {
                entry.put("fieldMappings", java.util.List.of());
            }
            result.add(entry);
        }
        return ApiResponse.ok(result);
    }

    /**
     * 获取任务的真实字段映射对比：源端 PG 视图字段 vs 目标端 Doris 表字段。
     * 返回每个视图的源端字段、目标端字段、以及对比状态（matched/source_only/target_only/type_diff）。
     */
    @GetMapping("/{id}/field-mapping")
    public ApiResponse<java.util.List<java.util.Map<String, Object>>> fieldMapping(@PathVariable Long id) {
        // 短窗口去重：5s 内复用上次结果，防止前端 burst 刷新耗尽 Doris 连接
        FieldMappingCacheEntry cached = fieldMappingCache.get(id);
        if (cached != null && !cached.isExpired()) {
            return ApiResponse.ok(cached.data());
        }
        SyncTask task = syncTaskRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("SyncTask not found: " + id));
        if (task.getViewNames() == null || task.getViewNames().isEmpty()) {
            return ApiResponse.ok(java.util.List.of());
        }

        boolean customSqlMode = "CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode());
        String schema = null;
        if (!customSqlMode) {
            var srcDs = sourceDataSourceRepository.findById(task.getSourceDataSourceId())
                    .orElseThrow(() -> new NoSuchElementException(
                            "SourceDataSource not found: " + task.getSourceDataSourceId()));
            schema = com.dfygt.dfetl.server.service.SourceSchemaResolver.resolveRequired(task, srcDs);
        }

        // 获取目标数据源
        var tgt = targetDataSourceRepository.findById(task.getTargetDataSourceId()).orElse(null);

        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        for (String viewName : task.getViewNames()) {
            java.util.Map<String, Object> viewResult = new java.util.LinkedHashMap<>();
            viewResult.put("viewName", viewName);

            // 目标表名
            String tgtTable = targetTableResolver.resolve(task, viewName);
            viewResult.put("targetTable", tgtTable);

            // 读取源端字段
            java.util.List<java.util.Map<String, Object>> sourceFields = new java.util.ArrayList<>();
            try {
                var cols = customSqlMode
                        ? sourceDataSourceService.listCustomSqlColumns(task.getSourceDataSourceId(), task.getCustomSql())
                        : sourceDataSourceService.listColumns(task.getSourceDataSourceId(), schema, viewName);
                for (var col : cols) {
                    java.util.Map<String, Object> f = new java.util.LinkedHashMap<>();
                    f.put("name", col.columnName());
                    f.put("type", col.dataType());
                    f.put("nullable", col.nullable());
                    f.put("primaryKey", col.primaryKey());
                    sourceFields.add(f);
                }
            } catch (Exception e) {
                viewResult.put("sourceError", e.getMessage());
            }
            viewResult.put("sourceFields", sourceFields);

            // 读取目标端字段
            java.util.List<java.util.Map<String, Object>> targetFields = new java.util.ArrayList<>();
            if (tgt != null) {
                try {
                    // 注入防御 1：db 名先做白名单校验后才拼到 jdbcUrl 中
                    String safeDb = IdentifierSanitizer.requireValid(tgt.getDbName(), "tgt.dbName");
                    String url = "jdbc:mysql://" + tgt.getFeHost() + ":" + tgt.getFePort() + "/" + safeDb;
                    String password = aesUtil.decrypt(tgt.getPasswordEnc());
                    // 注入防御 2：information_schema 查询使用 PreparedStatement 参数化，
                    // 不再把 dbName / tgtTable 拼到 SQL 字面量位置（避免 ' UNION ... -- 注入）
                    String sql = "SELECT column_name, data_type, character_maximum_length, is_nullable "
                            + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? "
                            + "ORDER BY ordinal_position";
                    try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url, tgt.getUsername(), password);
                         java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, safeDb);
                        ps.setString(2, tgtTable);
                        try (java.sql.ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                java.util.Map<String, Object> f = new java.util.LinkedHashMap<>();
                                String colName = rs.getString("column_name");
                                String dataType = rs.getString("data_type");
                                Long maxLen = rs.getObject("character_maximum_length") != null ? rs.getLong("character_maximum_length") : null;
                                String fullType = dataType;
                                if (maxLen != null && maxLen > 0) {
                                    fullType = dataType + "(" + maxLen + ")";
                                }
                                f.put("name", colName);
                                f.put("type", fullType);
                                f.put("nullable", "YES".equalsIgnoreCase(rs.getString("is_nullable")));
                                targetFields.add(f);
                            }
                        }
                    }
                } catch (Exception e) {
                    viewResult.put("targetError", e.getMessage());
                }
            }
            viewResult.put("targetFields", targetFields);
            viewResult.put("targetFieldSource", "DORIS_PHYSICAL");
            if (targetFields.isEmpty() && !viewResult.containsKey("targetError")) {
                java.util.List<java.util.Map<String, Object>> contractFields = loadMedicalContractTargetFields(task);
                if (!contractFields.isEmpty()) {
                    targetFields = contractFields;
                    viewResult.put("targetFields", targetFields);
                    viewResult.put("targetFieldSource", "MEDICAL_CONTRACT_PREVIEW");
                }
            }

            // 对比：按字段名小写匹配
            java.util.List<java.util.Map<String, Object>> mappings = new java.util.ArrayList<>();
            java.util.Map<String, java.util.Map<String, Object>> tgtMap = new java.util.LinkedHashMap<>();
            for (var tf : targetFields) {
                tgtMap.put(((String) tf.get("name")).toLowerCase(java.util.Locale.ROOT), tf);
            }
            java.util.Set<String> matchedTargets = new java.util.HashSet<>();
            for (var sf : sourceFields) {
                String srcName = ((String) sf.get("name")).toLowerCase(java.util.Locale.ROOT);
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("sourceField", sf.get("name"));
                row.put("sourceType", sf.get("type"));
                var matched = tgtMap.get(srcName);
                if (matched != null) {
                    matchedTargets.add(srcName);
                    row.put("targetField", matched.get("name"));
                    row.put("targetType", matched.get("type"));
                    row.put("status", "matched");
                } else {
                    row.put("targetField", null);
                    row.put("targetType", null);
                    row.put("status", "source_only");
                }
                mappings.add(row);
            }
            // 目标端有但源端没有的字段
            for (var tf : targetFields) {
                String tgtName = ((String) tf.get("name")).toLowerCase(java.util.Locale.ROOT);
                if (!matchedTargets.contains(tgtName)) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("sourceField", null);
                    row.put("sourceType", null);
                    row.put("targetField", tf.get("name"));
                    row.put("targetType", tf.get("type"));
                    row.put("status", "target_only");
                    mappings.add(row);
                }
            }
            viewResult.put("mappings", mappings);
            viewResult.put("sourceCount", sourceFields.size());
            viewResult.put("targetCount", targetFields.size());
            result.add(viewResult);
        }
        // 写入短窗口缓存（5s），防止前端 burst 刷新打爆 Doris 连接
        fieldMappingCache.put(id, new FieldMappingCacheEntry(result, System.currentTimeMillis()));
        return ApiResponse.ok(result);
    }

    private java.util.List<java.util.Map<String, Object>> loadMedicalContractTargetFields(SyncTask task) {
        java.util.Map<String, String> characteristics = parseDataCharacteristics(task);
        if (!"CONTRACT_DRIVEN".equalsIgnoreCase(characteristics.get("medicalMappingMode"))) {
            return java.util.List.of();
        }
        String datasetCode = characteristics.get("matchedDatasetCode");
        if (datasetCode == null || datasetCode.isBlank()) {
            return java.util.List.of();
        }
        try {
            MedicalDatasetContract contract = medicalDatasetContractService.loadByDatasetCode(datasetCode);
            if (contract.fields() == null || contract.fields().isEmpty()) {
                return java.util.List.of();
            }
            java.util.List<java.util.Map<String, Object>> fields = new java.util.ArrayList<>();
            for (var field : contract.fields()) {
                if (field == null || field.dorisColumn() == null || field.dorisColumn().isBlank()) {
                    continue;
                }
                java.util.Map<String, Object> f = new java.util.LinkedHashMap<>();
                f.put("name", field.dorisColumn());
                f.put("type", field.dorisType());
                f.put("nullable", !field.notNull());
                f.put("primaryKey", field.primaryKey());
                f.put("medicalFieldCode", field.code());
                f.put("medicalFieldName", field.name());
                fields.add(f);
            }
            return fields;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    private java.util.Map<String, String> parseDataCharacteristics(SyncTask task) {
        if (task == null || task.getDataCharacteristics() == null || task.getDataCharacteristics().isBlank()) {
            return java.util.Map.of();
        }
        try {
            java.util.Map<String, Object> raw = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    task.getDataCharacteristics(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            return result;
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }
}
