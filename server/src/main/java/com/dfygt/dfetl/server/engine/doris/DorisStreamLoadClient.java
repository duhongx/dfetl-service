package com.dfygt.dfetl.server.engine.doris;

import com.dfygt.dfetl.server.common.IdentifierSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Spec 020.1：Doris Stream Load HTTP 客户端薄封装。
 *
 * <p>实现 FE→BE 的两段式 PUT：
 * <ol>
 *   <li>PUT FE（无 body） → 获取 307 Location（BE URL）</li>
 *   <li>PUT BE（有 body） → 直连，无需 Expect:100-continue（经验证 BE 直连时不需要该头）</li>
 * </ol>
 */
@Slf4j
@Component
public class DorisStreamLoadClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 主机名/IP 白名单：字母、数字、点、连字符、下划线（兼容 IPv4 与一般域名）。
     * 拒绝 `#` `?` `/` `@` 等可破坏 URI 解析的字符，防止 SSRF。
     */
    private static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,255}$");

    private static String requireValidHost(String host) {
        if (host == null || !HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("非法的 Doris BE 主机名：" + host);
        }
        return host;
    }
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 60_000;

    /**
     * 提交一次 Stream Load（直连 BE，跳过 FE 307 重定向）。
     *
     * @param beHost    BE 主机（与 FE 相同 IP，如 192.168.1.31）
     * @param bePort    BE HTTP 端口（通常 8040，即 streamLoadPort）
     * @param db        目标库名
     * @param table     目标表名
     * @param username  用户名
     * @param password  明文密码（调用方负责解密）
     * @param headers   Stream Load 头（label/columns/format/merge_type 等），不要重复设置 Authorization
     * @param body      请求体字节
     */
    public StreamLoadResult put(String beHost, int bePort,
                                String db, String table,
                                String username, String password,
                                Map<String, String> headers, byte[] body) throws Exception {
        // 注入防御：beHost / db / table 拼到 URL path 前必须经字符集白名单（防 SSRF / URL path 注入）
        String safeHost = requireValidHost(beHost);
        String safeDb = IdentifierSanitizer.requireValid(db, "db");
        String safeTable = IdentifierSanitizer.requireValid(table, "table");
        String label = headers.get("label");
        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        String beUrl = String.format("http://%s:%d/api/%s/%s/_stream_load",
                safeHost, bePort, safeDb, safeTable);
        log.debug("StreamLoad direct-BE url={} label={}", beUrl, label);
        return doPut(URI.create(beUrl).toURL(), basicAuth, headers, body, label);
    }

    /**
     * 直连 BE 提交 body（不设 Expect:100-continue，BE 直连时不需要该头）。
     * 不使用 setFixedLengthStreamingMode 以避免 Java Expect 握手时的 ProtocolException。
     */
    private StreamLoadResult doPut(URL url, String basicAuth, Map<String, String> headers,
                                   byte[] body, String label) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PUT");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", basicAuth);
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getValue() != null) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        try (var out = conn.getOutputStream()) {
            out.write(body);
        }
        int httpStatus = conn.getResponseCode();
        var stream = httpStatus / 100 == 2 ? conn.getInputStream() : conn.getErrorStream();
        String resp = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();
        return parse(httpStatus, resp, label);
    }

    private StreamLoadResult parse(int httpStatus, String body, String label) {
        if (body == null || body.isBlank()) {
            return new StreamLoadResult(httpStatus, "Unknown", 0L, 0L, "empty response", label);
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            return new StreamLoadResult(
                    httpStatus,
                    root.path("Status").asText("Unknown"),
                    root.path("NumberLoadedRows").asLong(0),
                    root.path("NumberFilteredRows").asLong(0),
                    root.path("Message").asText(""),
                    label);
        } catch (Exception e) {
            log.warn("StreamLoad response parse failed: {}", body, e);
            return new StreamLoadResult(httpStatus, "ParseError", 0L, 0L, body, label);
        }
    }
}
