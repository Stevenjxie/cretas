# Track D1 Brief — BOM 配方 + 2 BOM 相关 Bug

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **来源**: 从原 TRACK_D_BRIEF.md 拆分 (2026-05-14 dispatch 调整 4→6 chats)
> **接收方**: Chat 5 (Track D1 worker)
> **派发方**: Organizer (Chat 1)
> **生效日**: 2026-05-14
> **总工时**: 名义 9 工作日 / Claude 加速预期 5-6 工作日
> **本文件目标**: 给到一份完全 self-contained 的 brief, 你不需要任何额外 context 就能立即上手干活

---

## §1 项目 Onboarding

### 1.1 这是什么项目

**Cretas (白垩纪) 食品溯源系统** — 一套面向**食品加工厂 + 餐饮企业**双主线的全链路溯源 + 生产管理 SaaS。

技术栈:
- **后端**: Java 21 + Spring Boot 3.2.12 + PostgreSQL + JPA (Hibernate 6) — 端口 10010
- **前端**: Expo 53+ React Native + TypeScript + React Navigation 7+ — 端口 3010
- **AI 服务**: Python FastAPI + LLM (Aliyun Qwen / DashScope) — 端口 8083
- **Embedding**: Java gRPC — 端口 9090
- **DB**: PostgreSQL (cretas_db = test, cretas_prod_db = prod) — 端口 5432

项目状态: Phase 3 核心完成 (~82-85%). 现处 ASAP Phase 0 + Sprint 1 ("六扇门交付冲刺")。

### 1.2 客户背景: 六扇门 F006 卤制品工厂

- **factoryId**: F006 (六腾门)
- **行业**: 卤制品加工 (猪蹄 / 猪头肉 / 牛肉等)
- **节奏**: ASAP 1.5 月交付 P0 客户测试关键功能
- **关键产品**: 珠奇 / 鲁猪蹄 / 牛肉等卤制品
- **客户已开过 4 次会议** — 第四次 (May 10) 提出本 brief 涉及的全部 BOM/单位 痛点

测试时**用 F006 prod 账号** (16 个真实角色账号已存在生产 DB), 不用 F001 dev seed。账号清单 see `reference_f006_liutengmen_prod_accounts.md` memory entry (organizer 知)。本地测试用 F001/cretas_db。

### 1.3 你在做什么

**你是 Track D1**, 6 个并行 chat 之一 (organizer + A + B + C + D1 + D2), 由 Organizer (Chat 1) 协调:
- Track A — Canvas 死代码修复 (9d)
- Track B — AI 钉钉 + 抄码 + PDF (12d)
- Track C — Attachment + 打印 + 三价 + RBAC (11d)
- **Track D1 — BOM 配方 + BOM 物料选择器 + 单位转换 (9d)** ← **你**
- Track D2 — 工序管理 + 工序通用 bug (7d, 并行 chat)

**原 Track D 太大 (16d), 拆成 D1 + D2 让 2 chat 并行**. BOM 子轨道 (你) 跟工序子轨道 (D2) 独立, 但可能在 product_type_id 共享上需要轻量协调 (per §11.3)。

---

## §2 任务范围与工时

### 2.1 3 个 sub-project 总览

| # | 项目 | 工时 | P 级 | 说明 |
|---|---|---|---|---|
| 1 | **M-BOM-1 BOM 配方编辑 UI (工厂端)** | 5d | P0 | 餐饮端 RecipeListScreen 已有, 工厂端缺 BomConfigScreen |
| 2 | **Bug 修: BOM 物料选择器** | 2d | P0 | 客户原话"物料名称是要手写吗?" → 应该是 Select |
| 3 | **Bug 修: 单位转换强校验** | 2d | P0 | 客户原话"统一单位"或"自动折算" → 强校验 g/kg |

**Total**: 9 工作日 (名义). Claude 加速 1.7-2x → **预期 ~5-6 工作日完成**。

### 2.2 为什么这 3 项打包给 Track D1

- BOM (项目 1) + 单位转换 (项目 3) + 物料 Select (项目 2) 高度耦合 — 都涉及 `bom_recipes` 主子表 + `raw_material_types` 字典硬外键
- BOM 是六扇门 1.5 月交付的核心验收点
- 2 个 bug 是客户实测发现的, 跟 BOM 密切关联, 一并修
- 工序相关 (原 Track D 项目 2+3) 拆出去给 Track D2, 工序跟 BOM 关联面小, 适合并行

### 2.3 关键节点

- **Day 5 末**: BOM 配方编辑 UI 工厂端可用, AIChat 一句话建 BOM 可跑
- **Day 7 末**: BOM 物料选择器修复完成 (MaterialSelectModal 替代手写)
- **Day 9 末**: 3 项全部 ship, 准备 demo 录制

### 2.4 ProductionBatch Entity Fork 需谨慎

`ProductionBatch` 当前是单表服务餐饮 + 工厂双主线, 但语义差异大 (餐饮 = 中央厨房批次, 工厂 = 车间生产批次)。**长期需要 fork 成 `RestaurantBatch` + `FactoryBatch`**, 但**不在本 track 范围**。你在本 track 中**不要动 ProductionBatch Entity**, 如发现必须改, **先 ping Organizer**。这个 fork 决策留 organizer 协调 Sprint 2 处理。

---

## §3 文件 Ownership (你的 / 不准动)

### 3.1 你拥有的目录/文件

