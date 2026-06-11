# 六扇门 F006 完整测试方案 — Codex 主执行卡（自包含，可重复跑）

**日期**: 2026-06-11 起
**背景**: Claude 额度收紧，**所有实际测试执行交给 Codex**。本卡是自包含测试圣经 —— 8 个测试维度 + 每维度的目标/前置/步骤/期望/SQL坐实/角色。Codex 按需取维度跑，回 organizer(Opus) gate 结果。**不改业务代码**（发现 bug → 报告，organizer 修）。
**关系**: 取代逐次 handoff。第一/二轮 fullflow 已跑（`docs/audits/liushanmen/2026-06-12-fullflow-e2e-simulation.md` / `2026-06-12-demo-dryrun.md` / `2026-06-12-foolproof-ux-audit.md`），本卡是后续标准化套件。

---

## 0. 环境 / 账号 / 铁律（每次开跑必读）

| 项 | 值 |
|---|---|
| 后端 prod | `47.100.235.168`，活跃端口蓝绿轮换 → `curl -s http://localhost:10010/api/mobile/health` 和 `10020`，谁 200 用谁（近期是 10020 green）|
| 后端 test | `47.100.235.168:10011`（cretas_db；写操作优先这里）|
| web-admin prod | `http://139.196.165.140:8086`（经网关）|
| prod DB | `ssh root@47.100.235.168 "PGPASSWORD=cretas123 psql -U cretas_user -h127.0.0.1 -d cretas_prod_db ..."`（写用户 **cretas_user** 非 cretas）|
| test DB | 同上 `-d cretas_db` |
| 登录端点 | `POST /api/mobile/auth/unified-login`（**不是** /auth/login），body `{"username":"...","password":"123456"}`，取 `.data.token`（**字段是 token 非 accessToken**，2026-06-11 实测）|
| 业务 API 前缀 | `/api/mobile/F006/...` |

### F006 账号矩阵（全部密码 123456）
| 账号 | 角色 | 权限 | 用途 |
|---|---|---|---|
| `f006_admin` | factory_super_admin | 全模块 rw | 看全局 / 兜底 |
| `f006_cashier` | cashier | finance rw + procurement/sales r | 出纳付款（#772 修后可达三付款页）|
| `f006_viewer` | viewer | 只读 | 只读隔离验证 |
| `f006_warehouse_mgr` | warehouse_manager | warehouse rw | 库存/盘点/收货 |
| `f006_sales_mgr` | sales_manager | sales rw | 下单/开始采购 |
| `f006_warehouse_worker` | warehouse_worker | — | **web 登录会弹回**（仓管走 RN 非 web，非 bug）|

> ⚠️ RBAC 双层: web 路由守卫先查 `meta.module`(硬编码 PERMISSION_MATRIX fallback + DB L1 异步加载) 后查 `meta.roles`。某角色新加后两层都要补（matrix 行 + `platform_role_permissions` DB 行），否则异步窗口 403。已知 X-6 同族坑。

### ⛔ 铁律
1. **prod 是真客户 F006 在用**: 写操作优先 test(10011)，或 prod 用 **DEMO 前缀** remark 标记 + 跑完报清理清单（**Codex 自己不删**，交 organizer）。只读浏览可在 prod。
2. **Playwright 必须 headed**: `headless:false` + viewport 1920×1080 + `--lang=zh-CN` + `--font-render-hinting=none`。多 chat 用不同 PLAYWRIGHT_PORT(你用 9222) + user-data-dir 隔离。中文字体无方块。
3. **截图全留档** → `docs/audits/liushanmen/<日期>-<维度>-screenshots/`，audit doc 末尾带 Headed Mode Verification block。
4. **不改代码**。发现 bug → 报告（精确 repro + 期望 vs 实际 + SQL/响应原文），别自己改。
5. **诚实判定**: 验不了就写 BLOCKED/未验证，禁止把"看着对/页面能打开"写成 PASS。区分"路由可达" ≠ "功能跑通" ≠ "数据正确"。
6. **mock 过 ≠ 真实路径过**（本项目反复踩）: 单测绿不代表真实路径对，必走真实 API/UI + SQL 坐实。

