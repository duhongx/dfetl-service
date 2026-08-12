package com.dfygt.dfetl.server.service.publish;

/** 从 message_send_record 原样领取的稳定消息。 */
public record RecoverableMessage(
        String messageId,
        Long taskId,
        Long batchId,
        Long publishLogId,
        String topic,
        String messageJson,
        boolean sendable
) {
    public RecoverableMessage(
            String messageId, Long taskId, Long batchId, Long publishLogId,
            String topic, String messageJson) {
        this(messageId, taskId, batchId, publishLogId, topic, messageJson, true);
    }
}
