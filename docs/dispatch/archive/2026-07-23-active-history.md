# Dispatch 完成归档 — 2026-07-23

## BUG-F006-PROD-PLAN-ACTION-REGRESSION-001 — `merged`

- **Base SHA**：`9ef4cde8a3e5e6859f3530c2bef5bd43b3f106af`
- **Owner**：`/root`
- **范围**：生产计划列表操作区、目标测试、F006 复盘。
- **结果**：恢复未完成普通计划的核对结单、存货生产小结及其余状态驱动生产操作；完成计划保持只读；核算入口补充结单门禁说明，计划汇总保持随时可达。
- **验证**：Vitest 6/6；`vue-tsc -b` + Vite production build（4457 modules）；`git diff --check`。
- **实现/合入**：实现 commit `88733170c51eb42f95f6e758e14c83122bccff05`；PR [#1654](https://github.com/Stevenjxie/cretas/pull/1654)；exact main `91040bc57b01bccd12ed9183d72bc55b720acef5`。
- **部署**：`NOT_DEPLOYED`。
- **安全**：生产业务写入 0；未修改计划、报工、库存或 LIUSHANMEN 数据。

## UX-F006-PROD-PLAN-ACTION-IA-002 — `merged`

- **Base SHA**：`16c4edfc3dbfc7a85167f1c754fd1f64e848f5d4`
- **Owner**：`/root`
- **范围**：生产计划列表行操作、统一档案与核算抽屉、目标测试与 F006 复盘。
- **结果**：计划编号承担详情入口；逐道录入/继续录入与核对结单/生产小结直接可见；生产单据、计划追溯、汇总和完工核算收敛至“档案与核算”；异常和低频写操作归入“更多”，完成态保持只读。
- **验证**：Vitest 7/7；`vue-tsc -b --pretty false`；Vite production `dist/index.html` 与 `assets/list-HhM0R-vi.js` 目标文案读回；`git diff --check`。
- **实现/合入**：实现 commit `6c162973ad06aca435fe5e6592dc70203b139d8f`；PR [#1662](https://github.com/Stevenjxie/cretas/pull/1662)；exact main `ddfe3ab226f0cd2bbb154ef6355454b8a4decdd6`。
- **部署**：`NOT_DEPLOYED`。
- **安全**：生产业务写入 0；未修改后端 API、计划、批次、报工、结算、库存或 LIUSHANMEN 数据。

## BUG-F006-L3-CREATE-POSTGRES-NULL-TYPE-001 — `merged`

- **Base SHA**：`aa5792f32`
- **Owner**：`/root`
- **范围**：MaterialCodeSegment Repository/Service、真实 JPA 执行测试与 dispatch 收尾。
- **根因**：父级内分类去重 JPQL 对可空 `parentCode` / `excludeId` 使用 `:parameter IS NULL`，Hibernate 在 PostgreSQL 上生成无类型空参数，创建共享 L3 时触发 SQLState `42P18`。
- **结果**：改用 Spring Data 派生 exists 查询；空父级由持久层生成 `parent_code IS NULL`，可选排除 ID 使用显式 Long 哨兵，保留父级内规范化去重和历史冲突门禁。
- **验证**：生产 F006 请求稳定复现追踪码 `87A2E84D` 且事务回滚；单次 release Maven 生命周期运行 `MaterialTaxonomyRepositoryQueryValidationTest,MaterialCodeSegmentServiceTest`，22/22 通过并生成可信 JAR manifest；`git diff --check` 通过。
- **实现/合入**：实现 commit `7288e9ceb`；PR [#1668](https://github.com/Stevenjxie/cretas/pull/1668)。
- **部署**：`NOT_DEPLOYED`，合入后从 exact `origin/main` 复用相同 Java backend tree 的可信制品发布。
- **安全**：失败复现没有创建分类；生产写入仅保留发布后的用户授权 F006 测试分类创建与回读。

## BUG-F006-R4-TRANSFER-OA-APPROVAL-001 — `merged`

- **Base SHA**：`6fb05d26634f015b965213421280fe7e11fc9e9b`
- **Owner**：`/root`
- **范围**：调拨提交/OA 领域适配、调拨列表与详情、个人 OA 待办/我发起的、目标测试与 F006 复盘。
- **结果**：草稿调拨显式提交后事务内启动唯一 `INVENTORY_TRANSFER` OA 实例；缺流程、节点、角色或独立审批人时保持草稿并明确失败；业务详情和 AI 工具不再提供本地审批/驳回，审批仅在个人 OA 执行并幂等回写调拨状态。
- **验证**：Java 13/13；Web 5/5；Web 正式构建 736 assets；`git diff --check`。
- **实现/合入**：实现 commit `c1c2248dec8d160db6ceaf90c320036d6ce0b53f`；PR [#1681](https://github.com/Stevenjxie/cretas/pull/1681)。
- **部署**：`NOT_DEPLOYED`。
- **安全**：生产调拨、库存及其他租户业务写入均为 0；本批不改变审批后的仓储发运/签收/确认状态机。

## BUG-F006-R4-WORKPROCESS-WORKFLOW-BOM-UX-001 — `merged`

- **Base SHA**：`e79d3f7dc3319605d103fe27167ce54d72bbcbff`
- **Owner**：`/root`
- **结果**：工序辅料支持移动平均价优先、采购参考价回退与权威刷新；固定 Workflow 产出基准从 OUTPUT/半成品节点解析；单位缺失/冲突前后端 fail-closed；Workflow 当前版本已发布启用且无待发布变更时禁止重复发布。
- **验证**：Java 49 tests；Web 8 files / 44 tests；`vue-tsc -b`；可信 Java/Web release manifests。
- **实现/合入**：commits `add8f20f1`、`455fed9c7`、`bb30c5db6`；PR [#1675](https://github.com/Stevenjxie/cretas/pull/1675)；exact main `4d2d3b5e8317b2585048bc01a6e3844ec211f1b4`。
- **部署**：2026-07-23 已部署生产；backend blue/10010、health pass；Web 四方 SHA-256 `6f668350b5534dab81627785992247f5dec65da0eb5b4d7a20ff84fff56d9150` 一致。
- **安全**：用户接手 UI 业务验收前 Codex 未保存辅料绑定；F006 业务写入 0，其他租户写入 0。

## BUG-F006-R4-OPENING-INVENTORY-PREVIEW-HINT-001 — `merged`

- **Base SHA**：`e79d3f7dc3319605d103fe27167ce54d72bbcbff`
- **Owner**：`/root/opening_preview_hint`
- **结果**：未完成“预览比对”时，期初导入确认按钮通过 hover/focus 明确提示禁用原因；影响预览的输入变化会使旧预览失效。
- **验证**：目标 Vitest 3/3；并入 Web 联合 8 files / 44 tests、`vue-tsc -b` 与可信 Web build。
- **实现/合入/部署**：commit `bb30c5db6`；PR [#1675](https://github.com/Stevenjxie/cretas/pull/1675)；exact main `4d2d3b5e8317b2585048bc01a6e3844ec211f1b4`；2026-07-23 已部署生产。
- **安全**：未执行预览、导入或任何库存 mutation。

## F006-R4-PRODUCTION-CLOSEOUT-RELEASE-001 — `review`

- **Base SHA**：`9a7c91c4c713a516a51a7ebc7b05ea2ab8344e5e`
- **Owner**：`/root`
- **结果**：生产计划仅允许选择成品；存货生产计划量改为选填；生产工单、汇总领料单和配料单在未物化执行单据时从 pinned Workflow/BOM 输出可审计参考内容；逐工序报工重排为投入、执行、产出结构；WIP 半成品批次使用 `product_type_id` 合法持久化并可供下一工序消费。
- **验证**：生产计划与打印目标测试、报工 UI 15 项、Clerk 28 项、真实 JPA Context 均已通过；最终 exact-main release gate 待本批统一执行。
- **实现**：集成 commits `dfd4cdd61`、`eaca1473d`、`0b3bcd8fb`。
- **部署**：`DEPLOY_AUTHORIZED_AWAITING_RELEASE`。
- **安全**：未创建生产计划、未重复扣料、未写入 F006 或其他租户业务数据。

## F006-R4-STOCKTAKE-OA-001 — `review`

- **Base SHA**：`233a92876264765c1484e9c979370fa439506232`
- **Owner**：`/root/opening_preview_hint`
- **结果**：盘点提交原子创建 `INVENTORY_ADJUSTMENT` OA；业务页移除直接审批/驳回；OA 审批回写领域状态并保留 `APPROVED → APPLIED` 应用步骤；审批预览只列真实差异，零差异明确库存影响为 0。
- **验证**：Java 编译、2 类目标测试、Web 6 项及构建通过。
- **实现**：集成 commit `69ae885ab`。
- **部署**：`DEPLOY_AUTHORIZED_AWAITING_RELEASE`。

## F006-R4-TRANSFER-LIFECYCLE-001 — `review`

- **Base SHA**：`233a92876264765c1484e9c979370fa439506232`
- **Owner**：`/root/workflow_publish_gate`
- **结果**：同厂仓间调拨收敛为“申请 → OA 批准 → 确认调拨”；旧发运/签收接口对同厂明确拒绝；库存生产计划复用唯一未完成调拨并允许调整草稿数量，计划正式完成时关闭关联未完成调拨及运行中 OA。
- **验证**：6 类 Java 目标测试共 23 项通过，含真实 Repository JPA Context 门禁；最终 Web 联合构建待本批执行。
- **实现**：集成 commit `a5ebe048d`。
- **部署**：`DEPLOY_AUTHORIZED_AWAITING_RELEASE`。

## F006-WIP-STOCKTAKE-DUAL-IDENTITY-20260723 — `merged`

- **Base SHA**：`a5ebe048dfa76aa4466582ea0fa1ca481db1e78c`
- **Owner**：`/root/wip_stocktake_identity`
- **结果**：盘点快照、详情、差异预览与应用校验同时支持原料 `materialTypeId` 和半成品/成品 `productTypeId`；WIP 返回真实产品名称、编码与批次单位，原料路径保持不变。
- **验证**：`FactoryStocktakeServiceImplTest`、`FactoryStocktakeM12ContractTest`、`StocktakeWorkflowIntegrationTest` 在联合目标测试中通过；最终真实 JPA Context 由 exact-main release gate 执行。
- **部署**：`DEPLOY_AUTHORIZED_AWAITING_RELEASE`。

## F006-OPTIONAL-PLAN-ROLLING-TRANSFER-20260723 — `merged`

- **Base SHA**：`a5ebe048dfa76aa4466582ea0fa1ca481db1e78c`
- **Owner**：`/root/optional_plan_transfer`
- **结果**：存货生产计划未填写计划产量时，按 1 个成品的 BOM 基准创建并复用唯一滚动调拨草稿；草稿明确要求 OA 提交前调整实际量；显式 0/负数继续 fail-closed。
- **验证**：`ProductionWorkflowOrchestratorGuardTest` 通过；结单关闭关联调拨的既有契约保持。
- **部署**：`DEPLOY_AUTHORIZED_AWAITING_RELEASE`。

## BUG-F006-WORKFLOW-REPUBLISH-VISUAL-GUARD-20260723 — `merged`

- **Base SHA**：`9e678185567121d1f5a84cf97b4b2cb42fa98ef6`
- **Owner**：`/root`
- **结果**：当前 Workflow 版本已发布并启用且没有待发布改动时，发布按钮显示“已发布并启用”、使用弱化完成态并保持禁用；存在新草稿时恢复“发布并启用”且可操作。
- **验证**：`ProductProcessWorkflowEditor.activation.spec.ts` 6/6 通过；`npm --prefix web-admin run build:check` 通过，Vite 生产构建转换 4462 个模块。
- **部署**：`NOT_DEPLOYED`。
