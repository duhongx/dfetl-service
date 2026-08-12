package com.dfygt.dfetl.server.service.publish;

/** RabbitMQ 发送前需要持久化的原始审计输入。 */
public record RabbitSendingRequest(
        String messageJson,
        String exchangeName,
        String routeKey,
        Long taskId,
        Long batchId,
        Long publishLogId) {

    public RabbitSendingRequest(String messageJson, String exchangeName, String routeKey,
                                Long taskId, Long batchId) {
        this(messageJson, exchangeName, routeKey, taskId, batchId, null);
    }
}
