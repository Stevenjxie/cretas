#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# LLM router → 规则缺口热力图 (rules-gap heatmap)  — router↔飞轮闭环 部件 1
# Spec: docs/superpowers/specs/2026-07-01-smart-llm-router-spec.md (§router↔flywheel)
#
# READ-ONLY. Turns the router's usage telemetry into the flywheel's rule-writing
# priorities — the one signal the flywheel currently does NOT consume:
#   Q1  which callers are LLM-heavy      = 规则缺口在哪 (rules not graduated there)
#   Q2  which candidates are promotable  = 毕业就绪 → promote 会直接砍对应 caller 的 LLM 量
#   Q3  which business_types near LoRA    = 自训垂直模型就绪度 (≥1000-2000/业态)
#   Q4  which router models are teachers = router 喂了多少语料
#
# Runs ON server 47 (DB localhost). No writes. Intended cadence: weekly (cron below).
# Reuses the Layer-5 attribution fix (aliyun_c no longer mislabeled) for clean caller rows.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail
eval "$(systemctl show cretas-python -p Environment | tr ' ' '\n' \
        | grep -E '^POSTGRES_(PASSWORD|USER|HOST)=' \
        | sed -E "s/^([^=]+)=(.*)$/export \1='\2'/")"
export PGPASSWORD="${POSTGRES_PASSWORD:-}"
PSQL=(psql -h "${POSTGRES_HOST:-localhost}" -U "${POSTGRES_USER:-smartbi_user}" -d smartbi_prod_db -X)

echo "# LLM router → 规则缺口热力图   $(date '+%Y-%m-%d %H:%M')"
echo

"${PSQL[@]}" <<'SQL'
\echo '## Q1 — LLM 需求 by caller (成功调用量 = 规则缺口信号；量大且趋势不降 = 该写规则)'
SELECT coalesce(caller,'(untagged)')                                              AS caller,
       count(*) FILTER (WHERE status_code BETWEEN 200 AND 299 AND ts>now()-interval '30 days') AS ok_30d,
       count(*) FILTER (WHERE status_code BETWEEN 200 AND 299 AND ts>now()-interval  '7 days') AS ok_7d,
       count(*) FILTER (WHERE status_code BETWEEN 200 AND 299
                          AND ts>now()-interval '14 days' AND ts<=now()-interval '7 days')      AS ok_prev7d,
       sum(total_tokens) FILTER (WHERE ts>now()-interval '30 days')               AS tok_30d
FROM smart_bi_llm_usage
WHERE ts > now()-interval '30 days'
GROUP BY 1 ORDER BY ok_30d DESC NULLS LAST LIMIT 15;

\echo ''
\echo '## Q2 — 规则候选毕业就绪 (ready = confidence>=0.9 AND occurrences>=3；毕业闸另需 factories>=2 + industries>=2 + 人工 promote --apply)'
SELECT coalesce(learning_type,'?')  AS learning_type,
       coalesce(business_type,'?')  AS business_type,
       count(*)                                                        AS candidates,
       count(*) FILTER (WHERE confidence>=0.9 AND occurrences>=3)      AS ready,
       count(DISTINCT factory_id)                                      AS factories
FROM smart_bi_learning_candidates
GROUP BY 1,2 ORDER BY ready DESC, candidates DESC LIMIT 12;

\echo ''
\echo '## Q3 — 蒸馏语料 LoRA 就绪 by business_type (垂直自训模型阈值 ~1000-2000/业态)'
SELECT coalesce(business_type,'?')  AS business_type,
       count(*)                     AS samples,
       count(DISTINCT source)       AS sources,
       count(DISTINCT task_type)    AS task_types,
       min(created_at)::date        AS since,
       CASE WHEN count(*)>=1000 THEN 'LoRA-READY(>=1000)'
            WHEN count(*)>=500  THEN 'halfway(>=500)'
            ELSE 'accumulating' END AS lora_status
FROM smart_bi_distillation_samples
GROUP BY 1 ORDER BY samples DESC LIMIT 10;

\echo ''
\echo '## Q4 — 语料 teacher = 哪些 router 模型喂的 (router 是唯一 teacher 咽喉)'
SELECT coalesce(teacher_model,'?')  AS teacher_model, count(*) AS samples
FROM smart_bi_distillation_samples GROUP BY 1 ORDER BY 2 DESC LIMIT 10;
SQL

cat <<'LEGEND'

## 读法 (how to act)
- Q1 caller LLM 量高 + Q2 该域有 ready 候选  → **promote --apply (人工 review)** → 直接砍该 caller 的 LLM 量
- Q1 量高 + Q2 无候选                        → **该 caller 缺捕获点** → 在其 call_chain 后加 distillation/candidate capture
- Q1 '(untagged)' 量大                       → **缺 llm_caller_context** → 给该 LLM 路径补 caller tag (归因黑洞)
- Q3 某 business_type >=1000                 → **垂直自训模型就绪** → export_distillation_dataset + LoRA (自训模型仍 trigger-gated, 上线接成 rules-first 后一层)
- Q4 teacher 分布                            → 确认 router 选的强模型在喂语料 (弱模型/空输出已被 Layer-4 校验挡掉, 不进语料)

纪律: 本报告只读, 不自动改任何规则/注册表; 毕业与重排都保留 human-in-loop。
LEGEND

# ── crontab (weekly Mon 09:30, after canary) ──
#   30 9 * * 1 bash /www/wwwroot/cretas/code/scripts/deploy/llm-router-rules-gap-heatmap.sh \
#     >> /www/wwwroot/cretas/logs/llm-rules-gap-heatmap.log 2>&1