**后端 (Java)**:
- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/bom/` — `BomRecipe.java` (新增) + `BomRecipeItem.java` (新增) + 现有 `BomItem.java`/`BomChangeLog.java` (保留兼容期)
- `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/bom/BomRecipeRepository.java` + `BomRecipeItemRepository.java` (新增)
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/bom/` (新建包) — BomRecipeService 接口 + impl + BomCostCalculationService
- `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/BomRecipeController.java` (新增)
- `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/bom/` (新建包) — 6 个 Tool

**Flyway 迁移**:
- `backend/java/cretas-api/src/main/resources/db/migration/V20260516_02__bom_redesign.sql` (新增)
- (Flyway tracker 由 Cretas 默认机制, 不手动 INSERT)

**前端 (RN)**:
- `frontend/CretasFoodTrace/src/screens/factory/bom/BomConfigScreen.tsx` (新增, 工厂端 BOM 列表)
- `frontend/CretasFoodTrace/src/screens/factory/bom/BomEditorScreen.tsx` (新增, 主子表编辑)
- `frontend/CretasFoodTrace/src/components/MaterialSelectModal.tsx` (新增, BOM 物料选择器复用组件)
- `frontend/CretasFoodTrace/src/screens/management/MaterialSpecManagementScreen.tsx` (修改: 强制 g/kg + 二级单位转换默认)
- `frontend/CretasFoodTrace/src/services/api/bomApiClient.ts` (新增)
- `frontend/CretasFoodTrace/src/types/bom.ts` (新增)

### 3.2 你不准改的 (改必先 ping Organizer)

- `backend/.../entity/BaseEntity.java` — 项目根 Entity, 影响所有表
- `backend/.../service/impl/IntentExecutorServiceImpl.java` — AI 意图路由核心
- `frontend/.../services/api/aiApiClient.ts` — AI API client 共享层
- `CLAUDE.md` + `.claude/rules/` — 项目规范
- `backend/.../entity/ProductionBatch.java` — 餐饮/工厂共享, fork 决策需 organizer
- Track A 目录: `frontend/.../screens/lowcode/` + `service/impl/DecorationServiceImpl.java` + `ai/tool/impl/pagedesign/` + `ai/tool/impl/decoration/`
- Track B 目录: `service/dingtalk/` + `entity/integration/` + `screens/shared/LabelScanScreen.tsx` + `ai/tool/impl/material/`
- Track C 目录: `entity/Attachment.java` + `service/attachment/` + `screens/smartbi/` + `ai/tool/impl/finance/RBACAuditTool.java`
- **Track D2 目录**: `backend/.../service/workprocess/` + `backend/.../entity/workprocess/` + `backend/.../controller/WorkProcessTaskController.java` + `frontend/.../screens/factory/workprocess/` + `ai/tool/impl/workprocess/` + 生产计划页 (工序 bug)
- 餐饮端 `frontend/.../screens/restaurant/recipes/RecipeListScreen.tsx` — 是你的**参考实现**, 但不准破坏 (餐饮端继续用)

### 3.3 共享文件 (轻量改动 OK, 大改先 ping)

- `frontend/.../types/navigation.ts` — 加新 screen 到 Stack 是 OK 的 (你的新页面 BomConfigScreen 等需要加)
- `frontend/.../i18n/locales/` — 加新 i18n key 是 OK 的
- `frontend/.../types/material.ts` 或 `restaurant.ts` — 仅查看, 不改
- `backend/.../service/conversion/MaterialUnitConversionService` — 仅 **调用**, 不改核心逻辑

### 3.4 Git Worktree 策略

强烈推荐用 git worktree 隔离:

```bash
cd C:/Users/Steve/my-prototype-logistics
git worktree add ../my-prototype-logistics-track-d1 -b feature/asap-track-d1 HEAD
cd ../my-prototype-logistics-track-d1
# 在此 worktree 内工作, 防止跟 main worktree + Track D2 worktree 冲突
```

Branch 命名: `feature/asap-track-d1-{编号}` 如 `feature/asap-track-d1-m-bom-1`。

---

## §4 Day-by-Day 执行计划

### Day 1 — BOM 配方 spec 吸收 + 现状 audit

**目标**: 完全掌握 `bom_recipes` + `bom_recipe_items` 新主子表 spec, 知道现有 `bom_items` 单表数据迁移路径

