#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# LLM router 数据驱动 chain 重排顾问 (re-rank advisor)  — router↔飞轮闭环 部件 2
# Spec: docs/superpowers/specs/2026-07-01-smart-llm-router-spec.md (§router↔flywheel)
#
# READ-ONLY / SUGGEST-ONLY. Joins live usage health (smart_bi_llm_usage) with the
# deployed SLOT_MODELS chains and flags where the STATIC benchmark-snapshot order
# disagrees with MEASURED reality:
#   · a HEAD-position (account,model) with low success% or high p50 latency → demote
#   · a fast+reliable model buried DEEP in the chain                        → promote
# Emits a suggested reorder for a HUMAN to review. NEVER auto-applies, NEVER touches
# _SAFE_MODELS (the billing-safety registry) — only chain ORDER is ever a suggestion.
#
# Runs ON server 47 (DB localhost + deployed module). Weekly cron (bottom).
# NOTE: the per-account dimension is only clean AFTER the 2026-07-01 attribution fix —
# give it ~1 week of data before trusting per-account rows; model-level signal is live now.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail
eval "$(systemctl show cretas-python -p Environment | tr ' ' '\n' \
        | grep -E '^POSTGRES_(PASSWORD|USER|HOST)=' \
        | sed -E "s/^([^=]+)=(.*)$/export \1='\2'/")"
export PGPASSWORD="${POSTGRES_PASSWORD:-}"
cd /www/wwwroot/cretas/code/backend/python || exit 2
# shellcheck disable=SC1091
source venv38/bin/activate

PGHOST="${POSTGRES_HOST:-localhost}" PGUSER="${POSTGRES_USER:-smartbi_user}" python - <<'PY'
import os, sys, types, subprocess, datetime

# ── load deployed chains + gate (stub the http client) ──
_s = types.ModuleType("common.llm_client"); _s.get_llm_http_client = lambda: None
sys.modules["common.llm_client"] = _s
import common.llm_router as r

# ── pull per-(provider,model) health from usage (30d) ──
SQL = """
SELECT provider, model,
  count(*) FILTER (WHERE ts>now()-interval '30 days')                                                    AS calls30,
  round(100.0*count(*) FILTER (WHERE status_code BETWEEN 200 AND 299 AND ts>now()-interval '30 days')
        / NULLIF(count(*) FILTER (WHERE ts>now()-interval '30 days'),0),1)                               AS okpct,
  coalesce(round(percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms)
        FILTER (WHERE status_code BETWEEN 200 AND 299 AND latency_ms IS NOT NULL
                 AND ts>now()-interval '30 days')),0)                                                     AS p50
FROM smart_bi_llm_usage WHERE ts>now()-interval '30 days'
GROUP BY 1,2;
"""
out = subprocess.run(["psql","-h",os.environ["PGHOST"],"-U",os.environ["PGUSER"],
                      "-d","smartbi_prod_db","-X","-t","-A","-F","|","-c",SQL],
                     capture_output=True, text=True)
health = {}   # (provider, model) -> (calls, ok%, p50ms)
for line in out.stdout.strip().splitlines():
    p = line.split("|")
    if len(p) == 5:
        health[(p[0], p[1])] = (int(p[2] or 0), float(p[3] or 0), int(float(p[4] or 0)))

def h(account, model):
    return health.get((account, model))   # provider==account after Layer-5 fix

DEMOTE_OK, DEMOTE_P50, PROMOTE_OK, PROMOTE_P50 = 90.0, 8000, 98.0, 2500
today = datetime.date.today()
print(f"# LLM router 重排顾问 (data-driven)   {today}   (只读/建议, 不动 _SAFE_MODELS)")
suggestions = 0
for slot, chain in r.SLOT_MODELS.items():
    live = [(a, m) for (a, m) in chain if r._refuse_reason(a, m, today) is None]
    rows, flags = [], []
    for idx, (a, m) in enumerate(live):
        st = h(a, m)
        tag = ""
        if st:
            calls, ok, p50 = st
            if idx < 3 and calls >= 20 and (ok < DEMOTE_OK or p50 > DEMOTE_P50):
                tag = "⚠️ demote (头部但慢/错)"; flags.append((a, m, tag))
            elif idx >= 5 and calls >= 20 and ok >= PROMOTE_OK and 0 < p50 <= PROMOTE_P50:
                tag = "⬆️ promote (靠后但快/稳)"; flags.append((a, m, tag))
            rows.append(f"    {idx:2} {a}/{m:32} calls={calls:<4} ok={ok:>5}% p50={p50}ms {tag}")
        else:
            rows.append(f"    {idx:2} {a}/{m:32} (无近期数据)")
    if flags:
        suggestions += len(flags)
        print(f"\n## {slot.value}  — {len(flags)} 建议")
        print("\n".join(rows))
if suggestions == 0:
    print("\n✅ 无重排建议 — 现有 chain 顺序与实测 health 一致 (或数据不足)。")
print("\n读法: demote/promote 仅是建议; 由人 review 后手动调 SLOT_MODELS 顺序 (绝不动 _SAFE_MODELS)。")
print("数据新鲜度: per-account 维度需 2026-07-01 归因修复后 ~1 周数据才可信。")
PY

# ── crontab (weekly Mon 10:00, after canary+heatmap) ──
#   0 10 * * 1 bash /www/wwwroot/cretas/code/scripts/deploy/llm-router-rerank-advisor.sh \
#     >> /www/wwwroot/cretas/logs/llm-rerank-advisor.log 2>&1
