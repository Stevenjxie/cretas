# Phase A — Test Plan: Sprint 11 AI Workdesk Full E2E + UX Audit

**Date**: 2026-05-28
**Owner**: AI 工厂 chat (worktree `sprint11-indicator`)
**Goal**: 不只 verify "能跑", audit "客户用着舒不舒服" — 4 维 (UI/UX + 操作顺序 + 使用逻辑 + Sprint 13 backlog)
**Time budget**: 8-15h 真做 (per Steve brief). Phase A 1-2h.

---

## qa-prompt v2.4 起步动作 8 条核对

| # | 项 | 本次答 |
|---|---|---|
| 1 | 数据来源 (新建 vs seed) | **混合**: 12 happy 用 seed (RES_3101_009 / F006 / F001 已 seed data per Sprint 11 audit) + 4 error 主动触发 (seed 不变, 行为 probe) |
| 2 | 跨模块联动 | **N/A**: 此次 audit scope 是 AI Workdesk 内部 (chat → Tool/Skill → response), 不 cross sales→PO→生产链. Workdesk 间一致性走 Phase E §C |
| 3 | 跨模块回写 | **N/A**: read-only AI 查询, 无写操作 to verify. **EXCEPT** Composite Tool 若调写子工具 — 待 Phase B 看实际行为 |
| 4 | 操作方式 | **真 Playwright Locator API** (browser_click / browser_type / Locator API), **禁 page.evaluate('...click()')** per qa-prompt Rule 4 |
| 5 | Console 监控 | **每步必 console_messages level=error** capture into JSON |
| 6 | Network 监控 | **每步必 network_requests** capture POST /ai-intents/execute + GET re-query |
| 7 | UI 文案抓 (MutationObserver) | **必装 install-once-per-page** observer (per Rule 7 — 不准 querySelectorAll race) |
| 8 | 流程依赖错误 UX (四位一体) | **至少 1 error-deep**: network message + toast 文案 + sticky + next action 全 verify |

**任务类型**: 新功能 E2E (Rule 1-9 主) + 部分发版前回归 (Rule 17 反模式扫荡 in Phase D). Rule 15 reviewer 必跑 (Phase C). Rule 16 入口点矩阵 必走 (本 Phase A 设计).

---

## 入口点矩阵 (Rule 16)

**7 Workdesks** confirmed (Steve brief 说 6, 实际 7 — quality 有 chief + manager 两个):

| # | Workdesk | Route | 主 entry | Indicator card click (entry 2)? |
|---|---|---|---|---|
| 1 | SalesOwner | `/workdesk/sales-owner` | chat input + IndicatorCard | ✅ 4 cards (Sprint 11 D7) |
| 2 | FinanceManager | `/workdesk/finance-manager` | chat input | ? (Phase B 验) |
| 3 | ProductionManager | `/workdesk/production-manager` | chat input | ? |
| 4 | Purchaser | `/workdesk/purchaser` | chat input | ? |
| 5 | QualityChief | `/workdesk/quality-chief` | chat input | ? |
| 6 | QualityManager | `/workdesk/quality-manager` | chat input | ? |
| 7 | WarehouseKeeper | `/workdesk/warehouse-keeper` | chat input | ? |

**Test coverage allocation**:
- **Core 12 happy** (Sprint 11 baseline replica + Sprint 12 P0 fix verify): SalesOwner × 4 phrase × 3 accounts
- **Breadth (Rule 16)**: 6 其他 Workdesks × 1 default phrase × qhj_warehouse_mgr = 6 smoke cases (验 NL routing fix 跨 Workdesk 一致)
- **Entry 2 (cross-paradigm)**: SalesOwner indicator card click → 跳转/弹层 → 是否触发 AI chat? (Phase B 探)
- **Error-deep**: 4 cases — LLM timeout / 错路由残留 / Composite blank / 数据缺 friendly

**Total**: 12 + 6 + 4 = **22 cases**

---

## 4 Phrase × 3 Accounts (core 12, Sprint 11 baseline replica)

Phrases (Sprint 11 verdict locked):
1. `帮我看上月损溢异常`
2. `损益分析`
3. `上月成本`
4. `哪个菜亏钱`

Accounts:
- A. `qhj_warehouse_mgr` / `RES_3101_009` (restaurant — main target)
- B. `f006_admin` / `F006` (manufacturer baseline)
- C. `warehouse_mgr1` / `F001` (sister baseline)

