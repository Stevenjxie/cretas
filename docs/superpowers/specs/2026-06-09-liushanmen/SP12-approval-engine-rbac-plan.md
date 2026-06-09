# SP12 · 实施计划 — 通用审批流引擎接入 + RBAC 补全 + 单据打印

> **Flyway 号段**: V20260911_41 — V20260911_49 (本 SP 保留)
> **并行约束**: 见下方 scope-lock 地图; SP11 餐饮报损并行须协调 WASTAGE vs MATERIAL_DISPOSAL moduleCode
> **Fleet 现状**: Codex/GPT 暂停。Composer out-of-harness (UI/样式)。CLI/E2E/构建 → Sonnet in-harness。
> **生成**: 2026-06-09 Sonnet in-harness

---

## Scope-Lock 地图

| 文件 / 目录 | 锁定 task | 注意 |
|-----------|---------|------|
| `entity/enums/FactoryUserRole.java` | T1 | 串行, 加枚举值 |
| `entity/config/ApprovalChainConfig.java` (DecisionType enum) | T2 | 串行 |
| `service/workflow/DecisionTypeMetadataRegistry.java` | T2 | 同 T2 |
| `service/impl/ProductionPlanServiceImpl.java` | T3 | 不可与 SP7 并行改同文件 |
| `controller/ProductionPlanController.java` | T3 | |
| `service/impl/InventoryCheckServiceImpl.java` (新建 or 已有) | T4 | |
| `controller/DisposalController.java` | T5 | |
| `entity/PaymentRequest.java` (新建) | T6 | |
| `service/PaymentRequestService.java` (新建) | T6 | |
| `controller/PaymentRequestController.java` (新建) | T6 | |
| `db/flyway/V20260911_4x__*.sql` | T1–T7 各自独占号 | 提 PR 前 `git ls-tree origin/main db/flyway | grep V20260911` 查重 |
| `controller/PrintController.java` | T8 | 仅 append 新方法, 不改已有 |
| `web-admin/src/views/production/plans/` | T9 | |
| `web-admin/src/views/finance/payment-requests/` (新建) | T10 | |

---

## 分阶段任务

### Phase A: 基础设施 (无业务依赖, 可立即开始)

---

#### T1 · 新增 RBAC 角色 + DB 种子

**目标**: `FactoryUserRole` 枚举加 `cashier` / `quality_controller`，Flyway 写入 DB 角色-权限配置

**Flyway**: `V20260911_43__rbac_new_roles_seed.sql`

**文件级改动**:
- `entity/enums/FactoryUserRole.java` — 在 `finance_manager` 后加 `cashier("出纳", "负责付款操作、银行对账", 15, "finance")`, 在 `quality_manager` 后加 `quality_controller("品控", "质量标准制定、特批", 15, "quality")`
- `db/flyway/V20260911_43__rbac_new_roles_seed.sql` — INSERT role_permission_mappings (加 `ON CONFLICT DO NOTHING`)

**先写测试** (`FactoryUserRoleTest`):
```java
@Test void cashier_role_has_finance_department() {
    assertEquals("finance", FactoryUserRole.cashier.getDepartment());
    assertEquals(15, FactoryUserRole.cashier.getLevel());
}
@Test void quality_controller_permission_prefix() {
    assertEquals("quality", FactoryUserRole.quality_controller.getPermissionPrefix());
}
```

**Flyway**: V20260911_43
**分发卡**: → 见下方卡 T1

---

#### T2 · 注册 PRODUCTION_REVERSAL_APPROVAL DecisionType

**目标**: 枚举加值 + Registry 注册

**文件级改动**:
- `entity/config/ApprovalChainConfig.java` — `DecisionType` enum 内 `PRODUCTION_PLAN_CHANGE` 后加 `PRODUCTION_REVERSAL_APPROVAL`
- `service/workflow/DecisionTypeMetadataRegistry.java` — `init()` 加 builder 条目 (moduleCode="PRODUCTION_REVERSAL")
- `db/flyway/V20260911_42__production_reversal_decision_type_seed.sql` — INSERT ai_approval_config (如有 workflow 配置种子表)

**先写测试**:
```java
@Test void production_reversal_module_code_lookup() {
    DecisionTypeMetadataRegistry reg = new DecisionTypeMetadataRegistry();
    reg.init();
    DecisionType dt = reg.lookupByModuleCode("PRODUCTION_REVERSAL");
    assertEquals(DecisionType.PRODUCTION_REVERSAL_APPROVAL, dt);
}
@Test void workflow_engine_has_active_check_returns_false_without_config() {
    // hasActiveWorkflow("F001", "PRODUCTION_REVERSAL") 无配置时返 false (不抛)
}
```

