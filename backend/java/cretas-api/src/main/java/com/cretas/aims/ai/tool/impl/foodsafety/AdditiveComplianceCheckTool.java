package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.foodsafety.AdditiveLimit;
import com.cretas.aims.repository.foodsafety.AdditiveLimitRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GB 2760-2014 添加剂合规校验 Tool — Sprint 8 P3 Phase B (食品召回闭环 step 4).
 *
 * <p>对指定批次 (或直接传 ingredients list) 跑添加剂合规校验. 查 ingredients 中每个 additive
 * 在指定食品类目下的 max_limit, 跟实际使用量比较, 标记超限项.
 *
 * <p>核心查询: {@link AdditiveLimitRepository#findByAdditiveCodeAndFoodCategoryAndActiveTrue}.
 *
 * <p>食品类目 (foodCategory) 参考 GB 2760 类目编号, e.g. "08.02 熟肉制品".
 *
 * <p>Phase B 简化版: 接收前端传 ingredients list (additive_code + usage_amount),
 * 不直接查 batch BOM (未来 Phase C 接 ProcessingBatchService 自动展开).
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"B-X 添加剂超限了吗"</li>
 *   <li>"这批添加剂合规吗"</li>
 *   <li>"X 产品 GB 2760 合规"</li>
 *   <li>"亚硝酸盐有没有超"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code ADDITIVE_CHECK}
 */
@Slf4j
@Component
public class AdditiveComplianceCheckTool extends AbstractBusinessTool {

    /** 默认食品类目 — 卤味 / 熟肉制品 (GB 2760-2014 08.02). */
    private static final String DEFAULT_CATEGORY = "08.02 熟肉制品";

    @Autowired
    private AdditiveLimitRepository additiveLimitRepository;

    /**
     * Sprint 9 P2.F V2 — smart match fallback (when strict additiveCode lookup misses,
     * 自动用 fuzzy match 找最接近的 GB 2760 entry).
     */
    @Autowired(required = false)
    private AdditiveSmartMatchTool smartMatchTool;

    @Override
    public String getToolName() {
        return "additive_compliance_check";
    }

    @Override
    public String getDescription() {
        return "GB 2760-2014 食品添加剂合规校验 — 查指定批次 ingredients 的添加剂限量, 标记超限项 + "
                + "提供合规标签建议. LLM 触发: 'B-X 添加剂超限了吗' / '这批添加剂合规吗' / "
                + "'X 产品 GB 2760 合规' / '亚硝酸盐有没有超'. read-only. "
                + "默认食品类目 '08.02 熟肉制品' (卤味), 可指定 productCategory 切换.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号, 标识用 (必填). e.g. B-20260518-A03");
        properties.put("batchNumber", batchNumber);

        Map<String, Object> productCategory = new HashMap<>();
        productCategory.put("type", "string");
        productCategory.put("description",
                "食品类目 (GB 2760), 默认 '08.02 熟肉制品'. e.g. '08.03 发酵肉制品' / '04.01 新鲜水果'");
        properties.put("productCategory", productCategory);

        // Ingredients format: [{"additiveCode": "INS 250", "usageAmount": 50, "unit": "mg/kg"}, ...]
        Map<String, Object> ingredients = new HashMap<>();
        ingredients.put("type", "array");
        ingredients.put("description",
                "ingredients 列表 (可选). 每项 {additiveCode, usageAmount, unit}. " +
                "缺省时返回该 productCategory 的限量参考表 (营养标签草稿模式)");
        properties.put("ingredients", ingredients);

