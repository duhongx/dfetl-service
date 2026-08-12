package com.dfygt.dfetl.server.external.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 外部 API 机构授权校验。
 */
@Service
public class ExternalApiAuthorizationService {

    public void assertAllowed(String yiLiaoJgDm) {
        ExternalApiPrincipal principal = ExternalApiSecurityContext.current().orElse(null);
        if (principal == null) {
            return;
        }
        if (!matches(principal.allowedYiLiaoJgDm(), yiLiaoJgDm)) {
            throw new AccessDeniedException("外部客户端无权访问机构: " + yiLiaoJgDm);
        }
    }

    private static boolean matches(String allowed, String actual) {
        if (allowed == null || allowed.isBlank() || "*".equals(allowed.trim())) {
            return true;
        }
        if (actual == null || actual.isBlank()) {
            return false;
        }
        return allowed.trim().toUpperCase(Locale.ROOT)
                .equals(actual.trim().toUpperCase(Locale.ROOT));
    }
}
