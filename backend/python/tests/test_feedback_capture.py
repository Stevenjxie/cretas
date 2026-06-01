"""Task 6: user mapping corrections flow into the learning candidate table.

Verifies POST /auto-parse/feedback, when correction_type == "mapping", also
calls capture_candidate(method="user_correction", confidence=1.0,
learning_type="field_mapping") with the right source_key/target_value.

The DB/pool is fully mocked — these tests never touch a real database.
"""
import time

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import smartbi.services.learning_promotion as lp
from smartbi.api.excel import router
# Use the same module namespace the endpoint imports the cache from
# (excel.py does ``from services.schema_cache import get_schema_cache``), so the
# global cache singleton seeded here is the one the endpoint reads.
from services.schema_cache import CacheEntry, get_schema_cache


@pytest.fixture
def client():
    """FastAPI TestClient with the excel router mounted (resolves Form params)."""
    app = FastAPI()
    app.include_router(router)
    return TestClient(app)


def _seed_cache(cache_key, field_mappings):
    """Insert a cache entry whose mapping_config carries the given field_mappings."""
    cache = get_schema_cache()
    cache._memory_cache[cache_key] = CacheEntry(
        key=cache_key,
        structure_config={},
        mapping_config={"field_mappings": field_mappings},
        created_at=time.time(),
        accessed_at=time.time(),
    )
    return cache


@pytest.fixture
def capture_spy(monkeypatch):
    """Replace capture_candidate with a spy and a fake non-None pool."""
    calls = []

    async def fake_capture(pool, learning_type, source_key, target_value,
                           factory_id, method, confidence, business_type="unknown"):
        calls.append({
            "pool": pool,
            "learning_type": learning_type,
            "source_key": source_key,
            "target_value": target_value,
            "factory_id": factory_id,
            "method": method,
            "confidence": confidence,
            "business_type": business_type,
        })

    async def fake_pool():
        return object()  # sentinel non-None pool

    monkeypatch.setattr(lp, "capture_candidate", fake_capture)
    monkeypatch.setattr("smartbi.config.get_pg_pool", fake_pool)
    return calls


def test_mapping_correction_captures_via_explicit_column(client, capture_spy):
    _seed_cache("k1", [{"original": "营业进账", "standard": "income"}])

    resp = client.post("/auto-parse/feedback", data={
        "cache_key": "k1",
        "correction_type": "mapping",
        "original_value": "income",
        "correct_value": "revenue",
        "column_name": "营业进账",
        "factory_id": "RES_3101_009",
        "business_type": "restaurant",
    })

    assert resp.status_code == 200
    assert resp.json()["success"] is True
    assert len(capture_spy) == 1
    call = capture_spy[0]
    assert call["learning_type"] == "field_mapping"
    assert call["source_key"] == "营业进账"
    assert call["target_value"] == "revenue"
    assert call["factory_id"] == "RES_3101_009"
    assert call["method"] == "user_correction"
    assert call["confidence"] == 1.0
    assert call["business_type"] == "restaurant"
    assert call["pool"] is not None


def test_mapping_correction_derives_column_from_old_standard(client, capture_spy):
    # No column_name form field; original_value is the previously-detected
    # (wrong) standard field. Resolver should find its column via the cache.
    _seed_cache("k2", [{"original": "营业进账", "standard": "income"}])

    resp = client.post("/auto-parse/feedback", data={
        "cache_key": "k2",
        "correction_type": "mapping",
        "original_value": "income",
        "correct_value": "revenue",
    })

    assert resp.status_code == 200
    assert resp.json()["success"] is True
    assert len(capture_spy) == 1
    call = capture_spy[0]
    assert call["source_key"] == "营业进账"
    assert call["target_value"] == "revenue"
    assert call["method"] == "user_correction"
    assert call["confidence"] == 1.0
    assert call["business_type"] == "unknown"


def test_mapping_correction_derives_column_from_column_name_in_payload(client, capture_spy):
    # original_value is itself the column name (add_user_correction docstring shape).
    _seed_cache("k3", [{"original": "门店", "standard": "store"}])

    resp = client.post("/auto-parse/feedback", data={
        "cache_key": "k3",
        "correction_type": "mapping",
        "original_value": "门店",
        "correct_value": "store_name",
    })

    assert resp.status_code == 200
    assert resp.json()["success"] is True
    assert len(capture_spy) == 1
    assert capture_spy[0]["source_key"] == "门店"
    assert capture_spy[0]["target_value"] == "store_name"


def test_non_mapping_correction_does_not_capture(client, capture_spy):
    _seed_cache("k4", [{"original": "营业进账", "standard": "income"}])

    resp = client.post("/auto-parse/feedback", data={
        "cache_key": "k4",
        "correction_type": "structure",
        "original_value": "2",
        "correct_value": "3",
    })

    assert resp.status_code == 200
    assert resp.json()["success"] is True
    assert len(capture_spy) == 0


def test_mapping_correction_unresolvable_column_skips_capture(client, capture_spy):
    # No column_name and original_value matches neither an original nor a standard
    # in the cache -> source_key unresolved -> capture skipped (no wrong mapping).
    _seed_cache("k5", [{"original": "营业进账", "standard": "income"}])

    resp = client.post("/auto-parse/feedback", data={
        "cache_key": "k5",
        "correction_type": "mapping",
        "original_value": "完全不相关的值",
        "correct_value": "revenue",
    })

    assert resp.status_code == 200
    assert resp.json()["success"] is True
    assert len(capture_spy) == 0


def test_capture_failure_does_not_break_feedback(client, monkeypatch):
    # capture_candidate raising must not break feedback recording (fail-open).
    _seed_cache("k6", [{"original": "营业进账", "standard": "income"}])

    async def boom(*a, **k):
        raise RuntimeError("db down")

    async def fake_pool():
        return object()

    monkeypatch.setattr(lp, "capture_candidate", boom)
    monkeypatch.setattr("smartbi.config.get_pg_pool", fake_pool)

    resp = client.post("/auto-parse/feedback", data={
        "cache_key": "k6",
        "correction_type": "mapping",
        "original_value": "income",
        "correct_value": "revenue",
    })

    assert resp.status_code == 200
    assert resp.json()["success"] is True
