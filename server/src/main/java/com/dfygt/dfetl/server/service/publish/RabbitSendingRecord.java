package com.dfygt.dfetl.server.service.publish;

import java.time.Instant;

/** 已解析、可直接批量写入 message_send_record 的 SENDING 记录。 */
public record RabbitSendingRecord(
        String messageId,
        Long taskId,
        Long batchId,
        Long publishLogId,
        String exchangeName,
        String routeKey,
        String topic,
        String messageKey,
        String businessKey,
        String tenantId,
        String sourceSystem,
        String traceId,
        String payloadType,
        String messageJson,
        String payloadJson,
        String headersJson,
        Instant sendStartTime) {

    public RabbitSendingRecord(String messageId, Long taskId, Long batchId,
                               String exchangeName, String routeKey, String topic,
                               String messageKey, String businessKey, String tenantId,
                               String sourceSystem, String traceId, String payloadType,
                               String messageJson, String payloadJson, String headersJson,
                               Instant sendStartTime) {
        this(messageId, taskId, batchId, null, exchangeName, routeKey, topic,
                messageKey, businessKey, tenantId, sourceSystem, traceId, payloadType,
                messageJson, payloadJson, headersJson, sendStartTime);
    }
}
