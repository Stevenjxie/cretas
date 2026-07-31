"""Phase 2B-3 chat-2B-incentive pilot tests — ``incentive_plan.py``.

Per ``docs/qa-audits/2026-05-12-python-migration-test-coverage-audit.md``
§2.4 row 5 + §3 priority 20 + §5 template + §8.3 row 5
(chat-2B-incentive: ``GET /incentive-plan/{type}/{id}`` path-param boundary).

Coverage:

* **Auth boundary (4 tests)** — JWT missing/invalid/expired + cross-factory
  denial via ``verify_jwt_and_factory`` (smartbi_compat/auth.py).
* **Path-param boundary (5 tests)** — type enum negotiation (salesperson /
  department / region Trap B / unsupported "garbage"), URL-encoded id with
  Chinese chars, very-long id, id-not-found (empty rows → default-target).
* **Happy path (3 tests)** — salesperson with sales rows, department with
  rows, empty-data fallback path that hits default-target ladder.
* **Rule 6 None-check (4 tests)** — all 4 query helpers raise ``ValueError``
  on ``None`` start/end dates (mirror Java NPE-style guard, not silent
  ``BETWEEN NULL AND NULL`` zero-result).
* **Helpers + Rule 4 / 8 / 9 / 10 / 11 / 12 (8+ tests)** —
  - ``_calculate_completion_rate`` Rule 10 intermediate-quantize-4-then-multiply
    (canary ``perf=100 goal=300`` → ``Decimal("33.33")`` HALF_UP, NOT 33.3333),
    plus zero-goal and None-perf guard branches.
  - ``_format_completion_rate_desc`` Trap C ``"120%-%"`` when ``target_to`` is
    None (Java bug mirrored for byte-shape parity).
  - ``_new_incentive_level_dict`` 9-key Lombok @Data declaration order +
    null emit (Rule 8 + Rule 9 — IncentiveLevel.java has no
    @JsonInclude(NON_NULL)).
  - ``_new_incentive_plan_dict`` 16-key shape + computed gapAmount /
    completionRate when both perf+goal supplied (mirrors
    ``IncentivePlan.forSalesperson`` static factory).
  - ``_generate_motivational_message`` Rule 12 boundary canaries
    (rate >= 100 / >= 80 / >= 60 / < 60).
  - ``createdAt`` field uses ``_java_isoformat`` (Rule 11 trailing-zero
    microsecond trim).

Rule checklist per ``.claude/rules/python-java-port.md``:

- [x] **Rule 1** — explicit ``is not None`` on completionRate path in
  ``_update_current_level`` (``Decimal("0")`` is falsy in Python but Java
  treats null-check separately from zero-check).
- [x] **Rule 4** — assert ``_decimal_to_number(Decimal("0"))`` returns
  ``int(0)`` for ``completionRate`` (golden parity per source line 19).
- [x] **Rule 6** — 4 ``ValueError`` tests on ``None`` date params.
- [x] **Rule 8 + Rule 9** — 9-key IncentiveLevel + 16-key IncentivePlan
  Lombok @Data declaration order + null emit (no ``@JsonInclude(NON_NULL)``
  on either DTO).
- [x] **Rule 10** — ``_calculate_completion_rate`` canary
  ``perf=100 / goal=300 → Decimal("33.33")`` (intermediate quantize at
  scale 4 then multiply, NOT naive ``(p/g*100).quantize(0.01)`` which gives
  ``Decimal("33.33")`` by coincidence but diverges at e.g. ``1/300``).
- [x] **Rule 11** — ``_java_isoformat`` used for ``createdAt`` (asserted
  via response-shape ISO 8601 prefix).
- [x] **Rule 12** — ``_generate_motivational_message`` uses
  ``Decimal.quantize(rounding=ROUND_HALF_UP)`` then f-string ``%.1f`` /
  ``%.0f`` on the quantized result (banker's-safe pre-quantize per Rule 12
  正 pattern in python-java-port.md).

Gold standard: ``test_config_thresholds_pilot.py`` (Phase 2C, 41 tests).
Sister: ``test_analysis_drilldown_pilot.py`` (Phase 2B-3, PR #419).
"""
from __future__ import annotations

import os
from datetime import date
from decimal import Decimal

import jwt
import pytest

# ── Test JWT secret (must be set before importing incentive_plan) ──
os.environ.setdefault("JWT_SECRET", "phase-2b3-incentive-pilot-test-secret")

