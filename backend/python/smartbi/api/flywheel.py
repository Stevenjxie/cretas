"""AI 飞轮运营台后端 API (卡5b) — 挂 `/api/smartbi/flywheel/*`.

实现卡5 (web-admin `/system/ai-flywheel`) 五个页面用的契约端点 (六个原始
契约 + 2026-07-28 补的两个卡5 前端补充契约):

  GET  /overview                 总览看板聚合 (问答量/档位分布/缓存命中率/晋升命中率/
                                  token 估算/契约失败率/澄清率/👍👎 分布)
  GET  /candidates                晋升候选队列 (复用 restaurant_intent_promotion.
                                  aggregate_candidates 的目标门控逻辑, 加富契约通过率
                                  /最近真实答案/plan_json)
  POST /candidates/approve        一键通过 -> 落 `ai_promoted_routes` (卡2 建表)
  POST /candidates/reject         一键否决 -> 落否决账本 (复用 promo.reject_candidate)
  POST /candidates/seed-import    manual_seed 批量导入 (补充契约, 逐条人审入表,
                                  不在端点内跑 LLM 出计划——见该端点 docstring)
  GET  /misses                     RESTAURANT_OPS_MISS 聚合 (复用 aggregate_misses),
                                  附带处理状态 (见 /misses/status)
  POST /misses/status              miss 复盘处理状态标注 (补充契约, 落
                                  MISS_STATUS_FILE)
  GET  /quality                     契约失败明细 + 👎 关联问答对
  POST /dataset/export               JSONL 训练对导出 (问句 → sealed plan → 反馈标签)

Spec: docs/superpowers/specs/2026-07-28-restaurant-ai-flywheel-reconnect-plan.md
  §P4 (五页面清单) + §1.5 (ai_promoted_routes 表定义) + 卡5/卡5b 分发卡正文。

依赖卡2 (并行): `ai_promoted_routes` 表由卡2 的 migration 建 (本模块禁止自建该
migration, 见卡5b 任务卡)。approve 端点对该表的 INSERT/UPSERT 用
`asyncpg.UndefinedTableError` 兜底 -> 503 + 明确提示, 不是静默假成功 (卡2 未
merge 时 approve 会 503, 这是预期行为, 不是 bug)。

Auth: **仅 `platform_admin`**（`_require_platform_admin`，本文件内定义，见其
docstring）。不是 Sub-Project C 共享的 `require_admin`（那个函数放行
`platform_admin` / `factory_super_admin` / `permission_admin` 三档 admin-tier
角色——那个宽度对"调用者传 factory_id、按 ID 校验越权"的端点形状是对的，
但本文件六个读端点没有 factory_id 参数、`_admin_channel_guc` 无条件把 GUC
清空成 `''` 看全部租户，若 `factory_super_admin`/`permission_admin`（单工厂
角色）也能通过，就是跨租户数据泄漏——2026-07-28 审查发现的阻断项，修复
见下方 `_require_platform_admin`）。与 web-admin 侧路由 `meta.roles:
['platform_admin']`（卡5 UI 早已限定）对齐，双端一致收窄，不留后门。

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

logger = logging.getLogger(__name__)

router = APIRouter(tags=["AI Flywheel Console"])


def _require_platform_admin(request: Request, *, action_name: str) -> None:
    """卡5b 专用鉴权gate — 只放行 `platform_admin`. 2026-07-28 审查发现的阻断项修复:

    之前直接复用 `smartbi.canonical.provenance._admin_auth.require_admin`,
    它的 `ADMIN_ROLES` 是三档 admin-tier (`platform_admin` /
    `factory_super_admin` / `permission_admin`) 都放行 -- 对 Sub-Project C
    那批"调用者传一个 factory_id 参数, 校验是否越权访问别的工厂"形状的端点
    是对的 (越权检查在 `require_factory_scope` 里, 按传入的 ID 比对)。

    但本文件六个端点根本不接受 factory_id 参数 -- 它们是运营台的"平台级
    聚合视图", `_admin_channel_guc`/`aggregate_candidates(factory_id=None)`
    无条件把 RLS GUC 清空成 `''`, 对 `smart_bi_llm_fallback_log` 这类 FORCE
    RLS 表等于"看全部租户"。`factory_super_admin`/`permission_admin` 语义上
    是单工厂角色, 如果被这里放行, 就能看到/导出全部租户的问句、答案、
    `feedback_comment`(负反馈原文常含运营敏感信息) -- 跨租户泄漏, 不是新
    风险类别: 与 `smartbi/api/data_quality_queue_admin.py`(Phase B 修复,
    "之前 require_admin 接受任意 admin tier 导致 F002 admin 能查 R_BEJ
    data") 同一漏洞形状, 唯一区别是那边端点接受 factory_id 参数、用
    `role != 'platform_admin' and factory_id 不等` 比对, 而这里的端点没有
    factory_id 参数可比 -- 干脆直接锁角色, 不给 factory_super_admin/
    permission_admin 开口子 (这是运营团队/organizer 拍板的选择: 卡5 web-admin
    路由 `meta.roles` 本就只写了 `['platform_admin']`, 双端一致最简单也最
    不容易漏改一处再出同款漏洞)。

    `auth_method == 'internal'` (Java -> Python 内部调用) 仍然直接放行 --
    与 `require_admin` 的既有约定一致, 内部调用不受角色门限制。
    """
    role = getattr(request.state, "role", None)
    auth_method = getattr(request.state, "auth_method", None)
    if auth_method == "internal":
        return
    if role is None:
        raise HTTPException(status_code=401, detail="未登录或会话已过期")
    if role != "platform_admin":
        raise HTTPException(
            status_code=403,
            detail=(
                f"{action_name}仅限平台管理员 (platform_admin) 访问 "
                f"(当前角色 {role!r} 无权访问 -- 本模块所有读端点跨租户, "
                f"factory_super_admin/permission_admin 不予放行以防跨租户数据泄漏)"
            ),
        )

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
    _require_platform_admin(request, action_name="AI 飞轮总览看板")
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
              COUNT(*) FILTER (
                WHERE (agg_meta->>'tier') = 'exact'
                  AND (agg_meta->>'planner_authority') = 'promoted_exact'
              )                                                                        AS promoted_hit_count,
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

    # 2026-07-28 追加修复: `promoted_hit_count`/`promoted_hit_rate` 上面这两个
    # 字段的数据来源是 capture 表 (`smart_bi_llm_fallback_log.agg_meta`),
    # **不是** `ai_promoted_routes.hit_count` —— 卡2 终审确认 hit_count 目前
    # 恒为 0 (卡2 有意不在热路径写它: 读路径上做写 + global 行 UPDATE 需要
    # 开 RLS 口子, 这个取舍是对的, 不要求卡2 改)。真实的"晋升命中"信号是
    # 回放路径命中晋升表时打的两个 agg_meta 标记 (`tier='exact'` 且
    # `planner_authority='promoted_exact'`, 卡2 给的部署核对标记) —— 上面
    # SQL 的 promoted_hit_count 就是数这个, 是真实数据, 不依赖 hit_count。
    # `_read_promoted_routes_summary` 里单独返回 `ai_promoted_routes` 表本身
    # 的 route_count (真实, 表里有多少条晋升配置), 但不再返回 SUM(hit_count)
    # (恒 0 的假指标, 见该函数 docstring)。
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
    显式 `available=False` 让前端知道这不是"晋升表里 0 条", 而是"表还没建"。

    ⚠️ 2026-07-28 审查发现: 本函数之前完全没设 GUC, 靠"侥幸" —— `overview`
    调用者收窄到 platform_admin 之前, `factory_super_admin` 也能到这里, 其
    JWT 常带自己的 factory_id, ambient GUC (连接池 setup 回调的默认值)
    就会是那个具体工厂, 而不是 `'__internal__'`——SELECT 会悄悄只统计一个
    工厂的 `ai_promoted_routes` 行, 却仍然返回 `available: True`, 变成"数据
    不完整但看起来正常"这种最难发现的 bug。现在调用者已锁定 platform_admin
    (`_require_platform_admin`), 但仍然显式设 GUC, 不依赖调用方角色间接保证:
    这张表是纯平台级晋升配置 (不是 per-tenant capture 数据), 显式重置为
    `tenant_ctx.INTERNAL_SENTINEL` ('__internal__') 而不是 `''` —— 空串是
    `smart_bi_llm_fallback_log` 那类 tenant_select 策略"放行全部租户"的哨兵,
    `ai_promoted_routes` 语义上不是"多租户行的集合", 用 `__internal__` 这个
    项目既有的"平台级/内部调用"哨兵更准确 (与 `tenant_ctx.
    set_pg_connection_tenant` 对无租户上下文请求的默认值一致)。

    ⚠️ 2026-07-28 追加修复 (organizer 转达卡2 终审确认): `ai_promoted_routes.
    hit_count` 目前**恒为 0** —— 没有任何代码在回放命中晋升表时更新它 (卡2
    有意不在热路径写: 那是读路径上做写, 且 global 行 UPDATE 需要开一个 RLS
    写口子, 这个取舍是对的, 不要求卡2 改)。因此本函数**不再返回
    `SUM(hit_count)`** —— 一个永远是 0 的数字包装成"晋升命中"指标展示出去,
    等于诚实到毫米原则要防的"看起来正常但是假的"。真实的晋升命中信号见
    `overview` 顶层的 `promoted_hit_count`/`promoted_hit_rate`
    (来自 capture 表 `agg_meta.tier='exact'` + `planner_authority=
    'promoted_exact'` 的真实统计, 不依赖这张表的 hit_count 列)。这里只返回
    `route_count` (真实 -- 这张表里实际有多少条被人审通过的晋升配置) 和一个
    `hit_count_instrumented: False` 标记, 让前端/复核者能从响应本身看出
    hit_count 未接线, 而不是只能从代码注释里才知道。"""
    try:
        from smartbi.config import get_pg_pool
        from smartbi.tenant_ctx import INTERNAL_SENTINEL
        pool = await get_pg_pool()
        if pool is None:
            return {"available": False, "reason": "pool_unavailable"}
        async with pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", INTERNAL_SENTINEL
            )
            row = await conn.fetchrow(
                """
                SELECT COUNT(*) AS route_count
                  FROM ai_promoted_routes
                 WHERE domain = $1
                """,
                domain,
            )
        return {
            "available": True,
            "route_count": int(row["route_count"] or 0) if row else 0,
            "hit_count_instrumented": False,
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
    _require_platform_admin(request, action_name="AI 飞轮晋升候选队列")
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


async def _insert_promoted_route(
    conn, *, domain: str, normalized_phrase: str, plan_json: Dict[str, Any],
    plan_version: str, source: str, scope: str, reviewed_by: Optional[str],
):
    """Shared INSERT ... ON CONFLICT DO UPDATE for `ai_promoted_routes`, used
    by both `/candidates/approve` (source='flywheel') and
    `/candidates/seed-import` (source='manual_seed'). Caller must have
    already called `_admin_channel_guc(conn)` on this connection (see
    `approve_candidate`'s comment for why the admin/global write channel is
    correct here — approve/seed-import are inherently cross-tenant actions).

    Raises `asyncpg.UndefinedTableError` / `asyncpg.exceptions.
    InsufficientPrivilegeError` uncaught -- callers translate those to the
    appropriate HTTP/per-entry response (single-entry `approve_candidate`
    aborts the whole request; batched `seed_import_candidates` catches per
    entry so one bad row doesn't sink the whole paste)."""
    return await conn.fetchrow(
        """
        INSERT INTO ai_promoted_routes
            (domain, normalized_phrase, plan_json, plan_version, source, scope, reviewed_by, hit_count, created_at)
        VALUES ($1, $2, $3::jsonb, $4, $5, $6, $7, 0, NOW())
        ON CONFLICT (domain, normalized_phrase) DO UPDATE
           SET plan_json = EXCLUDED.plan_json,
               plan_version = EXCLUDED.plan_version,
               source = EXCLUDED.source,
               scope = EXCLUDED.scope,
               reviewed_by = EXCLUDED.reviewed_by
        RETURNING domain, normalized_phrase, hit_count
        """,
        domain, normalized_phrase, _json.dumps(plan_json, ensure_ascii=False, default=str),
        plan_version, source, scope, reviewed_by,
    )


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
    _require_platform_admin(request, action_name="AI 飞轮候选通过")
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
            # 2026-07-28 审查发现: 这个 acquire() 之前也没显式设 GUC —— 若
            # ambient GUC (连接池 setup 回调默认值) 恰好是调用者自己的
            # factory_id (即使调用者现在已锁定 platform_admin, 其 JWT 仍可能
            # 带一个 factory_id), 而 body.scope 默认是 'global' (不等于那个
            # factory_id), INSERT 就可能撞上 `ai_promoted_routes` 的 RLS
            # WITH CHECK (若卡2 建的表带类似 V20260502_05 那种"GUC 为空/未设
            # 或等于目标行租户列"策略) 而失败, 之前被下面的通用 except
            # Exception 兜底成语义不清的 500。显式设为 admin 通道 ('', 空串)
            # ——写 scope='global' 或任意具体 factory_id 都不应受当前调用者
            # 租户限制 (approve 本身就是跨租户操作: 一个 platform_admin 审核
            # 通过的问法可以属于任何工厂或全局)。
            await _admin_channel_guc(conn)
            try:
                row = await _insert_promoted_route(
                    conn, domain=body.domain, normalized_phrase=normalized_phrase,
                    plan_json=plan_json, plan_version=plan_version, source="flywheel",
                    scope=body.scope, reviewed_by=reviewed_by,
                )
            except asyncpg.exceptions.InsufficientPrivilegeError as exc:
                # RLS WITH CHECK 违规的标准 asyncpg 映射 (SQLSTATE 42501,
                # "new row violates row-level security policy") —— 显式 403,
                # 不要糊进下面的通用 500 分支 (那样调用者分不清"服务器错误"
                # 还是"这条写入本来就不该被允许")。
                logger.warning(f"[flywheel] approve_candidate RLS violation: {exc}")
                raise HTTPException(
                    status_code=403,
                    detail=f"写入 ai_promoted_routes 被 RLS 拒绝 (scope={body.scope!r} 与当前租户上下文不匹配): {exc}",
                )
    except HTTPException:
        raise
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
    _require_platform_admin(request, action_name="AI 飞轮候选否决")
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
# POST /candidates/seed-import (卡5 前端补充契约: manual_seed 批量导入)
# ═══════════════════════════════════════════════════════════════════════
#
# spec §1.5 / §P4 item 2: "manual_seed 来源: 客户提供的常问问题清单 → 离线
# 批量跑一次 LLM 出计划 → 人审 → 落表"。"离线批量跑 LLM 出计划" 那一步不在
# 这个端点里发生（不适合同步 HTTP 调用一次性跑几十条 LLM 请求，也不该由这个
# 端点自己臆造 plan_json——那会违反"禁止降级返回假数据"：没有真实 LLM
# parse 结果时绝不能自己拼一个假计划充数）。这个端点对应的是流程最后一步
# "逐条人审入表"：调用方（人工审核完毕后的 web UI，或离线批跑脚本的输出）
# 为每条问法都带上已经跑出来、已经人审过的 plan_json，本端点只做批量校验
# + 落 `ai_promoted_routes`（source='manual_seed'，与 approve 的
# source='flywheel' 区分晋升来源）。逐条独立处理、一条失败不拖累其余条目，
# 响应里同时报 added/skipped，镜像 `apply_promotions` 的既有设计哲学。

class SeedImportEntry(BaseModel):
    query: str = Field(..., min_length=1, max_length=500)
    code: str = Field(..., description="RESTAURANT_OPS_* 意图代码")
    plan_json: Dict[str, Any] = Field(
        ..., description="离线批跑 LLM 出的计划 (必填——本端点不臆造计划)"
    )
    scope: str = Field("global")
    plan_version: Optional[str] = Field(None)


class SeedImportRequest(BaseModel):
    domain: str = Field("restaurant")
    entries: List[SeedImportEntry] = Field(..., min_length=1, max_length=200)


@router.post("/candidates/seed-import")
async def seed_import_candidates(request: Request, body: SeedImportRequest) -> dict:
    _require_platform_admin(request, action_name="AI 飞轮 manual_seed 批量导入")
    _domain_prefix(body.domain)

    from smartbi.gold.restaurant_intent import _VALID_CODES, _normalize_exact_phrase

    reviewed_by = _reviewed_by(request)
    pool = await _get_pool_or_503()

    added: List[Dict[str, Any]] = []
    skipped: List[Dict[str, Any]] = []
    for entry in body.entries:
        query = entry.query.strip()
        if not query:
            skipped.append({"query": entry.query, "reason": "empty_query"})
            continue
        if entry.code not in _VALID_CODES:
            skipped.append({"query": query, "reason": f"invalid_code:{entry.code!r}"})
            continue
        normalized_phrase = _normalize_exact_phrase(query)
        plan_version = entry.plan_version or (entry.plan_json.get("plan_version") if isinstance(entry.plan_json, dict) else None) or "1"
        try:
            async with pool.acquire() as conn:
                await _admin_channel_guc(conn)  # 同 approve_candidate: 批量导入是跨租户操作
                try:
                    row = await _insert_promoted_route(
                        conn, domain=body.domain, normalized_phrase=normalized_phrase,
                        plan_json=entry.plan_json, plan_version=plan_version,
                        source="manual_seed", scope=entry.scope, reviewed_by=reviewed_by,
                    )
                except asyncpg.exceptions.InsufficientPrivilegeError as exc:
                    skipped.append({"query": query, "reason": f"rls_denied: {exc}"})
                    continue
        except asyncpg.UndefinedTableError:
            # 表不存在是全局性的（不是某一条的问题）——不必逐条重复报同一件事,
            # 整个请求直接 503，比把 200 条同样的 skipped 塞进响应更清楚。
            raise HTTPException(
                status_code=503,
                detail="ai_promoted_routes 表不存在（依赖卡2 migration，尚未 merge，seed-import 暂不可用）",
            )
        except Exception as exc:
            logger.error(f"[flywheel] seed_import_candidates entry failed (query={query!r}): {exc}")
            skipped.append({"query": query, "reason": f"insert_failed: {exc}"})
            continue
        added.append({
            "query": query, "code": entry.code, "normalized_phrase": normalized_phrase,
            "hit_count": int(row["hit_count"] or 0) if row else 0,
        })

    return success_response(
        data={"domain": body.domain, "added": added, "skipped": skipped,
              "added_count": len(added), "skipped_count": len(skipped)},
        message=f"批量导入完成: {len(added)} 条通过, {len(skipped)} 条跳过",
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
    _require_platform_admin(request, action_name="AI 飞轮 Miss 复盘")
    if domain != "restaurant":
        _domain_prefix(domain)

    from smartbi.gold import restaurant_intent_promotion as promo

    pool = await _get_pool_or_503()
    misses = await promo.aggregate_misses(pool, limit=limit, factory_id=None)

    # 卡5 前端补充契约: 每条 miss 附带处理状态 (默认 'unreviewed' -- 未在
    # MISS_STATUS_FILE 里出现的问法就是还没被人工标注过)。
    status_map = promo.load_miss_status()
    for m in misses:
        entry = status_map.get(m["query"])
        m["status"] = entry["status"] if entry else "unreviewed"
        m["status_note"] = entry.get("note") if entry else None
        m["status_updated_at"] = entry.get("updated_at") if entry else None

    return success_response(
        data={"domain": domain, "count": len(misses), "misses": misses},
        message="Miss 聚合查询完成",
    )


# ═══════════════════════════════════════════════════════════════════════
# POST /misses/status (卡5 前端补充契约: miss 复盘处理状态标注)
# ═══════════════════════════════════════════════════════════════════════

class MissStatusRequest(BaseModel):
    domain: str = Field("restaurant")
    query: str = Field(..., min_length=1, max_length=500)
    status: str = Field(..., description=f"允许值: {sorted(['unreviewed', 'planned', 'wontfix', 'duplicate', 'resolved'])}")
    note: Optional[str] = Field(None, max_length=1000)


@router.post("/misses/status")
async def set_miss_status(request: Request, body: MissStatusRequest) -> dict:
    _require_platform_admin(request, action_name="AI 飞轮 Miss 状态标注")
    _domain_prefix(body.domain)

    from smartbi.gold import restaurant_intent_promotion as promo

    result = promo.set_miss_status(
        body.query, body.status, note=body.note, reviewed_by=_reviewed_by(request),
    )
    if not result.get("ok"):
        raise HTTPException(status_code=400, detail=f"状态标注失败: {result.get('reason', '未知原因')}")

    return success_response(
        data=result,
        message="已标注处理状态（rsync 部署前需人工 commit 该文件，见响应 durable=false）",
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
    _require_platform_admin(request, action_name="AI 飞轮质量与回归")
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
    _require_platform_admin(request, action_name="AI 飞轮蒸馏数据集导出")
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
