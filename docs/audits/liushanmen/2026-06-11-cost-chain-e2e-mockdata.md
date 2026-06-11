# 六扇门成本链 E2E 实跑 (DEMO mock 数据) — Tier0 #12

**日期**: 2026-06-11
**环境**: **test 环境** (server 47.100.235.168, Java 10011, DB `cretas_db`)。**未碰 prod (10010)**。
**目标**: 用 DEMO mock 数据填补 test 环境数据缺口, 把成本链从报工 → WIP → 财审端到端真跑通一次。
**结论**: 成本链**走通到 link 4 (WIP unit_cost)**; **link 5 (事件回填 SalesOrderItem.cost_unit_price) 被一个真实代码 bug 阻断**, 模拟回填后 link 6 (财审 actualCost) 正常点亮。**需 organizer 修代码** (见 §4)。

> ⚠️ **透明标注**: 本次全部用 **DEMO mock 数据** (`DEMO-` 前缀), **不是六扇门客户真实数据**。仅用于证明成本链代码管道在配齐输入后能跑通, 并定位剩余断点。

---

## 1. 背景 — 之前为什么没走通

成本链代码已修 (#699 FINISHED 路径发 `ProductionCostUpdatedEvent`), 但端到端从未用真数据走通, 因 test 环境数据缺口:

| 缺口 | 影响 |
|---|---|
| F006 所有工序 `work_processes.standard_hourly_rate = null` | `YieldReportServiceImpl.computeLaborCost` 诚实返回 null → labor_cost 算不出 |
| 原料批次 `material_batches.unit_price = null` | `computeMaterialCost` 诚实跳过无价项 → material_cost 算不出 |
| 报工全 SUBMITTED, 0 APPROVED | `postApprovedOutput` 从不触发 → 无 WIP unit_cost, 不发成本事件 |
| 计划未绑 `source_order_id` | 即便发事件, 回填监听器找不到销售订单 |

Steve 指示: 用 DEMO/mock 数据填这些缺口直接跑, 别等真数据。

---

## 2. Seed 的 DEMO 数据 (test 库, `DEMO-` 前缀)

| 实体 | ID / 值 | 说明 |
|---|---|---|
| 工序工价 | `WP-F006-ZS-*` (猪舌 6 道) `standard_hourly_rate = 50.00` | 原为 null, DEMO 配 50 元/时 |
| 销售订单 | `DEMO-SO-COSTCHAIN-001` status=`FINANCE_APPROVED` | 猪舌 100kg |
| 订单行 | item id 271, product `4e345886…` (猪舌), qty 100kg, unit_price 99 | cost_unit_price 初始 null |
| 生产计划 | `DEMO-PLAN-COSTCHAIN-001` `source_order_id=DEMO-SO-COSTCHAIN-001` | **关键: 绑了 source_order_id** |
| 生产批次 | id **1916** `production_plan_id=DEMO-PLAN-COSTCHAIN-001` | 猪舌 100kg |
| 工序任务 | `work_process_tasks` id **104**, process 修油 (order 1) | planned 100kg |
| 原料批次 | `DEMO-MB-COSTCHAIN-001` 猪舌原料 `unit_price=20.00` 200kg AVAILABLE | DEMO 配 20 元/kg |

报工通过**真实 API** 提交 + 审批 (非直写 DB), 让 `YieldReportServiceImpl` / `WipInventoryServiceImpl` 真实计算:
- 提交: `POST /api/mobile/F006/production/batches/1916/reports` (f006_workshop) → reportId **574**
  - body: FINISHED 产出 95kg, laborSegments `[{08:00-10:00, headcount=3}]`, materialBatchRefs `[{DEMO-MB-COSTCHAIN-001, 100kg}]`
- 审批: `PUT /api/mobile/F006/process-work-reporting/574/approve` (f006_admin) → status APPROVED, 触发 `postApprovedOutput`

---

## 3. 成本链逐环节非null验证

| # | 环节 | 字段 / 来源 | 值 | 计算 | 状态 |
|---|---|---|---|---|---|
| 1 | 报工人工成本 | `production_reports.labor_cost` (id 574) | **300.00** | 3人 × (120min/60) × 50元/时 | ✅ 非null |
| 2 | 报工材料成本 | `production_reports.material_cost` (id 574) | **2000.00** | 100kg × 20元/kg | ✅ 非null |
| 3 | WIP 单位成本 | `semi_finished_inventory.unit_cost` (batch 1916, id 6) | **24.2105** | 2300 / 95 | ✅ 非null |
| 4 | WIP 累计成本 | `semi_finished_inventory.accumulated_cost` | **2300.00** | 300 labor + 2000 material | ✅ 非null |
| 5 | 成本事件回填订单 | `sales_order_items.cost_unit_price` (item 271) | (模拟后) 24.2105 | 事件 → 监听器回填 | ⚠️ **被代码 bug 阻断** (见 §4) |
| 6 | 财审成本明细 | `cost-breakdown` API `actualCost` | **2421.05** | 100kg × 24.2105 | ✅ 非null (回填后) |

### Link 1+2 证据 (报工提交即算出, approve 前)
```
id=574 status=SUBMITTED output_kind=FINISHED output_quantity=95.00
total_workers=3 total_work_minutes=120
labor_cost=300.00  material_cost=2000.00  work_process_task_id=104  wip_mode=APPROVAL
```

### Link 3+4 证据 (approve 后 postApprovedOutput 滚动 WIP)
```
semi_finished_inventory (batch_id=1916):
 id=6 intermediate_batch_no=4e345886…-B1916-S1-104 source_work_process_task_id=104
 produced_quantity=95.00 available_quantity=95.00
 unit_cost=24.2105  accumulated_cost=2300.00  status=AVAILABLE
report 574: approval_status=APPROVED  wipPosted=true   ← 证明 postApprovedOutput 跑完
```

### Link 6 证据 (财审 cost-breakdown API, 回填后)
```json
{
  "actualCost": 2421.05,
  "actualProfit": -2421.05,
  "lines": [{
    "productName": "叮咚好食光轻卤门腔（猪舌）120g",
    "quantity": 100.0, "unitPrice": 99.0,
    "actualLineCost": 2421.05, "actualCostPerUnit": 24.2105
  }]
}
```
(totalAmount=0 / actualProfit 为负, 因 DEMO 订单无预付/开票流水; 成本侧完全正确传播。)

---

## 4. 🐛 断点根因 = 代码 BUG (非 seed 漏配) — 需 organizer 修

**Link 5 (`ProductionCostUpdatedEvent` → 回填 `sales_order_items.cost_unit_price`) 被代码 bug 阻断。**

#699 的修复**生效了** — approve 后 `WipInventoryServiceImpl.postApprovedOutput` 在 FINISHED 路径**确实发出了** `ProductionCostUpdatedEvent` (WIP unit_cost=24.2105 非 null, 满足发事件条件)。但 `@Async` 监听器抛异常:

```
ERROR [SimpleAsyncTaskExecutor-2] SimpleAsyncUncaughtExceptionHandler
  - Unexpected exception occurred invoking async method:
    com.cretas.aims.event.listener.OrderCostBackfillListener.onProductionCostUpdated
org.springframework.beans.factory.NoSuchBeanDefinitionException:
  No bean named 'transactionManager' available:
  No matching TransactionManager bean found for qualifier 'transactionManager'
```

### 根因
本项目是**多数据源**配置, `PlatformTransactionManager` bean 名是:
- `primaryTransactionManager` (`@Primary`, 主库 — `PrimaryDataSourceConfig.java:152`)
- `smartbiPostgresTransactionManager` (SmartBI 库 — `SmartBIPostgresDataSourceConfig.java:144`)

**没有**名为 `transactionManager` 的 bean。但两个成本监听器硬编码了错误的 qualifier:

```java
// OrderCostBackfillListener.java:33  +  OrderCostAlarmListener.java:32
@Transactional(transactionManager = "transactionManager",   // ❌ 该 bean 不存在
               propagation = Propagation.REQUIRES_NEW)
```

→ 每次成本事件触发, **两个监听器都抛 NoSuchBeanDefinitionException**, 回填永不执行 → `cost_unit_price` 永远 null → 财审 `actualCost` 永远 null。这正是"成本链代码已修但从没用真数据走通"的隐藏断点 (#699 的兄弟 bug, 在 fail-soft try/catch 之**外**, 在事务切面层抛, fail-soft 都救不到)。

### 建议修复 (organizer)
两个文件把 qualifier 改为存在的 `@Primary` bean (或直接删 qualifier 用 `@Primary` 默认):

```java
// OrderCostBackfillListener.java:33  +  OrderCostAlarmListener.java:32
@Transactional(transactionManager = "primaryTransactionManager",
               propagation = Propagation.REQUIRES_NEW)
```

修复后无需 seed 改动, 同样的报工→审批流程会自动回填 `cost_unit_price` → 财审 `actualCost` 自动点亮 (本次已用 SQL 模拟回填证明 link 6 正常)。

> **影响面**: 这是真实 prod 也存在的 bug (代码在 origin/main, prod/test 共享 jar)。任何工厂只要工序工价 + 原料单价配齐并审批报工, 财审 actualCost 都会因这个 qualifier bug 拿不到成本。属成本链关键路径, 建议优先修。

---

## 5. 清理 / DEMO 数据去向

- DEMO 数据全部 **`DEMO-` 前缀**, 留在 **test 库 (`cretas_db`)**, 不污染 prod。
- 工序工价 `standard_hourly_rate=50` 是对 6 道猪舌工序的 UPDATE (无前缀, 但仅 test 库; 属合理的 demo 配置, 也修复了真实数据缺口)。
- 未做任何 prod (10010 / cretas_prod_db) 改动。

---

## 6. 验证命令 reference

```bash
# test DB (server 47)
PGPASSWORD=cretas123 psql -U cretas_user -h 127.0.0.1 -d cretas_db

# 提交报工 (workshop) → 审批 (admin), 密码均 123456, 经 localhost (10011 仅对网关开放)
POST /api/mobile/F006/production/batches/1916/reports
PUT  /api/mobile/F006/process-work-reporting/574/approve
GET  /api/mobile/F006/sales/orders/DEMO-SO-COSTCHAIN-001/cost-breakdown

# bug 证据
grep 'OrderCostBackfillListener' /www/wwwroot/cretas/cretas-test.log
```