---

## 1. 八大测试维度总览

| # | 维度 | 重点 | 频率 |
|---|---|---|---|
| D1 | **主链 happy path** | SKU→销售→采购→生产→报工→财务→报表 端到端通 | 每次部署 smoke |
| D2 | **跨路径财务正确性** | #771 三拐弯 / 成本回填 / 移动均价 / 凭证平衡（最硬，最易错）| 财务相关改动后 |
| D3 | **防呆设计** | 5 规则 + 4位一体 error toast（差异化核心）| UI 写操作改动后 |
| D4 | **RBAC / 角色隔离** | 低权角色可达性 / 金额脱敏 / AI 路径鉴权 | 权限改动后 |
| D5 | **两点报工 vs 逐道** | F006 默认两点 / 中间免报 / 多段成本滚动 | 报工/成本改动后 |
| D6 | **状态机边界** | 撤回自愈 / 盘点月底门 / 幂等防重复 / 超收少收 | 状态机改动后 |
| D7 | **多端一致** | web ↔ RN ↔ AI chat 同一业务三端表现一致 | 跨端改动后 |
| D8 | **数据质量 / 诚实空** | 缺数据显诚实空非假数据 / 诚实 null / 脱敏 | 持续 |

---

## 2. 各维度详细

### D1 — 主链 happy path（每次部署后 smoke，~30min）

**目标**: 一条 SKU 从研发到报表端到端不断链。
**前置**: F006 已配 BOM+单价+税率13%+工价+仓库（prod 齐，test 缺 → 优先 prod+DEMO 或先复制配置到 test）。
**步骤 + 判定**:
| 步 | action | 期望 | SQL 坐实 |
|---|---|---|---|
| 1 销售下单 | 含税客户(default_tax_rate=13)下单，单价可空 | 低于底价红字预警**非阻断**；税额自动算 | `SELECT unit_price,tax_amount FROM sales_order_items WHERE sales_order_id=?` |
| 2 财审 | submit→finance-approve | 状态 FINANCE_APPROVED + **凭证三行** | 见 D2-B |
| 3 采购 | SO「开始采购」一键 / 单独建 PO | BOM 净需求预填；合同号/结算方式可录 | `SELECT * FROM purchase_orders WHERE ...` |
| 4 收货入库 | 收货(可超收30%)→入库 | 异常单 ACCEPT/RETURN；**批次带价** | `SELECT unit_price FROM material_batches WHERE batch_number LIKE 'MT-%'`（⚠️见已知 BUG-RCV）|
| 5 付款 | 申请→双审→出纳付款(f006_cashier) | 出纳屏显待付款；mark-paid 三写 | `SELECT status FROM payment_requests WHERE id=?` |
| 6 生产计划 | 以销定产+财审门拦截 | 未财审SO建计划被拦；多SO可合并 | `SELECT source_order_ids FROM production_plans WHERE id=?` |
| 7 转批次+报工 | 转批次→两点报工→审批 | 任务列表只2点；审批通过 | `SELECT report_kind,status FROM ...reports` |
| 8 财务成本 | 成本拆分+多段成本 | 材料逐料+人工(可诚实null)+制费 | 见 D2-C |
| 9 报表 | 进销存四时点/盐化/金蝶导出 | 数据非空，导出表头对 | — |

**判定**: 每步 ✅秒开有数据 / ⚠️数据缺(写明) / 🔴断链(置顶即报)。

---

### D2 — 跨路径财务正确性（最硬，财务改动后必跑，~1h）

> **本项目反复栽在这**: happy path 通但"拐弯"断。单测绿 ≠ 真实路径对。**必须新造真实业务链 + SQL 逐字坐实**，不能用现存数据宣称。

**B. 含税凭证三行**
- 前置: 客户 `default_tax_rate=13.00`（`SELECT default_tax_rate FROM customers WHERE id=?` 必须 13，否则税额0退化2行）。
- 步骤: 标准 API 建含税 SO → 财审。
- 期望: `voucher_entries` 真 **3 行**，借贷平衡。
```sql
SELECT line_no, subject_code, subject_name, COALESCE(debit,0), COALESCE(credit,0)
FROM voucher_entries ve JOIN vouchers v ON v.id=ve.voucher_id
WHERE v.voucher_number=? ORDER BY line_no;
-- 期望: 1|1122应收|含税|0 / 2|6001收入|0|未税 / 3|2221.01销项税|0|税额
-- 例: net 4000 + 13%税 520 = 含税 4520
```

