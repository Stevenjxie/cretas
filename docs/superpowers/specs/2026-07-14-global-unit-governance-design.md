# 全局单位治理与显式换算设计

日期：2026-07-14
状态：待实施
范围：SKU/原料主数据、Workflow、生产计划、逐道报工、库存、销售、BOM、成本与前端展示

## 1. 背景与已确认根因

F006 的 SHH0713 生产计划提供了完整复现：

- 成品 SKU `f255eaf6-74dc-4b36-9c19-4a44408fc1b8` 的主数据单位是 `克`，标准克重为 `200g`。
- 生产计划 `aa19fa73-3f97-4d8a-8219-8df6b2d292c1` 的计划数量是 `100000 克`。
- 该计划锁定当前已激活 Workflow `48 / v3`。
- Workflow 冷冻工序的 `data.outputUnit` 是 `g`，但绑定的成品物料节点 `baseUnit` 和产出端口 `unit` 都是 `件`。
- 运行时物化忠实复制端口，生成 `workflow_task_ports.unit = 件`；逐道录入横幅因此显示“计划产出（件）”。

当前系统同时存在以下事实源：

1. `ProductType.unit` / `RawMaterialType.unit` 主数据单位；
2. `ProductionPlan.plannedUnit` / `ProductionBatch.plannedUnit` 计划快照单位；
3. `WorkProcess.outputUnit`、Workflow process `inputUnit/outputUnit`；
4. Workflow 物料节点 `baseUnit`、端口 `unit`、运行时 `WorkflowTaskPort.unit`；
5. BOM `outputUnit` 和配方行 `unit`；
6. WIP、成品、原料库存批次原生单位；
7. 销售订单行单位和包装单位。

系统虽已有 `unit_of_measurements` 表和管理页面，但核心生产、销售和库存路径主要依赖字符串比较及多套独立换算器：

- `UnitConversionService`
- `SystemEnumService.convertUnit`
- `MaterialUomConverter`
- `FeedUnitConverter`
- `FgQuantityUnitConverter`
- `RestockUnitConverter`
- 前端 `feedUnitConversion.ts` 及各页面本地默认值

这些实现的别名、量纲、精度和失败语义并不完全一致。例如某些路径把“个、件、只”视为同一计数单位，另一些路径只识别“盒、个、件、只”；重量单位又存在 `克/g`、`千克/公斤/kg` 的原始字符串差异。

因此本次不采用“把页面上的件改成 g”的局部修复，而建立统一单位契约并逐步接管业务边界。

## 2. 目标与非目标

### 2.1 目标

- 每个数量都能明确回答：数值、规范单位代码、量纲、单位来源和换算依据。
- 系统固有换算、产品专属换算、工艺转换严格分离。
- Workflow 端口是 Workflow 批次报工的运行时单位权威，但端口必须通过主数据与换算契约校验。
- 商业单位可以不同于库存基础单位，例如 SKU 以 `g` 库存、销售按 `pcs` 下单。
- 无明确换算关系时 fail closed，不猜测、不按 1:1、不回退 `kg`。
- 历史快照保持可审计；未报工批次允许显式重新物化，已报工批次不静默改写。
- 逐步替换重复换算逻辑，同时保持旧字段和接口在迁移期兼容。

### 2.2 非目标

- 本阶段不重写工艺出成率、BOM 产耗关系或 MaterialProductConversion 的业务含义。
- 不把所有数据库单位字符串一次性改成外键；先通过统一服务和写入校验收口。
- 不自动推断密度，因此质量与体积之间默认不可换算。
- 不把所有计数/包装单位视为等价；`pcs`、`portion`、`box`、`case` 必须有产品关系才能互换。
- 不静默修改已有报工、库存流水、财务凭证或已消耗批次。

## 3. 概念模型

### 3.1 规范单位

所有业务计算使用规范单位代码。第一阶段内置代码如下：

| 量纲 | 规范代码 | 常见显示名/输入别名 |
|---|---|---|
| MASS | `mg` | 毫克 |
| MASS | `g` | 克 |
| MASS | `kg` | 千克、公斤 |
| MASS | `t` | 吨 |
| VOLUME | `ml` | 毫升、mL |
| VOLUME | `l` | 升、L |
| COUNT | `pcs` | 个、件、只 |
| COUNT | `portion` | 份 |
| PACKAGE | `box` | 盒 |
| PACKAGE | `case` | 箱 |
| PACKAGE | `bag` | 袋 |
| PACKAGE | `bottle` | 瓶 |

别名归一化只表示同一单位代码的不同写法，不表示换算关系。例如“克”和 `g` 是别名；“件”和“盒”不是别名。

