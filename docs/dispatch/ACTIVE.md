# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-17

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-17-active-history.md](archive/2026-07-17-active-history.md)，此前完整历史见 [2026-07-16-active-history.md](archive/2026-07-16-active-history.md)。

## 在飞任务

- `CRETAS-E2E-20260717-FE` — `queued`
  - Base SHA: `3ce98d4d9d3a7edca79606844c0ac34c0be7b4fb`
  - Owner: frontend code agent
  - Scope: `web-admin/src/views/procurement/suppliers/**`、`web-admin/src/components/dialog/StartPurchaseDialog.vue`、`web-admin/src/store/modules/permission.ts`（若实际路径不同，仅限对应 permission store 单文件）及其直接单元测试
  - 验收: Web 目标测试、TypeScript 检查、字段契约断言
- `CRETAS-E2E-20260717-AI` — `queued`
  - Base SHA: `3ce98d4d9d3a7edca79606844c0ac34c0be7b4fb`
  - Owner: AI workflow code agent
  - Scope: `web-admin/src/**/WorkProcessAIChatPanel.vue`、`web-admin/src/utils/aiEntryGuards.ts`、`backend/java/cretas-api/src/main/java/com/cretas/aims/**/CanvasAIController.java` 及其直接目标测试
  - 验收: Web 目标测试、AI controller/service 目标测试、模式与 SKU 实体保持契约
- `CRETAS-E2E-20260717-AUDIT` — `in-progress`
  - Base SHA: `3ce98d4d9d3a7edca79606844c0ac34c0be7b4fb`
  - Owner: coordinator
  - Scope: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/BomServiceImpl.java`、对应 BOM 测试、共享契约审查、最终集成与发布
  - 验收: BOM 变更日志操作者目标测试、最终 Web 检查、单生命周期 release build、服务级发布验证

## Scope 锁地图

- `CRETAS-E2E-20260717-FE`: 供应商历史、开始采购弹窗、permission store 与直接测试
- `CRETAS-E2E-20260717-AI`: 工序 AI 面板、AI SKU 守卫、Canvas AI controller 与直接测试
- `CRETAS-E2E-20260717-AUDIT`: BOM 审计服务、对应测试、ACTIVE/归档及最终集成

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
