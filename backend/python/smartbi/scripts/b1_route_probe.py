"""B-1 · 日结问句**实际**路由到哪个 intent —— 跑出来, ⛔ 不从意图描述推。

我上一轮按 `_INTENT_DESCRIPTIONS` 的字面(「菜品级别的毛利分析」vs「总体经营概览」)
把表格只挂在 `RESTAURANT_OPS_GROSS_MARGIN` 上。那是**从代码推的**。
真正要问的是: 老板打烊时那句话, 路由把它送到哪个 resolver。

⚠️ 硬约束 3: 开跑前清缓存, 并把清了哪几个贴出来 —— 拼错的属性名不会报错,
   只会让清理静默失效。
"""
import sys

from smartbi.gold.restaurant import restaurant_intent as ri

QUERIES = [
    "今天各菜品的经营情况",
    "今天生意怎么样",
    "今天卖得最多的菜是什么",
    "各菜品的毛利怎么样",
    "今天营业额多少",
    "列个表看看今天各菜品",
]


def main() -> int:
    for fn in ("clear_semantic_plan_cache", "clear_route_cache", "clear_tenant_gate_cache"):
        f = getattr(ri, fn, None)
        print(f"清缓存 {fn}: {'✅ 已调用' if f else '⛔ 不存在(拼错了?)'}")
        if f:
            f()
    print()

    # 找到关键词路由的真实入口 —— 名字不猜, 列出来。
    candidates = [n for n in dir(ri) if "intent" in n.lower() and callable(getattr(ri, n))]
    print("模块里与 intent 相关的可调用对象:")
    for n in candidates:
        print(f"  · {n}")
    print()

    keyword_map = getattr(ri, "_KEYWORD_INTENTS", None) or getattr(ri, "KEYWORD_INTENTS", None)
    print(f"关键词表: {type(keyword_map).__name__} 条目 {len(keyword_map) if keyword_map else 0}")
    print()

    for q in QUERIES:
        pref = ri._detect_output_preference(q)
        hits = []
        if isinstance(keyword_map, dict):
            hits = [(k, v) for k, v in keyword_map.items() if k in q]
        print(f"{q!r}")
        print(f"    pref={pref}")
        print(f"    关键词命中={hits[:3] or '无'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
