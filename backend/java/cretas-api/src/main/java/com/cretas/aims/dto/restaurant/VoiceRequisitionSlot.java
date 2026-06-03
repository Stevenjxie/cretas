package com.cretas.aims.dto.restaurant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 语音录入领料 slot 解析结果 (G7 Tier B)。
 *
 * <p>从语音文本 LLM slot-fill 提取食材/数量/单位, 返回草稿供人工二段式确认 (Rule 2 / Rule 4)。
 * <b>不写库</b> — 人工在表单确认后才 POST /requisitions 落库。</p>
 *
 * @since 2026-06-03 (G7)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceRequisitionSlot {

    /** 提取的食材名称 (原文)。 */
    private String ingredientName;

    /** 数量 (可能 null — 未识别)。 */
    private BigDecimal quantity;

    /** 单位 (kg/斤/个/包等)。 */
    private String unit;

    /** 模糊匹配到的 raw_material_types.id (可能 null, 待人工指认)。 */
    private String matchedMaterialTypeId;

    /** 匹配到的食材标准名 (前端展示用)。 */
    private String matchedMaterialName;

    /** 匹配置信度 0-1 (slot 提取成功 + 库内匹配命中 → 高)。 */
    private double matchConfidence;

    /** 语音识别原文。 */
    private String rawText;

    /** 人工可读提示 (Rule 2: "识别: '五斤猪肉' → 猪肉 5 斤")。 */
    private String message;
}
