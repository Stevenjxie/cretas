# SKU 单位、Workflow 与生产报工统一实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:test-driven-development` for each code task and `superpowers:verification-before-completion` before reporting completion.

**Goal:** 建立 SKU、BOM、Workflow、生产库、报工、仓储与销售共用的单位真值，消除 g/kg/盒/袋/箱漂移，并按已确认方案二完成数据迁移预览、F006 验收和安全发布。

**Architecture:** SKU 保存基础计量与包装换算，BOM 保存配料和工序归属，Workflow 只保存拓扑及 SKU/BOM 版本引用，生产报工读取快照并用生产库流水完成批次分摊。所有重量在领域层统一为 `BigDecimal kg`，数量型成品以 SKU 基本单位记账，展示字符串不参与计算。

**Tech Stack:** Java 21、Spring Boot 3.2、PostgreSQL/Flyway、Vue 3/TypeScript/Vitest、React Native/TypeScript、Maven/JUnit、Playwright。

## Global Constraints

- 以 `docs/superpowers/specs/2026-07-15-sku-unit-workflow-reporting-design.md` 为唯一设计规格。
- F006 是本次唯一可写验收租户；LIUSHANMEN 只读，除非用户再次明确授权。
- 10010/10020 是交替蓝绿槽位；发布前读取 nginx upstream，绝不假设固定活动槽位。
- 已完成业务单据保留单位与版本快照，不随主数据更新重算。
- 正式库存变化只允许通过既有流水、小结、撤销小结、退料或盘点产生。
- 每个任务先写失败测试，再做最小实现；全部代码完成后只执行一次集中构建与最终验收。

---

### Task 1: 统一 SKU 单位与包装规格领域契约

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ProductType.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/producttype/ProductTypeDTO.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductPackagingSpecServiceImpl.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductTypeController.java`
- Modify: `web-admin/src/views/system/products/index.vue`
- Modify: `web-admin/src/utils/productSpecification.ts`
- Test: existing backend packaging tests and `web-admin/src/utils/__tests__/productSpecification.spec.ts`

- [ ] Add tests for mass SKU versus fixed-pack SKU validation, semi-finished fixed `kg`, integer package factors, and canonical specification text.
- [ ] Make the RHS packaging unit read-only and inherited from SKU base unit; forbid `200g/克` and decimal count factors.
- [ ] Generate specifications such as `200g/袋 10袋/箱 2kg/箱`, trimming insignificant zeros and switching display to kg at 1000g.
- [ ] Lock base-unit changes after inventory/reporting/sales usage while keeping unused SKU editable.
- [ ] Keep existing multi-packaging API backward compatible and ensure inventory quantities remain in base units.

**Acceptance:** targeted Maven packaging tests and product specification Vitest pass.

### Task 2: 将产品管理收敛为统一 SKU 管理

**Files:**
- Modify: relevant `ProductType` controller/service/repository DTO files discovered by the contract map
- Modify: `web-admin/src/views/system/products/index.vue`
- Modify: related web-admin routes/menu labels only where necessary
- Test: new backend SKU-category/lifecycle tests and focused web-admin tests

- [ ] Display finished, customer-material, OEM, and semi-finished SKU in the same module; keep raw materials in the material dictionary.
- [ ] Add/confirm `SEMI_FINISHED` lifecycle: fixed base unit kg, no standard net weight or packaging specs.
- [ ] Make Workflow quick-create persist a formal semi-finished SKU that immediately appears in SKU management.
- [ ] For referenced SKU, replace hard delete/base-unit mutation with deactivate and explicit error messages.
- [ ] Expose historical semi-finished yield as read-only data, never as an editable SKU field.

**Acceptance:** API tests prove category visibility, lifecycle guards, and quick-create persistence.

### Task 3: 收敛 BOM 与 Workflow 的职责和单位

**Files:**
- Modify: `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue`
- Modify: `web-admin/src/views/system/product-processes/workflow/WorkflowProcessNode.vue`
- Modify: `web-admin/src/views/system/product-processes/workflow/types.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/workflowUnits.ts`
- Modify: `web-admin/src/views/system/product-processes/workflow/__tests__/workflowUnits.spec.ts`
- Modify: backend Workflow/BOM services identified by contract map
- Test: focused Workflow unit/model tests and backend BOM/Workflow tests

- [ ] Rewrite existing uncommitted Workflow changes to enforce raw/semi input and semi output in kg, and finished output in each SKU base unit.
- [ ] Remove manual unit selection, fixed ratio and English `box/case` conversion rows from Workflow UI and persisted configuration.
- [ ] Read raw/aux/package items and process binding from the selected BOM version; do not duplicate editable BOM ingredients in Workflow.
- [ ] Compute theoretical input/trim and overall theoretical yield read-only from BOM data, excluding packaging materials.
- [ ] On a new BOM version, create a pending-sync Workflow draft with a visible diff; never silently mutate a published Workflow.
- [ ] Snapshot SKU/BOM/Workflow versions and unit facts when a production plan is created.

**Acceptance:** Workflow tests prove unit inheritance, no conversion selector, BOM read-only inputs, and published-version isolation.

### Task 4: 生产库自动分摊、半成品与历史出成率

**Files:**
- Modify: backend production-store inventory, process reporting, semi-finished inventory, DTO and repository files identified by contract map
- Test: focused service integration tests

- [ ] Accept only total actual input per material from operators; allocate source batches in production-store transfer order inside a locked transaction.
- [ ] If available stock is insufficient, allow draft save but reject formal submit with required/available/shortage and the exact contact-warehouse message.
- [ ] Persist allocation details for later reversal and audit; leave unused production-store stock available for later reports or return flow.
- [ ] For semi-finished reporting, accept kg input/output, create a semi-finished batch on settlement, and make its remaining kg available downstream.
- [ ] Calculate historical semi-finished yield as weighted `sum(valid output kg) / sum(valid input kg)` by factory + semi SKU across all workflows, versions and valid historical batches.
- [ ] Exclude drafts and reversed/invalid settlements; do not average percentages.

**Acceptance:** integration tests cover FIFO-by-transfer allocation, concurrency protection, shortage draft gate, semi batch creation, downstream consumption and weighted all-history yield.

### Task 5: 重构生产报工表与工时录入

**Files:**
- Modify: `web-admin/src/views/production/components/processSheet/PROCESS_SHEET_CONFIG.ts`
- Modify: `web-admin/src/views/production/components/processSheet/ProcessDataTable.vue`
- Modify: `web-admin/src/views/production/components/processSheet/WorkHoursTable.vue`
- Modify: related `ProcessSheet.vue`, API types and focused tests

- [ ] Raw/semi rows show kg and accept actual input/output only; optional display toggle may show g without persisting it.
- [ ] Finished rows inherit SKU base unit and accept only actual production and sample quantities.
- [ ] Derive inbound = actual - sample and initial remaining = inbound; make both read-only.
- [ ] Derive product/inbound weights from SKU net weight, and sum multi-output weights; remove claim, used weight, manual net weight, manual finished weight, manual trim and packaging selection.
- [ ] Show theoretical BOM trim separately from actual mass difference when data is complete.
- [ ] Replace start + duration with start datetime + end datetime + people; derive duration, person-hours and cost, including cross-day and multiple segments.
- [ ] Keep operator messages concrete and prevent negative/non-integer count quantities.

**Acceptance:** component/unit tests cover the sheep-rib and fillet examples, multi-output totals, sample deduction, kg/g display, and cross-day work hours.

### Task 6: 仓储与销售多包装组合闭环

**Files:**
- Modify: existing packaging allocation backend and UI files already changed on this branch
- Test: existing `FgQuantityUnitConverterTest`, sales allocation tests, RN/web-admin focused tests

- [ ] Allow one transaction to combine multiple package specs and loose base-unit remainder.
- [ ] Convert and validate all rows into SKU base-unit totals while preserving package-detail snapshots.
- [ ] Require package choice only in warehouse/sales when ambiguity exists; keep production reporting independent from package choice.
- [ ] Keep stock ledger, availability and reversal quantities in base units.

**Acceptance:** tests cover two different case sizes for one SKU, mixed cases plus loose units, insufficient stock and reversal symmetry.

### Task 7: 扩展撤销小结并提供迁移预览

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/InterimSettleReversalServiceImpl.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/InterimSettleReversalRequestServiceImpl.java`
- Modify: related controller/DTO/tests
- Add: additive Flyway migration(s) and a read-only migration preview/report command or endpoint

