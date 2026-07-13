# 调料配方按工序 (Per-Process Seasoning Recipe) 设计

**日期**: 2026-07-13
**分支**: `feat/seasoning-per-process` (worktree `cretas-season`, off `origin/main`)
**关系**: 这是 `2026-07-13-bom-raw-material-recipe-page-cleanup-design.md`(Phase 1,已上线)明确切出的 **Phase 2**。Phase 1 修原辅料配方 tab(按 SKU 统计原料/辅料/包材总量);本 spec 处理**调料配方按工序拆**,是独立子系统。

---

## 背景与现状(代码勘查结论)

客户诉求(Steve 逐屏走查 + 澄清):调料配方要**按工序拆**,因为成本要核算到每道工序。不同工序需要不同调料:焯水可能不投料;熟制要投卤料并按锅序算;注射是专门工序,要配"注射多少量 + 注射什么内容(盐水/添加剂)"。报工时记"起了几锅",系统按锅数实时算调料用量。

**关键勘查结论(current main @ 2026-07-13,代码确认):**

1. **工序身份 = `workProcessId`**(`work_processes` 主数据 FK),同时被遗留线性链 `ProductWorkProcess` 和新图 `ProductProcessWorkflow` 共用,运行时任务 `WorkProcessTask` 两种模式都持久化它。**每个 SKU 的熟制是独立 WorkProcess 记录**(五香熟制/红烧熟制/泰式熟制),所以按 `workProcessId` 键控调料**天然区分 SKU 变体**——正是我们要的粒度。图节点 id(`workflowNodeId`)只在图内唯一、跨版本/跨遗留不可移植,**不选它**。
2. **注射/熟制/锅序/第一锅全量+第二锅比例/报工锅数 → 全都已实现并能算**,但:
   - 挂在 **SKU 级**:`BomSeasoningItem`(`recipe_id` FK,`section` = `INJECTION`/`COOKING` 两个固定段,**无 workProcessId**);锅序参数(`subsequentPotRatio`/`cookingPotBaseKg`/`injectionRate`)在 `BomRecipe` **header,一套/SKU**。
   - 成本算法 `RecipeCostCalculator`(数据源中立)已实现"第一锅×1、后续锅×比例"+ 注射/熟制分段,被 clerk/workflow 报工路径复用。
   - 报工识别"这步要记锅数吗"靠**名字正则** `.*(熟\|卤\|煮\|腌\|注射\|入味\|调味).*`(`ClerkProcessEntryServiceImpl.isSeasoningStep` L914-929);web-admin 锅数录入 UI **硬编码 `processCode === 'shuzhi'`**(`ProcessDataTable.vue` L204/L1074)。
3. **三套 seasoning 模型现状**:
   - `BomSeasoningItem`(SKU 级,section,**富成本算法**,clerk 报工路径实际使用)—— 选它做载体。
   - `ProcessMaterialRecipe`(**已按 workProcessId 键控**,但 `unitCost` 扁平、无锅序/注射分段、无 UI,仅 operator yield 路径读)—— 有对的 key、错的数学,不选。
   - 遗留 `ProductRecipe`(clerk 成本 fallback,迁移未完)。
4. **F006 那 8 个卤味产品调料配方现在是 0 行**(实际调料成本一直 ¥0+警告)。**没有生产数据会被改坏**,可放手重构。

**架构决策(hybrid,最小 blast radius):** 把 `BomSeasoningItem` **按 workProcessId 键控**,锅序/注射参数从"SKU header 一套"挪到"每道工序一套",**复用 `RecipeCostCalculator` 现有数学**,把报工锅数识别从"硬编码 shuzhi + 名字正则"改成"读工序的调味类型"。不推倒重建、不迁移到 ProcessMaterialRecipe(会丢锅序数学)。

---

## 设计(5 个组成)

### ① 工序调味类型 —— 复用现有「工序类别」模块(不新造字段)

**决策(Steve):不新增 `seasoningType` 字段,复用现有 `WorkProcess.processCategory`(工序管理页的「工序类别」下拉)。**

- 现有 `processCategory`(`work_processes.process_category`,String)在工序管理页由 `CATEGORIES` 下拉设置,现值:`前处理/加工/包装/灭菌/质检/存储/配送/其他`(`web-admin/src/views/system/work-processes/index.vue:38`)。
- **加两个值:`熟制`、`注射`**(注射是专门工序,Steve 确认)。`CATEGORIES` 列表 + 类型识别都读这里。
- 调味类型从 `processCategory` 派生:`熟制`→熟制型(锅序);`注射`→注射型(注射量+内容);其余(加工等)→普通(只填调料,或不需要留空)。
- 因每个 SKU 熟制是独立 WorkProcess,在工序类别上标一次即可。
- 名字正则(熟\|卤\|煮…)降级为**兜底**(processCategory 未标熟制/注射时的旧行为),不删,避免存量工序回归。

