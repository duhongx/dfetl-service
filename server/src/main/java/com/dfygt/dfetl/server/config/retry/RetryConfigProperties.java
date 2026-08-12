package com.dfygt.dfetl.server.config.retry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 重试与断路器配置属性。
 * 所有参数可通过 application.yml 的 dfetl.retry.* 配置节覆盖。
 */
@Data
@ConfigurationProperties(prefix = "dfetl.retry")
public class RetryConfigProperties {

    private SeaTunnelRetryConfig seatunnel = new SeaTunnelRetryConfig();
    private RedisRetryConfig redis = new RedisRetryConfig();
    private WebhookRetryConfig webhook = new WebhookRetryConfig();
    private TaskExecutionRetryConfig taskExecution = new TaskExecutionRetryConfig();
    private RabbitmqRetryConfig rabbitmq = new RabbitmqRetryConfig();

    @Data
    public static class SeaTunnelRetryConfig {
        /** submitJob 最大重试次数 */
        private int submitMaxAttempts = 3;
        /** submitJob 初始重试间隔（ms） */
        private long submitInitialIntervalMs = 1000;
        /** submitJob 退避倍数 */
        private double submitMultiplier = 2.0;
        /** getJobInfo 最大重试次数 */
        private int queryMaxAttempts = 3;
        /** getJobInfo 初始重试间隔（ms） */
        private long queryInitialIntervalMs = 500;
        /** getJobInfo 退避倍数 */
        private double queryMultiplier = 2.0;
        /** stopJob 最大重试次数 */
        private int stopMaxAttempts = 2;
        /** stopJob 初始重试间隔（ms） */
        private long stopInitialIntervalMs = 1000;
        /** stopJob 退避倍数 */
        private double stopMultiplier = 2.0;
        /** 断路器：连续失败阈值 */
        private int circuitBreakerThreshold = 5;
        /** 断路器：OPEN 状态冷却时间（ms） */
        private long circuitBreakerCooldownMs = 30000;
    }

    @Data
    public static class RedisRetryConfig {
        /** Redis XADD 最大重试次数 */
        private int maxAttempts = 3;
        /** Redis XADD 重试间隔（ms） */
        private long intervalMs = 500;
    }

    @Data
    public static class WebhookRetryConfig {
        /** Webhook 推送最大重试次数 */
        private int maxAttempts = 3;
        /** Webhook 推送重试间隔（ms） */
        private long intervalMs = 2000;
    }

    @Data
    public static class TaskExecutionRetryConfig {
        /** 任务自动重试默认最大次数（0=不重试） */
        private int defaultMaxAttempts = 0;
        /** 任务自动重试默认间隔（秒） */
        private int defaultIntervalSeconds = 30;
    }

    @Data
    public static class RabbitmqRetryConfig {
        /** RabbitMQ 发布最大重试次数 */
        private int maxAttempts = 3;
        /** RabbitMQ 发布重试间隔（ms） */
        private long intervalMs = 500;
    }
}
