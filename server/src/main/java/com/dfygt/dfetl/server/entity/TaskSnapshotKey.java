package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Spec 020：源端主键集合快照（每次执行入库一份），用于跨次集合差集检测删除。
 */
@Entity
@Table(name = "task_snapshot_key", indexes = {
        @Index(name = "idx_tsk_task_exec", columnList = "task_id, execution_id"),
        @Index(name = "idx_tsk_task_key",  columnList = "task_id, key_value")
})
@Getter
@Setter
@NoArgsConstructor
public class TaskSnapshotKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    @Column(name = "key_value", nullable = false, length = 500)
    private String keyValue;

    @Column(name = "captured_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime capturedAt;
}
