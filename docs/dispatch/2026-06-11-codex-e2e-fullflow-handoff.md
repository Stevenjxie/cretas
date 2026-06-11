# 六扇门 E2E 全流程测试 — Codex Handoff（自包含）

**日期**: 2026-06-11
**目标**: 真跑一遍 SKU创建→销售→采购→生产计划→两点报工→审批→财务→报表 完整链，验证端到端真通 + 重点观察本轮新修的财务正确性断点。
**执行者**: Codex（out-of-harness，本卡自包含全部上下文）。

---

## 0. 环境 + 账号 + 工具

| 项 | 值 |
|---|---|
| 后端 prod | `47.100.235.168:10010`（Java，活跃端口蓝绿轮换，查 10010/10020 哪个 200）|
| 后端 test | `47.100.235.168:10011`（写操作优先 test，cretas_db）|
| web-admin prod | `http://139.196.165.140:8086`（经网关）|
| prod DB | `PGPASSWORD=cretas123 psql -U cretas_user -h127.0.0.1 -d cretas_prod_db`（写用户 cretas_user，需 SSH 到 47）|
| test DB | 同上 `-d cretas_db` |
| F006 账号 | `f006_admin`/`123456`(factory_super_admin)、`f006_cashier`/`123456`(出纳)、`f006_viewer`(只读) |

### ⛔ 铁律（必遵守）
1. **prod 是真客户 F006 在用** —— 写操作优先 test(10011/cretas_db) 或 DEMO 前缀标记的 prod 数据。只读浏览验证可在 prod。
2. **Playwright 必须 headed**（`headless: false` + viewport 1920×1080 + `--lang=zh-CN` + `--font-render-hinting=none`），客户中文字体/演示价值。多 chat 用不同 PLAYWRIGHT_PORT（你用 9222）+ user-data-dir 隔离。
3. **截图全程留档**到 `docs/audits/liushanmen/e2e-fullflow-screenshots/`，audit doc 末尾带 Headed Mode Verification block。
4. **不改代码**（这是验证任务）；发现 bug → 报告，别自己改。

---

## 1. 完整 E2E 流程（9 阶段，每步 action + 期望）

> 主链顺序对应客户转录描述。逐阶段跑，每步截图，验"期望"。

### 阶段 0 — SKU + BOM（研发）
1. 研发样品创建(`POST /rd/requests` 或 web 研发页) → 审核通过
   - **期望**: 审核通过自动建报价任务(QuotationTask) + BOM草稿 + 通知销售主管(SampleApprovedEventListener)
2. BOM配置：原料/辅料/包材三类 + 16位编码 + 包材规格(packQtyPerProduct，仅PACKAGING行)
   - **期望**: BOM三tab(RAW/AUXILIARY/PACKAGING)；16位编码预览(`/material-segments/generate-code?l1=&l2=&l3=`)出真编码；包材规格自动入配比
3. 预报价 → 中试批次(is_trial，进研发库WH-RD) → 中报价(`/rd/quotations/{id}/calculate-mid-quote`) → 三价对比(`/rd/quotations/{id}/three-price-comparison`)
   - **期望**: 三价页出 预报价/中报价/实际成本 三列 + 偏差告警；试制批次不混入可售库存

### 阶段 1 — 销售订单
4. 销售下单(单价可空，客户配13%税率) → 毛利红线校验
   - **期望**: 单价可空允许；低于底价→**非阻断预警**(红字，非禁止)；客户 defaultTaxRate=13% → 订单自动算 taxAmount
5. 财审提交 → finance-approve → FINANCE_APPROVED
   - **期望**: **自动生成销售凭证三行价税分离**(借1122应收含税 / 贷6001收入未税 / 贷2221.01销项税)，借贷平衡。查 voucher_entries 确认 3 行（不是 2 行）

### 阶段 2 — 采购（两路）
6a. SO驱动：订单列表"开始采购"按钮 → BOM展开净需求 → 预填PO
6b. 单独采购：直接新建采购单(purchaseType=DIRECT，选供应商+物料，不依赖SO)
   - **期望**: 两路都能建PO；从SO的净需求 = BOM展开-现有库存
