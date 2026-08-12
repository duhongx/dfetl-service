package com.dfygt.dfetl.server.engine.doris;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.SystemSetting;
import com.dfygt.dfetl.server.repository.SystemSettingRepository;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Doris 自动建表的物理布局策略。
 *
 * <p>本服务只影响系统自动生成 CREATE TABLE DDL。已存在 Doris 表不因 bucket/partition
 * 与本策略不同而失败；bucket/partition 是性能和写入落分区策略，不参与医疗视图字段语义校验。
 */
@Service
@RequiredArgsConstructor
public class DorisAutoCreateTablePolicyService {

    /**
     * 分区开关。默认 false。
     * <p><b>重要约束</b>：分区仅对 DUPLICATE KEY 表或"分区列属于主键的 UNIQUE KEY 表"生效。
     * 医疗规范表均为 UNIQUE KEY + 业务主键，分区字段（xiugaisj 等）不在主键中，
     * 即使开启本开关，{@code DorisDdlBuilder} 也会按 Doris 约束跳过分区（并打 WARN）。
     * 医疗场景统一使用纯分桶（见 {@link #bucketCount}），分区在当前数据模型下基本不适用。
     */
    public static final String K_PARTITION_ENABLED = "doris.auto_create.partition.enabled";
    public static final String K_PARTITION_FIELD = "doris.auto_create.partition.field";
    public static final String K_PARTITION_GRANULARITY = "doris.auto_create.partition.granularity";
    public static final String K_PARTITION_HISTORY_MONTHS = "doris.auto_create.partition.history_months";
    public static final String K_PARTITION_FUTURE_MONTHS = "doris.auto_create.partition.future_months";
    public static final String K_PARTITION_HISTORY_DAYS = "doris.auto_create.partition.history_days";
    public static final String K_PARTITION_FUTURE_DAYS = "doris.auto_create.partition.future_days";

    public static final String K_BUCKET_STRATEGY = "doris.auto_create.bucket.strategy";
    public static final String K_BUCKET_FIXED = "doris.auto_create.bucket.fixed";
    public static final String K_BUCKET_TIER_LT_100K = "doris.auto_create.bucket.tier.lt_100k";
    public static final String K_BUCKET_TIER_100K_1M = "doris.auto_create.bucket.tier.100k_1m";
    public static final String K_BUCKET_TIER_1M_10M = "doris.auto_create.bucket.tier.1m_10m";
    public static final String K_BUCKET_TIER_10M_50M = "doris.auto_create.bucket.tier.10m_50m";
    public static final String K_BUCKET_TIER_50M_200M = "doris.auto_create.bucket.tier.50m_200m";
    public static final String K_BUCKET_TIER_200M_1B = "doris.auto_create.bucket.tier.200m_1b";
    public static final String K_BUCKET_TIER_GT_1B = "doris.auto_create.bucket.tier.gt_1b";

    private final SystemSettingRepository settingsRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TableLayoutPlan plan(SyncTask task, List<ColumnInfo> sourceColumns) {
        int bucketCount = bucketCount(task);
        PartitionPlan partition = partitionPlan(sourceColumns);
        return new TableLayoutPlan(bucketCount, partition);
    }

    private int bucketCount(SyncTask task) {
        String strategy = setting(K_BUCKET_STRATEGY, "FIXED").toUpperCase(Locale.ROOT);
        if (!"DATA_SCALE".equals(strategy)) {
            return bucket(K_BUCKET_FIXED, 10);
        }
        return switch (estimatedRowsTier(task)) {
            case "LT_100K" -> bucket(K_BUCKET_TIER_LT_100K, 1);
            case "ROWS_100K_1M" -> bucket(K_BUCKET_TIER_100K_1M, 2);
            case "ROWS_1M_10M" -> bucket(K_BUCKET_TIER_1M_10M, 4);
            case "ROWS_10M_50M" -> bucket(K_BUCKET_TIER_10M_50M, 8);
            case "ROWS_50M_200M" -> bucket(K_BUCKET_TIER_50M_200M, 16);
            case "ROWS_200M_1B" -> bucket(K_BUCKET_TIER_200M_1B, 32);
            case "GT_1B" -> bucket(K_BUCKET_TIER_GT_1B, 64);
            default -> bucket(K_BUCKET_FIXED, 10);
        };
    }

    private String estimatedRowsTier(SyncTask task) {
        if (task == null || task.getDataCharacteristics() == null || task.getDataCharacteristics().isBlank()) {
            return "";
        }
        try {
            Map<String, Object> data = objectMapper.readValue(
                    task.getDataCharacteristics(), new TypeReference<Map<String, Object>>() {});
            Object detailed = data.get("estimatedRowsTier");
            if (detailed != null && !detailed.toString().isBlank()) {
                return detailed.toString().trim().toUpperCase(Locale.ROOT);
            }
            Object coarse = data.get("dataScale");
            if (coarse == null) {
                return "";
            }
            return switch (coarse.toString().trim().toUpperCase(Locale.ROOT)) {
                case "SMALL" -> "ROWS_100K_1M";
                case "MEDIUM" -> "ROWS_10M_50M";
                case "LARGE" -> "ROWS_200M_1B";
                default -> "";
            };
        } catch (Exception ignored) {
            return "";
        }
    }

