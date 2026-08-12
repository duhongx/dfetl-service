package com.dfygt.dfetl.server.common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行错误信息的统一安全出口。
 *
 * <p>Doris load error 会把整条源记录拼在 {@code src line} 后面。该内容可能包含患者身份、
 * 诊疗信息等敏感数据，只允许保留错误原因，不允许进入日志、元数据库或 API。</p>
 */
public final class ExecutionErrorSanitizer {

    private static final Pattern SOURCE_ROW = Pattern.compile(
            "(?i)\\b(?:src\\s+line|source\\s+row)\\b.*$");
    private static final Pattern LEGACY_SAMPLE = Pattern.compile("^\\s*样例行\\s*[:：].*$");
    private static final String REDACTED = "[source row redacted]";

    private ExecutionErrorSanitizer() {
    }

    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        List<String> safeLines = new ArrayList<>();
        for (String line : raw.split("\\R", -1)) {
            if (LEGACY_SAMPLE.matcher(line).matches()) {
                continue;
            }
            Matcher matcher = SOURCE_ROW.matcher(line);
            if (matcher.find()) {
                String prefix = line.substring(0, matcher.start()).stripTrailing();
                safeLines.add(prefix.isBlank() ? REDACTED : prefix + " " + REDACTED);
            } else {
                safeLines.add(line);
            }
        }
        return String.join("\n", safeLines).stripTrailing();
    }
}