7. PO：合同号/结算方式/开票提醒 → 提交 → 审批 → 收货(超收/少收/异常动单) → 入库(MaterialBatch移动均价)
   - **期望**: 合同号可录可显；超收/少收产生异常单，采购员ACCEPT/RETURN决策；入库后原料移动均价更新
8. 付款申请 → 双端审批(财务+出纳) → 出纳付款(f006_cashier登录看待付款)
   - **期望**: 出纳屏(管理→进销存→出纳付款)显示待付款；markPaid 三写(PaymentRequest+ArApTransaction+Supplier余额)

### 阶段 3 — 生产计划
9. 以销定产：计划←SO（财审门拦截未财审SO）→ 多SO合并工单(加号追加销售单号) → 转批次
   - **期望**: 未财审SO建计划被拦(财审门)；多SO可合并；转批次 spawn 任务

### 阶段 4 — 两点报工（六扇门核心：做不了逐道工序，只两点）
10. F006默认两点报工(领料报工 + 产出报工，中间工序免报)
    - **期望**: 任务列表只 2 个报工点(第0道领料 + 末道产出)，非逐道；RN设备上 领料报工屏=领料批次+量+投入照(无时段/人工)
11. 多段：原料→半成品→半成品→成品，逐段报工
    - **期望**: 每段 半成品 unitCost 移动均价滚动；产出报工可续报(markComplete=false 累加，true 完工)
12. 完工 → FG入库(气调货标称vs实收)
    - **期望**: 出成率自动算；气调入库 实收≠标称时需"对方划单确认"(否则422)

### 阶段 5 — 审批/撤回
13. 报工撤回(**整单**，注意：只有整单 WHOLE_ORDER，无单工序) → 审批/快速通道(本人5min) → 成本回滚
    - **期望**: 撤回回滚 WIP/均价 + **清 SO 行 costUnitPrice**(自愈)；重报能重新回填新成本

### 阶段 6 — 财务
14. SP3回填cost_unit_price → 财审 actualCost
15. 成本拆分：材料逐料 + 人工 + 制费 + **多段成本链**(`/sales/orders/{orderId}/multi-stage-cost`)
16. 三层价格同屏对比 + 含税未税双值
17. 含税凭证 → 金蝶导出
    - **期望**: actualCost非null(配了工价+审批报工后)；多段成本链每段(料+人工+制费)+半成品unitCost+每盒贡献；两点报工人工"登下一期"诚实显示null+hint

### 阶段 7 — 报表
18. 进销存四时点(期初/期入/期中出/期末，按SKU+原料/辅料/包材) / 三价对比 / 人效达成率(M4/M5) / 盐化报表 / 金蝶凭证

### 阶段 8 — 盘点 + 复盘
19. 盘点：仓库发起→财务审批→生效(财务批前不能改库存)；盘盈盘损分列
20. 复盘：三价对比 + 成本拆分 + 进销存(系统现在能复盘，客户不用线下表)

---

## 2. 🔑 测试观察重点（本轮新修，优先验这些）

> 这些是 Fable 审计揪出、本轮刚修的，最容易出问题，**重点盯**：

### A. 跨路径财务正确性断点（#771，刚修，P0）
1. **多SO合并→成本回填**：建一个合并计划(2个SO)→报工→财审。**验：次级SO的行 costUnitPrice 也回填了**(改前永远null)。查 `SELECT cost_unit_price FROM sales_order_items WHERE sales_order_id IN (主SO, 次SO)`。
2. **撤回→成本自愈**：报工→财审(SO有costUnitPrice)→撤回→重报。**验：撤回后SO costUnitPrice清空(null)，重报后重新回填新值**(改前撤回后是脏数据永不刷新)。
3. **采购自动级联同源**：同一SO，点"开始采购"按钮算净需求 vs 财审自动级联(SupplyChainOrchestrator)算净需求。**验：两者净需求一致**(改前 legacy bom_items vs recipe 不一致)。

