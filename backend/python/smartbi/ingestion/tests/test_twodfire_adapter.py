"""Tests for the 二维火 (2dfire) POS ingest adapter SKELETON.

This is a SKELETON adapter — there is NO live 二维火 integration here. These
tests pin down the parts that exist today and matter for completion later:

  1. is_configured() — env-var presence detection (present / absent / partial)
  2. normalize_order / normalize_item / normalize_payment — the field mapping
     from a synthetic 二维火-shaped response dict → the canonical row_data shape
     that the EXISTING Silver pipeline (backfill_silver.ALIAS_TO_ATTR) already
     understands. This is the real value of the skeleton.
  3. authenticate / pull_* raise TwoDFireNotConfigured when creds absent (no
     network — the skeleton makes ZERO HTTP calls).

NO network is performed in any test. The handoff target shape (canonical
Chinese column keys like 账单号 / 门店名称 / 营业日期 / 商品信息 / 现金 …) is
asserted against ALIAS_TO_ATTR so a future drift in either side fails loudly.

Spec / discovery: docs/integrations/2dfire-adapter.md
"""
from __future__ import annotations

import pytest

from smartbi.canonical.aliases import ALIAS_TO_ATTR
from smartbi.ingestion.twodfire_adapter import (
    TwoDFireConfig,
    TwoDFireNotConfigured,
    TwoDFirePosAdapter,
    FIELD_MAP_ORDER,
    FIELD_MAP_ITEM,
    FIELD_MAP_PAYMENT,
)


# ── Fixtures: synthetic 二维火-shaped raw responses ──────────────────────
# These mimic the JSON dicts 二维火's open API is EXPECTED to return. Field
# names here are best-effort guesses (VERIFY against 二维火 docs) — the whole
# point of the FIELD_MAP is to make those names changeable in one place.

_RAW_ORDER = {
    "orderId": "2DF-20260601-0001",
    "shopName": "青花椒鱼火锅(总店)",
    "tradeTime": "2026-06-01 18:31:48",
    "originalAmount": "328.00",
    "discountAmount": "28.00",
    "actualAmount": "300.00",
    "payAmount": "300.00",
    "personCount": "4",
    "tableName": "A08",
    "orderType": "堂食",
    "channel": "门店",
    "cashierName": "张伟",
    "mealPeriod": "晚市",
}

_RAW_ITEM = {
    "orderId": "2DF-20260601-0001",
    "dishName": "麻辣牛蛙",
    "dishCode": "SP1001",
    "categoryName": "锅底",
    "quantity": "2",
    "unitPrice": "68.00",
    "amount": "136.00",
    "unit": "份",
}

_RAW_PAYMENT = {
    "orderId": "2DF-20260601-0001",
    "payType": "微信",
    "payAmount": "300.00",
}


def _make_config(**overrides) -> TwoDFireConfig:
    base = dict(
        api_base="https://open.2dfire.com",
        app_key="ak",
        app_secret="sk",
        shop_id="SHOP123",
    )
    base.update(overrides)
    return TwoDFireConfig(**base)


# ── is_configured() ──────────────────────────────────────────────────────

def test_is_configured_all_present():
    adapter = TwoDFirePosAdapter(factory_id="F001", config=_make_config())
    assert adapter.is_configured() is True


def test_is_configured_all_absent():
    cfg = TwoDFireConfig(api_base="", app_key="", app_secret="", shop_id="")
    adapter = TwoDFirePosAdapter(factory_id="F001", config=cfg)
    assert adapter.is_configured() is False


@pytest.mark.parametrize("missing", ["app_key", "app_secret", "shop_id"])
def test_is_configured_partial_is_false(missing):
    """Any missing credential field → not configured (fail-closed)."""
    cfg = _make_config(**{missing: ""})
    adapter = TwoDFirePosAdapter(factory_id="F001", config=cfg)
    assert adapter.is_configured() is False


def test_is_configured_api_base_has_default_does_not_count_as_configured():
    """api_base alone (it has a sensible default) is NOT enough — the
    secret triplet (app_key/app_secret/shop_id) is what gates 'configured'."""
    cfg = TwoDFireConfig(
        api_base="https://open.2dfire.com", app_key="", app_secret="", shop_id="",
    )
    adapter = TwoDFirePosAdapter(factory_id="F001", config=cfg)
    assert adapter.is_configured() is False


