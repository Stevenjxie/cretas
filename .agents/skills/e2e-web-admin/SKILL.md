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

## Execution Preference

Prefer a standalone Node Playwright script using `chromium.launch()`:

```javascript
import { chromium } from 'playwright';

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
await page.goto('http://localhost:5173', { waitUntil: 'domcontentloaded' });
// interact, assert, capture evidence
await browser.close();
```

Use `.agents/skills/agent-browser` or available browser tools for exploratory inspection. Treat `.mcp.json` as historical MCP configuration, not as a guarantee that MCP tools are available in Codex.

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
Failure cause:
Files changed, if --fix was requested:
Retest command:
```

Detailed references retained from the Claude skill:

- `references/test-rules.md`
- `references/report-format.md`
- `references/coverage-matrix.md`
