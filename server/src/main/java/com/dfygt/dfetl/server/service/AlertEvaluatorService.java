package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.config.retry.NonRetryableException;
import com.dfygt.dfetl.server.config.retry.RetryableException;
import com.dfygt.dfetl.server.common.WebhookUrlValidator;
import com.dfygt.dfetl.server.entity.AlertChannel;
import com.dfygt.dfetl.server.entity.AlertRule;
import com.dfygt.dfetl.server.entity.SyncTask;
import com.dfygt.dfetl.server.entity.TaskExecution;
import com.dfygt.dfetl.server.entity.ValidationRun;
import com.dfygt.dfetl.server.repository.AlertChannelRepository;
import com.dfygt.dfetl.server.repository.AlertRuleRepository;
import com.dfygt.dfetl.server.repository.TaskChunkRepository;
import com.dfygt.dfetl.server.repository.ValidationRunRepository;
import com.dfygt.dfetl.server.service.alert.AlertMessageFormatter;
import com.dfygt.dfetl.server.service.alert.DingTalkSignatureCodec;
import com.dfygt.dfetl.server.service.alert.WebhookResponseParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 告警触发引擎。
 *
 * <p>在任务执行完成后调用 {@link #evaluate(SyncTask, TaskExecution)}，
 * 检查所有启用的告警规则，符合条件的规则向对应渠道推送 Webhook。
 *
 * <p>spec alert-rule-evaluator-completion 关单 4 处缺口：
 * <ul>
 *   <li>Bug 1：补齐 {@code chunk_fail_rate} / {@code validation_result} 指标分支</li>
 *   <li>Bug 2：{@link AlertRule#getScopeValue()} 已持久化（实体改造），matchesScope 精确匹配</li>
 *   <li>Bug 3：接入 silenceMinutes 静默窗口判定 + 至少一个渠道成功后写回 lastTriggeredAt</li>
 *   <li>Bug 4：UI 端枚举对齐（前端独立改动）</li>
 * </ul>
 */
@Service
@Slf4j
public class AlertEvaluatorService {

    private final AlertRuleRepository ruleRepository;
    private final AlertChannelRepository channelRepository;
    private final TaskChunkRepository taskChunkRepository;
    private final ValidationRunRepository validationRunRepository;
    private final RestTemplate restTemplate;
    private final RetryTemplate webhookRetryTemplate;
    private final WebhookUrlValidator webhookUrlValidator;

    public AlertEvaluatorService(
            AlertRuleRepository ruleRepository,
            AlertChannelRepository channelRepository,
            TaskChunkRepository taskChunkRepository,
            ValidationRunRepository validationRunRepository,
            @Qualifier("sharedRestTemplate") RestTemplate restTemplate,
            @Qualifier("webhookRetryTemplate") RetryTemplate webhookRetryTemplate,
            WebhookUrlValidator webhookUrlValidator) {
        this.ruleRepository = ruleRepository;
        this.channelRepository = channelRepository;
        this.taskChunkRepository = taskChunkRepository;
        this.validationRunRepository = validationRunRepository;
        this.restTemplate = restTemplate;
        this.webhookRetryTemplate = webhookRetryTemplate;
        this.webhookUrlValidator = webhookUrlValidator;
    }

    /**
     * 评估告警规则，在任务执行完成后调用。
     *
     * @param task 刚执行完的同步任务
     * @param exec 执行记录（status 已回写）
     */
    public void evaluate(SyncTask task, TaskExecution exec) {
        evaluate(task, exec, null);
    }

    /**
     * 校验 run 终态写回后调用，只评估 validation_result 指标，避免重复触发 task_status/duration 等同步执行告警。
     */
    public void evaluateValidationResult(SyncTask task, TaskExecution exec) {
        evaluate(task, exec, "validation_result");
    }

    private void evaluate(SyncTask task, TaskExecution exec, String onlyMetric) {
        List<AlertRule> rules = ruleRepository.findByEnabledTrue();
        if (rules.isEmpty()) return;

        Long taskId = task.getId();
        Instant now = Instant.now();

        for (AlertRule rule : rules) {
            try {
                // Bug 3：静默窗口内的规则跳过推送
                if (silenceWindowSkipped(rule, now)) {
                    log.debug("AlertRule [{}] in silence window, skipped", rule.getName());
                    continue;
                }
                if (onlyMetric != null && !onlyMetric.equals(rule.getMetric())) continue;
                if (!matchesScope(rule, taskId)) continue;
                if (!matchesCondition(rule, exec)) continue;

                log.info("AlertRule [{}] triggered for task {} (status={})",
                        rule.getName(), taskId, exec.getStatus());

                boolean anyChannelSucceeded = pushAlert(rule, task, exec);

                // Bug 3：仅在至少一个渠道成功后写回 lastTriggeredAt，
                // 零成功不更新（避免静默被误启动，下次仍会重试）
                if (anyChannelSucceeded) {
                    rule.setLastTriggeredAt(now);
                    ruleRepository.save(rule);
                }
            } catch (Exception e) {
                log.warn("Alert evaluation error for rule {}: {}", rule.getId(), e.getMessage());
            }
        }
    }

    /**
     * 静默窗口判定：lastTriggeredAt 在 silenceMinutes 窗口内时跳过该规则。
     *
     * <p>纯函数；测试可独立验证：
     * <ul>
     *   <li>{@code last == null} → false（首次触发）</li>
     *   <li>{@code silence == null || silence <= 0} → false（兼容旧规则）</li>
     *   <li>{@code Duration < silenceMinutes} → true（在窗口内跳过）</li>
     *   <li>{@code Duration >= silenceMinutes} → false（窗口外恢复）</li>
     * </ul>
     */
    boolean silenceWindowSkipped(AlertRule rule, Instant now) {
        Instant last = rule.getLastTriggeredAt();
        Integer silence = rule.getSilenceMinutes();
        if (last == null || silence == null || silence <= 0) return false;
        return Duration.between(last, now).toMinutes() < silence;
    }

    /** 检查规则 scopeType 是否匹配当前任务 */
    private boolean matchesScope(AlertRule rule, Long taskId) {
        return switch (rule.getScopeType()) {
            case "all" -> true;
            case "task" -> rule.getScopeValue() != null
                    && rule.getScopeValue().equals(String.valueOf(taskId));
            case "group" -> false;
            default -> false;
        };
    }

    /** 检查规则条件是否命中 */
    private boolean matchesCondition(AlertRule rule, TaskExecution exec) {
        return switch (rule.getMetric()) {
            case "task_status" -> matchesEnumCondition(
                    rule, exec.getStatus() != null ? exec.getStatus().toLowerCase() : "");
            case "duration" -> {
                if (exec.getDurationMs() == null) yield false;
                double durationMin = exec.getDurationMs() / 60000.0;
                yield matchesNumericCondition(rule, durationMin);
            }
            case "dirty_count" -> {
                long failedRows = exec.getFailedRows() != null ? exec.getFailedRows() : 0;
                yield matchesNumericCondition(rule, failedRows);
            }
            case "write_diff" -> {
                long readRows = exec.getReadRows() != null ? exec.getReadRows() : 0;
                long writeRows = exec.getWriteRows() != null ? exec.getWriteRows() : 0;
                yield matchesNumericCondition(rule, Math.abs(readRows - writeRows));
            }
            // Bug 1：新增 chunk_fail_rate 分支（按 task_chunk 表 status='FAILED' 占比）
            case "chunk_fail_rate" -> {
                Long execId = exec.getId();
                if (execId == null) yield false;
                long total = taskChunkRepository.countByExecutionId(execId);
                if (total == 0) yield false;   // 除零保护：无 chunk 视为不命中
                long failed = taskChunkRepository.countByExecutionIdAndStatus(execId, "FAILED");
                double pct = failed * 100.0 / total;
                yield matchesNumericCondition(rule, pct);
            }
            // Bug 1：新增 validation_result 分支（取 ValidationRun 最新一条状态）
            case "validation_result" -> {
                Long execId = exec.getId();
                if (execId == null) yield false;
                String status = validationRunRepository
                        .findFirstByExecutionIdOrderByIdDesc(execId)
                        .map(ValidationRun::getStatus)
                        .orElse(null);
                if (status == null) yield false;
                yield matchesEnumCondition(rule, status.toLowerCase());
            }
            // 兼容存量 batch_status / 未知 metric：永不命中，不抛异常
            default -> false;
        };
    }

    private boolean matchesEnumCondition(AlertRule rule, String actualValue) {
        String threshold = rule.getThreshold() != null ? rule.getThreshold().toLowerCase() : "";
        return switch (rule.getCondition()) {
            case "eq" -> actualValue.equals(threshold);
            case "ne" -> !actualValue.equals(threshold);
            default -> false;
        };
    }

    private boolean matchesNumericCondition(AlertRule rule, double actual) {
        try {
            double threshold = Double.parseDouble(rule.getThreshold());
            return switch (rule.getCondition()) {
                case "gt" -> actual > threshold;
                case "lt" -> actual < threshold;
                case "gte" -> actual >= threshold;
                case "lte" -> actual <= threshold;
                case "eq" -> actual == threshold;
                default -> false;
            };
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 向规则的所有渠道推送告警。
     *
     * <p>spec alert-rule-evaluator-completion · Bug 3：返回值由 {@code void} 改为
     * {@code boolean anyChannelSucceeded}，作为「是否写回 lastTriggeredAt」的判定依据。
     *
     * @return 至少有一个渠道推送成功时返回 true；channelIds 为空 / 全部 disabled / 全部失败返回 false
     */
    private boolean pushAlert(AlertRule rule, SyncTask task, TaskExecution exec) {
        List<String> channelIds = rule.getChannelIds();
        if (channelIds == null || channelIds.isEmpty()) return false;

        boolean anySucceeded = false;
        for (String channelIdStr : channelIds) {
            Long channelId;
            try {
                channelId = Long.parseLong(channelIdStr);
            } catch (NumberFormatException e) {
                log.warn("Invalid channelId '{}' in rule {}", channelIdStr, rule.getId());
                continue;
            }
            Optional<AlertChannel> chOpt = channelRepository.findById(channelId);
            if (chOpt.isEmpty()) continue;
            AlertChannel ch = chOpt.get();
            if (!Boolean.TRUE.equals(ch.getEnabled())) continue;

            try {
                sendWebhook(ch, rule, task, exec);
                anySucceeded = true;
            } catch (Exception e) {
                log.warn("[Retry:Webhook:{}] push failed (rule={}): {}",
                        ch.getName(), rule.getName(), e.getMessage());
            }
        }
        return anySucceeded;
    }

    /**
     * 发送 Webhook（按 channel.type / messageFormat 分发，钉钉 secret 非空时加签）。
     *
     * <p>流水线：SSRF 校验 → 报文构造 → 钉钉签名（条件） → RetryTemplate 包裹 POST →
     * errcode 业务码识别。errcode!=0 抛 {@link NonRetryableException}，避免把同样的错报文重试到底。
     *
     * <p>spec alert-rule-evaluator-completion · Bug 3：移除外层兜底 try/catch，
     * 让 SSRF 失败 / 重试耗尽 / errcode 失败的异常透出给 {@link #pushAlert}，
     * 由 pushAlert 内层 try/catch 捕获并记 WARN 日志，作为「至少一个渠道成功」判定依据。
     */
    private void sendWebhook(AlertChannel channel, AlertRule rule, SyncTask task, TaskExecution exec) {
        // SSRF 校验：发送前每次校验（防止配置保存后被改库绕过，以及 DNS 重绑定）
        try {
            webhookUrlValidator.validate(channel.getWebhookUrl());
        } catch (IllegalArgumentException e) {
            log.warn("Webhook 渠道 [{}] URL 未通过 SSRF 校验，已跳过推送: {}",
                    channel.getName(), e.getMessage());
            // Bug 3：抛而非吞，让 pushAlert 把该渠道计为失败
            throw new RuntimeException("SSRF blocked: " + channel.getName(), e);
        }

        Map<String, Object> body = AlertMessageFormatter.format(channel, rule, task, exec);
        String url = channel.getWebhookUrl();
        if ("dingtalk".equalsIgnoreCase(channel.getType())
                && channel.getSecret() != null && !channel.getSecret().isBlank()) {
            url += DingTalkSignatureCodec.sign(channel.getSecret(), System.currentTimeMillis());
        }
        final String finalUrl = url;

        // 重试耗尽 / errcode!=0 时让异常自然透出给 pushAlert，不再外层兜底吞掉
        webhookRetryTemplate.execute(context -> {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
                ResponseEntity<String> resp = restTemplate.postForEntity(finalUrl, request, String.class);
                String respBody = resp.getBody();
                if (!WebhookResponseParser.isSuccess(respBody)) {
                    String errmsg = WebhookResponseParser.extractErrmsg(respBody);
                    // NonRetryableException 防止把同样的错报文一直重试
                    throw new NonRetryableException(
                            "Webhook errcode!=0: " + errmsg
                                    + " | channel=" + channel.getName()
                                    + " | rule=" + rule.getName()
                                    + " | body=" + respBody);
                }
                return null;
            } catch (ResourceAccessException e) {
                throw new RetryableException("Webhook connection error: " + e.getMessage(), e);
            } catch (HttpServerErrorException e) {
                throw new RetryableException("Webhook server error: " + e.getMessage(), e);
            } catch (HttpClientErrorException e) {
                throw new NonRetryableException("Webhook client error: " + e.getMessage(), e);
            }
        });
        log.info("Alert pushed to channel [{}] ({}, format={})",
                channel.getName(), channel.getType(), channel.getMessageFormat());
    }
}
