# TRACK H BRIEF — Sprint 2: UX-A2 行末"操作 ▾"下拉

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **Sprint 2 chat (基于 Sprint 1 已完成 ASAP)**
> **Brief 来源**: `SPRINT_2_PLAN.md` §5.4 (Chat H — UX-A2 10d 名义)
> **接收方**: Chat H (Sprint 2 worker)
> **派发方**: Chat 1 (Organizer)
> **派发日期**: 2026-05-15
> **预期完成**: ~5-6 工作日 (名义 10d, Claude 加速 ~1.7x)
> **PR 命名**: `[Sprint2-H] U-ACT-1 行末操作下拉`
> **STATUS 文件**: `宏见竞品分析/04-最终决策/STATUS/TRACK_H_STATUS.md`
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

### 当前业务背景

**客户**: 六扇门 (F006) 卤制品工厂 — ASAP 1.5 月交付

**Sprint 2 在哪**:
- **Sprint 1 (Week 6-7) 已完成 ASAP** — 6 个 track 全 ship + main 已合并
- **Sprint 2 (Week 8-10) 现在启动** — 6 个 worker chat 并行

**Sprint 2 UX 三件套** (你是其中之一):
- Chat G: UX-A1 流程图导航 — 首页 + 列表顶部
- **你 Chat H**: UX-A2 行末操作下拉 — 列表行末 BottomSheet
- Chat I: UX-A3 Sticky Footer 实时合计 — 列表底部固定栏

**列表页全面改造的三件套** — 完成后 Cretas 列表 UX 立刻和宏见对齐, 客户立即感知。

### 你是谁

**你 = Chat H = Sprint 2 worker**。Sprint 2 有 6 个并行 chat:
- Chat E: N31 销售→采购自动分流 (4d)
- Chat F: N48 研发样品→BOM→报价 (5d)
- Chat G: UX-A1 业务流程图导航 (10d)
- **Chat H (你)**: UX-A2 行末操作下拉 (10d) RN + Vue 全栈
- Chat I: UX-A3 Sticky Footer 实时合计 (7d)
- Chat J: P-FIN-1 采购财务审核 (3d)

### 沟通方式

- **不要在本 chat 跟 organizer 战略讨论** — 战略已定, 你只执行
- **每日在 STATUS 文件追加 1 段** (格式见 §7)
- **完成一个 sub-task → 推 PR 不要等 Day 10**
- **碰到 blocker 立即在 STATUS 报**

---

## §2 任务范围与工时

### 单项目 (U-ACT-1)

| 项目 | N# 编号 | 工时名义 | 工时加速 | 优先级 | 客户感知 |
|---|---|---|---|---|---|
| **行末"操作 ▾"下拉收纳次要动作** | U-ACT-1 (UX_BORROW.A-2) | 10d | ~6d | P0 ⭐⭐⭐ | 列表行不再拥挤, 长按 / 行末按钮展开 8-14 动作 + AI 入口 |

### 客户原话证据

**来源**: 宏见 UI 审计 UX_BORROW.md A-2 ⭐⭐⭐ Top ROI

**宏见做法**: 列表行末一个"操作 ▾"按钮, 点开 8-14 个次要动作 (转生产/转外购/退货/调拨/打印/复制/锁定 / ...)。**主行只显示 4-6 个 chip 状态**。

**Cretas 现状**: ⚠️ 移动端用 long press / swipe 但 8+ 选项 BottomSheet **未抽象**; Web-Admin 散用。

**HD 帧证据**: `000122` 物料需求页 + `001435` 库存查询 + `001151` 销售订单 — 所有列表都这么做。

### Cretas 怎么用

- **移动 App (RN)**: 长按行 → BottomSheet 显示 8-10 个动作 (已有 gesture handler, 缺统一抽象)
- **Web-Admin (Vue)**: 抽 `<RowActionMenu items={[]}/>` 组件 (element-plus el-dropdown 包装)
- **AI 增强**: BottomSheet 顶部 "💬 跟 AI 说" 入口 — "我要把这单转成生产"

### v3 销售红线 (Sprint 2 解禁)

