# 文员逐道工序录入 + 配方 BOM + 成本核算引擎 — 设计 Spec

**日期**: 2026-06-22
**触发**: 六膳门(LIUSHANMEN) 和牛(M6-7)牛腱 v9.0 真实业务。客户(张权) 微信确认了 调料配方/锅序规则；Steve 拍板「生产级通用、完整多对多 DAG、严格防呆、一步到位」。
**租户边界**: 功能做进 web-admin 通用产品(所有制造业租户)；**构建+测试只用 `DEMO_FACTORY`**，**绝不碰 F006/六膳门(LIUSHANMEN) 真客户租户**。
**关联规范**: `.claude/rules/fool-proof-design.md`、`.claude/rules/database-entity-sync.md`、`.claude/rules/field-naming-convention.md`、`.claude/rules/ai-intent-tool-skill-architecture.md`、`.claude/rules/worktree-and-main-only-deploy.md`。

---

## 1. 背景与问题

六膳门做卤味(和牛牛腱等)，业务有两种：
- **以销定产**：订单来了才买料，订单结束料用完。
- **存货生产(make-to-stock)**：提前预生产做库存(叮咚)，**开工时盒数不确定**，**无需关联销售订单**。

每批用库存里**不同批次、不同价**的原料 → 每批成品成本不同。客户 Excel 用 XLOOKUP 只能追一条链，追不动多批次原料成本，且公式一断全列 `#DIV/0!`/`#N/A`。

真实生产是**多对多 DAG，拆分+跨血缘混锅**：
- 一个 `滚揉` 批拆成多个 `焯水` 批（1→多）
- 多个不同原料血缘的 `焯水` 批汇进一个 `熟制` 锅（多→1）
- 多个 `熟制` 批汇进 `气调`
- `焯水` 出成率波动最大 → 成品产出浮动

客户要的是：**车间看各工序出成率 + 人工时长；经营看单盒成本拆分(原料/人工/调料/包装)**。报工**先在 PC 网页端由文员集中录入**(不走现场操作员 APP)，对当下六膳门更高效。

**现状缺口**(已 grep 验证 origin/main)：
- `web-admin/src/views/production/plans/list.vue` 的「核对结单」(`submitComplete()` → `POST /production-plans/{planId}/settle`) 是**纯两点汇总**(成品产量+原料领用+人数+工时)，**无逐道工序录入**。
- 逐道报工数据模型**已齐全**：`YieldReportService.submitReport` 三阶段(INPUT 领料→SEGMENT 工时→OUTPUT 产出) 写 `production_reports`，含 `source_batch_refs`(混锅来源边)、`byproducts`、`labor_segments`、`sample_retain_quantity`。
- **成本/出成引擎按订单号找批次** (`orderId → ProductionPlan.source_order_id → ProductionBatch`)。存货生产无订单号 → 核算页空(已实测复现)。
- **调料/注射成本无配方建模**。
- **人工无工时单价配置**。

---

## 2. 范围与拆解(3 子项目)

| 子项目 | 内容 | 依赖 |
|---|---|---|
| **SP-A 配方 BOM 维护 + 成本算法** | 配方实体(注射配方+熟制配方+锅序规则) + 配方成本纯函数 + web-admin 配方维护页 CRUD。主数据，可独立先做。 | 无 |
| **SP-B 文员逐道录入 + DAG capture** | 核对结单 → 逐道工序录入面板；熟制「上游来源选择器」写 `source_batch_refs`；复用 `submitReport` 写路径。 | 无(写已有模型) |
| **SP-C 核算引擎升级** | 核算页双模式 keying(订单号/批次号)；成本引擎接入 配方成本+注射+人工(工时单价)+DAG 原料血缘 → 单盒成本拆分+副产回收+留样。 | **依赖 A+B** |

建议实现顺序：**A 与 B 并行先做 → C 收口集成**。每个子项目各自 plan → 实现。

---

## 3. SP-A — 配方 BOM 维护 + 调料成本算法

### 3.1 数据模型

