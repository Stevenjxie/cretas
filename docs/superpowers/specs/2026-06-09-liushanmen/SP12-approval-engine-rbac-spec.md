# SP12 · 通用审批流引擎接入 + RBAC 补全 + 单据打印模板 — 设计规范

> **Wave**: 4 (SP11/12 并行, 依赖 P0 轻量状态机已 ship)
> **Flyway 号段**: `V20260911_4x` (41–49 保留给本子项)
> **Blueprint 对齐**: `00-master-blueprint.md §3.4–3.6 + §4`
> **生成**: 2026-06-09 Sonnet in-harness

---

## 1. 目标

| # | 目标 |
|---|------|
| G1 | 把 5 个现有"点位审批"场景接入已有 `WorkflowEngineService` 引擎, 替代各服务内部的临时 approve 调用 |
| G2 | 补齐六扇门缺失角色 `cashier`(出纳) / `quality_controller`(品控) 到 `FactoryUserRole` 枚举 + DB 种子 |
| G3 | 完成 `WorkflowEngineService` 对 3 个新场景的 `DecisionType` / `moduleCode` 注册 |
| G4 | 新增 2 个打印模板: `production-work-order`(公单/生产工单) + `consolidated-material-requisition`(汇总配料单), 接入 `PrintController` |
| G5 | 对已有 `stock-movement`/`packing-list`/`financial-invoice` 打印端点: payload builder 替换 stub 为真实 Service 调用 |

## 2. 范围

### IN SCOPE (本 SP 做)
- `cancelProductionPlan` → 加审批门控 (`PRODUCTION_REVERSAL` moduleCode)
- 盘点调账 (`InventoryAdjustment`) → 删直接调账, 改走 `INVENTORY_ADJUSTMENT` workflow
- 仓库报损双轨 (`DisposalRecord` 工厂侧 + `WastageRecord` 餐饮侧) → 各走 `WASTAGE` / `MATERIAL_DISPOSAL` workflow
- 付款申请 (`PaymentRequest`) → 新建实体 + 走 `PAYMENT` workflow (替代钉钉)
- `FactoryUserRole` 枚举加 `cashier` / `quality_controller` 两角色 + DB insert
- 角色权限矩阵 SQL 种子: 六扇门实际角色 → 各 module permission mapping
- PrintController 新增 `production-work-order` / `consolidated-material-requisition` 端点
- PrintController 已有 3 个 Sprint6 端点 payload builder stub → 真实 Service 调用

### OUT OF SCOPE (本 SP 不做)
- 不新建工作流引擎 (已有 `WorkflowEngineService` 满足)
- 不修改工作流 designer UI (已有 `workflow-designer/index.vue`)
- 不接外部财务 API (决策4 已定: 仅导表)
- 不做 HR 审批 (LEAVE / OVERTIME / HIRE — 已有 DecisionType, 非六扇门 P0 需求)
- 不做餐饮 RBAC 角色 (已够, 本 SP 只补工厂制造侧缺失角色)

---

## 3. 现状复用 (grep 验证)

| 已有资产 | 文件 | 状态 |
|---------|------|------|
| `WorkflowEngineService` + `WorkflowEngineServiceImpl` | `service/workflow/impl/` | ✅ 完整, 支持 SpEL/Redis/PG |
| `DecisionTypeMetadataRegistry` (32 DecisionType, 含 INVENTORY_ADJUSTMENT_APPROVAL/WASTAGE_APPROVAL/PAYMENT_APPROVAL) | `service/workflow/DecisionTypeMetadataRegistry.java` | ✅ 已注册, 仅需加 `PRODUCTION_REVERSAL_APPROVAL` |
| `ApprovalWorkflowInstance` / `ApprovalHistory` / `ApprovalChainConfig` | `entity/workflow/` | ✅ 无需新表 |
| `WorkflowEngineFacade` | `service/rules/integration/` | ✅ 直接用 |
| `PrintController` (8 端点: 5 原版 + 3 Sprint6) | `controller/PrintController.java` | ✅ 扩两个新端点 |
| `PriceMaskResolver` / `@PriceSensitive` | `security/PriceMaskResolver.java` | ✅ 复用已有脱敏框架 |
| `FactoryUserRole` (28 角色) | `entity/enums/FactoryUserRole.java` | ⚠️ 缺 cashier/quality_controller |
| `DisposalRecord` / `DisposalController` | `controller/DisposalController.java` | ⚠️ approveDisposal 直接改状态, 需接 workflow |
| `cancelProductionPlan` | `service/impl/ProductionPlanServiceImpl.java:960` | ⚠️ 无审批门控 |

