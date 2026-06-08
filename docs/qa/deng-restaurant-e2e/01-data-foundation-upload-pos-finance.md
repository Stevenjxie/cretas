# 01 Data Foundation: Upload, POS, Finance

## Entry Hook From 00

Require the completed Exit Hook from `00-charter-transcript-map.md`.

Minimum fields needed:

```yaml
chainId:
period:
factoryId:
accountsVerified.super:
evidenceRoot:
```

## Purpose

This validates Deng's "data foundation first" requirement: POS/finance files must be collected correctly before reports are trusted.

This file covers:

- Generic Excel/CSV upload.
- Prompt/classification asking what the file is about.
- POS流水/dish/order/payment parsing.
- Finance/Kingdee-like P&L data upload.
- POS name resolution and unmatched candidate handling.
- Honest no-fake-data behavior when coverage is insufficient.

## Roles

Primary: `qhj_prod`

Negative checks:

- `qhj_sales_mgr`: should not see sensitive finance amounts unless explicitly allowed.
- `qhj_operator`: should not access web-admin finance upload if mobile-only.

## Business Files To Prepare

Create or locate small files under evidence folder:

| File | Required Columns | Example Rows |
|---|---|---|
| POS流水 CSV/XLSX | `营业日期`, `菜品名称`, `订单号`, `数量`, `实收金额`, `支付方式`, `渠道` | `2026-06-06, 青花椒鱼, O-001, 2, 256, 微信, 堂食` |
| 财务/金蝶 CSV/XLSX | `日期`, `科目`, `金额`, `类型` | `2026-06-06, 食材采购, 458.30, COST` |
| 供应商进货 CSV/XLSX | `送货日期`, `供应商`, `物料`, `数量`, `单位`, `单价` | `2026-06-07, <SUPPLIER>, 青花椒, 5, kg, 46` |

## Deep Scenario 01-A: Generic Upload Routes POS File

Depth: `deep`

Steps:

1. Login web-admin as `qhj_prod`.
2. Go to the generic SmartBI/upload entry.
3. Upload POS流水 file.
4. When the system asks what the file is about, choose POS/营业流水/餐饮营业 data.
5. Confirm parse preview.
6. Capture:
   - upload API status.
   - upload ID.
   - detected fields.
   - parsed row count.
   - any unmapped dish name candidates.
7. Fresh navigate to restaurant analysis/POS name resolution area.
8. Verify the uploaded dish/order rows can be found by date and business key.

Pass criteria:

- Upload succeeds with `{ success: true }`.
- The file is not silently treated as generic finance or factory data.
- Parsed date is `2026-06-06` or the selected period.
- Unmatched dish names enter review/candidate state, not fake cost rows.
- The UI gives a next action if parsing confidence is low.

Fail examples:

- Upload succeeds but no upload ID/readback.
- User must manually pick factory-only data type for restaurant POS.
- Unknown dish cost is shown as `0` instead of `成本数据不足`.

## Deep Scenario 01-B: Generic Upload Routes Finance File

Depth: `deep`

Steps:

1. Login as `qhj_prod`.
2. Upload finance/Kingdee-like CSV/XLSX.
3. Choose 财务/P&L/费用 data in the classification prompt.
4. Confirm period is `2026-06`.
5. Verify parsed rows include:
   - 食材采购/采购成本.
   - 工资/人工.
   - 房租/水电.
   - 折旧/装修摊销 if present.
6. Fresh navigate to finance analysis/P&L page or SmartBI finance section.
7. Verify period totals match file totals.

Pass criteria:

- Finance data is tied to `RES_3101_009`, not global demo data.
- Finance upload is readable in later health/P&L queries.
- Missing categories show coverage notes, not made-up values.

## Medium Scenario 01-C: Supplier Purchase File Routes To Supplier Draft

Depth: `medium` until supplier draft readback is wired; `deep` once it creates/updates delivery draft.

Steps:

1. Upload supplier purchase CSV/XLSX through generic upload.
2. Choose supplier delivery / 供应商进货 / 送货单 as file type.
3. Verify the system routes to the matching supplier delivery draft module, or records a clear unsupported gap.
4. Confirm delivery date auto-populates from the file.

Expected current result:

- If implemented: draft is created and linked to supplier delivery module.
- If not implemented: record `GAP-01-C` with exact missing route/module/API. This is still a valid QA outcome because the transcript requires the path.

## RBAC Checks

| Account | Expected |
|---|---|
| `qhj_prod` | Can upload and see amounts |
| `qhj_finance_mgr` | Can upload/read finance amounts |
| `qhj_sales_mgr` | Should not see finance raw amounts; direct finance APIs should be 403 or amount-masked |
| `qhj_operator` | Should not access web-admin upload if mobile-only |

## Exit Hook To 02

Fill this before moving to `02-chef-report-procurement-plan.md`:

```yaml
chainId:
period:
factoryId:
posUpload:
  uploadId:
  fileName:
  parsedRows:
  unresolvedDishNames:
  evidence:
financeUpload:
  uploadId:
  fileName:
  totalRevenue:
  totalFoodCost:
  totalLaborCost:
  totalRentUtilities:
  evidence:
supplierPurchaseUpload:
  uploadId:
  draftId:
  routedModule:
  gapIfAny:
dateRange:
  from:
  to:
blockingDefects:
warnings:
```

