# Canvas 配置画布 — 缺口审计 + 补全计划 (handoff)

**日期**: 2026-05-29
**审计方式**: 2 个 Explore subagent 并行扫前端+git/docs/issue → 我(主 chat)逐项**代码级核实**(不信文档/issue 的二手结论)
**给谁**: 隔壁 chat 执行补全
**重要**: subagent 报告里有 **5 处过时/错误**(见下"已修正"),本文档只列**我代码核实过**的真实缺口。

---

## ⚠️ 先修正 subagent 的错误结论(避免隔壁 chat 白做)

| subagent 说 | 实际(我核实) | 证据 |
|---|---|---|
| Pricing 是 skeleton,抛 `UnsupportedOperationException` | **错**。`PricingEngineImpl` 5 种策略全有真实 `apply*` 实现 | `PricingEngineImpl.java:291-303` switch 全分支真方法 |
| Pricing BUNDLE 未实现 | **错**。BUNDLE 有真实单行实现 | `applyBundle()` `:420` 有真 body |
| 附件字段 UI 缺失 | **错**。附件已在字段类型选择器 | `usePageEditor.ts:21` `{type:'ATTACHMENT',label:'附件'}` |
| 字段权限是 stub | **错**。已落地,真持久化 | `PermissionMatrix.vue:76` `saveModuleConfig→factory_module_configs.permission_config` |
| "Production jar v20260529_150503" | **错**。那是**测试环境**(47:10011),生产没动 | 本 session 只 `--env test` |

**根因**: subagent 依据的 docs/issue 是 2026-05-19 写的(当时是骨架),代码在 Sprint 11 已推进,文档没更新。**结论:信代码,不信旧 issue。**

---

## ✅ 真实缺口清单(全部我代码核实)

### P0 — 影响"告警到底能不能用"

**GAP-1: Phase 2 事件驱动告警是"哑的" — 业务服务不发事件**
- **现象**: 告警引擎双路径都有代码 —
  - 定时扫描(5 个 `@Scheduled` 真激活,**会触发**): 库存到期(8am)/付款逾期(9am)/库存低(每15min)/销售下滑(10am)/应付到期(9:30am)
  - 事件驱动(监听器就绪但**永不触发**): `AlertPoAmountThresholdListener`/`AlertSoAmountThresholdListener`/`AlertQualityAnomalyListener`/`AlertInventoryLowListener` 都是 `@EventListener @Async`,等 `PurchaseOrderCreatedEvent`/`SalesOrderCreatedEvent`/`QualityInspectionCompletedEvent`/`InventoryStockChangedEvent`
