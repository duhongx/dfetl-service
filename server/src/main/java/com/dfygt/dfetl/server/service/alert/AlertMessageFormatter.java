package com.dfygt.dfetl.server.service.alert;

import com.dfygt.dfetl.server.common.ExecutionErrorSanitizer;
import com.dfygt.dfetl.server.entity.AlertChannel;
import com.dfygt.dfetl.server.entity.AlertRule;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钉钉 / 企业微信 Webhook 报文构造器（无状态工具类）。
 *
 * <p>按 {@link AlertChannel#getType()}（dingtalk / wecom） ×
 * {@link AlertChannel#getMessageFormat()}（text / markdown）四组合分发到 4 个 package-private 方法。
 *
 * <p>钉钉 text/markdown 都支持 atMobiles + isAtAll；企微 text 用 mentioned_mobile_list / mentioned_list；
 * 企微 markdown 协议不支持 @，按 R3.3 降级为不带 @。
 *
 * <p>设计对应 spec {@code alert-webhook-notification}，覆盖 R2.3-R2.6 与 R3.2-R3.5。
 */
public final class AlertMessageFormatter {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int ERROR_TRUNCATE_LIMIT = 500;

    private AlertMessageFormatter() {
    }

    /** 入口：按 channel 配置选 text/markdown 与平台分发。返回 Map 即 JSON 报文。 */
    public static Map<String, Object> format(
            AlertChannel channel, AlertRule rule, SyncTask task, TaskExecution exec) {
        boolean markdown = "markdown".equalsIgnoreCase(channel.getMessageFormat());
        boolean dingtalk = "dingtalk".equalsIgnoreCase(channel.getType());
        if (dingtalk) {
            return markdown
                    ? formatDingTalkMarkdown(channel, rule, task, exec)
                    : formatDingTalkText(channel, rule, task, exec);
        }
        // wecom
        return markdown
                ? formatWeComMarkdown(channel, rule, task, exec)
                : formatWeComText(channel, rule, task, exec);
    }

    /** 测试连通性专用，无 task/exec 上下文。返回 channel.type 对应的 text 报文，不带 @。 */
    public static Map<String, Object> formatTestProbe(AlertChannel channel) {
        String content = "【df-etl 告警测试】Webhook 渠道 " + safe(channel.getName()) + " 连接正常";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "text");
        body.put("text", Map.of("content", content));
        return body;
    }

    // ── 钉钉 text ────────────────────────────────────
    static Map<String, Object> formatDingTalkText(
            AlertChannel ch, AlertRule rule, SyncTask task, TaskExecution exec) {
        String content = String.join("\n", buildContentLines(rule, task, exec));
        content = appendAtMentions(content, ch); // 钉钉规则：text 末尾追加 @手机号 / @所有人 字符串
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "text");
        body.put("text", Map.of("content", content));
        body.put("at", buildDingTalkAt(ch));
        return body;
    }

    // ── 钉钉 markdown ────────────────────────────────
    static Map<String, Object> formatDingTalkMarkdown(
            AlertChannel ch, AlertRule rule, SyncTask task, TaskExecution exec) {
        String md = String.join("\n", buildMarkdownLines(rule, task, exec));
        md = appendAtMentions(md, ch); // 钉钉 markdown 同样需要 content 末尾 @ 占位
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "markdown");
        String title = rule.getName() == null ? "df-etl 告警" : rule.getName();
        body.put("markdown", Map.of("title", title, "text", md));
        body.put("at", buildDingTalkAt(ch));
        return body;
    }

    // ── 企微 text ────────────────────────────────────
    static Map<String, Object> formatWeComText(
            AlertChannel ch, AlertRule rule, SyncTask task, TaskExecution exec) {
        String content = String.join("\n", buildContentLines(rule, task, exec));
        Map<String, Object> textObj = new LinkedHashMap<>();
        textObj.put("content", content);
        List<String> mobiles = parseMobiles(ch.getMentionedMobiles());
        if (!mobiles.isEmpty()) {
            textObj.put("mentioned_mobile_list", mobiles);
        }
        if (Boolean.TRUE.equals(ch.getAtAll())) {
            textObj.put("mentioned_list", List.of("@all"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "text");
        body.put("text", textObj);
        return body;
    }

    // ── 企微 markdown（不支持 @）────────────────────
    static Map<String, Object> formatWeComMarkdown(
            AlertChannel ch, AlertRule rule, SyncTask task, TaskExecution exec) {
        String md = String.join("\n", buildMarkdownLines(rule, task, exec));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "markdown");
        body.put("markdown", Map.of("content", md));
        return body;
    }

    // ── helpers ──────────────────────────────────────
    static List<String> buildContentLines(AlertRule rule, SyncTask task, TaskExecution exec) {
        List<String> lines = new ArrayList<>();
        String severity = rule.getSeverity() == null ? "INFO" : rule.getSeverity().toUpperCase();
        lines.add("【df-etl 告警】" + severity);
        lines.add("任务：" + safe(task.getName()) + "（ID=" + task.getId() + "）");
        lines.add("状态：" + statusLabel(exec));
        lines.add("规则：" + safe(rule.getName()));
        lines.add("批次：" + (exec.getBatchNo() != null ? exec.getBatchNo() : "-"));
        if (exec.getFinishedAt() != null) {
            lines.add("时间：" + TS_FMT.format(exec.getFinishedAt().atZone(ZONE)));
        }
        String err = ExecutionErrorSanitizer.sanitize(exec.getErrorMsg());
        if (err != null && !err.isBlank()) {
            lines.add("错误：" + truncate(err));
        }
        return lines;
    }

    static List<String> buildMarkdownLines(AlertRule rule, SyncTask task, TaskExecution exec) {
        List<String> lines = new ArrayList<>();
        String severity = rule.getSeverity() == null ? "INFO" : rule.getSeverity().toUpperCase();
        lines.add("## " + severity + " " + safe(rule.getName()));
        lines.add("");
        lines.add("- 任务：**" + safe(task.getName()) + "**（ID=" + task.getId() + "）");
        lines.add("- 状态：" + statusLabel(exec));
        lines.add("- 批次：" + (exec.getBatchNo() != null ? exec.getBatchNo() : "-"));
        if (exec.getFinishedAt() != null) {
            lines.add("- 时间：" + TS_FMT.format(exec.getFinishedAt().atZone(ZONE)));
        }
        String err = ExecutionErrorSanitizer.sanitize(exec.getErrorMsg());
        if (err != null && !err.isBlank()) {
            lines.add("");
            lines.add("> 错误：" + truncate(err));
        }
        return lines;
    }

    static String statusLabel(TaskExecution exec) {
        String status = exec.getStatus() == null ? "" : exec.getStatus();
        return switch (status) {
            case "SUCCESS" -> "成功";
            case "RECONCILE_REQUIRED" -> "需要人工核对";
            case "CANCELLED" -> "已取消";
            case "FAILED" -> "失败";
            case "" -> "未知";
            default -> status;
        };
    }

    /** 钉钉规则：客户端按 atMobiles 渲染高亮，但 content 中需有 "@{手机号}" 字符串才会触发推送。 */
    static String appendAtMentions(String content, AlertChannel ch) {
        StringBuilder sb = new StringBuilder(content);
        List<String> mobiles = parseMobiles(ch.getMentionedMobiles());
        for (String m : mobiles) {
            sb.append(' ').append('@').append(m);
        }
        if (Boolean.TRUE.equals(ch.getAtAll())) {
            sb.append(" @所有人");
        }
        return sb.toString();
    }

    static Map<String, Object> buildDingTalkAt(AlertChannel ch) {
        Map<String, Object> at = new LinkedHashMap<>();
        List<String> mobiles = parseMobiles(ch.getMentionedMobiles());
        if (!mobiles.isEmpty()) {
            at.put("atMobiles", mobiles);
        }
        at.put("isAtAll", Boolean.TRUE.equals(ch.getAtAll()));
        return at;
    }

    /** 解析逗号分隔手机号，trim 空白，过滤空项；不做格式校验（由 AlertController 在保存时校验）。 */
    static List<String> parseMobiles(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : csv.split(",")) {
            String m = p.trim();
            if (!m.isEmpty()) {
                out.add(m);
            }
        }
        return out;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() > ERROR_TRUNCATE_LIMIT) {
            return s.substring(0, ERROR_TRUNCATE_LIMIT) + "…";
        }
        return s;
    }

    private static String safe(String s) {
        return s == null ? "-" : s;
    }
}
