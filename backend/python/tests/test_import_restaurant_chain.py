"""Unit tests for scripts/etl/import_restaurant_chain.py + scripts/etl/_lib/upsert_helpers.py.

Sub-ETL-2 (task #71 redispatch) per:
- spec §3.3 (loader skeleton), §1.5 (UPSERT keys per PR #332 audit), §6 (Sub-ETL-2c gate)

Tests use in-memory FakeConn mock + tmp_path file fixtures. No real DB.

Coverage targets:
1. Idempotency: load same CSV twice → same final stats counts (DELETE-INSERT pattern verified).
2. UPSERT correctness: SQL bound to correct natural keys per PR #332 audit.
3. Per-row failure: bad row aborts batch (Q-ETL-6 fail-loud).
4. Period parsing: 'YYYY-MM' / 'YYYY-MM-DD' / fallbacks.
5. RLS scope set: app.factory_id is set before any INSERT.
6. Gold refresh: agg_restaurant_daily_totals INSERT...ON CONFLICT DO UPDATE.

Per spec §3.3: NO real asyncpg connection — fakes only.
Per concurrent-edit-safety.md Rule 2: worktree-isolated tests.
"""

from __future__ import annotations

import asyncio
import csv
import importlib.util
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Optional

import pytest


# scripts/etl/ is operational (Q-ETL-5) — load by file path.
_PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
_LOADER_PATH = _PROJECT_ROOT / "scripts" / "etl" / "import_restaurant_chain.py"
_HELPERS_PATH = _PROJECT_ROOT / "scripts" / "etl" / "_lib" / "upsert_helpers.py"

# Ensure scripts/etl/ is on sys.path so import_restaurant_chain.py's
# `from _lib.upsert_helpers import ...` resolves at module load.
_SCRIPTS_ETL = _PROJECT_ROOT / "scripts" / "etl"
if str(_SCRIPTS_ETL) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS_ETL))

# Load _lib.upsert_helpers FIRST under the canonical name 'upsert_helpers'
# (matching what scripts/etl/import_restaurant_chain.py imports as `from _lib.upsert_helpers`).
# We also register it in sys.modules under the dotted path so dataclass __module__
# resolves correctly when @dataclass executes module-load.
_helpers_spec = importlib.util.spec_from_file_location(
    "_lib.upsert_helpers", _HELPERS_PATH,
)
assert _helpers_spec is not None and _helpers_spec.loader is not None
H = importlib.util.module_from_spec(_helpers_spec)
# Register under canonical name first so dataclass __module__ lookups succeed.
sys.modules["_lib.upsert_helpers"] = H
_helpers_spec.loader.exec_module(H)

_loader_spec = importlib.util.spec_from_file_location(
    "import_restaurant_chain", _LOADER_PATH,
)
assert _loader_spec is not None and _loader_spec.loader is not None, (
    f"cannot load {_LOADER_PATH}"
)
L = importlib.util.module_from_spec(_loader_spec)
sys.modules["import_restaurant_chain"] = L
_loader_spec.loader.exec_module(L)


# ──────────────────────────────────────────────────────────────────
# FakeConn — in-memory mock of asyncpg.Connection
# ──────────────────────────────────────────────────────────────────

@dataclass
class FakeRow:
    """Mimics asyncpg.Record with .get / [] access."""
    data: dict

    def __getitem__(self, k):
        return self.data[k]

    def get(self, k, default=None):
        return self.data.get(k, default)


