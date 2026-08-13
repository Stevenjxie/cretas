"""回放等价性探针 —— `test_replay_equivalence_gate` 红了之后要跑的就是它。

## 判据（owner 2026-08-12 定，2026-08-13 复用）

**比执行产出，不比 plan spec 字段。** 三分类各报条数并逐条贴：
① 执行等价 ② 执行不等价 ③ 执行失败。第三类按 plan-spec 比对完全看不见，
是最该量出来的一类。

## 做法

⛔ 不重建内部调用链 —— 前三版都死在「我搭的环境和生产不一样」上。
   这一版只做一件事：**把指纹闸在探针进程里打开**（monkeypatch，仓库代码一行不动），
   让存量计划真的走 `_replay_zero_token_plan` → 真实执行链。
   B 遍恢复真指纹 = 今天线上的行为。

🔴 **阳性对照**：A 遍必须能在日志里看到 `zero-token promoted-route hit`。
   看不到就说明 A 和 B 跑的是同一条路，「等价」是假的 —— 这条不通过整轮读数作废。

⛔ **清缓存用模块自带的 helper**（硬约束 3）：拼错的属性名不会报错，只会让清理
   静默失效。实测踩过：`_PLAN_CACHE` 根本不存在，同时漏清 `_ROUTE_CACHE`，
   读数 25/13 修正后是 29/9。

## 跑法

    scp backend/python/smartbi/scripts/replay_equivalence_probe.py \
        root@<host>:/tmp/cretas-probe/
    ssh <host> 'cd /tmp/cretas-probe && ./run.sh -u replay_equivalence_probe.py'

`run.sh` 负责四件套（整份服务进程环境 / venv 解释器 / prod 库 / 完整树）。
⚠️ 解释器要看活进程的 `cmdline`，**不要看 `exe`** —— 它会把 symlink 解析掉。
"""
import asyncio
import json
import logging
import os
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FACTORY = os.environ.get("PROBE_FACTORY", "MOCK_REST")
ctx = bootstrap_probe(FACTORY)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import tiered_answer  # noqa: E402

_REAL_FP_FN = ri._routing_rules_fingerprint

#: 执行失败的用户可见长相。⛔ 看到它先怀疑探针(见 `_probe_bootstrap`)。
FAILURE_MARKS = ("餐饮执行链暂时不可用", "系统这会儿有点忙", "系统这会儿没能弄懂")

#: 模块自带的清理 helper。⛔ 不要写属性名字符串去 getattr —— 拼错了静默 no-op。
_CACHE_CLEARERS = (
    "clear_semantic_plan_cache",
    "clear_route_cache",
    "clear_tenant_gate_cache",
    "clear_promoted_routes_cache",
)


class HitRecorder(logging.Handler):
    """阳性对照的采集器：只记 promoted-route hit 那一行。"""

    def __init__(self):
        super().__init__(level=logging.INFO)
        self.hits = []

    def emit(self, record):
        try:
            msg = record.getMessage()
        except Exception:  # noqa: BLE001
            return
        if "zero-token promoted-route hit" in msg:
            self.hits.append(msg)


def clear_caches(verbose=False):
    """硬约束 3：清了哪几个要能贴出来。"""
    cleared = []
    for name in _CACHE_CLEARERS:
        fn = getattr(ri, name, None)
        if not callable(fn):
            raise RuntimeError(
                f"清缓存 helper {name!r} 不存在 —— 名字改了。"
                f"⛔ 不要退化成「跳过它」: 那正是静默失效的形状。")
        fn()
        cleared.append(name)
    if verbose:
        print("# 已清缓存: " + ", ".join(cleared))
    return cleared


