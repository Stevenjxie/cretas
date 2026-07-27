"""AI 飞轮运营台后端 API (卡5b) — 挂 `/api/smartbi/flywheel/*`.

实现卡5 (web-admin `/system/ai-flywheel`) 五个页面用的六个契约端点:

  GET  /overview             总览看板聚合 (问答量/档位分布/缓存命中率/晋升命中率/
                              token 估算/契约失败率/澄清率/👍👎 分布)
  GET  /candidates            晋升候选队列 (复用 restaurant_intent_promotion.
                              aggregate_candidates 的目标门控逻辑, 加富契约通过率
                              /最近真实答案/plan_json)
  POST /candidates/approve    一键通过 -> 落 `ai_promoted_routes` (卡2 建表)
  POST /candidates/reject     一键否决 -> 落否决账本 (复用 promo.reject_candidate)
  GET  /misses                 RESTAURANT_OPS_MISS 聚合 (复用 aggregate_misses)
  GET  /quality                 契约失败明细 + 👎 关联问答对
  POST /dataset/export           JSONL 训练对导出 (问句 → sealed plan → 反馈标签)

Spec: docs/superpowers/specs/2026-07-28-restaurant-ai-flywheel-reconnect-plan.md
  §P4 (五页面清单) + §1.5 (ai_promoted_routes 表定义) + 卡5/卡5b 分发卡正文。

依赖卡2 (并行): `ai_promoted_routes` 表由卡2 的 migration 建 (本模块禁止自建该
migration, 见卡5b 任务卡)。approve 端点对该表的 INSERT/UPSERT 用
`asyncpg.UndefinedTableError` 兜底 -> 503 + 明确提示, 不是静默假成功 (卡2 未
merge 时 approve 会 503, 这是预期行为, 不是 bug)。

Auth: 平台管理员权限 (`require_admin`, 复用 Sub-Project C 的共享 admin RBAC —
`smartbi/canonical/provenance/_admin_auth.py`, 同一套 platform_admin /
factory_super_admin / permission_admin 三档, 与 web-admin 路由 meta.roles 对齐)。

═══════════════════════════════════════════════════════════════════════════
RLS GUC 用法 (本卡任务卡明确写"终审专查" — 完整推导见
`smartbi/gold/restaurant_intent_promotion.py` 的 `_set_rls_guc` docstring,
这里复述结论 + 本文件的具体落点):

`smart_bi_llm_fallback_log` FORCE RLS 的 `tenant_select` 策略:
    factory_id = current_setting('app.factory_id', true)
    OR current_setting('app.factory_id', true) = ''
    OR current_setting('app.factory_id', true) IS NULL
GUC 为空/未设 = 放行全部租户行 (spec §P4 说的"管理员通道")。

但 `smartbi/tenant_ctx.py` 的 asyncpg 连接池 `setup` 回调
(`set_pg_connection_tenant`) 已经在**每次** `pool.acquire()` 时执行
`SELECT set_config('app.factory_id', fid, false)`, `fid` 取当前请求的
ambient ContextVar, 未设时的默认值是哨兵 `"__internal__"`
(`tenant_ctx.INTERNAL_SENTINEL`) —— **不是空串**。`'__internal__'` 三个分支
都不命中 (非空/非 NULL, 也没有真实行 factory_id='__internal__') —— 一个
platform_admin 请求如果 JWT 没带 factoryId (常见, 因为平台管理员不属于单一
工厂), 若本文件不做任何处理直接查询, 会静默拿到 0 行, 不是"全部行" ——
跟这个项目其它"假 0"事故同一根因, 只是多绕了一层 (池子确实设了 GUC, 只是
设成了错误的哨兵值)。

所以: 本文件每个平台级读端点 (`overview` / `candidates` / `misses` /
`quality` / `dataset/export`) 在 `pool.acquire()` 之后、查询之前, 都显式
`SELECT set_config('app.factory_id', '', false)` —— 显式重置为空串, 覆盖
连接池 setup 回调或上一个借用者可能残留的任何值 (`_admin_channel_guc`
helper, 见下)。`is_local` 第三参传 `false` (session-scoped), 不是 `true`:
本项目已验证 asyncpg 的 `is_local=true` (`SET LOCAL` 语义) 在跨
`acquire()`/`execute()` 步骤边界时不可靠生效 (memory
`feedback_asyncpg_local_setconfig_rls_never_applies`) —— `false` 与
`tenant_ctx.set_pg_connection_tenant` 自己的写法一致, 是这个连接池模型下唯一
确定生效的写法。

`candidates`/`misses` 两个端点复用 `restaurant_intent_promotion.
aggregate_candidates`/`aggregate_misses`, 传 `factory_id=None` 触发它们内部
同款的管理员通道 (`_set_rls_guc(conn, None)` -> reset to `''`)。
═══════════════════════════════════════════════════════════════════════════
"""
from __future__ import annotations

