# 餐饮合成 AI 接底盘改造 — 设计 spec (P1 诚实+同比环比 / P2 反回扣核心)

> 日期: 2026-07-10
> 状态: 设计已定, 待 user review → writing-plans
> 来源: 战略定位对话 (餐饮连锁 ERP + AI 体检) + 2 个 Explore 底盘挖掘 (origin/main `3d64c7bfc`)

---

## 0. 一句话

把**已经建好但跟聊天 AI 完全没接线的餐饮底盘**(供应商价格异常威慑 / 损耗诊断 / 诊断处方 / 真实 recipe 成本)接进 Python 合成 FactBook, 让 demo 聊天"把底盘真实数据体现出来"; 同时删掉不该存在的假 seed (中餐单品 BOM), 补诚实降级 + restaurant-gold 同比环比。**性质 90% 是接线, 不是重建。**

---

## 1. 现状真相 (Explore 实测, 非猜测)

**两条互不通的 AI 路:**
1. **Python 合成聊天** (`agent/synthesis_engine.py` → `/synthesis/comprehensive`, 即 demo): 只读 9 个扁平 gold 聚合查询 (`gold/queries.py`)。
2. **餐饮体检底盘** (`services/restaurant/sections/*` + Java `RestaurantEconomicsAnalysisTool`): P&L / 损耗 / 成本弹性 / 14 指标诊断引擎+Rx处方 / 供应商价格异常 / 跨店基准。

**关系 = 通过 NOTHING 连接** (两条调用链零共享)。

**真假盘点** (9 维中):
- 真 gold (POS 派生): finance / sales / channel / meal_period / discount / attribution; review (真, 未提升的 raw JSONB)。
- **假**: `dish_margin` (手写 seed `restaurant_sku_forms`, 迁移 `V20260709_03`) + `weather` (`internal_seed_weather` 纯合成)。

**已建好但没接进合成的底盘** (关键复用点):
| 能力 | 文件 | 数据源 | 备注 |
|---|---|---|---|
| 供应商价格异常/威慑 | `gold/price_anomaly.py`, `api/price_anomaly.py`, `api/supplier_price.py`, Java `SupplierPriceComparisonTool` | `agg_supplier_price` ← `cretas_db.material_batches` | **反回扣直击**, 已含检测+申诉ack+RBAC脱敏, 威慑非处罚 |
| 损耗诊断树 | `services/finance/shrinkage_engine.py`, `sections/shrinkage_analysis.py` | 调用方喂 shrinkage_rows (标准 BOM×销量 vs 盘点实际) | 只下钻到档口, **无 日/人** |
| 诊断引擎+Rx处方 | `shared/diagnostics_engine.py` + `knowledge/restaurant/diagnostics_registry.yaml` + `playbooks/*.yaml` | 调用方喂 metrics dict | 14 指标, 结构化 RxAction(owner/时限/优先级/省钱额); **2 指标缺 playbook** (channel_margin_low, stored_value_dependency_high) |
| 真 ETL 桥 | `gold/restaurant_ops_etl.py` | `cretas_db` 领料/损耗/recipes/盘点 → `fact_restaurant_*` → `agg_restaurant_*` + `agg_restaurant_product_cost` | 真配方×真原料价, dish_margin 该接这个而非 seed |
| 同比 YoY | `api/yoy.py` | 上传 Excel 财报 (非 gold 门店表) | 行标签关键词匹配, 脆; **不是门店级 gold** |
| 环比 MoM | `services/financial/yoy_mom_comparison.py` | 同上 (上传 Excel) | 存在但绑在财报图里, **无门店级 gold MoM 端点** |

**结论**: 底盘绝大部分已成型; 真正要 build 的只有**门店级 gold 同比环比**; 其余是接线 + DEMO 真实感 seed。

---

## 2. 架构 (方案 A: 接进合成 FactBook)

保持 demo 单一聊天口 (`synthesis_engine.py`)。每个新维度 = plan trigger + gold/section 数据装配 + FactBook render, demo 聊天与手机端自动显示。

