# TRACK I BRIEF — Sprint 2: UX-A3 Sticky Footer 实时合计

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **Sprint 2 chat (基于 Sprint 1 已完成 ASAP)**
> **Brief 来源**: `SPRINT_2_PLAN.md` §5.5 (Chat I — UX-A3 7d 名义)
> **接收方**: Chat I (Sprint 2 worker)
> **派发方**: Chat 1 (Organizer)
> **派发日期**: 2026-05-15
> **预期完成**: ~4-4.5 工作日 (名义 7d, Claude 加速 ~1.7x)
> **PR 命名**: `[Sprint2-I] U-FOOTER-1 Sticky Footer 实时合计`
> **STATUS 文件**: `宏见竞品分析/04-最终决策/STATUS/TRACK_I_STATUS.md`
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
- **Sprint 2 (Week 8-10) 现在启动** — 6 个 worker chat 并行

**Sprint 2 UX 三件套** (你是其中之一):
- Chat G: UX-A1 流程图导航 — 首页 + 列表顶部
- Chat H: UX-A2 行末操作下拉 — 列表行末 BottomSheet
- **你 Chat I**: UX-A3 Sticky Footer 实时合计 — 列表底部固定栏

**列表页全面改造三件套** — 完成后 Cretas 列表 UX 立刻和宏见对齐。

### 你是谁

**你 = Chat I = Sprint 2 worker**。Sprint 2 有 6 个并行 chat:
- Chat E: N31 销售→采购自动分流 (4d)
- Chat F: N48 研发样品→BOM→报价 (5d)
- Chat G: UX-A1 业务流程图导航 (10d)
- Chat H: UX-A2 行末操作下拉 (10d)
- **Chat I (你)**: UX-A3 Sticky Footer 实时合计 (7d) RN + Vue 全栈
- Chat J: P-FIN-1 采购财务审核 (3d)

### 沟通方式

- **不要在本 chat 跟 organizer 战略讨论** — 战略已定, 你只执行
- **每日在 STATUS 文件追加 1 段** (格式见 §7)
- **完成一个 sub-task → 推 PR 不要等 Day 7**
- **碰到 blocker 立即在 STATUS 报**

---

## §2 任务范围与工时

### 单项目 (U-FOOTER-1)

| 项目 | N# 编号 | 工时名义 | 工时加速 | 优先级 | 客户感知 |
|---|---|---|---|---|---|
| **Sticky Footer 实时合计 + 分页同栏** | U-FOOTER-1 (UX_BORROW.A-3) | 7d | ~4.5d | P0 ⭐⭐⭐ | 列表底部固定栏显示合计 + 分页 + AI 分析入口 |

### 客户原话证据

**来源**: 宏见 UI 审计 UX_BORROW.md A-3 ⭐⭐⭐ Top ROI

**宏见做法**: 列表底部固定栏一行显示 `共 3 条 [1/3] 总金额: ¥6,525 总数量: 122 总损耗: 5.2%`, 跟分页器同栏。

**Cretas 现状**: ❌ **完全无** StickyFooterSummary 抽象 — 这是 Cretas 跟传统 ERP 的关键差距, 财务/运营录入实时无法校验。

**HD 帧证据**: `000949` 销售单底部 + `000745` 物料需求底部。

### Cretas 怎么用

- **移动 App (RN)**: 列表底部 50px sticky bar (用 SafeAreaView 适配)
- **Web-Admin (Vue)**: 列表分页器升级为 `<TableFooter>` 含 stats
- **AI 增强**: sticky bar 上 📊 图标点击 → AIChat "给我这页的统计分析"
- **RBAC**: 金额相关 stat 尊重 canViewPrice (Sprint 1 Track C ship)

### v3 销售红线 (Sprint 2 解禁)

完成本项目后, 销售可以说:
- ✅ "列表底部实时合计 + AI 分析入口"
- ✅ "10 个 RN list + 10 个 web view 全接入"
- ✅ "仓管角色看不到金额合计 (RBAC 集成)"

