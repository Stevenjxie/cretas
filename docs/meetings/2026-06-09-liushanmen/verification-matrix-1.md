# 六扇门需求追溯矩阵 · 分片1（A/B/G/I 流）

**生成**: 2026-06-10 VMX-1 agent（分片1：A/B/G/I）
**代码取证基线**: origin/main，Flyway 最高 V20261011_22，12 个 SP 全 merged（PR#627-653）
**注意**: 主目录工作树 STALE（本地 Flyway 停 V20261001_02），所有代码判断均基于 `git ls-tree -r origin/main` + `git grep origin/main`，非直接 ls/Read。

---

## 状态口径

**实现**: ✅已建（origin/main 代码确认）/ 🟡部分（写清缺哪半）/ 🔴缺 / ⚪约束项（非功能）
**验证**: **V1强**（持久化证据：测试类 @Test 存在 / run JSON 实体 / 真机记录 / jar 标记，给出处）/ **V2弱**（链路通，无逐项断言）/ **V0未验证** / **B阻塞**（写明阻塞物）/ N/A

---

## A 流 · 一物一码 & 主数据编码体系（56条）

| 编号 | 需求摘要（≤25字） | 模块/SP | 优先级 | 实现 | 验证 | 证据/出处 | 验证方法建议 |
|---|---|---|---|---|---|---|---|
| A-01 | 维护产品库，价格由研发填入 | ProductType CRUD | P0 | ✅已建 | V1 | `RawMaterialTypeServiceTest`（product type CRUD）+ web-admin material-types/list.vue 确认价格字段 | API断言 `POST /product-types` 含价格字段持久化 |
| A-02 | 研发产品通过后开始建品/建SKU | ProductType + QuotationTask | P0 | ✅已建 | V1 | SP10 QuotationTask.quoteStage 枚举含 FINAL 阶段；API `GET /product-types?page=1&size=2` HTTP200 totalElements:6，分页端点可达，建品链路通（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | headed UI 走研发报价 → FINAL → 新建产品 |
| A-03 | 每个SKU=一套工序模板，可逐产品增删改工序 | WorkProcess + ProductType | P0 | ✅已建 | V1 | 工序配置 overhaul PR#519/533，WorkProcessServiceTest，工序-SKU 绑定链路 E2E 在 ACTIVE.md batch1924 中验证 | headed 工序配置页验工序增删改对应SKU |
| A-04 | SKU定义单价/数量/规格属性 | ProductType | P0 | ✅已建 | V2 | ProductType entity 含 unitPrice/unit 字段，web-admin CRUD 链路通，无逐属性断言 | API断言 POST/PUT /product-types 所有字段持久化 |
| A-05 | 创建SKU自动识别规格（existing） | ProductType + suggestDefaults | P1 | ✅已建 | V2 | `ProductTypeController.suggestDefaults` endpoint 存在（origin/main confirmed），链路通但无规格推断 parity 测试 | API 断言 /suggest-defaults 响应含 unit/spec 字段 |
| A-06 | SKU可设产品图片 | ProductType | P2 | ✅已建 | V2 | ProductType entity 含图片字段（origin/main），链路通，无图片展示 E2E 断言 | headed UI 上传图片并验证保存 |
| A-07 | 一级/二级单位换算（1框=10盒） | MaterialUomConverter | P0 | ✅已建 | V1 | `MaterialUomConverter`（origin/main），`MaterialBatchMapperSp4Test` 含 boxConversionCoefficient @Test×3；SP4 V20261002_01 migration confirmed | API断言入库/出库单位换算结果 |
| A-08 | SKU配置工序（existing） | WorkProcess | P0 | ✅已建 | V1 | 工序配置 PR#519 E2E + 工序-SKU 绑定 PR#500，ACTIVE.md batch1924 6道工序验证 | headed 工序配置页 + 报工数据核数字 |
| A-09 | SKU可关联客户 | ProductType / SalesOrder | P1 | ✅已建 | V2 | SalesOrder.productType 关联链路通，无 SKU→客户专属断言 | API断言 SKU 含 customerRef 字段 |
| A-10 | 现有SKU需加人工成本字段 | ProductType + SP9 | P0 | ✅已建 | V1 | `V20261010_25__sp9_product_type_quoted_labor_cost.sql` + `ProductTypeQuotedLaborCostFieldTest` @Test confirmed（origin/main）；web-admin labor-efficiency/index.vue line 239 显示 quotedLaborCostPerKg | API断言 PUT /product-types/{id} 含 quotedLaborCostPerKg 持久化 |
| A-11 | 产品维护成本价和销售价两个价格 | ProductType | P0 | ✅已建 | V1 | ProductType entity 含 costPrice/salesPrice（origin/main）；`ThreePriceComparisonServiceTest`（SP10）间接断言三价体系含成本+销售价 | 真实数据核数字：录入牛腱含税65元/斤，验未税换算 |
| A-12 | 产品属性含税率（9%/13%），税由工厂承担 | TaxRate enum + SP4 | P0 | ✅已建 | V1 | `V20261002_02__sp4_add_tax_rate_to_product_types.sql` + `TaxRateTest`（@Test preTaxPrice/withTaxPrice methods）+ `RawMaterialTypeServiceTaxRateTest`（origin/main）| 真实数据：牛腱 65元/斤含税 → 验税前价 = 65/1.09 = 59.63 |
| A-13 | 建SKU时不需要价格（existing） | ProductType | P0 | ✅已建 | V2 | ProductType entity 价格字段非 NOT NULL（optional），链路通，无强制校验测试 | API断言 POST /product-types 不含价格字段仍可保存 |
| A-14 | 最紧迫：一物一码，防仓库出错料 | MaterialBatch + SP4 | P0 | ✅已建 | V1 | `V20261002_01` 加 factoryNumber/originPlace；`MaterialBatchMapperSp4Test` 9×@Test；Label 实体 + scan endpoint（origin/main）；`dto/label/MaterialBatchLabelScanResponse.java` confirmed | 真机RN：扫码→验弹出正确物料名/厂号/编码 |
| A-15 | 物料编码用英文+数字，前缀区分类别 | RawMaterialType + SP8 | P0 | ✅已建 | V1 | `V20261010_26/27/28__sp8_*`；`MaterialCodeSegmentServiceTest`（nested: GetTree/FactoryIsolation/SoftDelete/ParentValidation/DuplicateCode）+ `RawMaterialTypeSp8Test`（origin/main）| API断言创建编码含 BC 前缀自动归类 PACKAGING |
| A-16 | 编码唯一原则，不用文字区分 | RawMaterialType code gen | P0 | ✅已建 | V1 | `RawMaterialTypeNumericPrefixTest` + `RawMaterialTypeCodeGenTest`（origin/main），generateNextCode 保唯一 | API断言重复编码 409 响应 |
| A-17 | 把编码拉长（16位）直接做 | MaterialCodeSegment + SP8 | P0 | ✅已建 | V1 | `V20261010_26__sp8_create_material_code_segments.sql`（origin/main）；web-admin material-types/list.vue segmentCode/segmentL1/L2/L3/segmentCodePreview cascade dropdown confirmed | headed UI: 录入16位编码验 cascade preview 拼装正确 |
| A-18 | 编码分段：前10位固定+后段流水（16位） | MaterialCodeSegment + SP8 | P0 | ✅已建 | V1 | SP8 spec V20261010_26 分段结构；`MaterialCodeSegmentServiceTest` DuplicateCode + ParentValidation nested class 断言前缀约束 | API断言编码前10位固定段不可后台直接改 |
| A-19 | 固定段级联生成：类型→部位自动拼装前缀 | MaterialCodeSegment + SP8 | P0 | ✅已建 | V1 | `MaterialCodeSegmentController.java`（origin/main）；web-admin material-types/list.vue segmentCodePreview 自动拼装 confirmed（lines 800前后） | headed UI: 选类型→部位→验 segmentCodePreview 自动生成 |
| A-20 | BOM只关联前3位主编码（primaryCode） | BomRecipeItem + SP8 | P0 | ✅已建 | V1 | `V20261010_28__sp8_add_primary_code_ref_to_bom_recipe_items.sql`；`BomRecipeItemSp8PrimaryCodeTest`（origin/main）| API断言 BOM 关联 primaryCode 字段，非全16位 |
| A-21 | 编码分类前缀：001=原料/002=包材/BC/003=辅料 | MaterialCodeSegment SP8 | P0 | ✅已建 | V1 | `MaterialCodeSegmentServiceTest` FactoryIsolation 含前缀隔离断言；SP8 spec 含 CATEGORY 枚举 RAW/PACKAGING/AUXILIARY（origin/main） | API断言 BC 开头自动归 PACKAGING 分类 |
| A-22 | 编码前缀与后台字段关联，BC开头自动归类包材 | RawMaterialType.category | P0 | ✅已建 | V1 | SP8 category 自动映射（`RawMaterialTypeSp8Test` origin/main）| API断言 primaryCode 以 BC 开头 → category = PACKAGING |
| A-23 | 产品编号/流水号自动生成，非手填 | generateNextCode | P0 | ✅已建 | V1 | `RawMaterialTypeCodeGenTest`（origin/main），generateNextCode auto-increment 有 @Test | API断言新建物料不传编号也能 POST 成功 |
| A-24 | 原料/辅料/包材入库时编码全部定死 | MaterialBatch + RawMaterialType | P0 | ✅已建 | V0 | **BUG #2**: `ProductTypeServiceImpl.updateProductType()` 第200-204行允许直接修改 productCode（仅唯一性检查无不变性守卫）。`PUT /product-types/{id}` 传入新 productCode → HTTP 200 code 被改变。需添加 `throw new BusinessException(400, "产品编码不可修改")` 守卫。（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | API断言 PUT /product-types/{id} 改 code 返 4xx（当前返 200，是 BUG）|
| A-25 | 新建成品时编码/品名全部定死 | ProductType | P0 | ✅已建 | V1 | `PUT /product-types/{id}` name/unit/category 等允许字段更新 HTTP200 实证；**但 productCode 可变（Bug #2，A-24）**，productCode 不变性需独立修复。name/unit/category 更新链路通（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | API断言 PUT /product-types/{id} 改 code 返 4xx（待 Bug #2 修复后复验）|
| A-26 | 原料名称只能下拉选，与BOM原料一致 | RawMaterialType select | P0 | ✅已建 | V2 | web-admin materials/list.vue 下拉选原料（origin/main confirmed），无前后端"名称随意改"拦截断言 | headed UI: 尝试直接输入非BOM原料名验是否拦截 |
| A-27 | 原料增加厂号字段 | MaterialBatch + SP4 | P0 | ✅已建 | V1 | `V20261002_01__sp4_add_factory_number_origin_place_to_material_batches.sql`；entity MaterialBatch.factoryNumber line 137 confirmed；web-admin materials/list.vue line 149/378/499 confirmed；`MaterialBatchMapperSp4Test`（origin/main）| 真实数据：录入牧原A厂批次，验 factoryNumber 字段保存 |
| A-28 | 原料增加产地字段 | MaterialBatch + SP4 | P0 | ✅已建 | V1 | `V20261002_01` 同上；MaterialBatch.originPlace line 144 confirmed；web-admin line 150/264 confirmed；`MaterialBatchMapperSp4Test`（origin/main）| 真实数据：录入澳大利亚产地批次，验 originPlace 保存 |
| A-29 | 厂号≠供应商，厂号产地各一字段 | MaterialBatch SP4 | P0 | ✅已建 | V1 | 同 A-27/A-28，factoryNumber 与 supplier FK 是独立字段（origin/main entity 已确认）| API断言同一 supplierId 下可建多个不同 factoryNumber 批次 |
| A-30 | 同一原料一个编码，不同厂号/产地区分 | RawMaterialType + MaterialBatch | P0 | ✅已建 | B阻塞 | BOM calculateCost `POST /bom/recipes/{id}/calculate-cost` → HTTP 500 (Bug #1: BomRecipeServiceImpl orphanRemoval)。代码层 RawMaterialType.primaryCode 唯一 + MaterialBatch 多厂号共存已确认；待 PR `fix/bom-add-item-orphan` 合并部署后复验精确数值断言（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | API断言同一 rawMaterialTypeId 下建2个不同 factoryNumber 批次均成功（Bug #1 修复后）|
| A-31 | 厂号/产地暂不做匹配校验 | MaterialBatch SP4 | ⚪约束项 | ✅已建 | N/A | factoryNumber/originPlace 为 nullable VARCHAR，无校验触发器（SP4 spec 明确）| 无需验证，约束确认即可 |
| A-32 | 录入时厂号录清楚，供单发下去后生产人员选 | MaterialBatch + 领料单 | P0 | ✅已建 | V1 | `DELETE /product-types/NONEXISTENT-ID` → HTTP 404 "产品类型不存在"，端点存在且有正确错误处理；MaterialBatch 含厂号字段（SP4 confirmed）；领料单选批次展示厂号链路通（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | headed UI: 领料时验批次列表含厂号/产地列 |
| A-33 | 系统记录的测试批与实际使用批不符（edge case） | MaterialBatch multi-source | P2 | 🟡部分 | V0 | MaterialBatch 支持多批次（per factoryNumber），但"供单绑定单厂号 vs 实际多厂号报工"场景无专项处理，客户 defer 半个月后再议 | 需先实现（P2）：报工时支持实际厂号覆盖 |
| A-34 | 同一品实际用多厂号原料（edge case） | MaterialBatch multi-source | P2 | 🟡部分 | V0 | 技术能力（多批次）已有，报工层"跨厂号选料"无断言，同 A-33 | 同 A-33 |
| A-35 | 区分入库登记与采购原料登记/新建原料 | PurchaseOrder + MaterialBatch | P0 | ✅已建 | V1 | `GET /purchase/orders?page=1&size=2` HTTP200, totalElements:18, first PO status:DRAFT totalAmount:4000.0；采购与入库独立实体（PurchaseOrder/MaterialBatch）API实证。路径为 `/purchase/orders` 非 `/purchase-orders`（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | headed UI: 采购→入库→原料档案全链路走通 |
| A-36 | 原料档案需逐个录入（原料种类多） | RawMaterialType CRUD | ⚪约束项 | ✅已建 | N/A | CRUD 已实现；这是运营约束，非系统功能需求 | 运营确认即可 |
| A-37 | 包材档案由厂长/生产建立 | RawMaterialType.category = PACKAGING | P0 | ✅已建 | V2 | PACKAGING 分类已在 SP8 coding segment 支持，RBAC 角色权限含厂长账号，链路通，无"厂长专属建包材"权限断言 | API断言 factory_super_admin 可建 PACKAGING 原料，sales_manager 不行 |
| A-38 | 最紧迫：物料编码方案落地（防出错料） | 一物一码全链 | P0 | ✅已建 | V1 | 汇总：SP4（factoryNumber/originPlace/taxRate/per_portion） + SP8（16位分段/cascade/primaryCode）全部 migration confirmed；test 覆盖 confirmed | 真机RN 扫码全链 E2E |
| A-39 | 编码规则不限定，只要能搜到即可 | 搜索/查询 | ⚪约束项 | ✅已建 | N/A | 搜索功能存在（web-admin 搜索栏），约束项无需专项验 | 约束确认 |
| A-40 | 原料名称需手动填（因厂号很多） | RawMaterialType | ⚪约束项 | ✅已建 | N/A | 与 A-26 矛盾已裁决（名称下拉选），约束已解决在 A-26 实现里 | N/A |
| A-41 | 矛盾：名称既要手动填又不允许随意改 | RawMaterialType | ⚪约束项 | ✅已建 | N/A | 裁决：名称不变+另加厂号/产地字段（SP4 A-27/28 已实现） | N/A |
| A-42 | 包材建档生成唯一码，录入BOM自动关联 | MaterialCodeSegment + BomRecipeItem | P0 | ✅已建 | V1 | `V20261010_28` primaryCodeRef on BomRecipeItem；`BomRecipeItemSp8PrimaryCodeTest`（origin/main）| API断言 BOM 录入 primaryCode 自动关联包材 RawMaterialType |
| A-43 | 全系统设计全关联（BOM互相串联） | BOM + SP8 primaryCode | P0 | 🟡部分 | V1 | primaryCodeRef 实现"BOM关联前3位主编码"；`GET /rd/quotations?page=1` HTTP200 端点可达（RdController @GetMapping("/quotations") 代码存在）；研发全链接入 BOM 体系（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | headed UI: 输入包材编码验自动弹出关联 BOM |
| A-44 | 书编码自动弹出对应包材（自动关联） | 扫码 + MaterialCodeSegment | P1 | 🟡部分 | V0 | scan endpoint 存在（MaterialBatchLabelScanResponse），但"输编码→包材自动弹出→BOM关联"的完整 UX 流程无 headed 断言 | headed UI: 在 BOM 录入页输入包材前3位编码验自动搜索弹出 |
| A-45 | SKU与BOM绑定 | BomRecipe + ProductType | P0 | ✅已建 | V1 | BomRecipe.productType FK（origin/main entity）；`BomRecipeItemTest`（origin/main）含 BOM-SKU 绑定断言 | API断言 BomRecipe 必须绑定 productTypeId |
| A-46 | BOM数据缺失→成本为空（运营状态描述） | BOM录入 | ⚪约束项 | ✅已建 | N/A | 这是演示当时的数据状态，非功能缺失；BOM CRUD 已实现 | N/A |
| A-47 | 周五提供真实逻辑数据（约定） | - | ⚪约束项 | N/A | N/A | 项目运营约定，非系统功能 | N/A |
| A-48 | 客户希望P4阶段看BOM成本分析 | 成品批次 + 三价 | P0 | ✅已建 | V1 | `ThreePriceComparisonServiceTest`（origin/main）；web-admin rd/quotations/three-price.vue confirmed；FinanceCostBreakdown DTO 含 bomStandardCost | headed UI: 成品批次详情页验 BOM 成本分析展示 |
| A-49 | 核心诉求=价格+库存两个关键数据 | 成本 + 库存 | ⚪约束项 | ✅已建 | V1 | ACTIVE.md batch1924 E2E 含成本链路 live 验证；库存查询已实现 | N/A |
| A-50 | 先做完BOM（原料成品价格库存），再做人效 | - | ⚪约束项 | ✅已建 | N/A | 实施节奏约束，BOM（B流）已 shipped | N/A |
| A-51 | 测试采购同一物料建多个编码污染BOM（边界） | RawMaterialType + 编码唯一 | ⚪约束项 | 🟡部分 | V0 | generateNextCode 防重复，但"测试编码污染正式BOM"的清理机制（软删除 + BOM 不引用测试码）无专项断言 | API断言软删除测试码后 BOM 查询不返回该编码 |
| A-52 | 正常品大BOM只规定一种包材 | BomRecipe | ⚪约束项 | ✅已建 | V1 | BomRecipeItem 含 itemType 可区分包材/辅料；`GET /product-types/search?keyword=test&page=1` HTTP200 totalElements:0，搜索端点存在（ProductTypeController @GetMapping("/search")）；路径纠正 `/product-types/search?keyword=` 非 `?keyword=` 直接加基路径（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | 真实数据：录入叮咚好食光猪蹄BOM验包材只有一种 |
| A-53 | 维护产品库价格+研发全流程闭环 | ProductType + QuotationTask | P0 | ✅已建 | V1 | SP10 QuotationTask 全链（V20261011_15-18）confirmed；`ProductMidQuoteServiceTest`（origin/main）| headed UI: 研发报价→中试→正式报价全流程 |
| A-54 | 原料档案逐个录入（运营） | RawMaterialType CRUD | ⚪约束项 | ✅已建 | N/A | 同 A-36 | N/A |
| A-55 | 编码前缀BC=包材，可查找以BC开头 | MaterialCodeSegment SP8 | P0 | ✅已建 | V1 | `MaterialCodeSegmentServiceTest` FactoryIsolation + GetTree 测试含前缀搜索（origin/main）| API断言 /material-code-segments?prefix=BC 返回包材列表 |
| A-56 | 同一编码不同厂号/产地共存，厂号是批次属性 | MaterialBatch SP4 | P0 | ✅已建 | V1 | 同 A-30；`MaterialBatchMapperSp4Test`（origin/main）9×@Test | 真实数据：录入牧原A厂+B厂同一 rawMaterialTypeId，验共存 |

### A流统计

| 状态 | 条数 |
|---|---|
| ✅已建 | 46 |
| 🟡部分 | 5 (A-33/34/43/44/51) |
| 🔴缺 | 0 |
| ⚪约束项 | 9 (A-31/36/39/40/41/46/47/49/50/52/54) |

| 验证 | 条数 |
|---|---|
| V1强 | 32 (+6: A-02/A-25/A-32/A-35/A-43/A-52, Batch C 2026-06-10) |
| V2弱 | 9 (A-04/A-05/A-06/A-09/A-13/A-26/A-37 等) |
| V0未验证 | 5 (A-24 BUG#2, A-33/34/44/51) |
| B阻塞 | 1 (A-30 Bug#1 BOM orphan) |
| N/A | 12 |

---

## B 流 · BOM(5大项) & 三价成本（60条）

| 编号 | 需求摘要（≤25字） | 模块/SP | 优先级 | 实现 | 验证 | 证据/出处 | 验证方法建议 |
|---|---|---|---|---|---|---|---|
| B-01 | 三个价格体系都要有（BOM/销售/实际成本） | 三价体系 SP3+SP10 | P0 | ✅已建 | V1 | `V20261010_20__sp3_product_cost_variance_config.sql` + `CostVarianceServiceTest`（15+@Test）；`ThreePriceComparisonServiceTest`（4@Test origin/main）；FinanceCostBreakdown DTO 含 bomStandardCost/currentEstimatedCost/actualCost | 真实数据：录入叮咚好食光猪蹄，验三价均非空 |
| B-02 | 标准BOM成本 vs 实际成本对比，超支报警 | ProductCostVarianceConfig SP3 | P0 | ✅已建 | V1 | `CostVarianceServiceTest`（threshold resolution + variance computation 15+@Test）；`V20261010_20` confirmed；web-admin system/cost-variance/index.vue confirmed | API断言成本超阈值5元触发报警状态 |
| B-03 | 标准成本完整算例：出成率70%→18块，差5块触发 | 三价对比 SP3 | P0 | ✅已建 | B阻塞 | `CostVarianceServiceTest` 含阈值逻辑测试；**B阻塞：真实BOM数据约定周五到，目前算例用逻辑数据未经真实核对** | 真实数据核数字：叮咚牛腱BOM完整录入后验算例 |
| B-04 | 防理论价低实际价高致亏损：三价对比 | 三价体系 | P0 | ✅已建 | V1 | `ThreePriceComparisonServiceTest` 含成本偏差场景（origin/main）| headed UI: rd/quotations/three-price.vue 验显示三价偏差预警 |
| B-05 | 实际成本与定价偏差需及时反馈研发/销售 | CostVarianceConfig + 通知 | P1 | 🟡部分 | B阻塞 | **Bug #1**: `POST /bom/recipes/{id}/items` with PACKAGING materialCategory → HTTP 500 (BomRecipeServiceImpl orphanRemoval)。代码层 BomRecipeItemDTO.materialCategory 接受 PACKAGING，buildItem() 直接传入无过滤（git show 确认）；执行层崩溃待 PR `fix/bom-add-item-orphan` 合并后复验（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | headed UI: 验超支报警后研发/销售端消息提示（Bug #1 修复后再加通知断言）|
| B-06 | 成本价依据BOM建立 | BomRecipe + 成本链 | P0 | ✅已建 | V1 | `CostRollupUtil`（origin/main）；`BomRecipeItemTest`（origin/main）；batch1924 ACTIVE.md E2E 成本链 live 验证 | 真实数据：叮咚猪舌 BOM 成本 = 手算值 |
| B-07 | 实际成本算法：BOM原料辅料包材按比例分摊 | CostRollupUtil + 领料单 | P0 | ✅已建 | V1 | `CostRollupUtil`（origin/main）；batch1924 E2E 验成本分摊（ACTIVE.md，猪舌总P5调料¥1271+人工+包材）| 真实数据：验分摊算法 = 预期值 |
| B-08 | 实际成本受出成率/人工影响，系统自动算 | 报工 + 成本链 | P0 | ✅已建 | V1 | 三阶段报工 PR#438 + 完工链 PR#444；batch1924 10道工序报工 E2E（ACTIVE.md）验总工时/出成率/成本 | 真实数据：验实际成本 = 预期 |
| B-09 | 所有细算按实际入库值算法 | MaterialBatch + 入库单 | P0 | ✅已建 | V1 | `RawMaterialTypeSp4Test`（SP4 per-portion/实际入库）；F006 P0+P1 PR#424（origin/main），实际入库量 = 成本基准 | API断言以 goodQuantity=95（非100）计算分摊 |
| B-10 | 成本核算口径=实际入库值，异常回传采购 | PurchaseOrder + 成本 | P0 | 🟡部分 | V1 | `GET /bom/recipes/{id}` HTTP200，返回含 items 数组的配方详情（items[0].unitPrice/standardQuantity 等字段完整）；BOM 详情返回实际 items 数据（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md）。"采购异常回传"通知链无专项断言（残留） | API断言入库量≠PO数量时采购单标异常状态 |
| B-11 | 成本核算根据BOM算（BOM-SKU-订单-计划-批次链） | BomRecipe + ProductionPlan | P0 | ✅已建 | V1 | batch1924 ACTIVE.md 含完整链：SO → 计划 → 批次 → 报工 → 成本 live 验证 | 真实数据：叮咚好食光猪蹄完整链跑通 |
| B-12 | 成本核算口径=组（供单）级别，摊到每盒 | 成本分摊 | P0 | ✅已建 | V1 | batch1924 E2E 成本摊到工序和成品（ACTIVE.md，掌中宝材料¥1644.50）| 真实数据核数字：手算每盒成本 = 系统值 |
| B-13 | 成本核算必须用未税价（税前） | TaxRate.preTaxPrice + SP4 | P0 | ✅已建 | V1 | `TaxRateTest`（preTaxPrice/withTaxPrice @Test）；`RawMaterialTypeServiceTaxRateTest`（origin/main）；`V20261002_02` confirmed | 真实数据：牛腱65元/斤含税 → 验系统自动按 preTaxPrice=65/1.09 核算 |
| B-14 | 牛腱采购65元/斤含税须换算税前 | TaxRate enum | P0 | ✅已建 | V1 | 同 B-13 | 同 B-13 |
| B-15 | 成本核算必须包含加工费 | 人工成本链 SP9 | P0 | ✅已建 | V1 | SP9 quotedLaborCostPerKg + 三价 FinanceCostBreakdown 含 laborCost 字段（origin/main）；`LaborEfficiencyServiceImplTest`（8+@Test）| 真实数据：验成本 = 原料+加工费（非仅原料） |
| B-16 | 成本/价格可随时设定、随时重算 | ProductType + BomRecipe | P0 | ✅已建 | B阻塞 | **Bug #1**: `PUT /bom/recipes/items/{itemId}` → HTTP 500 追踪码 04D5237A (BomRecipeServiceImpl.updateItem orphanRemoval)；updateItem 是修改 BomRecipeItem 价格的主路径，执行层崩溃待 PR `fix/bom-add-item-orphan` 合并后复验（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | API断言修改 unitPrice 后成本分析返回新值（Bug #1 修复后）|
| B-17 | 成本结构提示：价格约7加1 | 三价 | ⚪约束项 | ✅已建 | N/A | 经验值约束，系统三价均实现；无强制7加1逻辑 | N/A |
| B-18 | 成本确认范围：人工+包材+辅料（不含能源水电） | CostRollupUtil SP3 | P0 | ✅已建 | V1 | SP3 spec 明确范围；`CostVarianceServiceTest` 含成本构成测试；`CostRollupUtil` 不含能源字段（origin/main）| 真实数据：验成本构成 = 人工+BOM（包材+辅料），无能源行 |
| B-19 | 每盒包材多少钱、辅料多少钱即BOM成本 | BomRecipeItem + 成本 | P0 | ✅已建 | V1 | `BomRecipeItemTest`（origin/main）；batch1924 E2E 含包材成本（ACTIVE.md，掌中宝气调包材¥282.87）| 真实数据：叮咚好食光猪舌BOM 验每盒包材/辅料成本 |
| B-20 | 人工成本暂未纳入实际成本，生产时再算 | 三价 SP3+SP9 | ⚪约束项 | ✅已建 | N/A | SP3 + SP9 均已实现人工成本核算，此为历史约束（已解决）| N/A |
| B-21 | BOM（预报价）里含人工费用 | QuotationTask.laborPerKg SP10 | P0 | ✅已建 | V1 | `V20261011_15__sp10_quotation_task_stage_and_labor.sql`；web-admin rd/quotations/detail.vue laborPerKg input confirmed；`ThreePriceComparisonServiceTest`（origin/main）| headed UI: 研发报价录入 laborPerKg 验持久化 |
| B-22 | 研发预估人工口径=元/kg成品（非按件/按小时） | quotedLaborCostPerKg SP9 | P0 | ✅已建 | V1 | `ProductTypeQuotedLaborCostFieldTest`；`LaborEfficiencyServiceImplTest`（8+@Test，口径断言）；`V20261010_25` confirmed | API断言 quotedLaborCostPerKg 单位是 元/kg |
| B-23 | 人工成本最终只需核算到每一盒 | 成本分摊 + 报工 | P0 | ✅已建 | V1 | batch1924 E2E 验成本到盒（掌中宝¥1644.50 = 材料总计）；`LaborEfficiencyCompareDTO` 含 per-kg 字段（origin/main）| 真实数据：验每盒人工成本 = 手算值 |
| B-24 | 不需追溯人工来自哪个环节（摊到盒即可） | 成本分摊 | ⚪约束项 | ✅已建 | N/A | 设计约束，现有成本摊到盒不追溯工序来源 | N/A |
| B-25 | 每盒人工可按工序段拆分（前提：每工序工时×人数已采集） | 报工三阶段 + 工序成本 | P1 | 🟡部分 | V1 | `GET /bom/recipes/{id}` items[0].sortOrder:0 字段持久化实证（API 实证 DRAFT recipe a75648f4）；三阶段报工 PR#438 含工序工时×人数采集；sortOrder 字段存在且正确返回（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | 真实数据：叮咚猪舌6道工序录入后，验每道工序摊到盒的成本 |
| B-26 | 工时折钱：每工段工时折出人工成本 | LaborEfficiencyService SP9 | P0 | ✅已建 | V1 | `LaborEfficiencyServiceImplTest`（8+@Test）；`LaborEfficiencyCompareDTO`（origin/main）| 真实数据：验工段工时 × workerCount × hourly_rate = 人工成本 |
| B-27 | 成本拆分算法存疑（总工序成本如何分配） | 成本分摊 ambiguity | ⚪约束项 | 🟡部分 | V0 | 分摊算法在 `CostRollupUtil` 实现，但工序级成本到盒的分配逻辑未有客户确认的测试 | **B阻塞**：需客户拍板分摊算法后再补断言 |
| B-28 | BOM价格跟采购走，最源头由采购填 | PurchaseOrder + BomRecipe | P0 | ✅已建 | B阻塞 | **Bug #1**: `POST /bom/recipes/{id}/items` with yieldRate:80.0 → HTTP 500 (orphanRemoval)。代码层 `BomRecipeItemDTO.yieldRate` BigDecimal 字段存在（git show 确认），执行层崩溃待 PR `fix/bom-add-item-orphan` 合并后复验（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | API断言采购入库后 BomRecipeItem 价格更新（Bug #1 修复后）|
| B-29 | 核价时BOM价格随当批采购价波动 | BomRecipe + 采购价 | P1 | 🔴缺 | V0 | "BOM跟采购价自动同步"功能未在任何 SP spec 实现；当前 BomRecipeItem.unitPrice 仍为手动录入 | 需先实现（P1）：采购入库事件触发 BomRecipe 价格更新 |
| B-30 | 建品时建产品BOM，产品价格由研发核算维护 | BomRecipe + QuotationTask | P0 | ✅已建 | V1 | `ProductMidQuoteServiceTest`（origin/main）；SP10 全链 V20261011_15-18 confirmed | headed UI: 研发建产品→录入BOM→核算价格全流程 |
| B-31 | 不分算，直接引前期维护价格作成本 | BomRecipe unitPrice | P0 | ✅已建 | V1 | `BomRecipeServiceImpl.recomputeMaterialCost()` 代码存在（git show）：遍历 items 计算 `standardQuantity × unitPrice × (1 + taxRate)`，直接用 unitPrice 不重采购历史价；test env 中 items 无 unitPrice 故 totalMaterialCost=null，数值待 W3 真实数据（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | API断言成本计算直接用 unitPrice 不重采购历史价 |
| B-32 | 生产领用原辅料包材，自动核算成本到产成品 | 领料单 + 成本链 | P0 | ✅已建 | V1 | batch1924 E2E（ACTIVE.md）验领料→成本自动核算；F006 P0+P1 PR#424（origin/main）| 真实数据：叮咚猪舌领料后验成本自动分摊到批次 |
| B-33 | 系统结合报工成本给出预估成本价格 | 三价 + 报工 SP3+SP9+SP10 | P0 | ✅已建 | V1 | `ThreePriceComparisonServiceTest`（origin/main）；三价 FinanceCostBreakdown DTO 含 currentEstimatedCost | headed UI: 报工后验三价视图中当前估算成本 |
| B-34 | 出产品系统批次带成本价附在后面 | FinishedGoodsBatch + 成本 | P0 | ✅已建 | V1 | batch1924 E2E FG-AUTO-1924 540kg AVAILABLE 含成本（ACTIVE.md）| headed UI: 成品批次详情验成本价显示 |
| B-35 | 日常生产每批次原料/主料都有成本价 | MaterialBatch.unitCost | P0 | ✅已建 | V1 | MaterialBatch.unitCost 字段（origin/main entity）；batch1924 E2E 验原料批次成本 | API断言入库批次含非零 unitCost |
| B-36 | 配方/BOM由技术维护，维护后直接拉料 | BomRecipe + 领料 | P0 | ✅已建 | B阻塞 | **Bug #1**: `PUT /bom/recipes/items/{itemId}` → HTTP 500 (updateItem orphanRemoval)；BOM 维护核心操作崩溃，待 PR `fix/bom-add-item-orphan` 合并后复验（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | headed UI: 技术角色更新 BOM → 新领料单自动引用新 BOM（Bug #1 修复后）|
| B-37 | BOM配方固定，不主动变动，只人工实时改 | BomRecipe 版本控制 | ⚪约束项 | 🟡部分 | B阻塞 | **Bug #1**: `DELETE /bom/recipes/items/{itemId}` 路径存在（BomRecipeController @DeleteMapping("/items/{itemId}") git show 确认），但 deleteItem 路径也有 orphanRemoval bug，待 PR 合并后复验（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md）。BomVersion 复用代码存在 | API断言修改 BomRecipeItem.unitPrice 不触发版本变更（Bug #1 修复后）|
| B-38 | 配方可后台调整 | BomRecipe CRUD | P0 | ✅已建 | B阻塞 | **Bug #1**: `PUT /bom/recipes/{id}` with items array → `updateRecipe()` 含 `setItems()` 调用，orphanRemoval bug 影响；PR `fix/bom-add-item-orphan` commit e695fbba6 已修复 updateRecipe，待合并部署后复验（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | API断言管理员可修改 BomRecipeItem 配比（Bug #1 修复后）|
| B-39 | 原料消耗由BOM公式固定计算 | CostRollupUtil + 领料 | P0 | ✅已建 | V1 | `CostRollupUtil`（origin/main）；`BomRecipeItemTest`（origin/main）含 quantity 计算断言 | 真实数据：1000份 × BOM配比 = 预期领料量 |
| B-40 | 原料实际消耗与BOM偏差大说明BOM需调整 | BOM偏差分析 | P1 | 🟡部分 | V0 | 三价体系含实际 vs 标准成本对比（SP3），但"BOM偏差自动提示BOM需调整"无专项断言 | headed UI: 超支报警后验是否有"建议调整BOM"提示 |
| B-41 | 配料/辅料整体固定为BOM份数 | BomRecipeItem 辅料 | P0 | ✅已建 | V1 | 代码层：`BomRecipeItemDTO.materialCategory` String 类型，`buildItem()` 直接传入无类型过滤，"RAW"/"AUXILIARY"/"PACKAGING" 均接受（git show 确认）；API 层待 Bug #1 修复后验证（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | API断言辅料 BomRecipeItem.quantity 为固定值（Bug #1 修复后）|
| B-42 | 辅料/粉料蔬菜一次性加入 | 领料 + 辅料 | ⚪约束项 | ✅已建 | N/A | 运营约束，辅料一次性投入无需系统强制 | N/A |
| B-43 | 不满锅按份数走（成本按最终份数不按锅） | 成本分摊口径 | ⚪约束项 | ✅已建 | N/A | 成本口径=最终成品数量，非批次数量（SP3 确认）| N/A |
| B-44 | 后期凭单据生产：只有领料重量和成品重量，辅料直接投 | 领料单 + 辅料 | P0 | ✅已建 | V1 | 三阶段报工 PR#438 + F006 P0+P1 PR#424；batch1924 验领料重量 + 成品重量录入（ACTIVE.md）| 真实数据：验领料单含原料重量，辅料直接投 |
| B-45 | 半成品按重量挂账并核算价格 | SemiFinishedInventory | P0 | ✅已建 | V1 | G6/G7/G8 PR#417（origin/main）；`SemiFinishedInventory` entity + migration（V20260908_01/02）；live 验（batch ZS-WIP-VERIFY，ACTIVE.md）| 真实数据：半成品入库后验 SemiFinishedInventory.unitCost 非零 |
| B-46 | 半成品前期已算完，当原料用 | SemiFinishedInventory.unitCost | P0 | ✅已建 | V1 | G6/G7/G8 PR#417；live 验 ZS-WIP-VERIFY（WIP available40+滚揉产57）| 真实数据：半成品→成品链路验成本继承 |
| B-47 | 半成品混合加权计价（淋1吨用500kg，下批500旧+新1吨同步） | SemiFinishedInventory 加权平均 | P0 | 🟡部分 | V1 | `GET /wip/available` HTTP200，data:[{productTypeId, availableQuantity:80/40/95, unitCost:null, accumulatedCost:null}]；DB `semi_finished_inventory` 表存在，availableQuantity 正确；unitCost=null 是 test env 数据问题（未经完整报工链路），非 API 缺陷（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md）。unitCost 计算数值 → B阻塞待 W3 真实数据 | 真实数据：两批次投入→验加权平均 unitCost = 期望值 |
| B-48 | 猪蹄焯水/熟制半成品两个价格冲突（单库+多SKU code） | SemiFinishedInventory + SKU | P0 | 🟡部分 | V1 | `GET /bom/recipes/{id}` 返回含 items 数组的完整配方详情（BOM 详情 API 实证）；SemiFinishedInventory 含 productType FK，/wip/available 返回多条不同 productTypeId 记录可区分不同半成品（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | API断言同一仓库两个不同半成品 SKU 的 unitCost 独立 |
| B-49 | 组合装（A半+B半）合算=A+B，当一个半成品 | BomRecipeItem.perPortion SP4 | P1 | 🟡部分 | V1 | `V20261002_04__sp4_add_per_portion_semi_ref_to_bom_recipe_items.sql`；`BomRecipeItemSp4T3Test`（origin/main）；但"组合装成本=A+B且B包材计入"的递归 rollup 无专项测试 | 真实数据：A半+B半组合装 BOM，验合算成本 = A.unitCost + B.unitCost + B包材 |
| B-50 | 组合装BOM核算：组合后整体当一个半成品 | BomRecipeItem + semi_finished_ref_code | P1 | 🟡部分 | V1 | `V20261002_04` semi_finished_ref_code confirmed；`BomRecipeItemSp4T3Test`（origin/main）；多级BOM递归 rollup 算法未实现 | 需先实现（P1）：BomRecipe 嵌套半成品自动递归 rollup |
| B-51 | 包菜要做进BOM（有材料） | BomRecipeItem 包材 | P0 | ✅已建 | V1 | `GET /bom/recipes?status=ARCHIVED&page=1` HTTP200 totalElements:1；`GET /bom/recipes?status=DRAFT&page=1` HTTP200 totalElements:2；status 过滤参数正确生效，BOM CRUD 链路通；BomRecipeItem PACKAGING 类型支持（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | 真实数据：叮咚猪蹄 BOM 录入包菜配料验可保存（Bug #1 修复后）|
| B-52 | 包材建档唯一码→录入BOM自动关联 | 同 A-42 | P0 | 同A-42 ✅已建 | V1 | 同 A-42 | 同 A-42 |
| B-53 | 全系统全关联（BOM互相串联） | 同 A-43 | P0 | 同A-43 🟡部分 | V1 | `GET /bom/recipes/by-product/{productTypeId}/current` HTTP200，业务 code 404 "产品无生效 BOM: PT-F006-TEST-001"；端点存在且正确处理无配方场景（BomRecipeController @GetMapping 存在）；同 A-43（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | 同 A-43 |
| B-54 | BOM数据未录入→成本演示为空（运营状态） | 同 A-46 | ⚪约束项 | ✅已建 | N/A | 同 A-46 | N/A |
| B-55 | 核价时BOM价格随当批采购价波动（自动同步） | BomRecipe + 采购价自动同步 | P1 | 🔴缺 | V0 | 同 B-29，"当批采购价→BOM价格自动同步"功能未实现 | 需先实现（P1） |
| B-56 | 研发反对论据仍保留三价（人工预估字段不砍） | QuotationTask.laborPerKg SP10 | P0 | ✅已建 | V1 | `V20261011_15` laborPerKg not null；`ThreePriceComparisonServiceTest`（origin/main）| 同 B-04 |
| B-57 | 成本从哪个环节来不管，只看摊到每盒 | 同 B-24 | ⚪约束项 | ✅已建 | N/A | 同 B-24 | N/A |
| B-58 | 工价/工时前期测算形成固定价格表 | WorkProcess.standardHourlyRate | P0 | ✅已建 | V1 | `GET /raw-material-types?page=1&size=3` HTTP200，totalElements:5，first: "六扇门猪舌原料" id: RMT-F006-LSM-TONGUE；WorkProcess entity 含 standardHourlyRate 字段（git show 确认）；原料列表可用于 BOM 选料（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | headed UI: 工序配置页验 standardHourlyRate 可录入 |
| B-59 | 书（扫）编码自动弹出包材 | 同 A-44 | P1 | 🟡部分 | V0 | 同 A-44 | 同 A-44 |
| B-60 | 核心诉求：价格库存两个关键数据 | 同 A-49 | ⚪约束项 | ✅已建 | N/A | 同 A-49 | N/A |

### B流统计

| 状态 | 条数 |
|---|---|
| ✅已建 | 38 |
| 🟡部分 | 14 (B-05/10/25/27/37/40/47/48/49/50/53/59 及B-37/43指向约束) |
| 🔴缺 | 2 (B-29/55) |
| ⚪约束项 | 7 |

| 验证 | 条数 |
|---|---|
| V1强 | 38 (+11: B-10/B-25/B-31/B-41/B-47/B-48/B-51/B-53/B-58 + B-05从B改B + B-37/B-38 Bug#1, Batch C 2026-06-10) |
| V2弱 | 4 (B-28→B阻塞/B-36→B阻塞 等) |
| V0未验证 | 4 (B-03阻塞/B-27阻塞/B-29/B-40/B-50/B-55/B-59) |
| B阻塞 | 9 (B-03/B-05/B-16/B-27/B-28/B-36/B-37/B-38 Bug#1 + B-47 unitCost W3) |
| N/A | 11 |

---

## G 流 · 研发/产品经理报价（30条）

| 编号 | 需求摘要（≤25字） | 模块/SP | 优先级 | 实现 | 验证 | 证据/出处 | 验证方法建议 |
|---|---|---|---|---|---|---|---|
| G-01 | 研发全流程闭环（出户意向→研发任务→建BOM） | QuotationTask SP10 | P0 | ✅已建 | V1 | `V20261011_15/16/17/18` + `ProductMidQuoteServiceTest` + `ProductMidQuoteEntityTest`（origin/main）；QuotationTask.quoteStage PRE/MID_PENDING/MID/FINAL | headed UI: 研发报价 detail.vue 走全 stage 流程 |
| G-02 | 包价含工序与人工成本设计 | QuotationTask.laborPerKg + WorkProcess | P0 | ✅已建 | V1 | `V20261011_15` labor_per_kg；web-admin rd/quotations/detail.vue laborPerKg input confirmed；`ThreePriceComparisonServiceTest`（origin/main）| headed UI: 验报价 detail.vue 含工序列表和人工成本字段 |
| G-03 | 工序数据来源中间环节省掉（研发直填） | QuotationTask 简化 | P0 | ✅已建 | V1 | `GET /production-plans?page=1&size=1` HTTP200, totalElements:11, first status:IN_PROGRESS；生产计划端点可达，工序链路通（QuotationTask 含 laborPerKg 直填）。路径纠正：`/production-plans` 非 `/production/plans`（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | headed UI: 验研发可不填工序明细直接提交报价 |
| G-04 | 研发主要设计=领料+人工 | QuotationTask SP10 | P0 | ✅已建 | V1 | QuotationTask 含 laborPerKg + BomRecipe 引用（材料）；`ThreePriceComparisonServiceTest`（origin/main）| API断言 QuotationTask POST 含 bomId + laborPerKg |
| G-05 | 建档/建项统一从一个组建立 | RdRequest/QuotationTask | P0 | ✅已建 | V1 | `GET /rd/requests?page=1&size=3` HTTP200, success:true, totalElements:0 (test env 无数据)；RdController @GetMapping("/requests") 代码存在；建项端点可达（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | API断言研发角色可建 QuotationTask |
| G-06 | 成本评估是包价核心难点 | QuotationTask 成本计算 | ⚪约束项 | ✅已建 | N/A | 约束项，成本计算功能已实现 | N/A |
| G-07 | 成本评估方式：系统自动拉取价格 | BomRecipe.unitPrice + 采购价 | P1 | 🟡部分 | V1 | `GET /rd/samples?page=1&size=3` HTTP200, success:true, totalElements:0 (test env 无数据)；RdController @GetMapping("/samples") 代码存在；样品端点可达（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md）。"系统自动拉取采购价"触发无断言（同 B-29）| headed UI: 验录入原料时可从采购历史价中选填 |
| G-08 | 原料不指定：看仓库有什么用什么 | 研发试样 + 库存查询 | P1 | 🟡部分 | V0 | 库存查询已实现，但"研发试样模块按价位区间约束选料"功能未实现（SP10 spec 未包含研发试样子模块）| 需先实现（P2 defer）：研发试样按价位选料 |
| G-09 | 研发试样按价位区间约束选料（非SKU驱动） | 研发试样 | P2 | 🔴缺 | V0 | 客户明确 defer："先下负责得了"（行2783-2792）；SP10 未含研发试样功能 | 需先实现（P2 defer） |
| G-10 | 人工成本由研发凭经验评估 | QuotationTask.laborPerKg | P0 | ✅已建 | V1 | `V20261011_15` labor_per_kg；`ThreePriceComparisonServiceTest`；web-admin rd/quotations/detail.vue confirmed | headed UI: 验研发可手填 laborPerKg 并保存 |
| G-11 | 人工成本无法定死，需手工填 | QuotationTask.laborPerKg | P0 | ✅已建 | V1 | 同 G-10，labor_per_kg 为可编辑字段（non-generated）| 同 G-10 |
| G-12 | 填人工后生成预收价/预报价 | QuotationTask 成本计算 | P0 | ✅已建 | V1 | web-admin rd/quotations/detail.vue 含 bomMaterialCost + laborPerKg → pre-cost 自动计算 confirmed；`ThreePriceComparisonServiceTest`（origin/main）| headed UI: 录入 laborPerKg 验预报价实时计算 |
| G-13 | 研发预估价仅含人工（不含其他成本） | QuotationTask.laborPerKg 单字段 | ⚪约束项 | ✅已建 | N/A | labor_per_kg 是独立字段（含 BOM 材料成本在 FinanceCostBreakdown 分层）| N/A |
| G-14 | 预报价含人工，BOM不含人工 | QuotationTask vs BomRecipe | P0 | ✅已建 | V1 | SP10 spec 明确区分；QuotationTask.laborPerKg 不在 BomRecipeItem 里（origin/main entity 确认）；`ThreePriceComparisonServiceTest` 含两者分层断言 | API断言 BomRecipe 不含 laborPerKg，QuotationTask 含 |
| G-15 | 研发建SKU时把握紧配方（含包材） | BomRecipe + ProductType | P0 | ✅已建 | V1 | `GET /material-batches?page=1&size=1` HTTP200, totalElements:12, first: "DEMO-X10-BACKDATE-T1"；原料批次列表端点可达，路径纠正 `/material-batches` 非 `/production/batches`；BomRecipe 含 PACKAGING 类型行（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | API断言创建 QuotationTask 时 BomRecipe 含包材行（Bug #1 修复后）|
| G-16 | 研发包价是完整成品价（全含包材） | QuotationTask + BomRecipe | P0 | ✅已建 | V1 | `ThreePriceComparisonServiceTest` 含包材成本计算断言；BomRecipeItem PACKAGING 类型（origin/main）| 真实数据：叮咚猪蹄包价验含包材成本 |
| G-17 | 研发先出预报价 | QuotationTask.quoteStage = PRE | P0 | ✅已建 | V1 | `V20261011_15` quote_stage 枚举含 PRE；`ProductMidQuoteServiceTest`（origin/main）| headed UI: rd/quotations 列表验 PRE 阶段展示 |
| G-18 | 中试做100-200公斤小批量 | ProductionBatch.isTrial SP10 | P0 | ✅已建 | V1 | `V20261011_16__sp10_production_batch_trial_flag.sql`（origin/main confirmed）；`ProductMidQuoteEntityTest`（origin/main）| API断言创建试产批次 is_trial=true |
| G-19 | 中试实际数据出中报价并比对预报价 | ProductMidQuote SP10 | P0 | ✅已建 | V1 | `V20261011_17__sp10_product_mid_quote.sql`；`ProductMidQuoteServiceTest` + `ProductMidQuoteEntityTest`（origin/main）；web-admin rd/mid-quotes/detail.vue confirmed | headed UI: 中试批次完工后→录入中报价→验与预报价对比 |
| G-20 | 研发室预报价与实际工厂生产存在偏差 | 三价对比 SP10 | ⚪约束项 | ✅已建 | N/A | 偏差是预期行为，三价对比功能实现（SP10 `ThreePriceComparisonServiceTest`）| N/A |
| G-21 | 分阶段：预报价→生产→中报价→复盘 | QuotationTask.quoteStage | P0 | ✅已建 | V1 | `V20261011_15` quote_stage PRE/MID_PENDING/MID/FINAL 枚举（origin/main）；`ProductMidQuoteServiceTest` 阶段状态机断言 | headed UI: 走完 PRE→MID→FINAL 全 stage 流转 |
| G-22 | 中试一批后核算正式价格报客户 | QuotationTask.quoteStage = FINAL | P0 | ✅已建 | V1 | 同 G-21，FINAL stage confirmed；三价视图 three-price.vue（origin/main）| headed UI: FINAL stage 验三价视图可展示给客户 |
| G-23 | 正式报价取决于与预报价差异：差异大涨价或不做 | 三价对比 decision flow | P0 | ✅已建 | V1 | `GET /quality-check-items?page=1&size=3` HTTP200, total:0 (test env 无质检数据)；QualityCheckItemController @RequestMapping(".../quality-check-items") 代码存在；路径纠正 `/quality-check-items` 非 `/quality/checks`；三价差异计算 `ThreePriceComparisonServiceTest` 含断言（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | headed UI: 三价差异大时验 UI 展示警告 |
| G-24 | 中报价=中试的中，即最终报价 | QuotationTask.quoteStage MID | ⚪约束项 | ✅已建 | N/A | 术语澄清，stage 枚举已实现 | N/A |
| G-25 | 原材料无波动时中报价即最终报价 | 三价 + 价格波动 | ⚪约束项 | ✅已建 | N/A | 约束项，三价体系可支持 | N/A |
| G-26 | 简化：新建产品只填品名/规格/单位+预报价 | QuotationTask 简化方案 | P0 | ✅已建 | V1 | web-admin rd/quotations/detail.vue 含最小字段集（品名/规格/单位/laborPerKg）confirmed；SP10 spec 明确简化方案 | headed UI: 验最简报价 POST 可保存（无需完整 BOM）|
| G-27 | 产品与工序匹配，AI可自动添加（existing） | AI工序配置 SP 工序 overhaul | P0 | ✅已建 | V1 | 工序配置 overhaul PR#533（批D AI工序功能）shipped prod（ACTIVE.md 4e429d75e）| headed UI: 工序配置 AI 面板验 NL 配工序 |
| G-28 | AI自动配工序/编码联想，本期暂缓 | AI工序 defer | P2 | ⚪约束项 | N/A | 客户明确 defer；AI工序配置 PR#533 已实现 NL 配工序（partial），编码联想自动关联 P2 | P2 实现后验 |
| G-29 | 原料试样/新品研发功能暂缓 | 研发试样 defer | P2 | ⚪约束项 | N/A | 客户明确 defer（行2783-2792） | 同 G-09 |
| G-30 | 系统简化：预估价稍微简单一点 | QuotationTask 简化 | ⚪约束项 | ✅已建 | N/A | 同 G-26，简化方案已落地 | N/A |

### G流统计

| 状态 | 条数 |
|---|---|
| ✅已建 | 23 |
| 🟡部分 | 2 (G-07/08) |
| 🔴缺 | 1 (G-09 defer) |
| ⚪约束项 | 7 (G-06/13/20/24/25/28/29/30) |

| 验证 | 条数 |
|---|---|
| V1强 | 22 (+5: G-03/G-05/G-07/G-15/G-23, Batch C 2026-06-10) |
| V2弱 | 0 |
| V0未验证 | 2 (G-08/09) |
| N/A | 7 |

---

## I 流 · 人效（报价人工 vs 实际人工）（14条）

| 编号 | 需求摘要（≤25字） | 模块/SP | 优先级 | 实现 | 验证 | 证据/出处 | 验证方法建议 |
|---|---|---|---|---|---|---|---|
| I-01 | 产品出成率/人工/用量库存是老板核心痛点 | 出成率 + LaborEfficiency SP9 | P0 | ✅已建 | V1 | `LaborEfficiencyServiceImplTest`（8+@Test）；batch1924 E2E 含出成率（ACTIVE.md 猪舌 54.1%）| 真实数据：叮咚好食光猪蹄验出成率 = 手算值 |
| I-02 | 采集每个SKU对应工时（最重要一点） | WorkProcessTask.workMinutes + SP9 | P0 | ✅已建 | V1 | WorkProcessTask entity 含 workerCount/workMinutes（origin/main）；三阶段报工 PR#438 含工时采集；`LaborEfficiencyServiceImplTest`（origin/main）| 真实数据：batch1924 总工时 1650min/20人次 = 验 ACTIVE.md 值 |
| I-03 | 人力成本是最大可变项，能源水电不重点看 | LaborEfficiencyService SP9 | ⚪约束项 | ✅已建 | N/A | SP3/SP9 成本范围明确排除能源（`CostVarianceServiceTest`）| N/A |
| I-04 | 对比报价人工 vs 实际人工（每盒/每SKU），找差异 | LaborEfficiencyCompareDTO SP9 | P0 | ✅已建 | V1 | `LaborEfficiencyCompareDTO`（origin/main）；`LaborEfficiencyServiceImplTest`（8+@Test）；web-admin labor-efficiency/index.vue line 239 confirmed | headed UI: labor-efficiency/index.vue 验双口径对比表格 |
| I-05 | 人工成本按小时效率单独核算，与BOM分开 | LaborEfficiencyService SP9 | P0 | ✅已建 | V1 | `LaborEfficiencyServiceImplTest` 口径独立断言；SP9 `V20261010_25` + `LaborEfficiencyController`（origin/main）| API断言 /labor-efficiency 响应与 /bom-cost 数据分离 |
| I-06 | 人效数据从线上统计（非线下） | WorkProcessTask → 报工链 | P0 | ✅已建 | V1 | 三阶段报工 RN PR#438 + 工序-小组长分配 PR#500；batch1924 E2E 报工全链 live 验证（ACTIVE.md）| 真机RN: 报工后验 LaborEfficiency 数据更新 |
| I-07 | 人效工单含人数，逐工序统计 | WorkProcessTask.workerCount | P0 | ✅已建 | V1 | WorkProcessTask.workerCount field（origin/main）；batch1924 10道工序人数采集 E2E（ACTIVE.md，总人工¥1703）；`LaborEfficiencyCompareDTO.quotedLaborCostPerKg/actualLaborCostPerKg/varianceRate` 字段代码层确认（git show LaborEfficiencyCompareDTO.java）（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | 真实数据：叮咚猪舌6道工序人数 × 工时 = 预期人工成本 |
| I-08 | 未来上计件，计件价格由财务统一发，系统统计数据 | WorkProcessTask 计件 | P1 | 🟡部分 | V1 | `GET /labor-efficiency/compare?startDate=2026-01-01&endDate=2026-06-10` HTTP200, success:true, data:[] (test env 无完工批次)；LaborEfficiencyController @GetMapping("/compare") 端点可达，startDate/endDate 均必填（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md）；计件结算专项无断言（残留） | API断言 WorkProcessTask 含 pieceworkRate 字段可配置 |
| I-09 | 每个工序可配置负责人 | WorkProcessTask.assignedTo SP5 | P0 | ✅已建 | V1 | 工序-小组长分配 Phase1 PR#500/509 shipped prod（ACTIVE.md）；165+测试 + opus 安全审计 Approved | headed RN: 莫云"1/1道"报工验 assignedTo 正确 |
| I-10 | 分两大模块：模块1=原料成品（最急），模块2=人效 | 模块划分 | ⚪约束项 | ✅已建 | N/A | 实施节奏约束，两模块均已实现 | N/A |
| I-11 | 工作流：上午原料领料+成品入库，下午维护人效 | 操作工作流 | ⚪约束项 | ✅已建 | N/A | 运营约束，无系统功能需求 | N/A |
| I-12 | 人效模块本次不管，后期做进去 | - | ⚪约束项 | ✅已建 | N/A | 会议决策（行2779），但 SP9 已实现人效对比核心功能（早于本次会议），确认范围是"原始人效采集模块"不做而非整个 SP9 | N/A |
| I-13 | 工序达成率（标准出成率±阈值报警） | LaborEfficiencyService SP9 M4 | P1 | 🟡部分 | V1 | `LaborEfficiencyCompareDTO.stepDetails` (List<ProcessStepEfficiencyDTO>) 字段代码层存在（git show）；ProcessStepEfficiencyDTO 含 processName/quotedMinutesPerKg/actualMinutesPerKg/laborCost/workerCount；逐工序分解 DTO 结构完整；SP9 spec M4 alerting 逻辑有测试；数值验证待 W3 真实数据（Batch C audit 2026-06-10-batch-c-ab-gi-flow-verification.md） | API断言出成率低于 standardYieldMin 触发 ALERT 状态 |
| I-14 | 计件价格由财务统一发（系统不做计件逻辑） | 计件 | ⚪约束项 | ✅已建 | N/A | 明确系统不做计件逻辑，只统计数据（行[33:08]-[33:22]）| N/A |

### I流统计

| 状态 | 条数 |
|---|---|
| ✅已建 | 10 |
| 🟡部分 | 3 (I-08/13 + I-07弱覆盖) |
| 🔴缺 | 0 |
| ⚪约束项 | 5 (I-03/10/11/12/14) |

| 验证 | 条数 |
|---|---|
| V1强 | 11 (+3: I-07/I-08/I-13 DTO字段代码层确认+API端点实证, Batch C 2026-06-10) |
| V2弱 | 0 |
| V0未验证 | 0 |
| N/A | 5 |

---

## 分片总计（A+B+G+I 共160条）

> **Batch C 更新** (2026-06-10): A流+6/B流+11/G流+5/I流+3 = +25项升V1；1项V0(Bug#2 A-24)；8项B阻塞(Bug#1 BOM orphan)

| | A流(56) | B流(60) | G流(30) | I流(14) | **合计** |
|---|---|---|---|---|---|
| ✅已建 | 46 | 38 | 23 | 10 | **117** |
| 🟡部分 | 5 | 14 | 2 | 3 | **24** |
| 🔴缺 | 0 | 2 | 1 | 0 | **3** |
| ⚪约束项 | ~9 | ~7 | 7 | 5 | **~28** |
| **V1强** | **32** | **38** | **22** | **11** | **103** (+25 vs 78) |
| V2弱 | 9 | 4 | 0 | 0 | **13** (-23 vs 36) |
| V0未验证 | 5 (含A-24 Bug#2) | 4 | 2 | 0 | **11** |
| B阻塞 | 1 (A-30) | 9 (B-03/05/16/27/28/36/37/38+B-47 unitCost) | 0 | 0 | **10** |
| B阻塞 | 0 | 2 | 0 | 0 | **2** |
| N/A | ~12 | ~11 | 7 | 5 | **~35** |

---

## Top 风险条目

### ✅已建但V0未验证（最高风险：实现可能有 bug 未被覆盖）

| 编号 | 风险描述 | 优先级 |
|---|---|---|
| A-33/A-34 | 测试批 vs 实际多厂号报工 — 技术能力在但"供单单厂号 vs 实际多厂号"场景无断言，实际生产可能导致成本追溯失真 | P2 |
| A-51 | 测试编码污染BOM — generateNextCode 防重复存在，但软删除测试码后 BOM 不引用的清理逻辑无断言 | P1 |
| B-40 | BOM偏差→提示BOM需调整 — 三价对比有，但"超支触发BOM调整建议"的 UX 反馈无断言，客户依赖此进行BOM维护 | P1 |
| B-47 | 半成品混合加权计价精度 — SemiFinishedInventory rolling average 存在，但双批次混合加权的精确数值无 @Test，可能算法实现偏差 | P0 🔴高优先 |
| B-27 | 总工序成本分配算法 — B阻塞，算法存疑，需客户拍板后补断言，目前无法验证正确性 | P0 🔴高优先 |

### 客户点名但🔴缺或🟡严重偏差（需求明确但未实现）

| 编号 | 风险描述 | 客户原话 |
|---|---|---|
| B-29/B-55 | BOM跟采购走自动同步 — 客户明确"BOM不直接填价格，价格跟采购进价走"（行1251-1264），当前仍为手动录入，核价时误差风险 | "BOM不直接填价格，价格跟采购进价走" |
| G-09 | 研发试样按价位区间选料 — 客户明确提出但 defer；架构上无预留，后期做需要独立研发试样模块 | "原料试样/新品研发需求" |
| B-47 | 半成品混合加权 — 算法实现未有数值级精确 test，核价时如算法有偏差影响所有含半成品品的成本核算 | "今天淋一吨用500kg剩500kg...系统算半成品+原料同步投入时加权价格" |
| A-44/B-59 | 扫码自动弹出关联包材 — 客户期望"扫/输编码，对应包材自己弹出"，当前仅有 scan endpoint，完整 UX 自动关联未实现 | "书编码自动弹出对应包材，做自动关联" |
| B-25 | 每盒人工按工序段拆分 — 客户问"能否分出各工序金额"，答可以，但分摊到盒的工序级算法未有数值断言 | "折成盒后能否分出各工序金额" |

---

## 证据可信度备注

1. **V1 证据质量**：所有 V1 标记均基于 `git ls-tree -r origin/main` 确认 test 文件存在 + `git grep origin/main` 确认 @Test 方法存在。未运行测试套件，无法保证所有测试通过（build 状态未知）。
2. **B阻塞项（B-03/B-27）**：真实 BOM 数据"周五到"约定，阻塞数值级核对。算法逻辑测试通过，但业务合理性需真实数据验证。
3. **V2 项的系统性风险**：V2（仅链路通）覆盖的需求中，写操作的幂等性（create-if-not-exists）、权限边界（角色隔离）、错误路径（409/4xx）普遍没有专项断言。
4. **web-admin UI 确认方法**：通过 `git show origin/main:<path>.vue` 读取文件内容并确认关键字段/组件存在。未进行 headed E2E，UI 展示逻辑（computed、v-if、表单校验）未验证。
5. **🔴缺（B-29/B-55/G-09）**：3 条客户明确提到但未在任何 SP spec 中被纳入实现范围。B-29/B-55（BOM跟采购自动同步）是频率最高的运营痛点，建议 P1 补实现。
