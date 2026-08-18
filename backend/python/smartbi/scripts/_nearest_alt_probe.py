"""量「最接近的替代」候选**在真租户上到底能不能算出来**。

⛔ 这个探针**不验收本轮改动**（prod 上跑的是部署后的活代码，改动还没上去）。
   它只量两件事：
     ① 现状：各租户三层（registry ∩ schema ∩ 本租户列非空值）的实测读数
     ② 候选替代（我准备声明的那几个已登记指标）**逐租户**能不能算

阳性对照：`营收` 必须在至少一个租户上算得出来。读成「全都算不出来」= 仪器坏了
（形态 A：会读出「全无」的仪器必须配一条已知应为「有」的探针）。
阴性对照：MOCK_REST 的 `税额`/`实收` 已知填充率 0，必须**不**出现在可算清单里。
"""
from __future__ import annotations

import asyncio
import json
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

TENANTS = ("MOCK_REST", "RES_3101_009", "DEMO_REST", "R_XMX_CHAIN", "R_GML_DEMO")

#: 我准备声明的「近邻」= 这个算不出来的量，**由哪几个已登记指标构成**。
#: ⛔ 这里只是探针里的候选，落地时它要住在 restaurant_intent.py 并配闸。
CANDIDATES = {
    "net_profit": ("gross_profit", "revenue"),
    "table_turnover": ("orders",),
    "return_rate": ("return_qty",),
    "customer_review": (),
    "production_time": (),
    "service_speed": (),
    "process_bottleneck": (),
}

UNSUPPORTED = tuple(CANDIDATES)


def _computable_base(schema_columns, counts, metrics, banned):
    """三层都过的基础指标 key。与 `computable_labels` 同法，这里要 key 不要 label。"""
    out = []
    for key, metric in metrics.items():
        if key in banned:
            continue
        requires = tuple(getattr(metric, "requires", ()) or ())
        if not requires:
            continue
        if not all(col in schema_columns for col in requires):
            continue
        if not all(counts.get(col, 0) > 0 for col in requires):
            continue
        out.append(key)
    return out


def _close_over_derived(base, derived, banned):
    """派生量：左右两侧都算得出来才算得出来。跑到不动点。"""
    ok = set(base)
    changed = True
    while changed:
        changed = False
        for key, d in derived.items():
            if key in ok or key in banned:
                continue
            if getattr(d, "left", None) in ok and getattr(d, "right", None) in ok:
                ok.add(key)
                changed = True
    return ok


async def one_tenant(fid):
    from smartbi.gold.queries import tenant_conn
    from smartbi.gold.restaurant.generic_executor import existing_columns
    from smartbi.gold.restaurant.metric_registry import METRICS, DERIVED
    from smartbi.config import get_pg_pool
    from smartbi.tenant_ctx import set_factory_id

    # ⚠️ 每个租户单独设上下文再单独取连接 —— RLS 是连接级的，
    #    一条连接上量两个租户会得到「除第一个外全 0」的整齐假读数。
    set_factory_id(fid)
    pool = await get_pg_pool()

    needed = {}
    for key, metric in METRICS.items():
        for col in (getattr(metric, "requires", ()) or ()):
            needed.setdefault(str(col).split(".", 1)[0], []).append(str(col))

    async with tenant_conn(pool, fid) as conn:
        who = await conn.fetchval("SELECT current_setting('app.factory_id', true)")
        schema_columns = await existing_columns(conn)
        counts = {}
        for table, cols in needed.items():
            live = [c for c in cols if c in schema_columns]
            if not live:
                for c in cols:
                    counts[c] = 0
                continue
            exprs = ", ".join(
                'count(%s)::int AS "%s"' % (c.split(".", 1)[1], c) for c in live)
            row = await conn.fetchrow(
                "SELECT %s FROM %s WHERE factory_id = $1" % (exprs, table), fid)
            for c in cols:
                counts[c] = int((row or {}).get(c) or 0) if c in live else 0

    banned = set(UNSUPPORTED)
    base = _computable_base(schema_columns, counts, METRICS, banned)
    ok = _close_over_derived(base, DERIVED, banned)

    def label_of(key):
        m = METRICS.get(key) or DERIVED.get(key)
        return getattr(m, "label", key) if m else key

    alts = {}
    for code, cands in CANDIDATES.items():
        hit = [c for c in cands if c in ok]
        alts[code] = (label_of(hit[0]) if hit else None,
                      [(c, c in ok) for c in cands])

    return {
        "factory_id": fid,
        "rls_says": who,
        "counts": {k: v for k, v in sorted(counts.items())},
        "computable": sorted(label_of(k) for k in ok),
        "alternatives": alts,
    }


async def main():
    out = []
    for fid in TENANTS:
        try:
            out.append(await one_tenant(fid))
        except Exception as exc:  # noqa: BLE001 - 失败要计数并逐条贴, ⛔ 不 continue
            out.append({"factory_id": fid, "error": "%s: %s" % (type(exc).__name__, exc)})

    for row in out:
        print("=" * 72)
        print("租户 %s  RLS上下文=%r" % (row["factory_id"], row.get("rls_says")))
        if "error" in row:
            print("  🔴 失败: %s" % row["error"])
            continue
        print("  列非空值计数:")
        for col, n in row["counts"].items():
            print("    %-52s %d" % (col, n))
        print("  可算出来的（含派生）: %s" % "、".join(row["computable"]))
        print("  候选替代逐条:")
        for code, (winner, detail) in row["alternatives"].items():
            print("    %-20s → %-12s  候选明细=%s"
                  % (code, winner or "（不给替代）", detail))

    print("=" * 72)
    # ── 对照 ──────────────────────────────────────────────────────────────
    good = [r for r in out if "error" not in r]
    any_revenue = any("营收" in r["computable"] for r in good)
    print("阳性对照 营收在至少一个租户上可算: %s" % any_revenue)
    mock = next((r for r in good if r["factory_id"] == "MOCK_REST"), None)
    if mock:
        print("阴性对照 MOCK_REST 税额不在可算清单: %s（实收: %s）"
              % ("税额" not in mock["computable"], "实收" not in mock["computable"]))
    print("成功租户 %d / %d" % (len(good), len(TENANTS)))
    if not any_revenue:
        print("🔴 阳性对照没过 —— 本轮读数作废，先查仪器")
        sys.exit(2)
    print(json.dumps({r["factory_id"]: r.get("alternatives") and
                      {k: v[0] for k, v in r["alternatives"].items()}
                      for r in good}, ensure_ascii=False))


if __name__ == "__main__":
    bootstrap_probe("MOCK_REST")
    asyncio.run(main())
