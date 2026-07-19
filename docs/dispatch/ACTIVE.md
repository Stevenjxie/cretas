# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `BOM01` — `review` — Owner: Codex coordinator — Base SHA: `c030f82063cf56e43103c79edf0076c17472c075` — Web Admin BOM 首次创建与版本管理已实现：原子幂等 ensure-draft、同 SKU 当前版本克隆、空草稿激活门禁及首次编辑交互；目标 JPA/Service/Vitest 已通过，单次 Web release 构建在 clean local commit 上执行，本轮不合并、不部署。

## Scope 锁地图

- `BOM01`：`backend/java/cretas-api/src/main/java/com/cretas/aims/{controller,dto/bom,service/bom,service/bom/impl,repository/bom}/**`、对应 `src/test/**/bom/**`、`web-admin/src/{api,views/production/bom}/**`、`docs/dispatch/ACTIVE.md`；验收命令：BOM 后端目标测试（Repository/Entity/JPQL 有改动时追加真实 JPA Context）、BOM 前端 Vitest、`scripts/deploy/release-web-manifest.sh build`、`git diff --check`。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
