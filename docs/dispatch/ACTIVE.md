# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-17

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-17-active-history.md](archive/2026-07-17-active-history.md)，此前完整历史见 [2026-07-16-active-history.md](archive/2026-07-16-active-history.md)。

## 在飞任务

| ID | 任务 | Base SHA | model | effort | orchestration | 分支 | scope 锁 | Owner | 状态 | 验收命令 | PR | 阻塞 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CFX-20260717-BE | 统一数量/计价单位契约，修复付款方式、供应商历史及 AI 确定性门禁 | `2233797dc8d6417885b628e45eab4a687f7ce0ee` | Codex | high | worker | `codex/f006-contract-backend-20260717` | `backend/java/cretas-api/**` | coordinator | review | 相关 Maven 目标测试；JPA 查询变更需真实 JPA Context；`git diff --check` | PR #1413 / `9107b4fb5` | 主源码编译通过；JPA Context 通过；失败组修正后 11/11 通过 |
| CFX-20260717-WEB | 修复 BOM/采购/AI/成本/枚举及长表格 Web 交互契约 | `2233797dc8d6417885b628e45eab4a687f7ce0ee` | Codex | high | worker | `codex/f006-contract-web-20260717` | `web-admin/**` | coordinator | review | 目标 Vitest/类型检查/构建；`git diff --check` | PR #1414 / `0f9d6ef4b` | 目标测试、类型检查及生产构建通过 |

## Scope 锁地图

| 文件 / 目录 | 锁定 task | 预计解锁 |
|---|---|---|
| `backend/java/cretas-api/**` | CFX-20260717-BE | PR 合并或任务归档后 |
| `web-admin/**` | CFX-20260717-WEB | PR 合并或任务归档后 |

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
