# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-18

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-17-active-history.md](archive/2026-07-17-active-history.md)，此前完整历史见 [2026-07-16-active-history.md](archive/2026-07-16-active-history.md)。

## 在飞任务

### E2E-PROD-READONLY-HARNESS-20260717

- 状态：`review`
- Base SHA：`3ce98d4d9d3a7edca79606844c0ac34c0be7b4fb`
- Owner：Codex `/root`
- Scope 锁：`AGENTS.md`、`scripts/e2e/production-readonly/**`、`tests/qa-r1-vue-smoke/**`、`tests/e2e-yield-mixed-sku/readonly-bom-workflow-contract.mjs`、`tests/e2e-yield-mixed-sku/prod-business-flow-audit.mjs`、`tests/e2e-yield-mixed-sku/nonprod-business-flow-audit.mjs`、`tests/e2e-yield-mixed-sku/ui-render-deep-audit.mjs`、`docs/testing/playwright-assets.md`、`.agents/skills/e2e-web-admin/**`、`.agents/skills/project-playwright-e2e/**`、`.claude/skills/e2e-web-admin/SKILL.md`、`.github/workflows/e2e-pr.yml`、`docs/dispatch/ACTIVE.md`
- 验收：旧 BOM/Workflow 观察项已迁入统一框架；旧 SmartBI runner 与重复依赖包、旧 BOM runner 已删除；生产写入口已改为显式非生产脚本，生产域名拒绝与测试环境确认门禁均通过；`AGENTS.md` 与 Web/Playwright E2E skills 已统一指向直接 MCP guarded entry；Node 单元 12/12、Playwright 本地 fixture 3/3（业务写 0）、bundle drift、CLI/MCP/迁移脚本语法检查通过。
- 下一动作：审查并合并更新后的任务 PR；合并后移入当日归档并释放 scope 锁。

## Scope 锁地图

- `E2E-PROD-READONLY-HARNESS-20260717`：`AGENTS.md`、`scripts/e2e/production-readonly/**`、三个旧 Playwright 候选及其迁移目标、`docs/testing/playwright-assets.md`、仓库 Web/Playwright E2E skills、`.github/workflows/e2e-pr.yml`。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
