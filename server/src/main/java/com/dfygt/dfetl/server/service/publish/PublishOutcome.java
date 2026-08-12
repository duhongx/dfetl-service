package com.dfygt.dfetl.server.service.publish;

/** 单条消息的 broker 发布终态。 */
public record PublishOutcome(String messageId, Status status, String error, int attempts) {

    public PublishOutcome {
        attempts = Math.max(1, attempts);
    }

    public enum Status {
        SENT,
        SEND_FAILED
    }

    public static PublishOutcome sent(String messageId) {
        return sent(messageId, 1);
    }

    public static PublishOutcome sent(String messageId, int attempts) {
        return new PublishOutcome(messageId, Status.SENT, null, attempts);
    }

    public static PublishOutcome failed(String messageId, String error) {
        return failed(messageId, error, 1);
    }

    public static PublishOutcome failed(String messageId, String error, int attempts) {
        return new PublishOutcome(messageId, Status.SEND_FAILED, error, attempts);
    }
}
