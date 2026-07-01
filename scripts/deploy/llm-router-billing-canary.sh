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
flags, safe = [], 0
with httpx.Client(timeout=25.0) as cli:
    for account, model in pairs:
        base, kenv = CFG[account]
        key = os.environ.get(kenv, "")
        if not key:
            flags.append(f"{account}/{model}: NO-KEY({kenv})"); continue
        try:
            resp = cli.post(f"{base}/chat/completions",
                            headers={"Authorization": f"Bearer {key}"},
                            json={"model": model, "messages": [{"role": "user", "content": "hi"}],
                                  "max_tokens": 1})
            sc, body = resp.status_code, resp.text
            if 200 <= sc < 300:
                safe += 1                                  # confirmed free quota
            elif sc == 403 and ("FreeTierOnly" in body or "AllocationQuota" in body):
                safe += 1                                  # exhausted-but-free (safe)
            elif sc == 402 and ("FREE_QUOTA_EXHAUSTED" in body or "Insufficient Balance" in body):
                safe += 1                                  # tencent free trial exhausted (safe)
            else:
                # 404 NOSKU = model retired; anything else = toggle flip / possible bill
                flags.append(f"{account}/{model}: HTTP {sc} :: {body[:90]}")
        except Exception as e:
            flags.append(f"{account}/{model}: ERR {type(e).__name__}")

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
