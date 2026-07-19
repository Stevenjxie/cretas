# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `REDUNDANCY-SH01-DATA-CLEAR-20260719`
  - 状态：`review`
  - Owner：Codex (`/root`)
  - Base SHA：`0bd8d80cada4ff00fc1b06fd5e4f5aeabfc5f7d0`
  - Scope：`backend/java/cretas-api/src/main/resources/db/flyway/V20261028_81__clear_frozen_legacy_shipment_test_data.sql`、对应 migration contract test、`docs/architecture/2026-07-19-redundancy-cleanup-wave-1.md` 与本台账/归档。
  - 生产冻结证据：SH-01A 已部署到 blue/10010；V80 已应用；旧表 64 行（56 live、8 soft-deleted），快照 checksum `92e9ccab1c78eb13feb1239ac748df7d`，入站 FK 0。
  - 验收命令：`mvn "-Dtest=LegacyShipmentDataClearMigrationContractTest" test`；`git diff --check`。
  - 下一动作：PR 合并后单独部署，并验证旧表为 0、正式销售发货数据不变。

- `CRETAS-WORKFLOW-TOPOLOGY-LABELS-20260719`
  - 状态：`in-progress`
  - Owner：Codex (`/root`)
  - Base SHA：`ca31c937fb3fadc20a1a1140822d96726649558a`
  - Scope：Workflow 拓扑只读分类/解析 DTO、对应后端单测；Web 生产计划 Workflow 解析类型、标签 helper 与对应 Vitest；本台账/归档。
  - 目标：在不修改数据库枚举、Workflow 持久化或历史数据的前提下，通过后端派生的逻辑投入数量，准确区分 1→1、多→1、1→多和多→多；可替代原料组继续按一个逻辑投入计算。
  - 验收命令：后端 `WorkflowTopologyClassifierTest,ProductWorkflowUnifiedResolutionTest`；Web `productionPlanWorkflowResolution.spec.ts`；`release-web-manifest.sh build`；`git diff --check`。
  - 下一动作：实现只读 `logicalRootInputCount` 契约与四类中文标签，目标测试和 Web 构建通过后提交 review。

## Scope 锁地图

- `REDUNDANCY-SH01-DATA-CLEAR-20260719`：锁定上述 V81 migration、契约测试、第一批清理文档与 dispatch 台账/归档。
- `CRETAS-WORKFLOW-TOPOLOGY-LABELS-20260719`：锁定 `WorkflowTopology*`、Workflow 输出解析 DTO/Service/目标单测、`web-admin/src/api/productionPlan.ts`、`web-admin/src/views/production/plans/productionPlanWorkflowResolution*` 与本台账/归档。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
