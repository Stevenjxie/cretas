package com.cretas.aims.ai.tool.impl.canvas;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Canvas 触发链配置更新工具
 *
 * 修改工厂的触发链配置，支持整链启用/禁用或单步骤启用/禁用。
 *
 * Intent Code: CANVAS_UPDATE_TRIGGER_CHAIN
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-04-09
 */
@Slf4j
@Component
public class CanvasUpdateTriggerChainTool extends AbstractBusinessTool {

    @Autowired(required = false)
    private FactoryTriggerChainRepository factoryTriggerChainRepo;

    @Override
    public String getToolName() {
        return "canvas_update_trigger_chain";
    }

    @Override
    public String getDescription() {
        return "修改工厂触发链配置，支持启用/禁用整条链或单个步骤。适用场景：通过 Canvas 配置界面调整自动化触发链逻辑，如关闭某条审批链或跳过某个步骤。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> chainCode = new HashMap<>();
        chainCode.put("type", "string");
        chainCode.put("description", "触发链代码，如 PURCHASE_APPROVAL、SALES_ORDER_FLOW 等");
        properties.put("chainCode", chainCode);

        Map<String, Object> enabled = new HashMap<>();
        enabled.put("type", "boolean");
        enabled.put("description", "整条链的启用状态（不指定 stepOrder 时生效）");
        properties.put("enabled", enabled);

        Map<String, Object> stepOrder = new HashMap<>();
        stepOrder.put("type", "integer");
        stepOrder.put("description", "步骤序号（从 1 开始），提供此参数时仅修改指定步骤的启用状态");
        properties.put("stepOrder", stepOrder);

        Map<String, Object> stepEnabled = new HashMap<>();
        stepEnabled.put("type", "boolean");
        stepEnabled.put("description", "指定步骤的启用状态（与 stepOrder 配合使用）");
        properties.put("stepEnabled", stepEnabled);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("chainCode"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("chainCode");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String chainCode = getString(params, "chainCode");
        Boolean enabled = getBoolean(params, "enabled");
        Integer stepOrder = getInteger(params, "stepOrder");
        Boolean stepEnabled = getBoolean(params, "stepEnabled");

        log.info("Canvas 触发链更新 - 工厂ID: {}, 链: {}, 启用: {}, 步骤: {}, 步骤启用: {}",
                factoryId, chainCode, enabled, stepOrder, stepEnabled);

        Optional<FactoryTriggerChain> chainOpt = factoryTriggerChainRepo.findByFactoryIdAndChainCode(factoryId, chainCode);
        if (chainOpt.isEmpty()) {
            throw new IllegalArgumentException("未找到触发链: " + chainCode + "（工厂: " + factoryId + "）");
        }

        FactoryTriggerChain chain = chainOpt.get();

        if (stepOrder != null) {
            // 修改单个步骤的启用状态
            if (stepEnabled == null) {
                throw new IllegalArgumentException("指定 stepOrder 时必须同时提供 stepEnabled 参数");
            }
            updateStepEnabled(chain, stepOrder, stepEnabled);
            factoryTriggerChainRepo.save(chain);

            log.info("Canvas 触发链步骤更新完成 - 链: {}, 步骤: {}, 启用: {}", chainCode, stepOrder, stepEnabled);
            return buildSimpleResult(
                    "触发链 " + chainCode + " 第 " + stepOrder + " 步已" + (stepEnabled ? "启用" : "禁用"),
                    Map.of("chainCode", chainCode, "stepOrder", stepOrder, "stepEnabled", stepEnabled)
            );
        } else {
            // 修改整条链的启用状态
            if (enabled == null) {
                throw new IllegalArgumentException("未指定 stepOrder 时必须提供 enabled 参数");
            }
            chain.setEnabled(enabled);
            factoryTriggerChainRepo.save(chain);

            String action = Boolean.TRUE.equals(enabled) ? "启用" : "禁用";
            log.info("Canvas 触发链整链更新完成 - 链: {}, 操作: {}", chainCode, action);
            return buildSimpleResult(
                    "触发链 " + chainCode + " 已" + action,
                    Map.of("chainCode", chainCode, "enabled", enabled)
            );
        }
    }

    /**
     * 修改触发链中指定步骤的 enabled 标志
     *
     * @param chain      触发链实体
     * @param stepOrder  步骤序号（从 1 开始）
     * @param stepEnabled 目标启用状态
     */
    private void updateStepEnabled(FactoryTriggerChain chain, int stepOrder, boolean stepEnabled) {
        List<Map<String, Object>> steps = chain.getSteps();
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("触发链 " + chain.getChainCode() + " 没有步骤配置");
        }

        // Steps are stored as a list; match by "order" field or by list index (1-based)
        boolean found = false;
        List<Map<String, Object>> updatedSteps = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            Map<String, Object> mutableStep = new HashMap<>(step);
            Object orderVal = mutableStep.get("order");
            int order = orderVal instanceof Number ? ((Number) orderVal).intValue() : -1;
            if (order == stepOrder) {
                mutableStep.put("enabled", stepEnabled);
                found = true;
            }
            updatedSteps.add(mutableStep);
        }

        if (!found) {
            // Fallback: try 1-based index if no "order" field
            if (stepOrder >= 1 && stepOrder <= steps.size()) {
                Map<String, Object> mutableStep = new HashMap<>(updatedSteps.get(stepOrder - 1));
                mutableStep.put("enabled", stepEnabled);
                updatedSteps.set(stepOrder - 1, mutableStep);
            } else {
                throw new IllegalArgumentException("触发链中不存在步骤序号: " + stepOrder);
            }
        }

        chain.setSteps(updatedSteps);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "chainCode", "请问要修改哪条触发链？请提供触发链代码（如 PURCHASE_APPROVAL、SALES_ORDER_FLOW 等）。",
                "enabled", "请问要启用还是禁用该触发链？（true 为启用，false 为禁用）",
                "stepOrder", "请问要修改第几步？请提供步骤序号（从 1 开始），不填则修改整条链。",
                "stepEnabled", "请问该步骤是否启用？（true 为启用，false 为禁用）"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "chainCode", "触发链代码",
                "enabled", "是否启用整链",
                "stepOrder", "步骤序号",
                "stepEnabled", "步骤是否启用"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
