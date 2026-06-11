"""供应商进价数据管道 — material_batches → agg_supplier_price。

cretas_db.material_batches（真实采购价 162 条）→ smartbi_db.agg_supplier_price，
点亮供应商涨价预警（price_anomaly.py 下游现成）。

设计要点
--------
- 读 cretas_db（cretas_pool）：material_batches WHERE unit_price IS NOT NULL
  AND deleted_at IS NULL，JOIN raw_material_types(name)、suppliers(name)。
  JOIN 不到名字时诚实兜底（ingredient_name = material_type_id 字符串），不编假名。
- source_note_id = "mb:{id}" 唯一标识来源是 material_batch 行。
- 幂等去重：写入前查 agg_supplier_price 中已存在的 source_note_id SET；
  只对新增行调用 upsert_supplier_price_batch，跳过已有行。重复跑安全。
- delivery_date 优先选 purchase_date；其次 inbound_date；仍 None 则跳过（无日期无法
  排趋势）。
- RLS：upsert_supplier_price_batch 内部已设 set_config('app.factory_id')，无需再设。
- factory_id None 拒绝（Rule 6）。

入口
----
    stats = await run_supplier_price_ingest(cretas_pool, smartbi_pool, factory_id)
    # stats: dict with keys: total_read, skipped_no_price, skipped_no_date,
    #                         skipped_existing, inserted, errors

Path bootstrap（独立脚本运行时 smartbi/ 入 sys.path）mirrors factory_production_etl。
"""
from __future__ import annotations

import logging
import sys
import os
from typing import Any, Dict, List, Optional, Set

# ── Path bootstrap ────────────────────────────────────────────────────────────
_here = os.path.dirname(os.path.abspath(__file__))
_service_root = os.path.dirname(os.path.dirname(_here))  # .../backend/python
if _service_root not in sys.path:
    sys.path.insert(0, _service_root)

from smartbi.gold.supplier_price_etl import upsert_supplier_price_batch  # noqa: E402

logger = logging.getLogger(__name__)


# ─────────────────────────────────────────────────────────────────────────────
# Internal helpers
# ─────────────────────────────────────────────────────────────────────────────

def _source_note_id(batch_id: Any) -> str:
    """Canonical source_note_id for a material_batch row."""
    return f"mb:{batch_id}"


def _to_float_safe(v: Any) -> Optional[float]:
    """Decimal / numeric DB value → float, None preserved."""
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: fetch raw data from cretas_db
# ─────────────────────────────────────────────────────────────────────────────

async def _fetch_material_batches(cretas_pool, factory_id: str) -> list:
    """Fetch material_batches with price, JOINed to names.

    JOINs raw_material_types and suppliers for display names.
    LEFT JOIN so that missing FK rows do not drop the batch price row (fallback
    to raw ID strings instead — honest, not fabricated).

    Returns list of asyncpg Record / dict-like rows.
    """
    sql = """
        SELECT
            mb.id,
            mb.factory_id,
            mb.material_type_id,
            mb.supplier_id,
            mb.purchase_date,
            mb.inbound_date,
            mb.unit_price,
            mb.receipt_quantity  AS quantity,
            mb.quantity_unit     AS unit,
            rmt.name             AS material_name,
            s.name               AS supplier_name
        FROM material_batches mb
        LEFT JOIN raw_material_types rmt
               ON rmt.id = mb.material_type_id
              AND rmt.deleted_at IS NULL
        LEFT JOIN suppliers s
               ON s.id = mb.supplier_id
              AND s.deleted_at IS NULL
        WHERE mb.factory_id  = $1::varchar
          AND mb.unit_price  IS NOT NULL
          AND mb.deleted_at  IS NULL
        ORDER BY mb.id
    """
    async with cretas_pool.acquire() as conn:
        return await conn.fetch(sql, factory_id)


# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: load existing source_note_ids from smartbi_db (idempotency)
# ─────────────────────────────────────────────────────────────────────────────

async def _load_existing_source_ids(smartbi_pool, factory_id: str) -> Set[str]:
    """Return set of source_note_ids already in agg_supplier_price for this factory.

    Only loads ids that look like 'mb:*' to avoid false collisions with Java-pushed
    delivery-note rows (whose source_note_ids are note UUIDs).
    """
    async with smartbi_pool.acquire() as conn:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, true)", factory_id
        )
        rows = await conn.fetch(
            """
            SELECT source_note_id
              FROM agg_supplier_price
             WHERE factory_id     = $1
               AND source_note_id LIKE 'mb:%'
            """,
            factory_id,
        )
    return {r["source_note_id"] for r in rows}


# ─────────────────────────────────────────────────────────────────────────────
# Stage 3: transform to upsert row dicts
# ─────────────────────────────────────────────────────────────────────────────

