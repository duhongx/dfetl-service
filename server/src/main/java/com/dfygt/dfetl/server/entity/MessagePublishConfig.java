package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 消息发布配置（message_publish_config）
 * 每个同步任务最多一条，控制 Redis 消息发布行为。
 */
@Entity
@Table(name = "message_publish_config", schema = "df_etl")
@Getter
@Setter
@NoArgsConstructor
public class MessagePublishConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联同步任务 ID（唯一） */
    @Column(name = "task_id", nullable = false, unique = true)
    private Long taskId;

    /** 是否启用消息发布 */
    @Column(nullable = false)
    private boolean enabled = false;

    /** Redis Pub/Sub channel */
    @Column(nullable = false, length = 200)
    private String channel;

    /** 消息类型，如 MFN^ZB3 */
    @Column(name = "message_type", nullable = false, length = 50)
    private String messageType;

    /** 业务主题，如 base.department */
    @Column(nullable = false, length = 100)
    private String topic;

    /** messageKey 模板，如 {yljgdm}:{ksdm} */
    @Column(name = "message_key_template", length = 500)
    private String messageKeyTemplate;

    /** 全量同步模式：ALL/SKIP/NOTIFY_ONLY，安全默认值为 SKIP */
    @Column(name = "full_sync_mode", nullable = false, length = 20)
    private String fullSyncMode = "SKIP";

    /** 限速（条/秒），null 表示不限速 */
    @Column(name = "rate_limit")
    private Integer rateLimit;

    /** 全量分页大小 */
    @Column(name = "page_size")
    private Integer pageSize = 1000;

    /** 来源系统标识 */
    @Column(name = "source_system", length = 50)
    private String sourceSystem = "HIS";

    /** 租户 ID */
    @Column(name = "tenant_id", length = 50)
    private String tenantId = "0";

    /** 手动字段映射 JSON（退化方案） */
    @Column(name = "field_mapping_json", columnDefinition = "TEXT")
    private String fieldMappingJson;

    /** Redis Stream MAXLEN 限制 */
    @Column(name = "stream_max_len")
    private Integer streamMaxLen = 10000;

    /** 全量 ALL 模式是否发送 TRUNCATE 信号 */
    @Column(name = "send_truncate_signal")
    private Boolean sendTruncateSignal = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
