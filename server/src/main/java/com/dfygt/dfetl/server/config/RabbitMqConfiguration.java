package com.dfygt.dfetl.server.config;

import com.dfygt.dfetl.server.service.publish.MessageSendRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * RabbitMQ 基础设施配置 — 仅在 transport=RABBITMQ 时加载。
 * 通过 @PostConstruct 配置 Spring Boot 自动创建的 RabbitTemplate（设置 mandatory + ConfirmCallback）。
 * 不再自定义 rabbitTemplate Bean，避免与 RabbitAutoConfiguration 冲突。
 */
@Configuration
@ConditionalOnProperty(name = "dfetl.message-publish.transport", havingValue = "RABBITMQ")
@Slf4j
public class RabbitMqConfiguration {

    private final RabbitTemplate rabbitTemplate;
    private final MessageSendRecordService sendRecordService;

    public RabbitMqConfiguration(RabbitTemplate rabbitTemplate,
                                 MessageSendRecordService sendRecordService) {
        this.rabbitTemplate = rabbitTemplate;
        this.sendRecordService = sendRecordService;
    }

    @PostConstruct
    public void configureRabbitTemplate() {
        rabbitTemplate.setMandatory(true);

        // 异步 Publisher Confirm 回调
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("[RabbitMQ:Confirm] nack received, cause={}, correlationData={}",
                        cause, correlationData);
                markFailed(correlationData, "RabbitMQ nack: " + (cause != null ? cause : "nack without cause"));
            }
        });

        rabbitTemplate.setReturnsCallback(returned -> {
            String messageId = returned.getMessage().getMessageProperties().getCorrelationId();
            String error = "RabbitMQ returned: replyCode=" + returned.getReplyCode()
                    + ", replyText=" + returned.getReplyText()
                    + ", exchange=" + returned.getExchange()
                    + ", routingKey=" + returned.getRoutingKey();
            log.error("[RabbitMQ:Return] {}", error);
            markFailed(messageId, error);
        });
        log.info("RabbitMqConfiguration: RabbitTemplate configured with mandatory=true, ConfirmCallback and ReturnsCallback");
    }

    private void markFailed(CorrelationData correlationData, String error) {
        markFailed(correlationData != null ? correlationData.getId() : null, error);
    }

    private void markFailed(String messageId, String error) {
        try {
            sendRecordService.markFailed(messageId, error);
        } catch (Exception e) {
            log.error("[RabbitMQ] failed to mark SEND_FAILED messageId={}: {}", messageId, e.getMessage());
        }
    }
}