完成本项目后, 销售可以说:
- ✅ "列表行末操作 ▾, 主行只显示状态 chip"
- ✅ "8 个列表全部接入 BottomSheet (RN) + 下拉菜单 (Vue)"
- ✅ "BottomSheet 顶部 AI 入口 — 一句话执行动作"

### 工时不达标怎么办

- 名义 10d 上限。Claude 加速 ~1.7-2x → 实际预期 5-6 工作日
- 如果工时 >> 1.5 倍名义 (例如超过 14d), 立即在 STATUS 报 organizer
- Organizer 会决定: 减 scope (先 5 list 接入剩余推到 Sprint 3) / 拉外援

---

## §3 文件 Ownership (防冲突)

### 你的 (Chat H 独占, 你可以随便改)

```
frontend/CretasFoodTrace/src/
├── components/list/                                     ← 新建目录 (你跟 Chat I 共享, 但不同文件)
│   ├── RowActionBottomSheet.tsx                        ← NEW (你)
│   └── index.ts                                         ← 你加导出
├── hooks/
│   └── useRowActions.ts                                ← NEW (你)
└── types/
    └── rowActions.ts                                    ← NEW

web-admin/src/
├── components/list/                                     ← 新建 (你跟 Chat I 共享, 但不同文件)
│   ├── RowActionMenu.vue                               ← NEW (你)
│   └── index.ts                                         ← 你加导出
├── composables/
│   └── useRowActions.ts                                ← NEW (你)
└── types/
    └── rowActions.ts                                    ← NEW
```

### 修改 (改前确认其他 chat 没动)

RN 8 个 list screen:
```
frontend/CretasFoodTrace/src/screens/
├── sales/SalesOrderListScreen.tsx                      ← 接入 BottomSheet
├── purchase/PurchaseOrderListScreen.tsx                ← 同
├── production/ProductionPlanListScreen.tsx             ← 同
├── inventory/InventoryListScreen.tsx                   ← 同
├── shipment/ShipmentListScreen.tsx                     ← 同
├── return/ReturnOrderListScreen.tsx                    ← 同
├── transfer/TransferListScreen.tsx                     ← 同
└── wastage/WastageListScreen.tsx                       ← 同
```

Web-Admin 8 个 list view:
```
web-admin/src/views/
├── sales/SalesOrderListView.vue                        ← 行末加 操作 ▾
├── purchase/PurchaseOrderListView.vue                  ← 同
├── production/ProductionPlanListView.vue               ← 同
├── inventory/InventoryListView.vue                     ← 同
├── shipment/ShipmentListView.vue                       ← 同
├── return/ReturnOrderListView.vue                      ← 同
├── transfer/TransferListView.vue                       ← 同
└── wastage/WastageListView.vue                         ← 同
```

### 共享只读 (改之前必须 ping organizer)

```
backend/.../entity/BaseEntity.java
backend/.../service/impl/IntentExecutorServiceImpl.java
backend/.../ai/tool/AbstractBusinessTool.java
frontend/.../services/api/aiApiClient.ts
frontend/.../store/* (canViewPrice store 来自 Sprint 1 Track C)
CLAUDE.md
.claude/rules/*
```

### 别 chat 的 (绝对不准碰)

- Chat E: `backend/.../service/shortage/`, `frontend/.../screens/sales/SalesOrderShortageReviewScreen.tsx`
- Chat F: `backend/.../entity/sample/`, `frontend/.../screens/rd/`
- Chat G: `frontend/.../components/workflow/`, `web-admin/.../components/workflow/`
- Chat I: `frontend/.../components/list/StickyFooterSummary.tsx`, `web-admin/.../components/list/TableFooter.vue`
- Chat J: `backend/.../service/purchase/PurchaseOrderApprovalFlow.java`

⚠️ **Chat I 跟你共享 `components/list/` 目录但不同文件** — 改前 git status 看 Chat I 有没有动同一文件。

### Sprint 1 已 ship 你强依赖

```
frontend/.../store/canViewPriceStore.ts                  ← Sprint 1 Track C RBAC 审计 ship
  └─ useRowActions 根据 canViewPrice 过滤价格相关 action

frontend/.../services/printService.ts                    ← Sprint 1 Track C 单据打印 PDF ship
  └─ "打印 PDF" action 调它

frontend/.../services/api/aiApiClient.ts                 ← AIChat 入口
  └─ BottomSheet "跟 AI 说" 调它
```

