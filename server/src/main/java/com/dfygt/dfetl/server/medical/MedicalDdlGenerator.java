package com.dfygt.dfetl.server.medical;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 医疗规范 DDL 生成器。
 *
 * <p>基于规范数据集定义生成 Doris UNIQUE KEY MOW 或 DUPLICATE KEY 表的 CREATE TABLE DDL。</p>
 */
@Service
public class MedicalDdlGenerator {

    private static final Logger log = LoggerFactory.getLogger(MedicalDdlGenerator.class);

    /** 优先分桶列：BINGRENID / GERENDABS 等业务标识列 */
    private static final Pattern PREFERRED_BUCKET_PATTERN = Pattern.compile(
            "(?i)(BINGRENID|GERENDABS|JIUZHENID|JIUZHENLSH|MENZHENJZLSH|GUAHAOLSH)");

    /** 次选分桶列：以 ID / LSH / BSH / BH 结尾的列（排除 YILIAOJGDM / YUANQUID） */
    private static final Pattern SECONDARY_BUCKET_PATTERN = Pattern.compile(
            "(?i).+(ID|LSH|BSH|BH)$");

    /** 当前阶段固定分桶数，后续接 DorisAutoCreateTablePolicyService */
    private static final int DEFAULT_BUCKET_COUNT = 10;

    private final SdvTypeMappingPolicy sdvTypeMapper;
    private final com.dfygt.dfetl.server.service.EtlSystemFieldsService etlSystemFieldsService;

    public MedicalDdlGenerator(SdvTypeMappingPolicy sdvTypeMapper,
                               com.dfygt.dfetl.server.service.EtlSystemFieldsService etlSystemFieldsService) {
        this.sdvTypeMapper = sdvTypeMapper;
        this.etlSystemFieldsService = etlSystemFieldsService;
    }

