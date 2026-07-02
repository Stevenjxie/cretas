package com.cretas.aims.repository;

import com.cretas.aims.entity.intent.IntentMatchRecord;
import com.cretas.aims.entity.intent.IntentMatchRecord.ErrorAttribution;
import com.cretas.aims.entity.intent.IntentMatchRecord.ExecutionStatus;
import com.cretas.aims.entity.intent.IntentMatchRecord.MatchMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 意图匹配记录数据访问接口
 *
 * 提供:
 * - 意图匹配记录的CRUD操作
 * - 按工厂/用户/会话查询
 * - 错误归因分析
 * - 统计数据查询
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Repository
public interface IntentMatchRecordRepository extends JpaRepository<IntentMatchRecord, String> {

    // ==================== 基本查询 ====================

    /**
     * 分页查询工厂的意图匹配记录
     */
    Page<IntentMatchRecord> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    /**
     * 根据用户ID分页查询
     */
    Page<IntentMatchRecord> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * 根据Session ID查询（追踪多轮对话）
     */
    List<IntentMatchRecord> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * 查询特定意图的匹配记录
     */
    Page<IntentMatchRecord> findByMatchedIntentCodeOrderByCreatedAtDesc(
            String intentCode, Pageable pageable);

    // ==================== 时间范围查询 ====================