---

## §4 Day-by-Day 执行计划

### Day 1-2 — RowActionBottomSheet (RN) + RowActionMenu (Vue) 抽象

#### Day 1 — RN BottomSheet

**任务**:

1. **起 worktree**:
   ```bash
   cd C:\Users\Steve\my-prototype-logistics
   git worktree add ../my-prototype-logistics-sprint2-track-h feature/sprint2-track-h-ux-act
   cd ../my-prototype-logistics-sprint2-track-h
   ```

2. **RowActionBottomSheet.tsx props 设计**:
   ```typescript
   export interface RowAction {
     id: string;                          // 唯一 id, e.g. "convert-to-production"
     icon: string;                        // emoji or material icon name
     label: string;                       // "转生产任务"
     onPress: () => void;
     danger?: boolean;                    // 红色 (如 "取消订单" "删除")
     disabled?: boolean;
     disabledReason?: string;             // 禁用原因 (悬浮提示)
     aiHint?: string;                     // 自然语言映射 "我要把这单转成生产"
     requiresConfirm?: boolean;           // 是否需要二次确认 (危险操作)
     priceRelated?: boolean;              // 是否价格相关 (仓管角色看不到)
   }

   export interface RowActionBottomSheetProps {
     actions: RowAction[];
     aiTriggerEnabled?: boolean;
     aiTriggerLabel?: string;             // 默认 "💬 跟 AI 说..."
     onAITrigger?: () => void;
     visible: boolean;
     onClose: () => void;
     title?: string;                       // BottomSheet 标题 (e.g. "销售单 SO-001")
   }
   ```

3. **BottomSheet 顶部固定 AI 入口** (按 UX_BORROW §F-2 示意图):
   - 第一个 list item: "💬 跟 AI 说..." (灰底, 突出)
   - 然后 divider
   - 然后 8-14 个 action

4. **手势触发**:
   - 长按 row → BottomSheet 弹
   - 或行末 swipe action button → BottomSheet 弹
   - 用 `react-native-gesture-handler`

5. **action button 渲染**:
   - icon (emoji or material) + label
   - danger 用红色 + 不规则放在底部
   - disabled 灰色 + 不可点击, 长按显示 disabledReason

6. **底部 sheet 动画** (`react-native-reanimated` 或 `@gorhom/bottom-sheet`):
   - 已有 BottomSheet 抽象? grep `BottomSheet`. 如有, 复用基础组件, 你只加 RowAction 配置层

**DoD Day 1**: RN BottomSheet 可 storybook 渲染 10 个动作 + AI 入口.

#### Day 2 — Vue RowActionMenu

**任务**:

1. **RowActionMenu.vue — element-plus el-dropdown 包装**:
   ```vue
   <script setup lang="ts">
   import { ElDropdown, ElDropdownMenu, ElDropdownItem } from 'element-plus';

   export interface RowAction {
     id: string;
     icon: string;                        // Element Plus icon name 或 emoji
     label: string;
     danger?: boolean;
     disabled?: boolean;
     disabledReason?: string;
     aiHint?: string;
     requiresConfirm?: boolean;
     priceRelated?: boolean;
   }

   const props = defineProps<{
     actions: RowAction[];
     aiTriggerEnabled?: boolean;
     aiTriggerLabel?: string;
     title?: string;
   }>();

   const emit = defineEmits<{
     'action-click': [actionId: string];
     'ai-trigger': [];
   }>();
   </script>

   <template>
     <ElDropdown trigger="click" @command="emit('action-click', $event)">
       <el-button>操作 <i class="el-icon-arrow-down" /></el-button>
       <template #dropdown>
         <ElDropdownMenu>
           <ElDropdownItem v-if="aiTriggerEnabled" divided
                             @click="emit('ai-trigger')">
             💬 {{ aiTriggerLabel || '跟 AI 说...' }}
           </ElDropdownItem>
           <ElDropdownItem v-for="action in actions"
                             :key="action.id"
                             :command="action.id"
                             :disabled="action.disabled"
                             :class="{ 'is-danger': action.danger }">
             {{ action.icon }} {{ action.label }}
           </ElDropdownItem>
         </ElDropdownMenu>
       </template>
     </ElDropdown>
   </template>
   ```

