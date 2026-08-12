package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 分片（Chunk）执行明细
 */
@Entity
@Table(name = "task_chunk")
@Getter
@Setter
@NoArgsConstructor
public class TaskChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    @Column(name = "view_name", length = 200)
    private String viewName;

    @Column(name = "chunk_no", nullable = false)
    private Integer chunkNo;

    @Column(name = "range_desc", length = 500)
    private String rangeDesc;

    /** PENDING | RUNNING | SUCCESS | FAILED | RETRYING | SKIPPED */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "read_rows")
    private Long readRows;

    @Column(name = "write_rows")
    private Long writeRows;

    @Column(name = "source_checksum", length = 128)
    private String sourceChecksum;

    @Column(name = "target_checksum", length = 128)
    private String targetChecksum;

    @Column(name = "doris_label", length = 300)
    private String dorisLabel;

    @Column(name = "fetch_size")
    private Integer fetchSize;

    @Column(name = "concurrency")
    private Integer concurrency;

    @Column(nullable = false)
    private Integer retries = 0;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
