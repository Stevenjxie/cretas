package com.cretas.aims.dto.bom;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 单行 BOM 出成率应用请求.
 *
 * <p>POST /api/mobile/{factoryId}/bom/yield-estimate/recalculate-apply 的请求体元素.
 * 调用方只提交用户明确选中的行.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BomYieldApplyRequest {

    /** bom_items.id (Long, RAW 主料行) */
    @NotNull(message = "bomItemId 不能为空")
    private Long bomItemId;

    /**
     * 要写入的出成率 (0–100 百分比).
     * 前端应直接传 suggestedYieldRate (已 ×100).
     */
    @NotNull(message = "yieldRate 不能为空")
    @DecimalMin(value = "0.01", message = "yieldRate 必须 > 0")
    @DecimalMax(value = "100.00", message = "yieldRate 不能超过 100")
    private BigDecimal yieldRate;
}
