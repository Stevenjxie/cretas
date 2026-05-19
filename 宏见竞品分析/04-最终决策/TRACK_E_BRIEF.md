# TRACK E BRIEF — Sprint 2: N31 销售订单 → 采购自动分流

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **Sprint 2 chat (基于 Sprint 1 已完成 ASAP)**
> **Brief 来源**: `SPRINT_2_PLAN.md` §5.1 (Chat E — N31 4d 名义)
> **接收方**: Chat E (Sprint 2 worker)
> **派发方**: Chat 1 (Organizer)
> **派发日期**: 2026-05-15
> **预期完成**: ~2.5-3 工作日 (名义 4d, Claude 加速 ~1.7x)
> **PR 命名**: `[Sprint2-E] S-MRP-1 销售订单→采购自动分流`
> **STATUS 文件**: `宏见竞品分析/04-最终决策/STATUS/TRACK_E_STATUS.md`
>
> **本文件原则**: 完全 self-contained。你不需要任何额外 context 就能动手干活。

---

## §1 项目 Onboarding (新人入门)

### Cretas 是什么

**Cretas Food Traceability System (白垩纪食品溯源系统)**
- 后端: Java 21 + Spring Boot 3.2.12 + PostgreSQL + JPA (Hibernate 6), 端口 10010
- 前端: Expo 53+ + TypeScript + React Navigation 7+ (React Native), 端口 3010
- AI 服务: Python + FastAPI + LLM API, 端口 8083
- 项目状态: Phase 3 核心完成 (82-85%)

源码位置: `C:\Users\Steve\my-prototype-logistics\`
- Java 后端: `backend/java/cretas-api/`
- RN 前端: `frontend/CretasFoodTrace/`
- Python 服务: `backend/python/`

### 当前业务背景

**客户**: 六扇门 (F006) 卤制品工厂 — ASAP 1.5 月交付 P0 修复

**Sprint 2 在哪**:
- **Sprint 1 (Week 6-7) 已完成 ASAP** — 6 个 track (A/B1/B2/C/D1/D2) 全 ship + main 已合并
- **Sprint 2 (Week 8-10) 现在启动** — 5 个 worker chat (E/F/G/H/I) + 1 个新增 chat (J 财务审核) 并行

**完整业务流第一节** (Sprint 2 拼出来):
研发样品 (N48, Chat F) → BOM → 报价 → 销售下单 → 销售单审批 → **缺料分流** (N31, **你 Chat E**) → 采购建议 / 生产任务 → 钉钉群通知

### 你是谁

**你 = Chat E = Sprint 2 worker**。Sprint 2 有 6 个并行 chat:
- **Chat E (你)**: N31 销售→采购自动分流 (4d) 后端为主
- Chat F: N48 研发样品→BOM→报价 (5d) 全栈
- Chat G: UX-A1 业务流程图导航 (10d) RN+Vue
- Chat H: UX-A2 行末操作下拉 (10d) RN+Vue
- Chat I: UX-A3 Sticky Footer 实时合计 (7d) RN+Vue
- Chat J: P-FIN-1 采购财务审核+三价标红 (3d) 后端+小前端

你只关心 Chat E 自己的工作。其他 chat 跟你无关 (除非 organizer ping)。

### 沟通方式

- **不要在本 chat 跟 organizer 战略讨论** — 战略已定, 你只执行
- **每日在 STATUS 文件追加 1 段** (格式见 §7)
- **完成一个项目 → 推 PR → ping organizer review**
- **碰到 blocker 立即在 STATUS 报, 不要自己卡死**

---

## §2 任务范围与工时

### 单项目 (S-MRP-1)

| 项目 | N# 编号 | 工时名义 | 工时加速 | 优先级 | 客户感知 |
|---|---|---|---|---|---|
| **销售订单 → 采购自动分流** | S-MRP-1 (MUST_COPY.N31) | 4d | ~2.5d | P0 | 销售单审批后自动给出缺料 + 推荐采购建议 + 钉钉群推送 |

### v3 销售红线 (Sprint 2 解禁)

完成本项目后, 销售可以说:
- ✅ "AI 一句话从销售单一直分流到采购" (本项目)
- ✅ "销售单审批后自动判断库存 / 推荐采购"
- ✅ "缺料分析报告自动推到钉钉群" (+ Sprint 1 Track B1 集成)

**仍禁**:
- ❌ "财务发票 / 收款流水 / 多客户记忆价" (Sprint 3-4 才做)

### 客户原话证据

**来源**: 全流程文档 §2.2-3, MUST_COPY.md N31

> 销售单审核通过 → 系统自动判断库存 → 缺料则分流采购, 否则直接生产
> 输出 chain-card UI: 销售单 + 缺料列表 + 推荐采购 + 推荐生产
> 客户一键确认 / 修改

**Cretas 当前状态**: 缺料分析逻辑分散在 4 处 (BomExpansionService / InventoryMatchingService / ProcurementSuggestionService / SupplyChainOrchestrator), 无统一入口。

### 工时不达标怎么办

- 名义 4d 是上限。Claude 加速通常 1.7-2x → 实际预期 2.5-3 工作日
- 如果工时 >> 1.5 倍名义 (例如超过 6d), 立即在 STATUS 报 organizer
- Organizer 会决定: 减 scope / 拉外援 / 让你跳过钉钉集成

---

## §3 文件 Ownership (防冲突)

### 你的 (Chat E 独占, 你可以随便改)

```
backend/java/cretas-api/src/main/java/com/cretas/aims/
├── service/shortage/                                    ← 新建目录
│   ├── ShortageAnalysisService.java                    ← NEW 接口
│   ├── impl/
│   │   └── ShortageAnalysisServiceImpl.java            ← NEW 实现
│   └── dto/
│       ├── ShortageReport.java                          ← NEW DTO
│       ├── ProcurementSuggestion.java                  ← NEW DTO
│       └── ProductionPlanSuggestion.java               ← NEW DTO
├── ai/tool/impl/shortage/                               ← 新建目录
│   └── ShortageAnalysisTool.java                       ← NEW AI Tool
├── event/
│   └── SalesOrderApprovedEvent.java                    ← NEW Spring Event
└── controller/
    └── SalesOrderShortageController.java               ← NEW REST API

