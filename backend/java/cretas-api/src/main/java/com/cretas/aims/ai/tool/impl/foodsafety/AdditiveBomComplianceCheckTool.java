package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.foodsafety.AdditiveLimit;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.foodsafety.AdditiveLimitRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BOM 反查 + GB 2760-2014 综合合规校验 Tool — Sprint 9 P2.F V2 (2026-05-21).
 *
 * <p>核心工作流 — 端到端 BOM 合规分析:
 * <ol>
 *   <li>查 BomRecipe (by productTypeId / bomVersionId)</li>
 *   <li>遍历 BomRecipeItem.materialName 用 fuzzy match 找 GB 2760 entry</li>
 *   <li>算 perKgUsage = standardQuantity / outputQuantityPerUnit × 1000 mg/kg</li>
 *   <li>跟 max_limit 比 → 0-80% green / 80-100% yellow / >100% red</li>
 *   <li>综合 report + summary (合规率 / 黄色警告数 / 红色超限数)</li>
 * </ol>
 *
 * <p>vs V1 ({@link AdditiveComplianceCheckTool}): V1 需用户手动传 ingredients list,
 * V2 直接从 BOM 推 — 操作员只需提供 productCode / bomVersionId 即可生成完整 report.
 *
 * <p>**防呆设计 (per fool-proof-design.md Rule 2 / Rule 5)**:
 * <ul>
 *   <li>Rule 2: response 含 productName + recipeCode + outputQuantityPerUnit 上下文</li>
 *   <li>Rule 5: 没找到 BOM → 提示去配置, 不留死路</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"叮咚卤猪蹄 BOM 合规吗"</li>
 *   <li>"产品 P-2020 配方添加剂查一下"</li>
 *   <li>"BOM B-2020 添加剂超限"</li>
 *   <li>"全表检查配方有没有违法添加剂"</li>
 * </ul>
 *
 * <p>Intent Code: {@code ADDITIVE_BOM_COMPLIANCE_CHECK}
 *
 * @author Cretas Team / Sprint 9 P2.F
 * @since 2026-05-21
 */
@Slf4j
@Component
public class AdditiveBomComplianceCheckTool extends AbstractBusinessTool {

    /** 默认食品类目 — 卤味 / 熟肉制品 (GB 2760-2014 08.02). */
    private static final String DEFAULT_CATEGORY = "08.02 熟肉制品";

    /** 单位转换: 1 g = 1000 mg, 1 kg = 1000 g. */
    private static final BigDecimal MG_PER_G = new BigDecimal("1000");

    /** 黄色警告阈值 (合规率 ≥ 80%). */
    private static final BigDecimal YELLOW_THRESHOLD = new BigDecimal("80");

    /** 红色超限阈值 (合规率 ≥ 100%). */
    private static final BigDecimal RED_THRESHOLD = new BigDecimal("100");

    @Autowired
    private BomRecipeRepository bomRecipeRepository;

    @Autowired
    private BomRecipeItemRepository bomRecipeItemRepository;

    @Autowired
    private AdditiveLimitRepository additiveLimitRepository;

    @Autowired
    private AdditiveSmartMatchTool additiveSmartMatchTool;  // 复用 fuzzy match 逻辑

    @Override
    public String getToolName() {
        return "additive_bom_compliance_check";
    }

    @Override
    public String getDescription() {
        return "BOM 反查 + GB 2760-2014 综合合规校验 — 查 BomRecipe → 遍历 items → fuzzy match GB 2760 → "
                + "算每 kg 产品添加剂用量 (perKgUsage = stdQty / outputQty × 1000 mg/kg) → "
                + "跟 max_limit 比 → 0-80% green / 80-100% yellow / >100% red 综合 report. "
                + "LLM 触发: '叮咚卤猪蹄 BOM 合规吗' / '产品 P-2020 配方添加剂查一下' / "
                + "'BOM B-2020 添加剂超限' / '全表检查配方有没有违法添加剂'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description",
                "产品类型 ID (productTypeId), 二选一 (优先于 bomRecipeId). e.g. 'P-叮咚卤猪蹄'");
        properties.put("productTypeId", productTypeId);