        schema.put("properties", properties);
        schema.put("required", List.of("batchNumber"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        String foodCategory = getString(params, "productCategory", DEFAULT_CATEGORY);
        List<Map<String, Object>> ingredients = getList(params, "ingredients");

        log.info("additive_compliance_check — factory={} batch={} category={} ingredientsCount={}",
                factoryId, batchNumber, foodCategory, ingredients != null ? ingredients.size() : 0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNumber", batchNumber);
        data.put("foodCategory", foodCategory);

        // Mode 1: 无 ingredients → 返回该类目限量参考表 (营养标签草稿)
        if (ingredients == null || ingredients.isEmpty()) {
            List<AdditiveLimit> categoryLimits = additiveLimitRepository
                    .findByFoodCategoryAndActiveTrue(foodCategory);
            List<Map<String, Object>> reference = new ArrayList<>();
            for (AdditiveLimit l : categoryLimits) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("additiveCode", l.getAdditiveCode());
                row.put("additiveName", l.getAdditiveName());
                row.put("maxLimit", l.getMaxLimit());
                row.put("unit", l.getUnit());
                row.put("regulationRef", l.getRegulationRef());
                reference.add(row);
            }
            data.put("mode", "REFERENCE");
            data.put("referenceCount", reference.size());
            data.put("reference", reference);
            data.put("actionHint", "/quality/additive-compliance?category=" + foodCategory);
            return buildSimpleResult(String.format(
                    "📚 食品类目 '%s' GB 2760 限量参考: 共 %d 项添加剂. 详见 reference 字段",
                    foodCategory, reference.size()), data);
        }

        // Mode 2: 有 ingredients → 逐项校验, 标记超限
        List<Map<String, Object>> compliantList = new ArrayList<>();
        List<Map<String, Object>> exceededList = new ArrayList<>();
        List<Map<String, Object>> unknownList = new ArrayList<>();

        for (Map<String, Object> ing : ingredients) {
            String additiveCode = ing.get("additiveCode") != null
                    ? ing.get("additiveCode").toString() : null;
            BigDecimal usageAmount = toBigDecimal(ing.get("usageAmount"));
            String unit = ing.get("unit") != null ? ing.get("unit").toString() : null;

            if (additiveCode == null || usageAmount == null) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("additiveCode", additiveCode);
                u.put("usageAmount", usageAmount);
                u.put("reason", "additiveCode 或 usageAmount 缺失");
                unknownList.add(u);
                continue;
            }

            Optional<AdditiveLimit> limitOpt = additiveLimitRepository
                    .findByAdditiveCodeAndFoodCategoryAndActiveTrue(additiveCode, foodCategory);

            // Sprint 9 P2.F V2 fallback: strict lookup miss → 尝试 fuzzy match
            // (用户用俗名 / 英文输入 additiveCode 时, 自动找到 GB 2760 entry)
            boolean fuzzyMatched = false;
            if (limitOpt.isEmpty()) {
                AdditiveLimit fuzzy = trySmartFallback(additiveCode, foodCategory);
                if (fuzzy != null) {
                    limitOpt = Optional.of(fuzzy);
                    fuzzyMatched = true;
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("additiveCode", additiveCode);
            row.put("usageAmount", usageAmount);
            row.put("usageUnit", unit);
            if (fuzzyMatched) {
                row.put("smartMatched", true);
                row.put("matchedCode", limitOpt.get().getAdditiveCode());
            }

            if (limitOpt.isEmpty()) {
                row.put("reason", "GB 2760 未收录 (" + foodCategory + "). 建议用 additive_smart_match 查找类似");
                unknownList.add(row);
                continue;
            }

            AdditiveLimit limit = limitOpt.get();
            row.put("additiveName", limit.getAdditiveName());
            row.put("maxLimit", limit.getMaxLimit());
            row.put("limitUnit", limit.getUnit());
            row.put("regulationRef", limit.getRegulationRef());

            // maxLimit = 0 表示按需适量, 不限量, 一律合规
            if (limit.getMaxLimit() == null
                    || limit.getMaxLimit().compareTo(BigDecimal.ZERO) == 0) {
                row.put("status", "COMPLIANT_UNRESTRICTED");
                compliantList.add(row);
            } else if (usageAmount.compareTo(limit.getMaxLimit()) > 0) {
                row.put("status", "EXCEEDED");
                row.put("excessAmount", usageAmount.subtract(limit.getMaxLimit()));
                exceededList.add(row);
            } else {
                row.put("status", "COMPLIANT");
                compliantList.add(row);
            }
        }

        data.put("mode", "VALIDATION");
        data.put("totalChecked", ingredients.size());
        data.put("compliantCount", compliantList.size());
        data.put("exceededCount", exceededList.size());
        data.put("unknownCount", unknownList.size());
        data.put("compliant", compliantList);
        data.put("exceeded", exceededList);
        data.put("unknown", unknownList);
        data.put("passed", exceededList.isEmpty());
        data.put("actionHint",
                "/quality/additive-compliance?batchNumber=" + batchNumber
                        + "&category=" + foodCategory);

        String message;
        if (!exceededList.isEmpty()) {
            message = String.format(
                    "🚨 批次 %s 添加剂校验 不合规: %d 项超 GB 2760 限量 (其中 %d 项合规, %d 项未收录). 详见 exceeded 字段",
                    batchNumber, exceededList.size(), compliantList.size(), unknownList.size());
        } else if (!unknownList.isEmpty()) {
            message = String.format(
                    "⚠️ 批次 %s 添加剂校验: %d 项合规, %d 项 GB 2760 未收录需人工核对",
                    batchNumber, compliantList.size(), unknownList.size());
        } else {
            message = String.format(
                    "✅ 批次 %s 添加剂校验全部 %d 项合规 (GB 2760-2014 %s)",
                    batchNumber, compliantList.size(), foodCategory);
        }
        return buildSimpleResult(message, data);
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Sprint 9 P2.F V2 — fuzzy fallback when strict additiveCode/category lookup misses.
     *
     * <p>Strategies (在指定 foodCategory 内优先):
     * <ol>
     *   <li>精确 additiveCode 跨类目查找 (用户传 INS code 但 category 配错)</li>
     *   <li>精确 additiveName 匹配 (用户用 name 而非 INS code) — 限定 category</li>
     *   <li>aliases JSONB 匹配 (用户用俗名 / 英文 — e.g. "VC" → INS 300) — 限定 category</li>
     * </ol>
     *
     * <p>返 null 表示真的未收录 (调用方走 unknown 分支).
     */
    private AdditiveLimit trySmartFallback(String query, String foodCategory) {
        if (query == null || query.isBlank()) return null;
        try {
            // 1. 精确 code 跨类目 (用户给对 INS code 但 category 配错)
            List<AdditiveLimit> byCode = additiveLimitRepository.findByAdditiveCodeAndActiveTrue(query);
            for (AdditiveLimit l : byCode) {
                if (l.getFoodCategory().equals(foodCategory)) return l;
            }
            // 同 code 但不同 category — 返第一个 (作为参考)
            if (!byCode.isEmpty()) return byCode.get(0);

            // 2. 精确 name (限定 category)
            List<AdditiveLimit> byName = additiveLimitRepository.findByAdditiveNameAndActiveTrue(query);
            for (AdditiveLimit l : byName) {
                if (l.getFoodCategory().equals(foodCategory)) return l;
            }

            // 3. aliases (限定 category)
            List<AdditiveLimit> byAlias = additiveLimitRepository.findByAliasContaining(query);
            for (AdditiveLimit l : byAlias) {
                if (l.getFoodCategory().equals(foodCategory)) return l;
            }

            return null;
        } catch (Exception e) {
            log.warn("trySmartFallback failed for query={}: {}", query, e.getMessage());
            return null;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
