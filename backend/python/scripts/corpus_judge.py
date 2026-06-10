"""Offline LLM-judge for P2 quality layer (G2 — global self-distillation cold-start).

Scores quality=3 rows in smart_bi_distillation_samples on three axes:
  - 事实性 (factuality): does the answer follow from the context, no fabrication
  - 可操作性 (actionability): is the answer actionable for a business user
  - 流畅 (fluency): is the answer clear and fluent Chinese

Promotion rule: overall >= 4 AND factuality >= 4 → quality=4 (training-ready).
            Otherwise stay at quality=3 (or demote to quality=2 if factuality<3).

Idempotency: rows with metadata->'judge' already set are skipped.

Usage:
    # Dry-run — print counts, no LLM/DB writes:
    cd backend/python
    python -m scripts.corpus_judge --dry-run

    # Real run (all sources):
    python -m scripts.corpus_judge --limit 100

    # Scope to one source:
    python -m scripts.corpus_judge --source intent_llm --limit 200

Environment variables (real run):
    SMARTBI_PROD_DSN   — asyncpg DSN e.g. postgresql://user:pass@host/smartbi_prod_db
                         Falls back to settings.postgres_url (smartbi_db).
    LLM_API_KEY / DASHSCOPE_API_KEY / LLM_ALIYUN_C_API_KEY etc.
                       — passed through common.llm_router.call_chain (SLOT.REVIEW).
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Path bootstrap — works whether run as ``python scripts/corpus_judge.py``
# or ``python -m scripts.corpus_judge`` from backend/python.
# Must add both backend/python AND backend/python/smartbi so that relative
# imports inside the smartbi service chain resolve correctly.
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.normpath(os.path.join(_SCRIPT_DIR, ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

# Now safe to import project modules
from common.llm_router import call_chain, SLOT  # noqa: E402

logger = logging.getLogger("corpus_judge")

# ---------------------------------------------------------------------------
# Budget-bypass stub — identical pattern to seed_chart_insight_corpus.py.
# This is an offline batch job; we never touch per-factory request quotas.
# ---------------------------------------------------------------------------

class _NoBudgetBucket:
    blocked: bool = False


class _NoBudgetTracker:
    """Never blocks. Safe for offline batch use only."""

    async def check_budget(self, factory_id: str) -> _NoBudgetBucket:
        return _NoBudgetBucket()

    async def consume(self, factory_id: str, tokens: int) -> None:
        pass


# ---------------------------------------------------------------------------
# Judge prompt
# ---------------------------------------------------------------------------

_JUDGE_SYSTEM_PROMPT = """\
你是一位严格的AI回答质量评审专家，专门评审面向中国食品工厂和餐饮企业的智能分析系统的回答质量。
评审时务必保持客观公正，对每个维度独立评分，不要互相影响。
你的评分将用于训练数据筛选，分数过高会导致低质量数据污染训练集，请从严评分。
"""

_JUDGE_USER_TEMPLATE = """\
请对以下AI回答进行质量评审。

## 输入上下文（系统提供给AI的信息）
{input_text}

## AI回答（需要评审的内容）
{teacher_output}

## 评审维度说明

### 1. 事实性 (factuality) 1-5分
- 5分：回答完全基于上下文数据，所有数字/实体/结论均可在上下文中找到依据
- 4分：回答基本正确，有极少量可接受的推断，无明显捏造
- 3分：部分内容有依据，但有1-2处轻微不准确或无法核实的说法
- 2分：多处与上下文不符或存在无中生有的数字/实体
- 1分：大量捏造，严重偏离上下文事实

### 2. 可操作性 (actionability) 1-5分
- 5分：给出具体、可落地的建议或洞察，业务人员可直接采取行动
- 4分：给出有实际参考价值的建议，方向明确
- 3分：有一定参考价值，但建议较为笼统
- 2分：仅描述现象，缺乏任何实际指导
- 1分：无实际意义，或建议有害

### 3. 流畅 (fluency) 1-5分
- 5分：表达清晰、专业，符合业务场景用语习惯
- 4分：表达清晰，基本无问题
- 3分：基本可读，有轻微表达问题
- 2分：存在明显表达问题，影响理解
- 1分：难以理解

### 4. 总体 (overall) 1-5分
综合以上三个维度，给出总体评分。注意：
- 如果事实性<=2，总体不得超过3分
- 如果任何维度<=1，总体不得超过2分
- 总体分应是对三个维度的综合判断，而非简单平均

