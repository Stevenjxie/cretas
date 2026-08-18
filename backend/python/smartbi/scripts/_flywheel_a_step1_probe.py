"""飞轮 A 第 10 项 · 第 1 步现状探针（当场重跑，不从文档推断）。

量三件事：
  1. load_promoted_samples() / aggregate_candidates() / aggregate_misses()
     —— 与 2026-08-18 架构地图里「候选 176 条排队，晋升 0 条」对比，
     数字若已经变了，以这次量到的为准。
  2. smart_bi_llm_fallback_log 的 query_embedding / history / user_feedback
     三列非空率（admin scope 全表 + 仅餐饮口径行两个口径都报，防止「量错分母」）。
  3. 纠正记账相关字段 (contract_missing / rejected_answer / answered_missing)
     现有非空率 —— 判断"纠正记账"这件事是不是已经部分存在，只是没被当成一件事看。

阳性对照：总行数必须 > 0，否则以下全部读数作废（硬约束 9）。

用法见 smartbi/scripts/_probe_bootstrap.py 头部注释。
"""
from __future__ import annotations

import asyncio
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe


async def main() -> None:
    ctx = bootstrap_probe("MOCK_REST")
    pool = await ctx.pool()

    from smartbi.gold.restaurant.restaurant_intent_promotion import (
        aggregate_candidates,
        aggregate_misses,
        load_promoted_samples,
        merge_samples,
    )

    promoted = load_promoted_samples()
    promoted_count = sum(len(v) for v in promoted.values())
    print(f"[1a] load_promoted_samples() = {promoted_count} 条 (codes={len(promoted)})")

    merged = merge_samples()
    merged_count = sum(len(v) for v in merged.values())
    print(f"[1b] merge_samples() 合计(种子+晋升) = {merged_count} 条")

    cands_admin = await aggregate_candidates(pool, factory_id=None, limit=500)
    recommended = sum(1 for c in cands_admin if c["recommended"])
    print(
        f"[2a] aggregate_candidates(factory_id=None, 跨租户) = {len(cands_admin)} 条候选, "
        f"recommended={recommended}"
    )
    if cands_admin:
        top5 = sorted(cands_admin, key=lambda c: -c["occurrence_count"])[:5]
        for c in top5:
            print(
                f"     · occ={c['occurrence_count']:>3} code={c['code']:<32} "
                f"conflict={c['conflict']} recommended={c['recommended']} "
                f"query={c['query'][:40]!r}"
            )

    cands_mock = await aggregate_candidates(pool, factory_id="MOCK_REST", limit=500)
    print(f"[2b] aggregate_candidates(factory_id=MOCK_REST) = {len(cands_mock)} 条候选")

    misses_admin = await aggregate_misses(pool, factory_id=None, limit=500)
    print(f"[2c] aggregate_misses(factory_id=None) = {len(misses_admin)} 条")

    async with pool.acquire() as conn:
        # admin 通道: 见 restaurant_intent_promotion._set_rls_guc 文档 —— 用
        # SELECT set_config('app.factory_id', '', false) 让 FORCE RLS 的宽松分支生效。
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", "")

        total = await conn.fetchval("SELECT COUNT(*) FROM smart_bi_llm_fallback_log")
        print(f"[POS] smart_bi_llm_fallback_log 总行数 (admin scope) = {total}")
        if not total:
            print(
                "🔴 阳性对照未过 —— 总行数为 0, 下面全部非空率读数作废, 先查 RLS/GUC",
                file=sys.stderr,
            )
            return

        cols = await conn.fetchrow(
            """
            SELECT
              COUNT(*)                                                      AS total,
              COUNT(query_embedding)                                        AS has_embedding,
              COUNT(history)                                                AS has_history,
              COUNT(user_feedback)                                          AS has_feedback,
              COUNT(promoted_to_template)                                   AS has_promoted_col,
              COUNT(*) FILTER (WHERE agg_meta ? 'contract_missing')         AS has_contract_missing,
              COUNT(*) FILTER (WHERE agg_meta ? 'rejected_answer')          AS has_rejected_answer,
              COUNT(*) FILTER (WHERE agg_meta ? 'answered_missing')         AS has_answered_missing,
              COUNT(*) FILTER (WHERE template_code LIKE 'RESTAURANT_OPS_%') AS restaurant_rows
            FROM smart_bi_llm_fallback_log
            """
        )
        print(
            f"[3] 全表(admin scope) 非空率: total={cols['total']} "
            f"query_embedding={cols['has_embedding']} history={cols['has_history']} "
            f"user_feedback={cols['has_feedback']} promoted_to_template列={cols['has_promoted_col']} "
            f"restaurant_rows={cols['restaurant_rows']}"
        )
        print(
            f"[4] 纠正相关字段(admin scope, agg_meta JSONB key 存在与否): "
            f"contract_missing={cols['has_contract_missing']} "
            f"rejected_answer={cols['has_rejected_answer']} "
            f"answered_missing={cols['has_answered_missing']}"
        )

        rest_cols = await conn.fetchrow(
            """
            SELECT
              COUNT(*)               AS total,
              COUNT(query_embedding) AS has_embedding,
              COUNT(history)         AS has_history,
              COUNT(user_feedback)   AS has_feedback
            FROM smart_bi_llm_fallback_log
            WHERE template_code LIKE 'RESTAURANT_OPS_%' OR template_code = 'RESTAURANT_FEEDBACK'
            """
        )
        print(
            f"[5] 仅餐饮口径行: total={rest_cols['total']} "
            f"query_embedding={rest_cols['has_embedding']} history={rest_cols['has_history']} "
            f"user_feedback={rest_cols['has_feedback']}"
        )

        # source 分布 —— 用来核对"production restaurant writes go through
        # log_template_hit (no embedding param)" 这个读代码结论是否与实测吻合。
        by_source = await conn.fetch(
            """
            SELECT source, COUNT(*) AS n, COUNT(query_embedding) AS has_emb,
                   COUNT(history) AS has_hist
            FROM smart_bi_llm_fallback_log
            GROUP BY source ORDER BY n DESC
            """
        )
        print("[6] 按 source 分组:")
        for r in by_source:
            print(f"     · source={r['source']!r:<12} n={r['n']:>6} has_emb={r['has_emb']} has_hist={r['has_hist']}")


if __name__ == "__main__":
    asyncio.run(main())
