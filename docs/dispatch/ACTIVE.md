# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-18

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)，此前历史见 [2026-07-17-active-history.md](archive/2026-07-17-active-history.md)。

## 在飞任务

- `CRETAS-RELEASE-GLOBAL-OUTCOME-20260718` — `review`
  - Base SHA：`6ee89e8b4399f53a61bae27c3354968d5b654a99`
  - Owner：当前协调者
  - 目标：修正统一发布入口对子部署 no-op/deployed 与 fallback build 的结构化回执，并补齐 Web 四方哈希验收。
  - 验收：`bash -n`、目标 shell 契约测试、现有 manifest/deploy 加速测试、编码检查、`git diff --check`。

## Scope 锁地图

- `CRETAS-RELEASE-GLOBAL-OUTCOME-20260718`
  - `scripts/deploy/release-cretas.sh`
  - `scripts/deploy/deploy-backend.sh`
  - `scripts/deploy/deploy-web-admin.sh`
  - `scripts/deploy/deploy-cretas-parallel.sh`（仅复核环境透传，不复制子部署逻辑）
  - `scripts/tests/test-release-cretas.sh`
  - 相关发布回执/加速契约测试
  - `.agents/skills/deploy-backend/SKILL.md`
  - `docs/dispatch/ACTIVE.md` 与当日归档

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
