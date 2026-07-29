package com.cretas.aims.ai.tool.impl.sample;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.rd.ProductSample;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.rd.ProductSampleService;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 样品 → BOM 草稿生成工具 — Sprint 2 / Track F (S-RD-1 / N48).
 *
 * <p>客户场景: 研发员审完样品后, 一句话生成 BOM 草稿,
 * 例: "给样品 SP-2026-001 建 BOM 类似 SKU-201 但减 10% 包材".
 *
 * <p>编排:
 * <ol>
 *   <li>查 ProductSample (factoryId + sampleId)</li>
 *   <li>构建 system + user prompt (含 sample 元数据 + referenceSku + adjustments + 工厂物料字典片段)</li>
 *   <li>调 DashScopeClient.chatLowTemp 生成 JSON</li>
 *   <li>解析 + 校验: materialName 必须能在 raw_material_types 字典找到 (case-insensitive name match);
 *       LLM 给的不在字典的物料过滤掉, 客户端 prompt 提示 "X 个物料未识别, 已过滤"</li>
 *   <li>返回 BOM 草稿 JSON + displayHint=bom-draft-card 让前端渲染编辑卡</li>
 * </ol>
 *
 * <p>注意: 此 Tool 不直接写 BomRecipe — 它是 BOM 草稿生成器, 用户在前端编辑 + 确认后,
 * 再调 bom_recipe_create Tool 写库. (跟 brief §4 Day 3 提到的 "sample-to-bom-create" Skill
 * 编排意图一致, 但 Skill 编排 Day 5 可加; MVP 只 ship 草稿生成工具.)
 *
 * @author Cretas Team / Track F (Sprint 2)
 * @since 2026-05-15
 */
@Slf4j
@Component
public class SampleToBomTool extends AbstractBusinessTool {

    @Autowired
    private ProductSampleService productSampleService;

    @Autowired
    private DashScopeClient dashScopeClient;

    @Autowired
    private RawMaterialTypeRepository materialTypeRepository;

    @Override
    public String getToolName() {
        return "sample_to_bom";
    }

