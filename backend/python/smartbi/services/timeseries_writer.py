from __future__ import annotations

import json
import logging
from typing import Iterable, Sequence

from smartbi.services.timeseries_extractor import TimeseriesRow

logger = logging.getLogger(__name__)


def _periods(rows: Sequence[TimeseriesRow]) -> list[str]:
    return sorted({row.period for row in rows if row.period})


def _insert_records(rows: Iterable[TimeseriesRow]) -> list[tuple]:
    records = []
    for row in rows:
        records.append(
            (
                row.factory_id,
                row.template_key,
                row.domain,
                row.period,
                row.canonical_field,
                row.value_num,
                json.dumps(row.dims or {}, sort_keys=True, ensure_ascii=False),
                row.dims_hash,
                row.source_upload_id,
            )
        )
    return records


async def write_timeseries(
    factory_id: str,
    template_key: str,
    rows: list[TimeseriesRow],
    source_upload_id: int,
) -> int:
    if not rows:
        return 0

    periods = _periods(rows)
    if not periods:
        return 0

    try:
        import smartbi.config

        pool = await smartbi.config.get_pg_pool()
        if pool is None:
            logger.warning(
                "[timeseries-writer] no pg pool; skipping upload=%s factory=%s",
                source_upload_id,
                factory_id,
            )
            return 0

        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", factory_id
                )

                existing_max_upload_id = await conn.fetchval(
                    """
                    SELECT max(source_upload_id)
                      FROM smart_bi_timeseries
                     WHERE factory_id = $1
                       AND template_key = $2
                       AND period = ANY($3::text[])
                    """,
                    factory_id,
                    template_key,
                    periods,
                )
                if (
                    existing_max_upload_id is not None
                    and source_upload_id < int(existing_max_upload_id)
                ):
                    logger.info(
                        "[timeseries-writer] skip older upload=%s factory=%s template=%s periods=%s existing_max=%s",
                        source_upload_id,
                        factory_id,
                        template_key,
                        periods,
                        existing_max_upload_id,
                    )
                    return 0

                await conn.execute(
                    """
                    DELETE FROM smart_bi_timeseries
                     WHERE factory_id = $1
                       AND template_key = $2
                       AND period = ANY($3::text[])
                    """,
                    factory_id,
                    template_key,
                    periods,
                )

                records = _insert_records(rows)
                await conn.executemany(
                    """
                    INSERT INTO smart_bi_timeseries
                        (factory_id, template_key, domain, period,
                         canonical_field, value_num, dims, dims_hash,
                         source_upload_id)
                    VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9)
                    """,
                    records,
                )
                return len(records)
    except Exception:
        logger.warning(
            "[timeseries-writer] failed for upload=%s factory=%s template=%s",
            source_upload_id,
            factory_id,
            template_key,
            exc_info=True,
        )
        return 0
