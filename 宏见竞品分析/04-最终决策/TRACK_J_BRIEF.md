# TRACK J BRIEF — Sprint 2: P-FIN-1 采购订单财务审核 + 三价标红

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **Sprint 2 chat (基于 Sprint 1 已完成 ASAP) — NEW CHAT (新加)**
> **Brief 来源**: `MUST_COPY.md` §D P1-2 "采购订单财务审核 + 三价标红"
> **接收方**: Chat J (Sprint 2 worker)
> **派发方**: Chat 1 (Organizer)
> **派发日期**: 2026-05-15
> **预期完成**: ~2 工作日 (名义 3d, Claude 加速 ~1.7x)
> **PR 命名**: `[Sprint2-J] P-FIN-1 采购订单财务审核+三价标红`
> **STATUS 文件**: `宏见竞品分析/04-最终决策/STATUS/TRACK_J_STATUS.md`
>
> **本文件原则**: 完全 self-contained。你不需要任何额外 context 就能动手干活。

---

## §1 项目 Onboarding (新人入门)

### Cretas 是什么

**Cretas Food Traceability System (白垩纪食品溯源系统)**
- 后端: Java 21 + Spring Boot 3.2.12 + PostgreSQL + JPA (Hibernate 6), 端口 10010
- 前端: Expo 53+ + TypeScript + React Navigation 7+ (React Native), 端口 3010
- Web-Admin: Vue 3 + Element Plus + Pinia
- 项目状态: Phase 3 核心完成 (82-85%)

### 当前业务背景

**客户**: 六扇门 (F006) 卤制品工厂 — ASAP 1.5 月交付

**Sprint 2 在哪**:
- **Sprint 1 (Week 6-7) 已完成 ASAP** — 6 个 track 全 ship + main 已合并
- **Sprint 2 (Week 8-10) 现在启动** — 5 个已规划 chat (E/F/G/H/I) + **你 Chat J 新加**

**你为什么是 Sprint 2 新加的**:
- Sprint 1 PR #660 修了三价对比 bug (Sprint 1 Track C 的一部分)
- 客户在六扇门第三次会议 part 1 又提: "三家对比没有 ... 可能是一些数据的 bug"
- Sprint 1 修了 "三价计算 + 显示 bug" 但**还没加 "标红 + 财务审核"** — 这是 P1-2 的延续

**完整业务流第一节** (Sprint 2 拼出来):
研发样品 (F) → BOM → 销售下单 → 审批 → 缺料分流 (E) → 推荐采购 → **采购单创建时三价标红 + 财务审核 (你)** → 通过 → 钉钉通知

### 你是谁

**你 = Chat J = Sprint 2 worker (新加)**。Sprint 2 有 6 个并行 chat:
- Chat E: N31 销售→采购自动分流 (4d)
- Chat F: N48 研发样品→BOM→报价 (5d)
- Chat G: UX-A1 业务流程图导航 (10d)
- Chat H: UX-A2 行末操作下拉 (10d)
- Chat I: UX-A3 Sticky Footer 实时合计 (7d)
- **Chat J (你, 新加)**: P-FIN-1 采购财务审核+三价标红 (3d)

### 沟通方式

- **不要在本 chat 跟 organizer 战略讨论** — 战略已定, 你只执行
- **每日在 STATUS 文件追加 1 段** (格式见 §7)
- **碰到 blocker 立即在 STATUS 报**

---

## §2 任务范围与工时

### 单项目 (P-FIN-1)

| 项目 | N# 编号 | 工时名义 | 工时加速 | 优先级 | 客户感知 |
|---|---|---|---|---|---|
| **采购订单财务审核 + 三价标红** | P-FIN-1 (MUST_COPY.P1-2) | 3d | ~2d | P1 | 采购单创建时三价对比, 差异 > N% 自动标红, 财务审核 |

### 客户原话证据

**来源**: 六扇门第三次会议 part 1, MUST_COPY.md P1-2

> 客户原话: "三家对比没有 ... 可能是一些数据的 bug"

**业务**: 采购订单创建时, 系统三价对比, 差异 > N% 自动标红, 财务审核。

**Cretas 当前状态**:
- ✅ `MaterialPriceComparisonDTO.java:11-35` 三价 DTO 已有 (BOM/移动平均/当前)
- ✅ Sprint 1 PR #660 修了三价计算 + 刷新 bug
- ❌ **缺审核流程** (PurchaseOrderApprovalFlow)
- ❌ **缺标红规则** (差异阈值可配置)
- ❌ **缺前端审核 UI**

### 跟 Sprint 1 PR #660 的延续

Sprint 1 干了:
- 修三价计算 bug
- 修新建采购单后刷新 bug
- DTO 已存在含 `priceAlert: Boolean`

