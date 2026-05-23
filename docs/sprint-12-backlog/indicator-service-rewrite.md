# Sprint 12 P0 Backlog — IndicatorQueryService Backend Rewrite

**目标 owner**: BI chat 或 sister AI 工厂 chat (TBD by Steve)
**预计工时**: 3-5 工作日
**优先级**: P0 — Sprint 11 4-B 临时方案的 root cause fix
**前置依赖**: Sprint 11 4-B band-aid 已 ship (PR pending main merge), 提供 customer-demo cover 直到 Sprint 12 真接 backend

---

## Sprint 11 临时方案的问题 (4-B band-aid)

Sprint 11 BI chat 在 anti-goal "不准 backend 改动" 约束下 (防撞 sister chat #205/#208/#203/#204 ship), 采用 4-B 路径 A:

**纯前端 compute 真业务数据** — `web-admin/src/views/indicator-center/B2BRealDataSection.vue` 直接 fetch `/api/mobile/{factoryId}/sales/orders?page=1&size=200` 然后前端 reduce 算 avg/sum/count, 渲染 3 个 B2B cards (订单总数 / 平均订单金额 / 销售总额) — 这些显示真 F006 业务数值 (avg ¥1.22M)。

同时 IndicatorCenterDashboard.vue 加 hardcoded 7-code filter 隐藏 V_23_11 mirrored codes:

```
'AVG_TICKET_PRICE', 'TABLE_TURNOVER', 'DISH_GROSS_MARGIN',
'RAW_WASTAGE_RATE', 'FOOD_SAFETY_PASS_RATE',
'FACTORY_YIELD_RATE', 'FACTORY_PLAN_ACHIEVE_RATE',
```

### 4 个 band-aid 问题

| # | 问题 | 影响 |
|---|---|---|
| 1 | 7-code filter 是 hardcoded constant in Vue, 增加新 indicator 需修前端 + redeploy | DB 改动跟 UI 不同步 — 加新 mirror code 或重命名都漏 |
| 2 | 前端 compute B2B 只有 3 个 cards, F006 还有 9 个 null indicator 等 backend 计算 (库存价值 / 库存周转率 / 月度销售额 / 质检不合格率 / HACCP 违规次数 / 食品安全检查通过率 / 翻台率 / 平均客单价 / 菜品毛利率) — 老板看到 "—" 不 actionable | 老板使用度: 真业务 3 cards + 9 个 "未计算" = 25% useful (improvement vs 100% mirror, 但仍 75% gap) |
| 3 | B2B card 限制在 200 个最近订单 (size=200), 历史超过 200 单的工厂 stats 不准 | F006 5 单 OK, 但客户 GMV ≥200 单时 broken |
| 4 | Indicator framework 在 prod 是分裂 truth — 7 cards 由 V_23_11 mirror (DB 写死), 10 cards 由 IndicatorComputation null/缓存, B2B 3 cards 由前端 SQL-via-API. 任何 dashboard 改动需要 reconcile 3 个 data source | maintenance overhead 高, sister chat 接手时上下文丢失风险大 |

---

## Sprint 12 真 fix 设计

### Phase A: 删 V_23_11 mirror migration (1d)

1. 写 `V_24_01__delete_v23_11_mirror_indicators.sql`:
   ```sql
   DELETE FROM indicators
   WHERE factory_id = 'F006'
     AND code IN ('AVG_TICKET_PRICE', 'TABLE_TURNOVER', 'DISH_GROSS_MARGIN',
                  'RAW_WASTAGE_RATE', 'FOOD_SAFETY_PASS_RATE',
                  'FACTORY_YIELD_RATE', 'FACTORY_PLAN_ACHIEVE_RATE');
   ```
2. 同时清 V_23_11 mirror 落的 `indicator_versions` 历史 (per Sprint 11 D2 mock generator output: 210 versions 中 30 天 × 7 codes = 210 rows for F006)
3. Test env 先跑 + verify 老 mirror cards 在 prod 8086 消失
4. Web-admin filter (per Sprint 11 4-B) 改成 `[]` 空数组, 或删 filter logic 直接

### Phase B: F006 工厂业态 7 个 indicator 真接 (2-3d)

业态匹配的 F006 工厂 codes (per F006 行业属性 + sister #220 retro Item 4 INDICATOR_QUERY domain tagging):

| code | name | 数据源 | compute SQL 概要 |
|---|---|---|---|
| `B2B_AVG_ORDER_VALUE` | B2B 平均订单金额 | `sales_orders` | `AVG(total_amount) WHERE factory_id=$1 AND status IN ('CONFIRMED','SHIPPED')` |
| `B2B_TOTAL_REVENUE_MTD` | 本月销售总额 | `sales_orders` | `SUM(total_amount) WHERE created_at >= date_trunc('month', NOW())` |
| `B2B_ORDER_COUNT_MTD` | 本月订单数 | `sales_orders` | `COUNT(*) WHERE created_at >= date_trunc('month', NOW())` |
| `FACTORY_INVENTORY_VALUE` | 库存总价值 | `material_batches + product_batches` | `SUM(quantity * unit_cost)` join material/product type |
| `FACTORY_INVENTORY_TURNOVER` | 库存周转率 | sales_orders + batches | `COGS / AVG(inventory_value)` (per month) |
| `FACTORY_QUALITY_REJECT_RATE` | 质检不合格率 | `quality_inspections` | `SUM(reject_qty) / SUM(total_qty)` |
| `FACTORY_HACCP_VIOLATIONS_MTD` | HACCP 违规次数 | `food_safety_records / haccp_audits` | `COUNT(*) WHERE violation = true AND created_at >= MTD` |

### Phase C: IndicatorQueryService 真实算法接入 (1-2d)

参考 `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/IndicatorQueryServiceImpl.java`:

```java
// 当前 (Sprint 11): 单纯查 indicators 表 lastValue + indicator_versions 历史
@Override
public IndicatorValueResponse getValue(String factoryId, String code) {
    Indicator i = indicatorRepository.findByFactoryIdAndCode(factoryId, code);
    return IndicatorValueResponse.fromEntity(i);  // 直接返 DB 字段
}

// Sprint 12 后: dispatch 到 IndicatorComputationStrategy 真算
@Override
public IndicatorValueResponse getValue(String factoryId, String code) {
    IndicatorComputationStrategy strategy = strategyRegistry.find(code);
    if (strategy == null) throw new IllegalStateException("No strategy for " + code);
    BigDecimal value = strategy.compute(factoryId, currentPeriod());
    Indicator i = indicatorRepository.findByFactoryIdAndCode(factoryId, code);
    i.setLastValue(value);
    i.setLastComputedAt(Instant.now());
    indicatorRepository.save(i);
    return IndicatorValueResponse.fromEntity(i);
}
```

需要新建 `IndicatorComputationStrategy` 接口 + 7 个实现 (one per code above). 跟 Sprint 11 Day 4 IndicatorQueryServiceImpl 的 framework 兼容, 不重写。

### Phase D: 删 B2B 前端 compute + 撤 hardcoded filter (0.5d)

- 删 `web-admin/src/views/indicator-center/B2BRealDataSection.vue`
- IndicatorCenterDashboard.vue 撤 `MIRRORED_CODES` const + filter logic
- 改回从 standard `/api/mobile/{factoryId}/indicators` 一律拉取
- 大字 banner "客户演示模式" 改回 default 隐藏 (per mock factory detection 已有 logic)

---

## DOD (5 条)

(a) V_24_01 migration 写完 + deploy test 10011 + smoke verify 老 mirror cards 在 prod 8086 消失
(b) 7 个 IndicatorComputationStrategy 实现 + 单测 + sample F006 data smoke (avg ¥1.22M ± 1% match)
(c) IndicatorQueryServiceImpl dispatch 到 strategy + 兼容老 caller path
(d) web-admin 撤 B2BRealDataSection + filter logic + Playwright verify F006 dashboard 显示 7 个真业务 cards (真值, 不是 mock)
(e) `docs/audits/sprint-12-bi-real-data-live.md` 记录 cross-verify SQL vs Tool 一致

---

## Risk + Mitigation

| Risk | Mitigation |
|---|---|
| Strategy 实现错 SQL → 结果跟 web-admin Sprint 11 前端 compute 不一致 | Sprint 12 必须 cross-verify SQL vs Tool ≤1% (per sister #220 Item 1 BLOCKER pattern). 写 audit doc 含 SQL + Tool output + diff. |
| 删 V_23_11 时漏清 indicator_versions → 老历史数据残留 trend graph 错 | Migration 含 `DELETE FROM indicator_versions WHERE indicator_id IN (...)` 子查询 |
| 7 个 SQL strategy 算法不准 (e.g. inventory turnover 跨月边界) | 写单测 + 跟 sales chat / 财务 chat 双 review |
| Sprint 12 dispatch 时 sister AI 工厂 chat 还在并行做 IndicatorComputation 框架重写 | dispatch 前 `gh pr list --state open` 验证 + worktree 隔离 |

---

## Ownership 选项

| 选项 | Pro | Con |
|---|---|---|
| BI chat 接 | 上下文连续 (写过 Sprint 11 4-B band-aid), 不丢人 | BI chat 还有 Sprint 11 BI tool 持续 polish 工作 |
| Sister AI 工厂 chat 接 | sister 已经在做 IndicatorComputation framework + intent registration, scope overlap | 需要 brief 交接, 风险 spec drift |
| 新 sister chat 接 | 隔离, 清晰 ownership | 上下文 cold, 需要 organizer brief |

**推荐**: Sister AI 工厂 chat (sister #220) — scope overlap minimal, sister 已熟 indicator framework + intent registration, brief 成本低。Steve TBD.

---

## Sprint 12 trigger

Steve 决定 Sprint 11 收官 → Sprint 12 P0 list 加入本 backlog → ping sister chat 接手。

Sprint 11 4-B band-aid 保留在 prod 直到 Sprint 12 Phase D 删除前端 compute (estimated 5-7 工作日 from Sprint 12 start)。
