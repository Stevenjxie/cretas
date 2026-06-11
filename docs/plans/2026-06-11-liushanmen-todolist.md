# 六扇门 ERP 修补 Todolist (优先级排序)

**日期**: 2026-06-11 · 来源: 12 SP 五维总审计 (`2026-06-11-liushanmen-5dim-audit.md`)
**口径**: 客户半月上线, 生产闭环 C 流为主。"维度提升"指五维阶梯: 漏→建 / 建→测 / 测→UIUX / UIUX→前端验。
**估量级**: S=半天内 · M=1-2天 · L=3天+

---

## Tier 0 — 上线必经 (C 流闭环 + 安全红线, 不做不能上)

| # | 做什么 | 维度提升 | SP | 严重度 | 估 |
|---|---|---|---|---|---|
| 01 | **封堵旧 POST /{planId}/cancel**: 加 workflow 前置检查或下线, web-admin handleCancel 改调 request-cancel + 申请撤回 dialog (计划号上下文+原因下拉, 防呆 Rule 2/3) | 建→接线 | SP12 | CRITICAL | M |
| 02 | **封堵 WHInventoryCheckScreen 直改库存**: 删除第141行 updateBatch 调用, 重接 StocktakeEntryScreen 盘点流程 | 漏→建 | SP7 | CRITICAL | S |
| 03 | **接线 WarehouseInventoryGuardService**: assertCanReceive 注入所有入库/调整路径 (当前零调用点, 红线形同虚设) + 1 条集成断言 | 建→接线 | SP7 | CRITICAL | M |
| 04 | **修 SP11 F2 导出货不对板**: 实现 exportInventoryLedger() 并改 exportLedger 端点调用 (当前下载的是凭证流水) | 建→建对 | SP11 | CRITICAL | M |
| 05 | **成本脱敏一次性收口**: SP9 三端点+DTO / SP10 ThreePriceComparisonDTO 补 @PriceSensitive; SP11 导出线程加 PriceSensitiveContext guard (R2); 写 1 条共享 PriceSensitiveIT (销售角色拿不到成本) | 漏→建+测 | SP9+10+11 | CRITICAL | M |
| 06 | **毛利红线语义拍板** (Steve+客户): 409 阻断 vs 200+warn 二选一收口 spec/实现/转录三方; 同时放开 sales_rep 调 check-margin (录单员必须**提交前**看到预警, 防呆 Rule 1) | 决策+小改 | SP3+5 | CRITICAL | S |
| 07 | **出纳 /approved DTO 补明细**: 供应商名/原料名/PO单号/单价/数量/单位/银行账户 (转录逐项点名; 当前只有 UUID, 出纳无法付款) + F006 建 cashier 测试用户 + 补银行账户数据 | 建→可用 | SP6+12 | CRITICAL | M |
| 08 | **修 2 处 DTO silent drop**: laborPerKg (submitQuotation 4 处补齐) + is_trial/trialSampleId (CreateProductionBatchRequest+toProductionBatch) + 各 1 roundtrip 测试 | 建→建对 | SP10 | HIGH | S |
| 09 | **web-admin 二次加工计划创建 UI**: POST /secondary-plan 端点孤岛接前端 (厂长无 UI 无法走"半成品→成品"路径, C 流核心) + plans 列表 SECONDARY 来源标签 | 漏→建 | SP2 | HIGH | M |
| 10 | **修 BUG-R1**: 撤回后 SemiFinishedInventory.producedQuantity 回滚 + 把 assertTrue(true) 占位换成真断言 (库存虚高直接污染出成率/备货看板) | 假测→真修 | SP2 | HIGH | S-M |
| 11 | **SP1 T6 web 批次详情双产出展示**: semiOutputQuantity/semiCode/outputKind + SFI 流水入口 (运营对双产出结果全盲) | 漏→建 | SP1 | HIGH | M |
| 12 | **成本链真实数据端到端走通一次** (test env): 报工 IN→unitCost 非 null→移动均价→SP3 回填 costUnitPrice→财审 breakdown 显示→进销存金额; 修走通过程暴露的断点 (B-47 unitCost=null 即此链未通) | 测→真验 | SP1+3+11 | HIGH | M |
| 13 | **打印链路收口**: 补 T7 Python production-work-order + consolidated-material-requisition 两路由 (Java/UI 已在, 当前必 502); 来不及就先隐藏打印按钮 (防呆 Rule 5, 别给客户 502) | 漏→建 | SP12 | HIGH | M |
| 14 | **web 报损 PRODUCTION_WASTE 枚举对齐**: 前端选项与后端 WastageReason 枚举不一致 → 提交必 400 (二选一: 删前端选项或加后端枚举值) | 建→建对 | SP7 | HIGH | S |
| 15 | **SP8 16位编码二选一拍板**: (a) 补字典管理页 + generate-code 端点让级联真可用; 或 (b) 半月上线先走"缩小版兜底" (T03/T04/T06 已可用), 隐藏级联 UI 防 dead-end | 决策+M | SP8 | HIGH | M-L |
| 16 | **退货加财务审批门**: completeReturnOrder 前置 finance review (转录明确"跟钱有关要审批") | 漏→建 | SP6 | HIGH | M |

**Tier 0 小计**: 16 项 ≈ 13-18 人天 (含 2 个拍板决策)

---

## Tier 1 — 上线前强烈建议 (半月内尽量, 不阻断首日)