```
用户问 → plan_dimensions (加新维度触发词)
       → _build_factbook (并行拉数据: 现有 gold + 新接的底盘 section)
       → FactBook.to_prompt_lines (加新 _render_*)
       → LLM grounded 叙述 → FactReconciler 核 → charts → demo 显示
```

**装配层**: 底盘 section (shrinkage/diagnostics) 需要调用方喂 dict, 不是纯 gold SQL。新增薄装配函数从 `agg_restaurant_*` / `agg_supplier_price` gold 表组装 section 输入, 喂给现有引擎 (复用引擎, 不重写)。

---

## 3. P1 — 诚实 + 同比环比

### P1.1 dish_margin 去假 + 诚实降级 (🔒 诚实/grounding)
- 删 DEMO_REST `restaurant_sku_forms` 假 seed (8 行, `V20260709_03` 造的)。
- `gold/queries.py:dish_margin()` 数据源改读真 `agg_restaurant_product_cost` (ETL 喂); 空表 → 返回 `dish_count:0` (现有行为)。
- **加 `NOTE_DISH_MARGIN_ABSENT`** (`agent/factbook.py`, 仿现有 `NOTE_REVIEW_ABSENT_NEXTACTION`): "没有配方成本卡, 单品毛利算不准 (中餐用量难固定); 已给总毛利率/加权毛利, 可看理论 vs 实际差在哪个档口。" — 修 Explore 发现的**静默丢弃**缺陷 (现在空表 plan 翻 False 无提示)。
- **中餐口径**: 有 recipe 数据时, 单品毛利标注"理论 (基于配方卡, 中餐仅供参考)", 且总是配聚合加权毛利。

### P1.2 weather 标注合成
- `_render_weather` 加"演示用合成天气数据"标注 (现在未标, 客户会误以为真)。

### P1.3 restaurant-gold 同比环比 (唯一真 build)
- 新 gold 查询 `period_comparison(factory_id, metric, window)`: 跑 `agg_daily` (+ `agg_daily_cost` 算毛利率) 门店/连锁级, 算**同比**(去年同期) + **环比**(上一等长周期)。
- 接进 FactBook: 营收/毛利率等聚合指标带 (同比 +X% / 环比 +Y%)。demo 答"营收 988 万" → "营收 988 万 (同比 +X% / 环比 -Y%)"。
- **诚实降级**: 对比期无数据 (DEMO 无去年 → 同比降级"去年同期无数据"; 环比在 60 天窗内可算)。
- 参考 `api/yoy.py` 的分类逻辑但**跑 gold 表非上传 Excel**。

---

## 4. P2 — 反回扣核心 (杀手锏, 底盘已建)

### P2.1 供应商价格异常威慑接进合成 (🔒)
- 新维度 `supplier_anomaly`: 触发词 供应商/涨价/采购异常/回扣/偷偷/价格。
- 装配层调 `gold/price_anomaly.py` (读 `agg_supplier_price`), FactBook render: 物料 / 涨幅 deltaPct / 风险级 / 方向 / 申诉状态。**威慑非处罚**口径 (记录趋势, 要解释)。
- grounded: 异常数字全进 facts_index 让 FactReconciler 核。

### P2.2 损耗诊断树 + 理论 vs 实际毛利 GAP (🔒)
- 新维度 `shrinkage`: 触发词 损耗/浪费/成本涨/漏在哪/差在哪。
- 装配层从 `agg_restaurant_*` 组 shrinkage_rows (标准成本 = BOM 理论 × 销量; 实际 = 盘点), 喂 `shrinkage_engine`, render 档口 variance + top_offenders + action_items。
- **理论 vs 实际毛利 GAP** 作为核心叙述: 理论加权毛利 (成本卡) vs 实际总毛利率 (P&L) 的差 = 损耗+浪费+回扣; GAP **环比**扩大 = 近期新漏点 → 下钻档口/供应商。这把 P1.3 同比环比 + P2.1 供应商异常 + P2.2 损耗串成一个反回扣故事。
- (日/人 下钻 Explore 说 shrinkage_engine 没有 → **本期只做到档口**, 日/人 留 P3。)

