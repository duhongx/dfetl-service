package com.dfygt.dfetl.server.service;

import java.util.Locale;
import java.util.regex.Pattern;

public final class CustomSqlValidator {

    private static final Pattern DANGEROUS_KEYWORDS = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|alter|truncate|create|grant|revoke|call|exec|execute|into|outfile|dumpfile)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCKING_READ = Pattern.compile(
            "\\bfor\\s+(update|share|no\\s+key\\s+update|key\\s+share)\\b|\\block\\s+in\\s+share\\s+mode\\b|\\b(updlock|xlock|holdlock)\\b",
            Pattern.CASE_INSENSITIVE);

    private CustomSqlValidator() {
    }

    public static String requireReadOnlySelect(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("自定义 SQL 不能为空");
        }
        String trimmed = sql.trim();
        rejectDangerousCommentContent(trimmed);
        String normalized = trimSingleTrailingSemicolon(trimmed);
        String policySql = normalizePolicyWhitespace(maskNonCode(normalized));
        String lower = policySql.trim().toLowerCase(Locale.ROOT);
        if (!lower.matches("(?s)^(select|with)\\b.*")) {
            throw new IllegalArgumentException("自定义 SQL 只允许 SELECT / WITH SELECT 查询");
        }
        if (DANGEROUS_KEYWORDS.matcher(policySql).find()) {
            throw new IllegalArgumentException("自定义 SQL 不允许包含写入、DDL 或执行类关键字");
        }
        if (LOCKING_READ.matcher(policySql).find()) {
            throw new IllegalArgumentException("自定义 SQL 不允许包含锁定读关键字");
        }
        return normalized;
    }

    private static void rejectDangerousCommentContent(String sql) {
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                int end = i + 2;
                while (end < sql.length() && sql.charAt(end) != '\n' && sql.charAt(end) != '\r') {
                    end++;
                }
                rejectIfDangerousComment(sql.substring(i + 2, end));
                i = end;
            } else if (c == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                String comment = end >= 0 ? sql.substring(i + 2, end) : sql.substring(i + 2);
                rejectIfDangerousComment(comment);
                i = end >= 0 ? end + 2 : sql.length();
            } else {
                i++;
            }
        }
    }

    private static void rejectIfDangerousComment(String comment) {
        String policy = normalizePolicyWhitespace(comment);
        if (DANGEROUS_KEYWORDS.matcher(policy).find() || LOCKING_READ.matcher(policy).find()) {
            throw new IllegalArgumentException("自定义 SQL 注释中不允许包含危险关键字");
        }
    }

    private static String trimSingleTrailingSemicolon(String sql) {
        String masked = maskNonCode(sql);
        int first = masked.indexOf(';');
        if (first < 0) {
            return sql;
        }
        if (masked.indexOf(';', first + 1) >= 0) {
            throw new IllegalArgumentException("自定义 SQL 只允许单条 SELECT 语句，不能包含多语句分号");
        }
        if (!masked.substring(first + 1).trim().isEmpty()) {
            throw new IllegalArgumentException("自定义 SQL 只允许单条 SELECT 语句，不能包含多语句分号");
        }
        return sql.substring(0, first).trim();
    }

    private static String maskNonCode(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (c == '\'') {
                i = maskSingleQuoted(sql, out, i);
            } else if (c == '"') {
                i = maskDoubleQuoted(sql, out, i);
            } else if (c == '-' && next == '-') {
                i = maskLineComment(sql, out, i);
            } else if (c == '/' && next == '*') {
                i = maskBlockComment(sql, out, i);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String normalizePolicyWhitespace(String sql) {
        return sql
                .replace('\u00A0', ' ')
                .replace('\u1680', ' ')
                .replace('\u180E', ' ')
                .replace('\u2028', ' ')
                .replace('\u2029', ' ')
                .replace('\u202F', ' ')
                .replace('\u205F', ' ')
                .replace('\u3000', ' ')
                .replaceAll("[\\u2000-\\u200A]", " ");
    }

    private static int maskSingleQuoted(String sql, StringBuilder out, int start) {
        out.append(' ');
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            out.append(c == '\n' || c == '\r' ? c : ' ');
            if (c == '\'') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append(' ');
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int maskDoubleQuoted(String sql, StringBuilder out, int start) {
        out.append(' ');
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            out.append(c == '\n' || c == '\r' ? c : ' ');
            if (c == '"') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                    out.append(' ');
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int maskLineComment(String sql, StringBuilder out, int start) {
        out.append("  ");
        int i = start + 2;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            out.append(c == '\n' || c == '\r' ? c : ' ');
            i++;
            if (c == '\n' || c == '\r') {
                break;
            }
        }
        return i;
    }

    private static int maskBlockComment(String sql, StringBuilder out, int start) {
        out.append("  ");
        int i = start + 2;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            out.append(c == '\n' || c == '\r' ? c : ' ');
            if (c == '*' && next == '/') {
                out.append(' ');
                return i + 2;
            }
            i++;
        }
        return i;
    }
}
