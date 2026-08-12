package com.dfygt.dfetl.server.scheduler.dto;

import com.dfygt.dfetl.server.scheduler.model.ScheduleConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronPreviewResponse {
    private boolean valid;
    private String cronExpression;
    private String timezone;
    private String description;
    private List<String> nextRuns = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    /** 当 ScheduleConfig 预览时回填 */
    private ScheduleConfig scheduleConfig;
}
