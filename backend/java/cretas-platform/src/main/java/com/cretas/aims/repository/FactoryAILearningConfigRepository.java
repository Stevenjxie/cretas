package com.cretas.aims.repository;

import com.cretas.aims.entity.intent.FactoryAILearningConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 工厂级AI学习配置 Repository
 */
@Repository
public interface FactoryAILearningConfigRepository extends JpaRepository<FactoryAILearningConfig, Long> {

    /**
     * 根据工厂ID查找配置
     */
    Optional<FactoryAILearningConfig> findByFactoryId(String factoryId);

    /**
     * 检查工厂配置是否存在
     */
    boolean existsByFactoryId(String factoryId);

    /**
     * 获取所有启用自动学习的工厂
     */
    List<FactoryAILearningConfig> findByAutoLearnEnabledTrue();

    /**
     * 获取所有处于学习阶段的工厂
     */
    @Query("SELECT c FROM FactoryAILearningConfig c " +
           "WHERE c.learningPhase = 'LEARNING'")
    List<FactoryAILearningConfig> findLearningPhaseFactories();

    /**
     * 获取所有需要检查阶段转换的工厂
     */
    @Query(value = "SELECT c.* FROM factory_ai_learning_config c " +
           "WHERE c.learning_phase = 'LEARNING' " +
           "AND EXTRACT(DAY FROM (CURRENT_DATE - c.created_at)) >= c.mature_threshold_days",
           nativeQuery = true)
    List<FactoryAILearningConfig> findFactoriesReadyForMatureTransition();

    /**
     * 获取启用清理的工厂配置
     */
    List<FactoryAILearningConfig> findByCleanupEnabledTrue();

    /**
     * 获取启用晋升的工厂配置
     */
    List<FactoryAILearningConfig> findByPromotionEnabledTrue();

    /**
     * 获取启用 LLM Fallback 的工厂配置
     */
    List<FactoryAILearningConfig> findByLlmFallbackEnabledTrue();

    /**
     * 获取启用 Specificity 重算的工厂配置
     */
    List<FactoryAILearningConfig> findBySpecificityRecalcEnabledTrue();

    /**
     * 更新阶段为成熟
     */
    @Modifying
    @Query("UPDATE FactoryAILearningConfig c SET c.learningPhase = 'MATURE', " +
           "c.phaseTransitionDate = CURRENT_DATE " +
           "WHERE c.factoryId = :factoryId")
    int transitionToMature(@Param("factoryId") String factoryId);

    /**
     * 更新最后 Specificity 重算时间
     */
    @Modifying
    @Query("UPDATE FactoryAILearningConfig c SET c.lastSpecificityRecalcAt = :time " +
           "WHERE c.factoryId = :factoryId")
    int updateLastSpecificityRecalcTime(
        @Param("factoryId") String factoryId,
        @Param("time") LocalDateTime time);

    /**
     * 获取所有工厂ID
     */
    @Query("SELECT c.factoryId FROM FactoryAILearningConfig c")
    List<String> findAllFactoryIds();

    /**
     * 获取成熟阶段的工厂数量
     */
    @Query("SELECT COUNT(c) FROM FactoryAILearningConfig c " +
           "WHERE c.learningPhase = 'MATURE'")
    long countMatureFactories();

    /**
     * 获取学习阶段的工厂数量
     */
    @Query("SELECT COUNT(c) FROM FactoryAILearningConfig c " +
           "WHERE c.learningPhase = 'LEARNING'")
    long countLearningFactories();

    // ========== EnvScaler 合成数据方法 ==========

    /**
     * 获取启用合成数据的工厂配置
     */
    List<FactoryAILearningConfig> findBySyntheticEnabledTrue();

    /**
     * 获取启用合成数据的工厂ID列表
     */
    @Query("SELECT c.factoryId FROM FactoryAILearningConfig c " +
           "WHERE c.syntheticEnabled = true")
    List<String> findSyntheticEnabledFactoryIds();

    /**
     * 禁用工厂的合成数据生成
     */
    @Modifying
    @Query("UPDATE FactoryAILearningConfig c SET " +
           "c.syntheticEnabled = false, " +
           "c.syntheticDisabledReason = :reason, " +
           "c.syntheticDisabledAt = :disabledAt " +
           "WHERE c.factoryId = :factoryId")
    int disableSynthetic(
        @Param("factoryId") String factoryId,
        @Param("reason") String reason,
        @Param("disabledAt") LocalDateTime disabledAt
    );

    /**
     * 重新启用工厂的合成数据生成
     */
    @Modifying
    @Query("UPDATE FactoryAILearningConfig c SET " +
           "c.syntheticEnabled = true, " +
           "c.syntheticDisabledReason = null, " +
           "c.syntheticDisabledAt = null " +
           "WHERE c.factoryId = :factoryId")
    int enableSynthetic(@Param("factoryId") String factoryId);

    /**
     * 统计启用合成数据的工厂数量
     */
    @Query("SELECT COUNT(c) FROM FactoryAILearningConfig c " +
           "WHERE c.syntheticEnabled = true")
    long countSyntheticEnabledFactories();

    /**
     * 统计被熔断禁用合成数据的工厂数量
     */
    @Query("SELECT COUNT(c) FROM FactoryAILearningConfig c " +
           "WHERE c.syntheticEnabled = false " +
           "AND c.syntheticDisabledReason IS NOT NULL")
    long countSyntheticDisabledFactories();
}
