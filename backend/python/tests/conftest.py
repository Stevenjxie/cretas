"""
SmartBI E2E Test Fixtures

Provides:
- httpx async client for API testing
- Excel ground truth data from Test.xlsx
- PostgreSQL connection for data accuracy tests
"""
import os
import sys
from pathlib import Path

import pytest
import httpx

# pandas is imported lazily inside excel_ground_truth fixture below.
# Reason: some local dev envs (anaconda) have broken numpy installs that
# crash on `import pandas`; making it lazy lets unit tests that don't need
# Excel parsing run without triggering the dependency chain.

# ── Paths ──────────────────────────────────────────────────────
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent  # my-prototype-logistics/
PYTHON_ROOT = PROJECT_ROOT / "backend" / "python"
EXCEL_PATH = PROJECT_ROOT / "Test.xlsx"

# Add python backend to sys.path so imports work
sys.path.insert(0, str(PYTHON_ROOT))
sys.path.insert(0, str(PYTHON_ROOT / "smartbi"))

# ── Config ─────────────────────────────────────────────────────
PYTHON_BASE = os.getenv("SMARTBI_PYTHON_URL", "http://localhost:8083")
JAVA_BASE = os.getenv("SMARTBI_JAVA_URL", "http://localhost:10010")
PG_DSN = os.getenv(
    "SMARTBI_PG_DSN",
    "postgresql://smartbi_user:smartbi_pass@localhost:5432/smartbi_db",
)


# ── httpx Client Fixtures ──────────────────────────────────────
@pytest.fixture(scope="session")
def base_url():
    return PYTHON_BASE


@pytest.fixture(scope="session")
def java_url():
    return JAVA_BASE


@pytest.fixture
async def client(base_url):
    """Async httpx client pointed at the Python service."""
    async with httpx.AsyncClient(base_url=base_url, timeout=30.0) as c:
        yield c


@pytest.fixture
async def java_client(java_url):
    """Async httpx client pointed at the Java backend."""
    async with httpx.AsyncClient(base_url=java_url, timeout=30.0) as c:
        yield c


@pytest.fixture(scope="session")
def sync_client(base_url):
    """Synchronous httpx client for non-async tests."""
    with httpx.Client(base_url=base_url, timeout=30.0) as c:
        yield c


# ── Excel Ground Truth ─────────────────────────────────────────
@pytest.fixture(scope="session")
def excel_ground_truth():
    """
    Read Test.xlsx and produce per-sheet ground truth:
      {
        "SheetName": {
          "row_count": int,
          "col_count": int,
          "columns": [...],
          "numeric_sums": {"col": sum_float, ...},
          "sample_rows": [{...}, ...],   # first 3 rows
        }
      }
    """
    if not EXCEL_PATH.exists():
        pytest.skip(f"Test.xlsx not found at {EXCEL_PATH}")

    import pandas as pd  # lazy: only needed when this fixture runs

    xls = pd.ExcelFile(EXCEL_PATH)
    ground_truth = {}

    for sheet in xls.sheet_names:
        df = pd.read_excel(xls, sheet_name=sheet)
        numeric_cols = df.select_dtypes(include="number").columns
        ground_truth[sheet] = {
            "row_count": len(df),
            "col_count": len(df.columns),
            "columns": list(df.columns),
            "numeric_sums": {
                c: float(df[c].sum()) for c in numeric_cols
            },
            "sample_rows": df.head(3).to_dict("records"),
        }

    return ground_truth


# ── PostgreSQL Engine (lazy) ───────────────────────────────────
@pytest.fixture(scope="session")
def pg_engine():
    """SQLAlchemy engine for smartbi_db, skips if unavailable."""
    try:
        from sqlalchemy import create_engine, text

        engine = create_engine(PG_DSN, pool_pre_ping=True)
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        return engine
    except Exception as exc:
        pytest.skip(f"PostgreSQL unavailable: {exc}")


# ── Sample Data Fixtures ───────────────────────────────────────
@pytest.fixture
def sample_bar_data():
    """Sample bar chart data with an outlier for anomaly detection."""
    return [
        {"category": "A", "value": 100},
        {"category": "B", "value": 120},
        {"category": "C", "value": 110},
        {"category": "D", "value": 130},
        {"category": "E", "value": 115},
        {"category": "F", "value": 125},
        {"category": "G", "value": 9999},  # outlier
    ]


