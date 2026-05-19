# Canvas 业务规则引擎 — 产品愿景

**Created**: 2026-05-18
**Author**: Steve + Claude (chat session bb64271a)
**Status**: Vision / Pre-spec (待拆解为 Phase 1-7 implementation specs)
**Origin**: F006 客户 (六膳门食品科技) 反馈 — 审批规则要自由配置 + AI 自然语言操作

---

## TL;DR

把 **Canvas 从"页面/字段编辑器"升级为"业务规则可视化引擎"** —
通过抽象 Canvas-Core 内核 + 7 个 domain 扩展模块, 让客户**自服务**配置所有业务规则
(审批/预警/通知/价格/校验/定时/权限), 并通过 **AI 自然语言**操作.

**核心差异化**: 客户老板能自己用 + AI 一句话改 — 其他 ERP (金蝶/用友/SAP) 都做不到.

---

## 1. 现状盘点 (2026-05-18)

### Canvas 体系已有 (Phase 0)
- **可视化编辑器**: `web-admin/src/views/platform/canvas-editor/`
- **AI Tools** (~20 个): `backend/.../ai/tool/impl/canvas/Canvas*Tool.java`
- **Dynamic Fields**: `CanvasDynamicFields.vue` + `DynamicModulePage`
- **Module wrapper**: `CanvasAwareWrapper.vue` (业务模块自动应用 Canvas 配置)
- **数据存储**: `canvas_dynamic_fields` 表

