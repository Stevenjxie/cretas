"""Unit tests for P4b-safe canonical-dish-aware grouping in
``smartbi.gold.queries.top_products``.

These tests are DB-free: they drive ``top_products`` through a fake
asyncpg pool/connection that captures the SQL + bound args and returns
canned rows shaped like what Postgres would return for the new
canonical-aware query. They prove two things end-to-end:

  (a) NULL-canonical rows map to output that is IDENTICAL in shape and
      values to the pre-P4b per-product behavior (same names, same sums,
      all keys present, NULL-safe provenance) — the safety property.
  (b) Two products sharing a confirmed canonical_dish_id collapse to ONE
      merged row with summed qty/revenue/bill, the canonical display
      name, a representative product_id (an int), and NULL-safe
      provenance — the merge property.

A small in-Python reference implementation of the COALESCE grouping
(`_reference_group`) also asserts, against seed rows, that NULL canonical
=> per-product output is byte-identical, independent of the live DB.

The live-DB GROUP BY semantics are additionally exercised by the
``test_gold_queries.py`` suite when Postgres is configured; here we keep
the proof self-contained so it runs in any environment (CI included).
"""
from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from smartbi.gold.queries import top_products


# ─────────────────────────────────────────────────────────────
# Fake asyncpg pool/connection
# ─────────────────────────────────────────────────────────────
class _FakeConn:
    def __init__(self, rows, capture):
        self._rows = rows
        self._capture = capture

    async def fetch(self, sql, *args):
        self._capture["sql"] = sql
        self._capture["args"] = args
        return self._rows


class _FakeAcquire:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *exc):
        return False


class _FakePool:
    def __init__(self, rows):
        self.rows = rows
        self.capture = {}

    def acquire(self):
        return _FakeAcquire(_FakeConn(self.rows, self.capture))


# ─────────────────────────────────────────────────────────────
# In-Python reference for the COALESCE group key — proves the
# grouping logic the SQL encodes, independent of a live DB.
# ─────────────────────────────────────────────────────────────
def _reference_group(seed):
    """Mirror the SQL group key + display name + sums.

    `seed` is a list of dicts: product_id, canonical_dish_id (None=unmerged),
    canonical_name (None when no canonical row), qty, revenue, bill_count.
    Returns rows shaped like the SQL's `grouped` CTE output (dicts), only
    keeping HAVING SUM(revenue) > 0, NOT yet ordered/limited.
    """
    groups = {}
    for r in seed:
        cdid = r["canonical_dish_id"]
        key = f"c{cdid}" if cdid is not None else f"p{r['product_id']}"
        g = groups.setdefault(
            key,
            {
                "rep_product_id": r["product_id"],
                "display_name": (
                    r["canonical_name"] if cdid is not None and r["canonical_name"]
                    else r["name"]
                ),
                "qty": Decimal("0"),
                "revenue": Decimal("0"),
                "bill_count": 0,
            },
        )
        g["rep_product_id"] = min(g["rep_product_id"], r["product_id"])  # MIN()
        g["qty"] += Decimal(str(r["qty"]))
        g["revenue"] += Decimal(str(r["revenue"]))
        g["bill_count"] += r["bill_count"]
    return [g for g in groups.values() if g["revenue"] > 0]


def _to_sql_rows(grouped):
    """Shape `grouped` CTE rows into the final SELECT row dicts the Python
    mapping consumes (provenance all-NULL — prod-OFF state)."""
    return [
        {
            "product_id": g["rep_product_id"],
            "name": g["display_name"],
            "qty": g["qty"],
            "revenue": g["revenue"],
            "bill_count": g["bill_count"],
            "confidence": None,
            "source": None,
            "source_upload_id": None,
            "prov_field_name": None,
        }
        for g in grouped
    ]


_TENANT = "TEST_P4B"
_RANGE = (date(2026, 4, 1), date(2026, 4, 30))
_OUT_KEYS = {
    "product_id", "product_name", "qty_sold", "revenue", "bill_count",
    "confidence", "source", "source_upload_id", "entity_id", "field_name",
}


@pytest.mark.asyncio
async def test_all_null_canonical_identical_to_per_product():
    """SAFETY: every product has canonical_dish_id NULL → each groups by
    itself → output names + sums identical to pre-P4b per-product grouping,
    all keys present, provenance NULL-safe (None / 'revenue' fallback)."""
    seed = [
        {"product_id": 11, "canonical_dish_id": None, "canonical_name": None,
         "name": "青花椒鱼", "qty": 5, "revenue": 500, "bill_count": 5},
        {"product_id": 12, "canonical_dish_id": None, "canonical_name": None,
         "name": "毛血旺", "qty": 3, "revenue": 240, "bill_count": 3},
    ]
    grouped = _reference_group(seed)
    # NULL canonical → exactly one group per product, same name + sums.
    assert {g["display_name"] for g in grouped} == {"青花椒鱼", "毛血旺"}
    for g in grouped:
        # Single member: rep id == the product's own id, sums == the row.
        src = next(s for s in seed if s["product_id"] == g["rep_product_id"])
        assert g["rep_product_id"] == src["product_id"]
        assert g["qty"] == Decimal(str(src["qty"]))
        assert g["revenue"] == Decimal(str(src["revenue"]))
        assert g["bill_count"] == src["bill_count"]

    pool = _FakePool(_to_sql_rows(sorted(grouped, key=lambda x: -x["revenue"])))
    out = await top_products(pool, _TENANT, _RANGE, top_n=5)

    items = out["top_products"]
    assert len(items) == 2
    # Output shape: every item has exactly the canonical key set.
    for it in items:
        assert set(it.keys()) == _OUT_KEYS
        assert isinstance(it["product_id"], int)
        assert it["confidence"] is None          # NULL-safe
        assert it["source"] is None
        assert it["source_upload_id"] is None
        assert it["field_name"] == "revenue"     # deterministic fallback
        assert it["entity_id"] == str(it["product_id"])
    # Names + sums identical to per-product.
    assert items[0]["product_name"] == "青花椒鱼"
    assert items[0]["revenue"] == 500.0
    assert items[0]["qty_sold"] == 5.0
    assert items[0]["bill_count"] == 5
    assert items[0]["product_id"] == 11
    assert items[1]["product_name"] == "毛血旺"
    assert items[1]["revenue"] == 240.0
    # Envelope shape preserved.
    assert out["factory_id"] == _TENANT
    assert out["start_month"] == "2026-04-01"
    assert out["end_month"] == "2026-04-01"


