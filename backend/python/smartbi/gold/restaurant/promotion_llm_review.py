"""晋升人审台的**第一道**：让模型独立复判一次候选的意图。

## 它是什么、不是什么

⛔ **它不是自动晋升。** 模型的输出只有一个用途: 把候选分成「人可以快速点头的」
   和「人必须停下来看的」两堆, 减少人审的量, **不减少人审这个环节本身**。
   任何一条进晋升表, 都仍然要人点头。

⛔ **它不是置信度打分。** 模型只回答一个封闭问题:「这句话该落到哪个意图」,
   答案必须是已注册意图之一或 `UNSURE`。没有分数, 没有阈值 ——
   与飞轮记录**一致就是一致, 不一致就是不一致**。

## 为什么需要它 (2026-08-08 实测)

那天我肉眼审了 96 条候选, 拦下四类会固化成错误的:

  1. **过期候选** —— 「最近30天折扣力度多大」记录的意图是「营收总览」, 那是
     折扣意图**建立之前**留下的。按它晋升就把「折扣」永久固化成「总览」。
  2. **把「没有数据」表达成「不归我管」** —— 「哪个供应商报价最贵」记录成
     OUT_OF_DOMAIN(与天气同一档), 晋升它等于对所有租户永久关门。
  3. **跨指标比率被当成单指标** —— 「食材成本占营收多少」记录成「食材成本」,
     晋升它就把「占营收」那半边永久丢掉。
  4. **只会返回拒绝的路由** —— 「员工人效怎么样」记录成预测排班, 而排班
     resolver 对历史问句只会礼貌拒绝。晋升它 = 固化一个答不出东西的路由。

这四类全靠我当时一条条看出来。**换个人、换个 session 就会重新批进去** ——
所以要有机制, 不能靠某一次的仔细。

📌 判据: 一条候选值不值得晋升, 不取决于它出现了多少次, 而取决于
   **「按它执行一次, 用户拿到的是不是对的东西」**。次数只说明它常被问。
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Sequence

logger = logging.getLogger(__name__)

#: 模型不确定时必须回这个 —— 给它一条**说不知道的路**, 否则它会硬挑一个。
UNSURE = "UNSURE"

#: 复判结论的三种去向。
VERDICT_AGREE = "agree"        # 与飞轮记录一致 -> 人快速复核即可
VERDICT_DISAGREE = "disagree"  # 与飞轮记录不同 -> **人必须停下来看**
VERDICT_UNSURE = "unsure"      # 模型说不准 -> 人来判


@dataclass(frozen=True)
class ReviewVerdict:
    query: str
    recorded_intent: str
    llm_intent: str
    verdict: str
    reason: str

    @property
    def needs_human_attention(self) -> bool:
        """只有一致的那批可以快速过 —— 其余都要人停下来。"""
        return self.verdict != VERDICT_AGREE


def _build_prompt(query: str, intent_catalogue: Dict[str, str]) -> List[Dict[str, str]]:
    """封闭问题 + 一条「说不知道」的出路。

    ⛔ 刻意**不告诉模型飞轮记录的是什么** —— 告诉它就变成了「你同不同意」,
       模型会倾向于同意, 复判就失去意义。这里要的是**独立的第二意见**。
    """
    lines = [f"- {code}: {desc}" for code, desc in sorted(intent_catalogue.items())]
    catalogue = "\n".join(lines)
    system = (
        "你在审核一个餐饮经营问答系统的意图路由。\n"
        "给你一句用户问句和全部可选意图，判断这句话应该落到哪一个意图。\n\n"
        "规则：\n"
        f"1. 只能回答意图列表里的代号，或者 {UNSURE}。\n"
        f"2. 拿不准、或者这句话同时问了两件事、或者它问的是比率/预测/"
        f"这些意图都装不下的东西 —— 一律回 {UNSURE}，不要硬挑一个。\n"
        "3. 「这个租户没有这份数据」不等于「这个问题不属于餐饮经营」。"
        "前者应落到对应的业务意图，后者才是域外。\n"
        '4. 只输出 JSON：{"intent": "...", "reason": "不超过30字"}'
    )
    user = f"可选意图：\n{catalogue}\n\n用户问句：{query}"
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def _parse(content: str, valid: Sequence[str]) -> tuple:
    """解析模型输出。⛔ 解析不出来一律当 UNSURE, 不猜。"""
    text = (content or "").strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
    try:
        data = json.loads(text)
    except Exception:
        return UNSURE, "模型输出不是合法 JSON"
    intent = str(data.get("intent") or "").strip()
    reason = str(data.get("reason") or "").strip()[:60]
    if intent != UNSURE and intent not in valid:
        return UNSURE, f"模型给了未注册的意图 {intent!r}"
    return intent or UNSURE, reason


async def review_candidate(
    query: str,
    recorded_intent: str,
    intent_catalogue: Dict[str, str],
    *,
    call_chain=None,
    slot=None,
) -> ReviewVerdict:
    """让模型独立判一次, 与飞轮记录比对。**不写库、不晋升。**

    ⚠️ 模型不可用时返回 `unsure` 而不是 `agree` —— 复判环节挂了, 只能让人
       全量看, 不能默认放行。(这条方向反了就是把审核变成橡皮图章。)
    """
    if call_chain is None or slot is None:  # pragma: no cover - 运行期注入
        from common.llm_router import SLOT, call_chain as _cc
        call_chain, slot = _cc, SLOT.REVIEW

    try:
        result = await call_chain(
            slot,
            {
                "messages": _build_prompt(query, intent_catalogue),
                "temperature": 0,
                "max_tokens": 200,
            },
            timeout=30.0,
        )
        content = (result["choices"][0]["message"]["content"] or "")
    except Exception as exc:  # noqa: BLE001
        logger.warning("[promotion-review] 模型复判失败(按 unsure 处理): %s", exc)
        return ReviewVerdict(query, recorded_intent, UNSURE, VERDICT_UNSURE,
                             f"复判不可用: {exc}"[:60])

    llm_intent, reason = _parse(content, list(intent_catalogue))
    if llm_intent == UNSURE:
        verdict = VERDICT_UNSURE
    elif llm_intent == recorded_intent:
        verdict = VERDICT_AGREE
    else:
        verdict = VERDICT_DISAGREE
    return ReviewVerdict(query, recorded_intent, llm_intent, verdict, reason)


def summarize(verdicts: Sequence[ReviewVerdict]) -> Dict[str, Any]:
    """给人看的分堆。**分歧和不确定排在前面** —— 人的注意力要花在那里。"""
    buckets: Dict[str, List[ReviewVerdict]] = {
        VERDICT_DISAGREE: [], VERDICT_UNSURE: [], VERDICT_AGREE: [],
    }
    for v in verdicts:
        buckets[v.verdict].append(v)
    return {
        "total": len(verdicts),
        "needs_attention": len(buckets[VERDICT_DISAGREE]) + len(buckets[VERDICT_UNSURE]),
        "disagree": buckets[VERDICT_DISAGREE],
        "unsure": buckets[VERDICT_UNSURE],
        "agree": buckets[VERDICT_AGREE],
    }
