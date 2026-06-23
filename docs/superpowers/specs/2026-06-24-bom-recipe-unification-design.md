# BOM 统管配方+锅序 — 数据模型合并设计 (Design Spec)

**日期**: 2026-06-24
**作者**: Opus organizer (with Steve, 基于张权 6/22 聊天定的结构)
**状态**: Draft → 待 Steve review
**触发**: 张权 review 后:「bom 和配方还有那个锅序规则…其实应该都是 BOM 的」。Steve 拍板「排进去」做 spec。
**前置**: SP-A 配方 (`product_recipes` + `recipe_ingredients` + `RecipeCostCalculator`) 已上线 prod;BOM 子系统 (`bom_recipes`/`bom_recipe_items`/`bom_versions`/ECN) 已存在;SP-F 逐工序录入 (`materializeBatch.computeSeasoningCost`) 依赖配方算调料成本。

---

## 1. 背景 — 张权定的结构 (6/22 聊天)

客户 (张权) 跟 Steve 谈定的产品配料模型:

- **一个 BOM 绑定一个 SKU** (`就是一个 bom 绑定一个 sku`)。
- **BOM 包含 = 原辅料 + 注射配方 + 熟制配方**,熟制配方里含**锅序规则** (`一个 bom 规则里面可以包含注射和熟制的配方，然后可以添加一个锅序配方在熟制配方里面`)。
- **锅序规则**: 第一锅全量,第二锅起 = 第一锅料 × 比例 (默认 1/3),第三锅同第二锅。即 **调料成本 = 第一锅 + (n-1)锅 × 比例**;比例 per-SKU 可调 (`不同品 比例不一样`);**老汤** (基础高汤) 可一直复用、不计成本 (`老汤基本可以一直用`,`是新汤的 3/1`)。
- **录入端只录「这次熟制几锅」**,系统按第一锅全量推算 (`录入数据只要录入这一次熟制上了几锅，就按照第一锅的全量的投入去推算后面的`)。
- 调整配方 = 换 BOM↔SKU 绑定 (`要调整的话就去换 bom 和 sku 的绑定`)。

**关键事实**: **这套锅序+调料成本逻辑已经实现且 prod 验证** —— SP-A 的 `RecipeCostCalculator` (第一锅全量+(n-1)×比例,比例可调,`countInSeasoning=false` 的老汤不计),prod 实测 0.55/0.34 对上张权 Excel v9.0。**逻辑没问题,本 spec 不改算法,只改「存哪」。**

---

## 2. 现状 — 两个子系统,都按 SKU,但分开

| | 配方 (SP-A) | BOM 子系统 |
|---|---|---|
| 实体/表 | `product_recipes` + `recipe_ingredients` | `bom_recipes` + `bom_recipe_items` (+ `bom_versions` / ECN / price-adjust) |
| 内容 | 注射配方 + 熟制配方(调料)+ 锅序参数 (`cookingPotBaseKg`, `subsequentPotRatio`, `injectionRate`) + ingredient(section=INJECTION/COOKING, dosagePerKgG, price1/2, countInSeasoning) | 原辅料(raw+aux+packaging)BOM 行(materialTypeId, standardQuantity, yieldRate, unit, unitPrice, taxRate)+ 出成率估算 + 成本汇总 + **版本控制/ECN/调价审计** |
| 键 | productTypeId (1 SKU 1 配方) | productTypeId (1 SKU 1 BOM) |
| 成熟度 | 简单、无版本 | 丰富、有版本/审批/ECN |
| 谁读 | `RecipeCostCalculator` + `materializeBatch.computeSeasoningCost` (SP-F 逐工序录入算调料成本) | BOM 成本 rollup / 达成率分析 |
| 前端 | (已并入 BOM 页「调料配方」tab — 6/24 step 1 UI 整合) | `bom-unified` 页 |

**差距只在「分开存 vs 一个 BOM 统管」,不在逻辑。**

---

## 3. 目标

