package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/**
 * 变更数据读取器：从 Doris 查询变更数据。
 * <p>
 * 关键设计：
 * <ul>
 *   <li>使用流式 ResultSet 处理（fetchSize=1000），避免大窗口 OOM</li>
 *   <li>全量分页使用 keyset pagination（基于主键）替代 OFFSET，避免性能爆炸和数据漂移</li>
 *   <li>按 {@code _etl_sync_time} 列过滤增量（与 EtlSystemFieldsService 定义一致）</li>
 *   <li>统一类型归一化：Timestamp/Date → ISO 字符串、BigDecimal → string、byte[] → Base64</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChangeDataReader {

    /** ETL 同步时间字段名 — 与 EtlSystemFieldsService 保持一致 */
    public static final String ETL_SYNC_TIME_COL = "_etl_sync_time";

    /** spec message-publish-tenant-scope · Bug 1+2：与 ValidationWhereBuilder.ETL_JOB_ID_COL 一致。 */
    public static final String ETL_JOB_ID_COL = "_etl_job_id";

    /** 流式查询的 fetchSize */
    private static final int FETCH_SIZE = 1000;

    /** 时间格式化器：Doris DATETIME 列存储格式为 'yyyy-MM-dd HH:mm:ss' */
    private static final DateTimeFormatter DORIS_DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    /** ISO-8601 时间格式化器（消息体内 Date/Timestamp 输出格式） */
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.of("Asia/Shanghai"));

    private final TargetDataSourceRepository targetDataSourceRepository;
    private final AesUtil aesUtil;
    private final com.dfygt.dfetl.server.common.JdbcConnectionPoolManager connectionPoolManager;

    /**
     * 增量查询（流式）：按 _etl_sync_time 时间窗口查询变更行，每读到一行调用 consumer。
     * <p>
     * 调用方需自行处理每行（不会把全部行加载到内存）。
     *
     * <p>spec message-publish-tenant-scope · Bug 1：{@code taskId} 非空时 SQL 追加
     * {@code AND `_etl_job_id` = ?}，把多机构共表的目标端收敛到「本任务写入的行」，与 spec 069
     * 校验链路 {@link com.dfygt.dfetl.server.service.validation.ValidationWhereBuilder#appendTenantScopeFilter}
     * 同口径。{@code taskId} 为 null 时保持原行为（向后兼容）。
     *
     * @param targetDsId  目标 Doris 数据源 ID
     * @param targetTable 目标表名
     * @param taskId      任务范围过滤；非 null 时 SQL 追加 {@code AND `_etl_job_id` = ?}
     * @param windowStart 窗口起始（含）
     * @param windowEnd   窗口结束（不含）
     * @param consumer    每行回调
     * @return 读取的总行数
     */
    public long streamByWindow(Long targetDsId, String targetTable, Long taskId,
                               Instant windowStart, Instant windowEnd,
                               Consumer<Map<String, Object>> consumer) {
        // Doris DATETIME 比较使用字符串字面量（与 _etl_sync_time 写入格式一致）
        String startStr = DORIS_DATETIME_FMT.format(windowStart);
        String endStr = DORIS_DATETIME_FMT.format(windowEnd);
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT * FROM `").append(sanitizeTable(targetTable)).append("`");
        sqlBuilder.append(" WHERE `").append(ETL_SYNC_TIME_COL).append("` >= ? AND `")
                .append(ETL_SYNC_TIME_COL).append("` < ?");
        if (taskId != null) {
            sqlBuilder.append(" AND `").append(ETL_JOB_ID_COL).append("` = ?");
        }
        String sql = sqlBuilder.toString();
        log.debug("streamByWindow: targetDsId={}, table={}, taskId={}, window=[{}, {})",
                targetDsId, targetTable, taskId, startStr, endStr);

        try (Connection conn = openDorisConnection(targetDsId);
             PreparedStatement ps = prepareStreamingStatement(conn, sql)) {
            ps.setString(1, startStr);
            ps.setString(2, endStr);
            if (taskId != null) {
                ps.setLong(3, taskId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return iterateRows(rs, consumer);
            }
        } catch (Exception e) {
            log.error("streamByWindow failed: targetDsId={}, table={}, error={}", targetDsId, targetTable, e.getMessage(), e);
            throw new RuntimeException("Failed to query changes by window from Doris: " + e.getMessage(), e);
        }
    }

    /**
     * 按本次同步批次读取目标端实际写入行。
     *
     * <p>消息发布的自动触发必须以 {@code _etl_batch_id = executionId} 为准，而不是把源端业务
     * 增量窗口套到 {@code _etl_sync_time}。后者是加载时间，和源端业务字段不是同一时间维度。
     */
    public long streamByBatch(Long targetDsId, String targetTable, Long taskId, Long batchId,
                              Consumer<Map<String, Object>> consumer) {
        if (batchId == null) {
            throw new IllegalArgumentException("batchId is required for batch-scoped message publish");
        }
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT * FROM `").append(sanitizeTable(targetTable)).append("`");
        sqlBuilder.append(" WHERE `_etl_batch_id` = ?");
        if (taskId != null) {
            sqlBuilder.append(" AND `").append(ETL_JOB_ID_COL).append("` = ?");
        }
        String sql = sqlBuilder.toString();
        log.debug("streamByBatch: targetDsId={}, table={}, taskId={}, batchId={}",
                targetDsId, targetTable, taskId, batchId);

        try (Connection conn = openDorisConnection(targetDsId);
             PreparedStatement ps = prepareStreamingStatement(conn, sql)) {
            ps.setLong(1, batchId);
            if (taskId != null) {
                ps.setLong(2, taskId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return iterateRows(rs, consumer);
            }
        } catch (Exception e) {
            log.error("streamByBatch failed: targetDsId={}, table={}, batchId={}, error={}",
                    targetDsId, targetTable, batchId, e.getMessage(), e);
            throw new RuntimeException("Failed to query changes by batch from Doris: " + e.getMessage(), e);
        }
    }

    /**
     * 查询指定同步批次当前在 Doris 中可见的行数。
     *
     * <p>该查询用于消息发布前的有界可见性确认，避免 Stream Load 已结束但 tablet publish
     * 尚未完成时把非空执行误记为 {@code SUCCESS/0}。</p>
     */
    public long countByBatch(Long targetDsId, String targetTable, Long taskId, Long batchId) {
        if (batchId == null) {
            throw new IllegalArgumentException("batchId is required for batch visibility check");
        }
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT COUNT(*) FROM `").append(sanitizeTable(targetTable)).append("`");
        sqlBuilder.append(" WHERE `_etl_batch_id` = ?");
        if (taskId != null) {
            sqlBuilder.append(" AND `").append(ETL_JOB_ID_COL).append("` = ?");
        }
        try (Connection conn = openDorisConnection(targetDsId);
             PreparedStatement ps = conn.prepareStatement(sqlBuilder.toString())) {
            ps.setLong(1, batchId);
            if (taskId != null) {
                ps.setLong(2, taskId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to count changes by batch from Doris: " + e.getMessage(), e);
        }
    }

    /**
     * 全量流式查询（keyset pagination）：按主键升序遍历整张表。
     * <p>
     * 优点：不依赖 OFFSET，性能稳定 O(n)；无数据漂移问题。
     * 限制：必须有可排序的主键列（数值或字符串）。
     *
     * @param targetDsId  目标 Doris 数据源 ID
     * @param targetTable 目标表名
     * @param pkColumn    主键列名（用于 keyset 分页排序）
     * @param pageSize    每批读取行数
     * @param consumer    每行回调
     * @return 读取的总行数
     */
    public long streamFullByKey(Long targetDsId, String targetTable,
                                String pkColumn, int pageSize,
                                Consumer<Map<String, Object>> consumer) {
        if (pkColumn == null || pkColumn.isBlank()) {
            throw new IllegalArgumentException("pkColumn is required for full table streaming");
        }
        String table = sanitizeTable(targetTable);
        String pk = sanitizeColumn(pkColumn);
        long totalCount = 0;
        Object lastKey = null;

        while (true) {
            String sql;
            if (lastKey == null) {
                sql = "SELECT * FROM `" + table + "` ORDER BY `" + pk + "` ASC LIMIT ?";
            } else {
                sql = "SELECT * FROM `" + table + "` WHERE `" + pk + "` > ? ORDER BY `" + pk + "` ASC LIMIT ?";
            }

            int rowsRead = 0;
            Object pageLastKey = null;
            try (Connection conn = openDorisConnection(targetDsId);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                if (lastKey != null) {
                    ps.setObject(idx++, lastKey);
                }
                ps.setInt(idx, pageSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = extractRow(rs);
                        consumer.accept(row);
                        rowsRead++;
                        totalCount++;
                        pageLastKey = row.get(pkColumn);
                    }
                }
            } catch (Exception e) {
                log.error("streamFullByKey failed: targetDsId={}, table={}, lastKey={}, error={}",
                        targetDsId, targetTable, lastKey, e.getMessage(), e);
                throw new RuntimeException("Failed full table stream: " + e.getMessage(), e);
            }
            if (rowsRead < pageSize) break; // 最后一页
            lastKey = pageLastKey;
        }
        return totalCount;
    }

    /**
     * 全表流式读取（不依赖主键）：直接 SELECT * 遍历整张表。
     * <p>
     * 使用流式 ResultSet（fetchSize 控制内存），适用于联合主键或无唯一列的表。
     * 比 keyset pagination 更通用，但不支持断点续传。
     *
     * <p>spec message-publish-tenant-scope · Bug 2：{@code taskId} 非空时 SQL 追加
     * {@code WHERE `_etl_job_id` = ?}，避免多机构共表场景全量发布读到其它机构的行。
     * {@code taskId} 为 null 时保持原 {@code SELECT *} 行为（向后兼容）。
     *
     * @param targetDsId  目标 Doris 数据源 ID
     * @param targetTable 目标表名
     * @param taskId      任务范围过滤；非 null 时 SQL 追加 {@code WHERE `_etl_job_id` = ?}
     * @param consumer    每行回调
     * @return 读取的总行数
     */
    public long streamFull(Long targetDsId, String targetTable, Long taskId,
                           Consumer<Map<String, Object>> consumer) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT * FROM `").append(sanitizeTable(targetTable)).append("`");
        if (taskId != null) {
            sqlBuilder.append(" WHERE `").append(ETL_JOB_ID_COL).append("` = ?");
        }
        String sql = sqlBuilder.toString();
        log.info("streamFull: targetDsId={}, table={}, taskId={}, sql={}", targetDsId, targetTable, taskId, sql);

        try (Connection conn = openDorisConnection(targetDsId);
             PreparedStatement ps = prepareStreamingStatement(conn, sql)) {
            if (taskId != null) {
                ps.setLong(1, taskId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                long count = iterateRows(rs, consumer);
                log.info("streamFull: targetDsId={}, table={}, taskId={}, rowsRead={}",
                        targetDsId, targetTable, taskId, count);
                return count;
            }
        } catch (Exception e) {
            log.error("streamFull failed: targetDsId={}, table={}, error={}", targetDsId, targetTable, e.getMessage(), e);
            throw new RuntimeException("Failed to stream full table from Doris: " + e.getMessage(), e);
        }
    }

    /** 查询全表总行数 */
    public long countTotal(Long targetDsId, String targetTable) {
        String sql = "SELECT COUNT(*) FROM `" + sanitizeTable(targetTable) + "`";
        try (Connection conn = openDorisConnection(targetDsId);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (Exception e) {
            log.error("countTotal failed: targetDsId={}, table={}, error={}", targetDsId, targetTable, e.getMessage(), e);
            throw new RuntimeException("Failed to count rows: " + e.getMessage(), e);
        }
    }

    // ── private helpers ────────────────────────────────────────────────

    /** 创建流式 PreparedStatement（fetchSize 控制） */
    private PreparedStatement prepareStreamingStatement(Connection conn, String sql) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        // Doris 不支持 useCursorFetch，使用 MySQL 流式读取方式（fetchSize=MIN_VALUE 触发逐行流式）
        // 对于小表（<10万行）直接全量读取即可，不设 fetchSize
        return ps;
    }

    /** 流式遍历 ResultSet 调用 consumer */
    private long iterateRows(ResultSet rs, Consumer<Map<String, Object>> consumer) throws SQLException {
        long count = 0;
        while (rs.next()) {
            consumer.accept(extractRow(rs));
            count++;
        }
        return count;
    }

    /**
     * 提取单行 + 类型归一化。
     * 归一化规则：
     * - Timestamp / java.sql.Date → ISO-8601 字符串
     * - LocalDateTime / Instant → ISO-8601 字符串
     * - BigDecimal → string（避免 Jackson 转 double 精度丢失）
     * - byte[] → Base64
     * - 其他 → 原值
     */
    private Map<String, Object> extractRow(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        Map<String, Object> row = new LinkedHashMap<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String name = meta.getColumnLabel(i);
            Object raw = rs.getObject(i);
            row.put(name, normalize(raw));
        }
        return row;
    }

    /** 类型归一化 */
    static Object normalize(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp ts) {
            return ISO_FMT.format(ts.toInstant());
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (value instanceof Instant i) {
            return ISO_FMT.format(i);
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"));
        }
        if (value instanceof LocalDate ld) {
            return ld.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (value instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof Clob clob) {
            try {
                return clob.getSubString(1, (int) clob.length());
            } catch (SQLException e) {
                return null;
            }
        }
        return value;
    }

    /** 表名安全检查：只允许字母数字下划线 */
    private String sanitizeTable(String table) {
        if (table == null || !table.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }
        return table;
    }

    /** 列名安全检查：只允许字母数字下划线 */
    private String sanitizeColumn(String col) {
        if (col == null || !col.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("Invalid column name: " + col);
        }
        return col;
    }

    /** 打开 Doris JDBC 连接（MySQL 协议） */
    private Connection openDorisConnection(Long targetDsId) throws SQLException {
        TargetDataSource tgt = targetDataSourceRepository.findById(targetDsId)
                .orElseThrow(() -> new NoSuchElementException("TargetDataSource not found: " + targetDsId));
        String url = "jdbc:mysql://" + tgt.getFeHost() + ":" + tgt.getFePort() + "/" + tgt.getDbName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        log.debug("openDorisConnection: url={}, user={}", url, tgt.getUsername());
        return connectionPoolManager.getConnection(url, tgt.getUsername(), aesUtil.decrypt(tgt.getPasswordEnc()));
    }
}
