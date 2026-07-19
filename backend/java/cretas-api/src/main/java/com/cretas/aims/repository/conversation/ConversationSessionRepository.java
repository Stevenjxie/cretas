package com.cretas.aims.repository.conversation;

import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 多轮对话会话 Repository
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, String> {

    Optional<ConversationSession> findBySessionIdAndFactoryIdAndUserId(
        String sessionId,
        String factoryId,
        Long userId
    );

    // ========== 活跃会话查询 ==========

    /**
     * 查找用户的活跃会话
     * 一个用户同一时间只能有一个活跃会话
     */
    @Query("SELECT s FROM ConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.userId = :userId " +
           "AND s.status = 'ACTIVE' " +
           "ORDER BY s.createdAt DESC")
    List<ConversationSession> findActiveByFactoryAndUser(
        @Param("factoryId") String factoryId,
        @Param("userId") Long userId
    );

    /**
     * 获取用户最新的活跃会话
     */
    default Optional<ConversationSession> findLatestActiveSession(String factoryId, Long userId) {
        List<ConversationSession> sessions = findActiveByFactoryAndUser(factoryId, userId);
        return sessions.isEmpty() ? Optional.empty() : Optional.of(sessions.get(0));
    }

    /**
     * 检查用户是否有活跃会话
     */
    @Query("SELECT COUNT(s) > 0 FROM ConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.userId = :userId " +
           "AND s.status = 'ACTIVE'")
    boolean hasActiveSession(
        @Param("factoryId") String factoryId,
        @Param("userId") Long userId
    );

    // ========== 按状态查询 ==========

    /**
     * 查找指定状态的会话
     */
    List<ConversationSession> findByStatus(SessionStatus status);

    /**
     * 查找需要超时处理的会话
     * 活跃状态 + 最后活跃时间超过阈值
     */
    @Query("SELECT s FROM ConversationSession s " +
           "WHERE s.status = 'ACTIVE' " +
           "AND s.lastActiveAt < :expireTime")
    List<ConversationSession> findExpiredSessions(@Param("expireTime") LocalDateTime expireTime);

    // ========== 历史查询 ==========

    /**
     * 查找用户的历史会话 (最近N个)
     */
    @Query("SELECT s FROM ConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.userId = :userId " +
           "ORDER BY s.createdAt DESC")
    List<ConversationSession> findRecentByUser(
        @Param("factoryId") String factoryId,
        @Param("userId") Long userId
    );

    /**
     * 查找成功识别意图的会话 (用于学习)
     */
    @Query("SELECT s FROM ConversationSession s " +
           "WHERE s.status = 'COMPLETED' " +
           "AND s.finalIntentCode IS NOT NULL " +
           "AND s.createdAt >= :since " +
           "ORDER BY s.createdAt DESC")
    List<ConversationSession> findCompletedSessions(@Param("since") LocalDateTime since);

    /**
     * 按意图代码查找已完成的会话
     */
    @Query("SELECT s FROM ConversationSession s " +
           "WHERE s.finalIntentCode = :intentCode " +
           "AND s.status = 'COMPLETED' " +
           "ORDER BY s.createdAt DESC")
    List<ConversationSession> findByFinalIntentCode(@Param("intentCode") String intentCode);

    // ========== 状态更新 ==========

    /**
     * 批量超时过期会话
     */
    @Modifying
    @Query("UPDATE ConversationSession s SET " +
           "s.status = 'TIMEOUT', " +
           "s.completedAt = :now, " +
           "s.updatedAt = :now " +
           "WHERE s.status = 'ACTIVE' " +
           "AND s.lastActiveAt < :expireTime")
    int expireInactiveSessions(
        @Param("expireTime") LocalDateTime expireTime,
        @Param("now") LocalDateTime now
    );

    /**
     * 取消用户的所有活跃会话 (开始新会话前调用)
     */
    @Modifying
    @Query("UPDATE ConversationSession s SET " +
           "s.status = 'CANCELLED', " +
           "s.completedAt = :now, " +
           "s.updatedAt = :now " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.userId = :userId " +
           "AND s.status = 'ACTIVE'")
    int cancelActiveSessionsForUser(
        @Param("factoryId") String factoryId,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now
    );

    // ========== 统计 ==========

    /**
     * 统计各状态的会话数量
     */
    @Query("SELECT s.status, COUNT(s) FROM ConversationSession s " +
           "WHERE s.createdAt >= :since " +
           "GROUP BY s.status")
    List<Object[]> countByStatus(@Param("since") LocalDateTime since);

    /**
     * 统计多轮对话的成功率
     */
    @Query("SELECT " +
           "COUNT(CASE WHEN s.status = 'COMPLETED' THEN 1 END) AS completed, " +
           "COUNT(CASE WHEN s.status = 'TIMEOUT' THEN 1 END) AS timeout, " +
           "COUNT(CASE WHEN s.status = 'CANCELLED' THEN 1 END) AS cancelled, " +
           "COUNT(CASE WHEN s.status = 'MAX_ROUNDS_REACHED' THEN 1 END) AS maxRounds, " +
           "COUNT(s) AS total " +
           "FROM ConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.createdAt >= :since")
    List<Object[]> getSuccessRate(
        @Param("factoryId") String factoryId,
        @Param("since") LocalDateTime since
    );

    /**
     * 平均对话轮次统计
     */
    @Query("SELECT AVG(s.currentRound) FROM ConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.status = 'COMPLETED' " +
           "AND s.createdAt >= :since")
    Double getAverageRoundsForCompleted(
        @Param("factoryId") String factoryId,
        @Param("since") LocalDateTime since
    );

    @Query("SELECT COUNT(s) FROM ConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.status = 'ACTIVE'")
    long countActiveByFactoryId(@Param("factoryId") String factoryId);

    // ========== 清理 ==========

    /**
     * 删除过期会话 (保留策略)
     */
    @Modifying
    @Query("DELETE FROM ConversationSession s " +
           "WHERE s.status IN ('TIMEOUT', 'CANCELLED', 'MAX_ROUNDS_REACHED') " +
           "AND s.completedAt < :before")
    int deleteOldUnsuccessfulSessions(@Param("before") LocalDateTime before);
}
