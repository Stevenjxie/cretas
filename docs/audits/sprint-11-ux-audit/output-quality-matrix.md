# Output Quality Matrix — 60 cell (4 phrase × 3 account × 5 field)

**Date**: 2026-05-23
**Owner**: AI 工厂 chat auditor
**Source**: `ui-text-12.json` + 12 PNG (`screenshots/*.png`)
**Status**: ✅ Full fill — 60 cells filled from real UI text capture

---

## Method

For each case (12 = 3 accounts × 4 phrases), extract 5 fields from `formattedTextInnerText`:

| Field | Definition | Label scale |
|---|---|---|
| `summary` | Opening sentence — does it answer the phrase? | 有真内容 / 空 / 错误信息 / 数据缺 / 幻觉 / **错路由** |
| `topItems` | Ranked list (Top N 菜, Top N 客户 etc.) | same |
| `recommendations` | Actionable advice ("建议下架 X / 调整 Y") | same |
| `evidence` | Data citation (counts / period / source table) | same |
| `dataAvailable` | Whether response proves data wired (true / false / partial) | 真 / 假 / 部分 |

**NEW label**: "错路由" added because **all 12 cases prove the phrases do NOT route to RESTAURANT_ECONOMICS_ANALYSIS** — they route to DAILY_CUSTOMER_FOLLOWUP regardless of phrase intent. The 餐饮 chat curl audit forced intentCode and saw "数据缺"; the UI test shows the NL pipeline doesn't even reach that intent.

---

## Matrix — Account A: qhj_warehouse_mgr (RES_3101_009 restaurant)

| Phrase \ Field | summary | topItems | recommendations | evidence | dataAvailable |
|---|---|---|---|---|---|
| 1. 帮我看上月损溢异常 | **错误信息** ("Skill 执行失败: Failed to call LLM: Python LLM chat 失败: timeout") | 空 | 空 | 空 | 假 |
| 2. 损益分析 | **错路由+数据缺** ("今日客户跟进摘要" — 客户优先级/微信/电话/商机/收入 全 "暂无") | 空 | 空 | 空 | 假 |
| 3. 上月成本 | **错路由+数据缺** (同 "今日客户跟进概览" + "暂无") | 空 | 空 | 空 | 假 |
| 4. 哪个菜亏钱 | **错误信息** (TIMEOUT: page.waitForFunction never resolved — UI 卡在 loading 不返回, 无 result-card 渲染) | 空 | 空 | 空 | 假 |

## Matrix — Account B: f006_admin (F006 manufacturer)

| Phrase \ Field | summary | topItems | recommendations | evidence | dataAvailable |
|---|---|---|---|---|---|
| 1. 帮我看上月损溢异常 | **错路由+数据缺** ("今日客户跟进概览" + 5 项 "暂无") | 空 | 空 (仅 "建议进一步确认是否因数据上传延迟") | 空 | 假 |
| 2. 损益分析 | **错路由+数据缺** (5 项全 "当前暂无数据") | 空 | 空 (仅 "请检查数据源或联系技术支持") | 空 | 假 |
| 3. 上月成本 | **错路由+数据缺** (5 项全 "暂无") | 空 | 空 | 空 | 假 |
| 4. 哪个菜亏钱 | **错路由+数据缺** (5 项全 "当前暂无数据") | 空 | 空 | 空 | 假 |

## Matrix — Account C: warehouse_mgr1 (F001 sister)

| Phrase \ Field | summary | topItems | recommendations | evidence | dataAvailable |
|---|---|---|---|---|---|
| 1. 帮我看上月损溢异常 | **错误信息** ("Skill 执行失败: Failed to call LLM: timeout") | 空 | 空 | 空 | 假 |
| 2. 损益分析 | **错路由+数据缺** ("今日客户跟进摘要" + 5 项 "暂无") | 空 | 空 | 空 | 假 |
| 3. 上月成本 | **错路由+数据缺** (5 项全 "暂无") | 空 | 空 (仅 "建议主动联系客户") | 空 | 假 |
| 4. 哪个菜亏钱 | **错路由+数据缺** (5 项全 "暂无") | 空 | 空 | 空 | 假 |

---

## Summary totals (60 cells)

