package com.dfygt.dfetl.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 机构数据治理 - 任务与数据源机构关联不一致记录。
 *
 * <p>语义：{@code sync_task.institution_id} 与 {@code source_data_source.institution_id}
 * 都非空但不相等。常见来源：
 * <ul>
 *   <li>共享数据源场景：单数据源被多个机构复用，任务侧已显式覆盖（业务正常）。</li>
 *   <li>历史脏数据：迁移过程错配（业务异常）。</li>
 * </ul>
 *
 * <p>本 DTO 仅用于报告输出，由人工根据上下文决策是否覆盖；不与孤儿修复流程合并，
 * 避免误删共享数据源的合法覆盖（详见 spec institution-management 任务 18.2，
 * Validates: Requirements 5.2）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InstitutionMismatchTaskDto {

    private Long taskId;

    private String taskName;

    private Long sourceDataSourceId;

    private String sourceDataSourceName;

    /** 任务自身的机构 ID（{@code sync_task.institution_id}）。 */
    private Long taskInstitutionId;

    /** 任务自身机构的名称（用于报告肉眼识别；机构已删除则为 null）。 */
    private String taskInstitutionName;

    /** 数据源上的机构 ID。 */
    private Long sourceInstitutionId;

    /** 数据源机构的名称（机构已删除则为 null）。 */
    private String sourceInstitutionName;
}
