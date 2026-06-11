"""Offline LLM-judge for P2 quality layer (G2 — global self-distillation cold-start).

Scores quality=3 rows in smart_bi_distillation_samples on three axes:
  - 事实性 (factuality): does the answer follow from the context, no fabrication
  - 可操作性 (actionability): is the answer actionable for a business user
  - 流畅 (fluency): is the answer clear and fluent Chinese

Promotion rule: overall >= 4 AND factuality >= 4 → quality=4 (training-ready).
            Otherwise stay at quality=3 (or demote to quality=2 if factuality<3).

Idempotency: rows with metadata->'judge' already set are skipped.

Cross-family judging (--cross-family):
    Re-judges quality=4 rows whose source is NOT 'chart_insight' (i.e. chat_qa,
    agent_insight, materialization — buckets without independent claims-pinning).
    Uses a DIFFERENT model family from qwen (the original teacher/judge family) to
    break the self-eval loop where teacher and judge share the same hallucination
    blind spots.  Family resolution:
        qwen    → uses SLOT.REVIEW chain filtered to accounts where the HEAD model
                  family is qwen (aliyun_c/b/a)   ← default, original behaviour
        glm     → filters chain to 'zhipu' account (GLM-family)
        deepseek→ filters chain to 'tencent' or 'aliyun_a_deepseek' account
    Demote rule: if cross-family overall < 4 OR cross-family factuality < 4
                 → quality 4 → 3  (no longer training-ready)
    Records metadata.judge_crossfamily = {model, family, scores, agreed: bool}.
    Idempotent: rows already having metadata.judge_crossfamily are skipped.
    Graceful on quota-exhausted: log warning, skip row (keeps current quality).

Usage:
    # Dry-run — print counts, no LLM/DB writes:
    cd backend/python
    python -m scripts.corpus_judge --dry-run

    # Real run (all sources):
    python -m scripts.corpus_judge --limit 100

    # Scope to one source:
    python -m scripts.corpus_judge --source intent_llm --limit 200

    # Cross-family re-judge of non-chart_insight quality=4 rows using GLM family:
    python -m scripts.corpus_judge --cross-family --judge-family glm --limit 200

    # Cross-family using DeepSeek family:
    python -m scripts.corpus_judge --cross-family --judge-family deepseek --limit 200

    # Override specific judge model (any family):
    python -m scripts.corpus_judge --cross-family --judge-model glm-4.5-air --limit 100

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
# Cross-family judge helpers
# ---------------------------------------------------------------------------

# Sources that already have independent G1 claims-pinning (server-recompute).
# These do NOT need cross-family judging — the self-eval loop is already broken
# by the independent verification layer.
_CLAIMS_PINNED_SOURCES: frozenset = frozenset({"chart_insight"})

# Mapping from logical family name → list of account names to include in the
# SLOT.REVIEW chain when calling the cross-family judge.
# Each family maps to accounts whose lead model belongs to that family.
#   qwen     → aliyun accounts (qwen3-max / qwen-max family)
#   glm      → zhipu account (GLM family) — independent of aliyun
#   deepseek → tencent (deepseek-v4-pro, free on TokenHub) +
#              aliyun_a_deepseek (DashScope-hosted deepseek, own free pool)
_FAMILY_ACCOUNTS: Dict[str, List[str]] = {
    "qwen": ["aliyun_c", "aliyun_b", "aliyun_a"],
    "glm": ["zhipu"],
    "deepseek": ["tencent"],  # aliyun_a_deepseek 移除: A 的 deepseek-v4-pro 是 "- -" 付费
    # nonqwen = MULTI-account non-qwen chain so the router's deep fallback actually
    # works for cross-family judging. Single-account (e.g. ["zhipu"]) short-circuits
    # the fallback: one circuit-breaker-open/exhausted account → "all providers
    # exhausted" with no fallback. zhipu(glm)→tencent(glm-5.1/kimi/minimax/deepseek
    # free)→aliyun_a_deepseek(deepseek) — all different family from the qwen3-max teacher.
    "nonqwen": ["zhipu", "tencent"],  # 移除 aliyun_a_deepseek(A 付费雷); tencent 有免费 deepseek/glm
}

# Default cross-family to use when --cross-family is given without --judge-family.
# GLM is first choice: completely separate vendor from Aliyun/qwen, independent
# free pool on Zhipu open.bigmodel.cn.
_DEFAULT_CROSS_FAMILY = "nonqwen"  # multi-account non-qwen chain (fallback works); was "glm"(single zhipu=no fallback)


def resolve_judge_chain(
    judge_family: Optional[str] = None,
    judge_model: Optional[str] = None,
) -> Tuple[Optional[List[str]], Optional[str]]:
    """Resolve the account chain and optional model override for the cross-family judge.

    Args:
        judge_family: One of 'qwen', 'glm', 'deepseek'.  None → use default chain.
        judge_model:  Explicit model name override (e.g. 'glm-4.5-air').
                      When given, the router still walks the resolved account chain
                      but each attempt uses this model instead of its slot default.
                      NOTE: the router overwrites ``payload["model"]`` per slot
                      entry, so we cannot force a specific model via call_chain
                      alone.  The model is passed back to the caller so it can be
                      injected directly into the payload before calling call_chain
                      with the appropriate account filter.

    Returns:
        (account_filter, model_override_or_None)
    """
    family = (judge_family or "").lower() or None
    account_filter: Optional[List[str]] = None
    if family:
        account_filter = _FAMILY_ACCOUNTS.get(family)
        if account_filter is None:
            logger.warning(
                "Unknown judge family %r — using default SLOT.REVIEW chain", family
            )
    return account_filter, judge_model or None


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

# Cross-family: fetch quality=4 rows that are NOT from claims-pinned sources
# and do NOT yet have a 'judge_crossfamily' key in their metadata.
_CROSSFAMILY_FETCH_SQL = """
    SELECT id, input_text, teacher_output, metadata, source
    FROM smart_bi_distillation_samples
    WHERE quality = 4
      AND source NOT IN ({sources_placeholder})
      AND (metadata IS NULL OR NOT (metadata ? 'judge_crossfamily'))
    ORDER BY id
    LIMIT $1
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


