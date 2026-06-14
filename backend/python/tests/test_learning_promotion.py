"""泛化学习毕业 gate + consult 纯函数单测。"""
from smartbi.services.learning_promotion import (
    is_branch_promotable,
    is_trunk_promotable,
    consult_in,
    consult_promoted_db,
)


class _AcquireContext:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, exc_type, exc, tb):
        return False


class _FakePool:
    def __init__(self, rows=None, exc=None):
        self.conn = _FakeConn(rows or [], exc)

    def acquire(self):
        return _AcquireContext(self.conn)


class _FakeConn:
    def __init__(self, rows, exc):
        self.rows = list(rows)
        self.exc = exc
        self.calls = []

    async def fetchrow(self, query, *args):
        self.calls.append((query, args))
        if self.exc:
            raise self.exc
        if self.rows:
            return self.rows.pop(0)
        return None


def _g(**kw):
    d = dict(
        learning_type="field_mapping", source_key="营业进账", target_value="revenue",
        business_type="restaurant", max_confidence=0.95, factory_count=2, has_correction=False,
    )
    d.update(kw)
    return d


# ---- 行业分支 gate ----
def test_branch_llm_consensus():
    ok, _ = is_branch_promotable(_g(), branch={}, trunk={})
    assert ok


def test_branch_blocks_single_factory():
    ok, _ = is_branch_promotable(_g(factory_count=1), {}, {})
    assert not ok


def test_branch_blocks_low_conf():
    ok, _ = is_branch_promotable(_g(max_confidence=0.8), {}, {})
    assert not ok


def test_branch_correction_fasttrack():
    # 折中: 纠正(conf 不限) + 再 1 工厂任意证据 → 升分支
    ok, _ = is_branch_promotable(
        _g(max_confidence=0.5, has_correction=True, factory_count=2), {}, {})
    assert ok


def test_branch_correction_needs_corroboration():
    # 单条纠正(仅 1 工厂)不够
    ok, _ = is_branch_promotable(_g(has_correction=True, factory_count=1), {}, {})
    assert not ok


def test_branch_unknown_business_type_never():
    ok, _ = is_branch_promotable(_g(business_type="unknown"), {}, {})
    assert not ok


def test_branch_skip_already_branched():
    ok, _ = is_branch_promotable(
        _g(), branch={"field_mapping": {"restaurant": {"营业进账": "revenue"}}}, trunk={})
    assert not ok


# ---- 全局主干 gate ----
def test_trunk_promote_two_industries():
    bs = {"field_mapping": {
        "restaurant": {"营业进账": "revenue"},
        "factory": {"营业进账": "revenue"},
    }}
    ok, _ = is_trunk_promotable("field_mapping", "营业进账", "revenue", bs)
    assert ok


def test_trunk_blocks_one_industry():
    bs = {"field_mapping": {"restaurant": {"营业进账": "revenue"}}}
    ok, _ = is_trunk_promotable("field_mapping", "营业进账", "revenue", bs)
    assert not ok


def test_trunk_diff_target_not_counted():
    # 两行业但映射到不同 target → 不算跨行业一致
    bs = {"field_mapping": {
        "restaurant": {"营业进账": "revenue"},
        "factory": {"营业进账": "income"},
    }}
    ok, _ = is_trunk_promotable("field_mapping", "营业进账", "revenue", bs)
    assert not ok


def test_trunk_unknown_consensus_direct():
    # unknown 业态(无行业可 scope)+ 跨工厂共识 → 直升主干(等价 v1 扁平)
    ug = dict(factory_count=2, max_confidence=0.95, has_correction=False)
    ok, _ = is_trunk_promotable("field_mapping", "营业进账", "revenue", {}, unknown_group=ug)
    assert ok


def test_trunk_unknown_single_factory_blocked():
    ug = dict(factory_count=1, max_confidence=0.95, has_correction=False)
    ok, _ = is_trunk_promotable("field_mapping", "营业进账", "revenue", {}, unknown_group=ug)
    assert not ok


def test_branch_unknown_still_never_branches():
    # unknown 永不升分支(改走主干), 即使达共识
    ok, _ = is_branch_promotable(
        _g(business_type="unknown", factory_count=5, max_confidence=0.99), {}, {})
    assert not ok


# ---- consult 层级 ----
def test_consult_branch_over_trunk():
    branch = {"field_mapping": {"restaurant": {"客流": "traffic_branch"}}}
    trunk = {"field_mapping": {"客流": "traffic_trunk"}}
    assert consult_in(branch, trunk, "field_mapping", "客流", "restaurant") == ("traffic_branch", "promoted_industry")
    assert consult_in(branch, trunk, "field_mapping", "客流", "factory") == ("traffic_trunk", "promoted")
    assert consult_in(branch, trunk, "field_mapping", "未知列", "restaurant") == (None, None)


def test_consult_learning_type_isolated():
    trunk = {"field_mapping": {"营业进账": "revenue"}, "classification": {}}
    assert consult_in({}, trunk, "classification", "营业进账", None) == (None, None)
    assert consult_in({}, trunk, "field_mapping", "营业进账", None) == ("revenue", "promoted")


async def test_consult_promoted_db_prefers_industry_branch():
    pool = _FakePool(rows=[{"target_value": "traffic_branch"}])

    result = await consult_promoted_db(pool, "field_mapping", " 客流 ", "restaurant")

    assert result == ("traffic_branch", "promoted_industry")
    assert len(pool.conn.calls) == 1
    query, args = pool.conn.calls[0]
    assert "business_type = $3" in query
    assert "occurrences >= 3" in query
    assert "confidence >= 0.9" in query
    assert args == ("field_mapping", "客流", "restaurant")


async def test_consult_promoted_db_uses_global_trunk_after_industry_miss():
    pool = _FakePool(rows=[None, {"target_value": "traffic_trunk"}])

    result = await consult_promoted_db(pool, "field_mapping", "客流", "restaurant")

    assert result == ("traffic_trunk", "promoted")
    assert len(pool.conn.calls) == 2
    query, args = pool.conn.calls[1]
    assert "business_type = 'unknown'" in query
    assert "occurrences >= 5" in query
    assert "confidence >= 0.92" in query
    assert args == ("field_mapping", "客流")


async def test_consult_promoted_db_none_pool_falls_back_to_json(monkeypatch):
    monkeypatch.setattr(
        "smartbi.services.learning_promotion.consult_promoted",
        lambda learning_type, source_key, business_type=None: (
            f"{learning_type}:{source_key}:{business_type}",
            "promoted",
        ),
    )

    result = await consult_promoted_db(None, "classification", "神秘菜", "restaurant")

    assert result == ("classification:神秘菜:restaurant", "promoted")


async def test_consult_promoted_db_exception_falls_back_to_json(monkeypatch):
    monkeypatch.setattr(
        "smartbi.services.learning_promotion.consult_promoted",
        lambda learning_type, source_key, business_type=None: ("json_value", "promoted"),
    )
    pool = _FakePool(exc=RuntimeError("db unavailable"))

    result = await consult_promoted_db(pool, "field_mapping", "客流", "restaurant")

    assert result == ("json_value", "promoted")