### 工时不达标怎么办

- 名义 7d 是上限。Claude 加速 ~1.7-2x → 实际预期 4-4.5 工作日
- 如果工时 >> 1.5 倍名义 (例如超过 10d), 立即在 STATUS 报 organizer
- Organizer 会决定: 减 scope (先 5 list 接入剩余推到 Sprint 3) / 拉外援

---

## §3 文件 Ownership (防冲突)

### 你的 (Chat I 独占, 你可以随便改)

```
frontend/CretasFoodTrace/src/
├── components/list/                                     ← 新建 (跟 Chat H 共享目录, 但不同文件)
│   ├── StickyFooterSummary.tsx                         ← NEW (你)
│   └── index.ts                                         ← 你加导出
├── hooks/
│   └── useListSummary.ts                               ← NEW (你)
└── types/
    └── listSummary.ts                                   ← NEW

web-admin/src/
├── components/list/                                     ← 新建 (跟 Chat H 共享目录, 但不同文件)
│   ├── TableFooter.vue                                 ← NEW (你)
│   └── index.ts                                         ← 你加导出
├── composables/
│   └── useListSummary.ts                               ← NEW (你)
└── types/
    └── listSummary.ts                                   ← NEW

backend/java/cretas-api/src/main/java/com/cretas/aims/
├── controller/
│   └── ListSummaryController.java                      ← NEW
├── service/listsummary/                                 ← 新建
│   ├── ListSummaryService.java                         ← NEW
│   └── impl/
│       └── ListSummaryServiceImpl.java                 ← NEW
└── dto/listsummary/
    ├── ListSummaryRequest.java
    └── ListSummaryResponse.java
```

### 修改 (改前确认其他 chat 没动)

RN 10 个 list screen:
```
frontend/CretasFoodTrace/src/screens/
├── sales/SalesOrderListScreen.tsx                      ← 接入 StickyFooter
├── purchase/PurchaseOrderListScreen.tsx                ← 同
├── production/ProductionPlanListScreen.tsx             ← 同
├── inventory/InventoryListScreen.tsx                   ← 同
├── shipment/ShipmentListScreen.tsx                     ← 同
├── return/ReturnOrderListScreen.tsx                    ← 同
├── transfer/TransferListScreen.tsx                     ← 同
├── wastage/WastageListScreen.tsx                       ← 同
├── attendance/AttendanceListScreen.tsx                 ← 同
└── quality/QualityListScreen.tsx                       ← 同
```

Web-Admin 10 个 list view:
```
web-admin/src/views/
├── sales/SalesOrderListView.vue                        ← 分页器升级为 TableFooter
├── purchase/PurchaseOrderListView.vue                  ← 同
├── production/ProductionPlanListView.vue               ← 同
├── inventory/InventoryListView.vue                     ← 同
├── shipment/ShipmentListView.vue                       ← 同
├── return/ReturnOrderListView.vue                      ← 同
├── transfer/TransferListView.vue                       ← 同
├── wastage/WastageListView.vue                         ← 同
├── attendance/AttendanceListView.vue                   ← 同
└── quality/QualityListView.vue                         ← 同
```

### 共享只读 (改之前必须 ping organizer)

```
backend/.../entity/BaseEntity.java
backend/.../service/impl/IntentExecutorServiceImpl.java
backend/.../ai/tool/AbstractBusinessTool.java
frontend/.../services/api/aiApiClient.ts
frontend/.../store/canViewPriceStore.ts  ← Sprint 1 Track C ship
CLAUDE.md
.claude/rules/*
```

### 别 chat 的 (绝对不准碰)

- Chat E: `backend/.../service/shortage/`
- Chat F: `backend/.../entity/sample/`
- Chat G: `frontend/.../components/workflow/`, `web-admin/.../components/workflow/`
- Chat H: `frontend/.../components/list/RowActionBottomSheet.tsx`, `web-admin/.../components/list/RowActionMenu.vue`
- Chat J: `backend/.../service/purchase/PurchaseOrderApprovalFlow.java`

