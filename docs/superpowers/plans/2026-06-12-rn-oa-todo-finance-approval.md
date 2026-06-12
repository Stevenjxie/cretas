# RN OA「我的待办」财务/出纳手机端审批 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development。本计划校准给 **in-harness Sonnet**（有 codebase 访问 + `.claude/rules`），每 Task 走 TDD（先写失败测试→实现→通过→commit），代码细节读现有同域 pattern 复用。spec: `docs/superpowers/specs/2026-06-12-rn-oa-todo-finance-approval-design.md`。

**Goal:** 给财务主管/出纳在 RN 手机端一个「我的待办」统一审批入口（客户要求审批流手机端），聚合现有 5 类审批端点 + 混合审批深度（小额一键/大额详情）。

**Architecture:** 后端新增统一待办聚合 API（fan-out 5 个已有 pending 查询 + 角色过滤 + ¥5000 阈值），RN 新增待办列表/详情屏 + 导航入口。**0 改现有审批端点**，**⛔ 不碰 WorkflowEngine**。

**Tech Stack:** Java 21 + Spring Boot（后端聚合）/ Expo RN + TS（前端）。

---

## 文件结构

**后端**（新建，包 `com.cretas.aims.controller.oa` + `service.oa`）:
- `dto/oa/TodoItemDTO.java` — 统一待办项（Lombok @Data/@Builder）
- `service/oa/MyTodoAggregatorService.java` — fan-out + 角色过滤 + 阈值（接口+impl 或单 @Service）
- `controller/oa/MyTodoController.java` — GET /my-todos + /count
- 测试 `service/oa/MyTodoAggregatorServiceTest.java`

**RN**（新建）:
- `src/services/api/myTodoApiClient.ts` — 调 /my-todos + /count + 各域 approve/reject
- `src/hooks/useMyTodos.ts` — 拉取 + 刷新
- `src/screens/oa/MyTodoListScreen.tsx` — 列表 + 混合审批
- `src/screens/oa/TodoDetailScreen.tsx` — 大额详情审批
- `src/navigation/MainNavigator.tsx`（改）— finance_manager/cashier 加待办 tab + 徽标

---

## 后端块（Sonnet in-harness 通道 A）

### Task B1: TodoItemDTO
**Files:** Create `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/oa/TodoItemDTO.java`
- [ ] **Step 1**: 写 DTO（Lombok @Data @Builder @NoArgsConstructor @AllArgsConstructor），字段严格按 spec §4.1:
  - `TodoType type`（新 enum: PURCHASE_FINANCE_REVIEW / SALES_FINANCE_REVIEW / PRICE_ANOMALY / STOCKTAKE_APPROVAL / PAYMENT_DISBURSE，内嵌或独立 enum 文件）
  - `String refId, refNumber, title, counterparty, submittedBy`
  - `java.math.BigDecimal amount`（@PriceSensitive 不加 —— 待办金额是审批人本就该看的，但 RN 端这是财务角色，无需脱敏）
  - `java.time.LocalDateTime submittedAt`
  - `boolean needDetail`
  - `String detailPath`（可空）
- [ ] **Step 2**: 编译 `./mvnw -o compile -q`，无错。
- [ ] **Step 3**: commit `feat(oa): TodoItemDTO 统一待办项`

### Task B2: MyTodoAggregatorService（核心 — TDD）
**Files:** Create `service/oa/MyTodoAggregatorService.java` + Test `service/oa/MyTodoAggregatorServiceTest.java`
注入现有：`PurchaseService`（采购单 PENDING_FINANCE_REVIEW 查询）/ `SalesService`（销售单 PENDING_FINANCE_REVIEW）/ 价格异常 service（FactorySupplierDelivery `/price-anomaly/pending` 背后的 service）/ `FactoryStocktakeService`（status=PENDING_APPROVAL，repo `findByFactoryIdAndStatus`）/ `PaymentRequestService.listApprovedForPayment(factoryId)`。
- [ ] **Step 1: 写失败测试**（用 Mockito mock 5 个域 service）:
  - `listTodos_financeManager_returns4Types`: mock 各域返 1 条 → finance_manager 调 listTodos → 返 4 条（采购财审/销售财审/价格异常/盘点审批），无付款。
  - `listTodos_cashier_returnsOnlyPayment`: cashier → 只付款类。
  - `listTodos_otherRole_returnsEmpty`: operator → 空。
  - `needDetail_aboveThreshold_true`: amount=6000(>5000) → needDetail=true；amount=4000 → false。
- [ ] **Step 2**: 跑测试确认 FAIL（service 未实现）。
- [ ] **Step 3: 实现** `listTodos(factoryId, callerRole)` + `countTodos(...)`:
  - 角色→类型映射常量 Map<String, Set<TodoType>>：finance_manager→{4类}，cashier→{PAYMENT_DISBURSE}，default 空。
  - 每类型 fan-out 对应域 pending 查询 → map 成 TodoItemDTO（type/refNumber/title/amount/counterparty/submittedBy/submittedAt）。amount 缺失→needDetail=true 保守。
  - 阈值 `@Value("${cretas.oa.todo.detail-threshold:5000}") BigDecimal threshold`；`needDetail = amount==null || amount.compareTo(threshold) > 0`。
  - 合并按 submittedAt 倒序。countTodos = listTodos().size()（或各域 count 求和，优先简单）。
  - **fan-out 用 try/catch 单类型失败不拖垮整体**（某域查询抛异常→log warn + 该类型空，不 500）。
