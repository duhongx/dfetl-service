package com.dfygt.dfetl.server.dto;

import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Setter
public class ValidationRunDto {

    private Long id;
    private Long taskId;
    private Long legacyExecId;
    private String mode;
    private String scope;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Instant windowStart;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Instant windowEnd;
    private int totalChunks;
    private int matchedChunks;
    private long diffRows;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 源端实际执行 SQL */
    private String sourceSql;
    /** 目标端实际执行 SQL */
    private String targetSql;
    /** 源端 WHERE 条件 */
    private String sourceWhere;
    /** 目标端 WHERE 条件 */
    private String targetWhere;

    /**
     * spec validation-workbench-redesign · Task P1-5.1
     * 校验触发来源：AUTO / AUTO_COUNT / MANUAL / MANUAL_FULL / DRIFT / GATE / MANUAL_REPAIR_RECHECK；
     * NULL = 本需求落地前的历史数据。
     */
    private String triggerType;

    /** 校验状态：PENDING / RUNNING / CONSISTENT / DIFF / ERROR */
    private String status;
    /** 错误信息（status=ERROR 时填充） */
    private String errorMsg;
    /**
     * spec 069：非阻塞口径警告（与 errorMsg 区分）。
     * 多机构共表目标端存在 {@code _etl_job_id} 为 NULL 的历史行时填充，
     * 提示按任务范围过滤可能漏算这些行，建议清空重采。校验结果仍为 CONSISTENT/DIFF。
     */
    private String scopeWarning;
    /** 源端行数 */
    private Long sourceRows;
    /** 目标端行数 */
    private Long targetRows;
    /** 医共体源窗口总行数：合规行 + 阻断剔除行。 */
    private Long sourceRowsTotal;
    /** 医共体分流后进入 SeaTunnel / Doris 对比口径的合规源行数。 */
    private Long validSourceRows;
    /** 医共体分流阻断、未写入 Doris 的行数。 */
    private Long excludedRows;
    /** 医共体分流告警但仍写入 Doris 的行数。 */
    private Long warningRows;

    /**
     * 是否可「续跑」（断点续跑跳过已 matched 分片，仅重算未通过分片）。
     * <p>仅 CHECKSUM 类（有分片记录）且存在未 matched 分片时为 true；
     * ROW_COUNT（无分片）恒为 false。前端据此决定是否显示「继续未完成校验」按钮，
     * 不再用 status 近似推断（见 ETL_RISK_REGISTER 2026-05-27 P2）。
     */
    private boolean resumable;
}
