# F006 MVP E2E Bug Review — 2026-07-20

> 本文档按 Bug ID 持续追加和更新，不覆盖既有条目。生产部署必须获得用户单独明确授权。

## 2026-07-20 M10-M12 集中修复批次（追加）

> 本批条目基线：F006 生产现场仅作只读证据，不修改订单、计划、发货单、盘点单、库存或 LIUSHANMEN。实现提交 `dbb4783a5758445570fb20a912f4c8ea45ffc18b`、测试对齐提交 `c77a58f7e2407f6180042acfc779ef40f3b04e53` 已通过 PR #1538 合入 exact main `bb1753001722b67e09a053a869efddf3bf473e55`。生产已发布：Java green/10020、JAR SHA-256 `42c617f134cc5d4fccb2d3c7ba21ff783a66ba0de6dbba65b7fe96256c95a9c5`；Web 四方 index SHA-256 `de70370ffc9f609e9d51a7354c7297ad7bc631959804c1ad8576271798934042`；Python 8083 health HTTP 200。发布过程业务 mutation=0，未做历史桥接，未触碰 LIUSHANMEN。

### BUG-F006-M10-TRANSFER-CREATE-UX-001

- **发现阶段/时间/页面/步骤**：M10，2026-07-20，仓储管理 → 调拨单 → 手动新建调拨单；选择调拨类型、调出/调入仓和 SKU。
- **期望/实际/业务影响**：类型只显示清晰中文；调出仓、调入仓均必选；数量与不可编辑的 SKU 权威单位相邻，现有库存位于单位右侧。实际类型混入枚举英文，仓库可空，库存插在数量与单位之间且单位像可编辑字段，易误填、误判。
- **证据路径**：`D:\Temp\codex-clipboard-fe87da05-ba6b-42b0-a7e7-6c0529f93261.png`。
- **根因/修改文件**：表单把 canonical 枚举当展示文案、校验规则未声明 required、数量/库存/单位列顺序与控件语义错误；修改 `web-admin/src/views/transfer/list.vue`、`transferCreate.ts`、`__tests__/transferCreate.spec.ts`。
- **测试**：`transferCreate.spec.ts` 覆盖中文类型、仓库必填、库存驱动 SKU、数量+只读单位、库存列顺序；整批 Web 类型检查/构建已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`；生产业务 mutation=0。

### BUG-F006-GLOBAL-TABLE-AUTO-WIDTH-001

- **发现阶段/时间/页面/步骤**：M10，2026-07-20，销售订单详情成品明细及全站语义表格检查。
- **期望/实际/业务影响**：名称、规格等长文本按内容自适应、允许语义换行并完整可读，数值/状态列保持紧凑；实际固定宽度+省略号截断成品名，其他模块同类字段也可能丢上下文。
- **证据路径**：`D:\Temp\codex-clipboard-fbfbb329-b373-42e8-a1a8-3c06956ee9ce.png`。
- **根因/修改文件**：全局表格样式以固定布局和单行截断为默认，缺少语义列宽规则；修改 `web-admin/src/style.css`，新增 `web-admin/src/__tests__/semanticTableLayout.spec.ts`。
- **测试**：`semanticTableLayout.spec.ts` 覆盖名称/规格自适应与数值列稳定；整批 Web 构建已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M10-SO-PURCHASE-SEMANTICS-001

- **发现阶段/时间/页面/步骤**：M10，2026-07-20，销售管理 → 销售订单列表操作列。
- **期望/实际/业务影响**：销售订单不应直接暴露“开始采购”；缺货补供必须进入独立补货/采购语义。实际每行出现“开始采购”，混淆销售与采购责任边界并可能诱发错误采购动作。
- **证据路径**：`D:\Temp\codex-clipboard-229aeca2-14ca-4443-b626-b19b3da18b5b.png`。
- **根因/修改文件**：销售列表复用了采购动作与弹窗，没有按订单领域隔离；修改 `web-admin/src/views/sales/orders/list.vue`，并由 `salesOrderDetailDisplay.spec.ts` 约束销售列表不再渲染该动作。
- **测试**：前端静态/组件契约断言销售订单操作列无“开始采购”；整批 Web 已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M10-APPROVAL-DECISION-EVIDENCE-001

- **发现阶段/时间/页面/步骤**：M10，2026-07-20，销售订单详情 → 审批进度。
- **期望/实际/业务影响**：时间本地化并标注周几；自动审批说明必须明确命中的阈值、阈值数值、免审配置名称/版本/条件及操作者。实际直接显示 ISO 微秒时间，备注仅称“未触发阈值或满足免审配置”，无法审计具体原因。
- **证据路径**：`D:\Temp\codex-clipboard-8c2194a3-9a6d-45e0-a2f2-2c77709dcc8c.png`；`D:\Temp\codex-clipboard-945ca834-4359-4ad2-92d3-a8ca7689e71d.png`。
- **根因/修改文件**：审批快照未保存命中规则细节，前端直接渲染原始时间/模糊备注；修改 `backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/SalesServiceImpl.java`、`web-admin/src/views/sales/orders/detail.vue` 及详情展示测试。
- **测试**：覆盖系统自动审批身份、规则说明、时间本地化与星期显示；Java/Web 整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M10-SO-PRODUCTION-ACTION-001

- **发现阶段/时间/页面/步骤**：M10，2026-07-20，打开已存在唯一已完成生产计划的 `SO-20260720-0001` 详情。
- **期望/实际/业务影响**：无有效计划显示“开始生产”；有效未完成计划显示“生产中”；已完成计划显示“已生产”，禁用动作且后端按订单行/剩余量原子防重，取消计划按现有契约释放。实际仍显示可点击“开始生产”，存在第二计划风险。
- **证据路径**：`D:\Temp\codex-clipboard-89ebdc10-dce2-47b0-851d-806e14c28957.png`。
- **根因/修改文件**：前端只看订单审批状态，后端批量转计划缺少完整的有效计划覆盖门禁；修改 `web-admin/src/views/sales/orders/detail.vue`、`salesOrderGuards.ts`/测试，及 `ProductionPlanServiceImpl.java`、`ProductionPlanSalesBatchDateTest.java`。
- **测试**：无计划/生产中/已完成三态、禁用不发 mutation、取消释放、有效计划重复创建拒绝；整批 Java/Web 已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`；未创建第二计划。

### BUG-F006-M10-SHIP-ADDRESS-001

- **发现阶段/时间/页面/步骤**：M10，2026-07-20，订单详情 → 新建发货单；地址为空。
- **期望/实际/业务影响**：订单地址优先、否则客户默认地址；两者均空时明确提示并要求本次手工地址，不得静默建无地址正式发货单。实际弹窗空地址且无清晰必填门禁，破坏物流履约追溯。
- **证据路径**：`D:\Temp\codex-clipboard-1a7ff5c1-c7b9-4401-9339-1fcb43bc3fb1.png`。
- **根因/修改文件**：发货创建未形成订单地址→客户地址→本次必填的统一契约；修改 `web-admin/src/views/sales/orders/detail.vue`、`salesOrderGuards.ts`/测试，`CreateDeliveryRequest.java`、`SalesServiceImpl.java` 及发货契约测试。
- **测试**：订单地址、客户回退、两者皆空、订单地址优先四态；后端空地址 fail-closed；整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`；未修改客户/订单。

### BUG-F006-M10-SHIP-AMOUNT-001

- **发现阶段/时间/页面/步骤**：M10，2026-07-20，新建发货单弹窗核对 5盒×¥20、税率13%。
- **期望/实际/业务影响**：逐行实时显示本次未税小计、税额、含税金额；底部按本次发运量汇总，部分发货不套整单金额且无浮点误差。实际只有数量/单位/单价，无法在提交前核对本次销售价值。
- **证据路径**：`D:\Temp\codex-clipboard-1a7ff5c1-c7b9-4401-9339-1fcb43bc3fb1.png`。
- **根因/修改文件**：弹窗缺少以本次数量为分母的金额派生模型；修改 `web-admin/src/views/sales/orders/detail.vue`、`salesOrderGuards.ts`、`salesOrderGuards.spec.ts`。
- **测试**：全量/部分发货、13%税率、数量变化、货币舍入；不改变既有后端金额 payload 契约。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M10-SHIP-DUPLICATE-CAPACITY-001

- **发现阶段/时间/页面/步骤**：M10，2026-07-20，订单5盒已由唯一有效发货单计划覆盖，订单详情仍可“新建发货单”。
- **期望/实际/业务影响**：订单行剩余可安排量=订单量−有效未取消发货计划量；部分只允许余量、全部安排/全部发货禁用；取消/拒绝释放；后端锁行并发防超量和幂等。实际仅依赖实际已发货量，可能重复/超量安排。
- **证据路径**：`D:\Temp\codex-clipboard-70f8ebb4-4415-4190-bda6-46819407d82e.png`。
- **根因/修改文件**：容量模型未区分 planned 与 shipped，也未绑定订单行；修改销售详情/guards、`SalesDeliveryItem.java`、发货 Repository/Service/DTO、迁移 `V20261028_87__sales_delivery_parent_child_and_line_capacity.sql` 及契约测试。
- **测试**：无单、部分、全部安排、全部发货、取消释放、多行逐项、并发/幂等、禁用零 mutation；整批 JPA/Java/Web 已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`；当前唯一发货单未变更。

### BUG-F006-M10-PARENT-CHILD-DELIVERY-001

- **发现阶段/时间/页面/步骤**：M10，2026-07-20，母发货单 `DLV-20260720-3244` 分配批次；现有“分配数量”只能表达一次库存取批，不能表达2盒+3盒分日发运。
- **期望/实际/业务影响**：销售订单→母发货单→一至多张子发运单→每张子单一至多批次；子单保存日期、配送方式/物流公司、运单号、地址快照、数量、批次和状态，容量、预留、确认发货和取消均原子且可追溯。实际母单兼任单次发运，无法严谨分批并容易混淆预留与实际扣减。
- **证据路径**：`D:\Temp\codex-clipboard-804a48ca-4883-44b9-9054-213fdf2164f3.png`。
- **根因/修改文件**：DeliveryRecord 缺母子层级、子单序号/幂等/行身份和母单聚合状态；修改 `SalesDeliveryRecord.java`、`SalesDeliveryItem.java`、相关 Repository/Service/Controller/DTO、迁移87、`SalesDeliveryShipmentContractTest.java`，以及销售详情/guards。
- **测试**：单子单5、子单2+3、跨批次、单批先2后3、部分/全部状态、取消释放、物流字段持久化、超量4xx、重复/并发不双扣、全链路追溯；整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`；生产发货单/库存未重放。

### BUG-F006-M10-RESERVED-BATCH-ALLOC-001

- **发现阶段/时间/页面/步骤**：M10，2026-07-20，WH-LOG 两批中旧批 reserved=5/available=0、新批 available=5；母单分配只显示新批。发货后旧批预留自动释放、新批扣5，同仓总量10→5。
- **期望/实际/业务影响**：只读确认预留归属及“订单生产批优先”规则；FEFO 只在订单可用集合内排序，禁止消费他单预留。早期怀疑 own reservation 被错误过滤，但后续证据证明无 stranded reservation、无双扣；强制改选旧批可能反而破坏生产批优先业务。
- **证据路径**：`D:\Temp\codex-clipboard-018e7590-5e5e-46fd-8082-32ab0cfe5430.png`、`D:\Temp\codex-clipboard-804a48ca-4883-44b9-9054-213fdf2164f3.png`、`D:\Temp\codex-clipboard-3fc5f97f-23e3-43ad-8d36-b365e3511b48.png`、`D:\Temp\codex-clipboard-b17106d2-3991-4e86-8dcf-d74c96ac9eb5.png`。
- **根因/修改文件**：当前证据不足以认定选择算法缺陷；预留生命周期已验证正确。该项不做强制选择逻辑修改；母子/容量改造仅加强订单行、母子单和 allocation 归属约束。
- **测试**：记录 own/other reservation、释放和不双占场景；以既有发货后库存守恒证据作为只读基线。
- **Commit/PR/main 状态**：`NEEDS_BUSINESS_DECISION`；相关通用约束为 `dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`，无“强制消费0554”代码。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`VERIFIED_NO_INVENTORY_LEAK`；待业务确认预留优先级。

### BUG-F006-M10-BATCH-ALLOCATION-STATE-001

- **发现阶段/时间/页面/步骤**：M10，2026-07-20，`DLV-20260720-3244` 首次分配5盒成功后返回列表，仍显示首次语义“分配批次”。
- **期望/实际/业务影响**：未分配、部分、完整未发货、已发货四态清晰；重开加载现有 allocation；同 payload no-op，修改只调差额并释放旧预留，发货后冻结。实际状态不变、表单像全新分配，诱发重复保存/双预留。
- **证据路径**：`D:\Temp\codex-clipboard-da165424-3198-4203-b023-699f424804ab.png`。
- **根因/修改文件**：UI 未派生 allocation completion，服务保存路径缺少锁、唯一集合和 unchanged no-op；修改 `SalesDeliveryBatchAllocationServiceImpl.java`、相关测试、`web-admin/src/views/sales/orders/detail.vue`/guards。
- **测试**：首次完整、同 payload no-op、修改差额、部分/完整 UI、并发不双预留、发货后只读、确认前必须完整；整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`；未重放生产 allocation。

### BUG-F006-M11-YIELD-MIXED-UNIT-001

- **发现阶段/时间/页面/步骤**：M11，2026-07-20，成品出厂核算查看订单/计划：5kg→4.5kg→5box（800g/box）。
- **期望/实际/业务影响**：工序/累计出成率应为90%/88.89%/80%，使用计划 pinned SKU/包装/净重快照；不可验证换算应 fail-closed。实际整批显示100%、末道“—”，跨 kg/box 直接相除或丢失净重，形成 P0 核算错报。
- **证据路径**：`D:\Temp\codex-clipboard-089a27f7-a02e-45c2-aa98-575d8e424170.png`。
- **根因/修改文件**：Yield 聚合把终端件数当重量并未携带可审计换算依据；修改 `BatchYieldDTO.java`、`OrderYieldSummaryDTO.java`、`YieldReportServiceImpl.java`、`OrderCostBreakdownService.java`、M67 页面/helper 与 Yield/Cost 测试。
- **测试**：`YieldReportServiceImplTest,OrderCostBreakdownServiceTest,OrderCostBreakdownSfiFeedTest` 已覆盖5kg→4.5kg→5box=90/88.89/80、pinned换算和计划隔离；该组三类共160 tests 已通过，最终 release gate 待整批执行。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASS_PENDING_FINAL_BUILD`。

### BUG-F006-M11-UNIT-DISPLAY-001

- **发现阶段/时间/页面/步骤**：M11，2026-07-20，成品出厂核算顶部、末道副文案和工序行。
- **期望/实际/业务影响**：所有用户可见 `box/case/slice`→盒/箱/片，g/kg不变；API/DB canonical 不变。实际泄漏“末道产出5.0 box”“4.5→5.0 box”，破坏单位契约一致性。
- **证据路径**：`D:\Temp\codex-clipboard-089a27f7-a02e-45c2-aa98-575d8e424170.png`。
- **根因/修改文件**：M67 局部字符串拼接绕过 displayUnit；修改 `web-admin/src/views/production-analytics/M67YieldCost.vue`、`m67YieldCostAudit.ts` 及测试。
- **测试**：M67 目标测试覆盖 box/case/slice 中文化、kg/g保持，Web 类型检查已通过，最终构建待整批执行。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASS_PENDING_FINAL_BUILD`。