- **根因(核实)**: `grep "new PurchaseOrderCreatedEvent\|new SalesOrderCreatedEvent\|new QualityAnomalyEvent"` → **0 个 publisher**。业务服务(PurchaseService/SalesService/QualityInspectionService/库存变更点)**从不发这些事件** → 监听器空转。
- **证据**: `service/alerts/listener/AlertPoAmountThresholdListener.java:46` 自己 log warn "Listener registered — caller must publish PurchaseOrderCreatedEvent"
- **修法**: 在 4 个业务服务的写操作末尾注入 `ApplicationEventPublisher` 并 `publishEvent(new XxxEvent(...))`。监听器+引擎已就绪,只缺"发"这一步。
- **要改的文件**: `PurchaseOrderService(createOrder)` / `SalesOrderService(createOrder)` / 质检完成 service / 库存变更 service(参考已经在用 `ApplicationEventPublisher` 的 `MaterialBatchServiceImpl`/`InvoiceServiceImpl` 写法)
- **验收**: 创建 PO 金额 ≥ 阈值 → `alert_events` 表出现一行 + 通知触发(对应 issue #33 的验收标准)
- **工时**: ~1-1.5 天(4 个发布点 + 单测 + 1 个端到端 smoke)。**P0**

### P1 — 配置能力可见缺口

**GAP-2: 公式 builder 无 UI**
- **现象**: 后端 + API 齐(`canvasApi.ts:49 getFormulas` / `:52 setFormula` / `PUT /v2/formulas/{code}`),但**没有任何 Vue 组件调用**(grep `getFormulas` 在 src 里只有 canvasApi.ts 定义处)。
- **修法**: 在 canvas-editor 加一个 `FormulaEditor.vue` tab,消费现有 API(列公式/编辑聚合逻辑/绑定模块)。后端不用动。
- **验收**: UI 能新建/编辑一条聚合公式(如按税率分组汇总)并保存,刷新后仍在。
- **工时**: ~0.5-1 天。**P1**

**GAP-3: publish-window + completeness-check 后端端点不存在**
- **现象**: 前端 `canvasApi.ts:122-157` 对这俩有 **TODO 兜底**(发布窗口 hardcode 22:00-6:00;完整性检查永远返回 `{passed:true}`)。后端 controller `grep` **无匹配** → 端点根本没实现。
- **修法**: 在 config/v2 controller 实现 `GET/PUT /config/publish-window`(存工厂级发布窗口)+ `GET /config/completeness-check`(真跑配置完整性校验)。
- **验收**: 改发布窗口能存;完整性检查返回真实未配置项列表。
- **工时**: ~0.5 天。**P1**

### P2 — 高级/锦上添花

**GAP-4: APS 权重无 UI**
- **现象**: 后端 `CanvasSetApsWeightTool.java` 存在,前端 `grep ApsWeight` **0 命中** → 只能用自然语言(AIChat)配,没有表单 UI。
- **修法**: 加一个排程权重配置小面板(或确认走 AIChat 即可,不补 UI)。
- **工时**: ~0.5 天。**P2**(也可不做,自然语言已能配)

**GAP-5: Pricing CYCLE 月末返利批处理未实现**
- **现象**: `PricingEngineImpl.java:78 SKIP_CYCLE_INLINE=true` + `:462 applyCycle()` 故意返 ZERO(注释:"月末批处理,非行内")。即 TIERED/PROMOTION/MEMBER/BUNDLE 都行内算,**只有 CYCLE 这种"按月累计返利"的批处理 job 没写**。BUNDLE 是单行近似(多行购物车感知是 issue #43,也未做)。
- **修法**: 写一个月末定时 job 查客户当月累计销售 → 按 tier 算返利。仅当客户要周期返利时才需要。
- **工时**: ~1-2 天。**P2**(看客户是否真要 CYCLE)

### P3 — 已知延后(非阻塞)

| GAP | 现状 | issue | 工时 |
|---|---|---|---|
| **GAP-6** 通知外部 SDK(短信/微信/钉钉/邮件) | 站内通知能用,外部渠道延后 | #41 | ~2-3 天 |
| **GAP-7** 24 个旧 `@Scheduled` 迁移到 DynamicScheduler | Canvas Cron 能用,旧硬编码定时没迁 | #34 | ~1-2 天(机械) |

---

## 建议执行顺序 + 并行

```
P0 GAP-1 (事件告警) ─── 独立,先做 ─── 最影响"告警能用"
P1 GAP-2 (公式UI)   ─┐
P1 GAP-3 (2端点)    ─┼── 三者互不依赖,可并行(不同文件)
P2 GAP-4 (APS UI)   ─┘
P2 GAP-5 (CYCLE批)  ─── 独立,看客户需求决定做不做
P3 GAP-6/7          ─── 最后,非阻塞
```

- **GAP-1/2/3 可三个 subagent 并行**(后端事件 / 前端公式UI / 后端2端点 —— 文件不重叠)。
- 改后端共享文件(业务 service)走 **git worktree 隔离**(并发编辑安全规则)。
- 全部只部 **test**,生产要等你明确说"部 prod"。

## 验收总则(每个 GAP 必做)
1. 部署 test + 实测(不是"代码合了就算完")
2. 留证据(API 调用截图 / 表里出现的行 / E2E 截图)
3. 防呆 4 要素错误 UX(失败时 message 含 什么/为什么/下一步)

---

## 审计边界(诚实)
- **我代码核实**: GAP-1(事件 publisher 缺失)、GAP-2(公式无 UI 消费)、GAP-3(端点无)、GAP-4(APS 无 UI)、GAP-5(CYCLE 故意 skip) —— 这 5 个是看了源码确认的。
- **未实跑**: 我没在 test 环境实跑 Canvas E2E 去看告警到底触发不触发(代码层确认事件没发布,但没动态验证);隔壁 chat 做 GAP-1 时第一步应**先实跑确认现状**(创建 PO 看 alert_events 是否真无行),再动手。
- **GAP-6/7** 来自 issue/doc,我没深挖代码,工时是粗估。
