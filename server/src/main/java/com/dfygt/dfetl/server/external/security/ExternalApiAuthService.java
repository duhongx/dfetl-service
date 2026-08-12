package com.dfygt.dfetl.server.external.security;

import com.dfygt.dfetl.server.common.AesUtil;
import com.dfygt.dfetl.server.external.entity.ExternalApiClient;
import com.dfygt.dfetl.server.external.entity.ExternalApiRequestNonce;
import com.dfygt.dfetl.server.external.repository.ExternalApiClientRepository;
import com.dfygt.dfetl.server.external.repository.ExternalApiRequestNonceRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 外部 API HMAC-SHA256 认证。
 */
@Service
@RequiredArgsConstructor
public class ExternalApiAuthService {

    public static final String HEADER_CLIENT_ID = "X-DFETL-Client-Id";
    public static final String HEADER_TIMESTAMP = "X-DFETL-Timestamp";
    public static final String HEADER_NONCE = "X-DFETL-Nonce";
    public static final String HEADER_SIGNATURE = "X-DFETL-Signature";

    private static final long ALLOWED_CLOCK_SKEW_MILLIS = 5 * 60 * 1000L;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ExternalApiClientRepository clientRepository;
    private final ExternalApiRequestNonceRepository nonceRepository;
    private final AesUtil aesUtil;

    @Transactional
    public ExternalApiPrincipal authenticate(HttpServletRequest request, byte[] body) {
        String clientId = requiredHeader(request, HEADER_CLIENT_ID);
        String timestamp = requiredHeader(request, HEADER_TIMESTAMP);
        String nonce = requiredHeader(request, HEADER_NONCE);
        String signature = requiredHeader(request, HEADER_SIGNATURE);

        ExternalApiClient client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new BadCredentialsException("未知外部 API clientId"));
        if (!Boolean.TRUE.equals(client.getEnabled())) {
            throw new BadCredentialsException("外部 API client 已禁用");
        }

        validateTimestamp(timestamp);
        validateNonce(clientId, nonce);

        String secret;
        try {
            secret = aesUtil.decrypt(client.getSecretEnc());
        } catch (IllegalStateException e) {
            throw new BadCredentialsException("外部 API secret 解密失败", e);
        }
        String expected = sign(request.getMethod(), request.getRequestURI(), timestamp, nonce, body, secret);
        if (!constantTimeEquals(expected, normalizeSignature(signature))) {
            throw new BadCredentialsException("外部 API 签名无效");
        }

        saveNonce(clientId, nonce);
        return new ExternalApiPrincipal(
                client.getClientId(),
                client.getClientName(),
                client.getAllowedYiLiaoJgDm());
    }

    private void validateNonce(String clientId, String nonce) {
        if (nonce.length() > 100) {
            throw new BadCredentialsException("外部 API nonce 过长");
        }
        if (nonceRepository.existsByClientIdAndNonce(clientId, nonce)) {
            throw new BadCredentialsException("外部 API nonce 已使用");
        }
    }

    private void saveNonce(String clientId, String nonce) {
        ExternalApiRequestNonce entity = new ExternalApiRequestNonce();
        entity.setClientId(clientId);
        entity.setNonce(nonce);
        try {
            nonceRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw new BadCredentialsException("外部 API nonce 已使用", e);
        }
    }

    private static String requiredHeader(HttpServletRequest request, String header) {
        String value = request.getHeader(header);
        if (value == null || value.isBlank()) {
            throw new BadCredentialsException("缺少外部 API 认证头: " + header);
        }
        return value.trim();
    }

    private static void validateTimestamp(String timestamp) {
        long requestMillis;
        try {
            requestMillis = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new BadCredentialsException("外部 API timestamp 必须为 epoch milliseconds", e);
        }
        long delta = Math.abs(Instant.now().toEpochMilli() - requestMillis);
        if (delta > ALLOWED_CLOCK_SKEW_MILLIS) {
            throw new BadCredentialsException("外部 API timestamp 超出允许窗口");
        }
    }

    static String sign(String method,
                       String path,
                       String timestamp,
                       String nonce,
                       byte[] body,
                       String secret) {
        try {
            String bodyHash = sha256(body == null ? new byte[0] : body);
            String stringToSign = method.toUpperCase(Locale.ROOT)
                    + "\n" + path
                    + "\n" + timestamp
                    + "\n" + nonce
                    + "\n" + bodyHash;
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BadCredentialsException("外部 API 签名计算失败", e);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String normalizeSignature(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