**A1. 多SO合并→成本回填（#771 断点1）**
- ⚠️ **关键**: 客户面路径是 web 计划页「加号追加销售单号」→ 走 SP5 `sourceOrderIds`（#771 修的就是这条）。**别用 legacy related_orders 路径**（GPT 2026-06-12 误用它→只主SO，那是测试假象非代码bug）。
- 步骤: 建2个含税 DEMO SO(remark `DEMO-XXX`)→财审→web 计划页主SO+加号追加次SO→转批次→**两点报工(必须真有 YIELD/OUTPUT 类型报工，否则 WIP 成本 null→事件不发→不回填)**→审批。
- 期望: 两个 SO 的行 cost_unit_price 都非 null。
```sql
SELECT pp.id, pp.source_order_ids FROM production_plans pp WHERE pp.id=?;  -- 必含两个SO
SELECT sales_order_id, id, cost_unit_price FROM sales_order_items
WHERE sales_order_id IN (?,?);  -- 两单的行都非null = PASS
```
- **已坐实(2026-06-11)**: listener 代码确遍历 sourceOrderIds(在 jar)；单SO回填日志 `[SP3-Backfill] 回填 costUnitPrice=40` 真落库。**未端到端坐实**: 多SO经加号路径的 DEMO 链（WIP 成本 null 卡过）→ **本维度首要补的就是这条**。

**A2. 撤回→成本自愈（#771 断点2）**
- 步骤: 上面的批次→整单撤回(WHOLE_ORDER，无单工序)→审批撤回→查→重报(产量改一点)→审批→查。
- 期望: 撤回后 cost_unit_price 清 null → 重报后回填**新值**(≠旧脏值)。
```sql
-- 三时点各查一次:
SELECT id, cost_unit_price FROM sales_order_items WHERE sales_order_id=?;
-- 撤回前有值 → 撤回后 null → 重报+审批后新值
```
- **已坐实**: `clearBackfilledCostUnitPrice` 方法在 jar。**未运行时坐实**: 真撤回链。

**A3. 采购两路同源（#771 断点3）**
- 步骤: 同一SO，路1 web「开始采购」算净需求 vs 路2 财审自动级联(SupplyChainOrchestrator/ProcurementSuggestion)算净需求。
- 期望: 两路 netRequired 逐物料一致(recipe-first 三路同源)。
- **已坐实**: BomExpansionService 三分支(recipe→legacy→RPF)在 jar。**未数据坐实**: 真 shortage_report 对比（prod 现状 sales_order_shortage_report 空）。

**C. 多段成本（#770）**
- 正确端点: `/api/mobile/F006/sales/orders/{orderId}/multi-stage-cost`（**注意 /sales 前缀**，无前缀的旧 doc 是笔误）。
- 多段产品需真有 原料→半成品→半成品→成品 链（二次加工 createSecondaryPlan+secondarySourceWipId 建3批次）。单段产品返空段+hint = **正确行为非bug**。
- 期望: 每段 料+人工+制费分列 + 半成品 unitCost 逐段涨 + 每盒贡献；两点报工人工 null 时 laborHint="登下一期"。
```sql
SELECT id,batch_number,stage_count,unit_cost FROM ... WHERE production_plan_id=?;
-- 已有历史: batch 1950 掌中宝6段/1949 猪舌6段/1924 猪舌10段
```

**移动均价**: 旧批+新批加权 = (旧量×旧价+新量×新价)/(旧量+新量)，scale-4 HALF_UP。收货入库后查批次均价。

---

### D3 — 防呆设计（UI 写操作改动后，差异化核心）

> 客户原话(仓管场景): "仓管年纪大文化低，不能太依赖他们，告诉他要收多少就行"。防呆 = **犯错前阻止**，不是犯错后报错。用**真实低权角色**走。