### B. 含税凭证三行（#742/#761，曾"merge了没走通"）
4. 经**标准API**建含税订单(客户配13%税)→财审→凭证。**验：voucher_entries 真 3 行**(借应收含税/贷收入未税/贷销项税)，不是2行。注意：客户必须有 default_tax_rate=13(F006 7个客户已配)，否则 taxAmount=0 → 退化2行。

### C. 多段成本（#770，你的核心需求）
5. 多段产品(原料→半成品→半成品→成品)→ `/sales/orders/{orderId}/multi-stage-cost`。**验：每段 料+人工+制费分列 + 半成品unitCost逐段涨 + 每盒贡献**；两点报工人工null时诚实显示"登下一期"(不伪造0)。

### D. 两点报工 vs 逐道（#718/#729）
6. **验：F006新建计划默认两点**(skip_process_reporting_default=true)；其他工厂(F001等)默认逐道。F006 报工只2点，中间免报。

### E. UI最后一公里（#769，刚修孤儿控件）
7. 验这些控件真出现+能提交：material-types编辑→关联客户下拉+包材规格输入；研发样品→价位选料面板；BOM→packQtyPerProduct(包材行)；销售付款菜单(`/sales/payment-requests`不再404)。

---

## 3. 数据准备（F006 已配，验证用）

- **F006 demo SKU 已配 BOM+单价**：椒麻掌中宝 ¥2.58/盒、猪舌 ¥4.86/盒、牛腱 ¥5.35/盒(材料+包材)。
- **F006 7个客户 default_tax_rate=13.00**(含税凭证靠这个)。
- **F006 工序 standard_hourly_rate 已配**(18-25元/时)，报工人工成本能算。
- **F006 仓库**：RAW/WIP/FINISHED/WH-RD(研发库) 已建(生产库隔离守卫真生效)。
- **F006 已坐实数据**：含税订单SO-20260611-0001(凭证V-2026-0054三行)、盐化扣量SD-DEMO-001、三价链(掌中宝/猪舌)、batch1924/1978成本链。
- 缺数据→test环境用mock data建，或prod用DEMO前缀。

---

## 4. ⚠️ 历史 gotchas（Codex 注意，这些坑踩过）

1. **build/单测过 ≠ 跑通**：前端调的路径要 vs 后端 @GetMapping 逐字核(曾有/processing vs /production 404)。
2. **后端真 ≠ 用户可达**：孤儿屏/零import/死列普遍(本轮修了一批)。验"UI入口真能点到"。
3. **mock过 ≠ 真实路径**：单测mock掉的，真实路径可能炸(含税凭证曾单测过但真实路径走seed模板=2行)。
4. **两点报工人工**：F006做不了逐道工序，人工常"登下一期"→null是诚实的，不是bug。
5. **活跃端口蓝绿轮换**：10010/10020 谁200用谁。prod DB写用户=cretas_user(非cretas)。
6. **RN报工**：设备OTA要最新bundle(查 logcat `No update available`确认)。

---

## 5. 交付物（Codex跑完产出）

1. 每阶段截图(headed，中文无方块) → `docs/audits/liushanmen/e2e-fullflow-screenshots/`
2. audit doc：每阶段 ✅走通/⚠️无数据/🔴坏 + 重点A-E(跨路径断点/含税三行/多段成本/两点报工/UI控件)逐项验证结果 + Headed Mode Verification block。
3. 发现的bug清单(别自己改，报告)。
4. 诚实结论：这条SKU→报表链能否从头跑到尾 + 几个断点。

---

## 附录：重点 A-E 可 copy-paste 验证命令（Codex 直接用）

> SSH 到 47：`ssh root@47.100.235.168`。DB：`PGPASSWORD=cretas123 psql -U cretas_user -h127.0.0.1 -d cretas_prod_db`（写测优先 `-d cretas_db`）。

### A1. 多SO合并→成本回填（#771 断点1）
```sql
-- 跑完 阶段3(多SO合并计划)+阶段4(报工)+阶段6(财审) 后：
-- 查合并计划关联的 SO，全部行应有 cost_unit_price（改前次级SO永远null）
SELECT pp.id AS plan_id, pp.source_order_id AS main_so, pp.source_order_ids,
       soi.sales_order_id, soi.product_type_id, soi.cost_unit_price
FROM production_plans pp
JOIN sales_order_items soi ON soi.sales_order_id = ANY(
  string_to_array(trim(both '[]"' from pp.source_order_ids::text), ',')
  || ARRAY[pp.source_order_id])
WHERE pp.id = '<合并计划ID>';
-- 期望：每个关联SO的行 cost_unit_price 都非null（不只主SO）
```

