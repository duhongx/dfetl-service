package com.dfygt.dfetl.server.service.validation;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.engine.doris.DorisTypeMappingPolicy;
import com.dfygt.dfetl.server.engine.doris.DorisTypeMappingPolicy.MappingResult;
import com.dfygt.dfetl.server.engine.doris.DorisTypeMappingPolicy.SourceTypeDescriptor;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.SyncTaskRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.dfygt.dfetl.server.service.SourceDataSourceService;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 列类型修正服务：扫描所有同步任务关联的 Doris 目标表，
 * 识别源端 NUMERIC 无精度但目标端为 DECIMAL 的列，并执行 ALTER TABLE 修正为 BIGINT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColumnTypeCorrectionService {

    private final SyncTaskRepository syncTaskRepo;
    private final SourceDataSourceRepository sourceDsRepo;
    private final SourceDataSourceService sourceDataSourceService;
    private final DorisTypeMappingPolicy typeMappingPolicy;
    private final ValidationJdbcHelper jdbcHelper;
    private final TargetDataSourceRepository targetDsRepo;
    private final AesUtil aesUtil;

    private static final int DATA_CHECK_TIMEOUT_SECONDS = 60;

    /**
     * 扫描所有同步任务，识别 NUMERIC 无精度但目标端为 DECIMAL 的列。
     *
     * @param dryRun true=仅输出清单和DDL，不执行数据验证和ALTER；false=执行修正
     */
    public CorrectionReport scan(boolean dryRun) {
        List<SyncTask> tasks = syncTaskRepo.findAll();
        List<CorrectionItem> items = new ArrayList<>();
        int totalScanned = 0;

        for (SyncTask task : tasks) {
            if (task.getSourceDataSourceId() == null || task.getTargetDataSourceId() == null) {
                continue;
            }

            SourceDataSource sourceDs = sourceDsRepo.findById(task.getSourceDataSourceId()).orElse(null);
            if (sourceDs == null) {
                continue;
            }

            String sourceDialect = sourceDs.getType() == null ? "MYSQL" : sourceDs.getType().toUpperCase();
            String schema = resolveSchema(task, sourceDs);
            if (schema == null) {
                continue;
            }

            // 获取目标端数据库名
            TargetDataSource targetDs = targetDsRepo.findById(task.getTargetDataSourceId()).orElse(null);
            if (targetDs == null) {
                continue;
            }
            String targetDb = targetDs.getDbName();

            // 获取该任务的表列表
            List<String> tables = getSourceTables(task);
            if (tables.isEmpty()) {
                continue;
            }

            // 获取 Doris 目标端当前列类型映射
            Map<String, Map<String, String>> dorisColumnTypes = getDorisColumnTypes(task, tables);

            for (String table : tables) {
                List<ColumnInfo> columns;
                try {
                    columns = sourceDataSourceService.listColumns(task.getSourceDataSourceId(), schema, table);
                } catch (Exception e) {
                    log.warn("ColumnTypeCorrection: listColumns failed taskId={} schema={} table={}: {}",
                            task.getId(), schema, table, e.getMessage());
                    continue;
                }

                String targetTable = resolveTargetTable(task, table);
                Map<String, String> currentTypes = dorisColumnTypes.getOrDefault(targetTable, Map.of());

                for (ColumnInfo col : columns) {
                    totalScanned++;

                    // 检查是否为 NUMERIC/NUMBER 无精度场景
                    if (!isNumericWithoutPrecision(col, sourceDialect)) {
                        continue;
                    }

                    // 获取推荐类型
                    boolean isView = "VIEW".equalsIgnoreCase(task.getSourceObjectType())
                            || "MATERIALIZED_VIEW".equalsIgnoreCase(task.getSourceObjectType());
                    SourceTypeDescriptor descriptor = SourceTypeDescriptor.fromColumn(sourceDialect, col, isView);
                    MappingResult recommended = typeMappingPolicy.recommend(descriptor);

                    // 推荐类型应为 BIGINT
                    if (!"BIGINT".equalsIgnoreCase(recommended.recommendedDorisType())) {
                        continue;
                    }

                    // 获取目标端当前列类型
                    String currentType = currentTypes.get(col.columnName());
                    if (currentType == null) {
                        // 尝试大小写不敏感匹配
                        currentType = currentTypes.entrySet().stream()
                                .filter(e -> e.getKey().equalsIgnoreCase(col.columnName()))
                                .map(Map.Entry::getValue)
                                .findFirst()
                                .orElse(null);
                    }

                    if (currentType == null) {
                        continue;
                    }

                    // 检查当前类型是否为 DECIMAL
                    if (!currentType.toUpperCase().startsWith("DECIMAL")) {
                        continue;
                    }

                    // 不匹配：推荐 BIGINT 但实际为 DECIMAL → 加入待修正清单
                    String ddl = generateAlterDdl(targetDb, targetTable, col.columnName());
                    CorrectionStatus status = dryRun ? CorrectionStatus.DRY_RUN : CorrectionStatus.DRY_RUN;
                    items.add(new CorrectionItem(
                            targetDb, targetTable, col.columnName(),
                            currentType, "BIGINT", ddl, status, null));
                }
            }
        }

        if (dryRun) {
            log.info("ColumnTypeCorrection: dryRun scan complete. totalScanned={} mismatchFound={}",
                    totalScanned, items.size());
            return new CorrectionReport(items, totalScanned, items.size(), 0, 0, 0);
        }

        // 非 dry-run：逐列执行数据验证 + ALTER
        int altered = 0;
        int skippedFractional = 0;
        int skippedError = 0;
        List<CorrectionItem> resultItems = new ArrayList<>();

        for (CorrectionItem item : items) {
            CorrectionItem resultItem = executeCorrectionForItem(item);
            resultItems.add(resultItem);
            switch (resultItem.status()) {
                case ALTERED -> altered++;
                case SKIPPED_FRACTIONAL -> skippedFractional++;
                case SKIPPED_TIMEOUT, SKIPPED_ERROR -> skippedError++;
                default -> {}
            }
        }

        log.info("ColumnTypeCorrection: scan complete. totalScanned={} mismatchFound={} altered={} "
                        + "skippedFractional={} skippedError={}",
                totalScanned, items.size(), altered, skippedFractional, skippedError);

        return new CorrectionReport(resultItems, totalScanned, items.size(),
                altered, skippedFractional, skippedError);
    }

    /**
     * 对单个待修正列执行数据验证和 ALTER。
     */
    private CorrectionItem executeCorrectionForItem(CorrectionItem item) {
        String dorisJdbcUrl = buildDorisJdbcUrlForDb(item.database());
        try (Connection conn = DriverManager.getConnection(dorisJdbcUrl)) {
            // 1. 数据验证：检查是否存在小数数据
            DataCheckResult check = checkFractionalData(conn, item.database(), item.tableName(), item.columnName());
            switch (check.status()) {
                case HAS_FRACTIONAL:
                    return new CorrectionItem(item.database(), item.tableName(), item.columnName(),
                            item.currentType(), item.recommendedType(), item.ddl(),
                            CorrectionStatus.SKIPPED_FRACTIONAL,
                            "存在小数数据，行数: " + check.count());
                case TIMEOUT:
                    return new CorrectionItem(item.database(), item.tableName(), item.columnName(),
                            item.currentType(), item.recommendedType(), item.ddl(),
                            CorrectionStatus.SKIPPED_TIMEOUT, check.reason());
                case ERROR:
                    return new CorrectionItem(item.database(), item.tableName(), item.columnName(),
                            item.currentType(), item.recommendedType(), item.ddl(),
                            CorrectionStatus.SKIPPED_ERROR, check.reason());
                case SAFE:
                    // 继续执行 ALTER
                    break;
            }

            // 2. 执行 ALTER TABLE
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(item.ddl());
                log.info("ColumnTypeCorrection: ALTER success db={} table={} column={}",
                        item.database(), item.tableName(), item.columnName());
                return new CorrectionItem(item.database(), item.tableName(), item.columnName(),
                        item.currentType(), item.recommendedType(), item.ddl(),
                        CorrectionStatus.ALTERED, null);
            } catch (SQLException e) {
                log.warn("ColumnTypeCorrection: ALTER failed db={} table={} column={}: {}",
                        item.database(), item.tableName(), item.columnName(), e.getMessage());
                return new CorrectionItem(item.database(), item.tableName(), item.columnName(),
                        item.currentType(), item.recommendedType(), item.ddl(),
                        CorrectionStatus.SKIPPED_ERROR, "ALTER 失败: " + e.getMessage());
            }
        } catch (SQLException e) {
            log.warn("ColumnTypeCorrection: connection failed for db={}: {}",
                    item.database(), e.getMessage());
            return new CorrectionItem(item.database(), item.tableName(), item.columnName(),
                    item.currentType(), item.recommendedType(), item.ddl(),
                    CorrectionStatus.SKIPPED_ERROR, "连接失败: " + e.getMessage());
        }
    }

    /**
     * 单列数据验证：检查是否存在小数数据。
     * 使用 SELECT COUNT(*) WHERE col != CAST(col AS BIGINT) 验证，设置 60 秒超时。
     */
    private DataCheckResult checkFractionalData(Connection conn, String db, String table, String column) {
        String sql = "SELECT COUNT(*) FROM `%s`.`%s` WHERE `%s` != CAST(`%s` AS BIGINT)"
                .formatted(db, table, column, column);
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(DATA_CHECK_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                long count = rs.next() ? rs.getLong(1) : 0;
                return count > 0
                        ? new DataCheckResult(CheckStatus.HAS_FRACTIONAL, count, null)
                        : new DataCheckResult(CheckStatus.SAFE, 0, null);
            }
        } catch (SQLTimeoutException e) {
            return new DataCheckResult(CheckStatus.TIMEOUT, -1,
                    "查询超时(%ds)".formatted(DATA_CHECK_TIMEOUT_SECONDS));
        } catch (Exception e) {
            return new DataCheckResult(CheckStatus.ERROR, -1, e.getMessage());
        }
    }

    /**
     * 生成 ALTER DDL。
     */
    public static String generateAlterDdl(String db, String table, String column) {
        return "ALTER TABLE `%s`.`%s` MODIFY COLUMN `%s` BIGINT".formatted(db, table, column);
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    /**
     * 获取 Doris 目标端当前列类型（通过 SHOW COLUMNS FROM 查询）。
     * 返回 Map: targetTable -> (columnName -> columnType)
     */
    private Map<String, Map<String, String>> getDorisColumnTypes(SyncTask task, List<String> sourceTables) {
        Map<String, Map<String, String>> result = new HashMap<>();
        String dorisJdbcUrl = jdbcHelper.buildDorisJdbcUrl(task);

        TargetDataSource targetDs = targetDsRepo.findById(task.getTargetDataSourceId()).orElse(null);
        if (targetDs == null) {
            return result;
        }
        String password = aesUtil.decrypt(targetDs.getPasswordEnc());

        try (Connection conn = DriverManager.getConnection(dorisJdbcUrl, targetDs.getUsername(), password)) {
            for (String sourceTable : sourceTables) {
                String targetTable = resolveTargetTable(task, sourceTable);
                Map<String, String> columnTypes = new HashMap<>();
                String sql = "SHOW COLUMNS FROM `%s`.`%s`".formatted(targetDs.getDbName(), targetTable);
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        String colName = rs.getString("Field");
                        String colType = rs.getString("Type");
                        columnTypes.put(colName, colType);
                    }
                } catch (SQLException e) {
                    log.warn("ColumnTypeCorrection: SHOW COLUMNS failed for {}.{}: {}",
                            targetDs.getDbName(), targetTable, e.getMessage());
                }
                result.put(targetTable, columnTypes);
            }
        } catch (SQLException e) {
            log.warn("ColumnTypeCorrection: Doris connection failed taskId={}: {}",
                    task.getId(), e.getMessage());
        }
        return result;
    }

    /**
     * 判断列是否为 NUMERIC/NUMBER 无精度场景。
     */
    private boolean isNumericWithoutPrecision(ColumnInfo col, String dialect) {
        String typeName = col.dataType();
        if (typeName == null) {
            return false;
        }
        String upper = typeName.toUpperCase();
        boolean isNumericType = upper.contains("NUMERIC") || upper.contains("NUMBER") || upper.contains("DECIMAL");
        if (!isNumericType) {
            return false;
        }
        // precision 为 null 或 ≤0 且 scale 为 null 或 0
        Integer precision = col.columnSize();
        Integer scale = col.decimalDigits();
        return (precision == null || precision <= 0) && (scale == null || scale == 0);
    }

    /**
     * 解析源端 schema。
     */
    private String resolveSchema(SyncTask task, SourceDataSource sourceDs) {
        // 优先使用任务级 schema，其次数据源级 schema
        String schema = task.getSourceSchema();
        if (schema != null && !schema.isBlank()) {
            return schema.trim();
        }
        schema = sourceDs.getSchemaName();
        if (schema != null && !schema.isBlank()) {
            return schema.trim();
        }
        // Oracle 默认使用 username 大写
        if ("ORACLE".equalsIgnoreCase(sourceDs.getType())) {
            String username = sourceDs.getUsername();
            return username == null ? null : username.toUpperCase();
        }
        return null;
    }

    /**
     * 获取任务的源表列表。
     */
    private List<String> getSourceTables(SyncTask task) {
        if ("CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode())) {
            // 自定义 SQL 模式不适用列类型修正
            return List.of();
        }
        List<String> viewNames = task.getViewNames();
        if (viewNames == null || viewNames.isEmpty()) {
            return List.of();
        }
        return viewNames;
    }

    /**
     * 解析目标表名（支持 targetTableMap 映射）。
     */
    private String resolveTargetTable(SyncTask task, String sourceTable) {
        String targetTableMap = task.getTargetTableMap();
        if (targetTableMap != null && !targetTableMap.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> map = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(targetTableMap, Map.class);
                String mapped = map.get(sourceTable);
                if (mapped != null && !mapped.isBlank()) {
                    return mapped;
                }
            } catch (Exception ignored) {
                // JSON 解析失败，使用默认表名
            }
        }
        return sourceTable;
    }

    /**
     * 构建 Doris JDBC URL（用于非 dry-run 模式的数据验证和 ALTER 执行）。
     */
    private String buildDorisJdbcUrlForDb(String database) {
        // 使用第一个可用的目标数据源来获取连接信息
        List<TargetDataSource> allTargets = targetDsRepo.findAll();
        for (TargetDataSource tds : allTargets) {
            if (database.equals(tds.getDbName())) {
                String password = aesUtil.decrypt(tds.getPasswordEnc());
                return String.format(
                        "jdbc:mysql://%s:%d/%s?useSSL=false&connectTimeout=5000&user=%s&password=%s",
                        tds.getFeHost(), tds.getFePort(), tds.getDbName(),
                        tds.getUsername(), password);
            }
        }
        // 回退：使用默认连接
        return "jdbc:mysql://127.0.0.1:9030/" + database + "?useSSL=false&connectTimeout=5000";
    }

    // ── 返回值类型 ──────────────────────────────────────────────────────────────

    public record CorrectionReport(
            List<CorrectionItem> items,
            int totalScanned,
            int mismatchFound,
            int altered,
            int skippedFractional,
            int skippedError
    ) {}

    public record CorrectionItem(
            String database,
            String tableName,
            String columnName,
            String currentType,
            String recommendedType,
            String ddl,
            CorrectionStatus status,
            String reason
    ) {}

    public enum CorrectionStatus {
        /** 已成功修正 */
        ALTERED,
        /** 存在小数数据，跳过 */
        SKIPPED_FRACTIONAL,
        /** 数据验证超时，跳过 */
        SKIPPED_TIMEOUT,
        /** 执行异常，跳过 */
        SKIPPED_ERROR,
        /** 仅预览模式 */
        DRY_RUN
    }

    private record DataCheckResult(CheckStatus status, long count, String reason) {}

    private enum CheckStatus { SAFE, HAS_FRACTIONAL, TIMEOUT, ERROR }
}
