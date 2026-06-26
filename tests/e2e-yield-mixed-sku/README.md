# Mixed SKU Yield E2E Campaign

This runner is intentionally isolated from the repository's other Playwright suites.

It uses:
- its own output directory: `.playwright-mcp/codex-YYYYMMDD-mixed-sku-yield-campaign`
- its own persistent browser profile under that directory
- an explicit app URL, defaulting to `http://127.0.0.1:3021`
- an explicit API base URL, defaulting to `http://127.0.0.1:10010`

Run:

```bash
node tests/e2e-yield-mixed-sku/mixed-sku-campaign.mjs
```

Production plan -> yield report -> settlement -> cost analysis live chain:

```bash
node tests/e2e-yield-mixed-sku/production-cost-live-chain.mjs
```

Pure headed frontend chain from production plan UI to yield entry and cost analysis UI:

```bash
node tests/e2e-yield-mixed-sku/headed-production-cost-flow.mjs
```

Useful environment variables:

```bash
E2E_APP_URL=http://127.0.0.1:3021
API_BASE_URL=http://127.0.0.1:10010
E2E_FACTORY_ID=...
E2E_USERNAME=...
E2E_PASSWORD=...
E2E_OUT=.playwright-mcp/codex-20260626-mixed-sku-yield-campaign
E2E_STRICT=1
E2E_HEADLESS=1
E2E_SCENARIO_COUNT=100
```

The runners automatically load `.env.e2e.local` and `.env.test.local` from the repository root before reading environment variables. They also map the existing local test credential names:

```bash
TEST_FACTORY_ADMIN_USER=factory_admin1
TEST_FACTORY_ADMIN_PASS=...
TEST_FACTORY_ADMIN_FACTORY_ID=F001
```

to `E2E_USERNAME`, `E2E_PASSWORD`, and `E2E_FACTORY_ID` when those `E2E_*` values are not already set. Keep those local files uncommitted.

For the live mutating chain, also set:

```bash
E2E_RUN_LIVE=1
E2E_ALLOW_MUTATION=1
E2E_LIVE_SCENARIO_COUNT=100
E2E_HEADED_SCENARIO_COUNT=100
```

`production-cost-live-chain.mjs` auto-discovers product type IDs and active customers after login, and falls back to the login user ID as supervisor. Override with `E2E_PRODUCT_TYPE_IDS`, `E2E_CUSTOMER_IDS`, or `E2E_SUPERVISOR_ID` only when a specific fixture set is required.

The runner always generates and validates at least 100 deterministic mixed batch/order/SKU scenarios. It only marks real business cases as `deep` after a live API/UI flow can create or read back state. If the backend, credentials, or frontend are unavailable, the JSON result is written as `blocked` instead of pretending the campaign passed.

Each runner also writes a `coverage` object and records a `scenario-coverage` gate. The gate fails if the generated 100-case matrix no longer covers required values such as max SKU count, multi-order, multi-raw-batch, long routes, rolling/partial output, yield warnings, shared costs, and stopped-SKU cases. This keeps the numeric target from being satisfied by narrow or repetitive scenarios.

`production-cost-live-chain.mjs` maps the real project API contract from production plans through batch cost analysis. It does not mutate shared data unless `E2E_RUN_LIVE=1` and `E2E_ALLOW_MUTATION=1` are both set.

`headed-production-cost-flow.mjs` launches independent persistent Playwright profiles under its own output directory and clicks real UI only. It defaults to headed mode, and it does not share browser state with the repository's other Playwright suites. With credentials and `E2E_ALLOW_MUTATION=1`, it runs up to `E2E_HEADED_SCENARIO_COUNT` real headed UI chains, defaulting to the full 100-scenario matrix. Without credentials or mutation permission it runs one shell audit and writes a `blocked` result with screenshots and UI audit evidence instead of inflating the executed count.

Rolling-order rule covered by the matrix:

- Daily settlement must remain available for rolling orders and mixed SKU routes even when final close is blocked.
- Final close / finished-goods receipt is allowed only after every SKU route is explicitly completed, or after the business intentionally stops that product.
- A stopped product/SKU is represented by terminal `CANCELLED` work-process tasks and must not keep the rolling order open forever.
- In-progress yield rate is a rolling reference: recorded final-step output divided by recorded first-step input. It is not the locked final yield rate until close.
