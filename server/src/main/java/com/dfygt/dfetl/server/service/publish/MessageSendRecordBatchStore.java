package com.dfygt.dfetl.server.service.publish;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

/**
 * message_send_record 的 PostgreSQL 批量写入入口。
 *
 * <p>每个公开方法只开启一个事务；JDBC batch 中任意语句失败时整批回滚。</p>
 */
@Repository
@RequiredArgsConstructor
public class MessageSendRecordBatchStore {

    private static final String UPSERT_SENDING_SQL = """
            INSERT INTO df_etl.message_send_record (
              message_id, task_id, batch_id, publish_log_id, channel_mode, exchange_name, route_key,
              topic, message_key, business_key, tenant_id, source_system, trace_id,
              payload_type, message_json, send_status, payload_json, headers_json,
              send_attempts, send_start_time, broker_confirm_time, sent_time,
              next_retry_time, last_error, external_record_status,
              external_record_time, external_record_error, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'RABBITMQ', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?,
                      NULL, NULL, NULL, NULL, NULL, NULL, NULL, now(), now())
            ON CONFLICT (message_id) DO UPDATE SET
              task_id = EXCLUDED.task_id,
              batch_id = EXCLUDED.batch_id,
              publish_log_id = EXCLUDED.publish_log_id,
              channel_mode = EXCLUDED.channel_mode,
              exchange_name = EXCLUDED.exchange_name,
              route_key = EXCLUDED.route_key,
              topic = EXCLUDED.topic,
              message_key = EXCLUDED.message_key,
              business_key = EXCLUDED.business_key,
              tenant_id = EXCLUDED.tenant_id,
              source_system = EXCLUDED.source_system,
              trace_id = EXCLUDED.trace_id,
              payload_type = EXCLUDED.payload_type,
              message_json = EXCLUDED.message_json,
              payload_json = EXCLUDED.payload_json,
              headers_json = EXCLUDED.headers_json,
              send_status = 'SENDING',
              send_attempts = message_send_record.send_attempts + 1,
              send_start_time = EXCLUDED.send_start_time,
              broker_confirm_time = NULL,
              sent_time = NULL,
              next_retry_time = NULL,
              last_error = NULL,
              external_record_status = NULL,
              external_record_time = NULL,
              external_record_error = NULL,
              updated_at = now()
            """;

    private static final String UPDATE_TERMINAL_SQL = """
            UPDATE df_etl.message_send_record
               SET send_status = ?,
                   send_attempts = send_attempts + ?,
                   broker_confirm_time = ?,
                   sent_time = CASE WHEN ? = 'SENT' THEN ? ELSE sent_time END,
                   last_error = ?,
                   external_record_status = CASE
                     WHEN ? = 'SENT' AND COALESCE(external_record_status, '') <> 'SENT'
                     THEN 'WAIT_SEND' ELSE external_record_status END,
                   external_record_time = CASE
                     WHEN ? = 'SENT' AND COALESCE(external_record_status, '') <> 'SENT'
                     THEN NULL ELSE external_record_time END,
                   external_record_error = CASE
                     WHEN ? = 'SENT' AND COALESCE(external_record_status, '') <> 'SENT'
                     THEN NULL ELSE external_record_error END,
                   updated_at = now()
             WHERE message_id = ?
               AND (? = 'SEND_FAILED' OR send_status <> 'SEND_FAILED')
            """;

    private static final String UPDATE_EXTERNAL_SQL = """
            UPDATE df_etl.message_send_record
               SET external_record_status = ?,
                   external_record_time = ?,
                   external_record_error = ?,
                   updated_at = now()
             WHERE message_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void upsertSendingBatch(List<RabbitSendingRecord> records) {
        if (records == null || records.isEmpty()) return;
        jdbcTemplate.batchUpdate(UPSERT_SENDING_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                RabbitSendingRecord record = records.get(index);
                ps.setString(1, record.messageId());
                setNullableLong(ps, 2, record.taskId());
                setNullableLong(ps, 3, record.batchId());
                setNullableLong(ps, 4, record.publishLogId());
                ps.setString(5, record.exchangeName());
                ps.setString(6, record.routeKey());
                ps.setString(7, record.topic());
                ps.setString(8, record.messageKey());
                ps.setString(9, record.businessKey());
                ps.setString(10, record.tenantId());
                ps.setString(11, record.sourceSystem());
                ps.setString(12, record.traceId());
                ps.setString(13, record.payloadType());
                ps.setString(14, record.messageJson());
                ps.setString(15, MessageSendRecordService.STATUS_SENDING);
                ps.setString(16, record.payloadJson());
                ps.setString(17, record.headersJson());
                ps.setTimestamp(18, Timestamp.from(record.sendStartTime()));
            }

            @Override
            public int getBatchSize() {
                return records.size();
            }
        });
    }

    @Transactional
    public void updateTerminalBatch(List<MessageTerminalRecord> records) {
        if (records == null || records.isEmpty()) return;
        jdbcTemplate.batchUpdate(UPDATE_TERMINAL_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                MessageTerminalRecord record = records.get(index);
                String status = record.status().name();
                Timestamp terminalTime = Timestamp.from(record.terminalTime());
                ps.setString(1, status);
                ps.setInt(2, Math.max(0, record.attempts() - 1));
                ps.setTimestamp(3, terminalTime);
                ps.setString(4, status);
                ps.setTimestamp(5, terminalTime);
                ps.setString(6, record.error());
                ps.setString(7, status);
                ps.setString(8, status);
                ps.setString(9, status);
                ps.setString(10, record.messageId());
                ps.setString(11, status);
            }

            @Override
            public int getBatchSize() {
                return records.size();
            }
        });
    }

    @Transactional
    public void updateExternalBatch(List<ExternalRecordUpdate> records) {
        if (records == null || records.isEmpty()) return;
        jdbcTemplate.batchUpdate(UPDATE_EXTERNAL_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                ExternalRecordUpdate record = records.get(index);
                ps.setString(1, record.status());
                ps.setTimestamp(2, Timestamp.from(record.updateTime()));
                ps.setString(3, record.error());
                ps.setString(4, record.messageId());
            }

            @Override
            public int getBatchSize() {
                return records.size();
            }
        });
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }
}
