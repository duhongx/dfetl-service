package com.dfygt.dfetl.server.service.publish;

import java.util.List;

/**
 * 统一消息发布接口 — 传输层抽象。
 */
public interface MessagePublisher {

    /**
     * 发布单条消息。失败时抛出 RuntimeException。
     */
    void publish(String message, String topic);

    /** 发布信号消息；broker 未确认时必须抛出异常。 */
    void publishSignal(String message, String topic);

    /**
     * 批量发布消息并返回每条消息的最终发送状态。
     */
    PublishBatchResult publishBatch(List<String> messages, String topic);

    /**
     * 批量发布消息，并携带任务/批次上下文。默认实现保持旧传输层行为。
     */
    default PublishBatchResult publishBatch(
            List<String> messages, String topic, MessagePublishContext context) {
        return publishBatch(messages, topic);
    }

    /**
     * 返回当前实现对应的传输方式枚举。
     */
    Transport type();
}