    /**
     * 查询工厂特定时间范围的记录
     */
    List<IntentMatchRecord> findByFactoryIdAndCreatedAtBetween(
            String factoryId, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * 查询特定日期的记录（用于日统计）
     */
    @Query(value = "SELECT * FROM intent_match_records r WHERE r.factory_id = :factoryId " +
           "AND CAST(r.created_at AS DATE) = CAST(:date AS DATE)",
           nativeQuery = true)
    List<IntentMatchRecord> findByFactoryIdAndDate(
            @Param("factoryId") String factoryId,
            @Param("date") LocalDateTime date);

    // ==================== 状态查询 ====================

    /**
     * 查询待执行的记录
     */
    List<IntentMatchRecord> findByExecutionStatusAndFactoryId(
            ExecutionStatus status, String factoryId);

    /**
     * 查询需要用户确认的记录
     */
    List<IntentMatchRecord> findByRequiresConfirmationTrueAndUserConfirmedIsNullAndFactoryId(
            String factoryId);

    /**
     * 查询调用了LLM的记录
     */
    Page<IntentMatchRecord> findByLlmCalledTrueOrderByCreatedAtDesc(Pageable pageable);

    // ==================== 错误归因查询 ====================

    /**
     * 根据错误归因类型查询
     */
    Page<IntentMatchRecord> findByErrorAttributionOrderByCreatedAtDesc(
            ErrorAttribution attribution, Pageable pageable);

    /**
     * 查询未匹配的记录（规则缺失分析）
     */
    @Query("SELECT r FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.matchedIntentCode IS NULL AND r.createdAt >= :startDate " +
           "ORDER BY r.createdAt DESC")
    List<IntentMatchRecord> findUnmatchedRecords(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate);

    /**
     * 查询低置信度的记录（歧义分析）
     */
    @Query("SELECT r FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.confidenceScore < :threshold AND r.matchedIntentCode IS NOT NULL " +
           "AND r.createdAt >= :startDate ORDER BY r.confidenceScore ASC")
    List<IntentMatchRecord> findLowConfidenceRecords(
            @Param("factoryId") String factoryId,
            @Param("threshold") java.math.BigDecimal threshold,
            @Param("startDate") LocalDateTime startDate);

    /**
     * 查询用户拒绝的记录（误匹配分析）
     */
    @Query("SELECT r FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.userConfirmed = false AND r.createdAt >= :startDate " +
           "ORDER BY r.createdAt DESC")
    List<IntentMatchRecord> findRejectedRecords(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate);

    // ==================== 统计查询 ====================

    /**
     * 统计总请求数 (从起始日期)
     */
    @Query("SELECT COUNT(r) FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.createdAt >= :startDate")
    long countTotalRequests(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate);

    /**
     * 统计总请求数 (日期范围)
     */
    @Query("SELECT COUNT(r) FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.createdAt >= :startDate AND r.createdAt < :endDate")
    long countTotalRequests(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    /**
     * 统计成功匹配数 (从起始日期)
     */
    @Query("SELECT COUNT(r) FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.matchedIntentCode IS NOT NULL AND r.createdAt >= :startDate")
    long countMatchedRequests(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate);

    /**
     * 统计成功匹配数 (日期范围)
     */
    @Query("SELECT COUNT(r) FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.matchedIntentCode IS NOT NULL AND r.createdAt >= :startDate AND r.createdAt < :endDate")
    long countMatchedRequests(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    /**
     * 统计LLM Fallback次数 (从起始日期)
     */
    @Query("SELECT COUNT(r) FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.llmCalled = true AND r.createdAt >= :startDate")
    long countLlmFallbackRequests(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate);

    /**
     * 统计LLM Fallback次数 (日期范围)
     */
    @Query("SELECT COUNT(r) FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.llmCalled = true AND r.createdAt >= :startDate AND r.createdAt < :endDate")
    long countLlmFallbackRequests(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    /**
     * 计算平均置信度
     */
    @Query("SELECT AVG(r.confidenceScore) FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.confidenceScore IS NOT NULL AND r.createdAt >= :startDate")
    java.math.BigDecimal calculateAverageConfidence(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate);

    /**
     * 按匹配方法统计
     */
    @Query("SELECT r.matchMethod, COUNT(r) FROM IntentMatchRecord r " +
           "WHERE r.factoryId = :factoryId AND r.createdAt >= :startDate " +
           "GROUP BY r.matchMethod")
    List<Object[]> countByMatchMethod(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate);

    /**
     * 按意图分类统计
     */
    @Query("SELECT r.matchedIntentCategory, COUNT(r) FROM IntentMatchRecord r " +
           "WHERE r.factoryId = :factoryId AND r.matchedIntentCategory IS NOT NULL " +
           "AND r.createdAt >= :startDate GROUP BY r.matchedIntentCategory")
    List<Object[]> countByIntentCategory(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate);

    /**
     * 按错误归因统计
     */
    @Query("SELECT r.errorAttribution, COUNT(r) FROM IntentMatchRecord r " +
           "WHERE r.factoryId = :factoryId AND r.errorAttribution IS NOT NULL " +
           "AND r.createdAt >= :startDate GROUP BY r.errorAttribution")
    List<Object[]> countByErrorAttribution(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate);

    /**
     * 查询强信号数量
     */
    @Query("SELECT COUNT(r) FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.isStrongSignal = true AND r.createdAt >= :startDate")
    long countStrongSignalRequests(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate);

    // ==================== RAG 检索查询 ====================

    /**
     * 查询高置信度成功案例（用于 RAG 检索）
     * 用于为新输入提供高质量的参考示例
     */
    @Query("SELECT r FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.confidenceScore >= :minConfidence " +
           "AND r.matchedIntentCode IS NOT NULL " +
           "AND r.createdAt >= :startDate " +
           "ORDER BY r.confidenceScore DESC")
    List<IntentMatchRecord> findHighConfidenceSuccesses(
            @Param("factoryId") String factoryId,
            @Param("minConfidence") java.math.BigDecimal minConfidence,
            @Param("startDate") LocalDateTime startDate,
            Pageable pageable);

    /**
     * 查询用户确认的记录（用于 RAG 检索）
     * 用户确认的记录是最可靠的训练数据来源
     */
    @Query("SELECT r FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.userConfirmed = true " +
           "AND r.userSelectedIntent IS NOT NULL " +
           "AND r.createdAt >= :startDate " +
           "ORDER BY r.createdAt DESC")
    List<IntentMatchRecord> findUserConfirmedRecords(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate,
            Pageable pageable);

    /**
     * 模糊匹配相似输入（用于 RAG 检索）
     * 根据标准化输入查找相似的历史记录
     */
    @Query("SELECT r FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.normalizedInput LIKE :pattern " +
           "AND r.confidenceScore >= :minConfidence " +
           "AND r.createdAt >= :startDate " +
           "ORDER BY r.confidenceScore DESC")
    List<IntentMatchRecord> findByNormalizedInputLike(
            @Param("factoryId") String factoryId,
            @Param("pattern") String pattern,
            @Param("minConfidence") java.math.BigDecimal minConfidence,
            @Param("startDate") LocalDateTime startDate,
            Pageable pageable);

    /**
     * Locate the latest record backing a user's visible AI answer so feedback
     * buttons can write to the same learning/audit stream.
     */
    @Query("SELECT r FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.userId = :userId " +
           "AND r.matchedIntentCode = :intentCode " +
           "AND r.normalizedInput = :normalizedInput " +
           "ORDER BY r.createdAt DESC")
    List<IntentMatchRecord> findLatestForFeedback(
            @Param("factoryId") String factoryId,
            @Param("userId") Long userId,
            @Param("intentCode") String intentCode,
            @Param("normalizedInput") String normalizedInput,
            Pageable pageable);

    @Query("SELECT r FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.userId = :userId " +
           "AND r.matchedIntentCode = :intentCode " +
           "AND r.userInput = :userInput " +
           "ORDER BY r.createdAt DESC")
    List<IntentMatchRecord> findLatestForFeedbackByUserInput(
            @Param("factoryId") String factoryId,
            @Param("userId") Long userId,
            @Param("intentCode") String intentCode,
            @Param("userInput") String userInput,
            Pageable pageable);

    @Query("SELECT r FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.userId = :userId " +
           "AND r.matchedIntentCode = :intentCode " +
           "ORDER BY r.createdAt DESC")
    List<IntentMatchRecord> findLatestForFeedbackByIntent(
            @Param("factoryId") String factoryId,
            @Param("userId") Long userId,
            @Param("intentCode") String intentCode,
            Pageable pageable);

    /**
     * 查询 LLM 高置信度记录（用于 RAG 检索）
     * LLM 判定高置信度的记录可作为 few-shot 示例
     */
    @Query("SELECT r FROM IntentMatchRecord r WHERE r.factoryId = :factoryId " +
           "AND r.llmCalled = true " +
           "AND r.llmConfidence >= :minConfidence " +
           "AND r.createdAt >= :startDate " +
           "ORDER BY r.llmConfidence DESC")
    List<IntentMatchRecord> findLlmHighConfidenceRecords(
            @Param("factoryId") String factoryId,
            @Param("minConfidence") java.math.BigDecimal minConfidence,
            @Param("startDate") LocalDateTime startDate,
            Pageable pageable);

    // ==================== 更新操作 ====================

    /**
     * 更新用户确认状态
     */
    @Modifying
    @Query("UPDATE IntentMatchRecord r SET r.userConfirmed = :confirmed, " +
           "r.confirmedAt = :confirmedAt WHERE r.id = :id")
    int updateUserConfirmation(
            @Param("id") String id,
            @Param("confirmed") Boolean confirmed,
            @Param("confirmedAt") LocalDateTime confirmedAt);

    /**
     * 更新执行状态
     */
    @Modifying
    @Query("UPDATE IntentMatchRecord r SET r.executionStatus = :status, " +
           "r.executionResult = :result, r.executedAt = :executedAt WHERE r.id = :id")
    int updateExecutionStatus(
            @Param("id") String id,
            @Param("status") ExecutionStatus status,
            @Param("result") String result,
            @Param("executedAt") LocalDateTime executedAt);

    /**
     * 更新错误归因
     */
    @Modifying
    @Query("UPDATE IntentMatchRecord r SET r.errorAttribution = :attribution, " +
           "r.attributionDetails = :details WHERE r.id = :id")
    int updateErrorAttribution(
            @Param("id") String id,
            @Param("attribution") ErrorAttribution attribution,
            @Param("details") String details);

    // ==================== 影子分类操作 ====================

    /**
     * 更新影子分类结果（PHRASE_MATCH vs BERT 对比）
     */
    @Modifying
    @Query(value = "UPDATE intent_match_records SET shadow_intent_code = :shadowIntent, " +
           "shadow_confidence = :shadowConf, shadow_agreed = :agreed, " +
           "classifier_entropy = :entropy, classifier_margin = :margin, " +
           "classifier_max_logit = :maxLogit WHERE id = :recordId",
           nativeQuery = true)
    int updateShadowResult(@Param("recordId") String recordId,
                           @Param("shadowIntent") String shadowIntent,
                           @Param("shadowConf") java.math.BigDecimal shadowConf,
                           @Param("agreed") boolean agreed,
                           @Param("entropy") java.math.BigDecimal entropy,
                           @Param("margin") java.math.BigDecimal margin,
                           @Param("maxLogit") java.math.BigDecimal maxLogit);

    /**
     * 标记 ZPD 边界（影子分类不一致时）
     */
    @Modifying
    @Query(value = "UPDATE intent_match_records SET zpd_boundary = true WHERE id = :recordId",
           nativeQuery = true)
    int markZpdBoundary(@Param("recordId") String recordId);

    // ==================== 清理操作 ====================

    /**
     * 删除旧记录（保留策略：默认90天）
     */
    @Modifying
    @Query("DELETE FROM IntentMatchRecord r WHERE r.createdAt < :cutoffDate")
    int deleteOldRecords(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * 查询需要归因分析的记录（执行失败但未归因）
     */
    @Query("SELECT r FROM IntentMatchRecord r WHERE r.executionStatus = 'FAILED' " +
           "AND r.errorAttribution IS NULL ORDER BY r.createdAt DESC")
    List<IntentMatchRecord> findRecordsNeedingAttribution();
}
