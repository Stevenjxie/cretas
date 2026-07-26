"""Tenant-scoped POS dish name to recipe-cost key resolution.

The Cretas operational database remains the primary source of product names.
SmartBI also keeps a small read model because historical/demo Gold cost rows can
outlive the operational seed rows that originally produced them.  Callers merge
this fallback only for names that the primary product_types/alias lookup did not
resolve.  Ambiguous names deliberately remain unresolved.
"""
from __future__ import annotations

import logging
from collections import defaultdict
from typing import Dict, Iterable, Mapping, Optional

logger = logging.getLogger(__name__)


async def merge_cost_product_mapping(
    smartbi_pool,
    factory_id: str,
    normalized_names: Iterable[str],
    primary: Optional[Mapping[str, str]] = None,
) -> Dict[str, str]:
    """Return a merged ``normalized_name -> product_source_pk`` mapping.

    ``primary`` always wins.  The SmartBI fallback is tenant-scoped through both
    the SQL predicate and the RLS GUC.  If a normalized name maps to more than
    one source key, it is not selected: guessing would corrupt COGS and margin.
    Older schemas without the dimension fail closed and keep the primary map.
    """
    merged: Dict[str, str] = dict(primary or {})
    missing = sorted({
        str(name).strip()
        for name in normalized_names
        if name and str(name).strip() and str(name).strip() not in merged
    })
    if not factory_id or not missing:
        return merged

    try:
        async with smartbi_pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)",
                factory_id,
            )
            rows = await conn.fetch(
                """
                SELECT normalized_name, product_source_pk
                  FROM dim_restaurant_cost_product
                 WHERE factory_id = $1
                   AND normalized_name = ANY($2::text[])
                   AND is_active = TRUE
                 ORDER BY normalized_name, product_source_pk
                """,
                factory_id,
                missing,
            )
    except Exception as exc:
        if "does not exist" not in str(exc):
            logger.warning(
                "[cost-product-map] SmartBI fallback lookup failed factory=%s: %s",
                factory_id,
                exc,
            )
        return merged

    candidates = defaultdict(set)
    for row in rows:
        name = str(row["normalized_name"] or "").strip()
        source_pk = str(row["product_source_pk"] or "").strip()
        if name and source_pk:
            candidates[name].add(source_pk)

    for name, source_pks in candidates.items():
        if len(source_pks) == 1:
            merged[name] = next(iter(source_pks))
        else:
            logger.warning(
                "[cost-product-map] ambiguous mapping left unresolved "
                "factory=%s name=%s candidates=%s",
                factory_id,
                name,
                sorted(source_pks),
            )
    return merged


async def merge_cost_product_names(
    smartbi_pool,
    factory_id: str,
    product_source_pks: Iterable[str],
    primary: Optional[Mapping[str, str]] = None,
) -> Dict[str, str]:
    """Return ``product_source_pk -> product_name`` with SmartBI fallback."""
    merged: Dict[str, str] = dict(primary or {})
    missing = sorted({
        str(source_pk).strip()
        for source_pk in product_source_pks
        if source_pk
        and str(source_pk).strip()
        and str(source_pk).strip() not in merged
    })
    if not factory_id or not missing:
        return merged
    try:
        async with smartbi_pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)",
                factory_id,
            )
            rows = await conn.fetch(
                """
                SELECT product_source_pk, product_name
                  FROM dim_restaurant_cost_product
                 WHERE factory_id = $1
                   AND product_source_pk = ANY($2::text[])
                   AND is_active = TRUE
                """,
                factory_id,
                missing,
            )
    except Exception as exc:
        if "does not exist" not in str(exc):
            logger.warning(
                "[cost-product-map] SmartBI name lookup failed factory=%s: %s",
                factory_id,
                exc,
            )
        return merged
    for row in rows:
        source_pk = str(row["product_source_pk"] or "").strip()
        product_name = str(row["product_name"] or "").strip()
        if source_pk and product_name:
            merged[source_pk] = product_name
    return merged
