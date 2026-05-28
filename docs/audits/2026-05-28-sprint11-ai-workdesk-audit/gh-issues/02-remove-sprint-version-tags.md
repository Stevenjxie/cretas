# [Sprint 13 P1] Remove 7 customer-visible `Sprint 8 P{X}` developer version tags from Workdesk headers

**Severity**: P1 (customer reads "Sprint 8 P4c" = "未完工的内部 beta", brand erosion)
**Source**: AI 工厂 Sprint 11 AI Workdesk audit `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/audit.md` §A2 leak (143 hits) + reviewer Ticket #2

## Problem

Every Workdesk page header contains a literal sprint-version `<el-tag>` visible to customers in production:

| File | Line | Visible text |
|---|---|---|
| `web-admin/src/views/workdesk/SalesOwnerWorkdesk.vue` | 22 | `Sprint 8 P1 (2026-05-20)` |
| `web-admin/src/views/workdesk/FinanceManagerWorkdesk.vue` | 22 | `Sprint 8 P2 (2026-05-20)` |
| `web-admin/src/views/workdesk/QualityManagerWorkdesk.vue` | 24 | `Sprint 8 P3 (2026-05-20)` |
| `web-admin/src/views/workdesk/QualityChiefWorkdesk.vue` | 28 | `Sprint 8 P4c (2026-05-20)` |
| `web-admin/src/views/workdesk/WarehouseKeeperWorkdesk.vue` | 29 | `Sprint 8 P4a (2026-05-20)` |
| `web-admin/src/views/workdesk/PurchaserWorkdesk.vue` | 28 | `Sprint 8 P4b (2026-05-20)` |
| `web-admin/src/views/workdesk/ProductionManagerWorkdesk.vue` | 33 | `Sprint 10 Loop 5 (2026-05-21)` |

Per audit: 143 leak hits across 17 cases. Every single AI Workdesk page customer opens shows this label.

## Customer impact

- Reads "Sprint 8 P4c" as "内部 beta 标签, 未完工"
- Damages trust ("产品还在内部测试") — especially severe for client demo / 微信演示
- 7/7 Workdesks affected → 100% Workdesk visit coverage

## Fix

One-line change per file: delete the `<el-tag>...</el-tag>` line.

Optional better: replace with `v-if="$store.state.devMode"` to keep for dev inspection.

## Test design

1. Spec assertion (this audit's spec, line 320 leak regex):
   ```ts
   const leak = await page.evaluate(() => document.body.innerText.match(/Sprint\s+\d+\s*[A-Z]\d?[a-z]?/));
   expect(leak).toBeNull();
   ```
2. Optional lint rule: ban `<el-tag>` containing `/Sprint \d/` text via custom Vue ESLint rule.

## Owner suggestion

- **Steve direct** (one-line PR across 7 files, ~5 min work)
- OR any frontend chat
- AI 工厂 chat can drive if Steve delegates

## Effort

10 min — 7 files, 7 line deletions, 1 PR.

## Verification

After fix: re-run AI Workdesk audit spec → A2 leak count drops from 143 → 0.

## Cross-references

- Audit: `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/audit.md` §A2
- Reviewer verbatim: same audit §Phase C ¶3
- Sibling sweep: same audit §Phase D
- Bonus: 9 `// Sprint 8 P*` HTML comments in `AppSidebar.vue:88-103` — stripped in production minify; verify with `npm run build` + grep dist
