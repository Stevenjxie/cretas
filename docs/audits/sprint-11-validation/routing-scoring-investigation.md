# Item 4: 6/10 Misroute Root Cause + Same-Cause Sweep

**Date**: 2026-05-23
**Skill**: depth-first-e2e Rule 8 (same-cause sweep before claim/commit)
**Verdict**: ⚠️ V_23_12/13 `negative_keywords` 完全 useless. Real fix needs `IntentKnowledgeBase.phraseToIntentMapping` + `domainBonus` 改造 — Sprint 12 work.

---

## 1. Scoring algorithm source

`backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java` lines 4577-4699 (`parallelScoreMatch`):

```java
double finalScore = phraseScore * scoreConfig.getPhraseWeight()      // 1.0
                  + semanticScore * scoreConfig.getSemanticWeight()  // 0.6
                  + keywordScore * scoreConfig.getKeywordWeight()    // 0.25
                  + domainBonus * 1.0                                 // up to 0.25
                  + opTypeBonus * 1.0;                                // ±0.15
finalScore = Math.max(0.0, finalScore - negativeKeywordPenalty);   // 0.15/kw
```

Weights from `IntentMatchingConfig.java:441-453`:
- **phraseWeight = 1.0** (5x bigger than keyword)
- semanticWeight = 0.6
- **keywordWeight = 0.25**
- negativeKeywordPenalty = 0.15 per matched keyword (max ~1.5 if all 10 match)

## 2. Real test: "良品率怎么样" pre/post V_23_12

| Intent | phrase shortcut hit? | keyword hit | domainBonus (QUALITY) | finalScore |
|---|---|---|---|---|
| INDICATOR_QUERY | NO (not in phraseToIntentMapping) | "良品率怎么样" exact, keywordScore ≈ 1.0 × 0.25 = 0.25 | 0 (not QUALITY domain) | ~0.25 |
| REPORT_QUALITY (pre-V_23_12) | NO | "良品率" substring, keywordScore × 0.25 ≈ 0.25 | up to +0.25 | ~0.5 |
| REPORT_QUALITY (post-V_23_12 with neg "良品率怎么样") | NO | same ~0.25 | +0.25 | 0.5 - 0.15 = **0.35** |

