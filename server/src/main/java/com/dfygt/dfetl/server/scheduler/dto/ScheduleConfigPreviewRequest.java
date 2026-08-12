package com.dfygt.dfetl.server.scheduler.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.dfygt.dfetl.server.scheduler.model.ScheduleMode;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties({"version", "description"})
public class ScheduleConfigPreviewRequest {
    private ScheduleMode mode;
    private String timezone;
    private Integer count;

    private Integer intervalMinutes;
    private Integer intervalHours;
    private Integer hour;
    private Integer minute;
    private Integer dayOfMonth;
    private List<String> daysOfWeek;
    /** ADVANCED 模式时直接传 cron */
    private String cronExpression;
}
