package com.cretas.aims.scheduler;

import com.cretas.aims.ai.synthetic.SyntheticDataService;
import com.cretas.aims.config.SyntheticDataConfig;
import com.cretas.aims.entity.ModelVersion;
import com.cretas.aims.entity.intent.FactoryAILearningConfig;
import com.cretas.aims.repository.FactoryAILearningConfigRepository;
import com.cretas.aims.repository.ModelVersionRepository;
import com.cretas.aims.repository.TrainingDataRepository;
import com.cretas.aims.service.MixedTrainingDataService;
import com.cretas.aims.service.MixedTrainingDataService.MixedTrainingDataSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型训练调度器
 * 定期检查各工厂的训练数据量，在满足条件时触发模型训练
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ModelTrainingScheduler {

    private final TrainingDataRepository trainingDataRepository;
    private final ModelVersionRepository modelVersionRepository;
    private final SyntheticDataConfig syntheticDataConfig;
    private final SyntheticDataService syntheticDataService;
    private final FactoryAILearningConfigRepository factoryAILearningConfigRepository;
    private final MixedTrainingDataService mixedTrainingDataService;

    @Value("${ml.training.min-data-count:50}")
    private int minTrainingDataCount;

    @Value("${ml.training.retrain-threshold:1.2}")
    private double retrainThreshold;

    @Value("${ml.training.enabled:false}")
    private boolean trainingEnabled;

    static final String TRAINING_CAPABILITY = "NOT_IMPLEMENTED";

    /**
     * 每天凌晨2点检查是否需要训练模型
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "${ml.training.schedule:0 0 2 * * ?}")
    @SchedulerLock(name = "ModelTrainingScheduler.scheduledTrainingCheck", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduledTrainingCheck() {
        if (!trainingEnabled) {
            log.debug("模型训练已禁用，跳过定时检查");
            return;
        }

        log.info("开始定时模型训练检查...");

        try {
            checkAndTrainModels();
        } catch (Exception e) {
            log.error("定时模型训练检查失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 检查所有工厂并按需训练模型
     */
    public void checkAndTrainModels() {
        // 获取所有有训练数据的工厂
        List<String> factories = trainingDataRepository.findDistinctFactoryIds();

        log.info("发现 {} 个工厂有训练数据", factories.size());

        for (String factoryId : factories) {
            try {
                // 在训练前先进行合成数据增强
                augmentWithSyntheticData(factoryId);
                checkAndTrainFactory(factoryId);
            } catch (Exception e) {
                log.error("检查工厂 {} 训练状态失败: {}", factoryId, e.getMessage());
            }
        }
    }

    /**
     * 检查单个工厂是否需要训练
     */
    private void checkAndTrainFactory(String factoryId) {
        long dataCount = trainingDataRepository.countByFactoryId(factoryId);

        log.debug("工厂 {} 当前数据量: {}", factoryId, dataCount);

        if (dataCount < minTrainingDataCount) {
            log.debug("工厂 {} 数据量 {} 不足最小要求 {}，跳过",
                    factoryId, dataCount, minTrainingDataCount);
            return;
        }

        // 检查每种模型类型
        String[] modelTypes = {"efficiency", "duration", "quality"};

        for (String modelType : modelTypes) {
            if (needsTraining(factoryId, modelType, dataCount)) {
                triggerTraining(factoryId, List.of(modelType));
            }
        }
    }

    /**
     * 判断是否需要训练特定类型的模型
     */
    private boolean needsTraining(String factoryId, String modelType, long currentDataCount) {
        Optional<ModelVersion> modelOpt = modelVersionRepository
                .findByFactoryIdAndModelTypeAndIsActiveTrue(factoryId, modelType);

        if (modelOpt.isEmpty()) {
            // 没有该类型的模型，需要训练
            log.info("工厂 {} 无 {} 模型，需要训练", factoryId, modelType);
            return true;
        }

        ModelVersion model = modelOpt.get();

        // 检查模型质量
        if (!model.hasGoodQuality()) {
            log.info("工厂 {} 的 {} 模型质量不佳 (R²={}), 需要重新训练",
                    factoryId, modelType, model.getR2Score());
            return true;
        }

        // 检查数据量增长
        int trainedDataCount = model.getTrainingDataCount() != null
                ? model.getTrainingDataCount() : 0;

        if (currentDataCount > trainedDataCount * retrainThreshold) {
            log.info("工厂 {} 数据量从 {} 增长到 {}，超过阈值 {}，需要重新训练 {} 模型",
                    factoryId, trainedDataCount, currentDataCount, retrainThreshold, modelType);
            return true;
        }

        return false;
    }

    /**
     * 触发模型训练
     */
    public void triggerTraining(String factoryId, List<String> modelTypes) {
        log.warn("模型训练能力不可用: factoryId={}, modelTypes={}, capability={}",
                factoryId, modelTypes, TRAINING_CAPABILITY);
    }

    /**
     * 手动触发所有工厂的模型训练检查
     */
    public Map<String, Object> manualTrainingCheck() {
        Map<String, Object> result = new HashMap<>();
        List<String> factories = trainingDataRepository.findDistinctFactoryIds();

        result.put("factoriesChecked", factories.size());
        result.put("minDataCount", minTrainingDataCount);
        result.put("trainingCapability", TRAINING_CAPABILITY);

        Map<String, Object> factoryStatus = new HashMap<>();
        for (String factoryId : factories) {
            long dataCount = trainingDataRepository.countByFactoryId(factoryId);
            boolean hasModel = modelVersionRepository
                    .existsByFactoryIdAndModelTypeAndIsActiveTrue(factoryId, "efficiency");

            Map<String, Object> status = new HashMap<>();
            status.put("dataCount", dataCount);
            status.put("hasEfficiencyModel", hasModel);
            status.put("meetsMinimum", dataCount >= minTrainingDataCount);
            status.put("trainingCapability", TRAINING_CAPABILITY);

            factoryStatus.put(factoryId, status);
        }

        result.put("factories", factoryStatus);
        return result;
    }

    /**
     * 获取训练状态摘要
     */
    public Map<String, Object> getTrainingStatus() {
        Map<String, Object> status = new HashMap<>();

        List<String> factories = trainingDataRepository.findDistinctFactoryIds();
        List<String> factoriesWithModels = modelVersionRepository.findDistinctFactoryIdsWithActiveModels();

        status.put("totalFactories", factories.size());
        status.put("factoriesWithModels", factoriesWithModels.size());
        status.put("trainingEnabled", trainingEnabled);
        status.put("trainingCapability", TRAINING_CAPABILITY);
        status.put("minDataCount", minTrainingDataCount);
        status.put("retrainThreshold", retrainThreshold);

        return status;
    }

    // ==================== EnvScaler 混合训练支持 ====================

    /**
     * 使用合成数据增强训练集
     *
     * @param factoryId 工厂ID
     */
    public void augmentWithSyntheticData(String factoryId) {
        // 检查工厂级配置是否启用合成数据
        Optional<FactoryAILearningConfig> factoryConfigOpt =
                factoryAILearningConfigRepository.findByFactoryId(factoryId);

        if (factoryConfigOpt.isEmpty()) {
            log.debug("工厂 {} 无 AI 学习配置，跳过合成数据增强", factoryId);
            return;
        }

        FactoryAILearningConfig factoryConfig = factoryConfigOpt.get();
        if (!factoryConfig.getSyntheticEnabled()) {
            log.debug("工厂 {} 的合成数据功能已禁用，跳过增强", factoryId);
            return;
        }

        // 检查全局配置是否启用合成数据
        if (!syntheticDataConfig.isEnabled()) {
            log.debug("全局合成数据功能已禁用，跳过工厂 {} 的增强", factoryId);
            return;
        }

        log.info("开始为工厂 {} 进行合成数据增强...", factoryId);

        try {
            // 调用 SyntheticDataService 生成合成数据
            List<SyntheticDataService.SyntheticGenerationResult> results =
                    syntheticDataService.generateForAllIntents(factoryId, 100);

            // 统计生成结果
            int totalGenerated = results.stream()
                    .mapToInt(SyntheticDataService.SyntheticGenerationResult::getGenerated)
                    .sum();
            int totalSaved = results.stream()
                    .mapToInt(SyntheticDataService.SyntheticGenerationResult::getSaved)
                    .sum();
            long successCount = results.stream()
                    .filter(SyntheticDataService.SyntheticGenerationResult::isSuccess)
                    .count();

            log.info("工厂 {} 合成数据增强完成: 处理 {} 个意图, 成功 {}, 生成 {} 个候选, 保存 {} 个样本",
                    factoryId, results.size(), successCount, totalGenerated, totalSaved);

        } catch (Exception e) {
            log.error("工厂 {} 合成数据增强失败: {}", factoryId, e.getMessage(), e);
        }
    }

    /**
     * 使用混合数据 (真实 + 合成) 进行训练
     *
     * @param factoryId 工厂ID
     * @return 混合训练数据组成信息
     */
    public Map<String, Object> trainWithMixedData(String factoryId) {
        log.info("准备工厂 {} 的混合训练数据...", factoryId);

        // 使用 MixedTrainingDataService 获取带权重的混合数据
        BigDecimal minConfidence = new BigDecimal("0.6");
        MixedTrainingDataSet dataSet = mixedTrainingDataService
                .getMixedTrainingData(factoryId, minConfidence);

        // 构建返回信息
        Map<String, Object> composition = new HashMap<>();
        composition.put("factoryId", factoryId);
        composition.put("realSampleCount", dataSet.getRealCount());
        composition.put("syntheticSampleCount", dataSet.getSyntheticTotal());
        composition.put("syntheticSamplesUsed", dataSet.getSyntheticCount());
        composition.put("totalSamples", dataSet.getTotalCount());
        composition.put("configuredMaxRatio", dataSet.getMaxRatio());
        composition.put("actualRatio", dataSet.getActualRatio());
        composition.put("syntheticWeight", dataSet.getSyntheticWeight());
        composition.put("totalEffectiveWeight", dataSet.getTotalEffectiveWeight());

        log.info("工厂 {} 混合训练数据组成: 真实样本={}, 合成样本={} (使用={}), 总计={}, 合成比例={:.1f}% (上限={:.1f}%), 有效权重={:.2f}",
                factoryId, dataSet.getRealCount(), dataSet.getSyntheticTotal(), dataSet.getSyntheticCount(),
                dataSet.getTotalCount(), dataSet.getActualRatio() * 100, dataSet.getMaxRatio() * 100,
                dataSet.getTotalEffectiveWeight());

        return composition;
    }

    /**
     * 导出混合训练数据用于 AI 服务训练
     *
     * @param factoryId 工厂ID
     * @return 带权重的训练数据列表
     */
    public List<Map<String, Object>> exportMixedTrainingData(String factoryId) {
        BigDecimal minConfidence = new BigDecimal("0.6");
        return mixedTrainingDataService.exportMixedTrainingData(factoryId, minConfidence);
    }

    /**
     * 获取混合训练数据统计
     *
     * @param factoryId 工厂ID
     * @return 统计信息
     */
    public Map<String, Object> getMixedTrainingStats(String factoryId) {
        BigDecimal minConfidence = new BigDecimal("0.6");
        return mixedTrainingDataService.getMixedTrainingStats(factoryId, minConfidence);
    }
}
