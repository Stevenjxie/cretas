"""本地跑 G1：直接调 tiered_answer，不起 web 层（Windows 上 main.py 需要 fcntl，起不来）。

⚠️ 问句集必须与**基线**逐字相同（短问法，不带「最近30天」前缀）——本轮已经栽过一次：
   我拿加了时间前缀的改写版去报改善，A 从 5 被读成 12。

用法（DB 走 SSH 隧道到 prod smartbi_db）：
    POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=15432 POSTGRES_DB=smartbi_db \
    POSTGRES_USER=smartbi_user POSTGRES_PASSWORD=*** python local_g1_driver.py
"""
import asyncio
import os
import sys
import time

import asyncpg

# ⚠️ 应用启动时把 smartbi/ 也放进 sys.path（有代码写 `from services.x import y`）。
# 不补这一句, 部分 resolver 会因 `No module named 'services'` 失败 ->
# 表现成 D-反问, 那是**本地 harness 造出来的假缺陷**, 不是代码问题。
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "smartbi"))

# 逐字取自 /tmp/g0g1b.sh（= g0g1c.sh，md5 相同，基线脚本）。
QUERIES = [
    "最近30天总营收是多少",
    "加权毛利率是多少",
    "哪个菜卖得最好",
    "毛利最低的菜品有哪些",
    "外卖和堂食各占多少",
    "哪个时段生意最好",
    "食材成本占营收多少",
    "最近损耗情况怎么样",
    "库存有什么要注意的",
    "哪个供应商报价最贵",
    "员工人效怎么样",
    "营收趋势怎么样",
    "各门店对比如何",
    "折扣力度多大",
    "明天天气怎么样",
]

FACTORY = "MOCK_REST"
ROLE = "factory_super_admin"


def verdict(res):
    """A 有答案 / B 诚实缺数据 / C 不在范围 / D 反问(禁止)。"""
    if res is None:
        return "D-无返回", ""
    kind = res.get("kind")
    code = res.get("code") or ""
    text = res.get("answer_text") or ""
    if code == "RESTAURANT_OPS_DATA_GAP" or "还没有数据" in text:
        return "B-诚实缺数据", code
    if code == "RESTAURANT_OPS_OUT_OF_DOMAIN":
        return "C-不在范围", code
    if kind == "clarification":
        return "D-反问(禁止)", code
    if kind == "answer":
        return "A-有答案", code
    return f"D-{kind}", code


async def main():
    from smartbi.gold.restaurant.restaurant_intent_service import tiered_answer

    # 🔴 必须带 setup=set_pg_connection_tenant 并先设 contextvar:
    #    fact/dim 表全部开了 RLS(forced=true), 策略是
    #      USING (factory_id = current_setting('app.factory_id'))
    #    裸 pool 借出的连接没设这个 GUC -> **所有租户查询返回 0 行** ->
    #    解析器拿不到菜单/门店就直接返回 None, 表现成「0ms 无返回」。
    #    第一版就是这么写的, 14/15 条全是 D-无返回, 看着像代码坏了。
    from smartbi.tenant_ctx import set_factory_id, set_pg_connection_tenant

    set_factory_id(FACTORY)
    pool = await asyncpg.create_pool(
        host=os.environ["POSTGRES_HOST"],
        port=int(os.environ["POSTGRES_PORT"]),
        user=os.environ["POSTGRES_USER"],
        password=os.environ["POSTGRES_PASSWORD"],
        database=os.environ["POSTGRES_DB"],
        min_size=1, max_size=4, command_timeout=180,
        setup=set_pg_connection_tenant,
    )
    print(f"{'问句':<22}|{'耗时ms':>7}|归宿")
    tally = {}
    try:
        for q in QUERIES:
            t0 = time.time()
            try:
                res = await tiered_answer(q, pool, FACTORY, ROLE)
                v, code = verdict(res)
            except Exception as e:  # noqa: BLE001
                v, code = f"E-{type(e).__name__}", str(e)[:40]
            ms = int((time.time() - t0) * 1000)
            tally[v[0]] = tally.get(v[0], 0) + 1
            print(f"{q:<22}|{ms:>7}|{v} {code}")
    finally:
        await pool.close()
    print("\n汇总:", " ".join(f"{k}={v}" for k, v in sorted(tally.items())))


if __name__ == "__main__":
    asyncio.run(main())