---

## 4. 数据模型增量

### 4.1 新实体: `PaymentRequest` (付款申请单)

> 对标蓝图 §3.6 "付款申请替代钉钉" 场景

```java
@Entity
@Table(name = "payment_requests")
public class PaymentRequest extends BaseEntity {
    private String factoryId;
    private String requestNo;          // PR-F006-yyyyMMdd-NNN
    private BigDecimal amount;
    private String currency;           // 默认 CNY
    private String payeeName;          // 收款方
    private String bankAccount;        // 收款账号
    private String purposeCode;        // MATERIAL_PURCHASE / WAGE / OPERATING_EXPENSE / OTHER
    private String purposeDescription;
    private String attachmentJson;     // OSS 附件 URL list
    private String status;             // DRAFT / PENDING_APPROVAL / APPROVED / REJECTED / PAID
    private Long submittedBy;
    private String workflowInstanceId; // 关联 ApprovalWorkflowInstance.id
    private LocalDate dueDate;
    private String linkedPurchaseOrderId; // 可选: 关联采购单
}
```

**Flyway**: `V20260911_41__payment_request.sql`

### 4.2 新 DecisionType: `PRODUCTION_REVERSAL_APPROVAL`

加入 `ApprovalChainConfig.DecisionType` 枚举 + `DecisionTypeMetadataRegistry` 注册:

```java
// ApprovalChainConfig.DecisionType 增加:
PRODUCTION_REVERSAL_APPROVAL,  // 生产计划撤回/反冲审批

// DecisionTypeMetadataRegistry.init() 增加:
DecisionTypeMetadata.builder()
    .decisionType(DecisionType.PRODUCTION_REVERSAL_APPROVAL)
    .displayName("生产计划撤回审批")
    .category(Category.PRODUCTION)
    .defaultApproverRoles(List.of("workshop_supervisor", "dispatcher"))
    .moduleCode("PRODUCTION_REVERSAL")
    .build()
```

**Flyway**: `V20260911_42__production_reversal_decision_type_seed.sql`
(INSERT INTO `ai_approval_config` 种子数据, 如有配置表)

### 4.3 新 FactoryUserRole 角色

```java
// 加入 FactoryUserRole 枚举
cashier("出纳", "负责付款操作、银行对账", 15, "finance"),
quality_controller("品控", "负责质量标准制定、特批、处置审核", 15, "quality"),
```

**Flyway**: `V20260911_43__rbac_new_roles_seed.sql`

```sql
-- 角色-权限映射 (factory_role_permissions 表, 如已有)
-- cashier: finance:read_write, procurement:read
-- quality_controller: quality:read_write, production:read, warehouse:read
```

### 4.4 RBAC 权限矩阵种子 (六扇门定制)

**Flyway**: `V20260911_44__liushanmen_rbac_matrix.sql`

六扇门实际使用角色与权限的映射 (INSERT INTO 对应权限配置表):

| 角色 | 核心权限 |
|-----|---------|
| `workshop_supervisor` | production:read_write, warehouse:read, quality:read |
| `warehouse_worker` | warehouse:read_write (无 price:view) |
| `quality_inspector` | quality:read_write, production:read |
| `quality_controller` (新) | quality:read_write, production:read, warehouse:read |
| `cashier` (新) | finance:read_write, procurement:read |
| `operator` | production:read (仅报工), warehouse:read (仅领料) |

> **🔒 红线**: `warehouse_worker` 禁止拥有 `warehouse:adjust` (盘点调账) 权限 — 必须通过 workflow 审批后由 `warehouse_manager` 或 `finance_manager` 确认才能调账。见§7。

