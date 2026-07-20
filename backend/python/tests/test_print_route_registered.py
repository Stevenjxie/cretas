"""Minimal smoke test: verify /api/printing/* routes are registered in the FastAPI app.

Root cause context (D-19/E-13 502 fix):
  Test Java (10011) was missing --cretas.python.base-url=http://localhost:8084 in
  cretas-backend-test.service, so PrintController proxied to prod Python (8083).
  Prod Python uses a different JWT_SECRET (no '-test' suffix), causing 401 →
  RestClientException → 502 to the client.

This test guards route registration only — it does NOT render PDFs (requires
reportlab + fonts which are not available in CI). Auth is expected to block (401)
since no JWT is provided; that proves the route exists (404 would mean missing).
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

# Ensure the backend/python directory is on sys.path so 'printing' can be imported.
_PYTHON_ROOT = Path(__file__).resolve().parent.parent
if str(_PYTHON_ROOT) not in sys.path:
    sys.path.insert(0, str(_PYTHON_ROOT))
if str(_PYTHON_ROOT / "smartbi") not in sys.path:
    sys.path.insert(0, str(_PYTHON_ROOT / "smartbi"))

# Set required env vars before importing the app so config doesn't blow up.
import os  # noqa: E402

os.environ.setdefault("JWT_SECRET", "cretas-jwt-secret-key-2026-test")
os.environ.setdefault("POSTGRES_DB", "smartbi_db")


def _import_print_router():
    """Import the printing router in isolation — does NOT need the full app."""
    from printing.api.print import router  # noqa: PLC0415
    return router


def _make_test_client():
    """Build a minimal FastAPI app with only the printing router mounted."""
    try:
        from fastapi import FastAPI  # noqa: PLC0415
        from fastapi.testclient import TestClient  # noqa: PLC0415
    except ImportError:
        pytest.skip("fastapi / starlette not installed")

    router = _import_print_router()
    app = FastAPI()
    app.include_router(router, prefix="/api/printing")
    return TestClient(app, raise_server_exceptions=False)


# ── Tests ──────────────────────────────────────────────────────────────────


def test_print_router_can_be_imported():
    """Route module imports without errors."""
    router = _import_print_router()
    route_paths = [r.path for r in router.routes]
    assert "/sales-order" in route_paths, f"sales-order route missing; got {route_paths}"
    assert "/purchase-order" in route_paths, f"purchase-order route missing; got {route_paths}"
    assert "/health" in route_paths, f"health route missing; got {route_paths}"
    # SP12 T8 — two new routes must be registered
    assert "/production-work-order" in route_paths, (
        f"production-work-order route missing; got {route_paths}"
    )
    assert "/consolidated-material-requisition" in route_paths, (
        f"consolidated-material-requisition route missing; got {route_paths}"
    )
    assert "/production-document-package" in route_paths, (
        f"production-document-package route missing; got {route_paths}"
    )
    # P1 #37 — multi-SO work order route must be registered
    assert "/production-work-order-multi" in route_paths, (
        f"production-work-order-multi route missing; got {route_paths}"
    )


def test_print_health_route_exists():
    """GET /api/printing/health returns 200 (no auth required for health)."""
    try:
        client = _make_test_client()
    except Exception as exc:  # noqa: BLE001
        pytest.skip(f"Could not build test client: {exc}")

    # Health is a GET and requires no auth in the isolated test app (no JWTMiddleware here).
    resp = client.get("/api/printing/health")
    # 200 = route registered + reportlab available
    # 500 = route registered but reportlab/fonts missing (still proves registration)
    # 404 = route NOT registered (fail)
    assert resp.status_code != 404, (
        f"GET /api/printing/health returned 404 — route is NOT registered. "
        f"body={resp.text[:200]}"
    )


@pytest.mark.parametrize("doc_type", [
    "sales-order",
    "purchase-order",
    "quotation",
    "production-task",
    "material-requisition",
    "stock-movement",
    "financial-invoice",
    "packing-list",
    # SP12 T8 — must not return 404
    "production-work-order",
    "consolidated-material-requisition",
    # P1 #37 — multi-SO work order must not return 404
    "production-work-order-multi",
])
def test_print_post_routes_registered(doc_type: str):
    """POST /api/printing/{doc_type} returns non-404 (route exists).

    422 = route exists but empty body (FastAPI validation error) — that's fine.
    404 = route NOT registered.
    """
    try:
        client = _make_test_client()
    except Exception as exc:  # noqa: BLE001
        pytest.skip(f"Could not build test client: {exc}")

    resp = client.post(f"/api/printing/{doc_type}", json={})
    assert resp.status_code != 404, (
        f"POST /api/printing/{doc_type} returned 404 — route NOT registered. "
        f"Check main.py includes printing_api router under /api/printing prefix. "
        f"body={resp.text[:200]}"
    )
