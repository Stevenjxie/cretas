# Sprint 2 详细按日排期 — Week 8-10 (15 工作日)

> ⚠️ **DEPRECATED 2026-05-19** — 本文件已 supersede by `31-DEEP-RE-AUDIT.md §P` (Round 11 deep re-audit). Sprint 1+2 track 已全 ship, 决策面 status 请直接看 31-doc + `30-BACKLOG-STATUS-AUDIT.md §13`. 本文件保留作为 historical reference (Sprint 1+2 时点 brief / 计划 / dispatch).


> **本文件用途**: Organizer (Chat 1) 调度 Sprint 2 的"主文档". 假设 ASAP (Sprint 1, 6 chats × 1 周) 已完成 — 6 个 Sprint 1 track 全部 ship + main 已合并.
>
> **执行机制**: 5 个 worker chat (Chat E-I) 并行做实现, Chat 1 协调 + review. 同 Sprint 1 调度模式.
>
> **总工时**: 名义 36d / Claude 加速 ~21 工作日 / 单人 ~5 周 → **5 chat 并行 ~3 周完成 (15 工作日 = Week 8-10)**
>
> **本文件原则**: 完全 self-contained. 任何 worker chat 拿到自己的 §5.X 段落 + §3 文件 ownership + §4 依赖说明就能干活, 不需要再读其他文档.
>
> **派发日期**: 2026-05-14 (Sprint 1 dispatch 同日预排)

---

## §1 Sprint 2 Onboarding (Sprint 1 已 ship 前提下)

### 1.1 项目背景

**Cretas (白垩纪) 食品溯源系统** — Java 21 + Spring Boot 3.2.12 后端 (10010) + Expo 53+ RN 前端 (3010) + Python FastAPI (8083). 客户**六扇门 F006** 卤制品工厂, ASAP 1.5 月交付 P0. Sprint 1 (Week 6-7) 完成 ASAP Phase 0 + 客户已反馈 bug + 8 项 P0 必抄.

### 1.2 Sprint 2 的目标 (从 MUST_COPY.md §B + UX_BORROW.md §A)

| 项目 | 来源 | 类型 | 工时 |
|---|---|---|---|
| **N31 销售订单 → 采购自动分流** | MUST_COPY P0 (全流程文档 §2.2-3) | 业务功能 | 4d |
| **N48 研发样品 → BOM → 报价链路** | MUST_COPY P0 (全流程文档 §1) | 业务功能 | 5d |
| **UX-A1 业务流程图导航** | UX_BORROW Top 3 ⭐⭐⭐ | UX 重塑 | 10d |
| **UX-A2 行末"操作 ▾"下拉** | UX_BORROW Top 3 ⭐⭐⭐ | UX 重塑 | 10d |
| **UX-A3 Sticky Footer 实时合计** | UX_BORROW Top 3 ⭐⭐⭐ | UX 重塑 | 7d |

**Total**: 36 人天名义 → Claude 加速 1.7x → **预期 ~21 工作日 / 5 chat 并行 ~15 工作日 = Week 8-10**

### 1.3 为什么是这 5 项 (战略意义)

- **N31 + N48** 拼接出**完整业务流第一节** (研发→报价→销售→缺料判断→采购), 客户演示能跑通"端到端业务链"
- **UX Top 3** 是宏见 UI 审计后 ROI 最高的 3 个模式 — 完成后 Cretas UI 一次性升一档, **客户立即感知**
- N31 解锁后 Cretas "AI 一句话调度生产/采购" 销售红线**真**站得住 (AIChat → ShortageAnalysisService → 单据流)
- UX-A1 流程图导航 + UX-A2 行末下拉 + UX-A3 Sticky Footer 是 RN App 列表页**全面改造的三件套**

### 1.4 Sprint 2 v3 销售红线 (Sprint 1 后再解禁)

完成 Sprint 2 后, 销售可以说:
- ✅ "AI 一句话从销售单一直分流到采购" (N31)
- ✅ "研发→样品→BOM→自动报价" (N48)
- ✅ "每个业务模块顶部都有流程图导航" (UX-A1)
- ✅ "列表行末操作 ▾, 主行只显示状态 chip" (UX-A2)
- ✅ "列表底部实时合计 + AI 分析入口" (UX-A3)

**仍禁**:
- ❌ "财务发票 / 收款流水 / 多客户记忆价" (Sprint 3-4 才做)
- ❌ "请假 / 调休 / 报销" (Sprint 5)

### 1.5 worker chat 沟通方式

- **不要在本 chat 跟 organizer 战略讨论** — 战略已定, 你只执行
- **每日在 STATUS 文件追加 1 段** (`宏见竞品分析/04-最终决策/STATUS/SPRINT2_TRACK_{E|F|G|H|I}_STATUS.md`, 新建)
- **完成一个 sub-project → 推 PR → ping organizer review**
- **碰到 blocker 立即在 STATUS 报, 不要自己卡死**

---

## §2 5 个 Worker Chat 总览 (Chat E-I)

