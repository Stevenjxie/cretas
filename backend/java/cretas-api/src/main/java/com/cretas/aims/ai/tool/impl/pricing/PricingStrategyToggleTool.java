package com.cretas.aims.ai.tool.impl.pricing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.pricing.PricingStrategy;
import com.cretas.aims.repository.pricing.PricingStrategyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI Tool: 启用/禁用价格策略 — Canvas-Pricing Phase 4b.
 *
 * <p>{@code enabled=true/false} 显式设置, {@code enabled=null} → toggle 当前状态.
 * factoryId guard: 跨工厂操作返 PERMISSION_DENIED.
 */
@Slf4j
@Component
public class PricingStrategyToggleTool extends AbstractBusinessTool {

    @Autowired
    private PricingStrategyRepository strategyRepo;

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
    @Transactional
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId 不能为空");
        }

        String strategyId = getString(params, "strategyId");
        String strategyCode = getString(params, "strategyCode");
        if ((strategyId == null || strategyId.isBlank())
                && (strategyCode == null || strategyCode.isBlank())) {
            throw new IllegalArgumentException("必须提供 strategyId 或 strategyCode");
        }

        // 1. load (factoryId guard below)
        PricingStrategy s;
        if (strategyId != null && !strategyId.isBlank()) {
            s = strategyRepo.findById(strategyId)
                    .orElseThrow(() -> new IllegalArgumentException("策略不存在: " + strategyId));
        } else {
            Optional<PricingStrategy> opt = strategyRepo.findByFactoryIdAndStrategyCode(factoryId, strategyCode);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("策略不存在: code=" + strategyCode + " (工厂 " + factoryId + ")");
            }
            s = opt.get();
        }

        // 2. factoryId guard (multi-tenant safety)
        if (!factoryId.equals(s.getFactoryId())) {
            log.warn("AI Tool pricing_strategy_toggle cross-factory attempt: caller={}, strategy.factoryId={}",
                    factoryId, s.getFactoryId());
            throw new SecurityException("无权操作其他工厂的策略 (访问拒绝)");
        }

        // 3. set enabled
        Boolean newEnabled = getBoolean(params, "enabled");
        boolean targetEnabled = (newEnabled != null)
                ? newEnabled
                : !Boolean.TRUE.equals(s.getEnabled());   // toggle if null
        s.setEnabled(targetEnabled);
        PricingStrategy saved = strategyRepo.save(s);

        log.info("AI Tool toggled pricing strategy: factoryId={}, code={}, enabled={}",
                factoryId, saved.getStrategyCode(), saved.getEnabled());

        // 防呆 R2: 必带身份上下文
        String displayName = saved.getStrategyName() != null && !saved.getStrategyName().isBlank()
                ? saved.getStrategyName() : saved.getStrategyCode();
        return buildSimpleResult(
                String.format("已%s价格策略: %s (%s)",
                        Boolean.TRUE.equals(saved.getEnabled()) ? "启用" : "禁用",
                        displayName, saved.getStrategyCode()),
                Map.of(
                        "strategyId", saved.getId(),
                        "strategyCode", saved.getStrategyCode(),
                        "enabled", saved.getEnabled(),
                        "factoryId", saved.getFactoryId()
                )
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "strategyId" -> "请问要启用/禁用哪个策略? 请提供策略ID.";
            case "strategyCode" -> "请问要启用/禁用哪个策略? 请提供策略代码.";
            default -> null;
        };
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
