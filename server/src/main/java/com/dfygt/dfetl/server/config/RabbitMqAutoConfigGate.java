package com.dfygt.dfetl.server.config;

import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * RabbitMQ 自动配置门控 — 仅在 transport=RABBITMQ 时重新导入 RabbitAutoConfiguration。
 * <p>
 * 主应用类通过 exclude 排除了 RabbitAutoConfiguration，
 * 本类在 transport=RABBITMQ 时将其重新导入，确保 ConnectionFactory 等基础设施 Bean 正常创建。
 */
@Configuration
@ConditionalOnProperty(name = "dfetl.message-publish.transport", havingValue = "RABBITMQ")
@Import(RabbitAutoConfiguration.class)
public class RabbitMqAutoConfigGate {
}
