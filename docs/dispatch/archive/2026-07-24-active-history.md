# 2026-07-24 调度归档

## `AUTO-SOP-RAG-SYNC-20260724` — `merged`

- Owner: `/root`
- Base SHA: `19bed09f8f78474c78ef84164bac44621535d458`
- 合入：PR #1732 / `cfe2b63cd7688da2e3581081eb16e86e3674ff1e` 更新 SOP、RAG 来源和系统回答约束；PR #1733 / `99f1d2301a894b308f0daf54038d25ff231016c4` 将关键 BOM/Workflow 发布门禁改为保留 RAG 来源的代码级确定性答案。
- 发布：从 clean exact `origin/main` 运行 `scripts/deploy/deploy-smartbi-python.sh --env prod`；生产 migration `0 pending / 120 already applied`，依赖缓存命中，import smoke 和 `8083/health` 通过。
- 验收：`python -m pytest tests/test_food_kb_manual_chat_sop_contract.py -q` 为 `10 passed`；3 种生产真实问法均为 HTTP 200、命中完整 `Workflow 草稿 → BOM 激活 → Workflow 发布启用` 顺序、ACTIVE BOM 门禁与双状态验收，禁词计数 0，每次返回 8 个当前 SOP 来源。
- 页面与知识库：`/aiassist.html` SHA-256 为 `a7000b8dd425b355b0b2dafb4775e6cce9bc9c066b9d4fa1bd5058053b837524`；`/lsmsop/` SHA-256 为 `9d9914aab5a76c07413a8d4e140f4e860c9ce7f4632a835d086e37bb7bf501b8`；RAG 为 36 个正式块且无 `.NEW` 临时块。
- 边界：生产业务写入为 0。测试 Python 未重启，原因是服务器缺少 `/www/wwwroot/cretas/.env.test`；测试库已由标准 runner 应用 34 个历史待执行 migration，需另项治理测试环境配置漂移。
- Scope 锁已释放。