继承 `BaseEntity`(created_at/updated_at/deleted_at)。命名遵循 `field-naming-convention.md`(Java camelCase / 列 snake_case / JSON camelCase)。

**`product_recipes`(配方头，1 SKU 1 条，设置一次绑定)**
| 列 | 类型 | 说明 |
|---|---|---|
| id | varchar PK | |
| factory_id | varchar | 租户 |
| product_type_id | varchar | **唯一(factory_id+product_type_id)**，绑定 SKU |
| name | varchar | 配方名 |
| injection_base_g | numeric | 注射基准原料重(g)，如 3990 |
| injection_rate | numeric | 注射率，如 0.20 |
| cooking_pot_base_kg | numeric | 熟制每锅基准原料(kg)，如 160 |
| subsequent_pot_ratio | numeric | **第二锅起比例，默认 0.3333，可配 per-SKU** |
| status | varchar | ACTIVE/INACTIVE |
| version | int | 乐观锁 |

**`recipe_ingredients`(配方明细)**
| 列 | 类型 | 说明 |
|---|---|---|
| id | varchar PK | |
| recipe_id | varchar FK | |
| factory_id | varchar | 冗余便于租户过滤 |
| section | varchar | 枚举 `INJECTION` / `COOKING` |
| seq | int | 序号 |
| name | varchar | 料名(盐/冰水/八角/高汤…) |
| dosage_per_kg_g | numeric | 每kg原料用量(g) |
| price_source1 | numeric | 单价来源1(元/kg) |
| price_source2 | numeric (nullable) | 单价来源2 |
| count_in_seasoning | boolean | **老汤/高汤=false**(不计入调料) |
| remark | varchar | 备注(如「取最高」「水0.56+制冰0.05」) |

> 最高单价 = `max(price_source1, price_source2)`(price_source2 为空时取 source1)，运行时计算，不落库(避免与源不同步)。

### 3.2 调料成本算法(纯函数，供 SP-C 调用)

```
注射/kg      = Σ(section=INJECTION:  dosage_per_kg_g/1000 × max(单价1,单价2))   # 每锅同量
熟制/kg(全量) = Σ(section=COOKING ∧ count_in_seasoning:  dosage_per_kg_g/1000 × max(单价1,单价2))

某次熟制(N 锅, 总原料 R kg, 逐锅原料 [r1..rN] 可选):
  注射总 = R × 注射/kg
  熟制总 = Σ_{i=1..N}  锅i原料 × 熟制/kg(全量) × (i==1 ? 1 : subsequent_pot_ratio)
           # 锅i原料未逐锅录 → 按 N 等分 R(默认)；逐锅录则用实际 r_i
  调料成本合计 = 注射总 + 熟制总
```

- 老汤(`count_in_seasoning=false`)不进 Σ ✓
- `subsequent_pot_ratio` 读配方(默认 1/3)✓
- **锅序规则只作用于熟制，不作用于注射**(注射每锅同量) ✓

**自检(对齐 v9.0 汇总页)**：
- 每kg原料：注射 ¥0.24；熟制全量 ¥0.31；第二锅起 ¥0.31×⅓≈¥0.10。
- 合计每kg：第一锅 ¥0.55(0.24+0.31) / 第二锅起 ¥0.34(0.24+0.10) ✓
- N=1, R=160kg → 注射 38.4 + 熟制 49.6 ≈ ¥88（汇总页 88.38）✓
- N=2 → 熟制再 +160×0.31×⅓ ✓

**全部金额计算用 `BigDecimal`**(避免浮点)，`HALF_UP` 舍入；序列化用项目既有数值约定。

### 3.3 计算公式输入「锅数 N」「逐锅原料」的来源
来自 SP-B 熟制工序报工记录(见 §4.4)。配方只存**规则**(比例)，不存**锅数**(事实)。

