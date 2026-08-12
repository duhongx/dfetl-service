package com.dfygt.dfetl.server.service.publish;

/**
 * 消息发布上下文，用于把逐条发送记录关联回同步任务和批次。
 */
public record MessagePublishContext(Long taskId, Long batchId, String topic, Long publishLogId) {

    public MessagePublishContext(Long taskId, Long batchId, String topic) {
        this(taskId, batchId, topic, null);
    }
}
