package com.dfygt.dfetl.server.dto;

/**
 * spec 022：全局校验策略（持久化在 system_setting，前缀 validation_*）。
 *
 * @param autoEnabled       是否启用自动校验
 * @param trigger           after_sync | manual_only
 * @param method            row_count | checksum | row_count_checksum | all
 * @param rowTolerance      行数容差百分比（0.0~100.0）
 * @param failBlock         校验失败是否阻断
 * @param revalidate        失败是否自动重校验
 * @param revalidateDelay   重校验延迟秒数
 * @param lookbackHours     校验回看窗口（小时），0 表示只验本次增量窗口
 */
public record ValidationPolicy(
        boolean autoEnabled,
        String  trigger,
        String  method,
        double  rowTolerance,
        boolean failBlock,
        boolean revalidate,
        int     revalidateDelay,
        int     lookbackHours
) {
    public static ValidationPolicy defaults() {
        // spec validation-workbench-redesign · Task P1-7.1 / Requirement 10 (AC 1)
        // lookbackHours 默认值 24 → 2：旧 24h 在大表上每天扫数百万行（性能差），
        // 而 lookback 唯一保留价值是兜「commit 延迟」（典型 PG/MySQL 应用 commit 几乎实时，
        // 2h 足够覆盖跨时区/慢事务）。任务级 validationLookbackHours 优先级更高、可显式覆盖。
        return new ValidationPolicy(false, "after_sync", "row_count",
                0.0, false, true, 30, 2);
    }
}
