package com.dfygt.dfetl.server.common;

import com.dfygt.dfetl.server.config.DataSourcePoolProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部数据源（源库 / 目标 Doris）JDBC 连接池管理器。
 * <p>
 * 统一收口运行时热路径上散落的 {@code DriverManager.getConnection}——按
 * "jdbcUrl + username + password" 作为身份键复用 HikariCP 连接池，避免：
 * <ul>
 *   <li>每次 count/checksum/diff/审计回读都新建并丢弃连接（多表大任务 2N 次连接）</li>
 *   <li>突发并发时连接数无上限，压垮外部库</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>身份键里的 password 不落日志、用 SHA-256 摘要参与 key，避免明文驻留 map key</li>
 *   <li>池数量按 {@link DataSourcePoolProperties#getMaxPools()} 上限 + LRU 淘汰，防无限增长</li>
 *   <li>{@code enabled=false} 时回退为每次直连（DriverManager 单连接，调用方仍 try-with-resources 关闭）</li>
 *   <li>Bean 销毁时关闭所有池（优雅停机）</li>
 * </ul>
 *
 * <p>调用方仍用 try-with-resources，从池借出的连接 {@code close()} 时归还而非真正关闭。
 */
@Slf4j
@Component
public class JdbcConnectionPoolManager {

    private final DataSourcePoolProperties properties;

    /** accessOrder=true 的 LinkedHashMap 实现 LRU。key = url|user|pwdHash。 */
    private final Map<String, HikariDataSource> pools;

    public JdbcConnectionPoolManager(DataSourcePoolProperties properties) {
        this.properties = properties;
        this.pools = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * 获取一个连接（池化或直连，取决于配置）。调用方负责 {@code close()}（try-with-resources）。
     *
     * @param jdbcUrl  JDBC URL（调用方已按各自方言拼装 + 校验）
     * @param username 用户名
     * @param password 明文密码（已解密）
     */
    public Connection getConnection(String jdbcUrl, String username, String password) throws SQLException {
        if (!properties.isEnabled()) {
            // 回退：直连单连接，行为与历史一致
            return java.sql.DriverManager.getConnection(jdbcUrl, username, password);
        }
        HikariDataSource ds = poolFor(jdbcUrl, username, password);
        return ds.getConnection();
    }

    private synchronized HikariDataSource poolFor(String jdbcUrl, String username, String password) {
        String key = poolKey(jdbcUrl, username, password);
        HikariDataSource existing = pools.get(key); // accessOrder=true：get 即刷新 LRU
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        evictIfNeeded();
        HikariDataSource created = buildPool(jdbcUrl, username, password);
        pools.put(key, created);
        log.info("JdbcConnectionPoolManager: created pool for url={} user={} (total pools={})",
                sanitizeUrl(jdbcUrl), username, pools.size());
        return created;
    }

    /** 超出 maxPools 时关闭最久未使用（LRU 头部）的池。 */
    private void evictIfNeeded() {
        while (pools.size() >= properties.getMaxPools()) {
            Iterator<Map.Entry<String, HikariDataSource>> it = pools.entrySet().iterator();
            if (!it.hasNext()) break;
            Map.Entry<String, HikariDataSource> eldest = it.next();
            it.remove();
            closeQuietly(eldest.getValue());
            log.info("JdbcConnectionPoolManager: evicted LRU pool (max-pools={} reached)", properties.getMaxPools());
        }
    }

    private HikariDataSource buildPool(String jdbcUrl, String username, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(properties.getMaxPoolSize());
        cfg.setMinimumIdle(properties.getMinIdle());
        cfg.setConnectionTimeout(properties.getConnectionTimeoutMs());
        cfg.setIdleTimeout(properties.getIdleTimeoutMs());
        cfg.setMaxLifetime(properties.getMaxLifetimeMs());
        cfg.setValidationTimeout(properties.getValidationTimeoutMs());
        cfg.setPoolName("ext-" + Integer.toHexString(jdbcUrl.hashCode()));
        // 初始化失败不阻塞应用启动（外部库可能临时不可达）
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }

    /** 身份键：url|user|sha256(password)，避免明文密码作为 map key 驻留内存。 */
    private String poolKey(String jdbcUrl, String username, String password) {
        return jdbcUrl + "|" + (username == null ? "" : username) + "|" + sha256(password);
    }

    private String sha256(String value) {
        if (value == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            // 退化：用 hashCode（不影响功能，仅 key 唯一性略弱）
            return Integer.toHexString(value.hashCode());
        }
    }

    /** 日志脱敏：JDBC URL 通常不含密码，但去掉可能的 user/password 查询参数。 */
    private String sanitizeUrl(String url) {
        if (url == null) return "null";
        return url.replaceAll("(?i)(password|user)=[^&]*", "$1=***");
    }

    private void closeQuietly(HikariDataSource ds) {
        if (ds != null && !ds.isClosed()) {
            try {
                ds.close();
            } catch (Exception e) {
                log.warn("JdbcConnectionPoolManager: close pool failed: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        log.info("JdbcConnectionPoolManager: closing {} external pools on shutdown", pools.size());
        pools.values().forEach(this::closeQuietly);
        pools.clear();
    }

    /** 当前活跃池数量（用于监控与测试）。 */
    public synchronized int activePoolCount() {
        return pools.size();
    }
}