- [ ] **Step 4**: 跑测试 PASS。
- [ ] **Step 5**: commit `feat(oa): MyTodoAggregatorService 待办聚合+角色过滤+阈值`

### Task B3: MyTodoController
**Files:** Create `controller/oa/MyTodoController.java`
- [ ] **Step 1**: 写 controller：`@RequestMapping("/api/mobile/{factoryId}/my-todos")`
  - `GET ""` → `ApiResponse<List<TodoItemDTO>>`，caller role 从 `request.getAttribute("role")`（复用现有 JwtAuthInterceptor 范式，非 SecurityContext —— 见 .claude/rules C1 孪生坑），调 service.listTodos。
  - `GET "/count"` → `ApiResponse<Integer>`。
  - `@RequirePermission` 宽松（任何登录用户；聚合层按角色过滤决定内容）。
- [ ] **Step 2**: 编译无错。
- [ ] **Step 3**: commit `feat(oa): MyTodoController /my-todos + /count`

### Task B4: 🔒 Opus 终审 + 部署
- [ ] gate: 角色过滤正确（finance_mgr 不串看付款/cashier 不看财审）、fan-out 不串租户、阈值边界、fan-out fail-soft。
- [ ] 从 main 部署后端 + live 验（finance_mgr token 调 /my-todos 返 4 类；cashier 返付款）。

---

## RN 块（Sonnet in-harness 通道 B，B 完成后或并行）

### Task R1: api client + hook
**Files:** Create `src/services/api/myTodoApiClient.ts` + `src/hooks/useMyTodos.ts`
- [ ] api client: `getMyTodos(factoryId)` / `getMyTodoCount(factoryId)` + 复用现有 approve/reject 域 client（采购 finance-approve / 销售 finance-approve / price-anomaly approve+reject / stocktake approve+reject / payment mark-paid）。
- [ ] hook: 拉取 + loading + refetch。
- [ ] commit。

### Task R2: MyTodoListScreen（防呆核心）
**Files:** Create `src/screens/oa/MyTodoListScreen.tsx`
- [ ] 列表卡片显 单号+金额+对象+申请人+时间（防呆 Rule 2）。
- [ ] `needDetail=false`：卡底「通过」「驳回」。通过→确认 dialog（带品名/单号/金额上下文）→ 调域 approve→刷新+徽标-1+sticky toast。驳回→**原因 dropdown**（标准 5-8 项+其他才 textarea，防呆 Rule 3）→ 调域 reject。
- [ ] `needDetail=true`：卡底只「查看详情」→ navigate TodoDetailScreen。
- [ ] 空状态「暂无待办」（防呆 Rule 5）。catch 409→「已被处理」+刷新。
- [ ] commit。

### Task R3: TodoDetailScreen
**Files:** Create `src/screens/oa/TodoDetailScreen.tsx`
- [ ] 按 type 拉对应域详情 API 显全明细 → 底部「通过」「驳回」（同 R2 dropdown）。
- [ ] commit。

### Task R4: MainNavigator 入口 + 徽标
**Files:** Modify `src/navigation/MainNavigator.tsx`
- [ ] 对 `getUserRole(user)` ∈ {finance_manager, cashier} 加「我的待办」Tab.Screen（复用现有条件渲染 pattern，line ~168-253）。
- [ ] Tab 徽标 = useMyTodos count（复用 ConfigChangeSet /pending/count 徽标范式）。
- [ ] commit。

### Task R5: headed E2E + OTA
- [ ] RN headed E2E：finance_mgr 登录→待办 tab 有徽标→小额一键通过(域端点真调 SQL 坐实)→大额强制详情→详情审批→驳回 dropdown；cashier→付款待办→mark-paid。
- [ ] OTA push prod（从 main）。

---

## 自审（spec coverage）

- spec §2 全 5 类 → Task B2 角色映射覆盖采购/销售财审+价格异常+盘点审批+付款 ✅
- spec §3 Option A 后端聚合 → B2/B3 ✅
- spec §4 混合审批深度 阈值 → B2 needDetail + R2 分支 ✅
- spec §6 防呆 4 位一体 → R2/R3（Rule 2/3/5 + 幂等 409） ✅
- spec §7 测试 → B2 单测 + R5 headed ✅
- spec §8 OUT（WorkflowEngine/per-factory阈值UI/报损/owner/复杂报表/push）→ 计划未含 ✅

## 派活路由（organizer）

| 块 | model | 通道 | scope 锁 |
|---|---|---|---|
| 后端 B1-B3 | Sonnet in-harness | organizer spawn subagent | `dto/oa/` + `service/oa/` + `controller/oa/`（全新，0 改现有） |
| 🔒 B4 终审+部署 | Opus | 本体 | - |
| RN R1-R4 | Sonnet in-harness | organizer spawn subagent | `src/screens/oa/` + `src/services/api/myTodoApiClient.ts` + `src/hooks/useMyTodos.ts` + MainNavigator |
| R5 headed E2E | Codex（测试归 Codex）| Steve courier | docs/audits |

后端先行（RN 依赖 /my-todos 端点）。🔒 角色过滤/审批正确性 Opus 终审从 main 部署。
