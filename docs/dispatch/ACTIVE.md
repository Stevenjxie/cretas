# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-18

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)，此前历史见 [2026-07-17-active-history.md](archive/2026-07-17-active-history.md)。

## 在飞任务

- `CRETAS-RELEASE-PARALLEL-FASTLANE-20260718` — `in-progress`
  - Base SHA: `8b82fd7973a5ab6d3c264be15df46e50a6d7dc8e`
  - Owner: coordinator
  - Scope: `scripts/deploy/release-java-preflight.sh`、`scripts/deploy/release-cretas-parallel.sh`、对应 shell 契约测试、`AGENTS.md`、`.agents/skills/deploy-backend/SKILL.md`、ACTIVE/归档
  - 验收: 低成本 Java 预检捕获常见测试配置问题；并行入口严格校验两类可信 manifest、仅在独立服务且已验证制品时并行、失败不掩盖任一子发布结果；shell syntax、目标 shell 测试、编码检查、`git diff --check`

## Scope 锁地图

- `CRETAS-RELEASE-PARALLEL-FASTLANE-20260718`: Java 预检、Web/Java 受控并行发布入口、直接契约测试与对应发布规则

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
