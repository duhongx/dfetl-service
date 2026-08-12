package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.common.WebhookUrlValidator;
import com.dfygt.dfetl.server.entity.AlertChannel;
import com.dfygt.dfetl.server.entity.AlertRule;
import com.dfygt.dfetl.server.repository.AlertChannelRepository;
import com.dfygt.dfetl.server.repository.AlertRuleRepository;
import com.dfygt.dfetl.server.service.alert.AlertMessageFormatter;
import com.dfygt.dfetl.server.service.alert.DingTalkSignatureCodec;
import com.dfygt.dfetl.server.service.alert.WebhookResponseParser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * 告警规则 + 通知渠道管理
 *
 * 规则：
 *   GET    /api/alerts/rules
 *   POST   /api/alerts/rules
 *   PUT    /api/alerts/rules/{id}
 *   DELETE /api/alerts/rules/{id}
 *   PATCH  /api/alerts/rules/{id}/enabled
 *
 * 渠道：
 *   GET    /api/alerts/channels
 *   POST   /api/alerts/channels
 *   PUT    /api/alerts/channels/{id}
 *   DELETE /api/alerts/channels/{id}
 *   POST   /api/alerts/channels/{id}/test
 */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private static final Pattern MOBILE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final int MAX_MOBILES = 30;

    private final AlertRuleRepository ruleRepo;
    private final AlertChannelRepository channelRepo;
    private final WebhookUrlValidator webhookUrlValidator;
    @Qualifier("sharedRestTemplate")
    private final RestTemplate sharedRestTemplate;

    // ─── 告警规则 ────────────────────────────────────────────────────────────

    @GetMapping("/rules")
    public ApiResponse<List<AlertRule>> listRules() {
        return ApiResponse.ok(ruleRepo.findAllByOrderByCreatedAtDesc());
    }

    @PostMapping("/rules")
    public ApiResponse<AlertRule> createRule(@RequestBody AlertRule body) {
        body.setId(null);
        body.setCreatedAt(Instant.now());
        return ApiResponse.ok(ruleRepo.save(body));
    }

    @PutMapping("/rules/{id}")
    public ApiResponse<AlertRule> updateRule(@PathVariable Long id, @RequestBody AlertRule body) {
        AlertRule rule = ruleRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("AlertRule not found: " + id));
        rule.setName(body.getName());
        rule.setEnabled(body.getEnabled());
        rule.setMetric(body.getMetric());
        rule.setCondition(body.getCondition());
        rule.setThreshold(body.getThreshold());
        rule.setSeverity(body.getSeverity());
        rule.setScopeType(body.getScopeType());
        rule.setScopeValue(body.getScopeValue());
        rule.setChannelIds(body.getChannelIds());
        rule.setSilenceMinutes(body.getSilenceMinutes());
        rule.setUpdatedAt(Instant.now());
        return ApiResponse.ok(ruleRepo.save(rule));
    }

    @DeleteMapping("/rules/{id}")
    public ApiResponse<Map<String, String>> deleteRule(@PathVariable Long id) {
        ruleRepo.deleteById(id);
        return ApiResponse.ok(Map.of("message", "deleted"));
    }

    @PatchMapping("/rules/{id}/enabled")
    public ApiResponse<AlertRule> toggleEnabled(
            @PathVariable Long id,
            @RequestParam boolean enabled) {
        AlertRule rule = ruleRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("AlertRule not found: " + id));
        rule.setEnabled(enabled);
        return ApiResponse.ok(ruleRepo.save(rule));
    }

    // ─── 通知渠道 ────────────────────────────────────────────────────────────

    @GetMapping("/channels")
    public ApiResponse<List<AlertChannel>> listChannels() {
        return ApiResponse.ok(channelRepo.findAllByOrderByCreatedAtDesc());
    }

    @PostMapping("/channels")
    public ApiResponse<AlertChannel> createChannel(@RequestBody AlertChannel body) {
        // SSRF 校验：保存前阻断指向内网/回环/云元数据的 webhook URL
        webhookUrlValidator.validate(body.getWebhookUrl());
        validateMentionedMobiles(body.getMentionedMobiles());
        body.setId(null);
        body.setCreatedAt(Instant.now());
        body.setLastTestStatus("untested");
        return ApiResponse.ok(channelRepo.save(body));
    }

    @PutMapping("/channels/{id}")
    public ApiResponse<AlertChannel> updateChannel(@PathVariable Long id, @RequestBody AlertChannel body) {
        // SSRF 校验：更新前阻断指向内网/回环/云元数据的 webhook URL
        webhookUrlValidator.validate(body.getWebhookUrl());
        validateMentionedMobiles(body.getMentionedMobiles());
        AlertChannel ch = channelRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("AlertChannel not found: " + id));
        ch.setName(body.getName());
        ch.setType(body.getType());
        ch.setWebhookUrl(body.getWebhookUrl());
        ch.setEnabled(body.getEnabled());
        ch.setSecret(body.getSecret());
        ch.setMentionedMobiles(body.getMentionedMobiles());
        ch.setAtAll(body.getAtAll());
        ch.setMessageFormat(body.getMessageFormat());
        return ApiResponse.ok(channelRepo.save(ch));
    }

    @DeleteMapping("/channels/{id}")
    public ApiResponse<Map<String, String>> deleteChannel(@PathVariable Long id) {
        channelRepo.deleteById(id);
        return ApiResponse.ok(Map.of("message", "deleted"));
    }

    /**
     * 测试 Webhook 连通性（向渠道发送一条测试消息）
     * POST /api/alerts/channels/{id}/test
     */
    @PostMapping("/channels/{id}/test")
    public ApiResponse<Map<String, String>> testChannel(@PathVariable Long id) {
        AlertChannel ch = channelRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("AlertChannel not found: " + id));
        boolean success = sendTestWebhook(ch);
        ch.setLastTestedAt(Instant.now());
        ch.setLastTestStatus(success ? "ok" : "fail");
        channelRepo.save(ch);
        return ApiResponse.ok(Map.of("status", success ? "ok" : "fail"));
    }

    // ─── 私有：发送测试 Webhook ──────────────────────────────────────────────

    private boolean sendTestWebhook(AlertChannel ch) {
        // SSRF 校验：测试连通性同样是服务端发起的出站请求，必须校验
        try {
            webhookUrlValidator.validate(ch.getWebhookUrl());
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = AlertMessageFormatter.formatTestProbe(ch);

            String url = ch.getWebhookUrl();
            if ("dingtalk".equalsIgnoreCase(ch.getType()) && StringUtils.hasText(ch.getSecret())) {
                url += DingTalkSignatureCodec.sign(ch.getSecret(), System.currentTimeMillis());
            }

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            // 复用关闭重定向 + 带超时的 sharedRestTemplate，避免 new RestTemplate() 跟随 3xx 跳转绕过校验
            ResponseEntity<String> resp = sharedRestTemplate.postForEntity(url, request, String.class);
            return WebhookResponseParser.isSuccess(resp.getBody());
        } catch (Exception e) {
            return false;
        }
    }

    // ─── 私有：手机号 CSV 校验 ──────────────────────────────────────────────

    /**
     * 校验逗号分隔的 @ 手机号列表。
     * <p>规则：
     * <ul>
     *   <li>{@code null} 或空白 → 放行</li>
     *   <li>逗号拆分后 trim、过滤空项</li>
     *   <li>最多 30 个，超出抛 IllegalArgumentException</li>
     *   <li>每个匹配 {@code ^1[3-9]\d{9}$}，否则抛 IllegalArgumentException</li>
     * </ul>
     */
    private void validateMentionedMobiles(String csv) {
        if (csv == null || csv.isBlank()) return;
        String[] parts = csv.split(",");
        if (parts.length > MAX_MOBILES) {
            throw new IllegalArgumentException("最多 " + MAX_MOBILES + " 个 @ 手机号");
        }
        for (String p : parts) {
            String m = p.trim();
            if (m.isEmpty()) continue;
            if (!MOBILE_PATTERN.matcher(m).matches()) {
                throw new IllegalArgumentException("非法手机号: " + m);
            }
        }
    }
}