def projection(result):
    """执行产出的可比投影。⛔ 不含 plan spec 字段。

    ⚠️ `kind` **必须**参与比较：只比 kpis 时，「A 有答案但 0 个 KPI」和
       「B 反问也 0 个 KPI」会被判成等价。实测因此把 25/13 报成了 31/9。
    """
    if not isinstance(result, dict):
        return {"__shape__": type(result).__name__}
    kpis = []
    for k in result.get("kpis") or []:
        if isinstance(k, dict):
            kpis.append({kk: k.get(kk) for kk in ("label", "value", "unit") if kk in k})
        else:
            kpis.append(str(k))
    return {
        "kind": result.get("kind"),
        "kpis": kpis,
        "answer_head": (result.get("answer_text") or "")[:60],
    }


def is_failure(proj, error):
    if error is not None or not isinstance(proj, dict):
        return True
    if proj.get("kind") == "unavailable":
        return True
    return any(m in (proj.get("answer_head") or "") for m in FAILURE_MARKS)


async def ask(phrase):
    try:
        out = await tiered_answer(
            phrase, await ctx.pool(), FACTORY, ctx.role, include_result_meta=True)
        return out, None
    except Exception as exc:  # noqa: BLE001 — ③ 就是要抓住并计数, ⛔ 不许 continue
        return None, f"{type(exc).__name__}: {exc}"


def _write_probe_out(out) -> None:
    """产出**永远**落盘, 包括所有早退路径。

    ⛔ 「没量到」不等于「沿用上次」。不写文件时, 读者(cron)分不清
       「这次没产出」和「这次产出恰好和上次一样」—— 而它默认按后者办。
    """
    dest = os.environ.get("PROBE_OUT", "/tmp/replay_equivalence.json")
    with open(dest, "w", encoding="utf-8", newline="") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2)


