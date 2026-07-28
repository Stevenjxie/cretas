package com.cretas.aims.ai.tool.impl.pricing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.pricing.PricingStrategy;
import com.cretas.aims.entity.pricing.PricingStrategyType;
import com.cretas.aims.repository.pricing.PricingStrategyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI Tool: 创建价格策略 — Canvas-Pricing Phase 4b.
 *
 * <p>支持 5 种类型: TIERED / PROMOTION / MEMBER / BUNDLE / CYCLE.
 * 工厂内 strategyCode 唯一; 同代码二次创建返 409 + actionHint.
 */
@Slf4j
@Component
public class PricingStrategyCreateTool extends AbstractBusinessTool {

    @Autowired
    private PricingStrategyRepository strategyRepo;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

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
    @Transactional
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        // factoryId is mandatory — tools always operate within tenant scope.
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId 不能为空");
        }

        String strategyCode = getString(params, "strategyCode");
        String strategyTypeStr = getString(params, "strategyType");

        // 1. validate strategyType enum
        PricingStrategyType type;
        try {
            type = PricingStrategyType.valueOf(strategyTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "无效的策略类型: " + strategyTypeStr +
                    " (有效值: TIERED, PROMOTION, MEMBER, BUNDLE, CYCLE)");
        }

        // 2. 幂等 (per fool-proof Rule 4): 检查 strategyCode 已存在
        Optional<PricingStrategy> existing =
                strategyRepo.findByFactoryIdAndStrategyCode(factoryId, strategyCode);
        if (existing.isPresent()) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "DUPLICATE");
            result.put("message", String.format(
                    "策略代码 %s 已存在 (工厂 %s) — 请使用其他代码或更新现有策略", strategyCode, factoryId));
            result.put("existingStrategyId", existing.get().getId());
            result.put("existingStrategyCode", existing.get().getStrategyCode());
            result.put("actionHint", "如要修改, 请用 pricing_strategy_update");
            return result;
        }

        // 3. build entity (factoryId-scoped)
        PricingStrategy s = new PricingStrategy();
        s.setFactoryId(factoryId);
        s.setStrategyCode(strategyCode);
        s.setStrategyName(getString(params, "strategyName"));
        s.setStrategyType(type);

        // rulesJson + scopeFilterJson are passed as Map<String,Object>
        @SuppressWarnings("unchecked")
        Map<String, Object> rulesJson = params.get("rulesJson") instanceof Map
                ? (Map<String, Object>) params.get("rulesJson") : null;
        s.setRulesJson(rulesJson);

        @SuppressWarnings("unchecked")
        Map<String, Object> scopeFilterJson = params.get("scopeFilterJson") instanceof Map
                ? (Map<String, Object>) params.get("scopeFilterJson") : null;
        s.setScopeFilterJson(scopeFilterJson);

        Integer priority = getInteger(params, "priority");
        s.setPriority(priority != null ? priority : 100);
        s.setEnabled(true);   // 默认启用 (per spec §1 entity default)

        String validFromStr = getString(params, "validFrom");
        if (validFromStr != null && !validFromStr.isBlank()) {
            s.setValidFrom(LocalDate.parse(validFromStr, DATE_FORMATTER));
        }
        String validToStr = getString(params, "validTo");
        if (validToStr != null && !validToStr.isBlank()) {
            s.setValidTo(LocalDate.parse(validToStr, DATE_FORMATTER));
        }

        // 4. save
        PricingStrategy saved = strategyRepo.save(s);
        log.info("AI Tool created pricing strategy: factoryId={}, code={}, type={}, priority={}",
                factoryId, saved.getStrategyCode(), saved.getStrategyType(), saved.getPriority());

        // 防呆 R2: result message 必带身份上下文 (策略名 / 代码 / 类型)
        String displayName = saved.getStrategyName() != null && !saved.getStrategyName().isBlank()
                ? saved.getStrategyName() : saved.getStrategyCode();
        return buildSimpleResult(
                String.format("已创建价格策略: %s (%s, 类型: %s, 优先级: %d, 启用)",
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
            case "strategyCode" -> "请问策略代码是什么? (工厂内唯一, 如 DINGDONG_TIERED_2026)";
            case "strategyType" -> "请问策略类型? TIERED 阶梯 / PROMOTION 促销 / MEMBER 会员 / BUNDLE 套餐 / CYCLE 跨周期返点.";
            default -> null;
        };
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
