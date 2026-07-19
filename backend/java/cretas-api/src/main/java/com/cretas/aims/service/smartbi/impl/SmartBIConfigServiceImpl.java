package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.dto.smartbi.ConfigOperationResult;
import com.cretas.aims.dto.smartbi.CreateAlertThresholdRequest;
import com.cretas.aims.dto.smartbi.CreateIncentiveRuleRequest;
import com.cretas.aims.dto.smartbi.UpdateIncentiveRuleRequest;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.smartbi.*;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.smartbi.*;
import com.cretas.aims.service.smartbi.ChartTemplateService;
import com.cretas.aims.service.smartbi.SmartBIConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SmartBI 配置管理服务实现
 *
 * <p>实现统一的配置管理功能：
 * <ul>
 *   <li>提供 CRUD 操作</li>
 *   <li>管理内存缓存</li>
 *   <li>支持配置热重载</li>
 *   <li>记录配置变更日志</li>
 * </ul>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartBIConfigServiceImpl implements SmartBIConfigService {

    private final AIIntentConfigRepository intentConfigRepository;
    private final SmartBiAlertThresholdRepository alertThresholdRepository;
    private final SmartBiIncentiveRuleRepository incentiveRuleRepository;
    private final SmartBiDictionaryRepository dictionaryRepository;
    private final SmartBiMetricFormulaRepository metricFormulaRepository;
    private final SmartBiChartTemplateRepository chartTemplateRepository;
    private final CacheManager cacheManager;

    // 使用 @Lazy 注入以避免循环依赖
    private ChartTemplateService chartTemplateService;

    @Autowired
    @Lazy
    public void setChartTemplateService(ChartTemplateService chartTemplateService) {
        this.chartTemplateService = chartTemplateService;
    }

    // 内存缓存
    private final Map<String, List<AIIntentConfig>> intentCache = new ConcurrentHashMap<>();
    private final Map<String, List<SmartBiAlertThreshold>> thresholdCache = new ConcurrentHashMap<>();
    private final Map<String, List<SmartBiIncentiveRule>> incentiveRuleCache = new ConcurrentHashMap<>();
    private final Map<String, List<SmartBiDictionary>> dictionaryCache = new ConcurrentHashMap<>();
    private final Map<String, List<SmartBiMetricFormula>> metricFormulaCache = new ConcurrentHashMap<>();
    private final Map<String, List<SmartBiChartTemplate>> chartTemplateCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> metricToTemplatesCache = new ConcurrentHashMap<>();

    // 最后更新时间
    private LocalDateTime lastIntentUpdate;
    private LocalDateTime lastThresholdUpdate;
    private LocalDateTime lastIncentiveRuleUpdate;
    private LocalDateTime lastFieldMappingUpdate;
    private LocalDateTime lastMetricFormulaUpdate;
    private LocalDateTime lastChartTemplateUpdate;

    @PostConstruct
    public void init() {
        log.info("初始化 SmartBI 配置缓存...");
        try {
            reloadAll();
        } catch (Exception e) {
            log.warn("SmartBI 配置缓存初始化失败（表可能不存在）: {}", e.getMessage());
        }
    }

    // ==================== 意图配置 ====================

    @Override
    public List<AIIntentConfig> listIntents(String category) {
        if (category != null && !category.isEmpty()) {
            return intentConfigRepository
                    .findByIntentCategoryAndIsActiveTrueAndDeletedAtIsNullOrderByPriorityDesc(category);
        }
        return intentConfigRepository.findByIsActiveTrueAndDeletedAtIsNullOrderByPriorityDesc();
    }

    // ==================== 告警阈值 ====================

    @Override
    public List<SmartBiAlertThreshold> listThresholds(String type) {
        if (type != null && !type.isEmpty()) {
            return alertThresholdRepository.findByThresholdTypeAndIsActiveTrue(type);
        }
        return alertThresholdRepository.findAll();
    }

    @Override
    @Transactional
    public ConfigOperationResult createThreshold(CreateAlertThresholdRequest request) {
        try {
            // 验证唯一性
            if (alertThresholdRepository.existsByThresholdTypeAndMetricCodeAndFactoryId(
                    request.getThresholdType(), request.getMetricCode(), request.getFactoryId())) {
                return ConfigOperationResult.error(
                        ConfigOperationResult.CONFIG_TYPE_THRESHOLD,
                        "阈值配置已存在: " + request.getThresholdType() + "/" + request.getMetricCode());
            }

            // Rule 17.1 fix: 显式构造 entity，不走 Builder/@Builder.Default 链路。
            // 业务默认值在此处显式应用，与原行为保持一致：
            //   - comparisonOperator 缺省 "GT"（对应 entity @Builder.Default = "GT"）
            //   - isActive 强制 true（保持原有 createThreshold 业务约定）
            SmartBiAlertThreshold threshold = new SmartBiAlertThreshold();
            threshold.setThresholdType(request.getThresholdType());
            threshold.setMetricCode(request.getMetricCode());
            threshold.setWarningValue(request.getWarningValue());
            threshold.setCriticalValue(request.getCriticalValue());
            threshold.setComparisonOperator(
                    request.getComparisonOperator() != null ? request.getComparisonOperator() : "GT");
            threshold.setUnit(request.getUnit());
            threshold.setDescription(request.getDescription());
            threshold.setFactoryId(request.getFactoryId());
            threshold.setIsActive(true);

            alertThresholdRepository.save(threshold);
            log.info("创建告警阈值: type={}, metricCode={}",
                    threshold.getThresholdType(), threshold.getMetricCode());

            // 刷新缓存
            refreshThresholdCache();

            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_THRESHOLD,
                    ConfigOperationResult.OPERATION_CREATE,
                    "阈值配置创建成功", 1);
        } catch (Exception e) {
            log.error("创建告警阈值失败: {}", e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_THRESHOLD,
                    "创建失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ConfigOperationResult updateThreshold(String id, SmartBiAlertThreshold threshold) {
        try {
            Optional<SmartBiAlertThreshold> existingOpt = alertThresholdRepository.findById(id);
            if (existingOpt.isEmpty()) {
                return ConfigOperationResult.error(
                        ConfigOperationResult.CONFIG_TYPE_THRESHOLD,
                        "阈值配置不存在: id=" + id);
            }

            SmartBiAlertThreshold existing = existingOpt.get();
            updateThresholdFields(existing, threshold);
            alertThresholdRepository.save(existing);
            log.info("更新告警阈值: id={}, metricCode={}", id, existing.getMetricCode());

            // 刷新缓存
            refreshThresholdCache();

            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_THRESHOLD,
                    ConfigOperationResult.OPERATION_UPDATE,
                    "阈值配置更新成功", 1);
        } catch (Exception e) {
            log.error("更新告警阈值失败: id={}, error={}", id, e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_THRESHOLD,
                    "更新失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ConfigOperationResult deleteThreshold(String id) {
        try {
            Optional<SmartBiAlertThreshold> existingOpt = alertThresholdRepository.findById(id);
            if (existingOpt.isEmpty()) {
                return ConfigOperationResult.error(
                        ConfigOperationResult.CONFIG_TYPE_THRESHOLD,
                        "阈值配置不存在: id=" + id);
            }

            SmartBiAlertThreshold existing = existingOpt.get();
            existing.setIsActive(false);
            existing.softDelete();
            alertThresholdRepository.save(existing);
            log.info("删除告警阈值: id={}, metricCode={}", id, existing.getMetricCode());

            // 刷新缓存
            refreshThresholdCache();

            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_THRESHOLD,
                    ConfigOperationResult.OPERATION_DELETE,
                    "阈值配置删除成功", 1);
        } catch (Exception e) {
            log.error("删除告警阈值失败: id={}, error={}", id, e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_THRESHOLD,
                    "删除失败: " + e.getMessage());
        }
    }

    @Override
    public ConfigOperationResult reloadThresholds() {
        try {
            int count = refreshThresholdCache();
            log.info("重载告警阈值缓存: count={}", count);
            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_THRESHOLD,
                    ConfigOperationResult.OPERATION_RELOAD,
                    "阈值配置重载成功", count);
        } catch (Exception e) {
            log.error("重载告警阈值失败: {}", e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_THRESHOLD,
                    "重载失败: " + e.getMessage());
        }
    }

    // ==================== 激励规则 ====================

    @Override
    public List<SmartBiIncentiveRule> listIncentiveRules(String ruleCode) {
        if (ruleCode != null && !ruleCode.isEmpty()) {
            return incentiveRuleRepository.findByRuleCodeAndIsActiveTrueOrderBySortOrderAsc(ruleCode);
        }
        return incentiveRuleRepository.findByIsActiveTrueOrderByRuleCodeAscSortOrderAsc();
    }

    @Override
    @Transactional
    public ConfigOperationResult createIncentiveRule(CreateIncentiveRuleRequest request) {
        try {
            // Rule 17.1 fix: 显式构造 entity，不走 Builder/@Builder.Default 链路。
            // 业务默认值在此处显式应用：
            //   - isActive 强制 true（保持原有 createIncentiveRule 业务约定）
            //   - sortOrder 缺省 0（对应 entity @Builder.Default = 0）
            SmartBiIncentiveRule rule = new SmartBiIncentiveRule();
            rule.setRuleCode(request.getRuleCode());
            rule.setRuleName(request.getRuleName());
            rule.setLevelName(request.getLevelName());
            rule.setMinValue(request.getMinValue());
            rule.setMaxValue(request.getMaxValue());
            rule.setRewardRate(request.getRewardRate());
            rule.setRewardAmount(request.getRewardAmount());
            rule.setDescription(request.getDescription());
            rule.setFactoryId(request.getFactoryId());
            rule.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
            rule.setIsActive(true);

            incentiveRuleRepository.save(rule);
            log.info("创建激励规则: ruleCode={}, levelName={}",
                    rule.getRuleCode(), rule.getLevelName());

            // 刷新缓存
            refreshIncentiveRuleCache();

            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_INCENTIVE_RULE,
                    ConfigOperationResult.OPERATION_CREATE,
                    "激励规则创建成功", 1);
        } catch (Exception e) {
            log.error("创建激励规则失败: {}", e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_INCENTIVE_RULE,
                    "创建失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ConfigOperationResult updateIncentiveRule(Long id, UpdateIncentiveRuleRequest request) {
        try {
            Optional<SmartBiIncentiveRule> existingOpt = incentiveRuleRepository.findById(id);
            if (existingOpt.isEmpty()) {
                return ConfigOperationResult.error(
                        ConfigOperationResult.CONFIG_TYPE_INCENTIVE_RULE,
                        "激励规则不存在: id=" + id);
            }

            SmartBiIncentiveRule existing = existingOpt.get();
            // Rule 17.1 fix: null-aware copy from DTO. 缺失字段保留原值，
            // 阻止 @Builder.Default (isActive=true, sortOrder=0) 静默覆盖。
            applyUpdateIncentiveRule(existing, request);
            incentiveRuleRepository.save(existing);
            log.info("更新激励规则: id={}, ruleCode={}", id, existing.getRuleCode());

            // 刷新缓存
            refreshIncentiveRuleCache();

            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_INCENTIVE_RULE,
                    ConfigOperationResult.OPERATION_UPDATE,
                    "激励规则更新成功", 1);
        } catch (Exception e) {
            log.error("更新激励规则失败: id={}, error={}", id, e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_INCENTIVE_RULE,
                    "更新失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ConfigOperationResult deleteIncentiveRule(Long id) {
        try {
            Optional<SmartBiIncentiveRule> existingOpt = incentiveRuleRepository.findById(id);
            if (existingOpt.isEmpty()) {
                return ConfigOperationResult.error(
                        ConfigOperationResult.CONFIG_TYPE_INCENTIVE_RULE,
                        "激励规则不存在: id=" + id);
            }

            SmartBiIncentiveRule existing = existingOpt.get();
            existing.setIsActive(false);
            existing.softDelete();
            incentiveRuleRepository.save(existing);
            log.info("删除激励规则: id={}, ruleCode={}", id, existing.getRuleCode());

            // 刷新缓存
            refreshIncentiveRuleCache();

            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_INCENTIVE_RULE,
                    ConfigOperationResult.OPERATION_DELETE,
                    "激励规则删除成功", 1);
        } catch (Exception e) {
            log.error("删除激励规则失败: id={}, error={}", id, e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_INCENTIVE_RULE,
                    "删除失败: " + e.getMessage());
        }
    }

    @Override
    public ConfigOperationResult reloadIncentiveRules() {
        try {
            int count = refreshIncentiveRuleCache();
            log.info("重载激励规则缓存: count={}", count);
            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_INCENTIVE_RULE,
                    ConfigOperationResult.OPERATION_RELOAD,
                    "激励规则重载成功", count);
        } catch (Exception e) {
            log.error("重载激励规则失败: {}", e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_INCENTIVE_RULE,
                    "重载失败: " + e.getMessage());
        }
    }

    // ==================== 字段映射 ====================

    @Override
    public List<SmartBiDictionary> listFieldMappings(String dictType) {
        if (dictType != null && !dictType.isEmpty()) {
            return dictionaryRepository.findByDictTypeAndIsActiveTrueOrderByPriorityAsc(dictType);
        }
        return dictionaryRepository.findAll();
    }

    @Override
    @Transactional
    public ConfigOperationResult createFieldMapping(SmartBiDictionary mapping) {
        try {
            // 验证唯一性
            if (dictionaryRepository.existsByDictTypeAndNameAndFactoryId(
                    mapping.getDictType(), mapping.getName(), mapping.getFactoryId())) {
                return ConfigOperationResult.error(
                        ConfigOperationResult.CONFIG_TYPE_FIELD_MAPPING,
                        "字段映射已存在: " + mapping.getDictType() + "/" + mapping.getName());
            }

            mapping.setIsActive(true);
            dictionaryRepository.save(mapping);
            log.info("创建字段映射: dictType={}, name={}", mapping.getDictType(), mapping.getName());

            // 刷新缓存
            refreshDictionaryCache();

            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_FIELD_MAPPING,
                    ConfigOperationResult.OPERATION_CREATE,
                    "字段映射创建成功", 1);
        } catch (Exception e) {
            log.error("创建字段映射失败: {}", e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_FIELD_MAPPING,
                    "创建失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ConfigOperationResult updateFieldMapping(Long id, SmartBiDictionary mapping) {
        try {
            Optional<SmartBiDictionary> existingOpt = dictionaryRepository.findById(id);
            if (existingOpt.isEmpty()) {
                return ConfigOperationResult.error(
                        ConfigOperationResult.CONFIG_TYPE_FIELD_MAPPING,
                        "字段映射不存在: id=" + id);
            }

            SmartBiDictionary existing = existingOpt.get();
            updateDictionaryFields(existing, mapping);
            dictionaryRepository.save(existing);
            log.info("更新字段映射: id={}, name={}", id, existing.getName());

            // 刷新缓存
            refreshDictionaryCache();

            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_FIELD_MAPPING,
                    ConfigOperationResult.OPERATION_UPDATE,
                    "字段映射更新成功", 1);
        } catch (Exception e) {
            log.error("更新字段映射失败: id={}, error={}", id, e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_FIELD_MAPPING,
                    "更新失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ConfigOperationResult deleteFieldMapping(Long id) {
        try {
            Optional<SmartBiDictionary> existingOpt = dictionaryRepository.findById(id);
            if (existingOpt.isEmpty()) {
                return ConfigOperationResult.error(
                        ConfigOperationResult.CONFIG_TYPE_FIELD_MAPPING,
                        "字段映射不存在: id=" + id);
            }

            SmartBiDictionary existing = existingOpt.get();
            existing.setIsActive(false);
            existing.softDelete();
            dictionaryRepository.save(existing);
            log.info("删除字段映射: id={}, name={}", id, existing.getName());

            // 刷新缓存
            refreshDictionaryCache();

            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_FIELD_MAPPING,
                    ConfigOperationResult.OPERATION_DELETE,
                    "字段映射删除成功", 1);
        } catch (Exception e) {
            log.error("删除字段映射失败: id={}, error={}", id, e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_FIELD_MAPPING,
                    "删除失败: " + e.getMessage());
        }
    }

    @Override
    public ConfigOperationResult reloadFieldMappings() {
        try {
            int count = refreshDictionaryCache();
            log.info("重载字段映射缓存: count={}", count);
            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_FIELD_MAPPING,
                    ConfigOperationResult.OPERATION_RELOAD,
                    "字段映射重载成功", count);
        } catch (Exception e) {
            log.error("重载字段映射失败: {}", e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_FIELD_MAPPING,
                    "重载失败: " + e.getMessage());
        }
    }

    // ==================== 指标公式 ====================

    @Override
    public List<SmartBiMetricFormula> listMetricFormulas(String formulaType) {
        if (formulaType != null && !formulaType.isEmpty()) {
            return metricFormulaRepository.findByFormulaTypeAndIsActiveTrue(formulaType);
        }
        return metricFormulaRepository.findByIsActiveTrueOrderByMetricCodeAsc();
    }

    @Override
    @Transactional
    public ConfigOperationResult createMetricFormula(SmartBiMetricFormula formula) {
        try {
            // 验证唯一性
            if (metricFormulaRepository.existsByMetricCodeAndIsActiveTrue(formula.getMetricCode())) {
                return ConfigOperationResult.error(
                        ConfigOperationResult.CONFIG_TYPE_METRIC_FORMULA,
                        "指标公式已存在: " + formula.getMetricCode());
            }

            formula.setIsActive(true);
            metricFormulaRepository.save(formula);
            log.info("创建指标公式: metricCode={}", formula.getMetricCode());

            // 刷新缓存
            refreshMetricFormulaCache();

            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_METRIC_FORMULA,
                    ConfigOperationResult.OPERATION_CREATE,
                    "指标公式创建成功", 1);
        } catch (Exception e) {
            log.error("创建指标公式失败: {}", e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_METRIC_FORMULA,
                    "创建失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ConfigOperationResult updateMetricFormula(Long id, SmartBiMetricFormula formula) {
        try {
            Optional<SmartBiMetricFormula> existingOpt = metricFormulaRepository.findById(id);
            if (existingOpt.isEmpty()) {
                return ConfigOperationResult.error(
                        ConfigOperationResult.CONFIG_TYPE_METRIC_FORMULA,
                        "指标公式不存在: id=" + id);
            }

            SmartBiMetricFormula existing = existingOpt.get();
            updateMetricFormulaFields(existing, formula);
            metricFormulaRepository.save(existing);
            log.info("更新指标公式: id={}, metricCode={}", id, existing.getMetricCode());

            // 刷新缓存
            refreshMetricFormulaCache();

            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_METRIC_FORMULA,
                    ConfigOperationResult.OPERATION_UPDATE,
                    "指标公式更新成功", 1);
        } catch (Exception e) {
            log.error("更新指标公式失败: id={}, error={}", id, e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_METRIC_FORMULA,
                    "更新失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ConfigOperationResult deleteMetricFormula(Long id) {
        try {
            Optional<SmartBiMetricFormula> existingOpt = metricFormulaRepository.findById(id);
            if (existingOpt.isEmpty()) {
                return ConfigOperationResult.error(
                        ConfigOperationResult.CONFIG_TYPE_METRIC_FORMULA,
                        "指标公式不存在: id=" + id);
            }

            SmartBiMetricFormula existing = existingOpt.get();
            existing.setIsActive(false);
            existing.softDelete();
            metricFormulaRepository.save(existing);
            log.info("删除指标公式: id={}, metricCode={}", id, existing.getMetricCode());

            // 刷新缓存
            refreshMetricFormulaCache();

            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_METRIC_FORMULA,
                    ConfigOperationResult.OPERATION_DELETE,
                    "指标公式删除成功", 1);
        } catch (Exception e) {
            log.error("删除指标公式失败: id={}, error={}", id, e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_METRIC_FORMULA,
                    "删除失败: " + e.getMessage());
        }
    }

    @Override
    public ConfigOperationResult reloadMetricFormulas() {
        try {
            int count = refreshMetricFormulaCache();
            log.info("重载指标公式缓存: count={}", count);
            return ConfigOperationResult.success(
                    ConfigOperationResult.CONFIG_TYPE_METRIC_FORMULA,
                    ConfigOperationResult.OPERATION_RELOAD,
                    "指标公式重载成功", count);
        } catch (Exception e) {
            log.error("重载指标公式失败: {}", e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_METRIC_FORMULA,
                    "重载失败: " + e.getMessage());
        }
    }

    // ==================== 图表模板 ====================

    @Override
    public List<SmartBiChartTemplate> listChartTemplates(String category, String chartType) {
        if (category != null && !category.isEmpty() && chartType != null && !chartType.isEmpty()) {
            return chartTemplateRepository.findByChartTypeAndCategoryAndIsActiveTrueOrderBySortOrder(chartType, category);
        } else if (category != null && !category.isEmpty()) {
            return chartTemplateRepository.findByCategoryAndIsActiveTrueOrderBySortOrder(category);
        } else if (chartType != null && !chartType.isEmpty()) {
            return chartTemplateRepository.findByChartTypeAndIsActiveTrueOrderBySortOrder(chartType);
        }
        return chartTemplateRepository.findByIsActiveTrueOrderBySortOrder();
    }

    @Override
    @Transactional
    public ConfigOperationResult createChartTemplate(SmartBiChartTemplate template) {
        try {
            // 验证唯一性
            if (chartTemplateRepository.existsByTemplateCodeAndIsActiveTrue(template.getTemplateCode())) {
                return ConfigOperationResult.error(
                        "CHART_TEMPLATE",
                        "图表模板已存在: " + template.getTemplateCode());
            }

            template.setIsActive(true);
            chartTemplateRepository.save(template);
            log.info("创建图表模板: templateCode={}", template.getTemplateCode());

            // 刷新缓存
            refreshChartTemplateCache();

            return ConfigOperationResult.success(
                    "CHART_TEMPLATE",
                    ConfigOperationResult.OPERATION_CREATE,
                    "图表模板创建成功", 1);
        } catch (Exception e) {
            log.error("创建图表模板失败: {}", e.getMessage(), e);
            return ConfigOperationResult.error(
                    "CHART_TEMPLATE",
                    "创建失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ConfigOperationResult updateChartTemplate(Long id, SmartBiChartTemplate template) {
        try {
            Optional<SmartBiChartTemplate> existingOpt = chartTemplateRepository.findById(id);
            if (existingOpt.isEmpty()) {
                return ConfigOperationResult.error(
                        "CHART_TEMPLATE",
                        "图表模板不存在: id=" + id);
            }

            SmartBiChartTemplate existing = existingOpt.get();
            updateChartTemplateFields(existing, template);
            chartTemplateRepository.save(existing);
            log.info("更新图表模板: id={}, templateCode={}", id, existing.getTemplateCode());

            // 刷新缓存
            refreshChartTemplateCache();

            return ConfigOperationResult.success(
                    "CHART_TEMPLATE",
                    ConfigOperationResult.OPERATION_UPDATE,
                    "图表模板更新成功", 1);
        } catch (Exception e) {
            log.error("更新图表模板失败: id={}, error={}", id, e.getMessage(), e);
            return ConfigOperationResult.error(
                    "CHART_TEMPLATE",
                    "更新失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public ConfigOperationResult deleteChartTemplate(Long id) {
        try {
            Optional<SmartBiChartTemplate> existingOpt = chartTemplateRepository.findById(id);
            if (existingOpt.isEmpty()) {
                return ConfigOperationResult.error(
                        "CHART_TEMPLATE",
                        "图表模板不存在: id=" + id);
            }

            SmartBiChartTemplate existing = existingOpt.get();
            existing.setIsActive(false);
            existing.softDelete();
            chartTemplateRepository.save(existing);
            log.info("删除图表模板: id={}, templateCode={}", id, existing.getTemplateCode());

            // 刷新缓存
            refreshChartTemplateCache();

            return ConfigOperationResult.success(
                    "CHART_TEMPLATE",
                    ConfigOperationResult.OPERATION_DELETE,
                    "图表模板删除成功", 1);
        } catch (Exception e) {
            log.error("删除图表模板失败: id={}, error={}", id, e.getMessage(), e);
            return ConfigOperationResult.error(
                    "CHART_TEMPLATE",
                    "删除失败: " + e.getMessage());
        }
    }

    @Override
    public ConfigOperationResult reloadChartTemplates() {
        try {
            int count = refreshChartTemplateCache();
            log.info("重载图表模板缓存: count={}", count);
            return ConfigOperationResult.success(
                    "CHART_TEMPLATE",
                    ConfigOperationResult.OPERATION_RELOAD,
                    "图表模板重载成功", count);
        } catch (Exception e) {
            log.error("重载图表模板失败: {}", e.getMessage(), e);
            return ConfigOperationResult.error(
                    "CHART_TEMPLATE",
                    "重载失败: " + e.getMessage());
        }
    }

    @Override
    public SmartBiChartTemplate getChartTemplate(String templateCode, String factoryId) {
        // 先查工厂级配置
        if (factoryId != null && !factoryId.isEmpty()) {
            Optional<SmartBiChartTemplate> factoryTemplate =
                    chartTemplateRepository.findByTemplateCodeAndFactoryId(templateCode, factoryId);
            if (factoryTemplate.isPresent() && factoryTemplate.get().getIsActive()) {
                return factoryTemplate.get();
            }
        }
        // 再查全局配置
        return chartTemplateRepository.findByTemplateCodeAndFactoryIdIsNull(templateCode)
                .filter(SmartBiChartTemplate::getIsActive)
                .orElse(null);
    }

    @Override
    public List<SmartBiChartTemplate> getChartTemplatesForMetric(String metricCode) {
        List<String> templateCodes = metricToTemplatesCache.get(metricCode);
        if (templateCodes == null || templateCodes.isEmpty()) {
            // 从数据库查询
            return chartTemplateRepository.findByApplicableMetricContaining(metricCode);
        }
        // 从缓存获取
        List<SmartBiChartTemplate> result = new ArrayList<>();
        List<SmartBiChartTemplate> allTemplates = chartTemplateCache.get("all");
        if (allTemplates != null) {
            for (SmartBiChartTemplate template : allTemplates) {
                if (templateCodes.contains(template.getTemplateCode())) {
                    result.add(template);
                }
            }
        }
        return result;
    }

    @Override
    public String recommendChartType(String metricCode, int dataPoints, boolean hasTimeDimension) {
        // 1. 先检查是否有指定模板
        List<SmartBiChartTemplate> templates = getChartTemplatesForMetric(metricCode);
        if (!templates.isEmpty()) {
            return templates.get(0).getChartType();
        }

        // 2. 根据数据特征智能推荐
        if (hasTimeDimension && dataPoints > 1) {
            return "LINE";  // 时间序列用折线图
        }
        if (metricCode != null) {
            String lowerCode = metricCode.toLowerCase();
            if (lowerCode.contains("ratio") || lowerCode.contains("rate")) {
                if (dataPoints <= 1) {
                    return "GAUGE";  // 单个比率用仪表盘
                }
                if (dataPoints <= 6) {
                    return "RADAR";  // 比率类指标用雷达图
                }
            }
            if (lowerCode.contains("structure") || lowerCode.contains("composition") || lowerCode.contains("distribution")) {
                return "PIE";  // 结构类用饼图
            }
        }
        if (dataPoints > 10) {
            return "BAR";  // 大数据量用柱状图
        }

        return "BAR";  // 默认柱状图
    }

    @Override
    public Map<String, Object> buildChartWithAnalysis(String templateCode, Map<String, Object> data, String factoryId) {
        log.info("构建带AI分析的图表: code={}, factoryId={}", templateCode, factoryId);
        return chartTemplateService.buildChartWithAnalysis(templateCode, data, factoryId);
    }

    // ==================== 全局操作 ====================

    @Override
    public ConfigOperationResult reloadAll() {
        try {
            int intentCount = refreshIntentCache();
            int thresholdCount = refreshThresholdCache();
            int incentiveRuleCount = refreshIncentiveRuleCache();
            int dictionaryCount = refreshDictionaryCache();
            int metricFormulaCount = refreshMetricFormulaCache();
            int chartTemplateCount = refreshChartTemplateCache();

            int totalCount = intentCount + thresholdCount + incentiveRuleCount
                    + dictionaryCount + metricFormulaCount + chartTemplateCount;

            log.info("重载所有配置缓存: intent={}, threshold={}, incentiveRule={}, dictionary={}, metricFormula={}, chartTemplate={}, total={}",
                    intentCount, thresholdCount, incentiveRuleCount, dictionaryCount, metricFormulaCount, chartTemplateCount, totalCount);

            // 清除 Spring Cache
            if (cacheManager != null) {
                cacheManager.getCacheNames().forEach(cacheName -> {
                    var cache = cacheManager.getCache(cacheName);
                    if (cache != null) {
                        cache.clear();
                    }
                });
            }

            Map<String, Integer> counts = new HashMap<>();
            counts.put("intents", intentCount);
            counts.put("thresholds", thresholdCount);
            counts.put("incentiveRules", incentiveRuleCount);
            counts.put("fieldMappings", dictionaryCount);
            counts.put("metricFormulas", metricFormulaCount);
            counts.put("chartTemplates", chartTemplateCount);

            return ConfigOperationResult.builder()
                    .success(true)
                    .configType(ConfigOperationResult.CONFIG_TYPE_ALL)
                    .operationType(ConfigOperationResult.OPERATION_RELOAD)
                    .message("所有配置重载成功")
                    .data(counts)
                    .affectedCount(totalCount)
                    .timestamp(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            log.error("重载所有配置失败: {}", e.getMessage(), e);
            return ConfigOperationResult.error(
                    ConfigOperationResult.CONFIG_TYPE_ALL,
                    "重载失败: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getConfigStatus() {
        Map<String, Object> status = new HashMap<>();

        // 配置数量
        Map<String, Long> counts = new HashMap<>();
        counts.put("intents", intentConfigRepository.count());
        counts.put("thresholds", alertThresholdRepository.count());
        counts.put("incentiveRules", incentiveRuleRepository.count());
        counts.put("fieldMappings", dictionaryRepository.count());
        counts.put("metricFormulas", metricFormulaRepository.count());
        counts.put("chartTemplates", chartTemplateRepository.count());
        status.put("counts", counts);

        // 缓存大小
        Map<String, Integer> cacheSizes = new HashMap<>();
        cacheSizes.put("intents", intentCache.values().stream().mapToInt(List::size).sum());
        cacheSizes.put("thresholds", thresholdCache.values().stream().mapToInt(List::size).sum());
        cacheSizes.put("incentiveRules", incentiveRuleCache.values().stream().mapToInt(List::size).sum());
        cacheSizes.put("fieldMappings", dictionaryCache.values().stream().mapToInt(List::size).sum());
        cacheSizes.put("metricFormulas", metricFormulaCache.values().stream().mapToInt(List::size).sum());
        cacheSizes.put("chartTemplates", chartTemplateCache.values().stream().mapToInt(List::size).sum());
        status.put("cacheSizes", cacheSizes);

        // 最后更新时间
        Map<String, LocalDateTime> lastUpdates = new HashMap<>();
        lastUpdates.put("intents", lastIntentUpdate);
        lastUpdates.put("thresholds", lastThresholdUpdate);
        lastUpdates.put("incentiveRules", lastIncentiveRuleUpdate);
        lastUpdates.put("fieldMappings", lastFieldMappingUpdate);
        lastUpdates.put("metricFormulas", lastMetricFormulaUpdate);
        lastUpdates.put("chartTemplates", lastChartTemplateUpdate);
        status.put("lastUpdates", lastUpdates);

        // 系统信息
        status.put("serverTime", LocalDateTime.now());
        status.put("cacheEnabled", cacheManager != null);

        return status;
    }

    // ==================== 私有辅助方法 ====================

    private int refreshIntentCache() {
        intentCache.clear();
        List<AIIntentConfig> allIntents =
                intentConfigRepository.findByIsActiveTrueAndDeletedAtIsNullOrderByPriorityDesc();
        intentCache.put("all", allIntents);

        // 按分类分组. intent_category 允许 NULL (DB 无 NOT NULL), 新建配置可能漏填.
        // ConcurrentHashMap.putAll 不接受 null key → 先按"UNCATEGORIZED" 兜底再 putAll.
        Map<String, List<AIIntentConfig>> byCategory = new HashMap<>();
        int nullCategoryCount = 0;
        for (AIIntentConfig intent : allIntents) {
            String category = intent.getIntentCategory();
            if (category == null) {
                category = "UNCATEGORIZED";
                nullCategoryCount++;
            }
            byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(intent);
        }
        if (nullCategoryCount > 0) {
            log.warn("refreshIntentCache: {} 个 intent 的 intentCategory 为 null, 已归入 UNCATEGORIZED",
                    nullCategoryCount);
        }
        intentCache.putAll(byCategory);

        lastIntentUpdate = LocalDateTime.now();
        return allIntents.size();
    }

    private int refreshThresholdCache() {
        thresholdCache.clear();
        List<SmartBiAlertThreshold> allThresholds = alertThresholdRepository.findAll();
        thresholdCache.put("all", allThresholds);
        lastThresholdUpdate = LocalDateTime.now();
        return allThresholds.size();
    }

    private int refreshIncentiveRuleCache() {
        incentiveRuleCache.clear();
        List<SmartBiIncentiveRule> allRules = incentiveRuleRepository.findByIsActiveTrueOrderByRuleCodeAscSortOrderAsc();
        incentiveRuleCache.put("all", allRules);
        lastIncentiveRuleUpdate = LocalDateTime.now();
        return allRules.size();
    }

    private int refreshDictionaryCache() {
        dictionaryCache.clear();
        List<SmartBiDictionary> allMappings = dictionaryRepository.findAll();
        dictionaryCache.put("all", allMappings);
        lastFieldMappingUpdate = LocalDateTime.now();
        return allMappings.size();
    }

    private int refreshMetricFormulaCache() {
        metricFormulaCache.clear();
        List<SmartBiMetricFormula> allFormulas = metricFormulaRepository.findByIsActiveTrueOrderByMetricCodeAsc();
        metricFormulaCache.put("all", allFormulas);
        lastMetricFormulaUpdate = LocalDateTime.now();
        return allFormulas.size();
    }

    private int refreshChartTemplateCache() {
        chartTemplateCache.clear();
        metricToTemplatesCache.clear();

        List<SmartBiChartTemplate> allTemplates = chartTemplateRepository.findByIsActiveTrueOrderBySortOrder();
        chartTemplateCache.put("all", allTemplates);

        // 按分类分组
        Map<String, List<SmartBiChartTemplate>> byCategory = new HashMap<>();
        for (SmartBiChartTemplate template : allTemplates) {
            byCategory.computeIfAbsent(template.getCategory(), k -> new ArrayList<>()).add(template);

            // 构建指标到模板的映射
            String applicableMetrics = template.getApplicableMetrics();
            if (applicableMetrics != null && !applicableMetrics.isEmpty()) {
                try {
                    // 简单解析 JSON 数组，例如 ["metric1","metric2"]
                    String cleaned = applicableMetrics.replaceAll("[\\[\\]\"]", "");
                    String[] metrics = cleaned.split(",");
                    for (String metric : metrics) {
                        String trimmed = metric.trim();
                        if (!trimmed.isEmpty()) {
                            metricToTemplatesCache.computeIfAbsent(trimmed, k -> new ArrayList<>())
                                    .add(template.getTemplateCode());
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析图表模板 {} 的 applicableMetrics 失败: {}",
                            template.getTemplateCode(), e.getMessage());
                }
            }
        }
        chartTemplateCache.putAll(byCategory);

        lastChartTemplateUpdate = LocalDateTime.now();
        return allTemplates.size();
    }

    private void updateThresholdFields(SmartBiAlertThreshold existing, SmartBiAlertThreshold updated) {
        if (updated.getWarningValue() != null) existing.setWarningValue(updated.getWarningValue());
        if (updated.getCriticalValue() != null) existing.setCriticalValue(updated.getCriticalValue());
        if (updated.getComparisonOperator() != null) existing.setComparisonOperator(updated.getComparisonOperator());
        if (updated.getUnit() != null) existing.setUnit(updated.getUnit());
        if (updated.getDescription() != null) existing.setDescription(updated.getDescription());
        if (updated.getIsActive() != null) existing.setIsActive(updated.getIsActive());
    }

    /**
     * Null-aware copy from {@link UpdateIncentiveRuleRequest} into existing entity.
     *
     * <p>Rule 17.1 fix: 接受 DTO 而非实体。DTO 字段为 null = 客户端未提供，保留原值。
     * 阻止 {@code @Builder.Default}（{@code isActive=true}、{@code sortOrder=0}）通过
     * Jackson {@code @NoArgsConstructor} 字段初始化器静默写回。
     *
     * <p>不可变字段 {@code ruleCode} 和 {@code factoryId} 不在 DTO 中暴露，无需复制。
     */
    private void applyUpdateIncentiveRule(SmartBiIncentiveRule existing, UpdateIncentiveRuleRequest req) {
        if (req.getRuleName() != null) existing.setRuleName(req.getRuleName());
        if (req.getLevelName() != null) existing.setLevelName(req.getLevelName());
        if (req.getMinValue() != null) existing.setMinValue(req.getMinValue());
        if (req.getMaxValue() != null) existing.setMaxValue(req.getMaxValue());
        if (req.getRewardRate() != null) existing.setRewardRate(req.getRewardRate());
        if (req.getRewardAmount() != null) existing.setRewardAmount(req.getRewardAmount());
        if (req.getDescription() != null) existing.setDescription(req.getDescription());
        if (req.getSortOrder() != null) existing.setSortOrder(req.getSortOrder());
        if (req.getIsActive() != null) existing.setIsActive(req.getIsActive());
    }

    private void updateDictionaryFields(SmartBiDictionary existing, SmartBiDictionary updated) {
        if (updated.getName() != null) existing.setName(updated.getName());
        if (updated.getAliases() != null) existing.setAliases(updated.getAliases());
        if (updated.getParentName() != null) existing.setParentName(updated.getParentName());
        if (updated.getMetadata() != null) existing.setMetadata(updated.getMetadata());
        if (updated.getSource() != null) existing.setSource(updated.getSource());
        if (updated.getPriority() != null) existing.setPriority(updated.getPriority());
        if (updated.getIsActive() != null) existing.setIsActive(updated.getIsActive());
    }

    private void updateMetricFormulaFields(SmartBiMetricFormula existing, SmartBiMetricFormula updated) {
        if (updated.getMetricName() != null) existing.setMetricName(updated.getMetricName());
        if (updated.getFormulaType() != null) existing.setFormulaType(updated.getFormulaType());
        if (updated.getBaseField() != null) existing.setBaseField(updated.getBaseField());
        if (updated.getFormulaExpression() != null) existing.setFormulaExpression(updated.getFormulaExpression());
        if (updated.getAggregation() != null) existing.setAggregation(updated.getAggregation());
        if (updated.getUnit() != null) existing.setUnit(updated.getUnit());
        if (updated.getFormatPattern() != null) existing.setFormatPattern(updated.getFormatPattern());
        if (updated.getDescription() != null) existing.setDescription(updated.getDescription());
        if (updated.getIsActive() != null) existing.setIsActive(updated.getIsActive());
    }

    private void updateChartTemplateFields(SmartBiChartTemplate existing, SmartBiChartTemplate updated) {
        if (updated.getTemplateName() != null) existing.setTemplateName(updated.getTemplateName());
        if (updated.getChartType() != null) existing.setChartType(updated.getChartType());
        if (updated.getCategory() != null) existing.setCategory(updated.getCategory());
        if (updated.getApplicableMetrics() != null) existing.setApplicableMetrics(updated.getApplicableMetrics());
        if (updated.getChartOptions() != null) existing.setChartOptions(updated.getChartOptions());
        if (updated.getDataMapping() != null) existing.setDataMapping(updated.getDataMapping());
        if (updated.getLayoutConfig() != null) existing.setLayoutConfig(updated.getLayoutConfig());
        if (updated.getDescription() != null) existing.setDescription(updated.getDescription());
        if (updated.getThumbnailUrl() != null) existing.setThumbnailUrl(updated.getThumbnailUrl());
        if (updated.getSortOrder() != null) existing.setSortOrder(updated.getSortOrder());
        if (updated.getIsActive() != null) existing.setIsActive(updated.getIsActive());
    }
}