class FakeConn:
    """In-memory mock of asyncpg.Connection.

    Tracks:
    - app.factory_id (set by SET command)
    - executed SQL log
    - "tables" as dicts (dim_store / dim_product / dim_ingredient /
      fact_pos_transaction / fact_pos_item / fact_restaurant_requisition /
      agg_restaurant_daily_totals)
    - BIGSERIAL counters

    UPSERT semantics:
    - dim_store ON CONFLICT (factory_id, name) → updates or inserts.
    - Returns FakeRow with surrogate id.
    """

    def __init__(self) -> None:
        self.factory_id: Optional[str] = None
        self.executed: list[tuple[str, tuple]] = []
        self._next_store_id = 1
        self._next_product_id = 1
        self._next_ingredient_id = 1
        self._next_txn_id = 1
        # tables (keyed by natural key tuple → row dict)
        self.dim_store: dict[tuple, dict] = {}
        self.dim_product: dict[tuple, dict] = {}
        self.dim_ingredient: dict[tuple, dict] = {}
        self.fact_pos_transaction: dict[tuple, dict] = {}
        self.fact_pos_item: list[dict] = []
        self.fact_restaurant_requisition: dict[tuple, dict] = {}
        self.agg_restaurant_daily_totals: dict[tuple, dict] = {}

    async def execute(self, sql: str, *args) -> str:
        self.executed.append((sql, args))
        s = sql.strip().upper()

        # After P1 fix (audit 2026-05-11): set_factory_scope / connect_factory_scoped
        # now use `SELECT set_config('app.factory_id', $1, <bool>)` because PostgreSQL
        # `SET` does not accept bind params. Match both old + new shapes defensively.
        if s.startswith("SET APP.FACTORY_ID") or "SET_CONFIG('APP.FACTORY_ID'" in s:
            self.factory_id = args[0]
            return "SET"

        if "DELETE FROM FACT_POS_ITEM" in s:
            txn_id, factory_id = args
            before = len(self.fact_pos_item)
            self.fact_pos_item = [
                i for i in self.fact_pos_item
                if not (i["transaction_id"] == txn_id and i["factory_id"] == factory_id)
            ]
            after = len(self.fact_pos_item)
            return f"DELETE {before - after}"

        if "INSERT INTO FACT_POS_ITEM" in s:
            (txn_id, factory_id, product_id, qty, unit_price, amount,
             source_item_raw, return_qty) = args
            self.fact_pos_item.append({
                "transaction_id": txn_id,
                "factory_id": factory_id,
                "product_id": product_id,
                "qty": qty,
                "unit_price": unit_price,
                "amount": amount,
                "source_item_raw": source_item_raw,
                "return_qty": return_qty,
            })
            return "INSERT 0 1"

        if "INSERT INTO AGG_RESTAURANT_DAILY_TOTALS" in s:
            factory_id, start_date, end_date = args
            # Synthesize rows from fact_restaurant_requisition in range.
            touched = 0
            by_date: dict[date, list[dict]] = {}
            for r in self.fact_restaurant_requisition.values():
                if r["factory_id"] != factory_id:
                    continue
                rd = r["date"]
                if rd < start_date or rd > end_date:
                    continue
                by_date.setdefault(rd, []).append(r)
            for d, items in by_date.items():
                qty_total = sum((x.get("actual_qty") or x.get("requested_qty") or 0)
                                for x in items)
                cost_total = sum((x.get("est_cost") or 0) for x in items)
                key = (factory_id, d)
                existing = self.agg_restaurant_daily_totals.get(key)
                version = (existing["version"] + 1) if existing else 1
                self.agg_restaurant_daily_totals[key] = {
                    "factory_id": factory_id,
                    "date": d,
                    "requisition_count": len(items),
                    "requisition_qty_total": qty_total,
                    "requisition_cost_total": cost_total,
                    "version": version,
                }
                touched += 1
            return f"INSERT 0 {touched}"

        return "OK"

    async def fetchrow(self, sql: str, *args) -> Optional[FakeRow]:
        self.executed.append((sql, args))
        s = sql.strip().upper()

        if "INSERT INTO DIM_STORE" in s:
            factory_id, name = args[0], args[1]
            key = (factory_id, name)
            if key in self.dim_store:
                return FakeRow({"store_id": self.dim_store[key]["store_id"]})
            sid = self._next_store_id
            self._next_store_id += 1
            self.dim_store[key] = {
                "store_id": sid,
                "factory_id": factory_id,
                "name": name,
                "brand": args[2],
                "city": args[3],
                "province": args[4],
                "region": args[5],
            }
            return FakeRow({"store_id": sid})

        if "INSERT INTO DIM_PRODUCT" in s:
            factory_id, name, norm_name = args[0], args[1], args[2]
            key = (factory_id, norm_name)
            if key in self.dim_product:
                return FakeRow({"product_id": self.dim_product[key]["product_id"]})
            pid = self._next_product_id
            self._next_product_id += 1
            self.dim_product[key] = {
                "product_id": pid,
                "factory_id": factory_id,
                "name": name,
                "normalized_name": norm_name,
            }
            return FakeRow({"product_id": pid})

        if "INSERT INTO DIM_INGREDIENT" in s:
            factory_id, source_pk = args[0], args[1]
            key = (factory_id, source_pk)
            if key in self.dim_ingredient:
                return FakeRow({"ingredient_id": self.dim_ingredient[key]["ingredient_id"]})
            iid = self._next_ingredient_id
            self._next_ingredient_id += 1
            self.dim_ingredient[key] = {
                "ingredient_id": iid,
                "factory_id": factory_id,
                "source_pk": source_pk,
                "name": args[2],
            }
            return FakeRow({"ingredient_id": iid})

        if "INSERT INTO FACT_POS_TRANSACTION" in s:
            # args order per FACT_POS_TXN_UPSERT_SQL:
            # (factory_id, upload_id, source_type, source_bill_no, store_id, ...,
            #  item_count $19, has_discount $20)
            factory_id, _upload_id, source_type, source_bill_no, store_id = args[0:5]
            key = (factory_id, source_type, store_id, source_bill_no)
            # item_count is the 19th SQL param → args index 18.
            item_count = args[18] if len(args) > 18 else None
            if key in self.fact_pos_transaction:
                # UPSERT path: update item_count to reflect latest write.
                self.fact_pos_transaction[key]["item_count"] = item_count
                return FakeRow({"id": self.fact_pos_transaction[key]["id"]})
            txn_id = self._next_txn_id
            self._next_txn_id += 1
            self.fact_pos_transaction[key] = {
                "id": txn_id,
                "factory_id": factory_id,
                "source_type": source_type,
                "source_bill_no": source_bill_no,
                "store_id": store_id,
                "date": args[6],  # date is arg index 6
                "item_count": item_count,
            }
            return FakeRow({"id": txn_id})

        if "INSERT INTO FACT_RESTAURANT_REQUISITION" in s:
            factory_id, source_pk = args[0], args[1]
            key = (factory_id, source_pk)
            row_data = {
                "id": 1,
                "factory_id": factory_id,
                "source_pk": source_pk,
                "date": args[3],
                "ingredient_id": args[4],
                "requested_qty": args[8],
                "actual_qty": args[9],
                "est_cost": args[11],
            }
            self.fact_restaurant_requisition[key] = row_data
            return FakeRow({"id": 1})

        return None

    async def close(self) -> None:
        pass

    def transaction(self):
        return FakeTransaction(self)


