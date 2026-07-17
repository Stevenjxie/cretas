# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-17

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-17-active-history.md](archive/2026-07-17-active-history.md)，此前完整历史见 [2026-07-16-active-history.md](archive/2026-07-16-active-history.md)。

## 在飞任务

- 无。

## Scope 锁地图

- 无。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
