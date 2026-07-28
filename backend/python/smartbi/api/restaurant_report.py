"""餐饮月度报告 HTTP 端点 (spec §3.2)。

三个端点，都挂在 ``/api/smartbi/restaurant/monthly-report`` 下:

* ``GET  /templates``  —— 列出可用模板（客户口径注册表落地前先是内置一份）
* ``POST /preview``    —— 只执行计划、返回 JSON（含数据截至时间），不渲染文件
* ``POST /export``     —— 执行计划 + 渲染 xlsx/pdf，直接下载

⚠️ 租户/RLS: 沿用 ``gold_reads`` 的 belt-and-suspenders 做法 —— 租户来自 JWT
(auth middleware 已 ``set_factory_id`` 到 ContextVar，asyncpg 池 setup 回调据此
``SET app.factory_id``)，body 里的 ``factory_id`` 只是双保险，不匹配就 403。
**不做 DEMO_REST → RES_3101_009 别名重映射**，与 tiered-answer 端点一致。

⛔ 拿不到数据一律 4xx/5xx + ``{success: false, message}``，绝不返回一个"能打开
但里面是占位数"的文件。
"""
from __future__ import annotations

import logging
from typing import Any, Dict, Optional
from urllib.parse import quote

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import Response
from pydantic import BaseModel

from smartbi.config import get_pg_pool
from smartbi.reporting import (
    REPORT_FORMATS,
    ReportGenerationError,
    build_monthly_report,
    generate_monthly_report_file,
    get_template,
    list_templates,
)
from smartbi.tenant_ctx import get_factory_id

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/restaurant/monthly-report", tags=["Restaurant Monthly Report"])


class MonthlyReportRequest(BaseModel):
    factory_id: Optional[str] = None
    # YYYY-MM。省略 = 数据截至日期所在的月份（不是"今天"所在的月份，
    # 见 runner.parse_period 的说明）。
    period: Optional[str] = None
    template_code: Optional[str] = None
    fmt: str = "xlsx"


def _resolve_tenant(factory_id: Optional[str]) -> str:
    tenant = get_factory_id()
    if not tenant:
        raise HTTPException(status_code=401, detail="tenant context not set")
    fid = factory_id or tenant
    if fid != tenant:
        raise HTTPException(
            status_code=403,
            detail=f"factory_id {fid!r} doesn't match tenant {tenant!r}",
        )
    return fid


def _role(request: Request) -> Optional[str]:
    return getattr(request.state, "role", None)


async def _pool():
    pool = await get_pg_pool()
    if not pool:
        # 不降级成"用缓存/空数据出报告" —— 直接说清楚。
        raise HTTPException(
            status_code=503,
            detail="数据库连接不可用，无法生成报告（不会返回占位数据）。",
        )
    return pool


def _failure_payload(exc: ReportGenerationError) -> Dict[str, Any]:
    return {"success": False, "message": exc.message, "data": exc.to_dict()}


@router.get("/templates")
async def list_report_templates() -> Dict[str, Any]:
    return {
        "success": True,
        "message": "ok",
        "data": {
            "templates": [
                {
                    "code": t.code,
                    "title": t.title,
                    "sections": [
                        {"key": s.key, "heading": s.heading, "query": s.query}
                        for s in t.sections
                    ],
                }
                for t in list_templates()
            ],
            "formats": list(REPORT_FORMATS),
        },
    }


@router.post("/preview")
async def preview_monthly_report(
    request: Request, body: MonthlyReportRequest,
) -> Dict[str, Any]:
    """执行计划并返回 JSON（不渲染文件）。

    用于前端先看一眼内容、或 cron 任务先探一次可用性再决定要不要导出。
    """
    fid = _resolve_tenant(body.factory_id)
    pool = await _pool()
    try:
        tpl = get_template(body.template_code)
    except KeyError:
        raise HTTPException(
            status_code=400, detail=f"未知报告模板 {body.template_code!r}",
        ) from None
    try:
        report = await build_monthly_report(
            pool, fid, _role(request), template=tpl, period=body.period,
        )
    except ReportGenerationError as exc:
        logger.info("[monthly-report] preview refused for %s: %s", fid, exc.code)
        return _failure_payload(exc)
    return {"success": True, "message": "ok", "data": report.to_dict()}


@router.post("/export")
async def export_monthly_report(request: Request, body: MonthlyReportRequest):
    """执行计划 + 渲染文件；成功返回文件流，失败返回 JSON 错误。"""
    fid = _resolve_tenant(body.factory_id)
    pool = await _pool()
    try:
        rendered = await generate_monthly_report_file(
            pool, fid, _role(request),
            fmt=body.fmt, period=body.period, template_code=body.template_code,
        )
    except ReportGenerationError as exc:
        logger.info("[monthly-report] export refused for %s: %s", fid, exc.code)
        # 200 + success:false 会让浏览器把错误 JSON 当文件存下来。用 422 让
        # 前端明确走错误分支。
        return Response(
            content=_json_bytes(_failure_payload(exc)),
            status_code=422,
            media_type="application/json; charset=utf-8",
        )
    filename = rendered.filename
    return Response(
        content=rendered.content,
        media_type=rendered.content_type,
        headers={
            "Content-Disposition": (
                f"attachment; filename*=UTF-8''{quote(filename)}"
            ),
            # 数据截至时间同时挂到响应头，前端不用解包文件就能提示用户。
            "X-Report-As-Of": rendered.report.freshness.as_of_date,
            "X-Report-Generated-At": rendered.report.freshness.generated_at,
        },
    )


def _json_bytes(payload: Dict[str, Any]) -> bytes:
    import json

    return json.dumps(payload, ensure_ascii=False).encode("utf-8")
