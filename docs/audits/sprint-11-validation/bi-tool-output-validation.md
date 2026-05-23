# Item 2: 7 BI 接口全 14 case Test (depth=deep)

**Date**: 2026-05-23
**Skill**: depth-first-e2e Rule 1+2 (every test depth=deep, content verdict not just status)
**Methodology**: 7 interfaces × (Path A explicit + Path B natural language) = 14 case (含 2 extra NL cases for INDICATOR_QUERY)
**Verdict**: ⚠️ **8/14 routing OK (57%) + 7 真实数据全是 F999_MOCK 镜像 (per Item 1 BLOCKER) + 2 critical bugs found**

---

## Test runner

`C:/temp/item2-bi-14case-raw.json` (uncommitted, raw evidence; key facts excerpted below).

Login: f006_admin / 123456 → /api/mobile/F006/ai-intents/execute
Sample tested 2026-05-23.

---

## Results (encoding fixed)

| # | Expected | Path | Query | Actual Intent | Tool | Status | Verdict | Content Sample |
|---|---|---|---|---|---|---|---|---|
| 1 | INDICATOR_QUERY | A explicit | "查询指标" + {indicator_code:AVG_TICKET_PRICE} | INDICATOR_QUERY | indicator_query | SUCCESS | ✅ routing / ⚠️ content mirror | "客单价 当前 37.3886 元 (状态: GREEN), 近 30 数据点" |
| 2 | INDICATOR_QUERY | B NL | "今天客单价多少" | INDICATOR_QUERY | indicator_query | SUCCESS | ✅ routing / ⚠️ content mirror | (same as 1) |
| 3 | LINEAGE_QUERY | A explicit | "溯源" | LINEAGE_QUERY | null | NEED_MORE_INFO | ⚠️ Tool needs real batch_id, no real test possible | "需要更多信息才能完成此操作" |
| 4 | LINEAGE_QUERY | B NL | "这批用了哪些原料" | **BATCH_CONSUMPTION_QUERY** | batch_consumption_query | **FAILED** | ❌ misroute + failed | "执行失败" |
| 5 | INDICATOR_COMPARISON | A explicit | "对比指标" + 2 codes | INDICATOR_COMPARISON | indicator_comparison | SUCCESS | ✅ routing / ⚠️ content mirror | "对比 2/2 个指标 — 全部正常" |
| 6 | INDICATOR_COMPARISON | B NL | "客单价和翻台率比一下" | INDICATOR_COMPARISON | indicator_comparison | SUCCESS | ✅ routing / ⚠️ content mirror | (same as 5) |
| 7 | INDICATOR_ALERT | A explicit | "看告警" | INDICATOR_ALERT | indicator_alert | SUCCESS | ✅ routing / ⚠️ content mirror | "近 1 个告警 — 1 个黄灯" |
| 8 | INDICATOR_ALERT | B NL | "哪个指标在报警" | INDICATOR_ALERT | indicator_alert | SUCCESS | ✅ routing / ⚠️ content mirror | (same as 7) |
| 9 | RESTAURANT_ECONOMICS_ANALYSIS (Composite, PR #186) | A explicit | "餐饮经营分析" | RESTAURANT_ECONOMICS_ANALYSIS | restaurant_economics_analysis | SUCCESS | ⚠️ status SUCCESS but content "暂无数据" overstate | "三项数据不可用: P&L 一页纸 / 损溢分析 / 成本归因. 已基于可用数据完成分析, 建议用查无数据明确标注." |
| 10 | RESTAURANT_ECONOMICS_ANALYSIS | B NL | "帮我看上月损溢异常" | RESTAURANT_ECONOMICS_ANALYSIS | restaurant_economics_analysis | SUCCESS | ⚠️ same 暂无数据 | (same as 9) |
| 11 | SMART_INDICATOR_QUERY (Skill, D6a a671ed7e9) | A explicit | "智能指标" + intentCode | **null** | null | **FAILED** | 🔴 **intent NOT FOUND in DB** | "未找到意图配置: SMART_INDICATOR_QUERY" |
| 12 | SMART_INDICATOR_QUERY | B NL | "看几个指标" | INDICATOR_COMPARISON | null | NEED_MORE_INFO | ❌ misroute to comparison, ask params | "好的，正在为您执行「指标对比」… 还需要您提供以下信息: 1. 请提供 1-10 个指标代码..." |
| 13 | INDICATOR_QUERY | C NL | "食安通过率怎么样" | **null** | null | COMPLETED | 🔴 **LLM fallback generates random text, NOT indicator query** | "根据最新的运营数据画像，工厂内部未开展任何实际生产...建议措施: 1. 启动生产计划: 将批次 PB-PP-AUTO... 改为 IN PROGRESS..." |
| 14 | INDICATOR_QUERY | D NL | "良品率怎么样" | **REPORT_QUALITY** | null | NEED_CLARIFICATION | ❌ misroute, negative_keyword V_23_12 ineffective (per Item 4) | "您的请求可能匹配多个意图，请明确您想要执行的操作。" |

---

## Summary by interface (7 interfaces)

| Interface | A explicit | B natural | Notes |
|---|---|---|---|
| INDICATOR_QUERY | ✅ #1 | ✅ #2, ✅ #14 misroute, 🔴 #13 LLM-hallucinate | NL routing 33% (1/3) |
| LINEAGE_QUERY | ⚠️ #3 needs batch_id | ❌ #4 misroute+fail | 0/2 真测过 |
| INDICATOR_COMPARISON | ✅ #5 | ✅ #6 | 2/2 ✅ |
| INDICATOR_ALERT | ✅ #7 | ✅ #8 | 2/2 ✅ |
| RESTAURANT_ECONOMICS_ANALYSIS (Composite PR #186) | ⚠️ #9 content unavailable | ⚠️ #10 same | 2/2 status SUCCESS but content blank |
| SMART_INDICATOR_QUERY (Skill D6a a671ed7e9) | 🔴 #11 intent NOT in DB | ❌ #12 misroute | 0/2 (intent 注册漏 ship) |
| (INDICATOR_QUERY extra NL coverage) | — | #13 #14 above | both fail |

---

## Critical bugs found (Sprint 12 P0+)

### Bug A: SMART_INDICATOR_QUERY intent NOT in DB
- **Tool**: smart-indicator-query Skill (a671ed7e9 D6a)
- **Symptom**: `POST .../ai-intents/execute {intentCode:SMART_INDICATOR_QUERY}` → "未找到意图配置: SMART_INDICATOR_QUERY"
- **Root cause**: D6a commit shipped Skill code but没注册 intent_code in ai_intent_configs table
- **Sprint 12 fix**: Add V_*_*__smart_indicator_query_intent.sql

### Bug B: LLM fallback generates hallucinated "indicator query" response
- **Tool**: INDICATOR_QUERY when routing 没命中, LLM fallback
- **Symptom**: "食安通过率怎么样" → LLM generates 600-char response about production planning (PB-PP-AUTO-...) — **completely unrelated to FOOD_SAFETY_PASS_RATE indicator**
- **Severity**: 防幻觉 critical violation — LLM 编造业务建议, 老板看到误以为是真分析
- **Root cause**: INDICATOR_QUERY keywords missing "食安通过率怎么样" exact phrase + no fallback to "indicator not found"
- **Sprint 12 fix**: Force INDICATOR_QUERY-class queries route to indicator_query Tool (return "暂无指标" if no match), not LLM open-generation

### Bug C: Composite Tool RESTAURANT_ECONOMICS_ANALYSIS shipped without data
- **Tool**: restaurant_economics_analysis (PR #186)
- **Symptom**: status SUCCESS but content "三项数据不可用: P&L 一页纸 / 损溢分析 / 成本归因" + UI shows nothing useful
- **Severity**: Sprint 11 主成功标准 #2 (Composite) PASS-by-name only, FAIL-by-content
- **Sprint 12 fix**: Either seed F006 data OR mark Composite Tool as "demo" not "ship"

### Bug D (already Item 4): NL routing misroute (#4 #14)
See `routing-scoring-investigation.md`

---

## Final verdict per criterion 主成功标准 #2

| Metric | Required | Actual |
|---|---|---|
| 7 interface × 2 path coverage | 14 | 14 |
| Routing accuracy | ≥9/14 | **8/14 (57%)** |
| Content business correctness | ≥9/14 | **0/14 真实** (all mirror data per Item 1) |
| Status SUCCESS | most | 10/14 SUCCESS (3 overstate) |
| Critical bugs found | 0 | **3 P0 bugs** (SMART intent 没注册 / LLM 幻觉 / Composite 空数据) |

**Item 2 verdict: 🔴 BLOCKER** — Sprint 11 Composite + smart Skill 都 not actually shipped (intent 注册漏 / 数据缺), Tool 调用层 4/4 OK but data 全是 mirror.
