# Deng Restaurant Productization E2E Test Suite

Status: draft test plan
Date: 2026-06-06
Tenant: qhj demo tenant `RES_3101_009`
Primary prod URL: `http://139.196.165.140:8086`

This folder is the chained QA plan for Deng's restaurant digitization workflow. It is intentionally written as a business scenario test suite, not a page smoke checklist.

## Source References

- Transcript: `docs/customer/2026-06-03-邓总-餐饮需求-语音转录.md`
- Transcript analysis: `docs/customer/2026-06-03-邓总-餐饮需求-分析与brainstorm.md`
- Data ingest: `docs/superpowers/specs/2026-06-03-restaurant-data-ingest-auto-design.md`
- AI health report: `docs/superpowers/specs/2026-06-03-restaurant-ai-health-report-design.md`
- Target cascade: `docs/superpowers/specs/2026-06-03-restaurant-target-cascade-design.md`
- POS name resolution: `docs/superpowers/specs/2026-06-04-restaurant-pos-name-resolution-design.md`
- Store KPI MVP: `docs/superpowers/specs/2026-06-04-restaurant-store-kpi-mvp-design.md`
- P2P gap closure plan: `docs/superpowers/plans/2026-06-05-restaurant-procure-to-pay-gap-closure.md`

## Execution Order And Hooks

Run the files in this order. Do not mark a file complete unless its Exit Hook is filled. The next file uses that hook as its Entry Hook.

| Order | File | Business Slice | Hook To Next |
|---|---|---|---|
| 00 | `00-charter-transcript-map.md` | Scope, transcript mapping, role matrix | `CHAIN_ID`, selected period, test accounts |
| 01 | `01-data-foundation-upload-pos-finance.md` | POS/finance Excel or CSV upload, file classification, SmartBI parse | upload IDs, period, normalized store/date range |
| 02 | `02-chef-report-procurement-plan.md` | Chef/stall report demand -> procurement plan | demand IDs, plan ID, planned material lines |
| 03 | `03-supplier-delivery-inbound-price.md` | Supplier delivery -> warehouse acceptance -> price anomaly -> inbound posting | supplier note ID, receive record ID, material batch IDs, anomaly IDs |
| 04 | `04-inventory-loss-stocktake-cost.md` | Inventory -> requisition/transfer -> wastage -> 10/20/30 stocktake -> cost attribution | issue/wastage/stocktake IDs, loss by person/stall |
| 05 | `05-finance-reconciliation-pnl-payment.md` | Supplier reconciliation -> payable -> P&L -> payment evidence | reconciliation ID, payable transaction ID, P&L period |
| 06 | `06-owner-analysis-kpi-health-ai.md` | Store KPI, AI health report, margin alerts, target completion | dashboard evidence, AI answers, alert IDs |
| 07 | `07-rbac-foolproof-regression.md` | Cross-role RBAC, fail-closed masking, fool-proof UX | final role matrix and residual gaps |

## Depth Rule

Every scenario must be labeled:

- `smoke`: page/API reachable only.
- `medium`: form submit/API call captured but no downstream readback.
- `deep`: create/fill + submit + API/toast + fresh readback + downstream module verification.

For this customer line, the target is not "all pages open". The target is at least one `deep` path through:

`POS/finance data -> chef report/procurement plan -> supplier delivery/inbound -> inventory/cost/loss -> reconciliation/P&L -> owner KPI/AI report`.

## Common Evidence Folder

Each run should write evidence under:

`tmp/e2e/deng-restaurant-chain/<CHAIN_ID>/`

Recommended subfolders:

- `screenshots/`
- `api/`
- `db/`
- `notes/`

Each file's Exit Hook should point to concrete evidence paths in this folder.

