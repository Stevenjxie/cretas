# 2026-06-12 RN 多角色流程与 OA 推荐

## 阻断 / 高风险

1. `CRITICAL` 财务主管与出纳 RN 端没有业务入口。`f006_finance_mgr` 和 `f006_cashier` 真机登录后只看到「首页 / 考勤 / 我的」和「系统设置」，没有采购财审、销售财审、出纳付款、价格异常、盘点审批或待办入口。6.9 转录明确要求“把财务拉进去，每天看价格/异常/付款”，当前 RN 端无法支撑。
2. `HIGH` RN 没有统一 OA「我的待办」。仓管有局部「领料调拨」、采购/销售有业务列表，但财务、出纳、跨部门审批没有统一收件箱；审批散在 factory-admin 栈里，真实低权角色进不去。
3. `HIGH` 代码路由与屏幕注册不一致：`finance_manager` / `cashier` 没有专属 navigator，会落到 `MainNavigator`；但付款/财审屏又注册在 `FAManagementStackNavigator` 或 `ProcurementManagerNavigator`。这解释了真机上财务/出纳看不到业务入口。
4. `MEDIUM` 仓管「盘点」快捷入口可见，但本轮点按未跳转，先按未验证记录；库存/入库页面本身可打开且有真实数据。

## 真机结果矩阵

| 角色 | 账号 | RN 结果 | 判定 | 证据 |
| --- | --- | --- | --- | --- |
| operator | `f006_moyun` | 投入报工 8kg + 图片 + SQL readback | PASS deep | `production_reports.id=505` |
| operator | `f006_weizj` | 时段报工 07:00-15:00/1人 + 图片 + SQL readback | PASS/WARN deep | `production_reports.id=506` |
| 仓管员 | `f006_warehouse_worker` | 仓储工作台、入库、库存均可打开，有真实数据 | PASS medium | [home](./rn-role-f006_warehouse_worker-home-real.png), [inbound](./rn-role-f006_warehouse_worker-inbound.png), [inventory](./rn-role-f006_warehouse_worker-inventory.png) |
| 采购主管 | `f006_procurement_mgr` | 采购订单列表可打开，20 条、总金额 98,611.18、新建采购单可见 | PASS medium | [purchase](./rn-role-f006_procurement_mgr-purchase-tab.png) |
| 销售主管 | `f006_sales_mgr` | 销售订单列表可打开，37 条、确认/取消按钮可见 | PASS medium | [sales](./rn-role-f006_sales_mgr-sales-tab.png) |
| 财务主管 | `f006_finance_mgr` | 只有首页/考勤/我的，无财务待办 | FAIL smoke | [home](./rn-role-f006_finance_mgr-home3.png) |
| 出纳 | `f006_cashier` | 只有首页/考勤/我的，无出纳付款 | FAIL smoke | [home](./rn-role-f006_cashier-home3.png) |

## 代码证据

- `AppNavigator.tsx:98-134`：显式分流仓库、operator、销售、采购、viewer，未分流 `finance_manager` / `cashier`，末尾 fallback 到 `MainNavigator`。
- `FAManagementStackNavigator.tsx:517-569`：采购财审、出纳付款屏注册在 factory admin 管理栈。
- `FAManagementStackNavigator.tsx:612-618`：盘点管理、价格异常审批也在 factory admin 管理栈。
- `ProcurementManagerNavigator.tsx:59`：采购主管栈中注册了 `CashierPaymentList`，但这不等于出纳角色可达。
- `CashierPaymentListScreen.tsx:7-9`：注释写角色为 `finance_manager / factory_super_admin`，且提到采购路径兼具付款职能；真机结果显示财务/出纳入口没有接上。

## 6.9 转录依据

- 现场手机端适合做简单必要动作，不适合承载全 ERP：复杂/大数据量留 PC，现场单点操作放手机端。
- 仓库可多账号，但汇总端应单点问责，避免并发写库存卡死。
- 仓库/生产现场需要收货、领料、调拨接收、盘点、库存查询。
- 财务需要每天看价格异常，不能等半个月后再发现问题。
- 采购入库和实际入库值是成本口径基础；超收/少收应先不阻塞现场，再异步回传采购异常。
- 跟钱有关的退货、付款、销售财审、采购财审必须经过财务确认。
- 盘点数据应先入暂存/盘点栏，财务批准后才生效，必须保留修改足迹。

## 推荐放进 RN App 的功能

### P0：周五前/演示优先

1. **统一 OA 待办首页**：所有角色登录后第一屏显示「我的待办」。字段最少包括业务类型、来源单号、金额是否脱敏、状态、发起人、提交时间、下一动作。支持一键进入详情，不在首页直接暴露不该看的金额。
2. **财务主管 RN 待办**：采购财审、销售财审、退货财审、价格异常、盘点审批。按钮只做通过/驳回/补资料，复杂凭证和报表留 PC。
3. **出纳 RN 待办**：只看已审批付款单，执行“标记已付款/驳回补资料/查看收款方信息”。不能让采购主管替代出纳入口。
4. **仓管 RN 工作台**：保留现有入库/库存/调拨/领料入口，补强“选采购单收货”“盘点任务执行”“差异提交到暂存栏”。
5. **operator RN 报工**：继续保持轻量；必须支持个人工序、投入、时段、完工出成、照片证据。时段照片标签持久化需修。

### P1：演示后 backlog

1. **采购 RN**：采购单、新建/编辑草稿、价格趋势、异常回传、付款申请状态。不建议把完整供应商主数据维护放手机端。
2. **销售 RN**：销售订单、确认/取消、缺货/备货状态、付款状态。销售创建价格可保留，但成本/毛利按权限脱敏。
3. **仓库异常 RN**：超收/少收、不合格、报损、退仓，现场先入库/暂存，异常流转到采购/财务。
4. **盘点 RN**：发起、执行、差异、拍照、提交，财务批准后生效；所有修改留审计。
5. **OA 通知**：推送只提醒“有待办/已退回/已通过”，不在通知正文泄露金额和成本。

## 本轮直接执行的验证

- 真实 RN App + F006 prod 账号登录。
- operator 两人真实报工并 SQL 坐实。
- 仓管员打开仓储首页、入库管理、库存管理。
- 采购主管打开采购订单真实列表。
- 销售主管打开销售订单真实列表。
- 财务主管/出纳登录坐实缺少业务入口。

## 未验证 / 限制

- 本轮未提交财务/出纳写操作，因为 RN 端没有入口。
- 仓管盘点快捷入口可见但点按未跳转，未做 deep。
- 没有修改任何业务代码。