### BUG-F006-M11-PACKAGING-COST-001

- **发现阶段/时间/页面/步骤**：M11，2026-07-20，核算页第二工序“本组归集成本¥0.00（含包装）”与成本拆解。
- **期望/实际/业务影响**：pinned BOM 的盒/膜/外箱按实际5盒用量和有效价格计入；缺价逐项标未归集并使完整成本告警，不得静默0。实际3项包材完全缺失却显示0，可能低估总成本/单盒成本。
- **证据路径**：`D:\Temp\codex-clipboard-089a27f7-a02e-45c2-aa98-575d8e424170.png`。
- **根因/修改文件**：成本聚合只覆盖原料/人工，包材价格/来源/完整性未进入明细 DTO；修改 `OrderCostBreakdownDTO.java`、Service/Impl、Yield/Cost 测试及 M67 audit UI/helper。
- **测试**：包材有价计入、缺价未归集而非0、M07/M09计划隔离；后端三类目标测试160项已通过，最终整批构建待执行。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASS_PENDING_FINAL_BUILD`。

### BUG-F006-M11-ORDER-SELECTOR-001

- **发现阶段/时间/页面/步骤**：M11，2026-07-20，成品出厂核算的订单筛选器。
- **期望/实际/业务影响**：主显示/搜索业务订单号 `SO-20260720-0001`，UUID仅作次级身份；实际仅展示内部 UUID，用户难以辨认核算对象。
- **证据路径**：`D:\Temp\codex-clipboard-089a27f7-a02e-45c2-aa98-575d8e424170.png`。
- **根因/修改文件**：选择器 label 直接绑定 orderId；修改 `M67YieldCost.vue`、`m67YieldCostAudit.ts` 及目标测试。
- **测试**：订单号优先、UUID回退与计划隔离；M67 目标测试已通过，最终 Web 构建待执行。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASS_PENDING_FINAL_BUILD`。

### BUG-F006-M11-COST-AUDIT-DETAIL-001

- **发现阶段/时间/页面/步骤**：M11，2026-07-20，成品出厂核算整页审计；当前仅有汇总、两工序、占比和 Sankey。
- **期望/实际/业务影响**：同页/抽屉提供核算对象、产出换算、原料/包材/人工/设备/其他明细、价格/费率来源、未知项、总账勾稽和完整公式；批次图可读/可展开。实际¥31.73/盒、¥20.53人工和包材0均无法追到数量×价格，成本不可审计。
- **证据路径**：`D:\Temp\codex-clipboard-089a27f7-a02e-45c2-aa98-575d8e424170.png`。
- **根因/修改文件**：API DTO 与 M67 只返回汇总，未建已归集/未归集及来源账；修改 Yield/Cost DTO/Service/Impl、M67 页面/helper和后端/前端测试。
- **测试**：核算对象+pinned版本、5盒/800g/4kg、原料批次、包材逐项、220人分钟=3.6667人小时、0与未知区分、总账勾稽、完整批次tooltip、计划隔离；目标测试已通过，最终整批构建待执行。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASS_PENDING_FINAL_BUILD`；不改历史生产/库存/settlement。

### BUG-F006-M12-WAREHOUSE-BADGE-CONTRAST-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，发起盘点的仓库下拉标签。
- **期望/实际/业务影响**：仓库类型 badge 在默认/选中/hover/暗色模式均达到正常文字 WCAG AA 4.5:1；实际亮蓝/橙底配近似色文字，标签近乎不可读。
- **证据路径**：`D:\Temp\codex-clipboard-9c52ede9-a1e4-4066-8d44-61c65e24439e.png`。
- **根因/修改文件**：仓库类型色板使用低对比前景色；修改 `web-admin/src/utils/warehouse.ts`、相关仓库/报损视图样式。
- **测试**：色板/语义标签目标断言及 Web 构建已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M12-AUTO-OPEN-COUNT-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，发起盘点点击“确认发起”成功。
- **期望/实际/业务影响**：严格一次创建后，使用响应 stocktakeId 自动打开同一新单录入弹窗；打开失败保留列表并明确提示。实际弹窗关闭，用户需在列表寻找新单，效率低且可能重复发起。
- **证据路径**：`D:\Temp\codex-clipboard-9c52ede9-a1e4-4066-8d44-61c65e24439e.png`、`D:\Temp\codex-clipboard-8583e488-b3da-4573-8c55-eafaca6c287e.png`。
- **根因/修改文件**：创建成功回调只刷新/关闭，丢弃响应身份；修改 `web-admin/src/views/warehouse/stocktakes/index.vue` 及 stocktakeCount 测试。
- **测试**：创建 POST=1、自动打开响应同一 ST、打开失败不重建、刷新可续录；整批 Web 已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`；现有 ST 不变。

### BUG-F006-M12-COUNT-UNIT-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，录入盘点数量弹窗7行混合批次。
- **期望/实际/业务影响**：系统库存、实盘数量、差异逐行带权威单位，输入显示单位后缀；box/case/slice中文化，g/kg不变，禁止跨单位求和。实际三列无单位，存在严重误录风险。
- **证据路径**：`D:\Temp\codex-clipboard-8583e488-b3da-4573-8c55-eafaca6c287e.png`。
- **根因/修改文件**：盘点明细 DTO 未返回 unit，UI 数值列无 formatter/suffix；修改 `StocktakeDTO.java`、`StocktakeDiffPreviewDTO.java`、`FactoryStocktakeServiceImpl.java`、盘点页面/helper/测试。
- **测试**：7行混合数量与 box/case/slice/kg/g 展示、按单位汇总；Java/Web 整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M12-COUNT-IDENTITY-DISPLAY-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，盘点录入弹窗“批次号/物料名称”。
- **期望/实际/业务影响**：主显示业务 batchNumber 和业务名称，UUID/materialTypeId仅次级/tooltip且严格工厂/仓库隔离。实际批次列显示 UUID，名称列显示 material code，仓管无法识别批次。
- **证据路径**：`D:\Temp\codex-clipboard-8583e488-b3da-4573-8c55-eafaca6c287e.png`。
- **根因/修改文件**：DTO/查询暴露内部身份而未解析批次与物料主数据；修改 Stocktake DTO/Service、Repository、盘点页面和真实 JPA/契约测试。
- **测试**：业务批次/名称、同名多批唯一识别、工厂/仓库隔离、JPA Context 启动门禁；整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M12-COUNT-QUICK-FILL-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，7行零差异盘点录入。
- **期望/实际/业务影响**：空白仍表示未盘；提供“全部按账面数量填入”、行级“账实一致”、仅填空白/二次确认覆盖、Tab/Enter移至下一行且不自动保存提交。实际需逐行重复录入，效率低且容易漏盘。
- **证据路径**：用户文字确认；稳定现场 `ST-202607-1844C85D` 仅作只读基线。
- **根因/修改文件**：录入状态缺少未盘语义、快捷填充和键盘焦点模型；修改 `stocktakes/index.vue`、`stocktakeCount.ts`、`stocktakeCount.spec.ts`，后端增加空白明细提交门禁。
- **测试**：7行全空一键填、部分差异仅补空白、空白阻止提交、Tab/Enter焦点、快捷填充零额外 mutation；整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M12-STOCKTAKE-TIME-MODEL-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，发起/提交审批/详情查看 `ST-202607-1844C85D`。
- **期望/实际/业务影响**：服务器创建 `inventoryCutoffAt` 并锁定库存快照；自动记录 countingStarted/submitted/approved/applied；对账范围只影响流水展示，结束锁定 cutoff，periodMonth自动派生；旧数据可信回显并标历史。实际仅“盘点月份”，缺基准时点和状态审计，范围/快照语义混淆。
- **证据路径**：`D:\Temp\codex-clipboard-73fce267-58d1-4d9b-87f2-c866fc2e9b55.png`、`...654d532c-2160-495f-956f-d837fcd90866.png`、稳定基线 `...576447c4-1b24-4260-9fc6-fe30b4cee7f0.png`。
- **根因/修改文件**：Entity/DTO 只有月份和有限状态时间，客户端可影响时间语义；修改 `FactoryStocktake.java`、Create/Stocktake DTO、Repository/Service/Controller、迁移 `V20261028_88__stocktake_cutoff_audit_and_cas.sql`、盘点页面与 JPA/契约测试。
- **测试**：server cutoff 防篡改、范围不改快照、结束锁定、period派生、旧数据回显、时间状态流转、JPA Context；整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`；生产 ST/库存未桥接。

### BUG-F006-M12-ACTOR-DISPLAY-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，盘点列表、提交/审批/详情。
- **期望/实际/业务影响**：发起/审批人主显示真实姓名/账号，内部ID仅次级/tooltip。实际显示1309，审批责任人不可识别。
- **证据路径**：`D:\Temp\codex-clipboard-654d532c-2160-495f-956f-d837fcd90866.png`、`D:\Temp\codex-clipboard-4bb73920-345d-42c3-9eb4-245674e63b19.png`。
- **根因/修改文件**：DTO 仅输出 actor ID；修改 Stocktake DTO/Service、盘点页面及测试。
- **测试**：真实姓名→账号→历史ID回退与工厂隔离；整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M12-SELF-APPROVAL-CONTROL-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，同一 f006_admin 发起并审批盘点。
- **期望/实际/业务影响**：有差异盘点强制 maker-checker，发起/录入人自批返回403/409且无部分库存写；严格零差异可按现有权限自确认但必须明确审计，仍走 APPROVED→APPLIED。实际缺后端差异感知自批门禁。
- **证据路径**：`D:\Temp\codex-clipboard-4bb73920-345d-42c3-9eb4-245674e63b19.png`。
- **根因/修改文件**：审批只检查角色/状态，未比较 actor 与差异影响；修改 Stocktake Entity/Repository/Service/Controller、迁移88和 M12 契约测试。
- **测试**：有差异自批拒绝且零写、零差异自确认审计、CAS/重复审批；整批 Java/JPA 已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M12-APPROVAL-EVIDENCE-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，待审批弹窗。
- **期望/实际/业务影响**：确认前展示已盘/未盘、平衡/盘盈/盘亏、按单位分组数量、库存影响、差异明细、批次、基准/执行/对账时间和真实发起人；后端按锁定版本/CAS重验。实际只有单号、仓库、月份、ID和备注，审批人无法知情决策。
- **证据路径**：`D:\Temp\codex-clipboard-4bb73920-345d-42c3-9eb4-245674e63b19.png`。
- **根因/修改文件**：审批 DTO/弹窗未复用差异预览且无版本锁；修改 Stocktake Preview DTO/Service/Controller、盘点页面、迁移88与契约测试。
- **测试**：7/0、7平衡/0盈/0亏、单位分组、零影响说明、差异展开、CAS stale拒绝；整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M12-ZERO-DIFF-AUTO-COMPLETE-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，零差异盘点审批后的状态设计复核。
- **期望/实际/业务影响**：用户最终纠正为零差异与有差异都保持 `PENDING_APPROVAL → APPROVED → APPLIED`；应用时CAS/身份/完整性再验，零差异只写状态与审计、不写库存调整/凭证。早期“审批后自动完成”建议会绕过最终锁定和审计。
- **证据路径**：用户正式业务纠正；生产基线 `D:\Temp\codex-clipboard-576447c4-1b24-4260-9fc6-fe30b4cee7f0.png`（7行零差异，应用后库存不变）。
- **根因/修改文件**：该项不是已上线缺陷，而是需求决策被撤回；代码明确保留两步，修改/验证 Stocktake Service/Controller、盘点页面与契约测试。
- **测试**：零差异审批后仍 APPROVED、应用后 APPLIED、库存调整/财务凭证=0、重复应用幂等/409、CAS重验。
- **Commit/PR/main 状态**：`WITHDRAWN / BUSINESS_DECISION_REVERSED`；保留两步实现为 `dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`REQUIREMENT_CORRECTED`；严禁实现自动完成。

### BUG-F006-M12-DETAIL-UNIT-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，已审批盘点详情/差异预览。
- **期望/实际/业务影响**：系统/实盘/差异逐行显示中文化权威单位，汇总按单位分组。实际三列无单位，无法判断数量口径。
- **证据路径**：`D:\Temp\codex-clipboard-a363f114-f3ce-4247-9e9a-5d1a6500a517.png`。
- **根因/修改文件**：Preview DTO/UI详情绕过 unit；修改 Stocktake DTO/Service、盘点页面/helper/tests。
- **测试**：kg/g、box/case/slice及单位分组；整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M12-BALANCED-TYPE-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，已审批详情7行 differenceQty=0。
- **期望/实际/业务影响**：正数SURPLUS/盘盈、负数SHORTAGE/盘亏、零值BALANCED/MATCH/账实一致；旧null可由0安全回显，写路径须规范。实际零差异类型显示“—”，语义不完整。
- **证据路径**：`D:\Temp\codex-clipboard-a363f114-f3ce-4247-9e9a-5d1a6500a517.png`。
- **根因/修改文件**：后端零值 differenceType 未规范，前端枚举无零差异回退；修改 Stocktake Service/DTO、盘点页面/helper/测试。
- **测试**：正/负/零三态、旧null+0兼容；整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M12-DETAIL-BATCH-IDENTITY-001

