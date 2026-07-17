# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-18

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)，此前历史见 [2026-07-17-active-history.md](archive/2026-07-17-active-history.md)。

## 在飞任务

- `WF-UX-20260718-01` | `in-progress` | Owner: Codex `/root` | Base SHA: `c39ef12f4fb87878184d114f9a69b7a42f92af05` | 修复 Workflow 编辑器原料去重、下拉收起、布局连线、投入命名、单位换算和工序快捷编辑 | 验收：目标 Vitest + Web build + F006 浏览器聚焦验证

## Scope 锁地图

- `WF-UX-20260718-01`: `web-admin/src/views/system/product-processes/workflow/**`、对应单元测试、`backend/java/cretas-api/src/main/java/com/cretas/aims/dto/producttype/ProductTypeOptionDTO.java`、`backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductTypeRepository.java`、`backend/java/cretas-api/src/test/java/com/cretas/aims/repository/ProductMasterDataRepositoryQueryValidationTest.java`、`docs/dispatch/ACTIVE.md` 与当日归档。补充 Java scope 用于让 Workflow SKU 选项直接继承 `gramsPerUnit`；Repository 投影变更必须通过真实 JPA Context 门禁。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