逐写操作 dialog 验 5 规则（详见 `.claude/rules/fool-proof-design.md`）:
| 规则 | 验什么 | headed 证 |
|---|---|---|
| R1 预显边界 | dialog 开即显可操作范围 + input `:max` + 超限禁提交 | 入库开即显"下单100已收30可入70(含30%超收=130)" |
| R2 上下文带身份 | 标题带品名+规格+单号+责任人+计划数 | "完成生产—掌中宝120g(SO-...)" |
| R3 原因 dropdown | 取消/退货/审批原因下拉非空 textarea，选"其他"才显输入 | 取消原因下拉 |
| R4 幂等防重复 | 重复点→409"已有草稿DLV-XXX,查看?"+跳转 | 连点两次快速出库 |
| R5 dead-end 改导航 | "未配置"空状态带下一步按钮 | "流程未配置→去配置" |
| 4位一体 | error toast: 具体文案=后端message原文 + sticky(duration0+showClose) + 含下一步 | "发货行51未分配批次，请先分配" |

**已知弱点(2026-06-12 抓)**: 盘点 dialog R1 弱(没显"批准前不改库存")；403页缺权限码指引；金蝶导出缺示例模板。

---

### D4 — RBAC / 角色隔离（权限改动后）

| 验点 | 方法 | 期望 |
|---|---|---|
| 低权角色可达性 | 各角色登录直接 URL 导航各页 | 有权→可达，无权→403(非白屏) |
| 金额脱敏 | sales_mgr 看采购价但不看财务P&L；非财务角色金额列脱敏 | `unit_price` 对非财务返 null（PriceFieldResponseAdvice）|
| owner 营收放行 | owner 角色看 gold 营收 | 放行非脱敏 |
| **AI 路径鉴权** | 低权角色经 AI chat 调敏感写 tool(删批次/财审/删客户) | ToolRbacEnforcer 拦截(同 controller PermissionService 同源，fail-closed) |
| 业态隔离 | 餐饮问题不撞制造业工具 | BusinessTypeScope 过滤 |

> AI 路径是 HTTP 之外的第二鉴权面（W9 #719 中央 ToolRbacEnforcer 6 执行点）。验"经 AI 能不能绕过 controller @RequirePermission"。

---

### D5 — 两点报工 vs 逐道（报工/成本改动后）

```sql
SELECT factory_id, skip_process_reporting_default FROM factory_settings WHERE factory_id IN ('F006','F001');
-- 期望: F006=true(两点) / F001=false(逐道)
SELECT product_type_id, process_name, reporting_required FROM product_work_processes
WHERE factory_id='F006' ORDER BY product_type_id, process_order;
-- 期望: 仅首末2道 reporting_required=true，中间false
```
- 两点报工人工常 null = **诚实**(F006做不了逐道，人工"登下一期")。真bug是"配了工价+审批了却 null"或"伪造0/估算"。
- 多段(原料→半成品→半成品→成品)每段 unitCost 移动均价滚动。

---

### D6 — 状态机边界（状态机改动后）

| 场景 | 步骤 | 期望 |
|---|---|---|
| 撤回自愈 | 见 D2-A2 | 清→重报新值 |
| 盘点月底门 | 非29日发起盘点 | 409"只能29日后发起"+actionHint = **防呆正常**(非bug)，可当亮点演 |
| 幂等防重复 | 连点快速出库/收款/开票 | 第2次 409+跳转，不建N个重复单 |
| 超收/少收 | 收货量>下单(含30%超收) | 异常单，采购员 ACCEPT/RETURN |
| 撤回快速通道 | 本人+无下游消费+5min窗口 | 免审批人但G1守卫不绕 |
| 四眼原则 | 撤回 submittedBy==approvedBy | 403(submittedBy≠approvedBy) |

---

### D7 — 多端一致（跨端改动后）

同一业务三端验：
- **web-admin**(139:8086): 管理端全功能。
- **RN App**(小米真机 f79c50d6，OTA 最新 bundle，logcat 查 `No update available`): 操作员报工/领料/入库/撤回/调拨接收。
- **AI chat**(web 内): 自然语言下单/查询/配工序，渲染卡片。
期望: 同一数据三端一致；RN 报工→web 批次详情看到证据相册；AI 路径鉴权同 HTTP。