Sprint 2 你要干 (本项目):
- 加 PurchaseOrderApprovalFlow 实体 + 状态机
- 加 ApprovalRule (差异阈值可配置, 默认 10%)
- 后端: 创建采购单时计算三价标红, 触发 APPROVAL_PENDING 状态
- 前端: PurchaseOrderApprovalScreen (财务审核) + 三价高亮
- AIChat: "审一下采购单 PO-001" 调 Tool 触发审核

### v3 销售红线 (Sprint 2 解禁)

完成本项目后, 销售可以说:
- ✅ "采购单三价对比 + 超阈值自动标红"
- ✅ "财务审核工作流: 标红需财务点头"
- ✅ "AIChat 一句话审采购单"

### 工时不达标怎么办

- 名义 3d 上限。Claude 加速 ~1.7-2x → 实际预期 2 工作日
- 如果工时 >> 1.5 倍名义 (例如超过 5d), 立即 STATUS 报 organizer
- Organizer 会决定: 减 scope (跳过 AIChat Tool 留 follow-up) / 拉外援

---

## §3 文件 Ownership (防冲突)

### 你的 (Chat J 独占, 你可以随便改)

```
backend/java/cretas-api/src/main/java/com/cretas/aims/
├── entity/purchase/                                     ← 已存在目录, 你加新文件
│   ├── PurchaseOrderApprovalFlow.java                  ← NEW Entity (你)
│   └── PurchaseOrderApprovalRule.java                  ← NEW Entity (差异阈值规则)
├── service/purchase/                                    ← 已存在
│   ├── PurchaseOrderApprovalService.java               ← NEW 接口 (你)
│   └── impl/
│       └── PurchaseOrderApprovalServiceImpl.java       ← NEW 实现 (你)
├── controller/
│   └── PurchaseOrderApprovalController.java            ← NEW (你)
├── ai/tool/impl/purchase/                               ← 已存在
│   └── PurchaseOrderApproveTool.java                   ← NEW AI Tool (你)
└── repository/
    ├── PurchaseOrderApprovalFlowRepository.java        ← NEW
    └── PurchaseOrderApprovalRuleRepository.java        ← NEW

backend/java/cretas-api/src/main/resources/db/flyway/
├── V20260601_05__purchase_order_approval.sql           ← NEW Flyway
└── V20260601_06__approval_intent.sql                   ← NEW Flyway (ai_intent_config)

frontend/CretasFoodTrace/src/
├── screens/purchase/                                    ← 已存在, 你加新 screen
│   └── PurchaseOrderApprovalScreen.tsx                 ← NEW (你)
└── components/purchase/                                 ← 已存在
    └── PriceComparisonTable.tsx                        ← NEW (你, 三价高亮组件)

web-admin/src/views/purchase/                            ← 已存在
└── PurchaseOrderApprovalView.vue                       ← NEW (你)
```

### 修改 (改前确认其他 chat 没动)

```
backend/.../service/purchase/PurchaseOrderService.java   ← 创建时 hook 触发 approval flow
backend/.../service/purchase/impl/PurchaseOrderServiceImpl.java
backend/.../entity/purchase/PurchaseOrder.java          ← 加 approval_flow_id 字段 (FK)
```

### 共享只读 (改之前必须 ping organizer)

```
backend/.../entity/BaseEntity.java
backend/.../service/impl/IntentExecutorServiceImpl.java
backend/.../ai/tool/AbstractBusinessTool.java
backend/.../dto/inventory/MaterialPriceComparisonDTO.java  ← Sprint 1 Track C ship, 你直接 import 用
frontend/.../services/api/aiApiClient.ts
CLAUDE.md
.claude/rules/*
```

### 别 chat 的 (绝对不准碰)

- Chat E: `backend/.../service/shortage/`
- Chat F: `backend/.../entity/sample/`
- Chat G: `frontend/.../components/workflow/`, `web-admin/.../components/workflow/`
- Chat H: `frontend/.../components/list/RowActionBottomSheet.tsx`, `web-admin/.../components/list/RowActionMenu.vue`
- Chat I: `frontend/.../components/list/StickyFooterSummary.tsx`, `web-admin/.../components/list/TableFooter.vue`

### Sprint 1 已 ship 你强依赖

```
backend/.../dto/inventory/MaterialPriceComparisonDTO.java    ← Sprint 1 已有, 你 import 用
backend/.../service/inventory/MaterialPriceComparisonService.java ← Sprint 1 PR #660, 你调它算三价
backend/.../service/dingtalk/DingTalkBotService.java         ← Sprint 1 Track B1, 标红时通知财务
backend/.../service/security/RBACService.java                ← Sprint 1 Track C, 校验财务角色
```

---

## §4 Day-by-Day 执行计划

### Day 1 — 后端 ApprovalFlow + 三价标红规则

