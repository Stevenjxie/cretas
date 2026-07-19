# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-19

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- [review] `CRETAS-REDUNDANCY-PR01-20260719`
  - Base SHA：`5d0fbdab88090178afe80d576cae32856a474d91`
  - Owner：Codex (`/root`)
  - 目标：将 BOM 配方固定为唯一真值；删除 PR-01 旧 `product_recipes` / `recipe_ingredients`、旧 CRUD 与迁移入口、成本 fallback、重复 Entity/Repository/DTO/计算入口及孤立 Web API/路由。
  - 生产删除范围：仅旧表当前 `DEMO_FACTORY` 的 2 条配方头和 17 条明细；V79 采用排他锁、已确认范围守卫、无 `CASCADE` 的 fail-closed 删除。
  - 验收：真实 JPA Context、V79 迁移契约、BOM 调料成本与报工目标测试、Web 类型检查及旧消费者源码清零检查。
  - 禁止：部署、直接执行生产 DML/DDL、修改 SH-01/BS-01/WF-01/SCH-01 运行时代码。

## Scope 锁地图

- `CRETAS-REDUNDANCY-PR01-20260719`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/BomRecipeController.java`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductRecipeController.java`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/bom/BomRecipeMigrationReport.java`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/recipe/**`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/bom/BomSeasoningItem.java`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/recipe/**`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/bom/BomSeasoningItemRepository.java`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/recipe/**`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/service/bom/BomRecipeMigrationService.java`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/ClerkProcessEntryServiceImpl.java`
  - `backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/**`
  - `backend/java/cretas-api/src/main/resources/db/flyway/V20261028_79__drop_legacy_product_recipes.sql`
  - `backend/java/cretas-api/src/test/java/com/cretas/aims/migration/**`
  - `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/bom/**`
  - `backend/java/cretas-api/src/test/java/com/cretas/aims/service/bom/**`
  - `backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry/**`
  - `backend/java/cretas-api/src/test/java/com/cretas/aims/service/recipe/**`
  - `web-admin/src/api/productRecipe.ts`
  - `web-admin/src/router/index.ts`
  - `web-admin/src/views/production/bom/seasoning/__tests__/BomSeasoningIntegration.source.spec.ts`
  - `tests/e2e-yield-mixed-sku/headed-seasoning-cost.mjs`
  - `docs/architecture/2026-07-19-redundancy-cleanup-wave-1.md`
  - `docs/dispatch/ACTIVE.md`
  - `docs/dispatch/archive/2026-07-19-active-history.md`（仅合并收尾）

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
