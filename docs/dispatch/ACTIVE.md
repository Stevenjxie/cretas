# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)，此前历史见 [2026-07-17-active-history.md](archive/2026-07-17-active-history.md)。

## 在飞任务

- [review] `CRETAS-REDUNDANCY-CV01-20260719`
  - Base SHA：`a8e9d2c42ec1070ec84682b36e12012c76dc3565`
  - Owner：Codex (`/root`)
  - 目标：删除无消费者且生产为空的 `cost_variance_configs` 历史表；提交 PR-01/SH-01 迁移或冻结写方案与 BS-01 字段收敛设计。WF-01、SCH-01 明确保留，不进入代码清理。
  - Scope：仅限下方锁定文件；禁止生产写入、部署及 PR-01/SH-01/BS-01 运行时代码改动。
  - 验收：`cd backend/java/cretas-api && mvn "-Dtest=CostVarianceConfigRepositoryQueryValidationTest,CostVarianceServiceImplTest,CostVarianceServiceTest,CostVarianceConfigsRemovalMigrationContractTest" test`
  - 验证结果：目标测试 32 tests，0 failures/errors，BUILD SUCCESS；真实 Hibernate/JPA Context 已启动。
  - 下一动作：完成最终 scope/diff 审查，提交并创建 PR；合并与生产部署保持独立状态。

## Scope 锁地图

- `CRETAS-REDUNDANCY-CV01-20260719`
  - `backend/java/cretas-api/src/main/resources/db/flyway/V20261028_78__drop_redundant_cost_variance_configs.sql`
  - `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/bom/CostVarianceConfigRepositoryQueryValidationTest.java`
  - `backend/java/cretas-api/src/test/java/com/cretas/aims/migration/CostVarianceConfigsRemovalMigrationContractTest.java`
  - `docs/architecture/2026-07-19-redundancy-cleanup-wave-1.md`
  - `docs/dispatch/ACTIVE.md`
  - `docs/dispatch/archive/2026-07-19-active-history.md`（仅合并收尾时）

## 阻塞项

- 无。Flyway V76 冲突已由 PR #1463 修复并将 gateway ledger 固定为 V77；本任务使用下一版本 V78。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
