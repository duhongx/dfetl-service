package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.entity.MessageSendRecord;
import com.dfygt.dfetl.server.repository.MessageSendRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * dfetl 本地逐条消息发送记录。
 *
 * <p>该服务只记录生产者侧闭环：发送前 SENDING，RabbitMQ confirm ack 后 SENT，
 * nack/mandatory return/同步发送异常后 SEND_FAILED。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageSendRecordService {

    static final String CHANNEL_RABBITMQ = "RABBITMQ";
    static final String STATUS_SENDING = "SENDING";
    static final String STATUS_SENT = "SENT";
    static final String STATUS_SEND_FAILED = "SEND_FAILED";
    static final String STATUS_WAIT_RETRY = "WAIT_RETRY";
    static final String STATUS_FAILED_FINAL = "FAILED_FINAL";
    static final String EXTERNAL_WAIT_SEND = "WAIT_SEND";
    static final String DEFAULT_PAYLOAD_TYPE = "com.dfygt.dfetl.server.service.publish.EtlMessage";

    private final MessageSendRecordRepository repository;
    private final MessageSendRecordBatchStore batchStore;
    private final ObjectMapper objectMapper;

    /**
     * 在一个 PostgreSQL 事务内领取可恢复记录。领取后立即刷新 send_start_time，其他实例只会
     * 跳过这些行或看见未过期的 SENDING，不会同时重发同一条消息。
     */
    @Transactional
    public List<RecoverableMessage> claimRecoverable(
            Instant now, Instant staleBefore, int limit, int maxAttempts) {
        List<MessageSendRecord> candidates = repository.lockRecoverableForUpdate(
                now, staleBefore, Math.max(1, limit));
        List<RecoverableMessage> claimed = new ArrayList<>(candidates.size());
        for (MessageSendRecord record : candidates) {
            int attempts = record.getSendAttempts() == null ? 0 : record.getSendAttempts();
            if (attempts >= Math.max(1, maxAttempts) || isNonRetryable(record.getLastError())) {
                record.setSendStatus(STATUS_FAILED_FINAL);
                record.setNextRetryTime(null);
                claimed.add(new RecoverableMessage(
                        record.getMessageId(), record.getTaskId(), record.getBatchId(),
                        record.getPublishLogId(), record.getTopic(), record.getMessageJson(), false));
                continue;
            }
            record.setSendStatus(STATUS_SENDING);
            record.setSendStartTime(now);
            record.setNextRetryTime(null);
            claimed.add(new RecoverableMessage(
                    record.getMessageId(), record.getTaskId(), record.getBatchId(),
                    record.getPublishLogId(), record.getTopic(), record.getMessageJson(), true));
        }
        repository.saveAll(candidates);
        return List.copyOf(claimed);
    }

    /** 把本次恢复失败收敛为指数退避重试或不可再重试的最终失败。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleRecoveryFailure(
            String messageId, String errorMessage, int maxAttempts, long baseBackoffMs) {
        repository.findByMessageId(messageId).ifPresentOrElse(record -> {
            int attempts = record.getSendAttempts() == null ? 0 : record.getSendAttempts();
            record.setLastError(truncate(errorMessage));
            if (attempts >= Math.max(1, maxAttempts) || isNonRetryable(errorMessage)) {
                record.setSendStatus(STATUS_FAILED_FINAL);
                record.setNextRetryTime(null);
            } else {
                record.setSendStatus(STATUS_WAIT_RETRY);
                long exponent = Math.min(20L, Math.max(0L, attempts - 1L));
                long multiplier = 1L << exponent;
                long delayMs;
                try {
                    delayMs = Math.multiplyExact(Math.max(1L, baseBackoffMs), multiplier);
                } catch (ArithmeticException ignored) {
                    delayMs = Long.MAX_VALUE;
                }
                delayMs = Math.min(delayMs, 3_600_000L);
                record.setNextRetryTime(Instant.now().plusMillis(delayMs));
            }
            repository.save(record);
        }, () -> log.warn("MessageSendRecord: recovery failure ignored, messageId not found={}", messageId));
    }

    /**
     * 在接触 RabbitMQ 前解析并一次事务写入整批 SENDING 记录。
     */
    public List<String> recordRabbitSendingBatch(List<RabbitSendingRequest> requests) {
        if (requests == null || requests.isEmpty()) return List.of();
        Instant sendStartTime = Instant.now();
        List<RabbitSendingRecord> records = new ArrayList<>(requests.size());
        Set<String> messageIds = new HashSet<>(requests.size());
        for (RabbitSendingRequest request : requests) {
            RabbitSendingRecord record = toSendingRecord(request, sendStartTime);
            if (!messageIds.add(record.messageId())) {
                throw new IllegalArgumentException("duplicate messageId in sending batch: " + record.messageId());
            }
            records.add(record);
        }
        batchStore.upsertSendingBatch(records);
        return records.stream().map(RabbitSendingRecord::messageId).toList();
    }

    /**
     * 一次事务回写整批 RabbitMQ 终态；批量 SQL 异常时退回既有单条路径。
     */
    public void markTerminalBatch(List<PublishOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return;
        Instant terminalTime = Instant.now();
        List<MessageTerminalRecord> records = outcomes.stream()
                .filter(outcome -> outcome != null
                        && outcome.messageId() != null
                        && !outcome.messageId().isBlank())
                .map(outcome -> new MessageTerminalRecord(
                        outcome.messageId(), outcome.status(), truncate(outcome.error()),
                        terminalTime, outcome.attempts()))
                .toList();
        if (records.isEmpty()) return;
        try {
            batchStore.updateTerminalBatch(records);
        } catch (RuntimeException batchFailure) {
            log.error("MessageSendRecord: batch terminal update failed, falling back to single writes, count={}, error={}",
                    records.size(), batchFailure.getMessage());
            for (PublishOutcome outcome : outcomes) {
                if (outcome == null || outcome.messageId() == null || outcome.messageId().isBlank()) continue;
                if (outcome.status() == PublishOutcome.Status.SENT) {
                    markSent(outcome.messageId());
                } else {
                    markFailed(outcome.messageId(), outcome.error());
                }
            }
        }
    }

    /**
     * 发送 RabbitMQ 前创建或更新本地记录。该方法失败时应中止发送，避免消息已投递但本地无记录。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String recordRabbitSending(String messageJson, String exchangeName, String routeKey,
                                      Long taskId, Long batchId) {
        JsonNode root = parseMessage(messageJson);
        String messageId = requiredText(root, "messageId");
        MessageSendRecord record = repository.findByMessageId(messageId).orElseGet(MessageSendRecord::new);

        record.setMessageId(messageId);
        record.setTaskId(taskId);
        record.setBatchId(batchId);
        record.setChannelMode(CHANNEL_RABBITMQ);
        record.setExchangeName(emptyToDefault(exchangeName, ""));
        record.setRouteKey(emptyToDefault(routeKey, text(root, "routeKey")));
        record.setTopic(text(root, "topic"));
        record.setMessageKey(text(root, "messageKey"));
        record.setPayloadType(DEFAULT_PAYLOAD_TYPE);
        record.setMessageJson(messageJson);
        record.setPayloadJson(jsonObject(root.get("payload")));
        record.setHeadersJson(jsonObject(root.get("headers")));

        JsonNode headers = root.get("headers");
        if (headers != null && headers.isObject()) {
            record.setBusinessKey(text(headers, "businessKey"));
            record.setTenantId(text(headers, "tenantId"));
            record.setSourceSystem(text(headers, "sourceSystem"));
            record.setTraceId(text(headers, "traceId"));
        } else {
            record.setBusinessKey(null);
            record.setTenantId(null);
            record.setSourceSystem(null);
            record.setTraceId(null);
        }

        record.setSendStatus(STATUS_SENDING);
        record.setSendAttempts((record.getSendAttempts() == null ? 0 : record.getSendAttempts()) + 1);
        record.setSendStartTime(Instant.now());
        record.setBrokerConfirmTime(null);
        record.setSentTime(null);
        record.setNextRetryTime(null);
        record.setLastError(null);
        record.setExternalRecordStatus(null);
        record.setExternalRecordTime(null);
        record.setExternalRecordError(null);
        repository.save(record);
        return messageId;
    }

    private RabbitSendingRecord toSendingRecord(RabbitSendingRequest request, Instant sendStartTime) {
        JsonNode root = parseMessage(request.messageJson());
        String messageId = requiredText(root, "messageId");
        JsonNode headers = root.get("headers");
        return new RabbitSendingRecord(
                messageId,
                request.taskId(),
                request.batchId(),
                request.publishLogId(),
                emptyToDefault(request.exchangeName(), ""),
                emptyToDefault(request.routeKey(), text(root, "routeKey")),
                text(root, "topic"),
                text(root, "messageKey"),
                headers != null && headers.isObject() ? text(headers, "businessKey") : null,
                headers != null && headers.isObject() ? text(headers, "tenantId") : null,
                headers != null && headers.isObject() ? text(headers, "sourceSystem") : null,
                headers != null && headers.isObject() ? text(headers, "traceId") : null,
                DEFAULT_PAYLOAD_TYPE,
                request.messageJson(),
                jsonObject(root.get("payload")),
                jsonObject(headers),
                sendStartTime);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(String messageId) {
        if (messageId == null || messageId.isBlank()) return;
        repository.findByMessageId(messageId).ifPresentOrElse(record -> {
            if (STATUS_SEND_FAILED.equals(record.getSendStatus())) {
                return;
            }
            Instant now = Instant.now();
            record.setSendStatus(STATUS_SENT);
            record.setBrokerConfirmTime(now);
            record.setSentTime(now);
            record.setLastError(null);
            if (!STATUS_SENT.equals(record.getExternalRecordStatus())) {
                record.setExternalRecordStatus(EXTERNAL_WAIT_SEND);
                record.setExternalRecordTime(null);
                record.setExternalRecordError(null);
            }
            repository.save(record);
        }, () -> log.warn("MessageSendRecord: markSent ignored, messageId not found={}", messageId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String messageId, String errorMessage) {
        if (messageId == null || messageId.isBlank()) return;
        repository.findByMessageId(messageId).ifPresentOrElse(record -> {
            record.setSendStatus(STATUS_SEND_FAILED);
            record.setBrokerConfirmTime(Instant.now());
            record.setLastError(truncate(errorMessage));
            repository.save(record);
        }, () -> log.warn("MessageSendRecord: markFailed ignored, messageId not found={}, error={}",
                messageId, errorMessage));
    }

    private JsonNode parseMessage(String messageJson) {
        try {
            return objectMapper.readTree(messageJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse message JSON for send record: " + e.getMessage(), e);
        }
    }

    private String jsonObject(JsonNode node) {
        if (node == null || node.isNull()) return "{}";
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize message field JSON: " + e.getMessage(), e);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Message field is required: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return "";
        return node.get(field).asText("");
    }

    private static String emptyToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > 2000 ? value.substring(0, 2000) + "...(truncated)" : value;
    }

    static boolean isNonRetryable(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) return false;
        String normalized = errorMessage.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("NO_ROUTE")
                || normalized.contains("REPLYCODE=312")
                || normalized.contains("MESSAGE FIELD IS REQUIRED")
                || normalized.contains("FAILED TO PARSE MESSAGE JSON")
                || normalized.contains("SERIALIZ")
                || normalized.contains("CONTRACT");
    }
}
