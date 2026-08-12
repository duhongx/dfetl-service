package com.dfygt.dfetl.server.service.alert;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 钉钉自定义机器人加签工具。
 *
 * <p>生成完整 URL query 片段（形如 {@code "&timestamp={ts}&sign={URLEncoded}"}），
 * 调用方拼到 webhookUrl 末尾即可。
 *
 * <p>无状态、纯函数、不依赖 Spring。
 */
public final class DingTalkSignatureCodec {

    private DingTalkSignatureCodec() {}

    /**
     * 计算钉钉加签 query 片段。
     *
     * @param secret         钉钉机器人安全设置的 secret，不能为空
     * @param timestampMillis 当前时间戳（毫秒）
     * @return "&timestamp={ts}&sign={URLEncoded sign}"
     * @throws IllegalArgumentException secret 为 null 或空
     * @throws IllegalStateException    HmacSHA256 不可用（理论上 JDK 必有）
     */
    public static String sign(String secret, long timestampMillis) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("钉钉加签 secret 不能为空");
        }
        try {
            String stringToSign = timestampMillis + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String sign = URLEncoder.encode(
                    Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
            return "&timestamp=" + timestampMillis + "&sign=" + sign;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
