"""Spike: 归因类样板 (attribution template) — LLMCompiler-pattern, measured.

Question class: "哪家店拖后腿，是客流还是客单价?" (which store is dragging the
chain down — is it foot-traffic 客流 or average-ticket 客单价?).

This is a MEASUREMENT SPIKE, not a wired feature. It exists to lock the
architecture decision with real numbers (per 2026-07-08 handoff §1: "让数据锁死
架构决策再铺开"). It reuses the REAL production infra:

  - real SQL against smartbi_prod_db / DEMO_REST (444K+ POS bills, 19 stores)
  - real ``call_chain(SLOT.INSIGHTS)`` free-tier LLM (enable_thinking:false)
  - a STRICT reverse-whitelist grounding check (handoff audit change #3):
    every number the LLM emits must appear in the deterministic data payload,
    else it is flagged. (Note: the shipped FactReconciler is intentionally
    "宁漏不错" / detect-only; this spike measures the stricter contract the
    handoff asked for.)

LLMCompiler shape (NOT ReAct):
  question
    → plan   (rules-first, 0 LLM — closed domain, per feedback_rules_first)
    → execute (deterministic per-store 客流/客单价 SQL + decomposition, 0 LLM)
    → synthesize (ONE grounded call_chain(SLOT.INSIGHTS) restatement)
    → ground  (reverse-whitelist hard-check, 0 LLM)

Two answers are produced from the SAME deterministic payload so they can be
compared side by side (handoff: "纯模板 vs 加薄复述 观感"):
  A) pure_template   — deterministic string, exact, 0 LLM calls
  B) llm_restated    — thin LLM restatement, +1 LLM call, reverse-whitelist gated

Decomposition (exact identity, no LLM):
  Revenue = Bills × AvgTicket             (营收 = 客流 × 客单价)
  Benchmark (chain peer):  B̄ = 总客流/门店数 ,  Ā = 总营收/总客流
  For store i:
    ΔR_i        = R_i − R̄            where R̄ = B̄ × Ā = 门店平均营收
    客流效应_i   = (B_i − B̄) × Ā
    客单价效应_i = B̄ × (A_i − Ā)
    交互项_i     = (B_i − B̄)(A_i − Ā)
    ΔR_i        = 客流效应 + 客单价效应 + 交互项   (holds exactly)
  Laggard = store with the most-negative ΔR (among stores above a traffic
  floor, so a shuttered/near-zero store does not masquerade as "拖后腿").
  Primary cause = whichever of {客流效应, 客单价效应} is the larger negative.

Usage (on server 47, with the cretas-python systemd env sourced):
    python -m scripts.spike_attribution_llmcompiler --factory DEMO_REST --days 30
    python -m scripts.spike_attribution_llmcompiler --factory DEMO_REST --days 90 --no-llm
"""
from __future__ import annotations

import argparse
import asyncio
import json
import re
import time
from typing import Any, Dict, List, Optional, Tuple


# ── Step 1: plan (rules-first, 0 LLM) ────────────────────────────────────────

def plan_attribution(question: str) -> Dict[str, Any]:
    """Decide which deterministic dimensions to pull. Rules-only (closed domain).

    For the attribution class the plan is near-trivial (one dimension), which is
    itself the finding: a closed-domain plan needs NO LLM. If future questions
    escape the rules, a 1-sentence LLM classifier goes at THIS site only (and
    would add exactly 1 call).
    """
    ql = (question or "").lower()
    wants_traffic = any(k in ql for k in ("客流", "人流", "订单", "单量", "来客", "进店"))
    wants_ticket = any(k in ql for k in ("客单价", "单价", "人均", "消费"))
    wants_store = any(k in ql for k in ("哪家", "哪个店", "门店", "分店", "店", "拖后腿", "垫底", "最差"))
    return {
        "dimension": "store_traffic_ticket_attribution",
        "wants_traffic": wants_traffic,
        "wants_ticket": wants_ticket,
        "store_scoped": wants_store,
        "llm_calls": 0,
    }


# ── Step 2: execute (deterministic SQL + decomposition, 0 LLM) ────────────────

_SQL_PER_STORE = """
WITH anchor AS (
    SELECT MAX(t.date) AS end_date
      FROM fact_pos_transaction t
     WHERE t.factory_id = $1
)
SELECT s.store_id,
       s.name AS store_name,
       COUNT(DISTINCT t.id)                       AS bills,
       COALESCE(SUM(i.amount), 0)::float          AS revenue,
       MIN(t.date)                                AS window_start,
       MAX(t.date)                                AS window_end
  FROM fact_pos_transaction t
  JOIN dim_store s
    ON s.store_id = t.store_id AND s.factory_id = t.factory_id
  LEFT JOIN fact_pos_item i
    ON i.transaction_id = t.id
  CROSS JOIN anchor
 WHERE t.factory_id = $1
   AND anchor.end_date IS NOT NULL
   AND t.date >  anchor.end_date - ($2::int)
   AND t.date <= anchor.end_date
 GROUP BY s.store_id, s.name
HAVING COUNT(DISTINCT t.id) > 0
 ORDER BY revenue DESC
"""