import json as _json
import logging
from typing import Any, Dict, List, Optional

import asyncpg
from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel, Field

from common.responses import success_response
from smartbi.canonical.provenance._admin_auth import require_admin

logger = logging.getLogger(__name__)

router = APIRouter(tags=["AI Flywheel Console"])

# ─── domain (spec §7 平台化: 新表新模块一律带 domain, 首发只有 restaurant) ──
_SUPPORTED_DOMAINS: Dict[str, str] = {
    # domain -> capture 表 template_code 前缀匹配 pattern (LIKE)。
    # capture 表本身没有 domain 列 (spec §7.1: "template_code 前缀天然可区分,
    # 不改") —— 域筛选在这五个读端点里全靠这个前缀映射。
    "restaurant": "RESTAURANT_OPS_%",
}


def _domain_prefix(domain: str) -> str:
    prefix = _SUPPORTED_DOMAINS.get(domain)
    if prefix is None:
        raise HTTPException(
            status_code=400,
            detail=f"暂不支持 domain={domain!r}（当前仅支持: {sorted(_SUPPORTED_DOMAINS)}）",
        )
    return prefix


async def _admin_channel_guc(conn) -> None:
    """Reset `app.factory_id` to `''` on `conn` — see module docstring's RLS
    GUC section for why this is required (not optional) before every
    platform-wide read in this file."""
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", "")


async def _get_pool_or_503():
    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="数据库连接池不可用")
    return pool


def _reviewed_by(request: Request) -> Optional[str]:
    """JWT `sub` claim, injected as `request.state.username` by
    `auth_middleware.JWTAuthMiddleware` (`scope['state']['username'] =
    claims.get('sub')`)."""
    return getattr(request.state, "username", None)


# ═══════════════════════════════════════════════════════════════════════
# GET /overview
# ═══════════════════════════════════════════════════════════════════════

# 粗估: T3 REVIEW 档单次调用 (静态块+餐饮域动态区 prompt + completion) 的
# token 量级占位常量 —— 尚未联调 smart_bi_llm_usage.caller 标签做真实按域
# token 归因 (该表有 caller/factory_id 列, 但当前 restaurant_intent.py 的 LLM
# 调用点没有打上可区分 caller 值, 无法可靠 JOIN)。`token_estimate` 字段和它
# 的 `_methodology` 说明并列返回, 让前端/复核者看得到这是估算而非实测 ——
# 对齐 spec 原文措辞"token 估算", 不是"token 实测"; 不是禁止降级返回假数据
# 规则要防的那种"假装是真实数据"的写法。
_AVG_TOKENS_PER_LLM_CALL = 2200
_TOKEN_ESTIMATE_METHODOLOGY = (
    f"llm_tier_count(窗口内 tier='llm' 的行数) × {_AVG_TOKENS_PER_LLM_CALL} "
    "(占位常量, 未联调 smart_bi_llm_usage 真实 token 归因) —— 估算非实测"
)


