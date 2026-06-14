"""P1 cold-start sweep: replay a question bank through the real chat pipeline.

For each (factory, question) pair, loads the factory's REAL upload data from
``smart_bi_dynamic_data``, calls ``InsightGenerator.generate_insights`` with
that data (the same path as ``general_analysis`` in ``chat.py``), and persists
the resulting (input_text_with_data_context → answer) pair into
``smart_bi_distillation_samples`` via ``persist_distillation_sample``.

**P0-1 prerequisite**: input_text embeds a data-context summary (not just the
bare query) so the corpus pair is self-contained — a future student model learns
to answer FROM data, not hallucinate numbers.  This mirrors the P0-1 fix in
``chat.py:_build_qa_input_text``.

Key design decisions
--------------------
- **Drives the REAL pipeline** — loads real factory data, calls the real LLM
  through ``InsightGenerator.generate_insights``, zero synthetic.
- **Data source**: ``smart_bi_dynamic_data`` for the factory's largest completed
  upload (same fallback logic as ``general_analysis``).
- **Budget bypass**: ``InsightGenerator`` calls ``llm_client`` directly (not
  through ``AgentBudgetTracker``), so the per-factory daily token quota is
  not consumed.
- **Idempotent**: ``persist_distillation_sample`` uses ``ON CONFLICT(input_hash)
  DO UPDATE``.  Re-running replays all questions and refreshes existing rows
  if the answer improved.
- **Quality = 4** when data_context passes lint (substantive numeric figures);
  **quality = 3** when data is unavailable (bare organic, still useful).

Usage::

    # Dry-run: list questions / factories without LLM / DB writes
    python -m scripts.sweep_chat_qa_replay --dry-run

    # Real sweep: default factories × built-in 26-question bank
    python -m scripts.sweep_chat_qa_replay

    # Specific factories
    python -m scripts.sweep_chat_qa_replay --factories F001,F006,qhj_prod --limit-data 500

    # Concurrency control
    python -m scripts.sweep_chat_qa_replay --concurrency 3

Environment
-----------
Same as running server: DASHSCOPE_API_KEY / LLM_API_KEY, POSTGRES_*.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Path bootstrap
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.normpath(os.path.join(_SCRIPT_DIR, ".."))
if _PYTHON_ROOT not in sys.path:
    sys.path.insert(0, _PYTHON_ROOT)
# main.py adds smartbi/ to sys.path so bare `from services.X import ...` resolves to
# smartbi/services/X (the real pipeline modules use that bare import). Mirror it here.
_SMARTBI_DIR = os.path.join(_PYTHON_ROOT, "smartbi")
if _SMARTBI_DIR not in sys.path:
    sys.path.insert(0, _SMARTBI_DIR)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("sweep_chat_qa_replay")

# ---------------------------------------------------------------------------
# Default factory list
# ---------------------------------------------------------------------------
DEFAULT_FACTORIES: List[str] = [
    "F001",
    "F006",
    "qhj_prod",
    "RES_3101_009",
    "R_GML_DEMO",
]

# ---------------------------------------------------------------------------
# Question bank
# Covers the restaurant ~21-question catalog + common intent questions.
# Organised by intent category so coverage can be tracked per-bucket.
# ---------------------------------------------------------------------------
QUESTION_BANK: List[Dict[str, str]] = [
    # --- 餐饮驾驶舱 / 经营概况 ---
    {"q": "本期整体营业情况如何？",                   "category": "overview"},
    {"q": "整体营收趋势怎么样？",                     "category": "overview"},
    {"q": "客单价变化趋势如何？",                     "category": "overview"},
    {"q": "本期和上期相比，营业额增长了多少？",        "category": "overview"},
    # --- 门店分析 ---
    {"q": "哪些门店表现最好，哪些最差？",              "category": "store"},
    {"q": "各门店营业额占比是多少？",                  "category": "store"},
    {"q": "门店间差距为什么这么大？",                  "category": "store"},
    {"q": "门店销量对比分析",                         "category": "store"},
    # --- 商品/菜品分析 ---
    {"q": "最热卖的菜品是什么？",                     "category": "product"},
    {"q": "哪些菜品贡献了最多收入？",                 "category": "product"},
    {"q": "菜品销量排行榜",                           "category": "product"},
    {"q": "哪个品类销售额最高？",                     "category": "product"},
    # --- 折扣/促销 ---
    {"q": "折扣活动对营业额有什么影响？",              "category": "discount"},
    {"q": "折扣比例是否合理？",                       "category": "discount"},
    {"q": "优惠券使用情况分析",                       "category": "discount"},
    # --- 时段/渠道 ---
    {"q": "堂食和外卖哪个渠道贡献更多？",              "category": "channel"},
    {"q": "各时段销售情况如何？",                     "category": "timeslot"},
    {"q": "周末和工作日的营业额差多少？",              "category": "timeslot"},
    # --- 成本/利润 ---
    {"q": "毛利润率是多少？",                         "category": "profit"},
    {"q": "成本占收入的比例是多少？",                  "category": "cost"},
    {"q": "主要成本构成分析",                         "category": "cost"},
    # --- 采购/原料 ---
    {"q": "原料采购情况如何？",                       "category": "purchase"},
    {"q": "库存是否充足？",                           "category": "inventory"},
    # --- 工厂生产 ---
    {"q": "生产计划完成情况如何？",                   "category": "production"},
    {"q": "本月产量是多少？",                         "category": "production"},
    # --- 诊断 ---
    {"q": "目前最需要关注的经营问题是什么？",           "category": "diagnosis"},
]


def get_question_bank() -> List[str]:
    """Return the ordered list of question strings."""
    return [item["q"] for item in QUESTION_BANK]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _derive_business_type(factory_id: str) -> str:
    """Mirror chat.py:_derive_business_type heuristic."""
    fid = (factory_id or "").upper()
    if fid.startswith("QHJ") or fid.startswith("RESTAURANT") or fid.startswith("RES_") or fid.startswith("R_"):
        return "restaurant"
    if fid.startswith("F") and len(fid) <= 6:
        return "factory"
    return "unknown"


def _build_data_context(data: List[Dict[str, Any]], answer: str) -> Optional[str]:
    """Build a compact key=value data-context string for the corpus pair.

    Mirrors ``_build_qa_data_context`` in chat.py.
    """
    try:
        if not data:
            return None
        import pandas as pd
        parts: List[str] = [f"rows={len(data)}"]
        if data[0] and isinstance(data[0], dict):
            cols = list(data[0].keys())[:8]
            parts.append(f"cols=[{','.join(str(c) for c in cols)}]")
            try:
                df = pd.DataFrame(data)
                numeric_cols = df.select_dtypes(include=["number"]).columns.tolist()
                for col in numeric_cols[:4]:
                    col_sum = df[col].sum()
                    col_max = df[col].max()
                    if col_sum != 0:
                        parts.append(f"{col}_sum={col_sum:.2f}")
                    elif col_max != 0:
                        parts.append(f"{col}_max={col_max:.2f}")
            except Exception:
                pass
        if answer and answer not in ("数据分析完成。", ""):
            parts.append(f"summary_snippet={answer[:120]}")
        return "; ".join(parts) if len(parts) > 1 else None
    except Exception:
        return None


def _qa_lint_has_data_context(data_context: Optional[str]) -> bool:
    """Mirror chat.py:_qa_lint_has_data_context."""
    import re
    if not data_context or len(data_context.strip()) < 20:
        return False
    return bool(re.search(r'[\d¥%]', data_context))


def _build_qa_input_text(query: str, data_context: Optional[str]) -> str:
    """Mirror chat.py:_build_qa_input_text."""
    header = "chat_qa|general_analysis"
    if data_context:
        return f"{header}|query:{query}|data_context:{data_context}"
    return f"{header}|query:{query}"


# ---------------------------------------------------------------------------
# Data loading
# ---------------------------------------------------------------------------

async def _load_factory_data(
    pool,
    factory_id: str,
    limit_rows: int,
) -> Tuple[Optional[int], List[Dict[str, Any]]]:
    """Load the largest completed upload's dynamic_data rows for a factory.

    Returns (upload_id, rows).  Rows are sampled to ``limit_rows`` if set.

    RLS notes:
    - smart_bi_pg_excel_uploads has FORCE ROW LEVEL SECURITY (V20260502_03):
        factory_id = app.factory_id  OR  app.factory_id = ''  OR  app.factory_id IS NULL
      Fix: set the ContextVar to `factory_id` before pool.acquire() so the pool
      setup callback applies the correct tenant GUC.
    - smart_bi_dynamic_data also has FORCE ROW LEVEL SECURITY (V20260502_04)
      with the same factory_id = app.factory_id policy.  Both tables must be
      queried on the same connection (same acquire block) so both get the RLS GUC
      applied by set_pg_connection_tenant.

    Column note: smart_bi_dynamic_data uses ``row_index`` (not ``row_number``).
    ``row_number`` is a PostgreSQL reserved function keyword that requires an
    OVER clause; using it bare in ORDER BY raises:
        ERROR: window function row_number requires an OVER clause
    which the outer except swallowed → data_rows=0 for every factory.
    Production (chat.py:994) uses no ORDER BY at all, just LIMIT.
    """
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id

    tenant_token = None
    try:
        tenant_token = set_factory_id(factory_id)
        # Use a single connection for both queries so both inherit the same
        # app.factory_id GUC applied by set_pg_connection_tenant.
        # Mirrors production chat.py:956-995 (single acquire block).
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                """SELECT id, row_count FROM smart_bi_pg_excel_uploads
                   WHERE factory_id = $1
                     AND upload_status = 'COMPLETED'
                     AND row_count > 0
                   ORDER BY row_count DESC, created_at DESC
                   LIMIT 1""",
                factory_id,
            )
            if not row:
                logger.warning(f"[data] {factory_id}: no completed uploads found")
                return None, []
            upload_id = row["id"]
            row_count = row["row_count"] or 0
            logger.info(f"[data] {factory_id}: using upload_id={upload_id} rows={row_count}")

            # Bug fix: column is row_index (not row_number).  row_number is a
            # PostgreSQL reserved window-function keyword and raises without OVER.
            # Production (chat.py:994) uses no ORDER BY — mirror that.
            limit_clause = f"LIMIT {limit_rows}" if limit_rows > 0 else ""
            fetched = await conn.fetch(
                f"""SELECT row_data FROM smart_bi_dynamic_data
                    WHERE upload_id = $1
                    ORDER BY row_index
                    {limit_clause}""",
                upload_id,
            )
        rows: List[Dict[str, Any]] = []
        for r in fetched:
            rd = r["row_data"]
            if isinstance(rd, str):
                try:
                    rd = json.loads(rd)
                except Exception:
                    continue
            if isinstance(rd, dict):
                rows.append(rd)
        logger.info(f"[data] {factory_id}: loaded {len(rows)} rows for upload {upload_id}")
        return upload_id, rows
    except Exception as e:
        logger.warning(f"[data] {factory_id}: data load failed: {e}")
        return None, []
    finally:
        if tenant_token is not None:
            reset_factory_id(tenant_token)


# ---------------------------------------------------------------------------
# Census helpers
# ---------------------------------------------------------------------------

async def _count_corpus_rows(pool, source: str = "chat_qa") -> int:
    try:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT COUNT(*) AS n FROM smart_bi_distillation_samples WHERE source = $1",
                source,
            )
        return int(row["n"]) if row else 0
    except Exception as e:
        logger.warning(f"[census] count query failed: {e}")
        return -1


# ---------------------------------------------------------------------------
# Per-question processing
# ---------------------------------------------------------------------------

def _extract_answer_from_insights_result(
    insights_result: Dict[str, Any],
    question: str,
) -> str:
    """Extract the best answer string from InsightGenerator.generate_insights() result.

    generate_insights() returns::

        {"success": bool, "insights": [...], "totalGenerated": int, "method": str}

    There is NO top-level "summary" key — that's the P0-1 bug: the old code did
    ``insights_result.get("summary", "")`` which always returned "".

    The *real* ``general_analysis`` endpoint (chat.py:1095-1103) uses:
    1. ``insights_result.get("summary", "数据分析完成。")`` → "数据分析完成。" (fallback)
    2. Then falls back to first insight's ``executive_summary`` or ``text``

    The *actual* content lives in ``insights[0]`` when type == "_meta":
      - ``executive_summary`` field on the _meta dict (injected by _parse_llm_insights)
      - or a plain ``text`` field on any insight

    So we mirror the same two-level extraction chat.py uses, but corrected to not
    treat the missing "summary" key as empty (which caused the sweep to always skip).
    """
    if not isinstance(insights_result, dict):
        return ""
    insights = insights_result.get("insights") or []
    # Pass 1: look for the _meta insight that carries executive_summary
    for ins in insights:
        if not isinstance(ins, dict):
            continue
        if ins.get("type") == "_meta":
            es = ins.get("executive_summary", "")
            if es and len(es) > 10:
                return es
            # _meta may also carry plain text
            t = ins.get("text", "")
            if t and len(t) > 10:
                return t
    # Pass 2: any insight with non-trivial text or executive_summary
    for ins in insights:
        if not isinstance(ins, dict):
            continue
        better = ins.get("executive_summary") or ins.get("text")
        if better and len(better) > 10:
            return better
    return ""


async def _process_one(
    pool,
    factory_id: str,
    question: str,
    data: List[Dict[str, Any]],
    upload_id: Optional[int],
    dry_run: bool,
    *,
    probe: bool = False,
) -> bool:
    """Run one question through InsightGenerator and persist the pair.

    probe=True: run a single real LLM call and print whether a non-empty
    corpus pair was produced — without persisting.  Useful for Opus to
    verify cheaply that the pipeline produces output before a full sweep.
    """
    business_type = _derive_business_type(factory_id)

    if dry_run:
        logger.info(
            f"[dry-run] would replay: factory={factory_id} "
            f"q={question[:50]!r} data_rows={len(data)}"
        )
        return True

    try:
        from smartbi.services.insights.generator import InsightGenerator
        from smartbi.services.distillation_capture import persist_distillation_sample

        t0 = time.monotonic()
        insight_gen = InsightGenerator()

        if data:
            # ----------------------------------------------------------------
            # WITH-DATA PATH: mirrors general_analysis lines 1086-1103.
            # Pass the raw rows to generate_insights; the generator summarises
            # them, builds the LLM prompt, and returns a result dict with shape:
            #   {"success": bool, "insights": [...], "totalGenerated": int, …}
            # NOTE: there is NO top-level "summary" key in that result — the old
            # ``insights_result.get("summary", "")`` always returned "" and made
            # the sweep silently skip every pair.  _extract_answer_from_insights_result
            # correctly pulls the answer from the _meta insight.
            # ----------------------------------------------------------------
            insights_result = await insight_gen.generate_insights(
                data,
                analysis_context=question,
            )
            answer = _extract_answer_from_insights_result(insights_result, question)
        else:
            # ----------------------------------------------------------------
            # NO-DATA PATH: mirrors general_analysis lines 1017-1048.
            # Use generate_text_analysis (bare LLM text call) so we still
            # produce a useful corpus pair even without upload data.
            # ----------------------------------------------------------------
            llm_result = await insight_gen.generate_text_analysis(question)
            answer = llm_result if (llm_result and llm_result.strip()) else ""
            insights_result = {}

        if not answer:
            logger.info(
                f"[replay] factory={factory_id} q={question[:40]!r}: "
                "InsightGenerator returned empty — skipping"
            )
            if probe:
                print(
                    f"[probe] EMPTY: factory={factory_id} q={question[:60]!r} "
                    f"data_rows={len(data)} — no corpus pair produced"
                )
            return False

        elapsed = int((time.monotonic() - t0) * 1000)

        # Build self-contained corpus pair
        data_context = _build_data_context(data, answer) if data else None
        input_text = _build_qa_input_text(question, data_context)
        quality = 4 if _qa_lint_has_data_context(data_context) else 3

        if probe:
            print(
                f"[probe] OK: factory={factory_id} q={question[:60]!r} "
                f"quality={quality} data_rows={len(data)} elapsed={elapsed}ms\n"
                f"  answer_snippet={answer[:120]!r}"
            )
            # probe mode: do not persist
            return True

        elapsed = int((time.monotonic() - t0) * 1000)

        # Build self-contained corpus pair
        data_context = _build_data_context(data, answer) if data else None
        input_text = _build_qa_input_text(question, data_context)
        quality = 4 if _qa_lint_has_data_context(data_context) else 3

        await persist_distillation_sample(
            pool,
            source="chat_qa",
            task_type="qa",
            input_text=input_text,
            teacher_output=answer,
            business_type=business_type,
            factory_id=factory_id,
            quality=quality,
            metadata={
                "sweep": "sweep_chat_qa_replay",
                "upload_id": upload_id,
                "data_rows": len(data),
                "has_data_context": data_context is not None,
                "elapsed_ms": elapsed,
            },
        )
        logger.info(
            f"[replay] factory={factory_id} q={question[:40]!r} "
            f"quality={quality} elapsed={elapsed}ms"
        )
        return True

    except Exception as e:
        logger.warning(
            f"[replay] FAILED factory={factory_id} q={question[:40]!r}: {e}"
        )
        return False


# ---------------------------------------------------------------------------
# Main sweep
# ---------------------------------------------------------------------------

async def run_sweep(
    factory_ids: List[str],
    questions: List[str],
    limit_data: int,
    dry_run: bool,
    *,
    concurrency: int = 2,
    probe: bool = False,
) -> None:
    logger.info(
        f"[sweep] factories={factory_ids} questions={len(questions)} "
        f"limit_data={limit_data or 'all'} concurrency={concurrency}"
        + (" DRY-RUN" if dry_run else "")
        + (" PROBE" if probe else "")
    )

    from smartbi.config import get_pg_pool

    if not dry_run:
        pool = await get_pg_pool()
        if pool is None:
            logger.error("[sweep] could not obtain DB pool — aborting")
            return
    else:
        pool = None  # type: ignore[assignment]

    before = await _count_corpus_rows(pool, "chat_qa") if pool else -1
    logger.info(f"[census] before: chat_qa rows = {before}")

    # Load data per factory once (reused across all questions)
    factory_data: Dict[str, Tuple[Optional[int], List[Dict[str, Any]]]] = {}
    if not dry_run:
        for fid in factory_ids:
            upload_id, rows = await _load_factory_data(pool, fid, limit_data)
            factory_data[fid] = (upload_id, rows)
    else:
        for fid in factory_ids:
            factory_data[fid] = (None, [])

    if probe:
        # Probe mode: run exactly ONE (first factory, first question) for real
        # and print whether a non-empty corpus pair was produced.
        fid = factory_ids[0]
        q = questions[0]
        upload_id, rows = factory_data.get(fid, (None, []))
        logger.info(f"[probe] running ONE pair: factory={fid} q={q!r} data_rows={len(rows)}")
        ok = await _process_one(pool, fid, q, rows, upload_id, dry_run=False, probe=True)
        logger.info(f"[probe] result: {'OK — corpus pair captured' if ok else 'FAILED — empty answer'}")
        return

    # Build work list: factory × question
    work = [(fid, q) for fid in factory_ids for q in questions]
    logger.info(f"[sweep] total (factory × question) pairs: {len(work)}")

    attempted = succeeded = 0
    sem = asyncio.Semaphore(concurrency)

    async def _task(fid: str, q: str) -> None:
        nonlocal attempted, succeeded
        async with sem:
            upload_id, rows = factory_data.get(fid, (None, []))
            attempted += 1
            ok = await _process_one(pool, fid, q, rows, upload_id, dry_run)
            if ok:
                succeeded += 1

    tasks = [asyncio.create_task(_task(fid, q)) for fid, q in work]
    await asyncio.gather(*tasks, return_exceptions=True)

    after = await _count_corpus_rows(pool, "chat_qa") if pool else -1
    added = (after - before) if before >= 0 and after >= 0 else "?"
    logger.info(
        f"[census] after:  chat_qa rows = {after}  (added/refreshed ~{added})"
    )
    logger.info(
        f"[sweep] done: attempted={attempted} succeeded={succeeded}"
        + (" (dry-run)" if dry_run else "")
    )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=(
            "P1 cold-start: replay question bank against real factory data "
            "to fill the chat_qa distillation corpus."
        ),
    )
    p.add_argument(
        "--factories",
        default=",".join(DEFAULT_FACTORIES),
        help=f"Comma-separated factory IDs (default: {','.join(DEFAULT_FACTORIES)})",
    )
    p.add_argument(
        "--limit-data",
        type=int,
        default=2000,
        help="Max rows to load from dynamic_data per factory (0 = all; default 2000)",
    )
    p.add_argument(
        "--concurrency",
        type=int,
        default=2,
        help="Max concurrent InsightGenerator calls (default 2)",
    )
    p.add_argument(
        "--probe",
        action="store_true",
        help=(
            "Run ONE real LLM call (first factory × first question) and print "
            "whether a non-empty corpus pair was produced — without persisting. "
            "Use this to verify the pipeline produces output before a full sweep."
        ),
    )
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="List questions / data source without running LLM / persisting",
    )
    p.add_argument(
        "--list-questions",
        action="store_true",
        help="Print the full question bank and exit",
    )
    return p


def main() -> None:
    args = _build_parser().parse_args()

    if args.list_questions:
        for i, item in enumerate(QUESTION_BANK, 1):
            print(f"{i:2}. [{item['category']}] {item['q']}")
        return

    factory_ids = [f.strip() for f in args.factories.split(",") if f.strip()]
    asyncio.run(
        run_sweep(
            factory_ids,
            questions=get_question_bank(),
            limit_data=args.limit_data,
            dry_run=args.dry_run,
            concurrency=args.concurrency,
            probe=args.probe,
        )
    )


if __name__ == "__main__":
    main()
