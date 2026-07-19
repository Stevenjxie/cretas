# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `CRETAS-F006-PLAN-REPORTING-LINKAGE-E2E-20260719`
  - 状态：`in-progress`
  - Owner：Codex (`/root`)
  - Base SHA：`9ebe0073f9346ee190f2f5a45319c350009c529a`
  - Scope：F006 生产计划选品与 Workflow 固定后到报工的 UI/API/数据库回读；任务专用 E2E/证据；若发现缺陷，仅修改生产计划到报工的 Workflow、投入/产出选择与锁定相关前后端代码、目标测试及本台账/归档。
  - 目标：验证报工自动继承生产计划固定的 Workflow；可替代原料和联产成品按业务规则支持选择；不可多选的投入/产出直接默认锁定且不能误选其他物料。
  - 验收：首次写入前实时证明 `factoryUser.factoryId=F006`；覆盖至少一个可选场景和一个锁定场景，提交后刷新并做 API/数据库或下游回读；全程记录 mutation 与实体 ID，其他租户业务写入为 0。
  - 结果：首次写入前实时证明 `f006_admin / factoryId=F006`；既有计划成功物化批次 `10583`、Workflow 实例 `41`，固定 `97@v1`，快照正确包含 2 个 RAW 必投端口和 1 个成品端口。生产 UI 发现 RAW-only→成品工序被误映射为上游半成品表单，已修正为投入形态优先；相关 Web tests 34/34 通过。
  - 追加阻塞：F006 W8 v2 已验证 AT_LEAST_ONE 原料/成品多选；正式报工在创建第二联产品批次时触发 `fk_production_batch_selected_workflow`，因为旧外键错误要求每个联产品都等于 Workflow 归属 SKU。
  - 下一动作：新增批次 Workflow pin 的联产兼容迁移与真实 PostgreSQL 回归，单次 release build 后合并部署，再重试同一计划正式报工并回读扣料/双产出。

## Scope 锁地图

- `CRETAS-F006-PLAN-REPORTING-LINKAGE-E2E-20260719`：锁定任务专用 E2E/证据、生产计划到报工的 Workflow 关联及投入/产出选择与锁定相关前后端目标代码、`V20261028_83__allow_joint_output_batch_workflow_pin.sql`、对应 schema/PostgreSQL 目标测试与本台账/归档。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
