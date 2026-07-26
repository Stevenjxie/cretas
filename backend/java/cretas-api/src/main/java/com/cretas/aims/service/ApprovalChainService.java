package com.cretas.aims.service;

import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审批链路配置服务接口
 *
 * 提供:
 * - 审批链配置的CRUD
 * - 根据决策类型获取审批链
 * - 条件匹配与自动审批判断
 * - 升级链路处理
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-30
 */
public interface ApprovalChainService {

    // ==================== 配置管理 ====================

    /**
     * 创建审批链配置
     *
     * @param factoryId 工厂ID
     * @param config 配置对象
     * @return 创建的配置
     */
    ApprovalChainConfig createConfig(String factoryId, ApprovalChainConfig config);

    /**
     * 更新审批链配置
     *
     * @param factoryId 工厂ID
     * @param configId 配置ID
     * @param config 更新内容
     * @return 更新后的配置
     */
    ApprovalChainConfig updateConfig(String factoryId, String configId, ApprovalChainConfig config);

    /**
     * 删除审批链配置 (软删除)
     *
     * @param factoryId 工厂ID
     * @param configId 配置ID
     */
    void deleteConfig(String factoryId, String configId);

    /**
     * 启用/禁用配置
     *
     * @param factoryId 工厂ID
     * @param configId 配置ID
     * @param enabled 是否启用
     * @return 更新后的配置
     */
    ApprovalChainConfig toggleEnabled(String factoryId, String configId, boolean enabled);

    /**
     * 获取配置详情
     *
     * @param factoryId 工厂ID
     * @param configId 配置ID
     * @return 配置对象
     */
    Optional<ApprovalChainConfig> getConfig(String factoryId, String configId);

    /**
     * 获取工厂所有配置
     *
     * @param factoryId 工厂ID
     * @return 配置列表
     */
    List<ApprovalChainConfig> getAllConfigs(String factoryId);

    /**
     * 根据决策类型获取配置列表
     *
     * @param factoryId 工厂ID
     * @param decisionType 决策类型
     * @return 配置列表 (按审批级别排序)
     */
    List<ApprovalChainConfig> getConfigsByDecisionType(String factoryId, DecisionType decisionType);

    // ==================== 审批链路处理 ====================

    /**
     * 获取匹配的审批配置
     * 根据决策类型和上下文条件匹配最合适的配置
     *
     * @param factoryId 工厂ID
     * @param decisionType 决策类型
     * @param context 上下文数据 (用于条件匹配)
     * @return 匹配的配置 (可能为空)
     */
    Optional<ApprovalChainConfig> findMatchingConfig(
            String factoryId,
            DecisionType decisionType,
            Map<String, Object> context);

    /**
     * 获取第一级审批配置
     *
     * @param factoryId 工厂ID
     * @param decisionType 决策类型
     * @return 第一级审批配置
     */
    Optional<ApprovalChainConfig> getFirstLevelConfig(String factoryId, DecisionType decisionType);

    /**
     * 获取下一级审批配置
     *
     * @param currentConfig 当前配置
     * @return 下一级配置 (如果存在)
     */
    Optional<ApprovalChainConfig> getNextLevelConfig(ApprovalChainConfig currentConfig);

    /**
     * 获取升级配置
     * 当审批超时时，获取升级目标配置
     *
     * @param currentConfig 当前配置
     * @return 升级目标配置
     */
    Optional<ApprovalChainConfig> getEscalationConfig(ApprovalChainConfig currentConfig);

    // ==================== 审批判断 ====================

    /**
     * 判断是否可以自动审批通过
     *
     * @param config 配置
     * @param context 上下文数据
     * @return 是否可自动通过
     */
    boolean canAutoApprove(ApprovalChainConfig config, Map<String, Object> context);

    /**
     * 判断是否可以自动拒绝
     *
     * @param config 配置
     * @param context 上下文数据
     * @return 是否可自动拒绝
     */
    boolean canAutoReject(ApprovalChainConfig config, Map<String, Object> context);

    /**
     * 判断用户是否有审批权限
     *
     * @param config 配置
     * @param userId 用户ID
     * @param userRole 用户角色
     * @return 是否有权限
     */
    boolean hasApprovalPermission(ApprovalChainConfig config, Long userId, String userRole);

    /**
     * 检查是否需要审批
     * 根据决策类型和上下文判断是否需要审批流程
     *
     * @param factoryId 工厂ID
     * @param decisionType 决策类型
     * @param context 上下文数据
     * @return 是否需要审批
     */
    boolean requiresApproval(String factoryId, DecisionType decisionType, Map<String, Object> context);

    /** Returns true when an enabled legacy flat-chain row still needs migration. */
    boolean hasEnabledLegacyConfig(String factoryId, DecisionType decisionType);

    // ==================== 统计与分析 ====================

    /**
     * 获取配置统计信息
     *
     * @param factoryId 工厂ID
     * @return 统计信息 (decisionType -> count)
     */
    Map<DecisionType, Long> getConfigStatistics(String factoryId);

    /**
     * 验证配置有效性
     * 检查配置是否存在循环引用、无效的升级链路等
     *
     * @param config 配置
     * @return 验证结果 (isValid, errors)
     */
    Map<String, Object> validateConfig(ApprovalChainConfig config);
}
