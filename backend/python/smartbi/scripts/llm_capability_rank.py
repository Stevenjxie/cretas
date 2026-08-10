#!/usr/bin/env python
"""按**实测能力**给 (账号,模型) 排名 —— `_build_chain` 的排序输入。

## 为什么需要它

`_build_chain` 原本纯按免费额度到期日升序拼链(use-it-or-lose-it)。那条策略
省额度, 但它对「这个模型答不答得对」**一无所知** —— 到期日早的模型排在链头,
拿走全部流量, 哪怕它在真实 prompt 上契约不合格。

⚠️ 更要紧的是 `_expiry_of` 的 docstring 一直写着 "soonest-expiry-first
   **WITHIN a quality tier**" —— 而 `_build_chain` 是**全局**排序, 没有 tier
   这一层。意图在注释里, 约束不存在(与 2026-08-09 那次 "Runtime order is
   authoritative" 同一种病)。本脚本产出的就是那个缺失的 tier, 并且是量出来的。

## 判据(两条, 缺一不可)

1. **prompt 同源** —— 用生产自己的 `_build_t3_prompt`。另写一份评测 prompt
   等于在测「模型对我编的题的表现」, 与链上真正发生的事无关。
2. **评分同源** —— 用生产自己的 `_t3_contract_violation` 当判据, ⛔ 不写答案
   标准答案表。一份手写答案表就是第二张会漂移的镜子(本仓 32 处静默耦合的
   同一种形状), 而且「契约不合格」正是 call_chain 真正会拒绝的那件事 ——
   模型在链上唯一需要通过的考试就是它。

题目也不手挑: 取回归电池 `CASES` 里**每条链的首问 + 所有无上下文依赖的单问**
(chain is None), 去重后按出现顺序取前 N —— 全是真实用户问句, 且选法是算出来的。

## 延迟闸(为什么高分也可能沉底)

2026-08-09 实测: tencent/minimax-m2.7 在真实 T3 prompt 上 3/3 满分, 但 13.4s。
把它放链头只会把「答不出来」换成「等到超时」—— call_chain 的总预算被它吃光,
后面健康的模型一个都轮不上。所以中位延迟超过槽的单跳预算 → 直接沉底,
分数再高也不例外。

用法:
    python -m smartbi.scripts.llm_capability_rank            # REVIEW 槽全池
    python -m smartbi.scripts.llm_capability_rank --slot insights --n 6
    python -m smartbi.scripts.llm_capability_rank --emit-table  # 打印可粘贴的注册表

退出码: 0 = 正常产出; 1 = 池内**没有**任何模型达标(链会退化, 该告警)
"""
from __future__ import annotations

import argparse
import asyncio
import json
import re
import statistics
import sys
import time
from typing import Any, Dict, List, Optional, Sequence, Tuple

import httpx

sys.path.insert(0, ".")

from common import llm_router as r  # noqa: E402

# 达标线: 契约通过率。低于它的模型不该排在任何健康模型前面。
_PASS_FLOOR = 0.5


def _query_set(limit: int) -> List[str]:
    """真实用户问句, 选法算出来而不是手挑。

    每条链的**首问**是自足的(链从那里开始, 没有上文); `chain is None` 的条目
    本来就是单问。两者合起来去重, 按 CASES 里的出现顺序取前 limit ——
    顺序固定, 所以不同模型、不同日期的分数可比。
    """
    from smartbi.scripts.restaurant_ai_eval import CASES

    picked: List[str] = []
    seen_chain = set()
    for case in CASES:
        chain = case.get("chain")
        if chain is not None:
            if chain in seen_chain:
                continue
            seen_chain.add(chain)
        q = case.get("q")
        if q and q not in picked:
            picked.append(q)
    return picked[:limit]


