# Dispatch 台账 — ACTIVE

**最后更新**：2026-07-20

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-07-19-active-history.md](archive/2026-07-19-active-history.md)，此前历史见 [2026-07-18-active-history.md](archive/2026-07-18-active-history.md)。

## 在飞任务

- `SEC-CREDENTIAL-ROTATION-20260719` — `in-progress` — Owner: Codex coordinator — Base SHA: `1ba9a241a77144a80851051efbac584abf4db69d` — 清除 tracked/服务器配置中的硬编码凭证，建立 secret regression gate，按消费者依赖顺序轮换已暴露的 Aliyun/API、数据库、JWT 与内部服务凭证，并完成生产重启、认证/Agent/零业务写入验收及历史暴露评估。
- `BOM-M04-BLOCKERS-20260720` — `in-progress` — Owner: Codex coordinator — Base SHA: `e12a6633f2b88d780751c0bdb9d346ffbfd854b9` — 修复 BOM 包材 `box/case` canonical unit 契约与 v1 激活时替换 orphan-removal 明细集合导致的生产 500；完成目标测试、唯一 Java/Web 制品、main 快进发布和 F006 指定配方验收。

- `AIASSIST-SOP-UX-20260719` — `in-progress`
  - Base SHA：`d7c7956546a9f53c28f6f34e959f94e05ca31223`
  - Owner：Codex `/root`
  - 目标：将 AI Assist 升级为独立的工厂操作/SOP 咨询 AI，优化 Food KB 回答契约与移动端 UI/UX；餐饮 AI 保持独立。
  - 验收：Food KB 目标测试、AI Assist 浏览器 E2E、生产入口与 API 回读。

## Scope 锁地图

- `SEC-CREDENTIAL-ROTATION-20260719`：`scripts/systemd/` 中遗留明文启动脚本、现有/新增 secret 扫描配置与测试、`.gitignore` / 凭证模板、`docs/dispatch/ACTIVE.md`、`docs/dispatch/archive/2026-07-19-active-history.md`；外部状态仅限已授权的 Cretas 47/139 服务器配置、PostgreSQL 角色密码、相关阿里云/API 凭证与必要服务重启。验收：tracked tree 与完整 Git 历史脱敏盘点、scanner gate、exact-main 发布门禁、Java/Python/网关健康、登录与 Restaurant Agent 只读 smoke、核心 ERP 零写入。
- `BOM-M04-BLOCKERS-20260720`：`backend/java/cretas-api/src/main/java/com/cretas/aims/{dto,entity/bom,service}/**/*Bom*`、BOM/unit contract 直接依赖、`backend/java/cretas-api/src/main/resources/db/flyway/V20260720_03__bom_recipe_item_canonical_units.sql` 及对应 `src/test/**`，`web-admin/src/views/production/bom/**`、`web-admin/src/utils/unitPricing.ts` 及对应测试，`docs/dispatch/ACTIVE.md`、`docs/dispatch/archive/2026-07-20-active-history.md`；不触碰 LIUSHANMEN。外部状态仅限 Cretas Java/Web 发布与 F006 配方 `9e2eafed-9205-4627-aa4e-8acf20c460fd` 的已授权连续验收，禁止删除/重建配方头或另造 v1。验收：真实 JPA 激活提交、Flyway migration contract、`box/case/g/kg` 单位契约、BOM Web 目标测试、唯一 release manifest、exact-main fastlane、upstream/health/四方哈希及指定记录状态。

- `AIASSIST-SOP-UX-20260719`
  - `web-admin/public/aiassist.html`
  - `backend/python/food_kb/**`
  - `backend/python/tests/test_food_kb_*`
  - `docs/manual/F006-production-full-chain-manual-test-sop.html`

## 阻塞项

- 无。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认 WIP 上限：2 个代码执行任务 + 1 个只读/测试任务。
