package com.dfygt.dfetl.server.external.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 外部 API HMAC 认证过滤器。
 */
@RequiredArgsConstructor
public class ExternalApiAuthFilter extends OncePerRequestFilter {

    private static final String EXTERNAL_API_PREFIX = "/api/v1/";

    private final ExternalApiAuthService authService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(EXTERNAL_API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getHeader(ExternalApiAuthService.HEADER_CLIENT_ID) == null) {
            filterChain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        try {
            ExternalApiPrincipal principal = authService.authenticate(cached, cached.body());
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_EXTERNAL_API")));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            ExternalApiSecurityContext.set(principal);
            filterChain.doFilter(cached, response);
        } catch (AuthenticationException e) {
            SecurityContextHolder.clearContext();
            ExternalApiSecurityContext.clear();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
        } finally {
            ExternalApiSecurityContext.clear();
        }
    }
}
