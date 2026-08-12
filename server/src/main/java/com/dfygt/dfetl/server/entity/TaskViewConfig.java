package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 同步任务下每个视图的字段映射配置（前端"字段映射"步骤的持久化）
 */
@Entity
@Table(name = "task_view_config",
       uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "view_name"}))
@Getter
@Setter
@NoArgsConstructor
public class TaskViewConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "view_name", nullable = false, length = 200)
    private String viewName;

    /** JSON 数组，字段映射列表 */
    @Column(name = "field_mappings", columnDefinition = "TEXT")
    private String fieldMappings;

    /** 根据映射生成的 Doris CREATE TABLE DDL */
    @Column(name = "doris_ddl", columnDefinition = "TEXT")
    private String dorisDdl;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
