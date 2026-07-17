---
name: project-playwright-e2e
description: >
  Use when writing, running, or debugging Playwright E2E scripts for this
  repository; adapting Claude Playwright MCP workflows to Codex; capturing
  screenshots/console evidence; testing web-admin or Expo Web; or creating
  reliable local browser automation with chromium.launch().
---

# Project Playwright E2E

Use this skill for concrete Playwright automation in this repo. Production
read-only acceptance and local/write-capable testing deliberately use different
entries so a convenient local runner cannot bypass production safety.

## Source Material

- `.mcp.json`: historical MCP server definitions for `playwright-rn`, `playwright-test`, and `weapp-dev`.
- `.playwright-mcp/`: historical screenshots, console logs, and page snapshots. Treat as read-only evidence unless regenerating evidence for a current task.
- `.agents/skills/e2e-web-admin`: workflow and evidence standards.
- `.agents/skills/depth-first-e2e`: deep/multi-round E2E rules.

## Default Targets

| Target | URL | Start Command |
|---|---|---|
| Web Admin | `http://localhost:5173` | `cd web-admin && npm run dev` |
| Expo Web | `http://localhost:8081` | `cd frontend/CretasFoodTrace && npx expo start --web` |
| Java API | `http://localhost:10010` | project-specific |

## Preferred Pattern

For production read-only acceptance, use the shared direct Playwright MCP
entry first:

```text
scripts/e2e/production-readonly/mcp-entry.js
```

Pass the checked-in filename to `browser_run_code_unsafe`. It consumes the
MCP-supplied `page`, reuses one clean UI-login session, records network and
console evidence, and installs the before-send mutation guard before the first
target navigation. Do not paste an ad-hoc production script, launch a second
browser, reuse storage state, or substitute a historical runner.

For local or explicitly non-production write-capable testing, standalone Node
scripts remain appropriate:

```javascript
import { chromium } from 'playwright';

const OUT = process.env.E2E_OUT || '.playwright-mcp/codex-current';
const BASE = process.env.E2E_ADMIN_URL || 'http://localhost:5173';

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await context.newPage();

const errors = [];
page.on('console', msg => {
  if (['error', 'warning'].includes(msg.type())) {
    errors.push(`[${msg.type()}] ${msg.text()}`);
  }
});
page.on('pageerror', err => errors.push(`[pageerror] ${err.message}`));

await page.goto(BASE, { waitUntil: 'domcontentloaded' });
await page.screenshot({ path: `${OUT}/landing.png`, fullPage: true });

await browser.close();
```

Create output directories before writing files. In PowerShell:

```powershell
New-Item -ItemType Directory -Force .playwright-mcp\codex-current
```

## Evidence Naming

Use stable, task-specific directories:

```text
.playwright-mcp/codex-YYYYMMDD-topic/
  00-login.png
  01-dashboard.png
  console.log
  result.json
```

For repeated runs, include a short scenario ID rather than only timestamps.

## Minimum Result JSON

Write or report structured results:

```json
{
  "scenario": "create_sales_order",
  "target": "web-admin",
  "url": "http://localhost:5173",
  "result": "PASS",
  "evidence": {
    "screenshots": [".../01-dashboard.png"],
    "apiStatus": 200,
    "toast": "saved",
    "readback": true
  },
  "consoleErrors": []
}
```

## Interaction Rules

- Use role/text locators first when stable.
- For Element Plus teleport components, wait for visible dropdown/dialog elements after click.
- Avoid raw keyword checks as final proof; pair them with submit/API/persistence/readback evidence.
- Prefer business keys created during the test over first-row selectors.
- Capture console and page errors.
- Use fresh navigation or reload for persistence checks.
- Keep scripts in `web-admin/`, `tests/`, or a task evidence directory when they are temporary. Do not leave large one-off scripts in the repo root unless the user asks.

## Running Tests

Use project-local Playwright if available:

```bash
node path/to/test.mjs
npx playwright test path/to/spec.ts
```

If dependencies are missing or browser downloads are required, ask for approval before network/install actions.

## When MCP Is Mentioned

If the user asks for Playwright MCP specifically:

1. Use the directly integrated Playwright MCP tools when they are available.
2. For production read-only acceptance, load
   `scripts/e2e/production-readonly/mcp-entry.js` by filename and keep one
   clean session for the ordered business-domain run.
3. If Playwright MCP is unavailable, say so; use the canonical CLI only when
   that still satisfies the user's request. Do not silently switch browser
   products when the user explicitly requires MCP.
4. Treat `.mcp.json` as historical configuration. Preserve it, but do not use
   it to infer current Codex MCP availability.

## Production Safety Boundary

- Production business writes must be zero. Authentication and exact registered
  query-only POST contracts are the only write-shaped requests allowed.
- Any unexpected `POST`, `PUT`, `PATCH`, or `DELETE` must be aborted before
  send and reported as a failed run.
- Never execute archived SmartBI/BOM runners or a write-capable yield/settlement
  script against production.
- `nonprod-business-flow-audit.mjs` is test-only and requires its explicit
  environment and write acknowledgements; its name is not a production entry.
- Use `scripts/e2e/production-readonly/README.md` for scenario IDs, evidence
  schema, validation commands, and credential/redaction rules.
