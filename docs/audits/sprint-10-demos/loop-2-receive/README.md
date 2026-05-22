# Sprint 10 Loop 2 — 入库/收货 AI 闭环 demo screenshots

This directory holds screenshots captured by the Playwright spec
`web-admin/tests/e2e-closed-loop/loop-2-receive.spec.ts`.

## Capture

```bash
cd web-admin
E2E_BASE_URL=http://139.196.165.140:8086 \
E2E_API_BASE=http://47.100.235.168:10010/api/mobile \
E2E_USER=f006_admin E2E_PASS=123456 E2E_FACTORY_ID=F006 \
npx playwright test --project sprint10-loop-2-receive
```

Screenshots land in `web-admin/test-results/screenshots/sprint10-loop-2/`.
Copy any desired demos here for archival:

```bash
cp web-admin/test-results/screenshots/sprint10-loop-2/*.png \
   docs/audits/sprint-10-demos/loop-2-receive/
```

## What to look for in each screenshot

| File | Validates |
|---|---|
| `01-path-a-today-pending.png` | Path A trigger ("今日 PO 待收") returns receiving table |
| `02-path-b-synonym.png` | Path B synonym ("什么 PO 该入库了") recognized — no error |
| `03-dialog-elements.png` | 确认收货 dialog: R2 title (供应商 + PO), R1 qty max attr, R3 status dropdown 4 options |
| `04-submit-success.png` | Submit successful — toast 显示 入库 X 件 + actionHint URL |
| `04-r4-duplicate-detected.png` | (Alt) R4 idempotent — 5min window 内重复 trigger DUPLICATE preview |
| `05-r3-other-selected.png` | R3 dropdown OTHER 选项 selectable |

## SQL verify (post-run)

```bash
ssh root@47.100.235.168 "PGPASSWORD=cretas123 psql -h localhost -U cretas_user \
  -d cretas_prod_db -c \"SELECT id, receive_number, receive_date, status, \
  ai_invocation_metadata FROM purchase_receive_records \
  WHERE ai_invocation_metadata @> '{\\\"testRun\\\": true, \
  \\\"source\\\": \\\"sprint-10-loop-2\\\"}'::jsonb \
  ORDER BY created_at DESC LIMIT 10\""
```

Expected: ≥1 row (assuming PO 待收 existed at time of test).

## Cleanup

```bash
./scripts/test/cleanup-sprint-10-test-data.sh loop-2
```