class FakeTransaction:
    """Mock asyncpg.Transaction with start/commit/rollback."""

    def __init__(self, conn: FakeConn) -> None:
        self.conn = conn
        self.started = False
        self.committed = False
        self.rolled_back = False

    async def start(self) -> None:
        self.started = True

    async def commit(self) -> None:
        self.committed = True

    async def rollback(self) -> None:
        self.rolled_back = True


# ──────────────────────────────────────────────────────────────────
# Fixtures
# ──────────────────────────────────────────────────────────────────

@pytest.fixture
def conn() -> FakeConn:
    return FakeConn()


@pytest.fixture
def product_sales_csv(tmp_path: Path) -> Path:
    """Build a canonical product_sales CSV with 3 rows × 2 stores."""
    d = tmp_path / "R_ILTEATRO_REAL" / "product_sales"
    d.mkdir(parents=True)
    p = d / "2024-02.csv"
    rows = [
        # (store, product, qty_total, gross_amount, ...)
        ["店A", "猫山王榴莲", "意式甜品", "甜品组",
         "P001", "份", "single", "regular",
         "10", "10", "0", "0",
         "份", "58", "580", "580", "0", "0", "580"],
        ["店A", "海盐焦糖布丁", "意式甜品", "甜品组",
         "P002", "份", "single", "regular",
         "5", "5", "0", "0",
         "份", "38", "190", "190", "0", "0", "190"],
        ["店B", "海盐焦糖布丁", "意式甜品", "甜品组",
         "P002", "份", "single", "regular",
         "8", "8", "1", "0",
         "份", "38", "304", "266", "38", "38", "266"],
    ]
    with p.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow([
            "store_name", "product_name", "product_category", "revenue_group",
            "product_code", "spec", "product_type", "order_method",
            "qty_single", "qty_total", "qty_refund", "qty_combo_child",
            "unit", "unit_price", "gross_amount", "net_after_discount",
            "discount_allocated", "refund_amount", "actual_receive",
        ])
        for r in rows:
            w.writerow(r)
    return p


@pytest.fixture
def purchase_inbound_csv(tmp_path: Path) -> Path:
    """Build a canonical purchase_inbound CSV with 2 rows × 2 ingredients."""
    d = tmp_path / "R_ILTEATRO_REAL" / "purchase_inbound"
    d.mkdir(parents=True)
    p = d / "2024-03.csv"
    rows = [
        ["2024-03-01", "PO001", "供应商A", "I001", "面粉", "袋", "kg", "25.0", "8.5", "212.5", "主仓"],
        ["2024-03-01", "PO001", "供应商A", "I002", "糖", "袋", "kg", "10.0", "6.0", "60.0", "主仓"],
    ]
    with p.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow([
            "inbound_date", "doc_no", "supplier_name", "product_code", "product_name",
            "spec", "unit", "inbound_qty", "unit_price", "total_amount", "warehouse",
        ])
        for r in rows:
            w.writerow(r)
    return p


