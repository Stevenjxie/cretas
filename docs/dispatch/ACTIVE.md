# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `REDUNDANCY-BS01-INJECTION-CONFIG-20260719`
  - 状态：`review`
  - Owner：Codex (`/root`)
  - Base SHA：`0f81ca356dbbf5d14546eb684ea5e64eaa0a97b9`
  - Scope：BS-01 的 `BomProcessSeasoning` Entity/Repository/DTO/Service/成本与版本快照链、V82 migration、真实 JPA Context/业务测试、Web BOM API/ProcessSheet/复制弹窗、审计交付文档及本台账/归档。
  - 生产证据：`bom_process_seasoning=0`；47 条 live 调料中 12 条工序绑定均有 `material_type_id`，35 条整 SKU 绑定里 28 条 COOKING 将从 recipe header 回填 `0.3333`，7 条 INJECTION 不改；旧调料 API 当前网关日志无命中。
  - 验收命令：真实 JPA repository query startup test；BomRecipe/Copy/Version/ClerkProcessEntry/RecipeCost 目标测试；Web ProcessSheet/BOM copy 目标测试；`git diff --check`。
  - 验收结果：后端 100 tests、真实 JPA Context、Web 17 tests、Vite build 与 `git diff --check` 均通过。
  - 下一动作：提交 PR，等待 JPA CI 门禁后合并并独立部署 V82/Java/Web。

- `CRETAS-WORKFLOW-TOPOLOGY-LABELS-20260719`
  - 状态：`review`
  - Owner：Codex (`/root`)
  - Base SHA：`ca31c937fb3fadc20a1a1140822d96726649558a`
  - Scope：Workflow 拓扑只读分类/解析 DTO、对应后端单测；Web 生产计划 Workflow 解析类型、标签 helper 与对应 Vitest；本台账/归档。
  - 目标：在不修改数据库枚举、Workflow 持久化或历史数据的前提下，通过后端派生的逻辑投入数量，准确区分 1→1、多→1、1→多和多→多；可替代原料组继续按一个逻辑投入计算。
  - 验收命令：后端 `WorkflowTopologyClassifierTest,ProductWorkflowUnifiedResolutionTest`；Web `productionPlanWorkflowResolution.spec.ts`；`release-web-manifest.sh build`；`git diff --check`。
  - 结果：新增只读 `logicalRootInputCount`，复用 EXACTLY_ONE 替代组折叠逻辑；生产计划候选标签明确显示 1→1、多→1、1→多、多→多，旧接口缺字段时 fail-safe 显示“投入关系待确认”。Web Vitest 11/11 通过；唯一 Maven release lifecycle 15/15 tests 通过并生成可信 JAR manifest；唯一 Web release build 成功并生成不可变 archive/manifest。
  - 下一动作：推送 PR；合并后从 exact `origin/main` 校验并复用两个可信制品，分别部署后端与 Web，再对 F006 既有多→1 Workflow 做生产只读 UI 验收。

## Scope 锁地图

- `REDUNDANCY-SH01-DATA-CLEAR-20260719`：锁定上述 V81 migration、契约测试、第一批清理文档与 dispatch 台账/归档。
- `CRETAS-WORKFLOW-TOPOLOGY-LABELS-20260719`：锁定 `WorkflowTopology*`、Workflow 输出解析 DTO/Service/目标单测、`web-admin/src/api/productionPlan.ts`、`web-admin/src/views/production/plans/productionPlanWorkflowResolution*` 与本台账/归档。
- `REDUNDANCY-BS01-INJECTION-CONFIG-20260719`：锁定上述 BS-01 后端、V82、目标测试、Web 消费者与 dispatch 台账/归档。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