    /**
     * 为单个数据集生成 CREATE TABLE DDL。
     *
     * @param dataset 数据集定义
     * @return DDL 结果；errorMessage 非 null 表示无法生成
     */
    public DdlResult generateDdl(DatasetDefinition dataset) {
        String tableName = dataset.shujujdm().toLowerCase(Locale.ROOT);

        List<FieldDefinition> pkFields = dataset.fields().stream()
                .filter(FieldDefinition::primaryKey)
                .sorted(Comparator.comparingInt(FieldDefinition::shunxuhao))
                .toList();
        boolean hasPrimaryKey = !pkFields.isEmpty();
        boolean hasXiugaisj = dataset.fields().stream()
                .anyMatch(f -> "XIUGAISJ".equalsIgnoreCase(f.ziduandm()));
        Map<String, String> etlFields = etlSystemFieldsService != null
                ? etlSystemFieldsService.enabledFields()
                : java.util.Map.of();
        String etlJobIdType = etlFields.entrySet().stream()
                .filter(entry -> "_etl_job_id".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (!hasPrimaryKey && etlJobIdType == null) {
            String msg = String.format("数据集 %s 无主键且平台未启用 _etl_job_id，无法生成 DUPLICATE KEY", dataset.shujujdm());
            log.error("[MedicalRegistry] {}", msg);
            return new DdlResult(tableName, null, msg);
        }

        // ── 列排序：PK 列在前按 shunxuhao 升序，非 PK 列在后按 shunxuhao 升序 ──
        List<FieldDefinition> nonPkFields = dataset.fields().stream()
                .filter(f -> !f.primaryKey())
                .sorted(Comparator.comparingInt(FieldDefinition::shunxuhao))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE `").append(tableName).append("` (\n");
        List<String> columnLines = new java.util.ArrayList<>();
        for (FieldDefinition field : pkFields) {
            String colName = field.ziduandm().toLowerCase(Locale.ROOT);
            String dorisType = sdvTypeMapper.mapToDorisType(
                    field.sdvType(),
                    field.biaoshigs(),
                    field.primaryKey());
            String comment = escapeComment(field.ziduanmc());
            columnLines.add("  `" + colName + "` " + dorisType + " NOT NULL COMMENT '" + comment + "'");
        }
        if (etlJobIdType != null) {
            columnLines.add("  `_etl_job_id` " + etlJobIdType + " NOT NULL COMMENT 'ETL系统字段'");
        }
        for (FieldDefinition field : nonPkFields) {
            String colName = field.ziduandm().toLowerCase(Locale.ROOT);
            String dorisType = sdvTypeMapper.mapToDorisType(
                    field.sdvType(), field.biaoshigs(), false);
            columnLines.add("  `" + colName + "` " + dorisType + " NULL COMMENT '"
                    + escapeComment(field.ziduanmc()) + "'");
        }
        for (Map.Entry<String, String> entry : etlFields.entrySet()) {
            if ("_etl_job_id".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            columnLines.add("  `" + entry.getKey() + "` " + entry.getValue()
                    + " NULL COMMENT 'ETL系统字段'");
        }
        for (int i = 0; i < columnLines.size(); i++) {
            sb.append(columnLines.get(i));
            if (i < columnLines.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(")\n");
        if (hasPrimaryKey) {
            List<String> keyColumns = new java.util.ArrayList<>(pkFields.stream()
                    .map(f -> "`" + f.ziduandm().toLowerCase(Locale.ROOT) + "`")
                    .toList());
            if (etlJobIdType != null) {
                keyColumns.add("`_etl_job_id`");
            }
            sb.append("UNIQUE KEY(").append(String.join(", ", keyColumns)).append(")\n");
        } else {
            sb.append("DUPLICATE KEY(`_etl_job_id`)\n");
        }

        // 表 COMMENT
        sb.append("COMMENT '").append(escapeComment(dataset.shujujmc())).append("'\n");

        if (hasPrimaryKey) {
            String bucketCol = selectBucketColumn(pkFields);
            sb.append("DISTRIBUTED BY HASH(`").append(bucketCol).append("`) BUCKETS ")
                    .append(DEFAULT_BUCKET_COUNT).append("\n");
        } else {
            sb.append("DISTRIBUTED BY RANDOM BUCKETS ").append(DEFAULT_BUCKET_COUNT).append("\n");
        }

        // PROPERTIES
        sb.append("PROPERTIES (\n");
        sb.append("  \"replication_allocation\" = \"tag.location.default: 1\"");
        if (hasPrimaryKey) {
            sb.append(",\n  \"enable_unique_key_merge_on_write\" = \"true\"");
            if (hasXiugaisj) {
                sb.append(",\n  \"function_column.sequence_col\" = \"xiugaisj\"");
            }
        }
        sb.append('\n');
        sb.append(");\n");

        return new DdlResult(tableName, sb.toString(), null);
    }

    /**
     * 从 PK 列中选择最优分桶列。
     *
     * <p>优先级：列名包含 BINGRENID/JIUZHENID/ID（忽略大小写）→ shunxuhao 最大的 PK 列</p>
     *
     * @param pkFields PK 字段列表（已按 shunxuhao 排序）
     * @return 分桶列名（小写）
     */
    String selectBucketColumn(List<FieldDefinition> pkFields) {
        // 优先选业务标识列（BINGRENID / GERENDABS 等）
        for (FieldDefinition field : pkFields) {
            if (PREFERRED_BUCKET_PATTERN.matcher(field.ziduandm()).matches()) {
                return field.ziduandm().toLowerCase(Locale.ROOT);
            }
        }

        // 次选以 ID/LSH/BSH/BH 结尾的列（排除 YILIAOJGDM / YUANQUID）
        for (FieldDefinition field : pkFields) {
            String name = field.ziduandm().toUpperCase(Locale.ROOT);
            if ("YILIAOJGDM".equals(name) || "YUANQUID".equals(name) || "YEWUJGDM".equals(name)) {
                continue;
            }
            if (SECONDARY_BUCKET_PATTERN.matcher(field.ziduandm()).matches()) {
                return field.ziduandm().toLowerCase(Locale.ROOT);
            }
        }

        // 无标识类列，选 shunxuhao 最大的 PK 列
        return pkFields.stream()
                .max(Comparator.comparingInt(FieldDefinition::shunxuhao))
                .map(f -> f.ziduandm().toLowerCase(Locale.ROOT))
                .orElse(pkFields.getFirst().ziduandm().toLowerCase(Locale.ROOT));
    }

    /**
     * 转义 COMMENT 中的单引号。
     */
    private String escapeComment(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "\\'");
    }
}