# ──────────────────────────────────────────────────────────────────
# upsert_helpers — unit tests
# ──────────────────────────────────────────────────────────────────

def test_normalize_product_name_basic() -> None:
    assert H.normalize_product_name("Cat Mountain Durian") == "cat mountain durian"


def test_normalize_product_name_whitespace_collapse() -> None:
    assert H.normalize_product_name("  A   B \t C  ") == "a b c"


def test_normalize_product_name_empty_raises() -> None:
    with pytest.raises(ValueError, match="empty after normalize"):
        H.normalize_product_name("   ")


def test_normalize_product_name_none_raises() -> None:
    with pytest.raises(ValueError, match="name required"):
        H.normalize_product_name(None)


def test_loader_stats_to_dict_shape(conn: FakeConn) -> None:
    s = H.LoaderStats(factory_id="R_X")
    d = s.to_dict()
    assert d["factory_id"] == "R_X"
    assert d["dim_store_upserts"] == 0
    assert d["fact_pos_item_inserts"] == 0
    assert "errors" in d
    assert isinstance(d["errors"], list)


def test_set_factory_scope_empty_raises() -> None:
    async def _run():
        c = FakeConn()
        with pytest.raises(ValueError, match="factory_id required"):
            await H.set_factory_scope(c, "")
    asyncio.run(_run())


def test_set_factory_scope_sets_app_setting() -> None:
    async def _run():
        c = FakeConn()
        await H.set_factory_scope(c, "R_TEST")
        assert c.factory_id == "R_TEST"
    asyncio.run(_run())


def test_upsert_dim_store_returns_id() -> None:
    async def _run():
        c = FakeConn()
        sid = await H.upsert_dim_store(c, "R_X", "店A")
        assert sid == 1
        # Re-upsert same key returns same id (idempotent).
        sid2 = await H.upsert_dim_store(c, "R_X", "店A")
        assert sid2 == 1
        # Distinct store → distinct id.
        sid3 = await H.upsert_dim_store(c, "R_X", "店B")
        assert sid3 == 2
    asyncio.run(_run())


def test_upsert_dim_store_factory_id_required() -> None:
    async def _run():
        c = FakeConn()
        with pytest.raises(ValueError, match="factory_id required"):
            await H.upsert_dim_store(c, "", "店A")
    asyncio.run(_run())


def test_upsert_dim_store_name_required() -> None:
    async def _run():
        c = FakeConn()
        with pytest.raises(ValueError, match="name required"):
            await H.upsert_dim_store(c, "R_X", "   ")
    asyncio.run(_run())


def test_upsert_dim_product_normalized_name_dedup() -> None:
    async def _run():
        c = FakeConn()
        # Same normalized → same product_id (Trad→Simp not done here, but
        # whitespace + case collapse covers basics).
        pid1 = await H.upsert_dim_product(c, "R_X", "Pasta Bolognese")
        pid2 = await H.upsert_dim_product(c, "R_X", "  pasta  bolognese  ")
        assert pid1 == pid2 == 1
    asyncio.run(_run())


def test_upsert_dim_ingredient_source_pk_dedup() -> None:
    async def _run():
        c = FakeConn()
        iid1 = await H.upsert_dim_ingredient(
            c, "R_X", "src-pk-1", "面粉", unit="kg",
        )
        iid2 = await H.upsert_dim_ingredient(
            c, "R_X", "src-pk-1", "面粉", unit="kg",
        )
        assert iid1 == iid2 == 1
    asyncio.run(_run())


def test_upsert_fact_pos_transaction_required_fields() -> None:
    async def _run():
        c = FakeConn()
        with pytest.raises(ValueError, match="source_bill_no required"):
            await H.upsert_fact_pos_transaction(
                c, factory_id="R_X", source_type="excel",
                source_bill_no="", store_id=1, date=date(2024, 2, 1),
            )
        with pytest.raises(ValueError, match="store_id required"):
            await H.upsert_fact_pos_transaction(
                c, factory_id="R_X", source_type="excel",
                source_bill_no="BILL_1", store_id=None, date=date(2024, 2, 1),
            )
    asyncio.run(_run())


def test_upsert_fact_pos_transaction_idempotent() -> None:
    async def _run():
        c = FakeConn()
        kwargs = dict(
            factory_id="R_X", source_type="excel",
            source_bill_no="BILL_1", store_id=42, date=date(2024, 2, 1),
            gross_amount=100.0,
        )
        tid1 = await H.upsert_fact_pos_transaction(c, **kwargs)
        tid2 = await H.upsert_fact_pos_transaction(c, **kwargs)
        assert tid1 == tid2  # natural key dedup
    asyncio.run(_run())


