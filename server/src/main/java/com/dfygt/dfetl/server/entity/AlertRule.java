package com.dfygt.dfetl.server.entity;

import com.dfygt.dfetl.server.common.StringListConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 告警规则
 * metric: task_status | dirty_count | validation_result | chunk_fail_rate | duration | read_rows | write_diff
 * condition: eq | ne | gt | lt | gte | lte
 * severity: critical | warning | info
 * scopeType: all | group | task
 */
@Entity
@Table(name = "alert_rule")
@Getter
@Setter
@NoArgsConstructor
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** 监控指标 */
    @Column(nullable = false, length = 50)
    private String metric;

    /** 比较条件 */
    @Column(name = "condition_op", nullable = false, length = 20)
    private String condition;

    /** 阈值（字符串，支持数值和枚举值） */
    @Column(nullable = false, length = 200)
    private String threshold;

    /** critical | warning | info */
    @Column(nullable = false, length = 20)
    private String severity = "warning";

    /** all | group | task（单列存储） */
    @Column(name = "scope", length = 20)
    private String scopeType = "all";

    /**
     * 适用范围对应的具体值：scopeType=task 时为 task_id；scopeType=all 时为 null。
     *
     * <p>spec alert-rule-evaluator-completion · Bug 2：原为 @Transient 不持久化导致
     * "指定分组 / 指定任务" 范围永远 short-circuit；改为 @Column 持久化，DB 列由 init.sql 末尾
     * idempotent ALTER 添加。
     */
    @Column(name = "scope_value", length = 200)
    private String scopeValue;

    /** 关联的通知渠道 ID 列表（JSON 存储） */
    @Convert(converter = StringListConverter.class)
    @Column(name = "channels", columnDefinition = "TEXT")
    private List<String> channelIds = new ArrayList<>();

    /** 静默时间（分钟），同一规则在此时间内只触发一次 */
    @Column(name = "silence_minutes")
    private Integer silenceMinutes = 60;

    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