- **发现阶段/时间/页面/步骤**：M12，2026-07-20，盘点详情、审批摘要、应用确认。
- **期望/实际/业务影响**：主显示业务 batchNumber+物料名称，同名多批可唯一辨认；有差异提示文案应为“应用差异后调整库存”，已审批不再写“批准后生效”。实际只显示物料名且状态文案矛盾。
- **证据路径**：`D:\Temp\codex-clipboard-a363f114-f3ce-4247-9e9a-5d1a6500a517.png`。
- **根因/修改文件**：详情 DTO/模板未带 batchNumber，文案未按当前状态/差异派生；修改 Stocktake DTO/Service、盘点页面与测试。
- **测试**：同名3批/2批唯一识别、审批/应用身份一致、零/有差异状态文案；整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M10-DELIVERY-BADGE-SEMANTICS-001

- **发现阶段/时间/页面/步骤**：M10最终只读回归，2026-07-20，已签收唯一发货单的销售订单详情。
- **期望/实际/业务影响**：红色 badge 仅统计待分配/待发货/待签收等 actionable 单据；全部签收后隐藏，历史总数用中性“发货记录（1）”。实际已签收仍显示红1，制造虚假待办。
- **证据路径**：`D:\Temp\codex-clipboard-dc57decd-595b-42cc-a36f-e87861209565.png`、`...3d845f93-b7f9-41bd-be53-1af9330f96a2.png`、`...91508e1b-48ef-4c5c-8237-8e66c8841ac2.png`。
- **根因/修改文件**：badge 绑定发货历史总数而非 actionable 状态集合；修改 `web-admin/src/views/sales/orders/detail.vue`、`salesOrderGuards.ts` 及详情测试。
- **测试**：无记录、1待办、1已签收、混合、取消不计待办；整批 Web 已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M10-ORDER-TRANSPORT-AGGREGATE-001

- **发现阶段/时间/页面/步骤**：M10最终只读回归，2026-07-20，发货单已签收但订单顶部仍“运输：已发货”。
- **期望/实际/业务影响**：仅全部有效发货单签收时显示已签收/运输完成；部分签收显示部分签收/运输中；取消单排除。实际订单头与发货记录状态矛盾。
- **证据路径**：同 `BUG-F006-M10-DELIVERY-BADGE-SEMANTICS-001` 三张截图。
- **根因/修改文件**：订单运输状态按最早“已发货”事件写死，未聚合有效子/母发货单；修改 `SalesServiceImpl.java`、Sales delivery entity/status/测试及 `detail.vue`/guards。
- **测试**：单单已签收、多单部分/全部签收、取消排除、刷新一致；整批 Java/Web 已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-M10-TRACKING-NO-GATE-001

- **发现阶段/时间/页面/步骤**：M10最终只读回归，2026-07-20，物流公司有值但运单号空仍可确认发货并签收。
- **期望/实际/业务影响**：普通物流发运在后端确认前强制公司、运单号、日期、数量；自提/自送须显式配送方式方可免填。历史空值只读标“未填写（历史数据）”。实际无 fail-closed 门禁，物流审计链不完整。
- **证据路径**：同上三张订单/发货截图。
- **根因/修改文件**：发货实体缺 deliveryMethod，ship service 未按配送方式校验 tracking；修改 `SalesDeliveryRecord.java`、迁移87、`SalesServiceImpl.java`、发货契约测试和销售详情 UI。
- **测试**：物流缺公司/运单/日期拒绝，自提/自送显式豁免，历史空值只读、子单字段持久化；整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`；历史记录不伪造。

### BUG-F006-M10-AUDIT-TIMELINE-DISPLAY-001

- **发现阶段/时间/页面/步骤**：M10最终只读回归，2026-07-20，销售订单审批进度和发货记录。
- **期望/实际/业务影响**：统一本地日期时间到秒/分钟并标星期；真实姓名/账号，系统自动通过显示“系统自动审批”；发货/签收可审计。实际原始 ISO 微秒时间、操作者“财务?”、发运节点缺失。
- **证据路径**：`D:\Temp\codex-clipboard-8c2194a3-9a6d-45e0-a2f2-2c77709dcc8c.png` 及最终三张订单/发货截图。
- **根因/修改文件**：详情时间线直出后端字符串、操作者空值用问号、未接入 delivery 事件；修改 `detail.vue`、`salesOrderDetailDisplay.spec.ts`，审批快照由 `SalesServiceImpl.java` 提供。
- **测试**：本地时间+星期、系统自动审批、真实 actor、发货/签收节点、无问号；整批 Web/Java 已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-PROD-PLAN-ACTION-IA-001

- **发现阶段/时间/页面/步骤**：生产计划最终回归，2026-07-20，已完成/已入库计划列表行与顶部操作区。
- **期望/实际/业务影响**：行内保留查看详情、生产单据、追溯与核算；顶部按主操作/导入导出/选中后批量/条件动作分组，完成计划只读。实际工单、追踪、打印、领料、配料、成本和更多平铺，顶部永久动作过多、挤压并增加误操作。
- **证据路径**：`D:\Temp\codex-clipboard-020af77c-faa0-4a73-bdd9-b9a9672898bb.png`。
- **根因/修改文件**：操作入口按历史功能逐个追加，未按语义、状态、权限和选择态聚合；修改 `web-admin/src/views/production/plans/list.vue`、presentation/document pack helpers及三组目标测试，后端/Python打印入口及测试。
- **最终打印交互决策**：工单、领料单、配料单业务模型绝不合并；统一输出一个“生产单据包 PDF”。`生产单据 ▾` 提供三类单据分别查看及主推荐“下载/打印生产单据包PDF”。PDF含封面/生产摘要、第1部分工单、第2部分领料、第3部分配料，各部分新页开始；统一计划号、SKU/产品名、批次日期、pinned BOM/Workflow版本、生成时间和连续“第x页/共y页”；长表续页重复表头；章节默认全选且可勾选部分，一次生成/下载/打印；中文字体嵌入，章节可独立横向；缺失数据和逐章节权限 fail-closed，三部分使用同一计划 pinned 快照。
- **测试**：状态/权限/选择态/响应式、单据菜单与追溯菜单；PDF章节分页、续页表头、章节选择、版本一致、缺失、权限、中文字体和一次下载；`productionPlanInformationArchitecture.spec.ts`、`productionDocumentPack.spec.ts`、PrintController/Python renderer 测试，整批已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`；只收拢入口，不删除单据功能/责任边界。

### BUG-F006-PROD-PLAN-FILTER-SUMMARY-001

- **发现阶段/时间/页面/步骤**：生产计划最终回归，2026-07-20，搜索 `PLAN-1784523993145-78E6EE57` 且全部状态；列表1行但页脚仍全厂12条/101,593/0.0%。
- **期望/实际/业务影响**：汇总与列表使用同一搜索/状态/分页过滤契约；当前应1条、计划5盒、实际5盒、100%；混合单位按单位分组。若保留全厂汇总必须独立标注。实际口径矛盾且跨单位相加，形成 P0/P1 误导。
- **证据路径**：`D:\Temp\codex-clipboard-020af77c-faa0-4a73-bdd9-b9a9672898bb.png`。
- **根因/修改文件**：列表搜索与服务端 summary 过滤参数脱节，汇总直接对异单位数量求和；修改 `ListSummaryServiceImpl.java`、其测试、`productionPlanListPresentation.ts`、`list.vue` 及 presentation 测试。
- **测试**：精确搜索/状态/分页同口径、box单项100%、混合单位分组、不伪装全厂汇总；整批 Java/Web 已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-PROD-PLAN-ACTUAL-UNIT-001

