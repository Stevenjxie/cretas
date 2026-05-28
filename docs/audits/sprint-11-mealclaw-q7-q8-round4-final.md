# Sprint 11 MealClaw — Q7/Q8 Round 4 (FINAL) — B.5 explicit-month UI evidence

**日期**: 2026-05-24 23:07
**Owner**: Cretas 餐饮 AI chat (PM 接手 B.5 — subagent acab1055a38979e75 silently terminated 0 commits)
**触发**: Steve picked B.5 sub-decision; PM ran Round 4 Playwright spec with explicit "2025年12月" prefix phrases against prod 8086 (B.5 logic already in PR #254)

---

## TL;DR — 真情况 (诚实)

| Layer | 状态 | Evidence |
|---|---|---|
| (1) B.5 month-extract UI | ✅ **已在 PR #254 prod** | line 585-622 `parseMonthFromInput` + line 644-673 sendQuery wire |
| (2) B.5 customer phrase → backend correct month | ✅ **VERIFIED Round 4** | API body for "2025年12月..." → "部分数据不可用: **档口损溢 / 成本刚性**" (NO MORE "P&L 一页纸" — P&L IS available!) |
| (3) Customer 看到 真 ¥1.9M P&L 在 UI | ❌ **UI gap** | WarehouseKeeperWorkdesk 只渲染 `formattedText` 不渲染 `resultData.summary.data.headline`. ¥1,935,193 number 在 API response 内, **不在 UI 显示** |

---

## Round 4 Playwright evidence (post-B.5-deploy + explicit month phrases)

**Spec**: `web-admin/tests/e2e-customer-journey/mealclaw-customer-r4-explicit-month.spec.ts`
**Target**: prod `http://139.196.165.140:8086`
**Account**: `qhj_warehouse_mgr / 123456` / `RES_3101_009`
**Run**: 2026-05-24 23:07, 4/4 PASS, 1.6 min

### 4 phrase × API + UI 真对比

| Phrase | API intentCode | API message | UI formattedText | UI 真显 ¥1.9M? |
|---|---|---|---|---|
| 帮我看2025年12月损溢异常 | `RESTAURANT_ECONOMICS_ANALYSIS` ✅ | "部分数据不可用: **档口损溢 / 成本刚性**" | 同 message | ❌ Workdesk 不渲染 |
| 2025年12月损益分析 | 同 ✅ | 同 | 同 | ❌ |
| **2025年12月成本** | **`INDICATOR_QUERY` ❌** | "需要您提供指标编码..." (NEED_MORE_INFO) | 同 | NEW BUG: "成本"-prefix phrase routes to INDICATOR_QUERY |
| 2025年12月哪个菜亏钱 | `RESTAURANT_ECONOMICS_ANALYSIS` ✅ | "部分数据不可用: 档口损溢 / 成本刚性" | 同 | ❌ |

### Round 3 vs Round 4 关键 diff

| | Round 3 (naked phrase) | Round 4 (explicit 2025年12月) |
|---|---|---|
| API intent | RESTAURANT_ECONOMICS_ANALYSIS (post PR #254) | 同 (3/4) |
| API "部分不可用" 包含 | **P&L 一页纸 / 档口损溢 / 成本刚性** (3 项) | **档口损溢 / 成本刚性** (2 项 — P&L 消失!) |
| 推断 | Phase F.1 backfill 命中 = false | Phase F.1 backfill 命中 = **true** (P&L 已生成) |
| UI 实际渲染 | "部分数据不可用..." 消息 | "部分数据不可用..." 消息 (只是少了一项) |
| Customer 直观感受 | "全部不可用" | "大部分不可用" (仍负面) |

---

## 终极 gap: UI 渲染层

API 返回包含 `resultData.summary.data.headline = "本店 current 盈利 ¥1,935,193 (100.00%)"` 以及 4 行 pnlLines, 但 `WarehouseKeeperWorkdesk.vue` 只 render `response.formattedText` 字段 (= "部分数据不可用..." 摘要). 没有 RestaurantEconomics-specific 卡片组件 to render headline + pnlLines.

**Customer 实际看到**:
- Card title 仍硬编码 "今日待收清单" (Workdesk title NOT 跟 intentName 联动)
- Body: "部分数据不可用: 档口损溢 / 成本刚性..."
- ¥1,935,193 number **完全不出现在屏幕**

**Curl 已证 API 真返 ¥1,935,193** (2026-05-24 06:42), 但 UI 不渲染.

---

## Q6 决策再修订 — 真现状

### 已 verified
- ✅ Workdesk routing fix (PR #254) live
- ✅ B.5 month extraction (PR #254 line 585-622) live
- ✅ Phase F.1 ETL data live (cretas_prod_db 31 rows ¥1.9M)
- ✅ API returns real ¥1.9M when customer uses "2025年12月" prefix
- ✅ 10 round1+round2+round3+round4 = 40 screenshots evidence

### 仍 gap
- ❌ WarehouseKeeperWorkdesk 缺 RestaurantEconomicsCard 组件
- ❌ Card title 硬编码 "今日待收清单"
- ❌ Shrinkage + cost_rigidity sub-Tools Phase 2/3 wire 未做 (Sprint 12)
- ❌ "成本"-prefix phrase BERT misroute to INDICATOR_QUERY (Sprint 12)

### Demo readiness 评分 (post Round 4)

| Audience | demo 评分 | 解释 |
|---|---|---|
| 技术演示 (CTO 客户) | 7/10 | API response 可粘 curl + 解释 "P&L 已可, UI render 排 Sprint 12" |
| 业务演示 (老板客户) | 3/10 | 屏幕上 0 个数字 + "数据不可用" 文案 ≈ "产品没准备好" |
| Steve 自演示 (强 narrate) | 5/10 | Steve 边演示边讲 "看, 后台已经能拉出 ¥193万" |

### 最终决策选项 (真 sub-options 给 Steve)

**B.6 (推荐, 1-2 hr)** — WarehouseKeeperWorkdesk 加一段 conditional render: if intentCode=RESTAURANT_ECONOMICS_ANALYSIS && resultData.summary.dataAvailable, 渲染 PNG 卡片 (headline + 4 行 pnlLines). 真 customer-visible ¥1.9M. PM 可接, 或 dispatch UI subagent.

**B.7 (5 min)** — Demo brief 改 "技术演示" 风格 + Steve 边演 边贴 curl 输出: "您看, 后台真有 ¥193万". 接受 UI gap, Steve 弥补.

**B.8 — Sprint 11 close as-is**: 接受当前状态作 audit 成果 (基础设施全 verify, UI render 排 Sprint 12). NOT demo to customer until B.6 done.

**B.9 — PAUSE entire customer demo**: Sprint 11 audit close 但 demo 排到 Sprint 12 (含 B.6 + shrinkage/cost_rigidity wire + 改 card title).

---

## Q7/Q8 真 DoD 复查 (post Round 4)

| DoD 真意 | 状态 | 备注 |
|---|---|---|
| (a) audit doc merged | ✅ | PR #215 (Q1-Q6) + PR #253 (Q7/Q8 r1-r3) + THIS doc r4 |
| (b) mealclaw-customer.spec PASS | ✅ | original 5/5 + r4 4/4 PASS |
| (c) 4 happy PNG + 6 UX PNG + ≥3min webm | ✅ | round1 + round2 (subagent) + round3 + round4 = 40+ PNG + 5 webm |
| (d) Q1-Q8 全 8 节真证据 | ✅ | curl/SQL/grep/Playwright/PNG 齐备 |
| (e) Q6 decision + 风险 + 失望率 + 截图依据 | ✅ | Steve picked B → B.1 (rejected) → B.5 (impl 真已 in PR #254) → NOW B.6/B.7/B.8/B.9 sub-decision |

---

## 关键 honest 总结 (给 Steve)

PM 实测发现 Sprint 11 Q6 Option B 的真"完成"边界比想象**深 1 层**:

1. PR #254 (Workdesk routing + B.5 month extract) — ✅ infrastructure 全 wire
2. Phase F.1 (DB COPY) — ✅ backend 数据真到位
3. Round 4 Playwright — ✅ API 真返 ¥1.9M for 2025年12月 phrase
4. **UI render 层缺 component** — Workdesk 只渲染 formattedText, 不渲染 P&L headline 卡片

Sprint 11 audit goal "prove what's there" → DONE.
Sprint 11 demo goal "customer 在浏览器看到 ¥1.9M" → NOT DONE (1-2 hr UI 组件工作 = B.6).

Steve 请选 B.6 / B.7 / B.8 / B.9.
