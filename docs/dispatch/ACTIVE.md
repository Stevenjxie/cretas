# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-18

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)，此前历史见 [2026-07-17-active-history.md](archive/2026-07-17-active-history.md)。

## 在飞任务

- `PURCHASE-RECEIVE-WAREHOUSE-PDF-20260718` — `in-progress`
  - Base SHA: `44bd057af0425e7b9655cd20f6f4f90149649155`
  - Owner: 当前协调者
  - 目标: Web 采购入库支持选择外仓且提交真实目标仓库；修正采购订单 PDF 的 PO/SO 语义。按用户纠正，不修改 RN 手机端。
  - 验收: Web 目标单测/类型检查；Java 可信制品唯一 Maven 生命周期覆盖 `PurchaseOrderPdfServiceImplTest`；最终范围审查与发布验证。

## Scope 锁地图

- `PURCHASE-RECEIVE-WAREHOUSE-PDF-20260718`
  - `web-admin/src/views/procurement/receives/**`
  - 对应 Web 单元测试
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/PurchaseOrderPdfServiceImpl.java`
  - `backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/PurchaseOrderPdfServiceImplTest.java`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/WarehouseInventoryGuardService.java`
  - `backend/java/cretas-api/src/test/java/com/cretas/aims/service/factory/WarehouseInventoryGuardServiceTest.java`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java`
  - `backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/impl/PurchaseServiceImplReceivePriceInheritTest.java`
  - `docs/dispatch/ACTIVE.md` 与本次归档

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
