package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.common.IdentifierSanitizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Doris 数据预检物理表规格。
 *
 * <p>所有动态标识符先经过白名单校验，再使用反引号引用。业务字段在暂存层统一为
 * 小写 {@code STRING NULL}；技术字段保持固定类型，不能被业务字段覆盖。
 */
public final class DorisPrecheckTableSpec {

    private static final Set<String> RESERVED_RAW_COLUMNS =
            Set.of("run_id", "row_id", "row_hash", "loaded_at");
    private static final String ISSUE_TABLE = "precheck_issue";
    private static final String SUMMARY_TABLE = "precheck_summary";

    private final String database;
    private final String rawTable;
    private final List<String> businessColumns;
    private final int rawRetentionDays;
    private final int issueRetentionDays;
    private final int summaryRetentionDays;
    private final int buckets;
    private final int replicationNum;

    private DorisPrecheckTableSpec(
            String datasetCode,
            String database,
            List<String> businessColumns,
            int rawRetentionDays,
            int issueRetentionDays,
            int summaryRetentionDays,
            int buckets,
            int replicationNum) {
        this.database = IdentifierSanitizer.requireValid(database, "precheckDatabase");
        this.rawTable = rawTableForDatasetCode(datasetCode);
        this.businessColumns = normalizeBusinessColumns(businessColumns);
        this.rawRetentionDays = requirePositive(rawRetentionDays, "rawRetentionDays");
        this.issueRetentionDays = requirePositive(issueRetentionDays, "issueRetentionDays");
        this.summaryRetentionDays = requirePositive(summaryRetentionDays, "summaryRetentionDays");
        this.buckets = requirePositive(buckets, "buckets");
        this.replicationNum = requirePositive(replicationNum, "replicationNum");
    }

    public static DorisPrecheckTableSpec create(
            String datasetCode,
            List<String> businessColumns,
            String database,
            int rawRetentionDays,
            int issueRetentionDays,
            int summaryRetentionDays,
            int buckets,
            int replicationNum) {
        return new DorisPrecheckTableSpec(
                datasetCode,
                database,
                businessColumns,
                rawRetentionDays,
                issueRetentionDays,
                summaryRetentionDays,
                buckets,
                replicationNum);
    }

    public String database() {
        return database;
    }

    public String rawTable() {
        return rawTable;
    }

    public List<String> businessColumns() {
        return businessColumns;
    }

    public String qualifiedRawTable() {
        return qualified(rawTable);
    }

    public String createRawTableDdl() {
        StringBuilder columns = new StringBuilder()
                .append("  `run_id` BIGINT NOT NULL,\n")
                .append("  `row_id` VARCHAR(64) NOT NULL,\n")
                .append("  `loaded_at` DATETIME NOT NULL,\n")
                .append("  `row_hash` VARCHAR(64) NULL");
        for (String column : businessColumns) {
            columns.append(",\n  ").append(quoted(column)).append(" STRING NULL");
        }
        return "CREATE TABLE IF NOT EXISTS " + qualifiedRawTable() + " (\n"
                + columns + "\n) ENGINE=OLAP\n"
                + "DUPLICATE KEY(`run_id`, `row_id`, `loaded_at`)\n"
                + "PARTITION BY RANGE(`loaded_at`) ()\n"
                + distributionAndDynamicPartition(rawRetentionDays);
    }

    public String createIssueTableDdl() {
        return "CREATE TABLE IF NOT EXISTS " + qualified(ISSUE_TABLE) + " (\n"
                + "  `run_id` BIGINT NOT NULL,\n"
                + "  `created_at` DATETIME NOT NULL,\n"
                + "  `row_id` VARCHAR(64) NOT NULL,\n"
                + "  `row_hash` VARCHAR(64) NULL,\n"
                + "  `business_pk` STRING NULL,\n"
                + "  `field_code` VARCHAR(128) NULL,\n"
                + "  `error_type` VARCHAR(64) NOT NULL,\n"
                + "  `severity` VARCHAR(20) NOT NULL,\n"
                + "  `raw_value` STRING NULL,\n"
                + "  `normalized_value` STRING NULL,\n"
                + "  `standard_rule` STRING NULL,\n"
                + "  `error_message` STRING NOT NULL\n"
                + ") ENGINE=OLAP\n"
                + "DUPLICATE KEY(`run_id`, `created_at`, `row_id`)\n"
                + "PARTITION BY RANGE(`created_at`) ()\n"
                + distributionAndDynamicPartition(issueRetentionDays);
    }

