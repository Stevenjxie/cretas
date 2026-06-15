# Phase 0「映射钉死」upload e2e 验证 (test 8084)

**日期**: 2026-06-15
**验证者**: Opus Organizer (live, 非单测)
**触发**: handoff `2026-06-15-handoff-smartbi-unified-source.md` §未做的验证 —— "单测过≠live好"。
**结论**: ✅ **地基 functional**(pin/review/域反推/RLS/GRANT 全 live work)+ ⚠️ **1 真 fidelity 发现**(率-字段 → 非 canonical `rate_percent`,review 队列不会surface)。

---

## 方法

合成 finance Excel(`factory_id=F_P0VERIFY`,易清理),上传 test 8084 `POST /api/smartbi/excel/auto-parse-async`(无 auth),轮询 + psql 直查权威表。列设计:5 个可映射 canonical(期间/科目/预算数/本月实际/同比增长)+ 1 个故意不可映射(神秘指标XYZ)+ 1 个率-后缀(达成率,精确同义词 = achievement_rate)。

## 结果

| 验证项 | 结果 | 证据 |
|---|---|---|
| schema/RLS/GRANT(test) | ✅ | pin/review 表 FORCE RLS + tenant_isolation(app.factory_id GUC)+ smartbi_user=`arwd`(GRANT 热修 live) |
| 列映射用受控vocab | ⚠️ PARTIAL | 期间→period / 科目→category / 预算数→budget_amount / 本月实际→actual_amount / 同比增长→yoy_rate ✓;**达成率→`rate_percent`(非 canonical)** ✗ |
| 映不出→review_queue | ✅ | 神秘指标xyz: detected_confidence 0.1, method `review_needed`, status PENDING |
| 域反推 | ✅ | infer_domain → `finance`(从 budget_amount/actual_amount/yoy_rate 判别票)。upload.detected_table_type=finance |
| 同模板重传→pin命中 | ✅ | 插 pin(模拟 operator confirm: 达成率→achievement_rate, 神秘指标xyz→actual_amount)→ 重传 same template_key `5b891d52104d6ca6` → 两列 pin 命中,review_queue **清空**。**pin(Layer 0)覆盖了 rate_percent 优先正则(Layer 1)** |

(本月实际→actual_amount_2 是 dedup 撞我故意设的 actual_amount pin,测试 artifact 非 bug。)

---

## ⚠️ 发现: 率-后缀字段 → 非 canonical `rate_percent`,且 review 不 surface

**根因**: `semantic_mapper.py:_classify_by_priority_regex` (line 580-624, "D1" Apr 16 增强) 在同义词匹配**之前**跑,把 `.*rate.*` / 率-后缀列映射到通用 `('rate','rate_percent',0.92)`,且注释明示"wins over substring-matching synonyms"。

- 达成率(以"率"结尾)→ 命中优先正则 → `rate_percent`,从不到 achievement_rate 同义词层。
- 同比增长(无"率")→ 落到同义词层 → `yoy_rate` ✓。

**影响**:
1. 所有率-后缀 canonical(achievement_rate / defect_rate / yield_rate / gross_margin_rate / net_margin_rate / mom_rate / turnover_rate)塌缩成单一 `rate_percent` → 受控字典对率-字段**不权威**(Phase 2"免选/智能"需区分 达成率 vs 出成率 vs 不良率)。
2. `rate_percent` 非 canonical 但 confidence 0.92(高)→ **不进 review 队列** → 自改进环**无法 surface 它**让 operator 纠正。pin **能**覆盖它(实测),但 operator 看不到它需要 pin。

**性质**: 预存 gap(优先正则早于 Phase 0),非 Phase 0 回归。Phase 0 新机制(pin/review/vocab-gate/inference)全 work。

**修复方向(待 Steve 定,🔒 mapper 影响所有上传 — 格外稳)**:
- (A) 优先正则对率-列先查受控字典精确同义词,命中则用 specific canonical,否则才 rate_percent。**风险**: 改变所有租户现有率-列 standard_name → 需先查谁消费 `rate_percent`(materialization/分析/前端)。
- (B) 折进 Phase 2 受控字典 reconciliation(让 vocab 真权威是统一源前提)。
- (C) 率-列 confidence 降阈或路由 review(会淹没队列,不推荐)。

**推荐**: 先小侦察 `rate_percent` 消费者,再走 (A) 的 scoped 修;或折进 Phase 2。不在本验证内擅改共享 mapper。

---

## 清理

F_P0VERIFY 全清(pin/review/field_def/dynamic_data/upload/agg_cache 0 残留)+ /tmp xlsx 删。
