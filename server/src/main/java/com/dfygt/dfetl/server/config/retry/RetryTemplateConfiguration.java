package com.dfygt.dfetl.server.config.retry;

import com.dfygt.dfetl.server.config.MessagePublishProperties;
import com.dfygt.dfetl.server.config.WebhookSecurityProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * 重试模板配置类。
 * 为 SeaTunnel REST、Redis、Webhook 创建独立的 RetryTemplate Bean，
 * 并提供共享的 RestTemplate Bean（供 AlertEvaluatorService 使用）。
 */
@Configuration
@EnableConfigurationProperties({RetryConfigProperties.class, MessagePublishProperties.class,
        WebhookSecurityProperties.class})
@RequiredArgsConstructor
@Slf4j
public class RetryTemplateConfiguration {

    private final RetryConfigProperties props;

    @Bean
    public RetryTemplate seaTunnelSubmitRetryTemplate() {
        var cfg = props.getSeatunnel();
        return buildExponentialRetryTemplate(
                cfg.getSubmitMaxAttempts(),
                cfg.getSubmitInitialIntervalMs(),
                cfg.getSubmitMultiplier(),
                "SeaTunnel", "submitJob");
    }

    @Bean
    public RetryTemplate seaTunnelQueryRetryTemplate() {
        var cfg = props.getSeatunnel();
        return buildExponentialRetryTemplate(
                cfg.getQueryMaxAttempts(),
                cfg.getQueryInitialIntervalMs(),
                cfg.getQueryMultiplier(),
                "SeaTunnel", "getJobInfo");
    }

    @Bean
    public RetryTemplate seaTunnelStopRetryTemplate() {
        var cfg = props.getSeatunnel();
        return buildExponentialRetryTemplate(
                cfg.getStopMaxAttempts(),
                cfg.getStopInitialIntervalMs(),
                cfg.getStopMultiplier(),
                "SeaTunnel", "stopJob");
    }

    @Bean
    public RetryTemplate redisRetryTemplate() {
        var cfg = props.getRedis();
        return buildFixedRetryTemplate(
                cfg.getMaxAttempts(),
                cfg.getIntervalMs(),
                "Redis", "publish");
    }

    @Bean
    public RetryTemplate webhookRetryTemplate() {
        var cfg = props.getWebhook();
        return buildFixedRetryTemplate(
                cfg.getMaxAttempts(),
                cfg.getIntervalMs(),
                "Webhook", "sendWebhook");
    }

    @Bean
    @ConditionalOnProperty(name = "dfetl.message-publish.transport", havingValue = "RABBITMQ")
    public RetryTemplate rabbitmqRetryTemplate() {
        var cfg = props.getRabbitmq();
        return buildFixedRetryTemplate(
                cfg.getMaxAttempts(),
                cfg.getIntervalMs(),
                "RabbitMQ", "publish");
    }

    /**
     * 共享 RestTemplate（供 AlertEvaluatorService 使用，替代每次 new）。
     * <p>SSRF 纵深防御：
     * <ul>
     *   <li>关闭自动重定向——防止 webhook 目标先返回 2xx 再用 3xx 跳转到内网/元数据端点绕过 URL 校验</li>
     *   <li>设置连接/读取超时——防止挂起的 webhook 占用线程</li>
     * </ul>
     * URL 本身的 SSRF 校验由 {@code WebhookUrlValidator} 在发送前完成。
     */
    @Bean
    public RestTemplate sharedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
                    throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                // 关闭重定向跟随：避免 webhook 通过 3xx 跳转到内网/云元数据绕过预校验
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        return new RestTemplate(factory);
    }

    // ── 内部构建方法 ────────────────────────────────────────────────────

    private RetryTemplate buildExponentialRetryTemplate(
            int maxAttempts, long initialInterval, double multiplier,
            String component, String operation) {
        RetryTemplate template = new RetryTemplate();

        // 仅对 RetryableException 重试，NonRetryableException 立即终止
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                maxAttempts,
                Map.of(RetryableException.class, true, NonRetryableException.class, false),
                true);
        template.setRetryPolicy(retryPolicy);

        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(initialInterval);
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(initialInterval * (long) Math.pow(multiplier, maxAttempts));
        template.setBackOffPolicy(backOff);

        template.registerListener(createRetryListener(component, operation, maxAttempts));
        return template;
    }

    private RetryTemplate buildFixedRetryTemplate(
            int maxAttempts, long intervalMs,
            String component, String operation) {
        RetryTemplate template = new RetryTemplate();

        // 仅对 RetryableException 重试，NonRetryableException 立即终止
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                maxAttempts,
                Map.of(RetryableException.class, true, NonRetryableException.class, false),
                true);
        template.setRetryPolicy(retryPolicy);

        FixedBackOffPolicy backOff = new FixedBackOffPolicy();
        backOff.setBackOffPeriod(intervalMs);
        template.setBackOffPolicy(backOff);

        template.registerListener(createRetryListener(component, operation, maxAttempts));
        return template;
    }

    private RetryListener createRetryListener(String component, String operation, int maxAttempts) {
        return new RetryListener() {
            @Override
            public <T, E extends Throwable> void onError(
                    RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                int attempt = context.getRetryCount();
                log.warn("[Retry:{}:{}] attempt {}/{}, error: {}",
                        component, operation, attempt, maxAttempts,
                        throwable.getMessage());
            }

            @Override
            public <T, E extends Throwable> void close(
                    RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                if (throwable == null && context.getRetryCount() > 0) {
                    log.info("[Retry:{}:{}] succeeded after {} retries",
                            component, operation, context.getRetryCount());
                } else if (throwable != null) {
                    log.error("[Retry:{}:{}] exhausted after {} attempts, final error: {}",
                            component, operation, context.getRetryCount() + 1,
                            throwable.getMessage());
                }
            }
        };
    }
}