    public String createSummaryTableDdl() {
        return "CREATE TABLE IF NOT EXISTS " + qualified(SUMMARY_TABLE) + " (\n"
                + "  `run_id` BIGINT NOT NULL,\n"
                + "  `created_at` DATETIME NOT NULL,\n"
                + "  `field_code` VARCHAR(128) NOT NULL,\n"
                + "  `error_type` VARCHAR(64) NOT NULL,\n"
                + "  `severity` VARCHAR(20) NOT NULL,\n"
                + "  `issue_count` BIGINT NOT NULL,\n"
                + "  `affected_rows` BIGINT NOT NULL\n"
                + ") ENGINE=OLAP\n"
                + "DUPLICATE KEY(`run_id`, `created_at`, `field_code`, `error_type`, `severity`)\n"
                + "PARTITION BY RANGE(`created_at`) ()\n"
                + distributionAndDynamicPartition(summaryRetentionDays);
    }

    public String addRawColumnDdl(String column) {
        String normalized = normalizeBusinessColumn(column);
        return "ALTER TABLE " + qualifiedRawTable()
                + " ADD COLUMN IF NOT EXISTS " + quoted(normalized) + " STRING NULL";
    }

    public static String rawTableForDatasetCode(String datasetCode) {
        String normalized = datasetCode == null
                ? null
                : datasetCode.trim().toLowerCase(Locale.ROOT);
        normalized = IdentifierSanitizer.requireValid(normalized, "datasetCode");
        if (!normalized.startsWith("ods_yl_") || normalized.length() == "ods_yl_".length()) {
            throw new IllegalArgumentException(
                    "数据预检仅支持 ODS_YL_ 数据集编码: " + datasetCode);
        }
        return IdentifierSanitizer.requireValid(
                "raw_" + normalized.substring("ods_".length()),
                "precheckRawTable");
    }

    static String normalizeBusinessColumn(String column) {
        String value = column == null ? null : column.trim().toLowerCase(Locale.ROOT);
        String normalized = IdentifierSanitizer.requireValid(value, "precheckBusinessColumn");
        if (RESERVED_RAW_COLUMNS.contains(normalized)) {
            throw new IllegalArgumentException("预检业务字段不能覆盖技术字段: " + column);
        }
        return normalized;
    }

    private static List<String> normalizeBusinessColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("预检业务字段不能为空");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String column : columns) {
            String value = normalizeBusinessColumn(column);
            if (!normalized.add(value)) {
                throw new IllegalArgumentException("预检业务字段大小写归一化后重复: " + column);
            }
        }
        return List.copyOf(new ArrayList<>(normalized));
    }

    private String distributionAndDynamicPartition(int retentionDays) {
        return "DISTRIBUTED BY HASH(`run_id`) BUCKETS " + buckets + "\n"
                + "PROPERTIES (\n"
                + "  \"replication_num\" = \"" + replicationNum + "\",\n"
                + "  \"dynamic_partition.enable\" = \"true\",\n"
                + "  \"dynamic_partition.time_unit\" = \"DAY\",\n"
                + "  \"dynamic_partition.start\" = \"-" + retentionDays + "\",\n"
                + "  \"dynamic_partition.end\" = \"3\",\n"
                + "  \"dynamic_partition.prefix\" = \"p\",\n"
                + "  \"dynamic_partition.buckets\" = \"" + buckets + "\",\n"
                + "  \"dynamic_partition.create_history_partition\" = \"true\"\n"
                + ")";
    }

    private String qualified(String table) {
        return quoted(database) + "." + quoted(table);
    }

    private static String quoted(String identifier) {
        return "`" + IdentifierSanitizer.requireValid(identifier, "dorisIdentifier") + "`";
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
        return value;
    }

}
