"""二维火 (2dfire) POS ingest adapter — SKELETON (NOT a live integration).

⚠️ HONEST FRAMING ⚠️
This is ready-to-fill scaffolding, NOT a working 二维火 integration. We do
NOT have 二维火 开放平台 API credentials yet. The pieces that exist and have
real value today are:

  1. Config from env (TWODFIRE_API_BASE / TWODFIRE_APP_KEY /
     TWODFIRE_APP_SECRET / TWODFIRE_SHOP_ID) + is_configured() gate.
  2. The FIELD_MAP_* constants + normalize_order / normalize_item /
     normalize_payment — they map a (best-effort-guessed) 二维火 API response
     shape onto the EXACT canonical Chinese column names the existing Silver
     pipeline (smartbi.canonical.aliases.ALIAS_TO_ATTR + scripts.backfill_silver)
     already understands. That is the work that makes completion later = just
     filling in endpoint URLs + verifying field names.
  3. A fool-proof not-configured path: authenticate() / pull_*() RAISE
     TwoDFireNotConfigured when creds are absent. NO HTTP calls happen.

What is deliberately STUBBED (TODO when we have creds + docs):
  - authenticate(): the real 二维火 OAuth/signature handshake.
  - pull_orders / pull_order_items / pull_payments: the real HTTP GETs.

────────────────────────────────────────────────────────────────────────────
WHY normalize_* targets Chinese column names (the handoff contract)
────────────────────────────────────────────────────────────────────────────
The existing POS ingest pipeline (discovered 2026-06-04) is:

    upload bytes
      → CSV rows
      → smart_bi_dynamic_data.row_data (JSONB, keyed by raw Chinese columns)
      → scripts.backfill_silver.backfill_upload()
          → ALIAS_TO_ATTR resolves Chinese column names → CanonicalRow
          → SilverNormalizer writes fact_pos_transaction / fact_pos_item /
            fact_pos_payment / fact_pos_discount
      → materialize_daily_order_type_meal() → Gold agg tables

  Key files (file:line):
    - smartbi/ingestion/pos_ingest.py:68  ingest_one_csv()  ← row_data writer
    - scripts/backfill_silver.py:368       backfill_upload() ← Silver writer
    - scripts/backfill_silver.py:286       _build_canonical_row() ← uses ALIAS
    - smartbi/canonical/aliases.py:21      ALIAS_TO_ATTR  ← Chinese→canonical
    - smartbi/canonical/normalizer.py:51   CanonicalRow   ← downstream target
    - smartbi/api/revenue_report.py:137    /upload endpoint ← Excel/zip path

So the adapter's job is NOT to invent a new schema — it is to produce
`row_data`-shaped dicts keyed by the SAME canonical Chinese column names that
ALIAS_TO_ATTR maps. Then the downstream Silver write + Gold materialize run
UNCHANGED. The future configured sync() will:

    raw_orders = await self.pull_orders(start, end)
    rows = [self.normalize_order(o) for o in raw_orders]
    # ... merge items + payments per bill into each row's row_data ...
    # → INSERT into smart_bi_dynamic_data (like ingest_one_csv) with
    #   detected_table_type='POS' + writer 'bill_flow_writer'
    # → backfill_upload(pool, upload_id, factory_id)
    # → materialize_daily_order_type_meal(...)

That handoff is left as a documented TODO in the sync endpoint, not here.

Design alignment: this class follows the BronzeAdapter contract
(smartbi/ingestion/base.py) in spirit — stateless except config, emits the
canonical shape, no business-table writes. We keep the explicit normalize_*
methods (rather than the RawEvent generator) because the future handoff is a
batch INSERT into smart_bi_dynamic_data, mirroring ingest_one_csv, not the
streaming RawEvent path.
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from datetime import date
from typing import Any, Dict, List, Optional, Union

logger = logging.getLogger(__name__)


# ════════════════════════════════════════════════════════════════════════
# Field mapping: 二维火 API response field → canonical Chinese column name
# ════════════════════════════════════════════════════════════════════════
#
# ⚠️ EVERY 二维火 source field name below is a BEST-EFFORT GUESS.
#    VERIFY against the real 二维火 开放平台 API docs before going live.
#    The canonical-column VALUE (right side) is NOT a guess — it is pinned to
#    smartbi.canonical.aliases.ALIAS_TO_ATTR and CI asserts every value exists
#    there (test_field_map_*_targets_are_known_aliases). Do NOT change the
#    right side without checking ALIAS_TO_ATTR; change the left side freely
#    once you have the real 二维火 field names.
#
# Order-level (bill grain) → fact_pos_transaction via CanonicalRow.
FIELD_MAP_ORDER: Dict[str, str] = {
    # 二维火 field (GUESS — VERIFY)   →   canonical column (pinned to ALIAS_TO_ATTR)
    "orderId": "账单号",            # VERIFY: bill/order id field name
    "shopName": "门店名称",          # VERIFY: store name field name
    "tradeTime": "营业日期",         # VERIFY: business date/timestamp field (ALIAS parses ts → date)
    "originalAmount": "应收金额",     # VERIFY: gross / pre-discount amount
    "discountAmount": "优惠金额",     # VERIFY: total discount amount
    "actualAmount": "实收金额",       # VERIFY: net / post-discount amount
    "personCount": "人数",          # VERIFY: customer/diner count
    "tableName": "桌号",            # VERIFY: table number/name
    "orderType": "订单类型",         # VERIFY: dine-in/takeout type
    "channel": "来源",             # VERIFY: order origin/channel
    "cashierName": "收银员",         # VERIFY: cashier/staff name
    "mealPeriod": "班次",           # VERIFY: meal period / shift (午市/晚市/中班/晚班 …)
    # NOTE: 实收金额 vs 收款金额 — 二维火 may expose both a "settled" and a
    #   "received" amount. We map actualAmount → 实收金额 (net). If 二维火 also
    #   returns a separate cash-tendered figure, map it to 收款金额 here.
}

# Item-level (line grain, product_summary shape) → fact_pos_item.
# In the qhj path, line items arrive as a bill-level 商品信息 blob parsed by
# combo_parser. The 二维火 API more likely returns structured per-item rows;
# we map those to the product_summary canonical columns so each item row can
# be normalized independently (and 账单号 ties it back to its bill).
FIELD_MAP_ITEM: Dict[str, str] = {
    "orderId": "账单号",            # VERIFY: parent bill id (FK to the order)
    "dishName": "商品名称",          # VERIFY: dish/product name
    "dishCode": "商品编码",          # VERIFY: dish/product code/SKU
    "categoryName": "商品分类",       # VERIFY: dish category
    "quantity": "数量",            # VERIFY: sold quantity
    "unitPrice": "单价",           # VERIFY: unit price
    "amount": "销售金额",           # VERIFY: line revenue (qty × price after disc)
    "unit": "单位",               # VERIFY: unit of measure (份/例/位 …)
}

# Payment-level (EAV shape) → fact_pos_payment.
# 二维火 returns payments as rows of {payType, payAmount}; the EXISTING
# backfill_silver._extract_payments scans a row_data dict for known
# payment-METHOD COLUMNS (现金/微信/美团/…) with non-zero values. So we
# pivot: the payType VALUE becomes the column NAME, payAmount becomes the
# value. This special pivot is why payment mapping is config (which fields),
# not a flat column→column dict.
FIELD_MAP_PAYMENT: Dict[str, str] = {
    "bill_no_field": "orderId",     # VERIFY: parent bill id on a payment row
    "pay_type_field": "payType",    # VERIFY: payment method name field (becomes the column)
    "pay_amount_field": "payAmount",  # VERIFY: payment amount field (becomes the value)
}


# ════════════════════════════════════════════════════════════════════════
# Config
# ════════════════════════════════════════════════════════════════════════

# Sensible default base; the secret triplet is what actually gates configured.
_DEFAULT_API_BASE = "https://open.2dfire.com"


@dataclass(frozen=True)
class TwoDFireConfig:
    """二维火 开放平台 credentials + endpoint base.

    Read from env via from_env() (project config pattern: os.environ). NEVER
    hardcode secrets. api_base has a default; the app_key/app_secret/shop_id
    triplet is empty until configured.
    """
    api_base: str
    app_key: str
    app_secret: str
    shop_id: str

    @classmethod
    def from_env(cls) -> "TwoDFireConfig":
        return cls(
            api_base=os.environ.get("TWODFIRE_API_BASE", _DEFAULT_API_BASE) or _DEFAULT_API_BASE,
            app_key=os.environ.get("TWODFIRE_APP_KEY", ""),
            app_secret=os.environ.get("TWODFIRE_APP_SECRET", ""),
            shop_id=os.environ.get("TWODFIRE_SHOP_ID", ""),
        )


class TwoDFireNotConfigured(RuntimeError):
    """Raised when a network-touching method is called without credentials.

    The whole point: NO silent fake data, NO degraded path. If creds are
    absent the caller gets a clear, actionable error (or the endpoint returns
    a fool-proof dead-end-with-next-action payload)."""


# ════════════════════════════════════════════════════════════════════════
# Adapter
# ════════════════════════════════════════════════════════════════════════

class TwoDFirePosAdapter:
    """二维火 POS adapter — SKELETON.

    Usage (future, once configured):
        adapter = TwoDFirePosAdapter(factory_id="F001")
        if not adapter.is_configured():
            ...  # return fool-proof not-configured response
        await adapter.authenticate()
        orders = await adapter.pull_orders("2026-06-01", "2026-06-02")
        rows = [adapter.normalize_order(o) for o in orders]
        # → hand rows off to the smart_bi_dynamic_data → backfill_silver path
    """

    def __init__(self, factory_id: str, config: Optional[TwoDFireConfig] = None):
        self.factory_id = factory_id
        self.config = config if config is not None else TwoDFireConfig.from_env()
        self._access_token: Optional[str] = None

    # ── Configuration gate ───────────────────────────────────────────────

    def is_configured(self) -> bool:
        """True only when the full secret triplet is present.

        api_base alone does NOT count (it has a default). Fail-closed: any
        missing field → False → caller shows the not-configured dead-end."""
        c = self.config
        return bool(c.app_key and c.app_secret and c.shop_id)

    def _require_configured(self) -> None:
        if not self.is_configured():
            raise TwoDFireNotConfigured(
                "二维火 API 凭证未配置 — 请在 .env 配置 "
                "TWODFIRE_APP_KEY / TWODFIRE_APP_SECRET / TWODFIRE_SHOP_ID 后重试"
            )

    def describe(self) -> Dict[str, Any]:
        """Logging/debug snapshot — NEVER includes secrets."""
        return {
            "adapter": "TwoDFirePosAdapter",
            "factory_id": self.factory_id,
            "api_base": self.config.api_base,
            "shop_id_set": bool(self.config.shop_id),
            "configured": self.is_configured(),
        }

    # ── Network-touching methods (STUBBED — no HTTP in the skeleton) ──────

    async def authenticate(self) -> str:
        """Obtain an access token from 二维火 开放平台.

        SKELETON: raises if not configured; raises NotImplementedError when
        configured (no real handshake yet).

        TODO (when creds + docs available):
          二维火 uses an app_key/app_secret signature scheme (typically an
          MD5/HMAC of sorted params + timestamp + nonce). Implement the
          token endpoint call here, cache self._access_token, and return it.
          Endpoint shape needed:  POST {api_base}/token  (VERIFY path).
        """
        self._require_configured()
        raise NotImplementedError(
            "二维火 authenticate() not implemented — fill in the OAuth/signature "
            "handshake once 二维火 开放平台 docs + credentials are available."
        )

    async def pull_orders(
        self, start: Union[str, date], end: Union[str, date]
    ) -> List[Dict[str, Any]]:
        """Pull raw order (bill-grain) records for [start, end].

        SKELETON: raises TwoDFireNotConfigured if creds absent, else
        NotImplementedError (NO fake data — project 'no degraded path' rule).

        TODO (when creds + docs available):
          GET {api_base}/orders?shopId={shop_id}&startDate={start}&endDate={end}
          (VERIFY path + param names + pagination). Return the list of raw
          order dicts; each goes through normalize_order().
        """
        self._require_configured()
        raise NotImplementedError(
            "二维火 pull_orders() not implemented — fill in the orders HTTP GET. "
            "Expected to return a list of raw order dicts shaped like FIELD_MAP_ORDER keys."
        )

    async def pull_order_items(
        self, order_ids: List[str]
    ) -> List[Dict[str, Any]]:
        """Pull raw line-item records for the given order ids.

        SKELETON: raises (see pull_orders).

        TODO: GET {api_base}/orderItems?shopId={shop_id}&orderIds=...
          (VERIFY path + whether items come embedded in the order response
          instead — if so this method may be unneeded). Each raw item goes
          through normalize_item().
        """
        self._require_configured()
        raise NotImplementedError(
            "二维火 pull_order_items() not implemented — fill in the order-items HTTP GET. "
            "Expected to return raw item dicts shaped like FIELD_MAP_ITEM keys."
        )

    async def pull_payments(
        self, start: Union[str, date], end: Union[str, date]
    ) -> List[Dict[str, Any]]:
        """Pull raw payment records for [start, end].

        SKELETON: raises (see pull_orders).

        TODO: GET {api_base}/payments?shopId={shop_id}&startDate=...&endDate=...
          (VERIFY path + param names). Each raw payment goes through
          normalize_payment(). If 二维火 embeds payments inside the order
          response, this method may be unneeded — confirm against docs.
        """
        self._require_configured()
        raise NotImplementedError(
            "二维火 pull_payments() not implemented — fill in the payments HTTP GET. "
            "Expected to return raw payment dicts shaped like FIELD_MAP_PAYMENT fields."
        )

    # ── Normalization (the real value — NO network, pure mapping) ─────────

    def normalize_order(self, raw: Dict[str, Any]) -> Dict[str, Any]:
        """Map one raw 二维火 order dict → canonical row_data dict.

        Output is keyed by the canonical Chinese column names that
        ALIAS_TO_ATTR understands, so the downstream Silver pipeline consumes
        it UNCHANGED. Only present (non-None) source fields are emitted.
        """
        return _map_via_field_map(raw, FIELD_MAP_ORDER)

    def normalize_item(self, raw: Dict[str, Any]) -> Dict[str, Any]:
        """Map one raw 二维火 line-item dict → canonical product_summary row_data."""
        return _map_via_field_map(raw, FIELD_MAP_ITEM)

    def normalize_payment(self, raw: Dict[str, Any]) -> Dict[str, Any]:
        """Map one raw 二维火 payment dict → EAV payment-column row_data.

        Pivot: the payType VALUE becomes the column NAME (e.g. "微信"),
        payAmount becomes that column's value. This matches
        backfill_silver._extract_payments, which scans row_data for known
        payment-method columns with non-zero amounts. The bill id ties the
        payment back to its order.
        """
        out: Dict[str, Any] = {}
        bill_no = raw.get(FIELD_MAP_PAYMENT["bill_no_field"])
        if bill_no is not None:
            out["账单号"] = bill_no
        pay_type = raw.get(FIELD_MAP_PAYMENT["pay_type_field"])
        pay_amount = raw.get(FIELD_MAP_PAYMENT["pay_amount_field"])
        if pay_type is not None and pay_amount is not None:
            # The payType value is the column name. backfill_silver._PAYMENT_COLUMNS
            # is a curated set (现金/微信/美团/…) — VERIFY 二维火's payType vocabulary
            # matches those names (or extend _PAYMENT_COLUMNS) so the payment isn't
            # dropped as "unknown".
            out[str(pay_type)] = pay_amount
        return out


def _map_via_field_map(
    raw: Dict[str, Any], field_map: Dict[str, str]
) -> Dict[str, Any]:
    """Apply a {source_field: canonical_column} map to a raw dict.

    - Only present, non-None source fields are emitted (missing data stays
      missing — backfill's required-field check handles it; no None clutter).
    """
    out: Dict[str, Any] = {}
    for src_field, canonical_col in field_map.items():
        if src_field in raw and raw[src_field] is not None:
            out[canonical_col] = raw[src_field]
    return out