@pytest.fixture
def sample_line_data():
    """Sample time series data for line chart + forecast."""
    return [
        {"month": "2024-01", "revenue": 1000},
        {"month": "2024-02", "revenue": 1100},
        {"month": "2024-03", "revenue": 1050},
        {"month": "2024-04", "revenue": 1200},
        {"month": "2024-05", "revenue": 1300},
        {"month": "2024-06", "revenue": 1250},
        {"month": "2024-07", "revenue": 1400},
        {"month": "2024-08", "revenue": 1500},
        {"month": "2024-09", "revenue": 1450},
        {"month": "2024-10", "revenue": 1600},
    ]


# ── Provenance env-flag cache ──────────────────────────────────
@pytest.fixture(autouse=True)
def _reset_provenance_env_cache():
    """Clear ``SMARTBI_ENABLE_PROVENANCE`` cache before AND after each test.

    :func:`smartbi.canonical.provenance._writer_hook.is_provenance_enabled` is
    ``@functools.cache``-wrapped (I4 fix) so the first call's value sticks
    for the lifetime of the process. That collides with ``monkeypatch.setenv``
    in tests: a test that flips the env to "1" leaves the cache stuck at
    True for every later test that calls :func:`is_provenance_enabled`,
    which surprises tests asserting the hook is NOT called when the env is
    unset.

    Importing the helper is wrapped in ``try/except ImportError`` so this
    fixture is harmless for any test environment that doesn't have the
    canonical provenance module installed (e.g. lightweight tests that
    don't touch the smartbi package).
    """
    try:
        from smartbi.canonical.provenance._writer_hook import (
            invalidate_provenance_flag_cache,
        )
    except ImportError:
        yield
        return
    invalidate_provenance_flag_cache()
    yield
    invalidate_provenance_flag_cache()


@pytest.fixture
def sample_profit_data():
    """Sample profit data for insight generation."""
    return [
        {"产品": "产品A", "营业收入": 5000, "成本": 3000, "利润": 2000},
        {"产品": "产品B", "营业收入": 8000, "成本": 5500, "利润": 2500},
        {"产品": "产品C", "营业收入": 3000, "成本": 2800, "利润": 200},
        {"产品": "产品D", "营业收入": 12000, "成本": 7000, "利润": 5000},
        {"产品": "产品E", "营业收入": 6000, "成本": 4500, "利润": 1500},
    ]


@pytest.fixture
def sample_quick_summary_data():
    """3-row data for quick-summary sum verification."""
    return [
        {"name": "Alice", "revenue": 1000, "cost": 500},
        {"name": "Bob", "revenue": 1500, "cost": 700},
        {"name": "Carol", "revenue": 2000, "cost": 900},
    ]


# ─────────────────────────────────────────────────────────────────────
# 数据织网 Sub-Project C — shared real-PG pool fixture (P2-7).
#
# Previously duplicated bit-for-bit across 3 test files
# (test_top_products_provenance.py, test_provenance_audit.py,
# test_factory_provenance_config.py). Each `clean_rows` fixture
# stays per-file because the table sets to wipe differ; this one
# centralizes the pool acquisition + skip-when-no-PG behavior.
# ─────────────────────────────────────────────────────────────────────
import pytest_asyncio  # noqa: E402


# ─────────────────────────────────────────────────────────────────────
# 数据织网 Sub-Project C — pytest_asyncio singleton-pool reset (post-test
# audit gate finding).
#
# `smartbi.config._pg_pool` is a module-level asyncpg.Pool singleton. The
# first real-PG test creates it bound to that test's event loop. pytest-
# asyncio creates a fresh event loop for each test (default
# function-scope), so test 2 hits `RuntimeError: Event loop is closed`
# when `get_cell_audit` → `get_pg_pool()` → pool._acquire() inside the
# now-closed loop.
#
# Fix: autouse fixture nulls the singleton before AND after each test.
# Production behavior (web service) is unchanged because that env has a
# single long-running event loop; the singleton works fine there.
# ─────────────────────────────────────────────────────────────────────
@pytest_asyncio.fixture(autouse=True)
async def _reset_smartbi_pg_pool_singleton():
    """Null the module-level asyncpg pool singleton between tests so each
    test gets a pool bound to its own event loop.
    """
    try:
        from smartbi import config as _smartbi_config
    except ImportError:
        yield
        return
    # before
    prev = getattr(_smartbi_config, "_pg_pool", None)
    if prev is not None and not prev._closed:
        try:
            await prev.close()
        except Exception:
            pass
    _smartbi_config._pg_pool = None
    yield
    # after
    cur = getattr(_smartbi_config, "_pg_pool", None)
    if cur is not None and not cur._closed:
        try:
            await cur.close()
        except Exception:
            pass
    _smartbi_config._pg_pool = None


