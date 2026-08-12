package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.JdbcConnectionPoolManager;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** 在目标 Doris 数据库中创建和对齐专用预检表，不接触正式业务表。 */
@Service
public class DorisPrecheckStorageService {

    private static final Set<String> REQUIRED_RAW_SYSTEM_COLUMNS =
            Set.of("run_id", "row_id", "row_hash", "loaded_at");
    private static final Map<String, String> RAW_SYSTEM_TYPES = Map.of(
            "run_id", "BIGINT",
            "row_id", "VARCHAR",
            "row_hash", "VARCHAR",
            "loaded_at", "DATETIME");
    private static final Map<String, String> ISSUE_TYPES = Map.ofEntries(
            Map.entry("run_id", "BIGINT"),
            Map.entry("created_at", "DATETIME"),
            Map.entry("row_id", "VARCHAR"),
            Map.entry("row_hash", "VARCHAR"),
            Map.entry("business_pk", "STRING"),
            Map.entry("field_code", "VARCHAR"),
            Map.entry("error_type", "VARCHAR"),
            Map.entry("severity", "VARCHAR"),
            Map.entry("raw_value", "STRING"),
            Map.entry("normalized_value", "STRING"),
            Map.entry("standard_rule", "STRING"),
            Map.entry("error_message", "STRING"));
    private static final Map<String, String> SUMMARY_TYPES = Map.of(
            "run_id", "BIGINT",
            "created_at", "DATETIME",
            "field_code", "VARCHAR",
            "error_type", "VARCHAR",
            "severity", "VARCHAR",
            "issue_count", "BIGINT",
            "affected_rows", "BIGINT");

    private final TargetDataSourceRepository targetRepository;
    private final AesUtil aesUtil;
    private final JdbcConnectionPoolManager connectionPoolManager;
    private final int rawRetentionDays;
    private final int issueRetentionDays;
    private final int summaryRetentionDays;
    private final int buckets;
    private final int replicationNum;

    public DorisPrecheckStorageService(
            TargetDataSourceRepository targetRepository,
            AesUtil aesUtil,
            JdbcConnectionPoolManager connectionPoolManager,
            @Value("${dfetl.data-precheck.doris.raw-retention-days:30}") int rawRetentionDays,
            @Value("${dfetl.data-precheck.doris.issue-retention-days:90}") int issueRetentionDays,
            @Value("${dfetl.data-precheck.doris.summary-retention-days:90}") int summaryRetentionDays,
            @Value("${dfetl.data-precheck.doris.buckets:16}") int buckets,
            @Value("${dfetl.data-precheck.doris.replication-num:1}") int replicationNum) {
        this.targetRepository = targetRepository;
        this.aesUtil = aesUtil;
        this.connectionPoolManager = connectionPoolManager;
        this.rawRetentionDays = rawRetentionDays;
        this.issueRetentionDays = issueRetentionDays;
        this.summaryRetentionDays = summaryRetentionDays;
        this.buckets = buckets;
        this.replicationNum = replicationNum;
    }

