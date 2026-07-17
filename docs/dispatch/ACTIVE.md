# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-18

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)，此前历史见 [2026-07-17-active-history.md](archive/2026-07-17-active-history.md)。

## 在飞任务

- `WF-UX-20260718-01` | `review` | Owner: Codex `/root` | Base SHA: `c39ef12f4fb87878184d114f9a69b7a42f92af05` | 完成 Workflow 编辑器交互、选择与连线、响应式单位换算、SKU 搜索、产出类型转换、BOM 发布门禁、独立版本和内嵌 AI Composer | 验收：目标 Vitest + Web build + Java 单生命周期目标测试与可信 JAR + F006 浏览器聚焦验证

## Scope 锁地图

- `WF-UX-20260718-01`: `web-admin/src/views/system/product-processes/workflow/**`、`web-admin/src/views/system/product-processes/index.vue`、共享 `WorkProcessAIChatPanel.vue` 及测试、后端 Workflow controller/entity/service/validator、产品类型 DTO/Repository、对应 controller/service/validation/process-entry/JPA Context 测试、Flyway Workflow migration、`docs/dispatch/ACTIVE.md` 与当日归档。Java scope 覆盖 `gramsPerUnit`、多投入运行时、BOM 发布门禁和独立版本；Repository/Entity 变更必须通过真实 JPA Context 门禁。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
