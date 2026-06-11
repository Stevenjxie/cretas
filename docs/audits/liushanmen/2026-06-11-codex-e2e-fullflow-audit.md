# 六膳门 E2E Fullflow Audit - Codex

日期: 2026-06-11  
执行人: Codex  
目标: 按 `docs/dispatch/2026-06-11-codex-e2e-fullflow-handoff.md` 验证 F006 主链和 A-E 重点断点。  
原则: 不改业务代码；prod 只读验证；写操作仅发生在 test 环境的标准含税订单验证。

## Headed Mode Verification

- Web: `http://139.196.165.140:8086`
- API: prod active `47.100.235.168:10020`, test `47.100.235.168:10011`
- 浏览器: Playwright Chromium `headless:false`
- Viewport: `1920x1080`
- Args: `--lang=zh-CN`, `--font-render-hinting=none`, `--remote-debugging-port=9222`
- Profile: `.pw-cache-codex-fullflow`
- 截图目录: `docs/audits/liushanmen/e2e-fullflow-screenshots/`
- 账号: `f006_admin / 123456`

## 截图证据

- `00-login.png`
- `01-dashboard.png`
- `02-sales-orders.png`
- `03-sales-order-detail.png`
- `04-procurement-payment-requests.png`
- `05-sales-payment-requests-old-path.png`
- `06-production-plans.png`
- `07-production-batches.png`
- `08-production-reversals.png`
- `09-warehouse-material-types.png`
- `10-rd-samples.png`
- `11-production-bom.png`
- `12-finance-inventory-ledger.png`
- `13-material-types-create-dialog.png`
- `14-bom-page.png`
- `15-bom-packaging-tab.png`
- `16-bom-add-dialog.png`
- `ui-check-result.json`
- `bom-add-dialog-text.txt`

## A-E 重点结论

### A. 跨路径财务断点 (#771)

结果: 部分通过，核心拐弯项未能真实坐实。

1. 单 SO 成本回填有历史成功证据  
   prod `SO-20260603-0001` item `430` 已有 `cost_unit_price=0.3784`，关联 completed batch `1924`。

2. 多 SO 合并成本回填未验证  
   prod 查询 `production_plans.source_order_ids/related_orders` 未找到 F006 多 SO 合并计划记录。没有真实合并计划就不能判定 #771 A1 通过，也不能误报失败。

3. 撤回自愈未验证  
   prod `report_reversal_logs` 有 3 条 F006 `DEMO_TEST` 撤回日志，但对应 batch 1970/1972/1974 没有关联 SO，因此无法验证“撤回清空 costUnitPrice -> 重报回填新值”。

4. 采购同源存在流程断点/未验证  
   prod `sales_order_shortage_report` 为空；`SO-20260611-0001` 的 `shortage-report` API 返回 `NOT_AVAILABLE`；关联采购列表为空。代码层面自动链路使用 `SalesOrderShortageReportListener/ProcurementSuggestionService`，采购页面普通新建只传 `salesOrderId`，没有同源净需求展开证据。本轮不能坐实两路 `netRequired` 一致。

### B. 含税凭证三行

结果: 通过。

prod 已有订单:

```text
SO-20260611-0001 / V-2026-0054
entries=3, total_debit=4520.00, total_credit=4520.00
1 1122    应收账款                     debit 4520.00
2 6001    主营业务收入                 credit 4000.00
3 2221.01 应交税费-应交增值税-销项税额 credit 520.00
```

这与 handoff 预期 `4000 + 520 = 4520` 一致。

test 标准 API 也跑通过一次含税订单:

```text
SO-20260611-0002 / V-2026-0021
300 + 39 = 339, voucher_entries = 3
```

### C. 多段成本 (#770)

结果: 端点行为正确；订单级多段链未验证。

- 正确端点是 `/api/mobile/F006/sales/orders/{orderId}/multi-stage-cost`。
- handoff 中 `/api/mobile/F006/orders/{orderId}/multi-stage-cost` 返回 404，是路径不对。
- 对 prod `SO-20260611-0001` 调用正确端点返回:

```text
stages=[]
stageCount=0
dataSourceHint=订单已投产但无半成品 WIP 段记录 (纯成品直产 / 未做两点报工)...
```

按 handoff 第 6 节，这是正确行为，不是 bug。

prod 存在多段 WIP 历史数据:

```text
batch 1950 掌中宝: 6 stages, unit_costs 1:null, 2:2.9642, 3:4.3338, 4:9.8689, 5:19.2457, 6:3.5661
batch 1949 猪舌: 6 stages, unit_costs 1:null, 2:1.0850, 3:1.3185, 4:1.1344, 5:2.1614, 6:1.0719
batch 1924 猪舌: 10 stages, all stages have unit_cost
```

