package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.config.retry.NonRetryableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RabbitMQ 消息序列化器 — 将 EtlMessage JSON 转换为医共体契约的 8 字段扁平 JSON。
 * 与 RedisStreamPublisher.buildStreamBody 逻辑一致，确保消费端无需区分传输通道。
 */
@Component
@ConditionalOnProperty(name = "dfetl.message-publish.transport", havingValue = "RABBITMQ")
public class RabbitMqMessageSerializer {

    private final ObjectMapper objectMapper;

    public RabbitMqMessageSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 序列化 EtlMessage JSON 为 8 字段扁平 JSON 字节数组。
     * 字段：messageId / createTime / routeKey / topic / messageKey / payload / headers / version
     * payload 和 headers 为 JSON 对象，与 AMQP JSON body 消费契约保持一致。
     *
     * @throws NonRetryableException 当 JSON 解析失败时
     */
    public byte[] serialize(String messageJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(messageJson, Map.class);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("messageId", str(parsed.get("messageId")));
            body.put("createTime", str(parsed.get("createTime")));
            // 兼容 routekey / routeKey / messageType
            Object routeKey = parsed.get("routekey");
            if (routeKey == null) routeKey = parsed.get("routeKey");
            if (routeKey == null) routeKey = parsed.get("messageType");
            body.put("routeKey", str(routeKey));
            body.put("topic", str(parsed.get("topic")));
            body.put("messageKey", str(parsed.get("messageKey")));
            Object payload = parsed.get("payload");
            body.put("payload", payload != null ? payload : Map.of());
            Object headers = parsed.get("headers");
            body.put("headers", headers != null ? headers : Map.of());
            body.put("version", str(parsed.getOrDefault("version", "1.0")));
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new NonRetryableException("Failed to serialize message for RabbitMQ: " + e.getMessage(), e);
        }
    }

    /**
     * 从 messageJson 中提取 routeKey 字段值。
     * 兼容 routekey / routeKey / messageType 三种字段名。
     *
     * @throws NonRetryableException 当 JSON 解析失败时
     */
    public String extractRouteKey(String messageJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(messageJson, Map.class);
            Object routeKey = parsed.get("routekey");
            if (routeKey == null) routeKey = parsed.get("routeKey");
            if (routeKey == null) routeKey = parsed.get("messageType");
            return routeKey != null ? routeKey.toString() : "";
        } catch (JsonProcessingException e) {
            throw new NonRetryableException("Failed to extract routeKey: " + e.getMessage(), e);
        }
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}
