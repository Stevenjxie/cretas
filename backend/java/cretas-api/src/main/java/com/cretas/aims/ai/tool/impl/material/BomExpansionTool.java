package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.service.orchestration.BomExpansionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BOM展开+库存校验 Tool
 *
 * 将 BomExpansionService 暴露给 AI 意图系统，支持：
 * - 查询产品所需原辅料清单 (BOM展开)
 * - 校验库存是否满足生产需求 (FEFO)
 * - 为排产→调拨流程提供数据支撑
 *
 * 调用示例：
 * "生产1000kg带鱼段需要多少原料？"
 * "检查一下库存够不够生产这批货"
 */
@Slf4j
@Component
public class BomExpansionTool extends AbstractBusinessTool {

    @Autowired
    private BomExpansionService bomExpansionService;

    @Override
    public String getToolName() {
        return "bom_expansion_calculate";
    }

    @Override
    public String getDescription() {
        return "根据产品和数量计算所需原辅料清单（BOM展开），并检查库存是否充足。" +
               "当用户问'生产XX需要多少原料'、'库存够不够'、'物料需求'、'BOM计算'时调用";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "productTypeId", Map.of("type", "string", "description", "产品类型ID"),
                "productName", Map.of("type", "string", "description", "产品名称（用于模糊查找）"),
                "quantity", Map.of("type", "number", "description", "计划生产数量"),
                "checkStock", Map.of("type", "boolean", "description", "是否同时检查库存（默认true）")
            ),
            "required", List.of("quantity")
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("quantity");
    }

    @Override
    public ActionType getActionType() {
        return ActionType.ANALYZE;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("material", "bom", "production");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String productTypeId = getString(params, "productTypeId");
        BigDecimal quantity = getBigDecimal(params, "quantity");
        boolean checkStock = params.containsKey("checkStock") ? Boolean.TRUE.equals(params.get("checkStock")) : true;

        if (productTypeId == null) {
            String productName = getString(params, "productName");
            if (productName != null) {
                // TODO: 通过名称模糊查找 productTypeId
                throw new IllegalArgumentException("请提供产品类型ID（productTypeId），暂不支持名称查找");
            }
            throw new IllegalArgumentException("请提供产品类型ID或产品名称");
        }

        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("请提供有效的生产数量");
        }

        // Step 1: BOM展开
        List<MaterialRequirement> requirements = bomExpansionService.expandBOM(factoryId, productTypeId, quantity);

        if (requirements.isEmpty()) {
            return buildSimpleResult("该产品无BOM配置", Map.of(
                "productTypeId", productTypeId,
                "quantity", quantity,
                "message", "未找到原辅料转换配置，请先在Web端配置产品的BOM"
            ));
        }

        // 构建物料需求列表
        List<Map<String, Object>> materialList = requirements.stream().map(req -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("materialTypeId", req.getMaterialTypeId());
            m.put("materialName", req.getMaterialTypeName());
            m.put("requiredQuantity", req.getRequiredQuantity());
            m.put("wastageRate", req.getWastageRate());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productTypeId", productTypeId);
        result.put("productionQuantity", quantity);
        result.put("materialCount", requirements.size());
        result.put("materials", materialList);

        // Step 2: 库存校验 (可选)
        if (checkStock) {
            MaterialCheckResult check = bomExpansionService.checkMaterialAvailability(factoryId, requirements);
            result.put("stockCheck", Map.of(
                "allSatisfied", check.isAllSatisfied(),
                "shortfallCount", check.getShortfalls() != null ? check.getShortfalls().size() : 0,
                "allocations", check.getAllocations() != null ? check.getAllocations().size() : 0
            ));

            if (!check.isAllSatisfied() && check.getShortfalls() != null) {
                result.put("shortfalls", check.getShortfalls().stream().map(s -> Map.of(
                    "materialTypeId", s.getMaterialTypeId(),
                    "materialName", s.getMaterialTypeName(),
                    "required", s.getRequiredQuantity(),
                    "available", s.getAvailableQuantity(),
                    "shortfall", s.getShortfallQuantity()
                )).collect(Collectors.toList()));
            }
        }

        boolean stockOk = true;
        if (checkStock && result.get("stockCheck") instanceof Map<?,?> sc) {
            stockOk = Boolean.TRUE.equals(sc.get("allSatisfied"));
        }
        String summary = String.format("生产 %s %s 需要 %d 种原辅料%s",
            quantity, productTypeId, requirements.size(),
            checkStock ? (stockOk ? "，库存充足" : "，部分库存不足") : "");

        return buildSimpleResult(summary, result);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        return switch (paramName) {
            case "productTypeId" -> "请问要计算哪个产品的物料需求？请提供产品ID";
            case "quantity" -> "计划生产多少数量？";
            default -> super.getParameterQuestion(paramName);
        };
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
