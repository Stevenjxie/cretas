# TRACK G BRIEF — Sprint 2: UX-A1 业务流程图导航 (Bento + Web)

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **Sprint 2 chat (基于 Sprint 1 已完成 ASAP)**
> **Brief 来源**: `SPRINT_2_PLAN.md` §5.3 (Chat G — UX-A1 10d 名义)
> **接收方**: Chat G (Sprint 2 worker)
> **派发方**: Chat 1 (Organizer)
> **派发日期**: 2026-05-15
> **预期完成**: ~5-6 工作日 (名义 10d, Claude 加速 ~1.7x)
> **PR 命名**: `[Sprint2-G] U-NAV-1 业务流程图导航`
> **STATUS 文件**: `宏见竞品分析/04-最终决策/STATUS/TRACK_G_STATUS.md`
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

源码位置: `C:\Users\Steve\my-prototype-logistics\`
- Java 后端: `backend/java/cretas-api/`
- RN 前端: `frontend/CretasFoodTrace/`
- Vue Web-Admin: `web-admin/`

### 当前业务背景

**客户**: 六扇门 (F006) 卤制品工厂 — ASAP 1.5 月交付

**Sprint 2 在哪**:
- **Sprint 1 (Week 6-7) 已完成 ASAP** — 6 个 track 全 ship + main 已合并
- **Sprint 2 (Week 8-10) 现在启动** — 6 个 worker chat 并行

**Sprint 2 UX 三件套** (你是其中之一):
- **你 Chat G**: UX-A1 流程图导航 — 首页 + 列表页顶部加节点流程图
- Chat H: UX-A2 行末操作下拉 — 列表行末"操作 ▾"BottomSheet
- Chat I: UX-A3 Sticky Footer 实时合计 — 列表底部固定栏

**完成后 Cretas UI 一次性升一档, 客户立即感知**。

### 你是谁

**你 = Chat G = Sprint 2 worker**。Sprint 2 有 6 个并行 chat:
- Chat E: N31 销售→采购自动分流 (4d)
- Chat F: N48 研发样品→BOM→报价 (5d)
- **Chat G (你)**: UX-A1 业务流程图导航 (10d) RN + Vue 全栈
- Chat H: UX-A2 行末操作下拉 (10d)
- Chat I: UX-A3 Sticky Footer 实时合计 (7d)
- Chat J: P-FIN-1 采购财务审核 (3d)

### 沟通方式

- **不要在本 chat 跟 organizer 战略讨论** — 战略已定, 你只执行
- **每日在 STATUS 文件追加 1 段** (格式见 §7)
- **完成一个 sub-task → 推 PR 不要等 Day 10**
- **碰到 blocker 立即在 STATUS 报**

---

## §2 任务范围与工时

### 单项目 (U-NAV-1)

| 项目 | N# 编号 | 工时名义 | 工时加速 | 优先级 | 客户感知 |
|---|---|---|---|---|---|
| **业务流程图导航 (Bento + Web)** | U-NAV-1 (UX_BORROW.A-1) | 10d | ~6d | P0 ⭐⭐⭐ | 首页 + 每业务模块顶部多了流程节点导航 (粉/绿/蓝 状态色 + 数量徽章 + AI 入口) |

### 客户原话证据

**来源**: 宏见 UI 审计 UX_BORROW.md A-1 ⭐⭐⭐ Top ROI

**宏见做法**: 每个模块顶部"节点流程图" — 粉色=待处理 / 绿色=进行中 / 蓝色=已完成。点击节点跳对应单据列表。

**Cretas 现状**: ❌ **完全无** WorkflowVisualizer 组件 — 这是 Cretas 跟传统 ERP 的关键差距。

**HD 帧证据**: `000043` 销售订单工作流图 / `001333` 采购流程图 / `001736` 财务流程图。

### Cretas 怎么用 (核心创新点)

- **移动 App (RN)**: 首页 BentoGrid 顶部一个 1x2 大卡片, 显示"今日工作流状态" + 3-5 个节点 + 数量徽章
- **Web-Admin (Vue)**: 每个业务模块 (销售/采购/生产) 顶部加 horizontal flowchart bar
- **AI 增强**: 节点支持"AI 触发" — 点节点 + 一句话即可 (AIChat 进入该节点 context)

### v3 销售红线 (Sprint 2 解禁)

完成本项目后, 销售可以说:
- ✅ "每个业务模块顶部都有流程图导航"
- ✅ "首页 BentoGrid 含 5 角色专属工作流卡片"
- ✅ "AI 触发节点 — 一句话进入待审列表"

### 工时不达标怎么办

- 名义 10d 是上限。Claude 加速 ~1.7-2x → 实际预期 5-6 工作日
- 如果工时 >> 1.5 倍名义 (例如超过 14d), 立即在 STATUS 报 organizer
- Organizer 会决定: 减 scope (先 5 模块 / 5 list 接入, 剩余推到 Sprint 3) / 拉外援

---

## §3 文件 Ownership (防冲突)

### 你的 (Chat G 独占, 你可以随便改)

```
frontend/CretasFoodTrace/src/
├── components/workflow/                                 ← 新建目录
│   ├── WorkflowVisualizer.tsx                          ← NEW 核心组件 (RN)
│   ├── WorkflowNode.tsx                                ← NEW 子组件
│   ├── WorkflowConnector.tsx                           ← NEW 连线 svg
│   └── index.ts
├── hooks/
│   └── useWorkflowStats.ts                             ← NEW 调 API
└── types/
    └── workflow.ts                                      ← NEW

