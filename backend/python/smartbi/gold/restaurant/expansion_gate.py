"""防膨胀闸 —— 新增之前先问：它是不是现有元素的组合？

🔴 存在的理由（Steve 2026-08-09 提出）：
   同一个毛病在这个仓库里长成了三个规模 ——
   Java AI 工具 **597 个**、餐饮读 resolver **20 个**、
   而「客单价 / 人均消费 / 每单金额」在业务嘴里是三个词、数学上是同一个式子。
   膨胀的根源是**没人在新增之前问那句话**。SmartBI 从 28 收敛到 8 再到 2，
   做的正是事后补问它。

⛔ 闸的形状（Steve 的设计 + 一个必须补的验证环）：

     新增指标/resolver 或飞轮出晋升候选
      → LLM 审核：这是不是已有能力的另一种说法？
      → 说「是 X」时**不能直接信** ——
        假阳性会把「客单价」路由到「营收」，系统自信地答错，
        比不加更糟（今天已真实发生过：客单价问句得到营收报表）
      → 拿这句话强制走 X 真跑一次 → 判定层判「答到所问了吗」
        · 答到了 → 审核判对 → 登记进整句层，指向 X（**拒绝的同时产出路由**，
                   所以同一条不会反复红）
        · 没答到 → 审核判错 → 这确实是新能力 → 放行去人审

⚠️ 「补对应词」不等于事先穷举同义说法 —— 那是最大的一张词表。
   只登记**真实观察到的那一句**，沿用现有整句层机制（≥2 次 + 人审）。
"""
from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence

logger = logging.getLogger(__name__)

#: 审核结论
VERDICT_DUPLICATE = "duplicate"     # 是已有能力的另一种说法
VERDICT_NEW = "new"                 # 确实是新能力
VERDICT_UNSURE = "unsure"           # 判不了（模型不可用 / 输出不可解析）


@dataclass
class ExpansionVerdict:
    """审核 + 验证的合并结论。

    `verified` 才是能行动的信号：
      · duplicate 且 verified=True  → 登记路由，拒绝新增
      · duplicate 但 verified=False → **审核判错了**，按新能力处理
      · new / unsure                → 放行去人审
    """
    query: str
    verdict: str
    existing_key: Optional[str] = None
    reason: str = ""
    verified: Optional[bool] = None
    verify_note: str = ""

    @property
    def should_block(self) -> bool:
        """真正该拦下的：审核说重复 **且** 验证证实它答得到。"""
        return self.verdict == VERDICT_DUPLICATE and self.verified is True


def _capability_catalogue() -> List[Dict[str, str]]:
    """现有能力清单 —— 喂给审核模型的唯一事实来源。

    ⛔ 从登记表**算出来**，不手写。手写会随登记表增长而过期，
       而过期的方向恰恰是「清单里没有、其实已经有了」→ 审核放行 → 膨胀。
       判据：判据里出现手写清单，就问「这张表错了会怎样」。
    """
    from smartbi.gold.restaurant.metric_registry import (
        AGGREGATIONS, DERIVED, DIMENSIONS, METRICS,
    )
    out: List[Dict[str, str]] = []
    for m in METRICS.values():
        out.append({"key": m.key, "label": m.label, "kind": "指标"})
    for d in DERIVED.values():
        out.append({"key": d.key, "label": d.label, "kind": "派生指标"})
    for d in DIMENSIONS.values():
        out.append({"key": d.key, "label": d.label, "kind": "维度"})
    for a in AGGREGATIONS.values():
        out.append({"key": a.key, "label": a.label, "kind": "聚合"})
    return out


_SYSTEM_PROMPT = """你是能力登记审核员。系统已经登记了一批**指标 / 维度 / 聚合**，
它们可以任意组合 —— 登记 N 个指标 × M 个维度 × K 个聚合，就有 N×M×K 种分析可用。

给你一个用户问句和现有登记清单。判断一件事：

**这个问句要的东西，能不能由清单里已有的元素组合出来？**

- 能组合出来 → duplicate，并说明用哪个已有元素（给出它的 key）。
  例：「人均消费」= 已有的「客单价」；「哪家店卖得最多」= 营收 × 门店 × 排名。
- 组合不出来（需要清单里没有的指标、维度或聚合）→ new。
- 不确定 → unsure。**不要猜**。

只输出 JSON：{"verdict": "duplicate"|"new"|"unsure", "existing_key": "...", "reason": "一句话"}"""


