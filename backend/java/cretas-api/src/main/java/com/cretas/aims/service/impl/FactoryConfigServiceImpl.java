package com.cretas.aims.service.impl;

import com.cretas.aims.entity.intent.FactoryAILearningConfig;
import com.cretas.aims.repository.FactoryAILearningConfigRepository;
import com.cretas.aims.service.FactoryConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工厂级AI学习配置服务实现
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FactoryConfigServiceImpl implements FactoryConfigService {

    private final FactoryAILearningConfigRepository configRepository;

    @Override
    public FactoryAILearningConfig getConfig(String factoryId) {
        return configRepository.findByFactoryId(factoryId)
            .orElseGet(() -> {
                log.debug("工厂 {} 无配置，返回默认配置", factoryId);
                return FactoryAILearningConfig.createDefault(factoryId);
            });
    }

    @Override
    @Transactional
    public FactoryAILearningConfig saveConfig(FactoryAILearningConfig config) {
        FactoryAILearningConfig saved = configRepository.save(config);
        log.info("保存工厂配置: factoryId={}, autoLearn={}, phase={}",
            config.getFactoryId(), config.getAutoLearnEnabled(), config.getLearningPhase());
        return saved;
    }

    @Override
    public BigDecimal getEffectiveConfidenceThreshold(String factoryId) {
        FactoryAILearningConfig config = getConfig(factoryId);
        return config.getEffectiveConfidenceThreshold();
    }

    @Override
    public boolean isAutoLearnEnabled(String factoryId) {
        FactoryAILearningConfig config = getConfig(factoryId);
        return Boolean.TRUE.equals(config.getAutoLearnEnabled());
    }

    @Override
    public boolean isLlmFallbackEnabled(String factoryId) {
        FactoryAILearningConfig config = getConfig(factoryId);
        return Boolean.TRUE.equals(config.getLlmFallbackEnabled());
    }

    @Override
    public int getMaxKeywordsPerIntent(String factoryId) {
        FactoryAILearningConfig config = getConfig(factoryId);
        return config.getMaxKeywordsPerIntent() != null ? config.getMaxKeywordsPerIntent() : 50;
    }

    @Override
    public BigDecimal getLlmNewKeywordWeight(String factoryId) {
        FactoryAILearningConfig config = getConfig(factoryId);
        return config.getLlmNewKeywordWeight() != null
            ? config.getLlmNewKeywordWeight()
            : BigDecimal.valueOf(0.80);
    }

    @Override
    @Transactional
    public int checkPhaseTransitions() {
        List<FactoryAILearningConfig> readyFactories =
            configRepository.findFactoriesReadyForMatureTransition();

        int transitioned = 0;
        for (FactoryAILearningConfig config : readyFactories) {
            if (config.shouldTransitionToMature()) {
                config.transitionToMature();
                configRepository.save(config);
                log.info("工厂 {} 已从 LEARNING 阶段转换到 MATURE 阶段", config.getFactoryId());
                transitioned++;
            }
        }

        if (transitioned > 0) {
            log.info("阶段转换检查完成: {} 个工厂转换到成熟阶段", transitioned);
        }

        return transitioned;
    }

    @Override
    public List<String> getAutoLearnEnabledFactories() {
        return configRepository.findByAutoLearnEnabledTrue()
            .stream()
            .map(FactoryAILearningConfig::getFactoryId)
            .collect(Collectors.toList());
    }

    @Override
    public List<FactoryAILearningConfig> getCleanupEnabledFactories() {
        return configRepository.findByCleanupEnabledTrue();
    }

    @Override
    @Transactional
    public FactoryAILearningConfig initializeIfNotExists(String factoryId) {
        return configRepository.findByFactoryId(factoryId)
            .orElseGet(() -> {
                FactoryAILearningConfig config = FactoryAILearningConfig.createDefault(factoryId);
                FactoryAILearningConfig saved = configRepository.save(config);
                log.info("初始化工厂配置: factoryId={}", factoryId);
                return saved;
            });
    }

    @Override
    @Transactional
    public void updateLastSpecificityRecalcTime(String factoryId) {
        configRepository.updateLastSpecificityRecalcTime(factoryId, LocalDateTime.now());
        log.debug("更新工厂 {} 的 specificity 重算时间", factoryId);
    }
}
