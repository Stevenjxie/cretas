package com.cretas.aims.dto.orchestration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 销售订单行项目库存匹配结果
 * 表示订单中单个产品品类的库存满足情况
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LineItemMatch {

    /** 销售订单行 ID (预留台账归属, 2026-07-06)。 */
    private String salesOrderItemId;

    /** 产品类型ID */
    private String productTypeId;

    /** 产品类型名称 */
    private String productTypeName;

    /** 订单需求数量 */
    private BigDecimal requiredQuantity;

    /** 当前可用成品库存数量 */
    private BigDecimal availableQuantity;

    /**
     * 缺口数量。
     * 若 availableQuantity >= requiredQuantity，则缺口为负数（表示有富余）；
     * 若 availableQuantity < requiredQuantity，则缺口为正数（表示不足）。
     */
    private BigDecimal shortfallQuantity;

    /**
     * 判断该行项目是否库存充足（无缺口）
     *
     * @return true 表示可用量满足需求量
     */
    public boolean isFullySatisfied() {
        return shortfallQuantity.compareTo(BigDecimal.ZERO) <= 0;
    }
}
