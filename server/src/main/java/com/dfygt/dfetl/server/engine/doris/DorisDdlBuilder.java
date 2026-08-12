package com.dfygt.dfetl.server.engine.doris;

import com.dfygt.dfetl.server.common.IdentifierSanitizer;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.service.EtlSystemFieldsService;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 根据源端字段元信息 + 任务配置，生成 Doris 目标表的 CREATE TABLE DDL。
 *
 * <p>设计原则：
 * <ul>
 *   <li>类型映射覆盖 MySQL/PG/Oracle/SQL Server 常用类型，未识别 fallback 到 STRING；</li>
 *   <li>UNIQUE_KEY: keys = upsertKeys > 源主键，并在启用多任务范围字段时追加 _etl_job_id；</li>
 *   <li>DUPLICATE_KEY: keys = splitPk > incrementalField > 第一列；</li>
 *   <li>分桶：DISTRIBUTED BY HASH(key) BUCKETS 10；</li>
 *   <li>Doris 要求 KEY 列必须排在最前面 — 自动调整列顺序；</li>
 *   <li>所有列默认 NULL（含 KEY 列）以容错源端 schema 漂移。</li>
 * </ul>
 */
@Slf4j
@Component
public class DorisDdlBuilder {

    static final String ETL_JOB_ID_COL = "_etl_job_id";

    /** ETL 系统字段配置（可选，未配置时不追加） */
    @Autowired(required = false)
    private EtlSystemFieldsService etlSystemFieldsService;

    @Autowired(required = false)
    private DorisTypeMappingPolicy typeMappingPolicy = new DorisTypeMappingPolicy();

    @Autowired(required = false)
    private DorisTypeMappingRuleService typeMappingRuleService;

    @Autowired(required = false)
    private DorisAutoCreateTablePolicyService autoCreateTablePolicyService;

    /**
     * 构建 CREATE TABLE IF NOT EXISTS DDL。
     *
     * @param task    同步任务（提供 dorisTableModel/upsertKeys/splitPk/incrementalField）
     * @param tgt     目标数据源（提供 dbName）
     * @param table   目标表名
     * @param columns 源端字段列表
     * @return DDL 文本
     */
    public String buildCreateTable(SyncTask task, TargetDataSource tgt, String table, List<ColumnInfo> columns) {
        return buildCreateTable(task, tgt, table, columns, null);
    }

