# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-18

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)，此前历史见 [2026-07-17-active-history.md](archive/2026-07-17-active-history.md)。

## 在飞任务

- `CRETAS-RELEASE-FASTLANE-20260718-POLICY` — `in-progress`
  - Base SHA: `50275a4257cba8e9894a3faf58a6c4123b034346`
  - Owner: coordinator
  - Scope: `AGENTS.md`、`.codex/rules/worktree-and-main-only-deploy.md`、`.agents/skills/deploy-backend/SKILL.md`、`scripts/deploy/publish-main-fastlane.sh` 及其直接契约测试、ACTIVE/归档
  - 验收: 受控无 PR fast-forward 门禁测试、shell syntax、编码检查、`git diff --check`
- `CRETAS-RELEASE-FASTLANE-20260718-WEB` — `claimed`
  - Base SHA: `50275a4257cba8e9894a3faf58a6c4123b034346`
  - Owner: coordinator (Dirac exited before structured handoff; coordinator adopted scope)
  - Scope: `scripts/deploy/deploy-web-admin.sh`、`scripts/deploy/release-web-manifest.sh`、对应 `scripts/tests/test-*web*manifest*.sh` 与既有 Web deploy 直接契约测试
  - 验收: manifest 有效复用、tree/哈希/dirty/损坏回退测试、既有 Web deploy 测试、shell syntax、`git diff --check`

## Scope 锁地图

- `CRETAS-RELEASE-FASTLANE-20260718-POLICY`: 发布规则、无 PR fast-forward helper、ACTIVE/归档
- `CRETAS-RELEASE-FASTLANE-20260718-WEB`: Web dist manifest helper、Web deploy 接入与直接测试

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
