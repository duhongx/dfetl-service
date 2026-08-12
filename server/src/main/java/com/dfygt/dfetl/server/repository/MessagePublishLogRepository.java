package com.dfygt.dfetl.server.repository;

import com.dfygt.dfetl.server.entity.MessagePublishLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface MessagePublishLogRepository extends JpaRepository<MessagePublishLog, Long> {

    List<MessagePublishLog> findByTaskIdOrderByPublishTimeDesc(Long taskId);

    /**
     * 查找指定 batchId 的最早日志（即原始日志，重发会产生新日志）。
     * 用于按 batchId 重发时定位原始记录的窗口信息。
     */
    Optional<MessagePublishLog> findFirstByBatchIdOrderByPublishTimeAsc(Long batchId);

    Optional<MessagePublishLog> findFirstByTaskIdAndBatchIdOrderByPublishTimeAsc(Long taskId, Long batchId);

    Optional<MessagePublishLog> findTopByTaskIdAndBatchIdOrderByPublishTimeDesc(Long taskId, Long batchId);

    Optional<MessagePublishLog> findTopByTaskIdOrderByPublishTimeDesc(Long taskId);

    Optional<MessagePublishLog> findTopByTaskIdAndBatchIdInOrderByPublishTimeDesc(
            Long taskId, List<Long> batchIds);

    /**
     * 查询尚未形成发布终态的原始 execution；负 batchId 是人工重发日志，不参与执行保护。
     */
    @Query("""
            select l from MessagePublishLog l
            where l.taskId = :taskId
              and l.status in :statuses
              and l.batchId > 0
            order by l.publishTime asc
            """)
    List<MessagePublishLog> findUnresolvedOriginalRuns(
            @Param("taskId") Long taskId,
            @Param("statuses") List<String> statuses);

    /** 领取尚未物化逐条消息的运行级恢复记录。 */
    @Query(value = """
            select l.*
              from df_etl.message_publish_log l
             where l.batch_id > 0
               and not exists (
                   select 1 from df_etl.message_send_record r
                    where r.publish_log_id = l.id
               )
               and (
                    (l.status = 'WAIT_RETRY'
                     and coalesce(l.next_retry_time, to_timestamp(0)) <= :now)
                 or (l.status in ('PENDING', 'RUNNING') and l.publish_time <= :staleBefore)
               )
             order by coalesce(l.next_retry_time, l.publish_time), l.id
             limit :limit
             for update skip locked
            """, nativeQuery = true)
    List<MessagePublishLog> lockRecoverableRunsForUpdate(
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            @Param("limit") int limit);

    Page<MessagePublishLog> findByTaskId(Long taskId, Pageable pageable);

    void deleteByTaskId(Long taskId);

    /** 按 log id 反查 — 重发接口只需要 log id 即可拿到所有信息（taskId, batchId, window） */
    Optional<MessagePublishLog> findById(Long id);
}