    public String buildCreateTable(
            SyncTask task,
            TargetDataSource tgt,
            String table,
            List<ColumnInfo> columns,
            String sourceDialect) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("无法生成 DDL：源端字段列表为空");
        }
        // 大小写折叠冲突检测：Doris 列名强制小写，源端若有 Name/NAME 这类仅大小写不同的列，
        // 折叠后会生成重复列名导致 CREATE TABLE 失败或列错位。执行期 fail-fast，
        // 与 DorisSchemaPreviewService / SeaTunnelConfBuilder 口径一致。
        assertNoCaseFoldingConflict(columns, table);
        String model = task.getDorisTableModel() != null ? task.getDorisTableModel() : defaultTableModel(task);
        // AGGREGATE_KEY 暂不支持自动建表（需要明确每列的聚合方式）
        if ("AGGREGATE_KEY".equalsIgnoreCase(model)) {
            throw new IllegalArgumentException("AGGREGATE_KEY 表不支持自动建表，请在 Doris 中手动创建");
        }

        // ETL 系统字段：UNIQUE KEY 表中 _etl_job_id 也参与 KEY，用于多机构共表隔离。
        Map<String, String> etlFields = etlSystemFieldsService != null
                ? etlSystemFieldsService.enabledFields()
                : Map.of();

        List<String> keyCols = includeTenantScopeKey(model, inferKeyColumns(model, task, columns), etlFields);
        if (keyCols.isEmpty()) {
            throw new IllegalArgumentException("无法推断 Doris KEY 列：请配置 upsertKeys / splitPk 或为源表添加主键");
        }
        boolean uniqueKey = "UNIQUE_KEY".equalsIgnoreCase(model);
        boolean requiresMow = Boolean.TRUE.equals(task.getEnableDorisMerge())
                || Boolean.TRUE.equals(task.getPartialColumns());
        if (requiresMow && !uniqueKey) {
            throw new IllegalArgumentException("Doris MERGE/partial update 仅支持 UNIQUE_KEY + merge-on-write 表");
        }
        String sequenceCol = null;
        if (task.getSequenceCol() != null && !task.getSequenceCol().isBlank()) {
            if (!uniqueKey) {
                throw new IllegalArgumentException("Doris sequence_col 仅支持 UNIQUE_KEY 表");
            }
            sequenceCol = resolveSourceColumnLower(columns, task.getSequenceCol(), "sequence_col");
        }

        List<ColumnInfo> sourceKeyCols = sourceColumnsInKeyOrder(columns, keyCols);
        List<String> etlKeyCols = etlFieldsInKeyOrder(etlFields, keyCols);
        List<ColumnInfo> sourceNonKeyCols = sourceNonKeyColumns(columns, keyCols);
        List<String> etlNonKeyCols = etlNonKeyFields(etlFields, keyCols);

        // Doris 列名/表名统一小写，避免大小写源端（如 Oracle）与 SeaTunnel JSON key 不匹配
        // 注入防御：dbName / tgtTable 拼到反引号 DDL 前必须经字符集白名单（与 SeaTunnel 源端 quoteIdentifier 对齐）
        String dbName = IdentifierSanitizer.requireValid(tgt.getDbName(), "tgt.dbName");
        String quotedTable = IdentifierSanitizer.requireValid(table.toLowerCase(Locale.ROOT), "tgtTable");

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS `").append(dbName).append("`.`").append(quotedTable).append("` (\n");
        int totalCols = sourceKeyCols.size() + etlKeyCols.size() + sourceNonKeyCols.size() + etlNonKeyCols.size();
        int idx = 0;
        for (ColumnInfo c : sourceKeyCols) {
            idx = appendSourceColumn(sb, c, true, sourceDialect, idx, totalCols);
        }
        for (String field : etlKeyCols) {
            idx = appendEtlColumn(sb, field, etlFields.get(field), true, idx, totalCols);
        }
        for (ColumnInfo c : sourceNonKeyCols) {
            idx = appendSourceColumn(sb, c, false, sourceDialect, idx, totalCols);
        }
        for (String field : etlNonKeyCols) {
            idx = appendEtlColumn(sb, field, etlFields.get(field), false, idx, totalCols);
        }
        sb.append(") ENGINE=OLAP\n");

        // KEY 子句
        String keyClause = "UNIQUE_KEY".equalsIgnoreCase(model) ? "UNIQUE KEY" : "DUPLICATE KEY";
        sb.append(keyClause).append('(');
        for (int i = 0; i < keyCols.size(); i++) {
            if (i > 0) sb.append(',');
            String keyColName = IdentifierSanitizer.requireValid(
                    keyCols.get(i).toLowerCase(Locale.ROOT), "keyColumn");
            sb.append('`').append(keyColName).append('`');
        }
        sb.append(")\n");

        DorisAutoCreateTablePolicyService.TableLayoutPlan layoutPlan = autoCreateTablePolicyService != null
                ? autoCreateTablePolicyService.plan(task, columns)
                : new DorisAutoCreateTablePolicyService.TableLayoutPlan(
                        10,
                        new DorisAutoCreateTablePolicyService.PartitionPlan(false, "", "", ""));

        boolean partitionEmitted = false;
        if (layoutPlan.partition() != null && layoutPlan.partition().enabled()) {
            // Doris 3.x: UNIQUE KEY + MoW=false 时，分区列不能是非 KEY 列
            // 检查分区字段是否在 KEY 列中，不在则跳过分区
            String partField = layoutPlan.partition().field();
            boolean partFieldIsKey = partField != null && keyCols.stream()
                    .anyMatch(k -> k.equalsIgnoreCase(partField));
            if (uniqueKey && !partFieldIsKey) {
                // 跳过分区：UNIQUE KEY 表（无论是否 MoW）的分区列必须是 KEY 列。
                // 医疗规范表均为 UNIQUE KEY + 业务主键，分区字段（如 xiugaisj）不在主键中，
                // 因此分区配置对医疗表实际不会生效——此处显式 WARN，避免管理员误以为已分区（静默跳过）。
                log.warn("DorisDdlBuilder: 表 {} 为 UNIQUE KEY，分区字段 '{}' 不在主键列 {} 中，"
                                + "按 Doris 约束跳过分区（医疗 UNIQUE KEY 表用纯分桶，分区配置不生效）",
                        table, partField, keyCols);
            } else {
                sb.append(layoutPlan.partition().clause());
                partitionEmitted = true;
            }
        }

        // 分桶（沿用第一个 KEY 列做 HASH；bucket 数来自 Doris 自动建表策略，默认保持 10）
        String bucketKey = IdentifierSanitizer.requireValid(
                keyCols.get(0).toLowerCase(Locale.ROOT), "bucketKey");
        sb.append("DISTRIBUTED BY HASH(`").append(bucketKey)
                .append("`) BUCKETS ").append(layoutPlan.bucketCount()).append('\n');

        // Properties
        sb.append("PROPERTIES (\n");
        sb.append("  \"replication_num\" = \"1\"");
        if (uniqueKey) {
            // Doris 2.1+ 默认 UNIQUE KEY 表启用 MoW，需要显式控制
            if (requiresMow) {
                sb.append(",\n  \"enable_unique_key_merge_on_write\" = \"true\"");
            } else {
                sb.append(",\n  \"enable_unique_key_merge_on_write\" = \"false\"");
            }
        }
        if (sequenceCol != null) {
            sb.append(",\n  \"function_column.sequence_col\" = \"").append(sequenceCol).append("\"");
        }
        // 动态分区属性
        if (partitionEmitted
                && layoutPlan.partition().dynamicProperties() != null
                && !layoutPlan.partition().dynamicProperties().isEmpty()) {
            for (Map.Entry<String, String> entry : layoutPlan.partition().dynamicProperties().entrySet()) {
                sb.append(",\n  \"").append(entry.getKey()).append("\" = \"").append(entry.getValue()).append("\"");
            }
        }
        sb.append("\n)");
        return sb.toString();
    }

    // ── KEY 列推断 ───────────────────────────────────────────────────────────

    List<String> inferKeyColumns(String model, SyncTask task, List<ColumnInfo> columns) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if ("UNIQUE_KEY".equalsIgnoreCase(model)) {
            // 1) upsertKeys（List<String>）
            if (task.getUpsertKeys() != null && !task.getUpsertKeys().isEmpty()) {
                for (String k : task.getUpsertKeys()) {
                    String key = k == null ? "" : k.trim();
                    if (!key.isEmpty() && containsColumn(columns, key)) keys.add(key);
                }
            }
            // 2) 源表主键
            if (keys.isEmpty()) {
                for (ColumnInfo c : columns) if (c.primaryKey()) keys.add(c.columnName());
            }
        } else { // DUPLICATE_KEY
            if (task.getSplitPk() != null && !task.getSplitPk().isBlank()
                    && containsColumn(columns, task.getSplitPk())) {
                keys.add(task.getSplitPk());
            } else if (task.getIncrementalField() != null && !task.getIncrementalField().isBlank()
                    && containsColumn(columns, task.getIncrementalField())) {
                keys.add(task.getIncrementalField());
            } else if (containsColumn(columns, "xiugaisj")) {
                keys.add("xiugaisj");
            } else {
                keys.add(columns.get(0).columnName());
            }
        }
        return new ArrayList<>(keys);
    }

    static List<String> includeTenantScopeKey(String model, List<String> keys, Map<String, String> etlFields) {
        LinkedHashSet<String> scoped = new LinkedHashSet<>();
        if (keys != null) {
            scoped.addAll(keys);
        }
        if ("UNIQUE_KEY".equalsIgnoreCase(model)
                && etlFields != null
                && etlFields.keySet().stream().anyMatch(ETL_JOB_ID_COL::equalsIgnoreCase)) {
            boolean alreadyPresent = scoped.stream().anyMatch(ETL_JOB_ID_COL::equalsIgnoreCase);
            if (!alreadyPresent) {
                scoped.add(ETL_JOB_ID_COL);
            }
        }
        return new ArrayList<>(scoped);
    }

    private String defaultTableModel(SyncTask task) {
        if (task == null) {
            return "DUPLICATE_KEY";
        }
        boolean upsertLike = "UPSERT".equalsIgnoreCase(task.getSyncMode())
                || Boolean.TRUE.equals(task.getEnableDorisMerge())
                || Boolean.TRUE.equals(task.getPartialColumns())
                || (task.getUpsertKeys() != null && task.getUpsertKeys().stream()
                .anyMatch(key -> key != null && !key.isBlank()));
        return upsertLike ? "UNIQUE_KEY" : "DUPLICATE_KEY";
    }

    private boolean containsColumn(List<ColumnInfo> cols, String name) {
        for (ColumnInfo c : cols) {
            if (c.columnName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private String resolveSourceColumnLower(List<ColumnInfo> cols, String name, String label) {
        String expected = name == null ? "" : name.trim();
        if (expected.isEmpty()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        for (ColumnInfo c : cols) {
            if (c.columnName().equalsIgnoreCase(expected)) {
                return c.columnName().toLowerCase(Locale.ROOT);
            }
        }
        throw new IllegalArgumentException(label + " 字段不存在于源字段列表: " + expected);
    }

    /**
     * 大小写折叠冲突检测：列名小写折叠后若出现重复（如 Name/NAME → name），抛 IllegalArgumentException。
     * Doris 列名强制小写，冲突会导致 CREATE TABLE 重复列名或列错位。
     */
    private void assertNoCaseFoldingConflict(List<ColumnInfo> columns, String table) {
        java.util.Map<String, java.util.List<String>> lowerToOriginals = new java.util.LinkedHashMap<>();
        for (ColumnInfo c : columns) {
            String name = c.columnName();
            if (name == null) continue;
            lowerToOriginals.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new java.util.ArrayList<>())
                    .add(name);
        }
        java.util.List<String> conflicts = lowerToOriginals.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getValue().stream().collect(java.util.stream.Collectors.joining(", ", "[", "]")) + " → " + e.getKey())
                .toList();
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException(
                    "[" + table + "] 源端字段存在大小写冲突，Doris 折叠后将产生重复列名: "
                            + String.join("; ", conflicts));
        }
    }

    private List<ColumnInfo> sourceColumnsInKeyOrder(List<ColumnInfo> cols, List<String> keys) {
        List<ColumnInfo> out = new ArrayList<>(cols.size());
        for (String k : keys) {
            for (ColumnInfo c : cols) {
                if (c.columnName().equalsIgnoreCase(k) && !out.contains(c)) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    private List<ColumnInfo> sourceNonKeyColumns(List<ColumnInfo> cols, List<String> keys) {
        List<ColumnInfo> out = new ArrayList<>(cols.size());
        for (ColumnInfo c : cols) {
            boolean key = keys.stream().anyMatch(k -> k.equalsIgnoreCase(c.columnName()));
            if (!key) out.add(c);
        }
        return out;
    }

    private List<String> etlFieldsInKeyOrder(Map<String, String> etlFields, List<String> keys) {
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            for (String field : etlFields.keySet()) {
                if (field.equalsIgnoreCase(key) && !out.contains(field)) {
                    out.add(field);
                    break;
                }
            }
        }
        return out;
    }

    private List<String> etlNonKeyFields(Map<String, String> etlFields, List<String> keys) {
        List<String> out = new ArrayList<>();
        for (String field : etlFields.keySet()) {
            boolean key = keys.stream().anyMatch(k -> k.equalsIgnoreCase(field));
            if (!key) {
                out.add(field);
            }
        }
        return out;
    }

    private int appendSourceColumn(StringBuilder sb, ColumnInfo c, boolean keyCol, String sourceDialect,
                                   int idx, int totalCols) {
        String colName = IdentifierSanitizer.requireValid(
                c.columnName().toLowerCase(Locale.ROOT), "sourceColumn");
        String dorisType = mapType(c, sourceDialect);
        sb.append("  `").append(colName).append("` ").append(dorisType);
        appendNullability(sb, dorisType, keyCol);
        if (++idx < totalCols) sb.append(',');
        sb.append('\n');
        return idx;
    }

    private int appendEtlColumn(StringBuilder sb, String field, String type, boolean keyCol, int idx, int totalCols) {
        String etlCol = IdentifierSanitizer.requireValid(field, "etlSystemField");
        String dorisType = type == null || type.isBlank() ? "VARCHAR(100)" : type;
        sb.append("  `").append(etlCol).append("` ").append(dorisType);
        appendNullability(sb, dorisType, keyCol);
        sb.append(" COMMENT 'ETL system field'");
        if (++idx < totalCols) sb.append(',');
        sb.append('\n');
        return idx;
    }

    private void appendNullability(StringBuilder sb, String dorisType, boolean keyCol) {
        if (!keyCol) {
            sb.append(" NULL");
            return;
        }
        String upper = dorisType.toUpperCase(Locale.ROOT);
        if (upper.startsWith("VARCHAR") || upper.equals("STRING") || upper.startsWith("CHAR")) {
            sb.append(" NOT NULL DEFAULT ''");
        } else if (upper.startsWith("INT") || upper.startsWith("BIGINT")
                || upper.startsWith("SMALLINT") || upper.startsWith("TINYINT")) {
            sb.append(" NOT NULL DEFAULT '0'");
        } else {
            sb.append(" NOT NULL");
        }
    }

    // ── 类型映射 ─────────────────────────────────────────────────────────────

    String mapType(ColumnInfo c) {
        return mapType(c, null);
    }

    String mapType(ColumnInfo c, String sourceDialect) {
        DorisTypeMappingPolicy.SourceTypeDescriptor descriptor =
                DorisTypeMappingPolicy.SourceTypeDescriptor.fromColumn(sourceDialect, c, true);
        DorisTypeMappingPolicy.MappingResult result = typeMappingRuleService != null
                ? typeMappingRuleService.recommend(descriptor)
                : typeMappingPolicy.recommend(descriptor);
        if (result.compatibilityLevel() == DorisTypeMappingPolicy.CompatibilityLevel.WARN) {
            log.warn("DorisDdlBuilder: source column {} type {} mapped to {} with warning: {}",
                    c.columnName(), c.dataType(), result.recommendedDorisType(), result.reason());
        }
        return result.recommendedDorisType();
    }
}
