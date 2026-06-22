# 文员逐道工序录入 + 配方 BOM + 成本核算引擎 — 设计 Spec

**日期**: 2026-06-22（v2，已纳入对抗性审计修订）
**触发**: 六膳门(LIUSHANMEN) 和牛(M6-7)牛腱 v9.0 真实业务。客户(张权) 微信确认了调料配方/锅序规则；Steve 拍板「生产级通用、完整多对多 DAG、严格防呆、一步到位」。
**租户边界**: 功能做进 web-admin 通用产品(所有制造业租户)；**构建+测试只用 `DEMO_FACTORY`**，**绝不碰 F006/六膳门(LIUSHANMEN) 真客户租户**。
**关联规范**: `.claude/rules/fool-proof-design.md`、`database-entity-sync.md`、`field-naming-convention.md`、`worktree-and-main-only-deploy.md`。

---

## 0. 审计修订记录（v1→v2）

v1 经 3 路对抗性审计，修正以下承重墙级错误（证据均验于 origin/main + demo seed）：

| # | v1 错误 | v2 修正 |
|---|---|---|
| C1 🔴 | 以为成本引擎遍历 `source_batch_refs`/`batch_relations` | **引擎实际只递归 `MaterialConsumption`+`MaterialBatch.source_doc_type='PRODUCTION_BATCH'`**（seed 已证明）。SP-B 结单时把 DAG 边**物化成 MaterialConsumption + MaterialBatch 行**，引擎零改动。`source_batch_refs` 仅留作血缘/出成率展示 |
| C2 🔴 | 拆分(1→多) traceCost 整额累加父成本 → 重复计 | **拆分子批 consumption 的 `total_cost` 在写入时按重量预分摊**（父成本×子投入/父产出），定为不变量 + diamond 测试 |
| C3 🔴 | `factory_configs` key-value 表(不存在) | 新建轻量 `factory_cost_settings`(factory_id+key+value) 放工时单价 |
| C4 🔴 | 裸 Map + 关键词正则脱敏 → 原料/人工/调料/包装明文泄漏；且现有端点用 procurement gate | 返回**强类型 DTO + `@PriceSensitive(permission="finance:read_write")`**；两处 gate 统一为 finance |
| C5 🔴 | `/production/batches/{batchNumber}/...` 无 factoryId 段 → 拦截器守卫不触发 | 端点必带 `/{factoryId}/...` + `findByFactoryIdAndBatchNumber` + assertSameFactory；**禁用** `@Deprecated findByBatchNumber`(全局唯一会泄漏) |

应改(澄清)：人工写入时持久化、R 锚定、submitReport 幂等、用现有 `production:read_write` 角色、boxCount=0 诚实报错、防呆 Rule1/5、Flyway 号预留——均已落入下文。

---

## 1. 背景与问题

六膳门做卤味，业务两种：**以销定产**(订单来才买料) 与 **存货生产**(预生产做库存，开工时盒数不定，无需关联销售订单)。每批用库存里不同批次、不同价的原料 → 每批成品成本不同；客户 Excel(XLOOKUP)只能追一条链，且一断全 `#DIV/0!`/`#N/A`。

真实生产是**多对多 DAG，拆分+跨血缘混锅**：滚揉拆多焯水(1→多)、多血缘焯水汇进熟制锅(多→1)、多熟制汇进气调；焯水出成率波动最大 → 成品产出浮动。

客户要：车间看各工序出成率+人工时长；经营看单盒成本拆分。报工**先 PC 网页端文员集中录入**(不走操作员 APP)。

**现状缺口**(grep 验于 origin/main)：
- 核对结单(`POST /production-plans/{planId}/settle`，`web-admin/src/views/production/plans/list.vue`)是**纯两点汇总**，无逐道工序录入。
- 逐道报工模型齐全：`YieldReportService.submitReport(factoryId,batchId,workerId,YieldReportRequest)` 三阶段(INPUT/SEGMENT/OUTPUT via `reportKind`)写 `production_reports`(含 `source_batch_refs`/`byproducts`/`labor_segments`/`sample_retain_quantity`/`packaging_detail`)。
- **成本引擎** `OrderCostBreakdownService.traceCost` 递归 `MaterialConsumption` + `MaterialBatch.source_doc_type='PRODUCTION_BATCH'/source_doc_id`（**非** source_batch_refs），`compute(factoryId,orderId,maskPrice)` 出 原料/人工/调料/包装/单盒/副产/可售。
- 出成率 `getOrderYieldSummary` 按 `orderId→ProductionPlan.source_order_id→ProductionBatch`；存货生产无订单号 → 核算页空(已实测)。
- **无配方建模；无工时单价配置**。

---

## 2. 范围与拆解(3 子项目)

