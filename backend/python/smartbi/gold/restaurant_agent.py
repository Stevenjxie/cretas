"""Compound-question agent v1 — planner-only decomposition (R28).

多主题复合句 (「这个月生意怎么样，另外米饭卖得好不好」) 此前只答一个主题,
R26b 的诚实尾注是承认问题不是解决问题。本模块补上解决:

  LLM 只负责**拆解**: 把复合问题拆成自包含子问题 (补全被省略的时间/对象),
  每个子问题交给现有 tiered 管道独立回答 (T1→T3 路由 / 实体 DB 验证 /
  resolver 真算 / Answer Contract 把关), 最终答案**确定性拼装**。

  LLM 永远不写答案正文、不碰任何数字 — 零编造不变量按构造成立:
  拆错了最多是某个子问题被澄清/拒答, 绝不会出现编造的数值。

守卫: 触发靠分隔词启发式 (不为普通问题烧 LLM); 子问题数上限 3;
每段长度校验; 解析失败/LLM 超时一律 fail-open 回单主题路径 (R26b 尾注
仍在那里兜底)。子问题并发执行控制时延。
"""
from __future__ import annotations

import json
import logging
import re
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

_COMPOUND_HINT_RE = re.compile(
    r"，另外|，再|，然后|；|，顺便|，以及|，还有|，同时|先.{2,24}?再"
)
_AGENT_LLM_TIMEOUT_SECONDS = 6.0
_MAX_PARTS = 3


def is_compound_question(query: Optional[str]) -> bool:
    """启发式复合判定 — 只有命中分隔形态才值得烧一次拆解 LLM。"""
    if not query:
        return False
    q = query.strip()
    if len(q) < 12 or "继续追问" in q:
        return False
    return bool(_COMPOUND_HINT_RE.search(q))


def _parse_parts(content: str) -> Optional[List[str]]:
    """Validate the planner's JSON. Fail-closed to None on anything odd —
    caller falls back to the single-topic path, never to a broken plan."""
    content = (content or "").strip()
    if content.startswith("```"):
        content = content.strip("`")
        if content[:4].lower() == "json":
            content = content[4:]
        content = content.strip()
    try:
        parsed = json.loads(content)
    except Exception:
        return None
    parts = parsed.get("parts") if isinstance(parsed, dict) else None
    if not isinstance(parts, list):
        return None
    cleaned: List[str] = []
    for p in parts[:_MAX_PARTS]:
        if not isinstance(p, str):
            return None
        p = p.strip()
        if not (4 <= len(p) <= 60):
            return None
        cleaned.append(p)
    if len(cleaned) < 2:
        return None  # 单主题 → 不走 agent, 原路径处理
    return cleaned


async def decompose_compound_question(query: str) -> Optional[List[str]]:
    """LLM planner: split into self-contained sub-questions. None = fail-open."""
    try:
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        prompt = (
            "把下面的复合问题拆成可以各自独立回答的子问题。规则:\n"
            "1. 每个子问题必须自包含: 把原句里省略的时间范围、菜品或门店对象补进去"
            "（只能用原句里出现过的词, 不得新增用户没提的内容）。\n"
            "2. 不改变原意, 不合并, 不新增问题。\n"
            f"3. 最多 {_MAX_PARTS} 个; 如果这其实是一个单一问题, parts 里只放原句。\n"
            '4. 只输出 JSON, 形如 {"parts": ["子问题1", "子问题2"]}, 不要其他文字。\n\n'
            f'复合问题: "{query}"\n'
            "JSON:"
        )
        payload = {
            "messages": [
                {"role": "system",
                 "content": "你只输出JSON格式的拆解结果，不输出任何其他文字。"},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0,
            "max_tokens": 300,
        }
        with llm_caller_context("restaurant_agent_planner"):
            result = await call_chain(
                SLOT.MAPPER, payload, timeout=_AGENT_LLM_TIMEOUT_SECONDS)
        content = result["choices"][0]["message"]["content"] or ""
        return _parse_parts(content)
    except Exception as exc:
        logger.warning(f"[restaurant-agent] decompose failed (fail-open): {exc}")
        return None


def assemble_compound_answer(
    parts: List[str],
    results: List[Optional[Dict[str, Any]]],
) -> Optional[Dict[str, Any]]:
    """Deterministic assembly — numbered sections, per-part honesty, merged
    charts/kpis. None when nothing answered (caller falls through)."""
    sections: List[str] = []
    charts: List[Any] = []
    kpis: List[Any] = []
    answered = 0
    first_code: Optional[str] = None
    contract_ok = True
    for idx, (part, res) in enumerate(zip(parts, results), 1):
        if not isinstance(res, dict) or not res.get("answer_text"):
            sections.append(
                f"{idx}. {part}\n这部分暂时没有可靠答案，请单独提问或换个问法。"
            )
            contract_ok = False
            continue
        answered += 1
        sections.append(f"{idx}. {part}\n{res['answer_text']}")
        charts.extend(res.get("charts") or [])
        kpis.extend(res.get("kpis") or [])
        if first_code is None and res.get("code"):
            first_code = res.get("code")
        if res.get("kind") == "answer" and res.get("contract_pass") is False:
            contract_ok = False
    if answered == 0:
        return None
    return {
        "kind": "answer",
        "answer_text": "\n\n".join(sections),
        "charts": charts[:3],
        "kpis": kpis[:6],
        "title": "复合问题解答",
        "code": first_code,
        "contract_pass": contract_ok,
        "compound_parts": len(parts),
        "spec": None,
    }
