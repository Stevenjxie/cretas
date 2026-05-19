package com.cretas.aims.ai.tool.impl.pricing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 修改价格策略 — Canvas-Pricing Phase 4b SKELETON.
 *
 * <p>修改单条策略的规则/优先级/有效期等. 不改 strategyType (用 toggle/delete + create 替换类型).
 *
 * <p><strong>Sister chat: 实际逻辑 throw UnsupportedOp, 待你填.</strong>
 */
@Slf4j
@Component
public class PricingStrategyUpdateTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "pricing_strategy_update";
    }

    @Override
    public String getDescription() {
        return "修改价格策略的规则/优先级/有效期. 用户说 '把叮咚阶梯改成 100kg 起 3%' 时调用.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        properties.put("strategyId", Map.of("type", "string", "description", "策略ID (UUID)"));
        properties.put("strategyCode", Map.of("type", "string", "description", "策略代码 (与 strategyId 二选一)"));
        properties.put("rulesJson", Map.of("type", "object", "description", "新的规则 JSON"));
        properties.put("priority", Map.of("type", "integer", "description", "优先级"));
        properties.put("validFrom", Map.of("type", "string", "description", "生效日期"));
        properties.put("validTo", Map.of("type", "string", "description", "失效日期"));

        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // strategyId 或 strategyCode 二选一 (sister chat 在 doExecute 校验)
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        log.warn("PricingStrategyUpdateTool skeleton invoked — sister chat to implement. " +
                "factoryId={}, params={}", factoryId, params);
        throw new UnsupportedOperationException(
                "pricing_strategy_update not yet implemented. Sister chat to wire to PricingStrategyController.updateStrategy."
        );
    }
}
