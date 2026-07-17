# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-16

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-16-active-history.md](archive/2026-07-16-active-history.md)，此前完整历史见 [2026-07-14-active-history.md](archive/2026-07-14-active-history.md)。

## 在飞任务

| ID | Base SHA | Scope 锁 | Owner | Mode | Worktree/PR | Status | Blocker | Next action |
|---|---|---|---|---|---|---|---|---|
| UNIT-BOX-CASE-20260717 | `893eaafc830e8c436723f7080c867b2a8613458a` | `web-admin/src/api/systemUnits.ts`; `web-admin/src/api/__tests__/systemUnits.spec.ts`; `web-admin/src/components/common/**`; `docs/dispatch/ACTIVE.md`; completion archive | Codex coordinator | inline | `C:\Users\Steve\cretas-workflow-validation-raw-filter` / `codex/workflow-validation-raw-filter` | in-progress | 无 | 修正 `box=盒`、`case=箱` 合并与选择，先合并部署 Web；验收：单位目标 Vitest、`vue-tsc --noEmit`、`git diff --check`、线上单位 API/静态资源验证 |
| WF-VALIDATION-RAW-FILTER-20260717 | `893eaafc830e8c436723f7080c867b2a8613458a` | `web-admin/src/views/system/product-processes/workflow/**`; related frontend tests; `docs/dispatch/ACTIVE.md`; completion archive | Codex coordinator | inline | `C:\Users\Steve\cretas-workflow-validation-raw-filter` / `codex/workflow-validation-raw-filter` | queued | 等待单位紧急修复部署 | 单位修复上线后继续实现未绑定 SKU Cell 定位高亮，以及原料下拉三级分类与搜索 |

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
