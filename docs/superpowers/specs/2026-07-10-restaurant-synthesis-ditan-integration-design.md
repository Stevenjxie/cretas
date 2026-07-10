# 餐饮合成 AI 接底盘改造 — 设计 spec (P1 诚实+同比环比 / P2.1 供应商价格异常)

> 日期: 2026-07-10
> 状态: **已过 Fable 对抗 gate 修订**, 待 user 终审 → writing-plans
> 来源: 战略定位对话 (餐饮连锁 ERP + AI 体检) + 2 Explore 底盘挖掘 (origin/main `3d64c7bfc`) + Fable 设计 gate
> **Fable gate 结论**: 原 P2.2 (理论vs实际毛利 GAP/损耗) **核心口径是假的** (DEMO 上 seed-vs-seed 剧场, 真租户上 NULL/按构造冤枉人) → **本期砍掉**, 用诚实口径另立 spec。本期只留经 Fable 验证诚实可建的 P1 + P2.1。

---

## 0. 一句话

把**已建好但跟聊天 AI 零接线的诚实底盘**(供应商价格异常威慑)接进 Python 合成 FactBook, 让 demo 聊天"把底盘真实数据体现出来"; 删掉假 dish BOM seed → 中餐单品毛利诚实降级到加权毛利; 补 restaurant-gold 同比环比。**性质: 接线 + 一处真 build (同比环比) + 诚实 demo seed。**

---

## 1. 现状真相 (Explore + Fable 实测)

**两条互不通的 AI 路**: Python 合成聊天 (`agent/synthesis_engine.py`, 只读 9 个扁平 gold 查询) ‖ 餐饮体检底盘 (`services/restaurant/*` + Java `RestaurantEconomicsAnalysisTool`)。**零接线。**

**9 维真假**: finance/sales/channel/meal_period/discount/attribution/review 真; **dish_margin (假 seed `restaurant_sku_forms`) + weather (`internal_seed_weather` 合成) 假**。

**Fable 纠正的关键事实**:
- `finance_summary` (`gold/queries.py:1103-1185`) **自 PR#1315 起已含真实成本** (material/labor/overhead/net_profit/gross_profit) — docstring `1064-1067` 是 stale, 别信 docstring 信代码。**所以加权毛利/总毛利率有真数据可降级。**
- `agg_daily_cost` **只有 DEMO seed 在写** (`V20260709_02`: 成本 = 营收×固定比例), 真租户为 NULL — 这是砍 P2.2 的根因。
- `detect_price_anomalies` (`gold/price_anomaly.py:88-130`) 自包含读 `agg_supplier_price` 带 RLS — **诚实薄接线, P2.1 可照建**。
- 空 dish_margin 静默丢弃 (`synthesis_engine.py:1110-1115`, 无 NOTE), `NOTE_REVIEW_ABSENT_NEXTACTION` (`factbook.py:53-56`) 可仿。
- plan_key 自动吸收新维度 (`synthesis_engine.py:651`)。seed 安全模式 DEMO-scoped 成立 (RLS FORCE + `SET app.factory_id='DEMO_REST'`)。

---

## 2. 架构 (方案 A: 接进合成 FactBook)

保持 demo 单一聊天口。新维度 = plan trigger + gold 数据装配 + FactBook render, demo 聊天/手机端自动显示。装配层从 gold 表组装 section 输入喂现有引擎 (复用不重写)。

---

## 3. P1 — 诚实 + 同比环比