**BOM 成为一个 SKU 的唯一配料容器**:原辅料 + 注射配方 + 熟制配方(含锅序规则)全部归属 BOM。配方不再是独立实体,而是 BOM 的子结构。调料成本/锅序计算从 BOM 读。

---

## 4. 设计决策 (含我推荐 + 待 Steve 确认的 fork)

### 决策 1 — 合并方式: 折叠进 BOM,弃用 product_recipes (推荐)
**Fork**:
- **A (推荐)**: 把注射/熟制 ingredient + 锅序参数**折叠进 BOM 模型** —— `bom_recipes` 加 `cooking_pot_base_kg`/`subsequent_pot_ratio`/`injection_rate` 列;新增 `bom_seasoning_items` 表(或扩 `bom_recipe_items` 加 `item_kind` 区分 MATERIAL/INJECTION/COOKING)存调料 ingredient。迁移 `product_recipes` 数据进 BOM,**弃用** `product_recipes`/`recipe_ingredients`(保留只读一段时间供回滚)。
- **B (轻量)**: 保留 `product_recipes`,BOM `1:1` 引用它(`bom_recipes.product_recipe_id`)。改动小,但**没真合并**(张权要「都是 BOM」,B 只是关联)。
- **推荐 A**: 张权明确要「都是 BOM」;A 让配方天然继承 BOM 的版本/ECN(配方改动也版本化,是好事)。代价是真迁移。

### 决策 2 — 调料 ingredient 存哪 (A 内的子选择)
- **A1 (推荐)**: 新表 `bom_seasoning_items`(bom_recipe_id FK, section INJECTION|COOKING, name, dosage_per_kg_g, price_source_1/2, count_in_seasoning)—— 跟原辅料 `bom_recipe_items` 分表,语义清晰(调料≠原辅料,计算路径不同)。
- A2: 扩 `bom_recipe_items` 加 `item_kind` —— 一表混存,省一张表但语义糊。
- **推荐 A1** (调料和原辅料计算/含义不同,分表干净)。

### 决策 3 — 锅序参数归属
`cooking_pot_base_kg` / `subsequent_pot_ratio` / `injection_rate` 直接做 `bom_recipes` 的列(per-SKU 一套,跟张权「一个 bom 一个 sku」一致)。比例可调即改这列。

### 决策 4 — 成本计算读路径改造 (🔒 不可回归)
`RecipeCostCalculator` 和 SP-F `materializeBatch.computeSeasoningCost` 现在从 `product_recipes` 读 → 改从 BOM 模型读(`bom_recipes` 锅序参数 + `bom_seasoning_items`)。**算法一字不改**(第一锅+(n-1)×比例,老汤不计),只改数据源。**必须回归测**:同一 SKU 迁移前后 `computeSeasoningCost` 输出逐分吻合(SP-A 0.55/0.34 不变)。

### 决策 5 — API + 前端
- 配方 CRUD 端点 (`/product-recipes`) → 并入 BOM 端点(`/bom/recipes/{id}/seasoning` 或在 BOM 创建/编辑 payload 里带 seasoning+锅序)。
- 前端「调料配方」tab(step 1 已建)改为编辑 BOM 内嵌的配方,而非独立 ProductRecipe。

---

## 5. 数据迁移 (🔒 真客户现网数据)

**现网有真数据**: F006(测试)+ **LIUSHANMEN(真客户)** 都有 `product_recipes` + `bom_recipes`(都按 SKU)。迁移:

1. Flyway: 建 `bom_seasoning_items` + `bom_recipes` 加锅序列。
2. 迁移脚本: 对每个 `product_recipes` 行,按 `(factory_id, product_type_id)` 找/建对应 `bom_recipes`,把锅序参数写进 BOM 列、ingredient 写进 `bom_seasoning_items`。
   - **冲突处理**: 若某 SKU 有 product_recipe 但无 bom_recipe → 建一个 BOM(只含配方,原辅料空)?还是要求先有 BOM?需决策(见开放问题)。
   - **幂等 + 可回滚**: `product_recipes` 不删(保留只读),迁移记录可追溯。
