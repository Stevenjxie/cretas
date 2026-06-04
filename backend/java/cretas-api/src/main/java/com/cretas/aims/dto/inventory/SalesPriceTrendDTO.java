package com.cretas.aims.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * B3 售价趋势 DTO — 财审辅助决策.
 *
 * <p>每条记录对应一笔历史成交销售订单中该产品的行级单价，
 * 供财务审核人员快速判断本单售价是否偏离历史水平 / BOM 标准成本。
 *
 * <p>字段均标记 @PriceSensitive，由 ResponseAdvice 对无 finance:read 权限角色 strip。
 * UI 应做 null 守卫。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesPriceTrendDTO {

    /** 销售订单号 (用于财务追溯). */
    private String orderNumber;

    /** 下单日期. */
    private LocalDate orderDate;

    /** 本单该产品销售单价. */
    @com.cretas.aims.security.PriceSensitive
    private BigDecimal unitPrice;

    /** 本单该产品销售数量 (辅助判断大客户折扣). */
    private BigDecimal quantity;

    /** 数量单位 (kg / 盒 / 箱 …). */
    private String unit;
}
