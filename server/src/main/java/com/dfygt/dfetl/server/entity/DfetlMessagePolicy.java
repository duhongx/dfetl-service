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

/** 标准数据集在所有机构间共享的消息发布默认策略。 */
@Entity
@Table(name = "dfetl_message_policy")
@Getter
@Setter
@NoArgsConstructor
public class DfetlMessagePolicy {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "dataset_id", nullable = false, unique = true)
    private Long datasetId;
    @Column(nullable = false)
    private Boolean enabled = false;
    @Column(nullable = false, length = 30)
    private String transport = "RABBITMQ";
    @Column(name = "full_sync_mode", nullable = false, length = 30)
    private String fullSyncMode = "ALL";
    @Column(name = "rate_limit", nullable = false)
    private Integer rateLimit = 1000;
    @Column(name = "routing_key", length = 100)
    private String routingKey;
    @Column(length = 100)
    private String topic;
    @Column(name = "key_template", length = 500)
    private String keyTemplate;
    @Column(name = "page_size", nullable = false)
    private Integer pageSize = 1000;
    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId = "0";
    @Column(name = "source_system", nullable = false, length = 50)
    private String sourceSystem = "HIS";
    @Column(name = "policy_revision", nullable = false)
    private Long policyRevision = 1L;
    @Version @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
