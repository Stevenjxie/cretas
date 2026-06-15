"""Phase 0: Layer-0 deterministic pin + controlled-vocab reject routing tests."""
from smartbi.services.semantic_mapper import SemanticMapper
from smartbi.services.domain_standard_fields import (
    ALL_CANONICAL_FIELDS,
    STANDARD_FIELDS,
    DOMAIN_FIELDS,
)


# ── fake asyncpg pool supporting transaction() + execute() + fetch() ──
class _Acq:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, *a):
        return False


class _Txn:
    async def __aenter__(self):
        return None

    async def __aexit__(self, *a):
        return False


class _Conn:
    def __init__(self, rows):
        self.rows = rows
        self.execs = []

    def transaction(self):
        return _Txn()

    async def execute(self, q, *a):
        self.execs.append((q, a))

    async def fetch(self, q, *a):
        self.fetched = (q, a)
        return self.rows


class _Pool:
    def __init__(self, rows):
        self.conn = _Conn(rows)

    def acquire(self):
        return _Acq(self.conn)


def _patch_pool(monkeypatch, pool):
    async def _get():
        return pool
    monkeypatch.setattr("smartbi.config.get_pg_pool", _get)


# ── controlled dictionary (single source of truth) ──
def test_dict_covers_all_domains():
    # finance migrated + production/sales/purchase/inventory added
    for f in ("revenue", "budget_amount", "output_quantity", "yield_rate",
              "sales_amount", "unit_price", "purchase_amount", "supplier_name",
              "stock_quantity", "turnover_rate"):
        assert f in ALL_CANONICAL_FIELDS, f
    assert set(DOMAIN_FIELDS) == {"finance", "production", "sales", "purchase", "inventory"}
    # shared dims present in every domain
    for d in DOMAIN_FIELDS.values():
        assert "period" in d and "category" in d


def test_finance_synonyms_preserved():
    # rule layer depends on synonyms — migration must keep them
    assert "营业收入" in STANDARD_FIELDS["revenue"]["synonyms"]
    assert "出成率" in STANDARD_FIELDS["yield_rate"]["synonyms"]


# ── template key (order-independent fingerprint) ──
def test_template_key_order_independent():
    a = SemanticMapper._compute_template_key(["销售额", "日期", "客户"])
    b = SemanticMapper._compute_template_key(["客户", "销售额", "日期"])
    assert a == b and len(a) == 16


def test_template_key_differs_by_column_set():
    a = SemanticMapper._compute_template_key(["销售额", "日期"])
    b = SemanticMapper._compute_template_key(["销售额", "日期", "客户"])
    assert a != b


# ── Layer 0 pin ──
async def test_pin_no_factory_fail_open():
    m = SemanticMapper()
    hits, unmapped = await m._map_with_pin(["营业额"], None)
    assert hits == [] and unmapped == ["营业额"]


async def test_pin_no_pool_fail_open(monkeypatch):
    _patch_pool(monkeypatch, None)
    m = SemanticMapper()
    hits, unmapped = await m._map_with_pin(["营业额"], "F1")
    assert hits == [] and unmapped == ["营业额"]


async def test_pin_hit_sets_guc_in_txn_and_maps(monkeypatch):
    pool = _Pool(rows=[{"column_name": "营业额", "standard_name": "sales_amount",
                        "confidence": 1.0}])
    _patch_pool(monkeypatch, pool)
    m = SemanticMapper()
    hits, unmapped = await m._map_with_pin(["营业额", "神秘列"], "F1")
    # pinned column mapped deterministically, unknown falls through
    assert unmapped == ["神秘列"]
    assert len(hits) == 1
    assert hits[0].standard == "sales_amount" and hits[0].method == "pin"
    # RLS GUC was set inside the transaction before the read
    assert any("set_config" in q and a == ("F1",) for q, a in pool.conn.execs)


async def test_pin_db_error_fail_open(monkeypatch):
    class _BoomConn(_Conn):
        async def fetch(self, q, *a):
            raise RuntimeError("db down")
    pool = _Pool(rows=[])
    pool.conn = _BoomConn([])
    _patch_pool(monkeypatch, pool)
    m = SemanticMapper()
    hits, unmapped = await m._map_with_pin(["营业额"], "F1")
    assert hits == [] and unmapped == ["营业额"]


async def test_pin_off_vocab_standard_ignored(monkeypatch):
    # a stale pin pointing at a non-canonical name is not trusted
    pool = _Pool(rows=[{"column_name": "营业额", "standard_name": "数量金额_2",
                        "confidence": 1.0}])
    _patch_pool(monkeypatch, pool)
    m = SemanticMapper()
    hits, unmapped = await m._map_with_pin(["营业额"], "F1")
    assert hits == [] and unmapped == ["营业额"]