`unit_of_measurements` 继续作为可配置显示、精度与同量纲系数的数据来源。系统内置单位不可被工厂改成不同量纲或不同基础系数；工厂可以停用展示、增加别名或增加有明确定义的同量纲单位。

### 3.2 三类转换

#### A. 系统固有转换

同量纲、与产品无关：

- `1000mg = 1g`
- `1000g = 1kg`
- `1000kg = 1t`
- `1000ml = 1l`

由统一 `UnitContractService` 按单位字典执行。

#### B. 产品专属转换

跨量纲或包装层级、只对一个 SKU/物料成立：

- `1 pcs = 200 g`
- `1 case = 20 pcs`
- `1 box = 10 portion`

新增 `product_unit_conversions`：

| 字段 | 含义 |
|---|---|
| `id` | 主键 |
| `factory_id` | 租户 |
| `product_type_id` | 适用 SKU |
| `from_unit_code` | 源规范单位 |
| `to_unit_code` | 目标规范单位 |
| `factor` | `1 from = factor to` |
| `source_type` | `NET_CONTENT` / `PACKAGING` / `MANUAL` |
| `is_primary_sales_conversion` | 是否销售默认换算 |
| `effective_from/effective_to` | 生效区间 |
| `version` | 乐观锁/审计版本 |
| 审计字段 | 创建、更新、软删除 |

约束：同一 SKU、源单位、目标单位在同一有效期只能有一条有效关系；factor 必须大于零；禁止闭环关系产生不一致乘积。

兼容映射：

- `ProductType.gramsPerUnit` 映射为主要计数/销售单位到 `g` 的 `NET_CONTENT` 关系。
- `ProductType.boxConversionCoefficient` 与 `level1Unit` 映射为 `level1Unit -> unit` 的 `PACKAGING` 关系。
- 迁移期写入旧字段时同步更新关系；新 API 写关系时回写可无损表达的旧字段。

#### C. 工艺转换

投入与产出之间的工艺关系，例如出成率、固定比例、实际称重，继续属于 Workflow `conversionRule`。它不能作为单位字典或产品包装换算使用。

## 4. 统一单位契约服务

新增后端边界服务 `UnitContractService`，提供：

- `normalize(unit)`：别名转规范代码；未知单位返回结构化错误。
- `describe(unit)`：返回量纲、显示名、精度和基础单位。
- `areEquivalent(a, b)`：仅判断规范代码相同。
- `canConvert(factoryId, productTypeId, from, to, at)`：查找系统或产品换算路径。
- `convert(quantity, context)`：返回数量、目标单位、所用路径、舍入信息。
- `requireCompatible(...)`：业务写入边界的 fail-closed 校验。
- `validateConversionGraph(productTypeId)`：检查重复、闭环和冲突。

`ConversionContext` 必须包含：

- factoryId；
- productTypeId（产品专属换算需要）；
- from/to 规范单位；
- 业务时间；
- 场景：计划、Workflow、报工、库存、销售、BOM、成本或展示；
- 舍入策略。

返回值不使用裸 `null` 表示所有失败，而使用：

- `CONVERTED`
- `IDENTITY`
- `UNSUPPORTED_DIMENSION`
- `PRODUCT_CONVERSION_MISSING`
- `AMBIGUOUS_CONVERSION`
- `INVALID_UNIT`

金额与库存写入场景对后三种状态必须阻断。只读展示可以显示“无法换算”，但不能显示伪造数值。

第一阶段由适配器让现有 `UnitConversionService`、`FeedUnitConverter`、`FgQuantityUnitConverter`、`RestockUnitConverter` 委托统一服务；业务调用点逐步迁移后再删除重复实现。

## 5. Workflow 单位生命周期

### 5.1 编辑器

- 绑定或重新选择物料/SKU 时，从主数据加载规范基础单位并更新物料节点 `baseUnit`。
- 端口默认继承所连物料节点单位。
- 页面加载完成、SKU 选项加载完成、保存前和发布前都执行一次纯函数 `reconcileWorkflowUnits`，而不是只在用户本地 mutate 时同步。
- 如果端口单位与物料基础单位不同，用户必须选择一条有效的产品专属换算；端口保存 `conversionRefId`，不能只保存一个不同的字符串。
- UI 同时显示“库存单位”“端口报工单位”“换算关系”，避免只显示一个容易误解的单位。

### 5.2 后端保存与发布

后端是最终防线，不能依赖前端自愈：

- 草稿保存允许不完整，但返回结构化 warnings。
- 发布必须校验每个物料节点的 SKU 存在、基础单位与主数据一致。
- 每个端口必须有规范单位、稳定 portId 和绑定物料。
- 端口单位等于物料基础单位时直接通过。
- 不等时必须有有效 `conversionRefId`，且关系适用于该 SKU 和方向。
- process `inputUnit/outputUnit` 只作为编辑提示；运行和报工由端口定义。发布时要求其与主端口一致或由系统重算，禁止并存冲突值。
- SKU 单位或产品换算关系变化时，将引用它的 DRAFT Workflow 自动重算；PUBLISHED Workflow 标记 `UNIT_REVIEW_REQUIRED`，禁止再次激活，直到生成新版本并确认。

