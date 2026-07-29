package com.cretas.aims.ai.tool.impl.pricing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.pricing.PricingStrategy;
import com.cretas.aims.entity.pricing.PricingStrategyType;
import com.cretas.aims.repository.pricing.PricingStrategyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Tool: 查询价格策略列表 — Canvas-Pricing Phase 4b.
 *
 * <p>过滤: factoryId (必, 自动从 context) + 可选 strategyType + 可选 enabledOnly.
 */
@Slf4j
@Component
public class PricingStrategyListTool extends AbstractBusinessTool {

    @Autowired
    private PricingStrategyRepository strategyRepo;

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
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId 不能为空");
        }

        // 1. base query (factoryId scoped)
        List<PricingStrategy> all =
                strategyRepo.findByFactoryIdOrderByPriorityAscCreatedAtDesc(factoryId);

        // 2. optional strategyType filter
        String typeStr = getString(params, "strategyType");
        PricingStrategyType typeFilter = null;
        if (typeStr != null && !typeStr.isBlank()) {
            try {
                typeFilter = PricingStrategyType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid strategyType filter: {}, ignored", typeStr);
            }
        }

        // 3. optional enabledOnly filter
        Boolean enabledOnly = getBoolean(params, "enabledOnly");

        // 4. project to lightweight DTO list (avoid leaking @Where soft-delete internals)
        List<Map<String, Object>> filtered = new ArrayList<>(all.size());
        for (PricingStrategy s : all) {
            if (typeFilter != null && s.getStrategyType() != typeFilter) continue;
            if (Boolean.TRUE.equals(enabledOnly) && !Boolean.TRUE.equals(s.getEnabled())) continue;

            Map<String, Object> row = new HashMap<>();
            row.put("strategyId", s.getId());
            row.put("strategyCode", s.getStrategyCode());
            row.put("strategyName", s.getStrategyName());
            row.put("strategyType", s.getStrategyType() != null ? s.getStrategyType().name() : null);
            row.put("priority", s.getPriority());
            row.put("enabled", s.getEnabled());
            row.put("validFrom", s.getValidFrom());
            row.put("validTo", s.getValidTo());
            row.put("rulesJson", s.getRulesJson());
            row.put("scopeFilterJson", s.getScopeFilterJson());
            filtered.add(row);
        }

        log.debug("AI Tool list strategies: factoryId={}, total={}, filtered={}",
                factoryId, all.size(), filtered.size());

        // 防呆 R2: result message 必带身份上下文 (工厂 + 数量)
        return buildSimpleResult(
                String.format("工厂 %s 共 %d 条价格策略 (过滤后 %d 条)",
                        factoryId, all.size(), filtered.size()),
                Map.of(
                        "factoryId", factoryId,
                        "totalCount", all.size(),
                        "filteredCount", filtered.size(),
                        "strategies", filtered
                )
        );
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