async def main():
    pool = await ctx.pool()
    print(f"# _PLAN_VERSION = {getattr(ri, '_PLAN_VERSION', '?')!r}")
    print(f"# 当前路由指纹 = {_REAL_FP_FN()!r}")
    clear_caches(verbose=True)

    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY)
        rows = await conn.fetch(
            """
            SELECT normalized_phrase, plan_json, routing_fingerprint
              FROM ai_promoted_routes
             WHERE domain='restaurant' AND plan_version=$1
               AND (scope='global' OR scope=$2)
             ORDER BY normalized_phrase
            """,
            ri._PLAN_VERSION, FACTORY)
    print(f"# 表里可载入的行数 = {len(rows)}")

    # ── 台账拆两行(owner 2026-08-13 裁定 ⑥) ────────────────────────────
    #
    # 🔴 旧台账把两件事压成一个数, 于是「仪器坏了」和「存量确实没有」
    #    **长得一模一样**:
    #      (a) 回放这条机制今天还能不能开火   → 恒 0 = 仪器坏了
    #      (b) 存量里有多少条今天真的会回放   → 恒 0 = **真事实**
    #
    # positive_control: 用一条**合成的、当前格式**的指纹走格式门。恒 1, 不为 0。
    #   ⛔ 不打桩 `plan_semantics_segment` —— 那是「关掉闸来证明闸能开」, 恒真式。
    #   ⛔ 不往 ai_promoted_routes 写任何行 —— 合成值只在进程内。
    # ⚠️ **代理判据, 标出来**: 它验的是「当前格式能通过格式门」, 不验下游执行链。
    #    下游由 ①②③ 三分类覆盖。要替换它, 就把合成行注入
    #    `ri._PROMOTED_ROUTES_CACHE` 驱动一次真回放。
    live_sem = ri._plan_semantics_fingerprint()
    synthetic_fp = ri.compose_routing_fingerprint()
    positive_control = int(ri.plan_semantics_segment(synthetic_fp) == live_sem)

    # eligible_stored: 存量里 normalize 后能对上活语义段的条数。
    # ⚠️ 今天大概率是 0 —— 那**不是故障**, 是「39 条人审晋升是旧格式」这个事实的读数。
    eligible_stored = sum(
        1 for r in rows
        if ri.plan_semantics_segment(r["routing_fingerprint"] or "") == live_sem
    )
    print(f"# positive_control = {positive_control} (1=机制能开火)")
    print(f"# eligible_stored  = {eligible_stored}/{len(rows)} (存量今天真的会回放的条数)")
    if not positive_control:
        print("⛔ 合成阳性对照没通过 —— 格式门本身坏了, 本轮读数作废")
        # ⚠️ 产出形状与正常出口一致, 否则台账那一行会缺列(而缺列长得像 0)。
        _write_probe_out({"rows": [], "positive_control": 0,
                          "eligible_stored": eligible_stored,
                          "stored_total": len(rows)})
        return 2

    if not rows:
        # 🔴 早退也必须写产出文件。不写的话 cron 的 `[ -r ... ]` 会读到
        #    **上一次**留下的 json, 于是台账里出现「这次的 rc + 上次的计数」——
        #    一行混着两次运行的读数, 格式合法、字段齐全、不报错。
        #    2026-08-13 在日结那条链上实测出现过一次, 同形状在这里也成立。
        _write_probe_out([])
        print("⛔ 0 行 —— plan_version 对不上, 这不是「没有存量计划」而是仪器问题")
        return 2

    recorder = HitRecorder()
    logging.getLogger("smartbi.gold.restaurant.restaurant_intent").setLevel(logging.INFO)
    logging.getLogger("smartbi.gold.restaurant.restaurant_intent").addHandler(recorder)

    out = []
    for i, row in enumerate(rows, 1):
        phrase, row_fp = row["normalized_phrase"], row["routing_fingerprint"]

        before = len(recorder.hits)
        ri._routing_rules_fingerprint = (lambda fp=row_fp: fp)   # A: 打开指纹闸
        clear_caches()
        res_a, err_a = await ask(phrase)
        hit_a = len(recorder.hits) > before

        ri._routing_rules_fingerprint = _REAL_FP_FN               # B: 今天线上的行为
        clear_caches()
        res_b, err_b = await ask(phrase)

        pa, pb = projection(res_a), projection(res_b)
        if not hit_a:
            cls = "⓪阳性对照未命中(本条分类作废)"
        elif is_failure(pa, err_a) or is_failure(pb, err_b):
            cls = "③执行失败"
        elif pa.get("kind") == pb.get("kind") and pa["kpis"] == pb["kpis"]:
            cls = "①执行等价"
        else:
            cls = "②执行不等价"
        out.append({"i": i, "phrase": phrase, "class": cls, "hit_a": hit_a,
                    "A": pa, "B": pb, "err_A": err_a, "err_B": err_b})
        print(f"[{i:>2}/{len(rows)}] {cls}  hitA={hit_a}  {phrase[:32]}")

    ri._routing_rules_fingerprint = _REAL_FP_FN
    # 🔴 两个数写进产出, 台账才看得见拆分。⛔ 不要只 print ——
    #    台账读的是这份 json, print 只进日志。
    _write_probe_out({
        "rows": out,
        "positive_control": positive_control,
        "eligible_stored": eligible_stored,
        "stored_total": len(rows),
    })

    hits = sum(1 for r in out if r["hit_a"])
    print("\n=== 阳性对照 ===")
    print(f"A 遍命中晋升的条数 = {hits}/{len(out)}")
    if hits == 0:
        print("⛔ 0 条命中 —— A 和 B 跑的是同一条路, 本轮读数作废(仪器没活)")
        return 2

    from collections import Counter
    print("\n=== 三分类(贴这一段进 PR) ===")
    counts = Counter(r["class"] for r in out)
    for k, v in counts.most_common():
        print(f"{k}: {v}")
    print("\n=== ②/③ 逐条 ===")
    for r in out:
        if r["class"].startswith(("②", "③")):
            print(f"  [{r['i']:>2}] A={r['A'].get('kind')} B={r['B'].get('kind')}  {r['phrase']}")
    print(f"\n明细: {os.environ.get('PROBE_OUT', '/tmp/replay_equivalence.json')}")
    return 0 if counts["②执行不等价"] == 0 and counts["③执行失败"] == 0 else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
