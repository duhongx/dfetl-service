package com.dfygt.dfetl.server.service.publish;

import java.time.Instant;

/** RabbitMQ confirm/return 收敛后的单条终态。 */
public record MessageTerminalRecord(
        String messageId,
        PublishOutcome.Status status,
        String error,
        Instant terminalTime,
        int attempts) {
}
