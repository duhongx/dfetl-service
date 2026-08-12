package com.dfygt.dfetl.server.engine.seatunnel;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SeaTunnel jobMap 凭据脱敏工具。
 * <p>
 * SeaTunnelConfBuilder 把解密后的源端/目标端密码放入 jobMap，jobMap 会被：
 * <ol>
 *   <li>序列化为 JSON 写入 {@code jobs/task_*_exec_*.json}（持久化磁盘）</li>
 *   <li>由 {@code SeaTunnelExecutorStrategy} 通过 {@code log.info} 打印到应用日志</li>
 *   <li>提交到 SeaTunnel REST（这一步必须保留明文，由调用方决定）</li>
 * </ol>
 * 第 1、2 步会沉淀凭据到日志平台和磁盘文件，本工具提供深拷贝并脱敏后的副本，
 * 用于日志/落盘场景；提交 REST 仍使用原始 jobMap。
 *
 * <p>脱敏规则：递归遍历 Map/List，匹配如下不区分大小写的字段名时把 value 替换为 {@code "***"}：
 * <ul>
 *   <li>{@code password} / {@code passwd} / {@code pwd}</li>
 *   <li>{@code access_key} / {@code accesskey} / {@code secret_key} / {@code secretkey}</li>
 *   <li>{@code token} / {@code authorization} / {@code basic_auth}</li>
 * </ul>
 */
public final class JobMapRedactor {

    /** 不区分大小写的敏感字段名（小写形式）。 */
    private static final Set<String> SENSITIVE_FIELDS;

    static {
        Set<String> s = new LinkedHashSet<>();
        s.add("password");
        s.add("passwd");
        s.add("pwd");
        s.add("access_key");
        s.add("accesskey");
        s.add("secret_key");
        s.add("secretkey");
        s.add("token");
        s.add("authorization");
        s.add("basic_auth");
        SENSITIVE_FIELDS = Set.copyOf(s);
    }

    /** 脱敏后的占位符 */
    private static final String REDACTED = "***";

    private JobMapRedactor() {}

    /**
     * 深拷贝 jobMap 并把敏感字段替换为占位符。原始 jobMap 不被修改。
     */
    public static Map<String, Object> redact(Map<String, Object> jobMap) {
        if (jobMap == null) return null;
        return deepCopyAndRedact(jobMap);
    }

    @SuppressWarnings("unchecked")
    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> m) {
            return deepCopyAndRedact((Map<String, Object>) m);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(redactValue(item));
            }
            return copy;
        }
        return value;
    }

    private static Map<String, Object> deepCopyAndRedact(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            String lowerKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (SENSITIVE_FIELDS.contains(lowerKey)) {
                copy.put(key, REDACTED);
            } else {
                copy.put(key, redactValue(entry.getValue()));
            }
        }
        return copy;
    }
}