| Chat | 项目 | 工时 (名义/加速) | 重 backend or 重 frontend | 客户感知 |
|---|---|---|---|---|
| **Chat E** | N31 销售→采购自动分流 | 4d / ~3d | 后端为主 (Java 单据流 + AIChat Tool) | 销售单审批后自动给出缺料 + 推荐采购建议 |
| **Chat F** | N48 研发样品→BOM→报价 | 5d / ~3.5d | 全栈 (Java + RN + AI 推荐配方) | 研发员建样品 → 一键自动生成 BOM + 报价任务 |
| **Chat G** | UX-A1 流程图导航 | 10d / ~6d | RN + Vue 全栈 | 首页 + 每业务模块顶部多了流程节点导航 |
| **Chat H** | UX-A2 行末操作下拉 | 10d / ~6d | RN + Vue 全栈 | 列表行不再拥挤, 长按 / 行末按钮展开 8-14 动作 |
| **Chat I** | UX-A3 Sticky Footer 实时合计 | 7d / ~4.5d | RN + Vue 全栈 | 列表底部固定栏显示合计 + 分页 + AI 分析入口 |

**Organizer (Chat 1)** 协调 + review + 集成. 不实现.

---

## §3 文件 Ownership (防冲突, 5 chats)

### 3.1 各 Chat 拥有 (独占, 可随便改)

| Chat | 拥有目录/文件 |
|---|---|
| **E** (N31) | NEW `backend/.../service/shortage/ShortageAnalysisService.java` + impl<br>NEW `backend/.../ai/tool/impl/shortage/ShortageAnalysisTool.java`<br>NEW `frontend/.../screens/sales/SalesOrderShortageReviewScreen.tsx`<br>NEW `frontend/.../components/chain/ShortageChainCard.tsx`<br>修改 `backend/.../controller/SalesOrderController.java` (审批后 hook)<br>修改 `backend/.../service/impl/BomExpansionService.java` (统一入口) |
| **F** (N48) | NEW `backend/.../entity/sample/SampleRequest.java` + Flyway V20260601_01__sample_request.sql<br>NEW `backend/.../service/sample/SampleRequestService.java`<br>NEW `backend/.../ai/tool/impl/sample/SampleToBomTool.java`<br>NEW `frontend/.../screens/rd/SampleRequestListScreen.tsx`<br>NEW `frontend/.../screens/rd/SampleRequestDetailScreen.tsx`<br>修改 `backend/.../service/quotation/QuotationService.java` (样品审核回写报价任务) |
| **G** (UX-A1) | NEW `frontend/.../components/workflow/WorkflowVisualizer.tsx`<br>NEW `frontend/.../components/workflow/WorkflowNode.tsx`<br>NEW `web-admin/src/components/workflow/WorkflowBar.vue`<br>NEW `backend/.../controller/WorkflowStatsController.java` (节点 count API)<br>修改 `frontend/.../screens/*/HomeScreen.tsx` 5 个角色 BentoGrid 加流程图卡片<br>修改 `web-admin/src/views/sales|purchase|production|finance|inventory/*ListView.vue` 5 模块顶部加 bar |
| **H** (UX-A2) | NEW `frontend/.../components/list/RowActionBottomSheet.tsx` (RN)<br>NEW `web-admin/src/components/list/RowActionMenu.vue`<br>NEW `frontend/.../hooks/useRowActions.ts`<br>修改 `frontend/.../screens/*/SalesOrderListScreen.tsx` + 7 个其他 List screen<br>修改 `web-admin/src/views/*ListView.vue` 行末加按钮 (8 个 list) |
| **I** (UX-A3) | NEW `frontend/.../components/list/StickyFooterSummary.tsx`<br>NEW `web-admin/src/components/list/TableFooter.vue`<br>NEW `backend/.../controller/ListSummaryController.java` (按 entity 算合计 API)<br>修改 `frontend/.../screens/*/` 10 个 list screen 接入<br>修改 `web-admin/src/views/*` 10 个 list view 分页器升级 |

### 3.2 5 个 Chat 共享只读 (改前必须 ping organizer)

- `backend/.../entity/BaseEntity.java`
- `backend/.../service/impl/IntentExecutorServiceImpl.java`
- `frontend/.../services/api/aiApiClient.ts`
- `frontend/.../navigation/*` (路由配置, UX-A1 加流程图入口必经)
- `CLAUDE.md` + `.claude/rules/*`
- **Sprint 1 已 ship 的 6 个 track 的核心文件** (尤其 Track D1 的 BOM Entity 跟 N31/N48 强耦合, Track C 的 Attachment 跟 N48 样品照片强耦合)

### 3.3 Git 策略 (每 chat 一个 worktree)

```bash
# Chat E 示例
git worktree add ../cretas-sprint2-track-e HEAD
cd ../cretas-sprint2-track-e
git checkout -b feature/sprint2-track-e-n31-shortage
```

PR 命名: `[Sprint2-{E|F|G|H|I}] N# 编号 项目名`

---

## §4 跟 Sprint 1 (6 chat) 的依赖关系

Sprint 2 强假设 Sprint 1 全部 ship. 关键依赖如下:

