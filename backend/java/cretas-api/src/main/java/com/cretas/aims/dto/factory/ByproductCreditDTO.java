package com.cretas.aims.dto.factory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 盘点单上的一条副产批次 —— 用于「副产价值确认」区。
 *
 * <p>🔴 <b>金额一律由后端算好返回, 前端只做格式化</b>。抵扣额走
 * {@code ByproductCreditService.creditOf} 这个唯一入口, 前端不得自行
 * {@code quantity × unitPrice} —— 本仓 2026-07-31 一天连修五处「同一件事多套实现」,
 * 两边一旦漂开, 用户看到的抵扣额与成本表里真正扣掉的会对不上, 而这种偏差不报错。</p>
 *
 * <p>🔴 <b>null 与 0 必须分得开</b>: {@code unitPrice}/{@code credit} 为 null 表示
 * 「还没人确认过」, 前端显示「未抵扣」; 确认为 0 是一个真实的确认结果, 显示 0.00。
 * 不臆造默认值(禁降级)。</p>
 *
 * <p><b>抵扣基数是盘点重量而非报工重量</b> —— 盘点就是以实物为准 (Steve 2026-07-31)。
 * 两者都返回给前端, 是为了让差异可见, 不是让前端去挑用哪个。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ByproductCreditDTO {

    /** 副产批次 ID。 */
    private String batchId;

    /** 批次号(给人看的)。 */
    private String batchNumber;

    /** 副产对应的原料字典 SKU。 */
    private String materialTypeId;

    /** SKU 名称; 查不到时返回 null, <b>不回退成 materialTypeId 冒充名称</b>。 */
    private String materialName;

    /** 数量单位(批次上的库存单位)。 */
    private String unit;

    /** 来源报工 ID —— 非 null 才是副产批次, 这条链由 Task 4 在报工时写入。 */
    private Long sourceReportId;

    /** 报工时登记的产出重量。 */
    private BigDecimal reportedQuantity;

    /** 盘点实盘重量; null = 这条还没盘。 */
    private BigDecimal stocktakeQuantity;

    /** 盘点 - 报工; 任一为 null 则为 null(不臆造 0)。 */
    private BigDecimal differenceQuantity;

    /** 确认单价; null = 未确认。 */
    private BigDecimal unitPrice;

    /** 单价确认时间; 与单价<b>一起</b>判定是否已确认 —— 有价无时间不算确认。 */
    private LocalDateTime priceConfirmedAt;

    /** 确认人。 */
    private Long priceConfirmedBy;

    /** 抵扣额 = 盘点重量 × 确认单价, 由 {@code ByproductCreditService} 算; null = 未抵扣。 */
    private BigDecimal credit;

    /** CONFIRMED / PENDING —— 与前端 {@code byproductCredit.ts#creditStatus} 同一套判据。 */
    private String creditStatus;
}