- **发现阶段/时间/页面/步骤**：生产计划最终回归，2026-07-20，计划详情/列表“实际数量”。
- **期望/实际/业务影响**：计划和实际都显示数量+中文 displayUnit，box/case/slice→盒/箱/片，g/kg不变；来源显示并可链接业务订单号。实际“计划成品5盒”但实际仅“5”，来源仅泛化“销售订单”。
- **证据路径**：`D:\Temp\codex-clipboard-020af77c-faa0-4a73-bdd9-b9a9672898bb.png`。
- **根因/修改文件**：列表/详情 actualQuantity 未绑定 planned/workflow/source display unit，来源 label 未带业务单号；修改 `productionPlanListPresentation.ts`、`list.vue` 及目标测试。
- **测试**：box/case/slice中文化、kg/g保持、实际5盒、来源SO业务号；整批 Web 已通过。
- **Commit/PR/main 状态**：`dbb4783a5758445570fb20a912f4c8ea45ffc18b + c77a58f7e2407f6180042acfc779ef40f3b04e53 (merged as bb1753001722b67e09a053a869efddf3bf473e55)`。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`（main bb175300；Java/Web/Python 已发布并通过服务级验收）。
- **回归状态**：`TARGET_TEST_PASSED`。

## BUG-F006-M09-INV-UNIT-001

- **发现阶段**：M09 完成仓库确认及成品仓 → 物流仓调拨后，分仓库存查询回归。
- **发现时间**：2026-07-20。
- **页面/步骤**：Web Admin → 仓储管理 → 分仓库存查询 → 选择 WH-FG/WH-LOG → 成品库存。
- **期望**：API/数据库继续保存 canonical unit；页面将 `box/case/slice` 显示为“盒/箱/片”，`g/kg` 保持不变。
- **实际**：成品单位列直接显示 canonical `box`；同组件原料单位列同样绕过共享展示转换。
- **业务影响**：非阻塞显示缺陷；库存数量、预留、仓位、批次、调拨和订单状态没有异常，但破坏全站单位展示契约一致性。
- **证据路径**：`D:\Temp\codex-clipboard-018e7590-5e5e-46fd-8082-32ab0cfe5430.png`；现场调拨 `TRF-20260720-0966` 已 `CONFIRMED`。
- **根因**：`web-admin/src/views/inventory/by-warehouse/index.vue` 的成品单位列使用 `prop="unit"` 直接渲染 API canonical 值；原料单位列也直接输出 `quantityUnit/unit`，均未调用共享 `displayUnit`。
- **修改文件**：
  - `web-admin/src/views/inventory/by-warehouse/index.vue`
  - `web-admin/src/views/inventory/by-warehouse/__tests__/inventoryUnitDisplay.spec.ts`
  - `docs/qa/F006-MVP-E2E-bug-review-2026-07-20.md`
- **测试**：`npx vitest run src/views/inventory/by-warehouse/__tests__/inventoryUnitDisplay.spec.ts src/utils/__tests__/unitPricing.spec.ts`，2 个测试文件、13 项断言全部通过；覆盖 `box/case/slice → 盒/箱/片` 与 `g/kg` 保持，并断言原料、成品单位列均通过 `displayUnit`。唯一一次 Vite 生产构建成功，4429 modules transformed；Web tree 为 `bc8c221d3e715f87aba69559cf7c5fa82effb25c`。
- **Commit/PR/main 状态**：实现 commit `ac560a82ba02d9d44c3c280674103cc1dd54395e`；PR [#1536](https://github.com/Stevenjxie/cretas/pull/1536)；main 状态 `MERGED_TO_MAIN`（本条目随该 PR 合入后生效）。
- **部署状态**：`DEPLOYED_PROD_2026-07-20`。
- **回归状态**：待 main 合入并由用户明确授权部署后，测试 Chat 在同一 F006 现场刷新验证。
- **数据边界**：本修复只调整 Web 展示；不修改 API payload、数据库、库存、预留、仓位、批次、调拨或订单，不触碰 LIUSHANMEN。

## F006 R2 — Workflow/BOM 拓扑与配置契约（2026-07-20）

### BLOCKER-F006-R2-BOM-WORKFLOW-DRAFT-PIN

- **发现阶段/页面/步骤**：R2，SKU `CPF0060016`；Workflow 已保存但 BOM v2 辅料页只能读取已发布/启用 Workflow，形成 Workflow-first 与 BOM-complete 的循环依赖。
- **期望/实际/业务影响**：BOM 草稿必须显式选择并固定已保存、结构完整的 Workflow revision；实际读取可变状态或 ACTIVE-only，导致草稿无法配置辅料并可能随后续编辑漂移。
- **根因**：Workflow 草稿缺少不可变 revision 身份；BOM 仅保存产品/版本级引用，辅料仍按 master workProcess 粗粒度绑定。
- **修改文件**：`ProductProcessWorkflowRevision`/repository、`BomWorkflowRevisionService`/controller/DTO、`BomRecipe` revision snapshot、`BomSeasoningWorkspaceServiceImpl`、发布/启用门禁、Web revision selector 及 Flyway `V20261028_98`。
- **测试**：保存 revision hash、同 revision 幂等 pin、跨工厂/SKU 拒绝、刷新快照稳定、ACTIVE BOM 与发布/启用 exact revision 双向门禁、无草稿重复发布 409、真实 JPA startup gate。
- **Commit/PR/main 状态**：revision/pin 核心 commit `9f5cb804f`，拓扑与 pinned-node 收口 commit `301fcb0cd`；PR [#1545](https://github.com/Stevenjxie/cretas/pull/1545) 已合入，代码 main commit `409aab41db0d031bc508ca528ac1d3c5e3c16cdf`，状态 `MERGED_TO_MAIN`。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：revision/pin、跨厂/SKU、刷新稳定、发布/启用 exact revision、真实 JPA 均已通过；clean rebase 后 Java 最终单生命周期 `221/221` 通过并生成可信 manifest（backend tree `ace4f31d1fe9529ed2e4dabb27d30ed1f0ccb7f0`，JAR SHA-256 `81eeffa3123350fe5d86b403db6c1a82115920e6b8900b9cf99314fac5a2ed79`）。生产 `CPF0060016`/BOM/Workflow 零写入、无历史桥接。

### BLOCKER-F006-R2-WORKFLOW-TOPOLOGY-RESOLUTION

- **发现阶段/页面/步骤**：R2，`CPF0060016` 的合法链路 `原料A + 原料B → 原料处理 → 半成品 → 定量包装 → 成品`；BOM v2 辅料页报“目标成品路径必须恰好可溯到一个入口原料 Cell”。
- **期望/实际/业务影响**：按目标 SKU 对 pinned Workflow revision 反向解析完整 DAG 子图；实际解析器硬编码单入口，使 2→1、2→2、合流/分流全部误判并阻断辅料配置。
- **根因**：旧 `ProductWorkflowResolutionServiceImpl` 使用 `rawRoots.size() != 1` 和 `rawRoots.get(0)`，把入口身份、工序路径和辅料作用域混为单链。
- **修改文件**：`BomWorkflowRevisionService`、`PinnedWorkflowGraph`、`ProductWorkflowResolutionServiceImpl`、`WorkflowProcessPath`、`BomSeasoningWorkspaceServiceImpl`/response、Web `BomAuxiliaryWorkspace.vue`/API 类型及 A-E 测试。
- **修复契约**：目标 SKU 唯一 terminal 是合法身份门禁，但入口可为 N；反向切片收集所有可达入口和工序、保留合流/分流边、节点去重并拓扑排序；环、无入口、孤儿、跨厂/SKU、重复同目标 terminal 明确 4xx；多产出缺角色/成本分摊 fail-closed。
- **终审补强**：ACTIVE BOM 与 DRAFT 一样始终从自身 pinned revision 解析工序，不得回落到产品当前 Workflow；工序辅料替代关系同时快照 master `workProcessId` 与精确 `workflowProcessNodeId`，同一工序模板在两个节点不串配置；辅料标准分母从 pinned 节点端口单位读取，现有仅能可靠表达 g/kg 的 legacy 剂量模型对盒/升等量纲明确 fail-closed，不再伪装为“每1kg”。
- **depth-first-e2e 矩阵**：A 1→1=`medium`；B 2→1=`deep`；C 1→2=`medium`；D 2→2=`deep`；E 合流→半成品→分流=`medium`。`BomWorkflowRevisionServiceTest` 的 A-E 五个具名矩阵场景全部通过：覆盖 revision 保存/pin、入口/工序严格集合、目标反向切片、共享节点去重、跨目标隔离、多产出角色/分摊门禁、环/孤儿/无入口/重复目标/跨厂 SKU 明确拒绝及 snapshot 不漂移。`BomSeasoningWorkspaceServiceTest` 覆盖 2→1 多入口摘要、精确 process-node 绑定、提交与 fresh readback；Web workspace 目标测试覆盖加载失败锁写、revision 状态与中文单位。
- **测试证据**：后端拓扑/BOM/辅料/替代料首组 30/30，通过单独真实 JPA repository startup gate 1/1；readiness/copy/process-sheet/unit/material-source 等同因回归 139/139；Web BOM workspace 30/30，单位/分类/来源边界补充回归 58/58。clean rebase 后 Java 最终 release 单生命周期 `221/221` 通过并生成可信 JAR manifest；Web 在 exact HEAD 干净 release worktree构建成功，735 assets，web tree `197b94e5ec84792106a3679e0c5b154f034ccdbd`，archive SHA-256 `623d3737ce2e56d0449edfb174144be946337456df73131adcf545313db6ce17`，index SHA-256 `61ba51d378309706a6d97d6da808e5f83cfe36ac2c357fb87eff0ea17474149c`。CI 首轮完整 Vitest 暴露 4 条已漂移的源契约断言后，同批校正为“工序单位已移除、类别使用受控 taxonomy、采购编辑保留原下单日、Workflow 模式由画布识别”；本地完整 Web Vitest 最终 `1673/1673` 通过（另 5 skipped）。
- **Commit/PR/main 状态**：revision 与 DAG 核心 `9f5cb804f`，拓扑/辅料作用域/门禁收口 `301fcb0cd`，Web fail-closed `e7beda5f8`；PR [#1545](https://github.com/Stevenjxie/cretas/pull/1545) 已合入，代码 main commit `409aab41db0d031bc508ca528ac1d3c5e3c16cdf`，状态 `MERGED_TO_MAIN`。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`FINAL_RELEASE_GATE_PASSED`；严格生产业务 mutation=0。

### AUDIT-F006-R2-WORKFLOW-TOPOLOGY-SAME-CAUSE-001

- **扫描范围**：Workflow→BOM→辅料→计划→报工→WIP→出成率→成本→结单，以及对应 Web 消费路径；逐项搜索 single input/output、first raw/product、`size==1`、`get(0)`、`findFirst`。
- **已确认并同批修复**：旧 ACTIVE resolver 单入口；BOM 辅料按 `workflowProcessNodeId`；BOM 复制完整 root 集合；多入口 WIP identity 不再取首原料；无兼容 Workflow revision 时 Web 不再预选错误候选。
- **保留的安全门禁**：目标 SKU terminal 唯一、多个可用 Workflow 必须显式消歧、端口输出单位唯一、混合输出单位不能压成计划头 singular unit、多产出缺角色/分摊拒绝；这些不是应删除的“单入口假设”。
- **需进入具体 deep/medium 回归而不能声称已解决**：
  - `YieldCalculationServiceImpl`/`YieldReportServiceImpl`：线性 `prevOutput`、首输入量与首 WIP unit；B/D deep、C/E medium 验证 target DAG、diamond 去重与单位安全聚合。
  - `CostReconcileService`/`OrderCostBreakdownService`：首末工序与单链成本；非线性未支持时必须明确不可核算，禁止静默重复共享上游或包材成本。
  - `ProcessSheet.vue`/`ProcessDataTable.vue`：`idx===0` 不能代表所有 DAG 根工序；按真实入边/port 推导，B/D/E 页面深测。
  - `ProductionSettlement` 仍为 singular finished quantity/batch；D 多目标完整结单需行级 settlement 模型，当前应 fail-closed，不得直接放宽。
- **证据/方法**：对 Base `5c2b30249` 与当前拓扑 commit 做代码差异核验；逐文件/行号分类 vulnerable、safe、needs verification；未访问生产、未写业务数据。
- **测试状态**：`READ_ONLY_AUDIT_COMPLETE`；已确认脆弱点中 resolver、辅料 node identity、BOM copy roots、WIP output identity 与 Web candidate 已有目标回归。同批不冒充已支持的非线性出成率/成本/多成品结单仍保留为具体 deep/medium 测试与 fail-closed 清单。
- **Commit/PR/main 状态**：审计零文件修改；对应修复已随 PR [#1545](https://github.com/Stevenjxie/cretas/pull/1545) 合入代码 main `409aab41db0d031bc508ca528ac1d3c5e3c16cdf`。
- **部署状态**：`NOT_DEPLOYED`。

### 本轮拓扑同因修复收口

- `FIX-F006-R2-BOM-WEB-FAIL-CLOSED-001`：替代关系/Workflow revision 加载失败锁定写入，跨单位替代、包材作用域与 unsupported basis fail-closed；Web 目标回归通过。
- `FIX-F006-R2-BOM-READINESS-PINNED-NODE-001`：readiness 使用 recipe pin 的不可变目标子图，并以 `workflowProcessNodeId` 区分同一工序模板的不同节点；ACTIVE BOM 不再依赖可变 DRAFT。
- `FIX-F006-R2-WIP-IDENTITY-TOPOLOGY-001`：2→1/2→2 合流 WIP identity 取产出半成品/节点快照并保留全部入口 provenance，不再压缩成首原料身份。
- `FIX-F006-R2-BOM-COPY-MULTIROOT-001`：BOM 复制候选比较规范化完整 root 集合，A+B 与 B+A 等价，辅料按精确 process node 复制。
- **Commit/PR/main 状态**：以上四项均随 PR [#1545](https://github.com/Stevenjxie/cretas/pull/1545) 合入代码 main `409aab41db0d031bc508ca528ac1d3c5e3c16cdf`，状态 `MERGED_TO_MAIN`。
- **部署/数据状态**：`NOT_DEPLOYED`；生产业务 mutation=0；未修改 `CPF0060016`、`BOM-20260720-004/005` 或现有 Workflow revision，未做历史桥接，未触碰 LIUSHANMEN。

### FEATURE-F006-R2-BOM-SUBSTITUTE-001 / FEATURE-F006-R2-BOM-MULTIPACK-001

- **发现阶段/期望**：BOM 的 RAW、工序辅料、PACKAGING 统一使用 parent item→multiple substitutes；包材按 SKU packaging level/role，辅料按 Workflow process node，禁止自由文本替代组和需求/成本双算。
- **根因**：旧 `substituteGroup` 只是不可解释文本，缺稳定 parent identity、作用域、换算、唯一/循环/版本克隆契约；包材重复录入自然用量和单位。
- **修改文件**：`BomItemSubstitute`/DTO/repository/service、Flyway `V20261028_99`、BOM recipe/clone/seasoning workspace、Web BOM 表单/树形摘要/packaging cards/替代选择器及测试。
- **测试**：自替代/同父重复/作用域/单位换算/循环拒绝、原料/辅料/包材共用关系、版本克隆、同工序辅料替代、1箱8盒折算0.125、无数量原料合法与包材正数门禁；目标 30/30 与真实 JPA startup gate 1/1 已通过。
- **终审补强**：替代关系读取失败在 Web 标记为未加载并禁止编辑/保存，不能把失败误作空集合后删除既有关系；同单位省略换算时按1:1，跨单位必须显式输入正换算系数；包材替代除包装层级/角色快照外，还必须能证明同一稳定分类族，分类缺失或跨外箱/封膜等角色均 fail-closed。
- **Commit/PR/main 状态**：核心 `785a2d908`，主流程集成 `301fcb0cd`，Web fail-closed `e7beda5f8`；`TARGET_TEST_PASSED_PENDING_MAIN`。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`FINAL_RELEASE_GATE_PASSED_PENDING_MAIN`；未修改生产 `BOM-20260720-004/005`。

### F006-R2-WORKFLOW-FIRST-BOM-GATE / F006-R2-BOM-COMPLETENESS-GATE

- **最终状态机**：`SKU_CREATED → WORKFLOW_DRAFT_STRUCTURALLY_COMPLETE → BOM_DRAFT_CONFIGURABLE → BOM_COMPLETE → BOM_ACTIVE → WORKFLOW_ENABLED`；运行态仍只消费 ACTIVE BOM/已启用 Workflow。
- **期望/实际/业务影响**：Workflow 结构未完成前禁止 BOM 写入；BOM 完整性必须按 pinned target 子图逐工序判断辅料/明确无需辅料，并按每个启用包装层判断包材。实际按 mutable latest DRAFT 和 master workProcessId 判断，会漏检重复工序节点并使 ACTIVE BOM 依赖后续草稿。
- **修改文件**：`ProductConfigurationReadinessService`、`ProductConfigurationCompletenessReport`、activation/publish/plan gates 与目标测试。
- **测试**：越级调用4xx且零部分写、ACTIVE 无 DRAFT 仍按快照校验、同 master process 多节点分离、缺辅料/包材明确定位、历史 snapshot 不漂移。
- **Commit/PR/main 状态**：精确 pinned-node/readiness 子修复 `301fcb0cd` 与 Web 入口锁写 `e7beda5f8` 已随 PR [#1545](https://github.com/Stevenjxie/cretas/pull/1545) 合入代码 main `409aab41db0d031bc508ca528ac1d3c5e3c16cdf`；更大范围的 Workflow-first/BOM completeness 父任务仍按 ACTIVE 台账继续，不在本条冒充全部完成。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：目标回归已纳入后端 139/139 与 Web 30/30；clean rebase 后 Java 最终单生命周期 221/221 与 Web release build 均已通过，精确子修复已合入 main。

### FIX-F006-R2-TAXONOMY-MIGRATION-CONFLICT-001

- **发现阶段/影响**：R2 分类身份迁移审查；历史规范化名称可能在同一父级发生冲突，若直接建立唯一索引会导致启动失败，若自动合并则会改写历史分类真值。
- **根因/修复**：迁移先把冲突的历史 `normalized_label` 安全隔离为 `NULL`，仅对 active 且非空记录建立部分唯一索引；服务层用 NFKC 规范化检查历史标签并返回明确 409。历史分类不合并、不重命名、不删除。
- **修改文件**：`V20261028_92__material_taxonomy_identity.sql`、`MaterialCodeSegmentServiceImpl`、repository 与 migration/JPA contract tests。
- **测试**：23 项通过，包含真实 Spring Data JPA Context；commit `288960d91`。
- **Commit/PR/main 状态**：`COMMITTED_PENDING_MAIN`。
- **部署状态**：`NOT_DEPLOYED`；生产业务写入 0。
- **回归状态**：`TARGET_TEST_PASSED`。

### BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001-CORE

- **发现阶段/影响**：R2 采购付款审查；旧采购侧 PaymentRequest 写入口可绕过唯一 AP 应付/核销真值，且历史未分配付款不应被自动桥接或伪装成可再次全额支付。
- **根因/修复**：采购侧 legacy create/submit/finance-approve/reject/mark-paid 统一 fail-closed 410；付款从 AP open item 发起并强绑定 supplier/PO/receipt/currency，支持部分/全额核销、悲观锁、幂等和余额上限。历史 identity/分配不完整记录标记 `NEEDS_RECONCILIATION`，只读保留且结算 409，不自动匹配。
- **修改文件**：AP settlement controller/DTO/entity/repository/service、`V20261028_94__ap_payable_settlement_core.sql`、PaymentRequest legacy boundary 与相关 tests。
- **测试**：Controller/Service/Migration 21 项通过；真实 Spring/Hibernate JPA Context 2 项通过；commit `1befab078`。
- **Commit/PR/main 状态**：`COMMITTED_PENDING_MAIN`。
- **部署状态**：`NOT_DEPLOYED`；未修改生产 PO/RCV/AP/PaymentRequest。
- **回归状态**：`TARGET_TEST_PASSED`。

## F006 R3 原料分类编码阻塞（2026-07-21）

### BUG-F006-R3-MATERIAL-CATEGORY-PREFIX-001

- **发现阶段/时间**：F006 R3 人工 MVP，2026-07-21；仓储管理 → 原料类型字典 → 新建原料类型，完整选择 `001 / 001007 / 0010070004` 后保存。
- **页面/步骤/证据**：页面已展示16位编码预览 `0010070002000002`，保存却连续出现三次“当前物料分类尚未配置业务编码前缀”。证据：`D:\Temp\codex-clipboard-7c2f3f97-c2c0-48a4-bff0-a3e7eebcd1a4.png`。
- **期望/实际**：可选择的启用 L3 应能使用稳定编码契约创建，且预览与保存同源；实际 Web 预览只解析旧16位分类码，创建边界再调用独立业务前缀分配器。相同 API 错误又被页面 catch 重复展示。
- **业务影响**：阻断新原料建档；同时使用户无法判断分类不可用还是编码服务故障，并存在重复点击风险。
- **根因**：双码能力引入后，`material-segments/generate-code` 与 `MaterialBusinessCodeService.allocateBusinessCode` 仍是两条不一致的 resolver；前缀表没有为全部历史 L3 预置配置，而旧分配器对此一律 409。Web 同时由请求拦截器和页面 `ElMessage.error` 处理同一 4xx，造成重复 Toast。
- **修复**：新增只读 `MaterialCodePreviewDTO` 契约，预览和创建共同调用 `MaterialBusinessCodeService`。显式、启用的最具体祖先前缀优先；没有显式前缀的启用 L3 按不可变10位数字 identity 的 base36 生成稳定 ASCII 前缀，首次创建在 L3 悲观锁下原子持久化并分配序号。预览严格零写；停用前缀、跨厂/非 L3/停用分类及前缀冲突 fail-closed，不覆盖或猜测历史配置。
- **前端修复**：完整 L1-L3 选定后同时显示“业务编码”和“16位分类编码（兼容）”；保存前复用同一只读契约，快速切换时丢弃 stale 响应；统一使用 `handleCatchError`，服务端业务错误由拦截器仅显示一次，网络错误仅显示一个兜底提示。
- **修改文件**：`MaterialBusinessCodeService/Impl`、`MaterialBusinessCodePrefixRepository`、`RawMaterialTypeService/Impl/Controller`、`MaterialCodePreviewDTO`、原料类型字典 `list.vue` 及对应 Java JPA/Service、Web source/error-toast 测试；复盘与 dispatch 归档。
- **测试**：`mvn "-Dtest=RawMaterialTypeSegmentContractTest,MaterialBusinessCodeRepositoryQueryValidationTest" test`：19/19 PASS，其中真实 Spring/Hibernate JPA Context 8/8；覆盖显式前缀、缺省稳定前缀、预览零写、12并发首次分配唯一、跨厂隔离、停用前缀拒绝、重复名称零分配。`npm test -- src/views/warehouse/material-types/__tests__/materialFamilyConsistency.source.spec.ts src/utils/__tests__/errorToast.spec.ts`：12/12 PASS。`npm run build:check`：PASS，`vue-tsc` 与 Vite 4452 modules 构建成功。
- **Commit/PR/main 状态**：实现 commit `83deeba16f7c91693e443666951b878758300945`；PR [#1547](https://github.com/Stevenjxie/cretas/pull/1547)；当前 `TARGET_TEST_PASSED_PENDING_MAIN`，最终合并状态以该 PR 与 `origin/main` 为准。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`TARGET_TEST_PASSED`；生产业务 mutation=0，未创建或修改 F006 原料/分类，未做历史桥接，未触碰 LIUSHANMEN。部署后由测试 Chat 在同一未创建现场刷新，重新选择原分类并完成唯一一次建档验证。

### ENH-F006-MATERIAL-BUSINESS-CODE-001 — 按当前 L3 受控映射历史编码

- **发现阶段/时间**：F006 R3 编码阻塞修复后的双码兼容核对，2026-07-21；用户确认需要依据当前 L3 为既有物料映射 `businessCode`，并厘清 `displayCode` 与旧16位编码的关系。
- **期望/实际/业务影响**：历史 `code` 必须继续作为不可变 `legacyClassificationCode`，新业务码只能在 L3 身份可证明时分配；`displayCode` 应优先显示 `businessCode`、缺失时回退旧码。现有 V93 迁移出于安全原因没有历史回填，因此既有记录全部仍通过16位码展示，不能仅靠新建路径自动获得新码。
- **只读审计结论**：不同工厂的历史分类完整度并不一致；有的记录能完整匹配当前启用 L3，也存在无法证明当前 L3 身份的旧行。生产精确计数仅保留在受控测试证据中，不进入公开仓库。本次审计只执行查询，业务 mutation=0。
- **安全裁决**：禁止改写16位 `code`、历史外键或分类身份；任何无法证明当前启用 L3 的历史行均只报告、不按名称或旧前缀猜测回填。`displayCode` 不是新增数据库列，而是稳定派生值：有 `businessCode` 时显示新码，否则显示旧16位码。
- **根因/修复**：新增工厂隔离的只读预览和显式确认回填边界，共用 `MaterialBusinessCodeService` 的显式前缀/L3稳定前缀 resolver。预览不占号、不写计数器；正式回填悲观锁定工厂物料行，仅对 `business_code IS NULL`、16位旧码合法且 L3 当前启用的行原子赋码。既有码跳过、无有效 L3/非法旧码逐项报告；同值重放成为 no-op，并发执行只能分配一次；任一冲突整事务回滚。
- **权限/接口**：`GET .../raw-material-types/business-code-backfill/preview` 只读；`POST .../raw-material-types/business-code-backfill` 要求 `system:read_write` 且角色为 `factory_super_admin`/`permission_admin`，并要求显式 `confirm=true` 与幂等键。普通新增/编辑接口不能借此改写历史业务码。
- **修改文件**：`MaterialBusinessCodeBackfillService/Impl`、回填 request/report DTO、`RawMaterialTypeRepository` 受控锁与条件更新、`RawMaterialTypeController` 管理接口，以及真实 JPA/Controller 目标测试。
- **测试**：`mvn -Dtest=MaterialBusinessCodeRepositoryQueryValidationTest,RawMaterialTypeFoolproofMvcTest test`：17/17 PASS；真实 Spring/Hibernate JPA Context 覆盖预览零写、唯一预览、正式映射、旧码保持、displayCode切换、重复回填 no-op、无效 L3/非法旧码跳过、并发只分配一次；Controller 覆盖未确认400和确认后调用。
- **Commit/PR/main 状态**：`TARGET_TEST_PASSED_PENDING_MAIN`；精确 commit/PR/main 在合入后更新。
- **部署状态**：`NOT_DEPLOYED`；未执行生产回填、未修改任何生产物料或分类。
- **回归状态**：`CODE_AND_TARGET_TEST_COMPLETE`；部署后也必须按工厂先预览，任何正式历史映射仍需用户单独授权，不能随部署自动执行。

### BUG-F006-R3-MATERIAL-DISPLAY-CODE-002 — 新建原料仍以前台16位编码为主

- **发现阶段/时间**：F006 R3 后端双码能力部署后的生产只读 UI 核对，2026-07-21。
- **页面/步骤**：仓储管理 → 原料类型字典 → 新建原料类型；分类区标题仍为“16位编码级联”，L1-L3 选项数字编码在前，列表与编辑框继续显示旧 `code`。
- **期望/实际/业务影响**：新原料应以短 `businessCode/displayCode` 作为用户主身份，16位 `code` 只承担历史兼容；实际 Web 列表直接绑定 `row.code`，造成“新编码没有生效”的正确观感，并增加日常识别负担。
- **证据路径**：`D:/Temp/codex-clipboard-42e8b470-897b-4a59-8eac-cd5268aa856e.png`。
- **根因**：后端创建、DTO 与搜索已支持 `businessCode/displayCode`，但 Web 只完成预览接入，列表、编辑态和分类文案未切换到双码展示契约。
- **修复**：列表和编辑态统一优先 `displayCode → businessCode → legacy code`；有短码时旧16位仅在“兼容码”提示中查看，历史未映射行回退旧码并标“历史编码”。新建区改为“物料分类与业务编码”，分类下拉以名称为主、内部分类码为次级说明；搜索文案明确同时支持名称、业务编码和历史编码。
- **修改文件**：`web-admin/src/views/warehouse/material-types/list.vue`、`materialFamilyConsistency.source.spec.ts`；后端、数据库和生产物料均未修改。
- **测试**：物料分类/双码 Web 目标测试 13/13 PASS；`vue-tsc -b` PASS；正式 Web release build PASS（735 assets），可信制品清单已生成且未重复构建。
- **Commit/PR/main 状态**：实现 commit `8a39acecd045a6338659f8a7e71eefe125bc1fdb`；PR [#1552](https://github.com/Stevenjxie/cretas/pull/1552)；状态 `READY_FOR_MAIN`。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`TARGET_TEST_PASSED`；生产业务 mutation=0，未创建/修改原料、分类或业务编码，未执行历史回填，未触碰 LIUSHANMEN。

### ENH-F006-MATERIAL-SUPPLIER-BIDIRECTIONAL-001 — 供应商与物料缺少双向多对多维护

- **发现阶段/时间**：F006 R3 原料/供应商主数据人工回归，2026-07-21。
- **页面/步骤**：原料类型字典 → 供应商；供应商详情 → 供应原料。原料侧只有只读空列表，供应商侧原入口实际是采购历史汇总，均不能维护正式关系。
- **期望/实际/业务影响**：一个供应商可关联多个物料，一个物料可关联多个供应商，同厂 `(factoryId, supplierId, materialTypeId)` 不得重复；实际用户无法建立关系，采购单也无法取得供应商限定的物料、采购单位和报价。
- **证据路径**：`D:/Temp/codex-clipboard-ebfc75cd-353f-4008-a1a4-a878865396a2.png`、`D:/Temp/codex-clipboard-a0fd5205-c3de-4d16-ac0b-e112d88123d5.png`、`D:/Temp/codex-clipboard-dbdd9cc9-97d6-490a-bc8c-b21aa73f0529.png`。
- **根因**：后端已有独立 `SupplierMaterial` 多对多实体/API和同厂唯一约束，但两个前端入口仍使用采购历史/只读查询；关系 DTO 对采购单位、价格来源和物料参考价信息不完整。
- **修复**：原料侧和供应商详情统一调用同一 `SupplierMaterial` API，支持搜索关联、编辑关系属性、唯一首选、停用与双向刷新；已关联供应商从候选排除，后端继续执行同厂身份、状态、重复和首选唯一门禁。采购历史继续作为独立只读语义，不冒充关系配置。
- **修改文件**：`web-admin/src/api/supplierManagement.ts`、`web-admin/src/views/warehouse/material-types/list.vue`、`web-admin/src/views/procurement/suppliers/SupplierDetailDrawer.vue`、`SupplierMaterialDTO/Request/ServiceImpl` 及目标测试。
- **测试**：`SupplierMaterialServiceImplTest`、`SupplierMaterialPurchaseSpecServiceImplTest` 与 Web `supplierMaterialRelation.source.spec.ts`；已纳入本批后端37项/Web14项目标测试并通过。Repository 变更的真实 JPA Context `SupplierRepositoryQueryValidationTest` 1/1 PASS。
- **Commit/PR/main 状态**：实现提交 `94bfdb74071d94e41c87426e91b190bf471ece12`；PR [#1557](https://github.com/Stevenjxie/cretas/pull/1557) 已合入；main `9de6436d1fe49eb9cadfa24c6de893cb9eb74cfc`。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`CODE_AND_TARGET_TEST_COMPLETE`；生产业务 mutation=0，未修改任何现有供应商、物料或关系，未触碰 LIUSHANMEN。

### BUG-F006-PURCHASE-SUPPLIER-PRICE-UNIT-CONTRACT-001 — 采购单位与供应价格未和物料/供应关系联通

- **发现阶段/时间**：F006 R3 供应原料关系及采购订单人工回归，2026-07-21。
- **页面/步骤**：新增供应原料关系时采购单位为普通输入框、采购价无计价单位；采购订单选择物料后仍可形成与供应关系不一致的单位/价格或把缺失价格静默写成0。
- **期望/实际/业务影响**：MaterialType 提供库存基本单位与可选参考价，SupplierMaterial 提供该供应商的采购单位/默认报价，PurchaseSpec 提供受约束包装换算；选中供应商+物料后采购行应继承同一真值，价格显示“元/单位”，后端拒绝错配且缺价保持未配置。
- **证据路径**：`D:/Temp/codex-clipboard-e44de6bf-3973-4c18-af53-86eaf4453275.png`。
- **根因**：供应关系页面使用自由文本单位；采购单前端直接维护独立 unit/price，后端只有零散兜底且外层赋值会绕过统一解析，缺失值还可能被前端转为0。
- **修复**：供应关系复用共享 `UnitSelect/displayUnit`；默认采购价按采购单位明确标注；原料参考价作为所有物料类型可选档案值；采购单只能选择当前供应商 ACTIVE 关系和规格，自动带入供应关系/规格/物料参考价并标注来源；后端按 `PurchaseSpec → SupplierMaterial → MaterialType` 受控解析单位、换算与价格，非法单位/规格明确拒绝，null 不再变0，订单行继续保存快照。
- **修改文件**：`RawMaterialTypeDTO/ServiceImpl`、`SupplierMaterial*DTO/Request/ServiceImpl`、`SupplierMaterialPurchaseSpec*`、`PurchaseServiceImpl`、采购/原料/供应商 Web 页面及对应目标测试。
- **测试**：覆盖供应关系单位价格、物料参考价兜底、规格包装换算、单位错配拒绝、缺价不伪造；已纳入本批后端37项/Web14项目标测试并通过。
- **Commit/PR/main 状态**：实现提交 `94bfdb74071d94e41c87426e91b190bf471ece12`；PR [#1557](https://github.com/Stevenjxie/cretas/pull/1557) 已合入；main `9de6436d1fe49eb9cadfa24c6de893cb9eb74cfc`。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`CODE_AND_TARGET_TEST_COMPLETE`；未修改生产采购单、供应商、物料或价格。

### BUG-F006-PURCHASE-ORDER-CREATE-ENTRY-001 — 新建采购订单仍弹出多录入方式

- **发现阶段/时间**：F006 R3 采购订单入口人工回归，2026-07-21。
- **页面/步骤**：采购管理 → 采购订单 → 新建采购订单；仍弹出普通新建、一键快速、二维表格、BOM展开四选一。
- **期望/实际/业务影响**：产品已确认当前只保留普通新建，主按钮应直接进入完整普通表单，独立 AI 录入保持；旧选择弹窗增加无效决策和误导入口。
- **证据路径**：`D:/Temp/codex-clipboard-9d870df6-6bff-4ee0-a511-ca4455b99e8d.png`。
- **根因**：采购列表仍保留旧 `CreateModeSelector` 与多入口状态机，之前需求只登记、未在现行页面落地。
- **修复**：主按钮直接调用普通新建表单；移除方式选择弹窗及三项废弃入口；AI 录入入口与普通表单原业务契约保持。
- **修改文件**：`web-admin/src/views/procurement/orders/list.vue`、采购入口源契约测试。
- **测试**：Web `orderCreationContracts.source.spec.ts` 覆盖直接打开、废弃入口不可见与 AI 入口保留；已纳入本批 Web14项目标测试并通过。
- **Commit/PR/main 状态**：实现提交 `94bfdb74071d94e41c87426e91b190bf471ece12`；PR [#1557](https://github.com/Stevenjxie/cretas/pull/1557) 已合入；main `9de6436d1fe49eb9cadfa24c6de893cb9eb74cfc`。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`CODE_AND_TARGET_TEST_COMPLETE`；未创建或修改生产采购订单。

### BUG-F006-R3-OA-PROCUREMENT-001 — 采购提交后没有 OA 实例/节点/待办

- **发现阶段/时间**：F006 R3 采购订单审批闭环，2026-07-21。
- **页面/步骤**：`PO-20260721-0001` 仅提交一次后状态为已提交，但详情审批人/节点为空；进入“我参与的工作流”显示0条。
- **期望/实际/业务影响**：首次提交应在同一事务创建唯一 OA 实例与首节点待办，发起人可在“我发起的”查看、审批人在“待我审批”处理，后续业务/财务节点连续推进；实际 `submitOrder()` 只改采购状态，OA 启动被错误放在旧直接审批路径，产生不可继续的孤儿 `SUBMITTED`。
- **证据路径**：`D:/Temp/codex-clipboard-1dca4a61-f74a-4527-a149-790ea215e98c.png`、`D:/Temp/codex-clipboard-5200e993-de32-4fbb-8366-b27c30738b40.png`。
- **根因**：采购提交与 Workflow Engine 没有事务边界；详情链接指向错误个人视图；待办 DTO/读模型缺少当前节点、角色和完成状态；采购/财务旧端点仍允许业务页旁路。
- **修复**：提交对采购单加悲观锁，先校验供应商、行、ACTIVE 模板、首节点角色及同厂可用处理人，再在同一事务启动唯一实例并投影领域状态；重复提交返回同一流程真值，已有 `SUBMITTED` 但无实例明确409，事务失败整体回滚。详情新增只读 `approval-progress`；新增统一“待我审批”，“我参与的”同时包含发起人和历史处理人且去重，发起人也可从“我发起的”查看；OA 动作要求当前节点+幂等键并用 optimistic/CAS 防双批，采购业务/财务旧直接审批端点统一410 fail-closed；业务节点通过后同一实例自动继续到财务节点或完成；Workflow Redis 状态仅在数据库事务 afterCommit 后刷新，回滚不留下幽灵实例。
- **修改文件**：`PurchaseController/Service/Repository`、`WorkflowInstanceController`、`WorkflowEngineService/Impl`、`ApprovalWorkflowInstanceRepository`、OA DTO、采购详情/待办/路由/工作台及目标测试。
- **测试**：`PurchaseServiceOaSubmissionTest` 覆盖首次提交、自动完成、重复幂等、孤儿已提交拒绝、缺处理人事务回滚、终态动作纯读；`PurchaseControllerOaOnlyTest` 覆盖所有旧审批入口410；WorkflowEngineServiceImplTest 的 Redis afterCommit 精确测试 1/1 PASS；Web OA 契约测试覆盖正确个人视图、待办入口、节点CAS与无业务页审批。已纳入本批后端37项/Web14项目标测试并通过；真实 JPA Context 1/1 PASS。
- **历史单安全恢复方案**：当前任务不操作 `PO-20260721-0001`。部署后仍先 query-only 证明它严格为同厂 `SUBMITTED` 且无实例；只有用户另行授权后，才允许使用代码支持的受限修复事务，以订单ID+当时唯一有效模板摘要构造幂等键，只补缺失 OA instance/首节点任务/审计读模型，不改订单行、金额、收货、库存或财务事实；同摘要重放 no-op，不同/歧义模板拒绝。
- **Commit/PR/main 状态**：实现提交 `94bfdb74071d94e41c87426e91b190bf471ece12`；PR [#1557](https://github.com/Stevenjxie/cretas/pull/1557) 已合入；main `9de6436d1fe49eb9cadfa24c6de893cb9eb74cfc`。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`CODE_AND_TARGET_TEST_COMPLETE`；生产业务 mutation=0，未取消、重提、重建或桥接 `PO-20260721-0001`。

### 2026-07-21 本轮严格审计边界与业务纠正

- 本轮只把有当前代码/截图/目标测试证据的四项标为完成；昨天跨 M10-M12、BOM、Workflow、财务等大量需求未逐项得到现行代码证据的，不推断为 PASS，后续需按独立 scope 核对。
- 用户要求核对的是昨天另一个测试 Chat 的需求，不是 Google Sheet；此前 Sheet 审计项撤回，不作为本批完成依据。
- 工序主数据的最终真值是“工序本身没有单位”，不是给工序补单位选择器；已登记的工序单位删除需求保持，不在本批反向新增单位。
- 零差异盘点仍保留 `PENDING_APPROVAL → APPROVED → APPLIED`，自动完成建议已撤回。
- Workflow 运行态只消费 ACTIVE BOM；存在 DRAFT 时仅改进文案，不允许 Workflow 消费草稿。
- 原料名称智能建议 `case` 本身可以正确；缺陷是英文 canonical 泄漏和错误标成 manual override，不得改成“原料一律 kg”。
- BOM 原料/辅料固定数量默认可空，实际投料来自计划/报工；包材固定用量仍是激活门禁。以上纠正不得被旧建议覆盖。

## Cretas 全系统个人 OA 工作台（2026-07-21）

### FEATURE-CRETAS-OA-PERSONAL-WORKBENCH-001

- **发现阶段/时间/页面/步骤**：F006 R3 采购 OA 修复后的入口审计，2026-07-21；路由已有 /workflow/pending、my-created、my-participated，但侧边栏没有独立个人 OA 模块，财务/采购等审批角色无法稳定进入统一待办。
- **期望/实际/业务影响**：所有登录角色都应拥有“个人 OA”，含待我审批、我发起的、已处理、抄送我的；任务仍必须按工厂、当前节点角色/明确用户过滤。实际工作流页面被归入 system 权限且没有菜单入口，“我参与的”又把仅由本人发起的实例混入“已处理”，无法形成可信个人审批工作台。
- **参考核对**：只读参考 PomeloX 已有审批首页的四队列信息架构和逐用户任务过滤；Cretas 继续复用现有 ApprovalWorkflowDefinition/Instance/History 与节点角色契约，不复制 PomeloX 状态机、不创建平行审批引擎。
- **根因**：个人审批路由错误绑定 system 模块，finance/procurement 等合法审批角色可能被前端权限挡住；menuConfig 与财务专用菜单均缺 OA；后端只有“我发起或处理过”的混合查询，没有 actor-only 已处理视图；notify 节点虽写 append-only history，却没有面向个人 OA 的抄送读模型。
- **修复**：新增顶级“个人 OA”及四个队列，统一使用所有登录角色具备的 dashboard 访问边界，真正的数据可见性由后端工厂/角色/用户过滤决定。新增 actor-only acted 查询；copied 从已持久化 notify-node transition 与节点 recipients/notifyRoles 推导，显式用户或当前角色命中才可见。my-participated 旧深链重定向到“已处理”。非采购业务域的待办可只读展示进度，但在领域 adapter 完成前不显示可写审批按钮，避免伪造通用领域回写。
- **修改文件**：WorkflowInstanceController、WorkflowEngineService/Impl、ApprovalWorkflowInstanceRepository、ApprovalHistoryRepository；Web router/index.ts、menuConfig.ts、pending.vue、my-created.vue、acted.vue、copied.vue；Java/Web/JPA 目标测试。
- **测试**：Web 菜单/路由/动作门禁 64/64 PASS；Java clean package 19/19 PASS（Controller 5、Service 13、真实 Hibernate JPA Context 1），BUILD SUCCESS；Web production build PASS，739 个资源。可信 manifest：Java JAR SHA-256 `6b5c19590f87773b8a8a294fbc0dca83ae4d482b5be58220c196e5d07612d957`，Web archive SHA-256 `2f79e87bdcf2e4539cd9c76d587639cf663fb004afad37d9df6f4899980ee5e2`。
- **Commit/PR/main 状态**：实现与门禁 commit `9d48e91ba61feada2fb356da44958002fd9516c6`；PR [#1560](https://github.com/Stevenjxie/cretas/pull/1560) 已合入；exact main `481b57f3b07755f0ad6fd7b0a68e9208e9093f90`。
- **部署状态**：NOT_DEPLOYED。
- **回归状态**：TARGET_TEST_PASS；生产业务 mutation=0，未创建/处理/桥接任何 F006 或 LIUSHANMEN 审批实例。

## F006 历史采购单 OA 恢复边界（2026-07-21）

### F006-OA-RECOVERY-001

- **发现阶段/时间/页面/步骤**：F006 R3 采购 OA 修复上线后的历史单续跑审计，2026-07-21；`PO-20260721-0001` 已处于 `SUBMITTED`，但没有关联 OA instance、当前节点或待办。
- **期望/实际/业务影响**：历史 split-brain 记录应能在严格受限、可审计、幂等的管理员事务中仅补齐缺失 OA 实例；实际现有提交入口对该状态正确 fail-closed，但没有安全恢复边界，订单永久无法继续审批。
- **证据路径**：`D:/Temp/codex-clipboard-1dca4a61-f74a-4527-a149-790ea215e98c.png`、`D:/Temp/codex-clipboard-5200e993-de32-4fbb-8366-b27c30738b40.png`。
- **根因**：采购提交原子化修复只覆盖未来首次提交；为避免自动桥接历史生产数据，旧 `SUBMITTED + workflowInstanceId=null` 被保留为冲突状态，但缺少独立的高权限恢复命令和订单快照/路由复核。
- **修复**：新增仅 `factory_super_admin` 可调用的采购 OA 恢复端点；强制校验工厂、订单 UUID、精确业务单号、`confirm=true`、幂等键与非空原因，并在行锁事务内验证订单仍为 `SUBMITTED`、仍无实例、冻结供应商/订单行 identity 完整、当前 PURCHASE_ORDER 流程可产生可执行节点。实例以原始创建人为发起人，恢复操作者、原因、幂等键和时间写入 workflow context；同 key 重放不重复创建，不同 key、已有实例、跨厂、无路由或无可执行节点全部 fail-closed，状态投影前失败不保存采购单。
- **修改文件**：`RecoverPurchaseApprovalRequest`、`PurchaseApprovalRecoveryResponse`、`PurchaseController`、`PurchaseService`、`PurchaseServiceImpl` 及对应 Controller/Service 目标测试。
- **测试**：正式 release lifecycle `mvn clean package -Dtest=PurchaseServiceOaSubmissionTest,PurchaseControllerOaOnlyTest` 16/16 PASS（Controller 3、Service 13），BUILD SUCCESS；覆盖首次恢复、原始发起人、审计 context、同 key 幂等、不同 key 冲突、确认/业务单号门禁、跨厂拒绝、缺路由与空节点事务失败。可信 JAR SHA-256 `0bd4a698095af829a8f1d984015089dcf24bbc770c5221782c21d6e05ca8167c`，backend tree `25802487482a3c1605c28becc5eb44f39d2df326`。
- **Commit/PR/main 状态**：实现与门禁 commit `7da960aa5bc2680bea71b9e9c881357a87b8ad05`；PR [#1567](https://github.com/Stevenjxie/cretas/pull/1567) 已合入；部署源码 main `c93e31a63d860db8e98996c705c5ee25dfa93108`。
- **部署状态**：`DEPLOYED`；Java `v20260721_233852`，active `green/10020`，5/5 切流后健康通过；`verify-release` 的 systemd、直连健康及 `approval-recovery` JAR marker 均通过。
- **回归状态**：`RECOVERED_AND_VERIFIED`；部署后先 query-only 证明 `PO-20260721-0001` 为 `SUBMITTED`、无实例，取得用户单独明确授权后使用固定幂等键严格执行1次恢复 POST。创建唯一实例 `6949ac9a-fd33-40e2-a45e-26db666035d2`，流程 `awf-f006-po-default` 自动完成为 `APPROVED`，订单投影为 `FINANCE_APPROVED`；刷新后匹配实例严格1个、审批历史1条、待办0，金额 `1440.00`、税额 `187.20`、9行及供应商 identity 不变。除该次授权恢复外业务 mutation=0，未取消、重提、重建订单，未触碰 LIUSHANMEN。

## F006 R3 采购审批后仓储收货断链（2026-07-22）

### BUG-F006-R3-PURCHASE-RECEIVING-ROUTE-001

- **发现阶段/时间/页面/步骤**：F006 R3 采购 OA 完成后的仓储续跑，2026-07-22；`PO-20260721-0001` 财务已审核，从采购详情点击“前往仓储收货任务”却进入仅有历史批次的原料页面，无法定位采购待收任务。
- **期望/实际/业务影响**：审批完成采购单应以只读投影出现在统一仓储页面，仓储人员从来源任务创建/续办唯一活动收货单并确认入库；实际路由指向旧采购入库/批次视图且没有待办投影，原始采购到库存链阻塞。
- **证据路径**：`D:/Temp/codex-clipboard-a9975166-798c-403b-9714-633d4e441983.png`、`D:/Temp/codex-clipboard-20d8d21b-ae86-4841-a454-44551959e430.png`。
- **根因**：采购详情仍指向采购模块旧收货页面；现有采购收货 Entity/Service/批次物化能力没有面向仓储工作台的待收货读模型；活动草稿占用只按物料聚合，无法安全区分同订单同物料多行；收货附件权限仍按采购模块判断。
- **修复**：复用既有 `PurchaseReceiveRecord`、确认入库和库存批次写路径，仅新增 `FINANCE_APPROVED/PARTIAL_RECEIVED` 采购单的仓储待收货投影；新页面/API 统一收敛到 `/api/mobile/{factoryId}/warehouse/receiving/**`，旧采购收货页面删除、旧路由只做零写重定向，Web 源码不再调用 `/purchase/receives/**`；采购详情零写跳转到 `/warehouse/materials` 并携带订单 ID/单号；统一仓储页置顶浅红待收行，支持目标仓库、分批数量、拖拽供货凭证、打印和确认；同订单活动草稿 fail-closed，收货行固定 `purchaseOrderItemId`，创建与确认均锁定并校验剩余量/单位；仓储请求不能覆盖已审批 PO 行价格，附件服务不可用时确认 fail-closed，历史同物料多行歧义只读标冲突而不猜测分摊；仓储拥有唯一写权限，采购只读追溯。客供料、销售缺料、生产入库和 ownership 扩展明确不在本批。
- **修改文件**：`PurchaseController/PurchaseService/PurchaseServiceImpl`、采购收货 DTO/Entity/Repository/Flyway、附件权限与打印；Web `procurement/orders/detail.vue`、`warehouse/materials/*`、采购收货 API、router/menu 及目标测试。
- **测试**：Java 最终单生命周期 `clean package` 通过，10 个目标测试类共 `73/73`，含真实 Hibernate/JPA Repository Context 启动门禁；JAR SHA-256 `f9bbfdd51031726680296695591a240f971792c912b98ddec11fbd40caae48ea`。Web `vue-tsc --noEmit` 通过，3 个目标测试文件 `70/70`，release Web manifest 成功，archive SHA-256 `bc8c40a19f942ae6f7211582a7210ede9927eb2b4bda413e92879896bfc941ef`；全仓 Web 源码和闭环脚本无旧 `/purchase/receives/**` 消费者。
- **Commit/PR/main 状态**：分支最终构建提交 `dd2df6393d33f74c4dcc098756177171e1434e04`，以本条所在最终 squash PR 合入 `main`。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`TARGET_TESTS_PASSED_AWAITING_DEPLOY`；按用户决定不由 Codex 运行 Playwright，部署后由用户从同一 `PO-20260721-0001` 做页面续测；修复过程生产业务 mutation=0，未取消、重建、重提或桥接采购单，未触碰 LIUSHANMEN。

## F006 R3 销售订单客户自带原料闭环（2026-07-22）

### FEATURE-F006-R3-CUSTOMER-SUPPLIED-RECEIVING-001

- **发现阶段/时间/页面/步骤**：F006 R3 销售、仓储与生产贯通核验，2026-07-22；需要在销售订单明确“代加工 + 客户自带原料”，由仓储统一页面收货，并保证客户库存只能用于同一客户、同一销售订单的生产。
- **期望/实际/业务影响**：期望销售订单保存加工方式、供料方式和结构化来料需求；审批完成后，每条需求成为唯一待收货任务；仓储分批实收后生成 `CUSTOMER_OWNED` 原料批次；生产计划冻结客户/销售单/供料方式/产出所有权，普通采购、调拨、缺料计算、报工与销售库存池不得误用客户库存。原实现没有销售供料契约、客户来料任务、所有权血缘和精确领用门禁，若仅复用普通库存会造成公司库存与客户财产混用。
- **证据/现场边界**：本批仅代码与目标测试；不重放或桥接 `PO-20260721-0001`，不修改任何 F006/LIUSHANMEN 生产订单、库存、计划或批次；按用户决定不由 Codex 运行 Playwright。
- **根因**：销售订单、订单行、生产计划、原料批次和成品批次之间缺少统一的加工/供料/所有权快照；销售侧旧客供收货入口可直接写库存；通用 FEFO、库存汇总、反向调拨和报工自动分配默认把所有可用批次视为公司资产。
- **修复**：新增 `STANDARD_SALE/TOLL_PROCESSING`、`FACTORY_SUPPLIED/CUSTOMER_SUPPLIED` 与 `COMPANY_OWNED/CUSTOMER_OWNED` 契约；普通销售+客供料 fail-closed，当前 MVP 不允许订单内混合供料模式。结构化来料需求行本身作为唯一仓储任务 identity，不新增平行任务表；只有审批完成状态投影到统一 `/warehouse/receiving/tasks`。仓储按任务行锁、剩余量、单位、同厂仓库、附件和幂等键确认收货，直接形成带 customer/sales-order lineage 的客户所有批次；旧销售侧直接收货接口固定返回 410 且零写。销售复制保留供料契约但重置实收事实。生产计划冻结客户、供料方式与产出所有权；原料库存、正式报工自动分配、显式批次、结单扣减及成品入库均按同一客户+销售单精确校验；普通公司库存查询、低库存/价值汇总、调拨和销售可售池排除客户所有批次。Web 销售新建/编辑/详情贯通中文字段和结构化客供需求，仓储统一页面复用原采购收货区域并区分采购两阶段与客供一步确认；RN 快速销售表单显式提交普通销售+工厂备料，避免旧客户端产生含糊订单。
- **主要修改文件**：`SalesOrder/SalesOrderItem/ProductionPlan/MaterialBatch/FinishedGoodsBatch` 及 DTO/Repository/Service/Flyway；`WarehouseReceivingController`、`SalesController`、附件权限；`ProductionPlanServiceImpl`、`ProductionStockAllocationServiceImpl`、供应链/缺料监听；Web `sales/orders/list.vue`、`detail.vue`、`warehouse/materials/PendingPurchaseReceivingPanel.vue`、共享 API/contract；RN `SalesOrderCreateScreen.tsx`/`salesApiClient.ts`。
- **测试**：Java 单一发布生命周期共执行 17 个测试类、131/131 通过，覆盖销售供料字段与非法组合、需求唯一/跨厂/数量精度、审批前不可收、附件必需、部分收货、重复幂等、跨任务 key 冲突、旧入口 410、3 组真实 JPA Repository 启动、公司/客户库存隔离、生产计划快照、报工自动/显式领用、结单与客户成品所有权；Web Vitest 3 个目标文件 14/14 通过，覆盖销售三页、统一仓储入口、采购原路径回归、客供一次确认和中文单位，`vue-tsc --noEmit` 通过；Java/Web 不可变发布构建均通过。JPA 门禁真实发现并修复了成品仓库字段路径错误及库存月度查询 `LocalDate`/`LocalDateTime` 类型不匹配，最终启动校验全绿。
- **Commit/PR/main 状态**：实施 PR [#1588](https://github.com/Stevenjxie/cretas/pull/1588) 已通过门禁并 squash 合入；实施 exact main `5d9b6d2f23b391b5766c2c57130b69fd8eb83c1b`。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`TARGET_TESTS_PASSED_AWAITING_USER_PLAYWRIGHT`；按用户决定不由 Codex 运行 Playwright；生产业务 mutation=0，未做历史桥接，未触碰 LIUSHANMEN。

## F006 R3 生产计划误报 Workflow 未绑定（2026-07-22）

### BUG-F006-R3-PRODPLAN-WORKFLOW-BINDING-001

- **发现阶段/时间/页面/步骤**：F006 生产计划真实 headed 复测，2026-07-22；生产管理 → 生产计划 → 新建计划 → 存货生产，选择 `CPF0060016 / E2E-MVP-R2-20260720-01-黄油鸡-成品800g` 后，页面误报“未找到覆盖该产品的工序 Workflow”。
- **期望/实际/业务影响**：该 SKU 已启用 Workflow `108/v1`，发布图终端成品 identity 与 SKU 完全一致，生产计划应解析并固定该版本；实际 `resolve-by-outputs` 返回 `resolutionMode=NONE`，导致存货生产无法继续。打开 Workflow 页面还会在无用户编辑时尝试保存复核草稿，且右侧 Workflow AI 面板回归并挤压画布。
- **证据路径**：`C:/Users/Steve/my-prototype-logistics/tmp/e2e-f006-prodplan-workflow-binding-20260722/01-selected-product-workflow-result.png`、`02-workflow-config-and-ai-sidebar.png`、`report.json`。Headed 只读真值：activation `activeWorkflowId=108/activeDefinitionVersion=1/enabled=true`；Workflow `PUBLISHED/v1`、终端 `skuId=1652bd01-eb3f-43f1-9a5b-5e42ba9cb689`、`unitWarnings=[]`，但遗留 `unitReviewRequired=true`；`pageErrors=0`、`actualBusinessWrites=0`。
- **根因**：工厂单位主数据变化会批量把已发布 Workflow 标为 `unitReviewRequired=true`；解析、激活和运行时把这个宽泛失效标记直接当作当前单位契约不合法的最终结论，未使用已有权威实时校验器复核，因此有效 Workflow 被静默排除。前端加载发布版时又把该标记直接转换成 DRAFT、设为 dirty 并触发自动保存；Workflow AI 侧栏被再次固定渲染。
- **修复**：`unitReviewRequired` 改为“触发实时复核”的提示标记：未标记继续走快速路径；已标记时用当前工厂 SKU/端口单位契约校验，校验通过的发布版可被生产计划解析、激活和运行时物化，真实不兼容仍 fail-closed。发布版页面加载严格只读，不再因标记自动 fork/保存草稿；只有当前契约确有差异时显示复核提示。移除 Workflow 编辑器固定右侧 AI 面板并恢复全宽画布。
- **修改文件**：`ProductWorkflowResolutionServiceImpl`、`ProductProcessWorkflowActivationServiceImpl`、`ProductProcessWorkflowRuntimeServiceImpl` 及其目标测试；Web `ProductProcessWorkflowEditor.vue` 与 activation 目标测试。
- **测试**：Java 6 个目标测试类共 `55/55` 通过，覆盖陈旧标记+实时有效、实时无效继续拒绝、解析/固定版本/激活/运行时；Web Vitest `3/3` 通过，覆盖发布版保持只读、不保存草稿、右侧 AI 不渲染。
- **Commit/PR/main 状态**：本条随最终提交更新 exact commit/main。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`TARGET_TESTS_PASSED_AWAITING_MERGE`；修复和验证期间生产业务 mutation=0，未创建生产计划、未修改 Workflow/SKU，未触碰 LIUSHANMEN。

## F006 R3 销售订单统一 OA 断链（2026-07-22）

### BUG-F006-R3-SALES-OA-INSTANCE-001

- **发现阶段/时间/页面/步骤**：F006 采购→销售→生产 headed MVP，2026-07-22；新建并确认 `SO-20260722-0001` 后业务状态自动进入 `FINANCE_APPROVED`，但个人 OA“我发起的”没有该订单，审批节点、角色和实例均不存在。
- **期望/实际/业务影响**：销售订单提交必须和采购订单一样启动唯一、持久化的统一 OA instance；免人工审批只能自动走到 OA 终态，不能绕过 OA 留痕。实际 F006 销售仍由 legacy `approval_chain_configs` 直接修改业务状态，`/workflow/my-created` 正确返回“无实例”，导致同一系统采购有 OA、销售无 OA，超过阈值的人工财审也没有可处理待办。
- **证据路径**：`C:/Users/Steve/my-prototype-logistics/tmp/e2e-f006-mvp-sales-20260722101705`、`C:/Users/Steve/my-prototype-logistics/tmp/e2e-f006-mvp-sales-production-readonly-20260722101937`。本轮销售 mutation 仅创建并确认该唯一测试单；发现断链后立即停止后续写入，生产入口只读验证 `actualBusinessWrites=0`。
- **根因**：采购 `submitOrder()` 已强制 active graph 并由 `WorkflowEngine` 原子创建实例；销售 `confirmOrder()` 在无 active graph 时 fallback legacy threshold，低额/外部订单直接 `approveFinanceForOrder`，高额只写 `PENDING_FINANCE_REVIEW`，两路都不创建 `approval_workflow_instances`。销售 Controller 还未把本次确认人传给 workflow；OA action controller 仅适配 PURCHASE_ORDER。审查同时发现三处同因残留：待人工审批订单仍发布 confirmed event 并提前生成财务凭证；运行中实例没有阻止管理员原地修改定义；OA action 虽要求 `idempotencyKey`，却没有持久化唯一账本。
- **修复**：F006 初始化并发布 `SALES_ORDER_APPROVAL` graph，保持既有政策：外部渠道及金额不超过5000元自动通过、超过5000元进入财务审批。所有分支均由 `WorkflowEngine` 创建唯一实例；确认接口以 JWT 当前用户作为发起人；缺 active graph/引擎/发起人 fail-closed；OA action adapter 负责 approve/reject 后事务性回写销售状态，业务页旧财审入口不得绕过 OA。实例以确切 active definition 启动并写绑定摘要，存在 RUNNING 实例时禁止修改、删除、归档或停用其定义；销售凭证只在财务批准事件后生成，待审批阶段不记账。新增通用 OA action durable ledger，以 PostgreSQL transaction advisory lock 和 `(factoryId, instanceId, idempotencyKey)` 唯一键保证同键同请求返回原结果、同键异请求409、失败事务不留下 completed 记录。OA 待办批量回填销售订单号、客户与金额。已发布模板可由有对应管理员角色的用户在“Canvas 配置编辑器 → 审批工作流 → 销售订单审批”中维护条件、阈值和审批角色；SQL 只提供首次默认模板，不覆盖工厂现有有效配置，也不回填或改写历史业务记录。迁移采用明确切流：新提交只走统一 OA；旧审批配置与历史记录仅只读兼容，旧写入口在已接入 OA 的工厂 fail-closed，待消费者归零后再独立审计删除，不允许新旧两条可写路径静默并行。
- **修改文件**：`SalesController`、`SalesService/Impl`、`WorkflowInstanceController`、`ApprovalWorkflowServiceImpl`、销售凭证 listener、OA action idempotency Entity/Repository/Service、F006 SALES_ORDER workflow 与幂等账本 Flyway；Web 销售订单/旧财审页、OA 待办、Canvas/ApprovalWorkflowEditor、旧审批链兼容页及对应 Java/Web 目标测试；不修改既有生产销售单或 OA 实例。
- **测试**：最终 Java 不可变发布生命周期 11 个目标测试类、63/63 PASS，含真实 PostgreSQL JPA repository startup gate；Web Vitest 10 files/29 tests PASS，`vue-tsc --noEmit` PASS，不可变 Web release build PASS（Vite 4457 modules）；迁移契约、真实销售金额阈值引擎、销售低额自动终态仍持久实例、高额财务待办、真实发起人、缺路由事务回滚、重复提交、OA approve/reject、业务页绕过、自批/跨厂、待审批不提前记账、definition running guard、OA action replay/冲突/失败回滚均已覆盖。
- **Commit/PR/main 状态**：PR [#1614](https://github.com/Stevenjxie/cretas/pull/1614) 已合入；exact deployed implementation main `a0982983c0c88b63d1dff246ef7be61eaef1fd13`。
- **部署状态**：`DEPLOYED_PROD_SERVICE_VERIFIED`；Java `v20260722_201341`，green/10020，5/5 健康；Web 四方 SHA-256 一致，Flyway `20261029.01`/`20261029.02` 成功，F006 有且仅有一条 published+enabled 销售 OA 定义。
- **回归状态**：`AWAITING_USER_UI_ACCEPTANCE`；现有 `SO-20260722-0001` 保留为现场，不重建、不桥接、不修改；须用新销售单分别验证自动通过与财务待办；未触碰 LIUSHANMEN，发布业务 mutation=0。

### BUG-F006-SALES-DETAIL-PURCHASE-PERMISSION-001

- **发现阶段/时间/页面/步骤**：F006 销售统一 OA 生产 headed 验收，2026-07-22；销售主管通过 UI 创建 `SO-20260722-0002` 后进入销售订单详情，页面在提交 OA 前加载关联采购数据。
- **期望/实际/业务影响**：销售主管应可在自身权限内查看并提交销售订单；只有具备采购读取权限的角色才加载和看到“关联采购”。实际详情页无条件请求 `/api/mobile/F006/purchase/orders`，销售主管收到 403，全局错误通知遮挡“确认并提交 OA 审批”，流程被阻塞。
- **证据路径**：`C:/tmp/cretas-f006-sales-oa-20260722/web-admin/.playwright-mcp/f006-sales-oa-write-20260722124816/11-low-detail-draft.png`、`99-failure-stop.png`、`trace.zip`、`result.json`；订单保持 `DRAFT`，只读核验 OA instance 数量为 0。
- **根因**：`web-admin/src/views/sales/orders/detail.vue` 的 `loadPurchaseOrders()` 与“关联采购”页签没有使用共享 `permissionStore.canAccess('procurement')` 门禁；局部 catch 无法阻止 Axios 全局 403 通知。
- **修复/修改文件**：新增 `canViewLinkedPurchases` 权限投影；无采购读取权限时请求在发送前返回且隐藏关联采购页签，有权限角色保持原查询与查看能力。修改 `detail.vue`、`salesOrderOaContract.source.spec.ts`。
- **测试**：销售订单目标 Vitest `6 files / 34 tests` PASS，覆盖权限投影、请求发送前门禁及页签显隐；唯一 Web release build `4457 modules` PASS，web tree `5ef7f95db8d45ef15e192d276a4d8b7bbc68b9e4`，archive SHA-256 `f02dd2e3dd12fcd5807fd21caf525de98a6ff7fe086b7c343e4d7578eab3be21`。部署后复用同一 `SO-20260722-0002` 从 OA 提交点继续 headed 验收，不创建第二张订单。
- **Commit/PR/main 状态**：实现 commit `8e2cb88b2852a8558c4fffeb17f624356713063d`；PR [#1625](https://github.com/Stevenjxie/cretas/pull/1625) 已合入；exact main `1149da969d44073ccc2783125c081355f238bc73`。
- **部署状态**：`DEPLOYED`；Web 四方 index SHA-256 `7324154303b28cdf024857085fd5e6a0bb2b2977599497020967de2f959f9b47` 一致。
- **回归状态**：`PASS`；复用同一 `SO-20260722-0002`，销售主管页面采购 API 请求 0、采购 Tab 0，唯一 OA 提交 POST=200，订单 `FINANCE_APPROVED`，个人 OA“我发起的”读回已完成；未新建第二订单，未触碰 LIUSHANMEN。

### BUG-F006-R3-OA-FINANCE-ROUTE-001

- **发现阶段/时间/页面/步骤**：F006 销售统一 OA 从头生产 headed 验收，2026-07-22；销售主管通过页面创建高金额 `SO-20260722-0003` 并提交 OA 后，详情正确显示当前节点“财务审批”和明确处理人 `f006_finance_mgr`，但财务经理访问“个人 OA → 待我审批”直接进入 403。
- **期望/实际/业务影响**：财务经理应从个人 OA 处理分配给自己的本工厂任务；任务可见性和动作权限仍由后端工厂、节点角色、明确人员与领域 adapter 强制校验。实际侧边栏向财务经理展示个人 OA，但前端 `ROLE_PATH_WHITELIST.finance_manager` 未包含 `/workflow`，路由守卫在请求待办 API 前即拒绝页面，销售审批链阻塞。
- **证据路径**：`C:/tmp/cretas-f006-sales-oa-20260722/web-admin/.playwright-mcp/f006-sales-oa-write-20260722132525/result.json`、`13-high-detail-after-confirm.png`；续跑失败证据 `C:/tmp/cretas-f006-sales-oa-20260722/web-admin/.playwright-mcp/f006-sales-oa-write-20260722132734/99-failure-stop.png`、`trace.zip`。订单保留在 `PENDING_FINANCE_REVIEW`，未重复创建或审批。
- **根因**：个人 OA 菜单和 `/workflow/**` 路由已迁移到共享 `dashboard` 模块，但历史 finance-manager 路径白名单只允许 `/dashboard`、`/finance` 和旧财审页面，遗漏新的统一 `/workflow` 前缀；菜单、模块权限和路径白名单三层契约不一致。
- **修复/修改文件**：仅在 `web-admin/src/router/guards.ts` 的 finance-manager 白名单加入 `/workflow`；后端 OA 鉴权、任务过滤和订单状态机不变，不扩大其他业务模块权限。新增 `personalOaAccess.spec.ts`，同时复用财审路由与菜单契约测试。
- **测试**：Web 目标 Vitest `3 files / 63 tests` PASS，覆盖 finance-manager 个人 OA 路径、四个共享队列、财审角色与菜单一致性。
- **Commit/PR/main 状态**：实现 commit `0c7cb8370b49bb70c6f9f68e1055f8a607590518`；PR [#1628](https://github.com/Stevenjxie/cretas/pull/1628) 已合入；发布时 exact main `9a95c6daeb48a14b04532fb5bc68dc9318745eb1`。
- **部署状态**：`DEPLOYED`；Web 四方 index SHA-256 `4c15dabf17edd5865cfdbe6576d2ecfc38f9c9d6baf4ca7fa81d0fea5b4d43e9` 一致。
- **回归状态**：`PASS`；复用同一 `SO-20260722-0003`，财务经理进入待办、执行唯一 OA action POST=200，已处理与销售“我发起的”均为已完成，销售详情 `OA状态=已通过/流程已结束`；未创建第二订单，未触碰 LIUSHANMEN。

### BUG-F006-R3-OA-PRESENTATION-001

- **发现阶段/时间/页面/步骤**：上述路由修复部署后的同一生产 headed 续测，2026-07-22；财务经理个人 OA 待办已可正常处理 `SO-20260722-0003`，但待办表格显示内部码、空授权角色与原始 ISO 时间。
- **期望/实际/业务影响**：应显示“销售订单”“财务主管”和本地化到秒的提交时间；实际显示“未知状态（SALES_ORDER）”、`-`、`2026-07-22T21:26:36...`，不阻塞审批但破坏 OA 业务可读性与授权透明度。
- **证据路径**：`C:/tmp/cretas-f006-sales-oa-20260722/web-admin/.playwright-mcp/f006-sales-oa-write-20260722135603/30-finance-pending-high-order.png`、`33-finance-acted.png`、`40-high-order-final-readback.png`、`result.json`。
- **根因**：`getPendingForUser()` 已解析当前节点名称却漏回填节点配置中的 `approverRoles`；Web `enumDisplay` 没有统一 OA 模块/角色标签，`pending.vue` 直接输出 `initiatedAt`。
- **修复/修改文件**：Controller 待办 DTO 复用节点配置回填角色；共享枚举补采购/销售模块及财务/超级管理员中文标签；待办页复用 `formatDateTime`。修改 `WorkflowInstanceController`、对应 Controller 测试、`enumDisplay.ts`、`pending.vue` 与 OA 目标测试。
- **测试**：Java `WorkflowInstanceControllerTest` 8/8 PASS；Web OA Vitest 2 files / 6 tests PASS；`git diff --check` PASS。
- **Commit/PR/main 状态**：等待本条最终 commit/PR/main 回填。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`TARGET_TESTS_PASSED_AWAITING_RELEASE`；发布后只读刷新同一已完成实例，不再产生审批或订单 mutation。

### BUG-F006-R3-SALES-PRODPLAN-BOM-POLICY-001

- **发现阶段/时间/页面/步骤**：F006 销售到生产 headed 续测，2026-07-22；在已财务批准的 `SO-20260722-0002`（orderId `cc5ca6c8-5f0f-4418-9897-375fe0ca8bb5`）对 item 728 唯一点击“开始生产”，`POST /production-plans/batch-from-so` 返回 HTTP 500，追踪码 `CE901995`。
- **期望/实际/业务影响**：销售来源计划应固定日期、Workflow/BOM 与订单行，并保证同一有效计划唯一；实际 readiness 校验在保存前抛空指针，用户无法建计划。生产库 query-only 证明该 order/item/notes 在 `production_plans` 严格 0 行，失败没有留下部分计划。
- **证据路径**：`C:/Users/Steve/my-prototype-logistics/tmp/e2e-f006-so0002-production-plan-20260722143638`；服务端 `logs/cretas-backend-error.log` 的 `CE901995` 栈定位 `ProductConfigurationReadinessService.evaluateBom:245`。
- **根因**：已激活 Workflow 的旧节点快照缺少后续新增的 `auxiliaryPolicy`，`string(...)` 返回 null；Java immutable `Set.of` 的 `contains(null)` 会抛 NPE。计划创建又错误地对已经 ACTIVE 的历史 BOM 重跑“未来版本激活完整性”规则，既可能 500，也会在仅修 null 后把同一合法历史链改成阻塞性 409。
- **修复/修改文件**：readiness 对 null 策略显式判定无效，查询与未来激活继续生成 `BOM_AUXILIARY_DECISION_REQUIRED` 并严格 fail-closed；生产计划准入改为要求并固定当前 ACTIVE BOM + ENABLED Workflow 的原 identity/version，不追溯重判或改写历史已激活版本。修改 `ProductConfigurationReadinessService`、`ProductionPlanServiceImpl`、对应目标测试及复盘/台账；不桥接或改写现有 BOM/Workflow/订单。
- **测试**：唯一 release lifecycle `release-jar-manifest.sh build --tests 'ProductConfigurationReadinessServiceTest,ProductionPlanWorkflowSelectionTest,ProductionPlanSalesBatchDateTest'` 构建成功，11/11 通过（4+3+4），并生成最终可信 JAR manifest；覆盖 null policy 无 NPE、未来完整性门禁、历史 ACTIVE BOM/Workflow 固定、同订单行有效计划重复创建 409、日期独立、订单锁与保存前失败无部分写。
- **Commit/PR/main 状态**：实现 commit `0837a475c134094e60604d82afbb9f4c3a21aa91`；PR [#1637](https://github.com/Stevenjxie/cretas/pull/1637)；exact merged/deployed main `da476ecdc7a946b1d6e144d8ed0f2e2566de25c2`。
- **部署状态**：`DEPLOYED`；生产版本 `v20260722_232844`，JAR MD5 `36a24eab818a2cc0ae0ffa2b900e030d`，active `blue/10010`；5/5 切流观察、systemd/端口、直连与网关健康通过。
- **回归状态**：`READY_FOR_SAME_RECORD_RESUME`；部署后 query-only 再确认计划仍为 0 行、订单仍为 `FINANCE_APPROVED`。测试 Chat 仅从同一 `SO-20260722-0002` / item 728 继续，不得重建订单。

### BUG-F006-PROD-PLAN-ACTION-REGRESSION-001 — 7 月 20 日操作区重构误删生产动作

- **发现阶段/时间/页面/步骤**：F006 生产计划列表人工回归，2026-07-23；操作列只剩“查看详情 / 生产单据 / 追溯与核算”，未完成计划找不到核对结单、存货生产小结、逐道录入等动作，成品出厂核算又被无解释置灰。
- **证据路径**：`D:/Temp/codex-clipboard-ef2d80fd-f73f-4ecb-94a3-5e37ac13ced2.png`、`D:/Temp/codex-clipboard-50a60499-8599-447f-9d94-cc7b265b845d.png`、`D:/Temp/codex-clipboard-8b75a4ec-867c-4292-b1cc-5169695f13d8.png`。
- **期望/实际/业务影响**：信息架构收拢的正确目标是减少平铺按钮，并让“已完成/已入库计划”只显示只读动作；它不等于删除未完成计划的状态驱动生产动作。实际重构把完成态规则套到整张表，普通计划无法从列表核对结单，存货生产无法小结，逐道录入/APP 报工/调拨/停产/取消也全部失去入口；核心生产闭环被 UI 阻断。
- **根因**：`bb1753001722b67e09a053a869efddf3bf473e55` 将旧操作列整体替换成三个“只读组”，但没有先建立 `plan status × source type × permission × responsibility` 动作矩阵。旧 handler、loading state、确认框和后端契约仍保留，模板入口却被全部删除。更严重的是 `productionPlanInformationArchitecture.spec.ts` 明确断言“只能有三个只读组”且断言不得出现“核对结单/小结/确认入库/取消”，把错误实现固化成测试真值；复盘中“只收拢入口，不删除功能”的结论因此与真实代码不符。
- **修复**：保留“查看详情 / 生产单据 / 追溯与核算”语义分组；仅对有写权限且状态为 `PENDING/IN_PROGRESS` 的行增加“生产操作”分组。普通计划恢复核对结单，存货生产恢复生产小结，并恢复逐道录入、可启动时 APP 报工/调拨、存货生产撤销小结/停产和取消计划。完成态不显示生产写动作；仓储确认入库与清账不恢复到生产列表，继续遵守仓储职责边界。成品出厂核算在未结单时显示“结单后”并给出可理解提示，生产计划汇总明确标注“随时查看”。
- **同因扫描结论**：
  - `生产单据` 的工单/领料单/配料单/单文件 PDF 入口仍完整，属于正确收拢；
  - `追溯与核算` 的单据追溯和计划汇总 handler 完整，问题是核算禁用缺少解释；
  - 顶部导入导出与选中后批量打印按既定 IA 工作，没有同类业务入口丢失；
  - `handleWarehouseReceipt/handleTransitClearing` 目前仅剩无入口的前端残留，但按后续职责模型不应恢复到生产列表，列为独立 P2 清理候选，不在本次扩大删除范围；
  - 存货生产已形成阶段完工批次但计划仍 `IN_PROGRESS` 时的“计划级精确跳转到对应批次核算”缺少 plan→finished-batch 查询契约，当前仍先通过计划汇总查看，后续如要开放需补后端精确 identity，不能用“全厂最近批次”猜测。
- **修改文件**：`web-admin/src/views/production/plans/list.vue`、`productionPlanInformationArchitecture.spec.ts`、本复盘与 dispatch 台账。
- **测试**：目标 Vitest `productionPlanInformationArchitecture.spec.ts` 6/6 PASS；Web `build:check` PASS（`vue-tsc -b` + Vite production build，4457 modules）。测试覆盖普通计划、存货生产、完成态只读、仓储动作不回流、核算门禁说明与随时可看的计划汇总。
- **Commit/PR/main 状态**：实现 commit `ecf37f96469376854961ce9918989510965fd3ae`，分支 `codex/prodplan-action-regression-20260723`；PR/main 待本条收尾回填。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`CODE_FIXED_TARGET_TEST_PASS`；生产业务 mutation=0，未修改任何计划、报工、库存或 LIUSHANMEN 数据。
