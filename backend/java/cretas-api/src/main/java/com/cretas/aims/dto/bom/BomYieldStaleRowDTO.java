package com.cretas.aims.dto.bom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * BOM 出成率应用过期行 (M10 乐观并发保护).
 *
 * <p>当 {@code expectedCurrentYieldRate} 与数据库当前值不一致时,
 * 应用操作整体返回 409 并携带此列表, 前端据此提示用户重新预览.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BomYieldStaleRowDTO {

    /** BOM 配方 ID */
    private String recipeId;

    /** 数据库实际当前值 (null 表示待评估) */
    private BigDecimal dbCurrent;

    /** 调用方期望的当前值 (来自上次预览结果的 currentYieldRate) */
    private BigDecimal expected;
}
