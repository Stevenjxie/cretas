# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `D10D` — `review` — Owner: Codex coordinator — Base SHA: `ebbc9893320442db26049b0c4875978d165fa231` — 修复 Restaurant Agent Read Tool Gateway 在生产 Python 3.8 缺少 `asyncio.timeout_at` 导致的 ACTIVE 发布阻断；兼容性测试与独立只读复核已通过，下一步为 PR 合并、Python 精确发布与 OFF/ACTIVE 重验。

## Scope 锁地图

- `D10D`：`backend/python/smartbi/agent/runtime/gateway.py`、`backend/python/tests/agent_runtime/test_read_tool_gateway.py`、`docs/dispatch/ACTIVE.md`、`docs/dispatch/archive/2026-07-19-active-history.md`；验收命令：定向 pytest、Python 3.8 生产只读 Gateway probe、生产 OFF/ACTIVE smoke 与发布核验。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