REPORT_QUALITY 0.35 > INDICATOR_QUERY 0.25 → REPORT_QUALITY still wins.
Result: **negative_keyword V_23_12/13 has NO effect on routing outcome** (verified Item 2 test #14 still misroute post-V_23_12).

priority field (90 vs 85) is **tie-break only**, not a multiplier. Different scores → priority useless.

## 3. Same-cause sweep — IntentKnowledgeBase phrase shortcut overlap

Found 13+ phrase shortcuts that DIRECTLY conflict with INDICATOR_* intents. These hardcoded `phraseToIntentMapping.put` entries give `phraseScore = 1.0 × phraseWeight 1.0 = 1.0` to the OTHER intent, overwhelming any keyword/priority configuration in INDICATOR_QUERY.

| File:line | Phrase | Mapped to (NOT INDICATOR_*) | Conflict with INDICATOR_QUERY keyword |
|---|---|---|---|
| `IntentKnowledgeBase.java:2855` | "不良率的趋势" | QUALITY_STATS | "良率"/"良品率" |
| `IntentKnowledgeBase.java:4787` | "不良品率" | QUALITY_STATS | "良品率" |
| `IntentKnowledgeBase.java:5288` | "损耗率" | REPORT_ANOMALY | "损耗率" (key INDICATOR keyword) |
| `IntentKnowledgeBase.java:6755` | "菜品毛利" | RESTAURANT_DISH_COST_ANALYSIS | "菜品毛利" |
| `IntentKnowledgeBase.java:6914` | "客单价" | RESTAURANT_ORDER_STATISTICS | "客单价" |
| `IntentKnowledgeBase.java:6916` | "今天客单价多少" | RESTAURANT_ORDER_STATISTICS | "今天客单价多少" exact |
| `IntentKnowledgeBase.java:6927` | "翻台率" | RESTAURANT_PEAK_HOURS_ANALYSIS | "翻台率" |
| `IntentKnowledgeBase.java:6928` | "翻台率多少" | RESTAURANT_PEAK_HOURS_ANALYSIS | |
| `IntentKnowledgeBase.java:6956` | "损耗率" | RESTAURANT_WASTAGE_RATE | (duplicate of 5288, different file location) |
| `IntentKnowledgeBase.java:6960` | "损耗率多少" | RESTAURANT_WASTAGE_RATE | |
| `IntentKnowledgeBase.java:6964` | "损耗率高吗" | RESTAURANT_WASTAGE_RATE | exact match |
| `IntentKnowledgeBase.java:6966` | "每月损耗率" | RESTAURANT_WASTAGE_RATE | |
| `IntentKnowledgeBase.java:6967` | "损耗率趋势" | RESTAURANT_WASTAGE_RATE | |

⚠️ "今天客单价多少" → RESTAURANT_ORDER_STATISTICS phrase shortcut hits, but real test #2 routed to INDICATOR_QUERY 成功. 假设原因: restaurantPhraseMapping only active for restaurant-business factory_id, F006 是制造业 (factory_type=FACTORY), 跳过 restaurantPhraseMapping. 验证需另外 grep 业态过滤逻辑 — Sprint 12 task.

generalPhraseMapping (lines 2855/4787/5288) **永远 active** — these are the 真路由 blocker.

## 4. Other competing intents (kw 重叠 list per SQL)

```sql
SELECT intent_code, keywords count, neg count, priority
FROM ai_intent_configs WHERE keywords text LIKE '%良品率%|%客单价%|%食安%|%毛利%'
```

| intent_code | kw | neg | priority |
|---|---|---|---|
| FINANCIAL_CHART_GENERATE | 21 | - | 70 |
| INCOME_STATEMENT_QUERY | 8 | - | 85 |
| INDICATOR_COMPARISON | 14 | - | 76 |
| INDICATOR_QUERY | 17 | - | 90 |
| PRODUCT_GROSS_MARGIN_RANKING | 5 | - | 0 |
| QUERY_PER_CAPITA_CONSUMPTION | 4 | - | 0 |
| REPORT_QUALITY | 17 | 11 | 85 |
| RESTAURANT_OPS_GROSS_MARGIN | 6 | - | null |
| SKU_GROSS_MARGIN | 12 | 9 | 85 |

9 intents 含 overlap keywords. V_23_12/13 added negative_keywords only to REPORT_QUALITY + SKU_GROSS_MARGIN + BATCH_CONSUMPTION_QUERY. **7 other competing intents 没碰** — even if negative_keyword worked, they'd still beat INDICATOR_QUERY in some routes.

## 5. V_23_12/13 是否 effective — verdict

❌ **NOT effective**. Three independent reasons:
1. Math: keyword 0.25 weight vs domainBonus 0.25, negative penalty 0.15 不够 push score 下去 below INDICATOR_QUERY's 0.25
2. Phrase shortcut (IntentKnowledgeBase 1.0 weight) trumps everything if matched
3. Only 3/9 competing intents have negative_keywords

Real Item 2 evidence (post-V_23_12 deploy): "良品率怎么样" still routes to REPORT_QUALITY (Item 2 #14).

## 6. 修法决定: Sprint 12 backlog (not this session)

**Why NOT fix this session**: 
- IntentKnowledgeBase is 7000+ lines hardcoded mappings, risk of breaking other routing
- Domain detection / domainBonus is 4-tier weighting system, refactor risk high
- Per Sprint 11 main goal "Validation + cleanup, no new ship", routing rework out of scope

**Sprint 12 P1 tickets**:
1. **V_*_*__indicator_query_phrase_shortcuts.sql** — bypass DB, modify `IntentKnowledgeBase.java` line 6755-6967 to also map INDICATOR queries OR add INDICATOR-specific phrases (e.g. "今天的良品率指标" → INDICATOR_QUERY)
2. **Tag INDICATOR_QUERY with domain=QUALITY/FINANCE/OPS** to get domainBonus on relevant queries
3. **Add config knob**: per-intent scoring weights override (currently global 1.0/0.6/0.25)
4. **Remove generalPhraseMapping line 5288 "损耗率" → REPORT_ANOMALY** (too greedy — should NOT default to REPORT_ANOMALY; either route to INDICATOR_QUERY or fall through to scoring)
5. **Concrete test design**: V_*_smoke test with 14 case from Item 2, post-fix expect ≥12/14 routing correct

## 7. Conclusion

Item 4 same-cause sweep: **13 phrase shortcuts + 9 keyword-overlap intents** all contribute to INDICATOR_QUERY losing scoring competition. V_23_12/13 was a 单点 fix that solves none of them.

This explains:
- 6/10 baseline misroute (Item 2 test set)
- "损耗率高吗" regression (Item 2 #13 LLM fallback after losing scoring)
- Steve's 30% Sprint 11 evaluation needs revise down due to fundamental routing layer not actually serving INDICATOR_QUERY

**Sprint 11 真实 progress 修正**: ~~30%~~ → ~~15-20%~~ → **~10%** (when factor in: data 100% mirror per Item 1, Composite 空数据 per Item 2, SMART_INDICATOR_QUERY intent 没注册 per Item 2 Bug A, routing fundamentally broken per Item 4)
