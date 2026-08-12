package com.dfygt.dfetl.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;

/**
 * 消息发布配置 DTO
 */
@Data
public class MessagePublishConfigDto {

    private Long id;

    @NotNull
    private Long taskId;

    private boolean enabled;

    private String channel;

    @NotBlank
    private String messageType;

    @NotBlank
    private String topic;

    private String messageKeyTemplate;

    /**
     * 请求中为 null 表示调用方未提供；create/update 的缺省语义由 Service 分别处理。
     * 响应 DTO 会从持久化 Entity 显式回填该字段。
     */
    private String fullSyncMode;

    private Integer rateLimit;

    private Integer pageSize = 1000;

    private String sourceSystem;

    private String tenantId;

    private String fieldMappingJson;

    private Instant createdAt;

    private Instant updatedAt;
}