---

## 5. DEMO 数据 (走真管线的真实感 seed)

**原则**: 与假 dish BOM 的区别 = **走真底盘 ETL/查询表, 不是断开的造假表**。demo 数据流经与真租户**一模一样的代码路径**, 只是数字是 demo。清晰标注 demo。

- **供应商价格**: seed `agg_supplier_price` (或经 `supplier_price_ingest_etl` 真管线), 含 1-2 个演示异常 (如某供应商青菜价环比 +25%, 洗洁精 +40)。
- **损耗/recipe/盘点**: seed DEMO_REST 的 `fact_restaurant_recipe_line` (真实感配方) + `fact_restaurant_wastage` + `fact_restaurant_stocktaking`, 经 `restaurant_ops_etl` 滚成 `agg_restaurant_*` + `agg_restaurant_product_cost`。→ dish_margin (理论) / 损耗 / GAP 都有数可显示。
- **迁移**: DEMO_REST-scoped, RLS GUC in-txn, 幂等 (ON CONFLICT), additive。**绝不碰真租户** (LIUSHANMEN/F006/qhj)。
- 部署后**必查 seed 真 populate 行数** (RLS FORCE 挡读 → 0 行静默, 历史踩过)。

---

## 6. 跨切面铁律

- **诚实降级**: 任何底盘数据缺 → `NOTE_*` 提示 + 下一步 (fool-proof Rule5), 绝不静默丢弃、绝不编数 (禁降级处理原则)。
- **中餐口径**: 单品毛利=理论/仅供参考; 主轴=加权毛利/总毛利率 + 同比环比 + 理论vs实际 GAP。
- **grounding**: 新维度数字全进 facts_index, FactReconciler 照核; magnitude gate 已在。
- **缓存/飞轮**: 新维度进 plan_key (语义缓存跨维度不串); 捕获只 llm 路径 (已有)。

---

## 7. Dispatch (organizer + Fable gate)

| 块 | model | 通道 | 🔒 |
|---|---|---|---|
| P1.1/P1.2/P1.3/P2.1/P2.2 Python 接线 | **Sonnet in-harness** (rules 自动可见, grounding 铁律) | in-harness | 🔒 grounding/诚实 |
| DEMO 真管线 seed 迁移 | Sonnet (Opus 钉口径) | in-harness | 🔒🔒 prod DB (DEMO_REST only) |
| 手机端显示新维度 (如需) | Composer/Sonnet | — | |
| **红线终审** | **Fable 对抗 gate** (诚实降级正确性 / 反回扣数字 grounding / 中餐口径不过度承诺 / seed 不碰真租户) → **Opus 出货闸 + 从 main 部署** | | 🔒🔒 |

**Fable gate 理由**: 这是"给客户看的数字 + 反回扣定责"的诚实/grounding 红线; 大批接线落地 → `fable` read-only diff-hunt 抓修复间相互作用 (同族前科直通)。

---

## 8. 不在本期 (P3, 后续 spec)

- 14 指标诊断引擎 + Rx 处方全量接进合成 (+ 补 2 个缺失 playbook)。
- 损耗 日/人 下钻 (shrinkage_engine 现只到档口)。
- NL2SQL 扩到 gold/agg 餐饮表 (现只跑上传 Excel)。
- P&L 一页纸接进合成 (需 gold cost 侧, `finance_summary` 现明确不含成本)。
- 邓总 P0 取数 OCR / 真底盘 wiring 到真租户 / benchmark 网络。

---

## 9. 验收

- DEMO 聊天"哪个供应商偷偷涨价" → 列真实感异常 (grounded); "损耗漏在哪" → 档口 variance + GAP; "营收咋样" → 带同比环比; "哪道菜毛利高" → 理论标注 + 诚实降级到加权毛利 (不再假 86 元)。
- 无数据维度 → 诚实 NOTE + 下一步, 零编造。
- headed 手机端验证真显示 (非方块/非 raw JSON)。
- Fable gate 过 + 从 main 部署 + 核运行产物含改动。
