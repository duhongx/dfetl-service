package com.dfygt.dfetl.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "snapshot_apply_history", indexes = {
        @Index(name = "idx_sah_task_created", columnList = "task_id, created_at")
})
@Getter
@Setter
public class SnapshotApplyHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "prev_execution_id", nullable = false)
    private Long prevExecutionId;

    @Column(name = "curr_execution_id", nullable = false)
    private Long currExecutionId;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "detected_keys", nullable = false)
    private int detectedKeys;

    @Column(name = "loaded_rows", nullable = false)
    private Long loadedRows = 0L;

    @Column(name = "filtered_rows", nullable = false)
    private Long filteredRows = 0L;

    @Column(name = "result", nullable = false, length = 40)
    private String result;

    @Column(name = "label", length = 128)
    private String label;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
