package com.dfygt.dfetl.server.service.publish;

import com.dfygt.dfetl.server.entity.MessageSendRecord;
import com.dfygt.dfetl.server.medical.MedicalRegistryConfig;
import com.dfygt.dfetl.server.repository.MessageSendRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

/**
 * RabbitMQ 发送成功后，异步批量补写医共体 msg_send 发送成功记录。
 */
@Service
@ConditionalOnProperty(name = "dfetl.message-publish.transport", havingValue = "RABBITMQ")
@RequiredArgsConstructor
@Slf4j
public class MedicalMsgSendRecordWriter {

    private static final int BATCH_SIZE = 500;
    private static final String SOURCE_APPLICATION = "dfetl-server";
    private static final String EXTERNAL_SENT = "SENT";
    private static final String EXTERNAL_SEND_FAILED = "SEND_FAILED";

    private static final String INSERT_SQL = """
            INSERT INTO msg_send (
              zhujianid, message_id, topic, route_key, message_key,
              payload_json, payload_type, headers_json, channel_mode, send_status,
              send_attempts, next_retry_time, sent_time, last_error, source_application,
              chuangjiansj, xiugaisj, tenantid
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final MedicalRegistryConfig medicalRegistryConfig;
    private final MessageSendRecordRepository repository;
    private final MessageSendRecordBatchStore batchStore;

    @Scheduled(fixedDelay = 3_000, initialDelay = 5_000)
    public void flushPendingScheduled() {
        try {
            flushPendingOnce();
        } catch (Exception e) {
            log.warn("MedicalMsgSendRecordWriter: scheduled flush failed: {}", e.getMessage());
        }
    }

    void flushPendingOnce() {
        List<MessageSendRecord> records =
                repository.findPendingExternalRecords(PageRequest.of(0, BATCH_SIZE));
        if (records.isEmpty()) {
            return;
        }

        if (!medicalRegistryConfig.isConfigured()) {
            markExternalFailed(records, "医共体 Doris 配置未完整设置，无法写入 msg_send");
            return;
        }

        try (Connection conn = medicalRegistryConfig.openConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            for (MessageSendRecord record : records) {
                bindRecord(ps, record);
                ps.addBatch();
            }
            ps.executeBatch();
            markExternalSent(records);
        } catch (Exception e) {
            log.warn("MedicalMsgSendRecordWriter: write msg_send failed count={}, error={}",
                    records.size(), e.getMessage());
            markExternalFailed(records, "写入医共体 msg_send 失败: " + e.getMessage());
        }
    }

    private void bindRecord(PreparedStatement ps, MessageSendRecord record) throws Exception {
        Instant now = Instant.now();
        Instant sentTime = record.getSentTime() != null ? record.getSentTime() : now;

        ps.setString(1, required(record.getMessageId()));
        ps.setString(2, required(record.getMessageId()));
        ps.setString(3, defaultString(record.getTopic(), ""));
        ps.setString(4, defaultString(record.getRouteKey(), ""));
        ps.setString(5, defaultString(record.getMessageKey(), ""));
        ps.setString(6, defaultString(record.getPayloadJson(), "{}"));
        ps.setString(7, defaultString(record.getPayloadType(),
                MessageSendRecordService.DEFAULT_PAYLOAD_TYPE));
        ps.setString(8, defaultString(record.getHeadersJson(), "{}"));
        ps.setString(9, "RABBITMQ");
        ps.setString(10, "SENT");
        ps.setInt(11, record.getSendAttempts() != null ? record.getSendAttempts() : 1);
        ps.setNull(12, Types.TIMESTAMP);
        ps.setTimestamp(13, Timestamp.from(sentTime));
        ps.setString(14, null);
        ps.setString(15, SOURCE_APPLICATION);
        ps.setTimestamp(16, Timestamp.from(now));
        ps.setTimestamp(17, Timestamp.from(now));
        ps.setString(18, defaultString(record.getTenantId(), "0"));
    }

    private void markExternalSent(List<MessageSendRecord> records) {
        Instant now = Instant.now();
        List<ExternalRecordUpdate> updates = records.stream()
                .map(record -> new ExternalRecordUpdate(
                        record.getMessageId(), EXTERNAL_SENT, now, null))
                .toList();
        try {
            batchStore.updateExternalBatch(updates);
        } catch (RuntimeException batchFailure) {
            log.error("MedicalMsgSendRecordWriter: batch local SENT update failed, falling back to JPA, count={}, error={}",
                    records.size(), batchFailure.getMessage());
            saveExternalFallback(records, EXTERNAL_SENT, now, null);
        }
    }

    private void markExternalFailed(List<MessageSendRecord> records, String error) {
        Instant now = Instant.now();
        String truncatedError = truncate(error);
        List<ExternalRecordUpdate> updates = records.stream()
                .map(record -> new ExternalRecordUpdate(
                        record.getMessageId(), EXTERNAL_SEND_FAILED, now, truncatedError))
                .toList();
        try {
            batchStore.updateExternalBatch(updates);
        } catch (RuntimeException batchFailure) {
            log.error("MedicalMsgSendRecordWriter: batch local failure update failed, falling back to JPA, count={}, error={}",
                    records.size(), batchFailure.getMessage());
            saveExternalFallback(records, EXTERNAL_SEND_FAILED, now, truncatedError);
        }
    }

    private void saveExternalFallback(
            List<MessageSendRecord> records, String status, Instant updateTime, String error) {
        for (MessageSendRecord record : records) {
            record.setExternalRecordStatus(status);
            record.setExternalRecordTime(updateTime);
            record.setExternalRecordError(error);
        }
        repository.saveAll(records);
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("messageId is required for msg_send");
        }
        return value;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > 2000 ? value.substring(0, 2000) + "...(truncated)" : value;
    }
}
