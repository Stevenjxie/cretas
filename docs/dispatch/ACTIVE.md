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

- `CRETAS-F006-WORKFLOW-TOPOLOGY-MATRIX-20260719`
  - 状态：`review`
  - Owner：Codex (`/root`)
  - Base SHA：`cc210a61b4afb4f4e88338a71f37610f5467f1b6`
  - Scope：`tests/e2e-workflow-routing/**` 与本台账；依照 `AGENTS.md` 第 13 条，通过独立任务专用 API/UI 路径对生产 F006 创建本测试所需 SKU、Workflow、生产计划并回读验证。禁止修改生产只读 harness、产品代码、数据库结构、全局配置或其他租户数据。
  - 目标：构造并验证 1→1、1→多、多→1、多→多、可替代原料非多→1、同集合歧义、最小超集、精确匹配优先及无共享 Workflow 的生产计划识别矩阵。
  - 验收：首次 mutation 前实时证明登录 `factoryId=F006`；逐项记录预期 mutation、实际 ID/行数和写后状态；所有请求、实体和回读均受 F006 约束；实际触发生产计划多选入口和 Workflow 悬浮预览；其他租户业务写入为 0。
  - 结果：任务专用 API 夹具 53/53 次写入成功，10/10 个 resolver 场景通过；Playwright MCP 已触发多→1 自动固定、1→多最小超集补全、精确重叠人工选路、无共享路线阻断及悬浮 Cell 图。UI 创建 F006 计划 `9aee62e4-e5bb-4510-a964-12cf9a6aba96`（`PLAN-1784437835291-6DB33FDC`），固定 Workflow `97@v1` 与 BOM `74ac6dfc-a9d7-4c14-8fdd-c43fd4ba06ea`；API/DB 回读一致，其他租户同前缀计数均为 0。
  - 下一动作：合入任务专用脚本 `tests/e2e-workflow-routing/f006-topology-matrix.mjs`；生产测试数据按用户要求保留，不迁移、不自动清理。

## Scope 锁地图

- `REDUNDANCY-SH01-DATA-CLEAR-20260719`：锁定上述 V81 migration、契约测试、第一批清理文档与 dispatch 台账/归档。
- `CRETAS-F006-WORKFLOW-TOPOLOGY-MATRIX-20260719`：锁定 `tests/e2e-workflow-routing/**` 与本台账。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