### 3.4 web-admin 配方维护页
- 路由建议 `/system/product-recipes`(归 `module: 'production'`，避免被 demo 策展隐藏——按近期菜单修复教训)。
- 列表：按产品列配方；新建/编辑：注射段表格 + 熟制段表格(可标「不计入调料」) + 第二锅起比例输入。
- 防呆：单价两源至少填一个；比例 0<ratio≤1；保存前校验唯一(同 SKU 不能两份 ACTIVE 配方)。

---

## 4. SP-B — 文员逐道录入 + DAG 来源捕获(录入方案1)

### 4.1 入口
生产计划行「核对结单」→ 若计划为**逐道报工**(`reportingRequired=true`) → 打开「**逐道工序录入**」面板(较大抽屉/独立页，因 6–7 道工序 × 可能多批)；**免工序报工**仍走现有两点汇总。
存货生产计划(sourceType `SAFETY_STOCK`)录完结单 → 复用现有「完工自动入库成品批次」生成**批次号**(供 SP-C 按批次号查)。

### 4.2 操作单元 = 批次链
- 半成品链(原料→修油→滚揉→焯水)= 一个 WIP 批次
- 成品链(熟制→气调→包装)= 成品批次

### 4.3 每道工序卡片录入字段
投入重量、产出重量、人工(开始时间/结束时间 + 人数 → **自动算总工时 = 时长 × 人数**)、副产(byproducts[])、损耗(waste_quantity)、留样(sample_retain_quantity，末道)。

### 4.4 两类特殊工序
- **拆分(滚揉→多焯水)**：该工序产出可拆成 N 个半成品批，各自重量 + 批号。
- **合并(熟制)**：
  1. 「**上游来源选择器**」多选已录焯水批(下拉显示 `批号·品名·日期`)+ 各自投料 kg → 写 `production_reports.source_batch_refs`。
  2. 录「**锅数 N**」(可选逐锅原料 kg) → 喂 SP-A 调料算法(§3.2)。

### 4.5 写路径(复用，零新模型)
复用 `YieldReportService.submitReport`(INPUT 领料→SEGMENT 工时→OUTPUT 产出)。文员录入 = 一次性把该批各道 INPUT/SEGMENT/OUTPUT 提交。
- `recordedBy/operatorId = 文员 token userId`，**走显式线程/方法参数传入，禁用 `SecurityUtils.getCurrentUserId()`**(本项目永返 null，按 C-B1 教训)。
- DAG 全靠 `source_batch_refs`(JSONB)，**无新 DAG 实体**。

### 4.6 防呆(四位一体，遵循 fool-proof-design.md)
- **Rule 1**：每道投入默认=上道产出(可改)；**注水(滚揉)工序例外允许产出>投入(>100% 出成率)**；其余超收预警。
- **Rule 2**：卡片头带 `品名/批号/工序名/计划数量`。
- **Rule 3**：损耗/异常原因 dropdown(标准选项 + 其他)。
- **Rule 4**：`idempotencyKey` 防重复结单；熟制「投入 = Σ上游投料 kg」容差校验。
- error toast sticky(duration:0 + showClose)，message 含 next action。

### 4.7 权限/业态
- 制造业态(FACTORY)。
- 鉴权用 `@RequireRole/@RequirePermission`(**非 `@PreAuthorize`** —— 本项目 method-security 未启用，是 NO-OP)。
- 文员角色需 `production:write`(或新增 `production:report:write`)。

---

## 5. SP-C — 核算引擎升级(双模式 keying + 成本遍历)

### 5.1 双模式 keying
- 现有：`订单号 → ProductionPlan.source_order_id → ProductionBatch`(保留)
- 新增：`批次号/计划号 → ProductionBatch` 直查 + `getBatchYieldSummary(factoryId, batchNumber)` + `GET /production/batches/{batchNumber}/yield-summary`。
- 核算页加查询模式切换(订单号 / 批次号)。

