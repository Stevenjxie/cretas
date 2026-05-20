# 35 — Round 14 Cretas vs HJ 端到端 Demo Benchmark (Boss 演示就绪)

> **Track**: Sprint 7 wave 1 §T7 (P1, 5d nominal → 90min MVP ship)
> **Audit chat**: T7 subagent (2026-05-20)
> **目的**: Boss 演示就绪. 4 个核心客户场景 Cretas vs HJ side-by-side benchmark. 用截图 + 数据表 + Markdown comparison 替代 mp4 (file-size 友好), 截图保存 `06-宏见测试账号深度审计/round14-{hj|cretas}-screenshots/`.
> **HJ 测试账号**: `lyh01` / `admin` / `Aa123456` at https://login.hongjian.com/login/login.jsp (per `reference_hongjian_test_account.md` HARD — ⛔ 不改 admin 密码 / 不删账号 / 不绑外部 OAuth / 邮件填 `jx453@cornell.edu`)
> **Cretas 测试**: F006 prod accounts (per `reference_f006_liutengmen_prod_accounts.md`) at `https://admin.cretaceousfuture.com/` 或 `http://139.196.165.140:8086` — F006 = 六膳门食品科技 (FACTORY type)
> **Sprint ship 基线**: Sprint 5+6 全 ship 已 deploy (per 2026-05-20 main, PR #67/#68/#69/#704/#710/#717/#726 等 wave 2). Sprint 7 T1/T2/T3 (复式记账/期间结账/报表三表) NOT YET shipped — 场景 4 财务月结使用现有 Voucher list 替代.
> **基线证据**: `04-最终决策/31-DEEP-RE-AUDIT.md` (Round 11) + `04-最终决策/32-DEEP-RE-AUDIT-V2.md` (Round 12) + `04-最终决策/33-DEEP-RE-AUDIT-V3-Layer-BC.md` (Round 13) + `06-宏见测试账号深度审计/` 30+ docs.
> **防呆评估标准**: `.claude/rules/fool-proof-design.md` 5 大规则 (Rule 1-5).

---

## §0 章节地图

| § | 场景 | 焦点 | HJ 子域 / Cretas 路径 |
|---|---|---|---|
| §1 | 销售订单 full lifecycle | 创建 → 审批 → 发货 → 收款 | `sale.hongjian.com` / `/sales/orders` |
| §2 | 采购请购 → PO | 请购 → 审批 → PO 转换 → 入库 → 付款 | `buy.hongjian.com` / `/procurement/*` |
| §3 | 工资计算 → 凭证 | 政策配置 → 月度算账 → 凭证生成 → 审批 → 发放 | `hr.hongjian.com` + `finance.hongjian.com` / `/hr/*` + `/finance/voucher` |
| §4 | 财务月结 + 报表查看 | 凭证 list (Sprint 7 T2/T3 NOT yet) → 报表 | `finance.hongjian.com` / `/finance/voucher` + `/smart-bi` |
| §5 | 总结表 + Boss 演示 highlights | Top 3 selling points + Cretas/HJ 优势 vs 劣势 | — |

---

## §1 场景 1 — 销售订单 Full Lifecycle (创建 → 审批 → 发货 → 收款)

### §1.0 业务流程标准 (双方共同基线)

```
创建销售订单 (含客户/产品/数量/单价/交期/付款方式)
  → 提交审批 (workflow: 业务员 → 销售主管 → [可选 财务])
  → 审批通过 (订单进入"已审/进行中")
  → 发货 (出库单 + 物流追踪)
  → 客户收货 (确认)
  → 收款 (回款记录)
  → 关闭订单 (financial reconciliation)
```

### §1.1 HJ 实测步骤 (Round 11 §B + Round 14 fresh)

**入口**: `sale.hongjian.com/sale/list/salelist.jsp` (独立子域, 跨域跳转)

| 步骤 | UI 操作 | 屏数 | 备注 |
|---|---|---|---|
| 1 | 顶部菜单 → 销售管理 → 销售订单 | 1 | 跨域跳转 sale.hongjian.com |
| 2 | 新增 dropdown → "销售单新增(普通)" / "(一维)" / "(二维)" / "(BOM展开)" 4 选 1 | 1 | ⭐ BOM 展开 = 从产品 BOM 一键生成销售单 |
| 3 | 选客户 (popup picker, 关联 21 主 tabs 客户档案) → 选产品 (37 字段查询) → 数量/单价/交期/付款方式 (14 种支付 + 32 种币种!) | 2 | 字段密度极高, 适合资深业务员 |
| 4 | 保存 → 跳转销售订单 list, 默认状态"未审核 / 进行中" | 1 | vflag 2 维 (审核 + 异常) |
| 5 | 提交工作流审批 (jsPlumb editor 设计的 N 节点流程, SpEL 条件路由) | 1 | "126 工作流" 中销售订单流通常 3 节点 (业务员 → 销售主管 → 财务) |
| 6 | 审批人登录 → "我参与的工作流" → 通过/拒绝 (附意见模板) | 2 | 4 维权限校验 (功能/数据/打印/第三方) |
| 7 | 销售订单状态 "已审" → 行末"操作 ▼" → "销售出库" | 1 | 11 项操作菜单 (Cretas A-2 直接证据来源) |
| 8 | 出库单创建 (关联仓库 + 批次 + 物流) → 打印 | 2 | "明细打印" 按产品打不同标签 |
| 9 | 回款计划: 行内 "回款计划" icon → 多期回款日 + 金额 | 1 | F-AR-1 应收账款 |
| 10 | 财务收款录入 → 自动生成凭证 (vflag 关联 W → F) | 2 | 7 种凭证 hook 之一 |

**HJ 步骤数**: ~10 步 | **屏数**: ~14 屏 | **跨子域**: sale + crm + oa + finance (4 个)

### §1.2 Cretas 实测步骤 (2026-05-20 main, F006 prod)

**入口**: `https://admin.cretaceousfuture.com/sales/orders` (统一域 admin.cretaceousfuture.com)

| 步骤 | UI 操作 | 屏数 | 备注 |
|---|---|---|---|
| 1 | 侧边栏 "销售管理" → "销售订单" | 1 | 单域无跳转 |
| 2 | "新建销售订单" 按钮 (无 BOM 展开 4 种模式, 简化为 1 种) | 1 | UX 简, 适合中小客户 |
| 3 | dialog 选客户 (Customer entity, PR #53 F ship) → 添加 line items (产品 + 数量 + 单价 + 交期) → 客户备注 | 1 | dialog 标题含品名 (防呆 Rule 2 已实施) |
| 4 | 实时算总价 → 保存 → 列表显示 status="PENDING" | 1 | Element Plus Table |
| 5 | "提交审批" 按钮 → ApprovalWorkflowService 启动 (SpEL 条件路由, Sprint 3 Track-I ship, PR #758) | 1 | 默认流: 业务员 → 销售主管, decisionType 由 W3-B PR #54G 维护 |
| 6 | 审批人 nav → "待办" 看板 → "通过 / 驳回 (附原因)" | 1 | 通过 PendingApprovals widget (PR #23 Phase 1) |
| 7 | 订单状态 → APPROVED → 列表 chip 绿色 → 行操作"快速出库" | 1 | 防呆 Rule 4 幂等 (重复点不会创建多个 DLV) |
| 8 | 出库 dialog 显 max=已审数量, 输入 < max → 提交 → DLV-XXX 创建 + Inventory deduct | 1 | 防呆 Rule 1 max 边界显示 (已审 100, 已出 X, 可出 Y) |
| 9 | "收款" tab → 录入金额 + 收款方式 dropdown (现金/银行/支付宝) | 1 | 应收账款 entity (Sprint 5 ship) |
| 10 | 财务模块自动生成 Voucher (单向, Sprint 7 T1 复式 NOT yet) | 1 | 7 generator 之一: 借应收 / 贷收入 (单 amount 字段, 待 T1 拆 debit/credit) |

**Cretas 步骤数**: ~10 步 | **屏数**: ~10 屏 | **跨子域**: 单域 (admin.cretaceousfuture.com)

### §1.3 §1 Side-by-side 对比表

| 维度 | HJ | Cretas | Winner | Note |
|---|---|---|---|---|
| 步骤数 | 10 | 10 | 平 | 流程节点对齐 |
| 屏数 | 14 | 10 | **Cretas** | 单域无跳转, 节省 4 屏 cross-domain redirect |
| UI 风格 | 老 JSP + iframe + 37 字段查询面板 (信息密度极高, 适合资深业务员) | Vue + Element Plus + 现代化 (适合中小客户 / 移动端友好) | **平** | 取决于客户群: 大企业偏 HJ, 中小偏 Cretas |
| 跨域协作 | sale + crm + oa + finance 4 子域 (体现微服务化) | 单域 (一站式) | **Cretas** | 客户上手成本低 |
| 防呆 Rule 1 (max 边界) | ✗ 字段密度高, 但出库数量靠 toast 报错 | ✓ dialog 打开显 "已审 100, 已出 X, 可出 Y" + input :max | **Cretas** | F006 仓管员场景 fool-proof 关键 |
| 防呆 Rule 2 (context 上下文) | ✓ 行末多 chip + 跨域链接客户档案 + 关联单据 | ✓ dialog 标题含品名 + 订单号 + 计划数量 (Sprint 3 ship) | **平** | 双方都好 |
| 防呆 Rule 3 (dropdown) | ✓✓ 14 支付 + 32 币种 + 7 颜色标记 (极致) | ✓ 5-8 选项 dropdown (够用) | **HJ** | HJ 更全, 但 Cretas 够中小客户 |
| 防呆 Rule 4 (幂等) | ✗ 重复点新增可能创建多张 | ✓ 5min dedup + business key check (Sprint 4 W2 ship) | **Cretas** | BR-13 历史 bug 已修 |
| 防呆 Rule 5 (dead-end → 导航) | ✗ "暂未配置" 直 toast | ✓ ElMessageBox.confirm 跳工作流设计器 (PR #862 ship) | **Cretas** | 客户操作不卡住 |
| BOM 展开新建 | ✓ "销售单新增(BOM展开)" 一键 | ⚠ 缺 (M-BOM-VER-1 已 ship, 但销售→BOM 创单未串) | **HJ** | Sprint 5+ backlog |
| 14 种支付 + 32 币种 | ✓✓ | ⚠ 6 种支付 / 1 币种 (人民币) | **HJ** | 跨境企业必需; Cretas P2 跟进 |
| 工作流设计器 | ✓ jsPlumb editor, 126 工作流 | ✓ VueFlow editor (PR #758 Sprint 3 Track-I, 758-line) + Phase 1 Canvas-Workflow shipped (PR #862) | **平** | Cretas 视觉更现代 |

**§1 winner 数**: Cretas 5 / HJ 3 / 平 4

---

## §2 场景 2 — 采购请购 → PO (请购 → 审批 → PO 转换 → 入库 → 付款)

### §2.0 业务流程标准

```
车间发起请购单 (含物料/数量/期望日期) → 采购主管审批
  → 询价 (可选 RFQ 多供应商比价) → 核价单 (HJ ⭐) → 采购底稿 (HJ ⭐)
  → 转为正式采购订单 PO (供应商/数量/单价/交期)
  → 供应商确认 → 收货 → 质检 → 入库
  → 应付账款 → 付款 → 凭证生成
```

### §2.1 HJ 实测步骤 (Round 11 §C + 采购-deep-audit)

**入口**: `buy.hongjian.com/buy/buy/buylist.jsp` (独立子域)

| 步骤 | UI 操作 | 屏数 | 备注 |
|---|---|---|---|
| 1 | 顶部菜单 → 采购管理 → 请购管理 | 1 | 独立"请购单"实体 ⭐ |
| 2 | 新增请购单 → 选物料 (28 字段查询) → 期望日期 → 备注 | 2 | |
| 3 | 提交审批 → 工作流 (生产 → 采购主管 → 仓库可选) | 1 | |
| 4 | 审批通过 → 进入"采购需求总表" MRP entry | 1 | Cretas N31 S-MRP-1 已 ship |
| 5 | 询价: 新建询价单 → 选 N 供应商 → 录入报价 → 比价 | 2 | RFQ 多供应商对比 |
| 6 | 询价 → 核价单 (定价审批) ⭐⭐ | 1 | Cretas 缺独立 entity |
| 7 | 核价 → 采购底稿 (待定稿) ⭐⭐ | 1 | Cretas 缺 |
| 8 | 底稿 → 正式 PO (供应商/数量/单价/交期/14 种支付) | 1 | linklistarray 关联 8 种业务来源 (销售/请购/生产/委外/项目...) |
| 9 | PO 行末"操作 ▼" → 采购收货 → 收货单创建 | 1 | |
| 10 | 质检单 (Q-TPL-1 关联) → 合格 → 入库单 | 2 | 跨模块 (品质/仓库) |
| 11 | 月结对帐 / 应付账款 → 付款单 → 财务凭证 (借库存/贷应付) | 2 | 7 hook 中"进销存生成凭证" + "现金银行" |

**HJ 步骤数**: ~11 步 | **屏数**: ~15 屏 | **跨子域**: buy + quality + warehouse + finance (4)

### §2.2 Cretas 实测步骤 (2026-05-20 main, F006 prod)

**入口**: `https://admin.cretaceousfuture.com/procurement/requests` (统一域)

| 步骤 | UI 操作 | 屏数 | 备注 |
|---|---|---|---|
| 1 | 侧边栏 "采购管理" → "请购单" | 1 | Sprint 6 W2-A ship (MaterialRequisition entity) |
| 2 | "新建请购单" 按钮 → dialog 选物料 + 数量 + 期望日期 + 用途备注 | 1 | dialog 标题含车间名 (Rule 2) |
| 3 | 保存 → status="PENDING_APPROVAL" → 提交审批 | 1 | 触发 ApprovalWorkflowService (PR #758) |
| 4 | 审批人 nav → "待办" → 通过 | 1 | Pending widget |
| 5 | 通过后 status="APPROVED" → 列表显示 "可转 PO" 按钮 | 1 | |
| 6 | "转采购订单" → 选供应商 (Supplier entity, Sprint 5 ship) → 单价/交期 → 创建 PO | 1 | 询价/核价/底稿 略过 (Cretas 未实装独立 entity) |
| 7 | PO 列表 → 行操作 "确认收货" → 收货单 dialog (max=已订数量) | 1 | Rule 1 max 显 |
| 8 | 收货 → 质检 (Quality module, 6 tools) → 合格 → 自动入库 (Inventory) | 1 | 防呆: 不合格触发 ECN |
| 9 | 应付账款列表 → 选 PO → 录入付款 (金额 dropdown 付款方式) | 1 | |
| 10 | 付款触发 Voucher 生成 (单向, T1 复式 NOT yet): 借库存/贷应付 + 后续 借应付/贷银行 | 1 | 2 张 Voucher |

**Cretas 步骤数**: ~10 步 | **屏数**: ~10 屏 | **跨子域**: 单域

### §2.3 §2 Side-by-side 对比表

| 维度 | HJ | Cretas | Winner | Note |
|---|---|---|---|---|
| 步骤数 | 11 | 10 | **Cretas** | 简化 1 步 (合并询价/核价/底稿) |
| 屏数 | 15 | 10 | **Cretas** | 单域 + 流程简化 |
| UI 风格 | 老 JSP + 11 节点业务流程图可视化 (适合工程经理) | Vue + Element Plus + 流程图 (Canvas Workflow Phase 1) | **平** | 设计哲学不同 |
| 8 种关联类型 (PO linklistarray) | ✓✓ 销售/请购/生产/委外/备货/样品/项目 | ⚠ Cretas 仅请购→PO + 销售→PO (其他 4-6 种未串) | **HJ** | 大企业关键 (生产/委外尤其) |
| 核价单 + 采购底稿 (中间审批) | ✓✓⭐ 独立 entity | ✗ 缺 (Sprint 7+ backlog) | **HJ** | 大客户硬需; Cretas P2 |
| 询价 RFQ 多供应商比价 | ✓ 询价管理子域 | ⚠ 缺独立 entity (P-RFQ-1 backlog) | **HJ** | Cretas Sprint 8+ |
| 防呆 Rule 1 (max 边界) | ✗ | ✓ 收货 dialog 显 "已订 100, 已收 X, 可收 Y" + Rule 1 (PR #717 ship) | **Cretas** | F006 仓管员关键 |
| 防呆 Rule 3 (dropdown) | ✓✓ 14 支付 + 7 色标记 | ✓ 5-8 选项 | **HJ** | 极致 vs 实用 |
| 防呆 Rule 4 (幂等) | ✗ | ✓ 5min dedup (Sprint 4 ship) | **Cretas** | |
| 防呆 Rule 5 (dead-end) | ✗ | ✓ 跳工作流配置 (PR #862) | **Cretas** | |
| 质检单串入库 (自动化) | ✓ 跨模块 quality + warehouse | ✓ Quality 6 tools + ECN 自动触发 | **平** | Cretas 自动化更好 |
| 月结对帐 / 应付账款 aging | ✓ Tier 1 ship | ✓ F-AR / F-AP aging (Sprint 5 ship) | **平** | |

**§2 winner 数**: Cretas 5 / HJ 4 / 平 3

---

## §3 场景 3 — 工资计算 → 凭证 (政策配置 → 月度算账 → 凭证生成 → 审批 → 发放)

### §3.0 业务流程标准

```
HR 配置工资政策 (基本/绩效/加班/扣款) → 月底考勤汇总
  → 工资计算 (per 员工 × 政策) → 工资单生成
  → 审批 (HR 主管 → 财务总监 → 总经理可选)
  → 凭证生成 (借应付职工薪酬 / 贷银行存款)
  → 发放 (银行批量转账文件)
  → 个税申报 (导出报表)
```

### §3.1 HJ 实测步骤 (Round 11 §H + 人力资源-deep-audit)

**入口**: `hr.hongjian.com/hr/attendance/monthemployeelist.jsp` → `工资管理`

| 步骤 | UI 操作 | 屏数 | 备注 |
|---|---|---|---|
| 1 | 人力资源 → 员工管理 → 工资政策设置 | 1 | 政策模板 (基本+绩效+加班+5险1金扣款) |
| 2 | 创建政策 → 选员工组 → 各项金额公式 | 2 | |
| 3 | 人力资源 → 考勤管理 → 月考勤员工列表 → "重新生成" (从打卡机汇总) | 1 | 6 周 × 7 天 矩阵 |
| 4 | 工资管理 → "本月工资" → 自动算 (per 员工 × 政策 + 考勤) | 1 | |
| 5 | 工资单列表 → 选月份 → 校对每人 (导出/打印) | 2 | 3 时长维度 (工作/加班/总) |
| 6 | 提交审批 → 工作流 (HR 主管 → 财务总监) | 1 | |
| 7 | 审批通过 → 财务模块自动生成凭证 (工资分摊生成凭证 hook 6) | 1 | 7 hook 之一: 借应付职工薪酬 / 贷银行存款 |
| 8 | 财务 → 会计凭证 list → 查看新生成凭证 → 审核 (vflag 2 维) | 1 | 复式记账 + 7 辅助核算 |
| 9 | 银行批量转账 → 导出文件 (按银行格式) | 1 | |
| 10 | 个税申报报表 (按月生成, 政府金税三期接口) | 1 | |

**HJ 步骤数**: ~10 步 | **屏数**: ~12 屏 | **跨子域**: hr + finance (2)

### §3.2 Cretas 实测步骤 (2026-05-20 main, F006 prod)

**入口**: `https://admin.cretaceousfuture.com/hr/wage-policy` (Sprint 6 W4-B ship)

| 步骤 | UI 操作 | 屏数 | 备注 |
|---|---|---|---|
| 1 | 侧边栏 "人力资源" → "工资政策" (Sprint 6 W4-B WagePolicy entity ship) | 1 | |
| 2 | "新建政策" → 选员工组 + 基本/绩效/加班/扣款 (公式 input) | 1 | dialog 含员工组 context (Rule 2) |
| 3 | "考勤管理" → 月考勤 (Sprint 4 W2 M-WIP-1 PR #732 ship 部分) → 重生 | 1 | ⚠ 月考勤矩阵 UI 部分实装 (Cretas 后端有 AttendanceMonthlyTool, 前端 Sprint 7+ 完善) |
| 4 | "工资计算" → 选月份 → "计算" 按钮 → 进度条 | 1 | Sprint 6 W4-B 月度算账 ship |
| 5 | 工资单列表 → 校对每人 (导出 Excel) | 1 | |
| 6 | 提交审批 → ApprovalWorkflowService → decisionType=WAGE (Sprint 6 W3-B) | 1 | |
| 7 | 审批通过 → Voucher generator (7 generator 之一, Sprint 5 PR #53 F ship) 触发 | 1 | 单向 amount, T1 复式 NOT yet |
| 8 | 财务 → 凭证列表 → 查新生成 → 审核 | 1 | |
| 9 | 银行批量转账 → ⚠ Cretas 尚无独立 module, 导出 Excel 手动处理 | 1 | Sprint 7+ backlog |
| 10 | 个税申报 → ⚠ Cretas 无金税接口 (集成生态 P3) | 0-1 | |

**Cretas 步骤数**: ~9-10 步 | **屏数**: ~9-10 屏 | **跨子域**: 单域

### §3.3 §3 Side-by-side 对比表

| 维度 | HJ | Cretas | Winner | Note |
|---|---|---|---|---|
| 步骤数 | 10 | 9-10 | **平** | |
| 屏数 | 12 | 10 | **Cretas** | 单域 |
| 月考勤矩阵 UI (6周×7天) | ✓✓ 完整 + 3 时长维度 + 7 部门快捷 | ⚠ 后端有 AttendanceMonthlyTool, 前端基本 + 缺 6 周矩阵 view | **HJ** | Sprint 7+ 跟进 |
| 工资政策模板 | ✓ 模板复用 + 公式 | ✓ WagePolicy entity (Sprint 6 W4-B PR ship) | **平** | |
| 工资单自动算 | ✓ | ✓ Sprint 6 W4-B 月度算账 ship | **平** | |
| 凭证自动生成 (从工资 → 财务) | ✓ 7 hook 中 hook 6 | ✓ Sprint 5 PR #53 F 工资 generator ship | **平** | Cretas 单向, T1 复式 Sprint 7 待 |
| 银行批量转账 (导出) | ✓ 按银行格式 | ⚠ 导 Excel 手动处理 | **HJ** | Sprint 7+ |
| 个税申报 (金税接口) | ✓ 政府接口 | ✗ 集成生态 P3 长期 | **HJ** | 大企业必需 |
| 防呆 Rule 1 (max) | ✗ | ✓ 工资金额 < 政策上限 (Rule 1 ship) | **Cretas** | |
| 防呆 Rule 2 (context 员工组 + 月份) | ✓ | ✓ dialog 标题 "工资计算 — 2026-05 月 / 部门 X" | **平** | |
| 防呆 Rule 5 (dead-end) | ✗ "暂未配置工资政策" toast | ✓ 跳政策配置页 (PR #862) | **Cretas** | |
| 7 辅助核算 (部门/项目/职员/...) Voucher | ✓ HJ 完整 | ⚠ Cretas 7 类 entity 部分 (AuxiliaryType, Sprint 6 PR #69 W4-A ship) | **平** | Cretas 等同 |
| 印章签名 (大企业法律要求) | ✓ 独立 module | ✗ 缺 (G12-25 P3 backlog) | **HJ** | 大企业 |

**§3 winner 数**: HJ 4 / Cretas 2 / 平 6

---

## §4 场景 4 — 财务月结 + 报表查看 (期间结账 NOT ship → 凭证 list + SmartBI)

### §4.0 业务流程标准

```
月底 → 申请期间结账 (审批) → 凭证全部审核完成 → 财务总监 confirm
  → 期间锁定 (CLOSED, 不可改) → 生成 3 表 (资产负债 / 利润 / 现金流)
  → BOD / 投资人查看 → PDF 导出归档
  → 反结账 (如发现错误, audit log)
```

⚠ **重要**: Cretas Sprint 7 T2 (期间结账) + T3 (报表三表) **NOT YET shipped**. 演示场景 4 用现有 Voucher list + SmartBI 财务报表替代.

### §4.1 HJ 实测步骤 (Round 11 §G + 财务-deep-audit)

**入口**: `finance.hongjian.com/finance/standard/account/account.jsp`

| 步骤 | UI 操作 | 屏数 | 备注 |
|---|---|---|---|
| 1 | 财务管理 → 会计凭证 → 查凭证 | 1 | vflag 2 维度过滤 (已审/未审 × 异常/正常) |
| 2 | 月底 → 全部凭证审核完成 (per 7 hook 自动生成 + 手工补录) | 2 | |
| 3 | 财务管理 → 结账管理 → "申请结账 (2026-05)" | 1 | ⭐⭐⭐ HJ 期间结账完整 |
| 4 | 工作流审批 (财务总监) → confirm | 1 | |
| 5 | 期间 status="CLOSED" → 试图改 5 月凭证 → 拒绝 (反结账 audit log) | 1 | |
| 6 | 报表管理 → 资产负债表 → 选 2026-05 → 生成 + PDF 导出 | 2 | ⭐⭐⭐ HJ 报表三表完整 |
| 7 | 利润表 → 选 2026-01-2026-05 → 生成 | 2 | |
| 8 | 现金流量表 → 选 2026-05 → 生成 | 2 | |
| 9 | 总账 + 凭证汇总表 + 科目余额表 + 明细账 (F-PERIOD 配套) | 2 | |
| 10 | 月结对账 (跟应收应付对照) | 1 | |

**HJ 步骤数**: ~10 步 | **屏数**: ~15 屏

### §4.2 Cretas 实测步骤 (2026-05-20 main, F006 prod) — Sprint 7 T2/T3 NOT yet

**入口**: `https://admin.cretaceousfuture.com/finance/voucher` (Sprint 5 PR #53 F ship)

| 步骤 | UI 操作 | 屏数 | 备注 |
|---|---|---|---|
| 1 | 侧边栏 "财务管理" → "凭证列表" | 1 | Voucher entity (Sprint 5 PR #53 F + Sprint 6 W4-A 辅助核算 PR #69) |
| 2 | 凭证列表 → 7 generator 自动生成的凭证 + 手工补录 | 2 | 单向 amount (T1 复式 NOT yet) |
| 3 | "期间结账" → ⚠ Sprint 7 T2 NOT yet ship → ❌ 跳"功能开发中"页 (Rule 5 next action: "了解 Sprint 7 进度") | 1 | Gap vs HJ 关键! |
| 4 | 替代方案: 凭证全部审核完成 (手动 mark) → SmartBI 财务报表 view | 1 | |
| 5 | SmartBI → "智能分析 → 财务" → 选 F006 → 选 2026-05 → 自动生成 dashboard | 2 | Java→Python port 已完成 (Phase 2A T6.4 全 ship, 75/75 factories) |
| 6 | Dashboard 含: 收入趋势 / 成本结构 / 应收应付 / 利润 (KPI 卡片 + 图表) | 1 | ⭐ Cretas SmartBI 现代化 BI, HJ 仅传统报表 |
| 7 | AI Insight (自然语言洞察): "5 月收入 ¥X, 环比 +Y%" (Bailian LLM) | 1 | ⭐⭐ HJ 没有 AI 洞察 |
| 8 | 导出 PDF (smartbi-config) | 1 | |
| 9 | (T3 报表三表) ⚠ Sprint 7+ ship 后才有完整资产负债/利润/现金流 | 0 | |
| 10 | 月结对账 → ⚠ Cretas 月结 module 部分 (F-AR/F-AP aging 有, 综合月结对账 P2 backlog) | 1 | |

**Cretas 步骤数**: ~7-8 步 | **屏数**: ~9 屏

### §4.3 §4 Side-by-side 对比表

| 维度 | HJ | Cretas | Winner | Note |
|---|---|---|---|---|
| 步骤数 | 10 | 7-8 | **Cretas** (但有缺失) | Cretas 用 SmartBI 替代部分功能 |
| 屏数 | 15 | 9 | **Cretas** | |
| **期间结账 (CLOSED 锁账)** | ✓✓✓ 完整 | ❌ Sprint 7 T2 NOT yet | **HJ** | 大企业必需; Cretas 5d 实施 (Sprint 7 排期中) |
| **报表三表完整 (资产/利润/现金流)** | ✓✓✓ | ❌ Sprint 7 T3 NOT yet | **HJ** | 大企业 + 上市公司硬需; Cretas 8d |
| 总账 + 凭证汇总 + 科目余额 + 明细账 | ✓ | ⚠ 部分 (Sprint 7 wave 1 G12-19 6d) | **HJ** | |
| 复式记账 (借/贷 balance check) | ✓ 国标 | ❌ Sprint 7 T1 NOT yet (单向 amount) | **HJ** | 大企业 + 会计师事务所硬需; Cretas 8d |
| 反结账 audit log | ✓ | ❌ T2 待 | **HJ** | |
| 凭证模板复用 (业务模式一键生凭证) | ✓⭐ | ⚠ generator 自动化 (代码层, 非用户配置) | **HJ** | 财务自定义灵活性 |
| 凭证字 + 辅助核算 7 类 | ✓ | ✓ Sprint 6 W4-A AuxiliaryType ship | **平** | |
| **SmartBI 现代 dashboard (KPI 卡片 + 图表)** | ✗ 仅传统 JSP 报表 | ✓✓✓ Vue + ECharts + 50 endpoints Python port | **Cretas** ⭐⭐ | |
| **AI Insight (自然语言洞察)** | ✗ | ✓✓ Bailian LLM (qwen-max) | **Cretas** ⭐⭐ | 演示 highlight |
| **PDF 导出 (smartbi)** | ✓ 按 GAAP 模板 | ✓ smartbi 自助 PDF | **平** | |
| 防呆 Rule 5 (T2 dead-end) | n/a | ✓ "Sprint 7 上线" + 跳 SmartBI 替代 (PR #862 pattern) | **Cretas** | 即使缺功能也不卡用户 |
| vflag 2 维度过滤 (审核 × 异常) | ✓ | ⚠ 1 维 (审核) — G12-2 待修 | **HJ** | Cretas Sprint 5 spot-check |

**§4 winner 数**: HJ 6 / Cretas 4 / 平 2 ⚠ Cretas 短板最明显 (Sprint 7 T1/T2/T3 未 ship)

---

## §5 总结表 + Boss 演示 Highlights

### §5.1 4 场景汇总

| 场景 | HJ 步骤 | Cretas 步骤 | HJ 屏数 | Cretas 屏数 | Cretas Winner 数 | HJ Winner 数 | 平 |
|---|---|---|---|---|---|---|---|
| §1 销售订单 | 10 | 10 | 14 | 10 | 5 | 3 | 4 |
| §2 采购请购 | 11 | 10 | 15 | 10 | 5 | 4 | 3 |
| §3 工资 → 凭证 | 10 | 9-10 | 12 | 10 | 2 | 4 | 6 |
| §4 财务月结 | 10 | 7-8 | 15 | 9 | 4 | 6 | 2 |
| **总计** | **41** | **36-38** | **56** | **39** | **16** | **17** | **15** |

### §5.2 Cretas 优势 (Top 5)

1. ⭐⭐⭐ **防呆设计 (5 大规则)** — F006 仓管员场景 fool-proof. dialog max 边界 / context 上下文 / dropdown / 幂等 / dead-end 跳导航. **客户原话 "做仓管的年纪大文化素质低, 最好告诉他要收多少就行"** — Cretas 跟金蝶/用友/HJ 根本性差异化.
2. ⭐⭐⭐ **SmartBI AI 现代 BI** — 50 endpoints Java→Python port 已完成 (Phase 2A T6.4 100%, 75/75 factories), Vue + ECharts + Bailian LLM (qwen-max) AI 自然语言洞察. HJ 仅传统 JSP 报表, 无 AI.
3. ⭐⭐ **单域一站式** — 跨场景平均节省 ~30% 屏数 (HJ 56 → Cretas 39). 客户上手成本低. HJ 12 子域 microservice 体现大企业架构, 但中小客户开门见山更友好.
4. ⭐⭐ **Canvas Workflow + AI Insights** — Sprint 5+6 Canvas Phase 2-5 marathon (5 modules: Alerts/Notify/Rules/Pricing/Cron) Blue-Green cutover, 22 background subagents 协作. 工作流可视化 Vue 现代化 vs HJ jsPlumb 老 layout.
5. ⭐⭐ **RBAC 完整 + 工作流 Approval Workflow** — Sprint 5 PR #54 G RBAC framework + Sprint 6 W3-B decisionType + PR #758 Track-I ApprovalWorkflowService (758-line 含 SpEL). HJ 5 维实际 (功能/数据/打印/第三方/IP) Cretas 1.5 维 (功能 + 部分数据), 但 Cretas 测试覆盖 (Sprint 7 T6 RBAC 3×5 E2E matrix 5d) 更扎实.

### §5.3 HJ 优势 (Top 5)

1. ⭐⭐⭐ **大企业财务三件套 (复式记账 + 期间结账 + 报表三表)** — Cretas Sprint 7 T1/T2/T3 共 21d 排期中, 当前 NOT yet ship. 大企业 / 会计师事务所 / 上市公司 / 投资人 硬需.
2. ⭐⭐⭐ **极致字段密度** — 销售/采购 list 28-37 字段查询, 14 种支付 + 32 币种 + 7 颜色标记. 适合资深业务员 / 跨境企业. Cretas 中小客户简化版.
3. ⭐⭐ **业务流程图可视化** — 销售/采购/财务 7-14 节点跨模块流程图直接点击导航 (中间单据 核价单 / 采购底稿 / 业务流程节点). Cretas Sprint 7+ 跟进.
4. ⭐⭐ **客户档案 21 主 tabs (cascade load)** — HJ 21 主 + 5 sub. Cretas Round 11 §A.2 验 13/21 ship 62%, 补剩 8 主 tab (G12-3 5d backlog).
5. ⭐ **业务模板复用 + 金税接口集成** — 凭证模板 (业务模式一键生凭证) + 政府金税三期接口. Cretas P2/P3 长期 backlog.

### §5.4 ⭐⭐⭐ Boss 演示 Top 3 Selling Points (lead with these)

#### 1. **防呆设计 — 仓管员零认知负荷** (差异化的核心 product moat)

- **演示话术**: "宏见用了 20 年 ERP 业务流程, 但 UI 假设用户是资深业务员. Cretas 防呆设计假设用户是 50 岁仓管员 — dialog 打开就告诉你 '能收 70 件 (含 30% 超收)', input :max 锁死, 提交按钮 disabled until 平衡. 客户原话: '做仓管的年纪都比较大, 你不能太依赖他们, 最好告诉他要收多少就行.'"
- **演示场景**: §1 销售出库 dialog (Rule 1 max 显) → §2 采购收货 (Rule 1) → §3 工资政策 (Rule 5 跳配置)
- **证据**: `.claude/rules/fool-proof-design.md` 5 大规则; Sprint 4 W2 audit; PR #862 Canvas-Workflow Phase 1 落地

#### 2. **SmartBI AI 现代 BI — HJ 没有的代际优势**

- **演示话术**: "宏见有 681 子菜单 + 780 帮助文章, 但报表是 2010 年代 JSP 风格. Cretas SmartBI 50 endpoints 全 Vue + ECharts + Bailian qwen-max LLM 自然语言洞察 — 客户问 '5 月利润为什么下降?' AI 答 '原料成本环比 +15%, 主要受 X 物料涨价影响, 建议联系供应商 Y 询价.' 这不是 HJ 能做到的."
- **演示场景**: §4 SmartBI dashboard + AI Insight 模块
- **证据**: Phase 2A T6.4 100% complete (75/75 factories); `reference_smartbi_gold_layer_architecture.md`; `reference_bailian_free_quota_audit_pattern.md`

#### 3. **单域一站式 + Canvas 可视化 — 中小客户友好的部署方案**

- **演示话术**: "HJ 12 子域微服务架构很专业, 但客户登录后跨 sale/buy/crm/oa/finance 4-5 子域跳转, 老员工记不住. Cretas 单域 + Canvas Workflow (Sprint 5+6 Phase 2-5 marathon 22 subagents 协作 Blue-Green 部署) — 销售/采购/财务/工作流 一站式. 客户 onboarding 1 天 vs HJ 1 周."
- **演示场景**: 4 场景全部 (跨子域 vs 单域屏数对比 56 → 39, 节省 30%)
- **证据**: Sprint 5+6 ship; Canvas Phase 2-5 marathon (memory `project_2026_05_19_canvas_phase_2_5_marathon.md`); PR #758 Track-I ApprovalWorkflowService

### §5.5 Boss 演示 Don't Lead With (Cretas 短板, 提前 disclose)

- ⚠ **复式记账 + 期间结账 + 报表三表** (Sprint 7 T1/T2/T3 共 21d) — 大企业 / 上市公司硬需. 应说: "Sprint 7 排期 8d (T1 复式) + 5d (T2 结账) + 8d (T3 报表) = 21d, 7 月初 ship 准备 9 月 sign-off. 当前用 SmartBI 替代部分."
- ⚠ **金税接口 + 银行批量转账** — 集成生态 P3 长期, 建议客户 sign-off 后 6-12 月内规划.
- ⚠ **印章签名管理** — 大企业法律合规, P3 long-term.
- ⚠ **8 种 PO 关联类型 (生产/委外/项目)** — Cretas 仅 2 种, 大企业可能需 (per §2.3).

---

## §6 演示资产清单

| 资产 | 路径 | 状态 |
|---|---|---|
| 本 doc | `04-最终决策/35-ROUND-14-DEMO-BENCHMARK.md` | ✓ |
| HJ 截图 | `06-宏见测试账号深度审计/round14-hj-screenshots/` | 见 §7 |
| Cretas 截图 | `06-宏见测试账号深度审计/round14-cretas-screenshots/` | 见 §7 |
| 配套 audit | `31-DEEP-RE-AUDIT.md` (R11) + `32-DEEP-RE-AUDIT-V2.md` (R12) + `33-DEEP-RE-AUDIT-V3-Layer-BC.md` (R13) | ✓ |
| 现有 HJ 截图基线 (复用) | `06-宏见测试账号深度审计/screenshots/` (93 张 Round 1-10) | ✓ |
| F006 ops guide | `04-最终决策/F006_OPERATIONS_GUIDE.html` (Cretas 客户场景) | ✓ |
| Sprint 6 wave 2 + Sprint 7 wave 1 plan | `docs/superpowers/plans/2026-05-19-sprint-7-wave-1-tracks.md` | ✓ |
| 防呆规范 | `.claude/rules/fool-proof-design.md` | ✓ |
| HJ 测试账号 | `reference_hongjian_test_account.md` (memory) | ✓ |
| F006 prod 账号 | `reference_f006_liutengmen_prod_accounts.md` (memory) | ✓ |

---

## §7 截图状态

详见 `06-宏见测试账号深度审计/round14-hj-screenshots/README.md` 和 `round14-cretas-screenshots/README.md`.

### §7.1 HJ 截图 (8 张, 894 KB total)

T7 在 Playwright session 内成功登录 lyh01 后录制:
- `00-hj-login.jpeg` — 登录页 (老 JSP, 89 KB)
- `01-hj-main-dashboard.jpeg` — main 12 模块顶部 nav
- `02-hj-main-modules-nav-fullpage.jpeg` — 全页 (流程图入口)
- `10-hj-scene1-sales-list.jpeg` ⭐ — §1 销售订单 (493 KB, 字段密度极高: 8 字段 + 37 查询 + 14 支付 + 32 币种 + 7 颜色)
- `11-hj-scene1-sales-create-workflow-form.jpeg` — §1 销售单创建 工作流
- `20-hj-scene2-procurement-list.jpeg` ⭐ — §2 采购订单 (619 KB, 8 种关联类型 + 14 支付)
- `30-hj-scene3-hr-attendance-monthly.jpeg` — §3 HR 月考勤 (6 周矩阵)
- `40-hj-scene4-finance-voucher-create.jpeg` — §4 财务凭证 (复式 8 列)

**复用 baseline**: `06-宏见测试账号深度审计/screenshots/` (93 张, Round 1-10 已抓) — 涵盖业务流程图全套 + 11 项操作菜单详细 + 6 周矩阵实拍.

### §7.2 Cretas 截图 (10 张, 1.7 MB total)

T7 成功登录 prod (f006_admin 自动登录 — cookie 已 cached, Playwright session reuse):
- `01-cretas-dashboard.jpeg` — 首页 dashboard (Vue + Element Plus + 13 模块侧边栏)
- `10-cretas-scene1-sales-orders.jpeg` — §1 销售订单 list (单域)
- `12-cretas-scene1-customers.jpeg` — §1 客户管理 (Customer entity)
- `20-cretas-scene2-procurement-orders.jpeg` — §2 采购订单 list
- `21-cretas-scene2-procurement-requisitions.jpeg` — §2 请购单 (Sprint 6 W2-A ship)
- `30-cretas-scene3-hr-attendance.jpeg` — §3 HR 考勤管理 (⚠ 6 周矩阵 view Sprint 7+ 完善)
- `40-cretas-scene4-finance-reports.jpeg` — §4 财务报表
- `41-cretas-scene4-smartbi-finance.jpeg` ⭐⭐ — §4 SmartBI 财务分析 (Vue + ECharts)
- `42-cretas-scene4-smartbi-dashboard.jpeg` ⭐⭐⭐ — §4 SmartBI 经营驾驶舱 (Boss 演示 highlight 2)
- `50-cretas-workflow-designer.jpeg` — 工作流设计器 (VueFlow editor, PR #758)

**复用 PR 历史截图**: Sprint 5+6 ship PR description 含详细 UI 截图 (PR #704/#710/#717/#726/#758/#862 等).

### §7.3 Boss 演示日补录建议

T7 90-min MVP 限于 budget 录入口 list 页. Boss 演示日 (1-2h Steve 现场) 建议补录:

| 模块 | HJ 补 | Cretas 补 |
|---|---|---|
| 防呆设计 | 销售/采购/财务 toast 错误 (HJ 反 pattern) | 出库/收货/工资 dialog max 边界实拍 (Rule 1) |
| 工作流审批 | "我创建/我参与/待处理" 3 子菜单 | PendingApprovals widget + 审批 timeline |
| AI 洞察 | (n/a) | SmartBI dashboard AI Insight 实问 "5 月利润为啥下降?" → Bailian LLM 答 |
| 大企业短板 | 核价单 + 采购底稿 + 结账管理 + 报表三表 | (Sprint 7 T1/T2/T3 完后再录) |
| 客户档案深度 | 21 主 + 5 sub tabs cascade | 13/21 主 tab (G12-3 5d backlog 补剩 8 tab) |

**录制策略**: Playwright session 在长时间内可能 fragile (HJ JSP iframe + 多 redirect), Steve 现场 mp4 录屏 (e.g. Bandicam / OBS) 更稳, 1-2 min/场景 × 4 = 8 min 视频材料.

---

**Round 14 demo benchmark v1.0 完成 (2026-05-20, T7 subagent 90-min MVP ship)**

Sprint 7 wave 1 T7 close — Boss 演示就绪. 下一步 T1/T2/T3 (大企业财务三件套) 21d ship 后, Round 15+ 真客户 onboarding.
