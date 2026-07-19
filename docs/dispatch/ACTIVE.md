# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `CR-REDUNDANCY-WO-01-20260719` — `in-progress`
  - Base SHA: `fe0c732bcf2d1d9d1b07bb755f9272e1fcc1e6af`
  - Owner: Codex `/root`
  - Scope: `backend/java/cretas-api/src/main/java/com/cretas/aims/{entity/WorkOrder.java,repository/WorkOrderRepository.java,controller/WorkOrderController.java,service/WorkOrderService.java,service/impl/WorkOrderServiceImpl.java,ai/tool/impl/crm/Order*Tool.java,ai/tool/impl/report/ReportTaskAssignWorkerTool.java,ai/tool/impl/system/TodoListTool.java}`、`backend/java/cretas-api/src/main/resources/{ai/tool/gateway/tool-descriptors.yaml,db/flyway/V20261028_84__drop_legacy_work_orders.sql}`、相关 JPA/Tool 测试、`docs/dispatch/**`
  - 验收命令: `mvn -q "-Dtest=LegacyWorkOrderRemovalRepositoryQueryValidationTest,OrderListToolTest,OrderQueryToolTest,OrderTodayToolTest,OrderDeleteToolTest,ToolDescriptorInventoryDriftTest,ApprovedToolSourceMetadataTest" test`
  - 下一动作: 将仍在用的订单查询/取消意图改接 `sales_orders`，删除无消费者的旧写 Tool、旧 `/work-orders` 全链路及空表迁移，完成真实 JPA Context 门禁后提交 PR。

## Scope 锁地图

- `CR-REDUNDANCY-WO-01-20260719` 锁定上述 WO-01 文件；不触碰打印类 `production-work-order`、WF-01、SCH-01、主工作区未提交内容和生产部署。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
