package com.cretas.aims.service.smartbi;

import com.cretas.aims.dto.smartbi.ConfigOperationResult;
import com.cretas.aims.dto.smartbi.CreateAlertThresholdRequest;
import com.cretas.aims.dto.smartbi.CreateIncentiveRuleRequest;
import com.cretas.aims.dto.smartbi.UpdateIncentiveRuleRequest;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.smartbi.*;

import java.util.List;
import java.util.Map;

/**
 * SmartBI 配置管理服务接口
 *
 * <p>提供统一的配置管理功能，包括：
 * <ul>
 *   <li>意图配置只读兼容（写入由规范 AIIntentService 单链路负责）</li>
 *   <li>告警阈值管理（CRUD + reload）</li>
 *   <li>激励规则管理（CRUD + reload）</li>
 *   <li>字段映射管理（CRUD + reload）</li>
 *   <li>指标公式管理（CRUD + reload）</li>
 *   <li>图表模板管理（CRUD + reload + 推荐）</li>
 *   <li>全局配置重载</li>
 * </ul>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-21
 */
public interface SmartBIConfigService {

    // ==================== 意图配置 ====================

    /**
     * 获取意图配置列表
     *
     * @param category 意图分类（可选，null 表示获取所有）
     * @return 意图配置列表
     */
    List<AIIntentConfig> listIntents(String category);

    // ==================== 告警阈值 ====================

    /**
     * 获取告警阈值列表
     *
     * @param type 阈值类型（可选，null 表示获取所有）
     * @return 阈值配置列表
     */
    List<SmartBiAlertThreshold> listThresholds(String type);

    /**
     * 创建告警阈值
     *
     * <p>Rule 17.1 anti-pattern fix（Issue #320）：
     * 接收 {@link CreateAlertThresholdRequest} 而非实体直绑，避免 {@code @Builder.Default}
     * 字段（{@code comparisonOperator="GT"}、{@code isActive=true}）通过 Jackson
     * 默认构造器静默写入。业务默认值由 service 层显式应用。
     *
     * @param request 阈值创建请求
     * @return 创建结果
     */
    ConfigOperationResult createThreshold(CreateAlertThresholdRequest request);

    /**
     * 更新告警阈值
     *
     * @param id        配置ID
     * @param threshold 阈值配置
     * @return 更新结果
     */
    ConfigOperationResult updateThreshold(String id, SmartBiAlertThreshold threshold);

    /**
     * 删除告警阈值（软删除）
     *
     * @param id 配置ID
     * @return 删除结果
     */
    ConfigOperationResult deleteThreshold(String id);

    /**
     * 重载告警阈值缓存
     *
     * @return 重载结果
     */
    ConfigOperationResult reloadThresholds();

    // ==================== 激励规则 ====================

    /**
     * 获取激励规则列表
     *
     * @param ruleCode 规则代码（可选，null 表示获取所有）
     * @return 激励规则列表
     */
    List<SmartBiIncentiveRule> listIncentiveRules(String ruleCode);

    /**
     * 创建激励规则
     *
     * <p>Rule 17.1 anti-pattern fix（Issue #320）：接收 DTO 而非实体直绑。
     *
     * @param request 激励规则创建请求
     * @return 创建结果
     */
    ConfigOperationResult createIncentiveRule(CreateIncentiveRuleRequest request);

    /**
     * 更新激励规则
     *
     * <p>Rule 17.1 anti-pattern fix（Issue #320）：接收 DTO 而非实体直绑，
     * service 层使用 null-aware copy 阻止 {@code @Builder.Default} 静默覆盖
     * （{@code isActive=true}、{@code sortOrder=0}）。
     *
     * <p>不可变字段：{@code ruleCode}、{@code factoryId}（业务约束，DTO 不暴露）。
     *
     * @param id      配置ID
     * @param request 激励规则更新请求
     * @return 更新结果
     */
    ConfigOperationResult updateIncentiveRule(Long id, UpdateIncentiveRuleRequest request);

    /**
     * 删除激励规则（软删除）
     *
     * @param id 配置ID
     * @return 删除结果
     */
    ConfigOperationResult deleteIncentiveRule(Long id);

    /**
     * 重载激励规则缓存
     *
     * @return 重载结果
     */
    ConfigOperationResult reloadIncentiveRules();

    // ==================== 字段映射 ====================

    /**
     * 获取字段映射列表
     *
     * @param dictType 字典类型（可选，null 表示获取所有）
     * @return 字段映射列表
     */
    List<SmartBiDictionary> listFieldMappings(String dictType);

