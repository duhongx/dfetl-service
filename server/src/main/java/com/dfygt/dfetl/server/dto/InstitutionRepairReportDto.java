package com.dfygt.dfetl.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 机构数据治理 - 一次性数据修复结果报告。
 *
 * <p>对应 {@code POST /api/institution/admin/repair-orphans}：将
 * {@code source_data_source.institution_id} 回填到对应的 {@code sync_task.institution_id}
 * （仅当任务 institution_id 为 null 且数据源 institution_id 非空）。
 *
 * <p>包含操作前后计数、本次实际更新行数、以及被修复的任务 ID 列表，便于运维核查。
 *
 * <p>spec institution-management 任务 18.3（Validates: Requirements 5.2）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InstitutionRepairReportDto {

    /** 修复前的孤儿任务总数（institution_id IS NULL 且数据源 institution_id 非空）。 */
    private long orphansBefore;

    /** 修复后剩余的孤儿任务数（理论上应为 0；非 0 表示并发或新增）。 */
    private long orphansAfter;

    /** 本次操作实际修改的 sync_task 行数。 */
    private int affectedRows;

    /** 被修复的任务 ID 列表（按 ID 升序，便于运维核查与回滚）。 */
    private List<Long> repairedTaskIds;

    /** dryRun=true 时为预演（不持久化）；false 为实际执行。 */
    private boolean dryRun;
}