@pytest_asyncio.fixture
async def c_provenance_pool():
    """asyncpg.Pool with the tenant-aware connection setup hook.

    Skips the test cleanly when ``settings.postgres_url`` is unset
    (matches the Sub-Project C real-PG canon: tunneled smartbi_db
    on port 5433 has the C migrations applied; local dev DB usually
    doesn't, hence skip-not-fail when no DSN configured).
    """
    import asyncpg
    from smartbi.config import get_settings
    from smartbi.tenant_ctx import set_pg_connection_tenant

    settings = get_settings()
    if not settings.postgres_url:
        pytest.skip("No Postgres configured")
    p = await asyncpg.create_pool(
        settings.postgres_url,
        min_size=1,
        max_size=3,
        setup=set_pg_connection_tenant,
    )
    try:
        yield p
    finally:
        await p.close()


# ═════════════════════════════════════════════════════════════════════
# Phase 2B-final — semantic endpoint coverage gate (Option B).
#
# Spec: docs/qa-audits/2026-05-12-python-migration-test-coverage-audit.md §6
#
# Every migrated endpoint in ``backend/python/smartbi_compat/api/`` must
# have ≥3 tests bearing ``@pytest.mark.api_endpoint("<name>")`` (happy +
# boundary + error per the §5 backfill template). Tag a test file by
# adding to its top, after the imports::
#
#     pytestmark = [pytest.mark.api_endpoint("analysis_inventory")]
#
# Multi-endpoint files list each marker explicitly so the gate counts
# every endpoint independently::
#
#     pytestmark = [
#         pytest.mark.api_endpoint("datasource_upload"),
#         pytest.mark.api_endpoint("datasource_preview"),
#         ...
#     ]
#
# Activation: the gate is **OFF by default** (Phase 2B backfill still in
# flight). Activate in a follow-up PR by setting the env var in the CI
# workflow once all 13 backfill chats have shipped::
#
#     env:
#       CRETAS_TEST_ENDPOINT_GATE: "1"
#
# Local one-off check: ``CRETAS_TEST_ENDPOINT_GATE=1 pytest tests/``.
# ═════════════════════════════════════════════════════════════════════
import collections  # noqa: E402

# Canonical 34 migrated endpoints — keep this list in sync with
# ``@router.<verb>(...)`` decorators in ``smartbi_compat/api/*.py``.
KNOWN_ENDPOINTS = frozenset({
    # analysis.py — 4 read endpoints
    "analysis_query_templates_list",
    "analysis_datasource_list",
    "analysis_alerts",
    "analysis_recommendations",
    # analysis_finance.py — composite + 3 sub-paths
    "analysis_finance",
    "analysis_finance_budget_achievement",
    "analysis_finance_yoy_mom",
    "analysis_finance_category_comparison",
    # Single-endpoint analysis modules
    "analysis_department",
    "analysis_drilldown",
    "analysis_inventory",
    "analysis_procurement",
    "analysis_production",
    "analysis_quality",
    "analysis_region",
    "analysis_sales",
    # config_thresholds.py — CRUD + reload
    "config_thresholds_list",
    "config_thresholds_create",
    "config_thresholds_update",
    "config_thresholds_delete",
    "config_thresholds_reload",
    # dashboard.py — single endpoint
    "dashboard_data_date_range",
    # dashboard_composite.py — 3 endpoints
    "dashboard_composite_executive",
    "dashboard_composite_executive_custom",
    "dashboard_composite_main",
    # datasource.py — 5 endpoints
    "datasource_fields",
    "datasource_history",
    "datasource_upload",
    "datasource_preview",
    "datasource_apply",
    # incentive_plan.py — single endpoint
    "incentive_plan",
    # query_templates_write.py — POST/PUT/DELETE
    "query_templates_create",
    "query_templates_update",
    "query_templates_delete",
    # cross-cutting sweep tests (not single endpoints)
    "r6_rbac_strip_sister_sweep",
})
assert len(KNOWN_ENDPOINTS) == 35, (
    f"KNOWN_ENDPOINTS size {len(KNOWN_ENDPOINTS)}, expected 35 — "
    "update audit §1 + this set together"
)

_ENDPOINT_GATE_ENV = "CRETAS_TEST_ENDPOINT_GATE"
_MIN_TESTS_PER_ENDPOINT = 3