backend/java/cretas-api/src/main/resources/db/flyway/
└── V20260601_01__sales_order_shortage_report.sql       ← NEW Flyway

frontend/CretasFoodTrace/src/
├── screens/sales/
│   └── SalesOrderShortageReviewScreen.tsx              ← NEW Screen
└── components/chain/
    └── ShortageChainCard.tsx                            ← NEW 组件
```

### 修改 (改前确认其他 chat 没动)

```
backend/.../controller/SalesOrderController.java        ← 审批后 hook 加 publishEvent
backend/.../service/impl/BomExpansionService.java       ← 不重写, 加统一入口标记
```

### 共享只读 (改之前必须 ping organizer)

```
backend/java/cretas-api/src/main/java/com/cretas/aims/
├── entity/BaseEntity.java                              ← 跨 chat 共用
├── service/impl/IntentExecutorServiceImpl.java         ← AI 意图核心路由
└── ai/tool/AbstractBusinessTool.java                   ← Tool 基类

frontend/CretasFoodTrace/src/services/api/aiApiClient.ts
CLAUDE.md
.claude/rules/*
```

### 别 chat 的 (绝对不准碰)

- Chat F: `backend/.../entity/sample/`, `frontend/.../screens/rd/`
- Chat G: `frontend/.../components/workflow/`, `web-admin/.../components/workflow/`
- Chat H: `frontend/.../components/list/RowActionBottomSheet.tsx`, `web-admin/.../components/list/RowActionMenu.vue`
- Chat I: `frontend/.../components/list/StickyFooterSummary.tsx`, `web-admin/.../components/list/TableFooter.vue`
- Chat J: `backend/.../service/purchase/PurchaseOrderApprovalFlow.java`

### Sprint 1 已 ship 你要复用 (只读, 不改)

```
backend/.../service/dingtalk/DingTalkBotService.java     ← Sprint 1 Track B1 ship, 你 import 用
backend/.../ai/tool/AbstractBusinessTool.java            ← Tool 基类
backend/.../ai/client/PythonLLMClient.java               ← LLM 客户端 (如果 Tool 内调 LLM)
backend/.../service/impl/BomExpansionService.java        ← 已有 BOM 展开逻辑, 你统一调用
backend/.../service/impl/InventoryMatchingService.java   ← 已有库存匹配, 你统一调用
backend/.../service/impl/ProcurementSuggestionService.java ← 已有采购推荐, 你统一调用
backend/.../service/impl/SupplyChainOrchestrator.java    ← 已有编排, 你参考
```

---

## §4 Day-by-Day 执行计划

### Day 1 — 阅读 + ShortageAnalysisService 接口设计

#### 任务

1. **grep + 读 4 处现有缺料逻辑** (按 §3.4 路径):
   - `BomExpansionService` — BOM 展开 (输入 productId → 输出物料需求列表)
   - `InventoryMatchingService` — 库存匹配 (输入需求 → 输出库存是否够)
   - `ProcurementSuggestionService` — 采购推荐 (输入缺料 → 输出供应商建议)
   - `SupplyChainOrchestrator` — 编排器 (现有的"半统一"入口)
2. **读 SalesOrderController** 找到审批 endpoint, 看现有审批后做了什么
3. **起 worktree**:
   ```bash
   cd C:\Users\Steve\my-prototype-logistics
   git worktree add ../my-prototype-logistics-sprint2-track-e feature/sprint2-track-e-n31-shortage
   cd ../my-prototype-logistics-sprint2-track-e
   ```
4. **写 ShortageAnalysisService 接口** (路径 §3.1):
   ```java
   public interface ShortageAnalysisService {
       ShortageReport analyzeForSalesOrder(String factoryId, String salesOrderId);
       List<ProcurementSuggestion> suggestProcurement(String factoryId, ShortageReport report);
       List<ProductionPlanSuggestion> suggestProduction(String factoryId, ShortageReport report);
   }
   ```
5. **设计 DTO** (`ShortageReport / ProcurementSuggestion / ProductionPlanSuggestion`):
   - `ShortageReport`: salesOrderId, totalRequired (List<MaterialNeed>), available, shortage, suggestionSummary
   - `ProcurementSuggestion`: materialId, materialName, suggestedQty, suggestedSupplierId, estimatedPrice, leadDays
   - `ProductionPlanSuggestion`: productId, productName, plannedQty, workProcessIds, startDate, endDate
6. **不实现 method body**, commit 接口 + DTO

**DoD Day 1**: 接口 + DTO commit + STATUS 段落.

---

### Day 2 — Service 实现 + SalesOrderController hook

#### 任务

1. **写 ShortageAnalysisServiceImpl** — 编排现有 4 个 service, **不重写**:
   ```java
   @Service
   public class ShortageAnalysisServiceImpl implements ShortageAnalysisService {
       @Autowired private BomExpansionService bomExpansion;
       @Autowired private InventoryMatchingService inventoryMatching;
       @Autowired private ProcurementSuggestionService procurement;
       // ...

       @Override
       public ShortageReport analyzeForSalesOrder(String factoryId, String salesOrderId) {
           SalesOrder order = salesOrderRepository.findById(salesOrderId).orElseThrow(...);
           List<MaterialNeed> needs = bomExpansion.expand(order.getProductId(), order.getQuantity());
           List<InventoryAvailability> available = inventoryMatching.checkAvailability(factoryId, needs);
           List<MaterialShortage> shortages = computeShortages(needs, available);
           return ShortageReport.builder()
               .salesOrderId(salesOrderId)
               .totalRequired(needs)
               .available(available)
               .shortage(shortages)
               .build();
       }
   }
   ```

2. **写 SalesOrderApprovedEvent**:
   ```java
   @Getter
   public class SalesOrderApprovedEvent extends ApplicationEvent {
       private final String factoryId;
       private final String salesOrderId;
       private final String approvedBy;
       public SalesOrderApprovedEvent(Object source, String factoryId, String salesOrderId, String approvedBy) {
           super(source);
           // ...
       }
   }
   ```

3. **SalesOrderController 审批成功 hook**:
   ```java
   @Autowired private ApplicationEventPublisher eventPublisher;

   public ResponseEntity<?> approveSalesOrder(...) {
       // 现有审批逻辑
       salesOrderService.approve(salesOrderId, userId);
       // 加 hook
       eventPublisher.publishEvent(new SalesOrderApprovedEvent(this, factoryId, salesOrderId, userId.toString()));
       return ApiResponse.success(...);
   }
   ```

4. **@EventListener 接 event**:
   ```java
   @Component
   public class SalesOrderApprovedListener {
       @Autowired private ShortageAnalysisService shortageAnalysis;
       @Autowired private SalesOrderShortageReportRepository repository;

       @EventListener
       @Async  // 异步, 不阻塞审批
       public void onSalesOrderApproved(SalesOrderApprovedEvent event) {
           ShortageReport report = shortageAnalysis.analyzeForSalesOrder(event.getFactoryId(), event.getSalesOrderId());
           // 写表 sales_order_shortage_report
           repository.save(toEntity(report));
       }
   }
   ```

5. **Flyway V20260601_01__sales_order_shortage_report.sql**:
   ```sql
   CREATE TABLE sales_order_shortage_report (
       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
       factory_id VARCHAR(36) NOT NULL,
       sales_order_id VARCHAR(36) NOT NULL,
       analysis_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING/COMPLETED/FAILED
       total_required JSONB,
       available JSONB,
       shortage JSONB,
       procurement_suggestions JSONB,
       production_suggestions JSONB,
       analysis_summary TEXT,
       created_at TIMESTAMP DEFAULT NOW(),
       updated_at TIMESTAMP DEFAULT NOW(),
       deleted_at TIMESTAMP NULL
   );
   CREATE INDEX idx_sosr_factory_sales ON sales_order_shortage_report(factory_id, sales_order_id);
   ```

6. **写 SalesOrderShortageController + 单测**:
   ```java
   @GetMapping("/api/mobile/{factoryId}/sales-orders/{id}/shortage-report")
   public ApiResponse<ShortageReport> getShortageReport(@PathVariable String factoryId, @PathVariable String id) {
       // 从表读, 或返回正在分析中
   }
   ```

7. **F001 dev seed 单测**: 创个销售单, 审批, 验证 event 触发 + 表写入

**DoD Day 2**: `curl /api/mobile/{factoryId}/sales-orders/{id}/shortage-report` 返回 JSON.

---

### Day 3 — AIChat ShortageAnalysisTool + chain-card UI 设计

#### 任务

1. **写 ShortageAnalysisTool** (遵守 `.claude/rules/ai-intent-tool-skill-architecture.md`):
   ```java
   @Slf4j
   @Component
   public class ShortageAnalysisTool extends AbstractBusinessTool {

       @Autowired
       @Lazy  // ⚠️ HARD RULE: 防循环依赖
       private ShortageAnalysisService shortageService;

       @Override
       public String getToolName() { return "shortage_analyze"; }

       @Override
       public String getDescription() {
           return "分析销售订单的缺料情况, 返回需要采购的物料 + 推荐生产任务 (调用方: 销售单审批后或 AI 用户问 '这单缺什么')";
       }

       @Override
       public Map<String, Object> getParametersSchema() {
           return Map.of(
               "type", "object",
               "properties", Map.of(
                   "salesOrderId", Map.of("type", "string", "description", "销售单 ID")
               ),
               "required", List.of("salesOrderId")
           );
       }

       @Override
       protected List<String> getRequiredParameters() { return List.of("salesOrderId"); }

       @Override
       protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
           String salesOrderId = getString(params, "salesOrderId");
           ShortageReport report = shortageService.analyzeForSalesOrder(factoryId, salesOrderId);
           List<ProcurementSuggestion> procurement = shortageService.suggestProcurement(factoryId, report);
           List<ProductionPlanSuggestion> production = shortageService.suggestProduction(factoryId, report);
           return Map.of(
               "status", "SUCCESS",
               "salesOrderId", salesOrderId,
               "shortage", report.getShortage(),
               "procurementSuggestions", procurement,
               "productionSuggestions", production,
               "displayHint", "chain-card"  // 前端识别 chain-card 渲染
           );
       }
   }
   ```

2. **绑定 intent** (Flyway 加 V20260601_02__shortage_intent.sql):
   ```sql
   INSERT INTO ai_intent_config (
       id, intent_code, intent_name, intent_category,
       tool_name, keywords, is_active, sensitivity_level
   ) VALUES (
       gen_random_uuid(), 'SHORTAGE_ANALYSIS', '缺料分析', 'DATA_QUERY',
       'shortage_analyze', '["缺料","缺什么","缺多少","缺哪些原料","库存够吗"]',
       true, 'LOW'
   );
   ```

3. **RN ShortageChainCard.tsx** — 3 段 card 组件:
   ```typescript
   interface ShortageChainCardProps {
     salesOrderId: string;
     salesOrder: SalesOrderSummary;
     shortage: MaterialShortage[];
     procurementSuggestions: ProcurementSuggestion[];
     productionSuggestions: ProductionPlanSuggestion[];
     onConfirmProcurement: (suggestions: ProcurementSuggestion[]) => void;
     onConfirmProduction: (suggestions: ProductionPlanSuggestion[]) => void;
     onDingTalkPush: () => void;
   }
   ```
   - 卡片 1: 销售单摘要 (订单号 / 客户 / 数量 / 状态)
   - 卡片 2: 缺料列表 + 推荐采购建议 (供应商 + 单价 + lead days)
   - 卡片 3: 推荐生产任务 (产品 + 工序链)

4. **SalesOrderShortageReviewScreen.tsx** 用 chain-card:
   - 取销售单 ID (route param)
   - 调 `GET /sales-orders/{id}/shortage-report` 拿数据
   - 渲染 `<ShortageChainCard ...>`
   - 操作: 一键确认采购 / 修改 / 钉钉推送

**DoD Day 3**: AIChat 输入 "F006 销售单 SO-001 缺什么" 返回 chain-card 结构 JSON.

---

### Day 4 — RN UI 完整接入 + 钉钉推送 + Demo + PR

#### 任务

1. **SalesOrderShortageReviewScreen 完整接入**:
   - 接入路由 (找 `SalesStackNavigator` 加路由 `SalesOrderShortageReview: { salesOrderId: string }`)
   - 调用 `salesApiClient.getShortageReport(salesOrderId)`
   - 处理 loading / error / pending 状态 (analysis 异步, 可能要 poll)
   - 跳转: 一键确认采购 → 跳到 `PurchaseOrderCreate` 预填; 一键确认生产 → 跳到 `ProductionPlanCreate` 预填

2. **钉钉推送集成** (Sprint 1 Track B1 已 ship `DingTalkBotService`):
   ```java
   // 在 SalesOrderApprovedListener 异步处理后
   @Autowired private DingTalkBotService dingTalkBotService;

   public void onSalesOrderApproved(SalesOrderApprovedEvent event) {
       ShortageReport report = ...;
       repository.save(toEntity(report));
       // 推钉钉
       String msg = buildDingTalkMessage(report);
       dingTalkBotService.sendNotification(event.getFactoryId(), "缺料告警", msg);
   }
   ```
   - 钉钉消息格式: ActionCard 显示 "销售单 SO-001 审批通过, 检测到 3 物料缺料 / 推荐 2 张采购单 [查看详情]"
   - 详情链接到 Cretas Web-Admin 或 H5 页面

3. **Demo 录制** (1-2 分钟):
   1. 销售员登录 (F006), 创销售单 (SO-XXX)
   2. 主管审批通过
   3. 自动触发缺料分析 (后端日志可见)
   4. 钉钉群收到 ActionCard 卡片 (这里强依赖 Sprint 1 钉钉 PoC, 若无可用 mock 截图)
   5. 销售员打开 RN → SalesOrderShortageReviewScreen → 看到 chain-card
   6. AIChat 输入 "SO-XXX 缺什么" → 返回相同 chain-card
   7. 一键确认采购 → 跳 PurchaseOrderCreate 预填 → 提交

4. **推 PR**:
   ```bash
   git push -u origin feature/sprint2-track-e-n31-shortage
   gh pr create --title "[Sprint2-E] S-MRP-1 销售订单→采购自动分流" --body "..."
   ```

   PR body 含:
   - 涉及文件清单
   - 测试方式 (单测 + curl + E2E demo)
   - 风险点 (event 异步可能 race / 钉钉 webhook 配置依赖 Sprint 1)
   - 跟 Sprint 1 哪些 PR 依赖 (Track B1 钉钉 / Track C 三价对比 / Track D1 BOM 物料字典 / Track D2 工序)

**DoD Day 4**: PR + demo + STATUS 4 段完整.

---

## §5 关键参考文档

| 路径 | 用途 |
|---|---|
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\MUST_COPY.md` §B N31 | 业务定义 + 客户原话 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\SPRINT_2_PLAN.md` §5.1 | Day-by-day 来源 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\01-客户档案\NUMBERING_MAP.md` | S-MRP-1 编号 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\TRACK_B1_BRIEF.md` | Sprint 1 钉钉 PoC (你 import `DingTalkBotService`) |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\ai-intent-tool-skill-architecture.md` HARD | Tool 注册 / @Lazy 防循环依赖 / 禁 IntentHandler |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\api-response-handling.md` | 统一响应格式 `{ success, data, message }` |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\database-entity-sync.md` | Entity / Flyway / BaseEntity audit 字段 |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\field-naming-convention.md` | camelCase / snake_case |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\typescript-type-safety.md` | 禁 `as any` |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\concurrent-edit-safety.md` HARD | 6 chat 并行 — commit 前必 git status / `git commit -- F1 F2` |

---

## §6 接口契约 (Interface Contracts)

### 后端 → 前端

**GET /api/mobile/{factoryId}/sales-orders/{id}/shortage-report**

```typescript
// Response
{
  success: true,
  data: {
    salesOrderId: string,
    analysisStatus: 'PENDING' | 'COMPLETED' | 'FAILED',
    salesOrder: { id, code, customerName, totalQuantity, status },
    totalRequired: Array<{ materialId, materialName, qty, unit }>,
    available: Array<{ materialId, availableQty }>,
    shortage: Array<{ materialId, materialName, shortageQty, unit }>,
    procurementSuggestions: Array<{
      materialId, materialName, suggestedQty,
      suggestedSupplierId, suggestedSupplierName,
      estimatedPrice, estimatedTotal, leadDays,
      priceComparison?: { bomStandardPrice, movingAvgPrice, currentPrice, priceAlert }  // ⚠️ Sprint 1 Track C 三价
    }>,
    productionSuggestions: Array<{
      productId, productName, plannedQty,
      workProcessIds: string[], workProcessNames: string[],
      startDate, endDate
    }>,
    analysisSummary: string
  },
  message: "操作成功"
}
```

### AIChat Tool 输出 schema

```json
{
  "status": "SUCCESS",
  "salesOrderId": "SO-001",
  "shortage": [...],
  "procurementSuggestions": [...],
  "productionSuggestions": [...],
  "displayHint": "chain-card"
}
```

前端 AIChat 看到 `displayHint: "chain-card"` 自动渲染 `<ShortageChainCard>` 而不是普通文字回复。

### Sprint 1 依赖的接口

| Sprint 1 提供 | 你怎么用 |
|---|---|
| Track B1 `DingTalkBotService.sendNotification(factoryId, title, content)` | 在 listener 异步推送 |
| Track C `MaterialPriceComparisonDTO` | 三价数据塞 `procurementSuggestions[].priceComparison` |
| Track C 三价对比刷新 bug fix | 你的推荐采购数据三价准确 |
| Track D1 `MaterialType` 物料字典 + 单位转换 | `bomExpansion` 输出物料是硬外键 |
| Track D2 `WorkProcess` + `ProductWorkProcessConfig` | `productionSuggestions[].workProcessIds` 准确 |

---

## §7 PR / Status Update 流程

### 每日 STATUS 更新

文件: `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\STATUS\TRACK_E_STATUS.md`

格式 (每天追加):
```markdown
## Day N (YYYY-MM-DD)
- ✅ 完成: X / Y / Z
- 🟡 进行中: A
- ❌ Blocker: B (需 organizer 协调)
- 明日计划: C / D
```

### PR 流程

1. **创建 worktree** (`.claude/rules/concurrent-edit-safety.md` 推荐):
   ```bash
   cd C:\Users\Steve\my-prototype-logistics
   git worktree add ../my-prototype-logistics-sprint2-track-e feature/sprint2-track-e-n31-shortage
   ```

2. **里程碑式 commit** — 完成一个 phase (Day) 立即 commit, 不要等 Day 4:
   ```bash
   git add backend/.../shortage/ShortageAnalysisService.java
   git commit -m "WIP: Day 1 ShortageAnalysisService 接口设计"
   ```

3. **并发安全 commit** (`.claude/rules/concurrent-edit-safety.md` Rule 5b):
   ```bash
   # 用 specific paths, 不要 git add .
   git commit -m "feat: ShortageAnalysisService 实现" -- backend/.../shortage/ShortageAnalysisServiceImpl.java backend/.../event/SalesOrderApprovedEvent.java
   ```

4. **推 PR**:
   ```bash
   git push -u origin feature/sprint2-track-e-n31-shortage
   gh pr create --title "[Sprint2-E] S-MRP-1 销售订单→采购自动分流" --body "$(cat <<'EOF'
   ## Summary
   - 新建 ShortageAnalysisService 统一入口
   - SalesOrderController 审批后异步触发缺料分析
   - 新增 ShortageAnalysisTool (AI 意图 SHORTAGE_ANALYSIS)
   - RN SalesOrderShortageReviewScreen + ShortageChainCard
   - 钉钉群推送集成

   ## Test plan
   - [ ] 单测: ShortageAnalysisServiceImpl 4 个 service 编排
   - [ ] curl: GET shortage-report 返回 JSON
   - [ ] AIChat: "SO-XXX 缺什么" → chain-card
   - [ ] E2E demo: 销售单审批 → 钉钉收到 → 一键确认采购

   ## Sprint 1 依赖
   - Track B1 DingTalkBotService (钉钉推送)
   - Track C MaterialPriceComparisonDTO (三价)
   - Track D1 MaterialType (BOM 展开)
   - Track D2 WorkProcess (生产建议)

   ## Risk
   - Event @Async 跟事务 commit 时序
   - 钉钉 webhook 配置依赖客户群

   🤖 Generated with Claude Code
   EOF
   )"
   ```

5. **等 organizer review** — 不要自己 merge

### Blocker 上报模板

```markdown
## Day N (YYYY-MM-DD)
- ❌ Blocker: Track D2 工序服务还没 ship, productionSuggestions 无法填 workProcessIds
- 影响: Day 3 推荐生产部分降级
- 建议方案: A) 等 D2; B) 先 ship 缺料 + 采购两段, 生产部分留 TODO; C) 拉外援
- 需要 organizer: 拍板 A/B/C
```

---

## §8 不要做 (Do Not Do)

### 严格禁止

1. **不要重写现有 4 个 service** (BomExpansion / InventoryMatching / Procurement / SupplyChain) — 你的范围是统一入口编排, 不是重写

2. **不要改 ownership 外的文件** (`§3` 已列):
   - 不准改 `BaseEntity.java`
   - 不准改 `IntentExecutorServiceImpl.java`
   - 不准改 `aiApiClient.ts`
   - 不准改 Chat F/G/H/I/J 的 ownership

3. **不要创建 IntentHandler** (`.claude/rules/ai-intent-tool-skill-architecture.md` ⛔):
   - Handler 架构已废弃, 必须用 Tool

4. **不要直接 @Autowired AIIntentService 到 ShortageAnalysisTool**:
   - 会循环依赖, 必须 `@Lazy`
   - 但其实 Tool 不需要注入 AIIntentService, 这点你天然避开

5. **不要降级处理** (CLAUDE.md 核心原则):
   - 不要返回假数据 (LLM 失败要明确 error)
   - 不要静默吞错
   - 不要 `catch (error: any) { /* ignore */ }`

6. **不要用 `as any`** (`.claude/rules/typescript-type-safety.md`):
   - RN 用 `useRoute<RouteProp<SalesStackParamList, 'SalesOrderShortageReview'>>()`

7. **不要并发改同一文件** (`.claude/rules/concurrent-edit-safety.md`):
   - 用 git worktree 隔离
   - 修改共享文件前 `git status` 确认
   - Commit 用 `git commit -- F1 F2` 锁定 scope

8. **不要在 Tool name 重名** (`.claude/rules/ai-intent-tool-skill-architecture.md`):
   - `shortage_analyze` 全仓唯一 (你可以 grep 确认)

---

## §9 验收清单

### 功能验收

- [ ] **后端**: ShortageAnalysisService 接口 + 实现编排现有 4 service 不重写
- [ ] **后端**: SalesOrderController 审批后 publishEvent
- [ ] **后端**: @EventListener @Async 接 event → 写 sales_order_shortage_report 表
- [ ] **后端**: GET /sales-orders/{id}/shortage-report 返回 JSON
- [ ] **后端**: ShortageAnalysisTool 注册到 ToolRegistry, intent SHORTAGE_ANALYSIS 绑定
- [ ] **AI**: AIChat "F006 销售单 SO-001 缺什么" 返回 chain-card 结构
- [ ] **前端**: SalesOrderShortageReviewScreen 接入 SalesStackNavigator
- [ ] **前端**: ShortageChainCard 渲染 3 段 (销售单 / 缺料采购 / 生产建议)
- [ ] **前端**: 一键确认采购 → 跳 PurchaseOrderCreate 预填
- [ ] **集成**: 销售单审批 → 钉钉群收到 ActionCard (依赖 Track B1)
- [ ] **三价**: procurementSuggestions[].priceComparison 含 BOM/移动平均/当前价 (依赖 Track C)

### 销售红线验收

- [ ] **红线**: 销售可以说 "AI 一句话从销售单一直分流到采购"
- [ ] **红线**: 销售单审批后自动判断库存 + 推荐采购
- [ ] **红线**: 缺料分析报告自动推到钉钉群

### 技术验收

- [ ] PR merged 到 main
- [ ] 无新增 `as any`
- [ ] 无新增 `catch (error: any)`
- [ ] Flyway migration 文件存在 (sales_order_shortage_report + ai_intent_config)
- [ ] 单元测试覆盖 ShortageAnalysisServiceImpl 4 service 编排
- [ ] E2E demo 视频录制

---

## §10 客户场景对照

### 客户期望

**六扇门 F006 (卤制品工厂)** 希望:
1. **"AI 一句话调度生产/采购"** — 销售下单后, 销售员/老板能 AI 一问就知道缺什么
2. **自动业务流转** — 销售单一审批通过, 系统自动判断库存, 推荐采购 / 生产任务
3. **钉钉群通知** — 缺料告警直接推到工厂运营钉钉群

### Cretas 的差异化卖点

宏见 ERP 范式: 销售员手动判断库存 / 主管手动建采购单 / 主管手动建生产任务。**全部 manual, 客户嫌麻烦**。

Cretas Sprint 2 完成后:
- ✅ 销售单审批 → 自动分析缺料 (后台 @Async)
- ✅ AIChat 一句话查缺料 (Tool + chain-card)
- ✅ 一键确认建采购单 / 生产任务
- ✅ 钉钉群通知 (Sprint 1 集成)

### 跟其他 Chat 的串联

```
Chat F (N48 研发样品→BOM→报价) — 提供 BOM 数据底层
       ↓