**Flyway**: V20260911_42
**分发卡**: → 见下方卡 T2

---

### Phase B: 审批接入 (依赖 T2 完成)

---

#### T3 · 生产计划撤回走审批流 🔒

**目标**: `cancelProductionPlan` → `requestCancelWithApproval` + `executeCancelApproved` callback

**文件级改动**:
- `service/ProductionPlanService.java` — 新增接口方法 `requestCancelWithApproval(...)` + `executeCancelApproved(...)`
- `service/impl/ProductionPlanServiceImpl.java`:
  - `cancelProductionPlan` 改名 `executeCancelApproved` (private, 只被 callback 调)
  - 新增 `requestCancelWithApproval`: 设 status=PENDING_APPROVAL + startWorkflow("PRODUCTION_REVERSAL")
- `controller/ProductionPlanController.java`:
  - 原 `DELETE /{id}/cancel` 改 `POST /{id}/request-cancel`
  - 新增 `POST /{id}/approve-cancel` (工厂总监 fast-path，内部走 workflow transitionNode APPROVE)
- `db/flyway/V20260911_44__production_plan_pending_approval_status.sql` — 若 status enum 需加 PENDING_APPROVAL 值

**先写测试**:
```java
@Test void requestCancel_sets_pending_approval_and_starts_workflow() {
    // mock workflowEngineService.startWorkflow → instance
    // verify plan.getStatus() == PENDING_APPROVAL
}
@Test void executeCancelApproved_sets_cancelled_and_cascades_tasks() {
    // 复用原有 cancelProductionPlan 测试逻辑
}
@Test void direct_cancel_endpoint_removed() {
    // 确认 ProductionPlanController 无 DELETE /{id}/cancel handler
}
```

**🔒 红线**: executor 只到 PR，Opus 终审验 bypass-hunt grep `cancelProductionPlan` 无对外暴露

**分发卡**: → 见下方卡 T3

---

#### T4 · 盘点提交→审批→调账 🔒

**目标**: 盘点差异提交走 `INVENTORY_ADJUSTMENT` workflow，调账只在 APPROVE 后执行

**文件级改动**:
- 找到 `InventoryCheckServiceImpl` (或 `WarehouseInventoryServiceImpl`) 中直接调账逻辑:
  - 重构为 `submitForApproval(checkId, userId)` + `executeAdjustment(checkId)`
- 加前置守卫: `executeAdjustment` 校验 workflowInstanceId 存在且 status=APPROVED
- `db/flyway/V20260911_45__inventory_check_workflow_fields.sql` — 给 inventory_checks 表加 `workflow_instance_id`, `submitted_by`, `submitted_at` 列

**先写测试**:
```java
@Test void submitForApproval_creates_workflow_instance() { ... }
@Test void executeAdjustment_fails_without_approved_workflow() {
    // status=PENDING → BusinessException 403
}
@Test void warehouse_worker_cannot_call_executeAdjustment_directly() { ... }
```

**🔒 红线**: 见 spec §7 R1 — warehouse_worker 无 inventory:adjust 权限

**分发卡**: → 见下方卡 T4

---

#### T5 · 报损双轨接 workflow

**目标**: `DisposalController.approveDisposal()` 改为通过 `MATERIAL_DISPOSAL` workflow

**文件级改动**:
- `DisposalController.java` 原 `PUT /{id}/approve` 改为 `POST /{id}/request-disposal` (启动 workflow)
- `DisposalRecordService.approveDisposal()` 改为 `executeDisposalApproved()` (仅 callback 可调)
- `db/flyway/V20260911_46__disposal_record_workflow_fields.sql` — 加 `workflow_instance_id`, `submitted_at`

**先写测试**:
```java
@Test void requestDisposal_starts_material_disposal_workflow() { ... }
@Test void approveDisposal_direct_endpoint_returns_409_if_no_workflow() { ... }
```

**分发卡**: → 见下方卡 T5

---

#### T6 · 付款申请实体 + 审批流 🔒

**目标**: 新实体 PaymentRequest + CRUD + 走 `PAYMENT` workflow

**Flyway**: `V20260911_41__payment_request.sql`

