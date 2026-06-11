# 六扇门 ERP 总审计 — 五维成熟度综合 (12 SP)

**日期**: 2026-06-11
**输入**: 12 个 SP 子项审计员的五维分类 + 漏项 JSON (SP1-SP12)
**综合者**: Fable 总审计
**五维**: MISSING(漏) → BUILT_UNTESTED(建未测) → BUILT_TESTED(建+测) → BUILT_TESTED_UIUX(+UIUX) → BUILT_TESTED_UIUX_FRONTEND(+前端跑通); NA_NO_FRONTEND=纯后端无前端维度

---

## 1. 五维总账热图 (136 个功能点)

| 维度 | 计数 | 占比 | 解读 |
|---|---|---|---|
| **MISSING** | 33 | 24% | 近 1/4 spec 承诺项未建, 集中在前端 (RN 屏 / web 入口 / 导航) |
| **BUILT_UNTESTED** | 22 | 16% | 多为"字段在/逻辑在但无测试"或"守卫建好未接线" |
| **BUILT_TESTED** | 45 | 33% | 最大簇 — 后端 + Mockito 单测; 几乎全部缺真实 DB/并发/集成验证 |
| **BUILT_TESTED_UIUX** | 27 | 20% | UI 已建但**零 headed E2E 证据** (全 12 SP 共性) |
| **BUILT_TESTED_UIUX_FRONTEND** | 6 | 4% | 仅 SP7 F1 / SP11 F1·F3·F4 / SP12 T4·X-10 有 API+DB/audit 实证 |
| **NA_NO_FRONTEND** | 3 | 2% | 凭证 listener / RBAC 种子 / 向后兼容设计 |

### Per-SP 热图

| SP | 主题 | 漏 | 建未测 | 建+测 | +UIUX | +前端验 | NA | 计 | 成熟度短评 |
|---|---|---|---|---|---|---|---|---|---|
| SP1 | 双产出 | 1 | 2 | 3 | 1 | 0 | 0 | 7 | 后端稳, T6 web 展示缺 |
| SP2 | 二次加工+撤回 | **5** | 1 | 7 | 2 | 0 | 0 | 15 | 后端最全, 前端缺口最大 |
| SP3 | 三价成本引擎 | 2 | 3 | 4 | 3 | 0 | 0 | 12 | 骨架在, 集成测试全缺 |
| SP4 | 一物一码补缺 | 0 | 1 | 2 | 3 | 0 | 0 | 6 | 两处断链 (前缀孤岛/ProductType税) |
| SP5 | 毛利红线+佣金 | 2 | 3 | 0 | 2 | 0 | 1 | 8 | 链路通, 配置无 UI |
| SP6 | 采购到付款 | **6** | 2 | 6 | 3 | 0 | 0 | 17 | RN 三屏全缺 |
| SP7 | 四仓+盘点报损 | 2 | 4 | 5 | 4 | 1 | 0 | 16 | 两个 CRITICAL 绕过 |
| SP8 | 16位编码 | 2 | 0 | 6 | 1 | 0 | 1 | 10 | 字典无 UI = P0 阻断 |
| SP9 | 人效双口径 | **8** | 0 | 2 | 0 | 0 | 0 | 10 | **最差**: 80% MISSING |
| SP10 | 三价报价 | 3 | 2 | 5 | 5 | 0 | 0 | 15 | 双 silent-drop + 脱敏泄露 |
| SP11 | 进销存+凭证导出 | 1 | 2 | 0 | 2 | **3** | 0 | 8 | 最成熟, 但 F2/R2 双 CRITICAL |
| SP12 | RBAC+审批+打印 | 1 | 2 | 5 | 1 | 2 | 1 | 12 | 旧端点绕过 = 红线违反 |

---

## 2. 跨 SP 综合发现 (单 SP 看不出、跨 SP 才暴露)

### X-1 ⛔ 成本脱敏系统性失守 (SP9 + SP10 + SP11, CRITICAL)
三个独立 SP 同根失守, 销售角色可拿到工厂成本数据:
- **SP9**: labor-efficiency 全部端点 + LaborEfficiencyCompareDTO 零 `@PriceSensitive` (spec §6 明确要求)
- **SP10**: ThreePriceComparisonDTO 零 `@PriceSensitive`, 预/中/实三价全字段明文 (前端 canViewPrice 仅客户端软脱敏)
- **SP11**: Red Line R2 — xlsx 导出线程内无 PriceSensitiveContext 检查, **导出通道整体绕过 @PriceSensitive JSON 层** (即使 SP3 等加了注解, 下载 xlsx 仍裸奔)
- 加重项: 全仓库无一条 `PriceSensitiveIT` (SP3/SP9 spec 均列) — **脱敏机制从未被测试证明过生效**