---

### D8 — 数据质量 / 诚实空（持续）

| 验点 | 期望 |
|---|---|
| 缺数据 | 显"暂无数据"诚实空 + 3步引导，**不返假数据** |
| 诚实 null | 人工未结显 null+hint，不伪造 0 |
| BOM 成本缺 | 显"待评估"不假展开 |
| 脱敏 | 非财务角色金额列 null 非 0 |
| AI 洞察 | 观察动词无因果词，无方括号实体名，claims-pinning |

---

## 3. 部署后回归 smoke（每次 prod 部署必跑，~15min）

最小集（任一失败阻断上线）:
1. 三端口健康: `curl 10010/10020/8083 health`
2. 登录: f006_admin + f006_cashier 都拿到 token
3. 凭证三行: 查最近含税 SO 的 voucher_entries=3 行平衡
4. 两点配置: factory_settings F006=true
5. 关键页可达: 销售订单/生产批次/付款申请/凭证详情 各 200
6. RBAC: f006_cashier 三付款页不 403（#772 回归）
7. AI 路径: 低权角色经 AI 删批次被拦

---

## 4. 已知 gotchas + 纠偏（Codex 别重新踩）

| 坑 | 真相 |
|---|---|
| 登录响应字段 | `.data.token`（非 accessToken）|
| 活跃端口 | 10010/10020 蓝绿轮换，谁 200 用谁 |
| prod 写用户 | cretas_user（非 cretas）|
| multi-stage-cost 路径 | `/sales/orders/{id}/multi-stage-cost`（有 /sales 前缀）|
| 采购订单页 | `/procurement/orders`（**不是** /procurement/purchase-orders=404）|
| 仓储页 | `/warehouse/materials` `/warehouse/stocktakes`（不是 /inventory/*=404）|
| 凭证页 | `/finance/voucher/:id` + `/finance/voucher-export`（/finance/vouchers=404）|
| BOM packQty 字段 | 添加原辅料弹窗**切到「包材」类别**才出"每产品用量"（停在默认"原料"看不到，非缺失）|
| 多段成本返空 | 单段产品返空段+hint = 正确行为非bug |
| 两点报工人工 null | F006 诚实值非bug |
| 盘点 409 | 月底门防呆非bug |
| f006_warehouse_worker web 登录弹回 | 仓管走 RN 非 web，非bug |
| multi-SO 用 related_orders | 错路径；客户面是 web「加号」→ sourceOrderIds |

---

## 5. 当前已知真 bug / 待 organizer 处置（2026-06-11 gate 结论）

| ID | 严重 | 现状 | 处置 |
|---|---|---|---|
| BUG-RCV | 🟡 真代码gap | 采购收货生成批次 unit_price 不从 PO 行价兜底，receive 没带价→批次 null→材料成本丢失（仓管文化低不填价时尤其，撞防呆核心）| **待 Steve 定**: 后端 receive 价 null 时兜底 PO 行价（红线财务+防呆）。admin 手填可绕过→非硬演示阻断 |
| BUG-MR500 | 🟡 待triage | `/processing/material-receipt` 500（`/material-batches` 可用替代）| 次要端点，triage 后定 |
| SO-PRICE-0 | ✅ 已修 | 新建SO单价落0 → 根因=遗留 AUD3_PROBE 审计定价策略(TIERED 100%折扣)毒化所有≤100量订单 | organizer 已 disable 策略+重验落价68。**这是真live bug非测试假象**（影响F006所有新单）|
| MULTI-SO-REL | 🟢 非bug | GPT 报"多SO只回填主单" | #771 代码对(遍历sourceOrderIds)，GPT 用了 legacy related_orders 路径=测试假象。backlog: related_orders 是否也纳入回填(低优先非客户路径)|

---

## 6. 交付物规范（每轮跑完）

1. audit doc → `docs/audits/liushanmen/<日期>-<维度>.md`: 每项 ✅/⚠️/🔴 + 期望vs实际 + SQL/响应原文 + Headed Verification block。
2. 截图 → `docs/audits/liushanmen/<日期>-<维度>-screenshots/`（headed 中文无方块）。
3. bug 清单（精确 repro，别自己改）。
4. DEMO 数据 created-IDs 台账 + 清理清单（Codex 不删，交 organizer）。
5. 诚实结论: 这条链能否端到端跑通 + 几个断点（验不了写未验证）。

---

## 7. 五类矩阵挂钩 — 优先测试靶清单（最重要，覆盖转录全要求）

> **数据源**: `docs/plans/2026-06-11-liushanmen-matrix-5dim.md`(136 功能点 × 5 成熟度) + `2026-06-11-liushanmen-5dim-audit.md`(跨 SP 综合) + 追溯矩阵 `docs/meetings/2026-06-09-liushanmen/verification-matrix.md`(456 条) + 两份转录 `transcript.txt`/`transcript-2b.txt`。
>
> **⚠️ 关键框架**: 五维矩阵是 **2026-06-11 快照**，其后 **18 PR #754-771 已修一批**（SP8 generate-code/SP9 双口径@PriceSensitive #707/SP5 毛利红线 warn #714/SP6 cashier #772+收货屏 #709/SP7 守卫 #700/SP10 中报价 #710/SP12 撤回角色 #705+AI RBAC #719…）。所以矩阵的 ⬛🟧⚠️🔗 是 **gap 假设源，不是当前真相**。**Codex 任务 = 用真实数据验"这个 gap 现在还开着没"** —— 这本身就是 gate（已修的标 ✅CLOSED，仍开的标 🔴OPEN+证据）。

### 五维成熟度 → 测试动作映射
| 矩阵维度 | 含义 | Codex 测试动作 |
|---|---|---|
| ⬛ MISSING (33) | spec 承诺未建（多前端/RN屏/入口）| 验"现在建了没"：找 UI 入口/RN 屏/端点是否存在 |
| 🟧 BUILT_UNTESTED (22) | 字段/逻辑在但无测 | **真实路径 + SQL 坐实**它真生效（最易"字段在但没接线"）|
| 🟨 BUILT_TESTED (45) | 后端+Mockito，缺真实 DB/并发/集成 | 真实 DB 链路 + 并发 + 集成验证（mock≠真实）|
| 🟩 +UIUX (27) | UI 建了但**零 headed E2E** | headed 真操作 + 截图 |
| ✅ +前端验 (6) | 已 API+DB 实证 | 回归 smoke 即可 |

### 🔴 最高优先靶 — ⚠️假完整 13 处 + 🔗孤岛 6 处（"看着完成实际断"，real-data E2E 专抓）
> 这 19 处是"单测绿/UI在/字段在但真实路径断"的重灾区。**每个都要：真实数据走一遍 + SQL/响应坐实 + 判 CLOSED/OPEN**。

| # | SP | 靶（矩阵原述） | 验法（真实数据） | 注 |
|---|---|---|---|---|
| 1 | SP1-T3 | 移动加权均价并发仅 mock-verify 无真实 DB 锁 | 并发两收货同物料→查均价是否加权正确无丢失 | #713 ensure-row-then-lock 后复验 |
| 2 | SP2-T2 | BUG-R1/R2 用 assertTrue(true) 占位 CI 不报警 | 真撤回链验回滚，非看测试 | |
| 3 | SP3-§4.1 | postSemiOutputLedger 并发/集成全缺 | 多段报工真实链查 unit_cost 逐段 | |
| 4 | SP3-§6 | LaborCostConfig **BOM 前端表单无字段无法配置** | 开 BOM 表单找人工成本/kg 录入控件 | #707 后复验 |
| 5 | SP3-§10 | 事件链零测 B-47 测试环境 unitCost=null 链未通 | 真实工价+审批报工→costUnitPrice 回填 | 见 D2-A1 |
| 6 | SP4-A3 | 类别数字前缀 15 测试全绿但 LabelService 未调用→标签仍'MA' | 建原料→查实际标签前缀是否生效 | |
| 7 | SP6-供应商银行信息 | 实测 null，出纳不知打款到哪 | 出纳付款屏看银行信息是否显示 | |
| 8 | SP7-F4 | 入库守卫**生产代码零调用点红线形同虚设** | 无单据直调入库 API→是否被拦 | #700 后复验 |
| 9 | SP7-WHInventoryCheck | 第141行仍直调 updateBatch 绕过状态机 | 盘点改库存是否走审批门 | |
| 10 | SP8-T02 | generate-code 预览端点后端不存在→前端404静默假编码 | 调 generate-code 端点真返码 | 转录16位需求，#754 后复验 |
| 11 | SP8-字典页 | 字典无 UI→级联永远空 P0 阻断 | 找分段字典管理页入口 | |
| 12 | SP10-QuotationTask | laborPerKg silent drop(service/convertToDTO 缺2处) | 建报价填 laborPerKg→查 DB 落库 | DTO往返坑 |
| 13 | SP10-is_trial | CreateRequest 无字段→勾选不落库 silent drop | 建试制批次勾 is_trial→查 DB | |
| 14 | SP10-三价对比 | DTO 零@PriceSensitive 成本对销售全量泄露 | sales 角色调三价端点→金额是否脱敏 | X-1 |
| 15 | SP11-F2 | 进销存导出调错方法→下载凭证流水非进销存货不对板 | 真导出进销存 Excel→开看是不是进销存 | CRIT |
| 16 | SP11-R2 | xlsx 下载绕过 JSON 脱敏=数据泄露通道 | sales 角色导出→Excel 里金额是否脱敏 | CRIT X-1 |
| 17 | SP12-X3/T3 | 旧 POST /cancel 未封任意角色绕过审批 | 低权角色调旧 cancel→是否被拦 | #705 后复验 |
| 18 | SP12-T5 | DisposalRecord submitForApproval 死代码旧 approve 直批 | 报损审批是否走 workflow | |
| 19 | SP12-T7 | Python 打印2路由完全缺失→Java/UI 调用必502 | 打印按钮→是否 502 | #674 后复验 |

### 🟥 CRITICAL 严重度靶（矩阵标 CRIT，逐个判现状）
SP7-F4 入库守卫 / SP7-WHInventoryCheck 绕过 / SP8 字典无UI / SP10 三价脱敏 / SP11-F2 货不对板 / SP11-R2 导出泄露 / SP12-T3 cancel绕过。**每个 prod 真实验，CLOSED/OPEN 给证据**。

### X-1 ⛔ 成本脱敏系统性（SP9+SP10+SP11 同根，CRITICAL）
销售角色是否能拿到工厂成本：用 **f006_sales_mgr** 调①labor-efficiency 端点 ②三价对比端点 ③进销存/凭证 Excel 导出 —— 三处金额是否都脱敏。#707/#693/#695 修了一批，**验现在还漏不漏**。

---

## 8. 六大业务流真实数据 E2E（用 prod F006 + 六扇门真实数据/图片）

> **数据源**: `六扇门工厂数据、/6.1-6.3/`（真实微信群报工照片 5.31~6.5 + 订单 Excel + 产品工序表）。已有 groundtruth 在 `cretas-e2e-replica/e2e-replica/`（猪舌6道/牛腱5道/掌中宝5道真实逐工序重量/工时/副产物/照片映射）。
> **原则**: 不编数字 —— 用真实微信报工的逐工序投入产出（如猪舌"294.5+245.5=540Kg"、托盘24.5、第1车320盒）、真实订单量、真实工序结构。写操作 remark 标 `DEMO-<flow>-<date>`。

逐流按转录细节要求验（每流 = 一个 audit doc）：

### 销售流（E 流 / SP5+SP10）
转录要求: 单价可空、客户13%税率、**毛利红线红字预警非阻断**、含税未税双值、销售方向付款。
- 含税下单(真实订单量)→低于底价红字(非409)→财审→**凭证三行**→含税未税双值显示
- 销售付款申请(对客户退款/返利)→出纳付款
- ⚠️ 验 #714 销售价≥预估价 warn 非阻断；验 sales 角色三价脱敏(X-1)

### 采购流（D 流 / SP6）
转录要求: 合同号/结算方式/开票提醒、超收少收异常动单、付款双审+出纳、**供应商银行信息**、移动均价。
- 真实供应商建PO(合同号/结算方式)→收货(真实超收)→异常单ACCEPT/RETURN→入库**带价**(⚠️BUG-RCV)→移动均价
- 付款申请→双审→**出纳屏看银行信息**(⚠️假完整#7)→mark-paid三写
- ⚠️ 验 cashier 三付款页(#772)；RN 收货屏(#709)；G2 全入库前置/PREPAID 豁免

### 研发流（G 流 / SP10）
转录要求: 样品审核自动建报价任务+BOM草稿+通知销售、预报价、**中试批次进研发库WH-RD不混可售**、中报价汇算、三价对比、**laborPerKg**。
- 样品创建→审核→验自动建 QuotationTask+BOM草稿+SampleApprovedEventListener 通知
- 预报价(填 laborPerKg ⚠️silent drop#12)→中试批次(勾 is_trial ⚠️silent drop#13，进 WH-RD)→中报价→三价对比(⚠️脱敏#14)

### 财务流（H 流 / SP11）
转录要求: **仅导金蝶/用友凭证表头**、进销存四时点、科目余额、月结期初快照。
- 进销存四时点(期初/期入/期中出/期末，按SKU+原/辅/包材)→**导出Excel验是不是进销存(⚠️货不对板#15)**
- 凭证序时账+科目余额导出(金蝶表头)→**sales角色导出验金额脱敏(⚠️泄露#16)**
- 月结触发期初快照(⚠️SnapshotService 零测，快照错污染后续)

### 生产流（C/B 流 / SP1+SP2+SP3）
转录要求: 同单双产出(半成品+成品)、二次加工、整单撤回自愈、移动均价、多段成本、超支报警、**两点报工**、人工双口径。
- **用真实微信报工数据**: 掌中宝/猪舌/牛腱真实逐工序投入产出+副产物(肥油/骨头/料头)+真实照片(群内报工jpg)上传 evidence
- 双产出(半成品+成品同单)→web 批次详情双产出展示(⚠️SP1-T6缺)+SFI流水
- 二次加工(原料→半成品A→半成品B→成品)createSecondaryPlan→多段成本(D2-C)
- 整单撤回→自愈(D2-A2)→重报
- 两点报工(F006默认，中间免报)→人工双口径对比(⚠️SP9最差80%MISSING，quoted/actual 是否还双null)
- 超支报警 CostVarianceService 阈值三级

### 仓储流（F 流 / SP7）
转录要求: 四仓体系(RAW/WIP/FG/WH-RD/SALTED)、入库守卫(无单禁直调)、盘点(仓库发起→财务审批门→生效)、报损盘盈盘损分列、**盐化独立扣量+报表**(转录4次强调)、调拨仓库维度。
- 入库守卫(⚠️CRIT#8 无单直调是否拦)→盘点(月底门→财务审批→生效，盘盈盘损分列)
- 报损(⚠️PRODUCTION_WASTE 枚举前后端是否一致#SP7)→审批 workflow
- **盐化独立扣量+报表**(⚠️转录4次强调，是否实现)→调拨(仓库维度过滤)

### 交付（六流各一 audit doc）
`docs/audits/liushanmen/2026-06-XX-<flow>-realdata-e2e.md`: 转录要求逐条 ✅满足/⚠️部分/🔴缺 + 矩阵 gap CLOSED/OPEN 判定 + 真实数据 SQL 坐实 + headed 截图 + Headed Verification block。

---

## 9. 推荐执行顺序（Codex）

1. **回归 smoke**（§3）— 确认基线没崩。
2. **🔴假完整/孤岛 19 靶**（§7）— 最高 ROI，专抓"看着完成实际断"，逐个判 CLOSED/OPEN。
3. **六大流真实数据 E2E**（§8）— 按转录细节，用六扇门真实数据/照片，一流一 doc。
4. **CRITICAL + X-1 脱敏**（§7）— 安全红线，sales 角色三处脱敏。
5. **维度专项**（§2 D1-D8）— 按改动域补。

每完成一批 → 回 organizer(Opus) gate。🔒 红线（财务/权限/迁移/业态）发现 bug → organizer 修+从 main 部署，Codex 不改码。