def test_replace_fact_pos_items_delete_insert_pattern() -> None:
    """Idempotency proof for fact_pos_item (no UNIQUE — DELETE+INSERT)."""
    async def _run():
        c = FakeConn()
        stats = H.LoaderStats(factory_id="R_X")
        items = [
            {"product_id": 1, "qty": 5.0, "unit_price": 10.0, "amount": 50.0,
             "source_item_raw": "raw1", "return_qty": 0.0},
            {"product_id": 2, "qty": 3.0, "unit_price": 8.0, "amount": 24.0,
             "source_item_raw": "raw2", "return_qty": 0.0},
        ]
        # Run 1: no prior rows → delete=0, insert=2
        d1, i1 = await H.replace_fact_pos_items(c, "R_X", 100, items, stats=stats)
        assert d1 == 0 and i1 == 2
        # Run 2: prior=2 → delete=2, insert=2 (same final state)
        d2, i2 = await H.replace_fact_pos_items(c, "R_X", 100, items, stats=stats)
        assert d2 == 2 and i2 == 2
        # Total fact_pos_item rows = 2 (DELETE removed run 1's, INSERT added 2 fresh).
        rows_for_txn = [r for r in c.fact_pos_item if r["transaction_id"] == 100]
        assert len(rows_for_txn) == 2
        # Stats reflect cumulative counts.
        assert stats.fact_pos_item_inserts == 4
        assert stats.fact_pos_item_deletes == 2
    asyncio.run(_run())


def test_refresh_agg_requires_dates() -> None:
    async def _run():
        c = FakeConn()
        with pytest.raises(ValueError, match="start_date"):
            await H.refresh_agg_restaurant_daily_totals(
                c, "R_X", None, date(2024, 2, 28),
            )
    asyncio.run(_run())


def test_refresh_agg_idempotent_via_version_increment() -> None:
    """Calling refresh twice on same range → version bumps."""
    async def _run():
        c = FakeConn()
        # Seed a fact_restaurant_requisition row in range.
        await H.upsert_fact_restaurant_requisition(
            c, factory_id="R_X", source_pk="src-1",
            date=date(2024, 3, 1), requested_qty=10.0, actual_qty=10.0, est_cost=100.0,
            type_="PURCHASE", status="RECEIVED",
        )
        n1 = await H.refresh_agg_restaurant_daily_totals(
            c, "R_X", date(2024, 3, 1), date(2024, 3, 31),
        )
        n2 = await H.refresh_agg_restaurant_daily_totals(
            c, "R_X", date(2024, 3, 1), date(2024, 3, 31),
        )
        assert n1 == n2 == 1
        # version should have bumped.
        key = ("R_X", date(2024, 3, 1))
        assert c.agg_restaurant_daily_totals[key]["version"] == 2
    asyncio.run(_run())


# ──────────────────────────────────────────────────────────────────
# import_restaurant_chain — orchestrator tests
# ──────────────────────────────────────────────────────────────────

def test_period_to_date_iso() -> None:
    assert L._period_to_date("2024-02") == date(2024, 2, 1)


def test_period_to_date_full_iso() -> None:
    assert L._period_to_date("2024-02-15") == date(2024, 2, 15)


def test_period_to_date_with_prefix() -> None:
    assert L._period_to_date("锦川火锅-2024-03") == date(2024, 3, 1)


def test_period_to_date_fallback() -> None:
    assert L._period_to_date("") == date(1970, 1, 1)
    assert L._period_to_date("garbage-stem") == date(1970, 1, 1)


def test_parse_decimal_valid() -> None:
    assert L._parse_decimal("3.14") == 3.14
    assert L._parse_decimal("") is None
    assert L._parse_decimal(None) is None


def test_parse_decimal_bad_raises() -> None:
    with pytest.raises(ValueError, match="bad numeric"):
        L._parse_decimal("not-a-number")


def test_source_pk_deterministic() -> None:
    a = L._source_pk("R_X", "supplier", "code", "name")
    b = L._source_pk("R_X", "supplier", "code", "name")
    assert a == b
    assert a.startswith("R_X:")
    # Different inputs → different hash.
    c = L._source_pk("R_X", "supplier", "code", "other")
    assert a != c


