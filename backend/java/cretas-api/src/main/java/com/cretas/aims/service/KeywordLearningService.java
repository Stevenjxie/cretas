package com.cretas.aims.service;

import com.cretas.aims.entity.config.AIIntentConfig;

import java.util.List;

/**
 * 关键词学习服务 - 统一的关键词学习入口
 *
 * 整合了分散在多处的关键词学习逻辑：
 * - 从用户输入提取新关键词
 * - 自动学习高置信度匹配的关键词
 * - 从用户反馈中学习关键词
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-05
 *
 * @deprecated Phase 2B-β: replaced by Python {@code backend/python/ai/learning/keyword_learner.py}.
 * This interface still ships in Bucket B for now (no functional change),
 * but no new callers should be added. Phase 3 will remove this entirely.
 */
@Deprecated
public interface KeywordLearningService {

    /**
     * 学习来源枚举
     */
    enum LearnSource {
        /** 用户确认（选择正确意图后学习） */
        USER_CONFIRM,
        /** 自动学习（高置信度匹配时） */
        AUTO_LEARN,
        /** 正向反馈（用户点赞或确认） */
        FEEDBACK_POSITIVE,
        /** 手动添加（管理员手动配置） */
        MANUAL_ADD,
        /** LLM 高置信度识别 */
        LLM_HIGH_CONFIDENCE,
        /** 从用户反馈学习 */
        FEEDBACK_LEARNED
    }

    /**
     * 从用户输入中学习新关键词
     *
     * 该方法会：
     * 1. 检查工厂是否启用自动学习
     * 2. 从输入中提取有意义的词语
     * 3. 过滤停用词和已存在的关键词
     * 4. 将新关键词添加到意图配置
     * 5. 记录到关键词效果追踪表
     *
     * @param factoryId 工厂ID
     * @param intentCode 意图代码
     * @param userInput 用户输入
     * @param source 学习来源
     * @return 新学到的关键词数量
     */
    int learnKeywords(String factoryId, String intentCode,
                      String userInput, LearnSource source);

    /**
     * 从输入中提取可能的新关键词
     *
     * 算法：
     * 1. 简单分词（按标点和空格）
     * 2. 过滤停用词
     * 3. 过滤已存在的关键词
     * 4. 过滤过短词（< 2 字符）
     * 5. 保留有意义的新词
     *
     * @param input 用户输入
     * @param intent 目标意图配置
     * @return 提取的新关键词列表
     */
    List<String> extractNewKeywords(String input, AIIntentConfig intent);

    /**
     * 将关键词添加到意图配置
     *
     * @param factoryId 工厂ID
     * @param intentCode 意图代码
     * @param keywords 要添加的关键词列表
     * @return 成功添加的数量
     */
    int addKeywordsToIntent(String factoryId, String intentCode, List<String> keywords);

    /**
     * 批量将关键词添加到意图配置（带来源和权重）
     *
     * @param factoryId 工厂ID
     * @param intentCode 意图代码
     * @param keywords 要添加的关键词列表
     * @param source 学习来源
     * @return 成功添加的数量
     */
    int addKeywordsToIntent(String factoryId, String intentCode,
                            List<String> keywords, LearnSource source);

    /**
     * 检查关键词是否应该被过滤（停用词等）
     *
     * @param keyword 待检查的关键词
     * @return true 表示应该过滤（不学习），false 表示可以学习
     */
    boolean shouldFilterKeyword(String keyword);

    /**
     * 获取停用词集合
     *
     * @return 停用词集合（不可修改）
     */
    java.util.Set<String> getStopWords();

    /**
     * 从意图配置中学习关键词（用于LLM高置信度匹配后的自动学习）
     *
     * @param userInput 用户输入
     * @param matchedIntent 匹配的意图配置
     * @param factoryId 工厂ID
     * @return 学到的关键词数量
     */
    int learnFromMatchedIntent(String userInput, AIIntentConfig matchedIntent, String factoryId);
}