```
Sprint 1 ship → Sprint 2 解锁
─────────────────────────────────────────────────────────
Track A Canvas (sessionId + LLM + PageEditor) ──┐
                                                  ├─→ Chat E (N31) 可用 AIChat sessionId 多轮
                                                  └─→ Chat F (N48) 可用 AILayoutAssistant 推 BOM

Track B1 钉钉机器人 ─────────────────────────────→ Chat E (N31) 缺料推送钉钉群
                                                  Chat F (N48) 样品审核通知钉钉

Track B2 抄码品识别 + PDF 扫码 RN ───────────────→ Chat E (N31) 采购单可含抄码品物料

Track C 通用 Attachment ──────────────────────────→ Chat F (N48) 样品照片 / 追踪记录 5+ 附件
                                                  Chat G (UX-A1) 流程节点可挂证据照片
Track C 单据打印 PDF ─────────────────────────────→ Chat H (UX-A2) 行末 "打印 PDF" 动作真实可跑
                                                  Chat I (UX-A3) sticky footer 上 "导出报表" 入口
Track C 三价对比 bug 修复 ────────────────────────→ Chat E (N31) 推荐采购时三价对比可见
Track C RBAC 审计 ───────────────────────────────→ Chat I (UX-A3) sticky footer 价格字段尊重 canViewPrice

Track D1 BOM 配方编辑 UI (工厂端) ─────────────────→ Chat F (N48) 样品审核 → 复用 BomConfigScreen 创建 BOM (强依赖)
Track D1 BOM 物料选择器 + 单位转换 ────────────────→ Chat E (N31) 缺料判断准确性 (依赖物料字典硬外键)
                                                  Chat F (N48) AI 生成 BOM 时物料 select 而非手写

Track D2 工序管理 + 产品工序配置 ──────────────────→ Chat E (N31) 销售单 → 推荐生产时 工序任务能生成
```

### 4.1 关键阻断关系 (Critical Path)

| 阻断关系 | 影响 | Organizer 协调 |
|---|---|---|
| **Chat F (N48) 强依赖 Track D1 (BOM)** | D1 未 ship 则 F 没法做 "样品 → BOM" | Sprint 1 末必须 merge D1, F 才能 Day 1 启动 |
| **Chat E (N31) 强依赖 Track D2 (工序)** | D2 未 ship 则 E 推荐生产无工序可挂 | Sprint 1 末必须 merge D2 |
| **Chat E (N31) 中依赖 Track A (Canvas)** | A 未 ship sessionId AIChat 多轮失败 | Sprint 1 必交付, 否则 N31 AI Tool 用单轮 |
| **Chat F (N48) 中依赖 Track C (Attach)** | C 未 ship 样品照片只能跳过 | F 可降级先做单据流 + 留 UI 接 attachment API |
| **UX-A1/A2/A3 (Chat G/H/I) 弱依赖** | 大部分独立 — 抽组件 + 接入既有 list | 不阻断 |

### 4.2 Sprint 2 启动前 Organizer 必查清单

Sprint 1 末 (Week 7 周五) Organizer 验收下面才能启动 Sprint 2:

- [ ] Track A 3 个 PR 全 merge: AILayoutAssistant 接真 LLM ✅ + PageEditor 挂导航 ✅ + Canvas Repository 统一 ✅
- [ ] Track B1 钉钉机器人 PoC 客户群 webhook 跑通 ✅
- [ ] Track B2 抄码品识别 + PDF 扫码 RN 两个 PR ✅
- [ ] Track C 通用 Attachment 5 模块接入 ✅ + 三价对比刷新 bug ✅ + 单据打印起步 ✅ + RBAC 审计 ✅
- [ ] Track D1 工厂端 BomConfigScreen ✅ + BOM 物料选择 bug ✅ + 单位转换 bug ✅
- [ ] Track D2 WorkProcessListScreen + ProductWorkProcessConfigScreen ✅ + 工序通用 bug ✅
- [ ] main 分支可 `mvn spring-boot:run` + `npx expo start` 无报错
- [ ] F006 prod 账号能登, 关键演示路径未 regression

如果任何一项 ❌, Sprint 2 对应受影响 chat 推迟启动 (例如 D1 没 ship, Chat F 延后).

---

## §5 Day-by-Day 执行计划 (Week 8-10)

> **5 chat 并行运行**. Day 1-15 是 5 个 chat 各自的执行日历, 不是 Organizer 的日历. 部分 chat 比另一些早完成 → ping organizer review 后做 demo prep / 帮其他 chat.

### 5.1 Chat E — N31 销售订单 → 采购自动分流 (4d 名义)

#### Day 1 — 阅读 + ShortageAnalysisService 接口设计

**目标**: 摸清 4 个分散逻辑 + 设计统一入口

**具体步骤**:
1. grep + 读 4 处现有缺料逻辑:
   - `backend/.../service/impl/BomExpansionService.java` — BOM 展开
   - `backend/.../service/impl/InventoryMatchingService.java` — 库存匹配
   - `backend/.../service/impl/ProcurementSuggestionService.java` — 采购推荐
   - `backend/.../service/impl/SupplyChainOrchestrator.java` — 编排
2. 起 worktree `cretas-sprint2-track-e` + branch `feature/sprint2-track-e-n31-shortage`
3. 写 `ShortageAnalysisService` 接口:
   ```java
   public interface ShortageAnalysisService {
       ShortageReport analyzeForSalesOrder(String factoryId, String salesOrderId);
       List<ProcurementSuggestion> suggestProcurement(String factoryId, ShortageReport report);
       List<ProductionPlanSuggestion> suggestProduction(String factoryId, ShortageReport report);
   }
   ```
