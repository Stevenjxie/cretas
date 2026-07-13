# ② 生产计划「多选成品 → 解析共用 raw workflow」实现设计 (fable 调研产出)

> 承接 ① 产品-工序配置双模式 (feat/workflow-dual-mode, commit 243109441)。
> raw-centric 一图多SKU: `memory/project_2026_07_13_raw_centric_multi_sku.md`。

## 核心机制 (复用, 零改动)
- DB INSERT 触发器 `pin_production_batch_workflow_selection` (Flyway V20261028_53) 按 batch.product_type_id → activation 填 selection; FK 强制 batch.pt==workflow.pt; materialize L99 同款过滤; compiler 全图拓扑建 task 分支无关。
- **🔴 skipProcessReporting=true 短路 materialize**: `WorkProcessTaskServiceImpl.spawnTasks` L196 skip=true 直接两点哨兵不调 materializeIfActive; F006 工厂默认 true → workflow 计划必须 skip=false。

## 反向索引 = 运行时扫描 (不建表)
扫 enabled activation → 过滤 owner category==RAW_MATERIAL → 复核 activation.activeDefinitionVersion==workflow.definitionVersion && PUBLISHED → parse nodesJson 抽 kind==FINISHED_GOOD 的 data.skuId 集合。F006 ~176 产品, enabled 至多几十行, 只读端点非热路径, 毫秒级。坏图剔除+log.error 不瘫全厂。

## 新增 (后端 4 文件)
1. Flyway `V20261028_58__production_plan_target_finished_goods.sql` (号落地重查, 当前最高 _57): `ALTER TABLE production_plans ADD COLUMN target_finished_good_ids jsonb;`
2. `dto/workflow/WorkflowOutputResolutionDTO.java`: Request{productTypeIds}, DTO{requestedProductTypeIds, resolutionMode(SELF_WORKFLOW/RAW_OWNED/NONE), candidates[]}, Candidate{workflowId, definitionVersion, ownerProductTypeId, ownerProductName, ownerProductCategory, ownerUnit, terminalOutputs[], exactMatch}, TerminalOutput{productTypeId, productName, unit}
3. `service/workflow/ProductWorkflowResolutionService.java`: `resolveForOutputs(factoryId, ids)` + `assertActiveWorkflowCoversOutputs(factoryId, ownerId, targets)`
4. `service/workflow/impl/ProductWorkflowResolutionServiceImpl.java`: 算法见下。JSON 读法复用 `ProductProcessWorkflowActivationServiceImpl.toDefinition` 同款 TypeReference。

**resolveForOutputs 算法**: 去重非空(空→400 WORKFLOW_RESOLUTION_EMPTY_SELECTION)→逐个 findByIdAndFactoryId 校验存在(缺→404)→ **单选优先自有图**: size==1 且该成品 enabled activation 且 owner 非 RAW → SELF_WORKFLOW 短路 → 否则扫 raw-owned 候选(终端⊇所选)排序(exactMatch>终端少>activatedAt新)→ 0 候选返 NONE 空列表**不报错**(报错留写路径)。

## 修改 (后端 7 文件)
5. `ProductProcessWorkflowActivationRepository`: `findByFactoryIdAndEnabledTrue`
6. `ProductProcessWorkflowRepository`: `findByIdInAndFactoryId`
7. `ProductProcessWorkflowController`: `@PostMapping("/resolve-by-outputs")` 注入 resolutionService (L44 构造器同步)
8. `dto/production/CreateProductionPlanRequest`: `List<String> targetFinishedGoodIds`
9. `entity/ProductionPlan`: jsonb 字段镜像 sourceOrderIds L207-209 (JsonBinaryType, 默认 null 不 new ArrayList)
10. `dto/production/ProductionPlanDTO` + `mapper/ProductionPlanMapper`: 字段透传 toEntity/toDTO
11. 🔒 `service/impl/ProductionPlanServiceImpl`: **只加不改**
    - `createProductionPlan`: 产品存在校验后、skip 工厂默认(L881)**之前**插守卫块 (targetFinishedGoodIds 非空时): assertActiveWorkflowCoversOutputs + skip=true→400 WORKFLOW_PLAN_REQUIRES_STEPWISE + null 强制 false
    - `createBatchFromPlan` L4056 前: plan.targetFinishedGoodIds 非空时复核 assertActiveWorkflowCoversOutputs (activation 窗口期可能被切); 除此零改动
    - assertActiveWorkflowCoversOutputs 失败 → 409 WORKFLOW_RESOLUTION_NOT_COVERED "所选成品没有对应的通用(原料)工序配置, 请先配置"

