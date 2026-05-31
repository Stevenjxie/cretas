"""
LLM 出境审计 admin — 只读 + CSV 导出 (数据主权可演示/可证明).

GET /api/smartbi/admin/llm-egress/summary?days=7
GET /api/smartbi/admin/llm-egress/recent?limit=200
GET /api/smartbi/admin/llm-egress/by-factory?days=7
GET /api/smartbi/admin/llm-egress/export.csv?days=30   (给客户/合规导出"哪些数据脱敏后出境了")

注: 镜像 llm_usage_admin —— 该 admin 路由组挂在 /api/smartbi/admin/* 下, 由网关/网络
层保护。审计表只存脱敏后 prompt 的 sha256, 不含明文。
"""
from __future__ import annotations

import csv
import io
import logging
from typing import Any, Dict, List

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import StreamingResponse

from smartbi.config import get_pg_pool

logger = logging.getLogger(__name__)
router = APIRouter()


async def _fetch(query: str, *args) -> List[Dict[str, Any]]:
    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(500, "PG pool unavailable")
    async with pool.acquire() as conn:
        rows = await conn.fetch(query, *args)
        return [dict(r) for r in rows]


@router.get("/summary")
async def summary(days: int = Query(7, ge=1, le=90)) -> Dict[str, Any]:
    """近 N 天出境总览: 总次数 / 已脱敏次数 / 脱敏敏感值总数 / 失败数。"""
    rows = await _fetch(
        """SELECT
             count(*) AS egress_calls,
             sum(CASE WHEN sanitized THEN 1 ELSE 0 END) AS sanitized_calls,
             sum(redacted_count) AS redacted_values,
             count(DISTINCT factory_id) AS factories,
             count(DISTINCT provider) AS providers,
             sum(CASE WHEN status_code >= 400 OR status_code IS NULL THEN 1 ELSE 0 END) AS failed
           FROM smart_bi_llm_egress_audit
           WHERE created_at > NOW() - INTERVAL '1 day' * $1""",
        days,
    )
    return {"window_days": days, "summary": rows[0] if rows else {}}


@router.get("/by-factory")
async def by_factory(days: int = Query(7, ge=1, le=90)) -> List[Dict[str, Any]]:
    """每工厂出境 + 脱敏统计 (给客户证明'你的数据出境前都脱敏了')。"""
    return await _fetch(
        """SELECT
             COALESCE(factory_id, '(system)') AS factory_id,
             count(*) AS egress_calls,
             sum(CASE WHEN sanitized THEN 1 ELSE 0 END) AS sanitized_calls,
             sum(redacted_count) AS redacted_values,
             count(DISTINCT provider) AS providers,
             max(created_at) AS last_egress_at
           FROM smart_bi_llm_egress_audit
           WHERE created_at > NOW() - INTERVAL '1 day' * $1
           GROUP BY factory_id
           ORDER BY egress_calls DESC NULLS LAST""",
        days,
    )


@router.get("/recent")
async def recent(limit: int = Query(200, ge=1, le=2000)) -> List[Dict[str, Any]]:
    return await _fetch(
        """SELECT created_at, call_site, slot, provider, model, factory_id,
             sanitized, redacted_count, redacted_fields, data_window,
             prompt_chars, prompt_sha256, status_code
           FROM smart_bi_llm_egress_audit
           ORDER BY created_at DESC
           LIMIT $1""",
        limit,
    )


@router.get("/export.csv")
async def export_csv(days: int = Query(30, ge=1, le=365)) -> StreamingResponse:
    """导出近 N 天出境审计为 CSV (合规/客户演示用). 不含 prompt 明文, 只含 sha256。"""
    rows = await _fetch(
        """SELECT created_at, call_site, slot, provider, model, factory_id,
             sanitized, redacted_count, redacted_fields, data_window,
             prompt_chars, prompt_sha256, status_code
           FROM smart_bi_llm_egress_audit
           WHERE created_at > NOW() - INTERVAL '1 day' * $1
           ORDER BY created_at DESC""",
        days,
    )
    buf = io.StringIO()
    cols = ["created_at", "call_site", "slot", "provider", "model", "factory_id",
            "sanitized", "redacted_count", "redacted_fields", "data_window",
            "prompt_chars", "prompt_sha256", "status_code"]
    writer = csv.DictWriter(buf, fieldnames=cols, extrasaction="ignore")
    writer.writeheader()
    for r in rows:
        row = dict(r)
        rf = row.get("redacted_fields")
        if isinstance(rf, list):
            row["redacted_fields"] = ",".join(str(x) for x in rf)
        writer.writerow(row)
    buf.seek(0)
    return StreamingResponse(
        iter([buf.getvalue()]),
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="llm-egress-audit-{days}d.csv"'},
    )