4. **不**实现 method body. commit 接口 + DTO. Status Day 1 done.

**DoD**: 接口 commit + STATUS 段落.

#### Day 2 — Service 实现 + SalesOrderController hook

**目标**: 销售单审批通过 event 触发 ShortageAnalysisService, 输出 JSON

**具体步骤**:
1. `ShortageAnalysisServiceImpl` — 编排现有 4 个 service, 不重写, 只统一入口
2. `SalesOrderController` 审批成功 hook: `applicationEventPublisher.publishEvent(new SalesOrderApprovedEvent(...))`
3. `@EventListener` 接 event → 调 `ShortageAnalysisService.analyzeForSalesOrder` → 写 `sales_order_shortage_report` 表 (新建 Flyway)
4. 单测: F001 dev seed 数据跑通

**DoD**: curl `/api/mobile/{factoryId}/sales-orders/{id}/shortage-report` 返回 JSON.

#### Day 3 — AIChat ShortageAnalysisTool + chain-card UI 设计

**目标**: AIChat "这单缺什么?" 调 Tool, 输出 chain-card 数据

**具体步骤**:
1. `ShortageAnalysisTool extends AbstractBusinessTool` (per `.claude/rules/ai-intent-tool-skill-architecture.md`):
   - `getToolName()` = `"shortage_analyze"`
   - 注入 `@Lazy ShortageAnalysisService`
   - 绑定 intent `SHORTAGE_ANALYSIS` (Flyway 加 ai_intent_config 行)
2. RN `ShortageChainCard.tsx` 组件 — 销售单 + 缺料列表 + 推荐采购 + 推荐生产 3 段 card
3. `SalesOrderShortageReviewScreen.tsx` 用 chain-card, 一键确认 / 修改 / 钉钉推送

**DoD**: AIChat 输入 "F006 销售单 SO-001 缺什么" 返回 card 结构.

#### Day 4 — RN UI 完整接入 + 钉钉推送 + Demo

**目标**: 跑通完整链路 + 录 1 分钟 demo

**具体步骤**:
1. SalesOrderShortageReviewScreen 接 hooks + 跳转
2. 调 Track B1 钉钉 webhook: 销售单审批后自动推 "缺料 + 推荐采购" 卡片到钉钉群 (3 行代码 import DingTalkBotService)
3. Demo 录: 创销售单 → 审批 → AIChat 触发 → 收到钉钉 → 一键确认采购
4. PR 推 `[Sprint2-E] N31 销售订单→采购自动分流`

**DoD**: PR 推送 + 录 demo + STATUS 4 段完整.

---

### 5.2 Chat F — N48 研发样品 → BOM → 报价 (5d 名义)

#### Day 1 — SampleRequest Entity + Flyway + Service 接口

**目标**: 样品实体 + 状态机 + 接口 commit

**具体步骤**:
1. Flyway `V20260601_01__sample_request.sql`:
   - 字段: id, factory_id, customer_id, sample_name, sample_code, spec, grade, main_material_id, urgency, status (DRAFT/SUBMITTED/REVIEWING/APPROVED/REJECTED), notes, photo_attachment_ids (TEXT[]), audit fields
   - 状态机: DRAFT → SUBMITTED → REVIEWING → (APPROVED | REJECTED)
2. `SampleRequest.java` Entity 继承 BaseEntity (per `.claude/rules/database-entity-sync.md`)
3. `SampleRequestService` 接口 commit (CRUD + submit + review + approve + linkToBom)
4. 起 worktree + branch.

**DoD**: 表 + Entity + 接口 commit, `\d sample_requests` 可见.

#### Day 2 — SampleRequestServiceImpl + Controller + 5 API

**目标**: REST API 跑通

**具体步骤**:
1. 5 个 endpoint:
   - `POST /api/mobile/{factoryId}/sample-requests` — 创建
   - `GET /api/mobile/{factoryId}/sample-requests` — 列表 (按状态筛选)
   - `GET /api/mobile/{factoryId}/sample-requests/{id}` — 详情
   - `POST .../sample-requests/{id}/submit` — 提交审核
   - `POST .../sample-requests/{id}/review` — 审核 (含 approve/reject)
2. `approve` 时:
   - 调 Track D1 `BomService.createFromSample(sampleId, factoryId, userId)` 自动生成 BOM
   - 调 `QuotationService.createTaskFromSample(sampleId)` 推送报价任务
   - 调 Track B1 `DingTalkBotService.sendNotification(...)` 通知销售
3. 单测: F001 dev seed 跑端到端

**DoD**: curl 跑通 5 个 endpoint + 审核 approve 后 BOM 自动建.

#### Day 3 — AI Tool: SampleToBomTool + 历史相似推荐

**目标**: AIChat "给这个样品建 BOM 类似 SKU-201 但减 10% 包材"

**具体步骤**:
1. `SampleToBomTool extends AbstractBusinessTool`:
   - tool name = `"sample_to_bom"`
   - 接受 sampleId + referenceSku + adjustments (textual)
   - 调 PythonLLMClient 推 BOM 草稿
2. AI Skill (可选): 编排 SampleToBomTool + BomCreateTool 形成多 Tool 协作
3. 绑定 intent `SAMPLE_TO_BOM` (Flyway 加配置行)

