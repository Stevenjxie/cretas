# Sprint 11 P0 Bug Fix Tracker

**Coordinator**: AI 工厂 chat (worktree `sprint11-indicator-keywords-seed-2026-05-22`)
**Created**: 2026-05-23
**Source**: PR #220 audit (AI 工厂 chat Goal 6/6 validation) → 3 P0 + STOP signal PR #224

---

## P0 ownership matrix

| P0 # | Bug | Owner chat | Worktree | Dispatch doc | PR # | Commit SHA | Deploy | Re-test | Status |
|---|---|---|---|---|---|---|---|---|---|
| P0-1 | SMART_INDICATOR_QUERY intent 漏 ship | BI chat | `sprint11-d5` | `docs/superpowers/dispatch/2026-05-23-p0-1-smart-indicator-query-intent.md` | — | — | — | — | 🟡 dispatched, awaiting BI chat ack |
| P0-2 | LLM hallucinates "食安通过率怎么样" → 编造 production text | BI chat OR AI 工厂 chat (Steve assigns) | TBD | `docs/superpowers/dispatch/2026-05-23-p0-2-llm-hallucination-guard.md` | — | — | — | — | 🟡 dispatched, awaiting Steve owner-assign |
| P0-3 | RESTAURANT_ECONOMICS Composite blank — **客户演示 BLOCKER** | 餐饮 chat | `mealclaw-pm-coord` | `docs/superpowers/dispatch/2026-05-23-p0-3-mealclaw-composite-rebuild-or-brief-rewrite.md` | — | — | — | — | 🔴 dispatched + STOP signal sent (PR #224 merged), awaiting Option A/B decision |

---

## 6h checkin log

### Checkin #1 — 2026-05-23 (P2 dispatch ship time)
- ✅ STOP signal: PR #224 `be06b9613` merged main
- ✅ 3 P0 dispatch prompts ship: this commit batch
- ✅ BI chat already responding (PR #222 `5242d9c82` "Mirror data warning banner" — Item 1 BLOCKER response, NOT P0-1/2 yet)
- ⏳ Pending: BI chat ack of P0-1+P0-2, 餐饮 chat decision A/B for P0-3
- Next checkin: +6h or when any chat status changes

### Checkin #2 — (scheduled +6h)
- [ ] P0-1 BI chat ack? PR #?
- [ ] P0-2 owner assigned? PR #?
- [ ] P0-3 Option A/B decision? PR #?
- [ ] Steve confirm STOP signal received?

### Checkin #3 — (scheduled +12h)
- [ ] P0-1 deployed + re-tested?
- [ ] P0-2 deployed + 4 hard cases verified?
- [ ] P0-3 fix deployed + Composite returns 业务数据?

### Checkin #4 — (scheduled +24h)
- [ ] All 3 P0 fixed + verified?
- [ ] STOP signal lifted (Steve approves Wechat invite resend)?
- [ ] Sprint 11 真实 retro updated?

---

## Escalation paths

- **P0-1**: if no BI chat ack within 6h → AI 工厂 chat coordinator pings Steve to verify BI chat status
- **P0-2**: if no owner assigned within 12h → AI 工厂 chat takes ownership (self-implement)
- **P0-3**: if no Option A/B decision within 12h → escalate to Steve immediately (客户演示 risk)

---

## DoD per P0 (depth-first-e2e Rule 10 commit ≠ delivery)

A P0 is "DONE" only when ALL of:
1. PR opened + admin-merged to main
2. Backend / web-admin deployed to prod (SSH systemctl is-active double check)
3. Re-test from this tracker → status PASS + content cross-verify (per smoke_validates_usefulness HARD)
4. Tracker row updated with all 4 columns (PR# / commit SHA / deploy / re-test)
5. (Optional) PR body documents the fix vs Item 2 audit baseline

NOT done signals:
- "应该 fix 了" without PR # evidence → tracker stays 🟡
- Deploy without re-test → tracker stays 🟡 + escalate

---

## Cross-references

- AI 工厂 chat audit (source): `docs/audits/2026-05-23-ai-factory-validation-session-retro.md` + Item 2 doc
- STOP signal: `docs/audits/2026-05-23-mealclaw-stop-customer-demo.md` (PR #224 `be06b9613`)
- Original retros (to update post-fix):
  - 餐饮 chat false done: `docs/superpowers/handoffs/2026-05-22-mealclaw-retrospective.md`
  - BI chat Sprint 11 D6 doc: should be already updated by BI chat
- Sprint 11 true retro: `docs/audits/2026-05-23-sprint-11-true-retro.md` (P4 deliverable)
