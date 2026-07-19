# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `CR-AI-DESCRIPTOR-BASELINE-01-20260719` — `review`
  - Base SHA: `a2c3ca12fdfe34e227ce19a4c165b46e0107439e`
  - Owner: Codex `/root`
  - Scope: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/gateway/descriptor/ToolDescriptorCatalogTest.java`、`docs/dispatch/**`
  - 验收命令: `mvn -q "-Dtest=ToolDescriptorCatalogTest,ToolDescriptorInventoryDriftTest,ApprovedToolSourceMetadataTest" test`
  - 下一动作: 13 项目标测试与独立只读终审通过；提交 PR 并解除 exact-main 发布构建阻塞。

## Scope 锁地图

- `CR-AI-DESCRIPTOR-BASELINE-01-20260719` 锁定上述 descriptor Catalog 测试与 dispatch 台账；不修改 inventory、runtime policy、Tool 实现或生产迁移。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