**DoD**: AIChat 输入触发 Tool, 返回 BOM 草稿 JSON.

#### Day 4 — RN UI: SampleRequestListScreen + DetailScreen

**目标**: 研发员能用 RN 创样品 / 看列表 / 审核

**具体步骤**:
1. `SampleRequestListScreen.tsx` — 列表卡片 + 状态 chip + 紧急程度颜色
2. `SampleRequestDetailScreen.tsx` — 样品照片 (用 Track C Attachment API) + 追踪记录 + 审核按钮
3. 集成 BomConfigScreen (Track D1) 当 approve 跳转
4. **强依赖 Track C Attachment**, 如 C 未 ship 用 mock placeholder

**DoD**: 研发员账号能创样品 → 审核 → 跳 BomConfigScreen.

#### Day 5 — 链路联调 + 钉钉通知 + Demo

**目标**: 完整业务流第一节跑通 + 录 demo

**具体步骤**:
1. 端到端测试: 研发员建样品 → 提交 → 主管审核 approve → BOM 自动建 + 报价任务建 + 钉钉群通知
2. Demo 录 (跟 Chat E 的 N31 demo 串成"完整业务流" 2 分钟视频)
3. PR `[Sprint2-F] N48 研发样品→BOM→报价`

**DoD**: PR + demo + STATUS 5 段.

---

### 5.3 Chat G — UX-A1 业务流程图导航 (10d 名义)

#### Day 1-2 — WorkflowVisualizer 组件抽象 (RN + Vue)

**目标**: 抽象一个能 driveby props 的流程图组件

**Day 1**:
1. Spec 读: `UX_BORROW.md` §A-1 + §F-1 (示意图)
2. RN `WorkflowVisualizer.tsx` props 设计:
   ```typescript
   interface WorkflowVisualizerProps {
     nodes: Array<{ id: string; label: string; status: 'PENDING' | 'IN_PROGRESS' | 'DONE'; count: number; onPress?: () => void; }>;
     orientation?: 'horizontal' | 'vertical';
     aiTriggerEnabled?: boolean;
   }
   ```
3. 实现节点圆 + 状态色 (粉/绿/蓝) + count badge + 连线 (svg / `react-native-svg`)
4. WorkflowNode 子组件

**Day 2**:
1. Vue `WorkflowBar.vue` — 等价组件给 web-admin
2. 颜色 token 注册到 Cretas Neo Minimal 设计系统 (现代化, 不抄宏见 raw 粉色)
3. Storybook / RN demo screen 验证 3 种节点配置

**DoD Day 2**: 组件可 `<WorkflowVisualizer nodes={[...]}/>` 渲染.

#### Day 3 — WorkflowStatsController 后端 API

**目标**: 5 业务模块的节点 count API

**具体步骤**:
1. `WorkflowStatsController` 5 endpoint:
   - `GET /api/mobile/{factoryId}/workflow-stats/sales` → `{ pending: 5, inProgress: 12, done: 87 }`
   - `.../purchase` `.../production` `.../finance` `.../inventory`
2. 每个 endpoint 内查现有列表 service 加 status filter + count
3. 缓存策略: 5 分钟 (Redis), 写操作 invalidate (per `.claude/rules/api-response-handling.md` 统一响应)

**DoD**: 5 个 curl 都返回 JSON.

#### Day 4-5 — RN BentoGrid 接入 (5 角色 HomeScreen)

**目标**: 首页加 1x2 大卡片 "今日工作流"

**Day 4**:
1. `SalesHomeScreen` + `PurchaseHomeScreen` + `ProductionHomeScreen` BentoGrid 加流程图卡片
2. 卡片点击节点 → 跳对应列表 (status filter 预填)
3. AI 增强: 卡片右上角"💬 跟 AI 说"入口

**Day 5**:
1. `FinanceHomeScreen` + `InventoryHomeScreen` 接入
2. 节点 count 自动刷新 (focus → refetch)
3. 测试 5 个角色 demo

**DoD Day 5**: 5 个 HomeScreen 流程图卡片可见可点击.

#### Day 6-7 — Web-Admin 5 模块顶部 bar 接入

**Day 6**:
1. `web-admin/src/views/sales/SalesOrderListView.vue` 顶部加 `<WorkflowBar>`
2. 同样接入 purchase / production

**Day 7**:
1. 接入 finance / inventory
2. 节点点击 → 列表 filter 联动 (用 Pinia tab store, 如果 Track 累积 tab 已 ship)
3. AI 增强按钮入口

**DoD Day 7**: 5 个 web-admin 列表页顶部都有 bar.

#### Day 8 — AI 触发逻辑

**目标**: 节点 + AI 入口可触发 AIChat 进入该节点 context

**具体步骤**:
1. 流程节点 `onPress` 长按 / AI 按钮点击 → AIChat sessionId 携带 `entryContext: { module: 'sales', node: 'pending' }`
2. AIChat 看到 context 自动提示 "你想批一下这 5 单待审吗?"
3. 跟 Track A Canvas (sessionId 通了) 配合验证多轮

**DoD**: AIChat entry context 验证.

#### Day 9 — 调优 + Bug 修

- 节点连线 svg edge cases (1 节点 / 5 节点 / 移动端 portrait)
- 颜色对比无障碍验证
- count badge 大数字 (>999) 显示