**动作**:
1. 读 `宏见竞品分析/01-客户档案/SCHEMA_DESIGN.md` §2.6 (line 1117-1419) — 完整 DDL + Entity + API
2. 读六扇门第四次会议 line 200-400 — 客户原话场景 (BOM 配方 + 物料 Select + 出成率 + 单位)
3. 现状 audit:
   - 看现有 `backend/.../entity/bom/BomItem.java` — 旧单表结构
   - 看现有 `BomController.java` (找 backend/.../controller/) — 旧 API
   - 看 `backend/.../service/.../BomExpansionService` 是怎么读 BomItem 的 (PR #312 上下文)
   - 看 `backend/.../service/conversion/` 现有 ConversionService — 单位换算逻辑
4. 列旧→新数据迁移 mapping (每条 bom_items → bom_recipes 1 行 + bom_recipe_items N 行, 聚合 by product_type_id)
5. 在 STATUS 写 Day 1 结论 + 第一版迁移 SQL 草稿

**产出**: Day 1 STATUS update + 迁移 SQL 草稿

### Day 2 — Flyway 迁移 V20260516_02__bom_redesign.sql

**目标**: 数据库 schema 上 (DRAFT, 本地 test 跑通)

**动作**:
1. 写 `V20260516_02__bom_redesign.sql`:
   - `CREATE TABLE bom_recipes (...)` (照搬 SCHEMA_DESIGN line 1135-1183)
   - `CREATE TABLE bom_recipe_items (...)` (照搬 line 1186-1229)
   - 数据迁移 INSERT: 现有 `bom_items` 按 (factory_id, product_type_id) 聚合 → 每组 1 条 `bom_recipes` (DRAFT 状态, source_type='MANUAL') + N 条 `bom_recipe_items`
   - 注意: 旧 `bom_items` 表**不 drop**, 保留 30 天观察期 (SCHEMA_DESIGN 决策 B)
   - 单位 normalize: 旧 bom_items.unit 可能是空或乱, normalize 成 ('g','kg','ml','L','个','袋','箱') 之一
2. 本地起 cretas_db 跑 migration, 验证:
   - `SELECT COUNT(*) FROM bom_recipes;` 跟 `SELECT COUNT(DISTINCT (factory_id, product_type_id)) FROM bom_items;` 一致
   - 没破坏现有 BOM 业务 (旧 BomController 还能查 bom_items)
3. 写回滚 SQL (备用)

**产出**: V20260516_02 SQL 文件 commit (本地未 push, 等组合 PR)

### Day 3 — Entity + Repository + Service

**目标**: Java 层就绪

**动作**:
1. 创建 `BomRecipe.java` Entity (照搬 SCHEMA_DESIGN line 1239-1294, 注意 `@PriceSensitive` 注解 5 个字段, 注意 `@PrePersist void assignUUID()`)
2. 创建 `BomRecipeItem.java` Entity (line 1297-1343, `@Transient calculateActualQuantity()` 方法很关键 — 出成率折算逻辑)
3. 创建 `BomRecipeRepository.java`:
   - `Optional<BomRecipe> findByFactoryIdAndProductTypeIdAndIsCurrentTrue(...)`
   - `Page<BomRecipe> findByFactoryIdAndStatus(...)`
4. 创建 `BomRecipeItemRepository.java`:
   - `List<BomRecipeItem> findByRecipeIdOrderBySortOrderAsc(...)`
5. 创建 `BomRecipeService` 接口 + `BomRecipeServiceImpl`:
   - `createRecipe(CreateBomRecipeRequest req)` — 创建草稿 + 自动 recipeCode 生成 (`BOM-YYYYMMDD-NNN`)
   - `activateRecipe(id)` — DRAFT → ACTIVE, 自动把同 productTypeId 其他版本 is_current=false (注意 partial unique 索引约束)
   - `cloneRecipe(id)` — 克隆为新 version
   - `calculateCost(id)` — 重算成本 (不写表, 返回 DTO)
   - `archiveRecipe(id)` — DRAFT/ACTIVE → ARCHIVED
6. 写 BomRecipeController.java (10 endpoint 照 SCHEMA_DESIGN line 1355-1369)
7. 跑单元测试 (用 H2 mock — Cretas 现有 pattern)
8. 提醒: **注意 PostgreSQL parameter-side IS NULL 用 CAST AS string** (per `.claude/rules/database-entity-sync.md`)

**产出**: Entity/Repo/Service/Controller all in, 单元测试 PASS

### Day 4 — 工厂端 BomConfigScreen + BomEditorScreen

**目标**: 工厂用户能在 RN App 配置 BOM

**动作**:
1. 先看餐饮端 `frontend/.../screens/restaurant/recipes/RecipeListScreen.tsx` (你的参考实现, 但餐饮端不准动)
2. 创建 `frontend/.../services/api/bomApiClient.ts`:
   - `getRecipes(productTypeId?, current?)` / `getRecipe(id)` / `createRecipe(req)` / `updateRecipe(id, req)` / `activateRecipe(id)` / `archiveRecipe(id)` / `cloneRecipe(id)` / `calculateCost(id)` / `addItem(recipeId, req)` / `updateItem(itemId, req)` / `deleteItem(itemId)`
   - 用现有 axios client + JWT interceptor (参考别的 api client)
3. 创建 `frontend/.../types/bom.ts`:
   - `interface BomRecipe { id, recipeCode, productTypeId, productName, version, isCurrent, overallYieldRate, outputQuantityPerUnit, outputUnit, totalMaterialCost?, ..., status: 'DRAFT'|'ACTIVE'|'ARCHIVED', items: BomRecipeItem[] }`
   - `interface BomRecipeItem { id, recipeId, materialTypeId, materialName, standardQuantity, yieldRate, actualQuantity, unit, unitPrice?, ... }`
4. 创建 `BomConfigScreen.tsx` — 列表页:
   - Tab: 全部 / DRAFT / ACTIVE / ARCHIVED
   - 列表项: productName + version + isCurrent badge + overallYieldRate + totalCost
   - FAB → 跳 BomEditorScreen 新建
5. 创建 `BomEditorScreen.tsx` — 主子表编辑页:
   - 表单: 产品 select (从 product_types 字典) + 出成率 input + 单份成品克数 + output_unit
   - 子表列表: 每行一个原料 (materialName 显示 + materialTypeId 隐藏 + standardQuantity + yieldRate + unit + unitPrice)
   - **物料添加按钮 → 弹出 raw_material_types Select 模态框** (这是项目 2 的核心, Day 6 集成)
   - 出成率自动折算 preview (per item): `actualQuantity = standardQuantity / (yieldRate/100)` 实时显示
   - 总成本聚合 preview (item_cost = actualQuantity × unitPrice, 总 = sum + labor + overhead)
   - 保存按钮 → POST /bom/recipes
   - 激活按钮 → POST /bom/recipes/{id}/activate
6. 加到 Stack navigation (`frontend/.../types/navigation.ts`)
7. 本地跑通 (cretas_db + 本地后端)

**产出**: 工厂端能列表 + 创建 + 编辑 + 激活 BOM 配方 (物料选择器 Day 6 集成)

### Day 5 — 出成率折算 service + AIChat Tool + PR

**目标**: 后端折算 service + 一句话建 BOM, ship 项目 1 PR

**动作**:
1. 写后端 `BomCostCalculationService`:
   - `calculateActualQuantity(standardQty, yieldRate)` — 单原料折算 (200g / 58% = 344.83g, 但 schema 注释说是 250.58g, 这是因为客户算的是"成品 200g 需要原料多少", 折算公式是 `200 / 0.58 = 344.83`, 但客户原话 "250.58" 看起来用了 `200 + 200×0.58/2.21` 之类奇怪算法, 实际**按 SCHEMA_DESIGN line 1340 `calculateActualQuantity()` 为准**: `standardQuantity.divide(yieldRate/100, 6, HALF_UP)`)
   - `calculateRecipeCost(recipe, items)` — 聚合成本
   - 注意 Java BigDecimal HALF_UP vs Python banker's rounding (per `.claude/rules/python-java-port.md` Rule 12, 虽然本 track 不涉及 Python, 但 Cretas 项目规则)
2. 创建 6 个 AI Tool (`ai/tool/impl/bom/`):
   - `BomRecipeCreateFromTextTool` — 实现 SCHEMA_DESIGN line 1410: "200g 牛肉 + 10g 盐 + 5g 糖" 一句话建 BOM
     - getToolName: `"bom_recipe_create_from_text"`
     - 参数: `productTypeId`, `outputQuantityPerUnit`, `outputUnit`, `ingredientsText` (LLM 解析)
     - 内部: 调 LLM 提取 → `material_type` 模糊匹配 → 调 `BomRecipeService.createRecipe(...)`
   - `BomRecipeCreateFromSampleTool` — `bom_recipe_create_from_sample` (从研发样品转)
   - `BomRecipeCloneWithModifyTool` — `bom_recipe_clone_with_modify` ("克隆 SKU-201 但减 10% 包材")
   - `BomRecipeCostCalculateTool` — `bom_recipe_cost_calculate` (重算成本+利润)
   - `BomRecipeActivateTool` — `bom_recipe_activate` (支持 WRITE preview, override `supportsPreview()` + `doPreview()`)
   - `BomRecipeQueryTool` — `bom_recipe_query` (查当前生效 BOM)
3. 注册 Tool: `@Component` 自动注册到 `ToolRegistry`, 无需手动 register, 启动日志确认 `✅ 注册工具: name=bom_recipe_*, class=Bom*Tool`
4. 在数据库 `ai_intent_config` 表插入 6 个 intent 行绑定这些 tool_name (per `.claude/rules/ai-intent-tool-skill-architecture.md`)
5. AIChat 跑通: 在 RN App 里说 "给红烧肉建 BOM, 五花肉 200g 出成率 80%, 老抽 5g, 糖 10g, 单份成品 150g" → BOM 创建成功
6. **写 PR**: `[Track-D1] M-BOM-1 BOM 配方编辑 UI (工厂端)`
   - PR body 含: 涉及文件 (列举) / 测试方式 (本地 BomEditorScreen 截图 + AIChat 一句话 demo) / 风险点 (兼容期 bom_items 双写考虑)

**产出**: M-BOM-1 PR 开 Organizer review

### Day 6 — Bug 修: BOM 物料选择器 (MaterialSelectModal)

**目标**: 复现 + 设计 Select 模态框

**动作**:
1. 复现: 在 Day 4 写的 BomEditorScreen 中, 如果还是手写 materialName, 改成 Select 弹窗
2. 看 `MaterialSpecManagementScreen.tsx` — 现有原料管理页, 了解 raw_material_types 字典结构
3. 创建 `MaterialSelectModal.tsx` 复用组件 (放 `frontend/.../components/`):
   - Searchbar (按 name/code 模糊搜索)
   - 列表显示 raw_material_types: 编码 + 名称 + 默认单位
   - 点击返回选中的 materialTypeId + materialName + defaultUnit
   - 接口: GET /raw-material-types?q=X&page=1&size=50
4. 集成到 BomEditorScreen.tsx 的"添加原料"按钮 → 弹出 MaterialSelectModal → 选中后自动 fill materialName + materialTypeId + unit (default)
5. **校验**: materialTypeId 必填, 提交 BOM 时若空报 "请从字典选择原料"
6. 后端校验 (Day 3 已写 ConstraintViolation, 此处再确认): @NotBlank private String materialTypeId 已生效

**产出**: MaterialSelectModal + BOM 物料选 (不是手写)

### Day 7 — 物料 Select PR

**目标**: ship 项目 2 PR

**动作**:
1. 测试: BOM 创建/编辑只能从字典选, 手写无效
2. PR: `[Track-D1] Bug 修: BOM 物料选择器 (字典 Select 替代手写)`
3. PR body: 含 before/after 截图

**产出**: 物料 Select PR open

### Day 8 — Bug 修: 单位转换强校验 (MaterialSpec + 后台折算)

**目标**: 原料字典层强制 g/kg, 后台支持自动折算

**动作**:
1. 修 `MaterialSpecManagementScreen.tsx`:
   - 原料定义页的"基本单位"input 改成 Select, 限定 ('g', 'kg', 'mg', 'ml', 'L', '个', '袋', '箱', '瓶', '盒')
   - 默认推荐 g (固体) / ml (液体)
   - 加 i18n: "请选择标准单位 (推荐 g 或 kg 便于全链路统一)"
2. 后端 `MaterialUnitConversionService` (现有, 不改): 验证 g↔kg / mg↔g / ml↔L / mg↔kg (1000000x) 都通
3. BOM 端 (Day 4 写的 BomEditorScreen) 加自动折算 UI:
   - 用户输入 "200 g" 时, 旁边自动显示 "(= 0.2 kg)"
   - 客户原话 line 263 "我在这写的克, 那我做调包的时候会自动折换成公斤" — 这意思是 BOM 用 g, 但实际拣货/采购单位是 kg, 后台自动折算
4. 库存出库 + 采购入库 跑全链路 (确认 ConversionService 在所有链路生效):
   - BOM 标准量 = 200 g, 库存出库 200g 或 0.2kg 都 OK
   - 找 `WarehouseService` / `PurchaseService` 等用 ConversionService 的地方, 确保没 bypass
   - **不修**业务逻辑, 只修单位 normalize 入口
5. 加 e2e 测试用例

**产出**: 单位强校验 + 折算链路验证

### Day 9 — 单位 PR + 整体收尾

**目标**: ship 项目 3 PR + 整体演示准备

**动作**:
1. PR: `[Track-D1] Bug 修: 单位转换强校验 (原料字典 g/kg + 全链路自动折算)`
2. 整体 demo 视频脚本:
   - BOM 成本管理 → 原辅料配方 → 新建 → 选猪蹄 → "添加物料" → MaterialSelectModal → 选五花肉 (g) → standardQty 200 → yieldRate 80% → 实际 250g 自动算出
   - 单位选 g 在 BOM, 但库存/采购自动转 kg 显示
   - AIChat → "给红烧肉建 BOM, 五花肉 200g 出成率 80%, 老抽 5g, 糖 10g, 单份成品 150g" → 成功
3. 写 final STATUS update — 列举所有 3 个 PR + screenshot 链接
4. **不录 demo 视频**, 由 Organizer 录

**产出**: 3 项全 ship, Track D1 完结

---

## §5 关键参考文档

### 5.1 必读 (按顺序)

| 优先级 | 文档 | 用途 |
|---|---|---|
| ★★★ | `宏见竞品分析/01-客户档案/SCHEMA_DESIGN.md` §2.6 (line 1117-1419) | bom_recipes + bom_recipe_items 完整 spec |
| ★★★ | `宏见竞品分析/01-客户档案/六扇门第四次-May10.md` | 客户原话场景 (BOM 配方 + 物料 Select + 单位转换最详细的客户语料) |
| ★★ | `.claude/rules/ai-intent-tool-skill-architecture.md` | AI Tool-Skill 架构规范, 加 AI Tool 必读 |
| ★★ | `.claude/rules/database-entity-sync.md` | PostgreSQL 严格 GROUP BY + parameter-side IS NULL CAST AS string |
| ★★ | `.claude/rules/field-naming-convention.md` | Java entity camelCase / DB snake_case / JSON camelCase |
| ★★ | `.claude/rules/api-response-handling.md` | 统一 `{success, data, message}` 格式, 禁降级 |
| ★ | `宏见竞品分析/03-审计过程/AUDIT_FRESH_C_CODE.md` | Cretas 代码现状, 知道哪些已 ship |
| ★ | `frontend/CretasFoodTrace/src/screens/restaurant/recipes/RecipeListScreen.tsx` | 你的参考实现 (餐饮端配方列表, 类似工厂端 BOM) |
| ★ | `CLAUDE.md` 项目根 | 全局规范快查 |

### 5.2 现有相关 PR (历史 context)

| PR | 摘要 |
|---|---|
| #297 | D2 BOM yield-rate UI preview + D3 g↔kg unit conversion — 部分 BOM 折算 UI 已 ship, 你可参考但本 track 重做主子表 |
| #312 | BomExpansionService 读 BomItem (RPF fallback) — 旧 BomItem 表的展开服务, 兼容期保留 |
| #311 | bom_items.unit standardization — A4 eager normalize, 单位 normalize 历史 |
| #293 | B1 工序 + B7 弹窗宽度 + B8 BOM 联动 quick wins — 历史 quick fix |
| #173 | 餐饮 P1 batch 一二级单位转换 — 餐饮端的单位转换历史 |
| #455 / #466 | @PriceSensitive on BomItem.unitPrice + 16 sister fields — RBAC 单价隐藏, 你的 BomRecipeItem 也要加 |

### 5.3 现有 API/Service 现状 (代码地址)

- 后端 BOM Controller: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/BomController.java` (旧, 服务 bom_items, **保持不动**)
- 后端 BOM Entity: `backend/.../entity/bom/BomItem.java` (旧主表), `BomChangeLog.java` (变更日志)
- 后端 Conversion Service: `backend/.../service/conversion/` 内 (单位换算逻辑, **不动核心**, 只用)
- 前端餐饮 RecipeListScreen: `frontend/CretasFoodTrace/src/screens/restaurant/recipes/RecipeListScreen.tsx` (参考, 不动)
- 前端 MaterialSpec: `frontend/CretasFoodTrace/src/screens/management/MaterialSpecManagementScreen.tsx` (Day 8 修)

---

## §6 接口契约 (重点细节)

### 6.1 BomRecipe + BomRecipeItem 主子表

**主表结构** (照搬 SCHEMA_DESIGN line 1135-1183):
- 主键: `id VARCHAR(191)` (UUID, 用 `@PrePersist void assignUUID()`)
- `factory_id` + `product_type_id` + `version` 组合唯一 (partial index where deleted_at IS NULL)
- `is_current` partial unique (同 product 只 1 个 ACTIVE)
- 5 个 `@PriceSensitive` 字段: totalMaterialCost / totalLaborCost / totalOverheadCost / totalCost / standardSalePrice
- 状态: DRAFT / ACTIVE / ARCHIVED
- 来源: MANUAL / SAMPLE_AUTOGEN / AI_GENERATED / IMPORTED

**子表结构** (line 1186-1229):
- 主键: `id BIGSERIAL` (auto-increment)
- `recipe_id` FK to bom_recipes(id) ON DELETE CASCADE
- `material_type_id` FK to `raw_material_types(id)` ON DELETE RESTRICT (硬外键, 禁手写)
- `unit` CHECK IN ('g','kg','mg','ml','L','个','袋','箱','瓶','盒')
- `material_category` IN ('RAW', 'AUXILIARY', 'PACKAGING')
- 2 个 `@PriceSensitive`: unitPrice / itemCost

### 6.2 老 bom_items 兼容期策略

- **不 drop** 旧 `bom_items` 表
- migration V20260516_02 一次性把 bom_items 数据**复制**到 bom_recipes + bom_recipe_items (而不是 move)
- 保留 30 天 (观察期), 之后另起 V20260615_01__drop_bom_items.sql 才删
- 期间 `BomExpansionService` 仍然读 `BomItem`, 但你的新 `BomRecipeService` 写新表
- 旧 BomController 端点 (`/api/mobile/{factoryId}/bom/items`) 保留, 新端点是 `/api/mobile/{factoryId}/bom/recipes`
- **不要** 删 `BomItem.java` Entity

### 6.3 单位转换 Service 接口 (现有 ConversionService, 不动)

- 现有 `MaterialUnitConversionService` 在 `backend/.../service/conversion/` 内
- 提供 `convert(value, fromUnit, toUnit)` 方法
- 内部用 `material_unit_conversions` 表存基础换算关系 (g↔kg=0.001, mg↔g=0.001, ml↔L=0.001)
- **你不修这个 service**, 只在 BomCostCalculationService + Day 8 的库存/采购入口确保所有 unit 都先 normalize 一遍 (调用此 service)

### 6.4 AIChat Tool 命名建议

照 SCHEMA_DESIGN line 1408-1416:

**BOM** (6 个):
- `bom_recipe_create_from_text`
- `bom_recipe_create_from_sample`
- `bom_recipe_clone_with_modify`
- `bom_recipe_cost_calculate`
- `bom_recipe_activate` (WRITE preview)
- `bom_recipe_query`

**Tool 类命名**: `XxxTool extends AbstractBusinessTool`, `@Component` 自动注册。

### 6.5 ProductionBatch Entity Fork (跟 Organizer 协调)

`ProductionBatch` 单表服务餐饮+工厂双主线。本 track 不涉及 spawn_tasks (那是 Track D2 工作), 所以 BOM 这边**不应该需要改 ProductionBatch**。

**若发现确需改 ProductionBatch.java**:
1. STOP 你的修改
2. 在 STATUS 写 blocker: "Day X 发现需在 ProductionBatch 加 XXX 字段, 原因 YYY"
3. ping Organizer, 让 organizer 决定:
   - 加字段 (短期解决, fork 推迟)
   - fork (长期方案, 但跨 chat 协调)
4. **不擅自动手**

---

## §7 PR / Status Update 流程

### 7.1 每日 STATUS 同步

每天在 `宏见竞品分析/04-最终决策/STATUS/TRACK_D1_STATUS.md` 追加 1 段 (如果文件不存在, 你自己创建):

```markdown
## Day N (YYYY-MM-DD)
- ✅ 完成: 项目内容 X / Y / Z
- 🟡 进行中: 项目 A (剩 N%)
- ❌ Blocker: 描述 (需 organizer 协调)
- 明日计划: 项目 B / C
```

Organizer 每天 review 你的 STATUS。

### 7.2 PR 流程

**每完成 1 项 sub-project 即 PR 1 次** (总共 3 个 PR), 不要憋大爆炸:
1. 起 branch `feature/asap-track-d1-{编号}` (如 `feature/asap-track-d1-m-bom-1`)
2. PR 标题: `[Track-D1] N# 编号 项目名` (本 brief 用 M-BOM-1 编号)
3. PR body 模板:
   ```
   ## Summary
   - 项目: M-BOM-1 BOM 配方编辑 UI (工厂端)
   - 涉及文件: (列举 ~10 个关键)
   - 测试方式: 本地 BomEditorScreen 截图 + AIChat demo
   - 风险点: bom_items 兼容期双写考虑

   ## Test plan
   - [ ] 本地 cretas_db migration 跑通
   - [ ] 单元测试 PASS
   - [ ] 工厂端能创建 + 编辑 + 激活 BOM
   - [ ] AIChat 一句话建 BOM 跑通

   🤖 Generated with Claude Code
   ```
4. Push 前在自己 worktree 内**两次 git status** (本 brief §3 + `.claude/rules/concurrent-edit-safety.md` 强调) 确保没意外文件
5. 推送后跟 Organizer 说"PR open, 等 review"
6. Organizer review + merge

### 7.3 大改动先 ping (必须)

以下情况**必须先 ping Organizer**:
- BOM 主子表迁移 (Day 2 第一次 push 前) — schema 大改动, organizer 拍板 migration 时机
- ProductionBatch Entity fork (任何动作前) — 跨 track 共享 Entity
- 你想改 §3.3 共享文件中的某个 (改 navigation.ts 加 screen 不需要 ping, 改字段类型需要)
- 跟 Track D2 在 product_type_id 或 raw_material_types 上的字段语义有协调需求

### 7.4 碰到 Blocker

任何 unknown / 阻塞 → STATUS 写 "❌ Blocker: ...", organizer 帮你解决。

---

## §8 不要做

### 8.1 不要 refactor BOM 整体架构

- 旧 BomController / BomItem 兼容期还在用, 30 天后才删
- 不要把 BomItem 重命名 / 改字段 / 改 Repository 签名 — 别的 service (BomExpansionService, BomChangeLog 等) 会炸
- 新功能全部在 `BomRecipe` + `BomRecipeItem` 上做

### 8.2 不要改 ProductionBatch Entity

- fork 决策需 organizer 拍板 (餐饮 vs 工厂语义差异问题)
- 本 track 不涉及 spawn_tasks (那是 Track D2)
- 若发现确需改, ping organizer

### 8.3 不要写新 N# 编号

- 现有编号 M-BOM-1 已映射到 SCHEMA_DESIGN
- 不要发明 D-XXX-1 之类新编号

### 8.4 不要破坏餐饮端 RecipeListScreen

- 餐饮端 `frontend/.../screens/restaurant/recipes/RecipeListScreen.tsx` 是你的**参考实现**
- 餐饮端不准动 — 餐饮客户继续用
- 工厂端做**独立**的 `BomConfigScreen` (不复用餐饮 Screen)

### 8.5 不要 bypass ConversionService

- 单位换算所有路径都走 `MaterialUnitConversionService` (现有)
- 你不要在 BomCostCalculationService 内自己 hard-code `1 kg = 1000 g`
- 通过现有 service 调用, 保持一致性

### 8.6 不要静默失败 / 降级处理

- 项目规范禁止: `.claude/rules/api-response-handling.md` 强调统一 `{success, data, message}` 格式
- BOM 校验失败 → 明确返回 `{success: false, message: "...", code: "BOM_VALIDATION_ERROR"}` + 前端 Alert
- 不要 try-catch 吞掉错误后返回假数据

### 8.7 不要硬编码 LLM key / DB 密码

- 凭证全部走环境变量 (`.claude/rules/CREDENTIAL-MANAGEMENT.md`)
- 你的 AI Tool 通过 `IntentExecutorServiceImpl` 间接调 LLM, 不直接拿 key

### 8.8 不要并发改同一文件

- 你跟 Track A/B/C/D2 不会共享文件 (per §3.2 ownership)
- 但如果你开了 multi-worktree, 用 git worktree 隔离 (`.claude/rules/concurrent-edit-safety.md`)
- commit 前 `git status --short` 确认 staging 区只有预期文件 (规则 5)

### 8.9 不要碰 Track D2 范围

- 工序相关 (`work_processes`, `product_work_processes`, `work_process_tasks`) 是 Track D2 负责
- "工序通用 P 过来"bug 也是 D2
- 你 BOM 跟工序在数据层接触面仅是 `product_type_id` 共享 (产品字典), 这是只读引用, 不会冲突

---

## §9 验收清单 (Track D1 完成判定)

### 9.1 3 项 sub-project 全 ship

- [ ] M-BOM-1: 工厂端能配置 BOM 配方 (主子表 + 出成率自动折算 + 物料 Select)
- [ ] Bug 2 (BOM 物料 Select): BOM 添加原料强制从 raw_material_types 字典选, 手写无效
- [ ] Bug 3 (单位强校验): 原料字典定义阶段强制 g/kg, BOM/库存/采购全链路自动折算

### 9.2 AIChat 一句话场景跑通

- [ ] "给红烧肉建 BOM, 五花肉 200g 出成率 80%, 老抽 5g, 糖 10g, 单份成品 150g" → bom_recipe_create_from_text Tool 触发 → BOM 创建
- [ ] "克隆 SKU-201 的 BOM 但减 10% 包材" → bom_recipe_clone_with_modify

### 9.3 客户实测场景跑通

- [ ] 客户原话 "我做这个珠奇一个成品是 200 克, 出成率是 58%, 自动折算" → BomEditorScreen 实时折算 preview
- [ ] 客户原话 "物料名称是要手写吗?" → MaterialSelectModal 弹窗
- [ ] 客户原话 "统一单位, 公斤" → 原料字典强制 g/kg

### 9.4 不破坏现有

- [ ] 旧 BomController 端点 + BomItem 表 30 天内仍正常 (兼容期)
- [ ] 餐饮端 RecipeListScreen 没动
- [ ] ProductionBatch Entity 没动

### 9.5 3 个 PR 全 merge

- [ ] PR 1: `[Track-D1] M-BOM-1 BOM 配方编辑 UI (工厂端)` merged
- [ ] PR 2: `[Track-D1] Bug 修: BOM 物料选择器 (字典 Select 替代手写)` merged
- [ ] PR 3: `[Track-D1] Bug 修: 单位转换强校验 (原料字典 g/kg + 全链路自动折算)` merged

---

## §10 客户场景对照表

| 客户原话 | 我的实现 | 涉及项目 | 涉及代码 |
|---|---|---|---|
| "物料名称是要手写吗?" (line 217) | MaterialSelectModal 弹窗从 raw_material_types 选 | 项目 2 | BomEditorScreen + MaterialSelectModal |
| "比如说我这个是 200 克, 那我可能就写个 200, 这个最好加个单位" (line 230) | standardQuantity input + unit Select (限定 g/kg/...) | 项目 1 + 3 | BomEditorScreen 行内字段 |
| "尽量单位是克, 然后 200 克, 200 克我的出成的话是 58%" (line 232) | yieldRate 字段 (DECIMAL 6,2, CHECK 0-100) | 项目 1 | BomRecipeItem.yieldRate |
| "我做这个珠奇一个成品是 200 克, 那我就写个 200 克, 那么我出生率是 58%, 自动折算的话就是 250.58" (line 268) | calculateActualQuantity = standard / (yield/100); 200/0.58=344.83 (注: 客户口误"250.58", 按数学公式) | 项目 1 | BomCostCalculationService + BomEditorScreen preview |
| "可以后台自动折算, 比如说我在这写的克, 那我做调包的时候会自动折换成公斤" (line 263) | 后端 ConversionService 自动 g↔kg 转换, 全链路统一 | 项目 3 | MaterialUnitConversionService (现有) |
| "如果不能自动折算的话, 是统一单位比较好一点" (line 259) | 原料字典强制 ('g','kg','mg','ml','L','个','袋','箱','瓶','盒') | 项目 3 | MaterialSpecManagementScreen unit Select |

---

## §11 风险 + 应急

### 11.1 已知风险

| 风险 | 缓解 |
|---|---|
| BOM 主子表迁移破坏现有 BomExpansionService | Day 2 兼容期保留旧表, BomItem 同步双写观察期 30 天 |
| ProductionBatch 餐饮/工厂语义混乱 | fork 决策推迟到 organizer 拍板, 本 track 不动 |
| 单位换算字典在不同地方 hard-code | 全部通过 MaterialUnitConversionService 调用, 不重复定义 |
| AIChat Tool 跟现有 337 tool 名字冲突 | 起名前 grep `getToolName()` 确认 unique |
| BomRecipe.id 是 String/UUID 而 BomRecipeItem.id 是 BIGSERIAL, mixed key 可能引混乱 | 文档清楚标明, JPA `@Column(name="id", length=191)` + `@PrePersist void assignUUID()` 保护 |
| 跟 Track D2 (工序) 的 product_type_id 共享语义 | 都只读 product_types 字典, 不会冲突; D2 spawn 工序任务时引用 product_type_id, 跟你 BOM 同字段不同表 |

### 11.2 应急联系点

- **任何 blocker** → STATUS 写明, ping Organizer (Chat 1)
- **跑偏方向** → STATUS 写"我想做 X, 但是 Y, 请确认", organizer 反馈
- **碰到 Track A/B/C/D2 文件** → STATUS 写, organizer 协调 (你不擅动)

### 11.3 跟 Track D2 协调

- D2 (Chat 7) 做工序管理 + 工序 bug, 跟你接触面仅是 `product_type_id` 字典共享
- 都通过 `product_types` 表读, 不冲突
- 如果 D2 在 ProductWorkProcessConfigScreen 里用了 product Select, 你 BOM 用的是同一个字典, 两边语义一致即可
- 若发现 D2 改了某共享字段或共享文件, ping organizer 协调

---

## §12 启动 Checklist (Day 0)

接到本 brief 后, 你应该:

1. [ ] 读完整本 brief (10-15 分钟)
2. [ ] 读 SCHEMA_DESIGN.md §2.6 (10 分钟, BOM 部分)
3. [ ] 读六扇门第四次会议 line 200-400 (10 分钟, BOM 部分)
4. [ ] 浏览 `.claude/rules/` 7 个核心规范文件 (10 分钟)
5. [ ] 起 git worktree: `git worktree add ../my-prototype-logistics-track-d1 -b feature/asap-track-d1 HEAD`
6. [ ] 创建 STATUS 文件: `宏见竞品分析/04-最终决策/STATUS/TRACK_D1_STATUS.md` 写 Day 0 onboarding 完成
7. [ ] 找 organizer 报到: "Track D1 收到 brief, Day 1 开始 M-BOM-1 spec 吸收 + 现状 audit"
8. [ ] Day 1 开干

---

**祝顺利。任何疑问 ping Organizer (Chat 1)。**