package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.InvalidStageTransitionException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.service.SalesOpportunityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;

/**
 * 商机阶段推进工具 (Sprint 8 P1 — 卤味老板 Workdesk V1, WRITE + Preview).
 *
 * <p>给销售员"把商机推到下一个阶段 / 标 CLOSED_WON / CLOSED_LOST" 的快捷入口.
 * 业务逻辑全走 {@link SalesOpportunityService.transitionStage} (state machine
 * + history audit + CLOSED_WON event publishing).
 *
 * <p>防呆 4 位一体:
 * <ul>
 *   <li>R1: doPreview 显当前 stage + 目标 stage + state machine 合法性</li>
 *   <li>R2: message 含 商机 title + customer 名 + before/after stage</li>
 *   <li>R3: newStage enum 白名单 (8 个 OpportunityStage)</li>
 *   <li>R4: state machine 拒绝 same-stage self-loop / 非法跳级 (service 层校验)</li>
 *   <li>error: backward / skip-forward 等场景 reason 必填, 后端 message 透传</li>
 * </ul>
 *
 * <p>Intent Code: {@code OPPORTUNITY_TRANSITION}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P1)
 */
@Slf4j
@Component
public class OpportunityTransitionStageTool extends AbstractBusinessTool {

    @Autowired
    private SalesOpportunityService salesOpportunityService;

    @Autowired
    private CustomerRepository customerRepository;

    @Override
    public String getToolName() {
        return "opportunity_transition_stage";
    }

    @Override
    public String getDescription() {
        return "推进商机到下一阶段 (state machine 含跳级 / 回退 / 终态重激活校验). "
                + "LLM 触发场景: 用户说 '把鲜湘缘的商机推到 NEGOTIATE' / '商机签了' (= CLOSED_WON) / "
                + "'商机黄了' (= CLOSED_LOST) / '推进商机' / '商机进度更新'. "
                + "WRITE + Preview 支持. 跳级 / 回退 需 confirmSkip=true 或 reason 必填.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> id = new HashMap<>();
        id.put("type", "string");
        id.put("description", "商机 UUID, 必填");
        properties.put("id", id);

        Map<String, Object> newStage = new HashMap<>();
        newStage.put("type", "string");
        newStage.put("description",
                "目标阶段 (R3 enum 白名单): LEAD / QUALIFIED / DEMO / PROPOSAL / NEGOTIATE / VERBAL / CLOSED_WON / CLOSED_LOST");
        newStage.put("enum", List.of("LEAD", "QUALIFIED", "DEMO", "PROPOSAL", "NEGOTIATE",
                "VERBAL", "CLOSED_WON", "CLOSED_LOST"));
        properties.put("newStage", newStage);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "可选 — 备注/原因. 回退 / 跳级 / 终态重激活 时必填");
        properties.put("reason", reason);

        Map<String, Object> confirmSkip = new HashMap<>();
        confirmSkip.put("type", "boolean");
        confirmSkip.put("description", "可选 — 允许跨阶段跳级 (默认 false). LEAD→PROPOSAL 等需要 true");
        confirmSkip.put("default", false);
        properties.put("confirmSkip", confirmSkip);

        Map<String, Object> probabilityOverride = new HashMap<>();
        probabilityOverride.put("type", "integer");
        probabilityOverride.put("description", "可选 — 手动覆盖默认 stage probability (0-100)");
        probabilityOverride.put("minimum", 0);
        probabilityOverride.put("maximum", 100);
        properties.put("probabilityOverride", probabilityOverride);