**文件级改动** (全新文件):
- `entity/PaymentRequest.java`
- `repository/PaymentRequestRepository.java`
- `dto/payment/CreatePaymentRequestRequest.java`
- `dto/payment/PaymentRequestDTO.java`
- `service/PaymentRequestService.java` (interface)
- `service/impl/PaymentRequestServiceImpl.java`
- `controller/PaymentRequestController.java`

`PaymentRequestServiceImpl.markPaid()` 前置检查: status≠APPROVED → 409 "付款申请未通过审批"

幂等防重: `create()` 内检查 5min 窗口同 factoryId+amount+payee+purpose → 409 + existingId

**先写测试**:
```java
@Test void create_payment_request_and_submit_for_approval() { ... }
@Test void markPaid_without_approval_throws_409() { ... }
@Test void duplicate_create_within_5min_returns_409_with_existing_id() { ... }
```

**🔒 红线**: 见 spec §7 R2 — 出纳不能绕过审批直接 mark-paid

**分发卡**: → 见下方卡 T6

---

### Phase C: 打印模板 (可与 Phase B 并行)

---

#### T7 · Python 新增打印模板 (production-work-order + consolidated-material-requisition)

**目标**: Python 服务加两个 PDF 渲染路由

**文件级改动** (`backend/python/`):
- `smartbi/api/printing.py` (或同级) 加 `/api/printing/production-work-order` + `/api/printing/consolidated-material-requisition` POST handler
- 各自用 ReportLab/WeasyPrint 渲染 (对齐已有 5 模板风格)

**先写测试** (Python pytest):
```python
def test_production_work_order_renders_pdf(client):
    r = client.post("/api/printing/production-work-order", json={
        "factoryName": "测试工厂", "planNumber": "PP-001",
        "productName": "白卤猪舌", "processes": [...]
    })
    assert r.status_code == 200
    assert r.headers["content-type"] == "application/pdf"
```

**分发卡**: → 见下方卡 T7

---

#### T8 · Java PrintController 新增两端点 + stub 替换

**目标**: 加 `production-work-order` / `consolidated-material-requisition` GET 端点，并把 3 个 Sprint6 stub builder 替换为真实 Service 调用

**文件级改动**:
- `controller/PrintController.java` — append 2 新 GET endpoint + 2 payload builder (调 ProductionPlanService + MaterialRequisitionService)
- `controller/PrintController.java` — `buildStockMovementPayload` / `buildFinancialInvoicePayload` / `buildPackingListPayload` 替换 stub → 真实 Service 调用

**先写测试**:
```java
@Test void printProductionWorkOrder_returns_pdf_bytes() { ... }
@Test void printConsolidatedMaterialRequisition_rbac_gates_warehouse_read() { ... }
```

**分发卡**: → 见下方卡 T8

---

### Phase D: Web Admin UI

---

#### T9 · 生产计划页"申请撤回" + 付款申请列表页

**目标**: Web admin UI 支持审批流入口

**文件级改动**:
- `web-admin/src/views/production/plans/index.vue` — "直接取消"按钮改"申请撤回" dialog (含计划号+产品名 context + 原因 el-select dropdown, Fool-Proof Rule 2+3)
- `web-admin/src/views/finance/payment-requests/index.vue` (新建) — 付款申请列表 + 新建 dialog + 审批按钮

**先写测试** (Playwright headed):
```
test('申请撤回-dialog-shows-context-and-dropdown', async ({ page }) => {
  // 点"申请撤回" → dialog 含计划号/产品名 / 原因 dropdown
})
```

**分发卡**: → 见下方卡 T9

---

## 分发卡

---

### 卡 T1 → Sonnet in-harness

**目标**: 加 `cashier` / `quality_controller` 到 FactoryUserRole 枚举 + Flyway DB 种子
**worktree**: `git worktree add -b feat/sp12-t1-rbac-roles ../cretas-sp12-t1 origin/main`
**允许改**: `entity/enums/FactoryUserRole.java`, `db/flyway/V20260911_43__rbac_new_roles_seed.sql`
**禁改**: 其他 RBAC 文件, 不动 PermissionServiceImpl
**先写测试**: `FactoryUserRoleTest` (2 测试用例如上)
**验收**: `mvn test -pl backend/java/cretas-api -Dtest=FactoryUserRoleTest` 绿 + Flyway `validate` 无错
**并行**: ✅ 与 T2-T8 独立
**交接**: PR off origin/main → `git diff origin/main...HEAD --stat` 只含上述 2 文件