#### 任务

1. **起 worktree**:
   ```bash
   cd C:\Users\Steve\my-prototype-logistics
   git worktree add ../my-prototype-logistics-sprint2-track-j feature/sprint2-track-j-fin-approval
   cd ../my-prototype-logistics-sprint2-track-j
   ```

2. **Flyway V20260601_05__purchase_order_approval.sql**:
   ```sql
   -- 审核流水表
   CREATE TABLE purchase_order_approval_flows (
       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
       factory_id VARCHAR(36) NOT NULL,
       purchase_order_id VARCHAR(36) NOT NULL,
       status VARCHAR(30) NOT NULL DEFAULT 'PENDING_FINANCE',  -- PENDING_FINANCE/APPROVED/REJECTED/SKIPPED
       trigger_reason VARCHAR(50),                               -- 'PRICE_ALERT' / 'AMOUNT_THRESHOLD' / 'MANUAL'
       price_alert_count INT DEFAULT 0,                          -- 标红物料数
       total_amount DECIMAL(18, 2),
       approval_required BOOLEAN DEFAULT false,                  -- 是否需要财务审核
       reviewer_id BIGINT,
       reviewed_at TIMESTAMP,
       review_comment TEXT,
       review_decision VARCHAR(20),                              -- 'APPROVE'/'REJECT'/'REQUEST_CHANGE'
       created_at TIMESTAMP DEFAULT NOW(),
       updated_at TIMESTAMP DEFAULT NOW(),
       deleted_at TIMESTAMP NULL,
       UNIQUE (purchase_order_id)
   );
   CREATE INDEX idx_poaf_factory_status ON purchase_order_approval_flows(factory_id, status);

   -- 审核规则表 (可配置阈值)
   CREATE TABLE purchase_order_approval_rules (
       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
       factory_id VARCHAR(36) NOT NULL,
       rule_name VARCHAR(100) NOT NULL,
       price_variance_threshold DECIMAL(5, 2) DEFAULT 10.00,    -- 默认 10% 差异标红
       amount_threshold DECIMAL(18, 2),                          -- 总金额超 N 元也触发审核
       enabled BOOLEAN DEFAULT true,
       created_at TIMESTAMP DEFAULT NOW(),
       updated_at TIMESTAMP DEFAULT NOW(),
       deleted_at TIMESTAMP NULL,
       UNIQUE (factory_id, rule_name)
   );

   -- 默认规则
   INSERT INTO purchase_order_approval_rules (factory_id, rule_name, price_variance_threshold, amount_threshold, enabled)
   SELECT DISTINCT factory_id, '默认审核规则', 10.00, 100000.00, true
   FROM factories;

   -- 给 purchase_order 表加 approval_flow_id FK
   ALTER TABLE purchase_orders ADD COLUMN approval_flow_id UUID;
   ALTER TABLE purchase_orders ADD CONSTRAINT fk_po_approval_flow
     FOREIGN KEY (approval_flow_id) REFERENCES purchase_order_approval_flows(id);
   ```

3. **Entity** (继承 BaseEntity):
   ```java
   @Entity
   @Table(name = "purchase_order_approval_flows")
   @Getter @Setter
   public class PurchaseOrderApprovalFlow extends BaseEntity {
       @Id @GeneratedValue(generator = "UUID")
       private String id;

       @Column(name = "factory_id", nullable = false)
       private String factoryId;

       @Column(name = "purchase_order_id", nullable = false)
       private String purchaseOrderId;

       @Column(name = "status", nullable = false)
       private String status;  // PENDING_FINANCE/APPROVED/REJECTED/SKIPPED

       @Column(name = "trigger_reason")
       private String triggerReason;

       @Column(name = "price_alert_count")
       private Integer priceAlertCount;

       // ... 其他字段
   }
   ```