2. **行末加 "操作 ▾" 按钮** — el-dropdown 自带

3. **顶部 "跟 AI 说" 入口** (divider 分隔)

4. **Storybook / 测试**:
   - 创建一个 demo page `web-admin/src/views/_dev/RowActionMenuDemo.vue`
   - 渲染 10 个动作

**DoD Day 2**: 组件 storybook 跑通 (RN + Vue 都能渲染 10 actions + AI 入口).

---

### Day 3 — useRowActions hook (RN + Vue) + 公共动作配置

#### 任务

1. **抽公共动作 (entityType 决定可用 action)**:
   ```typescript
   // types/rowActions.ts
   export type EntityType =
     | 'salesOrder' | 'purchaseOrder' | 'productionPlan'
     | 'inventory' | 'shipment' | 'returnOrder' | 'transfer' | 'wastage';

   export const COMMON_ACTIONS = {
     CONVERT_TO_PRODUCTION: { id: 'convert-to-production', icon: '📋', label: '转生产任务', aiHint: '我要把这单转成生产' },
     CONVERT_TO_PURCHASE: { id: 'convert-to-purchase', icon: '🛒', label: '转采购单', aiHint: '我要采购这些料' },
     CONVERT_TO_OUTSOURCE: { id: 'convert-to-outsource', icon: '📦', label: '转外购', aiHint: '我要找外购' },
     RETURN: { id: 'return', icon: '↩️', label: '退货', danger: false, aiHint: '我要退这单' },
     TRANSFER: { id: 'transfer', icon: '🔄', label: '调拨', aiHint: '我要调拨' },
     PRINT_PDF: { id: 'print-pdf', icon: '📄', label: '打印 PDF', aiHint: '打印这单' },
     COPY: { id: 'copy', icon: '📑', label: '复制', aiHint: '复制这单' },
     LOCK: { id: 'lock', icon: '🔒', label: '锁定', aiHint: '锁住这单不让人改' },
     UNDO_APPROVAL: { id: 'undo-approval', icon: '↩️', label: '撤销审批', danger: true, requiresConfirm: true, aiHint: '撤销这单的审批' },
     CANCEL: { id: 'cancel', icon: '❌', label: '取消订单', danger: true, requiresConfirm: true, aiHint: '取消这单' },
     DELETE: { id: 'delete', icon: '🗑️', label: '删除', danger: true, requiresConfirm: true },
     EDIT_PRICE: { id: 'edit-price', icon: '💲', label: '修改单价', priceRelated: true, aiHint: '改这单的价格' },
     VIEW_PRICE_HISTORY: { id: 'view-price-history', icon: '📈', label: '价格历史', priceRelated: true },
     // ... 14+ actions
   };
   ```

2. **`useRowActions(entityType, entity, role?)` 实现**:
   ```typescript
   // hooks/useRowActions.ts (RN)
   import { useCanViewPrice } from '../store/canViewPriceStore';

   export const useRowActions = (
     entityType: EntityType,
     entity: { status: string; id: string; canEdit?: boolean }
   ): RowAction[] => {
     const canViewPrice = useCanViewPrice();
     const allActions = ALL_ACTIONS_BY_ENTITY[entityType];

     return allActions
       // 1. 状态过滤
       .filter(action => isActionAvailableForStatus(action.id, entity.status))
       // 2. RBAC 过滤 (仓管角色看不到价格相关)
       .filter(action => !action.priceRelated || canViewPrice)
       // 3. 自定义权限 (后端 ACL 已经过滤过的, 这里只是 UI 层 double-check)
       .map(action => ({
         ...action,
         onPress: () => handleAction(action.id, entity),
         disabled: !entity.canEdit && needsEdit(action.id),
         disabledReason: !entity.canEdit ? '需要编辑权限' : undefined
       }));
   };
   ```

