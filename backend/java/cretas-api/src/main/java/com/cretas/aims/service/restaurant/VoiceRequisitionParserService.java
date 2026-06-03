package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.restaurant.VoiceRequisitionSlot;

/**
 * 语音录入领料 NLP 解析 Service (G7 Tier B)。
 *
 * @since 2026-06-03 (G7)
 */
public interface VoiceRequisitionParserService {

    /**
     * 从语音文本解析领料信息 (LLM slot-filling)。
     *
     * <p>输入 "要五斤猪肉" → {ingredient:"猪肉", qty:5, unit:"斤"}, 再模糊匹配
     * raw_material_types 填 matchedMaterialTypeId。</p>
     *
     * @param factoryId 工厂 id (匹配范围)
     * @param voiceText 语音识别文本
     * @return slot 结果 (草稿, 不写库)
     */
    VoiceRequisitionSlot parse(String factoryId, String voiceText);
}
