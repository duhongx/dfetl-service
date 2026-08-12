package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.config.retry.NonRetryableException;
import com.dfygt.dfetl.server.config.retry.RetryableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream 发布器 — 按医共体通道契约发送消息到 Redis Stream。
 * <p>
 * 契约要点：
 * <ul>
 *   <li>Stream key 直接使用 topic 字段（不再拼接前缀）</li>
 *   <li>Stream body 为扁平 String→String hash，字段：messageId / createTime / routeKey / topic / messageKey / payload / headers / version</li>
 *   <li>payload 和 headers 必须是 JSON 字符串（不是嵌套 map）</li>
 *   <li>不使用 XTRIM / DEL stream（避免消费组丢失在途消息）</li>
 *   <li>同一 messageId 只 XADD 一次</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "dfetl.message-publish.transport", havingValue = "REDIS_STREAM", matchIfMissing = true)
@Slf4j
public class RedisStreamPublisher implements MessagePublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RetryTemplate redisRetryTemplate;

    public RedisStreamPublisher(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            @Qualifier("redisRetryTemplate") RetryTemplate redisRetryTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.redisRetryTemplate = redisRetryTemplate;
    }

    /**
     * 发布单条消息到 Redis Stream。
     *
     * @param message  完整 EtlMessage JSON 字符串（用于解析各字段）
     * @param topic    Stream key（直接作为 Redis Stream key）
     */
    @Override
    public void publish(String message, String topic) {
        String streamKey = topic;
        try {
            Map<String, String> body = buildStreamBody(message);
            redisRetryTemplate.execute(context -> {
                try {
                    MapRecord<String, String, String> record = StreamRecords.string(body).withStreamKey(streamKey);
                    stringRedisTemplate.opsForStream().add(record);
                    return null;
                } catch (RedisConnectionFailureException e) {
                    throw new RetryableException("Redis connection failed: " + e.getMessage(), e);
                } catch (RedisSystemException e) {
                    throw new RetryableException("Redis system error: " + e.getMessage(), e);
                }
            });
        } catch (RetryableException e) {
            log.error("RedisStreamPublisher: XADD failed after retries streamKey={}, error={}", streamKey, e.getMessage());
            throw new RuntimeException("XADD failed after retries: " + e.getMessage(), e);
        } catch (NonRetryableException e) {
            log.error("RedisStreamPublisher: XADD failed (non-retryable) streamKey={}, error={}", streamKey, e.getMessage());
            throw new RuntimeException("XADD failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("RedisStreamPublisher: XADD failed streamKey={}, error={}", streamKey, e.getMessage());
            throw new RuntimeException("XADD failed: " + e.getMessage(), e);
        }
    }

    /**
     * 仅发布信号消息。重试耗尽后静默跳过，不抛出异常。
     */
    @Override
    public void publishSignal(String message, String topic) {
        String streamKey = topic;
        try {
            Map<String, String> body = buildStreamBody(message);
            redisRetryTemplate.execute(context -> {
                try {
                    MapRecord<String, String, String> record = StreamRecords.string(body).withStreamKey(streamKey);
                    stringRedisTemplate.opsForStream().add(record);
                    return null;
                } catch (RedisConnectionFailureException e) {
                    throw new RetryableException("Redis connection failed: " + e.getMessage(), e);
                } catch (RedisSystemException e) {
                    throw new RetryableException("Redis system error: " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            log.warn("[Retry:Redis:publishSignal] streamKey={}, all retries exhausted, skipping: {}",
                    streamKey, e.getMessage());
            // silently skip - signal messages are best-effort
        }
    }

    /**
     * 批量发布到 Redis Stream。
     * 使用 executePipelined 单次网络往返完成所有 XADD，替代逐条调用。
     */
    @Override
    public PublishBatchResult publishBatch(List<String> messages, String topic) {
        if (messages == null || messages.isEmpty()) return PublishBatchResult.empty();
        String streamKey = topic;

        // Pre-build all message bodies (fail fast on serialization errors)
        List<Map<String, String>> bodies = new ArrayList<>(messages.size());
        for (String msg : messages) {
            try {
                bodies.add(buildStreamBody(msg));
            } catch (Exception e) {
                log.error("RedisStreamPublisher: buildStreamBody failed, skipping message: {}", e.getMessage());
                // Skip this message but continue with others
            }
        }
        if (bodies.isEmpty()) {
            return new PublishBatchResult(messages.stream()
                    .map(ignored -> PublishOutcome.failed(null, "Redis message serialization failed"))
                    .toList());
        }

        // Try pipeline first (single network round-trip for all messages)
        try {
            redisRetryTemplate.execute(context -> {
                try {
                    stringRedisTemplate.executePipelined(new org.springframework.data.redis.core.SessionCallback<Object>() {
                        @Override
                        @SuppressWarnings("unchecked")
                        public Object execute(org.springframework.data.redis.core.RedisOperations operations) {
                            for (Map<String, String> body : bodies) {
                                MapRecord<String, String, String> record = StreamRecords.string(body).withStreamKey(streamKey);
                                operations.opsForStream().add(record);
                            }
                            return null;
                        }
                    });
                    return null;
                } catch (RedisConnectionFailureException e) {
                    throw new RetryableException("Redis connection failed: " + e.getMessage(), e);
                } catch (RedisSystemException e) {
                    throw new RetryableException("Redis system error: " + e.getMessage(), e);
                }
            });
        } catch (RetryableException e) {
            log.error("RedisStreamPublisher: pipeline XADD failed after retries streamKey={}, count={}, error={}",
                    streamKey, bodies.size(), e.getMessage());
            throw new RuntimeException("Batch XADD failed after retries: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("RedisStreamPublisher: pipeline XADD failed streamKey={}, count={}, error={}",
                    streamKey, bodies.size(), e.getMessage());
            throw new RuntimeException("Batch XADD failed: " + e.getMessage(), e);
        }
        List<PublishOutcome> outcomes = new ArrayList<>(messages.size());
        for (int i = 0; i < bodies.size(); i++) {
            outcomes.add(PublishOutcome.sent(null));
        }
        for (int i = bodies.size(); i < messages.size(); i++) {
            outcomes.add(PublishOutcome.failed(null, "Redis message serialization failed"));
        }
        return new PublishBatchResult(outcomes);
    }

    @Override
    public Transport type() {
        return Transport.REDIS_STREAM;
    }

    /**
     * 将 EtlMessage JSON 字符串解析为医共体契约要求的扁平 hash body。
     * <p>
     * 契约字段：messageId / createTime / routeKey / topic / messageKey / payload / headers / version
     * payload 和 headers 为 JSON 字符串。
     */
    private Map<String, String> buildStreamBody(String messageJson) throws JsonProcessingException {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(messageJson, Map.class);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("messageId", str(parsed.get("messageId")));
        body.put("createTime", str(parsed.get("createTime")));
        // 兼容 routekey / routeKey / messageType
        Object routeKey = parsed.get("routekey");
        if (routeKey == null) routeKey = parsed.get("routeKey");
        if (routeKey == null) routeKey = parsed.get("messageType");
        body.put("routeKey", str(routeKey));
        body.put("topic", str(parsed.get("topic")));
        body.put("messageKey", str(parsed.get("messageKey")));
        // payload 和 headers 必须是 JSON 字符串
        Object payload = parsed.get("payload");
        body.put("payload", payload != null ? objectMapper.writeValueAsString(payload) : "{}");
        Object headers = parsed.get("headers");
        body.put("headers", headers != null ? objectMapper.writeValueAsString(headers) : "{}");
        body.put("version", str(parsed.getOrDefault("version", "1.0")));
        return body;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}
