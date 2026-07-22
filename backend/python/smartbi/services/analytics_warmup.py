"""Background warmup: pre-populate Agent Insights cache after new upload.

Customer uploads xlsx → materialize_upload succeeds → this kicks off as async
task → concurrently calls LLM for standard "Dashboard AI 洞察" questions so
that when customer opens Dashboard 30-60s later, results are cache-hit
(6ms) instead of cold LLM (10-30s each).

Design:
- Fire-and-forget. Failures logged, don't break materialize.
- Concurrency limited (4 parallel LLM calls) to avoid DashScope rate limits.
- Only warms Dashboard-critical analyses. Deep-dive pages warm on-demand.
"""
from __future__ import annotations

import asyncio
import logging

import asyncpg

logger = logging.getLogger(__name__)


# Standard Dashboard-level questions that ALL restaurant tenants show by default.
# These are what Week 5 Agent Insights triggers on page load. Pre-warming them
# here means first Dashboard hit after upload serves from cache.
WARMUP_QUESTIONS = [
    "本期经营亮点与隐忧是什么？",
    "哪些菜品贡献最大营收？",
    "销售趋势有何变化？",
    "哪家门店表现突出？需要重点关注哪家？",
    "近期有什么数据异常值得管理层关注？",
]


async def warmup_factory_insights(
    pool: asyncpg.Pool,
    factory_id: str,
    upload_id: int,
    concurrency: int = 4,
) -> dict:
    """Kick off background LLM calls for factory Dashboard insights.

    Returns quickly with task tracking info — actual LLM calls run in background.
    Customer materialize endpoint already returned to client before this completes.
    """
    logger.info(
        f"[warmup] factory={factory_id} upload={upload_id} "
        f"starting {len(WARMUP_QUESTIONS)} questions at concurrency={concurrency}"
    )

    try:
        from smartbi.agent.orchestrator import AgentOrchestrator
        from smartbi.agent.narrative_cache import NarrativeCache
        from smartbi.agent.budget_tracker import AgentBudgetTracker
    except ImportError as e:
        logger.warning(f"[warmup] agent layer not available: {e}")
        return {"started": 0, "error": str(e)}

    cache = NarrativeCache(pool)
    budget = AgentBudgetTracker(pool)
    orchestrator = AgentOrchestrator(pool, cache, budget)

    # Default date range: last 90 days (matches Dashboard default "近 90 天")
    from datetime import date, timedelta
    date_end = date.today()
    date_start = date_end - timedelta(days=90)

    semaphore = asyncio.Semaphore(concurrency)

    async def _warmup_one(question: str):
        async with semaphore:
            try:
                import time
                t0 = time.time()
                await orchestrator.answer_insight(
                    factory_id=factory_id,
                    question=question,
                    date_range=(date_start, date_end),
                )
                logger.info(
                    f"[warmup] factory={factory_id} question='{question[:20]}...' "
                    f"warmed in {time.time() - t0:.1f}s"
                )
            except Exception as e:
                logger.warning(
                    f"[warmup] factory={factory_id} question='{question[:20]}...' failed: {e}"
                )

    # Fire all warmup calls concurrently
    await asyncio.gather(
        *[_warmup_one(q) for q in WARMUP_QUESTIONS],
        return_exceptions=False,
    )

    logger.info(f"[warmup] factory={factory_id} upload={upload_id} all warmups complete")

    # 加速 D: also pre-generate Top 130 dish recipe drafts in background.
    # When customer opens 配方管理 / AI 批量录配方, the drafts are ready in
    # auto_recipe_drafts table waiting for one-click 采纳.
    try:
        await _auto_pregenerate_recipe_drafts(pool, factory_id, top_n=130)
    except Exception as e:
        logger.warning(f"[warmup] recipe pregenerate failed: {e}")

    return {"started": len(WARMUP_QUESTIONS), "concurrency": concurrency}


