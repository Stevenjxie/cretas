package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest;
import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.bom.BomRecipeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M-BOM-1 一句话建 BOM (Track D1, WRITE).
 *
 * 客户场景: "给红烧肉建 BOM, 五花肉 200g 出成率 80%, 老抽 5g, 糖 10g, 单份成品 150g"
 *
 * 当前实现: 用正则解析 "数字 + 单位 + 物料名" pattern + 模糊匹配 raw_material_types.
 * 未来升级: 改 LLM-based extraction (handles 复杂句式 / 多语言 / 上下文消歧).
 *
 * 已知限制:
 *   - 必须显式提供 productTypeId (LLM 升级后可改为 productName 模糊匹配)
 *   - 物料名 fuzzy match: 找不到时报错, 提示用户先创建 raw_material_type
 *   - 出成率 / 损耗 / 包材识别简化, 全归 RAW (用户激活前调整)
 */
@Slf4j
@Component
public class BomRecipeCreateFromTextTool extends AbstractBusinessTool {

    /** 匹配 "200g 五花肉" / "5 克 盐" / "10克糖" 等格式. */
    private static final Pattern ITEM_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(g|kg|mg|ml|L|克|公斤|千克|毫升|升|个|袋|箱|瓶|盒)\\s*([\\u4e00-\\u9fa5A-Za-z]+)",
            Pattern.UNICODE_CHARACTER_CLASS);

    @Autowired
    private BomRecipeService recipeService;

    @Autowired
    private RawMaterialTypeRepository materialTypeRepo;

    @Override
    public String getToolName() {
        return "bom_recipe_create_from_text";
    }

    @Override
    public String getDescription() {
        return "通过自然语言一句话创建 BOM 配方草稿. "
             + "适用场景: '给红烧肉建 BOM, 五花肉 200g 出成率 80%, 老抽 5g, 糖 10g, 单份成品 150g'. "
             + "返回 DRAFT 状态配方, 用户检查后激活.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description", "产品类型 ID (LLM 应根据产品名解析)");

        Map<String, Object> productName = new HashMap<>();
        productName.put("type", "string");
        productName.put("description", "产品名 (e.g. '红烧肉'), 用于 BOM 显示");

        Map<String, Object> ingredientsText = new HashMap<>();
        ingredientsText.put("type", "string");
        ingredientsText.put("description",
                "原料文本, 用逗号或分号分隔. 格式: '物料名 数量+单位'. "
                + "示例: '五花肉 200g, 老抽 5g, 糖 10g'");

        Map<String, Object> outputQuantityPerUnit = new HashMap<>();
        outputQuantityPerUnit.put("type", "number");
        outputQuantityPerUnit.put("description", "单份成品克数/件数 (e.g. 150 表示 150g/份)");
        outputQuantityPerUnit.put("default", 200);

        Map<String, Object> outputUnit = new HashMap<>();
        outputUnit.put("type", "string");
        outputUnit.put("description", "成品计量单位");
        outputUnit.put("default", "g");

        Map<String, Object> overallYieldRate = new HashMap<>();
        overallYieldRate.put("type", "number");
        overallYieldRate.put("description", "整产品出成率 0-100, 默认 100");
        overallYieldRate.put("default", 100);

        Map<String, Object> properties = new HashMap<>();
        properties.put("productTypeId", productTypeId);
        properties.put("productName", productName);
        properties.put("ingredientsText", ingredientsText);
        properties.put("outputQuantityPerUnit", outputQuantityPerUnit);
        properties.put("outputUnit", outputUnit);
        properties.put("overallYieldRate", overallYieldRate);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("productTypeId", "ingredientsText"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("productTypeId", "ingredientsText");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String productTypeId = getString(params, "productTypeId");
        String productName = getString(params, "productName", productTypeId);
        String text = getString(params, "ingredientsText");
        BigDecimal outputQty = getBigDecimal(params, "outputQuantityPerUnit");
        if (outputQty == null) outputQty = new BigDecimal("200");
        String outputUnit = getString(params, "outputUnit", "g");
        BigDecimal yieldRate = getBigDecimal(params, "overallYieldRate");
        if (yieldRate == null) yieldRate = new BigDecimal("100");

        // 1. Parse text into items
        List<ParsedItem> parsed = parseItems(text);
        if (parsed.isEmpty()) {
            return buildSimpleResult(
                    "未从文本中解析出原料, 请检查格式 (e.g. '五花肉 200g, 盐 5g')",
                    Map.of("parsed", Collections.emptyList(), "text", text));
        }

        // 2. Resolve material_type_id by fuzzy name match against raw_material_types
        List<RawMaterialType> allMaterials = materialTypeRepo.findByFactoryId(factoryId);
        Map<String, RawMaterialType> nameMap = new HashMap<>();
        for (RawMaterialType m : allMaterials) {
            if (m.getDeletedAt() == null && m.getName() != null) {
                nameMap.put(m.getName(), m);
            }
        }

        List<BomRecipeItemDTO> items = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (ParsedItem p : parsed) {
            RawMaterialType match = findBestMatch(nameMap, p.materialName);
            if (match == null) {
                unresolved.add(p.materialName);
                continue;
            }
            BomRecipeItemDTO dto = new BomRecipeItemDTO();
            dto.setMaterialTypeId(match.getId());
            dto.setStandardQuantity(p.quantity);
            dto.setYieldRate(new BigDecimal("100"));
            dto.setUnit(normalizeUnit(p.unit));
            dto.setMaterialCategory(p.materialName.contains("包") || p.materialName.contains("袋")
                    ? "PACKAGING" : "RAW");
            dto.setSortOrder(items.size());
            items.add(dto);
        }

        if (!unresolved.isEmpty()) {
            return buildSimpleResult(
                    "以下物料在字典中未找到, 请先创建 raw_material_type: " + unresolved,
                    Map.of("unresolved", unresolved, "parsedCount", parsed.size()));
        }

        // 3. Build & persist
        CreateBomRecipeRequest req = new CreateBomRecipeRequest();
        req.setProductTypeId(productTypeId);
        req.setProductName(productName);
        req.setOverallYieldRate(yieldRate);
        req.setOutputQuantityPerUnit(outputQty);
        req.setOutputUnit(outputUnit);
        req.setSourceType(BomRecipe.SourceType.AI_GENERATED);
        req.setItems(items);
        req.setNotes("AI 一句话建 BOM: " + text);

        BomRecipe created = recipeService.createRecipe(factoryId, req);
        log.info("AI 建 BOM 草稿: factoryId={}, recipe={}, items={}",
                factoryId, created.getRecipeCode(), items.size());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recipeCode", created.getRecipeCode());
        summary.put("recipeId", created.getId());
        summary.put("status", "DRAFT");
        summary.put("itemCount", items.size());
        summary.put("hint", "已生成 DRAFT, 请检查后用 bom_recipe_activate 激活");
        return buildSimpleResult("BOM 草稿创建成功: " + created.getRecipeCode(), summary);
    }

    private List<ParsedItem> parseItems(String text) {
        List<ParsedItem> result = new ArrayList<>();
        if (text == null) return result;
        Matcher m = ITEM_PATTERN.matcher(text);
        while (m.find()) {
            ParsedItem p = new ParsedItem();
            p.quantity = new BigDecimal(m.group(1));
            p.unit = m.group(2);
            p.materialName = m.group(3);
            result.add(p);
        }
        return result;
    }

    /** Exact-match first, then prefix-contains (Chinese 中文 substring). */
    private RawMaterialType findBestMatch(Map<String, RawMaterialType> nameMap, String query) {
        RawMaterialType exact = nameMap.get(query);
        if (exact != null) return exact;
        for (Map.Entry<String, RawMaterialType> e : nameMap.entrySet()) {
            if (e.getKey().contains(query) || query.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 单位 normalize (跟 V20260516_02__bom_redesign.sql 一致). */
    private String normalizeUnit(String raw) {
        if (raw == null) return "g";
        String lower = raw.toLowerCase();
        switch (lower) {
            case "kg": return "kg";
            case "g":  return "g";
            case "mg": return "mg";
            case "ml": return "ml";
            case "l":  return "L";
            default: break;
        }
        switch (raw) {
            case "公斤":
            case "千克": return "kg";
            case "克":   return "g";
            case "毫克": return "mg";
            case "毫升": return "ml";
            case "升":
            case "公升": return "L";
            case "个":
            case "袋":
            case "箱":
            case "瓶":
            case "盒":   return raw;
            default:     return "g";
        }
    }

    private static class ParsedItem {
        BigDecimal quantity;
        String unit;
        String materialName;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