### 4.5 打印模板新端点数据 (无新表)

`PrintController` 新增两个端点的 payload builder 在服务层取数, 不新建表。

---

## 5. 组件与数据流

### 5.1 审批流接入流程 (以撤回生产计划为例)

```
前端 POST /api/mobile/{factoryId}/production-plans/{id}/request-cancel
    ↓
ProductionPlanController.requestCancel()
    ↓
ProductionPlanService.requestCancelWithApproval(factoryId, planId, reason, userId)
    ├─ 验 status 不是 COMPLETED/CANCELLED
    ├─ plan.setStatus(PENDING_APPROVAL)  ← 新状态
    ├─ plan.setCancelReason(reason)
    ├─ workflowEngineService.startWorkflow(factoryId, "PRODUCTION_REVERSAL",
    │       planId, context{amount=?, reason=reason}, userId)
    └─ save plan
    
审批人 PUT /api/mobile/{factoryId}/workflow/instances/{instanceId}/transition
    ↓
WorkflowInstanceController.transition(action=APPROVE)
    ↓
WorkflowEngineService.transitionNode(...)
    ↓  (workflow 终态 APPROVED)
WorkflowEngineServiceImpl onApproved callback
    ↓
ProductionPlanService.executeCancelApproved(planId)
    ├─ plan.setStatus(CANCELLED)
    └─ 级联关闭工序任务 (现有 cancelProductionPlan 逻辑)
```

### 5.2 盘点调账审批流

```
仓管员 POST /warehousing/inventory-checks/{checkId}/submit
    ↓
InventoryCheckService.submitForApproval(checkId, userId)
    ├─ check.setStatus(PENDING_APPROVAL)
    └─ workflowEngineService.startWorkflow(factoryId, "INVENTORY_ADJUSTMENT",
           checkId, context{variance=totalVariance}, userId)
    
审批人 APPROVE → workflowEngineService.transitionNode(APPROVE)
    ↓
InventoryCheckService.executeAdjustment(checkId)   ← 原直接调账逻辑移到此
```

### 5.3 付款申请流程

```
前端 POST /api/mobile/{factoryId}/payment-requests
    ↓
PaymentRequestController.create(request)
    ↓
PaymentRequestService.create(...)
    ├─ 保存 PaymentRequest (status=DRAFT)
    └─ 若 submitImmediately=true:
        └─ workflowEngineService.startWorkflow(factoryId, "PAYMENT",
               paymentRequestId, context{amount=amount, purpose=purpose}, userId)

出纳/财务主管 APPROVE → 
PaymentRequestService.markApproved(id) → status=APPROVED
出纳操作 PUT /payment-requests/{id}/mark-paid → status=PAID
```

### 5.4 打印模板新端点

```
GET /api/mobile/{factoryId}/print/production-work-order/{planId}
    ↓ ProductionPlanService.getById + 工序列表 + 汇总材料
    → Python /api/printing/production-work-order
    → PDF 流回
    
GET /api/mobile/{factoryId}/print/consolidated-material-requisition/{planId}
    ↓ MaterialRequisitionService.getByPlanId (跨批次汇总)
    → Python /api/printing/consolidated-material-requisition
    → PDF 流回
```

---

## 6. 端归属

| 功能 | 后端 (BE) | Web Admin | RN |
|-----|----------|-----------|-----|
| 付款申请 CRUD + 审批 | ✅ 新 Controller+Service | ✅ 付款申请列表+详情+审批按钮 | ❌ (P1) |
| 生产计划撤回申请 | ✅ 新 endpoint `/request-cancel` | ✅ "申请撤回"按钮替换直接取消 | ✅ 小组长撤回需用 RN |
| 盘点提交→审批→调账 | ✅ submitForApproval + executeAdjustment | ✅ 盘点页"提交审批"按钮 | ✅ 仓管员盘点提交用 RN |
| 报损双轨审批 | ✅ DisposalRecord 接 workflow | ✅ 报损审批页 | ❌ (P1) |
| cashier/quality_controller 角色 | ✅ 枚举+DB | ✅ 角色管理显示 | — |
| RBAC 矩阵种子 | ✅ Flyway | — | — |
| 公单打印 | ✅ 新端点 + Python 模板 | ✅ 生产计划页"打印公单"按钮 | ❌ |
| 汇总配料单打印 | ✅ 新端点 + Python 模板 | ✅ 领料管理页"打印汇总配料单" | ❌ |
| 已有打印 stub→真实 | ✅ payload builder 填实 | — | — |

