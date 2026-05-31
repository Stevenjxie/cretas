"""Persistence — save/load TemplateResults to smart_bi_pg_analysis_results.

Uses asyncpg raw SQL (consistent with materializer). Upsert keyed on
(upload_id, template_code). factory_id is always in WHERE on reads for
cross-tenant safety.
"""
from __future__ import annotations

import json
import logging
from typing import Any, Dict, List, Optional

from .templates.base import TemplateResult
from .templates.registry import get_registry

logger = logging.getLogger(__name__)


def _result_to_row_fields(result: TemplateResult) -> Dict[str, Any]:
    """Map TemplateResult → column values. Caller adds upload_id/factory_id/domain."""
    # Prefer the LLM-generated rich insight (produced at materialize time by
    # llm_materializer) so cache hits serve LLM-quality content at 0 query-token.
    # Fall back to the deterministic rule insight_text when the LLM was
    # unavailable / failed (llm_insight is None). NEVER store empty/fake — an
    # empty llm_insight must not clobber a present rule insight_text.
    chosen_insight = (
        result.llm_insight
        if (result.llm_insight and result.llm_insight.strip())
        else result.insight_text
    )
    return {
        "analysis_type": f"materialized:{result.code}",
        "template_code": result.code,
        "analysis_result": {
            "title": result.title,
            "data": result.data,
        },
        "chart_configs": [result.chart_config] if result.chart_config else [],
        "kpi_values": result.kpis or {},
        "insights": [chosen_insight] if chosen_insight else [],
    }


async def save_materialization_results(
    pool,
    upload_id: int,
    factory_id: str,
    domain: str,
    results: List[TemplateResult],
    schema_version: int = 1,
) -> int:
    """Upsert template results, filtering out skipped/errored ones.

    Returns count written.
    """
    rows_to_write = [r for r in results if r.applies]
    # Apr 26 2026: also collect codes that DIDN'T apply this time so we
    # delete any stale rows from previous materializations. Without this,
    # changing applies() (e.g. top_n_by_dim now returns False when total=0)
    # leaves stale data serving wrong cache hits.
    codes_to_remove = [r.code for r in results if not r.applies]

    if not rows_to_write and not codes_to_remove:
        logger.info(f"[persistence] upload {upload_id}: no applicable results to save")
        return 0

    count = 0
    async with pool.acquire() as conn:
        async with conn.transaction():
            # Phase 4 P1.d: clean up stale rows for templates that no longer
            # apply (e.g. data signal changed since last materialize).
            if codes_to_remove:
                deleted = await conn.execute(
                    """DELETE FROM smart_bi_pg_analysis_results
                       WHERE upload_id = $1 AND template_code = ANY($2::text[])""",
                    upload_id, codes_to_remove,
                )
                if deleted and deleted != 'DELETE 0':
                    logger.info(
                        f"[persistence] upload {upload_id}: pruned stale templates "
                        f"({deleted}, codes={codes_to_remove})"
                    )
            for r in rows_to_write:
                fields = _result_to_row_fields(r)
                # Upsert on (upload_id, template_code). analysis_type index matches.
                await conn.execute(
                    """INSERT INTO smart_bi_pg_analysis_results
                        (upload_id, factory_id, analysis_type, analysis_result,
                         chart_configs, kpi_values, insights, domain,
                         template_code, schema_version, created_at)
                       VALUES ($1, $2, $3, $4::jsonb, $5::jsonb, $6::jsonb,
                               $7::jsonb, $8, $9, $10, NOW())
                       ON CONFLICT (upload_id, template_code) WHERE template_code IS NOT NULL
                       DO UPDATE SET
                           analysis_result = EXCLUDED.analysis_result,
                           chart_configs   = EXCLUDED.chart_configs,
                           kpi_values      = EXCLUDED.kpi_values,
                           insights        = EXCLUDED.insights,
                           domain          = EXCLUDED.domain,
                           schema_version  = EXCLUDED.schema_version,
                           created_at      = NOW()""",
                    upload_id, factory_id,
                    fields["analysis_type"],
                    json.dumps(fields["analysis_result"], ensure_ascii=False, default=str),
                    json.dumps(fields["chart_configs"], ensure_ascii=False, default=str),
                    json.dumps(fields["kpi_values"], ensure_ascii=False, default=str),
                    json.dumps(fields["insights"], ensure_ascii=False, default=str),
                    domain, fields["template_code"], schema_version,
                )
                count += 1
    logger.info(
        f"[persistence] upload {upload_id}: saved {count}/{len(results)} "
        f"templates (domain={domain}, factory={factory_id})"
    )
    return count


async def load_materialization_results(
    pool,
    upload_id: int,
    factory_id: Optional[str] = None,
) -> List[Dict[str, Any]]:
    """Load cached results. factory_id enforces cross-tenant safety when provided."""
    registry = get_registry()
    async with pool.acquire() as conn:
        if factory_id is not None:
            rows = await conn.fetch(
                """SELECT template_code, analysis_result, chart_configs, kpi_values,
                          insights, domain, schema_version, created_at
                   FROM smart_bi_pg_analysis_results
                   WHERE upload_id = $1 AND factory_id = $2 AND template_code IS NOT NULL
                   ORDER BY template_code""",
                upload_id, factory_id
            )
        else:
            rows = await conn.fetch(
                """SELECT template_code, analysis_result, chart_configs, kpi_values,
                          insights, domain, schema_version, created_at
                   FROM smart_bi_pg_analysis_results
                   WHERE upload_id = $1 AND template_code IS NOT NULL
                   ORDER BY template_code""",
                upload_id
            )

    out: List[Dict[str, Any]] = []
    for row in rows:
        code = row['template_code']
        # Title from registry (not stored) — fallback to code if template unregistered
        try:
            title = registry.by_code(code).title
        except KeyError:
            title = code
        ar = row['analysis_result'] or {}
        if isinstance(ar, str):
            ar = json.loads(ar)
        cc = row['chart_configs'] or []
        if isinstance(cc, str):
            cc = json.loads(cc)
        kv = row['kpi_values'] or {}
        if isinstance(kv, str):
            kv = json.loads(kv)
        ins = row['insights'] or []
        if isinstance(ins, str):
            ins = json.loads(ins)
        out.append({
            "code": code,
            "title": ar.get("title", title),  # prefer stored title if present
            "data": ar.get("data", {}),
            "chart_config": (cc[0] if cc else None),
            "kpis": kv,
            "insight_text": (ins[0] if ins else None),
            "domain": row['domain'],
            "schema_version": row['schema_version'],
            "created_at": row['created_at'].isoformat() if row['created_at'] else None,
        })
    return out
