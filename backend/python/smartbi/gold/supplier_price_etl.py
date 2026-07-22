"""G7 取数自动化 Tier A — 供应商进价 gold ETL + 查询。

`agg_supplier_price` 是 append-only 历史进价表 (无 ON CONFLICT key)。每次 Java
确认一张送货单, 调 POST /api/smartbi/gold/supplier-price/batch-upsert →
upsert_supplier_price_batch 把行项批量插入。

查询函数为 G5 成本卡 (latest price) + G4 进价趋势诊断 解锁数据。

所有写/读都设 RLS 租户上下文 (set_config app.factory_id)。Rule 6: 拒绝 None
factory_id 静默零结果。
"""
from __future__ import annotations

import logging
from typing import Any, List, Optional

logger = logging.getLogger(__name__)


def _normalize_name(name: str) -> str:
    """与 restaurant_ops_etl._normalize_name 一致: 小写 + strip + 折叠空白。"""
    if not name:
        return ""
    return " ".join(str(name).lower().strip().split())


def _to_float(v: Any) -> Optional[float]:
    """Decimal/None → float|None (JSON-safe, mirrors gold read convention)."""
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


async def upsert_supplier_price_batch(
    smartbi_pool,
    factory_id: str,
    rows: List[dict],
) -> int:
    """Bulk-insert confirmed OCR/manual delivery lines into agg_supplier_price.

    Append-only — each confirmed delivery line = a new history row (no ON CONFLICT).

    Each row dict keys (camelCase/snake_case tolerated via .get):
        ingredient_name, normalized_name, raw_material_type_id, supplier_id,
        supplier_name, delivery_date, unit_price, quantity, unit, line_amount,
        source_note_id

    Returns count inserted. Rejects None factory_id (Rule 6).
    """
    if factory_id is None or factory_id == "":
        raise ValueError(
            f"upsert_supplier_price_batch: factory_id required (got {factory_id!r})"
        )
    if not rows:
        return 0

    # Build bulk arrays for UNNEST insert. normalized_name fallback to _normalize_name
    # so callers that omit it still dedup-key consistently.
    source_note_ids = [r.get("source_note_id") for r in rows]
    supplier_ids = [r.get("supplier_id") for r in rows]
    supplier_names = [r.get("supplier_name") for r in rows]
    ingredient_names = [r.get("ingredient_name") or "" for r in rows]
    normalized_names = [
        r.get("normalized_name") or _normalize_name(r.get("ingredient_name") or "")
        for r in rows
    ]
    raw_material_type_ids = [r.get("raw_material_type_id") for r in rows]
    delivery_dates = [r.get("delivery_date") for r in rows]
    unit_prices = [r.get("unit_price") for r in rows]
    quantities = [r.get("quantity") for r in rows]
    units = [r.get("unit") for r in rows]
    line_amounts = [r.get("line_amount") for r in rows]

    async with smartbi_pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )
            result = await conn.fetch(
                """
                INSERT INTO agg_supplier_price (
                    factory_id, source_note_id, supplier_id, supplier_name,
                    ingredient_name, normalized_name, raw_material_type_id,
                    delivery_date, unit_price, quantity, unit, line_amount
                )
                SELECT $1, sni, sid, sname, iname, nname, rmt,
                       ddate, uprice, qty, u, lamt
                  FROM UNNEST(
                    $2::text[], $3::text[], $4::text[], $5::text[], $6::text[],
                    $7::text[], $8::date[], $9::numeric[], $10::numeric[],
                    $11::text[], $12::numeric[]
                  ) AS t(sni, sid, sname, iname, nname, rmt, ddate, uprice, qty, u, lamt)
                RETURNING id
                """,
                factory_id,
                source_note_ids,
                supplier_ids,
                supplier_names,
                ingredient_names,
                normalized_names,
                raw_material_type_ids,
                delivery_dates,
                unit_prices,
                quantities,
                units,
                line_amounts,
            )
    count = len(result) if result is not None else 0
    logger.info(
        "[supplier-price] upserted %d rows for factory=%s (note=%s)",
        count, factory_id, source_note_ids[0] if source_note_ids else None,
    )
    return count


async def get_supplier_price_trend(
    pool,
    factory_id: str,
    ingredient_name: str,
    days: int = 90,
) -> List[dict]:
    """Return [{deliveryDate, unitPrice, supplierName, quantity}] ASC by date.

    Normalized-name match against the requested ingredient. Used by G4 进价趋势 chart.
    """
    if factory_id is None or factory_id == "":
        raise ValueError(
            f"get_supplier_price_trend: factory_id required (got {factory_id!r})"
        )
    normalized = _normalize_name(ingredient_name)
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        rows = await conn.fetch(
            """
            SELECT delivery_date, unit_price, supplier_name, quantity
              FROM agg_supplier_price
             WHERE factory_id = $1
               AND normalized_name = $2
               AND delivery_date >= CURRENT_DATE - ($3::int)
             ORDER BY delivery_date ASC
            """,
            factory_id, normalized, days,
        )
    return [
        {
            "deliveryDate": r["delivery_date"].isoformat()
            if r["delivery_date"] is not None else None,
            "unitPrice": _to_float(r["unit_price"]),
            "supplierName": r["supplier_name"],
            "quantity": _to_float(r["quantity"]),
        }
        for r in rows
    ]


async def get_latest_ingredient_prices(
    pool,
    factory_id: str,
) -> List[dict]:
    """Latest unit_price per ingredient (normalized_name) for G5 cost card.

    DISTINCT ON (normalized_name) ordered by delivery_date DESC picks the most
    recent confirmed price for each ingredient.
    """
    if factory_id is None or factory_id == "":
        raise ValueError(
            f"get_latest_ingredient_prices: factory_id required (got {factory_id!r})"
        )
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        rows = await conn.fetch(
            """
            SELECT DISTINCT ON (normalized_name)
                   normalized_name, ingredient_name, unit_price, unit,
                   delivery_date, supplier_name
              FROM agg_supplier_price
             WHERE factory_id = $1
             ORDER BY normalized_name, delivery_date DESC
            """,
            factory_id,
        )
    return [
        {
            "normalizedName": r["normalized_name"],
            "ingredientName": r["ingredient_name"],
            "unitPrice": _to_float(r["unit_price"]),
            "unit": r["unit"],
            "deliveryDate": r["delivery_date"].isoformat()
            if r["delivery_date"] is not None else None,
            "supplierName": r["supplier_name"],
        }
        for r in rows
    ]
