"""答案质量评分卡 —— 子串断言结构上看不见的那几条，用模型来看。

## 这是仪器，不是门禁

⛔ **本模块的判定不进生产链路，不决定给不给用户看。** 它只在评测时跑，
   产出「现有断言说通过、判定说没通过」这类**分歧清单**。
   （放行闸的误杀代价是用户什么都拿不到；清单的误杀代价只是多一行待核。
   `answer_addresses_query` 的模块注释里已经用 6 个样本量过这件事。）

## 为什么需要它（2026-08-11 实测，代价是四个 PR + 两轮全绿）

08-10 起上线的 8 张 markdown 表格，到用户手里全被并成一坨。
四个 PR、两轮 85/85 电池、CI 全绿，**没有一条断言看得见**：

  · 子串检查 `"销量" in answer` —— 字还在，只是排版没了
  · 源码检查 `"_markdown_table(" in <源码>` —— 调用还在，只是下游改了结果

两种在结构上就不可能覆盖「排版塌了」。同理也覆盖不了「答得烂」「说黑话」。

## 四条判据，各自谁在管

| 判据 | 确定性载体（免费、先跑） | 模型载体（补确定性看不见的） |
|---|---|---|
| ① 答上问的了吗 | 无 | `judge_answer_addresses_query`（已在 prod，直接复用） |
| ② 有无黑话 | `INTERNAL_VOCAB`+`ANALYST_JARGON` 词表 | 找**词表里没有**的技术词 |
| ③ 排版可读吗 | `markdown_table_problems`（只管表格） | 表格之外的排版 |
| ④ 数字自洽吗 | 无 | 只判**答案内部**的数字矛盾 |

⛔ **判据④刻意不叫「有没有编造数字」。** 判定模型手里只有问句和答案，
   **没有数据库** —— 它没有任何办法知道「本月营收 68 万」这个数是真是假。
   叫它「编造数字」等于给一个它做不到的承诺，然后拿它的沉默当成「数字是对的」。
   它能做且只能做的是**内部证伪**：分项加总对不上合计、「第一名是第二名的
   1.8 倍」与列出的数字对不上、说了「没有数据」却又给出数字。
   ⚠️ 「数字对不对」今天**没有任何仪器**（餐饮电池 145 条 contains 里
   断言指标数值的是 **0** 条，7 条含数字的全是日期窗口回显）。
   要补那条得靠库侧对数，不是靠判定模型 —— 这条写在报告里给 owner，
   不在这里假装已经覆盖。

## 失败方向

判定模型不可用 / 输出不可解析 → 该项返回 `None`（**判不了**），不是 `False`。
「判不了」和「判定没通过」是两件事，混起来会让分歧清单里塞满假条目，
而清单一旦不可信就没人看了。四象限里「判不了」单独一格。
"""
from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, replace
from typing import Any, Dict, List, Optional, Sequence, Tuple

from smartbi.gold.customer_text import ANALYST_JARGON, INTERNAL_VOCAB

logger = logging.getLogger(__name__)

#: 与规划器同一个池子（见 `answer_addresses_query` 的同名说明）：池子干了两边
#: 一起停，不会出现「规划器还能用但判定不了」的半截状态。
_JUDGE_SLOT = "review"

#: 判定要便宜。⚠️ 比 `answer_addresses_query` 的 1200 宽，因为排版问题可能出现在
#: 答案任何位置，截太短等于给后半段发永远绿的通行证。
#: （表格类排版由 `markdown_table_problems` 全文覆盖且免费，这里补的是其余部分。）
_MAX_ANSWER_CHARS = 2000
_MAX_QUERY_CHARS = 200
_MAX_OUTPUT_TOKENS = 400

#: ⛔ 提示词里**不列任何词表**。列了就把「有没有黑话」退化成「有没有出现我列的词」，
#: 而词表命中这一半已经由确定性层免费做掉了 —— 模型的价值恰恰在于找出**没列的**。
#: （本仓判据：判据里出现手写清单就问「这张表错了会怎样」。答：漏一个词就对它沉默。
#:  所以手写表负责已知的，模型负责扩表。）
_SYSTEM_PROMPT = """你在检查一个餐饮经营问答系统发给**餐厅老板/店长**的回答。

读的人是开餐馆的，不是工程师，也不懂统计学。

判断三件事，各自独立：

A. 黑话：回答里有没有店长读不懂、或者读懂了也没法据此行动的词？
   - 技术实现词（系统内部的概念名）算。
   - 统计/计量学术语算。
   - 「毛利」「翻台率」「客单价」这类**餐饮业务词不算** —— 店长每天在说。
   - 把你认为有问题的词**原样列出来**，不要改写。

B. 排版：给你的是 markdown 原文。它渲染出来读得下去吗？
   - 表格前后该空行的地方没空行、表格和正文糊在一起 —— 算问题。
   - 编号乱掉、该分行的挤成一段、一句话拖很长没有断点 —— 算问题。
   - 内容多不算问题，结构混乱才算。

C. 数字自洽：**只看回答内部有没有互相矛盾**。
   - ⛔ 你没有数据库，不要判断某个数字是真是假，那不是你的活。
   - 分项加起来对不上合计 —— 算问题。
   - 「第一名是第二名的 N 倍」之类的说法与列出的数字对不上 —— 算问题。
   - 说了「没有数据 / 查不到」却又给出了具体数字 —— 算问题。
   - 数字有出处、内部一致 —— 就算通过，哪怕你觉得它数值可疑。

只输出 JSON，不要解释：
{"jargon": {"ok": true/false, "words": ["原样列出的词"]},
 "layout": {"ok": true/false, "detail": "不超过40字"},
 "numbers": {"ok": true/false, "detail": "不超过40字"}}"""


