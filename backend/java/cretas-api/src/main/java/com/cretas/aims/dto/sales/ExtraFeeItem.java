package com.cretas.aims.dto.sales;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 销售订单"其他费用"行 — 客户原话 (Apr 7 transcript 2326-2334s):
 * "还有那些其他费用 — 一些额外业务的费用项目"
 *
 * 例: 装卸费 / 包装费 / 加急费 等. 不计入 totalAmount 聚合 (本轮先存字段).
 *
 * @since 2026-04-08
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtraFeeItem {
    /** 费用名称, 如 "装卸费" */
    private String name;
    /** 金额 — @PriceSensitive: stripped to null for warehouse_manager (BUG-6 follow-up 2026-05-12). */
    @PriceSensitive
    private BigDecimal amount;
    /** 备注, 可空 */
    private String remark;
}