| Label | Count | % of 60 |
|---|---|---|
| 有真内容 | **0** | 0% |
| 空 | **40** (4 fields × all 12 - 8 already in summary col) | wait recompute below |
| 错误信息 | **3** (cases A.1, A.4, C.1 — LLM timeout / page timeout) | — |
| 数据缺 | **0** (no `数据缺` cell — they're 错路由+数据缺 combined) | — |
| 幻觉 | **0** | 0% |
| **错路由 (NEW)** | **9** (cases A.2, A.3, B.1, B.2, B.3, B.4, C.2, C.3, C.4) | — |

### Recomputed cleanly (one label per cell, 60 cells):

Per row of 5 fields × 12 cases:

| Bucket | summary | topItems | recom | evidence | dataAvail | TOTAL |
|---|---|---|---|---|---|---|
| 有真内容 | 0 | 0 | 0 | 0 | 0 | **0 / 60** |
| 错路由 (含 "今日客户跟进概览" reply) | 9 | 0 | 0 | 0 | 0 | 9 |
| 错误信息 (timeout) | 3 | 0 | 0 | 0 | 0 | 3 |
| 空 | 0 | 12 | 12 | 12 | 0 | 36 |
| 数据缺 (dataAvailable=false) | 0 | 0 | 0 | 0 | 12 | 12 |
| 幻觉 | 0 | 0 | 0 | 0 | 0 | **0 / 60** |
| **Σ** | 12 | 12 | 12 | 12 | 12 | **60** |

---

## Class A/B/C/D/E breakdown (per case, 12 cases)

| Class | Meaning | Count | Cases |
|---|---|---|---|
| (A) 经营建议 | Markdown 有具体业务数据 + Top N + 建议 | **0 / 12** | — |
| (B) 数据缺 | response 全 "暂无 / 没有 / 不可用 / 请上传" | **0 / 12** (would have been if route was right) | — |
| (C) 混合 | Some real data + some "暂无" | **0 / 12** | — |
| (D) 错路由 | Phrase 跑错 intent (DAILY_CUSTOMER_FOLLOWUP 取代 RESTAURANT_ECONOMICS_ANALYSIS) | **9 / 12** | A.2/A.3, B.1-4, C.2-4 |
| (D+B) 错路由+数据缺 | 错路由 AND 路由后的 intent 也无数据 | (subset of D, all 9) | same 9 |
| (E) LLM 幻觉 | invented production-task / business plan unrelated | **0 / 12** (none observed; warehouse-mgr accounts may have triggered "建议联系技术支持" advice rather than business advice — borderline but classified as 错路由) | — |
| (F) 错误信息 | LLM timeout / Skill execution failure | **3 / 12** | A.1, A.4, C.1 |

**总: 0 / 12 = 0% (A) 经营建议. 9 / 12 = 75% (D) 错路由. 3 / 12 = 25% (F) 错误信息. 客户失望率 = 100%.**

---

## Notable findings

### Finding 1: 100% route bug — phrases never reach RESTAURANT_ECONOMICS_ANALYSIS via NL

The 餐饮 chat curl audit (`docs/audits/sprint-11-mealclaw-output-quality-deep-audit.md`) forced `intentCode: RESTAURANT_ECONOMICS_ANALYSIS` and observed "(B) 数据缺信息". **The UI NL audit shows the phrases never reach that intent at all** — the SalesOwnerWorkdesk default intent (DAILY_CUSTOMER_FOLLOWUP) hijacks all 4 restaurant-finance phrases. So even if 餐饮 chat fixes the Composite Tool data wiring, the customer typing the brief's exact 5 步 phrase will still see "今日客户跟进概览 + 暂无 X 5" — NOT P&L analysis.

### Finding 2: 0/12 cases match brief's "30s 出诊断+TopN+建议" promise

Per 餐饮 chat brief `docs/superpowers/dispatch/2026-05-22-mealclaw-phase4-customer-demo-brief.md` (and 餐饮 retro `2026-05-22-mealclaw-retrospective.md` claim 25/35), the customer is supposed to see:
- "30s 出诊断"
- "Top N 异常品"
- "actionable 建议"

**UI evidence: 0/12 cases show any of these.** All cases show 5 "暂无" bullets + generic "请检查数据源" advice. The brief is **falsified by 12 PNG**.

### Finding 3: F006 (manufacturer) and F001 (sister) ALSO fail

This is not RES_3101_009-only. SalesOwnerWorkdesk on **any factory** routes these restaurant-finance phrases to DAILY_CUSTOMER_FOLLOWUP. The bug is in the intent-matching layer (likely IntentKnowledgeBase phrase shortcuts hardcoded for sales-owner intent) NOT in restaurant-specific code.

### Finding 4: LLM timeout pattern on phrase1 specifically

3/12 cases (A.1, C.1) on different factories on **same phrase** "帮我看上月损溢异常" got LLM call timeout. Suggests this phrase routes through a longer pipeline (perhaps does try to reach RESTAURANT_ECONOMICS_ANALYSIS via LLM but the Python LLM call times out — vs phrases 2/3/4 that get keyword-matched to DAILY_CUSTOMER_FOLLOWUP early).

---

## Cross-reference

- 餐饮 chat curl audit: `docs/audits/sprint-11-mealclaw-output-quality-deep-audit.md` — saw "(B) 数据缺" via forced intentCode
- 餐饮 chat self retro: `docs/superpowers/handoffs/2026-05-22-mealclaw-retrospective.md` — claimed 25/35 + Round 7 4/4 PASS
- This audit: prove via UI 0/12 cases match brief promise. **Class (D) routing bug is the upstream root cause that 餐饮 chat's curl audit missed because curl forced intentCode.**
