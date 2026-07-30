package com.cretas.aims.service.impl;

import com.cretas.aims.service.TokenBlacklistService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * Token黑名单服务实现
 * 使用Caffeine内存缓存，每条entry的TTL等于对应token的剩余有效期，
 * 过期后自动移除（token本身也已失效，无需继续保留）。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-28
 */
@Service
public class TokenBlacklistServiceImpl implements TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistServiceImpl.class);

    /** 最大黑名单条目数，LRU淘汰 */
    private static final int MAX_ENTRIES = 10_000;

    /** 默认TTL：24小时（与accessToken默认有效期一致） */
    private static final long DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L;

    /**
     * 黑名单缓存：key=tokenHash, value=过期时间戳(ms)
     * 使用variable expiration，每条entry在对应token过期时自动清除
     */
    private final Cache<String, Long> blacklistedTokens;

    public TokenBlacklistServiceImpl() {
        this.blacklistedTokens = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfter(new Expiry<String, Long>() {
                    @Override
                    public long expireAfterCreate(String key, Long expirationTimestamp, long currentTime) {
                        long remainingMs = expirationTimestamp - System.currentTimeMillis();
                        return TimeUnit.MILLISECONDS.toNanos(Math.max(remainingMs, 0));
                    }

                    @Override
                    public long expireAfterUpdate(String key, Long expirationTimestamp,
                            long currentTime, long currentDuration) {
                        return currentDuration; // 不变
                    }

                    @Override
                    public long expireAfterRead(String key, Long expirationTimestamp,
                            long currentTime, long currentDuration) {
                        return currentDuration; // 不变
                    }
                })
                .build();
    }

    @Override
    public void blacklistToken(String token, long remainingTtlMs) {
        if (token == null || token.isBlank()) {
            return;
        }
        String hash = hashToken(token);
        long ttl = remainingTtlMs > 0 ? remainingTtlMs : DEFAULT_TTL_MS;
        long expirationTimestamp = System.currentTimeMillis() + ttl;
        blacklistedTokens.put(hash, expirationTimestamp);
        log.info("Token已加入黑名单: hash={}, ttl={}ms", hash.substring(0, 8), ttl);
    }

    @Override
    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String hash = hashToken(token);
        Long expirationTimestamp = blacklistedTokens.getIfPresent(hash);
        if (expirationTimestamp == null) {
            return false;
        }
        // 双重检查：缓存条目可能尚未被Caffeine的惰性清理移除
        if (System.currentTimeMillis() > expirationTimestamp) {
            blacklistedTokens.invalidate(hash);
            return false;
        }
        return true;
    }

    @Override
    public void blacklistAllUserTokens(Long userId) {
        // 此方法通过SessionRepository在调用方（MobileServiceImpl）中实现：
        // 查出用户所有活跃session的token，逐个加入黑名单。
        // 这里不需要额外的userId级别索引，因为session数量有限。
        log.info("用户所有Token拉黑请求: userId={} (由调用方遍历session实现)", userId);
    }

    /**
     * 对token做SHA-256哈希，避免在内存中存储完整token
     */
    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all JVMs
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