4. **PurchaseOrderApprovalServiceImpl** 核心逻辑:
   ```java
   @Service
   public class PurchaseOrderApprovalServiceImpl implements PurchaseOrderApprovalService {

       @Autowired private MaterialPriceComparisonService priceCompareService;  // Sprint 1
       @Autowired private PurchaseOrderApprovalRuleRepository ruleRepo;
       @Autowired private PurchaseOrderApprovalFlowRepository flowRepo;
       @Autowired private DingTalkBotService dingTalkBotService;

       @Override
       @Transactional
       public PurchaseOrderApprovalFlow evaluateAndCreate(String factoryId, PurchaseOrder po) {
           // 1. 取审核规则
           PurchaseOrderApprovalRule rule = ruleRepo.findByFactoryIdAndEnabled(factoryId, true)
               .orElseThrow(() -> new RuntimeException("无可用审核规则"));

           // 2. 算三价 (Sprint 1 提供)
           List<MaterialPriceComparisonDTO> priceComparisons = po.getItems().stream()
               .map(item -> priceCompareService.compare(factoryId, item.getMaterialTypeId(), item.getUnitPrice()))
               .toList();

           // 3. 应用标红规则 — 差异超阈值
           List<MaterialPriceComparisonDTO> redFlagged = priceComparisons.stream()
               .map(dto -> applyAlertRule(dto, rule.getPriceVarianceThreshold()))
               .filter(dto -> Boolean.TRUE.equals(dto.getPriceAlert()))
               .toList();

           // 4. 决定是否需要审核
           boolean approvalRequired = !redFlagged.isEmpty()
               || (rule.getAmountThreshold() != null && po.getTotalAmount().compareTo(rule.getAmountThreshold()) > 0);

           // 5. 创建审核流水
           PurchaseOrderApprovalFlow flow = new PurchaseOrderApprovalFlow();
           flow.setFactoryId(factoryId);
           flow.setPurchaseOrderId(po.getId());
           flow.setStatus(approvalRequired ? "PENDING_FINANCE" : "SKIPPED");
           flow.setTriggerReason(!redFlagged.isEmpty() ? "PRICE_ALERT" : "AMOUNT_THRESHOLD");
           flow.setPriceAlertCount(redFlagged.size());
           flow.setTotalAmount(po.getTotalAmount());
           flow.setApprovalRequired(approvalRequired);
           flow = flowRepo.save(flow);

           // 6. 钉钉通知财务 (Sprint 1 Track B1)
           if (approvalRequired) {
               String msg = String.format("采购单 %s 触发审核 (标红 %d 物料, 总金额 ¥%s)",
                   po.getCode(), redFlagged.size(), po.getTotalAmount());
               dingTalkBotService.sendNotification(factoryId, "采购审核待办", msg);
           }

           return flow;
       }

       private MaterialPriceComparisonDTO applyAlertRule(MaterialPriceComparisonDTO dto, BigDecimal threshold) {
           // 差异 = max(|当前 vs BOM|, |当前 vs 移动平均|)
           BigDecimal maxVariance = BigDecimal.ZERO;
           if (dto.getVarianceFromBom() != null) {
               maxVariance = maxVariance.max(dto.getVarianceFromBom().abs());
           }
           if (dto.getVarianceFromAvg() != null) {
               maxVariance = maxVariance.max(dto.getVarianceFromAvg().abs());
           }
           dto.setPriceAlert(maxVariance.compareTo(threshold) > 0);
           return dto;
       }

       @Override
       @Transactional
       public PurchaseOrderApprovalFlow approve(String factoryId, String flowId, String comment, Long reviewerId) {
           PurchaseOrderApprovalFlow flow = flowRepo.findByFactoryIdAndId(factoryId, flowId).orElseThrow(...);
           if (!"PENDING_FINANCE".equals(flow.getStatus())) {
               throw new IllegalStateException("只有 PENDING_FINANCE 可审核");
           }
           flow.setStatus("APPROVED");
           flow.setReviewerId(reviewerId);
           flow.setReviewedAt(LocalDateTime.now());
           flow.setReviewComment(comment);
           flow.setReviewDecision("APPROVE");
           return flowRepo.save(flow);
       }

       @Override
       @Transactional
       public PurchaseOrderApprovalFlow reject(String factoryId, String flowId, String comment, Long reviewerId) {
           // 同 approve, 但 status=REJECTED
       }
   }
   ```

5. **PurchaseOrderService hook** — 创建采购单时调用 ApprovalService:
   ```java
   @Service
   public class PurchaseOrderServiceImpl implements PurchaseOrderService {

       @Autowired private PurchaseOrderApprovalService approvalService;

       @Override
       @Transactional
       public PurchaseOrder create(String factoryId, PurchaseOrderCreateDTO dto, Long userId) {
           // 现有创建逻辑
           PurchaseOrder po = ... ;
           po = repository.save(po);

           // 加 hook: 触发审核 flow
           PurchaseOrderApprovalFlow flow = approvalService.evaluateAndCreate(factoryId, po);
           po.setApprovalFlowId(flow.getId());
           return repository.save(po);
       }
   }
   ```

6. **5 个 REST endpoint** (`PurchaseOrderApprovalController`):
   ```java
   @RestController
   @RequestMapping("/api/mobile/{factoryId}/purchase-order-approval")
   public class PurchaseOrderApprovalController {

       @GetMapping("/pending")
       public ApiResponse<Page<PurchaseOrderApprovalFlow>> listPending(...);

       @GetMapping("/{flowId}")
       public ApiResponse<PurchaseOrderApprovalFlowDetailDTO> getDetail(...);  // 含三价数据

       @PostMapping("/{flowId}/approve")
       public ApiResponse<PurchaseOrderApprovalFlow> approve(...);

       @PostMapping("/{flowId}/reject")
       public ApiResponse<PurchaseOrderApprovalFlow> reject(...);

       @GetMapping("/rules")
       public ApiResponse<List<PurchaseOrderApprovalRule>> listRules(...);

       @PutMapping("/rules/{ruleId}")
       public ApiResponse<PurchaseOrderApprovalRule> updateRule(...);
   }
   ```