### A2. 撤回→成本自愈（#771 断点2）
```sql
-- 撤回前：SO行有 cost_unit_price
SELECT id, cost_unit_price FROM sales_order_items WHERE sales_order_id='<SO>';
-- 撤回后立即查：该批次 productTypeId 的行 cost_unit_price 应被清成 null
-- 重报+财审后再查：应重新回填新值（不是旧脏数据）
-- 期望：撤回→null→重报→新值（自愈链活）
```

### A3. 采购自动级联同源（#771 断点3）
```bash
# 同一SO，对比两路净需求：
# 路1 手动按钮：
curl -s "http://localhost:10010/api/mobile/F006/purchase/orders/suggestions/from-so/<SO>" \
  -H "Authorization: Bearer <token>" | jq '.data.items[] | {materialName, requiredQuantity, netRequired}'
# 路2 自动级联：财审SO后查自动生成的草稿PO（ProcurementSuggestion）的净需求
# 期望：两路 netRequired 一致（改前 legacy bom_items vs recipe 不一致）
```

### B. 含税凭证三行（#742/#761）
```sql
-- 标准API建含税订单(客户13%税)→财审后，查生成的凭证分录
SELECT ve.subject_code, ve.subject_name, ve.direction, ve.amount
FROM voucher_entries ve
JOIN vouchers v ON v.id = ve.voucher_id
WHERE v.source_business_id = '<含税SO>' ORDER BY ve.sort_order;
-- 期望：3行 — 借1122应收(含税) / 贷6001收入(未税) / 贷2221.01销项税
-- 前提：SELECT default_tax_rate FROM customers WHERE id='<客户>'; 必须=13.00（否则taxAmount=0→2行）
```

### C. 多段成本（#770）
```bash
curl -s "http://localhost:10010/api/mobile/F006/sales/orders/<orderId>/multi-stage-cost" \
  -H "Authorization: Bearer <token>" | jq '.data.stages[] | {semiCode, materialCost, laborCost, overheadCost, stageSubtotal, unitCost, contributionPerBox, laborHint}'
# 期望：每段 料+人工+制费分列；半成品unitCost逐段涨；两点报工人工null时 laborHint="人工登下一期"（不是0）
```

### D. 两点报工默认（#718/#729）
```sql
SELECT factory_id, skip_process_reporting_default FROM factory_settings WHERE factory_id IN ('F006','F001');
-- 期望：F006=true（默认两点）/ F001=false（默认逐道）
-- F006某产品工序：reporting_required 应只首末2道=true
SELECT product_type_id, process_name, reporting_required FROM product_work_processes WHERE factory_id='F006' ORDER BY product_type_id, process_order;
```

### E. UI控件可达（#769）
```bash
# headed 浏览验证（截图）：
# - /warehouse/material-types 编辑dialog → 关联客户下拉 + 包材规格输入(PACKAGING行)
# - /rd/samples → "价位选料"按钮 → minPrice/maxPrice + 推荐选料
# - /production/bom → 包材行 packQtyPerProduct 输入
# - /sales/payment-requests → 侧边栏"销售付款申请"菜单(不再404)
git grep -c "navigate.*CashierPaymentList\|SalesPaymentRequests" web-admin/src/  # 应>0
```

### token 获取（API 验证用）
```bash
curl -s -X POST "http://localhost:10010/api/mobile/auth/unified-login" \
  -H "Content-Type: application/json" \
  -d '{"username":"f006_admin","password":"123456"}' | jq -r '.data.accessToken'
# 注意：登录端点是 /auth/unified-login（不是 /auth/login）
```

---

## 6. ⚠️⚠️ 关键补充（Codex 必读，否则会卡壳或误报）

