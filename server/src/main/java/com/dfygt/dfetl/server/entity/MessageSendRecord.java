package com.dfygt.dfetl.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 消息逐条发送记录（message_send_record）。
 */
@Entity
@Table(name = "message_send_record", schema = "df_etl")
@Getter
@Setter
@NoArgsConstructor
public class MessageSendRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true, length = 64)
    private String messageId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "publish_log_id")
    private Long publishLogId;

    @Column(name = "channel_mode", nullable = false, length = 32)
    private String channelMode;

    @Column(name = "exchange_name", length = 128)
    private String exchangeName;

    @Column(name = "route_key", nullable = false, length = 128)
    private String routeKey;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(name = "message_key", length = 256)
    private String messageKey;

    @Column(name = "business_key", length = 256)
    private String businessKey;

    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "source_system", length = 128)
    private String sourceSystem;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(name = "payload_type", nullable = false, length = 256)
    private String payloadType;

    @Column(name = "message_json", nullable = false, columnDefinition = "TEXT")
    private String messageJson;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "headers_json", columnDefinition = "TEXT")
    private String headersJson;

    @Column(name = "send_status", nullable = false, length = 32)
    private String sendStatus;

    @Column(name = "send_attempts", nullable = false)
    private Integer sendAttempts = 0;

    @Column(name = "send_start_time")
    private Instant sendStartTime;

    @Column(name = "broker_confirm_time")
    private Instant brokerConfirmTime;

    @Column(name = "sent_time")
    private Instant sentTime;

    @Column(name = "next_retry_time")
    private Instant nextRetryTime;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "external_record_status", length = 32)
    private String externalRecordStatus;

    @Column(name = "external_record_time")
    private Instant externalRecordTime;

    @Column(name = "external_record_error", columnDefinition = "TEXT")
    private String externalRecordError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