#### Day 10 — Demo + PR

1. Demo 录: 销售员登陆 → 首页流程图 → 点 "待审 5" → 列表 → 点单 → 审批
2. PR `[Sprint2-G] UX-A1 业务流程图导航`

---

### 5.4 Chat H — UX-A2 行末"操作 ▾"下拉 (10d 名义)

#### Day 1-2 — RowActionBottomSheet (RN) + RowActionMenu (Vue) 抽象

**Day 1** (RN):
1. `RowActionBottomSheet.tsx` props:
   ```typescript
   interface RowAction { icon: string; label: string; onPress: () => void; danger?: boolean; aiHint?: string; }
   interface RowActionBottomSheetProps { actions: RowAction[]; aiTriggerLabel?: string; visible: boolean; onClose: () => void; }
   ```
2. BottomSheet 顶部固定"💬 跟 AI 说..."入口 (按 UX_BORROW §F-2 示意图)
3. 长按 / swipe 手势 → 触发 BottomSheet

**Day 2** (Vue):
1. `RowActionMenu.vue` — element-plus el-dropdown 包装
2. 行末 "操作 ▾" 按钮 + 8-14 项
3. 顶部 "跟 AI 说" 入口

**DoD Day 2**: 组件 storybook 跑通.

#### Day 3 — useRowActions hook (RN) + 公共动作配置

**目标**: 抽公共动作 (转生产 / 转采购 / 转外购 / 退货 / 调拨 / 打印 / 复制 / 锁定 / 撤销审批 / 取消 / 删除)

**具体步骤**:
1. `useRowActions(entityType, entity)` 返回 action list, 根据 entity 状态过滤可用动作
2. RBAC 集成 — 仓管角色看到的 action 不含价格相关 (依赖 Track C RBAC 审计 ship 的 canViewPrice store)
3. AI Hint 字段 — 每个动作有自然语言映射 (例如 "锁定" → "我要锁住这单不让人改")

**DoD**: hook 单测 (10 状态 × 5 角色 = 50 组合).

#### Day 4-6 — RN 8 个 list screen 接入

**Day 4**: SalesOrderListScreen + PurchaseOrderListScreen
**Day 5**: ProductionPlanListScreen + InventoryListScreen + ShipmentListScreen
**Day 6**: ReturnOrderListScreen + TransferListScreen + WastageListScreen

每个 list 接入:
1. 长按 row → `<RowActionBottomSheet>` 弹
2. action list 用 `useRowActions`
3. 跑一遍 demo 验证

**DoD Day 6**: 8 个 RN list 全部接入.

#### Day 7-8 — Web-Admin 8 个 list view 接入

**Day 7**: web-admin 销售 / 采购 / 生产 / 库存 4 个 list
**Day 8**: web-admin 出货 / 退货 / 调拨 / 损耗 4 个 list

每个 view 接入:
1. 行末加 "操作 ▾" 按钮
2. 用 RowActionMenu + useRowActions hook (Vue composable 版本)
3. "打印 PDF" 调 Track C 单据打印 API

**DoD Day 8**: 8 个 web view 全接入.

#### Day 9 — AI 入口验证

1. BottomSheet "💬 跟 AI 说" 点击 → AIChat 进入, 携带 entity context
2. 例: 点销售单卡片 BottomSheet AI 按钮 → AIChat 自动提示 "你想对 SO-001 做什么?"
3. 多轮验证 (依赖 Track A sessionId)

**DoD**: AI 入口可用.

#### Day 10 — Demo + PR

- Demo 录: 长按销售单 → BottomSheet → 转采购 → 跑到 Chat E (N31) 的链路
- PR `[Sprint2-H] UX-A2 行末操作下拉`

---

### 5.5 Chat I — UX-A3 Sticky Footer 实时合计 (7d 名义)

#### Day 1 — StickyFooterSummary 组件抽象 (RN)

1. props:
   ```typescript
   interface SummaryStat { label: string; value: string | number; format?: 'currency' | 'number' | 'percent'; canViewPrice?: boolean; }
   interface StickyFooterSummaryProps { stats: SummaryStat[]; pagination?: { current: number; total: number; }; onAIAnalyze?: () => void; }
   ```
2. RN 实现: 底部 50px sticky bar (SafeAreaView 适配) + AI 图标点击 → AIChat
3. canViewPrice = false 的 stat 自动隐藏 (尊重 Track C RBAC ship 的 store)

**DoD**: 组件单测 + Storybook.

#### Day 2 — TableFooter.vue (Vue) + ListSummaryController 后端 API

1. `TableFooter.vue` — el-pagination + 合计 stats 同栏
2. `ListSummaryController` API:
   - `POST /api/mobile/{factoryId}/list-summary/{entityType}` body 传 filter conditions → 返回合计
   - 5 entity 起步: salesOrder, purchaseOrder, inventory, wastage, attendance
3. RBAC: 接 canViewPrice gate, 仓管看不到金额合计

**DoD**: curl 跑通 5 entity summary endpoint.

#### Day 3-4 — RN 10 list screen 接入

**Day 3**: sales / purchase / production / inventory / shipment 5 个
**Day 4**: return / transfer / wastage / attendance / quality 5 个