⚠️ **Chat H 跟你共享 `components/list/` 目录但不同文件** — 改前 git status 看 Chat H 有没有动同一文件。

### Sprint 1 已 ship 你强依赖

```
frontend/.../store/canViewPriceStore.ts                  ← Track C RBAC, 仓管 false → 隐藏金额 stat
backend/.../service/security/RBACService.java            ← Track C, 后端也校验

frontend/.../services/exportService.ts                   ← Track C 单据打印 (你的 sticky footer "导出报表" 入口)

frontend/.../services/api/aiApiClient.ts                 ← AIChat 入口 ("📊 AI 分析" 调它)
```

---

## §4 Day-by-Day 执行计划

### Day 1 — StickyFooterSummary 组件抽象 (RN)

#### 任务

1. **起 worktree**:
   ```bash
   cd C:\Users\Steve\my-prototype-logistics
   git worktree add ../my-prototype-logistics-sprint2-track-i feature/sprint2-track-i-ux-footer
   cd ../my-prototype-logistics-sprint2-track-i
   ```

2. **StickyFooterSummary.tsx props**:
   ```typescript
   export interface SummaryStat {
     label: string;                       // "共" / "金额" / "损耗"
     value: string | number;
     format?: 'currency' | 'number' | 'percent';
     unit?: string;                       // "条" / "¥" / "%"
     canViewPrice?: boolean;              // true → 受 RBAC gate (默认 false)
     trend?: 'up' | 'down' | 'flat';      // 可选趋势指示
     trendDelta?: number;                 // 趋势数值
   }

   export interface StickyFooterSummaryProps {
     stats: SummaryStat[];
     pagination?: {
       currentPage: number;
       totalPages: number;
       pageSize: number;
       totalItems: number;
       onPageChange: (page: number) => void;
     };
     onAIAnalyze?: () => void;            // 📊 AI 分析按钮
     onExport?: () => void;               // 📤 导出按钮 (可选)
     loading?: boolean;
   }
   ```

3. **RN 实现**:
   ```typescript
   import { SafeAreaView } from 'react-native-safe-area-context';
   import { useCanViewPrice } from '../../store/canViewPriceStore';

   const StickyFooterSummary: React.FC<StickyFooterSummaryProps> = ({
     stats, pagination, onAIAnalyze, onExport, loading
   }) => {
     const canViewPrice = useCanViewPrice();

     // 过滤 priceRelated stat
     const visibleStats = stats.filter(s => !s.canViewPrice || canViewPrice);

     return (
       <SafeAreaView edges={['bottom']} style={styles.container}>
         <View style={styles.row}>
           {/* Stats */}
           <View style={styles.stats}>
             {visibleStats.map((stat, i) => (
               <StatItem key={i} stat={stat} />
             ))}
           </View>

           {/* Actions */}
           <View style={styles.actions}>
             {onAIAnalyze && (
               <TouchableOpacity onPress={onAIAnalyze}>
                 <Text>📊</Text>
               </TouchableOpacity>
             )}
             {onExport && (
               <TouchableOpacity onPress={onExport}>
                 <Text>📤</Text>
               </TouchableOpacity>
             )}
           </View>
         </View>

         {/* Pagination */}
         {pagination && (
           <PaginationBar {...pagination} />
         )}
       </SafeAreaView>
     );
   };
   ```

4. **样式**:
   - 底部 50px sticky
   - 背景: Cretas Neo Minimal 浅灰 `#F8F9FA`
   - 边框: 顶部 1px `#E0E0E0`
   - 字体: stat label 12px, value 14px bold
   - 格式化: currency → `¥6,525.00`, percent → `5.2%`, number → `1,234`

5. **canViewPrice = false 的 stat 自动隐藏** (尊重 Track C):
   - 标记 `canViewPrice: true` 的 stat (如金额) 仓管角色看不到
   - 用 `useCanViewPrice()` hook

