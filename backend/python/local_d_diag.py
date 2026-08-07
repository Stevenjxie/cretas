"""逐条诊断剩余的 D。

🔑 本轮已经证明：每条 D 的形状都不同（缺终点 / 维度误标 / 菜名抽取器误抓 /
契约覆盖不足）。**「D=N」这个数字本身没有诊断价值**，必须逐条把 spec 打出来看。
"""
import asyncio
import os
import sys

import asyncpg

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "smartbi"))

D_QUERIES = [
    "最近损耗情况怎么样",
    "各门店对比如何",
    "折扣力度多大",
]

FACTORY = "MOCK_REST"
ROLE = "factory_super_admin"


def dump(q, spec, res):
    print(f"\n── {q}")
    if spec is None:
        print("   spec = None（解析器直接放弃）")
    else:
        g = lambda k, d=None: getattr(spec, k, d)  # noqa: E731
        print(f"   intent={g('intent')!r} conf={g('confidence')} tier={g('source_tier')}")
        print(f"   planned={g('planned_intents')}")
        print(f"   dims={g('dimensions')} metrics={g('requested_metrics')}")
        print(f"   dish={g('dish_slot')} store_scope={g('store_scope')} "
              f"window={g('window_label')!r} action={g('analysis_action')}")
        print(f"   clarification_needed={g('clarification_needed')} "
              f"missing={g('missing_slot')}")
        print(f"   time_defaulted={g('time_range_defaulted')} "
              f"store_defaulted={g('store_scope_defaulted')}")
        print(f"   unsupported={g('unsupported_requirements')}")
    kind = (res or {}).get("kind")
    text = ((res or {}).get("answer_text") or "")[:130].replace("\n", " ")
    print(f"   -> kind={kind} code={(res or {}).get('code')}")
    print(f"   -> {text}")


async def main():
    from smartbi.gold.restaurant.restaurant_intent import parse_restaurant_query
    from smartbi.gold.restaurant.restaurant_intent_service import tiered_answer
    from smartbi.tenant_ctx import set_factory_id, set_pg_connection_tenant

    set_factory_id(FACTORY)
    pool = await asyncpg.create_pool(
        host=os.environ["POSTGRES_HOST"], port=int(os.environ["POSTGRES_PORT"]),
        user=os.environ["POSTGRES_USER"], password=os.environ["POSTGRES_PASSWORD"],
        database=os.environ["POSTGRES_DB"], min_size=1, max_size=3,
        command_timeout=240, setup=set_pg_connection_tenant,
    )
    try:
        for q in D_QUERIES:
            try:
                from smartbi.gold.restaurant.restaurant_ops_router import dish_catalogue_scope
                async with dish_catalogue_scope(pool, FACTORY):
                    spec = await parse_restaurant_query(
                        q, pool, factory_id=FACTORY, semantic_first=True)
                res = await tiered_answer(q, pool, FACTORY, ROLE, precomputed_spec=spec)
                dump(q, spec, res)
            except Exception as e:  # noqa: BLE001
                print(f"\n── {q}\n   异常 {type(e).__name__}: {str(e)[:120]}")
    finally:
        await asyncio.sleep(1)
        await pool.close()


if __name__ == "__main__":
    asyncio.run(main())