根因: @PriceSensitive 只覆盖 JSON 序列化层, 新 DTO/导出路径无 checklist 强制审计。修法应一次性: 三处 DTO 注解 + 导出线程 guard + 一条共享 PriceSensitiveIT。

### X-2 ⛔ "新门建好、旧门没封" 绕过模式 ×4 (SP7 + SP12, CRITICAL)
同一反模式四处复发 — 新 workflow/状态机路径建好测好, 旧直通端点原样活着:
1. **SP12 T3**: 新 POST /request-cancel 走审批 ✅, 旧 POST /{planId}/cancel **仍可任意角色直接取消** (红线 R3 违反); 且 web-admin plans/list.vue handleCancel **仍调旧端点** — 新路径前端零接入
2. **SP7**: 新 StocktakeEntryScreen 走盘点状态机 ✅, 旧 WHInventoryCheckScreen 第141行**仍直调 materialBatchApiClient.updateBatch 改库存** (spec F1 红线明确要求删除)
3. **SP12 T5**: DisposalRecordService.submitForApproval 是死代码 (无 REST 端点), 旧 PUT /{id}/approve 仍直批
4. **SP6**: 退货 completeReturnOrder 无财务审批门 (转录明确 "退货他要审批, 跟钱有关")

### X-3 守卫/逻辑孤岛 ×6 — "建了没接线" (SP4 + SP7 + SP8 + SP10 + SP12 + SP6)
| 孤岛 | SP | 状态 |
|---|---|---|
| WarehouseInventoryGuardService.assertCanReceive | SP7 | 有实现有测试, **生产代码零调用点** — "仓库零自主权"红线形同虚设 (CRITICAL) |
| getNumericPrefix() 类别数字前缀 | SP4 | 15 测试全绿, LabelServiceImpl 仍硬编码 'MA' — A3 实际未生效 |
| DisposalRecordService.submitForApproval | SP12 | 死代码, 无 REST 暴露 |
| PaymentRequest.submitForApproval | SP6/SP12 | 死代码, Controller 无端点 |
| GET /material-segments/generate-code | SP8 | **反向孤岛**: 前端调用, 后端无端点 → 404 静默降级占位符 |
| GET /production/batches?isTrial=true | SP10 | **反向孤岛**: mid-quotes 下拉依赖, 后端无过滤 → 返回全量批次 |

### X-4 DTO roundtrip silent drop 复发 ×3 (违反 feedback_dto_roundtrip_silent_drop)
- **SP10 laborPerKg**: detail.vue 发送, submitQuotation service 不持久化 → 静默丢弃 (4 处缺 2)
- **SP10 is_trial/trialSampleId**: web-admin checkbox 发送, CreateProductionBatchRequest 无字段 → 永远写 false
- **SP1 T2a**: 4 新字段往返无专测 (风险未爆但同模式)

### X-5 毛利红线归属/语义冲突 (SP3 vs SP5, 需拍板)
同一实现被两个 SP 报出矛盾:
- SP3 spec §11.4 写 "不卡死流程, 200+warn"; 实现为 createSalesOrder **409 硬阻断**; 转录 "不允许低于" 支持阻断 → spec 与实现与转录三方需收口
- 叠加 SP5 发现: check-margin 预警端点角色门控**排除 sales_rep** → 普通销售录单员**看不到预警却被 409 硬拒** (违反防呆 Rule 1: 预先显示边界, 不事后报错)
- 公式偏离: spec ×(1+margin) vs 实现 ÷(1-margin) — 实现数学正确, spec 该改

### X-6 实际成本链 (SP1→SP3→SP9/SP10/SP11) 从未端到端走通真实数据
- SP1 移动均价 IN: 仅 mock 测试; audit B-47 显示**测试环境 unitCost=null** (完整生产链没走过)
- SP3 事件链 (ProductionCostUpdatedEvent→回填→报警) 零测试
- SP9 M2 写回 actualLaborCost: BLOCKED on SP1 scope-lock, **SP1 已合后未补** → actual 永远 null
- SP9 M1 quotedLaborCostPerKg: 产品表单无录入控件 → quoted 永远 null → **M3 双口径对比 = null vs null 空转**
- SP10 中报价: spec 承诺自动从移动均价汇算, 实现降级为前端手填三项成本
- 结论: 客户最关心的"实际成本/出成率/三价对比"整条链在真实数据面前**没有一个非 null 的端到端证明**