async def fetch_crossfamily_rows(
    pool,
    limit: int,
    source: Optional[str] = None,
) -> List[Dict[str, Any]]:
    """Fetch quality=4 rows from non-claims-pinned sources without cross-family judgment.

    Claims-pinned sources (e.g. chart_insight) have independent G1 verification,
    so they are excluded — the self-eval loop is already broken for them.

    Args:
        pool: asyncpg pool.
        limit: Max rows to return.
        source: Optional source filter (in addition to the claims-pinned exclusion).
    """
    if pool is None:
        return []

    # Build the NOT IN list for claims-pinned sources
    pinned = list(_CLAIMS_PINNED_SOURCES)
    placeholders = ", ".join(f"${i + 2}" for i in range(len(pinned)))
    sql = _CROSSFAMILY_FETCH_SQL.format(sources_placeholder=placeholders)

    # Add optional source filter
    source_clause_offset = len(pinned) + 2
    if source:
        sql = sql.rstrip() + f"\n      AND source = ${source_clause_offset}"

    async with pool.acquire() as conn:
        params: List[Any] = [limit] + pinned
        if source:
            params.append(source)
        rows = await conn.fetch(sql, *params)

    return [
        {
            "id": row["id"],
            "input_text": row["input_text"] or "",
            "teacher_output": row["teacher_output"] or "",
            "metadata": row["metadata"],
            "source": row["source"],
        }
        for row in rows
    ]


async def apply_crossfamily_result(
    pool,
    row_id: int,
    new_quality: int,
    cf_scores: Dict[str, int],
    cf_model: str,
    cf_family: str,
    agreed: bool,
) -> None:
    """Persist cross-family judge outcome.

    Records metadata.judge_crossfamily and updates quality if demoted.
    The 'agreed' flag indicates whether the cross-family judge concurred with q4.
    """
    cf_patch = json.dumps(
        {
            "judge_crossfamily": {
                "model": cf_model,
                "family": cf_family,
                "scores": cf_scores,
                "agreed": agreed,
            }
        },
        ensure_ascii=False,
    )
    async with pool.acquire() as conn:
        await conn.execute(_UPDATE_SQL, new_quality, cf_patch, row_id)


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
        "max_tokens": 2000,  # 给推理型judge(glm-4.5-air等)留够reasoning+JSON空间; 128会饿死reasoning致content空
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