每个 list:
1. 列表底部 `<StickyFooterSummary>` 接入
2. 调 ListSummaryController API
3. AI 图标点击 → AIChat "分析这页"

**DoD Day 4**: 10 个 RN list 全接入.

#### Day 5-6 — Web-Admin 10 list view 接入

**Day 5**: web 销售 / 采购 / 生产 / 库存 / 出货
**Day 6**: web 退货 / 调拨 / 损耗 / 考勤 / 质检

升级现有 el-pagination 为 `<TableFooter>`:
1. 引入合计 stats
2. AI 分析按钮
3. canViewPrice 字段隐藏验证

**DoD Day 6**: 10 个 web view 全接入.

#### Day 7 — AI 分析联调 + Demo + PR

1. AI 分析入口: sticky footer 📊 按钮 → AIChat "给我这页的统计分析" → AIChat 调 SmartBI Tool
2. Demo 录: 销售员看列表 → 看 sticky footer 实时合计 → 点 AI 分析 → 收到结构化分析
3. PR `[Sprint2-I] UX-A3 Sticky Footer 实时合计`

---

## §6 5 Chat 整体时序 (3 周看板)

```
                Week 8                  Week 9                  Week 10
              Mon Tue Wed Thu Fri    Mon Tue Wed Thu Fri    Mon Tue Wed Thu Fri
Chat E (N31)  D1  D2  D3  D4  ▶PR
Chat F (N48)  D1  D2  D3  D4  D5/PR
Chat G (UX-1) D1  D2  D3  D4  D5     D6  D7  D8  D9  D10/PR
Chat H (UX-2) D1  D2  D3  D4  D5     D6  D7  D8  D9  D10/PR
Chat I (UX-3) D1  D2  D3  D4  D5     D6  D7/PR

Organizer (Chat 1):
  Week 8 W1-W2: review Chat E/F early PR
  Week 8 W3-W5: review Chat I PR + 集成
  Week 9 全周: 跟踪 G/H 大件
  Week 10 W1-W2: review G/H 最后 PR
  Week 10 W3-W5: Sprint 2 整体 demo 录 5 分钟 + 客户演示准备
```

Claude 加速假设 1.7x → E (4d) ≈ 2.5 实际, F (5d) ≈ 3 实际, G/H (10d) ≈ 6 实际, I (7d) ≈ 4 实际. **3 周 buffer 充足**.

---

## §7 每日 Status 同步机制

每个 worker chat 每天在新文件追加 1 段:
```
宏见竞品分析/04-最终决策/STATUS/SPRINT2_TRACK_{E|F|G|H|I}_STATUS.md
```

格式 (同 Sprint 1):
```markdown
## Day N (YYYY-MM-DD)
- ✅ 完成: X / Y / Z
- 🟡 进行中: A
- ❌ Blocker: B (需 organizer 协调)
- 明日计划: C / D
```

Organizer (Chat 1) 每天读 5 个 STATUS, 处理 blocker, 准备 review.

---

## §8 PR + Review 流程

每个 chat 完成一个 sub-project 推 PR:
1. PR 标题: `[Sprint2-{E|F|G|H|I}] N# 编号 项目名`
2. PR body 含: 涉及文件 / 测试方式 / 风险点 / 跟 Sprint 1 哪些 PR 的依赖
3. Organizer review + merge (用 `commit-commands:commit-push-pr`)
4. 多 chat 并行 PR 时, organizer 决定 merge 顺序避免冲突 (推荐顺序: I < E < F < H < G, 因为 I 改 list 底部 / E/F 是新功能 / H/G 大改 list 顶部)

---

## §9 Sprint 2 末验收清单 (Week 10 周五)

### 9.1 业务功能 (N31 + N48)

- [ ] 销售员创销售单 → 审批 → 自动生成缺料分析报告 (N31)
- [ ] AIChat "F006 销售单 SO-001 缺什么" 返回 chain-card (N31)
- [ ] 缺料报告自动推送钉钉群 (N31 + Track B1 集成)
- [ ] 研发员创样品 → 提交 → 主管 approve → BOM 自动建 + 报价任务建 (N48)
- [ ] AIChat "给样品 SR-001 建 BOM 类似 SKU-201" 返回 BOM 草稿 (N48)
- [ ] 样品审核 approve 后钉钉群通知销售 (N48 + Track B1)
- [ ] 样品照片上传 (N48 + Track C Attachment)

### 9.2 UX 重塑 (UX Top 3)

- [ ] 5 个角色 RN HomeScreen 都看到流程图 BentoGrid 卡片 (UX-A1)
- [ ] 5 个 web-admin 模块顶部都有 WorkflowBar (UX-A1)
- [ ] 流程节点点击 → 列表 filter 联动 (UX-A1)
- [ ] AI 入口在节点旁可触发 AIChat 进入该 context (UX-A1)
- [ ] 8 个 RN list 长按 → RowActionBottomSheet 弹 8-14 动作 (UX-A2)
- [ ] 8 个 web-admin list 行末 "操作 ▾" 下拉可用 (UX-A2)
- [ ] BottomSheet / RowActionMenu 顶部 "💬 跟 AI 说" 入口可触发 (UX-A2)
- [ ] 仓管角色 BottomSheet 中不显示价格相关动作 (UX-A2 + Track C RBAC)
- [ ] 10 个 RN list 底部都有 StickyFooterSummary (UX-A3)
- [ ] 10 个 web-admin list 分页器升级为 TableFooter (UX-A3)
- [ ] 仓管角色看不到金额合计 stat (UX-A3 + Track C RBAC)
- [ ] sticky footer "📊 AI 分析" 入口可触发 SmartBI 分析 (UX-A3)

