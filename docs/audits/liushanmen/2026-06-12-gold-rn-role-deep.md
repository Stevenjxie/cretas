# Gold RN Role Deep Audit - 2026-06-12

Environment: prod Java `127.0.0.1:10010` via SSH `root@47.100.235.168`.
Runner: `docs/audits/liushanmen/gold_rn_role_deep_runner.py`.
Write marker: `DEMO-GOLD-RN`.

## Verdict

✅ Role write paths are live for procurement, warehouse, and sales.

The RN App entry/list evidence for these roles was captured earlier in `2026-06-12-rn-role-flow-and-oa-recommendation.md`. This gold pass used the same real role accounts against prod APIs to prove true writes and SQL persistence. Full form-by-form RN touch entry for every write screen is not claimed here.

Per organizer gate, finance/cashier RN approval todo was not re-tested as a bug; that is a known OA design gap.

## Positive Deep Paths

| Role | Account | Action | Result |
| --- | --- | --- | --- |
| procurement_manager | `f006_procurement_mgr` | create -> submit -> approve PO | PASS |
| warehouse_manager | `f006_warehouse_mgr` | create receive -> confirm inbound | PASS |
| sales_manager | `f006_sales_mgr` | create SO -> confirm SO | PASS |

Live objects:

```text
PO:      2ab9f302-7b28-4f67-b240-67645702cd12 | PO-20260612-0004 | PARTIAL_RECEIVED
Receive: 102719fe-756e-452f-9610-de3c96145e52 | RCV-20260612-3437 | CONFIRMED
Batch:   13ef10c5-eede-4d4a-ae55-d88eacff99d4 | MT-20260612-4059 | AVAILABLE
SO:      aaeae1ef-d84a-4321-bc93-faa3bb99a14d | SO-20260612-0010 | CONFIRMED
```

SQL readback pasted from runner:

```text
po|2ab9f302-7b28-4f67-b240-67645702cd12|PO-20260612-0004|PARTIAL_RECEIVED|DEMO-GOLD-RN procurement role PO 1781226667
receive|102719fe-756e-452f-9610-de3c96145e52|RCV-20260612-3437|CONFIRMED|6ce8414d-b5d6-466f-a4d3-bcbe687bfa7e
batch|13ef10c5-eede-4d4a-ae55-d88eacff99d4|MT-20260612-4059|AVAILABLE|6ce8414d-b5d6-466f-a4d3-bcbe687bfa7e
so|aaeae1ef-d84a-4321-bc93-faa3bb99a14d|SO-20260612-0010|CONFIRMED|DEMO-GOLD-RN sales role SO 1781226667
```

## Foolproof 5

| Guard | Result | Evidence |
| --- | --- | --- |
| Low-role viewer cannot cancel SO | PASS | `f006_viewer` cancel SO returned HTTP 403 with module/action hint |
| Material receipt requires warehouse | PASS | production manager missing `warehouseId` returned HTTP 400 `请指定入库仓库`, `hintTarget=warehouseId` |
| Disposal approve idempotent | PASS | repeated approve record `3` returned HTTP 400 `报废记录已审批, 请勿重复操作` |
| Stocktake month-end gate | PASS | HTTP 409, message says current date `2026-06-12`, next date `2026-06-29` |
| Operator cannot report unassigned task | PASS | `f006_moyun` on task `366` returned HTTP 403 `您不是该工序的负责人, 无权报工` |

## RN Headed Evidence Already Captured

| Account | RN App evidence |
| --- | --- |
| `f006_warehouse_worker` | `rn-role-f006_warehouse_worker-home-real.png`, `rn-role-f006_warehouse_worker-inbound.png`, `rn-role-f006_warehouse_worker-inventory.png` |
| `f006_procurement_mgr` | `rn-role-f006_procurement_mgr-purchase-tab.png` |
| `f006_sales_mgr` | `rn-role-f006_sales_mgr-sales-tab.png` |

## Limitations

- This gold pass does not claim finance/cashier RN approval todo is available. Organizer explicitly marked it as a new OA feature gap, not a regression bug.
- Warehouse/procurement/sales true writes were proven through prod API with real role tokens and DB readback; only App entry/list was captured headed.
- Created DEMO data was intentionally marked `DEMO-GOLD-RN`; do not clean concurrent `DEMO-771-VERIFY` data.
