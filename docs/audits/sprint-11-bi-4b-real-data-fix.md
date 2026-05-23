# Sprint 11 BI 4-B Band-aid Fix — F006 真业务数据接入 (prod 8086)

**日期**: 2026-05-23
**Owner**: BI chat (worktree `my-prototype-logistics-sprint11-d5`)
**Trigger**: Steve 拒绝收货 + cross-verify failure — V_23_11 mirror data ¥37.39 客单价 vs F006 真 sales_orders avg ¥1.22M = 差 99%
**Predecessor**: `docs/audits/sprint-11-bi-prod-live.md` (Sprint 11 BI P4 prod live), `docs/audits/sprint-11-bi-deep-audit.md` (3 finding)
**Successor**: `docs/sprint-12-backlog/indicator-service-rewrite.md` (Sprint 12 P0 backend 真 fix)

---

## Cross-Verify Failure 摘要

Per Steve's audit + sister AI 工厂 chat retro PR #220 / #227:

| 指标 | V_23_11 mirror 值 (Sprint 11) | F006 真业务值 | 差 |
|---|---|---|---|
| 客单价 (AVG_TICKET_PRICE) | ¥37.39 元/单 | ¥1,225,510 元/单 (sales_orders avg) | 99.99% |
| 翻台率 (TABLE_TURNOVER) | 1.41 次/天 | N/A — F006 是工厂不是餐厅, 业态错配 | 100% |
| 菜品毛利 (DISH_GROSS_MARGIN) | 39.44% | N/A — 工厂没"菜品"概念 | 100% |
| 食材损耗率 (RAW_WASTAGE_RATE) | 6.58% | N/A — F006 用 material_batches 不是餐厅原料 | 100% |
| 食安通过率 (FOOD_SAFETY_PASS_RATE) | 98.78% | TBD (Sprint 12 接 quality_inspections) | TBD |
| 综合良品率 (FACTORY_YIELD_RATE) | 96.1% | TBD (Sprint 12 接 production_batches) | TBD |
| 生产计划达成率 (FACTORY_PLAN_ACHIEVE_RATE) | 102.18% | TBD (Sprint 12 接 production_plans) | TBD |

**结论**: 7 个 V_23_11 mirror codes 全部 demonstrably wrong — 4 个业态错配 (餐饮 codes 不适用工厂 F006) + 3 个 mirror value 待 Sprint 12 真算。Sprint 11 PR #231 banner mitigation 不够 — 老板会忽略小字, 看到 ¥37.39 客单价仍误判 F006 业务规模。

---

## 4-B Band-aid 方案 (per Steve decision)

**Anti-goal override**: Steve clarified Sprint 11 anti-goal "不准 backend 改动" 是短期纪律 (防撞 sister chat), NOT 长期原则. Band-aid acceptable IF:
1. 真业务数据 cards 显示 (老板能看真 F006 业绩)
2. UI 明文标注 "临时方案 Sprint 12 接 backend"
3. Sprint 12 backlog doc 写完 + push main + ping sister chat

**4-B 路径 A 选定** (per AskUserQuestion 2026-05-23 03:00):

> 纯前端 compute (不撞 anti-goal): web-admin fetch /api/mobile/F006/sales-orders → 前端 reduce 算 avg + total + 月 GMV → 新建 B2B section 渲染 1-2 cards, 撤掉 7 个 mirror 餐饮 codes (隐藏 visibility, 不删 DB)

---

## 实施

### 新增 component

- `web-admin/src/views/indicator-center/B2BRealDataSection.vue` (266 行)
  - Fetch `GET /api/mobile/{factoryId}/sales/orders?page=1&size=200`
  - 前端 reduce 算 `{count, avgAmount, totalAmount}`
  - 渲染 3 个 KPI cards (订单总数 / 平均订单金额 / 销售总额)
  - 大字 banner "客户演示模式 · Sprint 12 接 backend 真算法" (border 2px primary, font 18px bold)
  - Section header 加 `临时方案 — Sprint 12 接 backend` 黄色 tag

### IndicatorCenterDashboard.vue 改动