3. 切换: 成本计算读路径切到 BOM 后,**灰度** —— 先 test 环境迁移+验证,再 prod。
4. 验证: 迁移后对每个 SKU 跑 `computeSeasoningCost` 前后对比,0 差异才算成功。

**风险**: 迁移错→真客户调料成本算错→单盒成本错→经营数据错。**必须 Opus 终审迁移脚本 + 真 PG 验证 + 灰度。**

---

## 6. 风险 / 红线

| 红线 | 为什么 🔒 |
|---|---|
| **真客户 (LIUSHANMEN) 配方数据迁移** | 迁错→成本算错。Opus 终审 + 真 PG 灰度 + 前后对比 0 差异 |
| **成本计算无回归** | SP-A 0.55/0.34 是对上客户 Excel 的;materializeBatch 依赖它;改读路径不能动算法 |
| **SP-F 依赖** | 逐工序录入的调料成本走 computeSeasoningCost;读路径切换要同步,不能断 |
| **BOM 版本/ECN 交互** | 配方进 BOM 后受版本控制;要确认配方改动走 BOM 的版本/审批流是否符合预期 |
| **Flyway 迁移** | 并发 session 撞号(已多次踩);出 PR 前查重号 |

---

## 7. 开放问题 (Steve 定)

1. **没有 BOM 但有配方的 SKU 怎么办** — 迁移时自动建空 BOM,还是要求先建 BOM?(影响迁移脚本 + 业务流程)
2. **配方进 BOM 后是否要走 BOM 的版本/审批/ECN** — 改个调料比例是否要审批?还是配方部分免审批?(影响 UX + 数据模型)
3. **product_recipes 何时真删** — 保留只读多久?(回滚窗口)
4. **决策 1 选 A(真折叠)还是 B(引用)** — 我推荐 A,但 A 工作量+迁移风险大;B 快但不算真「都是 BOM」。

---

## 8. 分发卡

| # | 任务 | 模型 | 🔒 |
|---|---|---|---|
| U1 | Flyway: `bom_seasoning_items` + `bom_recipes` 加锅序列 | Sonnet | 🔒 (schema) |
| U2 | BomRecipe 实体/repo 扩 + `BomSeasoningItem` 实体 | Sonnet | |
| U3 | **迁移脚本** product_recipes → BOM (幂等+可回滚) | **Opus** | 🔒🔒 真客户数据 |
| U4 | **RecipeCostCalculator + materializeBatch 读路径切 BOM** (算法不变) + 前后对比回归测 | **Opus** | 🔒 成本无回归 |
| U5 | API 并入 BOM (配方 CRUD → BOM payload) | Sonnet | |
| U6 | 前端「调料配方」tab 改编辑 BOM 内嵌配方 | Sonnet/Composer | |
| U7 | 灰度: test 迁移+验证 → prod 灰度 + 前后对比 | Opus 终审 | 🔒 出货闸 |

依赖: U3/U4 是核心(数据+计算),Opus 自做;U1/U2 先行;U5/U6 跟上;U7 最后灰度。

---

## 9. 我的建议 (organizer)

**这是真重构 + 真客户数据迁移,不要急。** SP-A 的锅序/调料逻辑已经对了(prod 验证),所以**这次合并是「搬家」不是「重写算法」** —— 价值是统一到 BOM(张权要的),风险全在**迁移 + 读路径切换不能让真客户成本回归**。

建议节奏: U1/U2 schema+实体 → U3 迁移脚本(Opus,真 PG 反复验)→ U4 读路径切换(Opus,前后对比 0 差异)→ U5/U6 API+UI → U7 test 灰度再 prod。**全程对 SP-A 现有成本输出做回归基线,任何 SKU 算出不一样就停。**

先请 Steve 定 §7 的 4 个开放问题(尤其决策 1 的 A vs B),再进 writing-plans。
