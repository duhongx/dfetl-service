package com.dfygt.dfetl.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 告警通知渠道（Webhook）
 * type: dingtalk | wecom
 * lastTestStatus: ok | fail | untested
 * messageFormat: text | markdown
 * 钉钉自定义机器人 secret 非空时启用 HMAC-SHA256 加签
 * mentionedMobiles 逗号分隔；atAll 控制 @所有人
 */
@Entity
@Table(name = "alert_channel")
@Getter
@Setter
@NoArgsConstructor
public class AlertChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** dingtalk | wecom */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "webhook_url", nullable = false, columnDefinition = "TEXT")
    private String webhookUrl;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    /** ok | fail | untested */
    @Column(name = "last_test_status", length = 20)
    private String lastTestStatus = "untested";

    /** 钉钉自定义机器人加签密钥；可空（不加签机器人）。 */
    @Column(name = "secret", columnDefinition = "TEXT")
    private String secret;

    /** 逗号分隔的 @ 手机号列表；可空。最多 30 个。 */
    @Column(name = "mentioned_mobiles", columnDefinition = "TEXT")
    private String mentionedMobiles;

    /** 是否 @所有人；默认 false。 */
    @Column(name = "at_all", nullable = false)
    private Boolean atAll = false;

    /** 消息格式：text | markdown，默认 text。 */
    @Column(name = "message_format", length = 20, nullable = false)
    private String messageFormat = "text";

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();
}