---

## 7. 🔒 红线设计章 (照蓝图 §3 逐字落地)

> **执行者只到 PR, 不自部署 prod。回 main 由 Opus 终审 + 部署。**

### 红线 R1 — 仓库零自主权 (蓝图 §3.4)

**约束**: 仓库员 (`warehouse_worker`) **禁止**直接调整库存账面数量。盘点差异必须经 `INVENTORY_ADJUSTMENT` workflow APPROVE 后才能调账。

**实现要求**:
- `InventoryCheckServiceImpl.applyAdjustment()` 加前置检查: 调用方必须持有 `workflowInstanceId` 且实例状态 = APPROVED, 否则 403。
- `warehouse_worker` 角色 DB 权限矩阵无 `inventory:adjust` 权限。
- 任何直接调账的 REST 端点 (`PUT /inventory-adjustments/apply-direct`) 必须加 `@RequirePermission("warehouse_manager:inventory:adjust")` 门控。
- **违反处理**: executor 提 PR 时代码 review 必须 grep `applyAdjustment\|directAdjust` 确认无 bypass。

### 红线 R2 — 财务审批不可绕 (蓝图 §3.4)

**约束**: 付款申请 (`PaymentRequest`) 必须经审批工作流，出纳无法直接 mark-paid 未审批的单。

**实现要求**:
- `PaymentRequestService.markPaid(id)` 加前置检查: `status == APPROVED`，否则 409 "付款申请未通过审批，请先走审批流程"。
- `cashier` 角色无法创建和自批同一付款申请 (申请人≠审批人 — 由 workflow 配置的 approverRoles 保障)。
- **违反处理**: 5-agent 终审必须包含 bypass-hunt grep `markPaid\|setPaid` 确认无绕过。

### 红线 R3 — 生产撤回必审 (蓝图 §3.4)

**约束**: 生产计划 CANCEL 只能通过 workflow APPROVE 后触发，直接调 `cancelProductionPlan` 无论何种角色都不得绕过（工厂总监通过加速审批通道，不绕过）。

**实现要求**:
- `ProductionPlanController` 的原 `DELETE /{id}/cancel` 端点改为 `POST /{id}/request-cancel`，返回 workflow instanceId。
- 原 `cancelProductionPlan` 内部方法改名为 `executeCancelApproved`，只能被 workflow callback 调用，不对外暴露 REST。
- `factory_super_admin` 有自批权限 (SpEL condition `#context.initiatorRole == 'factory_super_admin'` → auto-approve)。
- **违反处理**: 终审 grep `cancelProductionPlan` 确认无直接对外暴露。

### 红线 R4 — 品控独立 (蓝图 §3.4)

**约束**: `quality_controller` 角色独立于 `quality_inspector`，有审批质检特批 (`QUALITY_EXCEPTION`) 权限，但不能修改 `quality_inspector` 提交的检验原始记录（防止篡改）。

**实现要求**:
- `quality_controller` 权限: `quality:read_write` + `quality:exception:approve`。
- `quality_inspector` 权限: `quality:submit` (无 `quality:approve`)。
- QualityInspectionRecord 的 `setChecked/setApproved` 方法上加 `@RequirePermission("quality:approve")`。

---

## 8. 错误处理 (Fool-Proof 4位一体)

所有审批 409/403/400 必须满足:
1. **后端 message 明确**: "生产计划撤回申请已存在 (PENDING_APPROVAL)，请勿重复提交 — 请前往[审批中心]查看" (含 actionHint 跳转 URL)
2. **前端 toast 原样展示**: catch (e) → ElMessage({ message: e.response.data.message, type:'error', duration:0, showClose:true })
3. **toast sticky (duration:0)**
4. **含 next action**: actionHint 字段指向审批中心或相关页面