async def judge_row_with_chain(
    input_text: str,
    teacher_output: str,
    account_filter: Optional[List[str]] = None,
    model_override: Optional[str] = None,
    timeout: float = 60.0,
) -> Tuple[Optional[Dict[str, int]], Optional[str]]:
    """Call the SLOT.REVIEW chain filtered to a specific account family.

    Used by the cross-family judge to route to a non-qwen provider.

    Args:
        input_text: The corpus input context.
        teacher_output: The teacher answer to score.
        account_filter: List of account names to restrict the chain to
                        (e.g. ['zhipu'] for GLM family).  None → full chain.
        model_override: If set, force this model name in the payload instead of
                        the slot default.  Useful when calling a specific model
                        directly (e.g. 'glm-4.5-air').
        timeout: Per-provider timeout in seconds.

    Returns:
        (scores_dict_or_None, actual_model_used_or_None)
        The actual_model is extracted from the response if available, otherwise
        falls back to model_override or a best-guess from the account_filter.
    """
    system_prompt, user_prompt = build_judge_prompt(input_text, teacher_output)

    payload: Dict[str, Any] = {
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "max_tokens": 2000,  # 给推理型judge(glm-4.5-air等)留够reasoning+JSON空间; 128会饿死reasoning致content空
        "temperature": 0.0,
    }
    if model_override:
        payload["model"] = model_override

    try:
        response = await call_chain(
            SLOT.REVIEW,
            payload,
            chain=account_filter,
            timeout=timeout,
        )
    except RuntimeError as exc:
        # Quota exhausted for this family — log and signal gracefully
        logger.warning(
            "Cross-family judge: all providers exhausted for family filter %r: %s",
            account_filter,
            exc,
        )
        return None, None
    except Exception as exc:
        logger.warning("Cross-family judge: unexpected LLM failure: %s", exc)
        return None, None

    # Extract content
    try:
        choices = response.get("choices") or []
        if not choices:
            logger.warning("Cross-family judge: empty choices in response")
            return None, None
        raw_text = choices[0].get("message", {}).get("content", "") or ""
        # Try to capture the actual model used from the response
        actual_model: Optional[str] = response.get("model") or model_override
    except Exception as exc:
        logger.warning("Cross-family judge: could not extract content: %s", exc)
        return None, None

    scores = parse_judge_scores(raw_text)
    if scores is None:
        logger.warning(
            "Cross-family judge: score parse failed. Raw: %r", raw_text[:300]
        )
    return scores, actual_model


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


