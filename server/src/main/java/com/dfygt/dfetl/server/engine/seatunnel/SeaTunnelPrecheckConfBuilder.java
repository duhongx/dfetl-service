package com.dfygt.dfetl.server.engine.seatunnel;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.common.IdentifierSanitizer;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import com.dfygt.dfetl.server.medical.precheck.DorisPrecheckTableSpec;
import com.dfygt.dfetl.server.medical.precheck.PrecheckSourceProjectionBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 生成 PostgreSQL 视图到 Doris STRING 暂存表的一次性 SeaTunnel 作业配置。
 *
 * <p>该配置与正式同步配置严格隔离：固定单 Reader、禁止任何分片参数、固定追加到
 * 目标数据源现有数据库中的 {@code raw_yl_<datasetCode>}，不会注入正式 ETL 字段或写入正式业务表。
 */
@Component
@RequiredArgsConstructor
public class SeaTunnelPrecheckConfBuilder {

    private static final int DEFAULT_FETCH_SIZE = 50_000;
    private static final String POSTGRESQL_DRIVER = "org.postgresql.Driver";

    private final AesUtil aesUtil;
    private final PrecheckSourceProjectionBuilder projectionBuilder;

    public Map<String, Object> buildJobMap(
            Long runId,
            SourceDataSource source,
            TargetDataSource target,
            DorisPrecheckTableSpec tableSpec,
            String sourceSchema,
            String sourceObject,
            List<PrecheckSourceProjectionBuilder.ProjectionField> fields) {
        requirePositive(runId, "runId");
        requireNormalSource(source);
        requireNormalTarget(target);
        requireTargetDatabase(target, tableSpec.database());

        String query = projectionBuilder.build(
                source.getType(), runId, sourceSchema, sourceObject, fields);
        String identity = "dfetl_precheck_run_" + runId;

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("parallelism", 1);
        env.put("job.mode", "BATCH");
        env.put("job.name", identity);
        env.put("checkpoint.interval", 30_000);
        env.put("checkpoint.timeout", 3_600_000L);

        Map<String, Object> jdbc = new LinkedHashMap<>();
        jdbc.put("plugin_name", "Jdbc");
        jdbc.put("url", buildPostgresqlUrl(source));
        jdbc.put("driver", POSTGRESQL_DRIVER);
        jdbc.put("user", source.getUsername());
        jdbc.put("password", aesUtil.decrypt(source.getPasswordEnc()));
        jdbc.put("query", query);
        jdbc.put("parallelism", 1);
        jdbc.put("fetch_size", DEFAULT_FETCH_SIZE);
        jdbc.put("result_table_name", "precheck_raw");

        Map<String, Object> dorisConfig = new LinkedHashMap<>();
        dorisConfig.put("format", "json");
        dorisConfig.put("read_json_by_line", "true");
        dorisConfig.put("strict_mode", "true");
        dorisConfig.put("max_filter_ratio", "0");

        Map<String, Object> sink = new LinkedHashMap<>();
        sink.put("plugin_name", "Doris");
        sink.put("fenodes", target.getFeHost() + ":" + defaultPort(target.getHttpPort(), 8030));
        sink.put("query-port", defaultPort(target.getFePort(), 9030));
        sink.put("username", target.getUsername());
        sink.put("password", aesUtil.decrypt(target.getPasswordEnc()));
        sink.put("database", tableSpec.database());
        sink.put("table", tableSpec.rawTable());
        sink.put("sink.label-prefix", identity);
        sink.put("source_table_name", "precheck_raw");
        sink.put("schema_save_mode", "IGNORE");
        sink.put("data_save_mode", "APPEND_DATA");
        sink.put("sink.enable-2pc", true);
        sink.put("doris.config", dorisConfig);

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("env", env);
        job.put("source", List.of(jdbc));
        job.put("transform", List.of());
        job.put("sink", List.of(sink));
        return job;
    }

    private void requireNormalSource(SourceDataSource source) {
        if (source == null) {
            throw new IllegalArgumentException("源数据源不能为空");
        }
        if (!"POSTGRESQL".equalsIgnoreCase(source.getType())) {
            throw new UnsupportedOperationException(
                    "数据预检一期仅支持 PostgreSQL，当前源类型: " + source.getType());
        }
        if (!"NORMAL".equalsIgnoreCase(source.getStatus())) {
            throw new IllegalStateException("源数据源必须为 NORMAL，当前状态: " + source.getStatus());
        }
    }

    private void requireNormalTarget(TargetDataSource target) {
        if (target == null) {
            throw new IllegalArgumentException("目标数据源不能为空");
        }
        if (!"NORMAL".equalsIgnoreCase(target.getStatus())) {
            throw new IllegalStateException("Doris 目标数据源必须为 NORMAL，当前状态: " + target.getStatus());
        }
    }

    private void requireTargetDatabase(TargetDataSource target, String precheckDatabase) {
        String precheck = precheckDatabase.toLowerCase(Locale.ROOT);
        if (!sameDatabase(precheck, target.getDbName())) {
            throw new IllegalStateException(
                    "Doris 预检表必须位于路由目标数据库: actual=" + precheckDatabase
                            + ", expected=" + target.getDbName());
        }
    }

    private boolean sameDatabase(String normalizedPrecheck, String database) {
        return database != null
                && !database.isBlank()
                && normalizedPrecheck.equals(database.trim().toLowerCase(Locale.ROOT));
    }

    private String buildPostgresqlUrl(SourceDataSource source) {
        String host = requireHost(source.getHost());
        int port = defaultPort(source.getPort(), 5432);
        String database = IdentifierSanitizer.requireValid(source.getDbName(), "sourceDatabase");
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    private String requireHost(String host) {
        if (host == null || !host.matches("[A-Za-z0-9._-]{1,255}")) {
            throw new IllegalArgumentException("非法的 PostgreSQL host: " + host);
        }
        return host;
    }

    private int defaultPort(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    private void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }
}
