package com.dfygt.dfetl.server.dto;

import lombok.Data;

import java.time.Instant;

/**
 * 消息发布日志 DTO
 */
@Data
public class MessagePublishLogDto {

    private Long id;

    private Long taskId;

    private Long batchId;

    private String channel;

    private String topic;

    private Integer messageCount;

    private String status;

    private String errorMessage;

    private Instant publishTime;

    private String dataScope;

    private Instant windowStart;

    private Instant windowEnd;

    /** 本次发布的消息样本（前5条完整JSON数组），用于调试预览 */
    private String sampleMessages;
}
