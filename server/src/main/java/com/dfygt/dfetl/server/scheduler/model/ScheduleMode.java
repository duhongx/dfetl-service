package com.dfygt.dfetl.server.scheduler.model;

/**
 * spec 053 - 可视化调度模式枚举。
 */
public enum ScheduleMode {
    /** 手动触发，不自动执行 */
    MANUAL,
    /** 每 N 分钟 */
    EVERY_N_MINUTES,
    /** 每 N 小时（可指定起始分钟） */
    EVERY_N_HOURS,
    /** 每天固定时间 */
    DAILY,
    /** 每周固定星期 + 时间 */
    WEEKLY,
    /** 每月固定日 + 时间 */
    MONTHLY,
    /** 高级 Cron 表达式 */
    ADVANCED
}
