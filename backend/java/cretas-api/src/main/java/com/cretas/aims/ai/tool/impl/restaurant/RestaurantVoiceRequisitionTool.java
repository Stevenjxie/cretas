package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.restaurant.VoiceRequisitionSlot;
import com.cretas.aims.service.restaurant.VoiceRequisitionParserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音录入领料工具 (G7 Tier B, Tool-Skill 架构)。
 *
 * <p>对应意图 RESTAURANT_VOICE_REQUISITION。从语音识别文本提取食材/数量/单位, 返回
 * <b>草稿 slot</b> 供前端二段式人工确认 (Rule 2 / Rule 4) — <b>不直接写库</b>。实际领料单在
 * 用户确认后通过现有 POST /requisitions 创建。</p>
 *
 * <p>因为本工具只解析不落库, getActionType 默认 READ (非写操作), 不触发写护栏确认门。</p>
 *
 * @since 2026-06-03 (G7)
 */
@Slf4j
@Component
public class RestaurantVoiceRequisitionTool extends AbstractBusinessTool {

    @Autowired
    private VoiceRequisitionParserService parserService;

    @Override
    public String getToolName() {
        return "restaurant_voice_requisition";
    }

    @Override
    public String getDescription() {
        return "通过语音识别文本创建领料单草稿, 提取食材名称/数量/单位 (如\"要五斤猪肉\")。" +
                "返回草稿供人工确认后再提交, 不直接落库。适用场景: 厨房/仓管语音快速领料录入。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> voiceText = new HashMap<>();
        voiceText.put("type", "string");
        voiceText.put("description", "语音识别后的文本 (必需), 如\"要五斤猪肉\"");
        properties.put("voiceText", voiceText);

        Map<String, Object> requisitionDate = new HashMap<>();
        requisitionDate.put("type", "string");
        requisitionDate.put("description", "领料日期 YYYY-MM-DD (可选, 默认今日)");
        properties.put("requisitionDate", requisitionDate);

        schema.put("properties", properties);
        schema.put("required", List.of("voiceText"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("voiceText");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        String voiceText = getString(params, "voiceText");
        log.info("语音录入领料解析 - 工厂ID: {}, 文本: {}", factoryId, voiceText);

        VoiceRequisitionSlot slot = parserService.parse(factoryId, voiceText);

        Map<String, Object> result = new HashMap<>();
        result.put("ingredientName", slot.getIngredientName());
        result.put("quantity", slot.getQuantity());
        result.put("unit", slot.getUnit());
        result.put("matchedMaterialTypeId", slot.getMatchedMaterialTypeId());
        result.put("matchedMaterialName", slot.getMatchedMaterialName());
        result.put("matchConfidence", slot.getMatchConfidence());
        result.put("rawText", slot.getRawText());
        result.put("requisitionDate", getString(params, "requisitionDate"));
        result.put("message", slot.getMessage());
        result.put("isDraft", true);  // 草稿: 前端需人工确认后才创建领料单
        return result;
    }
}
