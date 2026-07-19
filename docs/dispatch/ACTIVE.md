# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `CRETAS-REDUNDANCY-AI01-20260719`
  - 状态：`review`
  - Owner：Codex (`/root`)
  - Base SHA：`054fec7626ec538055f6b7698c448ab1afd3301e`
  - Scope：AI-01 单表双 Entity 收敛；仅允许修改 `AIIntentConfig` canonical Entity/Repository、`SmartBIConfigController` 的 intents 兼容切片、`SmartBIConfigService*` 对应 intents 方法、删除 `entity/smartbi/AiIntentConfig` 与 `repository/smartbi/AiIntentConfigRepository`、相关真实 JPA/Controller/Service 测试，以及本台账/归档；不触碰生产任务、订单、SmartBI thresholds 或其他 AI runtime。
  - 目标：`ai_intent_configs` 只保留一套 JPA Entity/Repository；旧 `/smartbi-config/intents` 读取兼容走 canonical 真值，所有旧写入口显式冻结且不得 fallback/双写。
  - 验收：真实 JPA Context 启动并解析 canonical Repository；SmartBI intents GET 兼容测试通过；POST/PUT/DELETE/reload 明确返回 410；canonical `/ai-intents` 与 AI router 目标测试通过。
  - 验收证据：`AIIntentConfigRepositoryQueryValidationTest,SmartBIConfigControllerIsolationTest,SmartBIConfigServiceImplBatch1Test` 共 15 项通过；`AIIntentConfigControllerTest,SemanticRouterStartupTest,SemanticRouterTwinMarginTest` 通过。
  - 下一动作：提交并创建 PR；等待 `JPA repository query startup gate` 与必需 CI 通过后合并，再归档任务并释放 scope。

## Scope 锁地图

- `CRETAS-REDUNDANCY-AI01-20260719`：锁定 AI intent canonical Entity/Repository、SmartBI config intents 兼容切片及其测试；不锁 SmartBI thresholds 和其他 AI runtime。

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