def test_config_from_env_reads_environ(monkeypatch):
    monkeypatch.setenv("TWODFIRE_API_BASE", "https://api.example.com")
    monkeypatch.setenv("TWODFIRE_APP_KEY", "env-key")
    monkeypatch.setenv("TWODFIRE_APP_SECRET", "env-secret")
    monkeypatch.setenv("TWODFIRE_SHOP_ID", "env-shop")
    cfg = TwoDFireConfig.from_env()
    assert cfg.app_key == "env-key"
    assert cfg.app_secret == "env-secret"
    assert cfg.shop_id == "env-shop"
    assert cfg.api_base == "https://api.example.com"


def test_config_from_env_absent_gives_empty_creds(monkeypatch):
    for k in ("TWODFIRE_API_BASE", "TWODFIRE_APP_KEY", "TWODFIRE_APP_SECRET", "TWODFIRE_SHOP_ID"):
        monkeypatch.delenv(k, raising=False)
    cfg = TwoDFireConfig.from_env()
    # api_base has a default; the secret triplet must be empty so is_configured() is False.
    assert cfg.app_key == ""
    assert cfg.app_secret == ""
    assert cfg.shop_id == ""
    adapter = TwoDFirePosAdapter(factory_id="F001", config=cfg)
    assert adapter.is_configured() is False


# ── Not-configured guards on the network-touching methods ────────────────

@pytest.mark.asyncio
async def test_authenticate_raises_when_not_configured():
    cfg = TwoDFireConfig(api_base="", app_key="", app_secret="", shop_id="")
    adapter = TwoDFirePosAdapter(factory_id="F001", config=cfg)
    with pytest.raises(TwoDFireNotConfigured):
        await adapter.authenticate()


@pytest.mark.asyncio
async def test_pull_orders_raises_when_not_configured():
    cfg = TwoDFireConfig(api_base="", app_key="", app_secret="", shop_id="")
    adapter = TwoDFirePosAdapter(factory_id="F001", config=cfg)
    with pytest.raises(TwoDFireNotConfigured):
        await adapter.pull_orders("2026-06-01", "2026-06-02")


@pytest.mark.asyncio
async def test_pull_order_items_raises_when_not_configured():
    cfg = TwoDFireConfig(api_base="", app_key="", app_secret="", shop_id="")
    adapter = TwoDFirePosAdapter(factory_id="F001", config=cfg)
    with pytest.raises(TwoDFireNotConfigured):
        await adapter.pull_order_items(["2DF-1"])


@pytest.mark.asyncio
async def test_pull_payments_raises_when_not_configured():
    cfg = TwoDFireConfig(api_base="", app_key="", app_secret="", shop_id="")
    adapter = TwoDFirePosAdapter(factory_id="F001", config=cfg)
    with pytest.raises(TwoDFireNotConfigured):
        await adapter.pull_payments("2026-06-01", "2026-06-02")


@pytest.mark.asyncio
async def test_configured_pull_orders_still_stub_raises_not_implemented():
    """Even when configured, the skeleton has NO live HTTP — pull_* must
    raise NotImplementedError (not silently return fake data). This is the
    'no degraded / fake data' project rule."""
    adapter = TwoDFirePosAdapter(factory_id="F001", config=_make_config())
    with pytest.raises(NotImplementedError):
        await adapter.pull_orders("2026-06-01", "2026-06-02")


# ── normalize_order: 二维火 → canonical row_data shape ────────────────────

def test_normalize_order_maps_to_canonical_chinese_keys():
    adapter = TwoDFirePosAdapter(factory_id="F001", config=_make_config())
    out = adapter.normalize_order(_RAW_ORDER)

    # The output dict is keyed by the SAME canonical Chinese column names the
    # existing backfill_silver.ALIAS_TO_ATTR understands — so the downstream
    # Silver pipeline + Gold aggregations work UNCHANGED.
    assert out["账单号"] == "2DF-20260601-0001"
    assert out["门店名称"] == "青花椒鱼火锅(总店)"
    assert out["营业日期"] == "2026-06-01 18:31:48"
    assert out["应收金额"] == "328.00"
    assert out["优惠金额"] == "28.00"
    assert out["实收金额"] == "300.00"
    assert out["人数"] == "4"
    assert out["桌号"] == "A08"
    assert out["订单类型"] == "堂食"
    assert out["收银员"] == "张伟"
    assert out["班次"] == "晚市"


