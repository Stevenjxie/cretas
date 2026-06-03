package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.dto.restaurant.VoiceRequisitionSlot;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.restaurant.VoiceRequisitionParserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语音录入领料 NLP 解析实现 (G7 Tier B)。
 *
 * <p>用 LLM slot-filling 从语音文本提取 {ingredient, quantity, unit}, 再模糊匹配
 * raw_material_types。返回草稿 slot, <b>不写库</b> (二段式人工确认)。</p>
 *
 * @since 2026-06-03 (G7)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceRequisitionParserServiceImpl implements VoiceRequisitionParserService {

    private final PythonLLMClient llmClient;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SLOT_SYSTEM_PROMPT = """
            你是中餐厅领料录入助手。从员工的语音文本中提取领料信息。
            严格以 JSON 返回(无其他文字):
            {
              "ingredient": "食材名称(如猪肉/白菜/土豆, 无法识别返回null)",
              "quantity": 数量数值(无法识别返回null),
              "unit": "单位(斤/kg/个/包/袋等, 无法识别返回null)"
            }
            要点:1.quantity为纯数字(把'五'转成5) 2.只提取食材不要句子 3.无法识别的字段返回null 4.仅返回JSON
            """;

    @Override
    public VoiceRequisitionSlot parse(String factoryId, String voiceText) {
        if (voiceText == null || voiceText.isBlank()) {
            return VoiceRequisitionSlot.builder()
                    .rawText(voiceText)
                    .matchConfidence(0.0)
                    .message("未识别到语音内容, 请重新录音")
                    .build();
        }
        if (!llmClient.isAvailable()) {
            return VoiceRequisitionSlot.builder()
                    .rawText(voiceText)
                    .matchConfidence(0.0)
                    .message("语音解析服务暂不可用, 请手动选择食材填写数量")
                    .build();
        }

        String llmResponse;
        try {
            llmResponse = llmClient.chatLowTemp(SLOT_SYSTEM_PROMPT, voiceText);
        } catch (Exception e) {
            log.warn("语音 slot 解析 LLM 调用失败: {}", e.getMessage());
            return VoiceRequisitionSlot.builder()
                    .rawText(voiceText)
                    .matchConfidence(0.0)
                    .message("语音解析失败, 请手动填写")
                    .build();
        }

        String ingredient = null;
        BigDecimal quantity = null;
        String unit = null;
        try {
            Pattern pattern = Pattern.compile("\\{[\\s\\S]*\\}");
            Matcher matcher = pattern.matcher(llmResponse != null ? llmResponse : "");
            if (matcher.find()) {
                JsonNode json = objectMapper.readTree(matcher.group());
                ingredient = jsonStr(json, "ingredient");
                quantity = jsonDecimal(json, "quantity");
                unit = jsonStr(json, "unit");
            }
        } catch (Exception e) {
            log.warn("语音 slot JSON 解析失败: {}", e.getMessage());
        }

        // 模糊匹配库内食材
        String matchedId = null;
        String matchedName = null;
        if (ingredient != null && !ingredient.isBlank()) {
            try {
                Page<RawMaterialType> page = rawMaterialTypeRepository.searchMaterialTypes(
                        factoryId, ingredient.trim(), PageRequest.of(0, 1));
                if (page != null && page.hasContent()) {
                    RawMaterialType rmt = page.getContent().get(0);
                    matchedId = rmt.getId();
                    matchedName = rmt.getName();
                }
            } catch (Exception e) {
                log.debug("食材库匹配失败 ({}): {}", ingredient, e.getMessage());
            }
        }

        double confidence = computeConfidence(ingredient, quantity, matchedId);
        String message = buildMessage(voiceText, ingredient, quantity, unit, matchedName, matchedId);

        return VoiceRequisitionSlot.builder()
                .ingredientName(ingredient)
                .quantity(quantity)
                .unit(unit)
                .matchedMaterialTypeId(matchedId)
                .matchedMaterialName(matchedName)
                .matchConfidence(confidence)
                .rawText(voiceText)
                .message(message)
                .build();
    }

    /**
     * 置信度: 食材识别(0.4) + 库内匹配(0.3) + 数量(0.2) + 单位(0.1)。
     */
    private double computeConfidence(String ingredient, BigDecimal quantity, String matchedId) {
        double score = 0.0;
        if (ingredient != null && !ingredient.isBlank()) {
            score += 0.4;
        }
        if (matchedId != null) {
            score += 0.3;
        }
        if (quantity != null) {
            score += 0.2;
        }
        return score;
    }

    private String buildMessage(String voiceText, String ingredient, BigDecimal quantity,
            String unit, String matchedName, String matchedId) {
        if (ingredient == null || ingredient.isBlank()) {
            return "未能从「" + voiceText + "」识别出食材, 请手动选择";
        }
        StringBuilder sb = new StringBuilder("识别: 「").append(voiceText).append("」 → ");
        sb.append(matchedName != null ? matchedName : ingredient);
        if (quantity != null) {
            sb.append(" ").append(quantity.stripTrailingZeros().toPlainString());
        }
        if (unit != null) {
            sb.append(unit);
        }
        if (matchedId == null) {
            sb.append(" (未找到库内对应食材, 请确认或新建)");
        }
        return sb.toString();
    }

    private String jsonStr(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) ? null : s;
    }

    private BigDecimal jsonDecimal(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(n.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
