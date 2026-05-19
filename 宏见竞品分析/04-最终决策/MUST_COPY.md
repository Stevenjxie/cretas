# 必抄清单 (MUST_COPY) — 三重过滤后

> **本文件用途**：唯一行动清单。**只列**通过 3 重过滤的项目：
> 1. **Cretas 真没有**（fresh grep 验证，AUDIT-Final A）
> 2. **客户真需要**（六扇门 4 次会议 P0/P1，AUDIT-Final A）
> 3. **AI 替代不了**（不是 🟢 真替代，AUDIT-Final C）
> 4. **当前没在做**（不是 ✅ 已 ship 或 🟡 PR 进行中，AUDIT-Final B）
>
> **取代关系**：本文件 = 行动指南，BORROW_LIST 71 项 = 素材底稿。
>
> **方法**：3 路 fresh superpower 审计（FINAL_A_NEEDS_VS_CRETAS + FINAL_B_INPROGRESS + FINAL_C_AI_REPLACE）

---

## A. 三重过滤后的统计

| 过滤后类别 | 数量 | 工时估算 |
|---|---|---|
| 🔴 **P0 必抄**（客户已反复要求 + 0 代码） | **8 项** | ~32 人天 |
| 🟡 **P0 必修**（已开始但残留 bug / 半成品） | **5 项** | ~12 人天 |
| 🟠 **P1 必抄**（业务流完整性 + AI 替代不了） | **10 项** | ~40 人天 |
| ✅ **已 ship 别动**（4 项已交付） | 4 项 | 0 |
| 🟢 **AI 真替代，不抄**（12 项纯 AI 替代） | 12 项 | 0 |
| 🔵 **Hybrid，已有基础**（30 项 UI 在 + AI 辅） | 30 项 | 增量优化 |
| ⚪ **不抄**（合规反对/PR-driven/客户群不需要） | 22 项 | 0 |

**总工时**: ~84 人天 ≈ **17 周 ≈ 4 个月单人 / 2 个月双人**

vs 之前 220 人天估算——**压缩 60%+**，因为：
1. 排除已 ship 项（RBAC/列宽/收货分次显示已交付）
2. 排除 AI 真能替代项（NL Query / Insight Push 不需建传统 UI）
3. 排除"理论需要但客户不催"项

---

## B. 🔴 P0 必抄 — 客户已反复要求 + Cretas 0 代码 (8 项 / ~32 人天)

### N49. 钉钉机器人 PoC ⭐ 战略级
- **来源**：第二次会议 (2026-03-18) 张权-昆山反复强调
- **客户原话**："我们现在出了微信就是钉钉在用嘛，呃，这个我们日常跟这个系统去交互，用钉钉也比较方便"
- **Cretas 状态**：grep `钉钉|dingtalk|DingTalk` **0 hits in source**（仅 `docs/plans/dingtalk-integration-plan.md` 有计划，无 impl）
- **AI 替代评估**：🟡 AI 主+UI 辅 — 钉钉机器人是 Cretas AIChat 的另一个**入口**，复用现有 8 场景 + 18 Skill
- **抄什么**：
  - 钉钉应用注册 + 机器人 webhook 接入
  - 复用现有 AIChat 的 SSE/Skill/Tool 链路
  - 双向通道：从钉钉群消息触发 AIChat → 返回结果给钉钉
  - 支持 webhook 推送（AIInsightCard 异常推到钉钉群）
- **工时**：**6 人天**
- **依赖**：Phase 0 修死代码（sessionId 已通后才能演多轮）

### N20. 通用 attachment 系统 ⭐ 基础能力
- **来源**：第三次会议 part 2，第四次会议
- **客户原话**："拍照也可以留个单谱吧, 就是你留个附件类似一个拍照然后一个附件吗也可以的呀"
- **Cretas 状态**：grep `Attachment` 0 hits, 仅业务专属 `BatchEvidencePhoto`（生产证据照片）
- **AI 替代评估**：🔵 UI 主+AI 辅 — 上传/管理是 UI 基础，AI 是拍照 OCR 增强
- **抄什么**：
  - 通用 `attachment` 实体（entityType + entityId + fileUrl + fileType + uploaderId）
  - 5+ 业务接入：客户跟踪 / 采购订单 / 质检 / 生产证据 / 财务凭证
  - 文件上传到 OSS（已有基础）
  - 缩略图 + 权限隔离
- **工时**：**5 人天**

### N24/N25. 工序管理 + 产品工序配置（前端）
- **来源**：第四次会议 (2026-05-10)
- **客户原话**："工序管理新增工序... 产品工序配置 添加完了... 生成工序任务"
- **Cretas 状态**：后端 `WorkProcessController` + `ProductWorkProcessController` 齐全，**前端 grep `WorkProcessConfig` 0 hits**
- **AI 替代评估**：🔵 UI 主+AI 辅 — 工序配置是结构化数据 + 多人协作，UI 必须；AI 是"建工序拆包再加分割"语音辅助
- **抄什么**：
  - WorkProcessListScreen + 新增工序表单
  - ProductWorkProcessConfigScreen（产品 → 多工序绑定）
  - 生成工序任务按钮 + 状态机集成
  - AI Chat："给猪蹄加工序：拆包→分割→卤制→分切" 一句话配置
- **工时**：**5 人天**

### N32. BOM 配方编辑 UI（工厂端）
- **来源**：第四次会议
- **客户原话**："BOM 配方 原辅料需求明细表... 物料名称要选择不是手写"
- **Cretas 状态**：餐饮端 `RecipeListScreen` 有，**工厂端无 `BomConfigScreen`**
- **AI 替代评估**：🔵 UI 主+AI 辅 — BOM 是结构化配方，UI 表格必须；AI 是从研发样品自动生成 BOM
- **抄什么**：
  - 工厂端 BomConfigScreen（参考 RecipeListScreen 抽象）
  - 物料名称改为关联到原料字典 Select（不是手写）
  - 出成率字段 + 自动折算（200g/58% = 250.58g 原料）
  - 单位统一（强校验所有原料用 kg 或全自动折算）
  - AI："这个产品配方是 200g 牛肉 + 10g 盐 + 5g 糖" 一句话建 BOM
- **工时**：**5 人天**

### N13. 抄码品识别（卤制品行业专有）
- **来源**：第三次会议 part 1
- **客户原话**："有些规格他其实是抄码的, 每箱的规格是不一样的... 比如说像牛肉, 他每箱的重量都不一样"
- **Cretas 状态**：grep `抄码|abaca` 0 hits in source（memory `reference_abaca_term.md` 说已 spec, 但代码仓无证据）
- **AI 替代评估**：⚪ 无 AI 价值 — 这是数据模型 + 业务规则，AI 无关
- **抄什么**：
  - 原料字典加 `isAbacaPackaging: boolean` 字段
  - 采购单创建时 if 抄码=true → 箱数字段不显示
  - 入库时验码（实际称重）
  - spec exact match `=== '抄码'`（不用 includes 避免误报，per memory 锚点）
- **工时**：**2 人天**

### N3. 销售员/采购员/仓管员 RBAC 严格隔离审计
- **来源**：第三次会议 part 2
- **客户原话**："采购跟入库是两个人吗? 两个人两个角色... 仓管的不能让他们参与什么价格类的"
- **Cretas 状态**：⚠️ **进行中** — RBAC 大面积 sweep 已完成（PR #423 + 30+ follow-ups, R7-F2 13/13 PASS）含 `canViewPrice` store
- **抄什么**：基于 PR #423 框架，**审计仓管员视图是否真隔离价格**（详细 acceptance test）
- **工时**：**2 人天**（审计 + 补 negative regression）
- **注**：这不是"从零抄"，是"验证已 ship 框架的完整性"

