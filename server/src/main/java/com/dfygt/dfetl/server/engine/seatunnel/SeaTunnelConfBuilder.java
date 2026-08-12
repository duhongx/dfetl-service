package com.dfygt.dfetl.server.engine.seatunnel;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.TargetTableMapParser;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.medical.MedicalDatasetContract;
import com.dfygt.dfetl.server.medical.MedicalContractSnapshotCodec;
import com.dfygt.dfetl.server.medical.MedicalDatasetContractService;
import com.dfygt.dfetl.server.medical.source.MedicalSourceSelectBuilder;
import com.dfygt.dfetl.server.medical.source.MedicalSourceSelectPlan;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapter;
import com.dfygt.dfetl.server.medical.source.SourceDialectAdapterResolver;
import com.dfygt.dfetl.server.repository.SourceDataSourceRepository;
import com.dfygt.dfetl.server.repository.TargetDataSourceRepository;
import com.dfygt.dfetl.server.service.CustomSqlValidator;
import com.dfygt.dfetl.server.service.CustomSqlQueryBuilder;
import com.dfygt.dfetl.server.service.DialectQuoteHelper;
import com.dfygt.dfetl.server.service.EtlSystemFieldsService;
import com.dfygt.dfetl.server.service.SourceDataSourceService;
import com.dfygt.dfetl.server.service.SourceDataSourceService.ColumnInfo;
import com.dfygt.dfetl.server.service.WatermarkService;
import com.dfygt.dfetl.server.service.WhereClauseBuilder;
import com.dfygt.dfetl.server.service.sql.SqlLiteralEncoder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 根据 {@link SyncTask} 构造一份 SeaTunnel REST submit-job 配置（spec 015b）。
 *
 * <p>当前实现：
 * <ul>
 *   <li>source = Jdbc（覆盖 MySQL/PostgreSQL/Oracle/SQL Server，复用 server 的 jdbc URL 拼接）</li>
 *   <li>sink = Doris，UPSERT 注入 merge_type / sequence_col / partial_columns</li>
 *   <li>syncMode：TRUNCATE → DROP_DATA；APPEND → APPEND_DATA；UPSERT → APPEND_DATA + merge 配置</li>
 *   <li>where 子句复用 {@link WhereClauseBuilder}，white-list 已校验</li>
 *   <li>INCREMENTAL TIME_FIELD / ID_RANGE 均在 REST jobConfig 中注入</li>
 * </ul>
 *
 * <p>未覆盖：
 * <ul>
 *   <li>checkpoint 续跑（依赖 SeaTunnel checkpoint）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeaTunnelConfBuilder {

    private static final int DEFAULT_FETCH_SIZE = 50000;

    private final SourceDataSourceRepository sourceRepo;
    private final TargetDataSourceRepository targetRepo;
    private final AesUtil aesUtil;
    private final WhereClauseBuilder whereClauseBuilder;
    private final ObjectMapper objectMapper;
    private final SourceDataSourceService sourceDataSourceService;
    private final EtlSystemFieldsService etlSystemFieldsService;
    private final DialectQuoteHelper dialectQuoteHelper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.dfygt.dfetl.server.repository.SystemSettingRepository systemSettingRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MedicalDatasetContractService medicalContractService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MedicalSourceSelectBuilder medicalSourceSelectBuilder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SourceDialectAdapterResolver sourceDialectAdapterResolver;

    /**
     * 构造 SeaTunnel REST submit-job 所需的 JSON 作业配置（Map 结构）。
     *
     * @return env / source / transform / sink 四段的 Map，可直接被 ObjectMapper 序列化为 JSON
     */
    public Map<String, Object> buildJobMap(SyncTask task, WatermarkService.WindowContext window, Long execId)
            throws IOException {
        SourceDataSource src = sourceRepo.findById(task.getSourceDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("SourceDataSource not found"));
        TargetDataSource tgt = targetRepo.findById(task.getTargetDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("TargetDataSource not found"));

        boolean customSql = isCustomSql(task);
        if (!customSql && (task.getViewNames() == null || task.getViewNames().isEmpty())) {
            throw new IllegalArgumentException(
                    "SeaTunnel executor requires at least one source table; got none");
        }
        // 上层 DfetlExecutorService 已拆分多表为单表副本，此处取第一个（也应是唯一一个）
        String srcTable = customSql ? customSqlName(task) : task.getViewNames().get(0);
        Map<String, String> targetMap = parseJsonMap(task.getTargetTableMap());
        String tgtTable = targetMap.getOrDefault(srcTable, srcTable).toLowerCase(Locale.ROOT);

        String srcPwd   = aesUtil.decrypt(src.getPasswordEnc());
        String tgtPwd   = aesUtil.decrypt(tgt.getPasswordEnc());
        String srcUrl   = buildSourceJdbcUrl(src);
        String drvCls   = jdbcDriverClass(src.getType());
        String sourceSchema = resolveSourceSchema(task, src);
        List<ColumnInfo> sourceColumns = customSql
                ? resolveCustomSqlColumns(src, task)
                : resolveTableColumns(src, sourceSchema, srcTable);
        String where    = buildWhereClause(task, src.getType(), window);
        String query    = customSql ? buildCustomSqlQuery(src, task, where, sourceColumns)
                : buildTableQuery(task, src, sourceSchema, srcTable, where, sourceColumns);
        int parallelism = task.getParallelism() == null ? 4 : task.getParallelism();
        int batchSize   = resolveFetchSize(task);

        // env
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("parallelism", parallelism);
        env.put("job.mode", "BATCH");
        env.put("job.name", "sync_task_" + task.getId() + "_exec_" + execId);
        env.put("checkpoint.interval", 30000);
        // checkpoint.timeout 从全局配置读取（默认 1 小时）
        long checkpointTimeoutMs = getCheckpointTimeoutMs();
        env.put("checkpoint.timeout", checkpointTimeoutMs);

        // source
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("plugin_name", "Jdbc");
        source.put("url", srcUrl);
        source.put("driver", drvCls);
        source.put("user", src.getUsername());
        source.put("password", srcPwd);
        source.put("query", query);
        // 分片
        boolean idRange = false;
        boolean hasPartition = false;
        if (!customSql && window != null && window.windowEndId() != null && parallelism > 1
                && "INCREMENTAL".equalsIgnoreCase(task.getDataScope())
                && "ID_RANGE".equalsIgnoreCase(task.getIncrementMode())
                && task.getIncrementalField() != null && !task.getIncrementalField().isBlank()) {
            long lower = window.windowStartId() == null ? 0L : window.windowStartId();
            long upper = window.windowEndId();
            if (upper > lower) {
                source.put("partition_column", resolveDorisTargetField(
                        task.getIncrementalField(), sourceColumns, "partition_column"));
                source.put("partition_lower_bound", lower);
                source.put("partition_upper_bound", upper);
                source.put("partition_num", parallelism);
                idRange = true;
                hasPartition = true;
            }
        }
        if (!customSql && !idRange && task.getSplitPk() != null && !task.getSplitPk().isBlank()) {
            source.put("partition_column", resolveDorisTargetField(
                    task.getSplitPk(), sourceColumns, "partition_column"));
            source.put("partition_num", parallelism);
            hasPartition = true;
        }
        // 无分片时 env.parallelism 必须为 1，否则 SeaTunnel JDBC Source 会按全局并行度创建多个 reader，
        // 每个 reader 执行相同的 query 导致数据重复（source.parallelism=1 不足以覆盖 env 级别设置）
        if (!hasPartition && parallelism > 1) {
            env.put("parallelism", 1);
            source.put("parallelism", 1);
        }
        source.put("fetch_size", batchSize);
        source.put("result_table_name", "src_t");

        // sink
        String mode = task.getSyncMode() == null ? "APPEND" : task.getSyncMode().toUpperCase();
        Map<String, Object> sink = new LinkedHashMap<>();
        sink.put("plugin_name", "Doris");
        // fenodes 使用 FE HTTP 端口(8030)，SeaTunnel Doris connector 内部会跟随 307 重定向到 BE 8040
        sink.put("fenodes", tgt.getFeHost() + ":" + tgt.getHttpPort());
        // query-port 用于 JDBC 连接（执行 TRUNCATE、CREATE TABLE 等 DDL）
        sink.put("query-port", tgt.getFePort());
        sink.put("username", tgt.getUsername());
        sink.put("password", tgtPwd);
        sink.put("database", tgt.getDbName());
        sink.put("table", tgtTable);
        sink.put("sink.label-prefix", "dfetl_" + task.getId() + "_" + execId);
        // 不设 sink.buffer-size：使用默认 256KB，小数据量能触发 flush；大数据量依靠 checkpoint + 2PC
        sink.put("source_table_name", "src_t");

        // BATCH 模式必须启用 2PC：SeaTunnel 在 job 结束时会触发最后一个 checkpoint，在 commit 阶段 flush 所有 buffer 到 Doris。
        // 如果 2PC=false 且数据量 < buffer-size，数据会丢失（SeaTunnel 会误报 SUCCESS 但 Doris 无记录）
        Map<String, Object> dc = new LinkedHashMap<>();
        dc.put("format", "json");
        dc.put("read_json_by_line", "true");  // 必须：SeaTunnel 逐行发送 NDJSON，Doris 需要设置此项
        dc.put("strict_mode", "false");
        dc.put("max_filter_ratio", "0.01");
        if (medicalQueryOptions(task).datasetCode() != null) {
            dc.put("strict_mode", "true");
            dc.put("max_filter_ratio", "0");
        }

        if ("TRUNCATE".equals(mode)) {
            // 表结构已由服务端 DorisTableEnsurer 预建，告知 SeaTunnel 不要重复处理 schema（其默认模板要求源表有主键，Oracle 等场景会报 COMMON-24）
            sink.put("schema_save_mode", "IGNORE");
            sink.put("data_save_mode", "DROP_DATA");
            sink.put("sink.enable-2pc", true);
            sink.put("doris.config", dc);
        } else if ("UPSERT".equals(mode)) {
            sink.put("schema_save_mode", "IGNORE");
            sink.put("data_save_mode", "APPEND_DATA");
            sink.put("sink.enable-2pc", true);
            if (Boolean.TRUE.equals(task.getEnableDorisMerge())) {
                String sf = task.getSoftDeleteField();
                if (sf != null && !sf.isBlank()) {
                    String deleteField = resolveDorisTargetField(sf, sourceColumns, "delete field");
                    String dsv = task.getDeleteSignValue() == null ? "1" : task.getDeleteSignValue();
                    String dsvExpr = SqlLiteralEncoder.encode(dsv);
                    dc.put("merge_type", "MERGE");
                    dc.put("delete", deleteField + "=" + dsvExpr);
                }
            }
            if (task.getSequenceCol() != null && !task.getSequenceCol().isBlank()) {
                dc.put("function_column.sequence_col", resolveDorisTargetField(
                        task.getSequenceCol(), sourceColumns, "function_column.sequence_col"));
            }
            if (Boolean.TRUE.equals(task.getPartialColumns())) {
                dc.put("partial_columns", "true");
            }
            sink.put("doris.config", dc);
        } else if ("APPEND".equals(mode)) {
            sink.put("schema_save_mode", "IGNORE");
            sink.put("data_save_mode", "APPEND_DATA");
            sink.put("sink.enable-2pc", true);
            sink.put("doris.config", dc);
        } else {
            throw new IllegalArgumentException(
                    "SeaTunnelConfBuilder: unknown syncMode=" + mode + " for task " + task.getId());
        }

        // ETL 系统字段：注入 SQL Transform
        List<Map<String, Object>> transforms = new ArrayList<>();
        Map<String, String> etlFields = etlSystemFieldsService != null
                ? etlSystemFieldsService.enabledFields()
                : Map.of();
        if (!etlFields.isEmpty()) {
            String sql = buildEtlSelectSql(etlFields, "src_t", task, src, window, execId);
            Map<String, Object> tf = new LinkedHashMap<>();
            tf.put("plugin_name", "Sql");
            tf.put("source_table_name", "src_t");
            tf.put("result_table_name", "src_with_etl");
            tf.put("query", sql);
            transforms.add(tf);
            // 切换 sink 输入流
            sink.put("source_table_name", "src_with_etl");
        }

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("env", env);
        job.put("source", List.of(source));
        job.put("transform", transforms);
        job.put("sink", List.of(sink));
        return job;
    }

    /**
     * 构造注入 ETL 系统字段的 SELECT 语句（供 SeaTunnel SQL Transform 使用）。
     *
     * <p>字段含义见 {@link EtlSystemFieldsService}。所有字符串值经过单引号转义，
     * 数值/时间使用字面量或 NOW() 函数。
     */
    static String buildEtlSelectSql(Map<String, String> enabled,
                                    String srcAlias,
                                    SyncTask task,
                                    SourceDataSource src,
                                    WatermarkService.WindowContext window,
                                    Long execId) {
        StringBuilder sb = new StringBuilder("SELECT *");
        for (String field : enabled.keySet()) {
            sb.append(", ");
            switch (field) {
                case "_etl_batch_id"      -> sb.append(execId == null ? 0 : execId);
                case "_etl_job_id"        -> sb.append(task.getId() == null ? 0 : task.getId());
                case "_etl_job_version"   -> sb.append('\'').append(escapeSql(task.getVersion() == null ? "" : task.getVersion())).append('\'');
                case "_etl_sync_time"     -> {
                    String syncTime = java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(java.time.ZoneId.of("Asia/Shanghai"))
                            .format(java.time.Instant.now());
                    sb.append('\'').append(syncTime).append('\'');
                }
                case "_etl_source_system" -> sb.append('\'').append(escapeSql(resolveSourceCode(src))).append('\'');
                case "_etl_window_start"  -> sb.append('\'').append(escapeSql(formatWindow(window == null ? null : window.windowStart()))).append('\'');
                case "_etl_window_end"    -> sb.append('\'').append(escapeSql(formatWindow(window == null ? null : window.windowEnd()))).append('\'');
                default -> sb.append("NULL");
            }
            sb.append(" AS ").append(field);
        }
        sb.append(" FROM ").append(srcAlias);
        return sb.toString();
    }

    /** Window Instant → 字符串；null/全量 → "FULL" */
    private static String formatWindow(java.time.Instant t) {
        return t == null ? "FULL" : t.toString();
    }

    /**
     * spec 070：解析数据源稳定编码，作为 _etl_source_system 列值。
     * <p>
     * 优先返回 {@link SourceDataSource#getSourceCode()}（系统创建时生成、不可改）。
     * 存量数据源（迁移前未补码，sourceCode 为空）回退使用 {@code name} 并记 WARN，
     * 不阻断同步——血缘列稳定性受损但功能不中断，由迁移脚本（Task 8）渐进补码。
     *
     * @return source_code 或 name（空回退）；从未返回 null（{@code escapeSql} 已处理 null,但此处保险给空串）
     */
    private static String resolveSourceCode(SourceDataSource src) {
        if (src == null) {
            return "";
        }
        String code = src.getSourceCode();
        if (code != null && !code.isBlank()) {
            return code;
        }
        String fallback = src.getName() == null ? "" : src.getName();
        log.warn("SeaTunnelConfBuilder: source_code 为空，_etl_source_system 回退数据源名 id={} name={}（spec 070 建议补码）",
                src.getId(), fallback);
        return fallback;
    }

    /** SQL 字符串字面量转义：单引号倍写，禁止换行（防注入） */
    private static String escapeSql(String s) {
        if (s == null) return "";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                throw new IllegalArgumentException("Newline characters are not allowed in SQL string literals");
            }
        }
        return s.replace("'", "''");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * JDBC URL host 白名单：兼容 IPv4、域名、含点号/连字符/下划线的标识符；
     * 拒绝 `?` `&` `;` `@` `#` `/` `\` 等可破坏 URL/JDBC properties 解析的字符。
     */
    private static final java.util.regex.Pattern JDBC_HOST_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._-]{1,255}$");

    private String buildSourceJdbcUrl(SourceDataSource ds) {
        // 注入防御：host / dbName 拼到 JDBC URL 前必须经字符集白名单。
        // dbName 含 `?propertyX=Y` 时会注入 JDBC URL properties；MySQL JDBC 历史上有 autoDeserialize=true
        // 等危险属性可触发反序列化漏洞（CVE-2017-3589 类）。
        String host = ds.getHost();
        if (host == null || !JDBC_HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("非法的 JDBC host：" + host);
        }
        String dbName = com.dfygt.dfetl.server.common.IdentifierSanitizer.requireValid(
                ds.getDbName(), "ds.dbName");
        return switch (ds.getType().toUpperCase()) {
            case "MYSQL" -> "jdbc:mysql://%s:%d/%s?useSSL=%b&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                    .formatted(host, ds.getPort(), dbName, ds.getSsl());
            case "POSTGRESQL" -> "jdbc:postgresql://%s:%d/%s"
                    .formatted(host, ds.getPort(), dbName);
            case "ORACLE" -> "jdbc:oracle:thin:@//%s:%d/%s"
                    .formatted(host, ds.getPort(), dbName);
            case "SQLSERVER" -> "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=%b;trustServerCertificate=true"
                    .formatted(host, ds.getPort(), dbName, ds.getSsl());
            // Doris 走 MySQL 协议（FE Query 端口）
            case "DORIS" -> "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true"
                    .formatted(host, ds.getPort(), dbName);
            default -> throw new IllegalArgumentException("Unsupported source type: " + ds.getType());
        };
    }

    private String jdbcDriverClass(String type) {
        return switch (type.toUpperCase()) {
            case "MYSQL"      -> "com.mysql.cj.jdbc.Driver";
            case "POSTGRESQL" -> "org.postgresql.Driver";
            case "ORACLE"     -> "oracle.jdbc.OracleDriver";
            case "SQLSERVER"  -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            default -> throw new IllegalArgumentException("Unsupported source type: " + type);
        };
    }

    private String buildWhereClause(SyncTask task, String srcType, WatermarkService.WindowContext window) {
        if (isCustomSql(task)) {
            return whereClauseBuilder.build(task, srcType, window);
        }
        String tableName = task.getViewNames() != null && !task.getViewNames().isEmpty()
                ? task.getViewNames().get(0)
                : null;
        return whereClauseBuilder.build(task, srcType, window, tableName);
    }

    private boolean isCustomSql(SyncTask task) {
        return task != null && "CUSTOM_SQL".equalsIgnoreCase(task.getSourceMode());
    }

    private String customSqlName(SyncTask task) {
        String name = task.getCustomSqlName();
        if (name == null || name.isBlank()) {
            return task.getViewNames() != null && !task.getViewNames().isEmpty()
                    ? task.getViewNames().get(0)
                    : "custom_sql";
        }
        return name;
    }

    private String buildQuery(SourceDataSource src, String schema, String table, String where, List<ColumnInfo> cols) {
        // SeaTunnel Jdbc connector 直接消费整段 SQL
        // 注意：Oracle/SqlServer 默认返回大写/混合大小写列名，SeaTunnel 会用这些名作为 JSON key；
        // 而 Doris 列名默认全小写 → JSON key 与 Doris 列匹配失败导致 stream load 全部 filtered。
        // 解决：对 Oracle/SqlServer 显式列出列并 alias 为小写带引号 ("COL" AS "col")。
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(buildSelectClause(src, schema, table, cols));
        sb.append(" FROM ");
        if (schema != null && !schema.isBlank()) {
            sb.append(escapeIdentifier(src.getType(), schema)).append('.');
        }
        sb.append(escapeIdentifier(src.getType(), table));
        if (where != null && !where.isBlank()) sb.append(" WHERE ").append(where);
        return sb.toString();
    }

    private String buildTableQuery(
            SyncTask task,
            SourceDataSource src,
            String schema,
            String table,
            String where,
            List<ColumnInfo> cols) {
        MedicalQueryOptions medical = medicalQueryOptions(task);
        if (medical.datasetCode() == null) {
            return buildQuery(src, schema, table, where, cols);
        }
        if (medical.validSourceQuery() != null) {
            if (where != null && !where.isBlank()) {
                log.warn("SeaTunnelConfBuilder: medical validSourceQuery already contains row scope, ignore extra where "
                                + "taskId={} table={}",
                        task.getId(), table);
            }
            return medical.validSourceQuery();
        }
        MedicalDatasetContract contract = MedicalContractSnapshotCodec.resolveForTask(
                task, requireMedicalContractService(), objectMapper);
        SourceDialectAdapter adapter = requireSourceDialectAdapterResolver()
                .resolve(src.getType(), medical.compatibilityMode());
        MedicalSourceSelectPlan plan = requireMedicalSourceSelectBuilder()
                .buildSelect(schema, table, contract, cols, adapter, medical.fieldMapping());
        if (plan.hasBlockers()) {
            throw new IllegalStateException("医共体 Reader SQL 生成失败: " + plan.blockers());
        }
        if (plan.warnings() != null && !plan.warnings().isEmpty()) {
            log.warn("SeaTunnelConfBuilder: medical source select warnings taskId={} table={} warnings={}",
                    task.getId(), table, plan.warnings());
        }
        if (where != null && !where.isBlank()) {
            return plan.sql() + " WHERE " + where;
        }
        return plan.sql();
    }

    private String resolveSourceSchema(SyncTask task, SourceDataSource src) {
        if (task != null && task.getSourceSchema() != null && !task.getSourceSchema().isBlank()) {
            return task.getSourceSchema();
        }
        String schema = src.getSchemaName();
        if ((schema == null || schema.isBlank()) && "ORACLE".equalsIgnoreCase(src.getType())) {
            schema = src.getUsername() != null ? src.getUsername().toUpperCase(Locale.ROOT) : null;
        }
        return schema;
    }

    private String buildCustomSqlQuery(SourceDataSource src, SyncTask task, String where, List<ColumnInfo> cols) {
        String sql = CustomSqlValidator.requireReadOnlySelect(task.getCustomSql());
        String alias = CustomSqlQueryBuilder.alias(src.getType());
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(buildCustomSqlSelectClause(src, task, cols));
        sb.append(" FROM (\n");
        sb.append(sql).append("\n) ").append(alias);
        if (where != null && !where.isBlank()) {
            sb.append(" WHERE ").append(where);
        }
        return sb.toString();
    }

    private String buildCustomSqlSelectClause(SourceDataSource src, SyncTask task, List<ColumnInfo> cols) {
        // 大小写折叠冲突检测（自定义 SQL 输出列同样会被 alias 为小写）
        assertNoCaseFoldingConflict(cols, "CUSTOM_SQL");
        String tableAlias = CustomSqlQueryBuilder.alias(src.getType());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            String name = cols.get(i).columnName();
            String lower = name.toLowerCase(Locale.ROOT);
            sb.append(qualifiedIdentifier(src.getType(), tableAlias, name))
                    .append(" AS ")
                    .append(escapeAlias(src.getType(), lower));
        }
        return sb.toString();
    }

    /**
     * 构造 SELECT 子句中的列列表。
     * <ul>
     *   <li>MYSQL/POSTGRESQL：返回 "*"（列名本身就是小写，无需 alias）</li>
     *   <li>ORACLE/SQLSERVER：列出所有列并 alias 为引号包裹的小写 ("COL" AS "col")，使 SeaTunnel
     *       发送的 JSON key 与 Doris 默认小写列名匹配</li>
     * </ul>
     * 拉取列失败时回退到 "*"，保持原行为。
     */
    /**
     * 构造 SELECT 子句中的列列表。
     * <p>所有源端类型均枚举列并 alias 为小写，确保 SeaTunnel 输出的 JSON key 始终为小写，
     * 与 Doris 列名（强制小写）保持一致。
     * <ul>
     *   <li>MySQL：列名本身小写，内常无需 alias，使用反引号括号</li>
     *   <li>PostgreSQL：默认小写，但引号标识符可能大写，故次之 alias</li>
     *   <li>Oracle/SQL Server：列名大写，必须 alias 为小写</li>
     * </ul>
     * 拉取列失败时 fail-fast，不允许 SELECT * fallback。
     */
    private String buildSelectClause(SourceDataSource src, String schema, String table, List<ColumnInfo> cols) {
        // 大小写折叠冲突检测：源端若同时存在仅大小写不同的列（如 Name/NAME），
        // 全部 alias 为小写后会产生重复 JSON key，Doris stream load 行为不可预期。
        // 与 DorisSchemaPreviewService / validateAgainstSourceMetadata 口径一致，执行前 fail-fast。
        assertNoCaseFoldingConflict(cols, table);
        // 始终枚举列并 alias 为小写，避免源端大写列名与 Doris 小写列名不匹配
        String type = src.getType().toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            String name  = cols.get(i).columnName();
            String lower = name.toLowerCase(Locale.ROOT);
            sb.append(escapeIdentifier(type, name))
                    .append(" AS ")
                    .append(escapeAlias(type, lower));
        }
        return sb.toString();
    }

    /**
     * 大小写折叠冲突检测：列名小写折叠后若出现重复（如 Name/NAME → name），抛 IllegalArgumentException。
     * 用于源端列别名小写化前的执行期 fail-fast，避免重复 JSON key 导致 Doris stream load 数据错乱。
     */
    private void assertNoCaseFoldingConflict(List<ColumnInfo> cols, String objectName) {
        java.util.Map<String, java.util.List<String>> lowerToOriginals = new java.util.LinkedHashMap<>();
        for (ColumnInfo c : cols) {
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
                    "[" + objectName + "] 源端字段存在大小写冲突，小写折叠后将产生重复列名: "
                            + String.join("; ", conflicts));
        }
    }

    private List<ColumnInfo> resolveTableColumns(SourceDataSource src, String schema, String table) {
        List<ColumnInfo> cols;
        try {
            cols = sourceDataSourceService.listColumns(src.getId(), schema, table);
        } catch (Exception e) {
            throw sourceColumnResolutionException("TABLE_VIEW", table, e.getMessage(), e);
        }
        return requireColumns(cols, "TABLE_VIEW", table);
    }

    private List<ColumnInfo> resolveCustomSqlColumns(SourceDataSource src, SyncTask task) {
        List<ColumnInfo> cols;
        try {
            cols = sourceDataSourceService.listCustomSqlColumns(src.getId(), task.getCustomSql());
        } catch (Exception e) {
            throw sourceColumnResolutionException("CUSTOM_SQL", customSqlName(task), e.getMessage(), e);
        }
        return requireColumns(cols, "CUSTOM_SQL", customSqlName(task));
    }

    private List<ColumnInfo> requireColumns(List<ColumnInfo> cols, String sourceMode, String sourceName) {
        if (cols == null || cols.isEmpty()) {
            throw sourceColumnResolutionException(sourceMode, sourceName, "empty column list", null);
        }
        return cols;
    }

    private IllegalStateException sourceColumnResolutionException(
            String sourceMode, String sourceName, String reason, Throwable cause) {
        String message = "failed to resolve source columns for " + sourceMode + " " + sourceName
                + ": " + reason
                + "; cannot build lower-case select aliases; SELECT * fallback is not allowed";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private String resolveDorisTargetField(String sourceField, List<ColumnInfo> sourceColumns, String purpose) {
        if (sourceField == null || sourceField.isBlank()) {
            return sourceField;
        }
        String field = sourceField.trim();
        if (!whereClauseBuilder.isFieldNameSafe(field)) {
            throw new IllegalArgumentException("configured Doris control field is unsafe: "
                    + purpose + "=" + sourceField);
        }
        boolean exists = sourceColumns.stream()
                .map(ColumnInfo::columnName)
                .anyMatch(name -> name != null && name.equalsIgnoreCase(field));
        if (!exists) {
            throw new IllegalStateException("configured Doris control field not found in source columns: "
                    + purpose + "=" + sourceField);
        }
        return field.toLowerCase(Locale.ROOT);
    }

    /**
     * SQL 标识符转义。schema/table 在 server 入口已按白名单校验（{@link WhereClauseBuilder#isFieldNameSafe}）。
     */
    private String escapeIdentifier(String type, String ident) {
        return dialectQuoteHelper.quoteIdentifier(type, ident);
    }

    /** 从全局配置读取 checkpoint.timeout（毫秒），默认 1 小时 */
    private long getCheckpointTimeoutMs() {
        if (systemSettingRepository == null) return 3600000L;
        return systemSettingRepository.findById("etl.checkpoint_timeout_sec")
                .map(s -> {
                    try { return Long.parseLong(s.getSettingValue()) * 1000L; }
                    catch (Exception e) { return 3600000L; }
                })
                .orElse(3600000L);
    }

    /**
     * SeaTunnel JDBC source fetch_size：任务级 batchSize 优先，其次全局 etl.fetch_size，最后回退 50000。
     */
    private int resolveFetchSize(SyncTask task) {
        Integer taskBatchSize = task == null ? null : task.getBatchSize();
        if (taskBatchSize != null && taskBatchSize > 0) {
            return taskBatchSize;
        }
        if (systemSettingRepository == null) {
            return DEFAULT_FETCH_SIZE;
        }
        return systemSettingRepository.findById("etl.fetch_size")
                .map(s -> parsePositiveIntOrDefault(s.getSettingValue(), DEFAULT_FETCH_SIZE))
                .orElse(DEFAULT_FETCH_SIZE);
    }

    private static int parsePositiveIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String escapeAlias(String type, String alias) {
        return dialectQuoteHelper.quoteAlias(type, alias);
    }

    private String qualifiedIdentifier(String type, String tableAlias, String ident) {
        return quoteTableAliasReference(type, tableAlias)
                + "." + dialectQuoteHelper.quoteIdentifier(type, ident);
    }

    private String quoteTableAliasReference(String type, String tableAlias) {
        if ("ORACLE".equalsIgnoreCase(type)) {
            return tableAlias.toUpperCase(Locale.ROOT);
        }
        return dialectQuoteHelper.quoteAlias(type, tableAlias);
    }

    private MedicalDatasetContractService requireMedicalContractService() {
        if (medicalContractService == null) {
            throw new IllegalStateException("医共体契约服务未启用，无法生成标准 Reader SQL");
        }
        return medicalContractService;
    }

    private MedicalSourceSelectBuilder requireMedicalSourceSelectBuilder() {
        if (medicalSourceSelectBuilder == null) {
            throw new IllegalStateException("医共体 Reader SQL 构建器未启用");
        }
        return medicalSourceSelectBuilder;
    }

    private SourceDialectAdapterResolver requireSourceDialectAdapterResolver() {
        if (sourceDialectAdapterResolver == null) {
            throw new IllegalStateException("源库方言适配器解析器未启用");
        }
        return sourceDialectAdapterResolver;
    }

    private MedicalQueryOptions medicalQueryOptions(SyncTask task) {
        String dc = task == null ? null : task.getDataCharacteristics();
        if (dc == null || dc.isBlank()) {
            return MedicalQueryOptions.empty();
        }
        try {
            Map<String, Object> values = objectMapper.readValue(dc, new TypeReference<Map<String, Object>>() {});
            Object mode = values.get("medicalMappingMode");
            boolean contractDriven = mode != null && "CONTRACT_DRIVEN".equalsIgnoreCase(mode.toString());
            if (!contractDriven) {
                return MedicalQueryOptions.empty();
            }
            Object datasetCode = values.get("matchedDatasetCode");
            if (datasetCode == null || datasetCode.toString().isBlank()) {
                throw new IllegalStateException("医共体 contract-driven 任务缺少 matchedDatasetCode");
            }
            Object compatibilityMode = values.get("compatibilityMode");
            Object validSourceQuery = values.get("medicalValidSourceQuery");
            return new MedicalQueryOptions(
                    datasetCode.toString().trim().toUpperCase(Locale.ROOT),
                    compatibilityMode == null ? null : compatibilityMode.toString().trim(),
                    parseStringMap(values.get("fieldMapping")),
                    validSourceQuery == null || validSourceQuery.toString().isBlank()
                            ? null
                            : validSourceQuery.toString().trim());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (dc.contains("CONTRACT_DRIVEN")) {
                throw new IllegalStateException("医共体 contract-driven 任务 dataCharacteristics 不是合法 JSON: "
                        + e.getMessage(), e);
            }
            return MedicalQueryOptions.empty();
        }
    }

    private static Map<String, String> parseStringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().toString();
            String val = entry.getValue().toString();
            if (!key.isBlank() && !val.isBlank()) {
                mapped.put(key, val);
            }
        }
        return mapped;
    }

    private record MedicalQueryOptions(
            String datasetCode,
            String compatibilityMode,
            Map<String, String> fieldMapping,
            String validSourceQuery) {

        static MedicalQueryOptions empty() {
            return new MedicalQueryOptions(null, null, Map.of(), null);
        }
    }

    private Map<String, String> parseJsonMap(String json) {
        return TargetTableMapParser.parseStrict(json, objectMapper, null);
    }

    /**
     * 预览 Reader 实际拼出的 SELECT SQL（含 WHERE）。
     * <p>用于排查"增量任务为何读取 0 行"——把 windowStart/End 与最终 SQL 一起返给前端。
     * <p>不连接数据库、不执行任何查询，纯字符串构造。
     */
    public String previewReaderSql(SyncTask task, WatermarkService.WindowContext window) {
        SourceDataSource src = sourceRepo.findById(task.getSourceDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("SourceDataSource not found"));
        if (!isCustomSql(task) && (task.getViewNames() == null || task.getViewNames().isEmpty())) {
            throw new IllegalArgumentException("任务未配置源表");
        }
        String where = buildWhereClause(task, src.getType(), window);
        if (isCustomSql(task)) {
            List<ColumnInfo> cols = resolveCustomSqlColumns(src, task);
            return buildCustomSqlQuery(src, task, where, cols);
        }
        String table = task.getViewNames().get(0);
        String schema = resolveSourceSchema(task, src);
        List<ColumnInfo> cols = resolveTableColumns(src, schema, table);
        return buildTableQuery(task, src, schema, table, where, cols);
    }

    /**
     * 查询源端表的行数，用于执行前日志对比。
     * 失败时返回 -1（不影响主流程）。
     */
    public long countSourceRows(SyncTask task, WatermarkService.WindowContext window) {
        return countSourceRowsWithDiagnostic(task, window).rows();
    }

    /**
     * 执行前源端 COUNT 的可观测结果。rows=-1 表示统计失败，errorMessage 保留归因信息，
     * 供 AUTO_COUNT 哨兵记录 SOURCE_COUNT_TIMEOUT/SOURCE_COUNT_FAILED，而不是只写一个泛化 ERROR。
     */
    public SourceCountResult countSourceRowsWithDiagnostic(SyncTask task,
                                                            WatermarkService.WindowContext window) {
        try {
            return new SourceCountResult(countSourceRowsStrict(task, window), null);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("countSourceRows failed task={}: {}", task.getId(), message);
            String code = message.toLowerCase(java.util.Locale.ROOT).contains("timeout")
                    || message.toLowerCase(java.util.Locale.ROOT).contains("canceling statement")
                    ? "SOURCE_COUNT_TIMEOUT" : "SOURCE_COUNT_FAILED";
            return new SourceCountResult(-1L, code + ": " + message);
        }
    }

    public record SourceCountResult(long rows, String errorMessage) {
    }

    /**
     * 查询源端执行行集行数。与 {@link #countSourceRows(SyncTask, WatermarkService.WindowContext)}
     * 使用同一 SQL 构造逻辑，但将错误透出给调用方。
     */
    public long countSourceRowsStrict(SyncTask task, WatermarkService.WindowContext window) {
        SourceDataSource src = sourceRepo.findById(task.getSourceDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("SourceDataSource not found"));
        String sql = buildSourceCountSql(task, window, src);
        return executeSourceCountSql(src, sql);
    }

    /**
     * 构造源端执行行集 COUNT SQL，用于执行前 count 和监控页 source-count 保持语义一致。
     */
    public String buildSourceCountSql(SyncTask task, WatermarkService.WindowContext window) {
        SourceDataSource src = sourceRepo.findById(task.getSourceDataSourceId())
                .orElseThrow(() -> new NoSuchElementException("SourceDataSource not found"));
        return buildSourceCountSql(task, window, src);
    }

    private String buildSourceCountSql(SyncTask task, WatermarkService.WindowContext window, SourceDataSource src) {
        if (!isCustomSql(task) && (task.getViewNames() == null || task.getViewNames().isEmpty())) {
            throw new IllegalArgumentException("任务未配置源表");
        }
        String where = buildWhereClause(task, src.getType(), window);
        if (isCustomSql(task)) {
            return CustomSqlQueryBuilder.countSql(src.getType(), task.getCustomSql(), where);
        }
        String srcTable = task.getViewNames().get(0);
        String schema = resolveSourceSchema(task, src);
        MedicalQueryOptions medical = medicalQueryOptions(task);
        if (medical.datasetCode() != null) {
            if (medical.validSourceQuery() != null) {
                return "SELECT COUNT(*) FROM (" + stripTrailingSemicolon(medical.validSourceQuery())
                        + ") dfetl_valid_source_count";
            }
            // 行数统计不需要执行医共体 contract-driven 的字段投影、类型转换和 alias。
            // 对大视图使用 SELECT COUNT(*) FROM (SELECT <全部转换字段>) 会把 COUNT
            // 退化为全量投影，容易触发源端 query_timeout（task 798 曾因此返回 -1）。
            // 但执行期问题行分流后，SeaTunnel 实际读取 medicalValidSourceQuery 的合规子集；
            // 此时 AUTO_COUNT 和 ExecutionResult 必须统计同一合规子集，不能再统计原始视图总行数。
            // 执行 SQL 仍由 buildTableQuery 负责；COUNT 只复用同一 schema/table/where/window。
            String qualified = dialectQuoteHelper.qualifyTable(src.getType(), schema, srcTable);
            return "SELECT COUNT(*) FROM " + qualified
                    + (where.isBlank() ? "" : " WHERE " + where);
        }
        String qualified = dialectQuoteHelper.qualifyTable(src.getType(), schema, srcTable);
        return "SELECT COUNT(*) FROM " + qualified + (where.isBlank() ? "" : " WHERE " + where);
    }

    private static String stripTrailingSemicolon(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private long executeSourceCountSql(SourceDataSource src, String sql) {
        String url = buildSourceJdbcUrl(src);
        String pwd = aesUtil.decrypt(src.getPasswordEnc());
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url, src.getUsername(), pwd)) {
            SourceDataSourceService.prepareCustomSqlConnection(conn);
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                SourceDataSourceService.applyQueryTimeout(ps, src);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("查询源端执行行集 count 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询目标 Doris 表的行数，用于执行后日志对比。
     * 失败时返回 -1（不影响主流程）。
     */
    public long countTargetRows(SyncTask task) {
        return countTargetRows(task, null);
    }

    /**
     * 按本次执行窗口统计 Doris 行数。增量任务不能拿“窗口源行数”与“目标全表行数”比较，
     * 否则每次正常增量都会生成伪 DIFF；全量或缺少窗口字段时仍退回全表口径。
     */
    public long countTargetRows(SyncTask task, WatermarkService.WindowContext window) {
        try {
            TargetDataSource tgt = targetRepo.findById(task.getTargetDataSourceId()).orElse(null);
            if (tgt == null || task.getViewNames() == null || task.getViewNames().isEmpty()) return -1;
            String srcTable = task.getViewNames().get(0);
            Map<String, String> targetMap = parseJsonMap(task.getTargetTableMap());
            String tgtTable = targetMap.getOrDefault(srcTable, srcTable);
            String url = "jdbc:mysql://" + tgt.getFeHost() + ":" + tgt.getFePort()
                    + "/" + tgt.getDbName() + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            String pwd = aesUtil.decrypt(tgt.getPasswordEnc());
            String tgtDb = tgt.getDbName();
            String countSql = buildTargetCountSql(task, tgt, tgtTable, window);
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url, tgt.getUsername(), pwd);
                 java.sql.PreparedStatement ps = conn.prepareStatement(countSql);
                 java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (Exception e) {
            log.warn("countTargetRows failed task={}: {}", task.getId(), e.getMessage());
            return -1;
        }
    }

    String buildTargetCountSql(SyncTask task, TargetDataSource target) {
        if (task == null || target == null || task.getViewNames() == null || task.getViewNames().isEmpty()) {
            throw new IllegalArgumentException("任务或目标数据源不完整，无法构造目标端 count SQL");
        }
        String srcTable = task.getViewNames().get(0);
        Map<String, String> targetMap = parseJsonMap(task.getTargetTableMap());
        String tgtTable = targetMap.getOrDefault(srcTable, srcTable);
        return buildTargetCountSql(task, target, tgtTable);
    }

    private String buildTargetCountSql(SyncTask task, TargetDataSource target, String targetTable) {
        return buildTargetCountSql(task, target, targetTable, null);
    }

    String buildTargetCountSql(SyncTask task, TargetDataSource target, String targetTable,
                               WatermarkService.WindowContext window) {
        String sql = "SELECT COUNT(*) FROM `" + target.getDbName() + "`.`" + targetTable + "`";
        List<String> predicates = new ArrayList<>();
        if (isTaskScopeFilterActive(task)) {
            predicates.add("`_etl_job_id` = " + task.getId());
        }
        if (window != null && task.getIncrementalField() != null && !task.getIncrementalField().isBlank()) {
            String field = "`" + task.getIncrementalField().replace("`", "``") + "`";
            if (window.windowStart() != null && window.windowEnd() != null) {
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(java.time.ZoneId.of("Asia/Shanghai"));
                predicates.add(field + " >= '" + formatter.format(window.windowStart()) + "'");
                predicates.add(field + " < '" + formatter.format(window.windowEnd()) + "'");
            } else if (window.windowStartId() != null && window.windowEndId() != null) {
                predicates.add(field + " > " + window.windowStartId());
                predicates.add(field + " <= " + window.windowEndId());
            }
        }
        if (!predicates.isEmpty()) {
            sql += " WHERE " + String.join(" AND ", predicates);
        }
        return sql;
    }

    private boolean isTaskScopeFilterActive(SyncTask task) {
        return task != null
                && task.getId() != null
                && etlSystemFieldsService.enabledFields().containsKey("_etl_job_id");
    }
}
