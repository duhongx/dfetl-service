package com.dfygt.dfetl.server.external.security;

/**
 * 外部 API 认证后的调用方身份。
 */
public record ExternalApiPrincipal(
        String clientId,
        String clientName,
        String allowedYiLiaoJgDm) {
}
