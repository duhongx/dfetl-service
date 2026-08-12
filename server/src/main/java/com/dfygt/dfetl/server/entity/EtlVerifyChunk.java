package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Spec 023：Checksum 分片级结果。
 */
@Entity
@Table(name = "etl_verify_chunk",
        uniqueConstraints = @UniqueConstraint(name = "uk_verify_chunk_run_no",
                columnNames = {"validation_run_id", "chunk_no"}),
        indexes = {
                @Index(name = "idx_verify_chunk_task", columnList = "task_id, exec_id"),
                @Index(name = "idx_verify_chunk_run", columnList = "validation_run_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class EtlVerifyChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "exec_id", nullable = false)
    private Long execId;

    @Column(name = "validation_run_id")
    private Long validationRunId;

    @Column(name = "chunk_no", nullable = false)
    private Integer chunkNo;

    @Column(name = "chunk_start", length = 256)
    private String chunkStart;

    @Column(name = "chunk_end", length = 256)
    private String chunkEnd;

    @Column(name = "source_count")
    private Long sourceCount;

    @Column(name = "target_count")
    private Long targetCount;

    @Column(name = "source_checksum", length = 64)
    private String sourceChecksum;

    @Column(name = "target_checksum", length = 64)
    private String targetChecksum;

    @Column(name = "matched", nullable = false)
    private Boolean matched = false;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /** Spec 048：窗口 Checksum 的起止时间；null = 全表模式 */
    @Column(name = "scoped_window_start")
    private OffsetDateTime scopedWindowStart;

    @Column(name = "scoped_window_end")
    private OffsetDateTime scopedWindowEnd;
}