def test_normalize_order_only_emits_present_fields():
    """Missing source fields are simply absent from the output (no None
    clutter) — the backfill required-field check handles missing data."""
    adapter = TwoDFirePosAdapter(factory_id="F001", config=_make_config())
    minimal = {"orderId": "X1", "shopName": "店", "tradeTime": "2026-06-01"}
    out = adapter.normalize_order(minimal)
    assert out == {"账单号": "X1", "门店名称": "店", "营业日期": "2026-06-01"}


def test_normalize_order_skips_none_values():
    adapter = TwoDFirePosAdapter(factory_id="F001", config=_make_config())
    raw = {"orderId": "X1", "shopName": "店", "tradeTime": "2026-06-01", "tableName": None}
    out = adapter.normalize_order(raw)
    assert "桌号" not in out


# ── normalize_item: 二维火 → product_summary canonical shape ──────────────

def test_normalize_item_maps_to_canonical_chinese_keys():
    adapter = TwoDFirePosAdapter(factory_id="F001", config=_make_config())
    out = adapter.normalize_item(_RAW_ITEM)
    assert out["账单号"] == "2DF-20260601-0001"
    assert out["商品名称"] == "麻辣牛蛙"
    assert out["商品编码"] == "SP1001"
    assert out["商品分类"] == "锅底"
    assert out["数量"] == "2"
    assert out["单价"] == "68.00"
    assert out["销售金额"] == "136.00"
    assert out["单位"] == "份"


# ── normalize_payment: 二维火 → payment-column canonical shape ────────────

def test_normalize_payment_maps_paytype_to_amount_column():
    """A 二维火 payment row {payType:微信, payAmount:300} becomes the EAV
    payment-column shape backfill_silver._extract_payments scans:
    {账单号, 微信: 300}. The payType value names the column."""
    adapter = TwoDFirePosAdapter(factory_id="F001", config=_make_config())
    out = adapter.normalize_payment(_RAW_PAYMENT)
    assert out["账单号"] == "2DF-20260601-0001"
    assert out["微信"] == "300.00"


def test_normalize_payment_cash():
    adapter = TwoDFirePosAdapter(factory_id="F001", config=_make_config())
    out = adapter.normalize_payment({"orderId": "X1", "payType": "现金", "payAmount": "100"})
    assert out == {"账单号": "X1", "现金": "100"}


# ── FIELD_MAP targets must be keys the existing pipeline understands ──────

def test_field_map_order_targets_are_known_aliases():
    """Every canonical Chinese column the order FIELD_MAP emits MUST exist in
    ALIAS_TO_ATTR — otherwise backfill_silver would silently drop it. This
    pins adapter ↔ pipeline so a drift on either side fails CI loudly."""
    for src_field, canonical_col in FIELD_MAP_ORDER.items():
        assert canonical_col in ALIAS_TO_ATTR, (
            f"order FIELD_MAP target {canonical_col!r} (from {src_field!r}) "
            f"is not a recognized ALIAS_TO_ATTR column — backfill would drop it"
        )


def test_field_map_item_targets_are_known_aliases():
    for src_field, canonical_col in FIELD_MAP_ITEM.items():
        assert canonical_col in ALIAS_TO_ATTR, (
            f"item FIELD_MAP target {canonical_col!r} (from {src_field!r}) "
            f"is not a recognized ALIAS_TO_ATTR column"
        )


def test_field_map_payment_paytype_pinned():
    """Payment mapping is special: payType VALUE becomes the column NAME, and
    payAmount becomes its value. Assert the FIELD_MAP encodes which source
    fields drive that (so completion = verifying field names, not logic)."""
    assert FIELD_MAP_PAYMENT["pay_type_field"] == "payType"
    assert FIELD_MAP_PAYMENT["pay_amount_field"] == "payAmount"
    assert FIELD_MAP_PAYMENT["bill_no_field"] == "orderId"
