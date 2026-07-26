package com.cretas.aims.service.impl;

import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.config.ApprovalChainConfigRepository;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.workflow.DecisionTypeMetadata;
import com.cretas.aims.service.workflow.DecisionTypeMetadataRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 审批链路配置服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-30
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalChainServiceImpl implements ApprovalChainService {

    private final ApprovalChainConfigRepository approvalChainConfigRepository;
    private final ObjectMapper objectMapper;

    /** Canvas is the sole runtime source; legacy rows are migration evidence only. */
    private final @Lazy ApprovalWorkflowService approvalWorkflowService;
    private final DecisionTypeMetadataRegistry decisionTypeMetadataRegistry;

    // ==================== 配置管理 ====================

    @Override
    @Transactional
    public ApprovalChainConfig createConfig(String factoryId, ApprovalChainConfig config) {
        log.info("创建审批链配置 - factoryId={}, decisionType={}, name={}",
                factoryId, config.getDecisionType(), config.getName());

        // 设置工厂ID
        config.setFactoryId(factoryId);

        // 检查名称是否重复
        if (approvalChainConfigRepository.existsByFactoryIdAndDecisionTypeAndName(
                factoryId, config.getDecisionType(), config.getName())) {
            throw new BusinessException(409, "同类型下配置名称已存在: " + config.getName())
                    .withHint("请使用其他名称, 或编辑现有配置")
                    .withHintTarget("name");
        }

        // 验证配置
        Map<String, Object> validation = validateConfig(config);
        if (!(Boolean) validation.get("isValid")) {
            throw new BusinessException(400, "配置验证失败: " + validation.get("errors"))
                    .withHint("请检查 approverRoles 是否为 JSON 数组, 以及必填字段")
                    .withHintTarget("approverRoles");
        }

        // 设置默认值
        if (config.getEnabled() == null) {
            config.setEnabled(true);
        }
        if (config.getVersion() == null) {
            config.setVersion(1);
        }
        if (config.getPriority() == null) {
            config.setPriority(0);
        }
        if (config.getRequiredApprovers() == null) {
            config.setRequiredApprovers(1);
        }

        ApprovalChainConfig saved = approvalChainConfigRepository.save(config);
        log.info("审批链配置创建成功 - id={}", saved.getId());

        return saved;
    }

    @Override
    @Transactional
    public ApprovalChainConfig updateConfig(String factoryId, String configId, ApprovalChainConfig config) {
        log.info("更新审批链配置 - factoryId={}, configId={}", factoryId, configId);

        ApprovalChainConfig existing = approvalChainConfigRepository.findById(configId)
                .orElseThrow(() -> new EntityNotFoundException("Approval chain config", configId));

        // 验证工厂ID
        if (!existing.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权修改其他工厂的配置")
                    .withHint("请切换到该配置所属的工厂后再操作");
        }

        // 验证配置
        Map<String, Object> validation = validateConfig(config);
        if (!(Boolean) validation.get("isValid")) {
            throw new BusinessException(400, "配置验证失败: " + validation.get("errors"))
                    .withHint("请检查 approverRoles 是否为 JSON 数组, 以及必填字段")
                    .withHintTarget("approverRoles");
        }

        // 更新字段
        if (StringUtils.hasText(config.getName())) {
            // 检查名称是否与其他配置冲突
            if (!existing.getName().equals(config.getName()) &&
                    approvalChainConfigRepository.existsByFactoryIdAndDecisionTypeAndName(
                            factoryId, existing.getDecisionType(), config.getName())) {
                throw new BusinessException(409, "同类型下配置名称已存在: " + config.getName())
                        .withHint("请使用其他名称")
                        .withHintTarget("name");
            }
            existing.setName(config.getName());
        }
        if (config.getDescription() != null) {
            existing.setDescription(config.getDescription());
        }
        if (config.getTriggerCondition() != null) {
            existing.setTriggerCondition(config.getTriggerCondition());
        }
        if (config.getApprovalLevel() != null) {
            existing.setApprovalLevel(config.getApprovalLevel());
        }
        if (config.getRequiredApprovers() != null) {
            existing.setRequiredApprovers(config.getRequiredApprovers());
        }
        if (config.getApproverRoles() != null) {
            existing.setApproverRoles(config.getApproverRoles());
        }
        if (config.getApproverUserIds() != null) {
            existing.setApproverUserIds(config.getApproverUserIds());
        }
        if (config.getTimeoutMinutes() != null) {
            existing.setTimeoutMinutes(config.getTimeoutMinutes());
        }
        if (config.getEscalationConfigId() != null) {
            existing.setEscalationConfigId(config.getEscalationConfigId());
        }
        if (config.getAutoApproveCondition() != null) {
            existing.setAutoApproveCondition(config.getAutoApproveCondition());
        }
        if (config.getAutoRejectCondition() != null) {
            existing.setAutoRejectCondition(config.getAutoRejectCondition());
        }
        if (config.getPriority() != null) {
            existing.setPriority(config.getPriority());
        }

        // 增加版本号
        existing.setVersion(existing.getVersion() + 1);

        ApprovalChainConfig saved = approvalChainConfigRepository.save(existing);
        log.info("审批链配置更新成功 - id={}, version={}", saved.getId(), saved.getVersion());

        return saved;
    }

    @Override
    @Transactional
    public void deleteConfig(String factoryId, String configId) {
        log.info("删除审批链配置 - factoryId={}, configId={}", factoryId, configId);

        ApprovalChainConfig existing = approvalChainConfigRepository.findById(configId)
                .orElseThrow(() -> new EntityNotFoundException("Approval chain config", configId));

        if (!existing.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权删除其他工厂的配置")
                    .withHint("请切换到该配置所属的工厂后再操作");
        }

        // 软删除 - 禁用配置
        existing.setEnabled(false);
        approvalChainConfigRepository.save(existing);

        log.info("审批链配置已禁用 - id={}", configId);
    }

    @Override
    @Transactional
    public ApprovalChainConfig toggleEnabled(String factoryId, String configId, boolean enabled) {
        log.info("切换审批链配置状态 - factoryId={}, configId={}, enabled={}", factoryId, configId, enabled);

        ApprovalChainConfig existing = approvalChainConfigRepository.findById(configId)
                .orElseThrow(() -> new EntityNotFoundException("Approval chain config", configId));

        if (!existing.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权修改其他工厂的配置")
                    .withHint("请切换到该配置所属的工厂后再操作");
        }

        existing.setEnabled(enabled);
        return approvalChainConfigRepository.save(existing);
    }

    @Override
    public Optional<ApprovalChainConfig> getConfig(String factoryId, String configId) {
        return approvalChainConfigRepository.findById(configId)
                .filter(config -> config.getFactoryId().equals(factoryId));
    }

    @Override
    public List<ApprovalChainConfig> getAllConfigs(String factoryId) {
        return approvalChainConfigRepository.findByFactoryIdOrderByDecisionTypeAscPriorityDesc(factoryId);
    }

    @Override
    public List<ApprovalChainConfig> getConfigsByDecisionType(String factoryId, DecisionType decisionType) {
        return approvalChainConfigRepository.findByFactoryIdAndDecisionTypeAndEnabledTrueOrderByApprovalLevel(
                factoryId, decisionType);
    }

    // ==================== 审批链路处理 ====================

    @Override
    public Optional<ApprovalChainConfig> findMatchingConfig(
            String factoryId,
            DecisionType decisionType,
            Map<String, Object> context) {

        log.debug("查找匹配的审批配置 - factoryId={}, decisionType={}", factoryId, decisionType);

        List<ApprovalChainConfig> configs = approvalChainConfigRepository
                .findByFactoryIdAndEnabledTrueOrderByPriorityDesc(factoryId)
                .stream()
                .filter(c -> c.getDecisionType() == decisionType)
                .collect(Collectors.toList());

        // 按优先级遍历，找到第一个匹配条件的配置
        for (ApprovalChainConfig config : configs) {
            if (matchesTriggerCondition(config, context)) {
                log.debug("找到匹配配置 - id={}, name={}", config.getId(), config.getName());
                return Optional.of(config);
            }
        }

        // 如果没有匹配的，返回第一个无条件的配置
        return configs.stream()
                .filter(c -> !StringUtils.hasText(c.getTriggerCondition()))
                .findFirst();
    }

    @Override
    public Optional<ApprovalChainConfig> getFirstLevelConfig(String factoryId, DecisionType decisionType) {
        List<ApprovalChainConfig> configs = approvalChainConfigRepository.findFirstLevelConfig(factoryId, decisionType);
        return configs.isEmpty() ? Optional.empty() : Optional.of(configs.get(0));
    }

    @Override
    public Optional<ApprovalChainConfig> getNextLevelConfig(ApprovalChainConfig currentConfig) {
        if (currentConfig == null) {
            return Optional.empty();
        }

        Integer nextLevel = currentConfig.getApprovalLevel() + 1;
        return approvalChainConfigRepository.findByFactoryIdAndDecisionTypeAndApprovalLevelAndEnabledTrue(
                currentConfig.getFactoryId(),
                currentConfig.getDecisionType(),
                nextLevel);
    }

    @Override
    public Optional<ApprovalChainConfig> getEscalationConfig(ApprovalChainConfig currentConfig) {
        if (currentConfig == null || !StringUtils.hasText(currentConfig.getEscalationConfigId())) {
            return Optional.empty();
        }

        return approvalChainConfigRepository.findByIdAndEnabledTrue(currentConfig.getEscalationConfigId());
    }

    // ==================== 审批判断 ====================

    @Override
    public boolean canAutoApprove(ApprovalChainConfig config, Map<String, Object> context) {
        if (config == null || !StringUtils.hasText(config.getAutoApproveCondition())) {
            return false;
        }

        return evaluateCondition(config.getAutoApproveCondition(), context);
    }

    @Override
    public boolean canAutoReject(ApprovalChainConfig config, Map<String, Object> context) {
        if (config == null || !StringUtils.hasText(config.getAutoRejectCondition())) {
            return false;
        }

        return evaluateCondition(config.getAutoRejectCondition(), context);
    }

    @Override
    public boolean hasApprovalPermission(ApprovalChainConfig config, Long userId, String userRole) {
        if (config == null) {
            return false;
        }

        // 检查用户ID
        if (StringUtils.hasText(config.getApproverUserIds())) {
            try {
                List<Long> approverIds = objectMapper.readValue(
                        config.getApproverUserIds(),
                        new TypeReference<List<Long>>() {});
                if (approverIds.contains(userId)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("解析审批人ID列表失败 - configId={}", config.getId(), e);
            }
        }

        // 检查角色
        if (StringUtils.hasText(config.getApproverRoles()) && StringUtils.hasText(userRole)) {
            try {
                List<String> approverRoles = objectMapper.readValue(
                        config.getApproverRoles(),
                        new TypeReference<List<String>>() {});
                if (approverRoles.contains(userRole)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("解析审批人角色列表失败 - configId={}", config.getId(), e);
            }
        }

        return false;
    }

    @Override
    public boolean requiresApproval(String factoryId, DecisionType decisionType, Map<String, Object> context) {
        boolean hasGraphWorkflow = approvalWorkflowService
                .getActiveByDecisionType(factoryId, decisionType)
                .isPresent();
        if (hasGraphWorkflow) {
            DecisionTypeMetadata metadata = decisionTypeMetadataRegistry.get(decisionType);
            if (metadata == null || !metadata.isWired()) {
                throw new BusinessException(409, "该审批业务尚未接入画布运行时")
                        .withCode("OA_BUSINESS_NOT_WIRED")
                        .withHint("可先保存画布草稿；待业务接入完成后再发布启用");
            }
            log.debug("使用 Canvas 审批画布 - factoryId={}, decisionType={}",
                    factoryId, decisionType);
            return true;
        }

        if (hasEnabledLegacyConfig(factoryId, decisionType)) {
            throw new BusinessException(409, "旧版审批配置尚未迁移到审批画布")
                    .withCode("OA_LEGACY_CONFIG_MIGRATION_REQUIRED")
                    .withHint("请在系统设置 → 审批业务中发布并启用对应审批画布");
        }

        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasEnabledLegacyConfig(String factoryId, DecisionType decisionType) {
        return !approvalChainConfigRepository
                .findByFactoryIdAndDecisionTypeAndEnabledTrueOrderByApprovalLevel(
                        factoryId, decisionType)
                .isEmpty();
    }

    // ==================== 统计与分析 ====================

    @Override
    public Map<DecisionType, Long> getConfigStatistics(String factoryId) {
        List<Object[]> results = approvalChainConfigRepository.countByFactoryIdGroupByDecisionType(factoryId);

        Map<DecisionType, Long> stats = new EnumMap<>(DecisionType.class);
        for (Object[] row : results) {
            DecisionType type = (DecisionType) row[0];
            Long count = (Long) row[1];
            stats.put(type, count);
        }

        return stats;
    }

    @Override
    public Map<String, Object> validateConfig(ApprovalChainConfig config) {
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();

        // 基础验证
        if (config.getDecisionType() == null) {
            errors.add("决策类型不能为空");
        }
        if (!StringUtils.hasText(config.getName())) {
            errors.add("配置名称不能为空");
        }
        if (config.getApprovalLevel() == null || config.getApprovalLevel() < 1) {
            errors.add("审批级别必须大于0");
        }

        // 审批人验证 (至少配置角色或用户ID)
        if (!StringUtils.hasText(config.getApproverRoles()) &&
                !StringUtils.hasText(config.getApproverUserIds())) {
            errors.add("必须配置审批人角色或用户ID");
        }

        // JSON格式验证
        if (StringUtils.hasText(config.getTriggerCondition())) {
            if (!isValidJson(config.getTriggerCondition())) {
                errors.add("触发条件JSON格式无效");
            }
        }
        if (StringUtils.hasText(config.getApproverRoles())) {
            if (!isValidJson(config.getApproverRoles())) {
                errors.add("审批人角色JSON格式无效");
            }
        }
        if (StringUtils.hasText(config.getApproverUserIds())) {
            if (!isValidJson(config.getApproverUserIds())) {
                errors.add("审批人ID JSON格式无效");
            }
        }
        if (StringUtils.hasText(config.getAutoApproveCondition())) {
            if (!isValidJson(config.getAutoApproveCondition())) {
                errors.add("自动审批条件JSON格式无效");
            }
        }
        if (StringUtils.hasText(config.getAutoRejectCondition())) {
            if (!isValidJson(config.getAutoRejectCondition())) {
                errors.add("自动拒绝条件JSON格式无效");
            }
        }

        // 循环引用检测
        if (StringUtils.hasText(config.getEscalationConfigId())) {
            if (config.getId() != null && config.getId().equals(config.getEscalationConfigId())) {
                errors.add("升级配置不能指向自身");
            }
        }

        result.put("isValid", errors.isEmpty());
        result.put("errors", errors);

        return result;
    }

    // ==================== 私有方法 ====================

    /**
     * 检查是否匹配触发条件
     */
    private boolean matchesTriggerCondition(ApprovalChainConfig config, Map<String, Object> context) {
        if (!StringUtils.hasText(config.getTriggerCondition())) {
            return true; // 无条件，默认匹配
        }

        return evaluateCondition(config.getTriggerCondition(), context);
    }

    /**
     * 评估JSON条件表达式
     * 支持简单的键值匹配和比较操作
     *
     * 条件格式示例:
     * {"impactLevel": "HIGH"} - 精确匹配
     * {"delayMinutes": ">60"} - 大于比较
     * {"quantity": ">=100"} - 大于等于比较
     */
    private boolean evaluateCondition(String conditionJson, Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return false;
        }

        try {
            Map<String, Object> conditions = objectMapper.readValue(
                    conditionJson,
                    new TypeReference<Map<String, Object>>() {});

            for (Map.Entry<String, Object> entry : conditions.entrySet()) {
                String key = entry.getKey();
                Object expectedValue = entry.getValue();
                Object actualValue = context.get(key);

                if (actualValue == null) {
                    return false;
                }

                // 处理比较操作符
                if (expectedValue instanceof String) {
                    String expectedStr = (String) expectedValue;

                    if (expectedStr.startsWith(">=")) {
                        double expected = Double.parseDouble(expectedStr.substring(2));
                        double actual = toDouble(actualValue);
                        if (actual < expected) return false;
                    } else if (expectedStr.startsWith("<=")) {
                        double expected = Double.parseDouble(expectedStr.substring(2));
                        double actual = toDouble(actualValue);
                        if (actual > expected) return false;
                    } else if (expectedStr.startsWith(">")) {
                        double expected = Double.parseDouble(expectedStr.substring(1));
                        double actual = toDouble(actualValue);
                        if (actual <= expected) return false;
                    } else if (expectedStr.startsWith("<")) {
                        double expected = Double.parseDouble(expectedStr.substring(1));
                        double actual = toDouble(actualValue);
                        if (actual >= expected) return false;
                    } else if (expectedStr.startsWith("!=")) {
                        String expected = expectedStr.substring(2);
                        if (expected.equals(String.valueOf(actualValue))) return false;
                    } else {
                        // 精确匹配
                        if (!expectedStr.equals(String.valueOf(actualValue))) return false;
                    }
                } else {
                    // 非字符串的精确匹配
                    if (!expectedValue.equals(actualValue)) return false;
                }
            }

            return true;

        } catch (Exception e) {
            log.warn("评估条件表达式失败 - condition={}", conditionJson, e);
            return false;
        }
    }

    /**
     * 转换为double
     */
    private double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    /**
     * 验证JSON格式
     */
    private boolean isValidJson(String json) {
        try {
            objectMapper.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
