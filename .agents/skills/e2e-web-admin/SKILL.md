---
name: e2e-web-admin
description: >
  Use when verifying that web-admin or Expo Web user workflows actually work,
  including form submissions, CRUD, persistence, cross-module consistency,
  business workflow completion, pre-deploy checks, post-fix verification, or
  customer bug reproduction for Vue Web Admin and RN App via Expo Web.
---

# Web And App E2E Verification

This skill verifies real user workflows, not just page load. Use it after frontend changes, backend API changes, deployment preparation, and customer bug reports.

For deep multi-round campaigns, also use `depth-first-e2e`. For standalone Playwright implementation patterns, use `project-playwright-e2e`.

## Targets

| Target | Stack | Start | Default URL |
|---|---|---|---|
| Web Admin | Vue 3 + Element Plus | `cd web-admin && npm run dev` | `http://localhost:5173` |
| RN App Web | Expo + React Native Web | `cd frontend/CretasFoodTrace && npx expo start --web` | `http://localhost:8081` |
| API | Java backend | project-specific | `http://localhost:10010` |

Default credentials come from test environment setup. Do not commit credentials; use `.env.test.example` only for shape.

## Required Checks

Before running UI tests:

```bash
curl -s http://localhost:10010/api/mobile/health
```

Confirm the target URL responds. If a dev server must be started, follow the project command and report the URL.

## Production-Shaped Unit Gate

Before merging changes to SKU specifications, Workflow ports, process reporting, inventory receipt, or sales packaging, test production-shaped legacy state rather than only newly-created rows:

- Stored `g`, `box`, and `case` snapshots must render through the current `kg` or Chinese SKU-unit contract.
- Raw materials and mass-based semi-finished reporting must use `kg`; finished outputs must inherit the finished SKU base unit such as `盒` or `袋`.
- One SKU may have one standard weight and multiple packaging conversions; inventory and sales must require a packaging choice when more than one applies.
- Include at least one old persisted row and one new editable row. A test using only a fresh row is insufficient for compatibility changes.

Run this gate before production deployment so a compatibility defect does not force a second release cycle.

## Execution Preference

For production read-only acceptance, use the repository's canonical shared
harness first:

```text
scripts/e2e/production-readonly/mcp-entry.js
```

Pass that checked-in file to Codex Playwright MCP's filename entry. It consumes
the MCP-supplied `page`, installs its before-send mutation guard before the
first target navigation, and reuses one clean UI-login session. Do not paste an
ad-hoc script into the tool call and do not launch a second browser.

For local or write-capable non-production workflows, a standalone Node
Playwright script using `chromium.launch()` remains acceptable:

```javascript
import { chromium } from 'playwright';

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
await page.goto('http://localhost:5173', { waitUntil: 'domcontentloaded' });
// interact, assert, capture evidence
await browser.close();
```

Use `.agents/skills/agent-browser` or available browser tools for exploratory inspection. Treat `.mcp.json` as historical MCP configuration, not as a guarantee that MCP tools are available in Codex.

## Production Read-Only Acceptance

After the final backend and Web versions are both live, run one F006 production read-only suite covering the changed paths. For the SKU/unit workflow, cover SKU edit, Workflow, process-sheet reporting, finished-goods opening inventory, and sales order entry.

Canonical instructions, whitelist rationale, scenario IDs, CLI usage, and
evidence locations are in
`scripts/e2e/production-readonly/README.md`. The same core must be used by MCP,
the Node CLI, local Playwright fixtures, and the CI unit/drift gate.

The suite must:

- Use F006 only unless the user explicitly opens another tenant.
- Record every `POST`, `PUT`, `PATCH`, and `DELETE`; allow only authentication and exact documented read-only query POSTs from `config/readonly-post-whitelist.js`. Unexpected mutations must be aborted before send.
- Require both `actualBusinessWrites: 0` and zero blocked mutation attempts. A blocked attempt is a harness failure, not a successful acceptance run.
- Never save, publish, create, settle, or submit. Discard local unsaved UI rows before exit.
- Assert zero page errors, zero console errors, and zero unexpected HTTP responses `>=400`.
- Capture screenshots for unit labels, packaging selectors, horizontal overflow, sticky actions, and Workflow overlay/fit behavior.
- Keep credentials in gitignored environment sources. Evidence must never contain passwords, tokens, cookies, authorization headers, raw request bodies, email addresses, or phone numbers.

Before any production smoke, run:

```bash
node --test scripts/e2e/production-readonly/tests/unit.test.js
npx playwright test scripts/e2e/production-readonly/tests/fixture.spec.js --workers=1
node scripts/e2e/production-readonly/cli-runner.mjs --dry-run --production-readonly
```

Classify failures before changing product code:

| Failure class | Action |
|---|---|
| Product/API defect | Reproduce with a focused assertion, fix, then rerun the affected path |
| Locator or stale test expectation | Fix the harness; do not report it as a product defect |
| Shared auth/bootstrap/deployment failure | Repair the shared prerequisite, then rerun the full suite |

If shared prerequisites and deployed artifacts did not change, rerun only the failed scenario. Run the full suite again only after a new deployment or a shared setup change.

## Test Layers

| Layer | Purpose | Minimum Evidence |
|---|---|---|
| L1 page scan | Page is reachable and not broken | URL, visible landmark/table/content, no 401/500/error toast |
| L2 CRUD | Create/edit/delete works | filled fields, API response or toast, refreshed list, detail readback |
| L3 cross-module | Data created in A appears in B | created entity ID/name, dropdown/list readback in another module |
| L4 workflow | End-to-end business chain works | each transition, API/toast evidence, final persisted state |
| L5 embedded correctness | Business rules inside L2-L4 | non-empty fields, calculations, duplicate rejection, read-only/sequence constraints |

Do not claim L2/L3/L4 PASS from keyword checks alone.

## Valid PASS Evidence

A PASS must include the relevant subset:

- Target URL and role/account context.
- Fields filled and submitted.
- API status/URL or exact toast/response text.
- Persistence evidence after refresh or fresh navigation.
- Detail page or downstream module readback.
- Screenshot path for visual or workflow claims.
- Console/network errors if relevant.

No evidence means `UNVERIFIED`, not PASS.

## Platform Notes

Web Admin:

- Element Plus dialogs, popovers, selects, and message boxes often render through teleport. Use visible locators and wait for dropdown/dialog DOM.
- For `el-input-number`, native input setters may be needed.
- Prefer stable row lookup by business key rather than "first row" when dirty data exists.

RN App via Expo Web:

- Expo Web may store auth in localStorage/AsyncStorage fallback rather than SecureStore.
- Use visible text, accessibility labels, and route state cautiously; web rendering differs from native devices.

## Reporting

For each scenario, report:

```text
Scenario:
Target:
Depth/layer:
Result: PASS | FAIL | WARN | UNVERIFIED
Evidence:
Failure class and cause:
Business mutation count, for production read-only runs:
Files changed, if --fix was requested:
Retest command:
```

Detailed references retained from the Claude skill:

- `references/test-rules.md`
- `references/report-format.md`
- `references/coverage-matrix.md`
