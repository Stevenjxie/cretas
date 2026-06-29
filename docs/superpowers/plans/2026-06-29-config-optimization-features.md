# 上线配置优化 —— 3 个降工作量功能 + 调料补录 实施计划

> **For agentic workers:** 三个独立 full-stack 功能,可并行(不同文件)。每个走独立 worktree off origin/main,实现+自测+PR,**不自部署 prod**(回 main 由 Opus organizer 终审+部署 —— 🔒 涉及成本/BOM 配置)。

**Goal:** 把"上线一个产品要配 40-120min、100 产品 67-200h"降到可接受范围,补齐 3 个缺失的配置入口/批量工具,并给真客户产品补录调料配方。

**Architecture:** 三个功能各自后端加端点 + 前端加入口,互不依赖。后端实体大多已存在(FactoryCostSettings / ProductWorkProcess / BomItem),主要是加 Controller/Service 端点 + web-admin UI。调料补录是纯 headed 数据操作(占位值)。

**Tech Stack:** Java 21 + Spring Boot 3.2 + JPA;Vue3 + Element Plus(web-admin);prod F006 验证。

**背景证据**(配置面调研 2026-06-29):见各功能引用的 file:line。硬门槛只有"产品存在";BOM/工序链/配方/批次全是软门槛(缺则成本/追溯不全,不阻断生产)。已有复用杠杆:模板+`/assemble-sku`、BOM `/clone`、出成率自学习 `/bom/yield-estimate/*`、智能填充 `/product-types/suggest`。本计划补 3 个缺的。

---

## Feature 1:工时单价配置 UI(修 dead-end)

**问题**:`§15` 报工 warning 说"在工厂成本设置中配置工时单价",但 `FactoryCostSettings.laborHourlyRate` **无任何写入端点 + 无前端页**(防呆 Rule 5 dead-end)。实体已存在 `entity/config/FactoryCostSettings.java`(factoryId unique + laborHourlyRate)。

**Files:**
- Create: `controller/config/FactoryCostSettingsController.java`
- Create/Modify: `service/.../FactoryCostSettingsService.java`(+ impl)— 已有 repository `repository/config/FactoryCostSettingsRepository.java`
- Frontend Create: `web-admin/src/views/system/factory-cost-settings/index.vue`(或挂到现有 系统设置 页一个 tab/panel)
- Frontend Modify: `web-admin/src/api/` 加 api fn + 路由 + 菜单项

**API 契约:**
- `GET /api/mobile/{factoryId}/config/cost-settings` → `{ laborHourlyRate: number|null }`(null = 用默认 26,前端显示"未配置,当前按默认 ¥26/工时")
- `PUT /api/mobile/{factoryId}/config/cost-settings` body `{ laborHourlyRate: number }` → upsert(按 factoryId,无则 insert 有则 update),`laborHourlyRate > 0` 校验,返 `{ laborHourlyRate }`。

**验收:**
- 后端单测:PUT upsert 后 GET 回读一致;laborHourlyRate ≤ 0 返 400。
- headed:进设置页 → 填 30 → 保存 → 回读 30;然后跑一次 §15 那种工时段报工 → 人工成本按 30 算(不再是默认 26 warning)。
- 防呆:页面显示"未配置时按默认 ¥26",修复 warning 指向真实存在的页。

---

## Feature 2:工序链一键复制(单产品 30min→1min)

**问题**:每个产品手配整条工序链(`ProductWorkProcess` × N),最痛(4/5)。复制机制**已存在于测试 helper**(`tests/e2e-yield-mixed-sku/_headed-helpers.mjs` `copyProductProcesses` 循环 POST `/product-work-processes`),但**无用户端原子端点**。

**Files:**
- Modify: `controller/ProductWorkProcessController.java`(加 copy 端点)
- Modify: `service/.../ProductWorkProcessService.java`(+ impl)— 加 `copyChain(factoryId, sourceProductId, targetProductId)`,事务内 bulk 复制(processOrder/workProcessId/defaultCostCategory/unitOverride/estimatedMinutesOverride/reportingRequired/auxAllocMethod/auxUnitPrice/auxBasis 全带)
- Frontend Modify: `web-admin/src/views/.../products/index.vue` 或工序配置页 — 加"从产品复制工序链"按钮 + 源产品 el-select picker

**API 契约:**
- `POST /api/mobile/{factoryId}/product-types/{targetProductId}/copy-processes-from/{sourceProductId}` → 事务内:读源 `ProductWorkProcess` list,逐条 clone 到 target(processOrder 保持),**幂等**:target 已有工序链则返 409 `{existingCount, message:"目标已有工序链,先清空再复制"}`(防呆 Rule 4)。返 `{ copiedCount }`。