3. **状态机配置** — 每个 entity status 对应可用 action:
   ```typescript
   const SALES_ORDER_STATUS_ACTIONS: Record<string, string[]> = {
     'DRAFT': ['edit', 'submit', 'delete', 'copy'],
     'PENDING_APPROVAL': ['approve', 'reject', 'view-price-history'],
     'APPROVED': ['convert-to-production', 'convert-to-purchase', 'print-pdf', 'undo-approval', 'cancel'],
     'IN_PRODUCTION': ['view', 'print-pdf', 'undo-approval'],
     'SHIPPED': ['view', 'print-pdf', 'return'],
     'COMPLETED': ['view', 'print-pdf', 'copy'],
   };
   ```

4. **RBAC 集成** (Sprint 1 Track C ship 了 `canViewPriceStore`):
   - 仓管角色 (warehouse_manager) → canViewPrice = false → 过滤掉 priceRelated action
   - Sales/Purchase 角色 → canViewPrice = true → 全部 action 可见

5. **AI Hint 字段** — 每个 action 有自然语言映射:
   - "锁定" → aiHint "我要锁住这单不让人改"
   - 用户点 "💬 跟 AI 说" → AIChat 自动提示这 N 个 aiHint 让用户选

6. **单测** (10 状态 × 5 角色 = 50 组合):
   ```typescript
   describe('useRowActions', () => {
     it('销售单 DRAFT + 仓管角色 → 不显示 edit-price', () => {
       const actions = useRowActions('salesOrder', { status: 'DRAFT', id: '1' });
       expect(actions.find(a => a.id === 'edit-price')).toBeUndefined();
     });
     // 50 组合
   });
   ```

**DoD Day 3**: hook 单测 50 组合 PASS.

---

### Day 4-6 — RN 8 个 list screen 接入

#### Day 4 — SalesOrderListScreen + PurchaseOrderListScreen

**任务**:

1. **SalesOrderListScreen.tsx 接入**:
   ```typescript
   import { RowActionBottomSheet } from '../../components/list/RowActionBottomSheet';
   import { useRowActions } from '../../hooks/useRowActions';

   const SalesOrderListScreen = () => {
     const [selectedOrder, setSelectedOrder] = useState<SalesOrder | null>(null);
     const [bottomSheetVisible, setBottomSheetVisible] = useState(false);
     const actions = useRowActions('salesOrder', selectedOrder ?? { status: '', id: '' });

     const handleLongPress = (order: SalesOrder) => {
       setSelectedOrder(order);
       setBottomSheetVisible(true);
     };

     return (
       <>
         <FlatList
           data={orders}
           renderItem={({ item }) => (
             <SalesOrderCard
               order={item}
               onLongPress={() => handleLongPress(item)}
             />
           )}
         />
         <RowActionBottomSheet
           visible={bottomSheetVisible}
           actions={actions}
           aiTriggerEnabled={true}
           title={selectedOrder ? `销售单 ${selectedOrder.code}` : ''}
           onAITrigger={() => {
             setBottomSheetVisible(false);
             navigation.navigate('AIChat', {
               entryContext: { entityType: 'salesOrder', entityId: selectedOrder?.id }
             });
           }}
           onClose={() => setBottomSheetVisible(false)}
         />
       </>
     );
   };
   ```

2. **同样接入 PurchaseOrderListScreen**

3. **每个 list 接入步骤**:
   - 长按 row → BottomSheet 弹
   - action list 用 `useRowActions(entityType, entity)`
   - 跑一遍 demo 验证

#### Day 5 — ProductionPlanList + InventoryList + ShipmentList

#### Day 6 — ReturnOrder + Transfer + Wastage

每个 list 同 Day 4 pattern.

**DoD Day 6**: 8 个 RN list 全部接入.

---

### Day 7-8 — Web-Admin 8 个 list view 接入

#### Day 7 — web-admin 销售 / 采购 / 生产 / 库存 4 个 list

**任务**:

1. **`web-admin/src/views/sales/SalesOrderListView.vue` 行末加 操作 ▾**:
   ```vue
   <script setup lang="ts">
   import { RowActionMenu } from '@/components/list/RowActionMenu';
   import { useRowActions } from '@/composables/useRowActions';
   import { useRouter } from 'vue-router';

   const router = useRouter();
   </script>

   <template>
     <el-table :data="orders">
       <el-table-column prop="code" label="订单号" />
       <el-table-column prop="customerName" label="客户" />
       <!-- ... 状态 chip 列 -->
       <el-table-column label="操作" width="120">
         <template #default="{ row }">
           <RowActionMenu
             :actions="useRowActions('salesOrder', row)"
             :ai-trigger-enabled="true"
             @action-click="(actionId) => handleAction(actionId, row)"
             @ai-trigger="() => openAIChat(row)"
           />
         </template>
       </el-table-column>
     </el-table>
   </template>
   ```

2. **同样接入 purchase / production / inventory**

3. **"打印 PDF" 调 Sprint 1 Track C 单据打印 API**:
   ```typescript
   const handleAction = async (actionId: string, row: SalesOrder) => {
     if (actionId === 'print-pdf') {
       const url = await printService.printSalesOrder(row.id);
       window.open(url, '_blank');
     }
     // ... 其他 action
   };
   ```

#### Day 8 — web-admin 出货 / 退货 / 调拨 / 损耗 4 个 list

**DoD Day 8**: 8 个 web view 全接入.

---

### Day 9 — AI 入口验证

#### 任务

1. **BottomSheet "💬 跟 AI 说" 点击 → AIChat 进入, 携带 entity context**:
   ```typescript
   const handleAITrigger = () => {
     navigation.navigate('AIChat', {
       entryContext: {
         entityType: 'salesOrder',
         entityId: 'SO-001',
         factoryId: 'F006',
         availableActions: actions.map(a => ({ id: a.id, aiHint: a.aiHint }))
       }
     });
   };
   ```

2. **AIChat 看到 entityType + entityId 自动 contextual prompt**:
   - "你想对销售单 SO-001 做什么? 可以试试: '转生产' / '打印' / '锁定'"
   - 自动列出 availableActions 的 aiHint

3. **多轮验证** (依赖 Sprint 1 Track A sessionId):
   - "转生产" → AI 调 sales_order_convert_to_production Tool → 返回结果
   - "再帮我把另一单 SO-002 也转了" → 继续多轮

4. **如果 Sprint 1 Track A sessionId 还有 bug** (organizer 联系):
   - 降级到 single-turn (entryContext 只用一次)

**DoD Day 9**: AI 入口可用.

---

### Day 10 — Demo + PR

#### 任务

1. **Demo 录** (2 分钟):
   - 长按销售单 → BottomSheet 弹出 10 个动作
   - 点 "转采购" → 跳到 Chat E (N31) 的链路 (依赖 Chat E ship)
   - 长按销售单 → 点 "💬 跟 AI 说" → AIChat 自动提示 → "转生产" → 自动执行
   - Web-Admin: 行末点 "操作 ▾" → 同样动作
   - 仓管账号: 不显示价格相关 action (验证 Sprint 1 Track C RBAC 集成)

2. **推 PR**:
   ```bash
   git push -u origin feature/sprint2-track-h-ux-act
   gh pr create --title "[Sprint2-H] U-ACT-1 行末操作下拉" --body "..."
   ```

   PR body 含:
   - 涉及文件清单 (RN + Vue 组件 + hook + 16 个 list 接入)
   - 测试方式 (单测 50 组合 + storybook + E2E demo)
   - 风险点 (RBAC 依赖 Track C / 打印依赖 Track C / AI 入口依赖 Track A sessionId / 转采购依赖 Chat E)
   - 跟 Sprint 1 + Sprint 2 哪些 PR 依赖

**DoD Day 10**: PR + demo + STATUS 10 段完整.

---

## §5 关键参考文档

| 路径 | 用途 |
|---|---|
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\UX_BORROW.md` §A-2 + §F-2 | UX 模式定义 + 示意图 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\SPRINT_2_PLAN.md` §5.4 | Day-by-day 来源 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\01-客户档案\NUMBERING_MAP.md` | U-ACT-1 编号 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\TRACK_C_BRIEF.md` | Sprint 1 RBAC + 单据打印 (你强依赖) |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\TRACK_A_BRIEF.md` | Sprint 1 AIChat sessionId (你 AI 入口) |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\api-response-handling.md` | 统一响应 |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\typescript-type-safety.md` | 禁 `as any` |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\concurrent-edit-safety.md` HARD | 6 chat 并行 commit 安全 |