async def _auto_pregenerate_recipe_drafts(
    pool: asyncpg.Pool,
    factory_id: str,
    top_n: int = 130,
) -> None:
    """Background pre-generate AI recipe drafts for top N unmatched dishes.

    Stores results in auto_recipe_drafts table (factory_id, dish_name, drafts_json,
    generated_at). FE picks them up when customer opens 配方管理 page.
    """
    logger.info(f"[recipe-pregenerate] factory={factory_id} top_n={top_n} starting")

    # Step 1: Get top N unmatched dishes by revenue (read smartbi pool, no JWT context).
    # Explicit transaction required for set_config(..., true) to persist across
    # multiple await conn.execute calls in same conn.
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
            try:
                rows = await conn.fetch("""
                    SELECT p.normalized_name AS name,
                           SUM(i.amount)::float AS revenue
                      FROM fact_pos_item i
                      JOIN dim_product p ON p.product_id = i.product_id
                     WHERE i.factory_id = $1
                     GROUP BY p.normalized_name
                     ORDER BY revenue DESC
                     LIMIT $2
                """, factory_id, top_n)
            except Exception as e:
                logger.warning(f"[recipe-pregenerate] no POS data yet: {e}")
                return

    if not rows:
        logger.info(f"[recipe-pregenerate] factory={factory_id} no POS dishes, skip")
        return

    # Step 2: ensure auto_recipe_drafts table exists
    try:
        from config import get_settings as _get_settings
        cretas = await asyncpg.connect(_get_settings().food_kb_db_url)
        try:
            await cretas.execute("""
                CREATE TABLE IF NOT EXISTS auto_recipe_drafts (
                    id BIGSERIAL PRIMARY KEY,
                    factory_id VARCHAR(100) NOT NULL,
                    dish_name VARCHAR(500) NOT NULL,
                    drafts_json JSONB NOT NULL,
                    estimated_cost_ratio NUMERIC(5,4),
                    notes TEXT,
                    generated_at TIMESTAMP DEFAULT NOW(),
                    consumed_at TIMESTAMP,
                    UNIQUE(factory_id, dish_name)
                )
            """)

            # Filter out dishes already with recipes (avoid wasting LLM tokens)
            dish_names = [r["name"] for r in rows if r["name"]]
            existing = await cretas.fetch(
                "SELECT name FROM product_types WHERE factory_id = $1 AND name = ANY($2::text[]) AND deleted_at IS NULL",  # noqa: E501
                factory_id, dish_names,
            )
            already_set = {r["name"] for r in existing}
            todo = [n for n in dish_names if n not in already_set]
            if not todo:
                logger.info(f"[recipe-pregenerate] all {len(dish_names)} dishes already have product_type, skip")
                return

            # Filter out drafts already pre-generated (last 24h)
            existing_drafts = await cretas.fetch(
                """SELECT dish_name FROM auto_recipe_drafts
                    WHERE factory_id = $1 AND dish_name = ANY($2::text[])
                      AND generated_at > NOW() - INTERVAL '24 hours'""",
                factory_id, todo,
            )
            already_drafted = {r["dish_name"] for r in existing_drafts}
            need_gen = [n for n in todo if n not in already_drafted]
            logger.info(f"[recipe-pregenerate] factory={factory_id}: {len(dish_names)} top, "
                        f"{len(already_set)} have product_type, {len(already_drafted)} drafts cached, "
                        f"{len(need_gen)} need fresh AI gen")
        finally:
            await cretas.close()
    except Exception as e:
        logger.warning(f"[recipe-pregenerate] table/filter check failed: {e}")
        return

    if not need_gen:
        return

    # Step 3: call batch AI draft (concurrency 10)
    try:
        from common.llm_client import get_llm_http_client
        from config import get_settings as _get_settings
        from smartbi.api.restaurant_ops_recipes import _call_llm_for_recipe
        settings = _get_settings()
        client = get_llm_http_client()
        sem = asyncio.Semaphore(10)

        async def _gen_one(name: str):
            async with sem:
                return await _call_llm_for_recipe(name, None, client, settings)

        import time as _time
        t0 = _time.time()
        results = await asyncio.gather(*[_gen_one(n) for n in need_gen], return_exceptions=False)
        elapsed = _time.time() - t0
        succeeded = sum(1 for r in results if r.get("success"))
        logger.info(f"[recipe-pregenerate] factory={factory_id} {succeeded}/{len(need_gen)} drafts in {elapsed:.1f}s")

        # Step 4: store drafts
        cretas = await asyncpg.connect(_get_settings().food_kb_db_url)
        try:
            import json as _json
            for r in results:
                if not r.get("success"):
                    continue
                drafts_json = _json.dumps({
                    "ingredients": r.get("ingredients", []),
                    "estimatedCostRatio": r.get("estimatedCostRatio"),
                    "notes": r.get("notes", ""),
                }, ensure_ascii=False)
                await cretas.execute(
                    """INSERT INTO auto_recipe_drafts
                          (factory_id, dish_name, drafts_json, estimated_cost_ratio, notes, generated_at)
                       VALUES ($1, $2, $3::jsonb, $4, $5, NOW())
                       ON CONFLICT (factory_id, dish_name) DO UPDATE SET
                          drafts_json = EXCLUDED.drafts_json,
                          estimated_cost_ratio = EXCLUDED.estimated_cost_ratio,
                          notes = EXCLUDED.notes,
                          generated_at = NOW(),
                          consumed_at = NULL""",
                    factory_id, r["dishName"], drafts_json,
                    r.get("estimatedCostRatio"),
                    r.get("notes", ""),
                )
            logger.info(f"[recipe-pregenerate] factory={factory_id} stored {succeeded} drafts to auto_recipe_drafts")
        finally:
            await cretas.close()
    except Exception as e:
        logger.exception(f"[recipe-pregenerate] LLM/store failed: {e}")
