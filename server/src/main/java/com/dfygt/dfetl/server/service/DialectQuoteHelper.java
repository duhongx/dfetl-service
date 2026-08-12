package com.dfygt.dfetl.server.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 按源/目标数据库方言统一 quote SQL 标识符。
 *
 * <p>调用方仍负责在拼 SQL 前完成标识符白名单校验；本类只负责一致的方言引用规则。
 */
@Component
public class DialectQuoteHelper {

    private static final Pattern SIMPLE_IDENTIFIER =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_$#]{0,127}$");

    /**
     * Quote schema/table/column 等数据库对象标识符。
     *
     * <p>契约：
     * <ul>
     *   <li>只接收简单标识符，不接收 {@code schema.table}、函数、表达式或带别名字段。</li>
     *   <li>已按当前方言显式 quote 的标识符原样返回，避免二次 quote。</li>
     *   <li>Oracle 未显式 quote 且非 mixed-case 的标识符按 Oracle 默认折叠语义转为大写后再 quote。</li>
     * </ul>
     */
    public String quoteIdentifier(String dialect, String identifier) {
        String normalized = normalizeDialect(dialect);
        String ident = normalizeIdentifierInput(identifier);
        if (isAlreadyQuoted(normalized, ident)) {
            return ident;
        }
        requireSimpleIdentifier(ident);
        String objectIdentifier = "ORACLE".equals(normalized)
                ? canonicalizeOracleObjectIdentifier(ident)
                : ident;
        return quoteRawIdentifier(normalized, objectIdentifier);
    }

    public String quoteSimpleIdentifier(String dialect, String identifier) {
        return quoteIdentifier(dialect, identifier);
    }

    public String quoteColumn(String dialect, String column) {
        return quoteIdentifier(dialect, column);
    }

    public String quoteTable(String dialect, String table) {
        return quoteIdentifier(dialect, table);
    }

    /**
     * Quote SQL alias。Alias 不是数据库对象名，Oracle 下必须保留调用方指定大小写。
     */
    public String quoteAlias(String dialect, String alias) {
        String normalized = normalizeDialect(dialect);
        String ident = normalizeIdentifierInput(alias);
        if (isAlreadyQuoted(normalized, ident)) {
            return ident;
        }
        requireSimpleIdentifier(ident);
        return quoteRawIdentifier(normalized, ident);
    }

    public String qualifyTable(String dialect, String schema, String table) {
        if (schema == null || schema.isBlank()) {
            return quoteTable(dialect, table);
        }
        return quoteTable(dialect, schema) + "." + quoteTable(dialect, table);
    }

    private String normalizeIdentifierInput(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("SQL identifier 不能为空");
        }
        return identifier.trim();
    }

    private void requireSimpleIdentifier(String ident) {
        if (!SIMPLE_IDENTIFIER.matcher(ident).matches()) {
            throw new IllegalArgumentException("SQL identifier 必须是简单标识符: " + ident);
        }
    }

    private boolean isAlreadyQuoted(String normalizedDialect, String ident) {
        return switch (normalizedDialect) {
            case "MYSQL", "DORIS" -> ident.length() >= 2 && ident.startsWith("`") && ident.endsWith("`");
            case "SQLSERVER", "MSSQL" -> ident.length() >= 2 && ident.startsWith("[") && ident.endsWith("]");
            case "POSTGRESQL", "PG" -> ident.length() >= 2 && ident.startsWith("\"") && ident.endsWith("\"");
            case "ORACLE" -> ident.length() >= 2 && ident.startsWith("\"") && ident.endsWith("\"");
            default -> ident.length() >= 2 && ident.startsWith("\"") && ident.endsWith("\"");
        };
    }

    private String canonicalizeOracleObjectIdentifier(String ident) {
        boolean hasLower = false;
        boolean hasUpper = false;
        for (int i = 0; i < ident.length(); i++) {
            char ch = ident.charAt(i);
            if (Character.isLowerCase(ch)) {
                hasLower = true;
            } else if (Character.isUpperCase(ch)) {
                hasUpper = true;
            }
        }
        // Mixed-case metadata usually represents an Oracle quoted object; preserve its exact case.
        if (hasLower && hasUpper) {
            return ident;
        }
        return ident.toUpperCase(Locale.ROOT);
    }

    private String quoteRawIdentifier(String normalizedDialect, String ident) {
        return switch (normalizedDialect) {
            case "MYSQL", "DORIS" -> "`" + ident + "`";
            case "SQLSERVER", "MSSQL" -> "[" + ident + "]";
            case "POSTGRESQL", "PG" -> "\"" + ident + "\"";
            case "ORACLE" -> "\"" + ident + "\"";
            default -> "\"" + ident + "\"";
        };
    }

    String normalizeDialect(String dialect) {
        return dialect == null || dialect.isBlank()
                ? "MYSQL"
                : dialect.trim().toUpperCase(Locale.ROOT);
    }
}