def _build_messages(query: str, catalogue: Sequence[Dict[str, str]]) -> List[Dict[str, str]]:
    lines = [f"- [{c['kind']}] {c['key']} = {c['label']}" for c in catalogue]
    return [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {"role": "user", "content":
            "现有登记清单：\n" + "\n".join(lines) + f"\n\n用户问句：{query}"},
    ]


async def review_expansion(query: str) -> ExpansionVerdict:
    """只做审核，不做验证。⚠️ 单独用它是不安全的 —— 见 `review_and_verify`。"""
    query = (query or "").strip()
    if not query:
        return ExpansionVerdict(query, VERDICT_UNSURE, reason="空问句")
    try:
        from common.llm_router import SLOT, call_chain

        result = await call_chain(
            SLOT.REVIEW,
            {"messages": _build_messages(query, _capability_catalogue()),
             "temperature": 0, "max_tokens": 200},
            timeout=30.0,
        )
        text = result["choices"][0]["message"]["content"] or ""
    except Exception as exc:  # noqa: BLE001 — 旁路，失败一律降级成 unsure
        logger.warning("[expansion-gate] 审核调用失败, 按 unsure 处理: %s", exc)
        return ExpansionVerdict(query, VERDICT_UNSURE, reason=f"模型不可用: {exc}"[:80])

    match = re.search(r"\{.*\}", str(text), re.S)
    if not match:
        return ExpansionVerdict(query, VERDICT_UNSURE, reason="输出不可解析")
    try:
        parsed = json.loads(match.group(0))
    except (ValueError, TypeError):
        return ExpansionVerdict(query, VERDICT_UNSURE, reason="输出不是合法 JSON")

    verdict = str(parsed.get("verdict") or "").strip().lower()
    if verdict not in (VERDICT_DUPLICATE, VERDICT_NEW, VERDICT_UNSURE):
        return ExpansionVerdict(query, VERDICT_UNSURE,
                                reason=f"未知结论 {verdict!r}")
    return ExpansionVerdict(
        query, verdict,
        existing_key=(str(parsed.get("existing_key") or "").strip() or None),
        reason=str(parsed.get("reason") or "")[:120],
    )


async def review_and_verify(query: str, *, answer_of_existing: Optional[str] = None) -> ExpansionVerdict:
    """审核 + **验证**。这才是能用来做决定的那个。

    🔴 为什么必须验证：审核说「这是已有 X 的另一种说法」如果判错，
       系统会把这句话永久路由到 X，然后**自信地答错**。
       今天真实发生过：问「客单价最高的店」得到「营收最高的店」的报表，
       而系统标记为成功。假阳性比不加这道闸更糟。

    `answer_of_existing` 是「强制走已有能力」跑出来的答案。
    调用方负责真跑那一次 —— 本模块不碰执行链路，只负责判。
    ⛔ 没给答案就**不下 verified 结论**（None），调用方按「未验证」处理，
       不能当成验证通过。
    """
    verdict = await review_expansion(query)
    if verdict.verdict != VERDICT_DUPLICATE:
        return verdict
    if not answer_of_existing:
        verdict.verify_note = "未提供已有能力的实际回答, 无法验证"
        return verdict

    from smartbi.gold.restaurant.answer_addresses_query import (
        judge_answer_addresses_query,
    )
    answered, missing = await judge_answer_addresses_query(query, answer_of_existing)
    verdict.verified = answered          # None = 判不了, 不是 False
    verdict.verify_note = missing or ""
    if answered is False:
        logger.info("[expansion-gate] 审核说是 %s 的说法, 但判定说没答到 —— "
                    "按新能力放行: %.40s", verdict.existing_key, query)
    return verdict
