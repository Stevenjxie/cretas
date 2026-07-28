package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * M-BOM-1 从研发样品生成 BOM (Track D1, WRITE).
 *
 * <p>客户场景: 研发员完成新菜样品 → 通过本 Tool 自动生成 BOM 草稿,
 * 减少手工录入. 关联 SCHEMA spec §2.7 S-RD-1 (sample_followups).
 *
 * <p><b>当前状态: STUB</b> — Track D1 范围只交付了 bom_recipes 主子表 schema.
 * 完整实现需要:
 *   - rd_requests / product_samples / sample_followups 接入 (S-RD-1, Track 待定)
 *   - source_type=SAMPLE_AUTOGEN + source_sample_id 关联
 *   - 样品 BOM 推导规则 (从试制记录的物料用量 → BOM 标准量)
 *
 * <p>Day 5 仅占位返回 NOT_IMPLEMENTED 提示, 等 S-RD-1 ship 后接入.
 */
@Slf4j
@Component
public class BomRecipeCreateFromSampleTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "bom_recipe_create_from_sample";
    }

    @Override
    public String getDescription() {
        return "从研发样品自动生成 BOM 配方 (DRAFT 状态). "
             + "适用场景: 新菜研发完成, 一键生成产线 BOM. "
             + "当前需要先存在 product_samples + sample_followups (S-RD-1).";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> sampleId = new HashMap<>();
        sampleId.put("type", "string");
        sampleId.put("description", "product_samples.id");

        Map<String, Object> properties = new HashMap<>();
        properties.put("sampleId", sampleId);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("sampleId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("sampleId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String sampleId = getString(params, "sampleId");
        log.info("BOM 从样品生成 (STUB): factoryId={}, sampleId={}", factoryId, sampleId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NOT_IMPLEMENTED");
        result.put("sampleId", sampleId);
        result.put("message",
                "BOM 从样品自动生成功能尚未交付 — 依赖 S-RD-1 (rd_requests / product_samples / sample_followups). "
                + "请先用 bom_recipe_create_from_text 通过自然语言建 BOM, 或在 BomEditorScreen 手动配置.");
        result.put("alternativeTools", List.of(
                "bom_recipe_create_from_text",
                "bom_recipe_clone_with_modify"
        ));
        return buildSimpleResult("BOM 从样品生成 (STUB, 待 S-RD-1)", result);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
