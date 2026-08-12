package com.dfygt.dfetl.server.medical.precheck;

import com.dfygt.dfetl.server.service.DialectQuoteHelper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 为一期 PostgreSQL 全量预检生成一次性 STRING 投影 SQL。 */
@Component
public class PrecheckSourceProjectionBuilder {

    private final DialectQuoteHelper quoteHelper;

    public PrecheckSourceProjectionBuilder(DialectQuoteHelper quoteHelper) {
        this.quoteHelper = quoteHelper;
    }

    public String build(
            String dialect,
            Long runId,
            String schema,
            String sourceObject,
            List<ProjectionField> fields) {
        requirePostgresql(dialect);
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("runId 必须大于 0");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("预检投影字段不能为空");
        }

        String qualifiedSource = quoteHelper.qualifyTable("POSTGRESQL", schema, sourceObject);
        List<ResolvedField> resolvedFields = resolveFields(fields);
        List<String> selectItems = new ArrayList<>();
        selectItems.add(runId + "::BIGINT AS \"run_id\"");
        selectItems.add("CONCAT('" + runId
                + "-', LPAD(CAST(ROW_NUMBER() OVER () AS text), 20, '0')) AS \"row_id\"");
        selectItems.add(buildRowHash(resolvedFields) + " AS \"row_hash\"");
        selectItems.add("CURRENT_TIMESTAMP AS \"loaded_at\"");
        for (ResolvedField field : resolvedFields) {
            selectItems.add(nullableText(field.quotedSourceColumn())
                    + " AS " + field.quotedStagingColumn());
        }
        return "SELECT\n  " + String.join(",\n  ", selectItems)
                + "\nFROM " + qualifiedSource;
    }

    private List<ResolvedField> resolveFields(List<ProjectionField> fields) {
        List<ResolvedField> resolved = new ArrayList<>();
        Set<String> aliases = new LinkedHashSet<>();
        for (ProjectionField field : fields) {
            if (field == null) {
                throw new IllegalArgumentException("预检投影字段不能为 null");
            }
            String stagingColumn = DorisPrecheckTableSpec.normalizeBusinessColumn(field.stagingColumn());
            if (!aliases.add(stagingColumn)) {
                throw new IllegalArgumentException(
                        "预检投影字段大小写归一化后重复: " + field.stagingColumn());
            }
            if (field.sourceType() == null || field.sourceType().isBlank()) {
                throw new IllegalArgumentException("源字段类型不能为空: " + field.sourceColumn());
            }
            resolved.add(new ResolvedField(
                    stagingColumn,
                    quoteHelper.quoteColumn("POSTGRESQL", field.sourceColumn()),
                    quoteHelper.quoteAlias("POSTGRESQL", stagingColumn)));
        }
        return List.copyOf(resolved);
    }

    private String buildRowHash(List<ResolvedField> fields) {
        List<String> hashParts = new ArrayList<>();
        for (ResolvedField field : fields) {
            hashParts.add("'" + field.stagingColumn() + "'");
            String source = field.quotedSourceColumn();
            hashParts.add("CASE WHEN " + source + " IS NULL THEN '<NULL>' ELSE "
                    + "CAST(LENGTH(CAST(" + source + " AS text)) AS text) || ':' || CAST("
                    + source + " AS text) END");
        }
        return "MD5(" + String.join(" || CHR(31) || ", hashParts) + ")";
    }

    private String nullableText(String sourceColumn) {
        return "CASE WHEN " + sourceColumn + " IS NULL THEN NULL ELSE CAST("
                + sourceColumn + " AS text) END";
    }

    private void requirePostgresql(String dialect) {
        String normalized = dialect == null ? "" : dialect.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("POSTGRESQL", "PG").contains(normalized)) {
            throw new UnsupportedOperationException(
                    "数据预检一期仅支持 PostgreSQL，当前方言: " + dialect);
        }
    }

    public record ProjectionField(String sourceColumn, String stagingColumn, String sourceType) {
    }

    private record ResolvedField(
            String stagingColumn,
            String quotedSourceColumn,
            String quotedStagingColumn) {
    }
}
