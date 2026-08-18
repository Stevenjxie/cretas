"""Option A (确定性: 去尾缀后包含) vs Option B (embedding) —— 同一批样本上对打。

⚠️ 为什么必须量 A: 20 条 LIKE 零候选里, 「宝山店 -> 宝山」去掉一个「店」字
   就变回真子串了。⛔ 不先量它, embedding 就是过度工程。

⚠️ 为什么 A 不够: owner 定稿里那条
       '青花椒二人套餐' vs '鲜花椒大人二人套餐'
   **没有任何子串关系** —— 去多少尾缀都变不出来。这一类只有向量做得了。
   ⇒ 本探针把 owner 原文那两组当**第三批样本**跑, 看 A/B 各自能不能。

阳性对照: 全名 -> A/B 都必须唯一命中。
阴性对照: 无关词 -> A 必须 0 候选, B 的 top1 sim 必须低于 floor。
"""
from __future__ import annotations

import asyncio
import json
import os
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

ctx = bootstrap_probe(os.environ.get("PROBE_FID", "MOCK_REST"))

#: owner 定稿 `docs/decisions/2026-08-18-餐饮AI架构-完整版-owner定稿.md` 第三节原文。
#: ⛔ 不是我编的 —— 它是本轮设计依据里唯一「子串从原理上做不到」的证据。
OWNER_CASES = [
    ("山川店", "山川蒙湖店", ["山川蒙湖店", "静安嘉里中心店", "陆家嘴正大店"]),
    ("青花椒二人套餐", "鲜花椒大人二人套餐",
     ["鲜花椒大人二人套餐", "藤椒鸡", "水煮牛肉", "干锅花菜"]),
]

_STORE_SUFFIX = ("门店", "店", "分店", "餐厅")


def strip_store_suffix(mention: str) -> str:
    for suf in _STORE_SUFFIX:
        if mention.endswith(suf) and len(mention) > len(suf):
            return mention[: -len(suf)]
    return mention


def option_a(mention: str, candidates) -> list:
    """去尾缀 + 归一后双向包含。⛔ 纯字符串, 零外部依赖。"""
    from smartbi.canonical.entity_resolution.agents.deterministic import (
        normalize_for_dim,
    )

    m = normalize_for_dim(strip_store_suffix(mention))
    if not m:
        return []
    hits = []
    for c in candidates:
        n = normalize_for_dim(c)
        if m and (m in n or n in m):
            hits.append(c)
    hits.sort(key=len)
    return hits


async def option_b(mention: str, candidates, cache) -> list:
    """embedding 余弦排序。返回 [(name, sim), ...] 降序。"""
    from smartbi.services.llm_fallback_logger import get_embedding
    from smartbi.canonical.entity_resolution.agents.embedding import EmbeddingAgent

    async def emb(text):
        if text in cache:
            return cache[text]
        v = await get_embedding(text)
        if v is not None:
            cache[text] = v
        return v

    q = await emb(mention)
    if q is None:
        return []
    out = []
    for c in candidates:
        v = await emb(c)
        if v is None:
            continue
        out.append((c, EmbeddingAgent._cosine(q, v)))
    out.sort(key=lambda x: -x[1])
    return out


async def main() -> int:
    pool = await ctx.pool()
    fid = ctx.factory_id
    cache: dict = {}

    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", fid)
            stores = [r["name"] for r in await conn.fetch(
                "SELECT name FROM dim_store WHERE factory_id = $1 ORDER BY name", fid)]
            dishes = [r["name"] for r in await conn.fetch(
                "SELECT name FROM dim_product WHERE factory_id = $1 ORDER BY name", fid)]
    print("stores=%d dishes=%d" % (len(stores), len(dishes)))

    batches = []
    # 批 1: 第 1 步实测 LIKE **零候选**的 20 条门店简称
    b1 = []
    for full in stores:
        base = full
        for noise in ("模拟·", "模拟"):
            if base.startswith(noise):
                base = base[len(noise):]
                break
        core = base[:-1] if base.endswith("店") else base
        if len(core) >= 3:
            b1.append((core[:2] + "店", full, stores))
        if len(core) >= 4:
            b1.append((core[:3] + "店", full, stores))
    batches.append(("批1-门店简称(LIKE零候选)", b1))
    # 批 2: owner 定稿原文那两组
    batches.append(("批2-owner定稿原文", [
        (m, exp, cands) for m, exp, cands in OWNER_CASES]))
    # 批 3: 对照
    batches.append(("批3-对照", [
        (stores[0], stores[0], stores),
        (dishes[0], dishes[0], dishes),
        ("量子纠缠火箭发射器", None, stores),
        ("量子纠缠火箭发射器", None, dishes),
    ]))

    summary = {}
    for label, cases in batches:
        print("\n==== %s (n=%d) ====" % (label, len(cases)))
        a_uniq = a_hit = b_top1 = 0
        for mention, expect, cands in cases:
            a = option_a(mention, cands)
            b = await option_b(mention, cands, cache)
            a_ok = bool(a) and a[0] == expect
            a_u = a == [expect] if expect else (a == [])
            b_ok = bool(b) and b[0][0] == expect
            if expect is None:
                b_ok = not b or b[0][1] < 0.55
            a_hit += a_ok
            a_uniq += a_u
            b_top1 += b_ok
            margin = (b[0][1] - b[1][1]) if len(b) >= 2 else None
            print("  %-16s 期望=%-12s | A: n=%d %s %-14s | B: top1=%-14s sim=%.3f margin=%s %s" % (
                mention, str(expect)[:12], len(a), "✅" if a_ok else "🔴",
                json.dumps(a[:2], ensure_ascii=False)[:26],
                (b[0][0] if b else "-"), (b[0][1] if b else 0.0),
                ("%.3f" % margin) if margin is not None else "-",
                "✅" if b_ok else "🔴"))
        n = len(cases)
        summary[label] = (a_hit, a_uniq, b_top1, n)
        print("  小结: A命中top1 %d/%d  A唯一 %d/%d  B命中top1 %d/%d" % (
            a_hit, n, a_uniq, n, b_top1, n))

    print("\n==== 总表 (口径: 分母=该批样本数; A=去尾缀归一双向包含, B=embedding余弦top1) ====")
    for label, (a_hit, a_uniq, b_top1, n) in summary.items():
        print("  %-26s n=%-3d A命中=%-3d A唯一=%-3d B命中=%-3d" % (
            label, n, a_hit, a_uniq, b_top1))
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