    private PartitionPlan partitionPlan(List<ColumnInfo> sourceColumns) {
        if (!boolSetting(K_PARTITION_ENABLED, false)) {
            return new PartitionPlan(false, "", "", "自动分区未启用");
        }
        String field = setting(K_PARTITION_FIELD, "xiugaisj").trim();
        if (field.isBlank()) {
            return new PartitionPlan(false, "", "", "分区字段为空");
        }
        String sourceColumn = findColumn(sourceColumns, field);
        if (sourceColumn == null) {
            return new PartitionPlan(false, field.toLowerCase(Locale.ROOT), "", "分区字段不存在: " + field);
        }
        String granularity = setting(K_PARTITION_GRANULARITY, "MONTH").toUpperCase(Locale.ROOT);
        if ("DAY".equals(granularity)) {
            return dayPartition(sourceColumn.toLowerCase(Locale.ROOT));
        }
        return monthPartition(sourceColumn.toLowerCase(Locale.ROOT));
    }

    private PartitionPlan monthPartition(String field) {
        int historyMonths = intSetting(K_PARTITION_HISTORY_MONTHS, 36, 0, 120);
        int futureMonths = intSetting(K_PARTITION_FUTURE_MONTHS, 6, 1, 120);
        int bucketCount = intSetting(K_BUCKET_FIXED, 10, 1, 128);

        String clause = "PARTITION BY RANGE(`" + field + "`) ()\n";

        Map<String, String> dynamicProps = new LinkedHashMap<>();
        dynamicProps.put("dynamic_partition.enable", "true");
        dynamicProps.put("dynamic_partition.time_unit", "MONTH");
        dynamicProps.put("dynamic_partition.start", "-" + historyMonths);
        dynamicProps.put("dynamic_partition.end", String.valueOf(futureMonths));
        dynamicProps.put("dynamic_partition.prefix", "p");
        dynamicProps.put("dynamic_partition.buckets", String.valueOf(bucketCount));
        dynamicProps.put("dynamic_partition.create_history_partition", "true");

        return new PartitionPlan(true, field, "MONTH", clause, dynamicProps);
    }

    private PartitionPlan dayPartition(String field) {
        int historyDays = intSetting(K_PARTITION_HISTORY_DAYS, 90, 0, 400);
        int futureDays = intSetting(K_PARTITION_FUTURE_DAYS, 30, 1, 400);
        int bucketCount = intSetting(K_BUCKET_FIXED, 10, 1, 128);

        String clause = "PARTITION BY RANGE(`" + field + "`) ()\n";

        Map<String, String> dynamicProps = new LinkedHashMap<>();
        dynamicProps.put("dynamic_partition.enable", "true");
        dynamicProps.put("dynamic_partition.time_unit", "DAY");
        dynamicProps.put("dynamic_partition.start", "-" + historyDays);
        dynamicProps.put("dynamic_partition.end", String.valueOf(futureDays));
        dynamicProps.put("dynamic_partition.prefix", "p");
        dynamicProps.put("dynamic_partition.buckets", String.valueOf(bucketCount));
        dynamicProps.put("dynamic_partition.create_history_partition", "true");

        return new PartitionPlan(true, field, "DAY", clause, dynamicProps);
    }

    private String findColumn(List<ColumnInfo> columns, String name) {
        if (columns == null || name == null) {
            return null;
        }
        for (ColumnInfo column : columns) {
            if (column != null && column.columnName() != null && column.columnName().equalsIgnoreCase(name)) {
                return column.columnName();
            }
        }
        return null;
    }

    private int bucket(String key, int defaultValue) {
        return intSetting(key, defaultValue, 1, 128);
    }

    private int intSetting(String key, int defaultValue, int min, int max) {
        String raw = setting(key, String.valueOf(defaultValue));
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private boolean boolSetting(String key, boolean defaultValue) {
        String raw = setting(key, String.valueOf(defaultValue));
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(raw.trim());
    }

    private String setting(String key, String defaultValue) {
        return settingsRepository.findById(key)
                .map(SystemSetting::getSettingValue)
                .filter(value -> value != null && !value.isBlank())
                .orElse(defaultValue);
    }

    public record TableLayoutPlan(int bucketCount, PartitionPlan partition) {
    }

    public record PartitionPlan(boolean enabled, String field, String granularity, String clause,
                                Map<String, String> dynamicProperties) {
        public PartitionPlan(boolean enabled, String field, String granularity, String clause) {
            this(enabled, field, granularity, clause, Map.of());
        }

        public String reason() {
            return clause;
        }
    }
}
