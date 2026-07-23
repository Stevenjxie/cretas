# -*- coding: utf-8 -*-
"""餐饮 AI 回答 👍/👎 反馈 (飞轮断点 2 修补, 2026-07-23)。

统一收口: 不管回答来自哪条路 (Java execute→tiered delegate / chat.py tiered /
synthesis 直连), 前端只带 (问法原文, value, comment) 打这一个端点 —— 按
(租户, trim(query)) 找 smart_bi_llm_fallback_log 里最近一条捕获行, UPDATE 其
user_feedback / feedback_comment; 找不到 (synthesis 直连没有 tiered 捕获行)
就 INSERT 一条独立反馈行 (template_code='RESTAURANT_FEEDBACK'), 保证反馈信号
永不丢失。晋升 CLI / 复盘查询读同一张表, 反馈直接给晋升评审加证据
(👍 = 答案被真人确认, 👎 + comment = 待查线索)。

为什么用 (query, 最近一条) 关联而不是 log_id: log_id 需要把捕获 id 从
fire-and-forget 任务一路穿透 Java IntentExecuteResponse → 前端, 三层改动;
按问法关联零穿透, 语义是"用户对这句话最近一次的回答表态", 重复问法更新
最新行恰好正确。

Auth: JWT (request.state.factory_id, auth_middleware 注入)。demo 账号可写 —
auth_middleware 的 DEMO_WRITE_ALLOW_SUFFIXES 含 '/feedback' (反馈是信号
数据, 对齐 Java 侧 Sprint 11 Round 2 同款决策)。
"""
from __future__ import annotations

import logging
from typing import Optional

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/smartbi/restaurant", tags=["Restaurant Feedback"])


class RestaurantFeedbackRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=500, description="用户问法原文")
    value: int = Field(..., description="1=👍 / -1=👎")
    comment: Optional[str] = Field(None, max_length=1000, description="👎 时的补充说明")


@router.post("/feedback")
async def post_restaurant_feedback(request: Request, body: RestaurantFeedbackRequest) -> dict:
    if body.value not in (1, -1):
        raise HTTPException(status_code=400, detail="value must be 1 or -1")
    fid = getattr(request.state, "factory_id", None)
    if not fid:
        raise HTTPException(status_code=401, detail="tenant context not set")

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="database not available")

    query_text = body.query.strip()
    # user_feedback 列是 integer (AIQuery 的 logFeedback 同款 1/-1 语义),
    # 不是文本 — 首版误写 'up'/'down' 被 asyncpg 类型检查当场拒 (2026-07-23)。
    feedback = body.value
    try:
        async with pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", fid
            )
            updated = await conn.fetchval(
                """
                UPDATE smart_bi_llm_fallback_log
                   SET user_feedback = $3,
                       feedback_comment = $4
                 WHERE id = (
                        SELECT id FROM smart_bi_llm_fallback_log
                         WHERE factory_id = $1 AND trim(query) = $2
                         ORDER BY created_at DESC LIMIT 1
                       )
                RETURNING id
                """,
                fid, query_text, feedback, body.comment,
            )
            if updated is None:
                # synthesis 直连的回答没有 tiered 捕获行 — 反馈自成一行,
                # 信号不丢 (agg_meta.source 标记孤儿反馈, 复盘可区分)。
                updated = await conn.fetchval(
                    """
                    INSERT INTO smart_bi_llm_fallback_log
                        (query, factory_id, template_code, answer, source,
                         user_feedback, feedback_comment, agg_meta,
                         total_wall_ms, llm_wall_ms)
                    VALUES ($1, $2, 'RESTAURANT_FEEDBACK', '', 'template',
                            $3, $4, '{"source": "user_feedback_orphan"}'::jsonb,
                            0, 0)
                    RETURNING id
                    """,
                    query_text, fid, feedback, body.comment,
                )
    except HTTPException:
        raise
    except Exception as exc:
        logger.warning(f"[restaurant-feedback] write failed: {exc}")
        raise HTTPException(status_code=500, detail="feedback write failed")

    return {"success": True, "id": updated}
