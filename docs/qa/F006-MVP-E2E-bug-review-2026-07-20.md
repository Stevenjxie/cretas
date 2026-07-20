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
- **Commit/PR/main 状态**：revision/pin 核心 commit `9f5cb804f`，拓扑与 pinned-node 收口 commit `301fcb0cd`；`TARGET_TEST_PASSED_PENDING_MAIN`。
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
- **Commit/PR/main 状态**：revision 与 DAG 核心 `9f5cb804f`，拓扑/辅料作用域/门禁收口 `301fcb0cd`，Web fail-closed `e7beda5f8`；PR [#1545](https://github.com/Stevenjxie/cretas/pull/1545)，`TARGET_TEST_PASSED_PENDING_MAIN`。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：`FINAL_RELEASE_GATE_PASSED_PENDING_MAIN`；严格生产业务 mutation=0。

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
- **Commit/PR/main 状态**：审计零文件修改；修复 commits 随对应条目记录。
- **部署状态**：`NOT_DEPLOYED`。

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
- **Commit/PR/main 状态**：核心门禁/精确 pinned node `301fcb0cd`，Web 入口锁写 `e7beda5f8`；`TARGET_TEST_PASSED_PENDING_MAIN`。
- **部署状态**：`NOT_DEPLOYED`。
- **回归状态**：目标回归已纳入后端 139/139 与 Web 30/30；clean rebase 后 Java 最终单生命周期 221/221 与 Web release build 均已通过，待合入 main。

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
