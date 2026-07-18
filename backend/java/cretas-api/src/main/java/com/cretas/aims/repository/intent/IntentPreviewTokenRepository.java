package com.cretas.aims.repository.intent;

import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 意图预览令牌 Repository
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@Repository
public interface IntentPreviewTokenRepository extends JpaRepository<IntentPreviewToken, Long> {

    /**
     * 根据令牌值查找
     */
    Optional<IntentPreviewToken> findByToken(String token);

    Optional<IntentPreviewToken> findByTokenAndClaimIdAndStatus(
            String token, String claimId, TokenStatus status);

    /**
     * 根据令牌值和状态查找
     */
    Optional<IntentPreviewToken> findByTokenAndStatus(String token, TokenStatus status);

    /**
     * Atomically acquires the only execution lease for a confirmation token.
     *
     * <p>The expected digest is recomputed by the service from persisted bindings before this
     * update. Factory, tenant, user, status and expiry remain database predicates so a stale read
     * or concurrent request cannot bypass them.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE IntentPreviewToken t SET t.status = :executingStatus, " +
           "t.claimId = :claimId, t.claimedAt = :claimedAt " +
           "WHERE t.token = :token AND t.factoryId = :factoryId AND t.tenantId = :tenantId " +
           "AND t.userId = :userId AND t.commandDigest = :expectedCommandDigest " +
           "AND t.parametersHash = :expectedParametersHash AND t.toolName = :expectedToolName " +
           "AND t.descriptorVersion = :expectedDescriptorVersion AND t.executionMode = :expectedMode " +
           "AND t.status = :pendingStatus AND t.expiresAt > :now")
    int claimForExecution(@Param("token") String token,
                          @Param("factoryId") String factoryId,
                          @Param("tenantId") String tenantId,
                          @Param("userId") Long userId,
                          @Param("expectedCommandDigest") String expectedCommandDigest,
                          @Param("expectedParametersHash") String expectedParametersHash,
                          @Param("expectedToolName") String expectedToolName,
                          @Param("expectedDescriptorVersion") String expectedDescriptorVersion,
                          @Param("expectedMode") ToolExecutionMode expectedMode,
                          @Param("claimId") String claimId,
                          @Param("claimedAt") LocalDateTime claimedAt,
                          @Param("now") LocalDateTime now,
                          @Param("pendingStatus") TokenStatus pendingStatus,
                          @Param("executingStatus") TokenStatus executingStatus);

    /** Only the current lease owner can resolve an executing token. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE IntentPreviewToken t SET t.status = :resolvedStatus, " +
           "t.resolvedAt = :resolvedAt, t.resolutionMessage = :message " +
           "WHERE t.token = :token AND t.claimId = :claimId AND t.status = :executingStatus")
    int resolveClaim(@Param("token") String token,
                     @Param("claimId") String claimId,
                     @Param("executingStatus") TokenStatus executingStatus,
                     @Param("resolvedStatus") TokenStatus resolvedStatus,
                     @Param("resolvedAt") LocalDateTime resolvedAt,
                     @Param("message") String message);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE IntentPreviewToken t SET t.status = :expiredStatus, " +
           "t.resolvedAt = :now, t.resolutionMessage = '令牌已过期' " +
           "WHERE t.token = :token AND t.factoryId = :factoryId AND t.userId = :userId " +
           "AND t.status = :pendingStatus AND t.expiresAt <= :now")
    int expirePendingToken(@Param("token") String token,
                           @Param("factoryId") String factoryId,
                           @Param("userId") Long userId,
                           @Param("now") LocalDateTime now,
                           @Param("pendingStatus") TokenStatus pendingStatus,
                           @Param("expiredStatus") TokenStatus expiredStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE IntentPreviewToken t SET t.status = :cancelledStatus, " +
           "t.resolvedAt = :now, t.resolutionMessage = :reason " +
           "WHERE t.token = :token AND t.factoryId = :factoryId " +
           "AND t.userId = :userId AND t.status = :pendingStatus")
    int cancelPendingToken(@Param("token") String token,
                           @Param("factoryId") String factoryId,
                           @Param("userId") Long userId,
                           @Param("reason") String reason,
                           @Param("now") LocalDateTime now,
                           @Param("pendingStatus") TokenStatus pendingStatus,
                           @Param("cancelledStatus") TokenStatus cancelledStatus);

    /**
     * 查找用户的待确认令牌
     */
    List<IntentPreviewToken> findByFactoryIdAndUserIdAndStatus(String factoryId, Long userId, TokenStatus status);

    /**
     * 查找已过期但状态仍为 PENDING 的令牌
     */
    @Query("SELECT t FROM IntentPreviewToken t WHERE t.status = 'PENDING' AND t.expiresAt < :now")
    List<IntentPreviewToken> findExpiredPendingTokens(@Param("now") LocalDateTime now);

    /**
     * 批量标记过期令牌
     */
    @Modifying
    @Query("UPDATE IntentPreviewToken t SET t.status = 'EXPIRED', t.resolvedAt = :now, " +
           "t.resolutionMessage = '令牌已自动过期' WHERE t.status = 'PENDING' AND t.expiresAt < :now")
    int expireOldTokens(@Param("now") LocalDateTime now);

    /**
     * 取消用户指定意图的所有待确认令牌 (用于创建新令牌时清理旧的)
     */
    @Modifying
    @Query("UPDATE IntentPreviewToken t SET t.status = 'CANCELLED', t.resolvedAt = :now, " +
           "t.resolutionMessage = '被新的预览操作替代' " +
           "WHERE t.factoryId = :factoryId AND t.userId = :userId AND t.intentCode = :intentCode " +
           "AND t.status = 'PENDING'")
    int cancelPreviousTokens(@Param("factoryId") String factoryId,
                             @Param("userId") Long userId,
                             @Param("intentCode") String intentCode,
                             @Param("now") LocalDateTime now);

    /**
     * 删除指定天数之前的已处理令牌 (清理历史数据)
     */
    @Modifying
    @Query("DELETE FROM IntentPreviewToken t WHERE t.status != 'PENDING' AND t.resolvedAt < :before")
    int deleteOldResolvedTokens(@Param("before") LocalDateTime before);

    /**
     * 统计工厂的待确认令牌数量
     */
    @Query("SELECT COUNT(t) FROM IntentPreviewToken t WHERE t.factoryId = :factoryId AND t.status = 'PENDING'")
    long countPendingByFactory(@Param("factoryId") String factoryId);

    /**
     * 验证令牌有效性 (存在、状态为PENDING、未过期)
     */
    @Query("SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM IntentPreviewToken t " +
           "WHERE t.token = :token AND t.status = 'PENDING' AND t.expiresAt > :now")
    boolean isTokenValid(@Param("token") String token, @Param("now") LocalDateTime now);

    /**
     * 验证令牌有效性并检查用户匹配
     */
    @Query("SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM IntentPreviewToken t " +
           "WHERE t.token = :token AND t.userId = :userId AND t.status = 'PENDING' AND t.expiresAt > :now")
    boolean isTokenValidForUser(@Param("token") String token,
                                @Param("userId") Long userId,
                                @Param("now") LocalDateTime now);
}