web-admin/src/
├── components/workflow/                                 ← 新建目录
│   ├── WorkflowBar.vue                                 ← NEW 核心组件 (Vue)
│   ├── WorkflowNode.vue                                ← NEW 子组件
│   └── index.ts
├── composables/
│   └── useWorkflowStats.ts                             ← NEW
└── types/
    └── workflow.ts                                      ← NEW

backend/java/cretas-api/src/main/java/com/cretas/aims/
└── controller/
    └── WorkflowStatsController.java                    ← NEW 5 endpoint
```

### 修改 (改前确认其他 chat 没动)

```
frontend/CretasFoodTrace/src/screens/
├── sales/SalesHomeScreen.tsx                           ← BentoGrid 加流程图卡片
├── purchase/PurchaseHomeScreen.tsx                     ← 同
├── production/ProductionHomeScreen.tsx                 ← 同
├── finance/FinanceHomeScreen.tsx                       ← 同
└── inventory/InventoryHomeScreen.tsx                   ← 同

web-admin/src/views/
├── sales/SalesOrderListView.vue                        ← 顶部加 WorkflowBar
├── purchase/PurchaseOrderListView.vue                  ← 同
├── production/ProductionPlanListView.vue               ← 同
├── finance/FinanceListView.vue                         ← 同
└── inventory/InventoryListView.vue                     ← 同
```

### 共享只读 (改之前必须 ping organizer)

```
backend/.../entity/BaseEntity.java
backend/.../service/impl/IntentExecutorServiceImpl.java
backend/.../ai/tool/AbstractBusinessTool.java
frontend/.../services/api/aiApiClient.ts
frontend/.../navigation/*  ← 路由配置 (你加流程图入口必经)
CLAUDE.md
.claude/rules/*
```

### 别 chat 的 (绝对不准碰)

- Chat E: `backend/.../service/shortage/`, `frontend/.../screens/sales/SalesOrderShortageReviewScreen.tsx`
- Chat F: `backend/.../entity/sample/`, `frontend/.../screens/rd/`
- Chat H: `frontend/.../components/list/RowActionBottomSheet.tsx`, `web-admin/.../components/list/RowActionMenu.vue`
- Chat I: `frontend/.../components/list/StickyFooterSummary.tsx`, `web-admin/.../components/list/TableFooter.vue`
- Chat J: `backend/.../service/purchase/PurchaseOrderApprovalFlow.java`

### Sprint 1 已 ship 你可能用到 (只读)

```
frontend/.../services/api/aiApiClient.ts                 ← AIChat 入口 (节点 AI 触发)
backend/.../entity/decoration/FactoryHomeLayout.java     ← Sprint 1 Track A 已 ship 工厂端首页布局
frontend/.../screens/factory-admin/home/HomeLayoutEditorScreen.tsx ← Sprint 1 Track A
```

---

## §4 Day-by-Day 执行计划

### Day 1-2 — WorkflowVisualizer 组件抽象 (RN + Vue)

#### Day 1 — RN 组件

**任务**:

1. **起 worktree**:
   ```bash
   cd C:\Users\Steve\my-prototype-logistics
   git worktree add ../my-prototype-logistics-sprint2-track-g feature/sprint2-track-g-ux-nav
   cd ../my-prototype-logistics-sprint2-track-g
   ```

2. **读 `UX_BORROW.md` §A-1 + §F-1 (示意图)** — 看宏见 HD 帧的实际节点流程图样子

3. **WorkflowVisualizer.tsx props 设计**:
   ```typescript
   export interface WorkflowNode {
     id: string;
     label: string;              // 中文 "待审"
     status: 'PENDING' | 'IN_PROGRESS' | 'DONE';  // 决定颜色
     count: number;              // 数量徽章
     onPress?: () => void;       // 点击进入对应列表
     onLongPress?: () => void;   // 长按 = AI 触发 (entryContext)
   }

   export interface WorkflowVisualizerProps {
     nodes: WorkflowNode[];
     orientation?: 'horizontal' | 'vertical';   // 默认 horizontal
     aiTriggerEnabled?: boolean;                // 是否显示 AI 入口按钮
     onAITrigger?: () => void;                  // AI 按钮点击
     title?: string;                            // 卡片标题 "今日销售工作流"
   }
   ```

4. **WorkflowNode 子组件**:
   - 圆形节点 + 状态色 (Cretas Neo Minimal 调色板, **不抄宏见 raw 粉色**):
     - PENDING: 浅橙 `#FFE4B5` (温和提醒, 不刺眼)
     - IN_PROGRESS: 浅绿 `#D4EDDA`
     - DONE: 浅蓝 `#D1ECF1`
   - count badge 右上角, 大数字 > 999 显示 "999+"
   - label 居中
   - 长按 / 点击手势 (用 `react-native-gesture-handler`)

5. **WorkflowConnector 连线**:
   - SVG arrow 用 `react-native-svg`
   - 水平方向: 节点 1 → arrow → 节点 2 → ... 
   - 垂直方向: 节点 1 ↓ ↓ 节点 2
   - 移动端 portrait 优先 horizontal, 但允许 wrap

6. **AI 触发按钮**:
   - 卡片右上角 "💬" 图标 + "跟 AI 说"
   - 点击调 onAITrigger callback (调用方决定怎么跳 AIChat)

**DoD Day 1**: RN 组件可以 `<WorkflowVisualizer nodes={[...]}/>` 渲染, 在 Expo demo screen 看到 3 节点流程图.

#### Day 2 — Vue 组件

**任务**:

1. **WorkflowBar.vue 等价组件给 web-admin**:
   ```vue
   <script setup lang="ts">
   import { computed } from 'vue';

   interface WorkflowNode {
     id: string;
     label: string;
     status: 'PENDING' | 'IN_PROGRESS' | 'DONE';
     count: number;
   }

   const props = defineProps<{
     nodes: WorkflowNode[];
     aiTriggerEnabled?: boolean;
     title?: string;
   }>();

   const emit = defineEmits<{
     'node-click': [nodeId: string];
     'ai-trigger': [];
   }>();
   </script>

   <template>
     <div class="workflow-bar">
       <div class="title">{{ title }}</div>
       <div class="nodes">
         <WorkflowNode v-for="(node, i) in nodes" :key="node.id"
                       :node="node"
                       @click="emit('node-click', node.id)" />
       </div>
       <button v-if="aiTriggerEnabled" @click="emit('ai-trigger')">💬 跟 AI 说</button>
     </div>
   </template>
   ```

2. **颜色 token 注册到 Cretas Neo Minimal 设计系统** — 全部走 design token, 不要 hardcode 色值:
   - 找 `web-admin/src/styles/design-tokens.scss` (或类似), 加 workflow-pending / workflow-in-progress / workflow-done 三个变量
   - RN 端用 `frontend/CretasFoodTrace/src/theme/colors.ts` 同步

3. **Storybook / RN demo screen 验证 3 种节点配置**:
   - 3 节点 (待审 / 进行 / 完成)
   - 5 节点 (待审 / 已审 / 待生产 / 生产中 / 完成)
   - 1 节点 (空状态)

**DoD Day 2**: 组件可 `<WorkflowVisualizer nodes={[...]}/>` (RN) 和 `<WorkflowBar :nodes="[...]"/>` (Vue) 渲染.

---

### Day 3 — WorkflowStatsController 后端 API

#### 任务

1. **5 个 endpoint** (`WorkflowStatsController`):
   ```java
   @RestController
   @RequestMapping("/api/mobile/{factoryId}/workflow-stats")
   public class WorkflowStatsController {

       @GetMapping("/sales")
       public ApiResponse<WorkflowStatsDTO> getSalesWorkflowStats(@PathVariable String factoryId) {
           return ApiResponse.success(workflowStatsService.getSalesStats(factoryId));
       }

       @GetMapping("/purchase")  // 同
       @GetMapping("/production")  // 同
       @GetMapping("/finance")  // 同
       @GetMapping("/inventory")  // 同
   }
   ```

2. **WorkflowStatsService.getSalesStats** 实现:
   ```java
   public WorkflowStatsDTO getSalesStats(String factoryId) {
       int pending = salesOrderRepo.countByFactoryIdAndStatus(factoryId, "PENDING_APPROVAL");
       int inProgress = salesOrderRepo.countByFactoryIdAndStatusIn(factoryId, List.of("APPROVED", "IN_PRODUCTION"));
       int done = salesOrderRepo.countByFactoryIdAndStatusIn(factoryId, List.of("SHIPPED", "COMPLETED"));
       return WorkflowStatsDTO.builder()
           .module("sales")
           .nodes(List.of(
               new WorkflowNode("pending", "待审", "PENDING", pending),
               new WorkflowNode("in_progress", "进行中", "IN_PROGRESS", inProgress),
               new WorkflowNode("done", "已完成", "DONE", done)
           ))
           .build();
   }
   ```

3. **缓存策略 (5 分钟 Redis)**:
   ```java
   @Cacheable(value = "workflow-stats", key = "#factoryId + ':sales'")
   public WorkflowStatsDTO getSalesStats(String factoryId) { ... }
   ```
   - 写操作 invalidate: 销售单审批 / 提交 / 完成 时调 `cacheManager.getCache("workflow-stats").evict("F006:sales")`
   - 简单方式: 用 `@CacheEvict(value = "workflow-stats", allEntries = true)` on 关键 service method

4. **5 个 endpoint 全部用相同 pattern**:
   - sales: salesOrderRepo 按 status 统计
   - purchase: purchaseOrderRepo 按 status 统计
   - production: productionPlanRepo 按 status 统计
   - finance: invoiceRecordRepo / paymentRecordRepo 按 status 统计 (复合)
   - inventory: 简化为 "正常 / 低库存 / 过期" 三档

5. **统一响应格式** (`.claude/rules/api-response-handling.md`):
   ```json
   {
     "success": true,
     "data": {
       "module": "sales",
       "nodes": [
         {"id": "pending", "label": "待审", "status": "PENDING", "count": 5},
         {"id": "in_progress", "label": "进行中", "status": "IN_PROGRESS", "count": 12},
         {"id": "done", "label": "已完成", "status": "DONE", "count": 87}
       ],
       "lastRefreshedAt": "2026-05-15T10:30:00Z"
     },
     "message": "操作成功"
   }
   ```

**DoD Day 3**: 5 个 curl 都返回 JSON.

---

### Day 4-5 — RN BentoGrid 接入 (5 角色 HomeScreen)

#### Day 4 — Sales/Purchase/Production HomeScreen

**任务**:

1. **找 5 个角色 HomeScreen 位置** (Sprint 1 Track A 已 ship 工厂端首页 BentoGrid, 你需要参考):
   - `frontend/.../screens/sales/SalesHomeScreen.tsx`
   - `frontend/.../screens/purchase/PurchaseHomeScreen.tsx`
   - `frontend/.../screens/production/ProductionHomeScreen.tsx`
   - `frontend/.../screens/finance/FinanceHomeScreen.tsx`
   - `frontend/.../screens/inventory/InventoryHomeScreen.tsx`

   **降级方案**: 如果某角色没独立 HomeScreen, 共用 `FAHomeScreen` 的 BentoGrid 配置 (Sprint 1 Track A 已修通)

2. **SalesHomeScreen 加流程图卡片**:
   ```typescript
   import { WorkflowVisualizer } from '../../components/workflow';
   import { useWorkflowStats } from '../../hooks/useWorkflowStats';
   import { useNavigation } from '@react-navigation/native';

   const SalesHomeScreen = () => {
     const navigation = useNavigation();
     const { stats, refresh } = useWorkflowStats('sales');

     useFocusEffect(useCallback(() => { refresh(); }, []));

     return (
       <BentoGrid>
         {/* 1x2 大卡片 - 流程图 */}
         <BentoCard size="1x2">
           <WorkflowVisualizer
             title="今日销售工作流"
             nodes={stats.nodes.map(n => ({
               ...n,
               onPress: () => navigation.navigate('SalesOrderList', { statusFilter: n.id }),
               onLongPress: () => navigation.navigate('AIChat', {
                 entryContext: { module: 'sales', node: n.id }
               })
             }))}
             aiTriggerEnabled={true}
             onAITrigger={() => navigation.navigate('AIChat', { entryContext: { module: 'sales' } })}
           />
         </BentoCard>
         {/* 其他卡片 */}
       </BentoGrid>
     );
   };
   ```

3. **同样接入 PurchaseHomeScreen + ProductionHomeScreen**

4. **节点点击 → 列表 filter 预填**:
   - `navigation.navigate('SalesOrderList', { statusFilter: 'pending' })`
   - SalesOrderListScreen 接 `route.params.statusFilter` 自动预填

**DoD Day 4**: 3 个 RN HomeScreen 加流程图卡片, 点击节点跳列表.

#### Day 5 — Finance + Inventory + 自动刷新

**任务**:

1. **FinanceHomeScreen + InventoryHomeScreen 同样接入**

2. **节点 count 自动刷新**:
   - `useFocusEffect` 触发 refresh
   - 拉下来刷新 (RefreshControl)
   - 后端缓存 5 分钟, 客户端不需要太频繁

3. **5 角色 demo**:
   - 创建 5 个测试账号 (sales_mgr / purchase_mgr / production_mgr / finance_mgr / inventory_mgr)
   - 5 个账号分别登录, 验证 HomeScreen 流程图卡片

**DoD Day 5**: 5 个 RN HomeScreen 流程图卡片可见可点击.

---

### Day 6-7 — Web-Admin 5 模块顶部 bar 接入

#### Day 6 — 销售/采购/生产

**任务**:

1. **`web-admin/src/views/sales/SalesOrderListView.vue` 顶部加 WorkflowBar**:
   ```vue
   <script setup lang="ts">
   import { WorkflowBar } from '@/components/workflow';
   import { useWorkflowStats } from '@/composables/useWorkflowStats';
   import { useRouter } from 'vue-router';

   const { stats, refresh } = useWorkflowStats('sales');
   const router = useRouter();

   const handleNodeClick = (nodeId: string) => {
     // 用 Pinia tab store 切换 filter (Sprint 1 Web-Admin 累积 tab 已 ship, 如果)
     // 或者直接 router.push
     router.push({ path: '/sales/orders', query: { status: nodeId } });
   };

   const handleAITrigger = () => {
     // 打开 AIChat drawer 或跳 AI 页面
     // ...
   };
   </script>

   <template>
     <div class="page-with-workflow">
       <WorkflowBar
         :nodes="stats.nodes"
         :ai-trigger-enabled="true"
         title="销售订单工作流"
         @node-click="handleNodeClick"
         @ai-trigger="handleAITrigger"
       />
       <!-- 现有列表 -->
       <SalesOrderTable />
     </div>
   </template>
   ```

2. **同样接入 PurchaseOrderListView + ProductionPlanListView**

3. **节点点击 → 列表 filter 联动**:
   - 用 query string `?status=pending` 或 Pinia tab store
   - 列表表格 watch query, 自动 filter

**DoD Day 6**: 3 个 web-admin 列表页顶部有 bar.

#### Day 7 — Finance + Inventory + AI 增强

**任务**:

1. **FinanceListView + InventoryListView 接入**

2. **节点点击 → 列表 filter 联动 (Pinia tab store)**:
   - 如果 Sprint 1 Web-Admin 累积 tab store 已 ship, 用它
   - 否则用 `useRoute().query.status` watch

3. **AI 增强按钮入口**:
   - WorkflowBar 右侧 "💬 跟 AI 说" 按钮
   - 点击打开 AIChat drawer (web-admin 已有 AIChat 组件? 如有, 调它; 没有则跳新页面)

**DoD Day 7**: 5 个 web-admin 列表页顶部都有 bar + AI 入口可点.

---

### Day 8 — AI 触发逻辑

#### 任务

1. **流程节点 onPress 长按 / AI 按钮点击 → AIChat 携带 entryContext**:
   ```typescript
   navigation.navigate('AIChat', {
     entryContext: { module: 'sales', node: 'pending', factoryId: 'F006' }
   });
   ```

2. **AIChat 看到 context 自动提示**:
   - 修改 `AIChatScreen.tsx` (RN) 或 AIChat drawer (Vue):
     ```typescript
     useEffect(() => {
       const ctx = route.params?.entryContext;
       if (ctx) {
         const greeting = buildContextualGreeting(ctx);
         // greeting 例: "你想批一下这 5 单待审吗?"
         appendBotMessage(greeting);
       }
     }, []);
     ```

3. **跟 Sprint 1 Track A (sessionId 通了) 配合验证多轮**:
   - 多轮对话: "好的, 帮我批 SO-001 + SO-002" → AIChat 调 sales_order_approve Tool 2 次

4. **如果 Sprint 1 Track A sessionId 还有 bug** (organizer 联系):
   - 降级到 single-turn (entryContext 只用一次)
   - 留 TODO 等 Track A fix 后补 multi-turn

**DoD Day 8**: AIChat entry context 验证, 多轮或 single-turn 跑通.

---

### Day 9 — 调优 + Bug 修

#### 任务

1. **节点连线 svg edge cases**:
   - 1 节点 → 不显示连线
   - 5 节点 horizontal → 移动端 portrait wrap 到 2 行
   - 节点 label 过长 → truncate `...`

2. **颜色对比无障碍验证**:
   - 用 WCAG 工具检查 PENDING / IN_PROGRESS / DONE 三色 + 白底 对比度 >= 4.5:1
   - Cretas Neo Minimal 调色板已经 WCAG compliant, 你只需验证

3. **count badge 大数字 (>999) 显示**:
   - 999 显示 "999"
   - 1000-9999 显示 "1K+" or "9K+"
   - > 9999 显示 "9K+"

4. **空状态**:
   - 后端返回 `nodes: []` → 卡片显示 "暂无工作流数据" + 引导按钮

5. **加载状态**:
   - 第一次加载显示 Skeleton (Sprint 1 Track UX-M3 已抽象? 如有用它)
   - 否则用 ActivityIndicator

**DoD Day 9**: edge cases 全 cover, 5 角色 + 5 web view 自验一遍.

---

### Day 10 — Demo + PR

#### 任务

1. **Demo 录** (2-3 分钟):
   1. 销售员登陆 (F006) → 首页流程图 BentoGrid 卡片可见
   2. 点 "待审 5" 节点 → 跳销售单列表, 自动过滤 PENDING_APPROVAL
   3. 点单审批
   4. 回首页, 节点 count 自动刷新 (5 → 4)
   5. 长按 "待审" 节点 → 跳 AIChat, 自动问 "你想批这 4 单吗?"
   6. AI 多轮 "帮我批 SO-001 和 SO-002" → 完成
   7. Web-Admin 同样路径演示
   8. Finance 角色: 看到流程图 "待开票 / 待回款 / 已完成"

2. **推 PR**:
   ```bash
   git push -u origin feature/sprint2-track-g-ux-nav
   gh pr create --title "[Sprint2-G] U-NAV-1 业务流程图导航" --body "..."
   ```

   PR body 含:
   - 涉及文件清单 (RN 组件 + Vue 组件 + Java Controller + 5 HomeScreen + 5 View)
   - 测试方式 (storybook + E2E demo)
   - 风险点 (AIChat entryContext 强依赖 Sprint 1 Track A sessionId / 5 HomeScreen 接入若 Sprint 1 未 ship 用 FAHome 降级)
   - 跟 Sprint 1 哪些 PR 依赖

**DoD Day 10**: PR + demo + STATUS 10 段完整.

---

## §5 关键参考文档

| 路径 | 用途 |
|---|---|
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\UX_BORROW.md` §A-1 + §F-1 | UX 模式定义 + 示意图 + 战略意义 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\SPRINT_2_PLAN.md` §5.3 | Day-by-day 来源 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\01-客户档案\NUMBERING_MAP.md` | U-NAV-1 编号 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\TRACK_A_BRIEF.md` | Sprint 1 Canvas Bento 已 ship 工厂端首页 (你参考它的 BentoGrid pattern) |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\api-response-handling.md` | 统一响应 + 缓存 |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\typescript-type-safety.md` | 禁 `as any` |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\concurrent-edit-safety.md` HARD | 6 chat 并行 commit 安全 |

