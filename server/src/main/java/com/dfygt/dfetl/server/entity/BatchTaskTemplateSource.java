package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 批量任务模板关联的数据源（每条代表一个医疗机构的源端数据源）。
 */
@Entity
@Table(name = "batch_task_template_source", schema = "df_etl")
@Getter
@Setter
@NoArgsConstructor
public class BatchTaskTemplateSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "source_datasource_id", nullable = false)
    private Long sourceDatasourceId;

    /** 关联机构 ID（df_etl.institution.id），可空 */
    @Column(name = "institution_id")
    private Long institutionId;

    /** 覆盖模板的 schema（可选） */
    @Column(name = "source_schema", length = 100)
    private String sourceSchema;

    /** 该机构特有的过滤条件 */
    @Column(name = "static_filter", length = 1000)
    private String staticFilter;

    /** 机构名称（展示用） */
    @Column(name = "institution_name", length = 200)
    private String institutionName;

    /** 机构代码 */
    @Column(name = "institution_code", length = 50)
    private String institutionCode;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** 关联已创建的 sync_task（创建后回填） */
    @Column(name = "sync_task_id")
    private Long syncTaskId;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;
}