### ① 多段成本需要"多段半成品链"—— F006 可能没有，需先建
`/sales/orders/{orderId}/multi-stage-cost` 只在产品**真有 原料→半成品→半成品→成品 多段** 时才出多行。F006 demo SKU（掌中宝/猪舌/牛腱）目前大概率是**单段**(原料→成品)或没配半成品链 → 端点会返**单段或空**，这**不是 bug**。
- **要真验多段**：Codex 需先建一条多段链 —— 用**二次加工**(createSecondaryPlan + secondarySourceWipId)：原料→半成品A(批次1完工产半成品) → 半成品A→半成品B(批次2领半成品A产半成品B) → 半成品B→成品(批次3)。每步两点报工。然后查 multi-stage-cost 应出 3 段。
- 没建多段链时端点返单段=**正确行为**，别报 bug。

### ② test 环境(cretas_db) 缺 F006 配置数据 —— writes 可能失败
本轮 F006 的 BOM/单价/税率/仓库/工价配置**都在 prod(cretas_prod_db)**，test(cretas_db) **没有**。所以：
- 在 test 建 F006 订单/报工 → 可能因缺 BOM/单价/客户税率/工价 → 成本算不出/含税 0/凭证 2 行（**不是 bug，是 test 缺数据**）。
- **方案**：要么把 F006 配置复制到 test(BOM/单价/customers.default_tax_rate/standard_hourly_rate/仓库)，要么直接在 **prod 用 DEMO 前缀**数据跑(prod 有全配置，写操作标 DEMO-)。**推荐 prod+DEMO**(配置齐)，但写操作必须 DEMO 标记 + 跑完清理。