def test_load_product_sales_csv_happy_path(
    conn: FakeConn, product_sales_csv: Path,
) -> None:
    """Loader writes dim_store / dim_product / fact_pos_transaction / fact_pos_item."""
    async def _run():
        await H.set_factory_scope(conn, "R_ILTEATRO_REAL")
        stats = H.LoaderStats(factory_id="R_ILTEATRO_REAL")
        await L.load_product_sales_csv(
            conn, "R_ILTEATRO_REAL", "2024-02", product_sales_csv, stats,
        )
        # 2 distinct stores (店A, 店B), 2 distinct products (cache dedup).
        assert stats.dim_store_upserts == 2
        assert stats.dim_product_upserts == 2
        # 3 txn rows (one per CSV line).
        assert stats.fact_pos_transaction_upserts == 3
        # 3 fact_pos_item rows.
        assert stats.fact_pos_item_inserts == 3
        assert stats.fact_pos_item_deletes == 0
    asyncio.run(_run())


def test_load_product_sales_csv_idempotent(
    conn: FakeConn, product_sales_csv: Path,
) -> None:
    """Run loader twice on same CSV → fact_pos_transaction row count unchanged."""
    async def _run():
        await H.set_factory_scope(conn, "R_ILTEATRO_REAL")
        stats = H.LoaderStats(factory_id="R_ILTEATRO_REAL")
        await L.load_product_sales_csv(
            conn, "R_ILTEATRO_REAL", "2024-02", product_sales_csv, stats,
        )
        # First-load DB state.
        txn_rows_after_first = len(conn.fact_pos_transaction)
        item_rows_after_first = len(conn.fact_pos_item)
        assert txn_rows_after_first == 3
        assert item_rows_after_first == 3

        # Second load (same CSV) — UPSERT keeps natural key dedup.
        await L.load_product_sales_csv(
            conn, "R_ILTEATRO_REAL", "2024-02", product_sales_csv, stats,
        )
        # Same final row count — fact_pos_transaction natural key dedup +
        # fact_pos_item DELETE-by-parent-txn then INSERT.
        assert len(conn.fact_pos_transaction) == txn_rows_after_first
        assert len(conn.fact_pos_item) == item_rows_after_first
        # But delete-count incremented (3 rows from first run were deleted then reinserted).
        assert stats.fact_pos_item_deletes == 3
        assert stats.fact_pos_item_inserts == 6  # 3 first run + 3 second run
    asyncio.run(_run())


def test_load_product_sales_csv_missing_store_raises(
    conn: FakeConn, tmp_path: Path,
) -> None:
    """Bad row (no store_name) → ValueError raised (Q-ETL-6 fail-loud)."""
    p = tmp_path / "bad.csv"
    with p.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow([
            "store_name", "product_name", "product_category", "revenue_group",
            "product_code", "spec", "product_type", "order_method",
            "qty_single", "qty_total", "qty_refund", "qty_combo_child",
            "unit", "unit_price", "gross_amount", "net_after_discount",
            "discount_allocated", "refund_amount", "actual_receive",
        ])
        w.writerow([
            "", "海盐焦糖布丁", "", "", "", "", "", "",
            "5", "5", "0", "0", "份", "38", "190", "190", "0", "0", "190",
        ])

    async def _run():
        await H.set_factory_scope(conn, "R_X")
        stats = H.LoaderStats(factory_id="R_X")
        with pytest.raises(ValueError, match="store_name required"):
            await L.load_product_sales_csv(conn, "R_X", "2024-02", p, stats)
    asyncio.run(_run())


def test_load_product_sales_csv_qty_total_falls_back_to_qty_single(
    conn: FakeConn, tmp_path: Path,
) -> None:
    """L3 fix (PR #357 audit): when source Excel omits 数量(含套餐子商品) and only
    exposes 单卖数量, the loader uses qty_single for both item_count and
    fact_pos_item.qty so downstream Gold aggregates aren't NULL.

    Reproduces IL TEATRO 2月 shape: 17 source cols → canonical has qty_single
    populated but qty_total empty.
    """
    p = tmp_path / "qty_single_only.csv"
    with p.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow([
            "store_name", "product_name", "product_category", "revenue_group",
            "product_code", "spec", "product_type", "order_method",
            "qty_single", "qty_total", "qty_refund", "qty_combo_child",
            "unit", "unit_price", "gross_amount", "net_after_discount",
            "discount_allocated", "refund_amount", "actual_receive",
        ])
        # qty_total intentionally empty; qty_single = 7.
        w.writerow([
            "IL TEATRO", "黑松露披萨", "Pizza披萨", "",
            "", "", "餐饮商品", "单品",
            "7", "", "0", "",
            "份", "158", "1106", "1106", "0", "0", "1106",
        ])
        # Both populated → qty_total wins (sanity row).
        w.writerow([
            "IL TEATRO", "意式甜品", "Open", "",
            "", "", "餐饮商品", "单品",
            "10", "12", "0", "",
            "份", "58", "696", "696", "0", "0", "696",
        ])

    async def _run():
        await H.set_factory_scope(conn, "R_ILTEATRO_REAL")
        stats = H.LoaderStats(factory_id="R_ILTEATRO_REAL")
        await L.load_product_sales_csv(
            conn, "R_ILTEATRO_REAL", "2024-02", p, stats,
        )
        # 2 txn rows written.
        assert stats.fact_pos_transaction_upserts == 2
        # Fallback verification: item_count + qty come from qty_single
        # when qty_total is None.
        item_counts = sorted(
            row["item_count"] for row in conn.fact_pos_transaction.values()
        )
        # Row 1: qty_total missing → use qty_single=7. Row 2: qty_total=12 wins.
        assert item_counts == [7, 12]
        qtys = sorted(it["qty"] for it in conn.fact_pos_item)
        assert qtys == [7.0, 12.0]
    asyncio.run(_run())