---

### 卡 T2 → Sonnet in-harness

**目标**: 注册 `PRODUCTION_REVERSAL_APPROVAL` DecisionType + moduleCode="PRODUCTION_REVERSAL"
**worktree**: `git worktree add -b feat/sp12-t2-decisiontype ../cretas-sp12-t2 origin/main`
**允许改**: `entity/config/ApprovalChainConfig.java` (DecisionType enum 只加值), `service/workflow/DecisionTypeMetadataRegistry.java`
**禁改**: `WorkflowEngineServiceImpl.java` (MODULE_TO_DECISION 静态 map 不改, Registry 优先走)
**先写测试**: `DecisionTypeMetadataRegistryTest` 加 PRODUCTION_REVERSAL lookup 测试
**验收**: `mvn test -Dtest=DecisionTypeMetadataRegistryTest` 绿
**并行**: ✅ 与 T1/T7/T8 独立; T3 依赖 T2 完成
**交接**: PR off origin/main

---

### 卡 T3 → Sonnet in-harness 🔒

**目标**: 生产计划撤回改走审批流 (`requestCancelWithApproval` + `executeCancelApproved`)
**worktree**: `git worktree add -b feat/sp12-t3-cancel-approval ../cretas-sp12-t3 origin/main`
**允许改**: `service/ProductionPlanService.java`, `service/impl/ProductionPlanServiceImpl.java`, `controller/ProductionPlanController.java`, `db/flyway/V20260911_44__*`
**禁改**: `WorkflowEngineServiceImpl.java`, `ApprovalChainConfig.java`
**关键规则** (自包含):
- `cancelProductionPlan` 必须变成 private `executeCancelApproved`, 不对外暴露 REST endpoint
- `factory_super_admin` SpEL 自批: startWorkflow 的 contextJson 含 `initiatorRole`, workflow 节点设 SpEL `#context.initiatorRole == 'factory_super_admin'` auto-approve
- DTO 往返: 新 `CancelRequestDTO` 须在 create/set/convertToDTO 全 4 处同步
**先写测试**: `ProductionPlanServiceTest` 3 用例如 spec §9
**验收**: `mvn test -Dtest=ProductionPlanServiceTest` 绿 + grep `cancelProductionPlan` 确认无 public REST handler
**并行**: ❌ 依赖 T2 (需 PRODUCTION_REVERSAL moduleCode 注册)
**交接**: PR off origin/main → **🔒 回 Opus 终审**

---

### 卡 T4 → Sonnet in-harness 🔒

**目标**: 盘点提交→审批→调账，守卫 warehouse_worker 无直接调账权
**worktree**: `git worktree add -b feat/sp12-t4-stocktake-approval ../cretas-sp12-t4 origin/main`
**允许改**: 找到 InventoryCheck 相关 Service/Controller (用 `grep -rn cancelInventoryCheck\|applyAdjust\|stocktake` 定位), `db/flyway/V20260911_45__*`
**禁改**: RBAC 注解框架 (`RequirePermission.java`), `WorkflowEngineService` 接口
**关键规则**:
- `executeAdjustment` 须有 `if (instance.getStatus() != APPROVED) throw 403` 前置守卫
- fail-soft catch 不能吃掉 doomed transaction — 调账操作不用 REQUIRES_NEW，直接在 approve callback 的同一事务里 (调账是当前事务的主体)
**先写测试**: 3 用例如 spec §9
**验收**: `mvn test -Dtest=InventoryCheckServiceTest` 绿
**并行**: ✅ 与 T3 独立
**交接**: PR off origin/main → **🔒 回 Opus 终审**

---

### 卡 T5 → Sonnet in-harness

**目标**: 报损单改走 MATERIAL_DISPOSAL workflow
**worktree**: `git worktree add -b feat/sp12-t5-disposal-workflow ../cretas-sp12-t5 origin/main`
**允许改**: `controller/DisposalController.java`, `service/DisposalRecordService.java`, `service/impl/DisposalRecordService.java`, `db/flyway/V20260911_46__*`
**禁改**: `entity/DisposalRecord.java` 已有字段不移除; `WastageRecord` (餐饮) 不动
**先写测试**: 2 用例如 spec §9
**验收**: `mvn test -Dtest=DisposalRecordServiceTest` 绿
**并行**: ✅ 与 T3/T4 独立
**交接**: PR off origin/main

