# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-18

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)，此前历史见 [2026-07-17-active-history.md](archive/2026-07-17-active-history.md)。

## 在飞任务

- `CRETAS-RELEASE-CRITICAL-PATH-20260718` — `review`
  - Base SHA: `10b2c0195607fc891eb59b25a78a7f8a6e877ed0`
  - Owner: Codex `/root`
  - Scope: Java 可信制品远端预热/复用、构建与部署结构化阶段计时、启动路径优化，以及 Java/Web 统一发布总入口自动调度；不改 Web 业务代码
  - 验收: 发布脚本契约测试、shell 语法/编码/diff 检查、现有 manifest/deploy 加速门禁；Java 代码严格复用已通过的单一 Maven 生命周期可信制品
  - 下一动作: 提交已通过验证的统一入口与启动/制品优化，走 PR 合入 `origin/main` 后归档并释放 scope

## Scope 锁地图

- `CRETAS-RELEASE-CRITICAL-PATH-20260718` 锁定：`scripts/deploy/release-jar-manifest.sh`、`scripts/deploy/deploy-backend.sh`、`scripts/deploy/release-cretas.sh`、`scripts/deploy/release-cretas-artifacts.sh`、`scripts/deploy/deploy-cretas-parallel.sh`、`scripts/deploy/verify-release.sh`、新增 Java 制品预热/报告 helper、对应 `scripts/tests/`、`SemanticRouterServiceImpl.java`、`service/startup/StartupWarmupCoordinator.java`、`AiWarmupStatusRegistry.java` 及对应目标测试、`AGENTS.md`、`.agents/skills/deploy-backend/SKILL.md`、本任务 dispatch 文档。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
