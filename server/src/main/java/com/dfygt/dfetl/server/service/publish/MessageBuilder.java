package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.entity.MessagePublishConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息组装器 — 将数据行 + 字段映射 + 配置组装为标准 EtlMessage。
 * <p>
 * messageId 格式：{时间戳}{4位机器后缀}{6位序号} — 跨实例唯一。
 */
@Service
@Slf4j
public class MessageBuilder {

    private static final String VERSION = "1.0";
    private static final ZoneId ZONE_CHINA = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([^}]+)}");

    /** 4 位机器标识（基于 PID + hostname hash），用于 messageId 跨实例去重 */
    private static final String MACHINE_TAG = computeMachineTag();

    /** 序号计数器 */
    private final AtomicLong sequence = new AtomicLong(0);

    public EtlMessage build(Map<String, Object> row,
                            Map<String, String> fieldMapping,
                            MessagePublishConfig config,
                            String operation) {
        return build(row, fieldMapping, config, operation, "");
    }

    public EtlMessage build(Map<String, Object> row,
                            Map<String, String> fieldMapping,
                            MessagePublishConfig config,
                            String operation,
                            String yiLiaoJgDm) {
        return build(row, fieldMapping, config, operation, yiLiaoJgDm, null);
    }

    public EtlMessage build(Map<String, Object> row,
                            Map<String, String> fieldMapping,
                            MessagePublishConfig config,
                            String operation,
                            String yiLiaoJgDm,
                            String messageKeyTemplateOverride) {
        String messageId = generateMessageId();
        String createTime = ZonedDateTime.now(ZONE_CHINA).format(ISO_FORMATTER);
        String messageKeyTemplate = messageKeyTemplateOverride != null && !messageKeyTemplateOverride.isBlank()
                ? messageKeyTemplateOverride
                : config.getMessageKeyTemplate();
        String messageKey = resolveMessageKey(messageKeyTemplate, row, fieldMapping);

        Map<String, Object> payload = mapPayload(row, fieldMapping);

        MessageHeaders headers = new MessageHeaders(
                operation,
                messageKey,
                config.getTenantId(),
                yiLiaoJgDm,
                config.getSourceSystem(),
                generateTraceId()
        );

        return new EtlMessage(
                messageId, createTime, config.getMessageType(), config.getTopic(),
                messageKey, payload, headers, VERSION
        );
    }

    /** 构建信号消息（payload = null） */
    public EtlMessage buildSignal(MessagePublishConfig config, String signalType) {
        return buildSignal(config, signalType, "");
    }

    /** 构建信号消息（payload = null） */
    public EtlMessage buildSignal(MessagePublishConfig config, String signalType, String yiLiaoJgDm) {
        String messageId = generateMessageId();
        String createTime = ZonedDateTime.now(ZONE_CHINA).format(ISO_FORMATTER);

        MessageHeaders headers = new MessageHeaders(
                signalType, "", config.getTenantId(), yiLiaoJgDm, config.getSourceSystem(), generateTraceId()
        );

        return new EtlMessage(
                messageId, createTime, config.getMessageType(), config.getTopic(),
                "", null, headers, VERSION
        );
    }

    /**
     * 生成 messageId：{17 位时间戳}{4 位机器标识}{6 位序号} = 27 位
     * 跨实例唯一性保证：MACHINE_TAG 由 PID + hostname 派生
     */
    String generateMessageId() {
        String timestamp = ZonedDateTime.now(ZONE_CHINA).format(TIMESTAMP_FORMATTER);
        long seq = sequence.incrementAndGet();
        return timestamp + MACHINE_TAG + String.format("%06d", seq % 1_000_000);
    }

    /**
     * messageKey 模板解析 — 支持 ziduandm 占位符（反向查找源字段）。
     */
    String resolveMessageKey(String template, Map<String, Object> row,
                             Map<String, String> fieldMapping) {
        if (template == null || template.isEmpty()) return "";

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String fieldName = matcher.group(1);
            String value = lookupValue(fieldName, row, fieldMapping);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 查找字段值（多策略）：
     * 1. 通过 fieldMapping 反向查找 (ziduandm → Doris 列名)，从 row 取值
     * 2. 直接按占位符名从 row 取值
     * 3. 大小写不敏感匹配 row.keys
     * 4. 找不到 → 空字符串
     */
    private String lookupValue(String fieldName, Map<String, Object> row,
                               Map<String, String> fieldMapping) {
        // 策略 1：反向查找
        String sourceField = findSourceField(fieldName, fieldMapping);
        if (sourceField != null && row.containsKey(sourceField)) {
            return toString(row.get(sourceField));
        }
        // 策略 2：直接查
        if (row.containsKey(fieldName)) {
            return toString(row.get(fieldName));
        }
        // 策略 3：大小写不敏感匹配
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(fieldName)) {
                return toString(e.getValue());
            }
        }
        return "";
    }

    /**
     * 转换 payload key — 多策略匹配 ziduandm。
     * <p>
     * 给定：fieldMapping = (Doris 列名 → ziduandm)
     * 对 row 的每个 key，查找匹配的映射并替换为 ziduandm；找不到则保留原 key。
     * <p>
     * 匹配策略：精确 → 大小写不敏感 → 去下划线后比较
     */
    private Map<String, Object> mapPayload(Map<String, Object> row,
                                           Map<String, String> fieldMapping) {
        if (fieldMapping == null || fieldMapping.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (!isEtlSystemField(entry.getKey())) {
                    payload.put(entry.getKey(), entry.getValue());
                }
            }
            return payload;
        }
        // 预构建：normalize(dorisCol) → ziduandm（用于大小写/下划线不敏感匹配）
        Map<String, String> normalizedMapping = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fieldMapping.entrySet()) {
            normalizedMapping.put(normalize(e.getKey()), e.getValue());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String dorisCol = entry.getKey();
            if (isEtlSystemField(dorisCol)) {
                continue;
            }
            String mapped = fieldMapping.get(dorisCol);
            if (mapped == null) {
                mapped = normalizedMapping.get(normalize(dorisCol));
            }
            if (mapped != null) {
                payload.put(mapped, entry.getValue());
            } else {
                payload.put(dorisCol, entry.getValue());
            }
        }
        return payload;
    }

    /** 反向查找：ziduandm → Doris 列名 */
    private String findSourceField(String ziduandm, Map<String, String> fieldMapping) {
        if (fieldMapping == null || fieldMapping.isEmpty() || ziduandm == null) return null;
        // 精确匹配
        for (Map.Entry<String, String> e : fieldMapping.entrySet()) {
            if (ziduandm.equals(e.getValue())) return e.getKey();
        }
        // 大小写不敏感匹配
        for (Map.Entry<String, String> e : fieldMapping.entrySet()) {
            if (ziduandm.equalsIgnoreCase(e.getValue())) return e.getKey();
        }
        // normalize 后匹配
        String normTarget = normalize(ziduandm);
        for (Map.Entry<String, String> e : fieldMapping.entrySet()) {
            if (normTarget.equals(normalize(e.getValue()))) return e.getKey();
        }
        return null;
    }

    private boolean isEtlSystemField(String fieldName) {
        return fieldName != null && fieldName.toLowerCase().startsWith("_etl_");
    }

    /** 归一化：去下划线 + 转小写，便于 yi_liao_jg_dm vs yiLiaoJgDm 匹配 */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace("_", "").toLowerCase();
    }

    private String generateTraceId() {
        return "TRC" + UUID.randomUUID().toString().replace("-", "");
    }

    private String toString(Object value) {
        return value == null ? "" : value.toString();
    }

    /** 计算 4 位机器标识 — 基于 PID + hostname */
    private static String computeMachineTag() {
        try {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            String pidStr = runtimeName.split("@")[0];
            int pid = Integer.parseInt(pidStr);
            String hostname = runtimeName.contains("@") ? runtimeName.split("@")[1] : "";
            int combined = (pid * 31 + hostname.hashCode()) & 0xFFFF;
            return String.format("%04d", combined % 10000);
        } catch (Exception e) {
            // fallback：随机 4 位
            return String.format("%04d", (int) (Math.random() * 10000));
        }
    }
}