### P1.1 砍中餐单品毛利, 降级到加权毛利 (🔒 诚实/grounding)
- **删 DEMO_REST `restaurant_sku_forms` 假 seed** (8 行, `V20260709_03` 造)。
- **移除 `dish_margin` 单品维度** (中餐单品毛利本就是虚构; Fable #3: 接 `agg_restaurant_product_cost` 还缺 价格+名称+销量 装配, 不值)。触发词 (毛利/哪道菜/单品成本) → 改导向**加权毛利/总毛利率** (来自 `finance_summary` 真成本)。
- **加 `NOTE_DISH_MARGIN_ABSENT`** (仿 `NOTE_REVIEW_ABSENT_NEXTACTION`): "中餐单品用量难固定, 单品毛利算不准; 已给总毛利率/加权毛利。" — 修静默丢弃缺陷。
- **中餐口径焊进 fact 标签** (Fable #6: reconciler 只核数字不核限定词): facts_index key 用"加权毛利率/总毛利率", 不出现无限定的"单品毛利 X 元"。

### P1.2 weather 标注合成 (Fable #5)
- `_render_weather` 加"演示用合成天气数据"标注。

### P1.3 restaurant-gold 同比环比 (唯一真 build)
- 新 gold 查询 `period_comparison(factory_id, metric, window)`: 跑 `agg_daily` (+ `agg_daily_cost` 算毛利率, 真租户 NULL 则毛利率同比环比降级) 门店/连锁级, 算同比 (去年同期) + 环比 (上一等长周期)。参考 `api/yoy.py` 分类但**跑 gold 表非上传 Excel**。
- 接进 FactBook: 聚合指标带 (同比 +X% / 环比 +Y%)。诚实降级: 对比期无数据 → "去年同期无数据"/"无上一周期"。
- **DEMO 显示前提** (Fable #4): 现 DEMO 窗 ~60 天 (青花椒仅 14 天) → 同比必降级、"这两个月"环比也降级。**为让 demo 真显示同比环比, 扩 DEMO agg_daily seed 窗**至含去年同期 + 多个等长周期 (经真 seed 路径, `demo_seed` 标记)。

---

## 4. P2.1 — 供应商价格异常威慑 (真反回扣 win, Fable 验证诚实薄接线)
- 新维度 `supplier_anomaly`: 触发词 供应商/涨价/采购异常/回扣/偷偷/价格。
- 装配调 `detect_price_anomalies(pool, factory_id)` (自包含读 `agg_supplier_price` 带 RLS), FactBook render: 物料 / 涨幅 deltaPct / 风险级 / 方向 / 申诉状态。**威慑非处罚**口径 (记录趋势, 要解释)。
- grounded: 异常数字进 facts_index (逐条 collision-safe 中文标签, Fable #7 手工工作量, budget 进计划) 让 FactReconciler 核。

---

## 5. DEMO 数据 (走真管线, 无逃生门 — Fable #5)
**原则**: seed 只从 raw/silver 进, 流经真 transform; **禁止直接 seed gold 表** (那是换皮造假)。清晰 `demo_seed` 来源标记 (否则 `cost_source` 标记本身撒谎)。每个 demo-seeded 维度 render 带"演示数据"标注。
- **供应商价格**: seed 走 `supplier_price_ingest_etl` 真管线 (原料批次 → `agg_supplier_price`), 含 1-2 演示异常 (某供应商青菜价环比 +25%)。
- **agg_daily 扩窗** (P1.3): 经真 seed 路径补去年同期 + 多期, `demo_seed` 标记。
- **迁移**: DEMO_REST-scoped, RLS GUC in-txn, 幂等 additive; **绝不碰真租户** (LIUSHANMEN/F006/qhj)。部署后**必查 seed 真 populate 行数** (RLS 挡读→0 行静默)。⚠️ 已有两个 `V20260709_03` (dish_bom + weather), 新迁移**别重号**。

---

## 6. 跨切面铁律
- **诚实降级**: 底盘数据缺 → `NOTE_*` + 下一步 (fool-proof Rule5), 绝不静默丢弃/编数。
- **中餐口径**: 无单品毛利承诺; 主轴加权毛利/总毛利率 + 同比环比; 限定词焊进 fact 标签 (非靠 LLM 自觉)。
- **grounding**: 新维度数字全进 facts_index (逐条中文标签, collision-safe), FactReconciler 照核; magnitude gate 已在。
- **缓存/飞轮**: 新维度进 plan_key; 捕获只 llm 路径。

---

## 7. Dispatch (organizer + Fable gate)
| 块 | model | 通道 | 🔒 |
|---|---|---|---|
| P1.1/P1.2/P1.3/P2.1 Python 接线 + 同比环比 build | **Sonnet in-harness** (grounding 铁律自动可见) | in-harness | 🔒 grounding/诚实 |
| DEMO 真管线 seed (供应商异常 + agg_daily 扩窗) | Sonnet (Opus 钉口径) | in-harness | 🔒🔒 prod DB DEMO_REST only |
| 手机端显示新维度 (如需) | Composer/Sonnet | — | |
| **红线终审** | **Fable 对抗 gate** (诚实降级正确性 / 供应商异常 grounding / 中餐标签焊死 / seed 走真管线不碰真租户 / 同比环比降级正确) → **Opus 出货闸从 main 部署** | | 🔒🔒 |

---

## 8. 不在本期 (后续 spec)

### P2.2 反回扣 GAP/损耗 (Fable 砍掉现口径, 用诚实口径认真建)
- **诚实口径** (Fable 给): **逐食材** 理论消耗 (配方×销量) vs **领料/盘点/损耗实际** (requisition `est_cost` / stocktaking `difference_cost` / wastage `estimated_cost` — 唯一独立于营收的实际信号), **按 `pos_name_resolver` 覆盖率 gating** (未解析菜名对理论贡献 0, 不 gate 会按构造冤枉人)。
- **需先建的 grain** (非接线, 真 build): per-dish 销量源 (DEMO 现无) / 菜→档口 映射 (现不存在) / 名称解析覆盖率门。
- 档口 variance 待 grain 建成; 现只能诚实做到 逐食材 + 按 section 损耗额。

### 其余 P3
- 14 指标诊断引擎 + Rx 处方全量接入 (+ 补 2 缺失 playbook: channel_margin_low / stored_value_dependency_high)。
- NL2SQL 扩 gold/agg 餐饮表 (现只跑上传 Excel)。
- P&L 一页纸接入合成。
- 邓总 P0 取数 OCR / benchmark 网络。

---

## 9. 验收
- DEMO 聊天"哪个供应商偷偷涨价" → 列真实感异常 (grounded, 威慑非处罚); "营收咋样" → 带同比环比 (扩窗后真显示); "哪道菜毛利高" → 诚实降级到加权毛利 (**不再假 86 元**, 无单品承诺)。
- 无数据维度 → 诚实 NOTE + 下一步, 零编造; 中餐标签焊死不靠 LLM 自觉。
- headed 手机端验证真显示 (非方块/raw JSON)。
- Fable gate 过 + 从 main 部署 + 核运行产物含改动。