---

### 卡 T6 → Sonnet in-harness 🔒

**目标**: 全新 PaymentRequest 实体 + 审批流 + 出纳无法绕过
**worktree**: `git worktree add -b feat/sp12-t6-payment-request ../cretas-sp12-t6 origin/main`
**允许改**: 新建 `entity/PaymentRequest.java`, `repository/PaymentRequestRepository.java`, `dto/payment/*`, `service/PaymentRequestService.java`, `service/impl/PaymentRequestServiceImpl.java`, `controller/PaymentRequestController.java`, `db/flyway/V20260911_41__payment_request.sql`
**禁改**: `FactoryUserRole.java` (T1 负责), `ApprovalChainConfig.java` (T2 负责)
**关键规则**:
- `markPaid()` 前置: `if (req.getStatus() != APPROVED) throw BusinessException(409, "付款申请未通过审批，请先完成审批流程").withHint("/finance/payment-requests/" + id)`
- 幂等防重: 5min + business key (factoryId+amount+payeeName+purposeCode)
- DTO 往返 4 处: entity字段 + create set + update null-guard + convertToDTO
- `cashier` 角色不在 workflow approverRoles 中 (由 `finance_manager`/`factory_super_admin` 审批)
**先写测试**: 3 用例如 spec §9
**验收**: `mvn test -Dtest=PaymentRequestServiceTest` 绿 + Flyway validate
**并行**: ✅ 与 T3-T5 独立
**交接**: PR off origin/main → **🔒 回 Opus 终审**

---

### 卡 T7 → Sonnet in-harness (Python)

**目标**: Python 打印服务新增两个 PDF 渲染路由
**worktree**: `git worktree add -b feat/sp12-t7-python-templates ../cretas-sp12-t7 origin/main`
**允许改**: `backend/python/smartbi/api/` 下打印路由文件 (grep "printing" 定位), 新增模板 HTML/CSS
**禁改**: 已有 8 个打印路由的逻辑
**先写测试**: pytest 2 用例 (production-work-order + consolidated-material-requisition PDF 200)
**验收**: `cd backend/python && python -m pytest tests/test_printing.py -k "work_order or consolidated" -v` 绿
**并行**: ✅ 与所有 BE 任务独立
**交接**: PR off origin/main

---

### 卡 T8 → Sonnet in-harness

**目标**: Java PrintController 新增 2 端点 + Sprint6 stub builder → 真实 Service 调用
**worktree**: `git worktree add -b feat/sp12-t8-print-controller ../cretas-sp12-t8 origin/main`
**允许改**: `controller/PrintController.java` (append only), 可 import 新 Service (ProductionPlanService, MaterialRequisitionService)
**禁改**: 已有 8 端点的现有逻辑 (只补新方法)
**先写测试**: `PrintControllerTest` 2 新端点 mock Service → PDF bytes 返回
**验收**: `mvn test -Dtest=PrintControllerTest` 绿
**并行**: ❌ 依赖 T7 (Python 模板先就绪) 才能 E2E 测
**交接**: PR off origin/main

---

### 卡 T9 → Composer out-of-harness (UI)

**目标**: 生产计划页"申请撤回"dialog + 付款申请列表页

**Brief 自包含规则摘要**:
- Fool-Proof Rule 2: dialog header 必须显示 `计划号 + 产品名`
- Fool-Proof Rule 3: 撤回原因用 `el-select` (选项: 客户取消/原料不足/排程冲突/其他) + 选"其他"才显 textarea
- Fool-Proof Rule 4: "申请撤回"按钮点击前先 GET `/request-cancel` 检查是否已有 PENDING_APPROVAL → 若有 → 409 → dialog "已有待审批撤回申请 PR-xxx, 是否跳转查看?"
- 4位一体错误 toast: `ElMessage({ message, type:'error', duration:0, showClose:true })`
- 付款申请列表: 仿采购订单列表风格 (el-table + 状态 tag + 新建 dialog)