- 删除小字 "示例数据警告" banner (PR #231 mitigation, 老板会忽略)
- 改 `<el-alert>` 为 `<B2BRealDataSection :factory-id>` (非 mock factory + 有 mirror codes 时显示)
- `filteredIndicators` 加 hardcoded filter: `!MIRRORED_CODES.includes(i.code)` 隐藏 7 个 mirror codes
- 注释里加 Sprint 12 backlog reference

---

## DOD verify (5 条 + 2 新加)

### (a) Build hash match
```bash
$ curl -s http://139.196.165.140:8086/ | sha256sum
6d96b071e213192fcac8b5b2315b553588159b6d1a7de1c40817618bfa5f38d6  *-
$ sha256sum web-admin/dist/index.html
6d96b071e213192fcac8b5b2315b553588159b6d1a7de1c40817618bfa5f38d6
```
✓ Prod 8086 served = local dist build

### (b) Playwright B2B cards display real data (¥1.22M avg)
✓ Screenshot: `sprint-11-bi-screenshots/sprint-11-bi-4b-01-prod-8086-real-b2b-data.png`
- 订单总数: 5 单
- 平均订单金额 (B2B): ¥ 1,225,510 元/单
- 销售总额 (累计): ¥ 6,127,550 元
- 数据时间: 2026/5/23 03:13:36
- 样本: 最近 5 个销售订单

### (c) Mirror codes hidden, F006 real codes visible
Playwright `browser_evaluate` cross-check (per `/api/mobile/F006/indicators`):

```json
{
  "totalCount": 17,
  "visibleAfterFilter": 10,
  "hiddenMirror": [
    "AVG_TICKET_PRICE=37.3886",
    "TABLE_TURNOVER=1.4081",
    "RAW_WASTAGE_RATE=6.5848",
    "FOOD_SAFETY_PASS_RATE=98.778",
    "DISH_GROSS_MARGIN=39.4399",
    "FACTORY_YIELD_RATE=96.1",
    "FACTORY_PLAN_ACHIEVE_RATE=102.1806"
  ],
  "visibleCodes": [
    "RESTAURANT_WASTAGE_RATE=2.8",        ← F006 自己 indicator
    "RESTAURANT_TABLE_TURNOVER=null",     ← F006 待 Sprint 12 真算
    "RESTAURANT_AVG_ORDER_VALUE=null",
    "RESTAURANT_DISH_MARGIN=null",
    "RESTAURANT_FOOD_SAFETY_PASS=null",
    "FINANCE_INVENTORY_VALUE=null",
    "INVENTORY_TURNOVER_RATE=null",
    "SALES_MONTHLY_REVENUE=null",
    "QUALITY_REJECT_RATE=null",
    "FOOD_SAFETY_HACCP_VIOLATIONS=null"
  ]
}
```
✓ 7 mirror codes 100% hidden, 10 F006 codes (其中 1 已算 + 9 待 Sprint 12 backend) visible

### (d) Mobile responsive
✓ Screenshot: `sprint-11-bi-screenshots/sprint-11-bi-4b-02-mobile-375-real-b2b.png`
- 375×812 视口下 B2B section 3 cards 垂直 stack
- 大字 banner 自动 wrap, 可读性保留

### (e) F006 admin login → see real B2B data
✓ Login: `f006_admin / 123456` → URL `/indicator-center` → B2B section 显示 ¥1.22M avg 真业务数据

### (g) Sprint 12 backlog doc merged ← Steve 新加 DOD
✓ `docs/sprint-12-backlog/indicator-service-rewrite.md` 写完 (158 行)
- 4 个 band-aid 问题 + Sprint 12 4 phase 重写 design + 7 个 真 indicator design + 5 条 DOD + Risk + Ownership 选项
- Steve TBD: BI chat 接 OR sister AI 工厂 chat 接

### (h) UI cards 明文标注 "临时方案" ← Steve 新加 DOD
✓ B2BRealDataSection 大字 banner: "客户演示模式 · Sprint 12 接 backend 真算法" (18px bold, primary color, 2px border)
✓ Section header 黄色 tag: "临时方案 — Sprint 12 接 backend"
✓ 详细 banner body: 说明前端 compute 来源 + 其他 cards 为 mock + Sprint 12 真接 backend

---

## 老板真能用度

Sprint 11 真值 (per AI 工厂 chat retro PR #220 + 我之前 90% claim 矛盾):

| Sprint 11 时间点 | 老板能用度 | Evidence |
|---|---|---|
| Sprint 11 D7 (PR #192 ship) | 0% | UI mock data 显示, banner 小字 |
| Sprint 11 D9 Round 1 (PR #155 wire) | 10% | API 通 + UI 拉 17 cards, 但全是 mirror demo |
| Sprint 11 BI prod live (PR #217 + #228) | 10% (我误判 70%) | mirror data + banner mitigation 老板会忽略 |
| Sprint 11 BI 4-B 4 fix (本 doc) | **40-50%** | 3 真业务 B2B cards + 7 mirror hidden + 大字 banner |
| Sprint 12 backend 真算 (backlog doc) | 80-90% (目标) | 7 个 F006 工厂业态 indicator 真值 |

老板真能用度 +30-40% from 4-B band-aid. **没有声称 90% 因为还有 9 个 null cards 等 backend.**

---

## 已知限制

1. B2B cards size=200 limit — F006 当前 5 单 OK, 大客户 ≥200 单时 stats 不准
2. 7 mirror codes hidden but DB 没删 — Sprint 12 V_24_01 migration 真删
3. 9 个 null cards "未计算" — 老板看到 "—" 不 actionable, Sprint 12 backend 接
4. 食材损耗率 (RESTAURANT_WASTAGE_RATE = 2.8%) 是 F006 自己 indicator (不是 mirror), 但来源不明 — Sprint 12 audit
5. Sprint 11 D9 P2 时间戳 -88669 秒前 已被 sister chat 修, 但本 audit 没单独 verify (sister #220 retro 报修)

---

## Sprint 11 收官 ready

- ✓ 真业务数据 prod ship + hash verify
- ✓ Cross-verify ¥1.22M avg with sales_orders 5 rows
- ✓ Mirror codes hidden + 7 mirror values logged
- ✓ Sprint 12 backlog 写完 (3-5 day estimate)
- ✓ Big-font banner 老板能看见
- ✓ Audit doc cross-ref sister #220 retro
- ⏳ 待 Steve 确认: "BI prod 看了, 截图看了, F006 老板能用" → BI chat handoff Sprint 12

**Co-Authored-By**: BI chat (Claude Opus 4.7 1M context)
