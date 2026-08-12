package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.dto.SourceDataSourceDto;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 统一解析源端 schema，避免创建、执行、校验各自 fallback 造成读表不一致。
 */
public final class SourceSchemaResolver {

    private static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");

    private SourceSchemaResolver() {
    }

    public static String resolveRequired(SyncTask task, SourceDataSource source) {
        if (source == null) {
            throw new IllegalArgumentException("SourceDataSource 不能为空");
        }
        return resolveRequired(
                task == null ? null : task.getSourceSchema(),
                source.getSchemaName(),
                source.getType(),
                source.getUsername());
    }

    public static String resolveRequired(SyncTask task, SourceDataSourceDto source) {
        if (source == null) {
            throw new IllegalArgumentException("SourceDataSource 不能为空");
        }
        return resolveRequired(
                task == null ? null : task.getSourceSchema(),
                source.getSchema(),
                source.getType(),
                source.getUsername());
    }

    public static String resolveRequired(String taskSchema, SourceDataSource source) {
        if (source == null) {
            throw new IllegalArgumentException("SourceDataSource 不能为空");
        }
        return resolveRequired(taskSchema, source.getSchemaName(), source.getType(), source.getUsername());
    }

    public static String resolveRequired(String taskSchema, SourceDataSourceDto source) {
        if (source == null) {
            throw new IllegalArgumentException("SourceDataSource 不能为空");
        }
        return resolveRequired(taskSchema, source.getSchema(), source.getType(), source.getUsername());
    }

    public static String resolveRequired(String taskSchema, String datasourceSchema, String datasourceType, String username) {
        String schema = trimToNull(taskSchema);
        if (schema == null) {
            schema = trimToNull(datasourceSchema);
        }
        if (schema == null && "ORACLE".equalsIgnoreCase(trimToNull(datasourceType))) {
            String user = trimToNull(username);
            schema = user == null ? null : user.toUpperCase(Locale.ROOT);
        }
        if (schema == null) {
            throw new IllegalArgumentException(
                    "无法解析源端 schema: task.sourceSchema 和 datasource.schemaName 均为空");
        }
        if (!SCHEMA_PATTERN.matcher(schema).matches()) {
            throw new IllegalArgumentException("源端 schema 格式非法: " + schema);
        }
        return schema;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
