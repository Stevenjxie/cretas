# Cretas production read-only Playwright harness

This is the canonical harness for evidence-based, read-only acceptance of the
Cretas Web Admin. The MCP entry, Node CLI, and Playwright fixture tests share
the same session cleanup, UI login, mutation guard, evidence schema, and
scenario implementations.

## Safety contract

- Install the mutation guard before the first target-origin navigation.
- Use the real login UI. Authentication is the only default write-shaped
  request that may pass.
- Allow query-only `POST` endpoints only through the exact registry in
  `config/readonly-post-whitelist.js`.
- Abort every other `POST`, `PUT`, `PATCH`, or `DELETE` before it is sent.
- Treat any blocked mutation attempt as a harness failure, even though the
  server did not receive it.
- A successful production run must report `actualBusinessWrites: 0`.
- Never put credentials, authorization headers, cookies, tokens, raw request
  bodies, emails, or phone numbers in evidence.
- Write evidence only below `.playwright-mcp/`, `test-results/`, `test-output/`,
  or `tmp/`.

The repository's task-scoped F006 production-write exception does not apply
inside this harness. Even when the authenticated tenant is F006, this entry
remains strictly zero-write. Run an authorized F006 write through a separate
task-specific UI/API/SQL path; never add its business mutations to the
query-only POST registry.

AI chat is intentionally not in the read-only POST registry. A chat request can
dispatch a tool with side effects even when the visible UI looks like a draft.

## Direct Playwright MCP

Codex Playwright MCP evaluates filename entries in an isolated VM without
`process`, `require`, or dynamic imports. `mcp-entry.js` is therefore a
deterministic, dependency-free bundle generated from the canonical
`core/`/`scenarios/` source graph. `tests/mcp-entry.test.js` fails if it drifts.

The host must place run options on the current MCP `page` as
`page.__cretasReadonlyOptions` through a secure, non-logging injection path.
The entry copies and immediately deletes that temporary property before doing
any work. If the host cannot inject credentials without exposing them in tool
output, use the CLI for the production run; never paste a real password into a
tool call that echoes source.

After secure option injection, run the checked-in entry as a filename. Do not
paste the generated entry source into the tool call.

```text
browser_run_code_unsafe(
  filename="C:\\path\\to\\repo\\scripts\\e2e\\production-readonly\\mcp-entry.js"
)
```

MCP option fields:

```text
username          required for a real run; source from gitignored credentials
password          required for a real run
baseUrl           default https://admin.cretaceousfuture.com
expectedUsername  default f006_admin
expectedFactoryId default F006
evidenceDir       absolute path under a gitignored evidence directory
scenarios         array of scenario IDs
dryRun            true loads/describes only; no navigation or login
```

The MCP entry consumes the page supplied by Codex Playwright MCP. It does not
launch a second browser and does not close the shared MCP session.

After changing any bundled module, regenerate the entry:

```powershell
node scripts/e2e/production-readonly/build-mcp-entry.js
```

The local `package.json` only defines the CommonJS module boundary inherited
from `scripts/e2e/package.json`; it declares no dependencies. Runtime packages
continue to come from the repository root.

## Node CLI

Use the repository's Playwright dependency; do not create a separate package or
dependency tree for this harness.

```powershell
node scripts/e2e/production-readonly/cli-runner.mjs --dry-run --production-readonly
node scripts/e2e/production-readonly/cli-runner.mjs `
  --production-readonly `
  --base-url https://admin.cretaceousfuture.com `
  --scenario tenant-isolation,workflow-readonly
```

The CLI always creates and closes an isolated browser context. A non-local base
URL automatically enables production-read-only mode. CLI credentials are read
from `E2E_USERNAME` and `E2E_PASSWORD`; expected identity comes from
`E2E_EXPECTED_USERNAME` and `E2E_FACTORY_ID`. Never echo those variables.

## Validation

```powershell
node --test scripts/e2e/production-readonly/tests/unit.test.js
npx playwright test scripts/e2e/production-readonly/tests/fixture.spec.js --workers=1
node --check scripts/e2e/production-readonly/cli-runner.mjs
node --check scripts/e2e/production-readonly/mcp-entry.js
```

Run unit tests and the local fixture before any production smoke. For the final
production smoke, select tenant isolation plus one pure-GET page scenario. Stop
immediately if the guard reports a blocked mutation attempt or an actual write.

## Adding a scenario

1. Add a module below `scenarios/` and register it in `core/run-suite.js`.
2. Prefer `runReadOnlyPageScenario` for navigation and evidence deltas.
3. Do not click save, create, submit, publish, apply, pay, delete, or report-work
   controls. Opening a dialog is allowed only when cancellation itself is known
   to be client-side and the guard remains installed.
4. Add response extractors only for non-sensitive business fields.
5. Add a local fixture assertion and run the drift/unit gate.

## Retired standalone runners

The reusable contracts from `tests/qa-r1-vue-smoke/run-smoke.mjs` and
`tests/e2e-yield-mixed-sku/readonly-bom-workflow-contract.mjs` now live here.
Their standalone runners and duplicate dependency package were removed. The
SmartBI JSON/screenshots remain archived evidence.

The former `prod-business-flow-audit.mjs` was renamed to
`nonprod-business-flow-audit.mjs`. It is intentionally outside this read-only
harness because it creates plans, reports process rows, and settles production.
It has no default target or factory, rejects known production hosts, and
requires both `E2E_TARGET_ENV=test` and
`E2E_ALLOW_BUSINESS_WRITES=NON_PRODUCTION_ONLY` before it can start.

## Result vocabulary

Scenario results are `PASS`, `CONFIRMED_DEFECT`, `PARTIAL_DEFECT`,
`UNVERIFIED`, or `TOOL_ERROR`. Root-cause classes are `frontend`, `backend`,
`data`, `config`, `tool`, or `none`. HTTP 200 alone is never sufficient for a
PASS; the scenario must also retain page and business-field evidence.
