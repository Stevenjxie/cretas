# Sprint 11 UX 审计 — 2026-05-23

**Owner**: AI 工厂 chat (worktree `sprint11-indicator`)
**Goal**: Real Playwright UI + 肉眼 UX 审 + 录屏 — disprove or confirm 餐饮 chat #211 false retro 25/35 + Sprint 11 30% claim.
**触发**: Steve 质疑 1h paperwork ≠ 真测.

---

## Deliverables

| # | Artifact | Status |
|---|---|---|
| 1 | `web-admin/tests/e2e-customer-journey/full-customer-flow-2026-05-23.spec.ts` | ✅ committed |
| 2 | 12 PNG (`screenshots/*.png`) | 🟡 P2 in flight |
| 3 | 1 video `.webm` ≥5min (`videos/*.webm`) | 🟡 P2 in flight |
| 4 | `ui-text-12.json` (raw capture) | 🟡 P2 in flight |
| 5 | `output-quality-matrix.md` (60 cell: 4 phrase × 3 account × 5 field) | 🟡 P3 |
| 6 | `ux-state-matrix.md` (72 cell: 12 PNG × 6 dimension) | 🟡 P4 |
| 7 | `mealclaw-cross-verify.md` | 🟡 P5 |
| 8 | `verdict-2026-05-23.md` (A/B/C + 4 deploy decision) | 🟡 P6 |

---

## Spec topology

```
ACCOUNTS (3):
  A. qhj_warehouse_mgr / 123456 / RES_3101_009  ← restaurant target
  B. f006_admin        / 123456 / F006           ← manufacturer baseline
  C. warehouse_mgr1    / 123456 / F001           ← sister/baseline

PHRASES (4, restaurant-flavor):
  1. 帮我看上月损溢异常
  2. 损益分析
  3. 上月成本
  4. 哪个菜亏钱

12 cases = 3 × 4
Each case: login → nav /workdesk/sales-owner → wait initial query settle → fill phrase → click 发送 → wait result/error → screenshot fullPage + capture .formatted-output innerText
```

---

## Smoke validation (1 case, 2026-05-23 02:59)

`qhj_warehouse_mgr__phrase1` ("帮我看上月损溢异常"):
- Login: status=200, JWT captured
- Result card: rendered
- formattedText (284 chars): "### 今日客户跟进概要 ... 暂无最近的微信沟通记录 ... 没有待处理的电话跟进事项 ..."
- **CRITICAL preview finding**: phrase "上月损溢异常" routed to DAILY_CUSTOMER_FOLLOWUP intent (returns empty customer follow-up summary), NOT to RESTAURANT_ECONOMICS_ANALYSIS as 餐饮 chat curl audit assumed. This is a **routing bug + Class B (数据缺)** simultaneously.

Smoke artifacts deleted; full 12-case run in flight.

---

## Verdict legend

| Class | Meaning | Customer impact |
|---|---|---|
| **A 经营建议** | Markdown 有具体业务数据 + Top N + 建议 | demo-ready ✅ |
| **B 数据缺** | "暂无 / 没有 / 不可用 / 请上传" reply only | demo-fail 🔴 |
| **C 混合** | Some real data + some "暂无" | partial ⚠️ |
| **D 错路由** | Phrase 跑错 intent, 结果 unrelated | routing bug 🔴 |
| **E LLM 幻觉** | 编造 business plan / production task etc. | dangerous 🔴🔴 |
