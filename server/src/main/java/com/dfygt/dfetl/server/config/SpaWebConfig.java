package com.dfygt.dfetl.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * SPA 路由支持：非 API、非静态资源的请求全部返回 index.html，
 * 由前端 React Router 处理路由。
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        // 如果请求的是真实存在的静态文件，直接返回
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // 否则返回 index.html（SPA 路由回退）
                        // 但排除 API 路径（由 Controller 处理）
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("v3/")) {
                            return null;
                        }
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