**worktree**: `git worktree add -b feat/sp12-t9-web-ui ../cretas-sp12-t9 origin/main`
**允许改**: `web-admin/src/views/production/plans/index.vue`, 新建 `web-admin/src/views/finance/payment-requests/index.vue` + `detail.vue`
**禁改**: `web-admin/src/views/system/workflow-designer/` (工作流设计器不动)
**npm 安装**: `cd web-admin && npm install --prefer-offline --legacy-peer-deps` (禁止 mklink /J)
**先写测试**: Playwright headed — `生产计划申请撤回-dialog-shows-plan-context.spec.ts`
**验收**:
1. `cd web-admin && npm run build` 无错
2. `cd web-admin && npx playwright test sp12` headed — dialog 含计划号/产品名/原因 dropdown ✓
**并行**: ✅ 与 T3-T8 独立 (UI 不依赖后端接口实装, mock API 测)
**交接**: PR off origin/main

---

## RBAC 权限矩阵种子详细

**Flyway**: `V20260911_44__liushanmen_rbac_matrix.sql`

```sql
-- 所有 INSERT 加 ON CONFLICT DO NOTHING
-- warehouse_worker: 无 inventory:adjust
INSERT INTO factory_role_permissions (factory_id, role, permission)
VALUES ('F006', 'warehouse_worker', 'warehouse:read_write')
ON CONFLICT DO NOTHING;
-- cashier: finance:read_write + procurement:read
INSERT INTO factory_role_permissions (factory_id, role, permission)
VALUES ('F006', 'cashier', 'finance:read_write')
ON CONFLICT DO NOTHING;
-- quality_controller: quality:read_write + production:read + warehouse:read
INSERT INTO factory_role_permissions (factory_id, role, permission)
VALUES ('F006', 'quality_controller', 'quality:read_write')
ON CONFLICT DO NOTHING;
```

---

## Flyway 号分配汇总

| Flyway 号 | 说明 | Task |
|----------|------|------|
| V20260911_41 | payment_requests 表 | T6 |
| V20260911_42 | production_reversal DecisionType 种子 | T2 |
| V20260911_43 | rbac_new_roles_seed (cashier/quality_controller) | T1 |
| V20260911_44 | liushanmen_rbac_matrix (六扇门权限矩阵种子) | T1 兜底 |
| V20260911_45 | inventory_check workflow 字段 | T4 |
| V20260911_46 | disposal_record workflow 字段 | T5 |
| V20260911_47 | (预留) | — |
| V20260911_48 | (预留) | — |
| V20260911_49 | (预留) | — |

**查重纪律**: 每个 task PR merge 前必运行:
```bash
git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway | grep V20260911 | sort
```
发现冲突立即重编号 (只改未 apply 的)。

---

## 测试命令汇总

```bash
# 单元测试 (各 task 独立运行)
mvn test -pl backend/java/cretas-api -Dtest=FactoryUserRoleTest,DecisionTypeMetadataRegistryTest,ProductionPlanServiceTest,InventoryCheckServiceTest,DisposalRecordServiceTest,PaymentRequestServiceTest,PrintControllerTest

# Python 打印测试
cd backend/python && python -m pytest tests/test_printing.py -v

# Web Admin 构建
cd web-admin && npm run build && npm run type-check

# Playwright E2E (headed)
PLAYWRIGHT_PORT=9222 PLAYWRIGHT_CHAT_ID=sp12 npx playwright test sp12 --headed
```

---

## 分发总览

| # | 任务 | 推荐模型 | effort | orchestration | 分支 | 🔒 |
|---|------|---------|--------|--------------|------|----|
| T1 | RBAC 角色枚举 + DB 种子 | Sonnet | high | inline | feat/sp12-t1-rbac-roles | |
| T2 | DecisionType 注册 | Sonnet | high | inline | feat/sp12-t2-decisiontype | |
| T3 | 生产撤回审批 | Sonnet | high | inline | feat/sp12-t3-cancel-approval | 🔒 |
| T4 | 盘点调账审批 | Sonnet | high | inline | feat/sp12-t4-stocktake-approval | 🔒 |
| T5 | 报损接 workflow | Sonnet | high | inline | feat/sp12-t5-disposal-workflow | |
| T6 | 付款申请实体+流 | Sonnet | high | inline | feat/sp12-t6-payment-request | 🔒 |
| T7 | Python 打印模板 | Sonnet | high | inline | feat/sp12-t7-python-templates | |
| T8 | Java PrintController 补全 | Sonnet | high | inline | feat/sp12-t8-print-controller | |
| T9 | Web Admin UI | Composer | default | inline | feat/sp12-t9-web-ui | |

**串行约束**: T3 依赖 T2 完成 (PRODUCTION_REVERSAL moduleCode); T8 E2E 依赖 T7 Python 模板就绪。其余可并行。
