package com.cretas.aims.repository.learning;

import com.cretas.aims.entity.learning.LearnedExpression;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 学习表达 Repository
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-05
 */
@Repository
public interface LearnedExpressionRepository extends JpaRepository<LearnedExpression, String> {

    // ========== 精确匹配 ==========

    /**
     * 通过 hash 精确匹配表达
     * 同时查找全局表达 (factoryId IS NULL) 和工厂特定表达
     */
    @Query("SELECT e FROM LearnedExpression e WHERE e.expressionHash = :hash " +
           "AND (e.factoryId IS NULL OR e.factoryId = :factoryId) " +
           "AND e.isActive = true " +
           "ORDER BY e.factoryId DESC NULLS LAST, e.hitCount DESC")
    List<LearnedExpression> findByExpressionHash(
        @Param("hash") String expressionHash,
        @Param("factoryId") String factoryId
    );

    /**
     * 检查是否存在相同表达
     */
    @Query(value = "SELECT CASE WHEN COUNT(*) > 0 THEN true ELSE false END FROM ai_learned_expressions e " +
           "WHERE e.expression_hash = :hash " +
           "AND ((CAST(:factoryId AS varchar) IS NULL AND e.factory_id IS NULL) OR e.factory_id = :factoryId)",
           nativeQuery = true)
    boolean existsByHashAndFactory(
        @Param("hash") String expressionHash,
        @Param("factoryId") String factoryId
    );

    /**
     * 取出现有行（用于 flywheel v2 重提议追踪）
     *
     * 与 {@link #existsByHashAndFactory} 不同，返回完整实体（含 is_active / proposal_count），
     * 供 dedup 命中时判断是否可 promote。
     * 包含所有活跃状态（active=true/false/null），因为 staged 和 dormant 都需要被重提议追踪。
     */
    @Query(value = "SELECT * FROM ai_learned_expressions e " +
           "WHERE e.expression_hash = :hash " +
           "AND ((CAST(:factoryId AS varchar) IS NULL AND e.factory_id IS NULL) OR e.factory_id = :factoryId) " +
           "LIMIT 1",
           nativeQuery = true)
    java.util.Optional<LearnedExpression> findOneByHashAndFactory(
        @Param("hash") String expressionHash,
        @Param("factoryId") String factoryId
    );

    // ========== 按意图查询 ==========

    /**
     * 获取意图的所有活跃表达
     */
    @Query("SELECT e FROM LearnedExpression e WHERE e.intentCode = :intentCode " +
           "AND (e.factoryId IS NULL OR e.factoryId = :factoryId) " +
           "AND e.isActive = true " +
           "ORDER BY e.hitCount DESC")
    List<LearnedExpression> findByIntentCode(
        @Param("intentCode") String intentCode,
        @Param("factoryId") String factoryId
    );

    /**
     * 获取工厂的所有活跃表达
     */
    @Query("SELECT e FROM LearnedExpression e " +
           "WHERE (e.factoryId IS NULL OR e.factoryId = :factoryId) " +
           "AND e.isActive = true")
    List<LearnedExpression> findActiveByFactory(@Param("factoryId") String factoryId);

    /**
     * 统计意图的表达数量
     */
    @Query(value = "SELECT COUNT(*) FROM ai_learned_expressions e " +
           "WHERE e.intent_code = :intentCode " +
           "AND ((CAST(:factoryId AS varchar) IS NULL AND e.factory_id IS NULL) OR e.factory_id = :factoryId) " +
           "AND e.is_active = true",
           nativeQuery = true)
    int countByIntentCode(
        @Param("intentCode") String intentCode,
        @Param("factoryId") String factoryId
    );

    // ========== 相似表达查询 (编辑距离由 Java 层计算) ==========

    /**
     * 获取所有活跃表达 (用于相似匹配)
     */
    @Query("SELECT e FROM LearnedExpression e " +
           "WHERE (e.factoryId IS NULL OR e.factoryId = :factoryId) " +
           "AND e.isActive = true " +
           "AND e.hitCount >= :minHits")
    List<LearnedExpression> findCandidatesForSimilarMatch(
        @Param("factoryId") String factoryId,
        @Param("minHits") int minHits
    );

    // ========== 更新操作 ==========

    /**
     * 增加命中次数
     */
    @Modifying
    @Query("UPDATE LearnedExpression e SET e.hitCount = e.hitCount + 1, " +
           "e.lastHitAt = :now, e.updatedAt = :now WHERE e.id = :id")
    int incrementHitCount(@Param("id") String id, @Param("now") LocalDateTime now);

    /**
     * 验证表达
     */
    @Modifying
    @Query("UPDATE LearnedExpression e SET e.isVerified = true, e.updatedAt = :now WHERE e.id = :id")
    int markAsVerified(@Param("id") String id, @Param("now") LocalDateTime now);

    /**
     * 禁用表达
     */
    @Modifying
    @Query("UPDATE LearnedExpression e SET e.isActive = false, e.updatedAt = :now WHERE e.id = :id")
    int deactivate(@Param("id") String id, @Param("now") LocalDateTime now);

    // ========== 清理操作 ==========

    /**
     * 清理低效表达 (命中次数少 + 创建时间早)
     */
    @Modifying
    @Query(value = "UPDATE ai_learned_expressions SET is_active = false, updated_at = :now " +
           "WHERE hit_count < :minHits " +
           "AND created_at < :beforeDate " +
           "AND is_verified = false " +
           "AND is_active = true " +
           "AND ((CAST(:factoryId AS varchar) IS NULL AND factory_id IS NULL) OR factory_id = :factoryId)",
           nativeQuery = true)
    int deactivateIneffective(
        @Param("factoryId") String factoryId,
        @Param("minHits") int minHits,
        @Param("beforeDate") LocalDateTime beforeDate,
        @Param("now") LocalDateTime now
    );

    // ========== 统计 ==========

    /**
     * 统计各来源类型的表达数量
     */
    @Query(value = "SELECT e.source_type, COUNT(*) FROM ai_learned_expressions e " +
           "WHERE ((CAST(:factoryId AS varchar) IS NULL AND e.factory_id IS NULL) OR e.factory_id = :factoryId) " +
           "AND e.is_active = true " +
           "GROUP BY e.source_type",
           nativeQuery = true)
    List<Object[]> countBySourceType(@Param("factoryId") String factoryId);

    // ========== Embedding 缓存相关 ==========

    /**
     * 获取所有活跃表达 (用于初始化 embedding 缓存)
     */
    List<LearnedExpression> findByIsActiveTrue();

    /**
     * 获取工厂的所有活跃表达 (用于刷新工厂 embedding 缓存)
     */
    List<LearnedExpression> findByFactoryIdAndIsActiveTrue(String factoryId);

    /**
     * 获取所有有 embedding 的活跃表达
     */
    @Query("SELECT e FROM LearnedExpression e WHERE e.isActive = true " +
           "AND e.embeddingVector IS NOT NULL")
    List<LearnedExpression> findAllWithEmbedding();

    /**
     * 获取工厂的所有有 embedding 的活跃表达
     */
    @Query("SELECT e FROM LearnedExpression e " +
           "WHERE (e.factoryId IS NULL OR e.factoryId = :factoryId) " +
           "AND e.isActive = true " +
           "AND e.embeddingVector IS NOT NULL")
    List<LearnedExpression> findByFactoryIdWithEmbedding(@Param("factoryId") String factoryId);
}