        schema.put("properties", properties);
        schema.put("required", List.of("id", "newStage"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("id", "newStage");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.WRITE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.MEDIUM;
    }

    /**
     * Preview: 显当前 stage + 目标 stage + state machine 合法性. 不实际改 DB.
     */
    @Override
    protected Map<String, Object> doPreview(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String id = getString(params, "id");
        String newStageStr = getString(params, "newStage");
        String reason = getString(params, "reason", null);
        boolean confirmSkip = Boolean.TRUE.equals(getBoolean(params, "confirmSkip", false));

        Map<String, Object> result = new HashMap<>();
        result.put("status", "PREVIEW");

        // Validate enum (R3)
        OpportunityStage newStage;
        try {
            newStage = OpportunityStage.valueOf(newStageStr);
        } catch (IllegalArgumentException ex) {
            result.put("status", "PREVIEW_INVALID");
            result.put("canDo", false);
            result.put("code", 400);
            result.put("message", "目标阶段非法: " + newStageStr
                    + " (合法: LEAD/QUALIFIED/DEMO/PROPOSAL/NEGOTIATE/VERBAL/CLOSED_WON/CLOSED_LOST)");
            return result;
        }

        // Get current opportunity (R2 context)
        SalesOpportunity opp;
        try {
            opp = salesOpportunityService.get(factoryId, id);
        } catch (BusinessException ex) {
            result.put("status", "PREVIEW_INVALID");
            result.put("canDo", false);
            result.put("code", ex.getCode());
            result.put("message", "⚠️ " + ex.getMessage());
            return result;
        }
        OpportunityStage fromStage = opp.getStage();
        String customerName = resolveCustomerName(factoryId, opp.getCustomerId());

        result.put("id", id);
        result.put("title", opp.getTitle());
        result.put("customerId", opp.getCustomerId());
        result.put("customerName", customerName);
        result.put("fromStage", fromStage != null ? fromStage.name() : null);
        result.put("fromStageDisplay", fromStage != null ? fromStage.getDisplayName() : null);
        result.put("toStage", newStage.name());
        result.put("toStageDisplay", newStage.getDisplayName());
        result.put("newProbability", newStage.getDefaultProbability());

        // State machine 合法性预检 (light copy of service-side validateTransition)
        ValidationCheck check = validateTransitionLight(fromStage, newStage, reason, confirmSkip);
        result.put("canDo", check.canDo);
        if (!check.canDo) {
            result.put("status", "PREVIEW_INVALID");
            result.put("message", "⚠️ " + check.message);
        } else {
            result.put("message", String.format(
                    "✓ 准备推进商机 \"%s\" (%s): %s → %s (probability=%d%%)",
                    opp.getTitle(),
                    nameForDisplay(customerName),
                    fromStage != null ? fromStage.getDisplayName() : "?",
                    newStage.getDisplayName(),
                    newStage.getDefaultProbability()));
        }
        return result;
    }

    /**
     * Execute: 委托 SalesOpportunityService.transitionStage. 含 state machine 校验
     * + history audit + CLOSED_WON event publishing.
     */
    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String id = getString(params, "id");
        String newStageStr = getString(params, "newStage");
        String reason = getString(params, "reason", null);
        boolean confirmSkip = Boolean.TRUE.equals(getBoolean(params, "confirmSkip", false));
        Integer probabilityOverride = getInteger(params, "probabilityOverride");
        Long currentUserId = getUserId(context);

        OpportunityStage newStage;
        try {
            newStage = OpportunityStage.valueOf(newStageStr);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400,
                    "newStage 非法: " + newStageStr
                            + " (合法: LEAD/QUALIFIED/DEMO/PROPOSAL/NEGOTIATE/VERBAL/CLOSED_WON/CLOSED_LOST)");
        }

        log.info("opportunity_transition_stage — factory={} oppId={} newStage={} changedBy={}",
                factoryId, id, newStage, currentUserId);