### 5.3 编译、物化与报工

- Runtime compiler 重复执行发布级关键校验，防止历史或直接 API 数据绕过。
- 运行时快照保存规范端口单位、conversionRefId 和换算关系版本/快照。
- 逐道报工只读取快照端口，不能回退 ProductWorkProcess、SKU 当前单位或默认 `kg`。
- 请求单位必须能归一化且等于快照端口代码；展示名差异不构成冲突。
- 多产出分别校验、分别保留单位，不能跨单位求和。
- `WorkProcessTask.actualQuantity` 只记录主产出数量和主产出单位。

### 5.4 历史批次

- 已发生任何报工、库存或成本流水的批次保持快照不变。
- 未报工批次可以执行“重新物化 Workflow”，操作前展示单位差异并写审计记录。
- 已发布但尚未创建批次的错误 Workflow 必须生成新版本，不能原地篡改发布快照。

## 6. 业务模块规则

### 6.1 SKU、原料和 BOM

- SKU/原料主数据单位写入时立即规范化。
- 修改基础单位前检查库存余额、有效 Workflow、BOM 和开放订单；有历史业务数据时使用“新单位版本/迁移”流程，禁止裸改字符串。
- BOM 配方头产出单位必须能转换到 SKU 基础单位；配方行单位必须能转换到原料主数据单位。
- BOM 成本先换到各物料库存基础单位，再乘价格；不能将 `convertOrSame` 用于金额计算。

### 6.2 销售订单与生产计划

- 销售单位候选来自 SKU 有效产品换算关系，不再固定为 `kg/份/箱`。
- SKU 基础单位永远可选；其他单位只有存在换算时可选。
- 销售订单保存原始数量/单位，并同时保存转换到 SKU 基础单位的数量及 conversion snapshot。
- 生产计划默认使用 SKU 基础单位；若业务需要沿用销售单位，则计划同时保存原始商业数量和基础数量，不能只有一个含义不清的 plannedQuantity。
- 销售转生产计划时必须携带换算快照，不能依赖以后可能变化的 gramsPerUnit。

### 6.3 WIP、成品和原料库存

- 每个库存批次保留原生单位，但聚合、比较、预占和扣减前统一转换到该物料/SKU 的库存基础单位。
- 同一产品不同单位批次可以共存，但任何总量必须带目标单位和完整换算结果。
- 无法换算的批次不参与伪汇总，并形成阻断告警。
- FEFO、销售分配、发货扣减、退货恢复使用同一转换路径和同一精度。

### 6.4 报工、出成率和成本

- 报工保存原始端口数量/单位，同时可保存转换后的基础数量和 conversion snapshot。
- 出成率只有在单位可比或存在确定换算时计算；否则为 null 并说明原因。
- 跨单位的工艺产出不能直接相加。
- 成本先归一到对应库存基础数量，再计算单价；单价必须声明计价单位。
- 计数与重量换算必须绑定具体 SKU，禁止只拿任意 gramsPerUnit 套在其他产品上。

### 6.5 前端展示

- 所有单位 selector 从统一单位/产品换算 API 获取。
- 显示名称与规范代码分离；接口提交规范代码。
- 计划、报工和库存展示同时标明来源，例如“100 件（按 200g/件 = 20kg）”。
- 无换算时显示明确阻断文案和配置入口，不回退 `kg`、不隐藏单位、不允许自由文本制造新别名。

## 7. API 与兼容策略

新增接口：

- `GET /{factoryId}/units/catalog`
- `POST /{factoryId}/units/convert`
- `GET /{factoryId}/product-types/{id}/unit-conversions`
- `POST/PUT/DELETE /{factoryId}/product-types/{id}/unit-conversions/...`
- `GET /{factoryId}/unit-governance/conflicts`
- `POST /{factoryId}/production-batches/{id}/rematerialize-workflow`（仅未报工批次）

旧 DTO 字段继续接受迁移期别名，但进入 service 的第一步必须规范化；响应逐步增加 `unitCode/unitLabel/conversion`，旧 `unit` 暂时返回规范代码。

错误码至少包括：

- `UNIT_CODE_INVALID`
- `UNIT_DIMENSION_MISMATCH`
- `PRODUCT_UNIT_CONVERSION_MISSING`
- `PRODUCT_UNIT_CONVERSION_AMBIGUOUS`
- `WORKFLOW_PORT_UNIT_STALE`
- `WORKFLOW_UNIT_REVIEW_REQUIRED`
- `INVENTORY_UNIT_UNCONVERTIBLE`

