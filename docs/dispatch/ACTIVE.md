# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `D10E` — `in-progress` — Owner: Codex coordinator — Base SHA: `c030f82063cf56e43103c79edf0076c17472c075` — 修复 SmartBI V03 `EVIDENCE_RECORDED` 数据库约束函数中 `value` 参数与 `jsonb_each(...).value` 的 PL/pgSQL 歧义；新增 forward-only V06、真实 PostgreSQL payload 回归测试与 unexpected runtime 脱敏可观测性，随后执行生产迁移和 OFF/ACTIVE 重验。

## Scope 锁地图

- `D10E`：`backend/python/smartbi/database/migrations/V20261028_06__fix_agent_evidence_payload_constraint.sql`、`backend/python/smartbi/agent/runtime/bounded_runtime.py`、`backend/python/tests/agent_runtime/` 下目标回归测试、`docs/dispatch/ACTIVE.md`、`docs/dispatch/archive/2026-07-19-active-history.md`；验收命令：目标 pytest、真实 PostgreSQL migration/payload gate、生产 V06 迁移、OFF/ACTIVE smoke 与零 ERP 写入核验。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