7. **RBAC** (Sprint 1 Track C):
   - approve/reject 端点须财务角色 (`finance_manager` / `factory_super_admin`)
   - 用 `@PreAuthorize("hasRole('FINANCE')")` 或 RBACService 检查

**DoD Day 1**: 后端跑通 - curl create PO 时自动建 approval flow, curl list pending 看到, approve/reject 切状态.

---

### Day 2 — 前端审核 UI + 三价标红 + AIChat Tool

#### 任务

1. **RN PurchaseOrderApprovalScreen.tsx**:
   ```typescript
   const PurchaseOrderApprovalScreen = () => {
     const route = useRoute<RouteProp<PurchaseStackParamList, 'PurchaseOrderApproval'>>();
     const { flowId } = route.params;
     const { flow, isLoading } = useFlowDetail(flowId);

     return (
       <ScrollView>
         {/* 顶部审核摘要 */}
         <ApprovalSummaryCard
           code={flow.purchaseOrder.code}
           supplier={flow.purchaseOrder.supplierName}
           totalAmount={flow.totalAmount}
           triggerReason={flow.triggerReason}
           priceAlertCount={flow.priceAlertCount}
         />

         {/* 三价对比表 — 标红物料 */}
         <PriceComparisonTable
           items={flow.priceComparisons}
           highlightAlerts={true}  // priceAlert = true → 红色背景
         />

         {/* 审核操作 */}
         <ApprovalActions
           onApprove={async (comment) => {
             await approvalApiClient.approve(flowId, comment);
             Alert.alert('审核通过');
             navigation.goBack();
           }}
           onReject={async (comment) => {
             await approvalApiClient.reject(flowId, comment);
             Alert.alert('已退回');
             navigation.goBack();
           }}
         />
       </ScrollView>
     );
   };
   ```

2. **PriceComparisonTable.tsx 组件** (三价高亮):
   ```typescript
   const PriceComparisonTable: React.FC<{ items: MaterialPriceComparison[], highlightAlerts: boolean }> = ({ items, highlightAlerts }) => (
     <View>
       <View style={styles.header}>
         <Text>物料</Text><Text>BOM</Text><Text>移动均</Text><Text>当前</Text><Text>偏差</Text>
       </View>
       {items.map(item => (
         <View key={item.materialTypeId}
                style={[styles.row, highlightAlerts && item.priceAlert && styles.redRow]}>
           <Text>{item.materialName}</Text>
           <Text>¥{item.bomStandardPrice ?? '-'}</Text>
           <Text>¥{item.movingAvgPrice ?? '-'}</Text>
           <Text>¥{item.currentPrice ?? '-'}</Text>
           <Text style={item.priceAlert ? styles.redText : null}>
             {formatVariance(item.varianceFromBom, item.varianceFromAvg)}
           </Text>
         </View>
       ))}
     </View>
   );

   const styles = StyleSheet.create({
     redRow: { backgroundColor: '#FFE4E1' },        // 浅红背景
     redText: { color: '#C62828', fontWeight: 'bold' },
   });
   ```

3. **Vue PurchaseOrderApprovalView.vue** — 同样布局 (el-table 行 cell-class-name 加红色)

4. **AIChat Tool — PurchaseOrderApproveTool**:
   ```java
   @Slf4j
   @Component
   public class PurchaseOrderApproveTool extends AbstractBusinessTool {

       @Autowired @Lazy private PurchaseOrderApprovalService approvalService;

       @Override
       public String getToolName() { return "po_approve"; }

       @Override
       public String getDescription() {
           return "审核采购单 (财务角色). 调用方: AI 用户问 '审一下采购单 PO-001'";
       }

       @Override
       public boolean supportsPreview() { return true; }

       @Override
       protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params, Map<String, Object> context) {
           String poCode = getString(params, "purchaseOrderCode");
           // 返回 preview: "确认审核 PO-001? 标红 2 物料, 总金额 ¥58,750"
           return Map.of("status", "PREVIEW", ...);
       }

       @Override
       protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
           String poCode = getString(params, "purchaseOrderCode");
           String action = getString(params, "action");  // 'approve' / 'reject'
           String comment = getString(params, "comment");
           Long userId = (Long) context.get("userId");

           // 找 flow by purchaseOrderCode
           PurchaseOrderApprovalFlow flow = approvalService.findByPurchaseOrderCode(factoryId, poCode);
           if ("approve".equals(action)) {
               flow = approvalService.approve(factoryId, flow.getId(), comment, userId);
           } else {
               flow = approvalService.reject(factoryId, flow.getId(), comment, userId);
           }

           return Map.of(
               "status", "SUCCESS",
               "flowId", flow.getId(),
               "newStatus", flow.getStatus(),
               "displayHint", "approval-result-card"
           );
       }
   }
   ```