6. **单测 + Storybook**:
   - 3 stat (共/金额/损耗)
   - 5 stat (共/金额/数量/损耗/平均价)
   - 仓管角色 → 金额 stat 隐藏

**DoD Day 1**: 组件单测 + Storybook 跑通.

---

### Day 2 — TableFooter.vue (Vue) + ListSummaryController 后端 API

#### Vue 端

1. **TableFooter.vue — el-pagination + 合计 stats 同栏**:
   ```vue
   <script setup lang="ts">
   import { ElPagination } from 'element-plus';
   import { useCanViewPrice } from '@/store/canViewPriceStore';
   import { computed } from 'vue';

   interface SummaryStat { label: string; value: string|number; format?: string; canViewPrice?: boolean; }

   const props = defineProps<{
     stats: SummaryStat[];
     pagination?: {
       currentPage: number;
       totalPages: number;
       pageSize: number;
       totalItems: number;
     };
     loading?: boolean;
   }>();

   const emit = defineEmits<{
     'page-change': [page: number];
     'ai-analyze': [];
     'export': [];
   }>();

   const canViewPrice = useCanViewPrice();
   const visibleStats = computed(() => props.stats.filter(s => !s.canViewPrice || canViewPrice.value));
   </script>

   <template>
     <div class="table-footer">
       <div class="stats">
         <span v-for="(stat, i) in visibleStats" :key="i">
           {{ stat.label }}: <strong>{{ format(stat.value, stat.format) }}</strong>
         </span>
       </div>
       <ElPagination v-if="pagination"
                       :current-page="pagination.currentPage"
                       :total="pagination.totalItems"
                       :page-size="pagination.pageSize"
                       @current-change="emit('page-change', $event)" />
       <div class="actions">
         <button @click="emit('ai-analyze')">📊 AI 分析</button>
         <button @click="emit('export')">📤 导出</button>
       </div>
     </div>
   </template>
   ```

#### 后端 API

1. **ListSummaryController**:
   ```java
   @RestController
   @RequestMapping("/api/mobile/{factoryId}/list-summary")
   public class ListSummaryController {

       @PostMapping("/{entityType}")
       public ApiResponse<ListSummaryResponse> getSummary(
           @PathVariable String factoryId,
           @PathVariable String entityType,
           @RequestBody ListSummaryRequest request) {
           return ApiResponse.success(listSummaryService.computeSummary(factoryId, entityType, request));
       }
   }
   ```

2. **ListSummaryRequest body**:
   ```java
   @Data
   public class ListSummaryRequest {
       private Map<String, Object> filterConditions;  // 跟列表 filter 一致
       private List<String> fields;                    // 要算的字段, e.g. ["totalAmount", "totalQty", "wastageRate"]
       private LocalDate dateFrom;
       private LocalDate dateTo;
   }
   ```

3. **ListSummaryServiceImpl** — 按 entityType 路由:
   ```java
   @Service
   public class ListSummaryServiceImpl implements ListSummaryService {

       public ListSummaryResponse computeSummary(String factoryId, String entityType, ListSummaryRequest request) {
           switch (entityType) {
               case "salesOrder": return computeSalesOrderSummary(factoryId, request);
               case "purchaseOrder": return computePurchaseOrderSummary(factoryId, request);
               case "inventory": return computeInventorySummary(factoryId, request);
               case "wastage": return computeWastageSummary(factoryId, request);
               case "attendance": return computeAttendanceSummary(factoryId, request);
               // ... 5 entity 起步, 后续 sprint 加更多
               default: throw new IllegalArgumentException("Unsupported entityType: " + entityType);
           }
       }

       private ListSummaryResponse computeSalesOrderSummary(String factoryId, ListSummaryRequest request) {
           BigDecimal totalAmount = salesOrderRepo.sumAmountByConditions(factoryId, request.getFilterConditions());
           Long totalCount = salesOrderRepo.countByConditions(factoryId, request.getFilterConditions());
           BigDecimal avgAmount = totalCount > 0 ? totalAmount.divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
           return ListSummaryResponse.builder()
               .stats(List.of(
                   new SummaryStat("共", totalCount, "number", "条", false),
                   new SummaryStat("总金额", totalAmount, "currency", "¥", true),     // canViewPrice gate
                   new SummaryStat("平均金额", avgAmount, "currency", "¥", true)
               ))
               .build();
       }
   }
   ```