@router.get("/overview")
async def overview(
    request: Request,
    domain: str = Query("restaurant"),
    days: int = Query(7, ge=1, le=90),
) -> dict:
    require_admin(request, action_name="AI 飞轮总览看板")
    prefix = _domain_prefix(domain)
    pool = await _get_pool_or_503()

    async with pool.acquire() as conn:
        await _admin_channel_guc(conn)
        summary = await conn.fetchrow(
            """
            SELECT
              COUNT(*)                                                              AS total_queries,
              COUNT(*) FILTER (WHERE (agg_meta->>'served') = 'true')                 AS served_count,
              COUNT(*) FILTER (WHERE (agg_meta->>'contract_pass') = 'true')          AS contract_pass_count,
              COUNT(*) FILTER (WHERE (agg_meta->>'contract_pass') = 'false')         AS contract_fail_count,
              COUNT(*) FILTER (WHERE (agg_meta->>'clarification_needed') = 'true')   AS clarify_count,
              COUNT(*) FILTER (WHERE (agg_meta->>'tier') = 'llm')                    AS llm_tier_count,
              COUNT(*) FILTER (WHERE (agg_meta->>'tier') = 'cache')                  AS cache_tier_count,
              COUNT(*) FILTER (WHERE (agg_meta->>'planner_authority') = 'promoted_exact') AS promoted_hit_count,
              COUNT(*) FILTER (WHERE user_feedback = 1)                              AS thumbs_up,
              COUNT(*) FILTER (WHERE user_feedback = -1)                             AS thumbs_down
            FROM smart_bi_llm_fallback_log
            WHERE template_code LIKE $1
              AND created_at >= NOW() - make_interval(days => $2)
            """,
            prefix, days,
        )
        tier_rows = await conn.fetch(
            """
            SELECT COALESCE(agg_meta->>'tier', '(未标记)') AS tier, COUNT(*) AS n
              FROM smart_bi_llm_fallback_log
             WHERE template_code LIKE $1
               AND created_at >= NOW() - make_interval(days => $2)
             GROUP BY tier
             ORDER BY n DESC
            """,
            prefix, days,
        )

    total = int(summary["total_queries"] or 0) if summary else 0
    served = int(summary["served_count"] or 0) if summary else 0
    contract_pass = int(summary["contract_pass_count"] or 0) if summary else 0
    contract_fail = int(summary["contract_fail_count"] or 0) if summary else 0
    clarify = int(summary["clarify_count"] or 0) if summary else 0
    llm_tier = int(summary["llm_tier_count"] or 0) if summary else 0
    cache_tier = int(summary["cache_tier_count"] or 0) if summary else 0
    promoted_hits = int(summary["promoted_hit_count"] or 0) if summary else 0
    thumbs_up = int(summary["thumbs_up"] or 0) if summary else 0
    thumbs_down = int(summary["thumbs_down"] or 0) if summary else 0
    contract_checked = contract_pass + contract_fail

    def _rate(numer: int, denom: int) -> Optional[float]:
        return round(numer / denom, 4) if denom > 0 else None

    promoted_routes = await _read_promoted_routes_summary(domain)

    return success_response(
        data={
            "domain": domain,
            "window_days": days,
            "total_queries": total,
            "served_count": served,
            "served_rate": _rate(served, total),
            "cache_hit_count": cache_tier,
            "cache_hit_rate": _rate(cache_tier, total),
            "promoted_hit_count": promoted_hits,
            "promoted_hit_rate": _rate(promoted_hits, total),
            "llm_call_count": llm_tier,
            "contract_fail_count": contract_fail,
            "contract_fail_rate": _rate(contract_fail, contract_checked),
            "clarify_count": clarify,
            "clarify_rate": _rate(clarify, total),
            "feedback": {"thumbs_up": thumbs_up, "thumbs_down": thumbs_down},
            "tier_distribution": [
                {"tier": r["tier"], "count": int(r["n"] or 0)} for r in tier_rows
            ],
            "token_estimate": llm_tier * _AVG_TOKENS_PER_LLM_CALL,
            "token_estimate_methodology": _TOKEN_ESTIMATE_METHODOLOGY,
            "promoted_routes": promoted_routes,
        },
        message="总览看板聚合完成",
    )


