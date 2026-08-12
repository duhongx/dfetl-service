package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 目标数据源（Apache Doris）
 */
@Entity
@Table(name = "target_datasource")
@Getter
@Setter
@NoArgsConstructor
public class TargetDataSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** production | staging */
    @Column(nullable = false, length = 30)
    private String environment;

    @Column(name = "fe_host", nullable = false)
    private String feHost;

    @Column(name = "fe_port")
    private Integer fePort = 9030;

    @Column(name = "http_port")
    private Integer httpPort = 8030;

    @Column(name = "stream_load_port")
    private Integer streamLoadPort = 8040;

    @Column(nullable = false)
    private String username;

    /** AES-256 加密后的密码 */
    @Column(name = "password_enc", nullable = false)
    private String passwordEnc;

    @Column(name = "db_name", nullable = false)
    private String dbName;

    @Column(name = "default_write_database")
    private String defaultWriteDatabase;

    @Column(name = "write_batch_size")
    private Integer writeBatchSize = 50000;

    @Column(name = "write_concurrency")
    private Integer writeConcurrency = 8;

    @Column(name = "pool_size")
    private Integer poolSize = 20;

    @Column(nullable = false)
    private Boolean ssl = false;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** NORMAL | ERROR | TESTING */
    @Column(nullable = false, length = 20)
    private String status = "NORMAL";

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
