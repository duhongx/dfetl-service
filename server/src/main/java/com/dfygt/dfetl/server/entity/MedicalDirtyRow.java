package com.dfygt.dfetl.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 医共体行级问题记录。
 */
@Entity
@Table(name = "medical_dirty_row",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_medical_dirty_row_execution_dataset_hash",
                columnNames = {"execution_id", "dataset_code", "source_row_hash"}))
@Getter
@Setter
@NoArgsConstructor
public class MedicalDirtyRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    @Column(name = "dataset_code", nullable = false, length = 100)
    private String datasetCode;

    @Column(name = "dataset_name", length = 200)
    private String datasetName;

    @Column(name = "source_schema", length = 200)
    private String sourceSchema;

    @Column(name = "source_view", nullable = false, length = 200)
    private String sourceView;

    @Column(name = "target_table", length = 200)
    private String targetTable;

    @Column(name = "business_pk_json", columnDefinition = "TEXT")
    private String businessPkJson;

    @Column(name = "source_row_hash", nullable = false, length = 64)
    private String sourceRowHash;

    @Column(name = "window_json", columnDefinition = "TEXT")
    private String windowJson;

    @Column(name = "owner_name", length = 100)
    private String ownerName;

    @Column(name = "owner_source", length = 100)
    private String ownerSource;

    @Column(name = "row_action", nullable = false, length = 50)
    private String rowAction;

    @Column(nullable = false, length = 50)
    private String severity;

    @Column(nullable = false, length = 50)
    private String status = "OPEN";

    @Column(name = "raw_row_json", columnDefinition = "TEXT")
    private String rawRowJson;

    @Column(name = "error_count", nullable = false)
    private Integer errorCount = 0;

    @Column(name = "found_at", nullable = false, updatable = false)
    private Instant foundAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "handled_by", length = 100)
    private String handledBy;

    @Column(name = "handle_note", columnDefinition = "TEXT")
    private String handleNote;
}
