# Sprint 11 MealClaw — Q6 Option B Round 3 verify result (post-deploy)

**日期**: 2026-05-24
**Owner**: Cretas 餐饮 AI chat (PM 协调)
**触发**: Steve picked Q6 Option B → PR #254 merged + deploy to prod 8086 (commit `74accc37b`) + Playwright re-verify

---

## TL;DR — Routing fix 成功, 但 demo 仍不够 ready 因数据窗口 mismatch

| Layer | 状态 | Evidence |
|---|---|---|
| (1) Workdesk UI bug (Vue event-arg) | ✅ **FIXED + DEPLOYED** | PR #254 merged 74accc37b, prod 8086 vite hash refresh OK |
| (2) Intent routing 4/4 phrase | ✅ **FIXED + VERIFIED** | Playwright 5/5 PASS, API body shows `intentCode: RESTAURANT_ECONOMICS_ANALYSIS` |
| (3) Customer 看到真 ¥1.9M P&L in UI | ❌ **NOT READY** | UI default `month: "上月"` = April 2026, no data; only `month: "2025-12"` shows P&L |

---

## Round 3 Playwright evidence (post-deploy)

**Spec**: `web-admin/tests/e2e-customer-journey/mealclaw-customer.spec.ts` (unchanged)
**Target**: prod `http://139.196.165.140:8086` (post-deploy)
**Account**: `qhj_warehouse_mgr / 123456` / `RES_3101_009`
**Run**: 2026-05-24 21:07-21:10, 5/5 PASS, 2.3 min

### 4 phrase × API + UI 对比

| Phrase | API intentCode | dataAvailable | UI formattedText |
|---|---|---|---|
| 帮我看上月损溢异常 | `RESTAURANT_ECONOMICS_ANALYSIS` ✅ | false | "部分数据不可用: P&L 一页纸 / 档口损溢 / 成本刚性..." |
| 损益分析 | 同 ✅ | 同 | 同 |
| 上月成本 | 同 ✅ | 同 | 同 |
| 哪个菜亏钱 | 同 ✅ | 同 | 同 |

**Compared to Round 2 (pre-Workdesk-fix)**:
- ✅ intent now correct (was `MATERIAL_TODAY_RECEIVING_QUERY` for all 4)
- ✅ no more "今天暂无待收货" misleading answer
- ❌ but customer 看到 "数据不可用" 而非 ¥1.9M numbers

### Java log evidence (本次 smoke 期间)

```
2026-05-24 09:08:14 RestaurantFinancialMetricsFetcher: no REVENUE+COST data for factory=RES_3101_009 range=2026-04-01..2026-04-30
2026-05-24 09:08:39 (same — "上月" resolved to April)
```

Fetcher 被正确调用 (Phase 1 wire works) 但 range mismatch.

---

## Root cause: 数据 vs default month mismatch

**Backfill data in cretas_prod_db.smart_bi_finance_data**:
```
factory_id | count | min        | max        
RES_3101_009 | 31    | 2025-12-01 | 2025-12-31
```

**Resolution table** (`RestaurantFinancialMetricsFetcher.resolveMonthRange`):
| Input | Resolved range | Has data? |
|---|---|---|
| `2025-12` | 2025-12-01..2025-12-31 | ✅ 31 rows ¥1,935,193 |
| `上月` (default) | 2026-04-01..2026-04-30 | ❌ no data |
| `本月` | 2026-05-01..2026-05-31 | ❌ no data |
| `2025年12月` | 2025-12-01..2025-12-31 | ✅ same as above |

UI WarehouseKeeperWorkdesk fix (PR #254) now passes `context: { month: '上月' }` by default. Backend resolves to April 2026 → fetcher returns empty → Python emits "未提供 financial_metrics" canonical skip.

---

## Curl proof — data path 真 works (when month right)

```bash
# 2026-05-24 09:10:47 curl with explicit Dec 2025:
POST /api/mobile/RES_3101_009/ai-intents/execute
body: {"userInput":"哪个菜亏钱","context":{"month":"2025-12"}}
→ intentCode: RESTAURANT_ECONOMICS_ANALYSIS
→ summary.dataAvailable: true
→ headline: "本店 current 盈利 ¥1,935,193 (100.00%)"  ✅
```

Java log confirms:
```
2026-05-24 09:10:47 RestaurantFinancialMetricsFetcher: factory=RES_3101_009 range=2025-12-01..2025-12-31 revenue=1935193.27 ✅
```

---

## Demo readiness options (post Round 3)

### Option B.1 — Customer types explicit month (demo brief change)
- Update demo brief: 请客户问 "**2025年12月损溢异常**" 或 "**去年12月哪个菜亏钱**"
- Cost: 0 (text only)
- Risk: low — works immediately; client text is slightly different from MealClaw-comparable "上月损溢"

### Option B.2 — Backfill more months (proper)
- Backfill April/March/Feb 2026 data so "上月" works
- Cost: 1-3 hr ETL re-run with date range expansion
- Risk: low — same ETL pattern, just date param

### Option B.3 — Smart fallback in Fetcher (long-term)
- Modify `RestaurantFinancialMetricsFetcher`: if "上月" returns empty, fallback to latest-available-month (Dec 2025)
- Add response field: `"actualMonth": "2025-12 (latest available)"`
- Cost: 2-3 hr code + test
- Risk: medium — silent fallback can confuse user

### Option B.4 — Hardcode UI default (demo-only hack)
- Workdesk passes `month: "2025-12"` instead of "上月"
- Cost: 5 min
- Risk: high — only works for THIS factory until next month's data exists

---

## Q7/Q8 DoD recheck (post Round 3)

| DoD | 状态 | Evidence |
|---|---|---|
| (a) audit doc merged | ✅ Q1-Q6 (PR #215) + Q7/Q8 fresh (PR #253) + Q6 Option B fix (PR #254) + THIS Round 3 doc | merged |
| (b) mealclaw-customer.spec local PASS | ✅ 5/5 PASS Round 3 against prod 8086 | this doc |
| (c) 4 PNG + 6 UX PNG + 1 video ≥3min | ✅ Round 1 (initial), Round 2 (post-fix test env), Round 3 (post-deploy prod) | round3/ |
| (d) Q1-Q8 全 8 节真证据 | ✅ all 8 sections in [deep audit doc](sprint-11-mealclaw-output-quality-deep-audit.md) + [Q7/Q8 fresh](sprint-11-mealclaw-q7-q8-fresh-evidence.md) + [Q6 Option B fix](sprint-11-mealclaw-q6-option-b-fix.md) + this | ✅ |
| (e) Q6 明确 decision + 风险 + 失望率% | ✅ Steve picked B 2026-05-24 21:00, fix landed, NOW B.1-B.4 sub-decision needed for demo content | this doc §Demo options |

---

## Next action (Steve 决策)

**Routing 100% fixed. 数据窗口 mismatch 阻塞 customer 看到真 P&L.**

请选 B.1 / B.2 / B.3 / B.4 (上述 §Demo readiness options):
- **B.1 推荐 (零成本)**: 改 demo brief 说 "请客户问 '**2025年12月**损溢'"
- **B.2** (1-3 hr): 我 backfill April/March/Feb 2026 数据
- **B.3** (2-3 hr): code: smart fallback latest-available-month
- **B.4** (5 min, demo hack): UI 默认 "2025-12"
