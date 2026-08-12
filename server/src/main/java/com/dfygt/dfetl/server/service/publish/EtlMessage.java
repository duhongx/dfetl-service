package com.dfygt.dfetl.server.service.publish;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 标准消息体 — 发送到 Redis Pub/Sub 的 JSON 结构。
 */
public record EtlMessage(
    String messageId,
    String createTime,           // ISO-8601 with timezone, e.g. "2026-05-22T14:30:00+08:00"
    @JsonProperty("routeKey")
    String messageType,          // 序列化为 routeKey（医共体消费端按此字段路由）
    String topic,                // e.g. "base.department"
    String messageKey,           // e.g. "YGT330106H001:00:KS001"
    Map<String, Object> payload, // 业务数据
    MessageHeaders headers,
    String version               // always "1.0"
) {}
