package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * spec 047 D：使用文档（in-app docs）。
 *
 * <p>文档源 = {@code server/src/main/resources/userdocs/}（classpath）。
 * 启动时一次性把 markdown 内容读入内存，避免每次请求都走 jar 文件 I/O。
 *
 * <p>无需登录即可访问（SecurityConfig 已放行 {@code /api/docs/**}）。
 */
@Slf4j
@RestController
@RequestMapping("/api/docs")
public class DocsController {

    /** path 安全校验：仅允许 a-z A-Z 0-9 / _ - . */
    private static final Pattern SAFE_PATH = Pattern.compile("^[a-zA-Z0-9._/-]+$");

    private final Map<String, String> contentCache = new HashMap<>();
    private Map<String, Object> indexCache;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void preload() {
        // 加载 index.json
        try (InputStream is = new ClassPathResource("userdocs/index.json").getInputStream()) {
            indexCache = objectMapper.readValue(is, Map.class);
            log.info("DocsController preload: index.json loaded with {} top-level entries",
                    indexCache.size());
        } catch (Exception e) {
            log.warn("DocsController preload: index.json missing or invalid: {}", e.getMessage());
            indexCache = Map.of("error", "index.json not found");
        }
    }

    /** 返回文档目录树（来自 userdocs/index.json）。 */
    @GetMapping("/tree")
    public ApiResponse<Map<String, Object>> tree() {
        return ApiResponse.ok(indexCache);
    }

    /**
     * 读取单篇文档。path 不含前缀 {@code userdocs/}，如：
     * {@code zh-cn/02-sync-strategies/01-full-only.md}
     */
    @GetMapping("/page")
    public ApiResponse<Map<String, Object>> page(@RequestParam String path) {
        if (path == null || !SAFE_PATH.matcher(path).matches() || path.contains("..")) {
            return ApiResponse.error(400, "非法 path");
        }
        if (!path.endsWith(".md")) path = path + ".md";

        String cached = contentCache.get(path);
        if (cached != null) {
            return ApiResponse.ok(Map.of("path", path, "content", cached));
        }

        ClassPathResource res = new ClassPathResource("userdocs/" + path);
        if (!res.exists()) {
            return ApiResponse.error(404, "文档不存在: " + path);
        }
        try (InputStream is = res.getInputStream()) {
            String md = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            contentCache.put(path, md);
            return ApiResponse.ok(Map.of("path", path, "content", md));
        } catch (Exception e) {
            log.error("读取文档失败 path={}", path, e);
            return ApiResponse.error(500, "读取失败: " + e.getMessage());
        }
    }
}