async def run_crossfamily_judge(
    limit: int,
    judge_family: str = _DEFAULT_CROSS_FAMILY,
    judge_model: Optional[str] = None,
    source: Optional[str] = None,
    dry_run: bool = False,
    dsn: Optional[str] = None,
) -> None:
    """Cross-family re-judge loop for non-claims-pinned quality=4 rows.

    Fetches quality=4 rows from sources WITHOUT independent G1 claims-pinning
    (i.e. NOT chart_insight) that have not yet been cross-family judged.

    A DIFFERENT model family from qwen is used to break the self-eval loop
    where teacher and G2 judge share the same family's hallucination blind spots.

    Demote rule: cross-family overall < 4 OR factuality < 4 → quality 4 → 3.
    Agreement:  cross-family overall >= 4 AND factuality >= 4 → keep quality=4.
    Both outcomes record metadata.judge_crossfamily for audit + idempotency.

    Graceful on quota exhaustion: logs "family X exhausted, skipping" and moves
    on without crashing or changing the row's quality.

    Args:
        limit: Max rows to process.
        judge_family: 'glm', 'deepseek', or 'qwen'. Default: 'glm'.
        judge_model: Optional explicit model name (overrides slot default).
        source: Optional source filter within the non-claims-pinned set.
        dry_run: Count rows only, no LLM or DB writes.
        dsn: Optional asyncpg DSN override.
    """
    account_filter, model_override = resolve_judge_chain(judge_family, judge_model)

    # Describe the resolved family for logging
    family_label = judge_family or _DEFAULT_CROSS_FAMILY
    if account_filter:
        logger.info(
            "Cross-family judge: family=%r → accounts=%r model_override=%r",
            family_label,
            account_filter,
            model_override,
        )
    else:
        logger.warning(
            "Cross-family judge: unknown family %r — using full SLOT.REVIEW chain. "
            "This may re-use the same family as the teacher (self-eval loop not broken).",
            judge_family,
        )

    pool = await _make_pool(dsn) if not dry_run else None
    rows = await fetch_crossfamily_rows(pool or None, limit, source)

    if dry_run:
        pinned = ", ".join(sorted(_CLAIMS_PINNED_SOURCES))
        print(
            f"\nCROSS-FAMILY DRY-RUN: found {len(rows)} quality=4 non-claims-pinned rows "
            f"without 'judge_crossfamily' metadata\n"
            f"  (excluded sources with G1 claims-pinning: {pinned})\n"
            f"  family={family_label!r}  accounts={account_filter!r}\n"
            f"  limit={limit}\n"
            "No LLM calls or DB writes performed."
        )
        return

    n_judged = 0
    n_agreed = 0    # cross-family also ≥4 → keep q4
    n_demoted = 0   # cross-family < 4 → q4 → q3
    n_skipped = 0   # quota exhausted for this family
    n_failed = 0    # LLM/parse failure (not quota)

    for idx, row in enumerate(rows):
        row_id = row["id"]
        logger.debug(
            "[%d/%d] Cross-family judging row id=%d source=%s",
            idx + 1, len(rows), row_id, row.get("source"),
        )

        cf_scores, actual_model = await judge_row_with_chain(
            row["input_text"],
            row["teacher_output"],
            account_filter=account_filter,
            model_override=model_override,
        )

        if cf_scores is None:
            # Distinguish quota-exhausted (account_filter set + no result) from other failures
            if account_filter is not None:
                logger.warning(
                    "[%d/%d] Cross-family judge: family %r quota exhausted or unavailable "
                    "for row id=%d — skipping (row keeps current quality)",
                    idx + 1, len(rows), family_label, row_id,
                )
                n_skipped += 1
            else:
                logger.warning(
                    "[%d/%d] Cross-family judge: LLM failure for row id=%d — skipping",
                    idx + 1, len(rows), row_id,
                )
                n_failed += 1
            continue

        # Determine agreement: both overall and factuality must be ≥4 to agree
        agreed = cf_scores["overall"] >= 4 and cf_scores["factuality"] >= 4
        new_quality = 4 if agreed else 3

        used_model = actual_model or model_override or family_label
        await apply_crossfamily_result(
            pool,
            row_id,
            new_quality,
            cf_scores,
            cf_model=used_model,
            cf_family=family_label,
            agreed=agreed,
        )
        n_judged += 1

        if agreed:
            n_agreed += 1
            logger.debug(
                "Row id=%d: cross-family AGREED (q4 retained). scores=%s",
                row_id, cf_scores,
            )
        else:
            n_demoted += 1
            logger.info(
                "Row id=%d: cross-family DEMOTED q4→q3. scores=%s model=%s",
                row_id, cf_scores, used_model,
            )

        if (idx + 1) % 20 == 0:
            logger.info(
                "[%d/%d] Cross-family running totals — judged=%d agreed=%d "
                "demoted=%d skipped(quota)=%d failed=%d",
                idx + 1, len(rows),
                n_judged, n_agreed, n_demoted, n_skipped, n_failed,
            )

    # Final summary
    print(
        f"\nCROSS-FAMILY JUDGE COMPLETE  (family={family_label!r})\n"
        f"  judged   : {n_judged}\n"
        f"  agreed   : {n_agreed}  (cross-family ≥4 → q4 retained)\n"
        f"  demoted  : {n_demoted}  (cross-family <4 → q4→q3)\n"
        f"  skipped  : {n_skipped}  (family quota exhausted, row unchanged)\n"
        f"  failed   : {n_failed}  (LLM/parse error, row unchanged)\n"
    )

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
    # Cross-family judge arguments
    parser.add_argument(
        "--cross-family", action="store_true",
        help=(
            "Run cross-family re-judge on quality=4 rows from non-claims-pinned "
            "sources (chat_qa/agent_insight/materialization). Requires a different "
            "model family to break the qwen self-eval loop."
        ),
    )
    parser.add_argument(
        "--judge-family",
        default=_DEFAULT_CROSS_FAMILY,
        choices=list(_FAMILY_ACCOUNTS.keys()),
        help=(
            f"Model family for the cross-family judge. "
            f"Default: '{_DEFAULT_CROSS_FAMILY}'. "
            f"Choices: {', '.join(_FAMILY_ACCOUNTS.keys())}."
        ),
    )
    parser.add_argument(
        "--judge-model", default=None,
        help=(
            "Explicit model name override for cross-family judging "
            "(e.g. 'glm-4.5-air'). When given, forces this model in the "
            "payload before calling the resolved family chain."
        ),
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )

    if args.cross_family:
        asyncio.run(run_crossfamily_judge(
            limit=args.limit,
            judge_family=args.judge_family,
            judge_model=args.judge_model or None,
            source=args.source or None,
            dry_run=args.dry_run,
            dsn=args.dsn or None,
        ))
    else:
        asyncio.run(run_judge(
            limit=args.limit,
            source=args.source or None,
            dry_run=args.dry_run,
            dsn=args.dsn or None,
        ))


if __name__ == "__main__":
    main()