        try {
            SalesOpportunity saved = salesOpportunityService.transitionStage(
                    factoryId, id, newStage, reason, confirmSkip,
                    probabilityOverride, currentUserId);

            String customerName = resolveCustomerName(factoryId, saved.getCustomerId());

            Map<String, Object> data = new HashMap<>();
            data.put("id", saved.getId());
            data.put("title", saved.getTitle());
            data.put("customerId", saved.getCustomerId());
            data.put("customerName", customerName);
            data.put("stage", saved.getStage().name());
            data.put("stageDisplay", saved.getStage().getDisplayName());
            data.put("probability", saved.getProbability());
            data.put("closedAt",
                    saved.getClosedAt() != null ? saved.getClosedAt().toString() : null);

            String message = String.format(
                    "✓ 商机 \"%s\" (%s) 已推进至 %s (probability=%d%%)",
                    saved.getTitle(),
                    nameForDisplay(customerName),
                    saved.getStage().getDisplayName(),
                    saved.getProbability());
            return buildSimpleResult(message, data);

        } catch (InvalidStageTransitionException ex) {
            // 转换非法 — 4 位一体 透传 message + actionHint
            Map<String, Object> result = new HashMap<>();
            result.put("status", "INVALID_TRANSITION");
            result.put("message", "⚠️ " + ex.getMessage());
            result.put("actionHint", "/sales/customers"); // 跳客户列表让用户手动 navigate
            return result;
        }
    }

    /** 简化版 state machine 校验 — 跟 service 内部 validateTransition 等价. */
    private ValidationCheck validateTransitionLight(OpportunityStage from, OpportunityStage to,
            String reason, boolean confirmSkip) {
        if (from == null) {
            return new ValidationCheck(false, "当前阶段未知, 无法预览转换");
        }
        if (from == to) {
            return new ValidationCheck(false,
                    "无效转换: 起止阶段相同 (" + from.getDisplayName() + ")");
        }
        boolean fromTerminal = from.isTerminal();
        boolean toTerminal = to.isTerminal();

        if (!fromTerminal && toTerminal) {
            return new ValidationCheck(true, null);  // forward close OK
        }
        if (fromTerminal && !toTerminal) {
            if (reason == null || reason.isBlank()) {
                return new ValidationCheck(false,
                        "终态商机重新激活需说明原因 ("
                                + from.getDisplayName() + " → " + to.getDisplayName() + ")");
            }
            return new ValidationCheck(true, null);
        }
        if (fromTerminal && toTerminal) {
            if (reason == null || reason.isBlank()) {
                return new ValidationCheck(false,
                        "终态间转换需说明原因 ("
                                + from.getDisplayName() + " → " + to.getDisplayName() + ")");
            }
            return new ValidationCheck(true, null);
        }
        int delta = to.ordinalForFlow() - from.ordinalForFlow();
        if (delta == 1) return new ValidationCheck(true, null);
        if (delta > 1) {
            if (!confirmSkip) {
                return new ValidationCheck(false, String.format(
                        "跳级转换 (%s → %s, 跨 %d 阶段). 请确认 (confirmSkip=true) 并填写原因",
                        from.getDisplayName(), to.getDisplayName(), delta));
            }
            if (reason == null || reason.isBlank()) {
                return new ValidationCheck(false, "跳级转换需说明原因 ("
                        + from.getDisplayName() + " → " + to.getDisplayName() + ")");
            }
            return new ValidationCheck(true, null);
        }
        // backward
        if (reason == null || reason.isBlank()) {
            return new ValidationCheck(false, "阶段回退需说明原因 ("
                    + from.getDisplayName() + " → " + to.getDisplayName() + ")");
        }
        return new ValidationCheck(true, null);
    }

    private String resolveCustomerName(String factoryId, String customerId) {
        if (customerId == null) return null;
        try {
            Optional<Customer> opt = customerRepository.findByIdAndFactoryId(customerId, factoryId);
            return opt.map(Customer::getName).orElse(null);
        } catch (Exception ex) {
            log.warn("resolveCustomerName failed customer={} factory={}: {}",
                    customerId, factoryId, ex.getMessage());
            return null;
        }
    }

    private String nameForDisplay(String name) {
        return (name != null && !name.isBlank()) ? name : "该客户";
    }

    /** Internal: state machine validation light result. */
    private static class ValidationCheck {
        final boolean canDo;
        final String message;
        ValidationCheck(boolean canDo, String message) {
            this.canDo = canDo;
            this.message = message;
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