from smartbi_compat.api import incentive_plan as mod  # noqa: E402
from smartbi_compat.api.incentive_plan import (  # noqa: E402
    _DEPARTMENT_DEFAULT_TARGET,
    _DEPARTMENT_LEVELS,
    _SALESPERSON_DEFAULT_TARGET,
    _SALESPERSON_LEVELS,
    _calculate_completion_rate,
    _format_completion_rate_desc,
    _generate_motivational_message,
    _new_incentive_level_dict,
    _new_incentive_plan_dict,
    _to_decimal,
    router,
)
from smartbi_compat.auth import JWT_ALGORITHM  # noqa: E402
from smartbi_compat.date_range import DateRange  # noqa: E402

# Phase 2B endpoint coverage marker (see conftest.py KNOWN_ENDPOINTS).
pytestmark = [pytest.mark.api_endpoint("incentive_plan")]


# ============================================================
# JWT + test client fixtures (mirror test_analysis_drilldown_pilot.py)
# ============================================================


JWT_SECRET = "phase-2b3-incentive-pilot-test-secret"


@pytest.fixture(autouse=True)
def _jwt_env(monkeypatch):
    """Force JWT_SECRET to this file's value for the duration of each test.

    见 test_config_thresholds_pilot.py 同名 fixture 的说明: 顶部 setdefault 只在
    本文件先跑时生效, 否则签名/验签用的是两个不同的 secret → 全部 401。
    2026-07-31 python 门禁首次真跑全量时暴露, 此前被 `pytest ... || true` 吞掉。
    """
    monkeypatch.setenv("JWT_SECRET", JWT_SECRET)
    yield


def _make_token(
    *,
    user_id: int = 22,
    username: str = "alice",
    factory_id: str | None = "F001",
    role: str = "factory_super_admin",
    exp_offset: int = 3600,
) -> str:
    """Build a test JWT with the same shape as production tokens."""
    from time import time

    payload: dict = {
        "userId": user_id,
        "username": username,
        "role": role,
        "exp": int(time()) + exp_offset,
    }
    if factory_id is not None:
        payload["factoryId"] = factory_id
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def _auth_header(token: str | None = None, **token_kwargs) -> dict:
    if token is None:
        token = _make_token(**token_kwargs)
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def client():
    """FastAPI TestClient with the incentive-plan router mounted.

    DB-pool patching is done per-test via ``monkeypatch.setattr`` on the
    ``_query_*`` seam helpers (Section 3 of incentive_plan.py). Tests that
    don't reach a DB call (auth failures, unsupported target_type early-out)
    need no patching.
    """
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    app = FastAPI()
    app.include_router(router)
    return TestClient(app)


# ============================================================
# Auth boundary
# ============================================================


def test_endpoint_missing_bearer_returns_401(client):
    """GET without Authorization header → 401 (verify_jwt_and_factory)."""
    r = client.get(
        "/api/mobile/F001/smart-bi/incentive-plan/salesperson/sp_001",
    )
    assert r.status_code == 401


def test_endpoint_invalid_token_returns_401(client):
    """GET with malformed JWT → 401."""
    r = client.get(
        "/api/mobile/F001/smart-bi/incentive-plan/salesperson/sp_001",
        headers={"Authorization": "Bearer not.a.real.jwt"},
    )
    assert r.status_code == 401


def test_endpoint_expired_token_returns_401(client):
    """Expired JWT → 401 via ExpiredSignatureError path in auth.py."""
    expired = _make_token(exp_offset=-3600)
    r = client.get(
        "/api/mobile/F001/smart-bi/incentive-plan/salesperson/sp_001",
        headers=_auth_header(expired),
    )
    assert r.status_code == 401


def test_endpoint_cross_factory_returns_403(client):
    """Token for F001 hitting F002 URL → 403 (token_factory != factory_id)."""
    r = client.get(
        "/api/mobile/F002/smart-bi/incentive-plan/salesperson/sp_001",
        headers=_auth_header(factory_id="F001"),
    )
    assert r.status_code == 403


# ============================================================
# Path-param boundary
# ============================================================


def test_endpoint_unsupported_target_type_returns_200_success_false(client):
    """Java line 665 parity — unsupported targetType returns
    ``ResponseEntity.ok(ApiResponse.error("Unsupported target type: ..."))``.
    HTTP 200 always (per ``wrap_response(success=False)`` at line 550-554),
    no DB call made — early-out before any monkeypatching needed.
    """
    r = client.get(
        "/api/mobile/F001/smart-bi/incentive-plan/garbage_type/some_id",
        headers=_auth_header(factory_id="F001"),
    )
    # HTTP 200 always (matches Java ResponseEntity.ok parity)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is False, (
        f"Java parity requires success=false on unsupported type, got body={body!r}"
    )
    assert "Unsupported target type" in body["message"], (
        f"Expected 'Unsupported target type' message, got: {body['message']!r}"
    )
    assert "garbage_type" in body["message"], (
        "Message must include the offending type per Java line 665 format"
    )


