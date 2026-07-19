# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `CRETAS-SMARTBI-STAGED-MIGRATION-TARGET-20260719`
  - 状态：`review`
  - Owner：Codex (`/root`)
  - Base SHA：`054fec7626ec538055f6b7698c448ab1afd3301e`
  - 目标：让标准 Python 发布入口支持受校验的 migration target 与 migration-only 阶段，以安全执行 D9/D10 code-first、expand、contract 三阶段发布；拒绝不存在/ahead target 和 `--env all` 半迁移，禁止一次性越过 V05 兼容窗口。
  - 验收：Shell 语法；CLI/help/invalid/nonexistent target、target+all、migration-only、target-forwarding 回归；真实 PostgreSQL runner target/nonexistent/ahead 门禁；依赖缓存回归；`git diff --check`；独立只读终审。

## Scope 锁地图

- `CRETAS-SMARTBI-STAGED-MIGRATION-TARGET-20260719`：`scripts/deploy/deploy-smartbi-python.sh`、`scripts/migrations/apply-smartbi-migrations.sh`、`scripts/migrations/test-runner.sh`、`scripts/tests/test-smartbi-python-migration-target.sh`、本任务 dispatch 记录。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
