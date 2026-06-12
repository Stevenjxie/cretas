# DEMO-FE2 Directed Headed E2E Summary

Date: 2026-06-12  
Marker: `DEMO-FE2-20260612131543`  
Prod: green backend `127.0.0.1:10020`, web-admin entry `http://139.196.165.140:8086`  
RN device: Xiaomi `M2102K1AC`, ADB serial `f79c50d6`, package `com.cretas.foodtrace`

## Result

This run did create real DEMO-FE2 production-chain data, but the requested RN C8/C10 real-device reporting flow is not fully passable from the app UI.

The blocking point is C8 on Xiaomi RN: `f006_moyun` sees the assigned DEMO-FE2 batch task, opens the input reporting form, and the material picker opens. However the picker does not expose the newly created DEMO-FE2 BOM-compatible batch `DEMO-FE2-20260612131543-MAT-BOM`, even after cold restart and scrolling the picker. I did not select historical/non-DEMO batches, so C8 was stopped rather than faked.

## Completed With True RN Screenshots

- X2 cashier OA todo approved on Xiaomi RN.
  - Payment request: `PR-F006-20260612-8753`
  - Status after tap-through approval: `PAID`
  - Screenshot evidence: `X2-cashier-todo-list-demo-fe2-visible.png`, `X2-cashier-confirm-dialog.png`, `X2-cashier-after-approve.png`

- X2 finance OA todo approved on Xiaomi RN.
  - Purchase order: `PO-20260612-0010`
  - Status after tap-through approval: `FINANCE_APPROVED`
  - Screenshot evidence: `X2-finance-todo-list.png`, `X2-finance-approve-confirm-dialog.png`, `X2-finance-after-approve.png`

- Operator role gate on Xiaomi RN.
  - `f006_moyun` bottom tabs only show `工序 / 我的`; no `我的待办`.
  - Screenshot evidence: `RN-operator-home-tabs.png`

## RN Blockers / Failures

- C8 input reporting blocked in RN UI.
  - Batch visible: `DEMO-FE2-20260612131543-BATCH-RN`, batch id `2008`.
  - Tasks assigned to `f006_moyun` user id `1615`: task `403` input and task `404` final output.
  - Picker evidence: `C8-reenter-material-picker.png`, `C8-material-picker-scroll-find-1..5.png`.
  - Backend material exists and remains isolated: `27098a00-94e2-40fb-80cf-ad4016fb31fa | DEMO-FE2-20260612131543-MAT-BOM | AVAILABLE`.
  - UI does not show this batch; only older/historical batches are visible. Because of isolation rules, none were selected.

- C10 final output reporting could not be reached from RN, because C8 input did not complete in RN.

- RN fool-proof OTA checks:
  - Logout dialog still says generic `确定要退出吗？`; it does not include account/role context. Evidence: `RN-logout-dialog-cashier.png`.
  - Operator role gate passed for OA tab removal.
  - WS settings removal and QI biometric hiding were not completed in this run.

## Completed By API + SQL Evidence

- C13 production wastage photo required + approval.
  - No-photo create returned business failure: `code=422`, message `报损单必须上传至少一张照片作为凭证`, actionHint `请拍照后再创建`.
  - With-photo report: `WR-20260612-F1FAE3C1`, id `b96a3a4c-3f5a-4f99-8c52-3b1b5dc96584`.
  - Final status: `APPLIED`; approver `1552`; material adjustment `WASTAGE -0.10`, quantity `12.00 -> 11.90`.

- C14 reversal fast-path with real reports.
  - Batch `2009`, order `SO-20260612-0030`.
  - Reports: `556/557` first input/output, reversal log `11` with `fast_path=true`, then `558/559` re-report.
  - Unit cost progression captured by SQL/log: first `0.0500`, cleared `<null>`, re-filled `0.0833`.
  - This is API/SQL proof only; web-admin UI fast-path was not completed headed.

- C12 semi-finished dual output and secondary plan.
  - Output report `561` with `output_kind=BOTH`, `semi_output_quantity=5.00`, byproduct JSON present.
  - Semi inventory id `95`, code `DEMO-FE2-20260612131543-SEMI`.
  - Secondary plan API created id `9344b94d-e3ed-43a1-b0b2-b7d1eaeb3b23`, `secondary_source_wip_id=95`.

- B4 same-code weighted average.
  - Second same-code report `563`.
  - Semi inventory moved from `5.00 @ 0.2000 accumulated 1.00` to `10.00 @ 0.4000 accumulated 4.00`.
  - Transactions show second IN at `unit_cost_at_txn=0.6000`, weighted balance `0.4000`.

## Key SQL Readbacks

```text
payment_requests:
PR-F006-20260612-8753 | PAID | PURCHASE | 188.00 | paid_by=1628

purchase_orders:
PO-20260612-0010 | FINANCE_APPROVED | 100.00 | finance_reviewed_by=1551

work_process_tasks:
403 | batch 2008 | process_order 0    | PENDING | assigned_to 1615
404 | batch 2008 | process_order 9999 | PENDING | assigned_to 1615

wastage_reports:
WR-20260612-F1FAE3C1 | FACTORY | APPLIED | 0.1000 | PRODUCTION_WASTE | approved_by=1552

material_batch_adjustments:
WASTAGE | -0.10 | quantity 12.00 -> 11.90 | wastageReportId=b96a3a4c-3f5a-4f99-8c52-3b1b5dc96584

semi_finished_inventory:
95 | DEMO-FE2-20260612131543-SEMI | produced 10.00 | available 10.00 | unit_cost 0.4000 | accumulated_cost 4.00
```

## Artifacts

- Runner log: `docs/audits/liushanmen/2026-06-12-fe2-directed-runner.log`
- Screenshots/XML: `docs/audits/liushanmen/2026-06-12-fe2-directed-screenshots/`
- Summary: `docs/audits/liushanmen/2026-06-12-fe2-directed-headed-e2e-summary.md`

