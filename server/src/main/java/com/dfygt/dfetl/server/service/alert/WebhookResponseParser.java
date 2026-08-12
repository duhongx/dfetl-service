package com.dfygt.dfetl.server.service.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 钉钉/企微 Webhook 业务响应码解析。
 *
 * <p>钉钉/企微返回的 JSON 体形如 {@code {"errcode":0,"errmsg":"ok"}}：
 * <ul>
 *   <li>{@code errcode == 0} 视为成功</li>
 *   <li>{@code errcode != 0} 视为业务失败（签名错 310000 / 关键词不匹配 / IP 不允许等）</li>
 *   <li>无 {@code errcode} 字段视为成功（非钉钉/企微 webhook 兼容）</li>
 *   <li>响应体非 JSON（HTML 错误页等）视为成功（不误判，由 HTTP 状态码兜底）</li>
 * </ul>
 *
 * <p>无状态、纯函数、不依赖 Spring。
 */
public final class WebhookResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebhookResponseParser() {}

    /** 判定响应是否成功。 */
    public static boolean isSuccess(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return true;
        }
        try {
            JsonNode node = MAPPER.readTree(responseBody);
            JsonNode errcode = node.get("errcode");
            if (errcode == null || !errcode.isNumber()) {
                return true;
            }
            return errcode.asInt() == 0;
        } catch (Exception e) {
            return true;
        }
    }

    /** 提取 errmsg 字段；无字段返回空串。 */
    public static String extractErrmsg(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(responseBody);
            return node.path("errmsg").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