| # | 做什么 | 维度提升 | SP | 严重度 | 估 |
|---|---|---|---|---|---|
| 17 | RN 领半成品入口确认/补齐: YieldStepReportScreen 已有 wip/available 路径, 验证操作员真实可走通; YieldBatchSelectScreen SEMI tab 视验证结果定 | 测→前端验 | SP2 | HIGH | S-M |
| 18 | SP12 T5 报损 workflow REST 端点 + 废弃旧 PUT /approve; 同时**拍板 WastageReport vs DisposalRecord 双实体归一** (建议 WastageReport 为准) | 漏→建+决策 | SP7+12 | HIGH | M |
| 19 | SP9 M2 写回 actualLaborCost (SP1 已合, scope-lock 已解, 不补则双口径永远空) + 产品表单加 quotedLaborCostPerKg 输入 + sidebar 导航入口 | 漏→建 | SP9 | HIGH | M |
| 20 | SP4 A3 接线: LabelServiceImpl 调 getNumericPrefix (建议直接复用 SP8 primaryCode 前三位, 避免双编码体系) + A5 web 生成标签按钮 + RN 扫码对接 /labels/scan | 建→接线 | SP4+8 | HIGH | M |
| 21 | SP4 A8 ProductType 税换算补半边 (service 3 处 + UI taxRate select; 当前仅 DDL) | 漏→建 | SP4 | HIGH | M |
| 22 | SP5 FactoryGrossMarginConfig admin CRUD UI+API (红线参数当前只能 SQL 直写) | 漏→建 | SP5 | HIGH | M |
| 23 | 真实 DB 并发集成测试 ×2: SP1 双线程同时 IN (移动均价) + SP2 双线程超量扣减 (FOR UPDATE 正确性目前零自动化证明) | 测→真测 | SP1+2 | MEDIUM | M |
| 24 | SP10 confirm 中报价端点 + GET mid-quote 去 stub + isTrial 过滤参数 (三价对比 MID 价才进得来) | 漏→建 | SP10 | HIGH | M |
| 25 | SP6 RN 收货屏 (PurchaseReceiveScreen, 异常弹窗触发) — 仓管手机收货是 D 流主触点 | 漏→建 | SP6 | HIGH | L |
| 26 | headed E2E 三条关键链截图存档: 报工双产出→批次详情 / 盘点全链 / 撤回审批 (27 个 UIUX 项目前零 headed 证据) | UIUX→前端验 | 全 | MEDIUM | M |
| 27 | SP2 撤回角色分离 (提交≠审批, 防自批) + 驳回通知申请人 | 建→建对 | SP2 | MEDIUM | S-M |
| 28 | SP12 撤回"无证据直接撤回"快速通道 (客户原话边界情况) | 漏→建 | SP12 | MEDIUM | S |

---

## Tier 2 — P1 可缓 (上线后迭代)

| # | 做什么 | SP | 严重度 |
|---|---|---|---|
| 29 | 销售方向付款审批 (PaymentRequest 加 salesOrderId 路径; D 流非 C 流但转录明确) | SP6 | HIGH→P1 |
| 30 | PaymentRequest 改接真 WorkflowEngine (客户要可配置审批流; 当前硬编码状态机能用) | SP6+12 | MEDIUM |
| 31 | 含税/不含税口径设计 (SP3 成本 + SP11 凭证/进销存 + SP4 已有半边) — 金蝶导入正确性, 需独立 spec | SP3+4+11 | HIGH→P1 |
| 32 | 委外加工费独立成本科目 (FinanceCostBreakdown 加 processingFee) | SP3 | HIGH→P1 |
| 33 | 销售订单≥预估价校验 (G→E 跨流约束) | SP10+5 | HIGH→P1 |
| 34 | 盐化仓独立扣量/报表端点 (转录 4 次强调; 上线时盐化先走通用仓+人工口径) | SP7 | HIGH→P1 |
| 35 | SP9 M4 工序达成率 + M5 看板图表 + step-breakdown 端点 + 供单/每日维护工作流建模 | SP9 | HIGH→P1 |
| 36 | SP6 RN 出纳屏 + 异常决策屏; web detail.vue ×3 | SP6 | MEDIUM |
| 37 | 多 SO 合并公单 (打印模板 payload 支持多 SO) | SP5+12 | MEDIUM |
| 38 | SP11 盘盈/盘损分列 + 金蝶 per-movement 摘要模板 + Controller 层测试 (R2/幂等 HTTP 级验证) | SP11 | MEDIUM |
| 39 | SP3 双口径人工对比视图 + 成本组/公单级聚合 + 超支推送研发/销售 | SP3 | MEDIUM |
| 40 | SP1 组合装嵌套 BOM 成本聚合 + 半成品"先做后用"场景建模 | SP1 | MEDIUM |
| 41 | SP2 E2E Playwright spec (sp2-reversal 4 场景) + SP12 Sprint6 stub builder 替换 | SP2+12 | MEDIUM |
| 42 | 库存预警双向通知采购 / 账期到期自动提醒 / 发票回传销售 / 研发中试库 / 计件制存根 / 包材极简建档 | 多SP | LOW-MED |

---

## 决策待拍板清单 (阻塞对应条目)

1. **毛利红线 409 阻断 vs 200+warn** (条目06) — 转录倾向阻断, spec 写非阻断
2. **SP8 16位编码: 补全 vs 缩小版兜底先上** (条目15)
3. **WastageReport vs DisposalRecord 报损双实体归一** (条目18)
4. **SP1 半成品流水分类账 vs 客户"只做重量库存"** — 设计升级合理但需客户确认 (无代码改动, 补 spec 决策记录)
5. **SP4/SP8 标签前缀与物料 primaryCode 是否同源** (条目20)