def test_load_product_sales_csv_qty_both_missing_yields_null(
    conn: FakeConn, tmp_path: Path,
) -> None:
    """L3 fallback: when BOTH qty_total and qty_single are missing, item_count
    and fact_pos_item.qty stay None (no silent zero, no fabricated data).
    """
    p = tmp_path / "qty_both_missing.csv"
    with p.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow([
            "store_name", "product_name", "product_category", "revenue_group",
            "product_code", "spec", "product_type", "order_method",
            "qty_single", "qty_total", "qty_refund", "qty_combo_child",
            "unit", "unit_price", "gross_amount", "net_after_discount",
            "discount_allocated", "refund_amount", "actual_receive",
        ])
        w.writerow([
            "IL TEATRO", "无量产品", "Open", "",
            "", "", "餐饮商品", "单品",
            "", "", "", "",
            "份", "58", "0", "0", "0", "0", "0",
        ])

    async def _run():
        await H.set_factory_scope(conn, "R_ILTEATRO_REAL")
        stats = H.LoaderStats(factory_id="R_ILTEATRO_REAL")
        await L.load_product_sales_csv(
            conn, "R_ILTEATRO_REAL", "2024-02", p, stats,
        )
        # Both quantities missing → item_count is None (NULL in DB).
        for row in conn.fact_pos_transaction.values():
            assert row["item_count"] is None
        for it in conn.fact_pos_item:
            assert it["qty"] is None
    asyncio.run(_run())


def test_load_purchase_inbound_csv_happy_path(
    conn: FakeConn, purchase_inbound_csv: Path,
) -> None:
    """purchase_inbound writes dim_ingredient + fact_restaurant_requisition."""
    async def _run():
        await H.set_factory_scope(conn, "R_ILTEATRO_REAL")
        stats = H.LoaderStats(factory_id="R_ILTEATRO_REAL")
        await L.load_purchase_inbound_csv(
            conn, "R_ILTEATRO_REAL", "2024-03", purchase_inbound_csv, stats,
        )
        assert stats.dim_ingredient_upserts == 2  # 面粉, 糖
        assert stats.fact_restaurant_requisition_upserts == 2
    asyncio.run(_run())


def test_load_purchase_inbound_csv_idempotent(
    conn: FakeConn, purchase_inbound_csv: Path,
) -> None:
    """Run twice → same final row count (natural key UPSERT)."""
    async def _run():
        await H.set_factory_scope(conn, "R_ILTEATRO_REAL")
        stats = H.LoaderStats(factory_id="R_ILTEATRO_REAL")
        await L.load_purchase_inbound_csv(
            conn, "R_ILTEATRO_REAL", "2024-03", purchase_inbound_csv, stats,
        )
        ingredients_after_first = len(conn.dim_ingredient)
        reqs_after_first = len(conn.fact_restaurant_requisition)

        await L.load_purchase_inbound_csv(
            conn, "R_ILTEATRO_REAL", "2024-03", purchase_inbound_csv, stats,
        )
        assert len(conn.dim_ingredient) == ingredients_after_first
        assert len(conn.fact_restaurant_requisition) == reqs_after_first
        # Final row counts unchanged across runs (idempotent).
        # Counters bump per row-sighting per run (cache is per-call scope).
        # 2 ingredients × 2 runs = 4 upsert SQL hits, but DB state stays at 2.
        assert stats.dim_ingredient_upserts == 4
        assert stats.fact_restaurant_requisition_upserts == 4
    asyncio.run(_run())