## 输出格式要求
请严格按以下JSON格式输出，不要有任何额外内容：
{{"factuality": <1-5整数>, "actionability": <1-5整数>, "fluency": <1-5整数>, "overall": <1-5整数>}}
"""


def build_judge_prompt(input_text: str, teacher_output: str) -> Tuple[str, str]:
    """Build system + user messages for the LLM judge.

    Returns:
        (system_prompt, user_prompt) tuple ready to be passed to call_chain.
    """
    # Truncate to avoid overly long prompts — keep context usable
    input_text_trimmed = input_text[:3000] if len(input_text) > 3000 else input_text
    output_trimmed = teacher_output[:2000] if len(teacher_output) > 2000 else teacher_output

    user_prompt = _JUDGE_USER_TEMPLATE.format(
        input_text=input_text_trimmed,
        teacher_output=output_trimmed,
    )
    return _JUDGE_SYSTEM_PROMPT, user_prompt


def parse_judge_scores(raw_text: str) -> Optional[Dict[str, int]]:
    """Extract and validate JSON scores from the LLM judge's raw response.

    Handles markdown fences and leading/trailing text. Returns None on parse
    failure or when scores are outside the valid 1-5 range.

    Returns:
        Dict with keys factuality, actionability, fluency, overall (all int 1-5),
        or None if parsing/validation fails.
    """
    if not raw_text:
        return None

    # Strip markdown code fences if present
    text = raw_text.strip()
    if "```" in text:
        # Extract content between fences
        import re
        fence_match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
        if fence_match:
            text = fence_match.group(1)
        else:
            # Just remove the fence markers
            text = re.sub(r"```(?:json)?", "", text).strip().rstrip("`").strip()

    # Find the first JSON object
    try:
        import re
        json_match = re.search(r"\{[^{}]+\}", text, re.DOTALL)
        if not json_match:
            logger.debug("No JSON object found in judge response: %r", raw_text[:200])
            return None
        obj = json.loads(json_match.group(0))
    except json.JSONDecodeError as e:
        logger.debug("JSON parse failed: %s — raw: %r", e, raw_text[:200])
        return None

    # Validate required keys and score range
    required_keys = ("factuality", "actionability", "fluency", "overall")
    scores: Dict[str, int] = {}
    for key in required_keys:
        if key not in obj:
            logger.debug("Missing key %r in judge scores: %r", key, obj)
            return None
        try:
            val = int(obj[key])
        except (TypeError, ValueError):
            logger.debug("Non-integer value for %r: %r", key, obj[key])
            return None
        if not 1 <= val <= 5:
            logger.debug("Score out of range for %r: %d", key, val)
            return None
        scores[key] = val

    return scores


def decide_quality(scores: Dict[str, int]) -> int:
    """Determine the new quality value from judge scores.

    Promotion rule:
      - overall >= 4 AND factuality >= 4 → quality=4 (training-ready)
      - factuality < 3                   → quality=2 (clearly bad, demote)
      - otherwise                        → quality=3 (stay, excluded from export)

    Returns:
        New quality integer (2, 3, or 4).
    """
    if scores["overall"] >= 4 and scores["factuality"] >= 4:
        return 4
    if scores["factuality"] < 3:
        return 2
    return 3


# ---------------------------------------------------------------------------
# DB helpers
# ---------------------------------------------------------------------------

_FETCH_SQL = """
    SELECT id, input_text, teacher_output, metadata
    FROM smart_bi_distillation_samples
    WHERE quality = 3
      AND (metadata IS NULL OR NOT (metadata ? 'judge'))
    {source_clause}
    ORDER BY id
    LIMIT $1
"""

_UPDATE_SQL = """
    UPDATE smart_bi_distillation_samples
    SET quality = $1,
        metadata = COALESCE(metadata, '{}'::jsonb) || $2::jsonb
    WHERE id = $3
