package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 消息发布日志（message_publish_log）
 * 记录每次消息发布操作的状态。
 */
@Entity
@Table(name = "message_publish_log", schema = "df_etl")
@Getter
@Setter
@NoArgsConstructor
public class MessagePublishLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联同步任务 ID */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 批次 ID */
    @Column(name = "batch_id")
    private Long batchId;

    /** Redis Pub/Sub channel */
    @Column(nullable = false, length = 200)
    private String channel;

    /** 业务主题 */
    @Column(nullable = false, length = 100)
    private String topic;

    /** 发送消息数 */
    @Column(name = "message_count")
    private Integer messageCount;

    /** PENDING/RUNNING/SUCCESS/FAILED/PARTIAL/SKIPPED/WAIT_RETRY/FAILED_FINAL */
    @Column(nullable = false, length = 20)
    private String status;

    /** 错误信息 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 发布时间 */
    @Column(name = "publish_time", nullable = false)
    private Instant publishTime;

    /** 数据范围：INCREMENTAL/FULL */
    @Column(name = "data_scope", length = 20)
    private String dataScope;

    /** 增量窗口起始时间 */
    @Column(name = "window_start")
    private Instant windowStart;

    /** 增量窗口结束时间 */
    @Column(name = "window_end")
    private Instant windowEnd;

    /** 本次发布的消息样本（前5条完整JSON数组），用于调试预览 */
    @Column(name = "sample_messages", columnDefinition = "TEXT")
    private String sampleMessages;

    /** Doris 批次尚不可见等运行级恢复尝试次数。 */
    @Column(name = "retry_attempts", nullable = false)
    private Integer retryAttempts = 0;

    /** 下一次允许运行级恢复的时间。 */
    @Column(name = "next_retry_time")
    private Instant nextRetryTime;
}