5. **绑定 intent** (Flyway V20260601_06):
   ```sql
   INSERT INTO ai_intent_config (...) VALUES
   (gen_random_uuid(), 'PO_APPROVE', '采购单审核', 'APPROVAL',
    'po_approve', '["审采购单","审一下","批准采购","通过采购","退回采购"]',
    true, 'HIGH');  -- HIGH 因为是审批动作
   ```

6. **测试**:
   - 单测 ApprovalServiceImpl
     - 三价超阈值 → priceAlert true
     - 总金额超阈值 → approvalRequired true
     - 三价正常 + 总金额小 → SKIPPED
   - E2E:
     - 销售单审批 → Chat E 推荐采购建议 → 财务 (你) 自动建 approval flow
     - 财务 RN 收到钉钉 → 打开审核页 → 看到三价标红 → approve

**DoD Day 2**: 前端审核 UI + AI Tool 完成, 端到端跑通.

---

### Day 3 — 完整集成 + Demo + PR

#### 任务

1. **Sprint 2 集成测试**:
   - Chat E (N31) 推荐采购建议 → 你的 approval flow 自动建
   - 你接 Chat H (UX-A2) BottomSheet — 财务在采购单列表行末点 "审核" → 跳你的 ApprovalScreen
   - 你接 Chat I (UX-A3) sticky footer — 采购列表显示 "标红 X 单 / 待审 Y 单"
   - 你接 Chat G (UX-A1) WorkflowBar — 财务首页 "采购待审 5 / 已审 12 / 拒绝 2"

2. **Demo 录** (1 分钟):
   1. 销售员审批销售单 (依赖 Chat E ship)
   2. Chat E 推荐采购建议 SO-XXX
   3. 采购员点确认采购 → 创建 PO-001
   4. **后端自动触发**: 三价对比 → 检测到牛肉 BOM 价 vs 当前价偏差 15% > 10% 阈值
   5. **创建 approval flow** status=PENDING_FINANCE
   6. **钉钉群通知财务**: "PO-001 待审 (标红 1 物料)"
   7. 财务登录 RN → 看到审核待办
   8. 打开 PurchaseOrderApprovalScreen → **三价表牛肉行红色背景**
   9. 财务输入 comment "市场价格变动确认, 通过" → 点 approve
   10. PO 状态变 APPROVED, 继续后续流程
   11. (可选) AIChat: "审一下 PO-001 通过, 备注市场价确认" → 同样效果

