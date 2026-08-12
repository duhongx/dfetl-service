package com.dfygt.dfetl.server.external.dto;

/**
 * 创建或重置密钥时返回。secret 明文只在本响应中出现一次。
 */
public record ExternalApiClientSecretResponse(
        ExternalApiClientDto client,
        String secret
) {
}
