package com.cretas.aims.ai.tool.impl.pricing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.pricing.PricingStrategy;
import com.cretas.aims.repository.pricing.PricingStrategyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI Tool: 修改价格策略 — Canvas-Pricing Phase 4b.
 *
 * <p>修改单条策略的规则/优先级/有效期等. 不改 strategyType (用 toggle/delete + create 替换类型).
 * factoryId guard: 跨工厂修改返 PERMISSION_DENIED.
 */
@Slf4j
@Component
public class PricingStrategyUpdateTool extends AbstractBusinessTool {

    @Autowired
    private PricingStrategyRepository strategyRepo;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

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
        properties.put("scopeFilterJson", Map.of("type", "object",
                "description", "scope 过滤 JSON (productCategories / customerGroups / regions)"));
        properties.put("priority", Map.of("type", "integer", "description", "优先级"));
        properties.put("strategyName", Map.of("type", "string", "description", "显示名"));
        properties.put("validFrom", Map.of("type", "string", "description", "生效日期"));
        properties.put("validTo", Map.of("type", "string", "description", "失效日期"));

        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // strategyId 或 strategyCode 二选一 (doExecute 校验)
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

        // 1. load (with factoryId guard)
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
            log.warn("AI Tool pricing_strategy_update cross-factory attempt: caller={}, strategy.factoryId={}",
                    factoryId, s.getFactoryId());
            throw new SecurityException("无权修改其他工厂的策略 (访问拒绝)");
        }

        // 3. update mutable fields (strategyType 不可改, 见 Javadoc)
        if (params.containsKey("strategyName")) {
            s.setStrategyName(getString(params, "strategyName"));
        }

        if (params.containsKey("rulesJson")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rules = params.get("rulesJson") instanceof Map
                    ? (Map<String, Object>) params.get("rulesJson") : null;
            s.setRulesJson(rules);
        }

        if (params.containsKey("scopeFilterJson")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> scope = params.get("scopeFilterJson") instanceof Map
                    ? (Map<String, Object>) params.get("scopeFilterJson") : null;
            s.setScopeFilterJson(scope);
        }

        Integer newPriority = getInteger(params, "priority");
        if (newPriority != null) s.setPriority(newPriority);

        String validFromStr = getString(params, "validFrom");
        if (validFromStr != null && !validFromStr.isBlank()) {
            s.setValidFrom(LocalDate.parse(validFromStr, DATE_FORMATTER));
        }
        String validToStr = getString(params, "validTo");
        if (validToStr != null && !validToStr.isBlank()) {
            s.setValidTo(LocalDate.parse(validToStr, DATE_FORMATTER));
        }

        PricingStrategy saved = strategyRepo.save(s);
        log.info("AI Tool updated pricing strategy: factoryId={}, id={}, code={}, priority={}",
                factoryId, saved.getId(), saved.getStrategyCode(), saved.getPriority());

        // 防呆 R2: 必带身份上下文
        String displayName = saved.getStrategyName() != null && !saved.getStrategyName().isBlank()
                ? saved.getStrategyName() : saved.getStrategyCode();
        return buildSimpleResult(
                String.format("已更新价格策略: %s (%s, 类型: %s, 优先级: %d)",
                        displayName, saved.getStrategyCode(), saved.getStrategyType(), saved.getPriority()),
                Map.of(
                        "strategyId", saved.getId(),
                        "strategyCode", saved.getStrategyCode(),
                        "strategyType", saved.getStrategyType().name(),
                        "priority", saved.getPriority(),
                        "enabled", saved.getEnabled(),
                        "factoryId", saved.getFactoryId()
                )
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "strategyId" -> "请问要修改哪个策略? 请提供策略ID或代码.";
            case "strategyCode" -> "请问要修改哪个策略? 请提供策略代码.";
            default -> null;
        };
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
