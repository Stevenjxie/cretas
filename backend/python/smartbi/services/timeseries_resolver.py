from __future__ import annotations

import json
import logging
from typing import Any, Dict, Iterable, List, Optional

import smartbi.config

logger = logging.getLogger(__name__)


def _jsonb_param(value: Dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _row_to_dict(row: Any) -> Dict[str, Any]:
    item = dict(row)
    dims = item.get("dims")
    if isinstance(dims, str):
        try:
            item["dims"] = json.loads(dims)
        except json.JSONDecodeError:
            pass
    return item


async def query_timeseries(
    factory_id: str,
    *,
    template_key: Optional[str] = None,
    domain: Optional[str] = None,
    start: Optional[str] = None,
    end: Optional[str] = None,
    canonical_fields: Optional[Iterable[str]] = None,
    dims_filter: Optional[Dict[str, Any]] = None,
) -> List[Dict[str, Any]]:
    if not factory_id:
        return []

    conditions = ["factory_id = $1"]
    params: List[Any] = [factory_id]

    if template_key is not None:
        params.append(template_key)
        conditions.append(f"template_key = ${len(params)}")

    if domain is not None:
        params.append(domain)
        conditions.append(f"domain = ${len(params)}")

    if start is not None and end is not None:
        params.extend([start, end])
        conditions.append(f"period BETWEEN ${len(params) - 1} AND ${len(params)}")
    elif start is not None:
        params.append(start)
        conditions.append(f"period >= ${len(params)}")
    elif end is not None:
        params.append(end)
        conditions.append(f"period <= ${len(params)}")

    field_list = list(canonical_fields or [])
    if field_list:
        params.append(field_list)
        conditions.append(f"canonical_field = ANY(${len(params)})")

    if dims_filter:
        params.append(_jsonb_param(dims_filter))
        conditions.append(f"dims @> ${len(params)}::jsonb")

    where_clause = " AND ".join(conditions)
    sql = f"""
        SELECT period, canonical_field, dims, value_num
          FROM smart_bi_timeseries
         WHERE {where_clause}
         ORDER BY period
    """

    try:
        pool = await smartbi.config.get_pg_pool()
        if pool is None:
            return []

        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)", factory_id
                )
                rows = await conn.fetch(sql, *params)
        return [_row_to_dict(row) for row in rows]
    except Exception:
        logger.warning(
            "timeseries query failed; returning empty result",
            exc_info=True,
            extra={
                "factory_id": factory_id,
                "template_key": template_key,
                "domain": domain,
            },
        )
        return []
