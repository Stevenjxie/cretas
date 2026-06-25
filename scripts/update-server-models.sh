#!/bin/bash
# 更新服务器上的模型配置到免费额度版本
# 2026-03-05
#
# ⚠️  COST-SAFETY NOTICE (2026-06-11 billing incident):
#   All model codes in this script MUST be on the respective account's free allowlist.
#   See: reference_dashscope_free_model_allowlist in project memory / ACTIVE.md
#   AK rotation: 2026-06-11 (AccessKey IDs 见 .claude/rules/aliyun-credentials.md, 不入 git)
#   FREE replacement for qwen3-max-*/qwen-max/qwen-plus: qwen3.7-max-2026-06-08
#   FREE VL replacement: qwen3-vl-plus-2025-12-19

SERVER="root@47.100.235.168"

ssh $SERVER 'bash -s' << 'REMOTE_SCRIPT'

echo "=== 更新 cretas-backend.service ==="
sed -i \
  -e "s/--cretas.ai.dashscope.model=qwen3.5-plus/--cretas.ai.dashscope.model=qwen3.7-max-2026-06-08/" \
  -e "s/--cretas.ai.dashscope.correction-model=qwen3.5-35b-a3b/--cretas.ai.dashscope.correction-model=qwen3.5-27b/" \
  -e "s/--cretas.ai.dashscope.vision-model=qwen3.5-plus/--cretas.ai.dashscope.vision-model=qwen3-vl-plus-2025-12-19/" \
  -e "s/--cretas.ai.arena-rl.llm.model=qwen3.5-35b-a3b/--cretas.ai.arena-rl.llm.model=qwen3.5-27b/" \
  /etc/systemd/system/cretas-backend.service

echo "=== 更新 cretas-python.service ==="
sed -i \
  -e "s/LLM_MODEL=qwen3.5-plus-2026-02-15/LLM_MODEL=qwen3.7-max-2026-06-08/" \
  -e "s/LLM_MODEL=qwen3-max-2026-01-23/LLM_MODEL=qwen3.7-max-2026-06-08/" \
  -e "s/LLM_VL_MODEL=qwen3.5-plus/LLM_VL_MODEL=qwen3-vl-plus-2025-12-19/" \
  /etc/systemd/system/cretas-python.service

echo "=== systemctl daemon-reload ==="
systemctl daemon-reload

echo "=== 验证 cretas-backend.service ==="
grep -oP '(model|correction-model|vision-model)=[^ ]+' /etc/systemd/system/cretas-backend.service || true

echo "=== 验证 cretas-python.service ==="
grep 'LLM_MODEL\|LLM_VL_MODEL' /etc/systemd/system/cretas-python.service || true

echo "=== 重启 Python 服务 ==="
systemctl restart cretas-python
sleep 3

echo "=== 重启 Java 后端 ==="
systemctl restart cretas-backend
sleep 10

echo "=== 检查服务状态 ==="
for p in 8083 10010; do
  if ss -tlnp | grep -q ":${p} "; then
    echo "port ${p}: OK"
  else
    echo "port ${p}: NOT LISTENING"
  fi
done

echo "=== Done ==="
REMOTE_SCRIPT