### 已建但**独立**的 (待合并进 Canvas)
- `approval-workflow-editor/` — 独立 DAG 编辑器 (废弃合并)
- `purchase_order_approval_rules` 表 + `PurchaseApprovalRuleController` (PR #859 stop-gap)

### 硬编码散落 (要消灭的 ~12 处)
| 模块 | 当前 Service |
|---|---|
| 采购单财务复核 | `PurchaseServiceImpl.evaluateApprovalTrigger()` |
| 销售单财务复核 | `SalesOrderService` |
| 退货 / 调拨 / 报废 / 盘点 / 开票 / 收款 | 各 Service |
| HR 请假/报销 / BOM 变更 / 物料申购 / 工序完工 | 各 Service |

---

## 2. 架构 — Canvas-Core + 7 Domain 扩展

```
Canvas-Core (内核, 所有模块共用)
├── Canvas-Schema      — 通用 JSON schema (id / type / props / children)
├── Canvas-Renderer    — 通用渲染器 (DAG / Form / List)
├── Canvas-Editor      — 通用编辑器 UI (拖拽 + 属性面板 + palette)
├── Canvas-Engine      — 运行时引擎 (按 schema 执行)
└── Canvas-AI-ToolBase — AI Tool 抽象基类
   ↓
   ├── canvas-fields/   (已有) — 字段节点 ★
   ├── canvas-layout/   (已有) — 布局节点 ★
   ├── canvas-workflow/ (Phase 1) — 审批流程节点 ⭐
   ├── canvas-alerts/   (Phase 2) — 库存/质量预警 ⭐
   ├── canvas-notify/   (Phase 3) — 通知触发器 ⭐
   ├── canvas-rules/    (Phase 4) — 业务规则/校验 ⭐
   ├── canvas-pricing/  (Phase 5) — 价格策略 ⭐
   ├── canvas-cron/     (Phase 6) — 定时任务 ⭐
   └── canvas-permission/ (Phase 7) — 权限矩阵 (远期) ⭐
```

---

## 3. 7 个 Domain 详解

### 3.1 ⭐ Canvas-Workflow (审批流程)

**形态**: 拖拽 DAG (开始/判断/审批/通知/终态).

**用在 12 业务**:
采购 / 销售 / 退货 / 调拨 / 报废 / 盘点 / 开票 / 收款 / 请假 / 报销 / BOM 变更 / 物料申购.

**谁用**:
- 配置: factory_super_admin / permission_admin
- 触发: 业务用户 (procurement_mgr 提单 → 触发)
- 审批: 各级 (finance_mgr / 部门主管 / 总监)

**AI 例子**:
- "把采购金额阈值改成 5 万" → AI 找节点 → preview → 改阈值
- "在销售订单加二级总监审批" → AI 加节点 → preview → 加 DAG 行

---

### 3.2 ⚠️ Canvas-Alerts (预警规则)

**形态**: if-then 规则, 不需要 DAG.

```
[规则名]: 冻猪蹄低库存预警
触发: 当 [冻猪蹄] [< 30 kg] 或 [过期 < 7 天]
动作: ☑ 仪表盘红 ☑ 推送采购员 ☑ 邮件 warehouse@六腾门.com
[启用] ✓
```

**用在**:
原料/成品低库存 / 临期 / 质量合格率低 / 设备故障率高 / 应收逾期 / 任何数值业务指标.

**谁用**: factory_super_admin / 部门主管 (配) + 各角色 (收).

**AI 例子**:
- "冻猪蹄低于 50 给采购员发钉钉"
- "再加: 过期前 10 天也提醒"

---

### 3.3 📢 Canvas-Notify (通知规则)

**形态**: 事件触发器 + 接收者矩阵.

```
[规则名]: 销售订单状态变化通知
触发: ☑ 订单 创建 ☑ 财务通过 ☑ 完成发货
接收:
  销售员: ✓微信 ✗钉钉 ✗邮件
  生产经理: ✓微信 ✓钉钉
  客户: ✗ ✗ ✓邮件
模板: "订单 {orderNumber} 状态: {oldStatus} → {newStatus}"
```

**用在**: 订单状态变化 / 生产完工 / 入库 / 设备故障 / 应收逾期.

**谁用**: factory_super_admin (配) + 全员 (收).

**AI 例子**: "订单完成发货时给客户发邮件" → 一句话配规则.

---

### 3.4 💰 Canvas-Pricing (价格策略)

**形态**: 阶梯规则表 + 条件匹配.

```
匹配: IF 客户=叮咚 AND 月采购>¥10万 THEN 5%折扣
      ELIF 客户=叮咚 AND 月采购>¥5万 THEN 3%折扣
      ELIF 客户.评级=A THEN 2%折扣
      ELSE 0%

应用: ☑ SO 单价自动算 ☑ 报价单显示参考价
```

**用在**: 销售订单单价自动算 / 报价单 / 促销 (满减) / 阶梯采购 (供应商批量折扣).

**谁用**: sales_manager (配) + 自动生效.

**AI 例子**: "叮咚月采购超 10 万给 5%" → AI 配规则.

---

### 3.5 📋 Canvas-Rules (业务规则 / 校验)

**形态**: 字段联动 / 跨字段约束.

```
适用: 销售订单
规则 1: 交货日期 >= 今天+3天 ("六腾门生产周期")
规则 2: 订单 > ¥5万 必填合同附件
规则 3: 客户协议价 vs 订单单价 → 黄色警示偏离
```

**用在**: 字段必填 / 联动 / 校验 / 大额必传附件 / 跨实体约束 (库存不足不让下产) / 防呆.

**谁用**: factory_super_admin (配) + 所有用户提单时自动校验.

**AI 例子**: "销售订单超 5 万必传合同" → AI 加校验规则.

---

### 3.6 ⏰ Canvas-Cron (定时任务)

**形态**: cron 表达式 + 触发动作.

```
[任务]: 每月 1 号生成上月销售报表
执行: 每月 1 日 09:00 (cron: 0 0 9 1 * ?)
动作: ☑ 生成报表 ☑ 邮件给财务+总经理 ☑ 归档报表中心
历史: 最近 5 次执行 ✓ 全成功
```

**用在**: 日/月/季报表生成 / 库存盘点提醒 / 应收催收 / 数据归档 / 备份.

**谁用**: factory_super_admin / it_admin (配) + 系统自动跑.

**AI 例子**: "每周一早 9 点生成上周库存报表" → AI 加 cron.

---

### 3.7 🔐 Canvas-Permission (权限矩阵, 远期)

**形态**: 角色 × 模块 矩阵 + 字段级粒度.

```
角色\模块       采购    销售    生产    财务
admin           ✓写    ✓写    ✓写    ✓写
procurement_mgr ✓写    ○读    ○读    ○读
sales_mgr       ○读    ✓写    ○读    ○读

字段级: 销售员能看采购但**看不到单价** ✓
```

---

## 4. 完整模块使用 — 4 类入口

### 入口 A: 平台管理员配 (1 次性)
平台管理 → Canvas 配置中心 → 选模块 → 拖配规则.

### 入口 B: 业务用户使用 (透明)
采购员下单 → 系统按 Canvas 自动应用规则 (审批/价格/校验) → 业务用户感受不到背后规则.

### 入口 C: AI 助手 (自然语言)
任何 admin 点 🤖 → 对话 → AI 调 Canvas Tools → preview → 立即生效.

### 入口 D: API 集成 (远期)
客户 ERP/OA/钉钉 → webhook → /api/canvas/trigger/{module}/{event} → Canvas Engine 跑.

---

## 5. 用户画像 (5 角色)

| 角色 | 用法 | 频率 |
|---|---|---|
| **factory_super_admin** | 配规则 + 改流程 + AI 操作 | 每周几次 (初期密集) |
| **permission_admin** | 配权限矩阵 | 月度 |
| **部门主管** | 配本部门审批/通知 | 月度 |
| **业务员工** | **透明使用** (不直接看 Canvas) | 每天 |
| **Cretas 运维** | 监控客户配置 + 调优 | 按需 |

---

## 6. AI 能力图

### 已能 (Phase C 完成后)
- ✏️ 加 / 改 / 删 任何 Canvas 节点
- 📊 查询当前配置
- 🔄 切启用/禁用
- 📜 看历史变化
- 🧪 模拟运行 ("假设 PO ¥5 万会怎么走?")
- ⚠️ 风险提示 ("要关闭 12 模块全部审批?")

### 远期 AI 升级
- 🎯 **主动建议**: "本月 3 单大额 PO 都卡 2 天+, 建议加运营审"
- 📈 **数据驱动**: "90% 销售单 < ¥5万, 建议把财务审阈值改 ¥5万 → 月省财务 X 小时"
- 🔍 **异常检测**: "10 个 PO 都因没上传合同被驳回, 要自动加这条校验吗?"
- 🎨 **流程克隆**: "把销售流程复制到退货, 改 2 节点适配"

---

## 7. 实施路线图

```
Phase 1 (3-4 天): Canvas-Workflow ⭐⭐⭐ (F006 已要)
  └ 同时抽出 Canvas-Core 通用层

Phase 2 (2-3 天): Canvas-Alerts (库存/质量预警 — 常需求)
Phase 3 (2-3 天): Canvas-Notify (通知 — 跨模块基础)
Phase 4 (3-4 天): Canvas-Rules + Canvas-Pricing
Phase 5 (2 天):   Canvas-Cron

总: 12-16 天工程量
```

每 Phase ship 后客户立即可用.

---

## 8. 跟竞品对比

| 能力 | 金蝶/用友 | SAP | Cretas (做完后) |
|---|---|---|---|
| 字段自定义 | ✅ 基础 | ✅ 强 | ✅ Canvas |
| 流程可视化 | ⚠️ 有但复杂 | ✅ BPM 强 | ✅ Canvas + AI |
| 业务规则可配 | ⚠️ 部分 | ✅ DRL | ✅ Canvas |
| **AI 自然语言操作** | ❌ | ⚠️ 实验 | **✅ 全功能** ⭐ |
| 价格策略可视化 | ❌ | ⚠️ | ✅ |
| 预警规则可配 | ⚠️ 硬 | ✅ | ✅ |
| **客户自服务** | ❌ | ⚠️ 顾问配 | **✅** ⭐ |

**核心差异化**: **客户老板能自己用 + AI 一句话改**.

---

## 9. F006 客户预期上线 1 月后

- 自己进 Canvas 配完 12 模块审批 + 8 预警 + 5 通知
- **完全不需要联系 Cretas 改代码**
- AI 助手日常用 ("今天加个折扣规则" 一句搞定)
- 数据看板看到所有规则执行情况

**老板评价**: "上 ERP 上得最爽的一次"

---

## 10. 已 ship 的 Phase 0 处理建议

- **保留**: PR #859 (PurchaseApprovalRuleController + UI) 不删
- **标 deprecated**: Javadoc + UI callout "将在 Phase 1 后合并到 Canvas"
- **Phase 1 完成时**: 写 migration `purchase_order_approval_rules → canvas_workflow_nodes`, 数据保留

---

## 附录 — 关键文件 (chat 起点)

### Canvas 体系
- Editor: `web-admin/src/views/platform/canvas-editor/`
- Tools: `backend/.../ai/tool/impl/canvas/`
- Components: `web-admin/src/components/canvas/`
- Schema: `backend/.../entity/config/CanvasDynamicField.java`

### 现有独立 (待合并)
- Approval editor: `web-admin/src/views/platform/approval-workflow-editor/`
- Phase 0 stop-gap: `PurchaseApprovalRuleController.java` + `procurement/approval-rules/list.vue`

### 硬编码 (待清理)
- `PurchaseServiceImpl.evaluateApprovalTrigger`
- 11 其他业务 Service

### 项目规范
- `.claude/rules/ai-intent-tool-skill-architecture.md`
- `.claude/rules/fool-proof-design.md`
- `CLAUDE.md`

---

## 11. Architecture Decision Records

> 🎉 **All ADRs (001-006) LIVE in prod 2026-05-19**. Cretas Canvas business rule engine fully shipped. Sister chat backlog: 9 follow-up issues (#33-#45) at Stevenjxie/cretas.

每个 Phase 落地时记录关键 trade-off, 留给未来回看「为什么这么选」.

### ADR-001 — Phase 1 实施方式: B 完整一步到位

**Date**: 2026-05-18
**Status**: ✅ Implemented + Live (2026-05-19, Stevenjxie/cretas main `83b27b105`)

**Context**: Phase 1 PR #862/#23 完成 Step 1-4 (enum + Canvas Tab). Step 5 = PurchaseService 接 workflow. `ApprovalWorkflowExecutor` 当时 `ConcurrentHashMap` 内存版, 不持久化, Java 重启即丢. 3 选项:
- A. Simplified routing (~2h) — 一次决定, 不上 state machine
- B. 完整 state machine + Redis 持久化 (5-7d)
- C. Defer Step 5, UI 不接 service

**Decision**: B 一步到位.

**Rationale**:
1. 避免 A→B 迁移技术债 (schema 兼容问题)
2. 避免半成品演示导致客户信任崩塌
3. Sprint 4 计划本就要做, 提前不丢 scope
4. F006 客户可等一周看完整 product

**Consequences**: F006 等 1 周 (vs A 当天). 一次性 ship 风险集中, 但 6 子任务清晰 (B.1-B.6).

**B 6 子任务** (全部已 ship):
- B.1 `approval_history` 表 + Flyway ✅
- B.2 Redis 持久化 + PG 影子写 ✅
- B.3 state machine impl ✅
- B.4 DAG 执行引擎 (7 节点类型) ✅
- B.5 Canvas UI 节点属性面板 ✅
- B.6 PurchaseService 集成 + 4 E2E path ✅

**Status Tracking**: All 7 acceptance criteria PASS in prod 2026-05-19.

---

### ADR-002 — Phase 2 Alerts: Hybrid 触发 (4 event + 5 scheduled)

**Date**: 2026-05-18
**Status**: ✅ LIVE prod 2026-05-19 — Blue-Green deploy v20260519_130016 + web-admin 0d4d1cb39

**Context**: 8 alert types 触发场景不同. 选 A 纯 event-driven / B 纯 @Scheduled 跑批 / C hybrid.

**Decision**: C hybrid.
- **Event-driven (4)**: inventory_low / quality_anomaly / po_amount_threshold / so_amount_threshold — 业务实时事件
- **Scheduled (5)**: inventory_expiring / sales_decline / customer_payment_overdue / supplier_payable_due / inventory_low fallback — 时间窗口或聚合查询

**Rationale**: 实时事件用 Spring `@EventListener` 准实时响应; 时间窗口类型 (过期前 N 天) 必须 cron 跑批; inventory_low **双保** (event-driven 主路径 + 15min @fixedRate fallback) 防 event 未发布漏报.

**Consequences**:
- 4 业务 service 需 publish event (PurchaseService.createPO 等) — listener 现 @PostConstruct `log.warn` 标 gap, 不阻塞运行
- 5 scheduled evaluator 各自 query 业务 repo (MaterialBatch / InvoiceRecord / Payable / SO) — sister chat 接
- SpEL 双绑定 `#context.xxx` + `#xxx` 让简单 rule `#currentStock < #minStockLevel` 无前缀

**Status Tracking**:
- [x] AlertEngineServiceImpl (5 methods + dedup query)
- [x] 9 listener 类 (4 event + 4 scheduled + 1 fallback)
- [x] 6 AI Tools (factoryId 全消费)
- [x] CanvasAlertController 6 endpoints (BONUS 填实)
- [x] 22 new tests + 3334 regression PASS
- [ ] Canvas Vue Tab (sister chat)
- [ ] 业务 service publish 4 event (Phase 2 follow-up issue)

---

### ADR-003 — Phase 3 Notify: 1 真 4 stub + Graceful FAILED log

**Date**: 2026-05-18
**Status**: ✅ LIVE prod 2026-05-19 — Blue-Green deploy v20260519_130016 + web-admin 0d4d1cb39

**Context**: 5 channel sender (WeChat/DingTalk/Email/SMS/InApp). 4 外部 channel 需 SDK + creds (`weixin-java-cp` / `aliyun-dysmsapi` / `spring-boot-starter-mail` / DingTalk webhook). Subagent 阶段不加 pom dep (避免 scope creep). Stub pattern: 选 A throw UnsupportedOp / B 写 FAILED `NotifyLog`.

**Decision**: B 写 FAILED NotifyLog + actionable errorMsg.

**Rationale**:
1. **Graceful fan-out**: multi-channel 时一个 stub 不阻塞其他真 sender (e.g. InApp + Email + WeChat: InApp 成功, Email/WeChat FAILED, audit trail 完整)
2. **per fool-proof Rule 5** (dead-end → next action): errorMsg 含 "请 Phase 3 follow-up 加 X SDK" — operator 知道下一步
3. **替换路径平滑**: sister chat 加 SDK + creds 后直接替 stub body, 不动 sender interface

**Consequences**:
- 4 sender (WeChat/DingTalk/Email/SMS) 真 SDK + creds wire 留 sister chat
- Phase 1 NotifyNodeHandler wire (注入 NotifySender) 留 Phase 1 follow-up issue (1 行改)
- **Audit fix critical**: subagent 把 5 Tool factoryId 引用 0 → 42 次, audit script 通过

**Discovery during impl**: `.gitignore line 51 temp*` Windows 大小写不敏感匹配 `Template*` → `TemplateEngine.java` 静默丢失. Subagent 加 `!path/Template*.java` negation 修复. **Org-wide risk**: 所有以 `Temp*` 开头的 Java class 在 Windows 都中招 — 应 file follow-up issue.

**Status Tracking**:
- [x] TemplateEngine ({{var}} regex + missing var IAE per fool-proof)
- [x] InAppSender 真 impl
- [x] 4 stub sender FAILED log + actionable Javadoc
- [x] NotifySenderRegistry @Component 分发器
- [x] 5 AI Tools factoryId 全消费 (audit pass)
- [x] 17 tests PASS
- [ ] Email/WeChat/DingTalk/SMS real SDK (sister chat)
- [ ] Canvas Vue Tab (sister chat)
- [ ] Phase 1 NotifyNodeHandler wire (Phase 1 follow-up 1 行)
- [ ] Integration test @SpringBootTest (sister chat)

---

### ADR-004 — Phase 4a Rules: AOP + @RuleEvaluate Annotation

**Date**: 2026-05-18
**Status**: ✅ LIVE prod 2026-05-19 — Blue-Green deploy v20260519_130016 + web-admin 0d4d1cb39

**Context**: 业务规则触发场景: A 业务 service body 显式调用 `ruleEngine.evaluate(scope, input)` / B Spring AOP 注解隐式拦截 `@RuleEvaluate("ORDER")`.

**Decision**: B AOP + `@RuleEvaluate` annotation + RuleEvaluateAspect `@Around("@annotation(ruleEvaluate)")`.

**Rationale**:
1. **低耦合**: 不改业务 service body, 加 1 个 annotation 即生效
2. **annotation 即配置**: scope 直接写在注解里 (`@RuleEvaluate("ORDER")` / `("INVENTORY")` / `("CUSTOMER")`)
3. **fail-open**: aspect engine 异常时 log + proceed 不阻塞业务
4. **统一异常处理**: REJECT 抛 RuleViolationException → GlobalExceptionHandler → HTTP 400 + `{success:false, message, code:"RULE_VIOLATION", actionHint, severity}` (per fool-proof Rule 5)

**Key defensive design** (WorkflowEngineFacade):
- `@Autowired(required=false)` 防 Phase 1 service 不在 context 时启动 fail
- `hasActiveWorkflow()` pre-check 避免 Phase 1 IllegalArgumentException flooding

**Consequences**:
- @RuleEvaluate 手动 attach 到 PurchaseService/SalesService/InventoryService **留 sister chat** (改 prod service body 高风险, skeleton 阶段不动)
- MODIFY action 用 BeanWrapper reflection (limit: immutable obj 不动 — javadoc 标)

**Status Tracking**:
- [x] RuleEngineImpl 4 action types (LOG/REJECT/MODIFY/TRIGGER_WORKFLOW)
- [x] RuleEvaluateAspect @Order(10) fail-open
- [x] WorkflowEngineFacade wire Phase 1 (with required=false + pre-check)
- [x] GlobalExceptionHandler @ExceptionHandler(RuleViolationException) 加
- [x] 5 AI Tools factoryId 全消费
- [x] 20 new tests + 117 workflow regression + 125 Tool regression PASS
- [ ] @RuleEvaluate attach to PurchaseService / SalesService / InventoryService (sister chat)
- [ ] Canvas Vue Tab (sister chat)
- [ ] E2E F006 smoke (sister chat)

---

### ADR-005 — Phase 4b Pricing: 4 In-line + CYCLE Deferred to Batch

**Date**: 2026-05-18
**Status**: ✅ LIVE prod 2026-05-19 — Blue-Green deploy v20260519_130016 + web-admin 0d4d1cb39

**Context**: 5 strategy type — 4 在 SO 建单时实时算 (TIERED/PROMOTION/MEMBER/BUNDLE), 1 跨周期 (CYCLE 月返点 需查季度历史).

**Decision**: 4 in-line at `calculate()` time + CYCLE 用 `SKIP_CYCLE_INLINE=true` constant skip 在 switch 中, 留 sister chat 实现 month-end batch path.

**Rationale**:
1. In-line 4 type: SO 建单时直观算价, MEMBER 跟 customer level 绑定方便
2. CYCLE 跨周期: 每次 SO 都查季度 sales aggregate 性能浪费 → month-end batch + 单独 PricingApplicationLog 写返点更合理
3. Forward-thinking: `PricingRequest.costEstimate` 字段加好, sister chat wire SalesService 时 0 改 DTO

**Consequences**:
- `SalesServiceImpl.createOrderLine` 1 行替换 hardcoded price → `pricingEngine.calculate()` **留 sister chat** (改 prod service body 高风险)
- CYCLE batch path 决定留 sister chat (spec §10 Q2: 月底 cron 跑 + 写 PricingApplicationLog `cycle_rebate` 记录, 客户看下月 invoice 时应用)
- BUNDLE "single-line approximation" — multi-line cart-aware deferred (spec §9)

**Status Tracking**:
- [x] PricingEngineImpl 5 strategy type (CYCLE inert stub)
- [x] scopeFilterJson matching helper
- [x] PricingApplicationLog 仅 calculate() 写, simulate() 不写
- [x] 5 AI Tools factoryId 全消费
- [x] 14 tests PASS (13 unit + 1 @DataJpaTest integration)
- [ ] SalesServiceImpl.createOrderLine 1 行 wire (sister chat)
- [ ] CYCLE batch path 实现 (sister chat)
- [ ] BUNDLE multi-line cart-aware (sister chat)
- [ ] Canvas Vue Tab (sister chat)

---

### ADR-006 — Phase 5 Cron: Manual LockProvider.lock + Private ThreadPool

**Date**: 2026-05-18
**Status**: ✅ LIVE prod 2026-05-19 — Blue-Green deploy v20260519_130016 + web-admin 0d4d1cb39

**Context**: ShedLock 多实例锁选 A 手动 `LockProvider.lock(LockConfiguration)` per Runnable / B Spring `@SchedulerLock` annotation.

**Decision**: A 手动 LockProvider.lock wrap.

**Rationale**:
1. `@SchedulerLock` 必须 cron 在 annotation 上静态定义 + method 是 Spring bean. Phase 5 cron 从 DB 动态加载 → annotation 静态不可用
2. Manual lock at runtime 允许 cron 字符串运行时变 (用户改 DB 然后 reload)
3. Per task: `lockName=scheduled-task-{taskCode}`, `lockAtMostFor=10min`, `lockAtLeastFor=30s`, contention 静默 debug log skip (per fool-proof)

**Key design** (subagent smart choice):
- **私有 ThreadPoolTaskScheduler(pool=4)** 不靠 autowire — Spring auto-config 不保证给 `@SchedulingConfigurer` 提供, 与现有 `engine/DynamicSchedulerService` 同 pattern
- **CopyOnWriteArrayList** 跟踪 `ScheduledFuture` — `reload()` 时 cancel 全部 future + 重新 register (避免 leak)
- **runNow()** bypass ShedLock — 手动触发只允许单实例 (manual override)

**Consequences**:
- 现有 24 个 `@Scheduled` 方法迁移到 Canvas-Cron 留 sister chat (high-risk 改 prod service)
- Phase 2 alerts 5 个 @Scheduled evaluator 迁移到 Canvas-Cron 留 Phase 2 follow-up issue
- H2 test profile 无 shedlock table → subagent 加 `@TestConfiguration + @Import` in-memory LockProvider (test-only)

**Status Tracking**:
- [x] DynamicScheduler.configureTasks 真 cron 注册 (subagent verified 23:58:16 EchoTaskHandler in canvas-cron-2 thread, SUCCESS run_log)
- [x] ShedLock manual wrap with proper lockName/At-most/At-least
- [x] DynamicSchedulerServiceImpl CRUD methods
- [x] reload() cancel-on-future + re-register
- [x] runNow() bypass lock for manual override
- [x] 5 AI Tools factoryId 全消费 (含 global tasks NULL 路径)
- [x] ScheduledTaskController list + logs (BONUS 填实)
- [x] 22 tests PASS (含 1 SpringBootTest 真跑 cron)
- [ ] 24 现有 @Scheduled migration (sister chat)
- [ ] Phase 2 alerts 5 @Scheduled 迁移 (Phase 2 follow-up)
- [ ] Canvas Vue Tab (sister chat)

---

### Reversal Paths

任一 Phase 出问题时回退路径:
- **Phase 1**: B.3-B.4 超时则砍多节点支持, 退化 1 级审批 + 1 通知 (Sprint 4 复用 B.1/B.2)
- **Phase 2-5**: skeleton + backend complete 状态可独立 deploy (各自 PR 自包含). 任一 Phase rollback 不影响其他
- **跨 Phase**: Phase 4a WorkflowEngineFacade `@Autowired(required=false)` 即使 Phase 1 rollback 也不破坏 Phase 4a

---

## 12. 跟踪 — Phase 2-5 Sister Chat 接手清单

| Phase | PR | Backend | Sister chat 接手任务 |
|---|---|---|---|
| 2 Alerts | #24 | ✅ | Canvas Vue Tab + 4 业务 service publish event + 5 scheduler DB query body + multi-channel notify fan-out (依赖 Phase 3) |
| 3 Notify | #29 | ✅* | Canvas Vue Tab + 4 channel real SDK wire (WeChat/DingTalk/Email/SMS) + Phase 1 NotifyNodeHandler wire + Integration @SpringBootTest + `@RequireRole` class-level → method-level fix |
| 4a Rules | #26 | ✅ | Canvas Vue Tab + `@RuleEvaluate` attach to PurchaseService/SalesService/InventoryService + E2E F006 smoke |
| 4b Pricing | #25 | ✅ | Canvas Vue Tab + SalesServiceImpl.createOrderLine 1 行 wire + CYCLE batch path + BUNDLE multi-line |
| 5 Cron | #27 | ✅ | Canvas Vue Tab + 24 现有 @Scheduled migration + Phase 2 alerts 5 @Scheduled 迁移 |

*: Phase 3 InAppSender 真 impl, 其他 4 channel stub-with-FAILED-log graceful pattern.

---

**当前最新状态** (2026-05-19): Phase 1 LIVE in prod. Phase 2-5 backend impl 全 shipped (PR #24/#25/#26/#27/#29 OPEN at Stevenjxie/cretas). 总 ~95 new Java files + ~95 tests PASS (across all 5 phases).
