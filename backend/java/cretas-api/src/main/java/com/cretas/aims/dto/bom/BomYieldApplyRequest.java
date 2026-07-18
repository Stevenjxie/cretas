package com.cretas.aims.dto.bom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BomYieldApplyRequest {

    /** 当前 ACTIVE/current bom_recipes.id。 */
    @NotNull(message = "recipeId 不能为空")
    private String recipeId;

    /**
     * 要写入的出成率 (百分比, > 0).
     * 前端应直接传 suggestedYieldRate (已 ×100).
     * 保水/腌制等工序可合法超过 100 (e.g. 六扇门猪舌保水 105–126%), 故去掉 ≤100 上限约束.
     */
    @NotNull(message = "yieldRate 不能为空")
    @DecimalMin(value = "0.01", message = "yieldRate 必须 > 0")
    private BigDecimal yieldRate;

    /**
     * M10 乐观并发保护: 调用方期望的当前 DB 值 (来自上次预览结果的 {@code currentYieldRate}).
     *
     * <p>若提供, 应用前会对比数据库实际值; 不一致 → 整批返回 HTTP 409 + staleRows 列表.
     * null 表示调用方不做并发检查 (向后兼容旧前端).
     */
    private BigDecimal expectedCurrentYieldRate;
}
