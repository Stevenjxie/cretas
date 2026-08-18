"""第 1 步现状读数: 老板会说的简称, 现有两条匹配路径能命中多少?

量的是**两条真实在用的路径**, ⛔ 不是我自己写的相似度:

  (a) `restaurant_intent._validated_llm_store_names` 的 `name not in available`
      —— 精确集合成员判定, 不中就**静默丢弃**
  (b) `restaurant_ops_router._canonicalize_store_mention` 的 SQL LIKE
      —— 双向包含 `name LIKE %m% OR m LIKE %name%`

阳性对照: 库里的**全名**本身, 两条路都必须命中。
阴性对照: 与本租户完全无关的词, 两条路都必须**不**命中。
⚠️ 没有这两条, 「命中率 0%」分不清是「路径不行」还是「我的仪器没连上库」。

简称怎么造 (⛔ 不写死名字, 从库里真实实体机械派生):
  S1 去掉「模拟·」「演示」这类前缀词
  S2 只留头 2~3 个字 + 「店」   (山川蒙湖店 -> 山川店)
  S3 去掉尾巴的「店」
  S4 去掉中间的行政区/商圈词后再加「店」
菜品同理: 去括号里的规格 / 去前缀修饰 / 只留核心 3~4 字。
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

#: ⚠️ MOCK_REST 只做冒烟 —— 它的菜名 3~4 字、门店名统一「模拟·」前缀,
#:    去一个字仍是真子串 ⇒ LIKE 必过, 是「让闸不可能红」的验收环境。
#:    算得对不对一律在脏样本上验 (RES_3101_009 / DEMO_REST)。
_FID = os.environ.get("PROBE_FID", "MOCK_REST")
ctx = bootstrap_probe(_FID)

OUT = "/tmp/entity_shorthand_baseline_%s.json" % _FID

_PREFIX_NOISE = ("模拟·", "模拟", "演示·", "演示", "测试·", "测试")


def store_shorthands(name: str) -> list:
    """机械派生「老板会怎么说」。⛔ 每条都标出规则名, 便于逐条核。"""
    out = []
    base = name
    for noise in _PREFIX_NOISE:
        if base.startswith(noise):
            base = base[len(noise):]
            out.append(("S1去前缀", base))
            break
    core = base[:-1] if base.endswith("店") else base
    if len(core) >= 3:
        out.append(("S2头两字+店", core[:2] + "店"))
    if len(core) >= 4:
        out.append(("S3头三字+店", core[:3] + "店"))
    if core != base:
        out.append(("S4去店字", core))
    if len(core) >= 4:
        out.append(("S5尾两字+店", core[-2:] + "店"))
    seen, uniq = set(), []
    for rule, s in out:
        if s and s != name and s not in seen:
            seen.add(s)
            uniq.append((rule, s))
    return uniq


def dish_shorthands(name: str) -> list:
    out = []
    stripped = re.sub(r"[（(].*?[)）]", "", name).strip()
    if stripped and stripped != name:
        out.append(("D1去括号规格", stripped))
    core = stripped or name
    if len(core) >= 4:
        out.append(("D2后三字", core[-3:]))
        out.append(("D3前三字", core[:3]))
    if len(core) >= 5:
        out.append(("D4前四字", core[:4]))
    seen, uniq = set(), []
    for rule, s in out:
        if s and s != name and s not in seen:
            seen.add(s)
            uniq.append((rule, s))
    return uniq


def exact_hit(mention: str, available: list) -> bool:
    """(a) `_validated_llm_store_names` 的判定, 一模一样。"""
    return mention in set(available)


async def like_hit(conn, factory_id: str, mention: str, table: str, col: str) -> list:
    """(b) `_canonicalize_store_mention` 的 SQL, 一模一样(表/列参数化)。"""
    exact = await conn.fetch(
        f"SELECT {col} AS name FROM {table} WHERE factory_id = $1 AND {col} = $2 LIMIT 1",
        factory_id, mention,
    )
    if exact:
        return [exact[0]["name"]]
    rows = await conn.fetch(
        f"""
        SELECT {col} AS name FROM {table}
         WHERE factory_id = $1
           AND ({col} LIKE '%' || $2 || '%' OR $2 LIKE '%' || {col} || '%')
         ORDER BY LENGTH({col}) ASC
         LIMIT 6
        """,
        factory_id, mention,
    )
    return [r["name"] for r in rows]


async def main() -> int:
    pool = await ctx.pool()
    fid = ctx.factory_id
    report = {"factory_id": fid, "db": ctx.db_name, "cases": [], "controls": []}

    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", fid)
            print("RLS上下文 =", await conn.fetchval("SELECT current_setting('app.factory_id', true)"))

            stores = [r["name"] for r in await conn.fetch(
                "SELECT name FROM dim_store WHERE factory_id = $1 ORDER BY name LIMIT 50", fid)]
            dish_tbl, dish_col = "dim_canonical_dish", "canonical_name"
            try:
                dishes = [r["name"] for r in await conn.fetch(
                    "SELECT canonical_name AS name FROM dim_canonical_dish "
                    "WHERE factory_id = $1 AND status = 'active' ORDER BY canonical_name LIMIT 50", fid)]
            except Exception as exc:  # noqa: BLE001
                print("dim_canonical_dish 读不到:", exc)
                dishes = []
            if not dishes:
                dish_tbl, dish_col = "dim_product", "name"
                dishes = [r["name"] for r in await conn.fetch(
                    "SELECT name FROM dim_product WHERE factory_id = $1 ORDER BY name LIMIT 50", fid)]

            print("STORES(%d):" % len(stores), json.dumps(stores[:20], ensure_ascii=False))
            print("DISHES(%d) from %s:" % (len(dishes), dish_tbl),
                  json.dumps(dishes[:20], ensure_ascii=False))
            report["stores"] = stores
            report["dishes"] = dishes
            report["dish_table"] = dish_tbl

            # ── 阳性/阴性对照 ─────────────────────────────────────────
            for label, mention, kind, table, col, avail in (
                ("阳性-门店全名", stores[0] if stores else "", "store", "dim_store", "name", stores),
                ("阳性-菜品全名", dishes[0] if dishes else "", "dish", dish_tbl, dish_col, dishes),
                ("阴性-无关词", "量子纠缠火箭发射器", "store", "dim_store", "name", stores),
                ("阴性-无关词", "量子纠缠火箭发射器", "dish", dish_tbl, dish_col, dishes),
            ):
                if not mention:
                    continue
                like = await like_hit(conn, fid, mention, table, col)
                report["controls"].append({
                    "label": label, "kind": kind, "mention": mention,
                    "exact": exact_hit(mention, avail), "like_n": len(like),
                    "like": like[:3],
                })

            # ── 简称批量 ──────────────────────────────────────────────
            for kind, names, table, col, gen in (
                ("store", stores, "dim_store", "name", store_shorthands),
                ("dish", dishes, "dim_product" if dish_tbl == "dim_product" else dish_tbl,
                 dish_col, dish_shorthands),
            ):
                for full in names[:10]:
                    for rule, sh in gen(full):
                        like = await like_hit(conn, fid, sh, table, col)
                        report["cases"].append({
                            "kind": kind, "full": full, "rule": rule, "mention": sh,
                            "exact": exact_hit(sh, names),
                            "like_n": len(like),
                            "like_correct": full in like,
                            "like_unique_correct": like == [full],
                            "like": like[:4],
                        })

    print("\n==== 对照 ====")
    for c in report["controls"]:
        print("  %-14s %-12r exact=%-5s like_n=%d %s" % (
            c["label"], c["mention"][:12], c["exact"], c["like_n"],
            json.dumps(c["like"], ensure_ascii=False)))

    print("\n==== 简称逐条 ====")
    for c in report["cases"]:
        print("  [%s] %-22s %-10s %-12s exact=%-5s like_n=%d correct=%-5s uniq=%-5s %s" % (
            c["kind"], c["full"][:22], c["rule"], c["mention"][:12], c["exact"],
            c["like_n"], c["like_correct"], c["like_unique_correct"],
            json.dumps(c["like"][:3], ensure_ascii=False)))

    print("\n==== 汇总 (口径: 分母=机械派生的简称数) ====")
    for kind in ("store", "dish"):
        rows = [c for c in report["cases"] if c["kind"] == kind]
        if not rows:
            continue
        n = len(rows)
        print("  %s n=%d  exact命中=%d(%.1f%%)  like含正确=%d(%.1f%%)  like唯一正确=%d(%.1f%%)  like零候选=%d" % (
            kind, n,
            sum(c["exact"] for c in rows), 100.0 * sum(c["exact"] for c in rows) / n,
            sum(c["like_correct"] for c in rows), 100.0 * sum(c["like_correct"] for c in rows) / n,
            sum(c["like_unique_correct"] for c in rows), 100.0 * sum(c["like_unique_correct"] for c in rows) / n,
            sum(1 for c in rows if c["like_n"] == 0),
        ))
    with open(OUT, "w", encoding="utf-8", newline="") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=1)
    print("\nWROTE", OUT)
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