    @Override
    public String getDescription() {
        return "根据已存在的研发样品 (ProductSample) 生成 BOM 草稿. " +
                "可选参考 SKU + 自然语言调整 (例 '减 10% 包材' / '主原料换成低价').  " +
                "返回 JSON 草稿, 用户在前端编辑确认后再调 bom_recipe_create 写库.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> sampleId = new HashMap<>();
        sampleId.put("type", "string");
        sampleId.put("description", "ProductSample.id (UUID). 必填.");
        properties.put("sampleId", sampleId);

        Map<String, Object> referenceSku = new HashMap<>();
        referenceSku.put("type", "string");
        referenceSku.put("description", "参考产品 SKU 字符串 (可选). LLM 用作 context 推 BOM 配比, 不实际查 BOM.");
        properties.put("referenceSku", referenceSku);

        Map<String, Object> adjustments = new HashMap<>();
        adjustments.put("type", "string");
        adjustments.put("description", "自然语言调整说明 (可选). 例: '减 10% 包材' / '主原料换成低价牛肉'.");
        properties.put("adjustments", adjustments);

        schema.put("properties", properties);
        schema.put("required", List.of("sampleId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("sampleId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        String sampleId = getString(params, "sampleId");
        String referenceSku = getString(params, "referenceSku");
        String adjustments = getString(params, "adjustments");

        log.info("SampleToBomTool execute: factoryId={}, sampleId={}, referenceSku={}, adjustments={}",
                factoryId, sampleId, referenceSku, adjustments);

        // 1. 取 sample (factory-scoped, throws if not found / not owned by factory)
        ProductSample sample = productSampleService.getSample(factoryId, sampleId);

        // 2. 取工厂物料字典 (用于 LLM prompt 上下文 + 后续校验)
        //    字典通常 <500 records, 一次性拉 OK. 不存字典的物料 LLM 输出会被过滤.
        List<RawMaterialType> dictionary = materialTypeRepository.findByFactoryIdAndIsActive(factoryId, true);
        Map<String, RawMaterialType> dictByNameLower = dictionary.stream()
                .collect(Collectors.toMap(m -> m.getName().toLowerCase(), m -> m, (a, b) -> a));

        // 3. 构建 prompt + 调 LLM
        String systemPrompt = buildSystemPrompt(dictionary);
        String userPrompt = buildUserPrompt(sample, referenceSku, adjustments);
        String llmResponse = dashScopeClient.chatLowTemp(systemPrompt, userPrompt);

        // 4. 解析 LLM JSON
        Map<String, Object> parsed;
        try {
            parsed = parseLlmJson(llmResponse);
        } catch (Exception e) {
            log.error("SampleToBomTool LLM 输出解析失败: response={}", llmResponse, e);
            throw new RuntimeException("AI 生成 BOM 失败 — LLM 输出格式错误, 请重试或换个表达", e);
        }

        // 5. 校验 + 过滤 items (materialId 必须在工厂字典)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems = (List<Map<String, Object>>) parsed.getOrDefault("items", List.of());
        List<Map<String, Object>> validItems = new ArrayList<>();
        List<String> filteredMaterials = new ArrayList<>();
        for (Map<String, Object> item : rawItems) {
            String materialName = (String) item.get("materialName");
            if (materialName == null || materialName.isBlank()) {
                filteredMaterials.add("(空名称)");
                continue;
            }
            RawMaterialType match = dictByNameLower.get(materialName.toLowerCase());
            if (match == null) {
                filteredMaterials.add(materialName);
                continue;
            }
            // 回填字典里的 materialId 让前端可直接写 BOM (避免再查一次)
            item.put("materialId", match.getId());
            item.put("materialName", match.getName());  // 字典 canonical 大小写
            validItems.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("sampleId", sampleId);
        result.put("sampleCode", sample.getSampleCode());
        result.put("sampleName", sample.getName());
        result.put("bomDraft", validItems);
        result.put("totalWeight", parsed.get("totalWeight"));
        result.put("explanation", parsed.get("explanation"));
        result.put("warnings", parsed.getOrDefault("warnings", List.of()));
        result.put("filteredMaterials", filteredMaterials);  // 让前端提示用户哪些被过滤
        result.put("displayHint", "bom-draft-card");
        return result;
    }

    // ==================== Prompt building ====================

    private String buildSystemPrompt(List<RawMaterialType> dictionary) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Cretas 食品溯源系统的 BOM 配方设计师。根据样品描述 + 可选参考 SKU + 可选调整说明,").append('\n');
        sb.append("生成 BOM 草稿 JSON。\n\n");

        sb.append("【工厂物料字典 (").append(dictionary.size()).append(" 条, 你只能用字典里的物料名)】:\n");
        // 列出前 N 个物料让 LLM 知道字典里有什么; 超过 N 截断防 token 爆炸
        int maxList = 80;
        for (int i = 0; i < Math.min(maxList, dictionary.size()); i++) {
            RawMaterialType m = dictionary.get(i);
            sb.append("- ").append(m.getName());
            if (m.getCategory() != null) sb.append(" [").append(m.getCategory()).append("]");
            sb.append('\n');
        }
        if (dictionary.size() > maxList) {
            sb.append("- ... (还有 ").append(dictionary.size() - maxList).append(" 条未列出)\n");
        }
        sb.append('\n');

        sb.append("【输出格式】严格 JSON, 不要 markdown 代码块, 不要任何解释文字:\n");
        sb.append("{\n");
        sb.append("  \"items\": [\n");
        sb.append("    {\"materialName\":\"<物料名 — 必须跟字典名一字不差>\",").append('\n');
        sb.append("     \"quantity\":<数字>,\"unit\":\"<g/kg/L/ml/个>\",").append('\n');
        sb.append("     \"percentage\":<占比数字 0-100>}\n");
        sb.append("  ],\n");
        sb.append("  \"totalWeight\": <总重量 数字>,\n");
        sb.append("  \"totalWeightUnit\": \"<g/kg>\",\n");
        sb.append("  \"explanation\": \"<为什么这么配, 1-2 句话>\",\n");
        sb.append("  \"warnings\": [\"<注意事项 0-3 条>\"]\n");
        sb.append("}\n\n");

        sb.append("【规则】\n");
        sb.append("1. materialName 必须 exactly 匹配上面字典里的某一条; 不在字典的物料绝不输出.\n");
        sb.append("2. 至少 1 个 item, 最多 20 个.\n");
        sb.append("3. percentage 加起来应该 ≈100%.\n");
        sb.append("4. 如果调整说明 (例 '减 10% 包材') 改 percentage / quantity, 在 warnings 注明.\n");
        sb.append("5. 仅输出 JSON, 不要 markdown 不要解释.");

        return sb.toString();
    }

    private String buildUserPrompt(ProductSample sample, String referenceSku, String adjustments) {
        StringBuilder sb = new StringBuilder();
        sb.append("【样品信息】\n");
        sb.append("- 样品编号: ").append(sample.getSampleCode()).append('\n');
        sb.append("- 样品名称: ").append(sample.getName()).append('\n');
        if (sample.getSpecification() != null) {
            sb.append("- 规格: ").append(sample.getSpecification()).append('\n');
        }
        if (sample.getGrade() != null) {
            sb.append("- 等级: ").append(sample.getGrade()).append('\n');
        }
        if (sample.getMainMaterial() != null) {
            sb.append("- 主原料 (字典名): ").append(sample.getMainMaterial()).append('\n');
        }
        if (sample.getCustomerName() != null) {
            sb.append("- 客户: ").append(sample.getCustomerName()).append('\n');
        }
        if (sample.getCustomerLatestRequirement() != null) {
            sb.append("- 客户最新要求: ").append(sample.getCustomerLatestRequirement()).append('\n');
        }

        if (referenceSku != null && !referenceSku.isBlank()) {
            sb.append("\n【参考 SKU】: ").append(referenceSku)
                    .append(" (你凭经验推, 不查实际 BOM)\n");
        }
        if (adjustments != null && !adjustments.isBlank()) {
            sb.append("\n【调整说明】: ").append(adjustments).append('\n');
        }

        sb.append("\n请生成 BOM 草稿 JSON.");
        return sb.toString();
    }

    private Map<String, Object> parseLlmJson(String response) throws Exception {
        // 兼容 LLM 偶尔加 ```json ... ``` markdown 围栏
        String cleaned = response
                .replaceAll("(?s)^.*?\\{", "{")   // 截掉首个 { 前的杂文 (e.g. "Here is the BOM:")
                .replaceAll("(?s)\\}[^}]*$", "}") // 截掉最末 } 后的杂文
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
        return objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("sampleId".equals(paramName)) {
            return "请提供样品 ID (例如 SP-2026-001 或 UUID).";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "sampleId":     return "样品 ID";
            case "referenceSku": return "参考 SKU";
            case "adjustments":  return "调整说明";
            default:             return super.getParameterDisplayName(paramName);
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
