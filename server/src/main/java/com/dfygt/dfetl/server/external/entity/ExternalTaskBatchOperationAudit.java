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

import java.time.LocalDateTime;

/**
 * 外部批量任务运行 / 删除操作审计。
 */
@Entity
@Table(name = "external_task_batch_operation_audit")
@Getter
@Setter
@NoArgsConstructor
public class ExternalTaskBatchOperationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_batch_id", nullable = false, length = 128)
    private String externalBatchId;

    /** RUN | DELETE */
    @Column(nullable = false, length = 30)
    private String operation;

    @Column(name = "dry_run", nullable = false)
    private Boolean dryRun = false;

    /** SUCCESS | PARTIAL_SUCCESS | FAILED */
    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "total_count", nullable = false)
    private Integer totalCount = 0;

    @Column(name = "success_count", nullable = false)
    private Integer successCount = 0;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount = 0;

    @Column(name = "skipped_count", nullable = false)
    private Integer skippedCount = 0;

    @Column(length = 100)
    private String caller;

    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "result_body", columnDefinition = "TEXT")
    private String resultBody;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;
}
