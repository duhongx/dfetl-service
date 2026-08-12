package com.dfygt.dfetl.server.external.security;

import java.util.Optional;

/**
 * 保存当前请求的外部 API 身份；后台 JWT 请求不会设置该上下文。
 */
public final class ExternalApiSecurityContext {

    private static final ThreadLocal<ExternalApiPrincipal> CURRENT = new ThreadLocal<>();

    private ExternalApiSecurityContext() {
    }

    public static Optional<ExternalApiPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void set(ExternalApiPrincipal principal) {
        CURRENT.set(principal);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