---

## §6 接口契约 (Interface Contracts)

### RN 组件 props

```typescript
interface RowAction {
  id: string;
  icon: string;
  label: string;
  onPress: () => void;
  danger?: boolean;
  disabled?: boolean;
  disabledReason?: string;
  aiHint?: string;
  requiresConfirm?: boolean;
  priceRelated?: boolean;
}

interface RowActionBottomSheetProps {
  actions: RowAction[];
  aiTriggerEnabled?: boolean;
  aiTriggerLabel?: string;
  onAITrigger?: () => void;
  visible: boolean;
  onClose: () => void;
  title?: string;
}
```

### useRowActions hook

```typescript
function useRowActions(
  entityType: 'salesOrder' | 'purchaseOrder' | ...,
  entity: { status: string; id: string; canEdit?: boolean }
): RowAction[];
```

### Sprint 1 依赖接口

| Sprint 1 提供 | 你怎么用 |
|---|---|
| Track C `canViewPriceStore` (RN + Vue) | useRowActions 过滤 priceRelated action |
| Track C `printService.printSalesOrder/.printPurchaseOrder/...` | "打印 PDF" action 调用 |
| Track A AIChat sessionId 多轮 | AI 入口多轮对话 |

### Sprint 2 其他 Chat 集成

| Chat | 接入点 |
|---|---|
| Chat E (N31) | "转采购" action → 跳到 N31 缺料分析 chain-card |
| Chat F (N48) | "复制样品" action 在样品列表可用 |
| Chat G (UX-A1) | 你的 list 跟 Chat G 的顶部 WorkflowBar 在同页面, 不冲突 |
| Chat I (UX-A3) | 你的 list 底部 Chat I 加 sticky footer, 不冲突 |

---

## §7 PR / Status Update 流程

### 每日 STATUS 更新

文件: `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\STATUS\TRACK_H_STATUS.md`

格式 (每天追加):
```markdown
## Day N (YYYY-MM-DD)
- ✅ 完成: X / Y / Z
- 🟡 进行中: A
- ❌ Blocker: B (需 organizer 协调)
- 明日计划: C / D
```

### PR 流程

推荐分 3 sub-PR:
- `[Sprint2-H-1] U-ACT-1 组件抽象 + hook` (Day 1-3)
- `[Sprint2-H-2] U-ACT-1 RN 8 list 接入` (Day 4-6)
- `[Sprint2-H-3] U-ACT-1 Web 8 list 接入 + AI` (Day 7-10)

或者 1 大 PR Day 10 一次推.

### 并发安全 commit

```bash
git commit -m "feat: RowActionBottomSheet RN 组件" -- frontend/CretasFoodTrace/src/components/list/RowActionBottomSheet.tsx frontend/CretasFoodTrace/src/hooks/useRowActions.ts
```

⚠️ **Chat I 跟你共享 `components/list/` 目录** — commit 前 `git status` 看 Chat I 文件没动.

### Blocker 上报模板

```markdown
## Day N (YYYY-MM-DD)
- ❌ Blocker: Sprint 1 Track C canViewPriceStore 还没 ship
- 影响: RBAC 过滤 priceRelated action 无法验证
- 建议方案: A) 等 Track C ship; B) 用 mock useCanViewPrice = true; C) 留 TODO
- 需要 organizer: 拍板 A/B/C
```

---

## §8 不要做 (Do Not Do)

### 严格禁止

1. **不要 hardcode action list** — 用 `ALL_ACTIONS_BY_ENTITY` 配置, 每个 entity status 对应可用 action
2. **不要忽略 RBAC** — 必须接 Sprint 1 Track C canViewPriceStore, 仓管角色看不到价格相关 action
3. **不要改 ownership 外的文件** (§3)
4. **不要用 `as any`** — TypeScript 严格
5. **不要并发改同一文件** — 用 git worktree, `git commit -- F1 F2`
6. **不要修改 list screen 本身的业务逻辑** — 你只加 BottomSheet trigger, 不动列表 query / filter / sort
7. **不要 Chat I 的 `components/list/` 文件** — 你只加 RowActionBottomSheet.tsx, 不动 StickyFooterSummary.tsx
8. **不要 hardcode action 字符串 ID** — 用 const COMMON_ACTIONS