4. **RBAC: 接 canViewPrice gate**:
   - 后端也 double-check 仓管角色是否能调 summary endpoint 含金额字段
   - 如果是, 返回时把金额 stat 的 `canViewPrice: true`, 前端用 store 过滤
   - 严格起见: 后端可以直接不返回金额字段给仓管, 但前端 store gate 更简单

5. **5 entity 起步**: salesOrder, purchaseOrder, inventory, wastage, attendance

**DoD Day 2**: curl 跑通 5 entity summary endpoint.

---

### Day 3-4 — RN 10 list screen 接入

#### Day 3 — sales / purchase / production / inventory / shipment 5 个

**任务**:

1. **SalesOrderListScreen.tsx 接入**:
   ```typescript
   import { StickyFooterSummary } from '../../components/list/StickyFooterSummary';
   import { useListSummary } from '../../hooks/useListSummary';

   const SalesOrderListScreen = () => {
     const { orders, total, page, setPage, filterConditions } = useSalesOrders();
     const { summary, refresh } = useListSummary('salesOrder', filterConditions);

     useEffect(() => { refresh(); }, [filterConditions]);

     return (
       <View style={{ flex: 1 }}>
         <FlatList data={orders} renderItem={({ item }) => <SalesOrderCard order={item} />} />
         <StickyFooterSummary
           stats={summary.stats}
           pagination={{
             currentPage: page,
             totalPages: Math.ceil(total / 20),
             pageSize: 20,
             totalItems: total,
             onPageChange: setPage
           }}
           onAIAnalyze={() => navigation.navigate('AIChat', {
             entryContext: {
               module: 'sales',
               action: 'analyze-page',
               filterConditions,
               summaryStats: summary.stats
             }
           })}
         />
       </View>
     );
   };
   ```

2. **同样接入 purchase / production / inventory / shipment**

#### Day 4 — return / transfer / wastage / attendance / quality 5 个

**任务**: 5 个 list 同 Day 3 pattern

**DoD Day 4**: 10 个 RN list 全接入.

---

### Day 5-6 — Web-Admin 10 list view 接入

#### Day 5 — web 销售 / 采购 / 生产 / 库存 / 出货 5 个

**任务**:

1. **SalesOrderListView.vue 接入** — 升级现有 el-pagination 为 TableFooter:
   ```vue
   <script setup lang="ts">
   import { TableFooter } from '@/components/list/TableFooter';
   import { useListSummary } from '@/composables/useListSummary';
   import { useRouter } from 'vue-router';

   const filterConditions = ref({});
   const { summary, refresh } = useListSummary('salesOrder', filterConditions);
   watchEffect(() => { refresh(); });

   const router = useRouter();
   const handleAIAnalyze = () => {
     // 打开 AIChat drawer or 跳页面
   };
   </script>

   <template>
     <div>
       <el-table :data="orders" />
       <TableFooter
         :stats="summary.stats"
         :pagination="{ currentPage, totalPages, pageSize, totalItems }"
         @page-change="onPageChange"
         @ai-analyze="handleAIAnalyze"
         @export="onExport"
       />
     </div>
   </template>
   ```

2. **同样接入 purchase / production / inventory / shipment**

#### Day 6 — web 退货 / 调拨 / 损耗 / 考勤 / 质检 5 个

**任务**: 5 个 view 同 Day 5 pattern.

**DoD Day 6**: 10 个 web view 全接入.

---

### Day 7 — AI 分析联调 + Demo + PR

