# tests/qa-r1-vue-smoke

Archived Round 1 part 2 SmartBI Vue page L1 evidence (chat2 deliverable).

The original standalone runner was retired after its reusable page, console,
network, screenshot, and clean-session behavior moved to
`scripts/e2e/production-readonly/`. This directory is evidence only and must
not be treated as an executable production test package.

## Files

- `pages.mjs` — 18-page registry derived from `web-admin/src/router/`
- `round-1-vue-smoke.json` — per-page result (rendered/console/api/toast/screenshot)
- `console-matrix.json` — page × signal-type matrix
- `coverage-matrix.md` — Rule 11 breadth coverage + findings (human-readable)
- `screenshots/*.png` — one fullPage screenshot per page (18)

The JSON, matrix, route registry, and screenshots remain as immutable historical
evidence. Run current acceptance through the canonical harness; do not refresh
or re-commit this archived evidence directory.

## Spec / scope

- Spec: `docs/qa-specs/2026-05-12-smartbi-python-port-deep-e2e-spec.md` §5 Round 1 part 2 + §3.1 acceptance bar
- Out of scope: L2 CRUD writes, customer-facing UI (chat3 owns #423/#413/#414), RN App
- Acceptance bar: L1 = mount + no white screen + no unexplained console.error/warning + screenshot

## Headline result (2026-05-13)

18/18 pages render (mount + body). 3 follow-up tickets recommended:

1. **F1** `query-templates` 404 with visible error toast — `/smart-bi/query-templates`
2. **F2** calibration admin 404 (statistics + sessions) — `/smart-bi/calibration`
3. **F3** capability probe 503 on Dashboard + Finance — graceful fallback, but the upstream is unhealthy

See `coverage-matrix.md` for details and `round-1-vue-smoke.json` for full evidence.
