package com.dfygt.dfetl.server.external.entity;

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

import java.time.LocalDateTime;

/**
 * 外部批量任务创建请求幂等与审计记录。
 */
@Entity
@Table(name = "external_task_batch_request")
@Getter
@Setter
@NoArgsConstructor
public class ExternalTaskBatchRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_batch_id", nullable = false, unique = true, length = 128)
    private String externalBatchId;

    @Column(name = "yi_liao_jg_dm", nullable = false, length = 50)
    private String yiLiaoJgDm;

    @Column(name = "business_code", nullable = false, length = 50)
    private String businessCode;

    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "failure_policy", nullable = false, length = 30)
    private String failurePolicy;

    @Column(name = "total_count", nullable = false)
    private Integer totalCount = 0;

    @Column(name = "created_count", nullable = false)
    private Integer createdCount = 0;

    @Column(name = "existing_count", nullable = false)
    private Integer existingCount = 0;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount = 0;

    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    @Column(name = "result_body", columnDefinition = "TEXT")
    private String resultBody;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
