# Escalation — Backend RESTAURANT_ECONOMICS_ANALYSIS returns "成本刚性 unavailable" for RES_3101_009 (incl. 2025-12)

**From**: sprint13-frontend-workdesk chat (#304 frontend remediation)
**To**: backend / 餐饮 chat (owns S13-001)
**Date**: 2026-05-29
**Severity**: P1 — this, NOT a frontend bug, is why the customer sees no business value from the Sprint 12 routing fix.

## Summary

While doing the #304 frontend root-cause investigation I confirmed (real headed browser + direct API on prod 139→10010) that:

- Routing is correct: the Workdesk's real request `{userInput, context:{month}}` (no intentCode) routes to `RESTAURANT_ECONOMICS_ANALYSIS` for all tested phrases/accounts. ✓
- The frontend now renders the user's economics answer in an independent card (D-fixes shipped this PR). ✓
- **But the Composite returns degraded content for RES_3101_009 in every month I tried — including 2025-12, the month that is supposed to hold the ¥20.6M P&L:**

| phrase | context.month | intentCode | resultData.summary.dataAvailable | message |
|---|---|---|---|---|
| 哪个菜亏钱 | 2026-04 | RESTAURANT_ECONOMICS_ANALYSIS | false | 本月暂无营业数据 |
| 哪个菜亏钱 | 2025-12 | RESTAURANT_ECONOMICS_ANALYSIS | false | 部分数据不可用: 成本刚性 |
| 2025年12月哪个菜亏钱 | 2025-12 | RESTAURANT_ECONOMICS_ANALYSIS | false | 部分数据不可用: 成本刚性 |

Because `summary.dataAvailable !== true`, the structured P&L card (which the frontend now supports on SalesOwner, matching WarehouseKeeper) stays hidden and the user only sees the honest "部分数据不可用" degradation line. **No frontend change can surface the ¥20.6M while the Composite reports dataAvailable=false / cost_rigidity missing.**

## Likely cause (from project memory, please verify)

`feedback_smartbi_repo_uses_primary_datasource` + the May-29 mealclaw-backend close notes: the ETL writes `smartbi_prod_db` but the Java Composite reads `cretas_prod_db`; the RES_3101_009 cost rows (371 COST ¥4.06M) were COPY'd via the Option-C stopgap. If the cost-side COPY/wiring for the Composite's `cost_rigidity` sub-tool didn't land for 2025-12, `dataAvailable` stays false. This is the S13-001 data-wiring item.

## What the frontend already did (so backend can verify end-to-end)

Once the Composite returns `summary.dataAvailable=true` with `summary.data.data.{headline,headlineColor,pnlLines,storeName,period}`, **both** SalesOwnerWorkdesk and WarehouseKeeperWorkdesk will render the structured 餐厅经营分析 card automatically (same `extractRestaurantPnl` shape). No further frontend change needed.

## Asks

1. Confirm whether RES_3101_009 cost data for 2025-12 is reachable by the `RESTAURANT_ECONOMICS_ANALYSIS` Composite's cost_rigidity sub-tool in `cretas_prod_db`.
2. If not, land the cost COPY/wiring (S13-001) so `dataAvailable=true`.
3. Ping this chat / the verify chat when done so the end-to-end "user sees ¥20.6M P&L" DOD can be re-verified.
