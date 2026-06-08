# 00 Charter And Transcript Map

## Entry Hook

This is the first file in the chain.

Create a run id:

```text
CHAIN_ID=DENG-QHJ-YYYYMMDD-HHMM
PERIOD=2026-06
STORE_FACTORY_ID=RES_3101_009
```

Create evidence folder:

```text
tmp/e2e/deng-restaurant-chain/<CHAIN_ID>/
```

## Business Scenario In Plain Language

Deng's real workflow is not "warehouse enters a delivery note" as the first step. It starts the previous day from kitchen/stall demand:

1. Chef/stall owner reports tomorrow's required ingredients.
2. Store manager or purchaser aggregates those reports into a procurement plan.
3. Supplier delivers against that plan.
4. Warehouse clerk weighs and accepts goods, handles quantity/quality/price differences, and posts inbound inventory.
5. Price differences trigger warning/explanation, so supplier and purchaser know the system is watching.
6. Kitchen/stalls requisition materials from inventory; wastage and breakage are attributed to person/stall.
7. Warehouse stocktakes on the 10th, 20th, and 30th, so next-day loss and stock accuracy can be seen.
8. Finance reconciles suppliers before the 5th, produces reports before the 10th, and payment happens around the 15th/20th.
9. Boss sees revenue, food cost ratio, labor ratio, gross/net margin, loss, supplier price anomalies, target completion, and AI suggestions.

## Transcript Coverage Map

| Transcript Need | Test File | Current Implementation Status |
|---|---|---|
| 4-5 systems isolated; Excel/CSV upload needed | `01-data-foundation-upload-pos-finance.md` | Partially implemented through SmartBI upload/POS name resolution; generic "what file is this" routing needs verification and likely gap closure |
| POS/2dfire data drives revenue, dishes, gross margin | `01`, `06` | Excel/CSV supported path is priority; realtime 2dfire deferred |
| Kingdee/finance data for P&L | `01`, `05`, `06` | Finance upload/analysis exists in SmartBI; restaurant P&L coverage must be tested end to end |
| Chef/stall reports demand before purchase | `02` | Identified gap unless current code proves otherwise |
| Procurement plan, supplement order, second confirmation | `02` | Identified gap; must be validated as missing or implemented |
| Supplier delivery, warehouse acceptance, inbound | `03` | Implemented P2P/inbound workbench; already had prod evidence, still needs chain test |
| Price difference warning and supplier explanation | `03`, `06` | Supplier price alert exists; must test acceptance-path integration and explanation retention |
| Inventory, requisition, transfer, wastage, stocktake | `04` | RN restaurant requisition/wastage/stocktaking exists; deduction/accounting depth must be tested |
| Supplier monthly reconciliation and payment | `05` | P2P reconciliation/cost attribution implemented; payment evidence may still be partial |
| Boss-level KPI/AI health report | `06` | Store KPI/AI health/report specs and pieces exist; must test qhj data honesty |
| CRM, marketing attribution, repeat/lost customers | `06` | P1 implemented pieces; full chain requires separate marketing/CRM data tests |
| Dianping/Douyin/group-buying/ad ROI | `06` | Analysis intent exists in specs; production depth may be partial |

## Role Matrix

Use these accounts when available:

| Role | Account | Purpose |
|---|---|---|
| Super/admin | `qhj_prod / 123456` | Full qhj tenant owner view |
| Warehouse | `qhj_warehouse_mgr / 123456` | Delivery acceptance, stock, wastage, stocktake |
| Finance | `qhj_finance_mgr / 123456` | Supplier reconciliation, payable, P&L |
| Sales/marketing | `qhj_sales_mgr / 123456` | CRM/commission and negative RBAC for finance/cost |
| Operator/mobile | `qhj_operator / 123456` | Mobile-only or front-line app flows |
| Chef/stall | `qhj_chef_cold`, `qhj_chef_hot` | Needed for future report-demand flow; if missing, record as test environment gap |

## Baseline Test Data

Use one unique suffix per run:

```text
SUFFIX=<CHAIN_ID short timestamp>
SUPPLIER=E2E邓总供应商<SUFFIX>
DELIVERY_NO=DZ-FULL-<SUFFIX>
REPORT_DATE=2026-06-06
DELIVERY_DATE=2026-06-07
PERIOD=2026-06
```

Planned material lines:

| Stall/Owner | Material | Planned Qty | Unit | Planned Unit Price | Purpose |
|---|---:|---|---:|---:|---|
| 冷菜档口 | 黄瓜 | 12 | kg | 4.20 | cold dish prep |
| 热菜档口 | 土豆粉 | 20 | kg | 3.50 | hot dish prep |
| 调料负责人 | 青花椒 | 5 | kg | 40.00 | seasoning |
| 前厅物料 | 洗洁精 | 1 | 箱 | 90.00 | non-food material/control item |

Acceptance differences to test later:

| Material | Actual Qty | Actual Unit Price | Expected Rule |
|---|---:|---:|---|
| 黄瓜 | 11.5 kg | 4.20 | quantity short but accepted after note |
| 土豆粉 | 20 kg | 3.50 | normal |
| 青花椒 | 5 kg | 46.00 | price anomaly vs planned 40 |
| 洗洁精 | 1 箱 | 110.00 | price anomaly vs planned/historical 90 |

Expected delivery total if all accepted:

```text
黄瓜 11.5 * 4.20 = 48.30
土豆粉 20 * 3.50 = 70.00
青花椒 5 * 46.00 = 230.00
洗洁精 1 * 110.00 = 110.00
TOTAL = 458.30
```

## Exit Hook To 01

Fill this before moving to `01-data-foundation-upload-pos-finance.md`:

```yaml
chainId:
period:
factoryId: RES_3101_009
accountsVerified:
  super:
  warehouse:
  finance:
  sales:
  operator:
  chefCold:
  chefHot:
testDataSuffix:
evidenceRoot:
knownMissingAccounts:
openRisks:
```