> 注:另有 `ProductWorkProcess.default_cost_category`(RAW_MATERIAL/SEASONING/…成本类)和 `StepEntry.processCategory`(报工携带,"SEASONING")是相邻但不同的字段;本 spec 以 `WorkProcess.processCategory` 为工序类型权威,报工路径需把它透传/对齐(见 ④)。

### ② 调料配方按工序(核心数据 + UI)

**数据模型:**
- `BomSeasoningItem` 加 `work_process_id`(nullable,迁移期兼容)。每条调料明细归属 (SKU-recipe × 工序)。`section` 变为冗余(可由工序类别 processCategory 推导),保留兼容读。
- **每道工序的锅序/注射参数**需要一个 per-(recipe × 工序) 的家。新增轻量表 `bom_process_seasoning`(或等价):`(id, recipe_id, work_process_id, subsequent_pot_ratio, injection_amount_kg, notes)`,一行/（SKU-recipe × 工序）。`BomSeasoningItem` 明细行按 `(recipe_id, work_process_id)` 归组到它。
  - 熟制工序:用 `subsequent_pot_ratio`(第二锅起比例)。
  - 注射工序:用 `injection_amount_kg`(绝对注射量 kg,见 ③)。
- **成本算法复用**:`RecipeCostCalculator.compute(ratio, lines, injectionRawKg, potRawKgs)` 现有签名不变,只是**按工序分别调用**(该工序的 ratio + 该工序的 lines),再跨工序求和。

**UI(调料配方 tab):**
- 打开一个 SKU 的调料配方 → **从该产品激活的 workflow 拉出全部工序(workProcessId 列表)→ 按工序分组列出**。
- 每道工序按其**工序类别(processCategory)**出对的表单:
  - **熟制**:锅序(第二锅比例)+ 调料明细(每种卤料:名称/每kg用量/单价/是否计入成本〔老汤高汤不计〕)。
  - **注射**:注射量(**绝对 kg**)+ **注射内容明细**(盐水/添加剂…,每种注射物 + 用量)。〔Steve 澄清:注射配方 = 配"注射多少量(绝对) + 注射什么内容",内容可配,不是固定物〕
  - **普通(非熟制/注射 但用户想投料)**:只填调料明细。
  - **无需调料的工序**:留空(=0),**不强制**。
- 前置:该产品没建 workflow 工序链 → 空状态提示"先去建工序"(防呆 Rule 5 导航到 workflow 编辑器)。

### ③ 注射配方语义(Steve 澄清)

注射是专门工序。注射配方配两件事:
1. **注射多少量 —— 绝对注射量(kg)**(Steve 确认:按绝对量,不是相对原料重量的率)。存该工序的**绝对注射量**(kg),不用 `injection_rate` 百分比口径。
2. **注射什么内容** —— 注射内容明细(盐水 / 添加剂 …),每种注射物按用量。内容可配,不是固定某种物质。

**成本口径变更(与现有不同,Slice A 需调整)**:现有 `RecipeCostCalculator` 注射段 = `injectionRawKg × Σ(dosage/kg × 单价)`(**按每 kg 原料**)。改为**按绝对注射量**:注射工序成本 = 注射内容明细各自绝对用量 × 单价之和(或 = 绝对注射量 × 内容单位成本),不再乘原料重量。`RecipeCostCalculator` 注射分支相应改造(熟制分支的锅序数学不动)。

### ④ 报工锅数泛化

- 报工识别"这步要记锅数/是调味步"从**硬编码 `shuzhi` + 名字正则**改成**读该工序的工序类别(`processCategory`)**:
  - `processCategory == '熟制'` → 显示锅数录入(几锅 + 逐锅原料 kg)。
  - `processCategory == '注射'` → 显示注射量录入(绝对 kg,无锅数)。
  - 名字正则保留为 processCategory 未标熟制/注射时的兜底。
- 需把 **`workProcessId` 透传进 clerk 报工路径**(现 clerk 路径按 StepEntry 的 processName/Order 认工序,workProcessId 只用于自定义字段校验)。让报工能读到该步的工序类别(processCategory)+ 该步的锅序参数。
- 报工记"起了几锅" → `computeSeasoningCost` 按**该工序的**锅序参数算(见 ⑤)。
- web-admin `ProcessDataTable.vue`:锅数 expander 的 `isShuZhi`(`processCode === 'shuzhi'`)改为按该步工序类别(processCategory=='熟制')驱动。

### ⑤ 成本(computeSeasoningCost 按工序)

- `ClerkProcessEntryServiceImpl.computeSeasoningCost` 现在按 `productTypeId` 读**一套** SKU 级调料。改为:按 **(productTypeId 的 is_current BOM × 该报工步的 workProcessId)** 读**该工序的**调料明细 + 该工序的锅序/注射参数,调 `RecipeCostCalculator`。
- 一次报工只算它那道工序的调料;跨工序天然分摊到各工序成本(满足"成本核算到每道工序")。
- 遗留 `product_recipes` fallback:保留现有兜底(迁移未完),但新路径优先按工序读。

### ⑥ 软提示流程

- workflow 建好/激活后,调料配方页顶部**软提示**:"检测到该产品 N 道工序未配置调料 → 去配置"(不强制)。
- 不需要调料的工序留空 = 0,不阻断。

