package com.cretas.aims.service.impl;

import com.cretas.aims.entity.rules.StateMachine;
import com.cretas.aims.service.workflow.WorkflowLearningService;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.enums.QualityStatus;
import com.cretas.aims.repository.StateMachineRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.service.DecisionAuditService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.StateMachineService;
import com.cretas.aims.service.QualityDispositionRuleService;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 状态机服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StateMachineServiceImpl implements StateMachineService {

    private final StateMachineRepository stateMachineRepository;
    private final RuleEngineService ruleEngineService;
    private final DecisionAuditService decisionAuditService;
    private final ObjectMapper objectMapper;
    private final QualityDispositionRuleService qualityDispositionRuleService;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final WorkflowLearningService workflowLearningService;

    // 静态引用用于SpEL函数调用
    private static QualityDispositionRuleService staticQualityService;
    private static QualityInspectionRepository staticInspectionRepo;

    @jakarta.annotation.PostConstruct
    public void init() {
        staticQualityService = this.qualityDispositionRuleService;
        staticInspectionRepo = this.qualityInspectionRepository;
    }

    // SpEL 表达式解析器
    private final ExpressionParser spelParser = new SpelExpressionParser();

    // ==================== 状态机配置管理 ====================

    @Override
    public Optional<StateMachineConfig> getStateMachine(String factoryId, String entityType) {
        return stateMachineRepository.findByFactoryIdAndEntityTypeAndEnabledTrue(factoryId, entityType)
                .map(this::convertToConfig);
    }

    @Override
    @Transactional
    public StateMachineConfig saveStateMachine(String factoryId, String entityType,
                                               StateMachineConfig config, Long userId) {
        log.info("保存状态机配置 - factoryId={}, entityType={}", factoryId, entityType);

        StateMachine entity = stateMachineRepository.findByFactoryIdAndEntityType(factoryId, entityType)
                .orElse(new StateMachine());

        // 设置基本信息
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
            entity.setVersion(1);
            entity.setCreatedBy(userId);
        } else {
            entity.setVersion(entity.getVersion() + 1);
        }

        entity.setFactoryId(factoryId);
        entity.setEntityType(entityType);
        entity.setMachineName(config.getMachineName());
        entity.setMachineDescription(config.getMachineDescription());
        entity.setInitialState(config.getInitialState());
        entity.setEnabled(config.getEnabled() != null ? config.getEnabled() : true);

        // 序列化状态和转换
        try {
            entity.setStatesJson(objectMapper.writeValueAsString(config.getStates()));
            entity.setTransitionsJson(objectMapper.writeValueAsString(config.getTransitions()));
        } catch (JsonProcessingException e) {
            log.error("序列化状态机配置失败", e);
            throw new RuntimeException("状态机配置序列化失败: " + e.getMessage(), e);
        }

        StateMachine saved = stateMachineRepository.save(entity);
        log.info("状态机配置保存成功 - id={}, version={}", saved.getId(), saved.getVersion());

        return convertToConfig(saved);
    }

    @Override
    @Transactional
    public void deleteStateMachine(String factoryId, String entityType) {
        log.info("删除状态机配置 - factoryId={}, entityType={}", factoryId, entityType);
        stateMachineRepository.findByFactoryIdAndEntityType(factoryId, entityType)
                .ifPresent(sm -> {
                    sm.softDelete();
                    stateMachineRepository.save(sm);
                });
    }

    @Override
    public List<StateMachineConfig> getAllStateMachines(String factoryId) {
        return stateMachineRepository.findByFactoryIdAndEnabledTrue(factoryId).stream()
                .map(this::convertToConfig)
                .collect(Collectors.toList());
    }

    // ==================== 版本管理 ====================

    @Override
    @Transactional
    public StateMachineConfig publishDraft(String factoryId, String entityType, String draftId, Long userId) {
        log.info("发布状态机草稿 - factoryId={}, entityType={}, draftId={}", factoryId, entityType, draftId);

        StateMachine draft = stateMachineRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("草稿不存在: " + draftId));

        if (!"draft".equals(draft.getPublishStatus())) {
            throw new IllegalStateException("只能发布草稿状态的配置，当前状态: " + draft.getPublishStatus());
        }

        // 1. Archive current published version
        stateMachineRepository.findByFactoryIdAndEntityTypeAndPublishStatus(factoryId, entityType, "published")
                .ifPresent(published -> {
                    published.setPublishStatus("archived");
                    stateMachineRepository.save(published);
                    log.info("已归档旧版本 - version={}", published.getVersion());
                });

        // 2. Publish the draft with incremented version
        Integer maxVersion = stateMachineRepository.findMaxVersion(factoryId, entityType);
        draft.setPublishStatus("published");
        draft.setVersion(maxVersion + 1);
        draft.setEnabled(true);

        StateMachine saved = stateMachineRepository.save(draft);
        log.info("状态机已发布 - version={}", saved.getVersion());

        // Post-publish: analyze and index for AI learning (non-blocking)
        try {
            workflowLearningService.analyzeAndIndex(saved);
        } catch (Exception e) {
            log.warn("工作流学习分析失败（不影响发布）", e);
        }

        return convertToConfig(saved);
    }

    @Override
    public Optional<StateMachineConfig> getPublishedStateMachine(String factoryId, String entityType) {
        return stateMachineRepository.findByFactoryIdAndEntityTypeAndPublishStatus(factoryId, entityType, "published")
                .map(this::convertToConfig);
    }

    @Override
    public List<StateMachineConfig> getVersionHistory(String factoryId, String entityType) {
        return stateMachineRepository.findByFactoryIdAndEntityTypeOrderByVersionDesc(factoryId, entityType)
                .stream()
                .map(this::convertToConfig)
                .collect(Collectors.toList());
    }

    // ==================== 状态转换 ====================

    @Override
    public List<TransitionInfo> getAvailableTransitions(String factoryId, String entityType,
                                                         String currentState, Object entity) {
        Optional<StateMachineConfig> configOpt = getStateMachine(factoryId, entityType);
        if (configOpt.isEmpty()) {
            log.warn("未找到状态机配置 - factoryId={}, entityType={}", factoryId, entityType);
            return Collections.emptyList();
        }

        StateMachineConfig config = configOpt.get();
        List<TransitionInfo> availableTransitions = new ArrayList<>();

        // 找出所有从当前状态出发的转换
        for (TransitionDef transition : config.getTransitions()) {
            if (transition.getFrom().equals(currentState)) {
                TransitionInfo info = new TransitionInfo();
                info.setFrom(transition.getFrom());
                info.setTo(transition.getTo());
                info.setEvent(transition.getEvent());
                info.setDescription(transition.getDescription());

                // 查找目标状态名称
                config.getStates().stream()
                        .filter(s -> s.getCode().equals(transition.getTo()))
                        .findFirst()
                        .ifPresent(s -> info.setToName(s.getName()));

                // 评估守卫条件
                if (transition.getGuard() != null && !transition.getGuard().isEmpty()) {
                    try {
                        Boolean guardResult = evaluateGuard(factoryId, transition.getGuard(), entity);
                        info.setGuardPassed(guardResult);
                        if (!guardResult) {
                            info.setGuardFailReason("守卫条件不满足: " + transition.getGuard());
                        }
                    } catch (Exception e) {
                        log.warn("守卫条件评估失败: {}", e.getMessage());
                        info.setGuardPassed(false);
                        info.setGuardFailReason("守卫条件评估失败: " + e.getMessage());
                    }
                } else {
                    info.setGuardPassed(true);
                }

                availableTransitions.add(info);
            }
        }

        return availableTransitions;
    }

    @Override
    public TransitionValidation validateTransition(String factoryId, String entityType,
                                                   String currentState, String targetState, Object entity) {
        Optional<StateMachineConfig> configOpt = getStateMachine(factoryId, entityType);
        if (configOpt.isEmpty()) {
            return new TransitionValidation(false, "未找到状态机配置");
        }

        StateMachineConfig config = configOpt.get();

        // 查找转换定义
        Optional<TransitionDef> transitionOpt = config.getTransitions().stream()
                .filter(t -> t.getFrom().equals(currentState) && t.getTo().equals(targetState))
                .findFirst();

        if (transitionOpt.isEmpty()) {
            return new TransitionValidation(false,
                    "不允许从 " + currentState + " 转换到 " + targetState);
        }

        TransitionDef transition = transitionOpt.get();

        // 评估守卫条件
        if (transition.getGuard() != null && !transition.getGuard().isEmpty()) {
            try {
                Boolean guardResult = evaluateGuard(factoryId, transition.getGuard(), entity);
                if (!guardResult) {
                    return new TransitionValidation(false, "守卫条件不满足: " + transition.getGuard());
                }
            } catch (Exception e) {
                return new TransitionValidation(false, "守卫条件评估失败: " + e.getMessage());
            }
        }

        return new TransitionValidation(true, "验证通过");
    }

    @Override
    @Transactional
    public TransitionResult executeTransition(String factoryId, String entityType,
                                              String entityId, String currentState, String targetState,
                                              Object entity, Long userId) {
        log.info("执行状态转换 - entityType={}, entityId={}, {} -> {}",
                entityType, entityId, currentState, targetState);

        // 先验证
        TransitionValidation validation = validateTransition(factoryId, entityType,
                currentState, targetState, entity);
        if (!validation.getIsValid()) {
            return new TransitionResult(false, currentState, currentState, validation.getMessage());
        }

        // 获取转换定义
        StateMachineConfig config = getStateMachine(factoryId, entityType).orElse(null);
        if (config == null) {
            return new TransitionResult(false, currentState, currentState, "状态机配置不存在");
        }

        Optional<TransitionDef> transitionOpt = config.getTransitions().stream()
                .filter(t -> t.getFrom().equals(currentState) && t.getTo().equals(targetState))
                .findFirst();

        if (transitionOpt.isEmpty()) {
            return new TransitionResult(false, currentState, currentState, "转换定义不存在");
        }

        TransitionDef transition = transitionOpt.get();

        // 执行动作 (如果有)
        Map<String, Object> actionResults = new HashMap<>();
        if (transition.getAction() != null && !transition.getAction().isEmpty()) {
            try {
                Object result = executeAction(factoryId, transition.getAction(), entity);
                actionResults.put(transition.getAction(), result);
            } catch (Exception e) {
                log.error("执行动作失败 - action={}", transition.getAction(), e);
                // 动作失败不阻止状态转换，但记录错误
                actionResults.put(transition.getAction() + "_error", e.getMessage());
            }
        }

        // 触发 onTransition 规则
        try {
            ruleEngineService.executeRules(factoryId, "onTransition:" + entityType, entity);
        } catch (Exception e) {
            log.warn("触发 onTransition 规则失败", e);
        }

        // 记录状态转换审计日志
        try {
            String reason = transition.getDescription() != null
                    ? transition.getDescription()
                    : "状态转换: " + transition.getEvent();
            decisionAuditService.logStateTransition(
                    factoryId,
                    entityType,
                    entityId,
                    currentState,
                    targetState,
                    reason,
                    userId,
                    null,  // executorName - 可后续从用户服务获取
                    null   // executorRole - 可后续从用户服务获取
            );
            log.debug("状态转换审计日志已记录 - entityId={}", entityId);
        } catch (Exception e) {
            log.warn("记录状态转换审计日志失败 - entityId={}", entityId, e);
            // 审计失败不影响状态转换
        }

        TransitionResult result = new TransitionResult(true, currentState, targetState, "状态转换成功");
        result.setActionResults(actionResults);

        log.info("状态转换完成 - entityId={}, {} -> {}", entityId, currentState, targetState);
        return result;
    }

    @Override
    @Transactional
    public TransitionResult executeTransitionByEvent(String factoryId, String entityType,
                                                     String entityId, String currentState, String eventName,
                                                     Object entity, Long userId) {
        log.info("通过事件执行状态转换 - entityType={}, entityId={}, currentState={}, event={}",
                entityType, entityId, currentState, eventName);

        Optional<StateMachineConfig> configOpt = getStateMachine(factoryId, entityType);
        if (configOpt.isEmpty()) {
            return new TransitionResult(false, currentState, currentState, "状态机配置不存在");
        }

        // 查找匹配的转换
        Optional<TransitionDef> transitionOpt = configOpt.get().getTransitions().stream()
                .filter(t -> t.getFrom().equals(currentState) && eventName.equals(t.getEvent()))
                .findFirst();

        if (transitionOpt.isEmpty()) {
            return new TransitionResult(false, currentState, currentState,
                    "当前状态 " + currentState + " 不支持事件 " + eventName);
        }

        return executeTransition(factoryId, entityType, entityId, currentState,
                transitionOpt.get().getTo(), entity, userId);
    }

    // ==================== 状态查询 ====================

    @Override
    public String getInitialState(String factoryId, String entityType) {
        return getStateMachine(factoryId, entityType)
                .map(StateMachineConfig::getInitialState)
                .orElse(null);
    }

    @Override
    public List<StateInfo> getAllStates(String factoryId, String entityType) {
        return getStateMachine(factoryId, entityType)
                .map(StateMachineConfig::getStates)
                .orElse(Collections.emptyList());
    }

    @Override
    public boolean isFinalState(String factoryId, String entityType, String state) {
        return getStateMachine(factoryId, entityType)
                .map(config -> config.getStates().stream()
                        .anyMatch(s -> s.getCode().equals(state) && Boolean.TRUE.equals(s.getIsFinal())))
                .orElse(false);
    }

    // ==================== 私有方法 ====================

    /**
     * 将数据库实体转换为配置对象
     */
    private StateMachineConfig convertToConfig(StateMachine entity) {
        StateMachineConfig config = new StateMachineConfig();
        config.setId(entity.getId());
        config.setFactoryId(entity.getFactoryId());
        config.setEntityType(entity.getEntityType());
        config.setMachineName(entity.getMachineName());
        config.setMachineDescription(entity.getMachineDescription());
        config.setInitialState(entity.getInitialState());
        config.setVersion(entity.getVersion());
        config.setEnabled(entity.getEnabled());

        // 反序列化状态和转换
        try {
            if (entity.getStatesJson() != null) {
                config.setStates(objectMapper.readValue(entity.getStatesJson(),
                        new TypeReference<List<StateInfo>>() {}));
            } else {
                config.setStates(Collections.emptyList());
            }

            if (entity.getTransitionsJson() != null) {
                config.setTransitions(objectMapper.readValue(entity.getTransitionsJson(),
                        new TypeReference<List<TransitionDef>>() {}));
            } else {
                config.setTransitions(Collections.emptyList());
            }
        } catch (JsonProcessingException e) {
            log.error("反序列化状态机配置失败", e);
            config.setStates(Collections.emptyList());
            config.setTransitions(Collections.emptyList());
        }

        return config;
    }

    /**
     * 评估守卫条件
     * 使用 Spring Expression Language (SpEL) 评估表达式
     *
     * 支持的表达式格式:
     * - 属性访问: status == 'APPROVED'
     * - 比较运算: quantity > 0
     * - 逻辑运算: status == 'PENDING' && quantity > 0
     * - 方法调用: #hasPermission('APPROVE')
     * - 三元运算: priority > 5 ? true : false
     *
     * 内置变量:
     * - #root: 实体对象
     * - #factoryId: 工厂ID
     * - #hasPermission(role): 权限检查函数
     * - #now: 当前时间
     */
    private Boolean evaluateGuard(String factoryId, String guardExpression, Object entity) {
        if (guardExpression == null || guardExpression.trim().isEmpty()) {
            return true;
        }

        log.debug("评估守卫条件: {} (实体类型: {})",
                guardExpression, entity != null ? entity.getClass().getSimpleName() : "null");

        try {
            // 创建评估上下文
            StandardEvaluationContext context = new StandardEvaluationContext(entity);

            // 设置内置变量
            context.setVariable("factoryId", factoryId);
            context.setVariable("now", java.time.LocalDateTime.now());

            // 注册自定义函数
            registerGuardFunctions(context, factoryId);

            // 如果实体是 Map 类型，也可以直接访问属性
            if (entity instanceof Map) {
                Map<?, ?> entityMap = (Map<?, ?>) entity;
                for (Map.Entry<?, ?> entry : entityMap.entrySet()) {
                    context.setVariable(String.valueOf(entry.getKey()), entry.getValue());
                }
            }

            // 解析并评估表达式
            Expression expression = spelParser.parseExpression(guardExpression);
            Boolean result = expression.getValue(context, Boolean.class);

            log.debug("守卫条件评估结果: {} = {}", guardExpression, result);
            return result != null ? result : false;

        } catch (Exception e) {
            log.warn("守卫条件评估失败: {} - 错误: {}", guardExpression, e.getMessage());
            // 评估失败时返回 false，阻止状态转换
            throw new RuntimeException("守卫条件评估失败: " + e.getMessage(), e);
        }
    }

    /**
     * 注册守卫条件可用的自定义函数
     */
    private void registerGuardFunctions(StandardEvaluationContext context, String factoryId) {
        try {
            // 注册 hasPermission 函数 - 检查角色权限
            context.registerFunction("hasPermission",
                    StateMachineServiceImpl.class.getDeclaredMethod("hasPermission", String.class, String.class));

            // 注册 isBusinessHours 函数 - 检查是否在工作时间
            context.registerFunction("isBusinessHours",
                    StateMachineServiceImpl.class.getDeclaredMethod("isBusinessHours"));

            // 注册 daysBetween 函数 - 计算日期差
            context.registerFunction("daysBetween",
                    StateMachineServiceImpl.class.getDeclaredMethod("daysBetween",
                            java.time.LocalDateTime.class, java.time.LocalDateTime.class));

            // ==================== 质检处置守卫函数 ====================

            // 注册 isQualityPassed 函数 - 检查质量状态是否通过
            context.registerFunction("isQualityPassed",
                    StateMachineServiceImpl.class.getDeclaredMethod("isQualityPassed", String.class));

            // 注册 canReleaseWithQuality 函数 - 检查质检是否允许放行
            context.registerFunction("canReleaseWithQuality",
                    StateMachineServiceImpl.class.getDeclaredMethod("canReleaseWithQuality",
                            String.class, Long.class));

            // 注册 requiresQualityApproval 函数 - 检查质检是否需要审批
            context.registerFunction("requiresQualityApproval",
                    StateMachineServiceImpl.class.getDeclaredMethod("requiresQualityApproval",
                            String.class, Long.class));

            // 注册 getQualityDisposition 函数 - 获取推荐的质检处置动作
            context.registerFunction("getQualityDisposition",
                    StateMachineServiceImpl.class.getDeclaredMethod("getQualityDisposition",
                            String.class, Long.class));

        } catch (NoSuchMethodException e) {
            log.warn("注册守卫函数失败: {}", e.getMessage());
        }
    }

    // ==================== 守卫条件辅助函数 ====================

    /**
     * 检查权限 (供 SpEL 调用)
     */
    public static boolean hasPermission(String factoryId, String requiredRole) {
        // 简化实现：这里可以集成实际的权限检查逻辑
        // 例如从 SecurityContext 获取当前用户角色
        return true;
    }

    /**
     * 检查是否在工作时间 (08:00-18:00)
     */
    public static boolean isBusinessHours() {
        java.time.LocalTime now = java.time.LocalTime.now();
        java.time.LocalTime start = java.time.LocalTime.of(8, 0);
        java.time.LocalTime end = java.time.LocalTime.of(18, 0);
        return !now.isBefore(start) && !now.isAfter(end);
    }

    /**
     * 计算两个日期之间的天数
     */
    public static long daysBetween(java.time.LocalDateTime start, java.time.LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate());
    }

    // ==================== 质检处置守卫函数 ====================

    /**
     * 检查质量状态是否通过 (供 SpEL 调用)
     *
     * 用法: #isQualityPassed(qualityStatus)
     *
     * @param qualityStatus 质量状态字符串
     * @return 是否通过
     */
    public static boolean isQualityPassed(String qualityStatus) {
        if (qualityStatus == null) {
            return false;
        }
        try {
            QualityStatus status = QualityStatus.valueOf(qualityStatus);
            return status.isPassed();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 检查质检是否允许直接放行 (供 SpEL 调用)
     *
     * 用法: #canReleaseWithQuality(factoryId, batchId)
     *
     * 评估逻辑:
     * 1. 查找批次最新的质检记录
     * 2. 调用 QualityDispositionRuleService 评估
     * 3. 返回是否可以直接放行（无需审批）
     *
     * @param factoryId 工厂ID
     * @param batchId 生产批次ID
     * @return 是否可以直接放行
     */
    public static boolean canReleaseWithQuality(String factoryId, Long batchId) {
        if (staticQualityService == null || staticInspectionRepo == null) {
            return false;
        }

        try {
            // 获取批次最新的质检记录
            Optional<QualityInspection> latestInspection =
                    staticInspectionRepo.findFirstByProductionBatchIdOrderByInspectionDateDesc(batchId);

            if (latestInspection.isEmpty()) {
                // 无质检记录，不允许放行
                return false;
            }

            // 评估是否可以直接放行
            return staticQualityService.canDirectRelease(factoryId, latestInspection.get());

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查质检是否需要审批 (供 SpEL 调用)
     *
     * 用法: #requiresQualityApproval(factoryId, batchId)
     *
     * @param factoryId 工厂ID
     * @param batchId 生产批次ID
     * @return 是否需要审批
     */
    public static boolean requiresQualityApproval(String factoryId, Long batchId) {
        if (staticQualityService == null || staticInspectionRepo == null) {
            return true; // 默认需要审批
        }

        try {
            Optional<QualityInspection> latestInspection =
                    staticInspectionRepo.findFirstByProductionBatchIdOrderByInspectionDateDesc(batchId);

            if (latestInspection.isEmpty()) {
                return true;
            }

            DispositionResult result = staticQualityService.evaluateDisposition(
                    factoryId, latestInspection.get());

            return result.isRequiresApproval();

        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 获取推荐的质检处置动作 (供 SpEL 调用)
     *
     * 用法: #getQualityDisposition(factoryId, batchId)
     *
     * @param factoryId 工厂ID
     * @param batchId 生产批次ID
     * @return 推荐的处置动作名称 (RELEASE, CONDITIONAL_RELEASE, REWORK, SCRAP, SPECIAL_APPROVAL, HOLD)
     */
    public static String getQualityDisposition(String factoryId, Long batchId) {
        if (staticQualityService == null || staticInspectionRepo == null) {
            return "HOLD";
        }

        try {
            Optional<QualityInspection> latestInspection =
                    staticInspectionRepo.findFirstByProductionBatchIdOrderByInspectionDateDesc(batchId);

            if (latestInspection.isEmpty()) {
                return "HOLD";
            }

            DispositionResult result = staticQualityService.evaluateDisposition(
                    factoryId, latestInspection.get());

            return result.getRecommendedAction().name();

        } catch (Exception e) {
            return "HOLD";
        }
    }

    /**
     * 执行动作
     */
    private Object executeAction(String factoryId, String actionName, Object entity) {
        log.debug("执行动作: {}", actionName);

        // 通过规则引擎执行动作规则
        return ruleEngineService.executeRules(factoryId, "action:" + actionName, entity);
    }
}