3. **推 PR**:
   ```bash
   git push -u origin feature/sprint2-track-j-fin-approval
   gh pr create --title "[Sprint2-J] P-FIN-1 采购订单财务审核+三价标红" --body "..."
   ```

   PR body 含:
   - 涉及文件清单 (Entity + Service + Controller + Tool + 2 Flyway + RN screen + Vue view + PriceComparisonTable 组件)
   - 测试方式 (单测 + curl + AI Tool + E2E demo)
   - 风险点 (Sprint 1 PR #660 三价 service 依赖 / 财务角色 RBAC / 钉钉 webhook 依赖 Track B1)
   - 跟 Sprint 1 PR #660 的延续关系

**DoD Day 3**: PR + demo + STATUS 3 段完整.

---

## §5 关键参考文档

| 路径 | 用途 |
|---|---|
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\MUST_COPY.md` §D P1-2 | 业务定义 + 客户原话 (3 人天来源) |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\01-客户档案\NUMBERING_MAP.md` | P-FIN-1 编号 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\TRACK_C_BRIEF.md` | Sprint 1 三价对比 PR #660 (你延续) |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/inventory/MaterialPriceComparisonDTO.java` | 已有 DTO (line 11-35), 你直接 import |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\ai-intent-tool-skill-architecture.md` HARD | Tool 注册 / @Lazy 防循环依赖 / WRITE preview |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\api-response-handling.md` | 统一响应 |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\database-entity-sync.md` | Entity / Flyway / BaseEntity |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\field-naming-convention.md` | camelCase/snake_case |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\typescript-type-safety.md` | 禁 `as any` |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\concurrent-edit-safety.md` HARD | 6 chat 并行 commit 安全 |

---

## §6 接口契约 (Interface Contracts)

### 后端 → 前端 API

**GET /api/mobile/{factoryId}/purchase-order-approval/pending**
```typescript
// Response
{
  success: true,
  data: {
    content: Array<PurchaseOrderApprovalFlow>,
    totalElements, totalPages
  }
}
```

**GET /api/mobile/{factoryId}/purchase-order-approval/{flowId}**
```typescript
// Response (Detail with priceComparisons)
{
  success: true,
  data: {
    id, factoryId, purchaseOrderId, status, triggerReason,
    priceAlertCount, totalAmount, approvalRequired,
    purchaseOrder: {
      id, code, supplierId, supplierName, items: [...], totalAmount
    },
    priceComparisons: Array<MaterialPriceComparisonDTO>,  // 标红的物料 priceAlert=true
    reviewer, reviewedAt, reviewComment, reviewDecision
  }
}
```

**POST /api/mobile/{factoryId}/purchase-order-approval/{flowId}/approve**
```typescript
// Request
{ comment: string }
// Response
{ success: true, data: { ...flow, status: 'APPROVED' } }
```

**POST .../reject** — 同 approve, status=REJECTED

### AIChat Tool

```json
{
  "status": "SUCCESS",
  "flowId": "...",
  "newStatus": "APPROVED",
  "displayHint": "approval-result-card"
}
```

### Sprint 1 依赖

| Sprint 1 提供 | 你怎么用 |
|---|---|
| Track C `MaterialPriceComparisonDTO` (`dto/inventory/`) | 直接 import 用 |
| Track C `MaterialPriceComparisonService` | 调它算三价, 你应用阈值规则 |
| Track B1 `DingTalkBotService.sendNotification` | 标红时通知财务 |
| Track C `RBACService` | 审核端点财务角色 gate |

### Sprint 2 集成

| Chat | 接入点 |
|---|---|
| Chat E (N31) | 推荐采购 → 创建 PO → 你 evaluateAndCreate 自动 hook |
| Chat G (UX-A1) | 财务 WorkflowBar 节点显示 "采购待审 X / 已审 Y / 拒绝 Z" |
| Chat H (UX-A2) | 采购单列表行末 BottomSheet "审核" → 跳你的 ApprovalScreen |
| Chat I (UX-A3) | 采购单列表 sticky footer 显示 "标红 X 单 待审 Y 单" |

---

## §7 PR / Status Update 流程

### 每日 STATUS 更新

文件: `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\STATUS\TRACK_J_STATUS.md`

格式 (每天追加):
```markdown
## Day N (YYYY-MM-DD)
- ✅ 完成: X / Y / Z
- 🟡 进行中: A
- ❌ Blocker: B
- 明日计划: C / D
```

### PR 流程

一个大 PR `[Sprint2-J] P-FIN-1 采购订单财务审核+三价标红` Day 3 一次推.

### 并发安全 commit

```bash
git commit -m "feat: PurchaseOrderApprovalService 后端" -- backend/.../service/purchase/PurchaseOrderApprovalServiceImpl.java backend/.../entity/purchase/PurchaseOrderApprovalFlow.java
```

### Blocker 上报模板

```markdown
## Day N (YYYY-MM-DD)
- ❌ Blocker: Sprint 1 PR #660 三价对比刷新 bug 还有 edge case
- 影响: 三价数据不准, 标红规则可能误报
- 建议方案: A) 等 Sprint 1 PR #660 fix; B) 我加 defensive check 跳过缺数据物料
- 需要 organizer: 拍板 A/B
```

---

## §8 不要做 (Do Not Do)

### 严格禁止

1. **不要重写 MaterialPriceComparisonService** — Sprint 1 已 ship, 你只 import
2. **不要 hardcode 阈值 10%** — 用 PurchaseOrderApprovalRule 表配置
3. **不要改 ownership 外的文件** (§3)
4. **不要创建 IntentHandler** — Handler 架构已废弃 (`.claude/rules/ai-intent-tool-skill-architecture.md`)
5. **不要直接 @Autowired AIIntentService 到 Tool** — 用 `@Lazy`
6. **不要降级处理** (CLAUDE.md):
   - 三价 service 返回 null 时, 标红逻辑应该 fallback "无法判断" 状态, 不要假装通过
7. **不要用 `as any`** — TypeScript 严格
8. **不要并发改同一文件** — 用 git worktree, `git commit -- F1 F2`
9. **不要忘记 RBAC** — approve/reject 必须财务角色, 其他角色返 403

---

## §9 验收清单

### 功能验收

- [ ] **后端**: PurchaseOrderApprovalFlow + ApprovalRule 表创建
- [ ] **后端**: 创建采购单时自动 evaluateAndCreate 审核 flow
- [ ] **后端**: 三价偏差超阈值 (默认 10%) → priceAlert=true 标红
- [ ] **后端**: 总金额超阈值 (默认 10万) → 也触发审核
- [ ] **后端**: approve/reject endpoint 财务角色 RBAC gate
- [ ] **后端**: PurchaseOrderApproveTool 注册, intent PO_APPROVE 绑定
- [ ] **后端**: Tool supportsPreview = true (WRITE 操作)
- [ ] **前端 RN**: PurchaseOrderApprovalScreen 渲染三价表 + 标红
- [ ] **前端 RN**: PriceComparisonTable 组件 highlightAlerts 工作
- [ ] **前端 Vue**: PurchaseOrderApprovalView 等价实现
- [ ] **AI**: AIChat "审一下 PO-001 通过" 触发 preview + approve
- [ ] **钉钉**: 标红时通知财务群 (依赖 Track B1)
- [ ] **集成**: 跟 Chat E 推荐采购联动 (依赖 Chat E ship)

### 销售红线验收

- [ ] **红线**: "采购单三价对比 + 超阈值自动标红"
- [ ] **红线**: "财务审核工作流: 标红需财务点头"
- [ ] **红线**: "AIChat 一句话审采购单"

### 技术验收

- [ ] PR merged 到 main
- [ ] 无新增 `as any`
- [ ] 无新增 `catch (error: any)`
- [ ] Flyway migration 文件存在 (V20260601_05 + 06)
- [ ] 单元测试覆盖 PurchaseOrderApprovalServiceImpl
- [ ] E2E demo 视频录制

---

## §10 客户场景对照

### 客户期望

**六扇门 F006 (卤制品工厂)** 希望:
1. **三价对比可见** — 创建采购单时, 立刻看到 BOM 标准价 / 移动平均价 / 当前价
2. **差异标红** — 价格偏差超 10% 自动红色提醒
3. **财务审核** — 标红物料不能直接采购, 需财务点头
4. **AI 协助** — "审一下采购单 PO-001 通过, 备注市场价确认" 一句话搞定
5. **跟销售→采购联动** — Chat E 推荐采购 → 自动接入审核

### Cretas 的差异化卖点

**宏见 ERP 范式**: 采购单价格只能事后比对, 单据级缺三价数据; 财务审核是手动通知

**Cretas Sprint 2 完成后**:
- ✅ 创建采购单时三价立即对比 + 标红
- ✅ 财务审核自动化 (规则可配置, 默认 10% 阈值)
- ✅ 钉钉群通知 (依赖 Sprint 1 Track B1)
- ✅ AIChat 一句话审 (Tool + preview + execute)
- ✅ 跟 Chat E (N31) 推荐采购无缝衔接

### 跟其他 Chat 的串联

```
Chat E (N31) — 推荐采购建议 → 创建 PO → 你 evaluateAndCreate 触发审核
Chat F (N48) — 报价 BOM 数据底层 → 影响 BOM 标准价
Chat G (UX-A1) — 财务 WorkflowBar 节点显示 "采购待审 X"
Chat H (UX-A2) — 采购列表行末 BottomSheet "审核" → 跳你的 ApprovalScreen
Chat I (UX-A3) — 采购列表 sticky footer 显示 "标红 X 单"
```

完整业务流第一节: 研发 → BOM (F) → 销售下单 → 缺料分流 (E) → 推荐采购 → **三价标红 + 财务审核 (你)** → 通过 → 入库 → 钉钉通知

---

## 附录: 关键命令速查

### 启动开发环境

```powershell
# 后端 Java (10010)
cd C:\Users\Steve\my-prototype-logistics\backend\java\cretas-api
mvn spring-boot:run

# RN 前端 (3010)
cd C:\Users\Steve\my-prototype-logistics\frontend\CretasFoodTrace
npm start

# Web-Admin (Vue)
cd C:\Users\Steve\my-prototype-logistics\web-admin
npm run dev
```

### 验证 Flyway 跑过

```powershell
psql -h localhost -U postgres -d cretas_db -c "\d purchase_order_approval_flows"
psql -h localhost -U postgres -d cretas_db -c "SELECT * FROM purchase_order_approval_rules LIMIT 5;"
```

### Git Worktree

```powershell
cd C:\Users\Steve\my-prototype-logistics
git worktree add ../my-prototype-logistics-sprint2-track-j feature/sprint2-track-j-fin-approval
cd ../my-prototype-logistics-sprint2-track-j
```

### 安全 Commit

```powershell
git commit -m "feat: PurchaseOrderApprovalFlow 后端" -- backend/java/cretas-api/src/main/java/com/cretas/aims/entity/purchase/PurchaseOrderApprovalFlow.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/purchase/PurchaseOrderApprovalServiceImpl.java
```

---

**Brief 结束。Day 1 开始干活。第一件事: 创建 STATUS 文件 + 启动 worktree, 然后读 MaterialPriceComparisonDTO + Sprint 1 PR #660 摸清三价现状。**