Sprint 12 P0 NL routing fix (PR #246) 后预期: RES_3101_009 4/4 → RESTAURANT_ECONOMICS_ANALYSIS. 验 API 已 confirmed 04:13. 此 Phase B re-test UI level.

**5th phrase (餐饮 Phase F.1 verify)**: `哪个菜亏钱` + `context.month=2025-12` payload → 期望 Composite 真返 ¥1,935,193 Dec 2025 P&L per May 24 Phase F.1 resolved.

---

## 4 Error-deep Cases (qa-prompt 四位一体)

| # | Trigger | Expected (per 四位一体) |
|---|---|---|
| E1 | Empty input click 发送 | message 具体 ("请输入问题") + sticky duration:0 + showClose + actionHint "输入示例如..." |
| E2 | Forced misroute (phrase NOT in any shortcut, e.g. "随便聊聊") | should hit LLM fallback CONVERSATIONAL → toast OR friendly 'AI 对话' response, NOT raw error |
| E3 | Composite blank — old behavior probe (尝试 RES_3101_009 with month NOT in seed) | error toast 具体 "无该月数据" + sticky + actionHint "前往 SmartBI 上传 / 选其他月份" |
| E4 | Network 403 (低权限账号 — try warehouse_mgr1 on quality-chief routing) | 403 toast 具体 + sticky + actionHint "需要 quality:read 权限, 联系管理员" |

---

## Depth label allocation (per qa-prompt Rule 1)

| Tier | Count | Coverage |
|---|---|---|
| `smoke` | 6 | 6 其他 Workdesks 各 1 case (breadth, Rule 16) |
| `medium` | 6 | qhj phrases 2/3/4 + f006 phrases 1/2/3 (subset of 12 happy with no roundtrip) |
| `deep` | **6** | qhj phrase 1 (LLM-timeout error probe) + f006 phrase 4 + warehouse_mgr1 phrase 1 + 3 error cases (E1/E3/E4) — each with **Rule 11 roundtrip 3 步** if write op, else **observable-deep** for read-only AI |
| `error-deep` | 4 | E1-E4 全 (四位一体 + sticky + actionHint verify) |

**Target**: ≥3 deep + ≥1 error-deep (per Steve DOD e/f). Above plan gives 6 deep + 4 error-deep.

---

## MutationObserver setup (Rule 7)

Each Workdesk page install once on first nav:

```typescript
async function installToastObserver(page: Page) {
  await page.evaluate(() => {
    if ((window as any).__toastLog) return; // install once
    (window as any).__toastLog = [];
    new MutationObserver(muts => muts.forEach(m =>
      m.addedNodes.forEach(n => {
        if (n.nodeType === 1 && typeof (n as any).className === 'string' &&
            ((n as any).className.includes('el-message') || (n as any).className.includes('el-notification'))) {
          (window as any).__toastLog.push({
            time: Date.now(),
            cls: (n as any).className,
            text: (n as Element).textContent?.trim() || '',
            hasClose: (n as any).className.includes('is-closable'),
          });
        }
      })
    )).observe(document.body, { childList: true, subtree: true });
  });
}
async function readToastLog(page: Page) {
  return page.evaluate(() => (window as any).__toastLog || []);
}
```

After each click, wait 5s + read log to verify sticky behavior.

---

## Roundtrip 3-step audit (Rule 11) — for any write op encountered

For Composite Tool if it triggers writes (e.g. AI 决策建议 → 写日志 / 创建 task), apply:
1. Pre-op: `page.on('request', ...)` capture HTTP body keys
2. POST → verify 2xx + success=true + payload shape (no phantom factoryId, no backend-derived fields)
3. Independent GET → diff sent vs persisted

Sprint 11 AI Workdesk is **mostly read-only**. Only roundtrip-applicable case: if Composite Tool's sub-Tool writes audit log / cache → check writes don't silent-drop fields.

---

## 数据抽检 (Rule 9 — Top/中/末段)

For Composite Tool's `topItems` list (餐厅经营分析 returns 菜品 ranking):
- Top 3 + 中段 (`index = len/2`) + 末段 2-3 行
- Business semantic check: 菜名/金额是否真实业务实体 (NOT "门店名称" / "注：..." / "1.0/2.0/...")
- Cross-verify with SSH SQL on `smartbi_prod_db` per May 24 Phase F.1 resolved

---

## Files to be created

| File | Phase | Purpose |
|---|---|---|
| `web-admin/tests/e2e-customer-journey/sprint11-ai-workdesk-full.spec.ts` | A→B | Playwright spec |
| `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/PHASE-A-test-plan.md` | A | this doc |
| `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/screenshots/*.png` | B | 22 PNG (12 core + 6 breadth + 4 error) |
| `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/videos/*.webm` | B | ≥5min total |
| `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/captures.json` | B | network/toast/text raw |
| `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/sweep/*.md` | D | 3 patterns × sibling grep |
| `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/audit.md` | E-F | final 4-dim findings + reviewer verbatim + sweep verdict |
| `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/gh-issues/*.md` | E-F | Sprint 13 ticket drafts (then `gh issue create`) |

---

## Anti-goal hard checks (per Steve brief)

- ❌ Self-Critic 当 Reviewer (Phase C MUST be separate Agent zero-context)
- ❌ querySelectorAll for toast (must MutationObserver)
- ❌ Top 3 byte-match alone (must 中末段)
- ❌ "下一轮做" / "Sprint 13 修" (per depth-first Rule 4 — Sprint 13 tickets MUST be real gh issues NOT bullets)
- ❌ Commit on local branch as "done" (Rule 10 — push + PR + admin-merge mandatory)
- ❌ 1h paperwork (per goal Steve brief — 8-15h)

---

## DoD checklist (Steve brief 8 条)

| DoD | Phase | Status |
|---|---|---|
| (a) spec file merged main + local PASS | F | 🟡 |
| (b) 12+ PNG + 1 video ≥5min in audit dir | B | 🟡 |
| (c) audit doc含 depth breakdown + 4维 findings + reviewer verbatim + sweep verdict | E-F | 🟡 |
| (d) Sprint 13 backlog ≥5 real gh issues | E-F | 🟡 |
| (e) ≥3 deep L4 (Rule 2 + roundtrip 3 步) | B | 🟡 plan 6 |
| (f) ≥1 error-deep 完整四位一体 | B | 🟡 plan 4 |
| (g) ≥1 silent-drop probe (Rule 11) | B | 🟡 (if write op encountered) |
| (h) PR pushed + merged + Steve 确认 | F | 🟡 |
