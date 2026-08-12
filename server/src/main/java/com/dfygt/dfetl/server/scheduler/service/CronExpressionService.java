package com.dfygt.dfetl.server.scheduler.service;

import lombok.extern.slf4j.Slf4j;
import org.quartz.CronExpression;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * spec 053 - 基于 Quartz {@link CronExpression} 的 Cron 校验与预览服务。
 *
 * <p>所有 cron 解释一律走后端 Quartz，保证预览结果与实际触发一致。
 */
@Slf4j
@Service
public class CronExpressionService {

    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    /** 校验 cron 是否合法。 */
    public boolean isValid(String cron) {
        if (cron == null || cron.isBlank()) return false;
        return CronExpression.isValidExpression(cron);
    }

    /**
     * 计算未来 count 次执行时间。
     *
     * @param cron     合法的 Quartz cron
     * @param timezone 时区（null 时用默认 Asia/Shanghai）
     * @param count    预览次数（自动 clamp 到 [1, 10]）
     * @return ISO 8601 + timezone 格式的字符串列表
     */
    public List<String> getNextRuns(String cron, String timezone, int count) {
        List<String> result = new ArrayList<>();
        if (!isValid(cron)) return result;
        TimeZone tz = TimeZone.getTimeZone(timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone);
        int n = Math.max(1, Math.min(10, count));
        try {
            CronExpression expression = new CronExpression(cron);
            expression.setTimeZone(tz);
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            fmt.setTimeZone(tz);
            Date next = new Date();
            for (int i = 0; i < n; i++) {
                next = expression.getNextValidTimeAfter(next);
                if (next == null) break;
                result.add(fmt.format(next));
                // 推进 1ms 避免相同时间重复
                next = new Date(next.getTime() + 1);
            }
        } catch (ParseException e) {
            log.warn("CronExpressionService.getNextRuns parse failed: cron={}", cron, e);
        }
        return result;
    }

    /**
     * 风险检测：返回需要前端展示的 warning 文本。
     * <p>规则：
     * <ul>
     *   <li>秒级触发（第一段不是 0）→ 强警告</li>
     *   <li>每分钟以下高频 → 强警告</li>
     *   <li>包含 31 号 → 月份提示</li>
     * </ul>
     */
    public List<String> detectRisks(String cron) {
        List<String> warnings = new ArrayList<>();
        if (cron == null || cron.isBlank()) return warnings;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length >= 6) {
            String secField = parts[0];
            // 秒级触发：包含 / 或 * 而不是单一 0
            if (!"0".equals(secField) && (secField.contains("/") || secField.contains(",") || secField.contains("*"))) {
                warnings.add("当前 Cron 包含秒级触发，频率过高可能压垮源库与 Doris");
            }
            // 每月 31 号
            String dom = parts[3];
            if (dom.contains("31")) {
                warnings.add("当前选择了 31 号，部分月份没有该日期，本月可能不会执行");
            }
        }
        // 计算预期间隔（粗略）：取下两次执行差
        List<String> next = getNextRuns(cron, DEFAULT_TIMEZONE, 2);
        if (next.size() == 2) {
            try {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                fmt.setTimeZone(TimeZone.getTimeZone(DEFAULT_TIMEZONE));
                long diffSec = (fmt.parse(next.get(1)).getTime() - fmt.parse(next.get(0)).getTime()) / 1000;
                if (diffSec > 0 && diffSec < 60) {
                    warnings.add("当前任务执行频率较高（约 " + diffSec + " 秒一次），请确认源库和 Doris 能承受");
                }
            } catch (ParseException ignored) {
            }
        }
        return warnings;
    }
}
