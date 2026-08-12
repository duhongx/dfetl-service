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
 * 外部任务创建请求幂等与审计记录。
 */
@Entity
@Table(name = "external_task_request")
@Getter
@Setter
@NoArgsConstructor
public class ExternalTaskRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_request_id", nullable = false, unique = true, length = 128)
    private String externalRequestId;

    @Column(length = 100)
    private String caller;

    @Column(name = "yi_liao_jg_dm", nullable = false, length = 50)
    private String yiLiaoJgDm;

    @Column(name = "business_code", nullable = false, length = 50)
    private String businessCode;

    @Column(name = "source_schema", length = 100)
    private String sourceSchema;

    @Column(name = "source_object", nullable = false, length = 200)
    private String sourceObject;

    @Column(name = "source_object_type", length = 30)
    private String sourceObjectType;

    @Column(name = "task_id")
    private Long taskId;

    /** CREATING | CREATED | FAILED */
    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    @Column(name = "resolved_plan", columnDefinition = "TEXT")
    private String resolvedPlan;

    @Column(name = "external_batch_id", length = 128)
    private String externalBatchId;

    @Column(name = "batch_item_key", length = 256)
    private String batchItemKey;

    @Column(name = "batch_item_status", length = 30)
    private String batchItemStatus;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
