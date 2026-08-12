package com.dfygt.dfetl.server.engine.doris;

import com.dfygt.dfetl.server.common.IdentifierSanitizer;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalFieldContract;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 基于医共体数据集契约生成 Doris 标准表 DDL。
 */
@Component
public class MedicalDorisDdlBuilder {

    static final String ETL_JOB_ID_COL = "_etl_job_id";

    private static final int DEFAULT_BUCKET_COUNT = 10;

    private static final Pattern PREFERRED_BUCKET_PATTERN = Pattern.compile(
            "(?i)(BINGRENID|GERENDABS|JIUZHENID|JIUZHENLSH|MENZHENJZLSH|GUAHAOLSH)");

    private static final Pattern SECONDARY_BUCKET_PATTERN = Pattern.compile("(?i).+(ID|LSH|BSH|BH)$");

    public String buildCreateTable(
            String databaseName,
            MedicalDatasetContract contract,
            Map<String, String> etlFields) {
        if (contract == null) {
            throw new IllegalArgumentException("医共体数据集契约不能为空");
        }
        if (contract.fields() == null || contract.fields().isEmpty()) {
            throw new IllegalArgumentException("医共体数据集字段不能为空: " + contract.datasetCode());
        }
        String db = IdentifierSanitizer.requireValid(databaseName, "targetDatabase");
        String table = IdentifierSanitizer.requireValid(contract.targetTable(), "targetTable");
        Map<String, String> enabledEtlFields = etlFields == null ? Map.of() : new LinkedHashMap<>(etlFields);
        boolean hasPrimaryKey = contract.primaryKeys() != null && !contract.primaryKeys().isEmpty();
        if (!hasPrimaryKey && enabledEtlFields.keySet().stream()
                .noneMatch(ETL_JOB_ID_COL::equalsIgnoreCase)) {
            throw new IllegalArgumentException("无主键医共体数据集必须启用 _etl_job_id: " + contract.datasetCode());
        }

        OrderedMedicalColumns orderedColumns = orderedColumns(contract);
        List<String> keyColumns = keyColumns(contract, enabledEtlFields);
        requireSupportedKeyTypes(orderedColumns.keyFields(), enabledEtlFields);
        String bucketColumn = hasPrimaryKey ? selectBucketColumn(contract.primaryKeys()) : null;

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS `").append(db).append("`.`").append(table).append("` (\n");

        List<ColumnLine> lines = new ArrayList<>();
        Set<String> keySet = keyColumns.stream()
                .map(column -> column.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (MedicalFieldContract field : orderedColumns.keyFields()) {
            lines.add(new ColumnLine(
                    field.dorisColumn(),
                    field.dorisType(),
                    true,
                    escapeComment(field.name())));
        }
        if (keySet.contains(ETL_JOB_ID_COL) && enabledEtlFields.containsKey(ETL_JOB_ID_COL)) {
            lines.add(new ColumnLine(ETL_JOB_ID_COL, enabledEtlFields.get(ETL_JOB_ID_COL), true, "ETL system field"));
        }
        for (MedicalFieldContract field : orderedColumns.nonKeyFields()) {
            lines.add(new ColumnLine(
                    field.dorisColumn(),
                    field.dorisType(),
                    field.notNull(),
                    escapeComment(field.name())));
        }
        for (Map.Entry<String, String> entry : enabledEtlFields.entrySet()) {
            if (ETL_JOB_ID_COL.equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            lines.add(new ColumnLine(entry.getKey(), entry.getValue(), false, "ETL system field"));
        }

        for (int i = 0; i < lines.size(); i++) {
            ColumnLine line = lines.get(i);
            sb.append("  `").append(IdentifierSanitizer.requireValid(line.name(), "columnName")).append("` ")
                    .append(line.type()).append(line.notNull() ? " NOT NULL" : " NULL")
                    .append(" COMMENT '").append(line.comment()).append("'");
            if (i < lines.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(") ENGINE=OLAP\n");
        sb.append(hasPrimaryKey ? "UNIQUE KEY(" : "DUPLICATE KEY(")
                .append(keyColumns.stream()
                        .map(column -> "`" + IdentifierSanitizer.requireValid(column, "keyColumn") + "`")
                        .collect(Collectors.joining(",")))
                .append(")\n");
        sb.append("COMMENT '").append(escapeComment(contract.datasetName())).append("'\n");
        if (hasPrimaryKey) {
            sb.append("DISTRIBUTED BY HASH(`")
                    .append(IdentifierSanitizer.requireValid(bucketColumn, "bucketColumn"))
                    .append("`) BUCKETS ").append(DEFAULT_BUCKET_COUNT).append('\n');
        } else {
            sb.append("DISTRIBUTED BY RANDOM BUCKETS ").append(DEFAULT_BUCKET_COUNT).append('\n');
        }
        sb.append("PROPERTIES (\n");
        sb.append("  \"replication_num\" = \"1\"");
        if (hasPrimaryKey) {
            sb.append(",\n  \"enable_unique_key_merge_on_write\" = \"true\"");
        }
        if (hasPrimaryKey && contract.incrementalField() != null && !contract.incrementalField().isBlank()) {
            sb.append(",\n  \"function_column.sequence_col\" = \"")
                    .append(contract.incrementalField().toLowerCase(Locale.ROOT))
                    .append("\"");
        }
        sb.append("\n)");
        return sb.toString();
    }

    private OrderedMedicalColumns orderedColumns(MedicalDatasetContract contract) {
        Set<String> pk = contract.primaryKeys().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<MedicalFieldContract> keyFields = contract.fields().stream()
                .filter(field -> pk.contains(field.code().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparingInt(field -> field.order() == null ? 0 : field.order()))
                .toList();
        List<MedicalFieldContract> nonKeyFields = contract.fields().stream()
                .filter(field -> !pk.contains(field.code().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparingInt(field -> field.order() == null ? 0 : field.order()))
                .toList();
        return new OrderedMedicalColumns(keyFields, nonKeyFields);
    }

    private List<String> keyColumns(MedicalDatasetContract contract, Map<String, String> etlFields) {
        List<String> keys = (contract.primaryKeys() == null ? List.<String>of() : contract.primaryKeys()).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(ArrayList::new));
        if (etlFields.keySet().stream().anyMatch(ETL_JOB_ID_COL::equalsIgnoreCase)) {
            keys.add(ETL_JOB_ID_COL);
        }
        return keys;
    }

    private void requireSupportedKeyTypes(
            List<MedicalFieldContract> keyFields,
            Map<String, String> etlFields) {
        for (MedicalFieldContract field : keyFields) {
            if ("STRING".equalsIgnoreCase(field.dorisType())) {
                throw new IllegalArgumentException("医共体 Doris 主键不支持 STRING: "
                        + field.code() + " (" + field.format() + ")");
            }
        }
        etlFields.entrySet().stream()
                .filter(entry -> ETL_JOB_ID_COL.equalsIgnoreCase(entry.getKey()))
                .filter(entry -> "STRING".equalsIgnoreCase(entry.getValue()))
                .findFirst()
                .ifPresent(entry -> {
                    throw new IllegalArgumentException("医共体 Doris 主键不支持 STRING: " + entry.getKey());
                });
    }

    private String selectBucketColumn(List<String> primaryKeys) {
        for (String pk : primaryKeys) {
            if (PREFERRED_BUCKET_PATTERN.matcher(pk).matches()) {
                return pk.toLowerCase(Locale.ROOT);
            }
        }
        for (String pk : primaryKeys) {
            String upper = pk.toUpperCase(Locale.ROOT);
            if ("YILIAOJGDM".equals(upper) || "YUANQUID".equals(upper) || "YEWUJGDM".equals(upper)) {
                continue;
            }
            if (SECONDARY_BUCKET_PATTERN.matcher(pk).matches()) {
                return pk.toLowerCase(Locale.ROOT);
            }
        }
        return primaryKeys.get(primaryKeys.size() - 1).toLowerCase(Locale.ROOT);
    }

    private static String escapeComment(String value) {
        return value == null ? "" : value.replace("'", "\\'");
    }

    private record ColumnLine(String name, String type, boolean notNull, String comment) {
    }

    private record OrderedMedicalColumns(
            List<MedicalFieldContract> keyFields,
            List<MedicalFieldContract> nonKeyFields) {
    }
}
