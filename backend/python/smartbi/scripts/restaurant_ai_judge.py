"""离线判定 + 「judge 判定 vs 现有断言」四象限对比表。

    # ① 电池跑一次, 把答案存下来(这一步花的是电池的钱)
    python -m smartbi.scripts.restaurant_ai_eval \
        --only '亏钱,哪个菜卖得好,这月挣了多少' --record /tmp/answers.jsonl
    # ② 判定离线跑, 想跑几次跑几次(这一步每题 ~1,800 token, 不碰生产系统)
    python -m smartbi.scripts.restaurant_ai_judge /tmp/answers.jsonl

## 为什么分成两步

一轮全量电池 47万~130万 token；判定每题 ~1,800。绑在一起跑，就意味着
**每改一次评分卡就要重新问一遍生产系统** —— 而评分卡这种东西第一版必然要改几轮。
存下来之后，换提示词、加一条判据、调阈值，全都零电池成本。

## 这张表要回答的问题

**不是**「AI 答得好不好」，是「**我们今天的仪器，看得见什么、看不见什么**」。

|  | judge 通过 | judge 不通过 |
|---|---|---|
| **断言通过** | 一致通过 | 🔴 **盲区** —— 今天全绿, 但答案有问题 |
| **断言不通过** | 🟡 **误报** —— 断言挂在措辞上, 行为其实没错 | 一致失败 |

- 🔴 盲区格 = 「删 645 条 + 19 个意图」这台手术**今天没有仪器能守住**的部分。
- 🟡 误报格 = 该换成 judge 的那些 `contains`（**别照着感觉挑, 照这一格挑**）。

⛔ 「判不了」单独一列，不并进任何一格 —— 把判不了折进「通过」就是沉默即通过。
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
from typing import Any, Dict, List, Optional, Sequence, Tuple

#: 并发判定数。判定走 REVIEW 槽, 与规划器同池 —— 开太大等于自己跟自己抢链头,
#: 而链头一挤就开始超时重试, 那才是真正烧 token 的形态(计划文档实测: 链头不
#: 健康时单题从 4,600 涨到 1.5-2 万)。
_CONCURRENCY = 4

VERDICT_PASS, VERDICT_FAIL, VERDICT_UNKNOWN = "pass", "fail", "unknown"


def battery_required_vocabulary(cases: Optional[Sequence[Dict[str, Any]]] = None) -> frozenset:
    """电池**要求必须出现**的词 —— 判定模型说它们是黑话时不予采纳。

    🔴 2026-08-12 owner 拍板「成本覆盖率是业务词」时的落地方式。
       起因: 判定模型把 [21] 的 `成本覆盖率` 报成黑话, 而**同一条用例的
       `contains` 明确要求它出现** —— 两个判据直接打架。

    ⛔ **刻意不手写白名单。** 本仓判据: 判据里出现手写清单就问「这张表错了会
       怎样」——答: 多写一个词就永久静音一类真问题, 而且没人会去复查那张表。
       这里换成可推导的规则:

           电池断言「这个词必须出现」  ⇒  我们自己认定它是该说的话
                                      ⇒  再判它是黑话就是自相矛盾

       加/减词的动作因此发生在**电池断言**里(那是有人会读、会红的地方),
       而不是发生在一张只增不减的白名单里。

    ⚠️ 例外优先级: `INTERNAL_VOCAB` / `ANALYST_JARGON` **压过**本推导 ——
       万一哪天有人往 `contains` 里写了 `维度`, 那是电池的错, 不该因此把
       「内部概念词漏给店长」静音。

    ``cases``: 只为测试注入。⚠️ 2026-08-12 加这个参数是因为变异实测发现:
       拿**真实 CASES** 测「黑话优先」那条断言时它**恒真** —— 今天没有任何
       `contains` 含黑话词, 所以去掉 `- banned` 也不会红。一条不可能红的断言
       不是断言。改成可注入合成用例后, 测的是**这个函数的行为**, 而不是
       「今天的电池恰好没写错」。
       (电池今天没写错这件事由 `test_battery_never_requires_a_word_it_also_calls_jargon`
        单独守着 —— 那条读真实 CASES, 两条各司其职。)
    """
    from smartbi.gold.customer_text import ANALYST_JARGON, INTERNAL_VOCAB

    if cases is None:
        from smartbi.scripts.restaurant_ai_eval import CASES as cases

    banned = set(INTERNAL_VOCAB) | set(ANALYST_JARGON)
    words = set()
    for case in cases:
        for marker in case.get("contains", ()):
            token = str(marker).strip("「」 ")
            # 只收「像个词」的: 太短的(？/单字)和带数字的(日期回显)都不是词汇判据
            if len(token) >= 2 and not any(ch.isdigit() for ch in token):
                words.add(token)
    return frozenset(words - banned)

#: 四象限的键。
Q_AGREE_PASS = "一致通过"
Q_BLIND = "盲区(断言绿·判定红)"
Q_FALSE_ALARM = "误报(断言红·判定绿)"
Q_AGREE_FAIL = "一致失败"
Q_UNJUDGED = "判不了"


def load_records(path: str) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
    """读 JSONL。第一行是 `_meta`（取数条件），其余是逐题记录。"""
    meta: Dict[str, Any] = {}
    rows: List[Dict[str, Any]] = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            if "_meta" in obj:
                meta = obj["_meta"]
            else:
                rows.append(obj)
    return meta, rows


def quadrant(assertion_failed: bool, judge_verdict: str) -> str:
    """一条记录落在哪一格。纯函数 —— 四象限的定义本身值得被单测钉住。

    ⛔ `unknown` **不折叠**。折进 pass 就是「判不了当没问题」(沉默即通过),
       折进 fail 就是拿判定模型的宕机制造假盲区, 两个方向都会污染这张表 ——
       而这张表接下来要决定 P-A 删哪些东西。
    """
    if judge_verdict == VERDICT_UNKNOWN:
        return Q_UNJUDGED
    if assertion_failed:
        return Q_AGREE_FAIL if judge_verdict == VERDICT_FAIL else Q_FALSE_ALARM
    return Q_BLIND if judge_verdict == VERDICT_FAIL else Q_AGREE_PASS


def build_comparison(rows: Sequence[Dict[str, Any]],
                     verdicts: Sequence[Any]) -> Dict[str, List[Dict[str, Any]]]:
    """把 (记录, 判定) 配对分进四象限 + 判不了。纯函数, 可单测。"""
    buckets: Dict[str, List[Dict[str, Any]]] = {
        Q_BLIND: [], Q_FALSE_ALARM: [], Q_AGREE_FAIL: [],
        Q_AGREE_PASS: [], Q_UNJUDGED: [],
    }
    for row, verdict in zip(rows, verdicts):
        assertion_problems = list(row.get("assertion_problems") or [])
        cell = quadrant(bool(assertion_problems), verdict.verdict)
        buckets[cell].append({
            "idx": row.get("idx"),
            "q": row.get("q", ""),
            "assertion_problems": assertion_problems,
            "judge_problems": verdict.problems,
            "advisory": verdict.advisory,
            "unavailable": verdict.unavailable,
            "message": row.get("message", ""),
        })
    return buckets


def _fmt_problems(items: Sequence[str], limit: int = 3) -> str:
    if not items:
        return "—"
    shown = "; ".join(items[:limit])
    return shown + (f" (+{len(items) - limit})" if len(items) > limit else "")


def render_report(meta: Dict[str, Any],
                  buckets: Dict[str, List[Dict[str, Any]]]) -> str:
    """渲染成给 owner 读的 markdown。**分歧两格排在最前面** —— 注意力花在那里。"""
    total = sum(len(v) for v in buckets.values())
    out: List[str] = ["# judge 判定 vs 现有断言 —— 对比表", ""]

    prov = (meta.get("provenance") or {})
    out += ["## 取数条件", ""]
    if prov.get("unavailable"):
        out.append(f"⚠️ **不可知**（{prov['unavailable']}）—— 不要拿本轮与别轮直接比。")
    else:
        hits, fresh = prov.get("cache_hits", 0), prov.get("fresh_parses", 0)
        out.append(f"- 计划缓存：命中 {hits} / 真解析 {fresh}"
                   + ("（✅ 全冷）" if hits == 0 else
                      f"（⚠️ 其中 {hits} 题吃的是先前轮次写进缓存的计划，不是独立样本）"))
        if prov.get("models"):
            out.append("- 服务模型：" + "、".join(
                f"{m} ×{n}" for m, n in sorted(
                    prov["models"].items(), key=lambda kv: (-kv[1], kv[0]))))
    out += [f"- 子集：`--only {meta.get('only') or '(全量)'}`，共 {total} 题", ""]

    out += ["## 四象限", "",
            "| | judge 通过 | judge 不通过 |",
            "|---|---|---|",
            f"| **断言通过** | {len(buckets[Q_AGREE_PASS])} 一致通过 "
            f"| 🔴 **{len(buckets[Q_BLIND])} 盲区** |",
            f"| **断言不通过** | 🟡 **{len(buckets[Q_FALSE_ALARM])} 误报** "
            f"| {len(buckets[Q_AGREE_FAIL])} 一致失败 |",
            "",
            f"另有 **{len(buckets[Q_UNJUDGED])} 题判不了**"
            "（判定模型不可用/输出不可解析）——⛔ 不计入任何一格，"
            "它既不是通过也不是失败。", ""]

    for title, key, why in (
        ("🔴 盲区：断言全绿，判定说有问题", Q_BLIND,
         "**这一格就是「删 645 条」这台手术今天没有仪器守着的部分。**"
         "每一条都要读原文确认判定是对的，再决定补哪种断言。"),
        ("🟡 误报：断言红了，判定说答案没毛病", Q_FALSE_ALARM,
         "**该换成 judge 的 `contains` 照这一格挑，别照感觉挑。**"
         "电池注释里已记过两次同型（[02] 「哪一组门店」、[18] 「. 打包盒」）："
         "断言挂在措辞/排版上，换个说法就误报，而它守的从来不是措辞。"),
        ("判不了", Q_UNJUDGED,
         "判定链路本身的问题，不是被测系统的问题。数量多说明这批读数不可用。"),
        ("一致失败", Q_AGREE_FAIL, "两边都说有问题 —— 真回归，优先修。"),
    ):
        items = buckets[key]
        out += [f"## {title}（{len(items)}）", "", why, ""]
        if not items:
            out += ["（空）", ""]
            continue
        out += ["| # | 问句 | 现有断言说 | 判定说 |", "|---|---|---|---|"]
        for item in items:
            out.append(
                f"| {item['idx']} | {item['q'][:28]} "
                f"| {_fmt_problems(item['assertion_problems'])} "
                f"| {_fmt_problems(item['judge_problems']) if not item['unavailable'] else item['unavailable']} |")
        out.append("")

    out += ["## 一致通过（%d）" % len(buckets[Q_AGREE_PASS]), "",
            "两边都说没问题。**这不等于答案是对的** —— 判定模型手里没有数据库，"
            "「数字对不对」这条判据两边都看不见（见 `answer_quality_judge` 模块注释）。", ""]

    # ── 判据④：只报，不计入上面任何一格 ────────────────────────────────
    advisories = [(item, cell) for cell, items in buckets.items()
                  for item in items if item.get("advisory")]
    out += [f"## 数字存疑（{len(advisories)}）—— 仅供参考，**不计入四象限**", "",
            "🔴 2026-08-12 owner 拍板降级。当轮实测：判据④在**真实语料**上唯一一次"
            "开火，两条全是误报，且同一形态 —— 一句话里挂着两个指标的数字，"
            "判定模型绑错了主语。它在**合成样本**上是准的，所以不砍、降级。",
            "",
            "⛔ 读这一段要**逐条看原文**再下结论；把它当结论用，就是把两个假阳性"
            "打进盲区格，而那张表要用来排 P-A 的刀。", ""]
    if not advisories:
        out += ["（空）", ""]
    else:
        out += ["| # | 问句 | 判定模型说 | 它落在哪一格 |", "|---|---|---|---|"]
        for item, cell in advisories:
            out.append(f"| {item['idx']} | {item['q'][:24]} "
                       f"| {_fmt_problems(item['advisory'])} | {cell} |")
        out.append("")
    return "\n".join(out)


async def _judge_all(rows: Sequence[Dict[str, Any]]) -> List[Any]:
    from smartbi.gold.restaurant.answer_quality_judge import judge_answer_quality

    semaphore = asyncio.Semaphore(_CONCURRENCY)
    allow = battery_required_vocabulary()

    async def one(row: Dict[str, Any]):
        async with semaphore:
            return await judge_answer_quality(
                row.get("q", ""), row.get("message", ""),
                table_problems=row.get("table_problems") or (),
                allow=tuple(allow),
            )

    return list(await asyncio.gather(*(one(r) for r in rows)))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("record", help="restaurant_ai_eval --record 落的 JSONL")
    parser.add_argument("--out", default="", help="报告写到这个文件（默认打到 stdout）")
    parser.add_argument("--limit", type=int, default=0,
                        help="只判前 N 题（省 token 的预演；0=全判）")
    args = parser.parse_args()

    meta, rows = load_records(args.record)
    if args.limit:
        rows = rows[:args.limit]
    if not rows:
        print(f"FATAL: {args.record} 里没有可判的记录")
        sys.exit(2)

    verdicts = asyncio.run(_judge_all(rows))
    report = render_report(meta, build_comparison(rows, verdicts))

    unjudged = sum(1 for v in verdicts if v.verdict == VERDICT_UNKNOWN)
    if unjudged == len(rows):
        # ⛔ 全部判不了 = 这轮什么都没测到。必须让退出码说话, 否则一张
        #    「盲区 0 条」的空表读起来跟「一切正常」一模一样。
        print(report)
        print("\nFATAL: 全部 %d 题都判不了 —— 本轮没有产生任何判定。" % len(rows))
        sys.exit(3)

    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(report)
        print(f"报告已写入 {args.out}（{len(rows)} 题，判不了 {unjudged} 题）")
    else:
        print(report)


if __name__ == "__main__":
    main()