async def fetch_store_rows(pool, factory_id: str, days: int) -> List[Dict[str, Any]]:
    # The POS tables carry FORCE ROW LEVEL SECURITY. set_config(..., is_local=true)
    # is TRANSACTION-scoped, so the GUC must be set and the query run inside the
    # SAME explicit transaction — otherwise asyncpg autocommits each statement in
    # its own txn and FORCE RLS returns 0 rows (feedback_asyncpg_rls_guc_must_be_in_transaction).
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            rows = await conn.fetch(_SQL_PER_STORE, factory_id, days)
    return [dict(r) for r in rows]


def _round(x: float, n: int = 1) -> float:
    return round(float(x), n)


def compute_attribution(rows: List[Dict[str, Any]], *, traffic_floor_frac: float = 0.10) -> Dict[str, Any]:
    """Deterministic attribution payload. Every number the answer may cite comes
    from here (this dict is the grounding whitelist)."""
    stores: List[Dict[str, Any]] = []
    total_bills = sum(int(r["bills"]) for r in rows)
    total_rev = sum(float(r["revenue"]) for r in rows)
    n = len(rows)
    if n == 0 or total_bills == 0:
        return {"stores": [], "n_stores": 0, "no_data": True}

    bench_bills = total_bills / n                 # B̄  门店平均客流
    chain_ticket = total_rev / total_bills        # Ā  全链客单价
    bench_rev = bench_bills * chain_ticket        # R̄  门店平均营收

    median_bills = sorted(int(r["bills"]) for r in rows)[n // 2]
    floor = median_bills * traffic_floor_frac

    for r in rows:
        b = int(r["bills"])
        rev = float(r["revenue"])
        ticket = rev / b if b else 0.0
        dR = rev - bench_rev
        traffic_effect = (b - bench_bills) * chain_ticket
        ticket_effect = bench_bills * (ticket - chain_ticket)
        interaction = (b - bench_bills) * (ticket - chain_ticket)
        stores.append({
            "store_name": r["store_name"],
            "bills": b,
            "revenue": _round(rev, 0),
            "avg_ticket": _round(ticket, 1),
            "delta_revenue": _round(dR, 0),
            "traffic_effect": _round(traffic_effect, 0),
            "ticket_effect": _round(ticket_effect, 0),
            "interaction": _round(interaction, 0),
            "below_traffic_floor": b < floor,
        })

    # Laggard = most-negative ΔR among stores above the traffic floor.
    eligible = [s for s in stores if not s["below_traffic_floor"]]
    laggard = min(eligible or stores, key=lambda s: s["delta_revenue"])
    # Primary cause: the larger NEGATIVE of the two effects for the laggard.
    tr, tk = laggard["traffic_effect"], laggard["ticket_effect"]
    if tr < 0 and tr <= tk:
        primary = "客流"
    elif tk < 0 and tk < tr:
        primary = "客单价"
    else:
        # Laggard is not below benchmark on either factor (edge) — report weaker.
        primary = "客流" if tr <= tk else "客单价"

    anomalies = [s["store_name"] for s in stores if s["below_traffic_floor"]]

    return {
        "no_data": False,
        "n_stores": n,
        "total_bills": total_bills,
        "total_revenue": _round(total_rev, 0),
        "bench_bills": _round(bench_bills, 0),        # 门店平均客流
        "chain_avg_ticket": _round(chain_ticket, 1),  # 全链客单价
        "bench_revenue": _round(bench_rev, 0),        # 门店平均营收
        "window_start": str(rows[0].get("window_start")),
        "window_end": str(rows[0].get("window_end")),
        "laggard": laggard,
        "primary_cause": primary,
        "anomalies": anomalies,
        "stores": stores,
    }


# ── Step 3a: pure template (0 LLM) ───────────────────────────────────────────

def render_pure_template(p: Dict[str, Any], days: int) -> str:
    if p.get("no_data"):
        return f"近 {days} 天无 POS 销售数据。"
    lg = p["laggard"]
    cause = p["primary_cause"]
    # Numbers here are drawn verbatim from the payload.
    lines = [
        f"近 {days} 天，{lg['store_name']} 拖后腿：营收 ¥{lg['revenue']:.0f}，"
        f"比门店平均 ¥{p['bench_revenue']:.0f} 少 ¥{-lg['delta_revenue']:.0f}。",
        f"拆解：客流效应 ¥{lg['traffic_effect']:.0f}（该店 {lg['bills']} 单 vs 平均 "
        f"{p['bench_bills']:.0f} 单），客单价效应 ¥{lg['ticket_effect']:.0f}"
        f"（该店客单价 ¥{lg['avg_ticket']:.1f} vs 全链 ¥{p['chain_avg_ticket']:.1f}）。",
        f"主因是{cause}。",
    ]
    if p["anomalies"]:
        lines.append(f"（另：{'、'.join(p['anomalies'])} 客流极低，可能是新店/歇业，未计入对比。）")
    return "".join(lines)


# ── Step 3b: LLM restatement (ONE call) + Step 4 grounding ────────────────────

_SYNTH_SYSTEM = (
    "你是餐饮连锁老板的经营助手。下面给你一份已经算好的门店归因数据（JSON）。"
    "请用一两句口语化的中文向老板复述结论，像面对面汇报。"
    "严格规则：只能使用 JSON 里已经出现的数字，绝对不能编造、推算或改写任何数字或门店名；"
    "不确定就不说。不要输出 JSON、不要列公式、不要加免责声明，只说结论和主因。"
)


def _extract_numbers(text: str) -> List[float]:
    """All numeric tokens in the answer (ignoring the day-window and pure years)."""
    out: List[float] = []
    for m in re.findall(r"\d[\d,]*\.?\d*", text or ""):
        try:
            out.append(float(m.replace(",", "")))
        except ValueError:
            pass
    return out


def _grounding_whitelist(p: Dict[str, Any], days: int) -> List[float]:
    """Every number the answer is allowed to mention."""
    wl = {float(days), float(p["n_stores"]), float(p["total_bills"]),
          float(p["total_revenue"]), float(p["bench_bills"]),
          float(p["chain_avg_ticket"]), float(p["bench_revenue"])}
    for s in p["stores"]:
        for k in ("bills", "revenue", "avg_ticket", "delta_revenue",
                  "traffic_effect", "ticket_effect", "interaction"):
            wl.add(abs(float(s[k])))
            wl.add(float(s[k]))
    return sorted(wl)


def check_grounding(answer: str, whitelist: List[float], *,
                    entity_names: Optional[List[str]] = None, tol: float = 0.02) -> Dict[str, Any]:
    """Reverse-whitelist: every number in the answer must be in the whitelist
    (within a small relative tolerance for rounding). Returns violations.

    Entity names are stripped FIRST — a store called 示范门店01 must not have its
    "01" misread as a data claim. This entity-aware step is exactly why the
    shipped FactReconciler stays "宁漏不错"; without it a strict reverse-whitelist
    over-flags on names that embed digits.
    """
    clean_text = answer or ""
    for name in sorted(entity_names or [], key=len, reverse=True):
        if name:
            clean_text = clean_text.replace(name, "〈店〉")
    nums = _extract_numbers(clean_text)
    violations: List[float] = []
    for x in nums:
        ok = any(abs(x - w) <= max(1.0, abs(w) * tol) for w in whitelist)
        if not ok:
            violations.append(x)
    return {
        "numbers_in_answer": nums,
        "grounded": len(nums) - len(violations),
        "violations": violations,
        "clean": len(violations) == 0,
    }


async def synthesize_llm(payload: Dict[str, Any], factory_id: str, days: int) -> Dict[str, Any]:
    from common.llm_router import call_chain, SLOT
    try:
        from common.llm_metrics import llm_caller_context
        ctx = llm_caller_context("spike_attribution", factory_id=factory_id)
    except Exception:
        import contextlib
        ctx = contextlib.nullcontext()

    # Trim payload to the laggard + benchmark so the prompt is compact and the
    # model cannot wander into the full 19-store table.
    lg = payload["laggard"]
    compact = {
        "统计天数": days,
        "拖后腿门店": lg["store_name"],
        "该店营收": lg["revenue"],
        "门店平均营收": payload["bench_revenue"],
        "营收差额": lg["delta_revenue"],
        "该店客流单数": lg["bills"],
        "门店平均客流单数": payload["bench_bills"],
        "客流效应": lg["traffic_effect"],
        "该店客单价": lg["avg_ticket"],
        "全链客单价": payload["chain_avg_ticket"],
        "客单价效应": lg["ticket_effect"],
        "主因": payload["primary_cause"],
    }
    prompt_user = "门店归因数据：\n" + json.dumps(compact, ensure_ascii=False, indent=2)
    body = {
        "messages": [
            {"role": "system", "content": _SYNTH_SYSTEM},
            {"role": "user", "content": prompt_user},
        ],
        "temperature": 0.3,
        "max_tokens": 400,
    }
    t0 = time.monotonic()
    with ctx:
        resp = await call_chain(SLOT.INSIGHTS, body, timeout=60.0)
    elapsed_ms = int((time.monotonic() - t0) * 1000)
    answer = ""
    tokens = None
    try:
        answer = (resp["choices"][0]["message"]["content"] or "").strip()
        tokens = (resp.get("usage") or {}).get("total_tokens")
    except Exception:
        answer = json.dumps(resp, ensure_ascii=False)[:400]
    return {"answer": answer, "elapsed_ms": elapsed_ms, "tokens": tokens}


# ── Orchestration + measurement ──────────────────────────────────────────────

async def run(factory_id: str, days: int, use_llm: bool) -> Dict[str, Any]:
    from smartbi.config import get_pg_pool

    question = "哪家店拖后腿，是客流还是客单价？"
    result: Dict[str, Any] = {"factory_id": factory_id, "days": days, "question": question}

    t_plan0 = time.monotonic()
    plan = plan_attribution(question)
    result["plan"] = plan
    result["plan_ms"] = int((time.monotonic() - t_plan0) * 1000)

    pool = await get_pg_pool()
    if pool is None:
        result["error"] = "db pool unavailable"
        return result

    t_exec0 = time.monotonic()
    rows = await fetch_store_rows(pool, factory_id, days)
    payload = compute_attribution(rows)
    exec_ms = int((time.monotonic() - t_exec0) * 1000)
    result["execute_ms"] = exec_ms
    result["payload"] = payload

    if payload.get("no_data"):
        result["pure_template"] = {"answer": render_pure_template(payload, days),
                                   "elapsed_ms": result["plan_ms"] + exec_ms, "llm_calls": 0}
        return result

    # A) pure template — plan + execute + render, 0 LLM
    pure = render_pure_template(payload, days)
    result["pure_template"] = {
        "answer": pure,
        "elapsed_ms": result["plan_ms"] + exec_ms,
        "llm_calls": 0,
    }

    # B) LLM restated — plan + execute + ONE synthesis + grounding
    if use_llm:
        wl = _grounding_whitelist(payload, days)
        entity_names = [s["store_name"] for s in payload["stores"]]
        synth = await synthesize_llm(payload, factory_id, days)
        grounding = check_grounding(synth["answer"], wl, entity_names=entity_names)
        result["llm_restated"] = {
            "answer": synth["answer"],
            "elapsed_ms": result["plan_ms"] + exec_ms + synth["elapsed_ms"],
            "synth_ms": synth["elapsed_ms"],
            "tokens": synth["tokens"],
            "llm_calls": 1,  # plan is rules-based → synthesis is the only call
            "grounding": grounding,
        }

    return result


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--factory", default="DEMO_REST")
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--no-llm", action="store_true", help="deterministic only, skip the LLM restatement")
    ap.add_argument("--json-only", action="store_true", help="emit only the JSON blob")
    args = ap.parse_args()

    out = asyncio.run(run(args.factory, args.days, use_llm=not args.no_llm))

    if args.json_only:
        print(json.dumps(out, ensure_ascii=False, default=str))
        return

    print("=" * 72)
    print(f"归因样板 spike — factory={out['factory_id']} days={out['days']}")
    print(f"问题: {out['question']}")
    print("=" * 72)
    p = out.get("payload", {})
    if p.get("no_data"):
        print("无 POS 数据")
        return
    lg = p["laggard"]
    print(f"[数据] {p['n_stores']} 店 | 窗口 {p['window_start']}~{p['window_end']} | "
          f"总客流 {p['total_bills']} 单 | 全链客单价 ¥{p['chain_avg_ticket']}")
    print(f"[拖后腿] {lg['store_name']}: 营收¥{lg['revenue']:.0f} (Δ¥{lg['delta_revenue']:.0f}) "
          f"| 客流{lg['bills']}单 客单价¥{lg['avg_ticket']} | 主因={p['primary_cause']}")
    print("-" * 72)
    pt = out["pure_template"]
    print(f"A) 纯模板  [{pt['llm_calls']} LLM · {pt['elapsed_ms']}ms]")
    print(f"   {pt['answer']}")
    if "llm_restated" in out:
        lr = out["llm_restated"]
        g = lr["grounding"]
        print("-" * 72)
        print(f"B) 加薄复述 [{lr['llm_calls']} LLM · {lr['elapsed_ms']}ms · synth {lr['synth_ms']}ms · "
              f"{lr['tokens']} tok · grounding {'✓ clean' if g['clean'] else '✗ ' + str(g['violations'])}]")
        print(f"   {lr['answer']}")
    print("=" * 72)
    print("\n--- JSON ---")
    print(json.dumps(out, ensure_ascii=False, default=str))


if __name__ == "__main__":
    main()
