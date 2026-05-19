package com.cretas.aims.ai.tool.impl.pricing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 查询价格策略列表 — Canvas-Pricing Phase 4b SKELETON.
 *
 * <p><strong>Sister chat: 实际逻辑 throw UnsupportedOp, 待你填.</strong>
 */
@Slf4j
@Component
public class PricingStrategyListTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "pricing_strategy_list";
    }

    @Override
    public String getDescription() {
        return "查询当前生效的价格策略 (可按类型/启用状态过滤). 用户问 '现在有什么折扣策略' 时调用.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("strategyType", Map.of(
                "type", "string",
                "description", "按类型过滤, 不填则全部",
                "enum", Arrays.asList("TIERED", "PROMOTION", "MEMBER", "BUNDLE", "CYCLE")
        ));
        properties.put("enabledOnly", Map.of("type", "boolean",
                "description", "true = 仅返启用, false/null = 全部"));
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
        log.warn("PricingStrategyListTool skeleton invoked — sister chat to implement. " +
                "factoryId={}, params={}", factoryId, params);
        throw new UnsupportedOperationException(
                "pricing_strategy_list not yet implemented. Sister chat to wire to PricingStrategyController.listStrategies."
        );
    }
}