---

## §9 验收清单

### 功能验收

- [ ] **组件**: RN RowActionBottomSheet 渲染 8-14 actions + AI 入口
- [ ] **组件**: Vue RowActionMenu 等价实现 (el-dropdown)
- [ ] **hook**: useRowActions 50 状态×角色组合单测 PASS
- [ ] **RN**: 8 个 list screen 长按 → BottomSheet
- [ ] **RN**: BottomSheet "打印 PDF" 调 Sprint 1 Track C printService
- [ ] **RN**: 仓管角色看不到 priceRelated action
- [ ] **Web**: 8 个 list view 行末 "操作 ▾" 下拉
- [ ] **AI**: AI 入口跳 AIChat + entryContext + availableActions
- [ ] **AI**: AIChat 自动列出 aiHint 让用户选
- [ ] **AI**: 多轮对话验证 (依赖 Track A sessionId)

### UX 验收

- [ ] **设计**: danger action 红色 + 二次确认
- [ ] **禁用**: disabled action 灰色 + 显示 disabledReason
- [ ] **响应**: BottomSheet 移动端流畅, RowActionMenu PC 流畅

### 销售红线验收

- [ ] **红线**: "列表行末操作 ▾, 主行只显示状态 chip"
- [ ] **红线**: "8 个列表全部接入"
- [ ] **红线**: "BottomSheet 顶部 AI 入口"

### 技术验收

- [ ] PR merged 到 main
- [ ] 无新增 `as any`
- [ ] 无新增 `catch (error: any)`
- [ ] 50 组合单测 PASS
- [ ] E2E demo 视频录制

---

## §10 客户场景对照

### 客户期望

**六扇门 F006 (卤制品工厂)** 希望:
1. **列表行不再拥挤** — 主行 4-6 个 chip 显示状态, 不显示 14 个按钮
2. **8-14 个动作能用** — 转生产 / 转采购 / 退货 / 打印 / 复制 / 锁定 / 撤销审批 / 取消 / 删除
3. **AI 入口在行末** — "我要把这单转成生产" 一句话搞定
4. **仓管看不到价格** — RBAC 严格隔离 (Sprint 1 Track C 已 ship)

### Cretas 的差异化卖点

**宏见 ERP 范式**: 列表行 14 个按钮拥挤, 客户嫌乱; 仓管也能看到价格 (合规风险)

**Cretas Sprint 2 完成后**:
- ✅ 列表行清爽: chip + 行末 "操作 ▾"
- ✅ BottomSheet (RN) / Dropdown (Vue) 收纳 14 动作
- ✅ AI 入口 — 自然语言执行
- ✅ RBAC: 仓管看不到价格动作 (Sprint 1 Track C 集成)

### 跟其他 Chat 的串联

```
你 BottomSheet "转采购" action — 跳到 Chat E (N31) 缺料分析 chain-card
Chat F (N48) 样品列表 — 你的 BottomSheet 加 "复制样品" / "提交审核" action
Chat G (UX-A1) 流程图 — 跟你列表同页面顶部, 不冲突
Chat I (UX-A3) Sticky Footer — 列表底部, 不冲突
Chat J (P-FIN-1) 采购财务审核 — 你的 "审核" action 弹出 J 的审核流程
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
git worktree add ../my-prototype-logistics-sprint2-track-h feature/sprint2-track-h-ux-act
cd ../my-prototype-logistics-sprint2-track-h
```

### 安全 Commit

```powershell
git commit -m "feat: RowActionBottomSheet 抽象" -- frontend/CretasFoodTrace/src/components/list/RowActionBottomSheet.tsx frontend/CretasFoodTrace/src/hooks/useRowActions.ts
```

---

**Brief 结束。Day 1 开始干活。第一件事: 创建 STATUS 文件 + 启动 worktree, 然后读 UX_BORROW.md §A-2 + §F-2 设计 RowActionBottomSheet props。**