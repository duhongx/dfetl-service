package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 脏数据记录（类型转换失败、空值异常、Stream Load 失败等）
 */
@Entity
@Table(name = "dirty_record")
@Getter
@Setter
@NoArgsConstructor
public class DirtyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "execution_id")
    private Long executionId;

    @Column(name = "chunk_id")
    private Long chunkId;

    @Column(name = "view_name", length = 200)
    private String viewName;

    @Column(name = "chunk_no")
    private Integer chunkNo;

    /**
     * FIELD_CONVERT_FAIL | NULL_VIOLATION | TYPE_MISMATCH | WRITE_FAIL
     */
    @Column(name = "error_type", nullable = false, length = 50)
    private String errorType;

    @Column(name = "target_field", length = 200)
    private String targetField;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    /** 原始行数据（JSON 字符串） */
    @Column(name = "raw_data", columnDefinition = "TEXT")
    private String rawData;

    @Column(nullable = false)
    private Boolean handled = false;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "found_at", nullable = false, updatable = false)
    private Instant foundAt = Instant.now();
}
