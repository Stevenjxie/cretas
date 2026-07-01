#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# LLM router billing-safety CANARY  (spec 2026-07-01-smart-llm-router-spec.md)
#
# The router only calls `已开启`(ON) models, which 403 on exhaustion and never bill.
# But the ON toggle lives in the Aliyun console and can be flipped OFF by anyone, or a
# model can be retired (404 NOSKU) — the static _SAFE_MODELS registry can't see either.
# This canary re-probes EVERY registry entry with the real keys and FLAGS anything that
# is NOT a clean billing-safe response (200 has-quota / 403 FreeTierOnly / 402 free-
# exhausted). It also warns when the registry snapshot is stale (>21d → re-audit console).
#
# Runs ON server 47 (has the keys + venv + deployed module). Exit 1 on any flag so a
# cron wrapper can alert. Intended cadence: weekly  (crontab example at bottom).
#
# Manual run:  bash /www/wwwroot/cretas/code/scripts/deploy/llm-router-billing-canary.sh
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail
PYDIR=/www/wwwroot/cretas/code/backend/python
cd "$PYDIR" || { echo "[canary] no $PYDIR"; exit 2; }
# shellcheck disable=SC1091
source venv38/bin/activate
# Load the running service's LLM keys (never hardcode — they rotate).
eval "$(systemctl show cretas-python -p Environment | sed 's/^Environment=//' \
        | tr ' ' '\n' | grep -E '^LLM_(ALIYUN|TENCENT|ZHIPU)' \
        | sed -E "s/^([^=]+)=(.*)$/export \1='\2'/")"

python - <<'PY'
import sys, types, os, datetime, httpx
# stub the http-client import so we can load the module standalone
_s = types.ModuleType("common.llm_client"); _s.get_llm_http_client = lambda: None
sys.modules["common.llm_client"] = _s
import common.llm_router as r

pairs = list(r._SAFE_MODELS.keys())
CFG = {
    "aliyun_a": ("https://dashscope.aliyuncs.com/compatible-mode/v1", "LLM_ALIYUN_A_API_KEY"),
    "aliyun_b": ("https://dashscope.aliyuncs.com/compatible-mode/v1", "LLM_ALIYUN_B_API_KEY"),
    "aliyun_c": ("https://dashscope.aliyuncs.com/compatible-mode/v1", "LLM_ALIYUN_C_API_KEY"),
    "tencent":  ("https://tokenhub.tencentmaas.com/v1", "LLM_TENCENT_API_KEY"),
    "zhipu":    ("https://open.bigmodel.cn/api/paas/v4", "LLM_ZHIPU_API_KEY"),
}
def _classify(sc, body):
    """None = billing-safe (200 has-quota / 403 FreeTierOnly / 402 free-exhausted);
    else a flag string. 404 NOSKU = retired; other = toggle flip / possible bill."""
    if 200 <= sc < 300:
        return None
    if sc == 403 and ("FreeTierOnly" in body or "AllocationQuota" in body):
        return None
    if sc == 402 and ("FREE_QUOTA_EXHAUSTED" in body or "Insufficient Balance" in body):
        return None
    return f"HTTP {sc} :: {body[:90]}"

flags, safe = [], 0
with httpx.Client(timeout=30.0) as cli:
    for account, model in pairs:
        base, kenv = CFG[account]
        key = os.environ.get(kenv, "")
        if not key:
            flags.append(f"{account}/{model}: NO-KEY({kenv})"); continue
        payload = {"model": model, "messages": [{"role": "user", "content": "hi"}], "max_tokens": 1}
        hdr = {"Authorization": f"Bearer {key}"}
        result = "ERR unknown"
        # Retry once on transient timeout/network error so a blip doesn't cry wolf —
        # only a PERSISTENT bad status (real toggle flip / retired model) should flag.
        for attempt in (1, 2):
            try:
                resp = cli.post(f"{base}/chat/completions", headers=hdr, json=payload)
                result = _classify(resp.status_code, resp.text)
                break                                      # got a real HTTP status → trust it
            except Exception as e:
                result = f"ERR {type(e).__name__} (x{attempt})"
                # transient (timeout/connection) → retry once; a 2nd failure flags
        if result is None:
            safe += 1
        else:
            flags.append(f"{account}/{model}: {result}")

age = (datetime.date.today() - r._REGISTRY_AUDIT_DATE).days
print(f"[canary {datetime.date.today()}] {len(pairs)} entries · {safe} billing-safe · "
      f"registry age {age}d (audit {r._REGISTRY_AUDIT_DATE})")
rc = 0
if age > r._REGISTRY_MAX_AGE_DAYS:
    print(f"⚠️  REGISTRY STALE ({age}d > {r._REGISTRY_MAX_AGE_DAYS}d) — re-scrape console + refresh _SAFE_MODELS")
    rc = 3
if flags:
    print(f"⚠️  {len(flags)} FLAGGED (toggle flip / retired / possible bill — investigate):")
    for f in flags:
        print("   " + f)
    rc = 1
if rc == 0:
    print("✅ canary PASS — every registry entry still billing-safe, registry fresh")
print("👉 also eyeball the Aliyun 账单 (DashScope/百炼) — client-side logic can't see a "
      "console-level paid charge; that bill = the true backstop.")
sys.exit(rc)
PY
# ── crontab (install on server 47, weekly Mon 09:00, log + non-zero = review) ──
#   0 9 * * 1 bash /www/wwwroot/cretas/code/scripts/deploy/llm-router-billing-canary.sh \
#     >> /www/wwwroot/cretas/logs/llm-canary.log 2>&1 || echo "LLM canary FLAGGED $(date)" \
#     >> /www/wwwroot/cretas/logs/llm-canary-alerts.log
