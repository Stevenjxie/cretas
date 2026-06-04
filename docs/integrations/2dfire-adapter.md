# 二维火 (2dfire) POS Ingest Adapter — SKELETON

**Status: SKELETON, not a live integration.** We do NOT have 二维火 开放平台
API credentials yet. This document lists exactly what is needed to finish.

Created 2026-06-04. Branch: `feat/2dfire-pos-adapter-skeleton`.

---

## What exists today

| Piece | File | Status |
|---|---|---|
| Adapter class | `backend/python/smartbi/ingestion/twodfire_adapter.py` | Skeleton — config + normalize done, pull_* stubbed |
| Manual-trigger endpoint | `backend/python/smartbi/api/twodfire_ingest.py` | Only the not-configured dead-end is live |
| Config (env) | `backend/python/smartbi/.env.example` (`TWODFIRE_*`) | Done |
| Sync cursor table | `backend/python/smartbi/database/migrations/V20260926_01__twodfire_sync_state.sql` | Table created; not written yet |
| Tests | `smartbi/ingestion/tests/test_twodfire_adapter.py`, `smartbi/tests/test_twodfire_sync_endpoint.py` | 24 passing, no network |

**Endpoint:** `POST /api/smartbi/{factory_id}/ingest/2dfire/sync`
- Not configured (now) → HTTP 200 `{success:false, data:{configured:false, message, actionHint}}` (fool-proof Rule 5 dead-end-with-next-action).
- Configured (future) → would run pull → normalize → handoff; currently raises 501.

---

## How it fits the EXISTING POS pipeline (discovered 2026-06-04)

The adapter does NOT invent a schema. The existing Excel/zip POS ingest is:

```
upload bytes
  → CSV rows
  → smart_bi_dynamic_data.row_data (JSONB, keyed by raw Chinese column names)
  → scripts/backfill_silver.py:backfill_upload()
       → smartbi/canonical/aliases.py:ALIAS_TO_ATTR  (Chinese column → canonical attr)
       → CanonicalRow → SilverNormalizer → fact_pos_transaction / fact_pos_item
         / fact_pos_payment / fact_pos_discount
  → materialize_daily_order_type_meal() → Gold agg tables → revenue report
```

Key files (file:line):
- `smartbi/ingestion/pos_ingest.py:68` — `ingest_one_csv()` writes `row_data`
- `scripts/backfill_silver.py:368` — `backfill_upload()` writes Silver
- `scripts/backfill_silver.py:286` — `_build_canonical_row()` (uses `ALIAS_TO_ATTR`)
- `scripts/backfill_silver.py:238/259` — `_extract_payments()` / `_extract_discounts()` (EAV column scan)
- `smartbi/canonical/aliases.py:21` — `ALIAS_TO_ATTR`
- `smartbi/canonical/normalizer.py:51` — `CanonicalRow` (downstream target)
- `smartbi/api/revenue_report.py:137` — `/upload` endpoint (Excel/zip path)

**The adapter's job:** pull from 二维火 → `normalize_*` into `row_data` dicts
keyed by the SAME canonical Chinese column names `ALIAS_TO_ATTR` already maps
(账单号 / 门店名称 / 营业日期 / 商品信息 / 现金 / 微信 / …). Hand those off to
the same `smart_bi_dynamic_data → backfill_upload` downstream. **The Silver
write + Gold aggregations then run UNCHANGED** — no downstream edits required.

CI pins this contract: `test_field_map_*_targets_are_known_aliases` asserts
every `FIELD_MAP_*` target column exists in `ALIAS_TO_ATTR`.

---

## What's needed to FINISH the integration

### 1. Credentials (from 二维火 商家 / 开放平台 account)
- `TWODFIRE_APP_KEY` — 开放平台 app key
- `TWODFIRE_APP_SECRET` — 开放平台 app secret
- `TWODFIRE_SHOP_ID` — the 门店 id to pull (one per 二维火 store)
- `TWODFIRE_API_BASE` — confirm the real open-API host (default `https://open.2dfire.com`)

Set these in `backend/python/.env` (or `.env.prod` on the server). Leaving the
triplet empty keeps the endpoint in its safe not-configured state.

### 2. API docs to obtain (then fill the stubs)
From 二维火 开放平台 developer docs, get:
- **Auth handshake** → fill `authenticate()`. 二维火 typically uses an
  app_key/app_secret signature (sorted-params + timestamp + nonce, MD5/HMAC).
  Confirm token endpoint path + signing algorithm.
- **Orders endpoint** → fill `pull_orders(start, end)`. Need: path, query
  param names (shopId / date range), pagination, response envelope.
- **Order-items endpoint** → fill `pull_order_items(order_ids)`. Confirm
  whether items come embedded in the order response (then this is unneeded).
- **Payments endpoint** → fill `pull_payments(start, end)`. Confirm whether
  payments are embedded in the order response (then this is unneeded).

### 3. `FIELD_MAP_*` entries to VERIFY (in `twodfire_adapter.py`)
Every LEFT-side source field name is a **best-effort guess** and must be
checked against real 二维火 responses. The RIGHT-side canonical column is
already correct (pinned to `ALIAS_TO_ATTR`).

`FIELD_MAP_ORDER` (verify the 二维火 field names):
`orderId`→账单号, `shopName`→门店名称, `tradeTime`→营业日期,
`originalAmount`→应收金额, `discountAmount`→优惠金额, `actualAmount`→实收金额,
`personCount`→人数, `tableName`→桌号, `orderType`→订单类型, `channel`→来源,
`cashierName`→收银员, `mealPeriod`→班次.

`FIELD_MAP_ITEM` (verify): `orderId`→账单号, `dishName`→商品名称,
`dishCode`→商品编码, `categoryName`→商品分类, `quantity`→数量,
`unitPrice`→单价, `amount`→销售金额, `unit`→单位.

`FIELD_MAP_PAYMENT` (verify which fields): `bill_no_field`=orderId,
`pay_type_field`=payType, `pay_amount_field`=payAmount. The `payType` VALUE
becomes the row_data column NAME (现金/微信/美团/…). **Confirm 二维火's payType
vocabulary matches `scripts/backfill_silver.py:_PAYMENT_COLUMNS`** — if 二维火
uses a name not in that set, either map it or extend `_PAYMENT_COLUMNS`, else
the payment is silently dropped as "unknown".

### 4. Wire the configured sync path (`twodfire_ingest.py:sync_2dfire`)
The TODO block already sketches it:
1. `await adapter.authenticate()`
2. `pull_orders` / `pull_order_items` / `pull_payments`
3. Merge order + items + payments per bill into `row_data` dicts via
   `adapter.normalize_*`
4. INSERT a `smart_bi_pg_excel_uploads` row (`detected_table_type='POS'`) +
   each `row_data` into `smart_bi_dynamic_data` (mirror `ingest_one_csv`)
5. `await backfill_upload(pool, upload_id, factory_id)`
6. `await materialize_daily_order_type_meal(pool, factory_id, d1, d2)`
7. Persist the sync cursor into `twodfire_sync_state` (for incremental pulls)

### 5. Add real HTTP tests
Current tests assert config + mapping + not-configured response with NO
network. When `pull_*` is implemented, add tests with a mocked HTTP client
(e.g. `respx` / `httpx.MockTransport`) — keep production code network-free
under test.

---

## Honest limitations
- No 二维火 field name has been verified against real responses.
- No auth handshake is implemented.
- `pull_*` raise `NotImplementedError` even when "configured" — there is NO
  fake/degraded data path by design (project rule: no degraded handling).
- `twodfire_sync_state` is created but never written until step 4 is done.