Chat E (N31 销售→采购分流, 你) — 调用 BOM 展开 + 库存匹配 + 采购推荐
       ↓
Chat J (P-FIN-1 采购财务审核) — 接你的采购建议, 三价标红 + 财务审批
       ↓
钉钉群通知 (Sprint 1 Track B1)
```

完整业务流第一节: 研发样品 (F) → BOM → 销售下单 → 审批 → 缺料分流 (你) → 采购建议 → 财务审核 (J) → 钉钉

---

## 附录: 关键命令速查

### 启动开发环境

```powershell
# 后端 Java (端口 10010)
cd C:\Users\Steve\my-prototype-logistics\backend\java\cretas-api
mvn spring-boot:run

# 后端 Python (端口 8083)
cd C:\Users\Steve\my-prototype-logistics\backend\python
uvicorn main:app --port 8083

# 前端 (RN, 端口 3010)
cd C:\Users\Steve\my-prototype-logistics\frontend\CretasFoodTrace
npm start
```

### 健康检查

```powershell
curl http://localhost:10010/api/mobile/health
curl http://localhost:8083/health
```

### Git Worktree

```powershell
cd C:\Users\Steve\my-prototype-logistics
git worktree add ../my-prototype-logistics-sprint2-track-e feature/sprint2-track-e-n31-shortage
cd ../my-prototype-logistics-sprint2-track-e
```

### 安全 Commit

```powershell
git commit -m "feat: ShortageAnalysisService 实现" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/shortage/impl/ShortageAnalysisServiceImpl.java backend/java/cretas-api/src/main/java/com/cretas/aims/event/SalesOrderApprovedEvent.java
```

---

**Brief 结束。Day 1 开始干活。第一件事: 创建 STATUS 文件 + 启动 worktree, 然后 grep + 读 4 个 service 摸清现有缺料逻辑。**