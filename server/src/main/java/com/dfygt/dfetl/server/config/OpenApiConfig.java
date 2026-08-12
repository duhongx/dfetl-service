package com.dfygt.dfetl.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class OpenApiConfig {

    private static final String LEGACY_TASK_PREFIX = "/api/task";
    private static final String CANONICAL_SYNC_TASK_PREFIX = "/api/sync-task";
    private static final String EXTERNAL_AUTHORIZATION_GROUP = "external-authorization";

    @Bean
    public OpenApiCustomizer syncTaskPathOpenApiCustomizer() {
        return this::rewriteSyncTaskPaths;
    }

    @Bean
    public GlobalOpenApiCustomizer globalSyncTaskPathOpenApiCustomizer() {
        return this::rewriteSyncTaskPaths;
    }

    @Bean
    public GroupedOpenApi externalAuthorizationOpenApiGroup() {
        return GroupedOpenApi.builder()
                .group(EXTERNAL_AUTHORIZATION_GROUP)
                .pathsToMatch("/api/v1/**")
                .addOpenApiCustomizer(externalAuthorizationOpenApiCustomizer())
                .build();
    }

    OpenApiCustomizer externalAuthorizationOpenApiCustomizer() {
        return this::customizeExternalAuthorizationOpenApi;
    }

    static String rewritePath(String path) {
        if (path == null) {
            return null;
        }
        if (path.equals(LEGACY_TASK_PREFIX) || path.startsWith(LEGACY_TASK_PREFIX + "/")) {
            return CANONICAL_SYNC_TASK_PREFIX + path.substring(LEGACY_TASK_PREFIX.length());
        }
        return path;
    }

    private void rewriteSyncTaskPaths(io.swagger.v3.oas.models.OpenAPI openApi) {
        Paths paths = openApi.getPaths();
        if (paths == null || paths.isEmpty()) {
            return;
        }
        Paths rewritten = new Paths();
        for (Map.Entry<String, PathItem> entry : paths.entrySet()) {
            rewritten.addPathItem(rewritePath(entry.getKey()), entry.getValue());
        }
        openApi.setPaths(rewritten);
    }

    private void customizeExternalAuthorizationOpenApi(io.swagger.v3.oas.models.OpenAPI openApi) {
        openApi.setInfo(new Info()
                .title("df-etl 外部授权 API")
                .version("v1")
                .description("""
                        面向第三方系统的同步任务接口，只暴露 /api/v1/** 业务接口。
                        调用方使用 HMAC-SHA256 签名认证，签名串为 METHOD、REQUEST_URI、TIMESTAMP、NONCE 和请求体 SHA256 摘要按行拼接。
                        shared secret 只在外部授权调用方创建或重置时展示一次，不会通过接口返回。
                        """));

        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }

        components.addSecuritySchemes("dfetlClientId", headerScheme("X-DFETL-Client-Id", "外部授权调用方 Client ID"))
                .addSecuritySchemes("dfetlTimestamp", headerScheme("X-DFETL-Timestamp", "epoch milliseconds，允许 5 分钟时钟偏差"))
                .addSecuritySchemes("dfetlNonce", headerScheme("X-DFETL-Nonce", "单次请求随机串，同一 Client ID 下不可重复"))
                .addSecuritySchemes("dfetlSignature", headerScheme("X-DFETL-Signature", "HMAC-SHA256 十六进制小写签名"));

        openApi.setSecurity(List.of(new SecurityRequirement()
                .addList("dfetlClientId")
                .addList("dfetlTimestamp")
                .addList("dfetlNonce")
                .addList("dfetlSignature")));
    }

    private SecurityScheme headerScheme(String name, String description) {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(name)
                .description(description);
    }
}
