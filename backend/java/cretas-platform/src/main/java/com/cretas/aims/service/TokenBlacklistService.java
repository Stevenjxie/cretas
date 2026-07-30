package com.cretas.aims.service;

/**
 * Token黑名单服务接口
 * 用于登出后使已发放的JWT Token立即失效
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-28
 */
public interface TokenBlacklistService {

    /**
     * 将token加入黑名单
     *
     * @param token 要拉黑的JWT token
     * @param remainingTtlMs 该token剩余有效期（毫秒），黑名单条目在此之后自动过期
     */
    void blacklistToken(String token, long remainingTtlMs);

    /**
     * 检查token是否已被拉黑
     *
     * @param token JWT token
     * @return 如果token在黑名单中返回true
     */
    boolean isBlacklisted(String token);

    /**
     * 拉黑指定用户的所有token
     * 通过记录userId级别的撤销标记实现
     *
     * @param userId 用户ID
     */
    void blacklistAllUserTokens(Long userId);
}
