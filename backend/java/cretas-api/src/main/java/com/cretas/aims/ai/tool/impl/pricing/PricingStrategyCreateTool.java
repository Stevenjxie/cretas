package com.cretas.aims.ai.tool.impl.pricing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 创建价格策略 — Canvas-Pricing Phase 4b SKELETON.
 *
 * <p>支持 5 种类型: TIERED / PROMOTION / MEMBER / BUNDLE / CYCLE.
 *
 * <p><strong>Sister chat: 实际逻辑 throw UnsupportedOp, 待你填.</strong>
 * 期望: 调 {@code PricingStrategyController.createStrategy} 等价 service 方法.
 */
@Slf4j
@Component
public class PricingStrategyCreateTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "pricing_strategy_create";
    }

    @Override
    public String getDescription() {
        return "创建价格策略 (阶梯/促销/会员/套餐/跨周期返点). 用户描述场景如 '叮咚月采购超 10 万给 5%' 时调用.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        properties.put("strategyCode", Map.of("type", "string",
                "description", "策略代码 (工厂内唯一, 如 DINGDONG_TIERED_2026)"));
        properties.put("strategyName", Map.of("type", "string",
                "description", "策略显示名"));
        properties.put("strategyType", Map.of(
                "type", "string",
                "description", "策略类型",
                "enum", Arrays.asList("TIERED", "PROMOTION", "MEMBER", "BUNDLE", "CYCLE")
        ));
        properties.put("rulesJson", Map.of("type", "object",
                "description", "策略规则 JSON, 不同类型 shape 不同, 参见 spec §1"));
        properties.put("scopeFilterJson", Map.of("type", "object",
                "description", "scope 过滤 JSON (productCategories / customerGroups / regions)"));
        properties.put("priority", Map.of("type", "integer",
                "description", "优先级, 数字小优先, 默认 100"));
        properties.put("validFrom", Map.of("type", "string", "description", "生效日期 YYYY-MM-DD"));
        properties.put("validTo", Map.of("type", "string", "description", "失效日期 YYYY-MM-DD"));

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("strategyCode", "strategyType"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("strategyCode", "strategyType");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        log.warn("PricingStrategyCreateTool skeleton invoked — sister chat to implement. " +
                "factoryId={}, params={}", factoryId, params);
        throw new UnsupportedOperationException(
                "pricing_strategy_create not yet implemented. Sister chat to wire to PricingStrategyController.createStrategy."
        );
    }
}