### N31. 销售订单 → 采购自动分流（缺料判断）
- **来源**：全流程文档 §2.2-3
- **业务**：销售单审核通过 → 系统自动判断库存 → 缺料则分流采购，否则直接生产
- **Cretas 状态**：缺料分析逻辑分散在 4 处（BomExpansionService / InventoryMatchingService / ProcurementSuggestionService / SupplyChainOrchestrator），无统一入口
- **AI 替代评估**：🟡 AI 主+UI 辅 — AI Chat 触发但需要 UI 单据流
- **抄什么**：
  - `ShortageAnalysisService` 统一入口
  - 销售单审批后自动调用
  - 输出 chain-card UI：销售单 + 缺料列表 + 推荐采购 + 推荐生产
  - 客户一键确认 / 修改
- **工时**：**4 人天**（依赖 P0 修死代码 + BomExpansion stub 修复）

### N48. 研发样品 → BOM → 报价 链路
- **来源**：全流程文档 §1
- **业务**：研发员建样品 → 审核 → 自动生成 BOM → 推送报价任务
- **Cretas 状态**：grep `Sample|sample|样品` — 餐饮 SampleRecipeScreen 有, 工厂端无样品管理
- **AI 替代评估**：🔵 UI 主+AI 辅 — 样品档案 + 多人协作必须 UI；AI 是"从历史相似样品推荐配方"
- **抄什么**：
  - SampleRequest 实体（客户需求 + 紧急程度 + 状态机）
  - 样品档案：编码/名称/规格/等级/主原料/照片/追踪记录
  - 审核工作流：合格 → 自动生成 BOM + 推送报价任务 + 通知销售
  - AI："给这个样品建 BOM 类似 SKU-201 但减 10% 包材"
- **工时**：**5 人天**

---

## C. 🟡 P0 必修 — 半成品 / 残留 bug (5 项 / ~12 人天)

### M1. 生产工序"通用 P 过来"未关联 bug
- **来源**：第四次会议
- **现状**：⚠️ partial #567，follow-up #622/#623 open
- **修什么**：新建生产计划时，已配置的产品工序未带过来，下拉只有"通用"
- **工时**：**2 人天**

### M2. 三价对比新建后不刷新 bug
- **来源**：第三次会议 part 1
- **现状**：❌ 未开始 (T3-14, test env seed blocker)
- **修什么**：新建采购单后，三价对比图表数据未更新
- **工时**：**2 人天**

