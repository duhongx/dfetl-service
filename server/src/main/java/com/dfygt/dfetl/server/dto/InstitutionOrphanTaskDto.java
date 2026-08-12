package com.dfygt.dfetl.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 机构数据治理 - 孤儿任务记录。
 *
 * <p>语义：{@code sync_task.institution_id IS NULL} 但其引用的
 * {@code source_data_source.institution_id} 非空，意味着任务本应从数据源继承机构、
 * 但因历史数据 / 旧版 API 漏写而未继承（详见 {@code InstitutionGovernanceService}）。
 *
 * <p>用于 spec institution-management 任务 18.1 的 {@code GET /api/institution/orphans}
 * 列表端点（Validates: Requirements 5.2）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InstitutionOrphanTaskDto {

    /** 同步任务 ID（{@code sync_task.id}）。 */
    private Long taskId;

    /** 同步任务名称，便于运维肉眼识别。 */
    private String taskName;

    /** 任务关联的源数据源 ID（{@code sync_task.source_datasource_id}）。 */
    private Long sourceDataSourceId;

    /** 源数据源名称。 */
    private String sourceDataSourceName;

    /** 源数据源上的机构 ID（修复时回填到 {@code sync_task.institution_id}）。 */
    private Long sourceInstitutionId;
}
