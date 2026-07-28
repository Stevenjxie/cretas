package com.cretas.aims.ai.tool.impl.pricing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.pricing.AppliedStrategy;
import com.cretas.aims.service.pricing.PricingEngine;
import com.cretas.aims.service.pricing.PricingRequest;
import com.cretas.aims.service.pricing.PricingResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 模拟价格策略计算 — Canvas-Pricing Phase 4b.
 *
 * <p>调 {@link PricingEngine#simulate(String, String, int, BigDecimal, Long)} (NOT 持久化).
 * Canvas "模拟" 按钮 + AI 询问 "叮咚 1000kg 多少钱" 时调用.
 */
@Slf4j
@Component
public class PricingTestCalculateTool extends AbstractBusinessTool {

    @Autowired
    private PricingEngine pricingEngine;

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
        properties.put("quantity", Map.of("type", "number", "description", "数量 (支持小数, 如按公斤)"));
        properties.put("unitPriceList", Map.of("type", "number", "description", "标价 (商品 master 取)"));
        properties.put("customerId", Map.of("type", "integer", "description", "客户ID (可空, 匿名询价)"));
        properties.put("customerGroup", Map.of("type", "string",
                "description", "客户分组 (可空, MEMBER 策略需要, 如 VIP / A)"));
        properties.put("productCategory", Map.of("type", "string",
                "description", "商品类目 (可空, scope 过滤用)"));
        properties.put("region", Map.of("type", "string",
                "description", "区域 (可空, scope 过滤用, 如 east / north)"));
        properties.put("costEstimate", Map.of("type", "number",
                "description", "单位成本 (可空, 用于触发 'below cost' 防呆 warning)"));
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
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId 不能为空");
        }

        String productId = getString(params, "productId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        BigDecimal unitPriceList = getBigDecimal(params, "unitPriceList");
        if (unitPriceList == null) unitPriceList = BigDecimal.ZERO;
        Long customerId = getLong(params, "customerId");
        String customerGroup = getString(params, "customerGroup");
        String productCategory = getString(params, "productCategory");
        String region = getString(params, "region");
        BigDecimal costEstimate = getBigDecimal(params, "costEstimate");

        // Build PricingRequest with factoryId (multi-tenant safety).
        // Post-review C1: 必须用 simulate(PricingRequest) overload — 老 5-arg overload 静默
        // drop customerGroup / productCategory → MEMBER 策略 + scope 过滤 全失效.
        PricingRequest req = PricingRequest.builder()
                .factoryId(factoryId)
                .productId(productId)
                .quantity(quantity != null ? quantity : BigDecimal.ONE)
                .unitPriceList(unitPriceList)
                .customerId(customerId)
                .customerGroup(customerGroup)
                .productCategory(productCategory)
                .region(region)
                .costEstimate(costEstimate)
                // simulate path NEVER writes log → leave businessEntity* null
                .build();

        // Use the new PricingRequest overload so customerGroup / productCategory / region /
        // costEstimate all flow into engine. Old 5-arg overload would discard them.
        PricingResult result = pricingEngine.simulate(req);

        log.debug("AI Tool pricing_test_calculate: factoryId={}, productId={}, qty={}, original={}, final={}",
                factoryId, productId, quantity, result.getOriginalPrice(), result.getFinalPrice());

        // 防呆 R2: result message 必带身份上下文 (商品 / 数量 / 原价 / 终价 / 折扣)
        Map<String, Object> data = new HashMap<>();
        data.put("factoryId", factoryId);
        data.put("productId", productId);
        data.put("quantity", req.getQuantity());
        data.put("unitPriceList", unitPriceList);
        data.put("originalPrice", result.getOriginalPrice());
        data.put("finalPrice", result.getFinalPrice());
        data.put("totalDiscount", result.getTotalDiscount());
        data.put("appliedStrategies", flattenApplied(result.getAppliedStrategies()));
        data.put("warnings", result.getWarnings() != null ? result.getWarnings() : new ArrayList<>());

        return buildSimpleResult(
                String.format("模拟价格: 商品 %s × %d, 原价 ¥%s → 终价 ¥%s (折扣 ¥%s)%s",
                        productId, req.getQuantity(),
                        result.getOriginalPrice(), result.getFinalPrice(), result.getTotalDiscount(),
                        (result.getWarnings() != null && !result.getWarnings().isEmpty())
                                ? " — 注意: " + String.join("; ", result.getWarnings())
                                : ""),
                data
        );
    }

    private List<Map<String, Object>> flattenApplied(List<AppliedStrategy> applied) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (applied == null) return out;
        for (AppliedStrategy a : applied) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("strategyId", a.getStrategyId());
            entry.put("strategyCode", a.getStrategyCode());
            entry.put("strategyType", a.getStrategyType() != null ? a.getStrategyType().name() : null);
            entry.put("discountApplied", a.getDiscountApplied());
            out.add(entry);
        }
        return out;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "productId" -> "请问要模拟哪个商品? 请提供商品ID.";
            case "quantity" -> "请问数量是多少?";
            default -> null;
        };
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