@dataclass(frozen=True)
class QualityVerdict:
    """一条答案的四判据结论。

    每一项都可能是「判不了」(`None`)，与「判定没通过」(`False`) 严格分开。
    """

    #: ① 答上问的了吗。None = 判不了。
    addressed: Optional[bool] = None
    #: ① 没答到时缺了什么。
    missing: str = ""

    #: ② 词表命中（确定性，免费，永远可得 —— 不会是「判不了」）。
    jargon_listed: Tuple[str, ...] = ()
    #: ② 模型指出的、**词表里没有**的词。None = 判不了。
    jargon_unlisted: Optional[Tuple[str, ...]] = None

    #: ③ 表格结构问题（确定性，免费，永远可得）。
    layout_structural: Tuple[str, ...] = ()
    #: ③ 模型指出的其余排版问题。None = 判不了，"" = 没问题。
    layout_llm: Optional[str] = None

    #: ④ 数字内部矛盾。None = 判不了，"" = 没矛盾。
    number_conflict: Optional[str] = None

    #: 判定不可用的原因（模型挂了 / 输出不可解析）。"" = 判定跑通了。
    unavailable: str = ""

    @property
    def deterministic_problems(self) -> List[str]:
        """不花一个 token 就能得到的问题。**判定模型挂了也照样有。**"""
        out = [f"黑话「{w}」" for w in self.jargon_listed]
        out += [f"排版: {p}" for p in self.layout_structural]
        return out

    @property
    def llm_problems(self) -> List[str]:
        """判定模型指出的问题。判不了的项**不算问题**（不是 False）。"""
        out: List[str] = []
        if self.addressed is False:
            out.append(f"没答到所问: {self.missing or '(未说明)'}")
        if self.jargon_unlisted:
            out.append("黑话(词表未收): " + "、".join(self.jargon_unlisted))
        if self.layout_llm:
            out.append(f"排版: {self.layout_llm}")
        if self.number_conflict:
            out.append(f"数字矛盾: {self.number_conflict}")
        return out

    @property
    def problems(self) -> List[str]:
        return self.deterministic_problems + self.llm_problems

    @property
    def verdict(self) -> str:
        """三态：pass / fail / unknown。

        ⛔ 「判不了」不许折叠成 pass —— 那正是「沉默即通过」，本仓反复在拆。
           只有**确定性层也干净、且模型层确实跑通了**才算 pass。
        """
        if self.problems:
            return "fail"
        if self.unavailable or self.addressed is None:
            return "unknown"
        return "pass"


def scan_listed_jargon(text: str) -> Tuple[str, ...]:
    """词表命中。确定性、免费、可单测 —— 判定模型挂了这一层照样在。"""
    if not text:
        return ()
    hits = [w for w in (*INTERNAL_VOCAB, *ANALYST_JARGON) if w in text]
    # 去重且保持词表顺序（「p值」「P值」可能同时命中，读的人只关心命中了这一类）
    seen: Dict[str, None] = {}
    for w in hits:
        seen.setdefault(w, None)
    return tuple(seen)


def _extract_json(text: str) -> Optional[dict]:
    """取模型输出里的第一个 JSON 对象。

    ⚠️ 不直接 `json.loads(text)`：模型常在 JSON 前后附一句说明或用 ```json 包裹，
       整体解析失败会被读成「判不了」，白白丢掉一次有效判定。
       （与 `answer_addresses_query._extract_json` 同因同治。）
    """
    match = re.search(r"\{.*\}", text or "", re.S)
    if not match:
        return None
    try:
        parsed = json.loads(match.group(0))
    except (ValueError, TypeError):
        return None
    return parsed if isinstance(parsed, dict) else None


