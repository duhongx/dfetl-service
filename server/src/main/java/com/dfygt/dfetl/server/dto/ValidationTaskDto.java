package com.dfygt.dfetl.server.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ValidationTaskDto {

    private Long id;
    private String name;
    private Long taskId;
    /** 关联同步任务名（join 获取） */
    private String taskName;
    /** ROW_COUNT | CHECKSUM | ROW_COUNT_CHECKSUM */
    private String method;
    /** 校验表列表 */
    private List<String> tables;
    /** CONSISTENT | DIFF | PENDING | RUNNING */
    private String status;
    private Long sourceRows;
    private Long targetRows;
    /** 医共体源窗口总行数：合规行 + 阻断剔除行。 */
    private Long sourceRowsTotal;
    /** 医共体分流后进入 SeaTunnel / Doris 对比口径的合规源行数。 */
    private Long validSourceRows;
    /** 医共体分流阻断、未写入 Doris 的行数。 */
    private Long excludedRows;
    /** 医共体分流告警但仍写入 Doris 的行数。 */
    private Long warningRows;
    private Long diffRows;
    private Long durationMs;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Instant lastRunAt;
    private Long executionId;
    private String triggerType;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Instant windowStart;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Instant windowEnd;
    private String windowType;
    private Long windowStartId;
    private Long windowEndId;
    private LocalDateTime createdAt;
    /** 校验失败原因（status=ERROR 时填充） */
    private String errorMsg;
}