def pytest_collection_modifyitems(config, items):
    """Endpoint coverage gate (Option B).

    Counts ``api_endpoint`` markers across collected test items; when the
    env var is set to ``"1"`` and any endpoint in ``KNOWN_ENDPOINTS`` has
    fewer than ``_MIN_TESTS_PER_ENDPOINT`` tests, fail collection. Also
    reports markers that don't appear in ``KNOWN_ENDPOINTS`` (typo
    catcher).
    """
    if os.environ.get(_ENDPOINT_GATE_ENV) != "1":
        return

    counts: collections.Counter = collections.Counter()
    for item in items:
        for marker in item.iter_markers(name="api_endpoint"):
            if not marker.args:
                continue
            counts[marker.args[0]] += 1

    missing = sorted(
        e for e in KNOWN_ENDPOINTS if counts.get(e, 0) < _MIN_TESTS_PER_ENDPOINT
    )
    unknown = sorted(set(counts) - KNOWN_ENDPOINTS)

    parts = []
    if missing:
        parts.append(
            f"{len(missing)} endpoint(s) have <{_MIN_TESTS_PER_ENDPOINT} tests: "
            f"{missing}"
        )
    if unknown:
        parts.append(
            f"{len(unknown)} unknown endpoint marker(s) (typo or "
            f"missing from KNOWN_ENDPOINTS in conftest.py): {unknown}"
        )
    if parts:
        pytest.exit(
            "Phase 2B endpoint coverage gate FAILED — " + "; ".join(parts),
            returncode=1,
        )


# ═══════════════════════════════════════════════════════════════════════════
# PostgreSQL 可达性闸 (2026-08-10)
# ═══════════════════════════════════════════════════════════════════════════
#
# 21 个用例里写着:
#     if not settings.postgres_url:
#         pytest.skip("No Postgres configured")
#
# 而 `Settings.postgres_url` 是**拼出来的字符串**(f"postgresql://{user}:{pw}@
# {host}:{port}/{db}"), 各字段都有默认值 → 它**永远非空**。那个守卫是结构性的
# 死代码, 从来没有生效过一次。于是没有 PG 的环境里, 这些用例不是「优雅跳过」而是
# 报 `OSError: Connect call failed 127.0.0.1:5432` —— 149 个 error, 并因此被整体
# 挂进 ci-gate-excludes.txt, 一挂几个月。
#
# 判据: **写「没配就跳过」的守卫时, 要确认「没配」这个状态真的表达得出来。**
#       一个恒为真的表达式后面跟着 skip, 和没写守卫是一回事。
#
# 这里不改 `postgres_url` 的生产语义(它对线上是对的), 只在**测试会话开始时探一次
# 可达性**: 连不上就把它置空, 让那 21 个守卫按作者本来的意图生效。
# ⛔ 这不增加任何覆盖 —— 它只是把「伪装成错误的缺席」变成「如实报告的跳过」。
#    要真正跑这些用例, 得给 CI 加 postgres service container + 应用迁移, 那是
#    另一件事(见 ci-gate-excludes.txt ① 组)。
def _pg_reachable(dsn: str, timeout: float = 2.0) -> bool:
    """能不能真的**连上并查一句**, 而不是「端口上有东西在监听」。

    ⚠️ 第一版这里是 socket.create_connection((host, port)) —— TCP 握手成功就算
       可达。本机实测: 那个端口上确实有东西 accept, 但它不是一个能用的 PostgreSQL,
       握手后立刻断 → 用例拿到的是 `ConnectionResetError: [WinError 64]` 而不是
       「连接被拒绝」, 而我的探针早就判定「可达」并放行了。
       判据: **判别式要验「只有真东西才有的特征」, 不能验代理指标。**
       端口开着 ≠ 服务是它; 能跑 `SELECT 1` 才算数。
    """
    import asyncio

    async def _probe() -> bool:
        try:
            import asyncpg
        except Exception:
            return False
        conn = None
        try:
            conn = await asyncio.wait_for(asyncpg.connect(dsn), timeout=timeout)
            await conn.fetchval("SELECT 1")
            return True
        except Exception:
            return False
        finally:
            if conn is not None:
                try:
                    await conn.close()
                except Exception:
                    pass

    try:
        return asyncio.run(_probe())
    except Exception:
        return False


@pytest.fixture(scope="session", autouse=True)
def _postgres_reachability_gate():
    import pytest as _pytest

    try:
        from smartbi.config import Settings, get_settings
    except Exception:                      # 纯离线子集, 没有 config 也不该炸
        yield
        return

    s = get_settings()
    if _pg_reachable(s.postgres_url):
        yield                              # 有库 → 什么都不做, 用例照常真跑
        return

    mp = _pytest.MonkeyPatch()
    mp.setattr(Settings, "postgres_url", property(lambda self: ""), raising=False)
    mp.setattr(Settings, "postgres_async_url", property(lambda self: ""), raising=False)
    print(f"\n[conftest] PostgreSQL {s.postgres_host}:{s.postgres_port} 连不上 "
          f"(SELECT 1 失败) —— 依赖真库的用例将按其自带守卫跳过(不是失败)")
    try:
        yield
    finally:
        mp.undo()