async def _score_one(
    client: httpx.AsyncClient,
    account: str,
    model: str,
    slot: r.SLOT,
    queries: Sequence[str],
) -> Dict[str, Any]:
    """对一个 (账号,模型) 跑完整题集, 返回 {passed, total, latencies, reasons}。"""
    from smartbi.gold.restaurant import restaurant_intent as ri

    base, key = r._provider_config(account)
    passed = 0
    latencies: List[float] = []
    reasons: List[str] = []

    for query in queries:
        prompt = ri._build_t3_prompt(query, None, None, (), None)
        normalized = r._normalize_payload_for_provider({
            "model": model,
            "messages": [
                {"role": "system",
                 "content": "你只输出JSON格式的意图解析结果，不输出任何其他文字。"},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0,
            "max_tokens": ri._SEMANTIC_MAX_TOKENS,
        }, account)
        payload = r._apply_slot_params(slot, account, model, normalized)

        t0 = time.monotonic()
        try:
            resp = await client.post(
                base.rstrip("/") + "/chat/completions",
                json=payload,
                headers={"Authorization": f"Bearer {key}"},
                timeout=60.0,
            )
        except Exception as exc:  # noqa: BLE001 — 网络层任何异常都算不合格
            reasons.append(type(exc).__name__)
            continue
        latencies.append(time.monotonic() - t0)

        if not 200 <= resp.status_code < 300:
            reasons.append(
                "quota" if r._is_quota_exhausted(resp.status_code, resp.text)
                else f"http{resp.status_code}")
            continue
        try:
            content = (json.loads(resp.text)["choices"][0]["message"]
                       .get("content") or "")
        except Exception:  # noqa: BLE001
            reasons.append("unparseable_envelope")
            continue

        # ⛔ 判据就是生产的判据。content_validator=_t3_contract_violation 是
        #    call_chain 真正用来接受/拒绝一次应答的那个函数。
        violation = ri._t3_contract_violation(content)
        if violation is None:
            passed += 1
        else:
            reasons.append(violation)

    return {
        "account": account,
        "model": model,
        "passed": passed,
        "total": len(queries),
        "rate": passed / len(queries) if queries else 0.0,
        "p50": statistics.median(latencies) if latencies else None,
        "reasons": reasons,
    }


def _budget_for(slot: r.SLOT) -> float:
    """槽的单跳预算 —— 中位延迟超过它的模型沉底(见模块 docstring 的延迟闸)。"""
    from smartbi.gold.restaurant import restaurant_intent as ri

    if slot is r.SLOT.REVIEW:
        return float(ri._SEMANTIC_PROVIDER_TIMEOUT_SECONDS)
    return float(ri._T3_PROVIDER_TIMEOUT_SECONDS)


def _rank(rows: List[Dict[str, Any]], budget: float) -> List[Dict[str, Any]]:
    """排序键: (达标? 降序, 在预算内? 降序, 通过率降序, p50 升序, 到期日升序)。

    到期日**仍然是键**, 只是降到最后一位 —— 同能力同速度时依旧优先榨干快到期
    的免费额度(原策略的合理内核), 但它不再能把一个不合格的模型顶到链头。
    """
    def key(row: Dict[str, Any]) -> Tuple:
        p50 = row["p50"]
        within = p50 is not None and p50 <= budget
        return (
            -(1 if row["rate"] >= _PASS_FLOOR else 0),
            -(1 if within else 0),
            -row["rate"],
            p50 if p50 is not None else 9e9,
            r._expiry_of(row["account"], row["model"]),
        )

    return sorted(rows, key=key)


async def _run(slot: r.SLOT, limit: int) -> List[Dict[str, Any]]:
    queries = _query_set(limit)
    pool = list(dict.fromkeys(r._SLOT_POOLS[slot]))
    sem = asyncio.Semaphore(4)
    rows: List[Dict[str, Any]] = []

    async with httpx.AsyncClient() as client:
        async def one(pair: Tuple[str, str]) -> None:
            async with sem:
                rows.append(await _score_one(client, pair[0], pair[1], slot, queries))

        await asyncio.gather(*(one(p) for p in pool))
    return rows


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--slot", default="review")
    ap.add_argument("--n", type=int, default=6, help="题目数 (真实电池问句)")
    ap.add_argument("--emit-table", action="store_true",
                    help="额外打印可粘贴进 llm_router._CAPABILITY_RANK 的字面量")
    args = ap.parse_args(argv)

    slot = r.SLOT[args.slot.upper()]
    budget = _budget_for(slot)
    rows = asyncio.run(_run(slot, args.n))
    ranked = _rank(rows, budget)

    print(f"[capability] slot={slot.name} 题目={args.n} 单跳预算={budget}s "
          f"达标线={_PASS_FLOOR:.0%}")
    for i, row in enumerate(ranked):
        p50 = f"{row['p50']:.1f}s" if row["p50"] is not None else "  n/a"
        flag = "" if row["rate"] >= _PASS_FLOOR else "  ⚠不达标"
        if row["p50"] is not None and row["p50"] > budget:
            flag += "  ⚠超预算"
        why = ""
        if row["reasons"]:
            why = "  " + ",".join(sorted(set(row["reasons"]))[:3])
        print(f"  {i:2d}. {row['account']:>10}/{row['model']:<32} "
              f"{row['passed']}/{row['total']}  {p50}{flag}{why}")

    qualified = [x for x in ranked
                 if x["rate"] >= _PASS_FLOOR
                 and x["p50"] is not None and x["p50"] <= budget]
    print(f"\n达标且在预算内: {len(qualified)}/{len(ranked)}")

    if args.emit_table:
        print("\n# ── 粘贴进 llm_router._CAPABILITY_RANK ──")
        for i, row in enumerate(ranked):
            # ⚠️ p50 为 None(全程网络失败)的条目**照样要打印** —— 少一行会让
            #    注册表缺项, 而缺项在 _capability_rank_of 里是「排最后」的静默
            #    默认值, 看不出是没测到还是测得差。
            lat = f"{row['p50']:.1f}s" if row["p50"] is not None else "n/a"
            print(f'    ("{row["account"]}", "{row["model"]}"): {i},'
                  f'   # {row["passed"]}/{row["total"]}  {lat}')

    # 池里一个达标的都没有 = 链整体退化, 属于该叫醒人的信号。
    return 0 if qualified else 1




# ═══════════════════════════════════════════════════════════════════════════
# `--compare` —— 拿「已知好用的模型」当参照，量候选在**难题**上的槽位一致率。
#
# 为什么不写标准答案表: 这些题的正确编码方式我只能猜(「昨天」该编成
# {"type":"named","value":"yesterday"} 还是 {"type":"relative","unit":"day",
# "count":1}? 提示词里 named 的例子只列了 today/this_week/this_month)。我猜错了,
# 一个其实正确的模型会被判错 —— 那把尺子测的是我的猜测, 不是模型。
#
# 参照的来源是**电池结果**, 不是我的判断: `--ref` 指定的模型在 2026-08-10 04:40
# 那轮全量电池上, 这批题全部通过; 而 08-10 下午链头换成 qwen3-next-80b 之后,
# 同一批题成片挂掉。所以「与 ref 一致」= 「大概率能让电池过」。
#
# ⛔ 必须带阴性对照: 把已知坏的那个模型也放进候选。它**必须**得低分 ——
#    如果它也得高分, 说明这把尺子测的不是我声称的东西, 读数一律作废。
_HARD_QUERIES = [
    # 08-10 下午链头换弱模型后成片挂掉的那批 —— 全是多槽且槽间要一致的问句
    "本月全部门店哪道菜卖得最差",      # 排名方向 + limit + dish 维度
    "昨天全部门店卖了多少钱",          # 相对日期
    "本月全部门店订单量如何",          # 指标 orders 而不是 revenue
    "全部门店2026年3月生意怎么样",     # 绝对月份
    "明天怎么排班",                    # 预测 horizon → STAFFING_ADVICE
    "下周需要多少兼职",                # 预测 horizon, 且不能退化成历史查询
    # 正对照: 简单单槽问句, 所有模型都该一致。全体不一致 = 探针坏了。
    "本月全部门店营收多少",
]

# 承重字段 —— 计划错在这些位上, 下游确定性代码就会去算另一个问题。
_KEY_FIELDS = ("intent", "analysis_action", "requested_metrics", "time_range",
               "dish", "store")


def _plan_of(text: str) -> Optional[Dict[str, Any]]:
    body = (text or "").strip()
    if body.startswith("```"):
        body = body.strip("`")
        if body[:4].lower() == "json":
            body = body[4:]
        body = body.strip()
    try:
        parsed = json.loads(body)
    except Exception:  # noqa: BLE001
        return None
    return parsed if isinstance(parsed, dict) else None


def _norm(value: Any) -> Any:
    """比较前归一: list 与 tuple 同义, 顺序无关(指标是集合语义), None/空串同义。"""
    if isinstance(value, (list, tuple)):
        return tuple(sorted(_norm(v) for v in value))
    if isinstance(value, dict):
        return tuple(sorted((k, _norm(v)) for k, v in value.items()))
    if value == "":
        return None
    return value


async def _plans_for(client: httpx.AsyncClient, account: str, model: str,
                     slot: r.SLOT) -> Dict[str, Optional[Dict[str, Any]]]:
    from smartbi.gold.restaurant import restaurant_intent as ri

    base, key = r._provider_config(account)
    out: Dict[str, Optional[Dict[str, Any]]] = {}
    for query in _HARD_QUERIES:
        prompt = ri._build_t3_prompt(query, None, None, (), None)
        normalized = r._normalize_payload_for_provider({
            "model": model,
            "messages": [
                {"role": "system",
                 "content": "你只输出JSON格式的意图解析结果，不输出任何其他文字。"},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0,
            "max_tokens": ri._SEMANTIC_MAX_TOKENS,
        }, account)
        payload = r._apply_slot_params(slot, account, model, normalized)
        try:
            resp = await client.post(base.rstrip("/") + "/chat/completions",
                                     json=payload,
                                     headers={"Authorization": f"Bearer {key}"},
                                     timeout=60.0)
            content = (json.loads(resp.text)["choices"][0]["message"]
                       .get("content") or "") if 200 <= resp.status_code < 300 else ""
        except Exception:  # noqa: BLE001
            content = ""
        out[query] = _plan_of(content)
    return out


def compare_main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ref", default="aliyun_c/glm-4.6",
                    help="参照模型 (电池上已知好用的那个)")
    ap.add_argument("--candidates", default="", help="逗号分隔 account/model")
    ap.add_argument("--slot", default="review")
    args = ap.parse_args(argv)

    slot = r.SLOT[args.slot.upper()]
    pairs = [tuple(s.split("/", 1)) for s in
             ([args.ref] + [c for c in args.candidates.split(",") if c])]

    async def run() -> Dict[Tuple[str, str], Dict[str, Any]]:
        async with httpx.AsyncClient() as client:
            results = {}
            for account, model in pairs:
                results[(account, model)] = await _plans_for(
                    client, account, model, slot)
            return results

    plans = asyncio.run(run())
    ref_pair = pairs[0]
    ref = plans[ref_pair]

    print(f"[compare] 参照 = {ref_pair[0]}/{ref_pair[1]} (电池上已知好用)")
    print(f"          难题 {len(_HARD_QUERIES)-1} 条 + 正对照 1 条; "
          f"承重字段 {list(_KEY_FIELDS)}\n")

    for pair in pairs[1:]:
        got = plans[pair]
        agree = 0
        detail = []
        for query in _HARD_QUERIES:
            a, b = ref.get(query), got.get(query)
            if a is None or b is None:
                detail.append(f"    ✗ {query} —— {'参照' if a is None else '候选'}没吐出可解析计划")
                continue
            diffs = [f for f in _KEY_FIELDS if _norm(a.get(f)) != _norm(b.get(f))]
            if diffs:
                shown = ", ".join(
                    f"{f}: ref={a.get(f)!r} vs {b.get(f)!r}" for f in diffs[:3])
                detail.append(f"    ✗ {query} —— {shown}")
            else:
                agree += 1
        print(f"  {pair[0]}/{pair[1]}: 与参照一致 {agree}/{len(_HARD_QUERIES)}")
        for line in detail:
            print(line)
        print()
    return 0

# ═══════════════════════════════════════════════════════════════════════════
# `--schema` —— 合法性打分: 模型有没有遵守**提示词自己给的枚举**。
#
# 这是本文件里第一把真正有区分度、又不需要人写标准答案的尺子。前两把都不行:
#   · 契约合格率(main): 19 个候选 18 个满分 —— 地板题, 区分不出强弱。照它排序会
#     退化成纯延迟升序 = 最小最快的排最前(2026-08-10 实测造成回归 83→61)。
#   · 与参照模型一致率(compare): 需要一个「已知好用」的参照, 而参照本身会因为
#     额度烧完而死(glm-4.6 当天 403), 死了就全 0, 看起来像候选全差。
#
# 这把尺子的答案**来自提示词本身**: `_build_t3_prompt` 里逐字列着 intent 清单、
# named 时间的合法取值、analysis_action 的四个值、requested_metrics 白名单。
# 解析那份说明书, 再看模型输出有没有越界。
#
# 🔑 判据: **不判「答得对不对」(那要我猜), 判「有没有编造说明书里没有的值」。**
#    后者客观, 且直接对应下游失败方式 —— 确定性代码只认枚举内的值, 编出来的
#    `next_week` / `tomorrow` 到了下游要么被丢弃要么走错分支。
#
# 2026-08-10 实测区分度(prod, 7 道真实电池问句):
#    qwen3.8-max ×3账号 / qwen3.7-max-2026-05-20 / qwen3.7-flash   0 违规
#    deepseek-v4-flash-0731 / qwen3.7-flash-2026-07-15             编 "tomorrow"
#    qwen3.6-plus                                                  编 "yesterday"+"tomorrow"
# 同期端到端电池: glm-4.6 头 83/83/84; qwen3.5-plus 头 82/73/81 且 [51] 三轮全挂
# —— [51]「下周需要多少兼职」正是它编 "next_week" 的那道题, 机制与读数对得上。
#
# ⚠️ 单样本噪声: 同一个模型在不同账号上打分不完全一致(qwen3.7-max-2026-05-17
#    在 a 上 1 处、b/c 上 0 处)。**别把这张表当精确排名**, 它只可靠地区分
#    「稳定零违规」与「会编枚举」两档。qwen3.8-max 三个账号全 0 是最强的信号。

_INTENT_LINE_RE = re.compile(r'^\s*-\s*"(RESTAURANT_OPS_[A-Z_]+)"', re.M)
_NAMED_VALUES_RE = re.compile(r'\{"type":\s*"named",\s*"value":\s*([^}]+)\}')
_ACTION_RE = re.compile(r'analysis_action 必须是\s*([a-z、\s]+?)\s*之一')
_METRIC_RE = re.compile(r'([a-z_]+)\(')


def prompt_vocabulary(prompt: str) -> Dict[str, frozenset]:
    """从提示词里解析出它自己声明的合法取值。

    ⛔ 不在这里另写一份枚举 —— 提示词一改这里跟着改, 不可能漂。
    每一项都带**下限断言**: 解析空了就抛, 免得「没解析到」被当成「模型没违规」。
    """
    intents = frozenset(_INTENT_LINE_RE.findall(prompt))
    named_raw = _NAMED_VALUES_RE.search(prompt)
    named = frozenset(re.findall(r'"([a-z_]+)"', named_raw.group(1))) if named_raw else frozenset()
    action_raw = _ACTION_RE.search(prompt)
    actions = frozenset(
        a for a in re.split(r'[、\s]+', action_raw.group(1)) if a) if action_raw else frozenset()
    metric_block = prompt.split("requested_metrics 只能使用:", 1)
    metrics = frozenset(
        _METRIC_RE.findall(metric_block[1][:1500])) if len(metric_block) > 1 else frozenset()

    for name, got, floor in (("intents", intents, 10), ("named", named, 2),
                             ("actions", actions, 4), ("metrics", metrics, 8)):
        if len(got) < floor:
            raise RuntimeError(
                f"从提示词解析 {name} 只拿到 {len(got)} 项(<{floor}) —— 解析坏了。"
                f"⛔ 这时候一切「零违规」都是假的, 不许当读数用。")
    return {"intents": intents, "named": named, "actions": actions, "metrics": metrics}


def schema_violations(plan: Dict[str, Any], vocab: Dict[str, frozenset]) -> List[str]:
    """这份计划里有几处越界。返回人类可读的违规说明。"""
    bad: List[str] = []
    intent = plan.get("intent")
    if intent and intent not in vocab["intents"]:
        bad.append(f"intent={intent} 不在清单内")
    action = plan.get("analysis_action")
    if action and action not in vocab["actions"]:
        bad.append(f"analysis_action={action} 不在 {sorted(vocab['actions'])}")
    tr = plan.get("time_range") or {}
    if isinstance(tr, dict) and tr.get("type") == "named":
        value = tr.get("value")
        if value not in vocab["named"]:
            bad.append(f'time_range named="{value}" 不在 {sorted(vocab["named"])} —— 编的')
    for metric in (plan.get("requested_metrics") or []):
        if metric not in vocab["metrics"]:
            bad.append(f"requested_metrics 含 {metric}, 不在白名单")
    return bad


def schema_main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--slot", default="review")
    ap.add_argument("--candidates", default="", help="逗号分隔 account/model; 空=槽内全池")
    args = ap.parse_args(argv)

    from smartbi.gold.restaurant import restaurant_intent as ri
    slot = r.SLOT[args.slot.upper()]
    vocab = prompt_vocabulary(ri._build_t3_prompt("本月营收多少", None, None, (), None))
    print(f"[schema] 从提示词解析: intent {len(vocab['intents'])} 个 / "
          f"named {sorted(vocab['named'])} / action {sorted(vocab['actions'])} / "
          f"metric {len(vocab['metrics'])} 个")

    pairs = ([tuple(s.split("/", 1)) for s in args.candidates.split(",") if s]
             or list(dict.fromkeys(r._SLOT_POOLS[slot])))

    async def run():
        async with httpx.AsyncClient() as client:
            rows = []
            for account, model in pairs:
                plans = await _plans_for(client, account, model, slot)
                bad, dead = [], 0
                for q, plan in plans.items():
                    if plan is None:
                        dead += 1
                        continue
                    for v in schema_violations(plan, vocab):
                        bad.append(f"{q} → {v}")
                rows.append(((account, model), bad, dead, len(plans)))
            return rows

    rows = asyncio.run(run())
    rows.sort(key=lambda x: (x[2] == x[3], x[2] > 0, len(x[1]), x[0]))
    print(f"\n题目 {len(_HARD_QUERIES)} 条 (回归电池真实问句)\n")
    for (account, model), bad, dead, total in rows:
        if dead == total:
            # 🔴 一条计划都没吐出来时**不许打印「0 处越界」** —— 那看起来像满分。
            #    2026-08-10 首跑就踩到: qwen3.5-plus 7/7 拿不到计划(它当时已 403),
            #    报表却把它和真正零违规的模型并排显示成 "0 处越界"。
            #    「没测到」必须和「测了没问题」长得不一样。
            print(f"  —— 无读数  {account}/{model}  ({total}/{total} 拿不到计划, "
                  f"多半已 403/不可达; ⛔ 不要当成零违规)")
            continue
        flag = f"  ⚠{dead}/{total} 拿不到计划(下列越界只覆盖剩下的)" if dead else ""
        print(f"  {len(bad)} 处越界  {account}/{model}{flag}")
        for line in bad[:4]:
            print(f"      · {line}")
    return 0


# ⚠️ 入口块**必须留在文件最末尾**: 它引用上面所有 *_main 函数。往本文件追加新
#    mode 时, 新函数要写在这一段**之前** —— 已经踩过两次 NameError。
if __name__ == "__main__":
    import sys as _sys
    if "--schema" in _sys.argv:
        _sys.argv.remove("--schema")
        raise SystemExit(schema_main())
    if "--compare" in _sys.argv:
        _sys.argv.remove("--compare")
        raise SystemExit(compare_main())
    raise SystemExit(main())
