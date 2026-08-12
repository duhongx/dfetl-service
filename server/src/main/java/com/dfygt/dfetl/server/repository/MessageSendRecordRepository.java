package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.MessageSendRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MessageSendRecordRepository extends JpaRepository<MessageSendRecord, Long> {

    Optional<MessageSendRecord> findByMessageId(String messageId);

    long countByPublishLogId(Long publishLogId);

    long countByPublishLogIdAndSendStatus(Long publishLogId, String sendStatus);

    List<MessageSendRecord> findByPublishLogId(Long publishLogId);

    /**
     * 多实例恢复领取：行锁配合 SKIP LOCKED，领取事务会立即把候选更新为新的 SENDING。
     */
    @Query(value = """
            select *
              from df_etl.message_send_record
             where channel_mode = 'RABBITMQ'
               and (
                    (send_status = 'SENDING' and send_start_time <= :staleBefore)
                 or (send_status in ('SEND_FAILED', 'WAIT_RETRY')
                     and coalesce(next_retry_time, to_timestamp(0)) <= :now)
               )
             order by coalesce(next_retry_time, send_start_time, created_at), id
             limit :limit
             for update skip locked
            """, nativeQuery = true)
    List<MessageSendRecord> lockRecoverableForUpdate(
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            @Param("limit") int limit);

    @Query("""
            select r
            from MessageSendRecord r
            where r.channelMode = 'RABBITMQ'
              and r.sendStatus = 'SENT'
              and (r.externalRecordStatus is null
                   or r.externalRecordStatus = 'WAIT_SEND'
                   or r.externalRecordStatus = 'SEND_FAILED')
            order by r.sentTime asc
            """)
    List<MessageSendRecord> findPendingExternalRecords(Pageable pageable);
}