**验收:**
- 后端单测:源有 5 工序 → copy → target 有 5 工序,字段逐一相等;target 已有工序 → 409。
- headed:建新 SKU(无工序)→ 点"从 [叮咚猪舌] 复制工序链"→ 7 工序复制成功 → API 回读 target 7 工序 == 源。

---

## Feature 3:BOM Excel 批量导入(大批量上线省最多)

**问题**:100 产品 × 5 BOM 行 = 500 次手填,无批量(4/5)。`BomController POST /bom/items` 单条。

**Files:**
- Modify: `controller/BomController.java`(加 batch-import 端点)
- Modify: `service/.../BomService`(+ impl)— `batchImport(factoryId, productTypeId, rows[])` 事务原子
- Frontend Modify: `web-admin/src/views/production/bom/index.vue` — 加"Excel 导入"按钮 + 上传 + 下载模板 + 预览表 + 提交

**API 契约:**
- `POST /api/mobile/{factoryId}/bom/items/batch-import` body `{ productTypeId, items: [{materialName, materialTypeId?, materialCategory, standardQuantity, yieldRate?, unit}] }` → 事务内逐条校验+insert,**任一行失败整批回滚** + 返每行 `{row, ok, error}`。materialTypeId 缺则按 materialName 在 raw-material-types 模糊匹配,匹配不到该行 error(不静默)。返 `{ inserted, failed, rows:[...] }`。
- 前端 Excel 解析(`xlsx` 库)→ 转 items JSON → POST(后端不收文件,收 JSON,避免 POI 依赖)。前端提供"下载模板"(列:物料名/类别/成品含量/出成率/单位)。

**验收:**
- 后端单测:5 行合法 → inserted=5;第 3 行 materialName 匹配不到 → 整批回滚,failed 标第 3 行。
- headed:下载模板 → 填 3 行 → 上传 → 预览 → 提交 → API 回读 `/bom/items/{ptid}` 有 3 行。

---

## Feature 4(我做,非 fleet):调料配方补录(headed,占位值)

**问题**:F006 真客户卤味产品(叮咚猪舌/卤猪蹄等)全部零调料配方 → 调料成本恒 0。用**合理占位值**补录(明确标"占位待张权核"),让成本非 0、链路完整。

**做法**(复用 `headed-seasoning-cost.mjs` 已验证的 create DRAFT→PUT seasoning→activate 流程):
- 对每个真卤味产品:建/取 is_current recipe → PUT 一组占位调料(熟制段 1-2 条,如 卤料包 dosage 80g/kg @ ¥18/kg、盐 dosage 15g/kg @ ¥3/kg)→ activate。
- API 回读 `/seasoning` 确认 items 落库 + status ACTIVE。
- 占位值集中常量 + remark 标"PLACEHOLDER-待张权核实",方便日后替换。
- ⛔ 只补调料配方,不改产品其它真实数据(BOM/价格/客户)。

**验收:** 补录后这些产品 `/bom/recipes/by-product/{id}/seasoning` 有 items;跑一次熟制报工调料成本非 0 + 无"未设置调料配方"warning。

---

## 隔离 + 交付 + 红线

- 每 Feature 独立 worktree off origin/main:`git worktree add -b feat/cfg-N ../cretas-cfg-N origin/main`。
- 🔒 三个功能都涉及**成本/BOM 配置**(影响成本计算)→ 执行者只做到"实现+自测+PR off origin/main",**不自部署 prod**。回 main 由 Opus organizer 终审 + 从 main 部署(blue-green)+ 核对运行 jar 含修复。
- commit 锁 scope:`git commit -m "..." -- <files>` 或 `safe-commit.sh`;PR 前 `git diff origin/main...HEAD --stat` 确认无 sister 文件夹带。
- Flyway:若 FactoryCostSettings 表缺(实体存在但表可能没建)→ 加 V<date>__ migration(🔒 Opus 终审编号防撞)。先查表是否存在。

## 分发

| Feature | 推荐 | 通道 | 理由 |
|---|---|---|---|
| 1 工时单价 UI | Codex 或 Sonnet | 后端 CRUD + 前端表单,边界清楚 | 小、明确 |
| 2 工序链复制 | Codex | 后端 copy 端点 + 前端 button | 后端逻辑 helper 已有参照 |
| 3 BOM 导入 | Codex(后端)+ Composer(前端 Excel) | 前端 xlsx 解析 + 后端 batch | 前后端可拆 |
| 4 调料补录 | **Opus 自己 headed** | — | 数据操作,改真客户数据需 organizer 控 |

Opus organizer:设计 API 契约(本文档)+ 终审 PR + 部署 prod + 做 Feature 4。执行者部分写自包含 brief(内联 .claude/rules 摘要)。