## 8. 数据迁移与冲突治理

### 8.1 只读扫描

扫描所有主要表中的单位字符串，按以下类型输出冲突：

- 未知别名；
- 主数据与 Workflow 物料节点不一致；
- process outputUnit 与主产出端口不一致；
- 端口与绑定 SKU 不一致且无显式转换；
- 计划/批次单位缺失；
- 同一库存产品存在不可换算单位；
- gramsPerUnit 存在但无法确定对应计数单位；
- boxConversionCoefficient 缺少 level1Unit 或基础单位。

### 8.2 自动修复边界

可自动修复：

- 纯别名归一化，如 `克 -> g`、`公斤 -> kg`；
- 没有业务流水的 DRAFT Workflow 端口同步；
- 可从明确旧字段无损生成的产品换算关系。

必须人工确认：

- `件/份/盒/箱` 的真实关系；
- 已发布 Workflow 的跨量纲端口；
- 已有库存/报工/财务流水的单位变更；
- 同一 SKU 存在多条冲突换算路径。

迁移工具先 dry-run 输出报告，再按工厂分批执行；F006 作为首个验证租户。LIUSHANMEN 不在本任务操作范围内。

## 9. 分阶段实施

### Phase 1：核心契约与 Workflow 堵口

- 单位规范化、量纲、转换结果模型。
- 产品专属换算实体、API 与旧字段兼容。
- Workflow 前端 reconciliation。
- 后端草稿 warning、发布硬校验、编译/物化硬校验。
- SHH0713 冲突扫描、修复新 Workflow 版本和新批次验证。

### Phase 2：生产与库存

- 生产计划和批次双数量/换算快照。
- clerk process sheet 与 operator yield report 统一。
- WIP/FG/raw inventory 聚合、扣减、退回统一转换。
- 出成率与成本跨单位规则统一。

### Phase 3：销售、BOM 与全局 UI

- 销售单位候选和销售转计划换算。
- BOM 产出/行项目单位校验和成本归一。
- 全局单位 selector、标签和冲突治理页面。
- 下线重复转换器和未经规范化的硬编码默认值。

每个 Phase 独立提交、独立部署、独立数据验证，不在一次上线中迁移所有历史数据。

## 10. 测试与验收

### 10.1 单元测试

- 所有别名归一化和量纲判断。
- 同量纲转换、产品转换、包装链转换。
- 缺失、歧义、闭环、不一致关系。
- 精度和舍入策略。
- Workflow 节点/端口 reconciliation 纯函数。
- 发布和 runtime compiler 的 stale-unit 阻断。

### 10.2 集成测试

- SKU `g` + `1pcs=200g`：销售 10pcs -> 计划 2000g -> Workflow 报工 g -> 销售扣减 pcs。
- SKU `g` 且无产品转换：销售不得选择 pcs。
- Workflow 端口 stale 为 pcs：发布失败并定位节点/端口。
- 多产出 `pcs + g`：分别保存，主产出任务不求和。
- 同一 FG 存在 kg 与 pcs 批次：FEFO、预占、扣减和退回结果一致。
- 修改 SKU 单位后 PUBLISHED Workflow 进入 UNIT_REVIEW_REQUIRED。

### 10.3 生产验证

F006 需要验证：

1. 冲突扫描准确列出 Workflow 48/v3 的冷冻产出端口；
2. 新版本中成品节点和端口规范为 `g`；
3. 新计划顶部、工序横幅、输入框和保存请求均为 `g`；
4. 配置 `1pcs=200g` 后销售仍可按件，下游基础数量正确；
5. 无换算关系时前后端均阻断；
6. 既有已报工批次未被改写。

## 11. 可观测性与审计

- 每次转换记录来源：系统单位表、产品换算关系 ID/版本或 Workflow 快照。
- 阻断日志包含 factory、product、workflow、node、port、from/to unit 和业务场景，不记录敏感价格。
- 提供单位冲突数量、失败转换次数和按模块分布指标。
- 重新物化、换算关系变更和单位迁移写审计日志。

## 12. 决策摘要

- Workflow 端口是 Workflow 报工的单位权威，但不是可以脱离主数据任意填写的字符串。
- SKU/物料库存基础单位定义“存什么”；产品专属换算定义“怎么卖、怎么包装”；Workflow 工艺规则定义“怎么生产”。
- 商业单位与基础单位可以不同，但必须有版本化、可审计的显式转换。
- 所有金额、库存和生产写入路径 fail closed；只读展示允许诚实显示未知，不允许伪造数值。
- 采用渐进式统一契约，不采用一次性全库外键重构。
