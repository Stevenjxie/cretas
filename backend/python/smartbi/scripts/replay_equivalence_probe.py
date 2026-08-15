"""回放等价性探针 —— `test_replay_equivalence_gate` 红了之后要跑的就是它。

## 判据（owner 2026-08-12 定，2026-08-13 复用）

**比执行产出，不比 plan spec 字段。** 三分类各报条数并逐条贴：
① 执行等价 ② 执行不等价 ③ 执行失败。第三类按 plan-spec 比对完全看不见，
是最该量出来的一类。

## 做法

⛔ 不重建内部调用链 —— 前三版都死在「我搭的环境和生产不一样」上。
   这一版只做一件事：在探针进程里 monkeypatch 指纹（仓库代码一行不动），
   让存量计划真的走 `_replay_zero_token_plan` → 真实执行链。
   B 遍恢复真指纹 = 今天线上的行为。

🔴 **2026-08-15 订正：A 遍那根撬棍自 08-13 起撬不动晋升闸了。**

   原文写的是「把指纹闸在探针进程里打开」，那句话**今天是假的**：

     探针撬的  `ri._routing_rules_fingerprint`
       它唯一的消费者是 restaurant_intent.py:2999 的**计划缓存版本键**
     晋升闸比的 restaurant_intent.py:3248-3249
       `plan_semantics_segment(row.fp) != _plan_semantics_fingerprint()`

   2026-08-13 的**指纹分层**把晋升闸换成了语义段比对，而撬棍还压在旧杠杆上。
   实测：08-13 02:25 那次 `hitA=True` 38/40，03:40 之后**每一次都是 0/40**。
   ⇒ 形态 B「机制在、没接上」——monkeypatch 照跑、不报错、什么都没打开。

   ⚠️ 在撬棍修好之前，本探针**无法产出任何等价性证据**。它现在能诚实回答的
      只有一件事：「今天有几条存量是合格的」（`eligible_stored`）。

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

#: 🔴 A 遍的撬棍。**撬的必须是晋升闸真正比的那个量。**
#:
#: 晋升闸(restaurant_intent.py:3248-3249)比的是
#:     plan_semantics_segment(row.routing_fingerprint) != _plan_semantics_fingerprint()
#: 所以撬棍压在 `_plan_semantics_fingerprint` 上, ⛔ 不是
#: `_routing_rules_fingerprint` —— 后者唯一的消费者是 :2999 的**计划缓存版本键**,
#: 压它对晋升闸**一点作用都没有**(2026-08-13 指纹分层之后)。
#: 实测代价: 08-13 02:25 `hitA=True` 38/40 → 03:40 起每次 0/40, 连续三天读数作废。
_REAL_SEM_FP_FN = ri._plan_semantics_fingerprint

#: ⚠️ 旧撬棍留着**只为变异对照**(证明「换了杠杆」这件事本身可观测), ⛔ 不参与判定。
_REAL_RULES_FP_FN = ri._routing_rules_fingerprint

#: 告警分级的产出文件。cron 只负责 `cat` 它, **判定在 Python 里**(可单测)。
#: ⛔ 空文件 = 不告警。(a) 类「存量按设计全失效」落台账**不告警** ——
#:    它每天都会发生, 而误报的告警最终会让所有告警一起被忽略(形态 E)。
ALERT_OUT = os.environ.get("PROBE_ALERT_OUT", "/tmp/replay_equivalence.alert")

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


#: 告警分级 —— **判定在这里, ⛔ 不在 shell 里**。cron 只负责把非空的那行追加进
#: 告警文件。这样它可单测, 而且不用在 shell 里解 JSON。
#:
#: 🔴 (a) 类不告警(owner 2026-08-15 裁定 ①):
#:    `eligible_stored == 0` 意思是「存量按设计全部失效, 等人逐条盖章」——
#:    **不是故障**, 而它每天都会发生。天天误报的告警最终会让**所有**告警
#:    一起被忽略(形态 E: 闸的完备性与闸的存活是矛盾的)。
#:    ⇒ 它照常落台账(`eligible_stored` 那一列), 只是不喊。
def alert_for(rc: int, *, positive_control, eligible_stored, stored_total) -> str:
    """这次跑批该不该喊、喊什么。返回空串 = 不喊。

    ⚠️ 三态见硬约束 4。rc=2 的三个成因处置完全不同, ⛔ 不许压成一句。
    """
    if rc == 0:
        return ""
    if rc != 2:
        return ("REPLAY EQUIV DRIFT — 有条目不再等价(指纹**可能没变**)")
    # rc == 2 的三个成因
    if not positive_control:
        return ("REPLAY EQUIV INSTRUMENT DEAD — 合成阳性对照没通过, "
                "格式门本身坏了; 本次读数作废")
    if not stored_total:
        return ("REPLAY EQUIV INSTRUMENT DEAD — 晋升表 0 行(plan_version 对不上), "
                "本次读数作废")
    if eligible_stored:
        return (f"REPLAY EQUIV INSTRUMENT DEAD — 有 {eligible_stored} 条合格存量"
                "却一条都没回放, A 遍撬棍没打开晋升闸; 本次读数作废")
    # (a): eligible_stored == 0 —— 按设计如此, ⛔ 不喊。
    return ""


def _write_alert(line: str) -> None:
    """⛔ **永远**落盘(空串就写空文件) —— 与 `_write_probe_out` 同一条纪律:
    不写的话 cron 会读到上一次的告警, 于是一条**昨天的**故障被当成今天的。
    """
    with open(ALERT_OUT, "w", encoding="utf-8", newline="") as fh:
        fh.write(line + "\n" if line else "")


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
        return _finish(2, 0, eligible_stored, len(rows))

    if not rows:
        # 🔴 早退也必须写产出文件。不写的话 cron 的 `[ -r ... ]` 会读到
        #    **上一次**留下的 json, 于是台账里出现「这次的 rc + 上次的计数」——
        #    一行混着两次运行的读数, 格式合法、字段齐全、不报错。
        #    2026-08-13 在日结那条链上实测出现过一次, 同形状在这里也成立。
        _write_probe_out([])
        print("⛔ 0 行 —— plan_version 对不上, 这不是「没有存量计划」而是仪器问题")
        return _finish(2, positive_control, 0, 0)

    recorder = HitRecorder()
    logging.getLogger("smartbi.gold.restaurant.restaurant_intent").setLevel(logging.INFO)
    logging.getLogger("smartbi.gold.restaurant.restaurant_intent").addHandler(recorder)

    out = []
    for i, row in enumerate(rows, 1):
        phrase, row_fp = row["normalized_phrase"], row["routing_fingerprint"]

        before = len(recorder.hits)
        # 🔴 A: 打开晋升闸。闸比的是
        #      plan_semantics_segment(row.fp) != _plan_semantics_fingerprint()
        #    所以让「活语义段」等于**这一行自己的**语义段, 这一行就过闸。
        #    旧格式行的语义段是空串, 于是这里也返回空串 —— 两边相等, 闸开。
        # ⛔ 不是压 `_routing_rules_fingerprint`: 那根杠杆自 08-13 指纹分层之后
        #    与晋升闸无关(它只喂计划缓存版本键), 压它等于没测。
        _row_sem = ri.plan_semantics_segment(row_fp or "")
        ri._plan_semantics_fingerprint = (lambda s=_row_sem: s)
        clear_caches()
        res_a, err_a = await ask(phrase)
        hit_a = len(recorder.hits) > before

        ri._plan_semantics_fingerprint = _REAL_SEM_FP_FN           # B: 今天线上的行为
        clear_caches()
        res_b, err_b = await ask(phrase)

        pa, pb = projection(res_a), projection(res_b)
        # ⓪ 拆两类(2026-08-15) —— 原来一个标签把两件事压在一起, 于是台账上
        #    「阳性对照 1」和「40 条阳性对照未命中」同行打架:
        #      · 这条**本来就不合格**(旧格式) → 不回放是**设计**, 不是故障
        #      · 这条**合格却没回放**       → 那才是仪器问题(撬棍失效)
        #    判定用的是与 `eligible_stored` **同一个表达式**, ⛔ 不另写一套。
        row_eligible = ri.plan_semantics_segment(row_fp or "") == live_sem
        if not hit_a:
            cls = ("⓪存量格式过期(按设计不回放)" if not row_eligible
                   else "⓪合格却没回放(仪器问题: A 遍撬棍失效)")
        elif is_failure(pa, err_a) or is_failure(pb, err_b):
            cls = "③执行失败"
        elif pa.get("kind") == pb.get("kind") and pa["kpis"] == pb["kpis"]:
            cls = "①执行等价"
        else:
            cls = "②执行不等价"
        out.append({"i": i, "phrase": phrase, "class": cls, "hit_a": hit_a,
                    "eligible": row_eligible,
                    "A": pa, "B": pb, "err_A": err_a, "err_B": err_b})
        print(f"[{i:>2}/{len(rows)}] {cls}  hitA={hit_a}  {phrase[:32]}")

    ri._plan_semantics_fingerprint = _REAL_SEM_FP_FN
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
        # 🔴 rc=2 一直都对(硬约束 4: 「这次没量到东西」), 错的是**诊断**。
        #    0 条命中有两个完全不同的成因, 处置也完全不同:
        if eligible_stored == 0:
            print(f"⛔ 0 条命中, 且 eligible_stored=0/{len(out)} —— "
                  "**存量按设计全部失效**(旧格式, 等人逐条盖章), ⛔ 不是仪器死了。"
                  " 本轮没有等价性证据, 但这不是故障。")
        else:
            print(f"⛔ 0 条命中, 而 eligible_stored={eligible_stored}/{len(out)} —— "
                  "**仪器问题**: 有合格条目却一条都没回放, A 遍撬棍没打开晋升闸"
                  "(见本文件头部 2026-08-15 订正)。")
        return _finish(2, positive_control, eligible_stored, len(rows))

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
    rc = 0 if counts["②执行不等价"] == 0 and counts["③执行失败"] == 0 else 1
    return _finish(rc, positive_control, eligible_stored, len(rows))


def _finish(rc, positive_control, eligible_stored, stored_total) -> int:
    """⛔ **每条 return 都走这里** —— 告警文件与产出文件同一条纪律: 永远落盘。

    不落的话 cron 会读到上一次的告警, 于是**昨天的**故障被当成今天的。
    """
    line = alert_for(rc, positive_control=positive_control,
                     eligible_stored=eligible_stored, stored_total=stored_total)
    _write_alert(line)
    print(f"\n# alert = {line or '(不告警)'}")
    return rc


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