"""


async def _make_pool(dsn: Optional[str]):
    """Create asyncpg pool. Returns None if no DB creds available."""
    import asyncpg  # noqa: PLC0415

    effective_dsn = dsn
    if not effective_dsn:
        try:
            from smartbi.config import get_settings  # noqa: PLC0415
            effective_dsn = get_settings().postgres_url
        except Exception as exc:
            logger.warning("Could not load settings for pool DSN: %s", exc)
            return None

    if not effective_dsn:
        logger.warning("No DSN available — pool will be None.")
        return None

    try:
        pool = await asyncpg.create_pool(effective_dsn, min_size=1, max_size=3)
        logger.info("asyncpg pool created (DSN prefix: %s...)", effective_dsn[:30])
        return pool
    except Exception as exc:
        logger.error("Failed to create asyncpg pool: %s", exc)
        return None


async def fetch_unjudged_rows(
    pool,
    limit: int,
    source: Optional[str] = None,
) -> List[Dict[str, Any]]:
    """Fetch quality=3 rows without a 'judge' metadata key."""
    if pool is None:
        return []

    source_clause = "AND source = $2" if source else ""
    sql = _FETCH_SQL.format(source_clause=source_clause)

    async with pool.acquire() as conn:
        if source:
            rows = await conn.fetch(sql, limit, source)
        else:
            rows = await conn.fetch(sql, limit)

    result = []
    for row in rows:
        result.append({
            "id": row["id"],
            "input_text": row["input_text"] or "",
            "teacher_output": row["teacher_output"] or "",
            "metadata": row["metadata"],
        })
    return result


async def apply_judge_result(
    pool,
    row_id: int,
    new_quality: int,
    scores: Dict[str, int],
) -> None:
    """Persist the judge outcome: update quality + merge scores into metadata."""
    judge_patch = json.dumps({"judge": scores}, ensure_ascii=False)
    async with pool.acquire() as conn:
        await conn.execute(_UPDATE_SQL, new_quality, judge_patch, row_id)


# ---------------------------------------------------------------------------
# Core judging logic
# ---------------------------------------------------------------------------

async def judge_row(
    input_text: str,
    teacher_output: str,
    timeout: float = 60.0,
) -> Optional[Dict[str, int]]:
    """Call qwen3-max (SLOT.REVIEW) to score one corpus row.

    Returns parsed scores dict, or None if the LLM call or parse fails.
    """
    system_prompt, user_prompt = build_judge_prompt(input_text, teacher_output)

    payload = {
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "max_tokens": 128,
        "temperature": 0.0,  # deterministic for scoring
    }

    try:
        response = await call_chain(SLOT.REVIEW, payload, timeout=timeout)
    except RuntimeError as exc:
        logger.warning("LLM call exhausted all providers: %s", exc)
        return None
    except Exception as exc:
        logger.warning("LLM call failed unexpectedly: %s", exc)
        return None

    # Extract content from OpenAI-compatible response
    try:
        choices = response.get("choices") or []
        if not choices:
            logger.warning("Empty choices in LLM response")
            return None
        raw_text = choices[0].get("message", {}).get("content", "") or ""
    except Exception as exc:
        logger.warning("Could not extract content from response: %s", exc)
        return None

    scores = parse_judge_scores(raw_text)
    if scores is None:
        logger.warning("Score parse failed. Raw response: %r", raw_text[:300])
    return scores


# ---------------------------------------------------------------------------
# Main runner
# ---------------------------------------------------------------------------

async def run_judge(
    limit: int,
    source: Optional[str] = None,
    dry_run: bool = False,
    dsn: Optional[str] = None,
) -> None:
    """Main judging loop.

    Args:
        limit: Maximum number of rows to process.
        source: Optional source filter (e.g. 'intent_llm'). None = all sources.
        dry_run: If True, fetch and count rows only — no LLM calls, no DB writes.
        dsn: Optional asyncpg DSN override.
    """
    pool = await _make_pool(dsn) if not dry_run else None

    rows = await fetch_unjudged_rows(pool or None, limit, source)

    if dry_run:
        source_tag = f"source={source!r}" if source else "all sources"
        print(f"\nDRY-RUN: found {len(rows)} quality=3 rows without 'judge' metadata "
              f"({source_tag}, limit={limit}).")
        print("No LLM calls or DB writes performed.")
        return

    n_judged = 0
    n_promoted = 0   # → quality=4
    n_rejected = 0   # → quality=2
    n_unchanged = 0  # → quality=3
    n_failed = 0     # LLM/parse errors

    for idx, row in enumerate(rows):
        row_id = row["id"]
        logger.debug("[%d/%d] Judging row id=%d", idx + 1, len(rows), row_id)

        scores = await judge_row(row["input_text"], row["teacher_output"])
        if scores is None:
            n_failed += 1
            logger.warning("[%d/%d] Judge failed for row id=%d — skipping",
                           idx + 1, len(rows), row_id)
            continue

        new_quality = decide_quality(scores)
        await apply_judge_result(pool, row_id, new_quality, scores)
        n_judged += 1

        if new_quality == 4:
            n_promoted += 1
        elif new_quality == 2:
            n_rejected += 1
        else:
            n_unchanged += 1

        if (idx + 1) % 20 == 0:
            logger.info(
                "[%d/%d] Running totals — judged=%d promoted=%d unchanged=%d "
                "rejected=%d failed=%d",
                idx + 1, len(rows),
                n_judged, n_promoted, n_unchanged, n_rejected, n_failed,
            )

    # Final summary
    logger.info("=" * 60)
    print(
        f"\nJUDGE COMPLETE\n"
        f"  judged  : {n_judged}\n"
        f"  promoted: {n_promoted}  (quality 3→4, training-ready)\n"
        f"  unchanged: {n_unchanged}  (quality stays 3)\n"
        f"  rejected: {n_rejected}  (quality 3→2, clearly bad)\n"
        f"  failed  : {n_failed}  (LLM/parse errors, skipped)\n"
    )
    logger.info("=" * 60)

    if pool is not None:
        try:
            await pool.close()
        except Exception:
            pass


# ---------------------------------------------------------------------------
# CLI entry-point
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Offline LLM-judge for P2 quality layer (G2 corpus scoring).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "--source", default=None,
        help="Scope to a specific source value (e.g. 'intent_llm'). Default: all.",
    )
    parser.add_argument(
        "--limit", type=int, default=100,
        help="Max number of rows to judge per run (default: 100).",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Count rows only; skip LLM calls and DB writes.",
    )
    parser.add_argument(
        "--dsn", default=os.environ.get("SMARTBI_PROD_DSN", ""),
        help="asyncpg DSN (overrides SMARTBI_PROD_DSN env var).",
    )
    parser.add_argument(
        "--log-level", default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )

    asyncio.run(run_judge(
        limit=args.limit,
        source=args.source or None,
        dry_run=args.dry_run,
        dsn=args.dsn or None,
    ))


if __name__ == "__main__":
    main()