---

## §6 接口契约 (Interface Contracts)

### 后端 → 前端 API

**GET /api/mobile/{factoryId}/workflow-stats/{module}**

```typescript
// module: 'sales' | 'purchase' | 'production' | 'finance' | 'inventory'

// Response
{
  success: true,
  data: {
    module: string,
    nodes: Array<{
      id: string,           // "pending" / "in_progress" / "done" / "approved" etc
      label: string,        // 中文 "待审" / "进行中" / "已完成"
      status: 'PENDING' | 'IN_PROGRESS' | 'DONE',
      count: number
    }>,
    lastRefreshedAt: string  // ISO 8601
  },
  message: "操作成功"
}
```

### 跟 AIChat 的契约

```typescript
// 节点点击 / AI 按钮触发
navigation.navigate('AIChat', {
  entryContext: {
    module: 'sales',
    node: 'pending',
    factoryId: 'F006'
  }
});

// AIChat (Sprint 1 Track A 已 ship) 接收 entryContext
// 自动 prompt: "你想批一下这 X 单待审吗?"
```

### Sprint 1 依赖

| Sprint 1 提供 | 你怎么用 |
|---|---|
| Track A AIChat sessionId 多轮 (PR #651) | AIChat entryContext 携带多轮 context |
| Track A BentoGrid Editor (Sprint 1) | 你参考 BentoCard 1x2 pattern |
| 5 角色 HomeScreen (项目本身已有, 或 FAHome 降级) | 接入流程图卡片 |

---

## §7 PR / Status Update 流程

### 每日 STATUS 更新

文件: `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\STATUS\TRACK_G_STATUS.md`

格式 (每天追加):
```markdown
## Day N (YYYY-MM-DD)
- ✅ 完成: X / Y / Z
- 🟡 进行中: A
- ❌ Blocker: B (需 organizer 协调)
- 明日计划: C / D
```

### PR 流程

推荐分 2 sub-PR:
- `[Sprint2-G-1] U-NAV-1 组件抽象 + 后端 API` (Day 1-3 末)
- `[Sprint2-G-2] U-NAV-1 5 模块接入 + AI 触发` (Day 4-10)

或者 1 个大 PR `[Sprint2-G] U-NAV-1 业务流程图导航` Day 10 一次推.

### 并发安全 commit

```bash
git commit -m "feat: WorkflowVisualizer RN 组件" -- frontend/CretasFoodTrace/src/components/workflow/WorkflowVisualizer.tsx frontend/CretasFoodTrace/src/components/workflow/WorkflowNode.tsx
```

---

## §8 不要做 (Do Not Do)

### 严格禁止

1. **不要抄宏见 raw 粉色** — Cretas 是 Neo Minimal, 用温和柔色 (浅橙/浅绿/浅蓝)
2. **不要 hardcode 色值** — 全部走 design token (RN colors.ts / Vue design-tokens.scss)
3. **不要改 ownership 外的文件** (§3)
4. **不要用 `as any`** — 路由 `useRoute<RouteProp<...>>()`
5. **不要并发改同一文件** — 用 git worktree, `git commit -- F1 F2`
6. **不要修改 5 个 HomeScreen 共用的 BentoGrid 组件** — 你加 BentoCard 不改 BentoGrid 本身
7. **不要重写 AIChat** — 你只加 entryContext 入口, AIChat 本身是 Sprint 1 Track A ship 的
8. **不要 hardcode 模块列表** — 5 个 module 名字应该是 enum / constant

---

## §9 验收清单

### 功能验收

- [ ] **组件**: RN WorkflowVisualizer 支持 horizontal/vertical, 1-5 节点, 颜色 + count badge
- [ ] **组件**: Vue WorkflowBar 等价实现, design token 复用 RN
- [ ] **后端**: 5 个 WorkflowStatsController endpoint 跑通, 缓存 5min
- [ ] **后端**: 写操作 (审批 / 创建 / 完成) 触发 cache evict
- [ ] **RN**: 5 角色 HomeScreen 加流程图 BentoCard
- [ ] **RN**: 节点点击 → 跳列表 + filter 预填
- [ ] **RN**: 长按节点 → AIChat 携带 entryContext
- [ ] **Web**: 5 模块 ListView 顶部加 WorkflowBar
- [ ] **Web**: 节点点击 → query string filter
- [ ] **Web**: AI 按钮点击 → 打开 AIChat drawer
- [ ] **AI**: AIChat 看到 entryContext 自动 contextual greeting
- [ ] **AI**: 多轮验证 (依赖 Sprint 1 Track A)

### UX 验收

- [ ] **设计**: 颜色对比 WCAG 4.5:1
- [ ] **响应**: 移动端 portrait 1-5 节点都合理 wrap
- [ ] **加载**: Skeleton + 拉下刷新
- [ ] **空状态**: 节点为空显示引导
- [ ] **大数字**: count > 999 显示 "999+" or "1K+"

### 销售红线验收

- [ ] **红线**: "每个业务模块顶部都有流程图导航"
- [ ] **红线**: "首页 BentoGrid 含 5 角色专属工作流卡片"
- [ ] **红线**: "AI 触发节点 — 一句话进入待审列表"

### 技术验收

- [ ] PR merged 到 main
- [ ] 无新增 `as any`
- [ ] 无新增 `catch (error: any)`
- [ ] design token 走通, 0 hardcode 色值
- [ ] E2E demo 视频录制

---

## §10 客户场景对照

### 客户期望

**六扇门 F006 (卤制品工厂)** 希望:
1. **新员工 onboarding** — 新销售员一打开 App 就知道今天该干啥
2. **老板一眼看全貌** — "今天有 5 单待审 12 单进行 87 单完成"
3. **AI 触发节点** — 老板长按 "待审 5" → AIChat "你想全批吗?" → 一句话搞定

### Cretas 的差异化卖点

**宏见 ERP 范式**: 12 模块菜单 + 表格 + 行级操作 → 新员工要学 1 周才知道导航

**Cretas Sprint 2 完成后**:
- ✅ 首页流程图 BentoCard → 新员工 1 分钟懂业务流
- ✅ 节点 + AI 触发 → 老板能一句话搞定
- ✅ Web + RN 统一体验 → 跨设备一致

### 跟其他 Chat 的串联

```
Chat F (N48 样品流程) — 你 Workflow 节点显示 "样品 DRAFT 5 / REVIEWING 3 / APPROVED 12"
Chat E (N31 销售→采购) — 你 Sales 节点点 "已审 12" → 看到缺料分析 chain-card
Chat H (UX-A2 行末操作) — 列表页跟你的 Bar 在同页面, 不冲突
Chat I (UX-A3 Sticky Footer) — 列表页底部, 跟你顶部 Bar 上下夹击
Chat J (P-FIN-1 财务审核) — 财务 Workflow 节点显示 "待审 / 已审 / 标红"
```

---

## 附录: 关键命令速查

### 启动开发环境

```powershell
# 后端 Java (10010)
cd C:\Users\Steve\my-prototype-logistics\backend\java\cretas-api
mvn spring-boot:run

# Web-Admin (Vue)
cd C:\Users\Steve\my-prototype-logistics\web-admin
npm run dev

# RN 前端 (3010)
cd C:\Users\Steve\my-prototype-logistics\frontend\CretasFoodTrace
npm start
```

### Git Worktree

```powershell
cd C:\Users\Steve\my-prototype-logistics
git worktree add ../my-prototype-logistics-sprint2-track-g feature/sprint2-track-g-ux-nav
cd ../my-prototype-logistics-sprint2-track-g
```

### 安全 Commit

```powershell
git commit -m "feat: WorkflowVisualizer RN + Vue 组件" -- frontend/CretasFoodTrace/src/components/workflow/WorkflowVisualizer.tsx web-admin/src/components/workflow/WorkflowBar.vue
```

---

**Brief 结束。Day 1 开始干活。第一件事: 创建 STATUS 文件 + 启动 worktree, 然后读 UX_BORROW.md §A-1 + §F-1 设计 WorkflowVisualizer props。**