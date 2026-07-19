# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `REDUNDANCY-PT-01`｜状态：`in-progress`｜Base SHA：`8715e2d304f41ee5d6b72bd40232f5e9214650af`｜Owner：Codex `/root`｜目标：收敛 `process_tasks` 与 `work_process_tasks` 双轨，停止旧表双写与 fallback，迁移内部/前端消费者并补齐删除迁移预览；不执行生产部署或生产写入｜下一动作：完成调用链改造、真实 JPA Context 与目标测试，提交 PR。

## Scope 锁地图

- `REDUNDANCY-PT-01`：`backend/java/cretas-api/src/main/java/com/cretas/aims/{controller,entity,repository,scheduler,service,ai/tool/impl/processing}/**/*ProcessTask*`、`backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/{ProductionPlanServiceImpl,ProcessWorkReportingServiceImpl,StateMachineServiceImpl}.java`、对应 DTO/测试/Flyway migration、`frontend/CretasFoodTrace/src/**` 中 process-task 调用链、`web-admin/src/**` 中 process-task 调用链；验收命令：真实 JPA Context 查询启动测试 + ProcessTask/WorkProcessTask/ProductionPlan/报工目标测试 + RN/Web 目标测试或类型/构建检查。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
