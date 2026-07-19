# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `CR-HTTP-STATUS-MAP-01-20260719` — `review`
  - Base SHA: `8b9cd5365b01feedbee4ee4f93d6114aabe031f1`
  - Owner: Codex `/root`
  - Scope: `backend/java/cretas-api/src/main/java/com/cretas/aims/exception/GlobalExceptionHandler.java`、`backend/java/cretas-api/src/test/java/com/cretas/aims/exception/GlobalExceptionHandlerResponseStatusTest.java`、`docs/dispatch/**`
  - 验收命令: `mvn -q "-Dtest=GlobalExceptionHandlerResponseStatusTest,RestaurantAgentRunControllerTest,RestaurantAgentRunServiceTest,AgentOpsControllerTest,AgentOpsServiceTest" test`
  - 下一动作: 32 项目标测试与独立只读终审通过；提交 PR，重建可信制品并重新验证 Restaurant Agent OFF 合同。

## Scope 锁地图

- `CR-HTTP-STATUS-MAP-01-20260719` 锁定上述全局异常映射、专用测试与 dispatch 台账；不修改 Agent runtime、迁移、ERP Controller 或生产配置。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