    public DorisPrecheckTableSpec ensureStorage(
            Long targetDataSourceId,
            String datasetCode,
            List<String> businessColumns) {
        TargetDataSource target = targetRepository.findById(targetDataSourceId)
                .orElseThrow(() -> new NoSuchElementException(
                        "目标数据源不存在: " + targetDataSourceId));
        if (!"NORMAL".equalsIgnoreCase(target.getStatus())) {
            throw new IllegalStateException(
                    "Doris 目标数据源必须为 NORMAL，当前状态: " + target.getStatus());
        }
        DorisPrecheckTableSpec spec = DorisPrecheckTableSpec.create(
                datasetCode,
                businessColumns,
                target.getDbName(),
                rawRetentionDays,
                issueRetentionDays,
                summaryRetentionDays,
                buckets,
                replicationNum);

        try (Connection connection = openConnection(target);
             Statement statement = connection.createStatement()) {
            statement.execute(spec.createRawTableDdl());
            statement.execute(spec.createIssueTableDdl());
            statement.execute(spec.createSummaryTableDdl());

            Map<String, String> actualColumnTypes = loadColumnTypes(statement, spec.database(), spec.rawTable());
            Set<String> actualColumns = actualColumnTypes.keySet();
            if (!actualColumns.containsAll(REQUIRED_RAW_SYSTEM_COLUMNS)) {
                Set<String> missing = new LinkedHashSet<>(REQUIRED_RAW_SYSTEM_COLUMNS);
                missing.removeAll(actualColumns);
                throw new IllegalStateException(
                        "Doris 预检暂存表缺少技术字段，拒绝自动修复 Key 契约: " + missing);
            }
            validateColumnTypes(spec.rawTable(), actualColumnTypes, RAW_SYSTEM_TYPES);
            for (String column : spec.businessColumns()) {
                String actualType = actualColumnTypes.get(column);
                if (actualType != null && !isCompatibleType("STRING", actualType)) {
                    throw incompatibleType(spec.rawTable(), column, actualType, "STRING");
                }
            }
            for (String column : spec.businessColumns()) {
                if (!actualColumns.contains(column)) {
                    statement.execute(spec.addRawColumnDdl(column));
                }
            }
            validateColumnTypes(
                    "precheck_issue",
                    loadColumnTypes(statement, spec.database(), "precheck_issue"),
                    ISSUE_TYPES);
            validateColumnTypes(
                    "precheck_summary",
                    loadColumnTypes(statement, spec.database(), "precheck_summary"),
                    SUMMARY_TYPES);
            validatePhysicalLayout(statement, spec);
            return spec;
        } catch (Exception e) {
            if (e instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException(
                    "初始化 Doris 预检存储失败: " + safeMessage(e), e);
        }
    }

    private Map<String, String> loadColumnTypes(
            Statement statement,
            String database,
            String table) throws Exception {
        String sql = "SELECT LOWER(column_name), UPPER(data_type), is_nullable"
                + " FROM information_schema.columns"
                + " WHERE table_schema = '" + escapeLiteral(database) + "'"
                + " AND table_name = '" + escapeLiteral(table) + "'"
                + " ORDER BY ordinal_position";
        Map<String, String> columns = new LinkedHashMap<>();
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                String column = resultSet.getString(1);
                if (column != null && !column.isBlank()) {
                    String type = resultSet.getString(2);
                    columns.put(
                            column.trim().toLowerCase(Locale.ROOT),
                            type == null ? "" : type.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }

    private void validateColumnTypes(
            String table,
            Map<String, String> actual,
            Map<String, String> expected) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String actualType = actual.get(entry.getKey());
            if (actualType == null) {
                throw new IllegalStateException(
                        "Doris 预检公共表 " + table + " 缺少字段: " + entry.getKey());
            }
            if (!isCompatibleType(entry.getValue(), actualType)) {
                throw incompatibleType(table, entry.getKey(), actualType, entry.getValue());
            }
        }
    }

    /** Doris 会将 DDL 中的 STRING 在 information_schema 中规范化为 VARCHAR。 */
    private boolean isCompatibleType(String expected, String actual) {
        return expected.equals(actual)
                || ("STRING".equals(expected) && "VARCHAR".equals(actual));
    }

    private IllegalStateException incompatibleType(
            String table,
            String column,
            String actual,
            String expected) {
        return new IllegalStateException(
                "Doris 预检表 " + table + " 字段 " + column
                        + " 类型不兼容: actual=" + actual + ", expected=" + expected);
    }

    private void validatePhysicalLayout(
            Statement statement,
            DorisPrecheckTableSpec spec) throws Exception {
        validateCreateTable(statement, spec.qualifiedRawTable(), List.of(
                "DUPLICATE KEY(`RUN_ID`, `ROW_ID`, `LOADED_AT`)",
                "PARTITION BY RANGE(`LOADED_AT`)",
                "DISTRIBUTED BY HASH(`RUN_ID`)",
                "\"DYNAMIC_PARTITION.ENABLE\" = \"TRUE\""));
        validateCreateTable(statement, qualified(spec.database(), "precheck_issue"), List.of(
                "DUPLICATE KEY(`RUN_ID`, `CREATED_AT`, `ROW_ID`)",
                "PARTITION BY RANGE(`CREATED_AT`)",
                "DISTRIBUTED BY HASH(`RUN_ID`)",
                "\"DYNAMIC_PARTITION.ENABLE\" = \"TRUE\""));
        validateCreateTable(statement, qualified(spec.database(), "precheck_summary"), List.of(
                "DUPLICATE KEY(`RUN_ID`, `CREATED_AT`, `FIELD_CODE`, `ERROR_TYPE`, `SEVERITY`)",
                "PARTITION BY RANGE(`CREATED_AT`)",
                "DISTRIBUTED BY HASH(`RUN_ID`)",
                "\"DYNAMIC_PARTITION.ENABLE\" = \"TRUE\""));
    }

    private void validateCreateTable(
            Statement statement,
            String qualifiedTable,
            List<String> requiredFragments) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("SHOW CREATE TABLE " + qualifiedTable)) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Doris 未返回预检表物理定义: " + qualifiedTable);
            }
            String ddl = resultSet.getString(2);
            String normalized = ddl == null ? "" : ddl.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            for (String fragment : requiredFragments) {
                if (!normalized.contains(fragment)) {
                    throw new IllegalStateException(
                            "Doris 预检表物理合同不兼容: table=" + qualifiedTable
                                    + ", missing=" + fragment);
                }
            }
        }
    }

    private String qualified(String database, String table) {
        return "`" + database + "`.`" + table + "`";
    }

    private Connection openConnection(TargetDataSource target) throws Exception {
        int port = target.getFePort() == null ? 9030 : target.getFePort();
        String url = "jdbc:mysql://" + target.getFeHost() + ":" + port + "/" + target.getDbName()
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        return connectionPoolManager.getConnection(
                url,
                target.getUsername(),
                aesUtil.decrypt(target.getPasswordEnc()));
    }

    private String escapeLiteral(String value) {
        return value.replace("'", "''");
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
