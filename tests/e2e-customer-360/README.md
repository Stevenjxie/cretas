# Customer 360° E2E (Phase G)

Sprint 4 W1 S-CUSTOMER-TAB-1 Playwright specs covering the 21-tab customer detail view.

## Specs

| File | What it verifies |
|---|---|
| `golden-path.spec.ts` | All 21 tabs switch + URL `?tab=` updates + back button returns to list |
| `lazy-load.spec.ts` | defineAsyncComponent + KeepAlive — 5 tab visits = ≤6 chunks, revisit = no extra request |
| `url-state-restore.spec.ts` | Bookmark `?tab=invoices` + refresh both keep correct active tab |
| `rbac-mask.spec.ts` | Receptionist (no `canViewPrice`) sees `****` in 6 price tabs; non-price visible |
| `tracking-crud.spec.ts` | Tab 1 CRUD + 防呆 R2 dialog header (客户名+code) + R3 trackingType 6 options |
| `sales-user-change.spec.ts` | Tab 20 R1 + R2 + R3 dialog elements; optional R4 dedup mutation test |

## Run

```bash
cd tests/e2e-customer-360
npm install -D @playwright/test   # one-time
npx playwright install chromium    # one-time

# Local dev (default base URL = http://localhost:5173)
npx playwright test

# Specific spec
npx playwright test golden-path.spec.ts

# Against deployed env
PLAYWRIGHT_BASE_URL=https://admin.cretaceousfuture.com npx playwright test
```

## Environment variables

| Var | Default | Purpose |
|---|---|---|
| `PLAYWRIGHT_BASE_URL` | `http://localhost:5173` | web-admin base URL |
| `E2E_ADMIN_USER` | `factory_admin1` | account with all permissions |
| `E2E_ADMIN_PASS` | `123456` | admin password |
| `E2E_NOPRICE_USER` | `sales_receptionist1` | account WITHOUT `CAN_VIEW_PRICE` (rbac-mask test) |
| `E2E_NOPRICE_PASS` | `123456` | receptionist password |
| `E2E_CUSTOMER_ID` | _(auto-pick first row)_ | explicit customer id to test against |
| `E2E_MUTATE` | _(unset)_ | set `=1` to enable write-side tests (tab 20 submit + R4 dedup) |

## Pre-flight checks

1. **Backend running**: `curl http://localhost:10010/api/mobile/health` returns 200
2. **Web-admin dev server**: `cd web-admin && npm run dev` (or built dist served)
3. **Test customer exists**: At least one customer in the target factory; ideally one with orders/invoices/payments
4. **Sprint 4 W1 backend merged**: Flyway `V20260605_02` + `V20260605_03` applied (customer_sales_user_history table + tracking_type column)

## Notes

- `rbac-mask.spec.ts` auto-skips if `E2E_NOPRICE_USER` is empty
- `sales-user-change.spec.ts` R4 dedup mutation test auto-skips unless `E2E_MUTATE=1` to avoid polluting prod
- All specs use `page.screenshot('on')` — failures retain screenshots under `test-results/`
