package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.service.bom.BomRecipeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * M-BOM-1 BOM 克隆 + 选择性修改 (Track D1).
 *
 * 客户场景: "克隆 SKU-201 的 BOM 但减 10% 包材" / "复制红烧肉 v2, 调味料量 +5%"
 *
 * 修改维度:
 *   - 按 materialCategory 整体调整百分比 (e.g. PACKAGING -10%)
 *   - 按具体 materialTypeId 调整百分比
 *   - 不修改 = 纯克隆
 */
@Slf4j
@Component
public class BomRecipeCloneWithModifyTool extends AbstractBusinessTool {

    @Autowired
    private BomRecipeService recipeService;

    @Override
    public String getToolName() {
        return "bom_recipe_clone_with_modify";
    }

    @Override
    public String getDescription() {
        return "克隆 BOM 配方为新版本, 可选择性调整某类物料的用量百分比. "
             + "适用场景: 调试新配方 / 季节性调整 (e.g. '夏季包材 -10%') / 价格调控前的小幅修改.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> sourceRecipeId = new HashMap<>();
        sourceRecipeId.put("type", "string");
        sourceRecipeId.put("description", "源 BOM 配方 ID");

        Map<String, Object> adjustCategory = new HashMap<>();
        adjustCategory.put("type", "string");
        adjustCategory.put("description", "要调整的物料分类: RAW/AUXILIARY/PACKAGING");
        adjustCategory.put("enum", Arrays.asList("RAW", "AUXILIARY", "PACKAGING"));

        Map<String, Object> adjustPercent = new HashMap<>();
        adjustPercent.put("type", "number");
        adjustPercent.put("description", "调整百分比 (-100 ~ +100, 负数减少, 正数增加). 如 -10 表示减少 10%");

        Map<String, Object> properties = new HashMap<>();
        properties.put("sourceRecipeId", sourceRecipeId);
        properties.put("adjustCategory", adjustCategory);
        properties.put("adjustPercent", adjustPercent);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("sourceRecipeId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("sourceRecipeId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String sourceRecipeId = getString(params, "sourceRecipeId");
        String adjustCategory = getString(params, "adjustCategory");
        BigDecimal adjustPercent = getBigDecimal(params, "adjustPercent");

        // 1. Clone
        BomRecipe cloned = recipeService.cloneRecipe(factoryId, sourceRecipeId);
        log.info("BOM 克隆: source={}, cloned={} (v{})",
                sourceRecipeId, cloned.getRecipeCode(), cloned.getVersion());

        // 2. 选择性调整 (可选)
        int modifiedCount = 0;
        if (adjustCategory != null && !adjustCategory.isBlank() && adjustPercent != null) {
            BigDecimal multiplier = BigDecimal.ONE.add(
                    adjustPercent.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));

            BomRecipe full = recipeService.getRecipe(factoryId, cloned.getId());
            for (BomRecipeItem item : full.getItems()) {
                if (adjustCategory.equalsIgnoreCase(item.getMaterialCategory())) {
                    BigDecimal newQty = item.getStandardQuantity()
                            .multiply(multiplier)
                            .setScale(4, RoundingMode.HALF_UP);
                    recipeService.updateItem(factoryId, item.getId(),
                            buildItemDtoFromCurrent(item, newQty));
                    modifiedCount++;
                }
            }
            recipeService.calculateCost(factoryId, cloned.getId());
        }

        BomRecipe finalRecipe = recipeService.getRecipe(factoryId, cloned.getId());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("clonedRecipeCode", finalRecipe.getRecipeCode());
        summary.put("clonedVersion", finalRecipe.getVersion());
        summary.put("sourceRecipeId", sourceRecipeId);
        summary.put("modifiedItemCount", modifiedCount);
        summary.put("status", finalRecipe.getStatus());  // DRAFT
        summary.put("totalCost", finalRecipe.getTotalCost());
        return buildSimpleResult(
                "克隆成功: " + finalRecipe.getRecipeCode()
                + (modifiedCount > 0 ? " (调整了 " + modifiedCount + " 项)" : ""),
                summary);
    }

    /** Convert existing item back to DTO with overridden standardQuantity. */
    private com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO buildItemDtoFromCurrent(
            BomRecipeItem item, BigDecimal newStandardQty) {
        com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO dto =
                new com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO();
        dto.setMaterialTypeId(item.getMaterialTypeId());
        dto.setStandardQuantity(newStandardQty);
        dto.setYieldRate(item.getYieldRate());
        dto.setUnit(item.getUnit());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setTaxRate(item.getTaxRate());
        dto.setMaterialCategory(item.getMaterialCategory());
        dto.setSortOrder(item.getSortOrder());
        dto.setIsOptional(item.getIsOptional());
        dto.setSubstituteGroup(item.getSubstituteGroup());
        dto.setRemark(item.getRemark());
        return dto;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
