package com.cretas.aims.ai.tool.impl.pricing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 启用/禁用价格策略 — Canvas-Pricing Phase 4b SKELETON.
 *
 * <p><strong>Sister chat: 实际逻辑 throw UnsupportedOp, 待你填.</strong>
 */
@Slf4j
@Component
public class PricingStrategyToggleTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "pricing_strategy_toggle";
    }

    @Override
    public String getDescription() {
        return "启用/禁用价格策略. 用户说 '把促销策略停掉' / '叮咚阶梯重新启用' 时调用.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("strategyId", Map.of("type", "string", "description", "策略ID"));
        properties.put("strategyCode", Map.of("type", "string", "description", "策略代码 (与 strategyId 二选一)"));
        properties.put("enabled", Map.of("type", "boolean",
                "description", "true = 启用, false = 禁用; null = toggle 当前状态"));
        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        log.warn("PricingStrategyToggleTool skeleton invoked — sister chat to implement. " +
                "factoryId={}, params={}", factoryId, params);
        throw new UnsupportedOperationException(
                "pricing_strategy_toggle not yet implemented. Sister chat to wire to PricingStrategyController.toggleStrategy."
        );
    }
}