| 子项目 | 内容 | 依赖 |
|---|---|---|
| **SP-A 配方 BOM + 调料成本算法** | 配方实体(注射+熟制+锅序规则) + 成本纯函数 + web-admin 配方维护页 | 无 |
| **SP-B 文员逐道录入 + DAG 物化** | 核对结单→逐道工序录入；结单时把每道投入/产出/人工成本/混锅来源**物化成 `production_reports` + `MaterialConsumption` + `MaterialBatch(source_doc_type='PRODUCTION_BATCH')` 行**(引擎读的图)；拆分按重量预分摊 | 无 |
| **SP-C 核算引擎升级** | 核算页双模式 keying(订单号/批次号，带 `{factoryId}`)；强类型成本 DTO + finance 脱敏；接入配方/注射/人工/DAG → 单盒成本 | 依赖 A+B |

实现顺序：**A、B 并行先做 → C 收口**。Flyway 号由 organizer 集中预留(见 §7)。

---

## 3. SP-A — 配方 BOM + 调料成本算法

### 3.1 数据模型（继承 BaseEntity；列 snake_case / Java camelCase）

**`product_recipes`(1 SKU 1 条)**：`id`、`factory_id`、`product_type_id`(唯一(factory_id+product_type_id))、`name`、`injection_rate`(注射率 0.20)、`cooking_pot_base_kg`(每锅基准 160)、`subsequent_pot_ratio`(第二锅起比例，默认 0.3333**可配**)、`status`、`version`。
> 删除 v1 的 `injection_base_g`(死配置，注射成本按每kg生料算，基准重只是 Excel 推导用，不入库)。

**`recipe_ingredients`**：`id`、`recipe_id`、`factory_id`、`section`(枚举 `INJECTION`/`COOKING`)、`seq`、`name`、`dosage_per_kg_g`(每kg原料用量g)、`price_source1`、`price_source2`(可空)、`count_in_seasoning`(bool，老汤=false)、`remark`。最高单价 = `max(源1,源2)` 运行时算，不落库。

### 3.2 调料成本纯函数（BigDecimal，HALF_UP；供 SP-B 写入时调用）

**R 锚定（v2 关键修正）**：
- **注射 R = 注射前生料投入重**(滚揉/生料阶段 kg，即 307kg 级)，**不是**滚揉注水后的产出。注水增重只影响下游出成率，不改注射成本基数。
- **熟制 R = 熟制工序总投入 kg**(= Σ 上游焯水投料 kg)。
两个 R 不同源，不可共用。

```
注射/kg      = Σ(INJECTION: dosage_g/1000 × max(单价1,单价2))            # 每锅同量
熟制/kg(全量) = Σ(COOKING ∧ count_in_seasoning: dosage_g/1000 × max(单价1,单价2))

注射总  = R注射 × 注射/kg
熟制总  = Σ_{i=1..N} 锅i原料 × 熟制/kg(全量) × (i==1 ? 1 : subsequent_pot_ratio)
调料成本合计(绝对¥) = 注射总 + 熟制总
```
- 老汤(`count_in_seasoning=false`)不进 Σ；`ratio` 读配方；锅序只作用于熟制。
- **逐锅原料**：N>1 时**要求文员逐锅录 kg**（不默认等分——首锅全量×1，错配首锅 kg 最贵）；仅当文员选「等分」时才 `r_i=R/N` 并显式提示。
- 自检：每kg原料 注射¥0.24、熟制全量¥0.31、第二锅起¥0.10 → 合计第一锅¥0.55/第二锅起¥0.34；N=1,R=160→¥88（汇总页 88.38）✓。

### 3.3 SP-A → 引擎的接线（v2 新增，关键）
SP-A 算出的**绝对¥调料成本**，由 SP-B 在结单时**写入熟制工序对应的成本数据**——具体作为该熟制批的一条 `MaterialConsumption`(source_type='SEASONING'，total_cost=调料合计) 或持久到报工 `material_cost`，使 `OrderCostBreakdownService` 的调料桶能读到。**配方只存规则，锅数 N 与逐锅 kg 是录入事实(§4.4)。**

### 3.4 web-admin 配方维护页
路由 `/system/product-recipes`(`module:'production'` 防 demo 策展隐藏)；注射段+熟制段表格(可标「不计入调料」)+第二锅起比例。防呆：单价两源至少一个、0<ratio≤1、同 SKU 不能两份 ACTIVE 配方。

---

## 4. SP-B — 文员逐道录入 + DAG 物化（录入方案1）

