"""dim_store 全表到底有多少行 —— 用**与 RLS 无关**的统计量定案。

⚠️ 上一版探针的 `GROUP BY factory_id`(不设 GUC) 读出 {"MOCK_REST": 10}, 那是
   **池连接残留的会话级 GUC**, ⛔ 不是「全表只有 MOCK_REST」的证据。
   smartbi_user 既非 superuser 也无 bypassrls, 任何走 SELECT 的口径都会被 RLS 过滤。

RLS 过滤的是**行可见性**, ⛔ 不改 planner 统计:
  · pg_class.reltuples          最近一次 ANALYZE/VACUUM 的行数估计
  · pg_stat_user_tables         n_live_tup —— ⚠️ 必须带 schemaname 过滤
                                (本仓踩过: 不带 schema 会读到归档 schema 的行)
判据: reltuples ≈ 10 ⇒ 全表就 MOCK_REST 那 10 行, 我读出的 0 是真的 0。
      reltuples ≫ 10 ⇒ 有我看不见的租户, 「真实租户 dim_store 为空」这条结论作废。
阳性对照: 拿一张**已知很大**的表(fact 类)一起读, 证明 reltuples 不是恒 0。
"""
from __future__ import annotations

import asyncio
import json
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

ctx = bootstrap_probe("MOCK_REST")

TABLES = ("dim_store", "dim_product", "dim_canonical_dish",
          "fact_pos_order", "fact_pos_order_item", "agg_restaurant_daily_store")


async def main() -> int:
    pool = await ctx.pool()
    async with pool.acquire() as conn:
        print("database =", await conn.fetchval("SELECT current_database()"))
        print("\n%-32s %14s %14s %-22s" % ("table", "reltuples", "n_live_tup", "last_analyze"))
        for tbl in TABLES:
            row = await conn.fetchrow(
                """
                SELECT c.reltuples::bigint AS reltuples,
                       s.n_live_tup,
                       COALESCE(s.last_analyze, s.last_autoanalyze) AS la
                  FROM pg_class c
                  LEFT JOIN pg_stat_user_tables s
                         ON s.relid = c.oid AND s.schemaname = 'public'
                 WHERE c.relnamespace = 'public'::regnamespace AND c.relname = $1
                """, tbl)
            if row is None:
                print("%-32s %14s" % (tbl, "<表不存在>"))
                continue
            print("%-32s %14s %14s %-22s" % (
                tbl, row["reltuples"], row["n_live_tup"], str(row["la"])[:19]))

        # ⚠️ reltuples 是**上次 ANALYZE 时**的估计。先 ANALYZE 一次再读,
        #    否则「10」可能只是很久以前的快照。ANALYZE 只读统计, 不改数据。
        print("\n-- ANALYZE dim_store / dim_product 后重读 --")
        for tbl in ("dim_store", "dim_product"):
            try:
                await conn.execute(f"ANALYZE {tbl}")
            except Exception as exc:  # noqa: BLE001
                print("  ANALYZE %s 失败(权限?): %s" % (tbl, str(exc)[:80]))
                continue
            n = await conn.fetchval(
                "SELECT c.reltuples::bigint FROM pg_class c "
                "WHERE c.relnamespace = 'public'::regnamespace AND c.relname = $1", tbl)
            print("  %-20s reltuples=%s" % (tbl, n))

        print("\n-- 每租户逐个 set_config, 同事务内先贴 GUC 再数 (⛔ 不共用连接残留) --")
        for fid in ("MOCK_REST", "RES_3101_009", "QHJ_PROD", "DEMO_REST",
                    "R_XMX_CHAIN", "R_GML_DEMO", "__不存在的租户__"):
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)", fid)
                guc = await conn.fetchval(
                    "SELECT current_setting('app.factory_id', true)")
                # ⛔ 不带 WHERE —— 让 RLS 自己说话
                n = await conn.fetchval("SELECT count(*) FROM dim_store")
                print("  guc=%-18s dim_store(RLS可见)=%s" % (guc, n))
        print("\n判据: 「__不存在的租户__」必须是 0 (阴性对照); MOCK_REST 必须是 10 (阳性对照)")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
