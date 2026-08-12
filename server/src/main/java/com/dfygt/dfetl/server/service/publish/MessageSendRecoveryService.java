package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.config.MessagePublishProperties;
import com.dfygt.dfetl.server.entity.MessageSendRecord;
import com.dfygt.dfetl.server.repository.MessageSendRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** RabbitMQ 生产者逐条发送记录恢复器。 */
@Service
@ConditionalOnProperty(name = "dfetl.message-publish.transport", havingValue = "RABBITMQ")
@RequiredArgsConstructor
@Slf4j
public class MessageSendRecoveryService {

    private final MessageSendRecordService sendRecordService;
    private final MessagePublisher publisher;
    private final MessageSendRecordRepository repository;
    private final MessagePublishRunService publishRunService;
    private final MessagePublishProperties properties;

    @Scheduled(
            fixedDelayString = "${dfetl.message-publish.recovery-scan-interval-ms:5000}",
            initialDelayString = "${dfetl.message-publish.recovery-scan-interval-ms:5000}")
    public void scheduledRecover() {
        try {
            recoverOnce();
        } catch (Exception e) {
            log.error("MessageSendRecovery: scan failed", e);
        }
    }

    /** 单次有界恢复，返回成功重发的消息数。 */
    public int recoverOnce() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(
                Math.max(1L, properties.getRecoveryStaleSeconds()), ChronoUnit.SECONDS);
        List<RecoverableMessage> claimed = sendRecordService.claimRecoverable(
                now, staleBefore,
                Math.max(1, properties.getRecoveryBatchSize()),
                Math.max(1, properties.getRecoveryMaxAttempts()));
        int recovered = 0;
        Set<Long> affectedPublishLogs = new LinkedHashSet<>();
        for (RecoverableMessage message : claimed) {
            if (message.publishLogId() != null) {
                affectedPublishLogs.add(message.publishLogId());
            }
            if (!message.sendable()) {
                continue;
            }
            try {
                PublishBatchResult result = publisher.publishBatch(
                        List.of(message.messageJson()), message.topic(),
                        new MessagePublishContext(
                                message.taskId(), message.batchId(), message.topic(),
                                message.publishLogId()));
                if (result.failedCount() == 0) {
                    recovered++;
                } else {
                    String error = result.outcomes().stream()
                            .filter(outcome -> outcome.status() != PublishOutcome.Status.SENT)
                            .map(PublishOutcome::error)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse("RabbitMQ recovery publish failed");
                    sendRecordService.scheduleRecoveryFailure(
                            message.messageId(), error,
                            properties.getRecoveryMaxAttempts(),
                            properties.getRecoveryBaseBackoffMs());
                }
            } catch (Exception e) {
                sendRecordService.scheduleRecoveryFailure(
                        message.messageId(), e.getMessage(),
                        properties.getRecoveryMaxAttempts(),
                        properties.getRecoveryBaseBackoffMs());
            }
        }
        affectedPublishLogs.forEach(this::refreshPublishRun);
        return recovered;
    }

    private void refreshPublishRun(Long publishLogId) {
        List<MessageSendRecord> records = repository.findByPublishLogId(publishLogId);
        if (records.isEmpty()) return;
        int sent = (int) records.stream()
                .filter(record -> MessageSendRecordService.STATUS_SENT.equals(record.getSendStatus()))
                .count();
        int finalFailed = (int) records.stream()
                .filter(record -> MessageSendRecordService.STATUS_FAILED_FINAL.equals(record.getSendStatus()))
                .count();
        int pending = records.size() - sent - finalFailed;
        String status;
        if (sent == records.size()) {
            status = "SUCCESS";
        } else if (pending > 0) {
            status = "WAIT_RETRY";
        } else if (sent > 0) {
            status = "PARTIAL";
        } else {
            status = "FAILED_FINAL";
        }
        String error = records.stream()
                .map(MessageSendRecord::getLastError)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        try {
            publishRunService.complete(publishLogId, sent, status, error, null);
        } catch (Exception e) {
            log.warn("MessageSendRecovery: publish run refresh failed publishLogId={}, error={}",
                    publishLogId, e.getMessage());
        }
    }
}