### 4.1 入口
核对结单 → 逐道报工计划开「逐道工序录入」面板(大抽屉/独立页)；免工序报工仍走两点汇总。存货生产计划录完结单 → **必须生成带 `batch_number` 的 `ProductionBatch`(PB-)且写 `MaterialConsumption` 行**（供 SP-C 按批次号查 + 引擎遍历）。
> v2 注意：`SAFETY_STOCK` 是 `PlanSourceType`(计划来源)，与 `createSecondaryPlan`(planSourceType=SECONDARY，WIP 二次加工)是两套机制，勿混。结单自动入库生成的是 `FinishedGoodsBatch`(成品库存)，但**核算/引擎键在 `ProductionBatch`**——SP-B 必须确保存货生产链产出 ProductionBatch + MaterialConsumption，否则核算空。

### 4.2 操作单元 = 批次链
半成品链(原料→修油→滚揉→焯水)=WIP 批次；成品链(熟制→气调→包装)=成品批次。

### 4.3 每道工序卡字段
投入重量、产出重量、人工(开始/结束时间+人数→自动算 总工时=时长×人数)、副产(byproducts[])、损耗(waste)、留样(末道)。

### 4.4 两类特殊工序
- **拆分(滚揉→多焯水)**：产出拆 N 个半成品批，各自重量+批号；**每个子批的下游成本切片在物化时按 `子投入/父产出` 预分摊**(C2)。
- **合并(熟制)**：①「上游来源选择器」多选已录焯水批(下拉 批号·品名·日期)+各自投料 kg；②录「锅数 N」+逐锅原料 kg(N>1 必填)。

### 4.5 写路径 + 物化（v2 核心修正）
复用 `submitReport`(INPUT/SEGMENT/OUTPUT)写 `production_reports`(`recordedBy=文员 token userId` 显式传入，**禁用 SecurityUtils**；代他人报工用 `targetWorkerId`)。
**结单时额外物化引擎可读的成本图**：每条混锅来源 → 写 `MaterialConsumption`(production_batch_id=本批, batch_id=上游批, quantity=投料kg, **total_cost=按重量预分摊的上游成本切片**) + 维护上游 `MaterialBatch.source_doc_type='PRODUCTION_BATCH'/source_doc_id`。`source_batch_refs` 仍写(血缘/出成率展示)，但**成本图以 MaterialConsumption 为准**。
- **不变量**：拆分子批成本 = 预分摊切片，引擎(traceCost 向上求和)永不再分；加 diamond 测试(1 父→2 子→2 锅，断言父成本只计一次)。
- **幂等**(C2/补)：整次文员录入包**一个幂等事务**(idempotencyKey)；或 `(batchId, processStage)` 唯一约束防 per-process 双写(submitReport 本身无去重)。

### 4.6 防呆（四位一体，遵 fool-proof-design.md）
- **Rule 1（v2 修正：预先 max，非事后预警）**：每道投入框 `:max=上道产出`，超限**禁用提交**+显「上道产出 X，可投 ≤X」；**注水(滚揉)工序例外**允许产出>投入(>100%)。
- **Rule 2**：卡头带 品名/批号/工序名/计划数量。
- **Rule 3**：损耗/异常原因 dropdown+其他。
- **Rule 4**：幂等(见 §4.5)+熟制「投入=Σ上游投料」容差校验。
- **Rule 5（v2 新增）**：工时单价未配 → 错误带「去配置工时单价」跳转按钮；来源选择器为空 → EmptyState「先录上游批次」+动作；非死胡同。
- error toast sticky(duration:0+showClose)，message=后端真因+next action。

### 4.7 权限/业态（v2 修正）
制造业态；鉴权用 `@RequirePermission`(**非 `@PreAuthorize`**，本项目 method-security 未启用=NO-OP)。
- 写端点 `@RequirePermission({"production:read_write"})`，读(yield-summary)`{"production:read"}`。
- **不新造「文员」角色**：文员入口面向已有 `production='read_write'` 的角色(dispatcher/workshop_supervisor)。`production:write`(只写 `'w'`)会 403 读侧(L1 DB 教训)——不用。若六膳门坚持要独立文员角色，Flyway 在 L1 表加角色并授 `production='rw'`。

---

## 5. SP-C — 核算引擎升级

### 5.1 双模式 keying（v2 修正 tenant）
- 订单号(现有保留)。
- 批次号(新)：端点 **`GET /api/mobile/{factoryId}/production/batches/{batchNumber}/yield-summary`**(带 factoryId 段，拦截器守卫才触发) + `getBatchYieldSummary(factoryId,batchNumber)`，service 用 **`findByFactoryIdAndBatchNumber`** + `assertSameFactory`(404 on mismatch)；**禁用** `@Deprecated findByBatchNumber`(全局唯一→跨租户泄漏)。
- 核算页加查询模式切换(订单号/批次号)。

