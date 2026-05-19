package com.cretas.aims.ai.tool.impl.pricing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 模拟价格策略计算 — Canvas-Pricing Phase 4b SKELETON.
 *
 * <p>调用 {@code PricingEngine.simulate(...)} (NOT 持久化).
 * Canvas "模拟" 按钮 + AI 询问 "叮咚 1000kg 多少钱" 时调用.
 *
 * <p><strong>Sister chat: 实际逻辑 throw UnsupportedOp (PricingEngineImpl 抛), 待你填.</strong>
 */
@Slf4j
@Component
public class PricingTestCalculateTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "pricing_test_calculate";
    }

    @Override
    public String getDescription() {
        return "模拟某 SKU/客户/数量场景的成交价 (不下单, 仅 preview). 用户问 '叮咚 1000kg 最终多少钱' 时调用.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("productId", Map.of("type", "string", "description", "商品ID"));
        properties.put("quantity", Map.of("type", "integer", "description", "数量"));
        properties.put("unitPriceList", Map.of("type", "number", "description", "标价 (商品 master 取)"));
        properties.put("customerId", Map.of("type", "integer", "description", "客户ID (可空, 匿名询价)"));
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("productId", "quantity"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("productId", "quantity");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        log.warn("PricingTestCalculateTool skeleton invoked — sister chat to implement. " +
                "factoryId={}, params={}", factoryId, params);
        throw new UnsupportedOperationException(
                "pricing_test_calculate not yet implemented. Sister chat to wire to PricingEngine.simulate (which itself throws until impl)."
        );
    }
}