@pytest.mark.asyncio
async def test_shared_canonical_merges_into_one_row():
    """MERGE: two products confirmed-merged under the same canonical_dish_id
    collapse to ONE row — summed qty/revenue/bill, canonical display name,
    representative product_id (int), NULL-safe provenance. A third unmerged
    product still groups by itself (COALESCE fallback unchanged)."""
    seed = [
        # Two variants of the signature dish, both confirmed → canonical 900.
        {"product_id": 21, "canonical_dish_id": 900, "canonical_name": "招牌青花椒味",
         "name": "招牌青花椒鱼(单人份)", "qty": 4, "revenue": 400, "bill_count": 4},
        {"product_id": 22, "canonical_dish_id": 900, "canonical_name": "招牌青花椒味",
         "name": "青花椒味鱼", "qty": 6, "revenue": 360, "bill_count": 6},
        # Unmerged product — must remain its own row.
        {"product_id": 23, "canonical_dish_id": None, "canonical_name": None,
         "name": "毛血旺", "qty": 2, "revenue": 100, "bill_count": 2},
    ]
    grouped = _reference_group(seed)
    # Reference grouping: 2 groups (canonical 900 merged + product 23).
    assert len(grouped) == 2
    merged = next(g for g in grouped if g["display_name"] == "招牌青花椒味")
    assert merged["qty"] == Decimal("10")          # 4 + 6
    assert merged["revenue"] == Decimal("760")     # 400 + 360
    assert merged["bill_count"] == 10              # 4 + 6
    assert merged["rep_product_id"] == 21          # MIN(21, 22)

    pool = _FakePool(_to_sql_rows(sorted(grouped, key=lambda x: -x["revenue"])))
    out = await top_products(pool, _TENANT, _RANGE, top_n=5)

    items = out["top_products"]
    assert len(items) == 2                          # NOT 3 — merged
    top = items[0]
    assert set(top.keys()) == _OUT_KEYS
    assert top["product_name"] == "招牌青花椒味"      # canonical display name
    assert top["qty_sold"] == 10.0
    assert top["revenue"] == 760.0
    assert top["bill_count"] == 10
    assert isinstance(top["product_id"], int)       # representative, int shape
    assert top["product_id"] == 21
    assert top["entity_id"] == "21"
    # NULL-safe provenance on the merged row (prod-OFF empty field_provenance).
    assert top["confidence"] is None
    assert top["source"] is None
    assert top["source_upload_id"] is None
    assert top["field_name"] == "revenue"
    # Unmerged product still present, its own row.
    assert items[1]["product_name"] == "毛血旺"
    assert items[1]["product_id"] == 23
    assert items[1]["revenue"] == 100.0


@pytest.mark.asyncio
async def test_sql_uses_coalesce_canonical_fallback_grouping():
    """The emitted SQL must LEFT JOIN dim_canonical_dish and group by the
    COALESCE(canonical, product) key — this is the structural guarantee that
    a NULL canonical_dish_id falls back to per-product grouping. Also verify
    HAVING revenue > 0, the order whitelist, and bound args are unchanged."""
    pool = _FakePool([])  # empty result is fine; we inspect the SQL.
    await top_products(pool, _TENANT, _RANGE, top_n=7, order="asc")
    sql = pool.capture["sql"]
    assert "LEFT JOIN dim_canonical_dish" in sql
    assert "canonical_dish_id = p.canonical_dish_id" in sql
    assert "COALESCE('c' || p.canonical_dish_id" in sql
    assert "COALESCE(cd.canonical_name, p.name)" in sql
    assert "MIN(p.product_id)" in sql
    assert "HAVING SUM(a.revenue) > 0" in sql
    assert "餐巾纸" in sql
    assert "(?:白)?米饭" in sql
    assert "p.sub_category" in sql
    assert "ORDER BY g.revenue ASC" in sql          # order whitelist honored
    # Bound args unchanged: factory_id, start_month(=2026-04-01), end_month, top_n.
    args = pool.capture["args"]
    assert args[0] == _TENANT
    assert args[1] == date(2026, 4, 1)
    assert args[2] == date(2026, 4, 1)
    assert args[3] == 7


@pytest.mark.asyncio
async def test_order_desc_default_in_sql():
    """Default order is DESC (revenue ranking) — unchanged from pre-P4b."""
    pool = _FakePool([])
    await top_products(pool, _TENANT, _RANGE, top_n=5)
    assert "ORDER BY g.revenue DESC" in pool.capture["sql"]


@pytest.mark.asyncio
async def test_inverted_range_still_raises():
    """Range validation unchanged — inverted range raises before any query."""
    pool = _FakePool([])
    with pytest.raises(ValueError, match="start .* > end"):
        await top_products(pool, _TENANT, (date(2026, 4, 30), date(2026, 4, 1)))