### 5.2 单盒成本（让展示页由真实录入驱动）
- **原料**：`OrderCostBreakdownService.traceCost` 遍历 `MaterialConsumption`+`MaterialBatch(source_doc_type)`(SP-B 已物化，预分摊)→ 65.7:34.3。
- **人工（v2 修正）**：SP-B 写报工时即把 `labor_cost = (total_work_minutes/60 × total_workers) × 工时单价` 算好持久化；引擎读持久化 `totalLaborCost`(不动引擎)。
- **调料**：读 SP-B 物化的 SEASONING consumption(SP-A 算法产出，§3.3)。
- **包装**：末道 `packaging_detail`(膜/气体/标签/其他)。
- **副产回收**：byproducts×单价冲减(单价 null → 不臆造，credit 0)。
- **留样**：可售盒=总盒−留样；可售单盒=净成本÷可售盒(全留样→退化按净单盒)。
- **boxCount=0（v2 修正）**：若成本>0 但盒数未录 → 诚实返「盒数未录入，请先完成包装结单」，**禁返 ¥0 假数据**。

### 5.3 工时单价配置（v2 修正存储）
新建 `factory_cost_settings`(`factory_id`,`setting_key`,`setting_value`,审计)，key=`labor_hour_rate`。**未配置→明确提示，不默认 0、不返假数据**。值按工厂配置(非 prod-wide Flyway INSERT)。

### 5.4 输出 DTO（强类型，= 现有展示页结构）
原料/人工/调料/包装(各 `@PriceSensitive(permission="finance:read_write")`)+占比+副产回收+留样+单盒净成本+可售单盒+逐道出成率+整批出成率。车间视图额外显**逐批出成率分布**(min/max/spread)，不只 pooled(客户关心波动)。

### 5.5 🔒 红线（必经 Opus 终审）
- **价格脱敏（v2 修正）**：成本字段走**强类型 DTO + `@PriceSensitive(permission="finance:read_write")`**，统一 finance gate；**不返裸 Map**(关键词正则不匹配 原料/人工/调料/包装 → 泄漏)。复用的 `OrderCostBreakdownService` 也要把 gate 从 `shouldMaskPrice`(procurement) 改齐 `shouldMaskOperationalCost`(finance)，并核对 advice 是否尊重 `@PriceSensitive` 的 permission 覆盖。
- **多租户**：见 §5.1，by-batch 必带 factoryId 段 + findByFactoryIdAndBatchNumber + assertSameFactory；平台角色 by-design 跨厂。

---

## 6. 测试策略
- **SP-A**：调料纯函数单测(N=1/2/3、ratio 自定义、老汤排除、取最高、逐锅 vs 等分、注射 R=生料重)，对齐 0.55/0.34/88.38。
- **SP-B**：录入→物化 `production_reports`+`MaterialConsumption`+`MaterialBatch(source_doc_type)` 集成测；**diamond 拆分测(父成本只计一次)**；防呆(max 禁提交/注水例外/来源合计/幂等双击);`recordedBy` 非 null;labor_cost=工时×单价持久化。
- **SP-C**：双模式 keying;DAG 2 源混锅 65.7:34.3;人工读持久化;boxCount=0 诚实报错;**跨租户拒绝单测**(F006 查 DEMO 批次→404,安全探针: 他厂id+不存在批号→404=安全);**脱敏单测**(非财务角色 原料/人工/调料/包装 key absent;财务见值)。
- **E2E(DEMO_FACTORY headed)**：文员录一含**真拆分+混锅**的存货生产批次→结单→按批次号查→出成率/单盒/拆分正确。**绝不用现 seed**(它预聚合叶子、滚揉 mc=0、熟制 mc 硬编码、盒数预置——掩盖 C1/C2/注射/盒数0 全部危险路径)。**绝不碰 F006/LIUSHANMEN**。

---

## 7. 迁移与部署
- 新表 `product_recipes`/`recipe_ingredients`/`factory_cost_settings` 的 Flyway 迁移号**由 organizer 集中预留一段**，SP-A/SP-C 不各自取「下一号」(并行撞号→Spring "more than one migration"→阻断所有部署)；migration-bearing PR 串行合并。
- 🔒 schema 走 deploy 脚本，prod 只从 main 部署。工时单价值/演示数据**只 INSERT DEMO_FACTORY**，不进 prod-wide migration。
- M67 demo seed 升级补 `product_recipes`/`recipe_ingredients` 自包含(延续 DF_pt10 修复)。

---

## 8. 非目标(YAGNI)
不做可视化 DAG 画布;不动操作员 APP 报工(已存在);不做经营/销售汇总页(张权「展示页先不动」);不接实际工资/考勤(工时单价全厂统一);不做配方版本历史(换 BOM-SKU 绑定即可)。

---

## 9. 已定决策（原开放项已收口）
- 熟制逐锅原料：N>1 必填逐锅 kg，等分仅作显式可选 fallback。
- 工时单价存 `factory_cost_settings`(新表)。
- 文员入口复用 `production:read_write` 角色，不新造角色。
- 成本图以 `MaterialConsumption` 为准，`source_batch_refs` 仅血缘展示。