### M3. PDF 打印 + 扫码入库 RN 端 UI 串通
- **来源**：第三次会议 part 2
- **现状**：🟡 后端已 ship (#413, `PurchaseOrderPdfServiceImpl:128` 含 QR + Barcode128)，**RN 扫码 v2 PENDING**
- **修什么**：
  - 仓管员 RN APP 扫码 → 跳到对应入库单
  - 仓管员只录 2 字段：收货数量 + 商品日期
  - 拍照附件（依赖 N20 通用 attachment）
  - 双方签字电子化
- **工时**：**4 人天**

### M4. BOM 物料选择器（不是手写）
- **来源**：第四次会议
- **现状**：⚠️ 仅 spec D2
- **修什么**：BOM 配方添加时，物料名称从原料字典 select，自动带出单位/编码
- **工时**：**2 人天**（与 N32 BOM 配方编辑 UI 配套，可合并）

### M5. 单位转换强校验 (g↔kg)
- **来源**：第四次会议
- **现状**：⚠️ 仅 spec D3
- **修什么**：原料字典强制选 g 或 kg，BOM/库存/采购全链路统一，自动折算
- **工时**：**2 人天**

---

## D. 🟠 P1 必抄 — 业务流完整性 + AI 替代不了 (10 项 / ~40 人天)

### P1-1. 销售订单财务成本核算审核
- **来源**：全流程文档 §2.2
- **业务**：销售单审核时，系统拉 BOM 标准成本 + 历史生产成本，自动算订单总成本 + 利润，财务核对
- **Cretas 状态**：FinanceCostAnalysisDashboard 有，无单据级"成本核算 + 利润"流程
- **AI 替代评估**：🔵 UI 主+AI 辅 — 财务审批必 UI；AI 是"对比利润历史"分析
- **工时**：**5 人天**

### P1-2. 采购订单财务审核 + 三价标红
- **来源**：全流程文档 §3.1
- **业务**：采购订单创建时，系统三价对比，差异 > N% 自动标红，财务审核
- **Cretas 状态**：`MaterialPriceComparisonDTO.java:11-35` 实体在，缺审核流程和标红规则
- **AI 替代评估**：🔵 UI 主+AI 辅
- **工时**：**3 人天**

### P1-3. 开票申请 + 发票回写 + 收款流水
- **来源**：全流程文档 §6
- **业务**：销售员发起开票申请 → 财务审核开票 → 上传发票 PDF 自动回写销售单 → 收款流水关联订单
- **Cretas 状态**：`InvoiceRecord` + `PaymentRecord` 实体在，前端缺 UI 流程
- **AI 替代评估**：🔵 UI 主+AI 辅
- **工时**：**8 人天**

### P1-4. 采购订单按供应商拆单
- **来源**：BORROW_LIST P1
- **业务**：1 张请购单含 N 物料（来自 3 个供应商）→ 自动生成 3 张 PO 草稿
- **AI 替代评估**：🔵 UI 主+AI 辅
- **工时**：**5 人天**

### P1-5. 客户记忆价（多客户历史价）
- **来源**：BORROW_LIST S3 + 第三次会议 (单价 BOM 默认 + 可改)
- **业务**：建销售单时按客户带历史成交价
- **Cretas 状态**：`PriceListScreen` 有但无按客户记忆
- **AI 替代评估**：🔵
- **工时**：**3 人天**

### P1-6. 单据打印系统（含 PDF 模板）
- **来源**：BORROW_LIST C2 + 第三次会议（PDF 闭环）
- **业务**：5 个核心单据可打印 PDF（销售/采购/报价/生产任务/领料）
- **Cretas 状态**：采购订单 PDF 已 ship (#413)，其他 4 个缺
- **AI 替代评估**：🔵 UI 主+AI 辅 — AIChat "打印这张单" 触发
- **工时**：**6 人天**

### P1-7. 月度考勤可视化矩阵（8 色 + 节日 badge）
- **来源**：BORROW_LIST H1 + 客户管理层场景
- **Cretas 状态**：AttendanceMonthlyTool 后端有，前端 5 色无矩阵
- **AI 替代评估**：🔵 — SmartBI 可查"5 月谁迟到最多"但矩阵 UI 仍有用
- **工时**：**3 人天**

### P1-8. 请假/调休/报销流程
- **来源**：BORROW_LIST H4-H6
- **业务**：员工自助提交 + 审批 + HR 备案
- **Cretas 状态**：只有 TimeClock 打卡
- **AI 替代评估**：🟡 AI 主+UI 辅 — "我明天请病假" + 主管 UI 审批
- **工时**：**12 人天**（每种 4 天 × 3）

### P1-9. 库存出入流水追溯（含单号跳转）
- **来源**：BORROW_LIST W5 + 客户合规需要
- **业务**：每笔库存变动可点单号跳回源单据
- **Cretas 状态**：MaterialBatchTransaction 有，缺业务来源字段 + 跳转 UI
- **AI 替代评估**：🟢 — TraceFullTool/TraceBatchTool 真做（Cretas 独家）
- **工时**：**3 人天**（仅 UI 暴露）

### P1-10. 小组长代报工
- **来源**：BORROW_LIST M7 + 第一次会议（老员工不会扫码）
- **业务**：组长一次扫码代填全组 5-10 人 + 工资分摊
- **AI 替代评估**：🔵 UI 主+AI 辅
- **工时**：**3 人天**

---

## E. ✅ 已 ship 别动 (4 项)

| # | 项 | PR |
|---|---|---|
| 1 | RBAC 价格保护 + canViewPrice | #423 + 30+ follow-ups (R7-F2 13/13 PASS) |
| 2 | 收货数量分次显示 | #414 |
| 3 | 预估成本字段权限隐藏 | 并入 RBAC |
| 4 | 列宽 audit | #535 |

**这 4 项已交付**——别重复决策，但要在客户演示时确认还能 work。

---

## F. 🟢 AI 真替代不抄 (12 项, AUDIT-Final C 验证)

| 类型 | 数量 | Cretas 基座 |
|---|---|---|
| 自然语言查询（库存/账龄/业绩/考勤汇总等） | 6 项 | SmartBI NL Query (18 Screen) |
| 异常推送 | 2 项 | AIInsightCard / AIAlertsScreen |
| 拍照 OCR | 1 项 | 摄像头 + ISAPI |
| 视觉识别（异物检测）| 1 项 | YOLO `foreign_object_detection/` 已实装 |
| 食品溯源（库存流水追溯）| 1 项 | TraceFullTool / TraceBatchTool 独家 |
| 钉钉入口（属于 N49 已列）| 1 项 | AIChat + Webhook |

**这 12 项不抄宏见 UI** —— 直接演示 Cretas AI 即可。

---

## G. 🔵 Hybrid 已有基础不抄 (30 项)

这些项 Cretas 已有 UI 基础 + AI 辅助，不需要从宏见抄新东西。比如：
- 销售订单（已有 UI + AIChat SHIPMENT 场景）
- 库存查询（已有 + SmartBI）
- 生产任务列表（已有 + AIChat PRODUCTION_PLAN 场景）
- 设备管理（已有 IsapiDevice / DahuaDevice）

详见 AUDIT_FRESH_C_CODE.md §1 ✅/🟡 项。

---

## H. ⚪ 不抄 (22 项)

| 类别 | 项 |
|---|---|
| 反对项（详 EXECUTION_PLAN §5.1）| 12 模块菜单 / 20+ 子树 / 17-Tab 详情 / 工作流拖拽编辑器 / 工作手机微信 / 强制单据流 / 全功能砍模块定价 |
| 客户群不需要 | 完整 23 类财务凭证 / 多币种 / 折旧 / 期末结账 / 长期待摊 / 多仓位 bin-level / 借入借出 / 序列号箱标 / 委外管理深度 / 协同询价 / 业绩排名 / 撞客户审批 / Web 多 Tab / 登陆地点限制 / 售后租赁寄卖 / 微信网店 |

---

## I. 工时合计与排期

### Phase 0 (Week 1-2)
- 修死代码 10 人天（AIChat sessionId / AILayout / PageEditor / Canvas Repository）

### Phase 1 必抄 P0 (Week 3-7, 32 人天)
- N49 钉钉机器人 6
- N20 通用 attachment 5
- N24/N25 工序管理前端 5
- N32 BOM 配方编辑 5
- N13 抄码品识别 2
- N3 RBAC 仓管隔离审计 2
- N31 销售→采购自动分流 4
- N48 研发样品→BOM→报价 5
- 修 5 个 P0 bug 12

**合计 Phase 1：~44 人天 ≈ 9 周（单人）/ 4.5 周（双人）**

### Phase 2 必抄 P1 (Week 8-15, 40 人天)
- 销售订单财务审核 5
- 采购订单财务审核 3
- 开票回款流水 8
- 采购拆单 5
- 客户记忆价 3
- 单据打印 6
- 月度考勤矩阵 3
- 请假调休报销 12（可暂缓某些）
- 库存流水追溯 UI 3
- 小组长代报工 3

**合计 Phase 2：~51 人天 ≈ 10 周（单人）/ 5 周（双人）**

### 总计

**Phase 0 + 1 + 2 = ~105 人天**

| 团队规模 | 完成时间 |
|---|---|
| 1 人单跑 | 21 周 ≈ 5 月 |
| 2 人并行 | 11 周 ≈ 2.5 月 |
| 3 人并行 | 7-8 周 ≈ 2 月 |

---

## J. Sprint 建议

### Sprint 1 (Week 1-2): Phase 0 修死代码 + 客户 P0 bug 修复
- AIChat sessionId / AILayout 接真 LLM / PageEditor 挂导航 / Canvas Repository 对齐
- 三价对比刷新 / 工序通用关联 / BOM 物料选择器
- **可演示**：客户立即看到改善

### Sprint 2 (Week 3-4): 钉钉机器人 + 通用 attachment + 抄码品
- 解锁钉钉入口（客户 N49 战略需求）
- 5+ 模块附件能力
- 卤制品行业刚需

### Sprint 3 (Week 5-7): 工序管理 + BOM 配方 + 研发样品
- 前后端串通工艺管理
- 工厂端 BOM 编辑
- 完整业务流第一节（研发→报价）

### Sprint 4 (Week 8-9): 销售→生产→采购全链路
- 销售单财务审核 + 缺料分析 + 采购拆单
- 完整业务流第二节（销售→采购）
- PDF + 扫码闭环完整跑通

### Sprint 5 (Week 10-12): 财务回款 + HR 流程
- 开票申请 + 发票回写 + 收款流水
- 请假/调休/报销 3 选 2 优先
- 完整业务流第三节（出库→财务）

### Sprint 6 (Week 13-15): 完善 + Demo + 客户深度试用
- 月度考勤矩阵 / 库存流水追溯 UI / 客户记忆价 / 单据打印
- 录 10 分钟 demo
- 1+ 客户 2 周深度试用

---

## K. 关键证据引用 (备查)

| 来源 | 关键内容 |
|---|---|
| FINAL_A_NEEDS_VS_CRETAS.md | 47 客户需求条目, P0 18 / P1 17, 14+ NEW 项 |
| FINAL_B_INPROGRESS.md | 10 P0 状态: ✅4 / 🟡1 / ⚠️4 / ❌1 |
| FINAL_C_AI_REPLACE.md | 98 项 AI 替代评估: 🟢12.2% / 🟡16.3% / 🔵30.6% / 🔴18.4% / ⚪22.4% |
| REVISED_STRATEGY.md | Hybrid 是常态, 业务流不可压缩 |
| docs/会议内容/客户会议/ | 4 次会议 + 全流程文档（真实客户证据） |

---

## L. 元层认知

1. **客户真实反馈 > 竞品分析**：4 次会议 P0 比 BORROW_LIST 71 项更有依据
2. **三重过滤极有效**：71 → 18 必抄，节省 70%+ 工时
3. **Hybrid 是常态**：30 项 🔵 占主流，不要强行 AI 化
4. **审计驱动决策**：每次声明都要有 file:line 证据
5. **客户已经在用 Cretas**：很多功能可能已 ship 或在 #PR，先查再决策

---

## M. 文档地图（最终）

```
MUST_COPY.md  ← 行动入口（本文件）
    ↓ 决策依据
REVISED_STRATEGY.md  ← Hybrid 战略
TRUTH_AUDIT.md  ← 4 路审计真相
    ↓ 三重过滤证据
FINAL_A_NEEDS_VS_CRETAS.md  ← 客户需求 vs Cretas
FINAL_B_INPROGRESS.md  ← 进行中工作核查
FINAL_C_AI_REPLACE.md  ← AI 替代能力评估
    ↓ 客户真实档案
docs/会议内容/客户会议/  ← 4 次六扇门会议 + 全流程文档
    ↓ 竞品分析底稿
EXECUTION_PLAN / BORROW_LIST / STRATEGY / AUDIT_FRESH_*  / V1/V2HD/V3
    ↓ DEPRECATED
REPORT / REVIEW / GAPS
```

**团队所有人按顺序读**：MUST_COPY → REVISED_STRATEGY → 4 次客户会议 → 具体审计文档。

---

# 附录 N: R-HJ Audit 增量 (2026-05-15)

> **来源**: `06-宏见测试账号深度审计/08-MUST-COPY-AUGMENT.md` (实测 hongjian.com 测试账号 6.5h 后整合)
>
> **背景**: 第一次拿到宏见测试账号 (lyh01/admin), 完整审计 12 模块 + 280 子菜单 + 108 流程节点. 发现 22+ 项 Cretas 应该补的 + 2 项升级 (M-BOM-VER-1 P3→P0, F-VOUCHER-HOOK-1 → P0 战略).

## N.1 P0 战略级新增 (3 项 + 2 升级)

### N.1.1 新增 P0

| 编号 | 项 | 工时 | 说明 |
|---|---|---|---|
| **F-VFLAG-1** | 凭证生成 hook (vflag 4 状态 + 7 凭证生成器 + 批量) | 10d | 任何业务单据 → 自动财务凭证 |
| **C-LINKARRAY-1** | linkListArray 跨业务关联 (8 类: sale/sample/request/produce/outsource/stock/project/free) | 2d | 业务双向追溯 |
| **S-LOCK-1** | 锁定/备货/缺料 3 维度 + 公式 (锁:0 备:1 缺:0 行内显示) | 1d | 销售单实时, 跟 S-MRP-1 配套 |

### N.1.2 升级 P3 → P0

| 编号 | 原 P | 新 P | 升级理由 | 总工时 |
|---|---|---|---|---|
| **M-BOM-VER-1** | P3 | **P0** | R-HJ 实测宏见 BOM = 工程级 PLM-Lite (BOMID + 版本号 + 工作流 + ECN). F006 配方迭代刚需. | 15d (BOM 实体 5d + ECN 5d + 反查 2d + 批量 2d + 列汇总 1d) |
| **F-VOUCHER-HOOK-1** | (隐含 P3) | **P0 战略** | 实测 7 种凭证生成器 + vflag, 业务 → 财务桥梁. | (同 F-VFLAG-1 10d) |

### N.1.3 P0 配套前端 (后端已实装但前端 UI 缺)

| 编号 | 项 | 工时 | 说明 |
|---|---|---|---|
| **C-APPROVAL-EDITOR-1** | 工作流可视化拖拽编辑器 (C-APPROVAL-1 前端) | 15d | 后端 ApprovalChainConfig 已实装 |
| **C-PRT-EDITOR-1** | 打印模板可视化设计器 (C-PRT-1 前端) | 10d | 后端 ship 5 单据 (#413), 编辑器缺 |

---

## N.2 P1 战术级新增 (15 项)

| 编号 | 项 | 工时 |
|---|---|---|
| **U-FEED-1** | 升级日志 in-app feed (10 条 release notes) | 2d |
| **U-MARKER-1** | 订单标记 7 色 (灰红黄绿蓝紫白) | 1d |
| **U-VIEW-1** | 列表 view 5 模式切换 | 3d |
| **U-NEW-1** | 创建 4 模式 dropdown (含 BOM 展开) | 4d |
| **U-ICON-1** | 行内 7 icon 工具集 | 3d |
| **U-DEPT-1** | 部门切换 button row | 1d |
| **U-CHIP-MULTI-1** | 行内多 chip 状态 (4 chip 垂直堆) | 1d |
| **W-CLASS-1** | 仓库分类枚举 10 类 | 1d |
| **P-NUCLEAR-1** | 核价单 (询价→核价→采购 三阶段) | 3d |
| **P-DRAFT-1** | 采购底稿状态 (草稿态独立) | 1d |
| **P-IMPORT-1** | 采购类型 (正常/进口) | 1d |
| **M-WIP-1** | 在制品 (WIP) 状态 | 3d |
| **M-MATTREE-1** | 物料需求 tree 模式 | 4d |
| **M-PREP-1** | 生产任务预备 (草稿态) | 2d |
| **M-DELIVERY-WARN-1** | 生产交货预警 dashboard | 3d |
| **Q-MODE-1** | 全检/抽检 模式区分 | 1d |
| **Q-RETURN-1** | 质检退回单 | 3d |
| **C-WF-RULE-1** | 流转规则引擎 (金额/部门/角色) | 8d |
| **C-OPINION-1** | 节点意见模板 (常用语) | 2d |
| **C-VOUCHER-TPL-1** | 凭证模板系统 | 5d |

---

## N.3 P2 选做 (大客户/未来, 4 项)

| 编号 | 项 | 工时 | 客户群 |
|---|---|---|---|
| **F-VOUCHER-2-1** | 复式记账凭证 | 15-20d | 大型企业 |
| **F-PERIOD-1** | 期间结账 (月结/年结) | 8d | 大型企业 |
| **F-3REPORT-1** | 报表三表 (资产负债/损益/现金流) | 12d | 上市公司 |
| **C-CUSTOM-1** | 资料定制 (字段/公式自定义) | 20d+ | 多行业客户 |

---

## N.4 工时合计更新

| 类别 | 主 MUST_COPY 现有 | **R-HJ 增量** | 新合计 |
|---|---|---|---|
| P0 必抄 | 8 项 / 32d | **+5 项 (3 新 + 2 升级 + 2 配套) / +50d** | 13 项 / 82d |
| P0 必修 | 5 项 / 12d | (无) | 5 项 / 12d |
| P1 必抄 | 10 项 / 40d | **+20 项 / +52d** | 30 项 / 92d |
| P2 选做 | (隐含) | **+4 项 / +55-65d** | 4 项 / 60d |
| **总计** | ~84d | **+157-167d** | **~241d (主 MUST_COPY 翻倍 + 60%)** |

---

## N.5 Cretas Sprint 2-6 计划影响

**原 v2.1 ASAP**: 7 周 / ~35 工作日 (Claude 1.7x)
**R-HJ 增量**: P0 +50d → 加约 **+30d 实际工作日** (Claude 加速后)

**修正建议**:
- ASAP (Sprint 0+1): 保持 7 周 (基础 + 必修 + 关键修死代码)
- **加 Sprint 1.5 (Week 8)**: 集中做 F-VFLAG-1 + S-LOCK-1 + C-LINKARRAY-1 (P0 新 3 项 = 13d 名义)
- Sprint 2 (Week 9-12): M-BOM-VER-1 升级 (15d) + Sprint 2 原计划 (M-WP-1/M-WP-2/UX Top 3)
- Sprint 3-4: C-APPROVAL-EDITOR-1 (15d) + C-PRT-EDITOR-1 (10d) + 原 Sprint 3-4
- Sprint 5+: P1 增量 20 项 + 原 P1
- Sprint 6+: P2 选做 (大客户场景)

**总时间**: 30 周 → **35-40 周** (≈ 8-9 个月单人 + Claude 加速 + 25% buffer)

---

## N.6 销售话术升级 (R-HJ 后)

新增可说的 (基于 audit 发现):
- ✅ "Sprint 1.5 ship vflag, 您的库存单 → 财务凭证 hook 自动" (F-VFLAG-1)
- ✅ "Sprint 4 ship BOM 工程级 (版本号 + ECN 变更), 类似宏见 PLM" (M-BOM-VER-1)
- ✅ "Sprint 4 ship 工作流编辑器, 您可视化拖拽改审批流" (C-APPROVAL-EDITOR-1)
- ✅ "Sprint 4 ship 打印模板编辑器, 您可视化拖拽设计单据 PDF" (C-PRT-EDITOR-1)
- ✅ "我们对照宏见 12 模块 + 280 子菜单, 选了 22 项必抄 + 4 项选做, 比宏见更精准"

---

**v1.0 → R-HJ amend 完成 (2026-05-15)**.

---

# 附录 O: R-HJ Round 2-9 终极整合 (2026-05-15, v1.2)

> **来源**: `06-宏见测试账号深度审计/28-CRETAS-PRIORITIZED-BACKLOG.md` (Round 9 累计)
>
> **背景**: 附录 N (v1.1) 是 R-HJ Round 1 audit (~22 项 / +132d). Round 2-9 又做了:
> - Round 2: 用户视角深度交互 (38+ screenshots)
> - Round 3: B+C storyboard
> - Round 4: 6 项 G1-G6 高价值填充
> - Round 5: 681 真实子菜单数 + 1591 RBAC 权限点
> - Round 6: 元审计 verification
> - Round 7-8: 41 子域全 mapping (含 TV APK + 微分销 OAuth)
> - Round 9: Mobile audit skeleton
>
> **结果**: 主 MUST_COPY 33 项 + 附录 N 22 项 + Round 2-9 增量 33 项 = **88 总项 / 429d nominal / 258d Claude 加速**.
>
> ⚠️ **本附录是 v1.2 终极, `28-CRETAS-PRIORITIZED-BACKLOG.md` 是 authoritative source**. 任何后续 Sprint 计划应以 28-doc 为准, MUST_COPY 主体保留作为历史 trail.

## O.1 Round 2-9 新增 P1 项 (16 项 / +47d)

### 客户/CRM 域 (8 项)
| 编号 | 项 | 工时 | 来源 |
|---|---|---|---|
| **S-CRM-FULL-1** | Customer 扩展 22 字段 (税号/法人/客户状态11/重要程度4) | 5d | Round 4 客户档案实测 |
| **S-CUSTOMER-TAB-1** | 客户档案 21 跟踪 tab (跟踪/微信/通话/谈话录音/邮件) | 15d | Round 4 客户档案实测 |
| **S-CREDIT-1** | 客户信用管理 (额度+账期) | 5d | Round 1 |
| **S-INVOICE-CLIENT-1** | 客户级开票税率 17 档 + 发票类型 6 档 (含数电票) | 2d | Round 4 |
| **S-PROFIT-DETAIL-1** | 产品级销售利润详情页 (11 列) | 2d | Round 4 |
| **S-REMIND-1** | 收款提醒 → OA 任务集成 | 3d | Round 4 |
| **S-NEED-1** | 销售需求独立模块 | 5d | Round 7 |
| **S-PAYMENT-DATE-1** | 客户级对账日期 (1-31 号) | 1d | Round 4 |
| **S-REPORTS-PRESETS** | 销售 14+ 预置报表模板 | 8d | Round 5 |
| **S-SOURCE-1** | 客户来源 11 渠道分类 | 1d | Round 4 |
| **S-VIP-1** | VIP 4 分级 (含重要程度枚举) | 1d | Round 4 |

### 品质/HR 域 (4 项)
| 编号 | 项 | 工时 |
|---|---|---|
| **Q-PROCESS-1** | 工序质检不良 + 失败原因 + 处理结果闭环 | 5d |
| **H-WAGE-FULL** | 工资管理 11 项 (社保/专项扣除/年度) | 10d |
| **H-ATT-FULL** | 考勤管理 11 项 (高级排班/异常分析) | 10d |

### 系统/平台域 (5 项)
| 编号 | 项 | 工时 |
|---|---|---|
| **C-CHECKPOWER-1** | RBAC 权限检查统一函数 | 3d |
| **C-WF-VAR-1** | 工作流系统变量库 ({#own}, 业务变量) | 3d |
| **C-LOG-AUDIT-1** | 系统操作日志独立子菜单 (5 列 + 查询导出) | 3d |
| **C-EXPORT-CENTER-1** | 数据导出规则中心 (跨 12 模块) | 5d |
| **C-IMPORT-CENTER-1** | 数据导入规则中心 (含校验/未导入/成功/失败) | 5d |
| **C-WIDGET-1** | dashboard 卡片插件式 (10 独立 endpoint 渲染) | 5d |
| **C-INLINE-CS-1** | 在线客服 iframe | 1d |

### UX 域 (1 项)
| 编号 | 项 | 工时 |
|---|---|---|
| **U-DESKTOP-MODAL-1** | layui-layer 桌面级 modal (4 操作: 最小化/最大化/拉伸/关闭) | 3d |

## O.2 Round 2-9 新增 P2 项 (11 项 / +59d)

### 大销售/B2B 协同
| 编号 | 项 | 工时 | 客户群 |
|---|---|---|---|
| **S-OPP-1** | 商机管理 (lead/opportunity 漏斗) | 8d | 大销售 |
| **S-COMPLAINT-1** | 售后服务投诉 12 字段 | 4d | 服务 |
| **S-COMMISSION-1** | 合作伙伴佣金报表 (12 月统计) | 5d | 代理/分销 |
| **S-CALL-STAT-1** | 外呼通话统计 (15s/30s/60s/120s 多档) | 8d | 需云硬件 |
| **C-CRM-FULL** | 客户 50 项含商机 3 / 报表 6 / 资料定义 6 | 15d | 大客户 |
| **H-PARTNER-FULL** | 合作伙伴 4 项佣金管理 | 5d | 代理 |

### 餐饮 / 多门店 / 食品扩展
| 编号 | 项 | 工时 |
|---|---|---|
| **C-STORE-1** | 门店管理 5 子项 (餐饮 QHJ 升级) | 5d |
| **S-STORE-REPLEN-1** | 门店补货 10 列 | 5d |
| **C-IMAGE-LIB-1** | 公共图片库 (跨企业共享) | 3d |
| **C-FILE-DOMAIN-1** | 文件管理独立子域 (file.hongjian.com) | 3d |

### 生产域 (大型工厂)
| 编号 | 项 | 工时 | 客户群 |
|---|---|---|---|
| **M-WP-CONDITION-1** | 工序条件路由 (材质=不锈钢→工序A) | 5d | Round 5 新发现 |
| **M-TECHNOLOGY-1** | 作业指导书 (数字化 SOP) | 8d | Round 5 |
| **M-APS-1** | 高级排产 (auto + 历史 + 派工) | 15d | 大工厂 P3→P2 |
| **M-MOULD-1** | 模具完整生命周期 (13 项) | 12d | 注塑/电子 |

## O.3 Round 2-9 新增 P3 战略级 (8 项 / +51d)

| 编号 | 项 | 工时 | 备注 |
|---|---|---|---|
| **C-TV-DASHBOARD-1** | TV 大屏 Android app (跟 SmartBI 集成) | 15d | HoanTV.apk 对照, 餐饮厨房屏/工厂车间屏 |
| **C-MENU-ENGINE-1** | menu.jsp?m=X 配置驱动菜单架构 | 8d | Cretas 当前 hardcoded |
| **C-RBAC-FNO-1** | 细粒度 f_no 权限点 (1591 个) | 15d | 长期 P3 |
| **C-MICROSERVICE-1** | 38 子域微服务架构 (Cretas 当前 monolith) | 长期 | 战略 |
| **C-WECHAT-DOMAIN-1** | 微信子域独立 (weixin.hongjian.com) | 5d | F006 用钉钉, 暂不需 |
| **C-PARTNER-DOMAIN-1** | 合作伙伴管理独立子域 | 3d | |
| **C-DOCS-DOMAIN-1** | help.cretas.com 独立 docs 子域 | 5d | |
| **C-SERVICE-CODE-1** | 服务代码显示 (footer small) | 0.5d | 客户报问题方便 |

## O.4 工时合计最终 v1.2

| 类别 | v1.0 主 | v1.1 R-HJ Round 1 增量 | **v1.2 R-HJ Round 2-9 增量** | **新合计** |
|---|---|---|---|---|
| P0 战略 | 8 项 / 32d | +5 项 / +50d | (无新增) | **13 项 / 82d** |
| P0 必修 | 5 项 / 12d | — | (无) | **5 项 / 12d** + 1 项 N3→C-RBAC-1 |
| P1 必抄 | 10 项 / 40d | +20 项 / +52d | **+16 项 / +47d** | **47 项 / 152d** |
| P2 选做 | (隐含) | +4 项 / +60d | **+11 项 / +59d** | **15 项 / 126d** |
| P3 长期 | (隐含) | — | **+8 项 / +51d** | **8 项 / 51d** |
| **总计** | 33 项 / 84d | +22 项 / +132d | **+33 项 / +157d** | **88 项 / 429d nominal / 258d Claude 加速** |

## O.5 Sprint 计划修正 (v1.2 vs v1.1)

| Sprint | v1.1 推荐 | **v1.2 修正 (基于 429d)** |
|---|---|---|
| ASAP (Sprint 0-1) | Week 1-7 | Week 1-15 (12 周, 含 P0 + 必修, 100d) |
| Sprint 2 | Week 8-12 | **Week 16-22 — P1 上半 (CRM/销售/采购)** |
| Sprint 3 | Week 13-16 | **Week 23-30 — P1 下半 (财务/HR/系统)** |
| Sprint 4 | Week 17-20 | **Week 31-36 — UX 14 项 (U-NAV/U-ACT/U-FOOTER 等)** |
| Sprint 5 | Week 21-24 | **Week 37-44 — P2 大客户财务 (复式记账 + 三表)** |
| Sprint 6 | Week 25-30 | **Week 45-52 — P2 其他 (商机/拆单/RFQ)** |
| Sprint 7+ | (无) | **Week 53-65 — P3 长期 (TV 大屏 / 微服务 / RBAC 细粒度)** |

**总时间**: **9-10 月单人 (Claude 1.7×, +25% buffer) → 15 月全 88 项**.

**推荐**: 不做 P3 长期项, **只做 P0+P1+P2 = 81 项 / 378d nominal / 228d 实际 ≈ 10.5 月**.

## O.6 战略决策汇总

1. **客户群定位**: 食品 + 餐饮专精, 避开宏见主场 (电子/注塑/五金)
2. **88 项分级**: P0 18 项 (3 月 ASAP) + P1 47 项 (Sprint 2-4) + P2 15 项 (Sprint 5-6) + P3 8 项 (战略, 选做)
3. **AI/移动差异化** — Cretas 当前 337+ Tool + RN 移动原生 + 食品溯源 + YOLO 是宏见永远赶不上的
4. **配置中台 P0 必上** — C-APPROVAL-EDITOR + C-PRT-EDITOR + C-WF-RULE 客户自服务能力跟宏见拉平
5. **TV 大屏 P3 选做** — 餐饮厨房屏 / 工厂车间屏, 跟 SmartBI 集成有差异化

详见 `28-CRETAS-PRIORITIZED-BACKLOG.md` (88 项完整 list + 客户群导向 + 团队规模 3 option) + `29-EXECUTIVE-SUMMARY.md` (1-2 页 Steve/Boss 决策版).

---

**v1.1 (R-HJ Round 1) → v1.2 (R-HJ Round 2-9 终极整合) 完成 (2026-05-15)**.

---

# 附录 P: R-HJ Round 11 deep re-audit 终极整合 (2026-05-19, v1.3)

> **来源**: `06-宏见测试账号深度审计/31-DEEP-RE-AUDIT.md` (3517 行, 16 sections §A-§P)
>
> **触发**: Steve "目前分析书来的内容不完整, 需要再一次细节的去核对宏见的ERP测试网站, 每一个已经核对过的内容继续深度去抓一次"
>
> **方法**: 6 parallel subagents (Agent A-F) 分工 88 项 deep re-audit + organizer Layer B 13 项 Playwright fresh capture (HJ test account lyh01/admin).
>
> ⚠️ **本附录是 v1.3 终极, `31-DEEP-RE-AUDIT.md §P` 是 authoritative source**. 本主文档 + 附录 N + 附录 O 内的 status 列 + 工时估算大量 stale, 决策面建议直接读 31-doc §P.

## P.1 30+ 项 ❌→✅ Reconcile (本主文档应吸取)

详见 31-doc §P.4 完整列表. 主 MUST_COPY (B/C/D 章节) 受影响的项:

**B. 🔴 P0 必抄 (本文档 §B 8 项)**:
| # | 项 | 主文档标 | **Round 11 修正** |
|---|---|---|---|
| N49 | 钉钉机器人 | 6d 未抄 | 🟡 IN-FLIGHT (Track-B1 code 100% + 46 单测绿, blocked F006 凭证) |
| N20 | 通用 attachment | 5d 未抄 | ✅ Track-C PR #658 SHIPPED |
| N24/N25 | 工序管理 + 产品工序 | 5d 未抄 | ✅ Track-D2 PR #650 SHIPPED |
| N32 | BOM 配方编辑 | 5d 未抄 | ✅ Track-D1 PR #656 SHIPPED |
| N13 | 抄码品识别 | 2d 未抄 | ✅ Track-B2 PR #649 SHIPPED |
| N3 | RBAC 仓管隔离 | 2d 未抄 | ✅ PR #661 + k4/k5 follow-ups SHIPPED |
| N31 | 销售→采购自动分流 | 4d 未抄 | ✅ Sprint2-E PR #682 SHIPPED |
| N48 | 研发样品→BOM→报价 | 5d 未抄 | ✅ Sprint2-F PR #680 SHIPPED |

**总计 §B 8 项 ASAP 工时 32d → 真实剩 ~6d** (仅 N49 钉钉 in flight).

**C. 🟡 P0 必修 (本文档 §C 5 项)**:
| # | 项 | 主文档标 | **Round 11 修正** |
|---|---|---|---|
| M1 | 三价对比刷新 | 2d 未做 | ⚠️ blocked (#538 F006 test seed) — still pending |
| M2 | 工序通用关联 | 2d partial | ⚠️ P3 deferred per original author intent (维持) |
| M3 | PDF + 扫码 RN | 4d half | ✅ Track-B2 PR #653 SHIPPED |
| M4 | BOM 物料选择器 | 2d spec | ✅ Track-D1 PR #656 SHIPPED |
| M5 | 单位转换强校验 | 2d spec | ✅ Track-D1 PR #656 SHIPPED |

**总计 §C 5 项 12d → 真实剩 ~4d** (仅 M1 unblock).

**D. 🟠 P1 必抄 (本文档 §D 10 项)**:
| # | 项 | 主文档标 | **Round 11 修正** |
|---|---|---|---|
| P1-1 | 销售订单财务成本核算审核 | 5d | ✅ F-AR-1 ArApTransaction SHIPPED |
| P1-2 | 采购订单财务 + 三价标红 | 3d | ✅ Sprint2-J P-FIN-1 PR #675 SHIPPED |
| P1-3 | 开票申请 + 发票回写 + 收款流水 | 8d | ✅ F-INV-1 + ENHANCED tax_breakdown JSONB + OCR PR #763 SHIPPED |
| P1-4 | 采购订单按供应商拆单 | 5d | ❌ NOT DONE (维持 P2) |
| P1-5 | 客户记忆价 | 3d | ✅ S-PRICE-1 SHIPPED |
| P1-6 | 单据打印 5 项 | 6d | ⚠️ 1/5 ship (采购 PDF #413), 4 剩 (销售/报价/生产任务/领料) |
| P1-7 | 月度考勤可视化矩阵 | 3d | ⚠️ H-ATT-FULL MVP ship, 矩阵 UI 缺 6 周 ×7 天 (~6d 收尾) |
| P1-8 | 请假/调休/报销 | 12d | ✅ H-LEAVE+OVT+EXP Sprint 4 W2 Chat E PR #770 SHIPPED |
| P1-9 | 库存流水追溯 UI | 3d | ⚠️ TraceFullTool/TraceBatchTool 后端 ship, UI 暴露待 |
| P1-10 | 小组长代报工 | 3d | ❌ NOT DONE |

**总计 §D 10 项 40d → 真实剩 ~10d** (主要 P1-4 / P1-6 单据打印 4 项 / P1-9 UI 暴露 / P1-10).

## P.2 真实剩余总工时 (本文档 vs Round 11)

| 优先级 | 本主文档 §I 估算 | 附录 N (Round 1) | 附录 O (Round 2-9) | **附录 P (Round 11) 真实剩** |
|---|---|---|---|---|
| Phase 1 (P0 ASAP) | 44d | 82d | 100d | **~5d** (M-BOM-VER frontend + M1 unblock + C-APPROVAL Phase 2 收尾) |
| Phase 2 (P1) | 51d | 92d | 152d | **~30d** (按需触发) |
| 总 P0+P1 | 105d | 174d | 252d | **~35d ≈ 7-8 周 ≈ 2 月** |
| Steve sign-off 9 月 | — | — | 9 月 | **~3 月** (vs sign-off 省 6 月) |

**Round 11 结论**: Cretas backlog 已接近收口, **Sprint 5 可 close P0+P1**, Sprint 6+ 转向客户深度试用 + bug fix + 新客户 onboarding.

## P.3 5 处 Cretas 已超 HJ — 销售话术升级

- ✅ S-LOCK-1 chip 颜色 + tooltip 防呆 vs HJ 纯文本
- ✅ M-MATTREE-1 BOM tree + 叶子库存短缺 (HJ tree picker 无库存计算)
- ✅ W-CLASS-1 13 类 vs HJ 10 类
- ✅ S-REMIND-1 scanner auto + bell badge vs HJ OA 跨域跳
- ✅ decisionType N-of-M 会签 (HJ 没有)

新销售话术: "Cretas 不只 copy HJ, 多处已优化超 HJ".

## P.4 真 gap 是 decisionType 覆盖度 (不是 feature 单点)

- Cretas decisionType ~10 个 vs HJ 126 工作流定义 = **8% 覆盖率**
- Sprint 4+ 真 P0 priority 是 **扩 decisionType 枚举** (拓到 30+) + 业务场景 mapping

## P.5 本主文档应吸取的 5 个元教训 (per 31-doc §P.8)

1. **单关键字 grep 不可靠** — `Voucher` 漏 `VoucherFlag` enum + lowercase `vflag` column 等. 必须 multi-synonym (entity / enum / column / camelCase / lower).
2. **Sprint bundle 难 track** — PR #727 一次 ship 8 UX / PR #764 一次 ship 7 quick wins. Backlog 应按 bundle 标 commit ↔ item mapping.
3. **Backlog metadata drift 快** — 5-16 audit 后 5-17/18/19 又 ship 14+ PRs, daily reconcile 必要.
4. **真 gap 是 decisionType 覆盖度** (8% 覆盖) — 不是 feature 单点.
5. **5 处 Cretas 已超 HJ** — 销售话术升级.

## P.6 后续行动

### 必做 (本周)
- ✅ 本文档加附录 P (本节)
- ✅ 30-BACKLOG-STATUS-AUDIT §13 加 Round 11 reconcile
- ✅ 28-CRETAS-PRIORITIZED-BACKLOG 顶部加 Round 11 banner
- ⚠️ Steve 重新 sign-off "3 月 P0+P1" (vs 5-16 "9 月")

### 应做 (Sprint 5)
- M-BOM-VER-1 frontend follow-up 3d
- C-APPROVAL Phase 2 收尾 3-5d
- M1 unblock (#538 F006 test seed)
- Layer B browser 实测 25 项 high/medium priority (1-2 session)

### 可做 (Sprint 5-6)
- decisionType 扩枚举 (Cretas 10 → 30+) 提高工作流覆盖
- 客户深度试用 + bug fix + 新客户 onboarding

### 延后 (Sprint 7+, 按客户触发)
- P2 选做 (F-VOUCHER-2 / F-PERIOD / S-OPP-1 完整 entity / C-CUSTOM 等)
- P3 长期 (TV 大屏 / 微服务 / RBAC 细粒度)

---

**v1.2 (R-HJ Round 2-9) → v1.3 (R-HJ Round 11 deep re-audit) 完成 (2026-05-19)**.


---

# 附录 Q: R-HJ Round 12 deep re-audit 终极整合 (2026-05-19, v1.4)

> **来源**: `06-宏见测试账号深度审计/32-DEEP-RE-AUDIT-V2.md` (3096 行, 7 sections §A-§G) + `31-DEEP-RE-AUDIT.md §P.12` (Round 12 新增 backlog table)
>
> **触发**: Steve "用 superpowers 审计, 深入 HJ 帮助手册 + 跨模块数据流 + 全 UI/UX"
>
> **方法**: organizer Phase 1 capture (647 help articles batch + 12 模块 sub-menu inventory + 9 fresh UI snaps + jsPlumb editor URL) + 5 parallel subagents synthesis (Agent X1-X5).
>
> ⚠️ **本附录是 v1.4 终极, `32-DEEP-RE-AUDIT-V2.md §G` 是 authoritative source**. 主 MUST_COPY (B/C/D 章节) + 附录 N/O/P (Round 1-11) 内的 status 列 + 工时估算大量 stale, 决策面建议直接读 32-doc §F (销售话术) + §G (改进 backlog).

## Q.1 28 G12-* 新 backlog items (Round 12 fresh, 加入主表)

详见 31-doc §P.12 完整 28 项表. 关键 17 项 P0/P1 摘:

### P0 战略级 (1 新)
| # | Item | 工时 | Sprint |
|---|---|---|---|
| C-MENU-PERSONAL-VIEW | 我创建的/我参与的工作流 + 工作流处理 admin UI (per §D.5) | 6d | Sprint 5 W1 |

### P1 战术级 (16 新)
| # | Item | 工时 | Sprint |
|---|---|---|---|
| G12-1 | inline link counter (file/image/contract count per row) | 4d | Sprint 5 |
| G12-3 | 客户档案 21 主 tabs + 5 sub-tabs (补剩 8) | 5d | Sprint 5 |
| G12-4 | 辅助核算 7 类 Voucher 关联 | 4d | Sprint 5 |
| G12-6 | 数据权限维度 (row-level filter) RBAC 第 2 维 | 6d | Sprint 5 大客户 |
| G12-9 | 报价试算 Sales | 3d | Sprint 5 |
| G12-10 | 采购需求总表 MRP entry verify | 1d | Sprint 5 |
| F-TAX-DIRECT-1 | 税局直连数电票 (per §B.5) | 10d | Sprint 5+ |
| P-REQUISITION-1 | 请购单 entity | 5d | Sprint 5 |
| C-LINK-11TYPE-1 | linkno 扩 11 类 (Cretas 8 + HJ 3) | 3d | Sprint 5 |
| M-WAGE-INTEGRATION-1 | 生产工时 → 工资自动 trigger | 5d | Sprint 5 |

### P2 选做 (12 新, ~75d)
- 序列号管理 (6d) / 产品报废单 (3d) / 线边仓库 (5d) / 设备 lifecycle (10d) / 工序条件路由 (5d 重判) / 作业指导书 (8d) / 报表三表 (12d) / 结账管理 (8d) / 账簿 (6d) / 商机漏斗 (8d) / 业绩 6 项 (5d) / S-REPORTS-PRESETS 14+6 (10d)

### P3 长期 (10 新, ~50d)
- C-TV-DASHBOARD-1 (15d) / 集团公司 (5d) / help.cretas.com docs 子域 (5d) / 第三方扩展 (8d) / 抄码品 8 字段 (5d) / 报价试算 (4d) / F-VOUCHER-ANOMALY-1 (3d) / S-CRM-CUSTOMTAG-1 (2d) / 等

## Q.2 工时累计修正 (Round 11 → Round 12)

| 类别 | Round 11 附录 P 估剩 | Round 12 新增 (本 Q) | 新合计 |
|---|---|---|---|
| P0 战略 剩 | 5d | +6d (C-MENU-PERSONAL-VIEW) | 11d |
| P0 必修 剩 | 4d | 0 | 4d |
| P1 战术 剩 | 30d | +52d (G12-1/3/4/6/9/10 + 4 ID') | 82d |
| P2 选做 | 60d | +75d (12 新 items) | 135d |
| P3 长期 | 50d | +50d (10 新 items) | 100d |
| **合计 剩余** | ~150d | **+183d** | **~330d nominal** |

按 Claude 1.7× 加速 + 25% buffer:
- Round 11 估: ~150d / **~3 月** P0+P1 收口
- **Round 12 修正**: ~330d nominal / **~6.5 月** P0+P1+P2 (新增大客户场景)

vs Steve sign-off "9 月" → **仍省 2.5 月** (vs Round 11 省 6 月, 增量发现略增工时).

## Q.3 关键 finding 10 条 (Round 12 fresh)

1. **HJ help.hongjian.com 独立子域** + 14 chapters / 780 articles 完整在线手册 → Cretas 应学客户面 docs (新 P3 C-DOCS-DOMAIN-1, 5d)
2. **vflag 真相**: 2x2 维度 (审核 ✓✗ + 异常 ✓✗), 不是 4 单维 — Round 11 推测错, Round 12 修正
3. **RBAC 5 维** (功能/数据/打印/第三方 + 登陆地点) — 完整 vs Round 11 估 4 维
4. **decisionType 真实 14 含 CUSTOM** vs Round 11 估 10 — 11% 覆盖 (vs HJ 126)
5. **辅助核算 7 类 official** (客户/供应商/部门/职员/项目/存货/委外商) vs HJ docs 2023 article 仍写 6 类 (文档落后产品)
6. **Cretas linkno 8 类 vs HJ baseline 8 类 命名不匹配 3 类** (Cretas project/free, HJ file/image/contract) — 新 C-LINK-11TYPE-1
7. **生产工时 → 工资集成断点** (HJ 自动 trigger, Cretas H-WAGE ship 但缺 trigger) — 新 M-WAGE-INTEGRATION-1
8. **MRP 4 个 entry + 请购单 entity 完全缺** (Cretas 仅 ShortageAnalysis ship) — 新 P-REQUISITION-1
9. **Cretas 已超 HJ 6 patterns** (VueFlow / URL routing / KeepAlive cascade / 操作 ▼ 普及 / DesktopModal / Vue state hidden) — 32-doc §E
10. **HJ 帮助手册搜索框 + 蓝色超链接 + 红色注意事项** UX pattern — Cretas in-app help 集成 P3 候选

## Q.4 主 MUST_COPY 受影响项 (P0/P1 必抄章节 inline 修正)

**§B P0 必抄 (Round 11 8 项 → Round 12 新)**:
- 新增 P0: C-MENU-PERSONAL-VIEW (6d) — 工作流 personal view + admin UI
- N49 钉钉 (Track-B1 in flight) — 维持 Round 11 status

**§C P0 必修 (无变化)** — Round 11 5 项一致

**§D P1 必抄 (Round 11 10 项 → Round 12 新增 6 项)**:
- 新增 P1: F-TAX-DIRECT-1 (10d) — 税局直连数电票
- 新增 P1: P-REQUISITION-1 (5d) — 请购单 entity
- 新增 P1: C-LINK-11TYPE-1 (3d) — linkno 扩 11 类
- 新增 P1: M-WAGE-INTEGRATION-1 (5d) — 生产 → 工资 trigger
- 新增 P1: G12-6 数据权限维度 (6d) — RBAC 第 2 维
- 新增 P1: G12-1 inline link counter (4d) — 跨模块视觉
- 新增 P1: G12-9 报价试算 (3d) — 销售试算
- 新增 P1: G12-3 客户档案补剩 8 tabs (5d) — Round 11 §A.2 62% ship 收尾

## Q.5 销售话术 7 场景升级

详见 32-doc §F (175 行). 关键场景:

1. **创建销售订单**: HJ 12 click + 40 字段 + 3-5 min vs Cretas 1 句话 + 30 sec
2. **审批流配置**: HJ jsPlumb + SpEL + 多 page vs Cretas Canvas VueFlow 单 Tab (含 N-of-M HJ 没有)
3. **跨模块查找**: HJ linkno 反查 vs Cretas AIChat 1 句话 (但缺 inline link counter)
4. **库存查询**: HJ multi-step + 5 page vs Cretas SmartBI 1 句话
5. **财务凭证生成**: HJ 7 generator + vflag vs Cretas ✅ ship (Sprint3-E PR #693)
6. **RBAC 配置**: HJ 4 维 + 1591 f_no vs Cretas 单维 (F006 够用, 大客户需扩)
7. **帮助手册**: HJ 14 chapters / 780 articles 客户面 docs vs Cretas 缺 (P3 C-DOCS-DOMAIN-1)

## Q.6 后续行动 (Steve 决策)

### 必做 (本周)
- ✅ 本附录 Q 加入 MUST_COPY
- ✅ 28-Backlog 顶部加 Round 12 banner (已完成)
- ✅ 30-Audit 加 §14 Round 12 supplement (本 commit)
- ⚠️ Steve 重新 sign-off "6.5 月 P0+P1+P2" (vs 5-19 上次 sign-off "3 月") — 因 Round 12 揭示更多大客户场景

### 应做 (Sprint 5)
- C-MENU-PERSONAL-VIEW (P0 6d) — 工作流 personal view + admin UI
- P-REQUISITION-1 (P1 5d) — 请购单 entity
- C-LINK-11TYPE-1 (P1 3d) — linkno 扩
- M-WAGE-INTEGRATION-1 (P1 5d) — 生产 → 工资
- G12-6 数据权限 RBAC 第 2 维 (P1 6d)

### 可做 (Sprint 5-6)
- decisionType 扩枚举 (Cretas 14 → 30+) 提高工作流覆盖 11% → 25%
- vflag 异常状态维度补 (P3 3d)
- 客户档案补剩 8 tabs (P1 5d)

### 延后 (Sprint 7+ 按客户触发)
- 大客户财务: F-3REPORT (12d) / F-PERIOD (8d) / 账簿系列 (6d)
- 大销售: S-OPP-1 完整漏斗+日历 (8d) / 业绩 6 项 (5d)
- 战略 P3: TV 大屏 (15d) / 集团公司 (5d) / docs 子域 (5d)

### Archive (Cretas 不抄)
- 委外管理 / 办公自动化邮件短信 / 国际贸易 / 寄卖借入借出 / 模具 / 微信网店 — Round 11+12 一致维持

---

**v1.3 (R-HJ Round 11) → v1.4 (R-HJ Round 12 deep re-audit) 完成 (2026-05-19)**.
