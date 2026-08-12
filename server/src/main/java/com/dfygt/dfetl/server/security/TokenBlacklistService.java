package com.dfygt.dfetl.server.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spec 051：JWT 黑名单。
 *
 * <p>内存实现：ConcurrentHashMap<token, expiryInstant>，每小时清理已过期条目。
 * 适合单实例部署；多实例部署需换用 Redis 或共享存储。
 */
@Slf4j
@Service
public class TokenBlacklistService {

    /** key = JWT 字符串，value = 该 token 的自然过期时刻（到期后可安全删除） */
    private final ConcurrentHashMap<String, Instant> blacklist = new ConcurrentHashMap<>();

    /** 加入黑名单。{@code expiry} 为该 token 的自然过期时刻，过期后该条目无效可删除。 */
    public void add(String token, Instant expiry) {
        blacklist.put(token, expiry);
    }

    /** 判断 token 是否在黑名单中（已过期的条目视为不在黑名单，顺带清理）。 */
    public boolean isBlacklisted(String token) {
        Instant expiry = blacklist.get(token);
        if (expiry == null) {
            return false;
        }
        if (Instant.now().isAfter(expiry)) {
            blacklist.remove(token);
            return false;
        }
        return true;
    }

    /** 每小时扫描一次，删除已自然过期的条目，防止内存持续增长。 */
    @Scheduled(fixedDelay = 3_600_000)
    public void cleanup() {
        Instant now = Instant.now();
        int removed = 0;
        var it = blacklist.entrySet().iterator();
        while (it.hasNext()) {
            if (now.isAfter(it.next().getValue())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("TokenBlacklist cleanup: removed {} expired entries", removed);
        }
    }
}