### ③ 诚实标：#771/#770 是"首次真实数据验证"
跨路径断点修(#771)和多段成本(#770)**单测全绿，但从没用真实数据端到端跑过**(本 session 没真跑，handoff 就是让 Codex 第一个真验)。所以：
- Codex 是**第一个真实路径验证者** → 重点 A/C 出问题是**有可能的**(mock 过≠真实路径过，本 session 反复踩这个)。
- 出问题**正常**，报告即可(回 organizer 修)，别假设"merge 了就一定对"。

### ④ 两点报工人工 null = 预期，不是 bug
F006 **做不了逐道工序，只两点报工**，人工常"登下一期"(期间分摊)。所以：
- 报工当下 `report.laborCost` = null → 多段成本 laborCost 列 null + laborHint="人工登下一期" → **这是诚实正确**，别报"人工成本缺失 bug"。
- actualCost 可能只含材料(人工未结)→ 也是预期。
- **真 bug 是**：人工**应该有**(配了工价+审批了报工)却显示 null，或显示伪造的 0/估算值。

### ⑤ 预期数字（验"对"不只验"非空"）
- 含税订单：net 4000 + 13%税 520 = 含税 4520 → 凭证 借应收4520/贷收入4000/贷销项税520。
- F006 SKU 材料成本：掌中宝 ¥2.58/盒、猪舌 ¥4.86/盒、牛腱 ¥5.35/盒(已配，成本拆分材料栏应出这些数，非"—")。
- 移动均价：旧批+新批加权 = (旧量×旧价+新量×新价)/(旧量+新量)，scale-4 HALF_UP。

### ⑥ 清理（跑完）
prod 上建的 DEMO 数据(订单/批次/凭证/计划)跑完用 DEMO 前缀筛出，soft-delete 或记录给 organizer 清。别留脏数据污染 F006 真客户。

---

## 7. 🔜 第二轮测试（§1-6 流程+SQL 坐实跑完后再做，不替代第一轮）

> **执行顺序**：先跑完 §1-6（完整流程 + SQL 坐实，确认链路真通 + 数据对）→ **再**做本轮 headed UI/防呆/UX 优化。本轮是第二遍，目的从"链路通不通"升到"客户好不好用"。

**核心目的不是"数据对不对"，是"客户(低素养仓管/操作员/财务)能不能顺畅用 + 防呆设计是否真生效 + 哪里别扭该优化"。**

- **主方法 = headed UI 走真实操作流程**(Playwright headed / MCP browser)：以真实角色身份，一步步点完整流程，像客户那样用。每屏截图，**观察使用是否顺畅、防呆是否拦住误操作、有没有多余步骤/不直觉处**。
- **SQL(§附录) = 辅助坐实**：UI 操作完，用 SQL 确认后台数据真对(costUnitPrice/凭证3行)。**不是 SQL-only 测试**，SQL 只是给 UI 操作的结果背书。
- **发现 UX 问题 → 报告优化建议**(不只报 bug)：哪步卡、哪个操作绕、哪里该加"快速关联/一键"、防呆缺哪条。

### 🛡️ 防呆设计验证（核心 —— 这是 Cretas 跟金蝶/用友的差异化，必逐条验）
> 客户原话(张权-仓管场景)："做仓管的年纪大文化素质低，不能太依赖他们，最好告诉他这个东西你要收多少就行了"。防呆 = **用户犯错前阻止**，不是犯错后报错。

逐个写操作 dialog 验这 5 条 + 4位一体：

| 规则 | headed 验什么 | 例 |
|---|---|---|
| **Rule 1 预先显示边界** | dialog 打开**即显**可操作范围 + input 带 `:max` + 超限禁提交(不是填完点提交才报错) | 入库 dialog 开即显"下单100，已收30，可入70(含30%超收=130)" + input max=130 + 超限 disable |
| **Rule 2 上下文带身份** | dialog 标题/内容带 品名+规格+单号+责任人，关键计划数字显示 | "完成生产 — 叮咚椒麻掌中宝120g (SO-...0123)" + 显计划数量200 |
| **Rule 3 自由文本改 dropdown** | 取消/退货/审批原因是**下拉标准选项**(非空白 textarea)，选"其他"才显输入框 | 取消原因下拉(客户撤单/原料缺货/质量问题/排程冲突/其他) |
| **Rule 4 写操作幂等防重复** | 重复点(快速出库/收款/开票)→ 409 "已有草稿 DLV-XXX，是否查看?" + 跳转按钮(不是建 N 个重复单) | 连点两次快速出库 → 第二次弹"已有草稿，跳转?" |
| **Rule 5 dead-end 改导航** | "X未配置/暂未开通"空状态带**下一步按钮**(跳配置页)，不是死 toast 让用户懵 | "调拨流程未配置，是否前去配置?" → 跳工作流设计器 |
| **4位一体 错误 toast** | 错误文案**具体**(非"操作失败") + UI显示=后端message原文 + **sticky(不自动消失,可手动关)** + 含下一步提示 | "发货行51未完成批次分配，请先分配批次" sticky |

### ⚡ 快速关联 / 一键操作（你点名要验的）
重点走这些"快速/一键"操作，验是否真省事 + 防呆：
1. **快速关联客户**(P8 #769)：物料编辑 → 关联客户下拉，选了之后建采购/开票是否自动带客户(省手填)
2. **开始采购一键带入**(#748)：SO 列表"开始采购"→ 是否自动 BOM 展开 + 预填供应商/物料/数量(操作员不用手算净需求)
3. **快速出库**：SO → 一键出库，验幂等(Rule 4)
4. **价位选料**(R14 #769)：研发输价位区间 → 一键推荐候选原料(不用翻全表找)
5. **多SO合并**(#762)：建计划时"加号"快速追加销售单号(不用分别建多个计划)
6. **报工防呆**(两点报工屏)：领料/产出 input 是否带 max(领料不超库存/产出不超投入)、是否显品名+批次+计划数(Rule 1+2)

### UX 优化观察（报告，不改码）
每屏问：①操作员一眼知道该填什么吗？②有没有多余步骤能合并？③该有"快速/一键"的地方有没有？④防呆拦住误操作了吗？⑤错误提示告诉用户怎么修了吗？→ 列优化清单给 organizer。

### 测试角色（用真实低权角色走，不只 admin）
- **操作员/仓管**(报工/领料/入库/盘点)：验防呆最关键(目标用户)
- **财务**(财审/凭证/付款)：验金额 max/幂等
- **销售**(下单/开始采购)：验快速关联/一键
- f006_admin 只用于看全局；**真实操作流程用对应低权角色**(防呆是为他们设计的)