### 5.2 单盒成本遍历(让现有展示页由真实录入驱动，不再靠 seed)
- **原料**：DAG 遍历 `source_batch_refs`/`batch_relations` 回溯到原料层，**按实测投料量 × 各自累计单价**(65.7:34.3 递归 traceCost，复用现有 `OrderCostBreakdownService`)。
- **人工**：`Σ 工时 × 全厂工时单价`，`工时 = (total_work_minutes/60) × total_workers`。
- **调料**：调 SP-A 算法(注射 + 熟制，锅数 N 取自熟制报工记录)。
- **包装**：末道 `packaging_detail`(膜/气体/标签/其他)。
- **副产回收**：`byproducts`(肥油)× 单价 → 冲减成本。
- **留样**：可售盒 = 总盒 − 留样；可售单盒 = 净成本 ÷ 可售盒。

### 5.3 工时单价配置
全厂统一，存 `factory_configs` 新 key `labor_hour_rate`(numeric)。**未配置 → 明确提示「请先配置工时单价」，不默认 0、不返假数据**(禁止降级)。

### 5.4 输出 DTO(= 现有展示页结构，不动 UI)
原料/人工/调料/包装 + 各占比 + 副产回收 + 留样 + 单盒净成本 + 可售单盒成本 + 逐道出成率 + 整批出成率。

### 5.5 🔒 红线(必经 Opus 终审)
- **价格脱敏**：单盒成本/调料/人工 是 finance-sensitive → 用 `PriceMaskResolver.shouldMaskOperationalCost`(finance:read_write 闸)，非财务角色**整 key 移除**(不是 present-null，按两层脱敏 predicate 一致教训)；出成率/重量不脱敏。
- **多租户隔离**：by-batch 查询**必须校验 `batch.factory_id == caller factory`**(findById 不能裸查，按 disposal/reset-password 跨租户教训)；平台角色 by-design 跨厂。

---

## 6. 测试策略

- **SP-A**：调料成本纯函数单测——覆盖 N=1/2/3、ratio 自定义、老汤排除、取最高单价、逐锅原料 vs 等分；对齐 v9.0 数值(0.55/0.34/88.38)。
- **SP-B**：录入 → `submitReport` 写 `production_reports` + `source_batch_refs` 集成测；防呆校验(超收/注水例外/来源合计/幂等)单测；`recordedBy` 非 null(C-B1 回归)。
- **SP-C**：双模式 keying(订单号/批次号各一例)；DAG 成本遍历(2 源混锅 65.7:34.3)；人工工时单价；副产回收/留样；**跨租户拒绝单测**(F006 用户查 DEMO 批次→403/404)；**价格脱敏单测**(非财务角色 key absent / 财务角色见值)。
- **端到端(DEMO_FACTORY headed)**：文员录一个存货生产批次(含混锅)→ 核对结单 → 按批次号查核算页 → 出成率/单盒成本/拆分渲染正确。**绝不碰 F006/LIUSHANMEN**。

---

## 7. 迁移与部署

- Flyway 迁移(新表 `product_recipes`/`recipe_ingredients`、`factory_configs` 新 key)放 `backend/java/cretas-api/src/main/resources/db/flyway/`，编号不撞(部署前 `git fetch` 查 origin/main 最大号)。
- 🔒 schema 变更走 deploy 脚本，prod 只从 main 部署。
- 演示数据：M67 demo seed(`m67_demo_seed.sql`)升级——补 `product_recipes`/`recipe_ingredients` 锚点，使其自包含(延续近期 DF_pt10 自包含修复)。

---

## 8. 非目标(YAGNI)

- 不做可视化 DAG 画布(方案2 否决)。
- 不做操作员 APP 端改动(本期文员 PC 录入；APP 逐道报工已存在，不动)。
- 不做经营/销售汇总页(税前成本/毛利/售价)——张权明确「展示页先这样不动，数据先跑通」，留后续。
- 不接实际工资/考勤(工时单价全厂统一即可)。
- 不做配方版本历史(要改换 BOM-SKU 绑定即可)。

---

## 9. 开放项(实现时确认)

- 熟制逐锅原料：默认等分 R，留逐锅录入选项(已定，记录于此)。
- 文员角色 code/权限点最终命名(`production:write` vs 新增 `production:report:write`)——实现时按现有 RBAC L1 表确认。
