package com.cretas.aims.service.impl;

import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.DecisionAuditService;
import com.cretas.aims.service.QualityDispositionRuleService;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * 质检处置规则服务实现
 *
 * 基于可配置规则评估质检结果，确定处置动作
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityDispositionRuleServiceImpl implements QualityDispositionRuleService {

    private final ApprovalChainService approvalChainService;
    private final DecisionAuditService decisionAuditService;
    private final UserRepository userRepository;

    /**
     * 质检处置 → 库存桥接。放行 (RELEASE/CONDITIONAL_RELEASE) 时把本次质检隔离的成品/原料批次翻回
     * AVAILABLE, 报废 (SCRAP) 时把原料置 SCRAPPED —— 让「人的处置决策」真正执行到库存, 而非只写审计日志。
     * {@code required=false} 以不破坏未 wire 该 bean 的单测; 缺失时 graceful skip。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.quality.QualityDispositionInventoryService dispositionInventoryService;

    // ===================== 阈值配置（未来可配置化） =====================
    private static final BigDecimal PASS_RATE_THRESHOLD_RELEASE = new BigDecimal("95.00");
    private static final BigDecimal PASS_RATE_THRESHOLD_CONDITIONAL = new BigDecimal("85.00");
    private static final BigDecimal PASS_RATE_THRESHOLD_REWORK = new BigDecimal("70.00");
    private static final BigDecimal DEFECT_RATE_THRESHOLD_SCRAP = new BigDecimal("50.00");

    @Override
    public DispositionResult evaluateDisposition(String factoryId, QualityInspection inspection) {
        return evaluateDisposition(factoryId, inspection, new HashMap<>());
    }

    @Override
    public DispositionResult evaluateDisposition(
            String factoryId,
            QualityInspection inspection,
            Map<String, Object> context) {

        log.info("评估质检处置: factoryId={}, inspectionId={}, passRate={}",
                factoryId, inspection.getId(), inspection.getPassRate());

        // 构建评估上下文
        Map<String, Object> evalContext = buildEvaluationContext(inspection, context);

        // 基于规则评估处置动作
        DispositionAction recommendedAction = evaluateAction(inspection);
        BigDecimal confidence = calculateConfidence(inspection, recommendedAction);

        // 检查是否需要审批
        boolean requiresApproval = checkApprovalRequirement(
                factoryId, recommendedAction, evalContext);

        // 查找匹配的规则配置
        Optional<ApprovalChainConfig> matchedConfig = Optional.empty();
        if (requiresApproval) {
            matchedConfig = approvalChainService.findMatchingConfig(
                    factoryId, DecisionType.QUALITY_RELEASE, evalContext);
        }

        // 确定备选动作
        DispositionAction[] alternatives = determineAlternatives(recommendedAction);

        DispositionResult result = DispositionResult.builder()
                .recommendedAction(recommendedAction)
                .requiresApproval(requiresApproval)
                .triggeredRuleName(matchedConfig.map(ApprovalChainConfig::getName).orElse(null))
                .ruleConfigId(matchedConfig.map(ApprovalChainConfig::getId).orElse(null))
                .ruleVersion(matchedConfig.map(ApprovalChainConfig::getVersion).orElse(null))
                .confidence(confidence)
                .reason(buildReasonString(inspection, recommendedAction))
                .alternativeActions(alternatives)
                .evaluationContext(evalContext)
                .build();

        log.info("质检处置评估完成: action={}, requiresApproval={}, confidence={}",
                recommendedAction, requiresApproval, confidence);

        return result;
    }

    @Override
    public boolean canDirectRelease(String factoryId, QualityInspection inspection) {
        // 直接放行条件：
        // 1. 合格率 >= 95%
        // 2. 结果为 PASS
        // 3. 无需审批
        if (inspection.getPassRate() == null) {
            return false;
        }

        boolean passRateOk = inspection.getPassRate().compareTo(PASS_RATE_THRESHOLD_RELEASE) >= 0;
        boolean resultPass = "PASS".equalsIgnoreCase(inspection.getResult());

        if (!passRateOk || !resultPass) {
            return false;
        }

        // 检查是否有阻止直接放行的规则
        Map<String, Object> context = buildEvaluationContext(inspection, new HashMap<>());
        return !approvalChainService.requiresApproval(
                factoryId, DecisionType.QUALITY_RELEASE, context);
    }

    @Override
    public boolean requiresSpecialApproval(
            String factoryId,
            QualityInspection inspection,
            DispositionAction requestedAction) {

        // 特批场景：
        // 1. 合格率低于阈值但请求放行
        // 2. 不合格但请求条件放行
        // 3. 严重缺陷但不报废

        Map<String, Object> context = buildEvaluationContext(inspection, new HashMap<>());
        context.put("requestedAction", requestedAction.name());

        // 检查是否匹配特批规则
        return approvalChainService.requiresApproval(
                factoryId, DecisionType.QUALITY_EXCEPTION, context);
    }

    @Override
    @Transactional
    public DispositionExecutionResult executeDisposition(
            String factoryId,
            QualityInspection inspection,
            DispositionAction action,
            Long executorId,
            String reason) {

        log.info("执行质检处置: factoryId={}, inspectionId={}, action={}, executorId={}",
                factoryId, inspection.getId(), action, executorId);

        // 获取执行人信息
        User executor = userRepository.findById(executorId)
                .orElseThrow(() -> new EntityNotFoundException("User", executorId));

        // 构建上下文
        Map<String, Object> context = buildEvaluationContext(inspection, new HashMap<>());
        context.put("requestedAction", action.name());
        context.put("executorId", executorId);
        context.put("reason", reason);

        // 检查是否需要特批
        boolean needsSpecialApproval = requiresSpecialApproval(factoryId, inspection, action);

        String auditLogId = null;
        String approvalRequestId = null;
        boolean approvalInitiated = false;
        String newStatus = null;

        if (needsSpecialApproval) {
            // 需要特批 - 记录审计并启动审批流程
            Optional<ApprovalChainConfig> config = approvalChainService.findMatchingConfig(
                    factoryId, DecisionType.QUALITY_EXCEPTION, context);

            var auditLog = decisionAuditService.logForceInsertWithRuleConfig(
                    factoryId,
                    "QualityInspection",
                    inspection.getId(),
                    context,
                    reason,
                    true,
                    executorId,
                    executor.getFullName(),
                    executor.getRole() != null ? executor.getRole() : "UNKNOWN",
                    config.map(ApprovalChainConfig::getId).orElse(null),
                    config.map(ApprovalChainConfig::getVersion).orElse(null),
                    config.map(ApprovalChainConfig::getName).orElse(null)
            );

            auditLogId = auditLog.getId();
            approvalInitiated = true;
            approvalRequestId = auditLog.getId(); // 使用审计日志ID作为审批请求ID
            newStatus = "PENDING_APPROVAL";

            log.info("质检处置需要特批: auditLogId={}", auditLogId);

        } else {
            // 直接执行
            Map<String, Object> outputResult = new HashMap<>();
            outputResult.put("action", action.name());
            outputResult.put("newStatus", mapActionToStatus(action));

            var auditLog = decisionAuditService.logRuleExecution(
                    factoryId,
                    "QualityInspection",
                    inspection.getId(),
                    context,
                    outputResult,
                    List.of("QUALITY_DISPOSITION_RULE"),
                    "质检处置: " + action.getDescription(),
                    executorId,
                    executor.getFullName(),
                    executor.getRole() != null ? executor.getRole() : "UNKNOWN"
            );

            auditLogId = auditLog.getId();
            newStatus = mapActionToStatus(action);

            // 🔒 库存桥接: 只在<b>最终直接执行</b>分支翻转库存 (非 approval-initiated 分支)。
            // 未审批申请 / 被拒审批不会走到这里, 保证「审批门禁」—— 翻转发生在最终执行/审批通过, 不在提交时。
            // 在同一 @Transactional 内, 与审计日志一起提交/回滚。放行→隔离批次回 AVAILABLE。
            if (dispositionInventoryService != null) {
                try {
                    dispositionInventoryService.applyDisposition(inspection, action);
                } catch (Exception e) {
                    // 库存翻转失败必须让整个处置事务回滚 (审计日志与库存状态不能不一致): 食安关键路径, fail-loud。
                    log.error("质检处置库存桥接失败, 回滚整个处置: inspectionId={}, action={}: {}",
                            inspection.getId(), action, e.getMessage(), e);
                    throw e;
                }
            }

            log.info("质检处置已执行: action={}, newStatus={}", action, newStatus);
        }

        return DispositionExecutionResult.builder()
                .success(true)
                .executedAction(action)
                .approvalInitiated(approvalInitiated)
                .approvalRequestId(approvalRequestId)
                .auditLogId(auditLogId)
                .message(approvalInitiated
                        ? "处置请求已提交，等待审批"
                        : "处置已执行: " + action.getDescription())
                .newBatchStatus(newStatus)
                .build();
    }

    // ===================== 私有辅助方法 =====================

    /**
     * 基于质检结果评估处置动作
     */
    private DispositionAction evaluateAction(QualityInspection inspection) {
        BigDecimal passRate = inspection.getPassRate();
        String result = inspection.getResult();

        // 空数据处理
        if (passRate == null) {
            return DispositionAction.HOLD;
        }

        // 直接放行
        if (passRate.compareTo(PASS_RATE_THRESHOLD_RELEASE) >= 0 &&
                "PASS".equalsIgnoreCase(result)) {
            return DispositionAction.RELEASE;
        }

        // 条件放行
        if (passRate.compareTo(PASS_RATE_THRESHOLD_CONDITIONAL) >= 0) {
            return DispositionAction.CONDITIONAL_RELEASE;
        }

        // 返工
        if (passRate.compareTo(PASS_RATE_THRESHOLD_REWORK) >= 0) {
            return DispositionAction.REWORK;
        }

        // 缺陷率过高 - 报废
        BigDecimal defectRate = inspection.getDefectRate();
        if (defectRate != null && defectRate.compareTo(DEFECT_RATE_THRESHOLD_SCRAP) >= 0) {
            return DispositionAction.SCRAP;
        }

        // 低合格率但不够报废条件 - 特批申请
        if (passRate.compareTo(PASS_RATE_THRESHOLD_REWORK) < 0) {
            return DispositionAction.SPECIAL_APPROVAL;
        }

        return DispositionAction.HOLD;
    }

    /**
     * 计算置信度
     */
    private BigDecimal calculateConfidence(QualityInspection inspection, DispositionAction action) {
        BigDecimal passRate = inspection.getPassRate();
        if (passRate == null) {
            return BigDecimal.ZERO;
        }

        // 基于合格率与阈值的距离计算置信度
        switch (action) {
            case RELEASE:
                // 合格率越高，置信度越高
                return passRate.min(new BigDecimal("100"));
            case CONDITIONAL_RELEASE:
                // 介于85-95之间
                return new BigDecimal("75").add(
                        passRate.subtract(PASS_RATE_THRESHOLD_CONDITIONAL)
                                .divide(new BigDecimal("10"), 2, java.math.RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("20")));
            case REWORK:
                return new BigDecimal("70");
            case SCRAP:
                return new BigDecimal("85"); // 报废是明确的决定
            default:
                return new BigDecimal("50");
        }
    }

    /**
     * 构建评估上下文
     */
    private Map<String, Object> buildEvaluationContext(
            QualityInspection inspection,
            Map<String, Object> additionalContext) {

        Map<String, Object> context = new HashMap<>(additionalContext);

        context.put("inspectionId", inspection.getId());
        context.put("productionBatchId", inspection.getProductionBatchId());
        context.put("passRate", inspection.getPassRate());
        context.put("defectRate", inspection.getDefectRate());
        context.put("result", inspection.getResult());
        context.put("qualityGrade", inspection.getQualityGrade());
        context.put("sampleSize", inspection.getSampleSize());
        context.put("passCount", inspection.getPassCount());
        context.put("failCount", inspection.getFailCount());
        context.put("inspectionDate", inspection.getInspectionDate());

        return context;
    }

    /**
     * 检查是否需要审批
     */
    private boolean checkApprovalRequirement(
            String factoryId,
            DispositionAction action,
            Map<String, Object> context) {

        // RELEASE 通常不需要审批（除非特殊规则）
        if (action == DispositionAction.RELEASE) {
            return approvalChainService.requiresApproval(
                    factoryId, DecisionType.QUALITY_RELEASE, context);
        }

        // CONDITIONAL_RELEASE, REWORK 可能需要审批
        if (action == DispositionAction.CONDITIONAL_RELEASE ||
                action == DispositionAction.REWORK) {
            return approvalChainService.requiresApproval(
                    factoryId, DecisionType.QUALITY_RELEASE, context);
        }

        // SCRAP 通常需要审批
        if (action == DispositionAction.SCRAP) {
            context.put("dispositionType", "SCRAP");
            return approvalChainService.requiresApproval(
                    factoryId, DecisionType.QUALITY_RELEASE, context);
        }

        // SPECIAL_APPROVAL 总是需要审批
        return action == DispositionAction.SPECIAL_APPROVAL;
    }

    /**
     * 确定备选动作
     */
    private DispositionAction[] determineAlternatives(DispositionAction recommended) {
        switch (recommended) {
            case RELEASE:
                return new DispositionAction[]{};
            case CONDITIONAL_RELEASE:
                return new DispositionAction[]{DispositionAction.REWORK, DispositionAction.SPECIAL_APPROVAL};
            case REWORK:
                return new DispositionAction[]{DispositionAction.CONDITIONAL_RELEASE, DispositionAction.SCRAP};
            case SCRAP:
                return new DispositionAction[]{DispositionAction.REWORK, DispositionAction.SPECIAL_APPROVAL};
            case SPECIAL_APPROVAL:
                return new DispositionAction[]{DispositionAction.SCRAP, DispositionAction.REWORK};
            default:
                return new DispositionAction[]{DispositionAction.SPECIAL_APPROVAL};
        }
    }

    /**
     * 构建处置理由字符串
     */
    private String buildReasonString(QualityInspection inspection, DispositionAction action) {
        StringBuilder sb = new StringBuilder();
        sb.append("合格率: ").append(inspection.getPassRate()).append("%, ");
        sb.append("缺陷率: ").append(inspection.getDefectRate()).append("%, ");
        sb.append("质量等级: ").append(inspection.getQualityGrade()).append(". ");

        switch (action) {
            case RELEASE:
                sb.append("质检合格，符合放行条件。");
                break;
            case CONDITIONAL_RELEASE:
                sb.append("合格率达标但未达最优，建议条件放行。");
                break;
            case REWORK:
                sb.append("合格率偏低，需要返工处理。");
                break;
            case SCRAP:
                sb.append("缺陷率过高，建议报废处理。");
                break;
            case SPECIAL_APPROVAL:
                sb.append("不满足常规规则，需要特批审批。");
                break;
            default:
                sb.append("需要进一步评估。");
        }

        return sb.toString();
    }

    /**
     * 将动作映射到批次状态
     */
    private String mapActionToStatus(DispositionAction action) {
        switch (action) {
            case RELEASE:
                return "PASSED";
            case CONDITIONAL_RELEASE:
                return "CONDITIONAL_PASSED";
            case REWORK:
                return "REWORK_REQUIRED";
            case SCRAP:
                return "SCRAPPED";
            case SPECIAL_APPROVAL:
                return "PENDING_APPROVAL";
            default:
                return "PENDING";
        }
    }
}