def test_discover_canonical_csvs_walks_layout(
    tmp_path: Path, product_sales_csv: Path, purchase_inbound_csv: Path,
) -> None:
    """discover_canonical_csvs yields (report_type, period, path) tuples."""
    source_dir = tmp_path / "R_ILTEATRO_REAL"
    entries = list(L.discover_canonical_csvs(source_dir))
    # 1 product_sales + 1 purchase_inbound, deterministic sorted by report_type.
    assert len(entries) == 2
    report_types = [e[0] for e in entries]
    assert "product_sales" in report_types
    assert "purchase_inbound" in report_types


def test_discover_canonical_csvs_missing_dir_empty() -> None:
    """Missing source_dir → empty iter (not error)."""
    entries = list(L.discover_canonical_csvs(Path("/nonexistent/path/xyz")))
    assert entries == []


def test_discover_canonical_csvs_none_raises() -> None:
    with pytest.raises(ValueError, match="source_dir required"):
        list(L.discover_canonical_csvs(None))


def test_replace_fact_pos_items_validation() -> None:
    async def _run():
        c = FakeConn()
        with pytest.raises(ValueError, match="factory_id required"):
            await H.replace_fact_pos_items(c, "", 100, [])
        with pytest.raises(ValueError, match="transaction_id required"):
            await H.replace_fact_pos_items(c, "R_X", None, [])
        with pytest.raises(ValueError, match="items required"):
            await H.replace_fact_pos_items(c, "R_X", 100, None)
    asyncio.run(_run())


def test_rls_scope_set_before_inserts(
    conn: FakeConn, product_sales_csv: Path,
) -> None:
    """RLS contract: app.factory_id must be set before any INSERT.

    Verifies SET command appears in execute log BEFORE any INSERT-into-fact_pos_item.
    """
    async def _run():
        await H.set_factory_scope(conn, "R_ILTEATRO_REAL")
        stats = H.LoaderStats(factory_id="R_ILTEATRO_REAL")
        await L.load_product_sales_csv(
            conn, "R_ILTEATRO_REAL", "2024-02", product_sales_csv, stats,
        )
        # Find SET position. After P1 fix (audit 2026-05-11) the helper uses
        # `SELECT set_config('app.factory_id', $1, false)` instead of the literal
        # `SET app.factory_id = $1` (which PostgreSQL rejects with bind params).
        set_idx = next(
            (i for i, (sql, _) in enumerate(conn.executed)
             if sql.strip().upper().startswith("SET APP.FACTORY_ID")
             or "SET_CONFIG('APP.FACTORY_ID'" in sql.strip().upper()),
            None,
        )
        assert set_idx is not None, "factory_id scope was never set"
        # Find first INSERT INTO fact_pos_item.
        first_insert_item_idx = next(
            (i for i, (sql, _) in enumerate(conn.executed)
             if "INSERT INTO FACT_POS_ITEM" in sql.strip().upper()),
            None,
        )
        assert first_insert_item_idx is not None
        assert set_idx < first_insert_item_idx, (
            f"SET app.factory_id at {set_idx} not before INSERT at {first_insert_item_idx}"
        )
    asyncio.run(_run())


def test_upsert_sql_uses_correct_natural_keys() -> None:
    """SQL constants match PR #332 audit findings."""
    # dim_store: UNIQUE (factory_id, name)
    assert "ON CONFLICT (factory_id, name)" in H.DIM_STORE_UPSERT_SQL
    # dim_product: UNIQUE (factory_id, normalized_name)
    assert "ON CONFLICT (factory_id, normalized_name)" in H.DIM_PRODUCT_UPSERT_SQL
    # dim_ingredient: UNIQUE (factory_id, source_pk)  (NOT name)
    assert "ON CONFLICT (factory_id, source_pk)" in H.DIM_INGREDIENT_UPSERT_SQL
    # fact_pos_transaction: uq_fact_pos_txn
    assert (
        "ON CONFLICT (factory_id, source_type, store_id, source_bill_no)"
        in H.FACT_POS_TXN_UPSERT_SQL
    )
    # fact_restaurant_requisition: uq_fact_req_factory_source (NOT source_bill_no)
    assert "ON CONFLICT (factory_id, source_pk)" in H.FACT_REQUISITION_UPSERT_SQL
    # fact_pos_item: NO ON CONFLICT — DELETE-then-INSERT pattern.
    assert "ON CONFLICT" not in H.INSERT_FACT_POS_ITEM_SQL
    assert "DELETE FROM fact_pos_item" in H.DELETE_FACT_POS_ITEM_BY_TXN_SQL


def test_loader_stats_accumulates_errors() -> None:
    """Errors list is mutable on stats."""
    s = H.LoaderStats(factory_id="R_X")
    s.errors.append("err 1")
    s.errors.append("err 2")
    assert len(s.errors) == 2
    d = s.to_dict()
    assert d["errors"] == ["err 1", "err 2"]