### X-7 报损双实体疑似重复建模 (SP7 WastageReport vs SP12 DisposalRecord)
SP7 新建 WastageReport 双轨报损 (16 测试 + RN/web UI + prod 实证); SP12 同期把**另一个** DisposalRecord 接 MATERIAL_DISPOSAL workflow (死代码)。两套报损实体并存, 审批流/台账口径可能分叉 — 需确认归一 (建议: WastageReport 为准, DisposalRecord 路径明确弃用或合并)。

### X-8 编码体系双轨疑似冲突 (SP4 A3 vs SP8)
SP4 建了类别→数字前缀 (01/02/...) 用于标签码 (未接线); SP8 建了三级分段 16 位物料编码 + primaryCode。标签码前缀与物料 primaryCode 前三位是否应同源未对齐 — 接线 SP4 A3 时应直接复用 SP8 primaryCode, 避免两套类别编码。

### X-9 "功能依赖配置 + 配置无 UI 入口" 模式 ×4
- SP5 FactoryGrossMarginConfig: 红线参数只能 SQL 直写 (HIGH)
- SP8 分段字典: 无管理页, 级联下拉永远空 = 16位编码 P0 阻断 (HIGH)
- SP10 cost_variance_configs: 阈值只能 API/DB 写
- SP6/SP12 cashier: 枚举/权限在, F006 无一个 cashier 用户 → 出纳链路无法 E2E
对低技术素养客户 (六扇门), 无 UI 入口 = 功能不存在。

### X-10 RN 端系统性落后 (仓管/操作员/出纳是 RN 主用户)
缺: SP6 收货/出纳/异常三屏, SP2 ReversalSubmitScreen + 领半成品 tab, SP10 is_trial, SP4 扫码溯源对接 + 批次编辑厂号产地, SP12 申请撤回入口。web-admin-only 与六扇门"年纪大文化低"用户画像直接冲突 (fool-proof 规范触发原话即来自此客户)。

### X-11 跨 SP 去重更正
- **SP6 漏项 "凭证导出/总账对接未实现" 不成立** — SP11 F3 exportSequentialLedger 已建+测+API 实证 (5242 bytes)。残留缺口只是金蝶 per-movement-type 摘要模板 (SP11 漏项)。
- PaymentRequest 双报 (SP6 + SP12 T6) = 同一实体; 合并后真问题: ①硬编码状态机非 WorkflowEngine (客户要"可配置审批流") ②出纳 /approved 视图无供应商名/原料名/PO号/银行账户 (CRITICAL — 无法替代钉钉) ③销售方向付款未实现
- 盘点双报 (SP7 F6 + SP12 T4) = 同一链; 取 SP12 结论 (全链 audit V1 实证), SP7 的 MEDIUM 担忧降级
- 毛利红线双报 (SP3 §11 + SP5) = 同一实现, 见 X-5

---

## 3. 假完整清单 (标"建+测"实则测试空转/路径假通)

| # | 项 | SP | 假在哪 |
|---|---|---|---|
| 1 | BUG-R1 producedQuantity 撤回不更新 | SP2 | 测试本体 `assertTrue(true)` 占位 — **绿色测试掩盖已知缺陷**, CI 永不报警; 库存虚高影响出成率/备货看板 |
| 2 | SELECT FOR UPDATE 并发锁 ×3 | SP1/SP2/SP3 | 全部 mock-verify-call-path, 无真实 DB 两线程竞争测试; spec §8.1 明确要求的并发用例未实现 |
| 3 | SP9 M3 双口径对比 API | SP9 | 8 测试全绿, 但 quoted 无 UI 录入永远 null + actual M2 未建永远 null → **功能对客户 = 空表** |
| 4 | SP12 T8 打印端点 | SP12 | 8 用例覆盖, 但下游 T7 Python 路由 MISSING → 真实调用必 502 (X-7 audit 实证 502) |
| 5 | SP11 F2 进销存导出 | SP11 | 端点存在, **调错方法** exportSequentialLedger → 下载内容是凭证流水不是进销存 (功能性货不对板) |
| 6 | SP4 A3 数字前缀 | SP4 | 15 测试全绿, 未接入 LabelService → 所有标签仍 'MA' 前缀, 需求实际未生效 |
| 7 | SP10 GET /mid-quotes/{id} | SP10 | 端点"存在"但 stub 返回硬编码提示文案, 不查库 |
| 8 | SP10 三价对比 | SP10 | 4 测试绿, 但 confirm 中报价端点 MISSING → MID 价永远进不了对比 |
| 9 | SP3 事件链 listeners | SP3 | 结构正确零测试; B-47 audit: 测试环境 unitCost=null, 事件链从未在真实链路验证 |
| 10 | SP8 16位编码预览 | SP8 | 生成器有测试, 前端预览调不存在的端点 → 404 静默降级为前缀拼接占位符, 用户看到假编码 |
| 11 | SP10 试制批次下拉 | SP10 | UI 调 isTrial=true, 后端无过滤 → 下拉返回全量批次 (功能假可用) |
| 12 | SP12/SP6 submitForApproval ×2 | SP12 | DisposalRecord + PaymentRequest 的 workflow 提交方法均为死代码, 无 REST 可达 |
| 13 | headed E2E 证据 | 全 SP | 27 个 BUILT_TESTED_UIUX 项**无一**有 headed 截图/录屏; 仅 SP11/SP12 部分 API 断言 |

