package com.dfygt.dfetl.server.config;

import com.dfygt.dfetl.server.security.JwtAuthFilter;
import com.dfygt.dfetl.server.external.security.ExternalApiAuthFilter;
import com.dfygt.dfetl.server.external.security.ExternalApiAuthService;
import com.dfygt.dfetl.server.web.LegacyTaskPathWarningFilter;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Optional;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final RequestMatcher SPA_NAVIGATION_REQUEST = SecurityConfig::isSpaNavigationRequest;

    private final LegacyTaskPathWarningFilter legacyTaskPathWarningFilter;
    private final JwtAuthFilter jwtAuthFilter;
    private final Optional<ExternalApiAuthService> externalApiAuthService;
    private final UserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/logout", "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                // OpenAPI 文档（仅供开发环境/CI 生成前端类型使用，生产环境建议在网关级屏蔽）
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // spec 047：使用文档（in-app docs，登录前/后均可访问）
                .requestMatchers(HttpMethod.GET, "/api/docs/**").permitAll()
                // 前端静态资源（打包在 jar 中）
                .requestMatchers("/", "/index.html", "/favicon.svg", "/icons.svg", "/assets/**").permitAll()
                // SPA 页面导航：浏览器刷新任意前端路由时交给 React Router，API 与运维端点仍需认证
                .requestMatchers(SPA_NAVIGATION_REQUEST).permitAll()
                // SSE 日志流（Tomcat async dispatch 需要放行）
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, authException) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(legacyTaskPathWarningFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        externalApiAuthService.ifPresent(authService ->
                http.addFilterAfter(new ExternalApiAuthFilter(authService), JwtAuthFilter.class));
        return http.build();
    }

    private static boolean isSpaNavigationRequest(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || !accept.contains(MediaType.TEXT_HTML_VALUE)) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !isProtectedBackendPath(path);
    }

    private static boolean isProtectedBackendPath(String path) {
        return isPathOrChild(path, "/api")
                || isPathOrChild(path, "/actuator")
                || isPathOrChild(path, "/v3")
                || isPathOrChild(path, "/swagger-ui")
                || "/swagger-ui.html".equals(path);
    }

    private static boolean isPathOrChild(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

}
