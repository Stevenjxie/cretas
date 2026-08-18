# -*- coding: utf-8 -*-
"""哪些租户的 `agg_daily` 真的有多门店数据 —— ⛔ 不猜租户 id。

⚠️ RLS 是 force 的, 所以**每个租户单独设会话变量**并把 `current_setting`
   贴出来 (memory A⁶: 只在一个样本上成立的阳性对照证明不了仪器在别的样本上活着)。
⚠️ `pg_class` 不带 schema 过滤 ⇒ 同名表可能有多行, 一起打出来 (memory 8-16)。
"""
from __future__ import annotations

import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

ctx = bootstrap_probe("MOCK_REST")

CANDIDATES = [
    "MOCK_REST", "DEMO_REST", "RES_3101_009", "QHJ_PROD",
    "R_GML_DEMO", "R_XMX_CHAIN", "REST_DEMO", "T001",
]


async def main() -> int:
    pool = await ctx.pool()
    async with pool.acquire() as conn:
        for r in await conn.fetch(
                "SELECT n.nspname, c.relname, c.relrowsecurity "
                "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                "WHERE c.relname IN ('agg_daily','dim_store') ORDER BY 1,2"):
            print(f"  schema={r['nspname']:<12} {r['relname']:<12} rls={r['relrowsecurity']}")
        who = await conn.fetchrow(
            "SELECT current_user AS u, current_database() AS db, "
            "(SELECT rolbypassrls FROM pg_roles WHERE rolname=current_user) AS bypass")
        print(f"  user={who['u']} db={who['db']} bypassrls={who['bypass']}")
    print()

    hits = 0
    for fid in CANDIDATES:
        async with pool.acquire() as conn:
            # 会话级 (false), ⛔ 不是事务级 —— asyncpg 自动提交, true 会当场失效
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", fid)
            got = await conn.fetchval("SELECT current_setting('app.factory_id', true)")
            row = await conn.fetchrow(
                "SELECT COUNT(*) n, COUNT(DISTINCT store_id) s, MIN(date) d0, MAX(date) d1 "
                "FROM agg_daily WHERE factory_id = $1", fid)
            stores = await conn.fetchval(
                "SELECT COUNT(*) FROM dim_store WHERE factory_id = $1", fid)
            mark = "OK " if row["n"] else "   "
            if row["n"]:
                hits += 1
            print(f"{mark}{fid:<14} ctx={got!r:<16} agg_daily 行={row['n']:<7} "
                  f"门店={row['s']:<4} {row['d0']}~{row['d1']}  dim_store={stores}")
    print()
    if hits == 0:
        print("rc=2 一个候选租户都没读到 —— 仪器/库有问题, ⛔ 不是「都没有数据」")
        return 2
    print(f"有多门店 POS 数据的候选: {hits}/{len(CANDIDATES)}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
