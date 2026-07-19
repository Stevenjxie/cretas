# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `REDUNDANCY-SH01-FREEZE-20260719` — `review`
  - Base SHA: `6368314cc33c12bcf0c6705a002f5b78f1eead77`
  - Owner: `Codex /root`
  - Scope: `backend/java/cretas-api` 旧 shipment mutation Controller/Service、shipment mutation AI Tools、`V20261028_80__freeze_legacy_shipment_ai_writes.sql` 与目标测试；`frontend/CretasFoodTrace` 旧出货写入口；`web-admin` 旧出货写入口；本任务证据文档与 dispatch 归档。数据清空必须在 410 已部署后作为独立任务和 migration 执行。
  - 验收命令: `mvn "-Dtest=LegacyShipmentWriteFreezeControllerTest,LegacyShipmentWriteFreezeServiceTest,LegacyShipmentMutationToolRemovalContractTest,ToolDescriptorInventoryDriftTest,ShipmentTraceabilityFlowTest,SalesDeliveryHonorBatchAllocationTest,SalesDeliveryBatchAllocationServiceWarehouseTest" test`; RN/Web 旧 mutation 消费者清零目标测试；`git diff --check`。

## Scope 锁地图

- `REDUNDANCY-SH01-FREEZE-20260719`: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ShipmentController.java`, `backend/java/cretas-api/src/main/java/com/cretas/aims/service/ShipmentRecordService.java`, `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/**` legacy shipment mutation registrations, `backend/java/cretas-api/src/main/resources/db/flyway/V20261028_80__freeze_legacy_shipment_ai_writes.sql`, related backend tests, `frontend/CretasFoodTrace/src/**` legacy shipment mutation consumers, `web-admin/src/views/{sales,warehouse}/shipments/**`, SH-01 evidence/dispatch docs.

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
