package com.dfygt.dfetl.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** 标准数据集在所有机构间共享的同步、性能和调度默认策略。 */
@Entity
@Table(name = "dfetl_sync_policy")
@Getter
@Setter
@NoArgsConstructor
public class DfetlSyncPolicy {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "dataset_id", nullable = false, unique = true)
    private Long datasetId;
    @Column(name = "write_mode", nullable = false, length = 20)
    private String writeMode = "TRUNCATE";
    @Column(name = "sync_template", nullable = false, length = 30)
    private String syncTemplate = "FULL_ONLY";
    @Column(name = "incremental_field", length = 100)
    private String incrementalField;
    @Column(name = "increment_mode", nullable = false, length = 20)
    private String incrementMode = "TIME_FIELD";
    @Column(name = "upper_bound_strategy", nullable = false, length = 30)
    private String upperBoundStrategy = "CURRENT_TIME";
    @Column(name = "upper_bound_delay_minutes", nullable = false)
    private Integer upperBoundDelayMinutes = 5;
    @Column(name = "lookback_seconds", nullable = false)
    private Integer lookbackSeconds = 0;
    @Column(name = "reader_parallelism", nullable = false)
    private Integer readerParallelism = 4;
    @Column(name = "fetch_size")
    private Integer fetchSize;
    @Column(name = "rate_limit", nullable = false)
    private Integer rateLimit = 0;
    @Column(name = "schedule_enabled", nullable = false)
    private Boolean scheduleEnabled = true;
    @Column(name = "schedule_mode", nullable = false, length = 30)
    private String scheduleMode = "EVERY_N_HOURS";
    @Column(name = "schedule_interval_hours")
    private Integer scheduleIntervalHours = 4;
    @Column(name = "schedule_cron", length = 128)
    private String scheduleCron;
    @Column(name = "schedule_timezone", nullable = false, length = 64)
    private String scheduleTimezone = "Asia/Shanghai";
    @Column(name = "policy_revision", nullable = false)
    private Long policyRevision = 1L;
    @Version @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
