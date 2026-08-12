package com.dfygt.dfetl.server.scheduler.dto;

import lombok.Data;

@Data
public class CronPreviewRequest {
    /** Quartz Cron 表达式（6/7 段） */
    private String cronExpression;
    /** 时区，默认 Asia/Shanghai */
    private String timezone;
    /** 预览未来执行次数，1~10，默认 5 */
    private Integer count;
}
