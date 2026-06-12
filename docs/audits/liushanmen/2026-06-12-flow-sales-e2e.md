# §8 销售流真实数据 E2E

判定: 🔴 PARTIAL / 出库断链  
深度: medium(SQL + API, 未新造完整出库)

## 已坐实

- 含税销售单存在:
  - `SO-20260611-0001`, `FINANCE_APPROVED`, 未税 `4000.00`, 税额 `520.00`, remark `DEMO-tax-voucher-Friday demo (taxRate13)`.
  - `SO-20260611-0004`, `FINANCE_APPROVED`, 未税 `13600.00`, 税额 `1768.00`, remark `DEMO-FULLFLOW-20260611 周五演示掌中宝订单`.
- 凭证三行正确:
  - `SO-20260611-0001` -> `V-2026-0054`, 3 entries: 应收 `4520.00`, 主营收入 `4000.00`, 销项税 `520.00`.
  - `SO-20260611-0004` -> `V-2026-0056`, 3 entries: 应收 `15368.00`, 主营收入 `13600.00`, 销项税 `1768.00`.
- 成本回填:
  - `SO-20260611-0004` cost breakdown API 200; line actualCostPerUnit `40`, actualLineCost `8000`, actualProfit `5600`, actual margin `41.18%`.
  - multi-stage endpoint 200，但当前只有单段 `DEMO-COST2-SF-223637`, `outputUnitCost=40`, `laborCost=null`，人工 null 是两点报工诚实状态。

## 断链

- 销售出库/发货未闭环: SQL 查 `sales_delivery_records/sales_delivery_items/finished_goods_batches`，上述两个 SO 均无 delivery record。
- 因此不能判定“销售下单 -> 财审 -> 出库 -> 回款”全链完成，只能判财审/凭证/成本段完成。

## 结论

周五演示如果要展示销售全链，需要补 DEMO 销售出库记录或现场从 SO 生成出库；否则销售流只能演示到财审凭证和成本看板。
