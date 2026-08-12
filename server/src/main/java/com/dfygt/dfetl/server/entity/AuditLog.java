package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 用户操作审计日志（append-only，禁止修改或删除）
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_time", nullable = false, updatable = false)
    private Instant actionTime = Instant.now();

    @Column(name = "user_name", nullable = false, length = 100)
    private String userName = "system";

    /**
     * 操作类型：创建任务 | 修改任务 | 发布任务 | 运行任务 | 停止任务 | 删除任务 等
     */
    @Column(nullable = false, length = 50)
    private String action;

    /** sync_task | source_datasource | target_datasource */
    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "target_name", length = 200)
    private String targetName;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "client_ip", length = 50)
    private String clientIp;
}