但这些未关联本次订单级端点，因此只能说明 WIP 多段成本数据存在，不能说明订单接口完整展示通过。

BOM 标准成本数字已坐实:

```text
掌中宝 total_material_cost = 2.5778
轻卤门腔猪舌 total_material_cost = 4.8578
纸片牛腱肉 total_material_cost = 5.3528
```

人工/制费为空，没有伪造 0，符合“两点报工人工登下一期时 null 是诚实”的要求。

### D. 两点报工

结果: 通过配置和工序要求验证。

```text
factory_settings:
F006 skip_process_reporting_default = true
F001 skip_process_reporting_default = false
```

F006 产品工序 `reporting_required` 只在首末工序为 true:

- 掌中宝: 水解化冻 true，气调 true，中间工序 false
- 猪舌: 修油 true，气调(分切装盒) true，中间工序 false
- 牛腱: 修油 true，气调(抛片装盒) true，中间工序 false

### E. UI 最后一公里

结果: 大部分可达，仍有字段/流程缺口。

通过:

- `/procurement/payment-requests` 可达并有真实付款申请数据。
- `/sales/payment-requests` 旧路径也已可达，不再 404，显示销售付款申请页面。
- `/rd/samples` 有“价位选料”按钮。
- `/warehouse/material-types` 新建弹窗有“关联固定客户”下拉和“包装层级”配置。

风险/缺口:

- BOM 添加原辅料弹窗未见 `packQtyPerProduct` 或“包材规格/每盒用量”字段；只有原料/辅料/包材 tab 和通用成品含量/出成率字段。
- 研发“价位选料”仅确认入口存在；本轮未创建样品和价位区间数据，未验证 min/max 推荐选料的提交闭环。
- 原料类型弹窗文案为“关联固定客户”，不是 handoff 里的“关联客户”；功能看起来等价，但需产品侧确认命名是否接受。

## 9 阶段流程覆盖

| 阶段 | 结果 | 证据 |
|---|---|---|
| 0 SKU/BOM | 部分通过 | BOM 成本数字 SQL 通过；BOM UI 可达；packQty 字段未见 |
| 1 销售订单/财审 | 通过 | prod SO 详情可达；含税凭证三行通过 |
| 2 采购 | 部分通过 | 付款申请可达；采购同源净需求未验证 |
| 3 生产计划 | 可达 | headed 截图 `06-production-plans.png` |
| 4 两点报工 | 通过配置 | SQL 验 F006 默认 true，工序首末 true |
| 5 撤回 | 可达但自愈未验证 | UI 有撤回日志；无关联 SO 成本链证据 |
| 6 财务 | 部分通过 | 凭证三行通过；多段订单端点返回正确空段 hint |
| 7 报表 | 可达 | 进销存台账页面可达，需日期范围后查询 |
| 8 盘点复盘 | 未深跑 | 本轮未做盘点写操作 |

## Bug / Risk List

1. P0 未验证: #771 多 SO 合并成本回填没有真实数据证据  
   需要创建 prod DEMO 多 SO 合并计划或在 test 补齐 F006 配置后重跑，最终用 SQL 坐实所有关联 SO 行 `cost_unit_price`。

2. P0 未验证: #771 撤回自愈没有关联 SO 的撤回样本  
   现有撤回日志无 `source_order_id`，不能证明 costUnitPrice 清空和重报回填。

3. P1 风险: 采购同源链路缺少真实落库证据  
   `sales_order_shortage_report` 为空，`shortage-report` API 返回 `NOT_AVAILABLE`，近期无 `PO-AUTO-*`。需用一个 DEMO SO 从财审后触发自动短缺报告，再与 UI 开始采购净需求比较。

4. P1 UI 缺口: BOM 包材行未见 `packQtyPerProduct/包材规格` 字段  
   截图 `15-bom-packaging-tab.png`、`16-bom-add-dialog.png` 未看到该字段。

5. P2 未完成: 研发价位选料只验证入口，未验证 min/max 推荐选料提交闭环  
   需要先有样品/原料价格数据再跑。

## 诚实结论

主链页面可达，含税凭证三行、F006 两点报工配置、BOM 标准成本数字均通过。  
本轮真正没有坐实的是 handoff 最强调的“拐弯”: 多 SO 合并回填、撤回重报自愈、采购两路同源。这三项需要新造带 DEMO 前缀的真实业务链或补齐 test 配置后重跑，不能用现有 prod 数据宣称通过。

