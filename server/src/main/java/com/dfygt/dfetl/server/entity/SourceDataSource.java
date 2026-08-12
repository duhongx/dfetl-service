package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 来源数据源（MySQL / PostgreSQL / Oracle / SQL Server）
 */
@Entity
@Table(name = "source_datasource")
@Getter
@Setter
@NoArgsConstructor
public class SourceDataSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** MYSQL | POSTGRESQL | ORACLE | SQLSERVER | DORIS */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private Integer port;

    @Column(name = "db_name", nullable = false)
    private String dbName;

    @Column(nullable = false)
    private String username;

    /** AES-256 加密后的密码 */
    @Column(name = "password_enc", nullable = false)
    private String passwordEnc;

    @Column(name = "schema_name")
    private String schemaName;

    @Column(nullable = false)
    private Boolean readonly = false;

    @Column(name = "query_timeout")
    private Integer queryTimeout = 60;

    @Column(name = "read_concurrency")
    private Integer readConcurrency = 4;

    @Column(name = "pool_size")
    private Integer poolSize = 10;

    @Column(nullable = false)
    private Boolean ssl = false;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** NORMAL | ERROR | TESTING */
    @Column(nullable = false, length = 20)
    private String status = "NORMAL";

    /** 关联机构 ID（df_etl.institution.id），可空 */
    @Column(name = "institution_id")
    private Long institutionId;

    /**
     * 数据源稳定编码（spec 070）：{机构首字母}-{库类型}-{序号}，如 xrmyy-mysql-01。
     * 创建时由系统自动生成，唯一、不可改、不展示；存量数据可空，迁移补码后由部分唯一索引保唯一。
     * 用作目标表 _etl_source_system 列的稳定来源标识，替代可变的 name。
     */
    @Column(name = "source_code", length = 100)
    private String sourceCode;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
