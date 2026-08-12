package com.dfygt.dfetl.server.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BatchTaskTemplateSourceDto {

    private Long id;

    private Long templateId;

    private Long sourceDatasourceId;

    /** 覆盖模板的 schema（可选） */
    private String sourceSchema;

    /** 该机构特有的过滤条件 */
    private String staticFilter;

    /**
     * 关联机构 ID（df_etl.institution.id），spec institution-management 任务 17.2 引入。
     *
     * <p>新版前端应直接传 {@code institutionId}；为空时服务端兼容旧字段
     * {@link #institutionCode} 自动解析（按 code 严格查找，未命中则记录 WARN）。
     */
    private Long institutionId;

    /** 机构名称（展示用，旧字段，保留以兼容历史前端） */
    private String institutionName;

    /** 机构代码（旧字段，保留以兼容历史前端，可触发 institutionId 自动解析） */
    private String institutionCode;

    private Boolean enabled = true;

    /** 关联已创建的 sync_task ID（创建后回填） */
    private Long syncTaskId;

    private LocalDateTime createdAt;
}
