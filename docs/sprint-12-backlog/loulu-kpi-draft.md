# Phase C 卤味业态 KPI — Steve draft (2026-05-29)

**Source**: Steve §3 draft 2026-05-29 (BI chat checkpoint). Phase D ship 完后 Phase C 用.
**Decision**: Option A — ship 7 strategies with null-preserve, 数据空就 "—", F006 老板录数据后自动填.
**Code prefix**: `FACTORY_LU_*` (区别已有 RESTAURANT_* / B2B_* / FACTORY_*).

| 卤味 KPI | 算法 (分子/分母) | 数据源 | F006 现状 | 预期 null? |
|---|---|---|---|---|
| 出品率 | 成品重量 / 投料重量 | production_batches + material_batches | ⚠️ production_batches 仅 2 行 | 大概率 null "—" |
| 卤汁损耗率 | 卤汁损耗 / 卤汁投入 | wastage_records | ⚠️ 看 F006 有无 | 待验 |
| 真空包装合格率 | 合格包装 / 总包装 | quality_inspections | ⚠️ 仅 3 行 (0 F006) | 大概率 null |
| 单位成本 | 总成本 / 产量 | material_batches + production_batches | ⚠️ 数据少 | 待验 |
| 日均产量 | sum(production_batches.qty) / 天数 | production_batches | ⚠️ 仅 2 行 | 大概率 null |
| 原料周转天数 | 库存量 / 日均消耗 | material_batches | ✅ 8 ACTIVE batches | 可能能算 |
| 准时交货率 | 准时交货数 / 总交货数 | sales_orders | ✅ 5 单 (样本小) | 可算 (加 n=N disclaimer) |

## 关键诚实点 (Steve 原话)
> F006 真业务数据少 → 7 个卤味 KPI 大部分会返 null "—" (per null-preserve), 这是对的, 不是失败.
> 等 F006 老板真录入数据后自动填.

## Phase C 实施 checklist (待办)
- [ ] 7 个 FACTORY_LU_* strategy 实现 (ratio 用 null-preserve, counter 用 ZERO)
- [ ] grep / SSH 确认每个数据源表的真实列名 (production_batches / wastage_records 列)
- [ ] V_06 migration: 7 indicators (category=FACTORY_LU) + 7 JPA_AGGREGATE computations + 7 actionHints
- [ ] SMART_INDICATOR_QUERY intent 注册 (#264) + phrase_mapping 12 短语
- [ ] IntentRoutingTest 12 phrase × F006 100% routing
- [ ] 跨账号 RLS verify (factory_super_admin / sales_owner / warehouse_keeper)
- [ ] deploy + SSH verify compute_source='REAL_BUSINESS:FACTORY_LU_*'

## 待 Steve 确认的精确算法 (Phase C 实施前)
- 出品率: "成品重量" 是 production_batches 哪个列? "投料重量" 是 material_batches 消耗?
- 卤汁损耗率: wastage_records 怎么区分"卤汁" vs 其他损耗? (category 字段?)
- 准时交货: "准时" = required_delivery_date >= 实际交货日? sales_orders 哪个列记实际交货?
