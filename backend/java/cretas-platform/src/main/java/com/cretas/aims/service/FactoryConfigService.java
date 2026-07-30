package com.cretas.aims.service;

import com.cretas.aims.entity.intent.FactoryAILearningConfig;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工厂级AI学习配置服务接口
 */
public interface FactoryConfigService {

    /**
     * 获取工厂配置（不存在则返回默认配置）
     *
     * @param factoryId 工厂ID
     * @return 工厂配置
     */
    FactoryAILearningConfig getConfig(String factoryId);

    /**
     * 保存工厂配置
     *
     * @param config 配置对象
     * @return 保存后的配置
     */
    FactoryAILearningConfig saveConfig(FactoryAILearningConfig config);

    /**
     * 获取有效的置信度阈值
     * 学习阶段会使用更低的阈值
     *
     * @param factoryId 工厂ID
     * @return 有效阈值
     */
    BigDecimal getEffectiveConfidenceThreshold(String factoryId);

    /**
     * 检查是否启用自动学习
     *
     * @param factoryId 工厂ID
     * @return true 如果启用
     */
    boolean isAutoLearnEnabled(String factoryId);

    /**
     * 检查是否启用 LLM Fallback
     *
     * @param factoryId 工厂ID
     * @return true 如果启用
     */
    boolean isLlmFallbackEnabled(String factoryId);

    /**
     * 获取每个意图的最大关键词数
     *
     * @param factoryId 工厂ID
     * @return 最大关键词数
     */
    int getMaxKeywordsPerIntent(String factoryId);

    /**
     * 获取 LLM 学习新词的初始权重
     *
     * @param factoryId 工厂ID
     * @return 初始权重
     */
    BigDecimal getLlmNewKeywordWeight(String factoryId);

    /**
     * 检查工厂阶段转换
     * 学习阶段超过阈值天数自动转换为成熟阶段
     *
     * @return 转换的工厂数量
     */
    int checkPhaseTransitions();

    /**
     * 获取所有启用自动学习的工厂ID
     *
     * @return 工厂ID列表
     */
    List<String> getAutoLearnEnabledFactories();

    /**
     * 获取所有启用清理的工厂
     *
     * @return 工厂配置列表
     */
    List<FactoryAILearningConfig> getCleanupEnabledFactories();

    /**
     * 初始化工厂默认配置（如果不存在）
     *
     * @param factoryId 工厂ID
     * @return 配置对象
     */
    FactoryAILearningConfig initializeIfNotExists(String factoryId);

    /**
     * 更新最后 specificity 重算时间
     *
     * @param factoryId 工厂ID
     */
    void updateLastSpecificityRecalcTime(String factoryId);
}
