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

/** 标准数据集在所有机构间共享的校验默认策略。 */
@Entity
@Table(name = "dfetl_validation_policy")
@Getter
@Setter
@NoArgsConstructor
public class DfetlValidationPolicy {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "dataset_id", nullable = false, unique = true)
    private Long datasetId;
    @Column(name = "inherit_global", nullable = false)
    private Boolean inheritGlobal = true;
    @Column(nullable = false)
    private Boolean enabled = false;
    @Column(name = "trigger_mode", nullable = false, length = 30)
    private String triggerMode = "AFTER_SYNC";
    @Column(name = "validation_method", nullable = false, length = 30)
    private String validationMethod = "ROW_COUNT";
    @Column(name = "row_tolerance", nullable = false, precision = 8, scale = 4)
    private java.math.BigDecimal rowTolerance = java.math.BigDecimal.ZERO;
    @Column(name = "fail_block", nullable = false)
    private Boolean failBlock = false;
    @Column(name = "revalidate_enabled", nullable = false)
    private Boolean revalidateEnabled = true;
    @Column(name = "revalidate_delay", nullable = false)
    private Integer revalidateDelay = 30;
    @Column(name = "lookback_hours", nullable = false)
    private Integer lookbackHours = 2;
    @Column(name = "policy_revision", nullable = false)
    private Long policyRevision = 1L;
    @Version @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