---

## 4. 漏项汇总 (转录/需求目录有、spec 未收或未建; 跨 SP 去重后)

### 高严重度 (转录/目录明确要求)
| 漏项 | 来源 SP | 严重度 |
|---|---|---|
| 出纳付款视图明细 (供应商名/原料名/PO号/单价/数量/银行账户) — 转录 1057-1079 明确逐项点名 | SP6+SP12 | CRITICAL |
| 退货需财务审批 ("跟钱有关要审批", 转录 2399-2416) | SP6 | HIGH |
| 销售方向付款审批 (转录+目录明确双向) — G5 dead code | SP6 | HIGH |
| 委外加工费独立成本科目 ("一定要有加工费", 转录 1844) | SP3 | HIGH |
| 销售订单不得低于预估价 (转录 15:51-16:08, G→E 跨流约束) | SP10 | HIGH |
| 含税/不含税口径 (成本未税计算 + 金蝶凭证税分离) — 三 SP 同缺 | SP3+SP4+SP11 | HIGH |
| 盐化仓独立扣量/报表 (转录重复 4 次强调) — F11 完全缺失 | SP7 | HIGH |
| 半成品流水 vs 客户"只做重量库存"设计升级未记录依据 | SP1 | HIGH |
| 供单/每日工时维护工作流实体 (客户走"供单驱动每日维护"模型) | SP9 | HIGH |
| 撤回审批角色分离 (提交≠审批, 当前同权限可自批) | SP2 | MEDIUM |

### 中低严重度 (可 P1)
成本组/公单级粒度 (SP3) · 超支及时反馈研发/销售推送 (SP3, log-only) · 多 SO 合并公单 (SP5+SP12) · 半成品"先做后用"场景 (SP1) · 组合装嵌套 BOM 成本聚合 (SP1) · 无证据工单直接撤回快速通道 (SP12) · 驳回通知申请人 (SP2) · 账期到期自动提醒 (SP6) · 金蝶 per-movement-type 摘要模板 (SP11) · 盘盈/盘损分列 (SP11) · 盘点采购理论值第二维度 (SP7) · 库存预警双向通知采购 (SP7) · 研发/中试独立库 (SP7) · 发票回传销售闭环 (SP5) · 三层价格同屏对比 (SP5) · 纯加工费报价模式 (SP10) · 报价人工/盒分项对比 (SP10) · 计件制字段存根 (SP9) · 人效图表可视化 (SP9) · 包材建档极简必填 (SP8) · 扫码自动弹包材 (SP8, 已 defer) · 16位完整结构年份位/校验位 (SP4)

---

## 5. C 流 (生产闭环) 上线就绪度判定

C 流构成: SP1 双产出 + SP2 二次加工/撤回 + SP12 撤回审批 + SP7 盘点报损 + SP3 成本。

| 层 | 就绪度 | 依据 |
|---|---|---|
| 后端数据模型+服务 | ~80% | 迁移全在 origin/main, 单测覆盖广, prod 有撤回/盘点实证 |
| 安全/红线一致性 | ~50% | 4 处旧端点绕过 + 守卫孤岛 + 脱敏失守 |
| 前端可操作性 | ~45% | 二次加工无创建 UI, RN 多屏缺, 双产出 web 不可见 |
| 真实数据验证 | ~35% | 成本链 unitCost=null, headed E2E 零证据 |
| **综合 (端到端客户可用)** | **~60%** | |

---

## 6. 配套文件
- 优先级 todolist: `docs/plans/2026-06-11-liushanmen-todolist.md`
- 五维全矩阵: `docs/plans/2026-06-11-liushanmen-matrix-5dim.md`