- [ ] Extend the existing 24-hour request/approval reversal from safety-stock-only to normal, customer-material and OEM flows where all ledger rows are reversible.
- [ ] Block reversal after downstream consumption or when any required inverse ledger is missing; direct the user to stocktake for physical discrepancies or expired windows.
- [ ] Reverse raw/semi/finished inventory and restore report rows atomically.
- [ ] Build a preview that reports impacted SKU, inventory batches, quantities, per-kg prices, invariant amounts, Workflow/plans and ambiguous records before any migration write.
- [ ] Define g→kg conversion as quantity / 1000 and per-kg price × 1000 with amount invariant; keep completed historical snapshots unchanged.

**Acceptance:** reversal service tests cover all supported source types, downstream block and exact inverse ledger; preview is read-only and deterministic.

### Task 8: 集中验证、F006 验收与安全发布

**Files:**
- Modify only test fixtures/E2E specs or release notes required by discovered failures

- [ ] Run `git diff --check` and review only the final scoped diff for unintended files/secrets.
- [ ] Run all changed backend target tests, then one Maven package/test gate for `backend/java/cretas-api`.
- [ ] Run focused web-admin Vitest/typecheck/build once after all frontend edits.
- [ ] Run focused RN tests/typecheck only for changed sales/warehouse paths.
- [ ] Use isolated browser E2E on F006 for SKU editing, Workflow, production-store shortage draft, semi report, finished report, mixed packaging, reversal and stocktake boundaries.
- [ ] Run migration preview on F006 and prove it performs no writes; do not execute cross-tenant migration.
- [ ] Commit scoped files, push branch, request code review, address findings, and merge only after all gates pass.
- [ ] Before backend deploy, read `/www/server/panel/vhost/nginx/_upstream_cretas.conf`, deploy to the inactive 10010/10020 slot, verify health/business probes, then switch traffic and preserve rollback.

**Acceptance:** committed test/build/E2E evidence, F006 before/after invariants, active-slot proof, health probe and remote ancestry proof are all recorded.
