package com.cretas.aims.dto.finance;

import java.math.BigDecimal;

/**
 * 结转成本 (期末 COGS 权责化) 小结 — carryCost 返回, 供结账流程 / 日志暴露。
 *
 * @param totalCogs        本期已结转的销售成本 (借 6401 / 贷 1405 的金额, 2 位小数)
 * @param costedItemCount  参与结转的发货明细行数 (有成本)
 * @param missingQuantity  <b>诚实 null</b>: 已发货但无单位成本、未结转的成品数量合计
 * @param missingItemCount 已发货但无单位成本、未结转的发货明细行数 ("N 笔无成本未结转")
 */
public record CostCarryoverSummary(
        BigDecimal totalCogs,
        long costedItemCount,
        BigDecimal missingQuantity,
        long missingItemCount) {
}
