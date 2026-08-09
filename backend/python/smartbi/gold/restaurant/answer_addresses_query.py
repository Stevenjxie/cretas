"""判定：这个回答，回答了用户问的那件事吗？

🔴 存在的理由（2026-08-09 实测）：
   问「本月全部门店客单价最高的店是哪家」，系统端出一份**全店营收概览**
   （营收最高的门店、总单数、平均每单），通篇没有"按门店比客单价"这件事。
   而系统判定它**成功了**：`served=true`、`contract_pass=true`、`confidence=0.95`，
   于是这个错答案被写进计划缓存，之后每次问同一句话都零成本、稳定地
   重放同一个错答案 —— 比偶尔出错更难发现。

⛔ 为什么不用词表判：
   系统里已有一张 `_REQUEST_TEXT_TOKENS`，把「客单价」归进 `orders` 这一类
   （与「订单」「单量」同组）。那份营收概览里出现了「订单数 1665 单」，
   按词表核对**会判成"答到了"** —— 词表在这个案例上直接失效。
   而且每出现一个新提法（翻台率、复购率、坪效…）就要往表里加一行，
   那正是本项目反复验证过没用的「加关键词」老路。

⛔ 为什么不用置信度判：
   实测两个方向都不成立 —— 上面那条**错**计划 confidence=0.95（远高于 0.85 门槛），
   而 2026-07-30 记录里 deepseek-v3.2 给出**内容完全正确**的计划却返回 -1.0。
   置信度是模型对自己的评价，与对错不相关。

✅ 判据：拿**用户原话**与**系统答案**交给一个独立模型比对。
   它不参与理解、不参与执行，只回答"答的是不是所问" —— 这是可证伪的判断，
   而且新提法不需要事先登记（实测「客单价」「翻台率」从未在任何地方登记，
   判定模型照样指出"没有给出客单价最高的门店"/"缺翻台率数据"）。

⚠️ **只用来决定「学不学」，绝不用来决定「给不给用户看」。**
   实测 6 个样本里有 1 个误杀：「上个月哪个折扣活动让利最多」的答案把 6 个活动
   按金额降序列全了，判定模型认为"没明确指出哪个最多"。这个挑刺有道理，
   但作为放行闸就是误杀 —— 拦下来用户什么都拿不到。
   放在「学不学」上，误杀的代价只是：这句话不被固化（下次仍走大模型），
   外加待办清单里多一条假的。**都不影响用户。**

⚠️ 判定模型自身不可用时返回 None（不是 False）——「判不了」和「判定没答到」
   是两件事。调用方一律按「不学」处理：答案照给，但不写进缓存/晋升。
   两个方向都安全。
"""
from __future__ import annotations

import json
import logging
import re
from typing import Optional, Tuple

logger = logging.getLogger(__name__)

#: 判定用的槽位。走 REVIEW —— 与规划器同一个池子，池子干了两边一起停，
#: 不会出现「规划器还能用但判定不了」的半截状态。
_JUDGE_SLOT = "review"

#: ⛔ 提示词里**不给任何指标清单/关键词表**。给了就等于把「答没答到」
#:    退化成「有没有出现我列的那些词」，回到词表老路。
_SYSTEM_PROMPT = """你是回答质量判定员。给你「用户的问题」和「系统给出的回答」。

只判断一件事：**这个回答是否回答了用户问的那件事**。

判定标准：
- 用户问的具体对象、指标、比较方式，回答里必须真的给出。
- 回答了相关但不同的问题 = 没答到（例如问「客单价最高的店」却给了「营收最高的店」）。
- 数字对不对不在判定范围内，只判「答的是不是所问」。
- 如实说「没有数据 / 不支持该分析」也算**答到了**（它正面回应了问题，没有拿别的顶替）。
- 反问澄清（如「你想看哪个时间范围」）也算**答到了**（它没有给出错误结论）。

只输出 JSON，不要解释：{"answered": true/false, "missing": "没答到时说明缺了什么；答到了填空字符串"}"""

#: 判定本身要便宜: 输入截断 + 输出限长。答案前半段足以判断「答的是不是所问」,
#: 后面通常是明细与建议。
_MAX_ANSWER_CHARS = 1200
_MAX_QUERY_CHARS = 200
_MAX_OUTPUT_TOKENS = 200


def _extract_json(text: str) -> Optional[dict]:
    """从模型输出里取第一个 JSON 对象。

    ⚠️ 不用 `json.loads(text)` 直接解析：模型常在 JSON 前后附一句说明，
       或用 ```json 包裹。整体解析失败就等于判定失效，而那会被
       调用方读成「判不了」，白白丢掉一次有效判定。
    """
    match = re.search(r"\{.*\}", text, re.S)
    if not match:
        return None
    try:
        parsed = json.loads(match.group(0))
    except (ValueError, TypeError):
        return None
    return parsed if isinstance(parsed, dict) else None


async def judge_answer_addresses_query(
    query: str,
    answer: str,
    *,
    slot: str = _JUDGE_SLOT,
) -> Tuple[Optional[bool], str]:
    """返回 (答到了吗, 缺什么)。

    ``None`` = 判不了（模型不可用 / 输出不可解析）。调用方按「不学」处理，
    **不要**把它当成 False —— False 会让这句话进待办清单，而「判不了」
    进清单只会制造噪音。

    ⚠️ 本函数不抛异常：它跑在回答已经发出之后的旁路上，
       任何失败都不该影响主链路，也不该让日志写不进去。
    """
    query = (query or "").strip()
    answer = (answer or "").strip()
    if not query or not answer:
        return None, ""

    try:
        from common.llm_router import SLOT, call_chain

        result = await call_chain(
            getattr(SLOT, slot.upper()),
            {
                "messages": [
                    {"role": "system", "content": _SYSTEM_PROMPT},
                    {"role": "user", "content":
                        f"用户的问题：{query[:_MAX_QUERY_CHARS]}\n\n"
                        f"系统给出的回答：{answer[:_MAX_ANSWER_CHARS]}"},
                ],
                "temperature": 0,
                "max_tokens": _MAX_OUTPUT_TOKENS,
            },
            timeout=30.0,
        )
        text = result["choices"][0]["message"]["content"] or ""
    except Exception as exc:  # noqa: BLE001 — 旁路, 任何失败都只降级成「判不了」
        logger.warning("[answer-judge] 判定调用失败, 按「判不了」处理: %s", exc)
        return None, ""

    parsed = _extract_json(str(text))
    if parsed is None or "answered" not in parsed:
        logger.warning("[answer-judge] 输出不可解析, 按「判不了」处理: %r",
                       str(text)[:120])
        return None, ""

    answered = bool(parsed.get("answered"))
    missing = str(parsed.get("missing") or "")[:200]
    # ⛔ 答到了就不该带 missing —— 带了说明模型没按契约走, 只留判定结果。
    return answered, ("" if answered else missing)
