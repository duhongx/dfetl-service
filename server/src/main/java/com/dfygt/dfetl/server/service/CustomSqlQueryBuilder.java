package com.dfygt.dfetl.server.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 自定义 SQL 外层查询构造器。
 *
 * <p>只包裹用户已校验为只读 SELECT/WITH SELECT 的 SQL，不改写用户 SQL 内部字段。
 */
public final class CustomSqlQueryBuilder {

    private static final String CUSTOM_SQL_ALIAS = "dfetl_src";
    private static final Pattern WHERE_PREFIX = Pattern.compile("(?i)^\\s*where\\s+");

    private CustomSqlQueryBuilder() {
    }

    public static String countSql(String dialect, String customSql, String whereClause) {
        String normalized = CustomSqlValidator.requireReadOnlySelect(customSql);
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM (\n")
                .append(normalized)
                .append("\n) ")
                .append(alias(dialect));
        String where = normalizeWhere(whereClause);
        if (!where.isBlank()) {
            sb.append(" WHERE ").append(where);
        }
        return sb.toString();
    }

    public static String alias(String dialect) {
        return "ORACLE".equals(normalizeDialect(dialect))
                ? CUSTOM_SQL_ALIAS.toUpperCase(Locale.ROOT)
                : CUSTOM_SQL_ALIAS;
    }

    private static String normalizeWhere(String whereClause) {
        if (whereClause == null || whereClause.isBlank()) {
            return "";
        }
        return WHERE_PREFIX.matcher(whereClause.trim()).replaceFirst("").trim();
    }

    private static String normalizeDialect(String dialect) {
        return dialect == null || dialect.isBlank()
                ? "MYSQL"
                : dialect.trim().toUpperCase(Locale.ROOT);
    }
}