---

## 三切片(顺序做,每片可单独验证)

### Slice A — 后端模型 + 成本 + 报工识别(数据链跑通)
- **工序类别扩展**:`WorkProcess.processCategory` 复用(已是 String,**无需加列**);工序管理页 `CATEGORIES` 加「熟制」「注射」;类型识别读 processCategory。
- `BomSeasoningItem.work_process_id`(Flyway 加列)+ `bom_process_seasoning`(per-工序 锅序/`injection_amount_kg` 参数表)。
- 迁移:存量 `BomRecipe` header 的 pot 参数 + `section` 明细 → 映射到该 SKU 的熟制/注射工序组(F006 为空,近似 no-op;写迁移但低风险)。
- `computeSeasoningCost` 按工序读(注射改绝对量口径,见 ③);`isSeasoningStep`/锅数识别读 `processCategory`(熟制/注射);`workProcessId` 透传进 clerk 报工路径。
- **验证**:API/脚本造一个带熟制+注射工序的 SKU,配两道工序不同调料,报工两道 → 各工序调料成本按各自锅序/注射算对(不再混成一套)。
- 🔒 **红线**:这片改成本核算逻辑(`computeSeasoningCost`/`RecipeCostCalculator` 调用)+ Flyway 迁移 → Opus 终审 + 从 main 部署。执行者做到 PR+自测停。

### Slice B — 调料配方 tab 按工序 UI
- (工序类别「熟制/注射」下拉已在 Slice A 前端加好)。
- 调料配方 tab 重构:按工序分组 + 按工序类别(读 `processCategory`)出表单(熟制锅序 / 注射量(绝对 kg)+内容 / 普通)+ 软提示 + 空状态导航。
- 复用 Phase 1 的防呆规范(4 位一体错误 / dead-end 导航)。
- **验证**:headed 走查——建 workflow → 调料配方页按工序列出 → 各类型表单正确 → 留空不强制 → 保存。

### Slice C — 报工锅数 UI 泛化
- `ProcessDataTable.vue` 锅数/逐锅原料录入从 `processCode === 'shuzhi'` 硬编码改为按该步工序类别(processCategory)驱动。
- **验证**:headed——一个熟制工序(非 shuzhi 命名)也能出锅数录入;注射工序出注射量;普通工序不出。

---

## 迁移与数据现状

- F006 8 个卤味产品调料 0 行 → Slice A 迁移近似 no-op,风险低。
- `section`(INJECTION/COOKING)保留兼容:新写入按工序类别(processCategory)决定段,旧读兼容。
- 存量 `BomRecipe` header 的 `subsequentPotRatio`:迁移时若 SKU 有唯一熟制工序,搬到对应工序组;多/无则留 header 默认 + 记 warning(不静默丢)。
- 存量 `injectionRate`(旧的百分比率)因口径改绝对量,**不直接搬**成 `injection_amount_kg`;记 warning 提示需人工按绝对量重配(F006 为空,实际无存量)。

---

## 风险 / 碰撞

| 项 | 风险 | 缓解 |
|---|---|---|
| Slice A 改 computeSeasoningCost | 🔒 成本口径,算错影响财务 | 复用现有 RecipeCostCalculator 数学(不重写);对抗审计;Opus 终审 |
| Flyway 迁移 | 跨 session 撞号 / prod schema | 从 main 部署;号用 `V<今天>_NN`;runner 校验 |
| 前端 collision | 调料配方 tab 与 Phase 1 同处 `bom/index.vue`/`ProductRecipeView.vue` | Phase 1 已 merge;Slice B 前 `git status` 确认无并发 |
| clerk 报工透传 workProcessId | 改报工路径,回归风险 | 保留名字正则兜底;单产品 headed 回归 |
| workflow 工序链依赖 | raw-centric 多SKU 仍在演进 | 键控 workProcessId(两模型共用),不依赖图节点 id |

## 验收

- 一个带 熟制 + 注射 两类工序的 SKU:各工序配不同调料,报工两道 → 各工序调料成本按各自锅序/注射参数分别算对,汇总到各工序成本。
- 调料配方 tab 按工序分组,按类型出表单;不需要的工序留空=0 不阻断;没 workflow 时导航去建。
- 报工锅数录入不再依赖 `shuzhi` 命名,按工序类别(processCategory)驱动。
- 通篇防呆:错误 4 位一体 + dead-end 导航;headed(zh-CN,1920×1080)截图存档。
- 不破坏:原辅料配方(Phase 1)、operator yield 路径(ProcessMaterialRecipe)、遗留 product_recipes fallback。

---

## 明确不做(边界)

- **不迁移到 ProcessMaterialRecipe**(丢锅序数学);它继续服务 operator yield 路径不动。
- **不删 product_recipes fallback**(BOM 统一迁移未完,归 那条线)。
- **不改 workflow 图/运行时编译器**(只读它拉工序列表)。
- 人工费用、原辅料配方(Phase 1)不碰。