    /**
     * 创建字段映射
     *
     * @param mapping 字段映射
     * @return 创建结果
     */
    ConfigOperationResult createFieldMapping(SmartBiDictionary mapping);

    /**
     * 更新字段映射
     *
     * @param id      配置ID
     * @param mapping 字段映射
     * @return 更新结果
     */
    ConfigOperationResult updateFieldMapping(Long id, SmartBiDictionary mapping);

    /**
     * 删除字段映射（软删除）
     *
     * @param id 配置ID
     * @return 删除结果
     */
    ConfigOperationResult deleteFieldMapping(Long id);

    /**
     * 重载字段映射缓存
     *
     * @return 重载结果
     */
    ConfigOperationResult reloadFieldMappings();

    // ==================== 指标公式 ====================

    /**
     * 获取指标公式列表
     *
     * @param formulaType 公式类型（可选，null 表示获取所有）
     * @return 指标公式列表
     */
    List<SmartBiMetricFormula> listMetricFormulas(String formulaType);

    /**
     * 创建指标公式
     *
     * @param formula 指标公式
     * @return 创建结果
     */
    ConfigOperationResult createMetricFormula(SmartBiMetricFormula formula);

    /**
     * 更新指标公式
     *
     * @param id      配置ID
     * @param formula 指标公式
     * @return 更新结果
     */
    ConfigOperationResult updateMetricFormula(Long id, SmartBiMetricFormula formula);

    /**
     * 删除指标公式（软删除）
     *
     * @param id 配置ID
     * @return 删除结果
     */
    ConfigOperationResult deleteMetricFormula(Long id);

    /**
     * 重载指标公式缓存
     *
     * @return 重载结果
     */
    ConfigOperationResult reloadMetricFormulas();

    // ==================== 图表模板 ====================

    /**
     * 获取图表模板列表
     *
     * @param category  模板分类（可选，null 表示获取所有）
     * @param chartType 图表类型（可选，null 表示获取所有）
     * @return 图表模板列表
     */
    List<SmartBiChartTemplate> listChartTemplates(String category, String chartType);

    /**
     * 创建图表模板
     *
     * @param template 图表模板
     * @return 创建结果
     */
    ConfigOperationResult createChartTemplate(SmartBiChartTemplate template);

    /**
     * 更新图表模板
     *
     * @param id       配置ID
     * @param template 图表模板
     * @return 更新结果
     */
    ConfigOperationResult updateChartTemplate(Long id, SmartBiChartTemplate template);

    /**
     * 删除图表模板（软删除）
     *
     * @param id 配置ID
     * @return 删除结果
     */
    ConfigOperationResult deleteChartTemplate(Long id);

    /**
     * 重载图表模板缓存
     *
     * @return 重载结果
     */
    ConfigOperationResult reloadChartTemplates();

    /**
     * 获取指定图表模板
     *
     * @param templateCode 模板代码
     * @param factoryId    工厂ID（可选，用于获取工厂级覆盖配置）
     * @return 图表模板，如果不存在返回 null
     */
    SmartBiChartTemplate getChartTemplate(String templateCode, String factoryId);

    /**
     * 获取指定指标适用的图表模板列表
     *
     * @param metricCode 指标代码
     * @return 适用的图表模板列表
     */
    List<SmartBiChartTemplate> getChartTemplatesForMetric(String metricCode);

    /**
     * 根据数据特征推荐图表类型
     *
     * @param metricCode       指标代码
     * @param dataPoints       数据点数量
     * @param hasTimeDimension 是否包含时间维度
     * @return 推荐的图表类型
     */
    String recommendChartType(String metricCode, int dataPoints, boolean hasTimeDimension);

    /**
     * 根据模板和数据构建图表配置，并生成AI分析文本
     *
     * @param templateCode 模板代码
     * @param data         图表数据
     * @param factoryId    工厂ID（可选）
     * @return 包含图表配置和AI分析文本的 Map
     */
    Map<String, Object> buildChartWithAnalysis(String templateCode, Map<String, Object> data, String factoryId);

    // ==================== 全局操作 ====================

    /**
     * 重载所有配置缓存
     *
     * @return 重载结果
     */
    ConfigOperationResult reloadAll();

    /**
     * 获取配置状态摘要
     *
     * @return 配置状态信息（各类型配置数量、最后更新时间等）
     */
    Map<String, Object> getConfigStatus();
}
