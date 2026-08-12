package com.dfygt.dfetl.server.config;

import com.dfygt.dfetl.server.service.publish.Transport;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消息发布全局配置属性。
 */
@Data
@ConfigurationProperties(prefix = "dfetl.message-publish")
public class MessagePublishProperties {
    /** 传输方式：REDIS_STREAM 或 RABBITMQ */
    private Transport transport = Transport.REDIS_STREAM;
    /** topic 前缀（可选，默认空字符串） */
    private String topicPrefix = "";
    /** Doris 数据可见延迟（毫秒），等待 Stream Load 后 tablet publish */
    private long dorisVisibilityDelayMs = 5000;
    /** Doris 批次可见性的最长等待时间（毫秒）。 */
    private long dorisVisibilityTimeoutMs = 30000;
    /** Doris 批次可见性轮询间隔（毫秒）。 */
    private long dorisVisibilityPollIntervalMs = 500;
    /** RabbitMQ correlated confirm/return 的单批有界等待时间（毫秒） */
    private long rabbitConfirmTimeoutMs = 5000;
    /** 外部 API 首次全量发布默认限速（条/秒） */
    private int defaultRateLimit = 1000;
    /** 外部 API 允许请求的最大发布限速（条/秒） */
    private int maxRateLimit = 10000;
    /** PENDING 超过该秒数后允许按 executionId 受控重发。 */
    private long pendingRetryTimeoutSeconds = 1800;
    /** 生产者发送恢复扫描间隔（毫秒）。 */
    private long recoveryScanIntervalMs = 5000;
    /** SENDING 超过该秒数视为进程中断，可重新领取。 */
    private long recoveryStaleSeconds = 60;
    /** 单次恢复最多领取的消息数。 */
    private int recoveryBatchSize = 100;
    /** 同一稳定 messageId 的最大发送尝试次数。 */
    private int recoveryMaxAttempts = 5;
    /** 自动恢复的基础退避时间（毫秒），随后指数增长。 */
    private long recoveryBaseBackoffMs = 5000;
}
