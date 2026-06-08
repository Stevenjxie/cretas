package com.cretas.aims.dto.producttype;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * T149/T150: SKU 智能防呆填充 — 按产品名称从历史 SKU 记忆推断的填充建议.
 *
 * <p>来源 (优先级):
 * <ol>
 *   <li>名称相似度匹配到的历史产品 → 取其 大类/单位/一级单位/装箱系数/温区/规格/标准克重/出成率
 *       (matchedFrom = 该产品名);</li>
 *   <li>名称匹配不到 → 仅按关键词规则推断大类 (其余留 null, 禁假数据);</li>
 *   <li>都没把握 → 对应字段返 null (不臆造).</li>
 * </ol>
 *
 * <p>所有字段都是「建议默认值」, 前端只回填用户尚未手动设置的字段, 全部可被用户覆盖.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductTypeSuggestionDTO {

    @Schema(description = "建议产品大类 (FINISHED_PRODUCT/RAW_MATERIAL/PACKAGING/SEASONING/CUSTOMER_MATERIAL); 无把握时为 null")
    private String productCategory;

    @Schema(description = "建议二级单位 (如 盒); 无名称匹配时为 null")
    private String unit;

    @Schema(description = "建议一级单位 (如 筐); 无名称匹配时为 null")
    private String level1Unit;

    @Schema(description = "建议装箱换算系数 (1 一级单位 = N 二级单位); 无名称匹配时为 null")
    private BigDecimal boxConversionCoefficient;

    // ---- T150: 扩展建议字段 (名称匹配时带入, 无匹配时为 null, 禁假数据) ----

    @Schema(description = "建议温区 (如 冷藏/冷冻/常温); 无名称匹配或历史产品未配置时为 null")
    private String temperatureZone;

    @Schema(description = "建议规格 (如 120g/盒 20盒/框); 无名称匹配或历史产品未配置时为 null")
    private String specification;

    @Schema(description = "建议标准克重 (克/二级单位); 无名称匹配或历史产品未配置时为 null")
    private BigDecimal gramsPerUnit;

    @Schema(description = "建议半成品出成率 (0~1); 无名称匹配或历史产品未配置时为 null")
    private BigDecimal wipToFgYield;

    @Schema(description = "匹配来源产品名称 (透明展示, 让用户知道按哪条历史记忆匹配); 仅按关键词推断大类或无匹配时为 null")
    private String matchedFrom;
}
