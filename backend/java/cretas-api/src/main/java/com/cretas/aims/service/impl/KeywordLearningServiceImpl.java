package com.cretas.aims.service.impl;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.KeywordEffectiveness;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.service.FactoryConfigService;
import com.cretas.aims.service.KeywordEffectivenessService;
import com.cretas.aims.service.KeywordLearningService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 关键词学习服务实现
 *
 * 统一整合了分散在多处的关键词学习逻辑：
 * - AIIntentServiceImpl.tryAutoLearnKeywords()
 * - AIIntentServiceImpl.tryLearnKeywordsForSelectedIntent()
 * - AIIntentServiceImpl.extractNewKeywords()
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-05
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KeywordLearningServiceImpl implements KeywordLearningService {

    private final AIIntentConfigRepository intentRepository;
    private final KeywordEffectivenessService keywordEffectivenessService;
    private final FactoryConfigService factoryConfigService;
    private final ObjectMapper objectMapper;

    /**
     * 停用词列表（用于过滤无意义的词）
     * 从 AIIntentServiceImpl 迁移过来的统一停用词表
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "是", "了", "把", "我", "要", "你", "他", "她", "它",
            "这", "那", "有", "没", "不", "在", "和", "与", "或", "但",
            "给", "让", "被", "对", "从", "到", "为", "以", "等", "也",
            "就", "都", "还", "很", "太", "好", "请", "帮", "帮我", "一下",
            "看看", "查", "查一下", "查看", "能", "可以", "吗", "呢", "啊", "吧"
    );

    /**
     * 单次学习最大关键词数
     */
    private static final int MAX_NEW_KEYWORDS_PER_INPUT = 3;

    @Override
    @Transactional
    public int learnKeywords(String factoryId, String intentCode,
                             String userInput, LearnSource source) {
        if (factoryId == null || intentCode == null || userInput == null) {
            log.debug("跳过关键词学习: 参数不完整");
            return 0;
        }

        // 检查工厂是否启用自动学习
        if (!factoryConfigService.isAutoLearnEnabled(factoryId)) {
            log.debug("工厂 {} 未启用自动学习，跳过", factoryId);
            return 0;
        }

        // 获取意图配置
        Optional<AIIntentConfig> intentOpt = intentRepository
                .findByIntentCodeAndIsActiveTrueAndDeletedAtIsNull(intentCode);
        if (intentOpt.isEmpty()) {
            log.debug("意图配置不存在: {}", intentCode);
            return 0;
        }

        AIIntentConfig intent = intentOpt.get();

        // 提取新关键词
        List<String> newKeywords = extractNewKeywords(userInput, intent);
        if (newKeywords.isEmpty()) {
            log.debug("未从输入中提取到新关键词: {}", userInput);
            return 0;
        }

        // 添加关键词
        return addKeywordsToIntent(factoryId, intentCode, newKeywords, source);
    }

    @Override
    public List<String> extractNewKeywords(String input, AIIntentConfig intent) {
        if (input == null || input.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 获取现有关键词
        Set<String> existingKeywords = getExistingKeywords(intent);

        // 分词：按标点符号、空格分割
        String[] tokens = input.toLowerCase()
                .replaceAll("[\\p{Punct}\\s]+", " ")
                .trim()
                .split("\\s+");

        // 过滤并提取新关键词
        List<String> newKeywords = new ArrayList<>();
        for (String token : tokens) {
            if (shouldFilterKeyword(token)) continue;
            if (existingKeywords.contains(token.toLowerCase())) continue;

            newKeywords.add(token);
        }

        // 限制单次学习的关键词数量（防止噪音）
        if (newKeywords.size() > MAX_NEW_KEYWORDS_PER_INPUT) {
            newKeywords = newKeywords.subList(0, MAX_NEW_KEYWORDS_PER_INPUT);
        }

        return newKeywords;
    }

    @Override
    @Transactional
    @CacheEvict(value = {"allIntents", "intentsByCategory"}, allEntries = true)
    public int addKeywordsToIntent(String factoryId, String intentCode, List<String> keywords) {
        return addKeywordsToIntent(factoryId, intentCode, keywords, LearnSource.AUTO_LEARN);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"allIntents", "intentsByCategory"}, allEntries = true)
    public int addKeywordsToIntent(String factoryId, String intentCode,
                                   List<String> keywords, LearnSource source) {
        if (keywords == null || keywords.isEmpty()) {
            return 0;
        }

        Optional<AIIntentConfig> intentOpt = intentRepository
                .findByIntentCodeAndIsActiveTrueAndDeletedAtIsNull(intentCode);
        if (intentOpt.isEmpty()) {
            log.warn("意图配置不存在: {}", intentCode);
            return 0;
        }

        AIIntentConfig intent = intentOpt.get();
        int maxKeywords = factoryConfigService.getMaxKeywordsPerIntent(factoryId);

        try {
            // 解析现有关键词
            List<String> existingKeywords = new ArrayList<>();
            String keywordsJson = intent.getKeywords();
            if (keywordsJson != null && !keywordsJson.isEmpty()) {
                existingKeywords = objectMapper.readValue(keywordsJson,
                        new TypeReference<List<String>>() {});
            }

            // 检查是否超过最大关键词数量
            if (existingKeywords.size() >= maxKeywords) {
                log.debug("意图 {} 关键词已达上限 {}", intentCode, maxKeywords);
                return 0;
            }

            // 添加新关键词（不重复）
            Set<String> existingSet = existingKeywords.stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());

            int addedCount = 0;
            BigDecimal initialWeight = factoryConfigService.getLlmNewKeywordWeight(factoryId);
            String effectivenessSource = mapLearnSourceToEffectivenessSource(source);

            for (String keyword : keywords) {
                if (existingKeywords.size() + addedCount >= maxKeywords) {
                    break;
                }
                if (!existingSet.contains(keyword.toLowerCase())) {
                    existingKeywords.add(keyword);
                    existingSet.add(keyword.toLowerCase());
                    addedCount++;

                    // 同时记录到效果追踪表
                    keywordEffectivenessService.createOrUpdateKeyword(
                            factoryId, intentCode, keyword,
                            effectivenessSource, initialWeight);
                }
            }

            if (addedCount > 0) {
                // 保存更新
                String updatedKeywordsJson = objectMapper.writeValueAsString(existingKeywords);
                intent.setKeywords(updatedKeywordsJson);
                intentRepository.save(intent);

                log.info("添加 {} 个关键词到意图 {}: source={}, keywords={}",
                        addedCount, intentCode, source, keywords.subList(0, Math.min(addedCount, keywords.size())));
            }

            return addedCount;

        } catch (Exception e) {
            log.error("添加关键词到意图 {} 失败: {}", intentCode, e.getMessage());
            return 0;
        }
    }

    @Override
    public boolean shouldFilterKeyword(String keyword) {
        if (keyword == null) return true;

        String lowerKeyword = keyword.toLowerCase().trim();

        // 过短
        if (lowerKeyword.length() < 2) return true;

        // 停用词
        if (STOP_WORDS.contains(lowerKeyword)) return true;

        // 纯数字
        if (lowerKeyword.matches("^\\d+$")) return true;

        // 纯标点
        if (lowerKeyword.matches("^[\\p{Punct}]+$")) return true;

        return false;
    }

    @Override
    public Set<String> getStopWords() {
        return Collections.unmodifiableSet(STOP_WORDS);
    }

    @Override
    @Transactional
    public int learnFromMatchedIntent(String userInput, AIIntentConfig matchedIntent, String factoryId) {
        if (userInput == null || matchedIntent == null) {
            return 0;
        }

        try {
            // 提取新关键词
            List<String> newKeywords = extractNewKeywords(userInput, matchedIntent);

            if (newKeywords.isEmpty()) {
                log.debug("未从输入中提取到新关键词: {}", userInput);
                return 0;
            }

            // 添加到意图配置
            int added = addKeywordsToIntent(factoryId, matchedIntent.getIntentCode(),
                    newKeywords, LearnSource.LLM_HIGH_CONFIDENCE);

            if (added > 0) {
                log.info("从LLM匹配学习 {} 个关键词到意图 {}: {}",
                        added, matchedIntent.getIntentCode(),
                        newKeywords.subList(0, Math.min(added, newKeywords.size())));
            }

            return added;

        } catch (Exception e) {
            log.warn("从匹配意图学习关键词失败: {}", e.getMessage());
            return 0;
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 获取意图现有关键词集合
     */
    private Set<String> getExistingKeywords(AIIntentConfig intent) {
        Set<String> existingKeywords = new HashSet<>();
        String keywordsJson = intent.getKeywords();
        if (keywordsJson != null && !keywordsJson.isEmpty()) {
            try {
                List<String> keywords = objectMapper.readValue(keywordsJson,
                        new TypeReference<List<String>>() {});
                existingKeywords.addAll(keywords.stream()
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet()));
            } catch (Exception e) {
                log.warn("解析意图关键词失败: {}", e.getMessage());
            }
        }
        return existingKeywords;
    }

    /**
     * 将 LearnSource 映射到 KeywordEffectiveness 的 source 字段
     */
    private String mapLearnSourceToEffectivenessSource(LearnSource source) {
        switch (source) {
            case USER_CONFIRM:
                return KeywordEffectiveness.Source.FEEDBACK_LEARNED;
            case AUTO_LEARN:
                return KeywordEffectiveness.Source.AUTO_LEARNED;
            case FEEDBACK_POSITIVE:
                return KeywordEffectiveness.Source.FEEDBACK_LEARNED;
            case MANUAL_ADD:
                return KeywordEffectiveness.Source.MANUAL;
            case LLM_HIGH_CONFIDENCE:
                return KeywordEffectiveness.Source.AUTO_LEARNED;
            case FEEDBACK_LEARNED:
                return KeywordEffectiveness.Source.FEEDBACK_LEARNED;
            default:
                return KeywordEffectiveness.Source.AUTO_LEARNED;
        }
    }
}
