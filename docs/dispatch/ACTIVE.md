# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-16

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-16-active-history.md](archive/2026-07-16-active-history.md)，此前完整历史见 [2026-07-14-active-history.md](archive/2026-07-14-active-history.md)。

## 在飞任务

| ID | Base SHA | Scope 锁 | Owner | Mode | Worktree/PR | Status | Blocker | Next action |
|---|---|---|---|---|---|---|---|---|
| MAT16-CONTRACT-20260716 | `9e9331607` | `backend/java/cretas-api/**/RawMaterialType*`, `backend/java/cretas-api/**/MaterialCodeSegment*`, material-code Flyway/tests, `MaterialBatchServiceImpl` 与 `AIController` 的物料快速创建窄入口，`web-admin/src/views/warehouse/material-types/**`, RN material-type screens/APIs | `/root/material_contract_impl` | code | `C:/Users/Steve/cretas-material-contract` | in-progress | 无 | 建立统一 L1-L3/16位编码后端契约并收口 Web/RN/AI/快速创建路径；运行定向后端、前端测试 |
| SETTLE-CONTRACT-20260716 | `9e9331607` | `ProductionPlan*` settlement DTO/controller/service/tests, `web-admin/src/views/production/plans/**` | `/root/settlement_contract_impl` | code | `C:/Users/Steve/cretas-settlement-contract-v2` | in-progress | 无 | 实现服务端事实派生的最小核对结单，修正式报工状态与投入/产出单位来源；运行定向测试 |

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