def _to_upsert_row(raw_row, existing_ids: Set[str]) -> Optional[dict]:
    """Convert a material_batch DB row to an upsert-ready dict.

    Returns None if the row should be skipped (existing, no date, etc.).
    The caller should log skip reasons separately via stats.
    """
    batch_id   = raw_row["id"]
    source_id  = _source_note_id(batch_id)

    if source_id in existing_ids:
        return None  # already ingested; idempotent skip

    # delivery_date: prefer purchase_date, fall back to inbound_date
    delivery_date = raw_row.get("purchase_date") or raw_row.get("inbound_date")
    if delivery_date is None:
        return None  # no date → cannot place on price timeline; skip

    # ingredient_name: honest fallback to material_type_id string (not fabricated)
    material_name = raw_row.get("material_name")
    ingredient_name = (
        material_name
        if material_name is not None
        else str(raw_row["material_type_id"])
    )

    # supplier_name: honest fallback to supplier_id string
    supplier_name_raw = raw_row.get("supplier_name")
    supplier_name = (
        supplier_name_raw
        if supplier_name_raw is not None
        else (str(raw_row["supplier_id"]) if raw_row.get("supplier_id") else None)
    )

    return {
        "source_note_id":      source_id,
        "raw_material_type_id": str(raw_row["material_type_id"]),
        "supplier_id":         str(raw_row["supplier_id"]) if raw_row.get("supplier_id") else None,
        "supplier_name":       supplier_name,
        "ingredient_name":     ingredient_name,
        # normalized_name left to upsert_supplier_price_batch._normalize_name
        "delivery_date":       delivery_date,
        "unit_price":          _to_float_safe(raw_row["unit_price"]),
        "quantity":            _to_float_safe(raw_row.get("quantity")),
        "unit":                raw_row.get("unit"),
        "line_amount":         None,  # material_batches has no line_amount field
    }


# ─────────────────────────────────────────────────────────────────────────────
# Orchestrator
# ─────────────────────────────────────────────────────────────────────────────

async def run_supplier_price_ingest(
    cretas_pool,
    smartbi_pool,
    factory_id: str,
) -> Dict[str, Any]:
    """Ingest material_batches prices into agg_supplier_price.

    Idempotent: rows already present (source_note_id = 'mb:{id}') are skipped.

    Returns stats dict:
        total_read       — rows from material_batches (with price)
        skipped_no_date  — rows without purchase_date or inbound_date
        skipped_existing — rows already in agg_supplier_price
        inserted         — rows actually inserted
        errors           — list of error strings (empty on full success)
    """
    if factory_id is None or factory_id == "":
        raise ValueError(
            f"run_supplier_price_ingest: factory_id required (got {factory_id!r})"
        )

    stats: Dict[str, Any] = {
        "total_read":       0,
        "skipped_no_date":  0,
        "skipped_existing": 0,
        "inserted":         0,
        "errors":           [],
    }

    try:
        raw_rows = await _fetch_material_batches(cretas_pool, factory_id)
    except Exception as exc:
        stats["errors"].append(f"fetch: {exc}")
        logger.exception("[supplier-ingest] fetch failed for factory=%s", factory_id)
        return stats

    stats["total_read"] = len(raw_rows)
    if not raw_rows:
        logger.info("[supplier-ingest] no priced material_batches for factory=%s", factory_id)
        return stats

    try:
        existing_ids = await _load_existing_source_ids(smartbi_pool, factory_id)
    except Exception as exc:
        stats["errors"].append(f"load_existing: {exc}")
        logger.exception("[supplier-ingest] failed to load existing ids for factory=%s", factory_id)
        return stats

    upsert_rows: List[dict] = []
    for raw in raw_rows:
        row = _to_upsert_row(raw, existing_ids)
        if row is None:
            batch_id  = raw["id"]
            source_id = _source_note_id(batch_id)
            if source_id in existing_ids:
                stats["skipped_existing"] += 1
            else:
                stats["skipped_no_date"] += 1
        else:
            upsert_rows.append(row)

    if not upsert_rows:
        logger.info(
            "[supplier-ingest] nothing new to insert for factory=%s "
            "(total=%d existing=%d no_date=%d)",
            factory_id, stats["total_read"],
            stats["skipped_existing"], stats["skipped_no_date"],
        )
        return stats

    try:
        inserted = await upsert_supplier_price_batch(smartbi_pool, factory_id, upsert_rows)
        stats["inserted"] = inserted
    except Exception as exc:
        stats["errors"].append(f"upsert: {exc}")
        logger.exception("[supplier-ingest] upsert failed for factory=%s", factory_id)
        return stats

    logger.info(
        "[supplier-ingest] factory=%s total=%d existing=%d no_date=%d inserted=%d",
        factory_id,
        stats["total_read"],
        stats["skipped_existing"],
        stats["skipped_no_date"],
        stats["inserted"],
    )
    return stats