#### 任务

1. **AI 分析入口**:
   - 点 📊 → 跳 AIChat (或打开 drawer), 携带:
     ```typescript
     entryContext: {
       module: 'sales',
       action: 'analyze-page',
       filterConditions: { ... },
       summaryStats: [{ label: '总金额', value: 6525, format: 'currency' }, ...]
     }
     ```
   - AIChat 看到 `action: 'analyze-page'` → 自动调 SmartBI Tool (`smartbi_quick_analysis` 或类似) 生成结构化分析
   - 返回: 趋势分析 / 异常检测 / 建议 (3 段 markdown)

2. **跟 Sprint 1 SmartBI 集成**:
   - SmartBI 已 ship (Sprint 之前), 你只需 entryContext 携带的 filterConditions + summaryStats 喂给它
   - AIChat 内调 SmartBI 后端 `/api/smartbi/quick-analysis` 接口

3. **Demo 录** (1-2 分钟):
   1. 销售员登陆 → 销售单列表
   2. 列表底部看到 sticky footer: "共 25 条 [1/2] | 总金额 ¥58,750 | 平均 ¥2,350 | 📊 📤"
   3. 滑动 filter (status = APPROVED) → footer stats 实时更新
   4. 点 📊 → AIChat 弹出 → "根据当前 25 单 APPROVED 销售: 总金额 ¥58,750, 平均 ¥2,350, 较上周 +15%, 异常: SO-007 金额超过均值 3 倍, 建议: 复核客户信用"
   5. 仓管账号登陆 → footer 没显示金额 stat (RBAC)
   6. Web-Admin 同样路径演示

4. **推 PR**:
   ```bash
   git push -u origin feature/sprint2-track-i-ux-footer
   gh pr create --title "[Sprint2-I] U-FOOTER-1 Sticky Footer 实时合计" --body "..."
   ```

   PR body 含:
   - 涉及文件清单 (RN + Vue 组件 + hook + composable + Controller + Service + 20 个 list 接入)
   - 测试方式 (单测 + curl + E2E demo)
   - 风险点 (RBAC 依赖 Track C / AI 分析依赖 SmartBI 后端)
   - 跟 Sprint 1 哪些 PR 依赖

**DoD Day 7**: PR + demo + STATUS 7 段完整.

---

## §5 关键参考文档

| 路径 | 用途 |
|---|---|
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\UX_BORROW.md` §A-3 + §F-3 | UX 模式定义 + 示意图 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\SPRINT_2_PLAN.md` §5.5 | Day-by-day 来源 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\01-客户档案\NUMBERING_MAP.md` | U-FOOTER-1 编号 |
| `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\TRACK_C_BRIEF.md` | Sprint 1 RBAC (你强依赖 canViewPriceStore) |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\api-response-handling.md` | 统一响应 + 缓存 |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\database-entity-sync.md` | PG 严格 GROUP BY |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\typescript-type-safety.md` | 禁 `as any` |
| `C:\Users\Steve\my-prototype-logistics\.claude\rules\concurrent-edit-safety.md` HARD | 6 chat 并行 commit 安全 |

---

## §6 接口契约 (Interface Contracts)

### 后端 → 前端 API

**POST /api/mobile/{factoryId}/list-summary/{entityType}**

```typescript
// entityType: 'salesOrder' | 'purchaseOrder' | 'inventory' | 'wastage' | 'attendance' | ...

// Request
{
  filterConditions: { status?: string, customerId?: string, ... },
  fields?: string[],                  // 可选, 指定要算的字段
  dateFrom?: string,                  // ISO 8601
  dateTo?: string
}

// Response
{
  success: true,
  data: {
    entityType: string,
    stats: Array<{
      label: string,                  // "总金额" / "共" / "损耗"
      value: number | string,
      format: 'currency' | 'number' | 'percent' | 'plain',
      unit?: string,                  // "¥" / "条" / "%"
      canViewPrice?: boolean,         // 是否价格相关 (前端 store gate)
      trend?: 'up' | 'down' | 'flat',
      trendDelta?: number
    }>,
    pagination: {
      currentPage: number,
      totalPages: number,
      pageSize: number,
      totalItems: number
    }
  },
  message: "操作成功"
}
```