### 9.3 演示与文档

- [ ] 5 分钟 Sprint 2 demo 视频 (Organizer 录, 串 N31 + N48 + UX Top 3)
- [ ] 客户演示路径文档 `docs/customer-demo/sprint2-demo-script.md`
- [ ] PR-link summary 表 (Organizer 在 STATUS 总账)
- [ ] **完整业务流第一节** 客户演示路径打通: 研发样品 → BOM → 报价 → 销售下单 → 审批 → 缺料分流 → 采购建议 / 生产任务 → 钉钉群通知

### 9.4 工程基础线

- [ ] `mvn spring-boot:run` 启动无报错
- [ ] `npx expo start` 启动无报错
- [ ] 测试环境 (10011 + 8084) deploy + smoke 跑通
- [ ] F006 prod 账号能登 + 关键路径未 regression
- [ ] Sprint 2 引入的 3 个 Flyway migration 都有 prod 部署计划 (per `.claude/rules/server-operations.md` ⛔ Smartbi 数据库 schema 变更 HARD RULE — 虽不是 smartbi, 同等严肃)

---

## §10 紧急联系点 (Organizer 心智 checklist)

| 信号 | Organizer 动作 |
|---|---|
| Sprint 1 Track D1 没 ship | Chat F (N48) 延后启动, 推到 Week 9; 让 Chat F 先做 SampleRequest Entity 准备 |
| Sprint 1 Track D2 没 ship | Chat E (N31) 推荐生产部分降级, 只做缺料 + 采购两段 chain-card |
| Sprint 1 Track A sessionId 还有 bug | UX-A1 + UX-A2 + N31 + N48 的 AI 入口全部降级 (用 single-turn, 不用 multi-turn) |
| Sprint 1 Track C Attachment 没 ship | Chat F 样品照片用 mock placeholder, 留 hook 等 C ship 后接入 |
| Chat G 或 H 工时 >> 12d | 协调减 scope: 先 5 模块 / 5 list 接入, 剩余推到 Sprint 3 |
| 任何 Chat 跟其他 Chat 共享文件冲突 | 立即停, organizer 协调 merge 顺序, 临时合并 stash |
| F006 prod 账号 regression | 立即 freeze 当天 merge, 回滚最近 PR 直到 fix |

---

## §11 关键参考文档清单 (5 chat 都要读)

| 文档 | 用途 |
|---|---|
| `01-客户档案/NUMBERING_MAP.md` | N# 编号双向映射 (Sprint 2 涉及 N31 + N48 + UX-A1/A2/A3) |
| `01-客户档案/SCHEMA_DESIGN.md` | DDL + Entity + API spec (重点 §sample_request, §workflow_stats, §list_summary 新加) |
| `04-最终决策/MUST_COPY.md` §B (N31, N48 详情) | 业务定义 + 客户原话 + 工时来源 |
| `04-最终决策/UX_BORROW.md` §A (Top 3 详情) + §F (示意图) | UX 模式定义 + Cretas 接入策略 |
| Sprint 1 全部 TRACK_*_BRIEF.md | 了解 Sprint 1 6 chat 各自交付什么 (强依赖关系) |
| `.claude/rules/ai-intent-tool-skill-architecture.md` | Tool 注册 / Skill 编排 (Chat E + F + G 全要遵守) |
| `.claude/rules/api-response-handling.md` | 统一响应格式 + 错误处理 |
| `.claude/rules/database-entity-sync.md` | PG 字段同步 + Flyway 规范 |
| `.claude/rules/field-naming-convention.md` | camelCase / snake_case 命名 |
| `.claude/rules/typescript-type-safety.md` | 禁用 `as any` |
| `.claude/rules/concurrent-edit-safety.md` HARD | 5 chat 并行最大风险 — commit 前必 git status |
| `.claude/rules/server-operations.md` | 双环境 deploy + Flyway 部署 |

---

## §12 元注意事项

1. **不要在 worker chat 里讨论战略** — 战略定在 Organizer (Chat 1)
2. **worker chat 严格按 §5.X 执行** — 跑偏会导致跨 chat 集成失败
3. **每个 chat 用 TaskCreate 跟自己进度**
4. **共享文件 (§3.2) 改前必 ping organizer** — 防 silent breaking
5. **每完成一项就 PR** — 不要等到 Week 10 大爆炸 PR
6. **Claude 加速预期 1.7-2x** — 名义工时是上限, 实际通常 5-6 折
7. **跟 Sprint 1 的依赖在 §4** — 启动前必查
8. **F006 prod 演示路径不能 regression** — 任何改动都先在 cretas_db (test) 验证

---

**下一步**: Sprint 1 (Week 6-7) 末 organizer 验收 6 个 track 全 ship → 开 5 个新 chat (E/F/G/H/I) → 把对应 §5.X 段落 + §3 + §4 复制过去 → 5 chat 并行开干 → 3 周完成 Sprint 2.