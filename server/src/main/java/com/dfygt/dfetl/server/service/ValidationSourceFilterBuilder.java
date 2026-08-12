package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.service.sql.SqlLiteralEncoder;

import java.util.List;

/**
 * Validation-only source row filter.
 *
 * <p>Doris MERGE delete rows must still be read by SeaTunnel execution so Doris can apply the delete.
 * Validation compares source effective rows with Doris current rows, so it excludes rows carrying the
 * configured delete marker.
 */
public final class ValidationSourceFilterBuilder {

    private ValidationSourceFilterBuilder() {
    }

    public static String buildEffectiveSourceWhere(SyncTask task,
                                            String dialect,
                                            String baseWhere,
                                            List<String> sourceColumns,
                                            WhereClauseBuilder whereClauseBuilder,
                                            DialectQuoteHelper dialectQuoteHelper) {
        String normalizedBase = stripWhereKeyword(baseWhere);
        if (!hasDorisMergeSoftDelete(task)) {
            return normalizedBase;
        }

        String softDeleteField = task.getSoftDeleteField().trim();
        if (!whereClauseBuilder.isFieldNameSafe(softDeleteField)) {
            throw new IllegalArgumentException("softDeleteField is unsafe: " + task.getSoftDeleteField());
        }
        if (!containsColumn(sourceColumns, softDeleteField)) {
            throw new IllegalStateException("softDeleteField not found in source columns: " + softDeleteField);
        }

        String quotedField = dialectQuoteHelper.quoteColumn(dialect, softDeleteField);
        String deleteSignValue = task.getDeleteSignValue() == null || task.getDeleteSignValue().isBlank()
                ? "1"
                : task.getDeleteSignValue().trim();
        String condition = "(" + quotedField + " IS NULL OR " + quotedField + " <> "
                + SqlLiteralEncoder.encode(deleteSignValue) + ")";
        if (normalizedBase.isBlank()) {
            return condition;
        }
        return "(" + normalizedBase + ") AND " + condition;
    }

    public static boolean hasDorisMergeSoftDelete(SyncTask task) {
        return task != null
                && Boolean.TRUE.equals(task.getEnableDorisMerge())
                && task.getSoftDeleteField() != null
                && !task.getSoftDeleteField().isBlank();
    }

    private static boolean containsColumn(List<String> columns, String expected) {
        if (columns == null || columns.isEmpty()) {
            return false;
        }
        for (String column : columns) {
            if (column != null && column.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    private static String stripWhereKeyword(String where) {
        if (where == null || where.isBlank()) {
            return "";
        }
        String trimmed = where.trim();
        if (trimmed.regionMatches(true, 0, "WHERE ", 0, 6)) {
            return trimmed.substring(6).trim();
        }
        return trimmed;
    }
}
