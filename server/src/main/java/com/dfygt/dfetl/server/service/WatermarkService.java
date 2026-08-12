package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * 负责：
 * 1. 计算本次执行的增量窗口（computeWindow）
 * 2. 任务成功后提交水位（commit）
 *
 * <p>窗口冻结规则：窗口在创建 task_execution 时一次性确定，重跑直接复用。
 * CUSTOM_WINDOW 永远不提交水位。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatermarkService {

    private final SyncTaskRepository syncTaskRepo;
    private final SourceDataSourceRepository sourceDsRepo;
    private final AesUtil aesUtil;
    private final WhereClauseBuilder whereClauseBuilder;
    private final SourceTableResolver sourceTableResolver;
    private final DialectQuoteHelper dialectQuoteHelper;
    private final SourceDataSourceService sourceDataSourceService;

    // ── WindowContext（record，不可变）────────────────────────────────────────

    /**
     * 一次执行的窗口上下文。
     *
     * @param windowType     FULL / INCREMENT / CUSTOM_WINDOW / FULL_THEN_INCREMENT
     * @param windowStart    时间增量：窗口起点（null=无下界）
     * @param windowEnd      时间增量：窗口终点（null=无上界）
     * @param windowStartId  ID 增量：上次最大 ID（null=首次）
     * @param windowEndId    ID 增量：本次最大 ID（null=无上界）
     */
    public record WindowContext(
            String windowType,
            Instant windowStart,
            Instant windowEnd,
            Long windowStartId,
            Long windowEndId
    ) {
        public boolean isInitialFullSync() {
            return "FULL_THEN_INCREMENT".equals(windowType);
        }

        public boolean isIncrementalWindow() {
            return "INCREMENT".equalsIgnoreCase(windowType);
        }

        public boolean hasScopedWindow() {
            return isIncrementalWindow()
                    && (windowStart != null || windowEnd != null
                    || windowStartId != null || windowEndId != null);
        }
    }

    // ── computeWindow ────────────────────────────────────────────────────────

    /**
     * 根据任务配置计算本次执行窗口。
     * <p>调用时机：DfetlExecutorService 创建 task_execution 前。
     */
    public WindowContext computeWindow(SyncTask task) {
        return computeWindow(task, null, null);
    }

    /**
     * 支持临时覆盖（CUSTOM_WINDOW 通过 API 临时传入）。
     */
    public WindowContext computeWindow(SyncTask task, Instant overrideStart, Instant overrideEnd) {
        String dataScope = task.getDataScope();
        if (dataScope == null) dataScope = "FULL";
        dataScope = dataScope.trim().toUpperCase(Locale.ROOT);

        return switch (dataScope) {
            case "FULL" -> new WindowContext("FULL", null, null, null, null);
            case "INCREMENTAL" -> {
                // 一键全量→增量：首次跑全量，同时记录准水位（commit 时写入）
                if (Boolean.TRUE.equals(task.getInitialFullSync())
                        && !Boolean.TRUE.equals(task.getInitialFullSyncDone())) {
                    yield computeInitialFullSyncWindow(task);
                }
                yield computeIncrementalWindow(task);
            }
            case "CUSTOM_WINDOW" -> {
                Instant ws = overrideStart != null ? overrideStart : task.getCustomWindowStart();
                Instant we = overrideEnd   != null ? overrideEnd   : task.getCustomWindowEnd();
                yield new WindowContext("CUSTOM_WINDOW", ws, we, null, null);
            }
            default -> throw new IllegalArgumentException(
                    "WatermarkService: unknown dataScope=" + dataScope + " for task " + task.getId());
        };
    }

    // ── commit watermark ─────────────────────────────────────────────────────

    /**
     * 增量任务执行成功后，提交水位到 sync_task.incremental_checkpoint。
     * CUSTOM_WINDOW / FULL 不调用此方法。
     */
    @Transactional
    public void commit(SyncTask task, WindowContext window) {
        // 一键全量→增量：首次全量成功 → 写入准水位 + 标记完成
        if (window.isInitialFullSync()) {
            if ("ID_RANGE".equals(task.getIncrementMode()) && window.windowEndId() != null) {
                if (isIdWatermarkRegression(task, window.windowEndId())) {
                    log.warn("WatermarkService: skip initial ID watermark regression task={} newEndId={} existing={}",
                            task.getId(), window.windowEndId(), task.getInitialWatermark());
                } else {
                    task.setInitialWatermark(String.valueOf(window.windowEndId()));
                }
                // 同时设置 incrementalCheckpoint 用于监控页展示
                if (task.getIncrementalCheckpoint() == null) {
                    task.setIncrementalCheckpoint(Instant.now());
                }
            } else if (window.windowEnd() != null) {
                if (isTimeWatermarkRegression(task, window.windowEnd())) {
                    log.warn("WatermarkService: skip initial time watermark regression task={} newEnd={} existing={}",
                            task.getId(), window.windowEnd(), task.getIncrementalCheckpoint());
                } else {
                    task.setIncrementalCheckpoint(window.windowEnd());
                }
            }
            task.setInitialFullSyncDone(true);
            syncTaskRepo.save(task);
            log.info("WatermarkService: initial full sync done, task={} promoted to INCREMENTAL (next run)", task.getId());
            return;
        }
        if (!"INCREMENT".equals(window.windowType())) {
            return;
        }
        if ("ID_RANGE".equals(task.getIncrementMode())) {
            if (window.windowEndId() != null) {
                // 单调性保护：新水位不大于现有水位时跳过，避免重启恢复或乱序提交导致水位回退
                if (isIdWatermarkRegression(task, window.windowEndId())) {
                    log.warn("WatermarkService: skip ID watermark regression task={} newEndId={} existing={}",
                            task.getId(), window.windowEndId(), task.getInitialWatermark());
                    if (isSameIdWatermark(task, window.windowEndId())) {
                        syncTaskRepo.save(task);
                    }
                    return;
                }
                // 将 ID 以字符串存入 initial_watermark（复用字段，避免新增列）
                // 下次 computeWindow 读取时解析
                task.setInitialWatermark(String.valueOf(window.windowEndId()));
                // 同时更新 incrementalCheckpoint 为当前时间（供 MonitorPage 展示）
                task.setIncrementalCheckpoint(Instant.now());
                syncTaskRepo.save(task);
                log.info("WatermarkService: commit ID watermark task={} endId={}", task.getId(), window.windowEndId());
            }
        } else {
            // TIME_FIELD：windowEnd 即水位
            if (window.windowEnd() != null) {
                // 单调性保护：新水位不晚于现有水位时跳过，避免重启恢复或乱序提交导致水位回退
                if (isTimeWatermarkRegression(task, window.windowEnd())) {
                    log.warn("WatermarkService: skip time watermark regression task={} newEnd={} existing={}",
                            task.getId(), window.windowEnd(), task.getIncrementalCheckpoint());
                    return;
                }
                task.setIncrementalCheckpoint(window.windowEnd());
                syncTaskRepo.save(task);
                log.info("WatermarkService: commit time watermark task={} windowEnd={}", task.getId(), window.windowEnd());
            }
        }
    }

    /**
     * TIME_FIELD 水位回退判定：新 windowEnd 不晚于现有 incrementalCheckpoint 即视为回退。
     */
    private boolean isTimeWatermarkRegression(SyncTask task, Instant newEnd) {
        Instant existing = task.getIncrementalCheckpoint();
        if (existing == null) {
            return false;
        }
        return !newEnd.isAfter(existing);
    }

    /**
     * ID_RANGE 水位回退判定：新 windowEndId 不大于现有水位（解析自 initial_watermark）即视为回退。
     * 现有水位无法解析为数值时不阻断提交（保持向后兼容）。
     */
    private boolean isIdWatermarkRegression(SyncTask task, Long newEndId) {
        String existingRaw = task.getInitialWatermark();
        if (existingRaw == null || existingRaw.isBlank()) {
            return false;
        }
        try {
            long existing = Long.parseLong(existingRaw.trim());
            return newEndId <= existing;
        } catch (NumberFormatException e) {
            // 现有水位是时间戳字符串或其他格式，无法做 ID 比较，放行
            return false;
        }
    }

    private boolean isSameIdWatermark(SyncTask task, Long newEndId) {
        if (newEndId == null) {
            return false;
        }
        String existingRaw = task.getInitialWatermark();
        if (existingRaw == null || existingRaw.isBlank()) {
            return false;
        }
        try {
            return newEndId.equals(Long.parseLong(existingRaw.trim()));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 首次全量同步窗口：不加 WHERE 过滤（按 FULL 读取），同时记录准水位供 commit 写入。
     */
    private WindowContext computeInitialFullSyncWindow(SyncTask task) {
        String mode = task.getIncrementMode();
        if ("ID_RANGE".equals(mode)) {
            Long currentMaxId = queryMaxId(task, null);
            log.info("WatermarkService: initial full sync (ID_RANGE) task={} maxId={}", task.getId(), currentMaxId);
            return new WindowContext("FULL_THEN_INCREMENT", null, null, null, currentMaxId);
        }
        // TIME_FIELD：准水位 = now - delayMinutes
        int delayMinutes = 0;
        if ("DELAY_MINUTES".equals(task.getUpperBoundStrategy())) {
            delayMinutes = task.getUpperBoundDelayMinutes() != null ? task.getUpperBoundDelayMinutes() : 5;
        }
        Instant endTs = Instant.now().minus(delayMinutes, ChronoUnit.MINUTES);
        log.info("WatermarkService: initial full sync (TIME_FIELD) task={} endTs={}", task.getId(), endTs);
        return new WindowContext("FULL_THEN_INCREMENT", null, endTs, null, null);
    }

    // ── private ──────────────────────────────────────────────────────────────

    private WindowContext computeIncrementalWindow(SyncTask task) {
        String mode = task.getIncrementMode();
        if ("ID_RANGE".equals(mode)) {
            return computeIdRangeWindow(task);
        }
        // 默认 TIME_FIELD
        return computeTimeFieldWindow(task);
    }

    private WindowContext computeTimeFieldWindow(SyncTask task) {
        int lookback = task.getLookbackSeconds() != null ? task.getLookbackSeconds() : 0;
        if (lookback > 0 && isAppendWriteMode(task)) {
            throw new IllegalArgumentException(
                    "APPEND 模式不支持 lookbackSeconds > 0：回看窗口会重复追加数据，请改用 UPSERT 或关闭回看");
        }

        // windowStart = 上次成功水位（incrementalCheckpoint），null 则用 initialWatermark 解析
        Instant windowStart = task.getIncrementalCheckpoint();
        if (windowStart == null && task.getInitialWatermark() != null && !task.getInitialWatermark().isBlank()) {
            try {
                windowStart = Instant.parse(task.getInitialWatermark().trim());
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "initialWatermark 格式非法：TIME_FIELD 模式下应为 ISO-8601 Instant，例如 2026-01-01T00:00:00Z", e);
            }
        }

        // windowEnd = now - delayMinutes
        int delayMinutes = 0;
        if ("DELAY_MINUTES".equals(task.getUpperBoundStrategy())) {
            delayMinutes = task.getUpperBoundDelayMinutes() != null ? task.getUpperBoundDelayMinutes() : 5;
        }
        Instant windowEnd = Instant.now().minus(delayMinutes, ChronoUnit.MINUTES);

        // spec 019: 回看窗口（晚到数据补偿）
        if (lookback > 0 && windowStart != null) {
            windowStart = windowStart.minusSeconds(lookback);
            log.debug("WatermarkService: task={} apply lookbackSeconds={} -> windowStart={}",
                    task.getId(), lookback, windowStart);
        }

        // 校验窗口不反转：windowStart 必须早于 windowEnd
        if (windowStart != null && windowEnd != null && !windowStart.isBefore(windowEnd)) {
            log.warn("WatermarkService: 反转窗口检测 taskId={} windowStart={} >= windowEnd={} "
                   + "(lookbackSeconds={}, upperBoundDelayMinutes={}), 跳过本次执行",
                   task.getId(), windowStart, windowEnd,
                   task.getLookbackSeconds(), task.getUpperBoundDelayMinutes());
            throw new IllegalStateException(
                    "增量窗口反转（windowStart >= windowEnd），请检查 lookbackSeconds 和 upperBoundDelayMinutes 配置");
        }

        return new WindowContext("INCREMENT", windowStart, windowEnd, null, null);
    }

    private WindowContext computeIdRangeWindow(SyncTask task) {
        // windowStart = 上次 max ID（存在 initialWatermark 字段中）
        Long lastMaxId = null;
        if (task.getInitialWatermark() != null && !task.getInitialWatermark().isBlank()) {
            try {
                lastMaxId = Long.parseLong(task.getInitialWatermark().trim());
                if (lastMaxId < 0) {
                    throw new NumberFormatException("negative ID watermark");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "initialWatermark 格式非法：ID_RANGE 模式下应为整数 ID", e);
            }
        }

        if (lastMaxId == null) lastMaxId = 0L;

        // 查询源端当前最大 ID；必须复用执行过滤条件和上次 ID 下界。
        Long currentMaxId = queryMaxId(task, lastMaxId);
        if (currentMaxId == null) {
            log.info("WatermarkService: ID_RANGE task={} no rows above lastMaxId={}, using empty ID window",
                    task.getId(), lastMaxId);
            return new WindowContext("INCREMENT", null, null, lastMaxId, lastMaxId);
        }

        log.info("WatermarkService: ID_RANGE task={} lastMaxId={} currentMaxId={}", task.getId(), lastMaxId, currentMaxId);
        return new WindowContext("INCREMENT", null, null, lastMaxId, currentMaxId);
    }

    private boolean isAppendWriteMode(SyncTask task) {
        return task != null
                && task.getSyncMode() != null
                && "APPEND".equalsIgnoreCase(task.getSyncMode().trim());
    }

    private Long queryMaxId(SyncTask task, Long lastMaxId) {
        if (task.getSourceDataSourceId() == null || task.getIncrementalField() == null) {
            throw new IllegalStateException("ID_RANGE max query failed: sourceDataSourceId/incrementalField is required");
        }
        SourceDataSource ds = sourceDsRepo.findById(task.getSourceDataSourceId())
                .orElseThrow(() -> new IllegalStateException(
                        "ID_RANGE max query failed: SourceDataSource not found, id=" + task.getSourceDataSourceId()));

        String table = task.getViewNames() != null && !task.getViewNames().isEmpty()
                ? task.getViewNames().get(0) : null;
        if (table == null || table.isBlank()) {
            throw new IllegalStateException("ID_RANGE max query failed: source table is required");
        }

        String sql = buildMaxIdSql(task, ds, lastMaxId);
        String jdbcUrl = buildSourceJdbcUrl(ds);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, ds.getUsername(), aesUtil.decrypt(ds.getPasswordEnc()));
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                long val = rs.getLong(1);
                return rs.wasNull() ? null : val;
            }
            return null;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("WatermarkService: ID_RANGE max query failed for task {}: {}", task.getId(), msg, e);
            throw new IllegalStateException("ID_RANGE max query failed: " + msg, e);
        }
    }

    private String buildMaxIdSql(SyncTask task, SourceDataSource ds, Long lastMaxId) {
        if (task.getSourceMode() != null && "CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode())) {
            throw new UnsupportedOperationException("CUSTOM_SQL 模式暂不支持 ID_RANGE MAX(id) 查询");
        }
        String field = task.getIncrementalField();
        String table = task.getViewNames().get(0);
        String schema = resolveSourceSchema(task, ds);
        String dialect = ds.getType() == null ? "MYSQL" : ds.getType().toUpperCase(Locale.ROOT);
        // 映射回 JDBC 原始大小写（任务配置可能是小写，但源端列名可能是大写）
        if (sourceDataSourceService != null && task.getSourceDataSourceId() != null) {
            String resolved = sourceDataSourceService.resolveOriginalColumnName(
                    task.getSourceDataSourceId(), schema, table, field);
            if (resolved != null) {
                field = resolved;
            }
        }
        String qualifiedTable = dialectQuoteHelper.qualifyTable(dialect, schema, table);
        String where = whereClauseBuilder.build(
                task,
                dialect,
                lastMaxId == null
                        ? new WindowContext("FULL", null, null, null, null)
                        : new WindowContext("INCREMENT", null, null, lastMaxId, null),
                table);
        String sql = "SELECT MAX(" + dialectQuoteHelper.quoteColumn(dialect, field) + ") FROM " + qualifiedTable;
        return where == null || where.isBlank() ? sql : sql + " WHERE " + where;
    }

    private String resolveSourceSchema(SyncTask task, SourceDataSource ds) {
        if (task.getSourceSchema() != null && !task.getSourceSchema().isBlank()) {
            return task.getSourceSchema();
        }
        String schema = ds.getSchemaName();
        if ((schema == null || schema.isBlank()) && "ORACLE".equalsIgnoreCase(ds.getType())) {
            schema = ds.getUsername() != null ? ds.getUsername().toUpperCase(Locale.ROOT) : null;
        }
        return schema;
    }

    private String buildSourceJdbcUrl(SourceDataSource ds) {
        return switch (ds.getType().toUpperCase()) {
            case "MYSQL" -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&connectTimeout=5000&socketTimeout=10000",
                    ds.getHost(), ds.getPort(), ds.getDbName());
            case "POSTGRESQL" -> String.format(
                    "jdbc:postgresql://%s:%d/%s?connectTimeout=5",
                    ds.getHost(), ds.getPort(), ds.getDbName());
            case "ORACLE" -> String.format(
                    "jdbc:oracle:thin:@%s:%d/%s", ds.getHost(), ds.getPort(), ds.getDbName());
            case "SQLSERVER" -> String.format(
                    "jdbc:sqlserver://%s:%d;databaseName=%s;loginTimeout=5",
                    ds.getHost(), ds.getPort(), ds.getDbName());
            // Doris 走 MySQL 协议（FE Query 端口）
            case "DORIS" -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=10000",
                    ds.getHost(), ds.getPort(), ds.getDbName());
            default -> throw new IllegalArgumentException("Unsupported DB type: " + ds.getType());
        };
    }
}