async def _read_promoted_routes_summary(domain: str) -> Dict[str, Any]:
    """`ai_promoted_routes` (卡2 建表) 的 hit_count 汇总 -- 表可能尚不存在
    (卡2 未 merge), 用 `UndefinedTableError` 兜底而不是让整个 overview 500。
    显式 `available=False` 让前端知道这不是"晋升表里 0 条", 而是"表还没建"。"""
    try:
        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()
        if pool is None:
            return {"available": False, "reason": "pool_unavailable"}
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                SELECT COUNT(*) AS route_count, COALESCE(SUM(hit_count), 0) AS total_hits
                  FROM ai_promoted_routes
                 WHERE domain = $1
                """,
                domain,
            )
        return {
            "available": True,
            "route_count": int(row["route_count"] or 0) if row else 0,
            "total_hits": int(row["total_hits"] or 0) if row else 0,
        }
    except asyncpg.UndefinedTableError:
        return {"available": False, "reason": "ai_promoted_routes 表不存在（依赖卡2 migration，尚未 merge）"}
    except Exception as exc:  # fail-open, mirrors promotion module's philosophy
        logger.warning(f"[flywheel] _read_promoted_routes_summary failed (fail-open): {exc}")
        return {"available": False, "reason": str(exc)}


# ═══════════════════════════════════════════════════════════════════════
# GET /candidates
# ═══════════════════════════════════════════════════════════════════════

@router.get("/candidates")
async def list_candidates(
    request: Request,
    domain: str = Query("restaurant"),
    min_confidence: float = Query(0.75, ge=0.0, le=1.0),
    min_count: int = Query(1, ge=1),
    limit: int = Query(200, ge=1, le=1000),
) -> dict:
    require_admin(request, action_name="AI 飞轮晋升候选队列")
    if domain != "restaurant":
        # aggregate_candidates 的 SQL 目前硬编码 RESTAURANT_OPS_% 前缀
        # (对象门控逻辑本身是 restaurant 专属), 尚不支持其它 domain。
        _domain_prefix(domain)  # raises 400 with the standard message

    from smartbi.gold import restaurant_intent_promotion as promo

    pool = await _get_pool_or_503()
    base = await promo.aggregate_candidates(
        pool, min_confidence=min_confidence, min_count=min_count,
        limit=limit, factory_id=None,  # 管理员通道 (跨租户)
    )
    enriched = await _enrich_candidates(pool, base)

    return success_response(
        data={"domain": domain, "count": len(enriched), "candidates": enriched},
        message="候选队列查询完成",
    )


async def _enrich_candidates(pool, base: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Batch-fetch 契约通过率 + 最近真实答案 + 最近 plan_json (agg_meta) for
    the normalized queries `aggregate_candidates` returned -- one extra query
    for the whole page, not N+1 per candidate."""
    if not base:
        return []
    queries = [c["query"] for c in base]
    async with pool.acquire() as conn:
        await _admin_channel_guc(conn)
        rows = await conn.fetch(
            """
            SELECT trim(query)                                                     AS norm_query,
                   COUNT(*)                                                         AS total_count,
                   COUNT(*) FILTER (WHERE (agg_meta->>'contract_pass') = 'true')    AS pass_count,
                   (ARRAY_AGG(answer ORDER BY created_at DESC))[1]                  AS last_answer,
                   (ARRAY_AGG(agg_meta ORDER BY created_at DESC))[1]                AS last_plan_json
              FROM smart_bi_llm_fallback_log
             WHERE source = 'template'
               AND template_code LIKE 'RESTAURANT_OPS_%%'
               AND trim(query) = ANY($1::text[])
             GROUP BY trim(query)
            """,
            queries,
        )
    by_query = {r["norm_query"]: r for r in rows}
    out: List[Dict[str, Any]] = []
    for c in base:
        r = by_query.get(c["query"])
        total = int(r["total_count"] or 0) if r else 0
        passed = int(r["pass_count"] or 0) if r else 0
        plan_json = None
        if r and r["last_plan_json"]:
            try:
                plan_json = _json.loads(r["last_plan_json"]) if isinstance(r["last_plan_json"], str) else dict(r["last_plan_json"])
            except Exception:
                plan_json = None
        out.append({
            **c,
            "contract_pass_rate": round(passed / total, 4) if total > 0 else None,
            "last_answer_preview": (r["last_answer"] or "")[:500] if r else None,
            "plan_json": plan_json,
        })
    return out


# ═══════════════════════════════════════════════════════════════════════
# POST /candidates/approve
# ═══════════════════════════════════════════════════════════════════════

class ApproveCandidateRequest(BaseModel):
    domain: str = Field("restaurant")
    query: str = Field(..., min_length=1, max_length=500)
    code: str = Field(..., description="RESTAURANT_OPS_* 意图代码")
    scope: str = Field("global", description="'global' 或具体 factory_id")
    plan_json: Optional[Dict[str, Any]] = Field(
        None, description="不传则取该问法最近一条捕获行的 agg_meta 作为计划"
    )
    plan_version: Optional[str] = Field(None)