付款申请创建: 幂等防重 (5min 内同 factoryId+amount+payee+purpose 检查 PENDING_APPROVAL 记录 → 409 + existingId)

---

## 9. 测试策略

### 单元测试
- `PaymentRequestServiceTest`: create/submit/approve/reject/markPaid (status machine, 未审批 markPaid → 409)
- `ProductionPlanServiceTest`: requestCancelWithApproval → PENDING_APPROVAL; executeCancelApproved → CANCELLED; 直接 cancelProductionPlan 不对外
- `WorkflowEngineServiceTest`: PRODUCTION_REVERSAL moduleCode lookup → PRODUCTION_REVERSAL_APPROVAL decisionType
- `FactoryUserRoleTest`: cashier.getLevel()==15, quality_controller.getPermissionPrefix()=="quality"

### 集成测试 (TDD 先写)
- 盘点 → submitForApproval → workflow APPROVE → executeAdjustment → inventory 更新 (事务完整性)
- warehouse_worker 无法直接调账 (403)
- 付款申请: 未审批 markPaid → 409; 已审批 markPaid → 200

### E2E 测试 (headed Playwright, web-admin)
- 生产计划页: "申请撤回"按钮 → 打开 dialog(含计划号+产品名+原因 dropdown) → 提交 → 审批中心出现待审条目
- 付款申请列表页: 新建 → 提交审批 → 审批人 APPROVE → 出纳 mark-paid
- 打印公单: 生产计划详情页 "打印公单" → PDF 含工序列表 + 汇总材料

---

## 10. 依赖

| 依赖 | 状态 | 说明 |
|-----|------|------|
| P0 轻量状态机 (采购/销售已接 WorkflowEngine) | ✅ 已 ship | SP12 沿用同一引擎 |
| `WorkflowEngineService` Phase 1 全接口 | ✅ 已 ship | `transitionNode`/`startWorkflow`/`cancel` 均可用 |
| Python `/api/printing/{type}` 渲染框架 | ✅ 已有 8 类型 | 新增 2 个 Python 模板 (独立工作) |
| `SemiFinishedInventory`/`MaterialRequisition` (公单取数用) | ✅ 已 ship | |

---

## 11. ⚠️ 跨子项依赖/风险

1. **SP6 (采购付款) 与 SP12 (付款申请实体) 共用 PaymentRequest 表**: SP6 如已建 purchase_payment_requests 表 → SP12 直接复用/扩展，不新建同名表。Opus 终审前必须 grep `payment_request` 确认无表名冲突。

2. **Flyway 跨 session 撞号**: V20260911_4x 段已被本 SP 预留，但若其他 SP 并发写同号段迁移 → 部署阻断。merge 前必须 `git ls-tree origin/main db/flyway | grep V20260911` 查重，发现冲突立即重编号。

3. **`DisposalRecord` 工厂侧报损 vs `WastageRecord` 餐饮侧报损 共存**: `DisposalRecord.approveDisposal()` 目前直接改状态，SP12 改为 workflow 后 `DisposalController.approveDisposal` REST 端点需同步废弃或保留为 admin bypass (标 `@Deprecated`)。若餐饮 SP11 同时改 WastageRecord 审批 → 需协调 moduleCode 区分 (WASTAGE vs MATERIAL_DISPOSAL)。

4. **RBAC 矩阵 Flyway seed 与生产数据冲突**: V20260911_44 的 INSERT 需加 `ON CONFLICT DO NOTHING`，防止重跑 migration 破坏已有手动配置的工厂权限。

5. **Python 打印模板并发**: 新增两个 Python 打印模板 (production-work-order / consolidated-material-requisition) 需同步在 Python 服务 `backend/python/smartbi/api/printing.py`(或相应路由) 增加渲染逻辑。若 Python 服务并发部署 → 需先部 Python、再部 Java，否则 Java 调 Python 得 404。
