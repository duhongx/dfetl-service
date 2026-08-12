package com.dfygt.dfetl.server.scheduler.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * spec 053 - 可视化调度配置 POJO（持久化为 JSON 字符串）。
 *
 * <p>不同 mode 用到的字段不同：
 * <ul>
 *   <li>{@link ScheduleMode#MANUAL}：所有字段为空，cronExpression=null</li>
 *   <li>{@link ScheduleMode#EVERY_N_MINUTES}：intervalMinutes</li>
 *   <li>{@link ScheduleMode#EVERY_N_HOURS}：intervalHours + minute</li>
 *   <li>{@link ScheduleMode#DAILY}：hour + minute</li>
 *   <li>{@link ScheduleMode#WEEKLY}：daysOfWeek + hour + minute</li>
 *   <li>{@link ScheduleMode#MONTHLY}：dayOfMonth + hour + minute</li>
 *   <li>{@link ScheduleMode#ADVANCED}：仅 cronExpression</li>
 * </ul>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScheduleConfig {

    private ScheduleMode mode;
    private String timezone;
    private String description;
    private String cronExpression;
    private Integer version = 1;

    private Integer intervalMinutes;
    private Integer intervalHours;
    private Integer hour;
    private Integer minute;
    private Integer dayOfMonth;
    /** MON, TUE, WED, THU, FRI, SAT, SUN */
    private List<String> daysOfWeek;
}
