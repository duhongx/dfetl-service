package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.SyncTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 统一解析源端表关系：sourceSchema/sourceTable/sourceMode/customSql。
 */
@Component
@RequiredArgsConstructor
public class SourceTableResolver {

    private final WhereClauseBuilder whereClauseBuilder;

    public record SourceRelation(
            String dialect,
            String schema,
            String table,
            String sourceMode,
            String customSql,
            boolean customSqlMode
    ) {}

    public SourceRelation resolveRequired(SyncTask task, SourceDataSource source, String sourceTable) {
        if (task == null) {
            throw new IllegalArgumentException("SyncTask 不能为空");
        }
        if (source == null) {
            throw new IllegalArgumentException("SourceDataSource 不能为空");
        }
        String mode = task.getSourceMode() == null || task.getSourceMode().isBlank()
                ? "TABLE_VIEW"
                : task.getSourceMode().trim().toUpperCase(Locale.ROOT);
        boolean customSqlMode = "CUSTOM_SQL".equals(mode);
        String schema = resolveSchemaRequired(task, source);
        String table = sourceTable;
        if (table == null || table.isBlank()) {
            table = customSqlMode ? resolveCustomSqlName(task) : firstViewName(task);
        }
        validateIdentifier(schema, "源端 schema");
        validateIdentifier(table, "源表名");
        return new SourceRelation(
                normalizeDialect(source.getType()),
                schema,
                table,
                mode,
                task.getCustomSql(),
                customSqlMode
        );
    }

    public String resolveSchemaRequired(SyncTask task, SourceDataSource source) {
        return SourceSchemaResolver.resolveRequired(task, source);
    }

    private String resolveCustomSqlName(SyncTask task) {
        String name = trimToNull(task.getCustomSqlName());
        if (name != null) {
            return name;
        }
        String view = firstViewName(task);
        return view == null ? "custom_sql" : view;
    }

    private String firstViewName(SyncTask task) {
        if (task.getViewNames() == null || task.getViewNames().isEmpty()) {
            return null;
        }
        return task.getViewNames().get(0);
    }

    private void validateIdentifier(String identifier, String label) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (!whereClauseBuilder.isFieldNameSafe(identifier)) {
            throw new IllegalArgumentException(label + "格式非法: " + identifier);
        }
    }

    private String normalizeDialect(String dialect) {
        return dialect == null || dialect.isBlank()
                ? "MYSQL"
                : dialect.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
