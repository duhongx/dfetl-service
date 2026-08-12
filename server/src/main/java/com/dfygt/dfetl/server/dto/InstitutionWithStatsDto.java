package com.dfygt.dfetl.server.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 机构 + 关联资产统计 DTO。
 *
 * <p>用于机构管理列表、机构数据视图等需要附带聚合数据的场景：包装基础 {@link InstitutionDto}，
 * 并附加该机构当前关联的数据源数量、同步任务数量、最近同步时间，以及任务状态摘要
 * （按 sync_task.status 分组的 count）。
 */
@Data
public class InstitutionWithStatsDto {

    /** 机构基础信息。 */
    private InstitutionDto institution;

    /** 关联的 source_data_source 数量。 */
    private long datasourceCount;

    /** 关联的 sync_task 数量。 */
    private long syncTaskCount;

    /** 关联同步任务中最近一次的 last_run_time，无则为 null。 */
    private LocalDateTime lastSyncTime;

    /** 同步任务状态摘要：status → count，例如 {@code {"SUCCESS": 5, "FAILED": 1}}。 */
    private Map<String, Long> statusSummary;
}
