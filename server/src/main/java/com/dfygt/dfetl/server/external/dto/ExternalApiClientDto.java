package com.dfygt.dfetl.server.external.dto;

import com.dfygt.dfetl.server.external.entity.ExternalApiClient;

import java.time.LocalDateTime;

/**
 * 管理端外部 API client 摘要，永不返回 secret 明文或密文。
 */
public record ExternalApiClientDto(
        Long id,
        String clientId,
        String clientName,
        Boolean enabled,
        String allowedYiLiaoJgDm,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ExternalApiClientDto from(ExternalApiClient client) {
        return new ExternalApiClientDto(
                client.getId(),
                client.getClientId(),
                client.getClientName(),
                client.getEnabled(),
                client.getAllowedYiLiaoJgDm(),
                client.getDescription(),
                client.getCreatedAt(),
                client.getUpdatedAt());
    }
}