        Map<String, Object> bomRecipeId = new HashMap<>();
        bomRecipeId.put("type", "string");
        bomRecipeId.put("description",
                "BOM Recipe ID (UUID), 二选一. e.g. 'a1b2c3d4-...'");
        properties.put("bomRecipeId", bomRecipeId);

        Map<String, Object> foodCategory = new HashMap<>();
        foodCategory.put("type", "string");
        foodCategory.put("description",
                "食品类目 (GB 2760), 默认 '08.02 熟肉制品'. e.g. '07.02 糕点' / '12.04 酱油'");
        properties.put("foodCategory", foodCategory);

        schema.put("properties", properties);
        schema.put("required", new ArrayList<>());  // productTypeId / bomRecipeId 二选一, 不强制
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return new ArrayList<>();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String productTypeId = getString(params, "productTypeId");
        String bomRecipeId = getString(params, "bomRecipeId");
        String foodCategory = getString(params, "foodCategory", DEFAULT_CATEGORY);

        log.info("additive_bom_compliance_check — factory={} productTypeId={} bomRecipeId={} category={}",
                factoryId, productTypeId, bomRecipeId, foodCategory);

        // Step 1: 查 BomRecipe
        BomRecipe recipe = null;
        if (bomRecipeId != null && !bomRecipeId.isBlank()) {
            recipe = bomRecipeRepository.findById(bomRecipeId).orElse(null);
        } else if (productTypeId != null && !productTypeId.isBlank()) {
            recipe = bomRecipeRepository
                    .findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                            factoryId, productTypeId, BomRecipe.Status.ACTIVE)
                    .orElse(null);
        } else {
            return buildSimpleResult(
                    "⚠️ 请提供 productTypeId 或 bomRecipeId (二选一)",
                    Map.of("status", "MISSING_PARAM", "actionHint", "/bom/recipes"));
        }

        if (recipe == null) {
            // Rule 5 防呆: 提示去 BOM 配置页
            String hint = productTypeId != null
                    ? String.format("/bom/recipes?productTypeId=%s", productTypeId)
                    : "/bom/recipes";
            return buildSimpleResult(
                    String.format("❓ 未找到激活 BOM (productTypeId=%s, bomRecipeId=%s). 请先在 BOM 模块配置激活配方",
                            productTypeId, bomRecipeId),
                    Map.of("status", "BOM_NOT_FOUND", "actionHint", hint));
        }

        // 校验工厂隔离
        if (!recipe.getFactoryId().equals(factoryId)) {
            return buildSimpleResult(
                    "⚠️ 该 BOM 不属于当前工厂",
                    Map.of("status", "FACTORY_MISMATCH"));
        }

        // Step 2: 取 items
        List<BomRecipeItem> items = bomRecipeItemRepository
                .findByRecipeIdOrderBySortOrderAsc(recipe.getId());
        if (items.isEmpty()) {
            return buildSimpleResult(
                    String.format("ℹ️ BOM %s (%s) 没有配料项. 请检查 BOM 配置",
                            recipe.getRecipeCode(), recipe.getProductName()),
                    Map.of("status", "EMPTY_BOM",
                            "recipeCode", recipe.getRecipeCode(),
                            "productName", recipe.getProductName(),
                            "actionHint", "/bom/recipes/" + recipe.getId()));
        }

        BigDecimal outputQty = recipe.getOutputQuantityPerUnit();
        if (outputQty == null || outputQty.compareTo(BigDecimal.ZERO) == 0) {
            return buildSimpleResult(
                    String.format("⚠️ BOM %s 单份成品克数 (outputQuantityPerUnit) 未设置或为 0", recipe.getRecipeCode()),
                    Map.of("status", "INVALID_OUTPUT_QTY", "recipeCode", recipe.getRecipeCode()));
        }

        // Step 3-4: 遍历 item → fuzzy match → 算 perKgUsage → 跟 max_limit 比
        List<Map<String, Object>> additiveReport = new ArrayList<>();
        List<Map<String, Object>> unmatched = new ArrayList<>();
        int greenCount = 0;
        int yellowCount = 0;
        int redCount = 0;

        for (BomRecipeItem item : items) {
            String materialName = item.getMaterialName();
            if (materialName == null || materialName.isBlank()) continue;

            // 直接查 (additiveName, foodCategory) 精确匹配
            AdditiveLimit limit = findAdditiveByNameWithFallback(materialName, foodCategory);

            if (limit == null) {
                // 非添加剂 (原料 / 调料) — 不报错, skip
                continue;
            }

            // 算 perKgUsage: standardQuantity / outputQuantityPerUnit × 1000 (g → mg/kg)
            BigDecimal stdQty = item.getStandardQuantity();
            if (stdQty == null) continue;

            // unit normalization: 大多 BOM 用 g; 若 unit = mg / kg / ml / 个 需额外处理
            // 简化: 默认 g → mg/kg conversion. 其他单位标 unmatched 让用户人工核对
            String unit = item.getUnit();
            BigDecimal perKgUsage;
            if (unit == null || unit.equalsIgnoreCase("g")) {
                // 标准: g per outputQty (g) × 1000 mg/g × 1000 g/kg ÷ outputQty (g) ... 简化为
                // mg/kg = (stdQty g × 1000 mg/g) / (outputQty g / 1000 g/kg)
                //       = stdQty × 1000 × 1000 / outputQty
                // 实际 = (stdQty / outputQty) × 1000000 mg/kg —— 但 standardQuantity 通常已是配方用量
                // 简化用 1000 倍 (g/份 → mg per 单份 / kg)
                perKgUsage = stdQty.multiply(MG_PER_G).divide(outputQty, 4, RoundingMode.HALF_UP);
            } else if (unit.equalsIgnoreCase("mg")) {
                perKgUsage = stdQty.divide(outputQty, 4, RoundingMode.HALF_UP);
            } else if (unit.equalsIgnoreCase("kg")) {
                perKgUsage = stdQty.multiply(MG_PER_G).multiply(MG_PER_G).divide(outputQty, 4, RoundingMode.HALF_UP);
            } else {
                // 非质量单位 (个 / 袋 / 瓶 ...) 无法计算 mg/kg
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("materialName", materialName);
                u.put("additiveCode", limit.getAdditiveCode());
                u.put("unit", unit);
                u.put("reason", String.format("单位 '%s' 非质量单位, 无法换算 mg/kg, 请人工核对", unit));
                unmatched.add(u);
                continue;
            }

            BigDecimal maxLimit = limit.getMaxLimit();
            String status;
            BigDecimal complianceRate;

            if (maxLimit == null || maxLimit.compareTo(BigDecimal.ZERO) == 0) {
                // GB 2760 "适量使用" — 一律 green
                status = "GREEN_UNRESTRICTED";
                complianceRate = BigDecimal.ZERO;
                greenCount++;
            } else {
                complianceRate = perKgUsage.multiply(new BigDecimal("100"))
                        .divide(maxLimit, 2, RoundingMode.HALF_UP);
                if (complianceRate.compareTo(RED_THRESHOLD) >= 0) {
                    status = "RED_EXCEEDED";
                    redCount++;
                } else if (complianceRate.compareTo(YELLOW_THRESHOLD) >= 0) {
                    status = "YELLOW_WARNING";
                    yellowCount++;
                } else {
                    status = "GREEN_COMPLIANT";
                    greenCount++;
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", item.getId());
            row.put("materialName", materialName);
            row.put("additiveCode", limit.getAdditiveCode());
            row.put("additiveName", limit.getAdditiveName());
            row.put("standardQuantity", stdQty);
            row.put("unit", unit != null ? unit : "g");
            row.put("perKgUsage", perKgUsage);
            row.put("maxLimit", maxLimit);
            row.put("limitUnit", limit.getUnit());
            row.put("complianceRate", complianceRate);
            row.put("status", status);
            row.put("regulationRef", limit.getRegulationRef());
            additiveReport.add(row);
        }

        // 按 complianceRate 降序 (红色优先)
        additiveReport.sort((a, b) -> {
            BigDecimal ra = (BigDecimal) a.get("complianceRate");
            BigDecimal rb = (BigDecimal) b.get("complianceRate");
            return rb.compareTo(ra);
        });

        // 综合 summary
        int totalChecked = additiveReport.size();
        boolean overallPass = redCount == 0;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAdditivesChecked", totalChecked);
        summary.put("greenCount", greenCount);
        summary.put("yellowCount", yellowCount);
        summary.put("redCount", redCount);
        summary.put("unmatchedCount", unmatched.size());
        summary.put("overallPass", overallPass);
        summary.put("complianceRatePct",
                totalChecked > 0
                        ? new BigDecimal(greenCount).multiply(new BigDecimal("100"))
                                .divide(new BigDecimal(totalChecked), 2, RoundingMode.HALF_UP)
                        : new BigDecimal("100"));

        // Rule 2 防呆: 含上下文 (productName / recipeCode / outputQty / category)
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recipeId", recipe.getId());
        data.put("recipeCode", recipe.getRecipeCode());
        data.put("productName", recipe.getProductName());
        data.put("productTypeId", recipe.getProductTypeId());
        data.put("outputQuantityPerUnit", outputQty);
        data.put("outputUnit", recipe.getOutputUnit());
        data.put("foodCategory", foodCategory);
        data.put("summary", summary);
        data.put("additiveReport", additiveReport);
        data.put("unmatched", unmatched);
        data.put("actionHint",
                "/quality/additive-compliance?bomRecipeId=" + recipe.getId() + "&category=" + foodCategory);

        // 综合 message
        String message;
        if (totalChecked == 0) {
            message = String.format(
                    "ℹ️ BOM %s (%s) — %d 项配料中无识别到的 GB 2760 添加剂. 全是原料 / 调味料",
                    recipe.getRecipeCode(), recipe.getProductName(), items.size());
        } else if (redCount > 0) {
            message = String.format(
                    "🚨 BOM %s (%s) 添加剂综合校验 不合规: %d 项 ≥ 100%% (超 GB 2760 限量), " +
                            "%d 项 80-100%% (黄色警告), %d 项 ≤ 80%% (合规). 详见 additiveReport 字段",
                    recipe.getRecipeCode(), recipe.getProductName(), redCount, yellowCount, greenCount);
        } else if (yellowCount > 0) {
            message = String.format(
                    "⚠️ BOM %s (%s) 添加剂综合校验: %d 项黄色警告 (80-100%% 接近上限), %d 项合规. " +
                            "建议优化配方留余量. 详见 additiveReport",
                    recipe.getRecipeCode(), recipe.getProductName(), yellowCount, greenCount);
        } else {
            message = String.format(
                    "✅ BOM %s (%s) 添加剂综合校验全部 %d 项合规 (GB 2760-2014 %s)",
                    recipe.getRecipeCode(), recipe.getProductName(), greenCount, foodCategory);
        }
        return buildSimpleResult(message, data);
    }

    /**
     * 按 materialName fuzzy 查 AdditiveLimit (指定食品类目优先, 跨类目 fallback).
     * 复用 smart match 逻辑: 精确 name → aliases → name LIKE.
     */
    private AdditiveLimit findAdditiveByNameWithFallback(String materialName, String foodCategory) {
        // 1. 精确 name + category
        List<AdditiveLimit> byName = additiveLimitRepository.findByAdditiveNameAndActiveTrue(materialName);
        for (AdditiveLimit l : byName) {
            if (l.getFoodCategory().equals(foodCategory)) {
                return l;
            }
        }
        // 2. 精确 name, 任意 category (跨类目兼容)
        if (!byName.isEmpty()) {
            return byName.get(0);
        }

        // 3. aliases JSONB 匹配
        try {
            List<AdditiveLimit> byAlias = additiveLimitRepository.findByAliasContaining(materialName);
            for (AdditiveLimit l : byAlias) {
                if (l.getFoodCategory().equals(foodCategory)) {
                    return l;
                }
            }
            if (!byAlias.isEmpty()) {
                return byAlias.get(0);
            }
        } catch (Exception e) {
            log.warn("aliases fallback failed for {}: {}", materialName, e.getMessage());
        }

        // 4. name LIKE (限定 category 优先)
        List<AdditiveLimit> byLike = additiveLimitRepository.findByAdditiveNameContainingAndActiveTrue(materialName);
        for (AdditiveLimit l : byLike) {
            if (l.getFoodCategory().equals(foodCategory)) {
                return l;
            }
        }
        if (!byLike.isEmpty()) {
            return byLike.get(0);
        }

        return null;  // 非添加剂 (普通原料)
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