def test_endpoint_region_falls_through_to_first_salesperson_trap_b(
    client, monkeypatch
):
    """Source line 537-538 / Trap B — ``target_type="region"`` falls through
    to ``_generate_default_plan`` which iterates all sales rows and returns
    a salesperson plan for the FIRST row's salesperson_name. URL targetId
    is silently ignored. This is a Java bug mirrored for byte-shape parity.

    We assert the response is a salesperson plan (not a region plan), and
    that ``targetType`` in the returned plan is "salesperson" (NOT "region")
    because ``_generate_salesperson_plan`` overwrites it.
    """

    async def _fake_default_plan(factory_id, target_type, range_):
        # _generate_default_plan called with target_type="region" per source
        # line 547, then routes through first-salesperson path. Verify the
        # routing arrived here (not an early-return path).
        assert target_type == "region", (
            f"Trap B: endpoint must pass target_type='region' through to "
            f"_generate_default_plan, got {target_type!r}"
        )
        return {
            "id": "plan-region-fallback",
            "targetType": "salesperson",  # overwritten by salesperson plan
            "targetId": "alice",
            "targetName": "alice",
        }

    monkeypatch.setattr(mod, "_generate_default_plan", _fake_default_plan)

    r = client.get(
        "/api/mobile/F001/smart-bi/incentive-plan/region/should_be_ignored",
        headers=_auth_header(factory_id="F001"),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is True
    assert body["data"]["targetType"] == "salesperson", (
        "Trap B — region routes to first-salesperson, plan must reflect that"
    )


def test_endpoint_target_id_with_chinese_chars_routes_through(client, monkeypatch):
    """URL-encoded path param with Chinese chars must decode and pass
    through to the generator unchanged. FastAPI auto-decodes %E5%BC%A0%E4%B8%89
    → "张三" — verify we don't double-decode or break on non-ASCII.
    """

    captured = {}

    async def _fake_salesperson_plan(factory_id, salesperson_id, range_):
        captured["salesperson_id"] = salesperson_id
        return {"id": "plan-zhang-san", "targetId": salesperson_id, "targetType": "salesperson"}

    monkeypatch.setattr(mod, "_generate_salesperson_plan", _fake_salesperson_plan)

    # %E5%BC%A0%E4%B8%89 = "张三" (UTF-8)
    r = client.get(
        "/api/mobile/F001/smart-bi/incentive-plan/salesperson/%E5%BC%A0%E4%B8%89",
        headers=_auth_header(factory_id="F001"),
    )
    assert r.status_code == 200, r.text
    assert captured["salesperson_id"] == "张三", (
        f"FastAPI URL-decoding must pass through Chinese chars, captured={captured!r}"
    )


def test_endpoint_very_long_target_id_routes_through(client, monkeypatch):
    """1000-char target_id must not crash the routing layer (no path-param
    length cap in incentive_plan source). Asserts we don't pre-truncate or
    error before reaching the generator.
    """
    long_id = "x" * 1000

    async def _fake_salesperson_plan(factory_id, salesperson_id, range_):
        return {"targetId": salesperson_id, "targetType": "salesperson"}

    monkeypatch.setattr(mod, "_generate_salesperson_plan", _fake_salesperson_plan)

    r = client.get(
        f"/api/mobile/F001/smart-bi/incentive-plan/salesperson/{long_id}",
        headers=_auth_header(factory_id="F001"),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["data"]["targetId"] == long_id


def test_endpoint_department_with_no_matching_rows_uses_default_target(
    client, monkeypatch
):
    """``_generate_department_plan`` with empty rows → ``perf=0``, ``target=0``
    → falls back to ``_DEPARTMENT_DEFAULT_TARGET = 500000``. Confirms the
    "id not found in data" boundary case routes through to default ladder
    instead of erroring or producing a None target.
    """

    async def _fake_query_department(factory_id, range_, department_id):
        return []  # No rows for this department

    monkeypatch.setattr(mod, "_query_department_data", _fake_query_department)

    r = client.get(
        "/api/mobile/F001/smart-bi/incentive-plan/department/nonexistent_dept",
        headers=_auth_header(factory_id="F001"),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    plan = body["data"]
    assert plan["targetType"] == "department"
    assert plan["targetId"] == "nonexistent_dept"
    # perf=0, target=DEFAULT_DEPARTMENT (500000) → completionRate=0
    assert plan["currentPerformance"] == 0, (
        f"Empty rows must yield perf=0, got {plan['currentPerformance']!r}"
    )
    assert plan["targetGoal"] == int(_DEPARTMENT_DEFAULT_TARGET), (
        f"Empty rows must fall back to _DEPARTMENT_DEFAULT_TARGET={_DEPARTMENT_DEFAULT_TARGET}, "
        f"got {plan['targetGoal']!r}"
    )
    assert plan["completionRate"] == 0, (
        f"perf=0 / goal=500000 must yield completionRate=0, got {plan['completionRate']!r}"
    )


# ============================================================
# Happy path — full plan generation through endpoint
# ============================================================


def test_endpoint_salesperson_happy_path_returns_full_plan(client, monkeypatch):
    """Salesperson with real sales rows → full 16-key plan with computed
    completionRate, gapAmount, levels, currentLevelName, motivationalMessage.
    """

    async def _fake_query_salesperson(factory_id, range_, salesperson_id):
        return [
            {"amount": Decimal("50000"), "monthly_target": Decimal("100000")},
            {"amount": Decimal("30000"), "monthly_target": Decimal("0")},
        ]

    monkeypatch.setattr(mod, "_query_salesperson_sales", _fake_query_salesperson)

    r = client.get(
        "/api/mobile/F001/smart-bi/incentive-plan/salesperson/sp_alice",
        headers=_auth_header(factory_id="F001"),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is True

    plan = body["data"]
    # 16-key shape (Rule 8/9)
    expected_keys = {
        "id", "targetType", "targetId", "targetName",
        "currentPerformance", "targetGoal", "gapAmount", "completionRate",
        "levels", "currentLevelName", "nextLevelName", "gapToNextLevel",
        "motivationalMessage", "estimatedReward", "potentialReward", "createdAt",
    }
    assert set(plan.keys()) == expected_keys, (
        f"Plan key drift — expected {expected_keys}, got {set(plan.keys())}"
    )
    assert plan["targetType"] == "salesperson"
    assert plan["targetId"] == "sp_alice"
    # perf = 50000 + 30000 = 80000; target = 100000 + 0 = 100000
    assert plan["currentPerformance"] == 80000
    assert plan["targetGoal"] == 100000
    # gapAmount = target - perf = 20000
    assert plan["gapAmount"] == 20000
    # completionRate = 80000/100000 * 100 = 80
    assert plan["completionRate"] == 80
    # 4 salesperson levels
    assert len(plan["levels"]) == 4
    # 80% completion → "银牌" level (range 80-100)
    assert plan["currentLevelName"] == "银牌"
    assert plan["nextLevelName"] == "金牌"
    # motivationalMessage populated (rate >= 80 branch)
    assert plan["motivationalMessage"] is not None
    assert "20" in plan["motivationalMessage"]  # "距离目标只差 20000 元"


def test_endpoint_department_happy_path_returns_full_plan(client, monkeypatch):
    """Department with rows → full plan with department ladder (3 levels)."""

    async def _fake_query_department(factory_id, range_, department_id):
        return [
            {"sales_amount": Decimal("400000"), "sales_target": Decimal("500000")},
            {"sales_amount": Decimal("100000"), "sales_target": Decimal("0")},
        ]

    monkeypatch.setattr(mod, "_query_department_data", _fake_query_department)

    r = client.get(
        "/api/mobile/F001/smart-bi/incentive-plan/department/sales_dept",
        headers=_auth_header(factory_id="F001"),
    )
    assert r.status_code == 200, r.text
    plan = r.json()["data"]
    assert plan["targetType"] == "department"
    assert plan["targetId"] == "sales_dept"
    # perf = 500000, target = 500000 → 100% completion
    assert plan["currentPerformance"] == 500000
    assert plan["targetGoal"] == 500000
    assert plan["completionRate"] == 100
    # 3 department levels
    assert len(plan["levels"]) == 3
    # 100% completion → "优秀" (range 100-120) per Java isInRange semantic
    # ``below_to = rate < to`` → rate=100 < to=120, ``above_from = rate >= 100``
    assert plan["currentLevelName"] == "优秀"


def test_endpoint_salesperson_empty_rows_uses_default_target(client, monkeypatch):
    """Salesperson with NO sales rows → perf=0, target falls back to
    ``_SALESPERSON_DEFAULT_TARGET = 100000``. Confirms the empty-rows fallback
    sums to ``Decimal("0")`` (Rule 1 — ``Decimal("0")`` is falsy in Python,
    but the ``target == 0`` check uses ``==`` not Python truthiness, so the
    fallback fires correctly).
    """

    async def _fake_query_salesperson(factory_id, range_, salesperson_id):
        return []

    monkeypatch.setattr(mod, "_query_salesperson_sales", _fake_query_salesperson)

    r = client.get(
        "/api/mobile/F001/smart-bi/incentive-plan/salesperson/ghost_sp",
        headers=_auth_header(factory_id="F001"),
    )
    assert r.status_code == 200, r.text
    plan = r.json()["data"]
    assert plan["currentPerformance"] == 0
    assert plan["targetGoal"] == int(_SALESPERSON_DEFAULT_TARGET), (
        f"Empty rows must fall back to default {_SALESPERSON_DEFAULT_TARGET}"
    )
    assert plan["completionRate"] == 0


# ============================================================
# Rule 6 — None-check on date params (all 4 query helpers)
# ============================================================


@pytest.mark.asyncio
async def test_rule6_query_salesperson_sales_rejects_none_start():
    """Rule 6 — ``_query_salesperson_sales`` must raise ``ValueError`` on
    None ``start_date`` to prevent silent ``BETWEEN NULL AND NULL`` zero-result.
    """
    bad_range = DateRange(start_date=None, end_date=date(2026, 5, 31))
    with pytest.raises(ValueError, match="start/end required"):
        await mod._query_salesperson_sales("F001", bad_range, "sp_001")


@pytest.mark.asyncio
async def test_rule6_query_department_data_rejects_none_end():
    """Rule 6 — ``_query_department_data`` must raise ``ValueError`` on
    None ``end_date``.
    """
    bad_range = DateRange(start_date=date(2026, 5, 1), end_date=None)
    with pytest.raises(ValueError, match="start/end required"):
        await mod._query_department_data("F001", bad_range, "dept_001")


@pytest.mark.asyncio
async def test_rule6_query_all_sales_rows_rejects_none_start():
    """Rule 6 — ``_query_all_sales_rows`` (default-fallback path) must raise
    ``ValueError`` on None dates.
    """
    bad_range = DateRange(start_date=None, end_date=None)
    with pytest.raises(ValueError, match="start/end required"):
        await mod._query_all_sales_rows("F001", bad_range)


@pytest.mark.asyncio
async def test_rule6_query_all_department_rows_rejects_none_dates():
    """Rule 6 — ``_query_all_department_rows`` must raise ``ValueError``."""
    bad_range = DateRange(start_date=None, end_date=date(2026, 5, 31))
    with pytest.raises(ValueError, match="start/end required"):
        await mod._query_all_department_rows("F001", bad_range)


# ============================================================
# Rule 10 — _calculate_completion_rate intermediate-quantize-then-multiply
# ============================================================


def test_rule10_calculate_completion_rate_intermediate_quantize_at_scale_4():
    """Rule 10 lock-down — Java:

        BigDecimal.divide(divisor, 4, HALF_UP).multiply(100)

    means: divide quantize-at-scale-4 → 0.3333 → multiply by 100 → 33.33
    (scale 4+0=4 by BigDecimal multiply semantics, but display via
    _decimal_to_number int-collapse / float conversion).

    Naive Python ``(perf/goal*Decimal("100")).quantize(Decimal("0.01"))``
    differs because Python Decimal arithmetic preserves 28-digit precision
    until quantize. Source quantizes BEFORE multiply at scale 4 to match.

    Canary: ``perf=100, goal=300``:
      - HALF_UP intermediate quantize: ``Decimal("0.3333") * 100 = Decimal("33.3300")``
      - naive: ``100/300 = 0.333333...3 * 100 = 33.333...3``, quantize-after
        at scale 2 → 33.33 (coincidence) but at scale 4 → 33.3333.
    """
    result = _calculate_completion_rate(Decimal("100"), Decimal("300"))
    assert result == Decimal("33.3300"), (
        f"Rule 10 — expected Decimal('33.3300') from divide(4,HALF_UP).multiply(100), "
        f"got {result!r}. If this is 33.3333, source dropped intermediate quantize at scale 4."
    )


def test_rule10_calculate_completion_rate_division_by_zero_returns_zero():
    """``goal=0`` (or negative) → returns ``Decimal("0")`` per Java line 174
    null-or-zero guard. Critical for "no monthly_target rows" empty fallback.
    """
    assert _calculate_completion_rate(Decimal("100"), Decimal("0")) == Decimal("0")
    assert _calculate_completion_rate(Decimal("100"), Decimal("-50")) == Decimal("0")


def test_rule10_calculate_completion_rate_none_perf_returns_zero():
    """``perf=None`` → returns ``Decimal("0")``. Python ``Decimal("0")`` is
    falsy but Java guard checks ``perf != null`` separately. Rule 1 adherence.
    """
    assert _calculate_completion_rate(None, Decimal("100")) == Decimal("0")


# ============================================================
# _format_completion_rate_desc — Trap C ("120%-%")
# ============================================================


def test_format_completion_rate_desc_with_to_value():
    """Java ``String.format("完成率达到 %.0f%%-%.0f%%", from, to)`` truncates
    integer thresholds — for ``Decimal("60")``, ``%.0f`` → ``"60"``.
    """
    assert (
        _format_completion_rate_desc(Decimal("60"), Decimal("80"))
        == "完成率达到 60%-80%"
    )


def test_format_completion_rate_desc_trap_c_none_to():
    """Trap C lock-down (spec §5) — Java produces ``"完成率达到 120%-%"``
    when ``targetTo`` is null because ``%.0f`` on a null BigDecimal degrades.
    Python mirrors via empty string. This is a Java bug we MUST preserve
    for byte-shape parity (top-level ladder "钻石" / "卓越" have target_to=None).
    """
    assert (
        _format_completion_rate_desc(Decimal("120"), None)
        == "完成率达到 120%-%"
    )


# ============================================================
# _new_incentive_level_dict — 9-key shape (Rule 8 + Rule 9)
# ============================================================


def test_new_incentive_level_dict_emits_9_fields_in_lombok_order():
    """Rule 8 + Rule 9 — IncentiveLevel 9-key Lombok @Data declaration order
    + null emit (IncentiveLevel DTO has no ``@JsonInclude(NON_NULL)``).
    """
    level = _new_incentive_level_dict(
        "银牌", Decimal("80"), Decimal("100"), Decimal("1000")
    )
    expected_keys = [
        "levelName", "description", "targetFrom", "targetTo", "rewardAmount",
        "rewardRate", "current", "achieved", "gap",
    ]
    assert list(level.keys()) == expected_keys, (
        f"IncentiveLevel key order drift — expected {expected_keys}, "
        f"got {list(level.keys())}. Per Rule 8 + source line 105-115, "
        f"order mirrors IncentiveLevel.java Lombok @Data declaration."
    )

    # Rule 9 — nulls MUST emit (no @JsonInclude(NON_NULL))
    assert level["rewardRate"] is None, "Rule 9 — null field must emit"
    assert level["gap"] is None, "Rule 9 — null field must emit"

    # Default values
    assert level["current"] is False
    assert level["achieved"] is False
    assert level["levelName"] == "银牌"
    # Rule 4 — _decimal_to_number(Decimal("80")) → int(80)
    assert level["targetFrom"] == 80
    assert level["rewardAmount"] == 1000


def test_new_incentive_level_dict_target_to_none_for_top_tier():
    """Top-tier levels (钻石 / 卓越) have ``target_to=None``. Verify the
    dict carries ``targetTo: None`` (not 0, not missing).
    """
    level = _new_incentive_level_dict(
        "钻石", Decimal("120"), None, Decimal("5000")
    )
    assert level["targetTo"] is None
    assert level["description"] == "完成率达到 120%-%"  # Trap C


# ============================================================
# _new_incentive_plan_dict — 16-key shape + computed fields
# ============================================================


def test_new_incentive_plan_dict_emits_16_fields_in_lombok_order():
    """Rule 8 + Rule 9 — IncentivePlan 16-key Lombok @Data declaration order
    + null emit. IncentivePlan DTO has no ``@JsonInclude(NON_NULL)``.
    """
    plan = _new_incentive_plan_dict(target_type="salesperson")
    expected_keys = [
        "id", "targetType", "targetId", "targetName",
        "currentPerformance", "targetGoal", "gapAmount", "completionRate",
        "levels", "currentLevelName", "nextLevelName", "gapToNextLevel",
        "motivationalMessage", "estimatedReward", "potentialReward", "createdAt",
    ]
    assert list(plan.keys()) == expected_keys, (
        f"IncentivePlan key order drift — expected {expected_keys}, "
        f"got {list(plan.keys())}. Per Rule 8 + source line 137-160, "
        f"order mirrors IncentivePlan.java Lombok @Data declaration."
    )


def test_new_incentive_plan_dict_computes_gap_and_completion_when_both_given():
    """Source line 161-167 — when both ``current_performance`` and
    ``target_goal`` are not None, mirror Java
    ``IncentivePlan.forSalesperson`` factory which calls
    ``calculateGapAmount`` and ``calculateCompletionRate`` after build.
    """
    plan = _new_incentive_plan_dict(
        target_type="salesperson",
        current_performance=Decimal("75000"),
        target_goal=Decimal("100000"),
    )
    # gapAmount = 100000 - 75000 = 25000
    assert plan["gapAmount"] == 25000
    # completionRate = 75000/100000 * 100 = 75 (Rule 10: 0.7500 * 100 = 75.0000)
    # _decimal_to_number(Decimal("75.0000")) → int(75) per Rule 4 int-collapse
    assert plan["completionRate"] == 75


def test_new_incentive_plan_dict_skips_computed_when_either_missing():
    """When ``target_goal`` is None (e.g., empty-data fallback path
    line 512), ``gapAmount`` and ``completionRate`` MUST stay None,
    not coerced to 0 or computed from a Decimal("0") sentinel.
    """
    plan = _new_incentive_plan_dict(target_type="region")
    assert plan["gapAmount"] is None
    assert plan["completionRate"] is None
    assert plan["currentPerformance"] is None
    assert plan["targetGoal"] is None
    # 16 keys still present (Rule 9 — null emit)
    assert len(plan) == 16


def test_rule11_createdat_uses_java_isoformat():
    """Rule 11 — ``createdAt`` uses ``_java_isoformat(datetime.now())`` so
    trailing-zero microseconds are trimmed to match Java Jackson
    ``LocalDateTime`` serialization. We can't assert exact value (volatile)
    but we can assert (a) ISO 8601 prefix, (b) no trailing zero in fraction.
    """
    plan = _new_incentive_plan_dict(target_type="salesperson")
    created_at = plan["createdAt"]
    assert isinstance(created_at, str)
    # ISO 8601 starts with year
    assert created_at.startswith("20"), f"Not ISO 8601: {created_at!r}"
    # Either no dot (whole-second) or non-zero-terminated fraction (Rule 11)
    if "." in created_at:
        _head, frac = created_at.rsplit(".", 1)
        assert not frac.endswith("0"), (
            f"Rule 11 — _java_isoformat must trim trailing-zero microseconds, "
            f"got fractional part {frac!r} in {created_at!r}"
        )


# ============================================================
# _generate_motivational_message — Rule 12 boundary canaries
# ============================================================


@pytest.mark.parametrize(
    "rate,expected_phrase",
    [
        # rate >= 100 → "太棒了！... 已完成目标"
        (Decimal("100"), "太棒了"),
        (Decimal("150.5"), "太棒了"),
        # 80 <= rate < 100 → "距离目标只差 ... 加把劲"
        (Decimal("80"), "距离目标只差"),
        (Decimal("99.99"), "距离目标只差"),
        # 60 <= rate < 80 → "... 已完成 ... 下一个等级的奖励"
        (Decimal("60"), "下一个等级"),
        (Decimal("79.99"), "下一个等级"),
        # rate < 60 → "需要加速冲刺"
        (Decimal("0"), "需要加速冲刺"),
        (Decimal("59.99"), "需要加速冲刺"),
    ],
)
def test_generate_motivational_message_boundary_branches(rate, expected_phrase):
    """Rule 12 boundary lock-down for 4-branch threshold (100 / 80 / 60).

    Off-by-one here would silently mis-classify near-threshold messages.
    Boundary canaries: 100 (top→excellence edge), 80 (excellence→encourage),
    60 (encourage→urgency).
    """
    plan = {
        "completionRate": int(rate) if rate == rate.to_integral_value() else float(rate),
        "targetName": "alice",
        "gapAmount": 5000,
    }
    _generate_motivational_message(plan)
    assert expected_phrase in plan["motivationalMessage"], (
        f"Rule 12 boundary — rate={rate!r} expected branch phrase "
        f"{expected_phrase!r}, got message={plan['motivationalMessage']!r}"
    )


def test_generate_motivational_message_none_rate_uses_fallback():
    """``completionRate=None`` → generic "继续努力，您一定可以的！" fallback
    (source line 250-252). Critical because empty-data path returns plan
    with ``completionRate=None``.
    """
    plan = {
        "completionRate": None,
        "targetName": "alice",
        "gapAmount": None,
    }
    _generate_motivational_message(plan)
    assert plan["motivationalMessage"] == "继续努力，您一定可以的！"


def test_rule12_motivational_message_half_up_rate_quantize():
    """Rule 12 — ``rate.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)``
    must use HALF_UP, not banker's. Canary: ``Decimal("99.95")``:

      - HALF_UP:    Decimal("100.0") → message ".0%" branch (rate >= 100? NO,
        because we compare RAW rate >= 100, not quantized. Raw 99.95 < 100,
        so we hit ``rate >= 80`` branch with gap_q.)
      - banker's:   Decimal("100.0") → same display

    Better canary: ``Decimal("85.55")`` → ``.1f`` HALF_UP → "85.6",
    banker's (Decimal default) → "85.6" (because 5 preceded by even 5? No,
    actually banker's rounds 85.55→85.6 because 5 is odd. Let me use 85.45:
    HALF_UP → "85.5", banker's → "85.4" (4 even, banker's keeps).
    """
    plan = {
        "completionRate": float(Decimal("85.45")),  # raw 85.45 < 100, >= 80
        "targetName": "alice",
        "gapAmount": 5000,
    }
    _generate_motivational_message(plan)
    # rate >= 80 branch uses gap_q (not rate_q) — rate_q is only used in
    # rate >= 100 and rate >= 60 / < 60 branches. So 85.45 hits the gap
    # branch without exercising rate_q quantize. Switch canary to <80.
    # rate=Decimal("75.45") → 60-80 branch with rate_q displayed.
    plan2 = {
        "completionRate": float(Decimal("75.45")),
        "targetName": "alice",
        "gapAmount": 0,
    }
    _generate_motivational_message(plan2)
    # HALF_UP: 75.45 → 75.5 (5 rounds up always)
    # banker's: 75.45 → 75.4 (4 even, banker's rounds to even)
    assert "75.5" in plan2["motivationalMessage"], (
        f"Rule 12 — _generate_motivational_message must use ROUND_HALF_UP, "
        f"got {plan2['motivationalMessage']!r}. If '75.4' appears, "
        f"rate.quantize fell back to banker's default."
    )


# ============================================================
# Module exports + router contract
# ============================================================


def test_module_advertises_router_and_helpers():
    """Stable exports for chat-cleanup / chat-cutover audits."""
    assert hasattr(mod, "router")
    assert hasattr(mod, "get_incentive_plan")
    assert hasattr(mod, "_calculate_completion_rate")
    assert hasattr(mod, "_format_completion_rate_desc")
    assert hasattr(mod, "_new_incentive_level_dict")
    assert hasattr(mod, "_new_incentive_plan_dict")
    assert hasattr(mod, "_generate_motivational_message")
    assert hasattr(mod, "_generate_salesperson_plan")
    assert hasattr(mod, "_generate_department_plan")
    assert hasattr(mod, "_generate_default_plan")


def test_router_declares_single_get_endpoint():
    """Spec §5 — single GET route at the canonical Java-compatible alias."""
    paths_methods = {(r.path, frozenset(r.methods)) for r in router.routes}
    assert (
        "/api/mobile/{factory_id}/smart-bi/incentive-plan/{target_type}/{target_id}",
        frozenset({"GET"}),
    ) in paths_methods


def test_constants_match_java_ladders():
    """Lock-down for hard-coded ladders (source line 48-58).

    Drift here means a sister chat changed the ladder thresholds / rewards
    without updating Java reference — byte-shape divergence at every call.
    """
    # Salesperson 4-tier: 铜/银/金/钻
    assert len(_SALESPERSON_LEVELS) == 4
    assert _SALESPERSON_LEVELS[0] == ("铜牌", Decimal("60"), Decimal("80"), Decimal("500"))
    assert _SALESPERSON_LEVELS[3] == ("钻石", Decimal("120"), None, Decimal("5000"))

    # Department 3-tier: 达标/优秀/卓越
    assert len(_DEPARTMENT_LEVELS) == 3
    assert _DEPARTMENT_LEVELS[0] == ("达标", Decimal("80"), Decimal("100"), Decimal("5000"))
    assert _DEPARTMENT_LEVELS[2] == ("卓越", Decimal("120"), None, Decimal("20000"))

    # Default targets (Java line 738 / 773)
    assert _SALESPERSON_DEFAULT_TARGET == Decimal("100000")
    assert _DEPARTMENT_DEFAULT_TARGET == Decimal("500000")


# ============================================================
# _to_decimal helper
# ============================================================


def test_to_decimal_handles_none():
    """Source line 66-77 — None-safe Decimal cast, returns Decimal("0")
    per Java null-handling in calculateCompletionRate / sumField.
    """
    assert _to_decimal(None) == Decimal("0")


def test_to_decimal_passes_decimal_through():
    assert _to_decimal(Decimal("123.45")) == Decimal("123.45")


def test_to_decimal_converts_string_and_int():
    assert _to_decimal("100") == Decimal("100")
    assert _to_decimal(42) == Decimal("42")


def test_to_decimal_returns_zero_on_garbage():
    """Defensive — non-castable input → Decimal("0") (mirror Java null path)."""
    assert _to_decimal("not_a_number") == Decimal("0")
    assert _to_decimal(object()) == Decimal("0")