### RN 组件 props

```typescript
interface SummaryStat {
  label: string;
  value: string | number;
  format?: 'currency' | 'number' | 'percent';
  unit?: string;
  canViewPrice?: boolean;
  trend?: 'up' | 'down' | 'flat';
  trendDelta?: number;
}

interface StickyFooterSummaryProps {
  stats: SummaryStat[];
  pagination?: { currentPage; totalPages; pageSize; totalItems; onPageChange };
  onAIAnalyze?: () => void;
  onExport?: () => void;
  loading?: boolean;
}
```

### Sprint 1 依赖

| Sprint 1 提供 | 你怎么用 |
|---|---|
| Track C `canViewPriceStore` (RN + Vue) | StickyFooterSummary 过滤 canViewPrice stat |
| Track C `exportService` | "📤 导出" 按钮调用 |
| SmartBI 后端 `/api/smartbi/quick-analysis` | AIChat 调它做趋势分析 |

---

## §7 PR / Status Update 流程

### 每日 STATUS 更新

文件: `C:\Users\Steve\my-prototype-logistics\宏见竞品分析\04-最终决策\STATUS\TRACK_I_STATUS.md`

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
- `[Sprint2-I-1] U-FOOTER-1 组件抽象 + 后端 API` (Day 1-2)
- `[Sprint2-I-2] U-FOOTER-1 20 list 接入 + AI` (Day 3-7)

或者 1 大 PR Day 7 一次推.

### 并发安全 commit

```bash
git commit -m "feat: StickyFooterSummary RN 组件" -- frontend/CretasFoodTrace/src/components/list/StickyFooterSummary.tsx frontend/CretasFoodTrace/src/hooks/useListSummary.ts
```

⚠️ **Chat H 跟你共享 `components/list/` 目录** — commit 前 `git status` 看 Chat H 文件没动.

---

## §8 不要做 (Do Not Do)

### 严格禁止

1. **不要忽略 RBAC** — 必须接 Sprint 1 Track C canViewPriceStore, 仓管角色看不到金额 stat
2. **不要在后端不 gate** — Service 层应该看 user role, 不要前端全权
3. **不要改 ownership 外的文件** (§3)
4. **不要用 `as any`** — TypeScript 严格
5. **不要并发改同一文件** — 用 git worktree, `git commit -- F1 F2`
6. **不要修改 list screen 本身的业务逻辑** — 你只加 sticky footer, 不动列表 query / filter / sort
7. **不要 Chat H 的 `components/list/` 文件** — 你只加 StickyFooterSummary.tsx, 不动 RowActionBottomSheet.tsx
8. **不要 hardcode entity type 列表** — 用 enum / config, 后续 sprint 加更多 entity
9. **不要忽略 PG GROUP BY 严格模式** (`.claude/rules/database-entity-sync.md`):
   - sumAmount + count 一起查时, GROUP BY 必须包含所有 SELECT 的非聚合列

---

## §9 验收清单

### 功能验收

- [ ] **组件**: RN StickyFooterSummary 渲染 stats + 分页 + AI/导出按钮
- [ ] **组件**: Vue TableFooter 等价实现 (el-pagination + stats)
- [ ] **后端**: 5 个 entity summary endpoint 跑通 (salesOrder/purchaseOrder/inventory/wastage/attendance)
- [ ] **RN**: 10 个 list screen 接入
- [ ] **Web**: 10 个 list view 接入
- [ ] **RBAC**: 仓管角色看不到金额 stat (依赖 Track C)
- [ ] **AI**: 📊 按钮跳 AIChat + analyze-page 自动 SmartBI 分析
- [ ] **导出**: 📤 按钮调 exportService (Sprint 1 Track C)
- [ ] **响应**: stat 实时更新 (filter 变化 → refetch)

