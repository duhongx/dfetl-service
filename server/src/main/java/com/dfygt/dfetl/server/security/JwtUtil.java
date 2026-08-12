package com.dfygt.dfetl.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Component
public class JwtUtil {

    @Value("${dfetl.jwt.secret}")
    private String secret;

    @Value("${dfetl.jwt.expiration-seconds}")
    private long expirationSeconds;

    @Value("${dfetl.jwt.refresh-expiration-seconds:604800}")
    private long refreshExpirationSeconds;

    private static final String CLAIM_TYPE   = "type";
    private static final String CLAIM_REFRESH_VERSION = "refreshVersion";
    private static final String TYPE_ACCESS  = "access";
    private static final String TYPE_REFRESH = "refresh";

    private SecretKey key;

    @PostConstruct
    public void init() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // HMAC-SHA256 requires at least 32 bytes
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationSeconds * 1000L);
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiry)
                .claims(Map.of(CLAIM_TYPE, TYPE_ACCESS))
                .signWith(key)
                .compact();
    }

    /** 生成 Refresh Token，有效期默认 7 天，type=refresh。 */
    public String generateRefreshToken(String username, int refreshTokenVersion) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshExpirationSeconds * 1000L);
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiry)
                .claims(Map.of(
                        CLAIM_TYPE, TYPE_REFRESH,
                        CLAIM_REFRESH_VERSION, refreshTokenVersion
                ))
                .signWith(key)
                .compact();
    }

    /** 提取 token 的自然过期时刻（用于黑名单存储）。 */
    public Instant extractExpiration(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    /** 判断是否是 access token（type 字段为 access 或未设置）。 */
    public boolean isAccessToken(String token) {
        String type = (String) parseClaims(token).get(CLAIM_TYPE);
        return type == null || TYPE_ACCESS.equals(type);
    }

    /** 判断是否是 refresh token（type 字段为 refresh）。 */
    public boolean isRefreshToken(String token) {
        return TYPE_REFRESH.equals(parseClaims(token).get(CLAIM_TYPE));
    }

    public int extractRefreshTokenVersion(String token) {
        Object value = parseClaims(token).get(CLAIM_REFRESH_VERSION);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean validateToken(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isExpired(token);
    }

    private boolean isExpired(String token) {
        return parseClaims(token).getExpiration().before(new Date());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
