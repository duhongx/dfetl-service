package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.config.MessagePublishProperties;
import com.dfygt.dfetl.server.config.retry.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RabbitMQ 实现 — Topic Exchange 路由、持久化、Confirm/Return 终态收敛。
 * <p>
 * 路由规则：
 * <ul>
 *   <li>Exchange 名称 = routeKey 第一个 "_" 前的部分（如 YL_ZHIGONGXX → Exchange "YL"）</li>
 *   <li>Routing Key = 完整 routeKey 字段值</li>
 *   <li>Exchange 类型 = topic, durable=true, autoDelete=false</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "dfetl.message-publish.transport", havingValue = "RABBITMQ")
@Slf4j
public class RabbitMqPublisher implements MessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqMessageSerializer messageSerializer;
    private final RetryTemplate rabbitmqRetryTemplate;
    private final MessageSendRecordService sendRecordService;
    private final long confirmTimeoutMs;

    /** 已声明 Exchange 的本地缓存（避免重复声明） */
    private final Set<String> declaredExchanges = ConcurrentHashMap.newKeySet();

    public RabbitMqPublisher(
            RabbitTemplate rabbitTemplate,
            RabbitMqMessageSerializer messageSerializer,
            @Qualifier("rabbitmqRetryTemplate") RetryTemplate rabbitmqRetryTemplate,
            MessageSendRecordService sendRecordService,
            MessagePublishProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageSerializer = messageSerializer;
        this.rabbitmqRetryTemplate = rabbitmqRetryTemplate;
        this.sendRecordService = sendRecordService;
        this.confirmTimeoutMs = Math.max(1L, properties.getRabbitConfirmTimeoutMs());
    }

    @Override
    public void publish(String message, String topic) {
        PublishBatchResult result = publishBatch(
                List.of(message), topic, new MessagePublishContext(null, null, topic));
        if (result.failedCount() > 0) {
            throw new IllegalStateException(result.outcomes().get(0).error());
        }
    }

    @Override
    public void publishSignal(String message, String topic) {
        publish(message, topic);
    }

    @Override
    public PublishBatchResult publishBatch(List<String> messages, String topic) {
        return publishBatch(messages, topic, new MessagePublishContext(null, null, topic));
    }

    @Override
    public PublishBatchResult publishBatch(
            List<String> messages, String topic, MessagePublishContext publishContext) {
        if (messages == null || messages.isEmpty()) return PublishBatchResult.empty();

        List<PublishOutcome> outcomes = new ArrayList<>(messages.size());
        List<PreparedPublish> prepared = new ArrayList<>(messages.size());
        for (String message : messages) {
            try {
                prepared.add(prepare(message, publishContext));
            } catch (RuntimeException e) {
                outcomes.add(PublishOutcome.failed(null, e.getMessage()));
            }
        }

        if (prepared.isEmpty()) {
            return new PublishBatchResult(outcomes);
        }

        List<String> messageIds;
        try {
            messageIds = sendRecordService.recordRabbitSendingBatch(prepared.stream()
                    .map(PreparedPublish::auditRequest)
                    .toList());
            if (messageIds.size() != prepared.size()) {
                throw new IllegalStateException("RabbitMQ audit batch result size mismatch: requested="
                        + prepared.size() + ", persisted=" + messageIds.size());
            }
        } catch (RuntimeException e) {
            for (int i = 0; i < prepared.size(); i++) {
                outcomes.add(PublishOutcome.failed(null, e.getMessage()));
            }
            return new PublishBatchResult(outcomes);
        }

        List<PendingPublish> pending = new ArrayList<>(prepared.size());
        for (int i = 0; i < prepared.size(); i++) {
            PreparedPublish item = prepared.get(i);
            String messageId = messageIds.get(i);
            AtomicInteger attempts = new AtomicInteger();
            try {
                pending.add(sendForConfirm(item, messageId, attempts));
            } catch (RuntimeException e) {
                outcomes.add(PublishOutcome.failed(messageId, e.getMessage(), attempts.get()));
            }
        }
        for (PendingPublish item : pending) {
            outcomes.add(awaitTerminal(item));
        }
        markTerminalBatchSafely(outcomes);
        return new PublishBatchResult(outcomes);
    }

    @Override
    public Transport type() {
        return Transport.RABBITMQ;
    }

    // ── 内部方法 ──────────────────────────────────────────────────────────

    /**
     * 从 routeKey 提取 Exchange 名称：取第一个 "_" 前的部分。
     * 若无 "_" 或 "_" 在首位，使用完整 routeKey 作为 Exchange 名称。
     */
    String deriveExchangeName(String routeKey) {
        return RabbitMqRouteResolver.exchangeName(routeKey);
    }

    /**
     * 确保 Exchange 已声明（幂等，带本地缓存）。
     * 声明 durable, non-autoDelete Topic Exchange。
     */
    void ensureExchangeDeclared(String exchangeName) {
        if (declaredExchanges.contains(exchangeName)) return;
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare(exchangeName, "topic", true, false, null);
            return null;
        });
        declaredExchanges.add(exchangeName);
    }

    private PreparedPublish prepare(String message, MessagePublishContext publishContext) {
        String routeKey = messageSerializer.extractRouteKey(message);
        String exchangeName = deriveExchangeName(routeKey);
        byte[] body = messageSerializer.serialize(message);
        String outgoingMessageJson = new String(body, StandardCharsets.UTF_8);
        return new PreparedPublish(
                body,
                exchangeName,
                routeKey,
                new RabbitSendingRequest(
                        outgoingMessageJson,
                        exchangeName,
                        routeKey,
                        publishContext != null ? publishContext.taskId() : null,
                        publishContext != null ? publishContext.batchId() : null,
                        publishContext != null ? publishContext.publishLogId() : null));
    }

    private PendingPublish sendForConfirm(
            PreparedPublish prepared, String messageId, AtomicInteger attempts) {
        String routeKey = prepared.routeKey();
        String exchangeName = prepared.exchangeName();
        ensureExchangeDeclared(exchangeName);

        return rabbitmqRetryTemplate.execute(context -> {
            try {
                attempts.incrementAndGet();
                CorrelationData correlationData = new CorrelationData(messageId);
                rabbitTemplate.send(
                        exchangeName,
                        routeKey,
                        buildMessage(prepared.body(), messageId),
                        correlationData);
                return new PendingPublish(
                        messageId,
                        correlationData,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(confirmTimeoutMs),
                        attempts.get());
            } catch (AmqpConnectException e) {
                String error = "RabbitMQ connection failed: " + e.getMessage();
                throw new RetryableException(error, e);
            } catch (AmqpIOException e) {
                String error = "RabbitMQ IO error: " + e.getMessage();
                throw new RetryableException(error, e);
            }
        });
    }

    private PublishOutcome awaitTerminal(PendingPublish pending) {
        String messageId = pending.messageId();
        try {
            // get(0, unit) still returns an already-completed future. Do not reject a
            // confirmed message merely because earlier items in the same batch used up
            // this item's original waiting window before the loop reached it.
            long remainingNanos = Math.max(0L, pending.deadlineNanos() - System.nanoTime());
            CorrelationData.Confirm confirm = pending.correlationData().getFuture()
                    .get(remainingNanos, TimeUnit.NANOSECONDS);
            ReturnedMessage returned = pending.correlationData().getReturned();
            if (returned != null) {
                String error = "RabbitMQ returned: replyCode=" + returned.getReplyCode()
                        + ", replyText=" + returned.getReplyText()
                        + ", exchange=" + returned.getExchange()
                        + ", routingKey=" + returned.getRoutingKey();
                return PublishOutcome.failed(messageId, error, pending.attempts());
            }
            if (confirm == null || !confirm.isAck()) {
                String reason = confirm == null ? "confirm result is null" : confirm.getReason();
                String error = "RabbitMQ nack: " + reason;
                return PublishOutcome.failed(messageId, error, pending.attempts());
            }
            return PublishOutcome.sent(messageId, pending.attempts());
        } catch (TimeoutException e) {
            String error = "RabbitMQ confirm timeout after " + confirmTimeoutMs + "ms";
            return PublishOutcome.failed(messageId, error, pending.attempts());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String error = "RabbitMQ confirm interrupted";
            return PublishOutcome.failed(messageId, error, pending.attempts());
        } catch (ExecutionException e) {
            String error = "RabbitMQ confirm failed: "
                    + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return PublishOutcome.failed(messageId, error, pending.attempts());
        }
    }

    /**
     * 构建 AMQP Message（带 correlationId）：用于 ConfirmCallback 识别 nack 消息。
     */
    private Message buildMessage(byte[] body, String correlationId) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setContentType("application/json");
        props.setCorrelationId(correlationId);
        return new Message(body, props);
    }

    private void markTerminalBatchSafely(List<PublishOutcome> outcomes) {
        try {
            sendRecordService.markTerminalBatch(outcomes);
        } catch (Exception e) {
            log.error("[RabbitMQ:Confirm] failed to persist terminal batch count={}: {}",
                    outcomes.size(), e.getMessage());
        }
    }

    private record PreparedPublish(
            byte[] body,
            String exchangeName,
            String routeKey,
            RabbitSendingRequest auditRequest) {
    }

    private record PendingPublish(
            String messageId, CorrelationData correlationData, long deadlineNanos, int attempts) {
    }
}