@router.post("/candidates/approve")
async def approve_candidate(request: Request, body: ApproveCandidateRequest) -> dict:
    require_admin(request, action_name="AI 飞轮候选通过")
    _domain_prefix(body.domain)

    from smartbi.gold.restaurant_intent import _VALID_CODES, _normalize_exact_phrase

    if body.code not in _VALID_CODES:
        raise HTTPException(status_code=400, detail=f"code={body.code!r} 不是有效的 RESTAURANT_OPS_* 代码")

    query = body.query.strip()
    if not query:
        raise HTTPException(status_code=400, detail="query 不能为空")
    normalized_phrase = _normalize_exact_phrase(query)

    pool = await _get_pool_or_503()

    plan_json = body.plan_json
    plan_version = body.plan_version
    if plan_json is None:
        async with pool.acquire() as conn:
            await _admin_channel_guc(conn)
            row = await conn.fetchrow(
                """
                SELECT agg_meta FROM smart_bi_llm_fallback_log
                 WHERE source = 'template' AND trim(query) = $1
                 ORDER BY created_at DESC LIMIT 1
                """,
                query,
            )
        if row and row["agg_meta"]:
            try:
                raw = _json.loads(row["agg_meta"]) if isinstance(row["agg_meta"], str) else dict(row["agg_meta"])
                plan_json = raw
                plan_version = plan_version or raw.get("plan_version")
            except Exception:
                plan_json = None
    plan_json = plan_json or {}
    plan_version = plan_version or "1"

    reviewed_by = _reviewed_by(request)
    try:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                INSERT INTO ai_promoted_routes
                    (domain, normalized_phrase, plan_json, plan_version, source, scope, reviewed_by, hit_count, created_at)
                VALUES ($1, $2, $3::jsonb, $4, 'flywheel', $5, $6, 0, NOW())
                ON CONFLICT (domain, normalized_phrase) DO UPDATE
                   SET plan_json = EXCLUDED.plan_json,
                       plan_version = EXCLUDED.plan_version,
                       scope = EXCLUDED.scope,
                       reviewed_by = EXCLUDED.reviewed_by
                RETURNING domain, normalized_phrase, hit_count
                """,
                body.domain, normalized_phrase, _json.dumps(plan_json, ensure_ascii=False, default=str),
                plan_version, body.scope, reviewed_by,
            )
    except asyncpg.UndefinedTableError:
        raise HTTPException(
            status_code=503,
            detail="ai_promoted_routes 表不存在（依赖卡2 migration，尚未 merge，approve 暂不可用）",
        )
    except Exception as exc:
        logger.error(f"[flywheel] approve_candidate insert failed: {exc}")
        raise HTTPException(status_code=500, detail="写入 ai_promoted_routes 失败")

    return success_response(
        data={
            "domain": body.domain,
            "normalized_phrase": normalized_phrase,
            "code": body.code,
            "scope": body.scope,
            "hit_count": int(row["hit_count"] or 0) if row else 0,
        },
        message="候选已通过并写入晋升表",
    )


# ═══════════════════════════════════════════════════════════════════════
# POST /candidates/reject
# ═══════════════════════════════════════════════════════════════════════

class RejectCandidateRequest(BaseModel):
    domain: str = Field("restaurant")
    query: str = Field(..., min_length=1, max_length=500)
    reason: str = Field(..., min_length=1, max_length=500)


@router.post("/candidates/reject")
async def reject_candidate(request: Request, body: RejectCandidateRequest) -> dict:
    require_admin(request, action_name="AI 飞轮候选否决")
    _domain_prefix(body.domain)

    from smartbi.gold import restaurant_intent_promotion as promo

    result = promo.reject_candidate(
        body.query, body.reason, rejected_by=_reviewed_by(request),
    )
    if not result.get("ok"):
        raise HTTPException(status_code=400, detail=f"否决失败: {result.get('reason', '未知原因')}")

    return success_response(
        data=result,
        message=(
            "已否决（已存在，未重复写入）" if result.get("already_rejected")
            else "已否决，已写入否决账本（rsync 部署前需人工 commit 该文件，见响应 durable=false）"
        ),
    )


# ═══════════════════════════════════════════════════════════════════════
# GET /misses
# ═══════════════════════════════════════════════════════════════════════

@router.get("/misses")
async def list_misses(
    request: Request,
    domain: str = Query("restaurant"),
    limit: int = Query(200, ge=1, le=1000),
) -> dict:
    require_admin(request, action_name="AI 飞轮 Miss 复盘")
    if domain != "restaurant":
        _domain_prefix(domain)

    from smartbi.gold import restaurant_intent_promotion as promo

    pool = await _get_pool_or_503()
    misses = await promo.aggregate_misses(pool, limit=limit, factory_id=None)

    return success_response(
        data={"domain": domain, "count": len(misses), "misses": misses},
        message="Miss 聚合查询完成",
    )


# ═══════════════════════════════════════════════════════════════════════
# GET /quality
# ═══════════════════════════════════════════════════════════════════════

@router.get("/quality")
async def quality(
    request: Request,
    domain: str = Query("restaurant"),
    limit: int = Query(100, ge=1, le=1000),
) -> dict:
    require_admin(request, action_name="AI 飞轮质量与回归")
    prefix = _domain_prefix(domain)
    pool = await _get_pool_or_503()

    async with pool.acquire() as conn:
        await _admin_channel_guc(conn)
        contract_failures = await conn.fetch(
            """
            SELECT id, query, LEFT(answer, 500) AS answer_preview,
                   agg_meta->>'tier' AS tier, agg_meta->>'spec_intent' AS spec_intent,
                   factory_id, created_at
              FROM smart_bi_llm_fallback_log
             WHERE template_code LIKE $1
               AND (agg_meta->>'contract_pass') = 'false'
             ORDER BY created_at DESC
             LIMIT $2
            """,
            prefix, limit,
        )
        negative_feedback = await conn.fetch(
            """
            SELECT id, query, LEFT(answer, 500) AS answer_preview,
                   feedback_comment, factory_id, created_at
              FROM smart_bi_llm_fallback_log
             WHERE template_code LIKE $1
               AND user_feedback = -1
             ORDER BY created_at DESC
             LIMIT $2
            """,
            prefix, limit,
        )

    return success_response(
        data={
            "domain": domain,
            "contract_failures": [dict(r) for r in contract_failures],
            "negative_feedback": [dict(r) for r in negative_feedback],
        },
        message="质量明细查询完成",
    )


# ═══════════════════════════════════════════════════════════════════════
# POST /dataset/export
# ═══════════════════════════════════════════════════════════════════════

class DatasetExportRequest(BaseModel):
    domain: str = Field("restaurant")
    days: Optional[int] = Field(None, ge=1, le=365, description="不传则不按时间窗过滤")
    contract_pass: Optional[bool] = Field(None)
    served: Optional[bool] = Field(None)
    feedback: Optional[int] = Field(None, description="1=👍 / -1=👎，不传则不筛反馈")
    limit: int = Field(5000, ge=1, le=20000)


@router.post("/dataset/export")
async def export_dataset(request: Request, body: DatasetExportRequest) -> dict:
    require_admin(request, action_name="AI 飞轮蒸馏数据集导出")
    prefix = _domain_prefix(body.domain)
    pool = await _get_pool_or_503()

    conditions = ["template_code LIKE $1"]
    args: List[Any] = [prefix]

    if body.days is not None:
        args.append(body.days)
        conditions.append(f"created_at >= NOW() - make_interval(days => ${len(args)})")
    if body.contract_pass is not None:
        args.append("true" if body.contract_pass else "false")
        conditions.append(f"(agg_meta->>'contract_pass') = ${len(args)}")
    if body.served is not None:
        args.append("true" if body.served else "false")
        conditions.append(f"(agg_meta->>'served') = ${len(args)}")
    if body.feedback is not None:
        args.append(body.feedback)
        conditions.append(f"user_feedback = ${len(args)}")

    args.append(body.limit)
    sql = f"""
        SELECT query, answer, agg_meta, user_feedback, feedback_comment, created_at
          FROM smart_bi_llm_fallback_log
         WHERE {' AND '.join(conditions)}
         ORDER BY created_at DESC
         LIMIT ${len(args)}
    """

    async with pool.acquire() as conn:
        await _admin_channel_guc(conn)
        rows = await conn.fetch(sql, *args)

    lines: List[str] = []
    for r in rows:
        try:
            agg_meta = _json.loads(r["agg_meta"]) if isinstance(r["agg_meta"], str) else (dict(r["agg_meta"]) if r["agg_meta"] else {})
        except Exception:
            agg_meta = {}
        # plan = agg_meta 减去纯基础设施字段 (tier/confidence/served/contract_pass
        # 是捕获元数据, 不是"计划"的一部分) -- 剩下的字段 (requested_metrics /
        # planned_intents / dimensions / dish_slot / store_scope / ... ) 才是
        # 问句实际解析出的语义计划, 这是训练对的核心。
        infra_keys = {"tier", "confidence", "served", "contract_pass", "source"}
        plan = {k: v for k, v in agg_meta.items() if k not in infra_keys}
        record = {
            "query": r["query"],
            "plan": plan,
            "answer": r["answer"],
            "feedback_label": r["user_feedback"],
            "feedback_comment": r["feedback_comment"],
            "contract_pass": agg_meta.get("contract_pass"),
            "created_at": r["created_at"].isoformat() if r["created_at"] else None,
        }
        lines.append(_json.dumps(record, ensure_ascii=False, default=str))

    jsonl = "\n".join(lines)
    return success_response(
        data={"domain": body.domain, "count": len(lines), "jsonl": jsonl},
        message=f"导出 {len(lines)} 条训练对",
    )
