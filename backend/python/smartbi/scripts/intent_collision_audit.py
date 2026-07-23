"""Intent inventory collision audit — read-only.

The 2026-07-23 「外卖占了几成」 misroute class: intent B's DESCRIPTION contains
wording that belongs to intent A's capability (REVENUE_REPORT_GENERATE's
description said 「堂食外卖占比」), so the LLM tier confidently routes A-class
queries to B. Every overlapping description is a latent mine of this class —
this script finds them all at once instead of waiting for probes to step on
them one by one.

Three collision detectors over active ``ai_intent_configs`` rows:

  K  keyword duplicated across intents           (direct routing ambiguity)
  D  intent A's keyword inside intent B's description   (the LLM-steal class)
  S  description char-bigram Jaccard >= threshold       (near-duplicate scope)

Output: markdown ranked report (restaurant/common first). Read-only — fixes
are applied separately and deliberately, never by this script.

    python -m smartbi.scripts.intent_collision_audit [--min-jaccard 0.35]
"""
from __future__ import annotations

import argparse
import asyncio
import re
from collections import defaultdict
from typing import Any, Dict, List, Tuple


_PUNCT_RE = re.compile(r"[\s，。、；：/（）()\[\]「」【】·,.;:!！?？\-—|]+")

# Generic tokens that legitimately appear everywhere — not collision evidence.
_STOP_KEYWORDS = {
    "查询", "统计", "分析", "查看", "数据", "报表", "情况", "多少", "怎么样",
    "生成", "列表", "详情", "记录", "管理", "信息", "餐饮", "门店", "今天",
}


def _norm(text: str) -> str:
    return _PUNCT_RE.sub("", text or "")


def _bigrams(text: str) -> set:
    t = _norm(text)
    return {t[i:i + 2] for i in range(len(t) - 1)} if len(t) > 1 else set()


def _jaccard(a: set, b: set) -> float:
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


async def _load_rows() -> List[Dict[str, Any]]:
    from smartbi.config import get_pg_pool
    import asyncpg
    from config import get_settings

    # ai_intent_configs lives in the CRETAS db (same source the Java side and
    # ai.db snapshot read), not the smartbi analytics db.
    conn = await asyncpg.connect(get_settings().food_kb_db_url)
    try:
        rows = await conn.fetch(
            """
            SELECT intent_code, intent_name, description, keywords::text AS kw,
                   tool_name, business_type, factory_id, intent_category
              FROM ai_intent_configs
             WHERE is_active IS NOT FALSE AND deleted_at IS NULL
            """
        )
    finally:
        await conn.close()
    out = []
    for r in rows:
        import json
        try:
            kws = json.loads(r["kw"]) if r["kw"] else []
        except Exception:
            kws = []
        out.append({
            "code": r["intent_code"],
            "name": r["intent_name"] or "",
            "desc": r["description"] or "",
            "keywords": [k for k in kws if isinstance(k, str)],
            "tool": r["tool_name"] or "",
            "bt": r["business_type"] or "COMMON",
            "factory": r["factory_id"],
            "cat": r["intent_category"] or "",
        })
    return out


def _scope_overlap(a: Dict, b: Dict) -> bool:
    """Two intents can actually compete only if a tenant could see both."""
    if a["factory"] and b["factory"] and a["factory"] != b["factory"]:
        return False
    bt_a, bt_b = a["bt"], b["bt"]
    return bt_a == "COMMON" or bt_b == "COMMON" or bt_a == bt_b


def _restaurant_first(pair: Tuple[Dict, Dict]) -> int:
    bts = {pair[0]["bt"], pair[1]["bt"]}
    if "RESTAURANT" in bts:
        return 0
    if "COMMON" in bts:
        return 1
    return 2


def audit(rows: List[Dict[str, Any]], min_jaccard: float) -> str:
    lines: List[str] = ["# 意图库碰撞审计报告", ""]
    lines.append(f"活跃意图 {len(rows)} 条。三类碰撞: K=关键词重复 / "
                 f"D=A的关键词出现在B的描述 / S=描述近重复(Jaccard≥{min_jaccard})")
    lines.append("")

    # K: duplicated keywords
    kw_owner: Dict[str, List[Dict]] = defaultdict(list)
    for r in rows:
        for k in set(r["keywords"]):
            if len(k) >= 2 and k not in _STOP_KEYWORDS:
                kw_owner[k].append(r)
    k_hits = []
    for k, owners in kw_owner.items():
        owners = [o for o in owners]
        for i in range(len(owners)):
            for j in range(i + 1, len(owners)):
                if _scope_overlap(owners[i], owners[j]):
                    k_hits.append((k, owners[i], owners[j]))
    k_hits.sort(key=lambda h: _restaurant_first((h[1], h[2])))
    lines.append(f"## K 关键词重复 ({len(k_hits)} 对)")
    for k, a, b in k_hits[:60]:
        lines.append(f"- 「{k}」: {a['code']}({a['bt']}) ↔ {b['code']}({b['bt']})")
    lines.append("")

    # D: keyword-in-description (the LLM-steal class)
    d_hits = []
    for a in rows:
        strong_kws = [k for k in set(a["keywords"])
                      if len(k) >= 3 and k not in _STOP_KEYWORDS]
        for b in rows:
            if a is b or not _scope_overlap(a, b):
                continue
            stolen = [k for k in strong_kws if k in (b["desc"] or "")]
            if stolen:
                d_hits.append((a, b, stolen))
    d_hits.sort(key=lambda h: (_restaurant_first((h[0], h[1])), -len(h[2])))
    lines.append(f"## D 关键词↔描述窃取 ({len(d_hits)} 对) — 外卖几成事故同类")
    for a, b, stolen in d_hits[:80]:
        lines.append(
            f"- {a['code']} 的关键词 {stolen[:3]} 出现在 "
            f"{b['code']}({b['bt']}, tool={b['tool'] or '-'}) 的描述中"
        )
    lines.append("")

    # S: near-duplicate descriptions
    grams = [(r, _bigrams(r["desc"])) for r in rows if len(_norm(r["desc"])) >= 10]
    s_hits = []
    for i in range(len(grams)):
        for j in range(i + 1, len(grams)):
            a, ga = grams[i]
            b, gb = grams[j]
            if not _scope_overlap(a, b):
                continue
            sim = _jaccard(ga, gb)
            if sim >= min_jaccard:
                s_hits.append((sim, a, b))
    s_hits.sort(key=lambda h: (_restaurant_first((h[1], h[2])), -h[0]))
    lines.append(f"## S 描述近重复 ({len(s_hits)} 对)")
    for sim, a, b in s_hits[:60]:
        lines.append(f"- {sim:.2f} {a['code']}({a['bt']}) ↔ {b['code']}({b['bt']})")

    return "\n".join(lines)


async def run(min_jaccard: float) -> None:
    rows = await _load_rows()
    print(audit(rows, min_jaccard))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--min-jaccard", type=float, default=0.35)
    args = parser.parse_args()
    asyncio.run(run(args.min_jaccard))


if __name__ == "__main__":
    main()