### UX 验收

- [ ] **设计**: 移动端 SafeAreaView 适配 (iPhone 底部安全区)
- [ ] **格式化**: currency `¥6,525.00`, percent `5.2%`, number `1,234`
- [ ] **空状态**: 列表为空 → footer 显示 "暂无数据"
- [ ] **加载**: loading 状态显示 Skeleton

### 销售红线验收

- [ ] **红线**: "列表底部实时合计 + AI 分析入口"
- [ ] **红线**: "10 个 RN list + 10 个 web view 全接入"
- [ ] **红线**: "仓管角色看不到金额合计 (RBAC 集成)"

### 技术验收

- [ ] PR merged 到 main
- [ ] 无新增 `as any`
- [ ] 无新增 `catch (error: any)`
- [ ] PG GROUP BY 不报严格模式错误
- [ ] E2E demo 视频录制

---

## §10 客户场景对照

### 客户期望

**六扇门 F006 (卤制品工厂)** 希望:
1. **财务录入实时校验** — 录入销售单时, 底部立刻看到 "本月总金额 ¥58,750", 防止数据错乱
2. **运营一眼看全貌** — 库存列表底部显示 "共 234 SKU 总价值 ¥125,400 低库存 12 项"
3. **AI 分析入口** — 财务点 📊 立即得到 "本月销售较上周 +15%, 异常 SO-007 超均值 3 倍"
4. **仓管隔离** — 仓管看库存只看数量, 不看金额 (合规)

### Cretas 的差异化卖点

**宏见 ERP 范式**: 列表底部仅有分页器, 客户要算总金额得自己加; 仓管能看到金额 (合规风险)

**Cretas Sprint 2 完成后**:
- ✅ 列表底部 sticky 合计实时显示
- ✅ 金额 / 数量 / 损耗 / 平均 4 类 stat
- ✅ AI 分析按钮 — 一键得到结构化分析
- ✅ 导出按钮 — 一键导出 PDF (Sprint 1 Track C)
- ✅ RBAC: 仓管看不到金额

### 跟其他 Chat 的串联

```
Chat E (N31) 销售单列表 — 你的 sticky footer 显示 "总金额 ¥XX 缺料 X 物料"
Chat F (N48) 样品列表 — 你的 sticky footer 显示 "共 N 样品 紧急 X 个 待审 Y 个"
Chat G (UX-A1) 流程图 — 跟你列表同页面顶部, 不冲突
Chat H (UX-A2) 行末操作 — 列表行末, 跟你底部 sticky 不冲突
Chat J (P-FIN-1) 财务审核 — 你 sticky footer 显示 "标红 X 单 待审 Y 单"
```

完整列表页 UX:
```
┌────────────────────────────────────────┐
│  WorkflowBar (Chat G)                   │ ← 顶部
├────────────────────────────────────────┤
│  订单卡片 #1            [操作 ▾ (H)]   │
│  订单卡片 #2            [操作 ▾ (H)]   │
│  订单卡片 #3            [操作 ▾ (H)]   │
├────────────────────────────────────────┤
│  共 3 条 │ ¥6,525 │ 损耗 5.2% │ 📊 📤 │ ← 你 sticky footer
└────────────────────────────────────────┘
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
git worktree add ../my-prototype-logistics-sprint2-track-i feature/sprint2-track-i-ux-footer
cd ../my-prototype-logistics-sprint2-track-i
```

### 安全 Commit

```powershell
git commit -m "feat: StickyFooterSummary 抽象" -- frontend/CretasFoodTrace/src/components/list/StickyFooterSummary.tsx frontend/CretasFoodTrace/src/hooks/useListSummary.ts
```

---

**Brief 结束。Day 1 开始干活。第一件事: 创建 STATUS 文件 + 启动 worktree, 然后读 UX_BORROW.md §A-3 + §F-3 设计 StickyFooterSummary props。**