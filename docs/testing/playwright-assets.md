# Playwright asset registry

This registry prevents production acceptance from accidentally reusing a
write-capable or credential-bearing historical script. The canonical production
entry is `scripts/e2e/production-readonly/`.

| Asset | Intended environment | Reusable parts | Production status |
|---|---|---|---|
| `scripts/e2e/production-readonly/` | Production read-only, local fixture, CI unit gate | Entire shared harness | **Canonical** |
| `tests/qa-r1-vue-smoke/` | Archived QA evidence | Route/evidence history only; executable runner removed | **Evidence only**: current page scan and capture behavior lives in the canonical harness |
| `tests/e2e-comprehensive/p2-guardrail-smoke.mjs` | Non-production guardrail | Fresh Chromium/context pattern | **Forbidden for production**: embedded credentials and AI POSTs |
| `scripts/e2e/production-readonly/scenarios/bom-readonly.js` | Production read-only | BOM dialog and UI contract migrated from the retired focused audit | **Canonical**: protected by the shared before-send mutation guard |
| `tests/e2e-yield-mixed-sku/_headed-helpers.mjs` | Test-data setup | None for production | **Forbidden for production**: token reuse and direct API mutations |
| `tests/e2e-yield-mixed-sku/nonprod-business-flow-audit.mjs` | Explicit non-production write audit | Mixed-SKU plan, process-sheet, yield, cost and settlement chain | **Non-production only**: rejects known production hosts and requires two explicit test/write acknowledgements |
| `tests/e2e-yield-mixed-sku/ui-render-deep-audit.mjs` | Historical write-capable UI audit | UI/oracle comparison reference | **Forbidden for production**: creates records and submits settlement; not part of the canonical read-only harness |
| `tests/v1-e2e/helpers/login.ts` | Local/CI integration | UI locator ideas | **Non-production only**: test defaults and write-capable suites |
| `tests/v1-e2e/helpers/auth-cache.ts` | Local/CI integration | Storage-state speedup | **Forbidden for production**: shared cached auth violates clean-session isolation |
| `web-admin/e2e-auth-helper.ts` | Web-admin local tests | API contract reference | **Forbidden for production**: token injection bypasses real UI login |
| `web-admin/tests/e2e/production-bom-flow.spec.ts` | Write-capable BOM workflow | UI/domain coverage reference | **Forbidden for production**: CRUD and workflow mutations |
| `.mcp.json` | Historical local MCP configuration | None | Not a runtime availability guarantee |

## Rules

- Preserve historical evidence by default. A legacy executable may be retired
  only after reusable assertions are migrated, references are removed, and
  this registry records its replacement or archived evidence location.
- Never copy a credential from an old script into the canonical harness.
- Do not maintain a second whitelist or result schema in a wrapper.
- `.agents/skills/e2e-web-admin/SKILL.md` is the canonical skill instruction.
  `.claude/skills/e2e-web-admin/SKILL.md` is only a pointer and is checked by the
  drift unit test.

## Canonical entries and evidence

- MCP: `scripts/e2e/production-readonly/mcp-entry.js` (generated bundle loaded
  with `browser_run_code_unsafe(filename=...)`).
- CLI: `node scripts/e2e/production-readonly/cli-runner.mjs`.
- CI/unit drift gate:
  `node --test scripts/e2e/production-readonly/tests/unit.test.js`.
- Local browser fixture:
  `npx playwright test scripts/e2e/production-readonly/tests/fixture.spec.js --workers=1`.
- Evidence is allowed only below `.playwright-mcp/`, `test-results/`,
  `test-output/`, or `tmp/`. These paths are gitignored; JSON, Markdown, and
  screenshots must not appear in `git status`.

The source modules under `core/` and `scenarios/` are canonical. Run
`node scripts/e2e/production-readonly/build-mcp-entry.js` after changing them.
The unit gate compares the generated entry byte-for-byte with a fresh bundle.

## Credentials and evidence redaction

- Read credentials only from a gitignored file or environment variable.
- Use UI login in production; do not inject an API token or reuse storageState.
- Do not print credential values. The persisted report redacts usernames and
  sensitive keys and stores only payload shape for arbitrary strings.
- Screenshots mask the expected username and login credential fields.
- The MCP sandbox has no Node environment access. A host must inject temporary
  page options through a secure non-logging path; otherwise use the CLI rather
  than placing a password in echoed MCP source.

## Mutation whitelist maintenance

Each read-only POST entry must use an exact anchored pathname, name the server
contract, and include a query-only rationale. Before adding one, inspect the
controller and service on current `origin/main` and add a negative near-match
test. Generic prefixes, AI chat, save, validation-with-side-effects, import,
export-job, submit, publish, apply, payment, reporting, and delete endpoints are
not eligible. An unexpected mutation is aborted before send and fails the
scenario even though `actualBusinessWrites` remains zero.