## 前端 (2 文件)
12. `api/productionPlan.ts`: `resolveWorkflowByOutputs(factoryId, productTypeIds)` POST + TS 类型
13. `views/production/plans/list.vue` (非 CUSTOMER_ORDER 分支):
    - planForm 加 targetFinishedGoodIds[]/resolvedCandidates[]/selectedCandidateOwnerId/resolutionMode
    - 「产品类型」form-item(L3202附近) 非 SO 改「生产成品」el-select **multiple** filterable (过滤 category !== RAW_MATERIAL/PACKAGING/SEASONING; null category 遗留产品保留可选)
    - watch 选中集(去抖300ms)→ resolveWorkflowByOutputs → 渲染候选区:
      - SELF_WORKFLOW: info tag「用该成品自己的图」, selectedCandidateOwnerId=成品自身, 锁逐道
      - RAW_OWNED 1候选: 自动选+摘要卡(owner原料名+终端chips+exactMatch tag)
      - RAW_OWNED N候选: el-radio-group 卡片选(每卡 owner名/终端/exactMatch), 未选禁提交
      - NONE 多选: el-alert error「所选成品没有对应通用(原料)工序配置请先配置」+ 按钮「去产品工序配置」router.push('/system/product-processes') /「改单成品分别建」; 禁提交
      - NONE 单选: 维持现状 productTypeId=所选成品, 照旧 handleProductChange, 不传 targetFinishedGoodIds
    - 命中候选: skipProcessReporting 强制 false + switch disabled(镜像 reportModeLocked L317); 数量 label「计划投料数量({ownerUnit})」; SAFETY_STOCK 仍隐藏数量
    - submitPlan(非SO分支 L753): productTypeId=selectedCandidateOwnerId, targetFinishedGoodIds=所选, skip=false; catch 409 WORKFLOW_RESOLUTION_NOT_COVERED→ElMessageBox 带跳转
    - 列表「产品」列 L2714 + 详情 L2999: targetFinishedGoodIds 非空时 owner 名后追加成品 chips(→五香/红烧/泰式); 名字用 productTypes 映射, 查不到显 id+已删除

## 边界 (关键几条)
- B1 成品既有自有图又是原料图终端(单选): SELF 优先自有图
- B3 图终端是所选超集(3终端选2产): 允许; materialize 不裁剪(全图建task), 第3支不报即不产不扣; targetFinishedGoodIds 仅记录/校验不改runtime (裁剪留Phase2)
- B5 单位: plannedQuantity=投料量kg, plannedUnit 经 L321 自动取 owner 单位; 成品入库量由逐道产出决定与计划数量解耦
- B6 skip 短路(见上): 锁逐道+400+null强制false(须在工厂默认前)
- B7 并发: create 内复核 + createBatchFromPlan 复核 + 触发器 advisory lock
- B10 版本漂移: 解析复核 definitionVersion==active && PUBLISHED
- B12 Flyway 撞号: merge 前重查 _58 是否被占

## 顺序
① 先(否则造不出合法多终端 raw 图)→ ② 后端(可独立 curl 验)→ ② 前端 → E2E(F006 猪蹄3SKU: 多选3成品→唯一候选自动选→建计划(owner原料kg)→转批次→workflow-config全图→逐道报→3成品各入库)
