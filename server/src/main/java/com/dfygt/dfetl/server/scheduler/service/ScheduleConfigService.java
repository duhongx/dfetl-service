package com.dfygt.dfetl.server.scheduler.service;

import com.dfygt.dfetl.server.scheduler.model.ScheduleConfig;
import com.dfygt.dfetl.server.scheduler.model.ScheduleMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * spec 053 - ScheduleConfig &lt;-&gt; cronExpression 双向转换 + 中文描述生成。
 *
 * <p>所有 cron 都生成为 6 段 Quartz 表达式（秒 分 时 日 月 周），秒永远为 0。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleConfigService {

    private static final Set<String> VALID_DOW = Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private final ObjectMapper objectMapper;

    // ── public api ─────────────────────────────────────────────────────────

    /**
     * 根据 ScheduleConfig 生成 cronExpression。MANUAL 返回 null。
     *
     * @throws IllegalArgumentException 字段非法时
     */
    public String toCron(ScheduleConfig config) {
        if (config == null || config.getMode() == null) {
            throw new IllegalArgumentException("scheduleConfig.mode 不能为空");
        }
        switch (config.getMode()) {
            case MANUAL:
                return null;
            case EVERY_N_MINUTES: {
                int n = require(config.getIntervalMinutes(), "intervalMinutes");
                if (n < 1 || n > 1440) {
                    throw new IllegalArgumentException("intervalMinutes 必须在 [1, 1440] 范围内");
                }
                return "0 */" + n + " * * * ?";
            }
            case EVERY_N_HOURS: {
                int h = require(config.getIntervalHours(), "intervalHours");
                int m = config.getMinute() == null ? 0 : config.getMinute();
                if (h < 1 || h > 23) throw new IllegalArgumentException("intervalHours 必须在 [1, 23] 范围内");
                if (m < 0 || m > 59) throw new IllegalArgumentException("minute 必须在 [0, 59] 范围内");
                return "0 " + m + " */" + h + " * * ?";
            }
            case DAILY: {
                int h = require(config.getHour(), "hour");
                int m = require(config.getMinute(), "minute");
                checkHourMinute(h, m);
                return "0 " + m + " " + h + " * * ?";
            }
            case WEEKLY: {
                int h = require(config.getHour(), "hour");
                int m = require(config.getMinute(), "minute");
                checkHourMinute(h, m);
                List<String> dows = config.getDaysOfWeek();
                if (dows == null || dows.isEmpty()) {
                    throw new IllegalArgumentException("WEEKLY 模式至少要选择一天");
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < dows.size(); i++) {
                    String d = dows.get(i).toUpperCase();
                    if (!VALID_DOW.contains(d)) {
                        throw new IllegalArgumentException("非法 daysOfWeek 值：" + d);
                    }
                    if (i > 0) sb.append(",");
                    sb.append(d);
                }
                return "0 " + m + " " + h + " ? * " + sb;
            }
            case MONTHLY: {
                int h = require(config.getHour(), "hour");
                int m = require(config.getMinute(), "minute");
                int d = require(config.getDayOfMonth(), "dayOfMonth");
                checkHourMinute(h, m);
                if (d < 1 || d > 31) throw new IllegalArgumentException("dayOfMonth 必须在 [1, 31] 范围内");
                return "0 " + m + " " + h + " " + d + " * ?";
            }
            case ADVANCED: {
                String cron = config.getCronExpression();
                if (cron == null || cron.isBlank()) {
                    throw new IllegalArgumentException("ADVANCED 模式必须填写 cronExpression");
                }
                if (!org.quartz.CronExpression.isValidExpression(cron)) {
                    throw new IllegalArgumentException("非法 Quartz Cron：" + cron);
                }
                return cron.trim();
            }
            default:
                throw new IllegalArgumentException("不支持的 mode：" + config.getMode());
        }
    }

    /** 生成中文描述，如 "每天 02:30 执行"。 */
    public String describe(ScheduleConfig config) {
        if (config == null || config.getMode() == null) return "";
        switch (config.getMode()) {
            case MANUAL:
                return "手动触发，不自动执行";
            case EVERY_N_MINUTES:
                return "每 " + config.getIntervalMinutes() + " 分钟执行一次";
            case EVERY_N_HOURS: {
                int m = config.getMinute() == null ? 0 : config.getMinute();
                return "每 " + config.getIntervalHours() + " 小时的第 " + m + " 分钟执行";
            }
            case DAILY:
                return "每天 " + pad(config.getHour()) + ":" + pad(config.getMinute()) + " 执行";
            case WEEKLY: {
                String dows = String.join("、", config.getDaysOfWeek().stream().map(this::dowZh).toList());
                return "每周 " + dows + " " + pad(config.getHour()) + ":" + pad(config.getMinute()) + " 执行";
            }
            case MONTHLY:
                return "每月 " + config.getDayOfMonth() + " 号 " + pad(config.getHour()) + ":" + pad(config.getMinute()) + " 执行";
            case ADVANCED:
                return "高级 Cron 表达式：" + config.getCronExpression();
            default:
                return "";
        }
    }

    // ── 反解析（spec 053 Phase 4 提前实现：cron -> ScheduleConfig） ─────────

    private static final Pattern P_EVERY_N_MIN  = Pattern.compile("^0\\s+\\*/(\\d+)\\s+\\*\\s+\\*\\s+\\*\\s+\\?$");
    private static final Pattern P_EVERY_N_HOUR = Pattern.compile("^0\\s+(\\d+)\\s+\\*/(\\d+)\\s+\\*\\s+\\*\\s+\\?$");
    private static final Pattern P_DAILY        = Pattern.compile("^0\\s+(\\d+)\\s+(\\d+)\\s+\\*\\s+\\*\\s+\\?$");
    private static final Pattern P_WEEKLY       = Pattern.compile("^0\\s+(\\d+)\\s+(\\d+)\\s+\\?\\s+\\*\\s+([A-Z,]+)$");
    private static final Pattern P_MONTHLY      = Pattern.compile("^0\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+\\*\\s+\\?$");

    /**
     * 把旧任务的 cronExpression 反解析为 ScheduleConfig。无法识别返回 ADVANCED。
     */
    public ScheduleConfig fromCron(String cron, String timezone) {
        ScheduleConfig c = new ScheduleConfig();
        c.setTimezone(timezone == null || timezone.isBlank() ? CronExpressionService.DEFAULT_TIMEZONE : timezone);
        c.setVersion(1);
        if (cron == null || cron.isBlank()) {
            c.setMode(ScheduleMode.MANUAL);
            c.setDescription(describe(c));
            return c;
        }
        String trimmed = cron.trim();
        Matcher m;
        if ((m = P_EVERY_N_MIN.matcher(trimmed)).matches()) {
            c.setMode(ScheduleMode.EVERY_N_MINUTES);
            c.setIntervalMinutes(Integer.parseInt(m.group(1)));
        } else if ((m = P_EVERY_N_HOUR.matcher(trimmed)).matches()) {
            c.setMode(ScheduleMode.EVERY_N_HOURS);
            c.setMinute(Integer.parseInt(m.group(1)));
            c.setIntervalHours(Integer.parseInt(m.group(2)));
        } else if ((m = P_DAILY.matcher(trimmed)).matches()) {
            c.setMode(ScheduleMode.DAILY);
            c.setMinute(Integer.parseInt(m.group(1)));
            c.setHour(Integer.parseInt(m.group(2)));
        } else if ((m = P_WEEKLY.matcher(trimmed)).matches()) {
            c.setMode(ScheduleMode.WEEKLY);
            c.setMinute(Integer.parseInt(m.group(1)));
            c.setHour(Integer.parseInt(m.group(2)));
            c.setDaysOfWeek(java.util.Arrays.stream(m.group(3).split(",")).toList());
        } else if ((m = P_MONTHLY.matcher(trimmed)).matches()) {
            c.setMode(ScheduleMode.MONTHLY);
            c.setMinute(Integer.parseInt(m.group(1)));
            c.setHour(Integer.parseInt(m.group(2)));
            c.setDayOfMonth(Integer.parseInt(m.group(3)));
        } else {
            c.setMode(ScheduleMode.ADVANCED);
            c.setCronExpression(trimmed);
        }
        c.setCronExpression(c.getMode() == ScheduleMode.ADVANCED ? trimmed : trimmed);
        c.setDescription(describe(c));
        return c;
    }

    // ── JSON 序列化辅助 ─────────────────────────────────────────────────────

    public String toJson(ScheduleConfig config) {
        try {
            return config == null ? null : objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("scheduleConfig 序列化失败", e);
        }
    }

    public ScheduleConfig fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, ScheduleConfig.class);
        } catch (JsonProcessingException e) {
            log.warn("ScheduleConfig fromJson failed: {}", e.getMessage());
            return null;
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private int require(Integer v, String name) {
        if (v == null) throw new IllegalArgumentException(name + " 不能为空");
        return v;
    }

    private void checkHourMinute(int h, int m) {
        if (h < 0 || h > 23) throw new IllegalArgumentException("hour 必须在 [0, 23] 范围内");
        if (m < 0 || m > 59) throw new IllegalArgumentException("minute 必须在 [0, 59] 范围内");
    }

    private String pad(Integer v) {
        if (v == null) return "00";
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private String dowZh(String dow) {
        return switch (dow.toUpperCase()) {
            case "MON" -> "一";
            case "TUE" -> "二";
            case "WED" -> "三";
            case "THU" -> "四";
            case "FRI" -> "五";
            case "SAT" -> "六";
            case "SUN" -> "日";
            default -> dow;
        };
    }
}