def parse_quality_payload(
    content: str, *, listed: Sequence[str] = ()
) -> Optional[Dict[str, Any]]:
    """把模型输出解析成 {jargon_unlisted, layout, numbers}。纯函数，可单测。

    返回 ``None`` = 解析不出来（判不了）。

    ⛔ 模型列的词里，**已经在词表里的要剔掉** —— 否则同一个词会在
       `jargon_listed` 和 `jargon_unlisted` 里各报一次，读的人以为有两个问题。
       模型这一层的价值就是「词表没收的」，重复的部分不是它的贡献。
    """
    parsed = _extract_json(content)
    if parsed is None:
        return None

    def _section(key: str) -> Optional[dict]:
        value = parsed.get(key)
        return value if isinstance(value, dict) else None

    jargon, layout, numbers = _section("jargon"), _section("layout"), _section("numbers")
    if jargon is None and layout is None and numbers is None:
        # 三个键一个都没有 —— 不是本契约的输出，别硬猜。
        return None

    words: List[str] = []
    if jargon and not jargon.get("ok", True):
        raw = jargon.get("words")
        if isinstance(raw, list):
            listed_set = set(listed)
            words = [
                str(w).strip()[:20] for w in raw
                if str(w).strip() and str(w).strip() not in listed_set
            ]

    def _detail(section: Optional[dict]) -> str:
        if not section or section.get("ok", True):
            return ""
        return str(section.get("detail") or "").strip()[:80]

    return {
        "jargon_unlisted": tuple(words),
        "layout": _detail(layout),
        "numbers": _detail(numbers),
    }


def _build_messages(query: str, answer: str) -> List[Dict[str, str]]:
    return [
        {"role": "system", "content": _SYSTEM_PROMPT},
        # ⛔ 答案原样传，**不要压平空白** —— 判据 B 判的就是换行与空行。
        #    电池里到处在用的 `" ".join(message.split())` 一旦用在这里，
        #    排版判据就等于自发了一张永远绿的通行证。
        {"role": "user", "content":
            f"用户的问题：{query[:_MAX_QUERY_CHARS]}\n\n"
            f"系统给出的回答（markdown 原文）：\n{answer[:_MAX_ANSWER_CHARS]}"},
    ]


async def judge_answer_quality(
    query: str,
    answer: str,
    *,
    slot: str = _JUDGE_SLOT,
    table_problems: Sequence[str] = (),
    call_chain=None,
    slot_enum=None,
) -> QualityVerdict:
    """跑完四条判据。**不抛异常** —— 任何失败都只降级成「判不了」。

    ``table_problems`` 由调用方传入（电池里已有 `markdown_table_problems`，
    全文覆盖且免费，不在这里重算一遍）。

    ⚠️ 判据① 走已在 prod 的 `judge_answer_addresses_query`，**不重写**：
       它有自己的单测、自己的 prod 记录，重写一份只会多一个会漂的实现。
    """
    query, answer = (query or "").strip(), (answer or "").strip()
    listed = scan_listed_jargon(answer)
    base = QualityVerdict(
        jargon_listed=listed, layout_structural=tuple(table_problems),
    )
    if not query or not answer:
        return replace(base, unavailable="问句或答案为空")

    # ── 判据①：复用 prod 模块 ────────────────────────────────────────────
    try:
        from smartbi.gold.restaurant.answer_addresses_query import (
            judge_answer_addresses_query,
        )
        addressed, missing = await judge_answer_addresses_query(query, answer)
    except Exception as exc:  # noqa: BLE001 — 旁路，判不了不该让评测崩
        logger.warning("[quality-judge] 判据①调用失败: %s", exc)
        addressed, missing = None, ""

    # ── 判据②③④：一次调用 ──────────────────────────────────────────────
    if call_chain is None or slot_enum is None:  # pragma: no cover - 运行期注入
        from common.llm_router import SLOT, call_chain as _cc
        call_chain, slot_enum = _cc, SLOT
    try:
        result = await call_chain(
            getattr(slot_enum, slot.upper()),
            {
                "messages": _build_messages(query, answer),
                "temperature": 0,
                "max_tokens": _MAX_OUTPUT_TOKENS,
            },
            timeout=30.0,
        )
        content = str(result["choices"][0]["message"]["content"] or "")
    except Exception as exc:  # noqa: BLE001
        logger.warning("[quality-judge] 判据②③④调用失败: %s", exc)
        return QualityVerdict(
            addressed=addressed, missing=missing if addressed is False else "",
            jargon_listed=listed, layout_structural=tuple(table_problems),
            unavailable=f"判定模型不可用: {exc}"[:120],
        )

    payload = parse_quality_payload(content, listed=listed)
    if payload is None:
        logger.warning("[quality-judge] 输出不可解析: %r", content[:120])
        return QualityVerdict(
            addressed=addressed, missing=missing if addressed is False else "",
            jargon_listed=listed, layout_structural=tuple(table_problems),
            unavailable="判定输出不可解析",
        )

    return QualityVerdict(
        addressed=addressed,
        missing=missing if addressed is False else "",
        jargon_listed=listed,
        jargon_unlisted=payload["jargon_unlisted"],
        layout_structural=tuple(table_problems),
        layout_llm=payload["layout"],
        number_conflict=payload["numbers"],
    )
