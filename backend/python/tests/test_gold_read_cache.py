"""Pure (no-DB) tests for smartbi.gold.gold_read_cache.compute_cache_key.

WS1 Task 2: the cache key is deterministic SHA256 over
(endpoint | start | end | role | params). These tests exercise only the
key derivation (no DB needed). The GoldReadCache.get/put RLS-GUC paths are
covered by real-DB integration when Postgres is available — mirrors how
narrative_cache splits hash tests (pure) from cache I/O (DB).
"""
from __future__ import annotations

from smartbi.gold.gold_read_cache import compute_cache_key


def test_cache_key_deterministic():
    """Same args (in any param dict ordering) → same 64-char key."""
    k1 = compute_cache_key(
        "finance-summary", "2025-01-01", "2025-12-31", "factory_super_admin",
        {"top_n_stores": 10},
    )
    k2 = compute_cache_key(
        "finance-summary", "2025-01-01", "2025-12-31", "factory_super_admin",
        {"top_n_stores": 10},
    )
    assert k1 == k2
    assert len(k1) == 64
    assert all(c in "0123456789abcdef" for c in k1)


def test_cache_key_param_order_independent():
    """params dict ordering must not change the key (sort_keys=True)."""
    k1 = compute_cache_key(
        "finance-summary", None, None, "admin", {"a": 1, "b": 2},
    )
    k2 = compute_cache_key(
        "finance-summary", None, None, "admin", {"b": 2, "a": 1},
    )
    assert k1 == k2


def test_cache_key_varies_by_role():
    """Different role → different key (no cross-role leak)."""
    base = ("finance-summary", "2025-01-01", "2025-12-31")
    k_admin = compute_cache_key(*base, "factory_super_admin", {"top_n_stores": 10})
    k_operator = compute_cache_key(*base, "operator", {"top_n_stores": 10})
    assert k_admin != k_operator


def test_cache_key_varies_by_range():
    """Different date range → different key."""
    k_full = compute_cache_key(
        "finance-summary", "2025-01-01", "2025-12-31", "admin", {},
    )
    k_all = compute_cache_key(
        "finance-summary", None, None, "admin", {},
    )
    k_partial = compute_cache_key(
        "finance-summary", "2025-01-01", None, "admin", {},
    )
    assert len({k_full, k_all, k_partial}) == 3


def test_cache_key_varies_by_endpoint():
    """Different endpoint name → different key."""
    k_fin = compute_cache_key("finance-summary", None, None, "admin", {})
    k_kpi = compute_cache_key("kpi-summary", None, None, "admin", {})
    assert k_fin != k_kpi


def test_cache_key_none_role_stable():
    """A None role is handled without crashing and is stable."""
    k1 = compute_cache_key("finance-summary", None, None, None, {})
    k2 = compute_cache_key("finance-summary", None, None, None, {})
    assert k1 == k2
    assert len(k1) == 64
