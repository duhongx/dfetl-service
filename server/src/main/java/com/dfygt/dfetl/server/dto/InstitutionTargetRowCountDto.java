package com.dfygt.dfetl.server.dto;

import lombok.Data;

/**
 * 「按目标表统计机构实际行数」单条结果（spec 069 P2）。
 *
 * <p>区别于 {@link InstitutionWithStatsDto}（统计口径全部来自 sync_task 元数据），
 * 本 DTO 的 {@link #rowCount} 来自对 Doris 目标表按 {@code _etl_job_id} 分组的
 * {@code COUNT(*)} 实测——即「这家机构在这张表里到底落了多少行真实数据」。
 *
 * <p>机构维度行数 = 该机构在目标表 T 上所有同步任务（每个任务一个 {@code _etl_job_id = task.id}）
 * 对应行数之和。多机构共表场景下，运维据此可快速发现「某机构数据量异常」。
 *
 * <p>该查询走目标端 Doris 实时连接，属可观测性辅助；单机构/单表查询失败（目标表尚未建好、
 * Doris 临时不可达等）以 {@link #error} 标注，不影响其它机构结果，也不抛断整个请求。
 */
@Data
public class InstitutionTargetRowCountDto {

    /** 机构 ID。 */
    private Long institutionId;

    /** 机构编码（institution.code）。 */
    private String institutionCode;

    /** 机构名称（institution.name）。 */
    private String institutionName;

    /**
     * 该机构在目标表上的实际行数（按 {@code _etl_job_id} 聚合求和）。
     * 查询失败时为 null，并在 {@link #error} 给出原因。
     */
    private Long rowCount;

    /** 该机构贡献到该目标表的同步任务数（即参与聚合的 {@code _etl_job_id} 个数）。 */
    private long taskCount;

    /** 查询失败原因（成功时为 null）；前端据此展示「查询失败」而非误显示 0 行。 */
    private String error;
}
