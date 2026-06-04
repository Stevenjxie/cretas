"""二维火 POS ingest — manual-trigger sync endpoint (SKELETON).

POST /api/smartbi/{factory_id}/ingest/2dfire/sync

⚠️ HONEST FRAMING ⚠️
This endpoint is scaffolding for a future 二维火 开放平台 integration. We do
NOT have 二维火 API credentials yet. Today it implements exactly ONE real
behavior: the fool-proof not-configured dead-end (Rule 5 — dead-end with
next action). The configured path (pull → normalize → handoff to the
existing Silver pipeline) is a documented TODO.

Auth + factory scoping reuse the revenue-report helpers
(_enforce_factory_match) so behavior matches the rest of /api/smartbi/*.

See: smartbi/ingestion/twodfire_adapter.py + docs/integrations/2dfire-adapter.md
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException, Request

from smartbi.api._revenue_report_helpers import _enforce_factory_match
from smartbi.ingestion.twodfire_adapter import TwoDFirePosAdapter

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/smartbi/{factory_id}/ingest/2dfire")


async def _get_pool():
    """Lazily resolved asyncpg pool. Only used on the (future) configured path.

    Patched in tests to assert it is NOT touched on the not-configured path."""
    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="DB pool unavailable")
    return pool


@router.post("/sync")
async def sync_2dfire(factory_id: str, request: Request):
    """Manually trigger a 二维火 POS sync for this factory.

    Not configured (current reality) → fool-proof Rule 5 dead-end:
      HTTP 200, success:false, message names the exact env vars, actionHint
      points the user at configuring credentials. NO DB, NO network.

    Configured (future) → would run pull → normalize → handoff. STUBBED.
    """
    caller_factory = _enforce_factory_match(factory_id, request)
    adapter = TwoDFirePosAdapter(factory_id=caller_factory)

    if not adapter.is_configured():
        # Rule 5 (fool-proof-design): dead-end with a concrete next action.
        # 200 + success:false so the frontend shows a friendly, actionable
        # banner rather than a scary error toast.
        return {
            "success": False,
            "data": {
                "configured": False,
                "message": (
                    "二维火 API 凭证未配置 — 请在 .env 配置 "
                    "TWODFIRE_APP_KEY / TWODFIRE_APP_SECRET / TWODFIRE_SHOP_ID 后重试"
                ),
                "actionHint": "配置凭证",
            },
            "message": "二维火 API 凭证未配置",
        }

    # ── Configured path (FUTURE — STUBBED) ────────────────────────────────
    # TODO (once creds + docs available): wire pull → normalize → handoff:
    #
    #   pool = await _get_pool()
    #   await adapter.authenticate()
    #   raw_orders = await adapter.pull_orders(start, end)
    #   raw_items = await adapter.pull_order_items([o["orderId"] for o in raw_orders])
    #   raw_payments = await adapter.pull_payments(start, end)
    #   # Build row_data dicts (one per bill) merging order + its items + payments,
    #   # all keyed by canonical Chinese columns (adapter.normalize_*), then:
    #   #   1. INSERT a smart_bi_pg_excel_uploads row (detected_table_type='POS')
    #   #   2. INSERT each row into smart_bi_dynamic_data (row_data JSONB)
    #   #      — mirror smartbi/ingestion/pos_ingest.py:ingest_one_csv
    #   #   3. await backfill_upload(pool, upload_id, factory_id)  (Silver write)
    #   #   4. await materialize_daily_order_type_meal(pool, factory_id, d1, d2)
    #   #   5. Persist sync cursor in twodfire_sync_state (see migration).
    #   # The Gold aggregations + revenue report then see the data UNCHANGED.
    raise HTTPException(
        status_code=501,
        detail=(
            "二维火 configured sync path not implemented — adapter is a skeleton. "
            "See docs/integrations/2dfire-adapter.md to finish the integration."
        ),
    )
