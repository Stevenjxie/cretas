# 六扇门两点报工流程实证 (#690 reportingRequired) — test 环境

**日期**: 2026-06-11
**环境**: test (10011 / cretas_db, 47 服务器, PGPASSWORD=cretas123 psql -U cretas_user -h 127.0.0.1)
**方法**: 直接 DB seed DEMO 配置 + 调用 **live test API (10011)** spawn-tasks/reports/yield (经真实 `WorkProcessTaskServiceImpl.spawnTasks` 过滤逻辑) + DB 断言
**登录**: f006_admin / 123456 (factory_super_admin, F006)
**⛔ 安全**: 全程只动 test (10011 / cretas_db)。prod (10010 真客户) 未触碰。全部 DEMO 数据 `DEMO-` 前缀。

---

## 背景 (Steve 纠正)

之前真机演示报工连的是 prod, 显示**逐道**报工 (油炸等中间工序在报) —— F006 prod `reporting_required` 全 true 未配置。
六扇门转录 (01:39 张權) 明确"中间全部省掉了"。正确流程 = **只报领料(首道) + 成品/半成品产出(末道), 中间免报**。

#690 已实现 per-工序 `reporting_required` (`ProductWorkProcess`), default true (逐道); false = 免报 (spawn 跳过, 不生成 `work_process_task`, 配置行保留供溯源)。

本次实证 = #690 的能力**真能产出六扇门两点流程** (操作员任务列表只见领料 + 产出, 中间道不出现)。

---

## 前置确认: #690 已部署到 test

- migration `V20261015_01__pwp_reporting_required.sql` 已 apply:
  ```
  reporting_required | boolean | DEFAULT true   ← information_schema 确认列存在
  ```
- spawn 过滤代码 (origin/main `WorkProcessTaskServiceImpl.java` line 156-158):
  ```java
  // Wave2 可配置报工粒度: 跳过免报工序 (reportingRequired=false), 不生成报工任务。
  // null 视为 true (向后兼容)。
  .filter(t -> !Boolean.FALSE.equals(t.getReportingRequired()))
  ```

---

## 阶段 1: 配两点 (中间免报)

复用 F006 现有掌中宝工序链 (WP-F006-ZZB-01..05: 水解化冻→焯水→油炸→熟制伴汁→气调) 建两个 DEMO 产品:

| 产品 | id | 报工配置 |
|---|---|---|
| **DEMO-两点报工掌中宝** | `DEMO-690-TWOPOINT` | 首末 true, 中间 3 道 false |
| **DEMO-逐道报工掌中宝** (对照) | `DEMO-690-PERSTEP` | 5 道全 true |

### 配置 SQL 验证 (DB 断言)

**DEMO-690-TWOPOINT** (两点):
```
 process_order | process_name | reporting_required
---------------+--------------+--------------------
             1 | 水解化冻     | t   ← 领料投入点 (报)
             2 | 焯水         | f   ← 免报
             3 | 油炸         | f   ← 免报
             4 | 熟制伴汁     | f   ← 免报
             5 | 气调         | t   ← 成品产出点 (报)
```

**DEMO-690-PERSTEP** (逐道对照):
```
 process_order | process_name | reporting_required
---------------+--------------+--------------------
   1..5  水解化冻/焯水/油炸/熟制伴汁/气调  全部 t
```

---

## 阶段 2: spawn 批次, 验证只首末两道 spawn

建两个 DEMO 批次 (status IN_PROGRESS, is_trial=true), 调 live test API:
`POST /api/mobile/F006/production/batches/{batchId}/spawn-tasks`

| 批次 | id | 产品 |
|---|---|---|
| DEMO-690-2PT-B1 | 1917 | DEMO-690-TWOPOINT |
| DEMO-690-5PT-B1 | 1918 | DEMO-690-PERSTEP |

### API spawn 响应 + DB 断言

**批次 1917 (两点) → spawn 恰好 2 task**:
```
 process_order | process_name | status
---------------+--------------+---------
             1 | 水解化冻     | PENDING   ← 领料 (首道)
             5 | 气调         | PENDING   ← 产出 (末道)
(2 rows)
```

**批次 1918 (逐道对照) → spawn 全 5 task**:
```
 process_order | process_name | status
---------------+--------------+---------
             1 | 水解化冻     | PENDING
             2 | 焯水         | PENDING
             3 | 油炸         | PENDING
             4 | 熟制伴汁     | PENDING
             5 | 气调         | PENDING
(5 rows)
```

### task_count 对比 (决定性证据)

```
 production_batch_id |      batch      | task_count
---------------------+-----------------+------------
                1917 | DEMO-690-2PT-B1 |          2   ← 两点
                1918 | DEMO-690-5PT-B1 |          5   ← 逐道
```

### 中间免报道 task = 0 断言

```
 process_name | task_in_1917
--------------+--------------
 焯水         |            0
 油炸         |            0
 熟制伴汁     |            0
```

**焯水/油炸/熟制 三道免报工序在两点批次中完全无 task** —— 被 `!Boolean.FALSE.equals(reportingRequired)` 过滤跳过。
**操作员打开报工屏 (YieldStepReportScreen 拉 work-process-tasks) 只会看到 领料(化冻) + 产出(气调) 两个任务**, 中间油炸等不出现 = 六扇门要的"中间全部省掉了"。

同样的工序链 (5 道完全相同), 配置不同 → spawn 结果不同 (2 vs 5)。证明配置真生效, 非偶然。

---

## 阶段 3: 出成率两点算 + 无 NPE

对两点批次 1917 提交两笔报工 (经 live API `POST .../reports`):
- 首道 化冻 (task 105) INPUT 1000kg
- 末道 气调 (task 106) OUTPUT 540 盒

调 `GET /api/mobile/F006/production/batches/1917/yield`:

```json
{
  "batchId": 1917,
  "firstStepInput": 1000.00, "firstStepInputUnit": "kg",
  "lastStepOutput": 540.00,  "lastStepOutputUnit": "盒",
  "steps": [
    { "processOrder": 1, "processName": "水解化冻", "totalInput": 1000.00, "phase": "IN_PRODUCTION" },
    { "processOrder": 5, "processName": "气调",     "totalOutput": 540.00, "phase": "COMPLETED", "outputKind": "FINISHED" }
  ]
}
```

**出成率 steps 数组只含 2 步** (化冻 + 气调), 中间 3 道免报工序不出现, **无 NPE / 无除零 / 无幽灵步**。
`YieldCalculationServiceImpl.calculateSteps` 本就 report-driven (按已存在 ProductionReport 分组), 跳过的免报工序无报工 → 自然不参与计算 → 出成率天然按"首道IN(1000kg) → 末道OUT(540盒)"两点算, 无需改 calculateSteps/calculateBatchYield (向后兼容铁律成立)。

---

## 结论

✅ **#690 reportingRequired 能力真产出六扇门两点流程**:

1. **配置层**: per-工序 `reporting_required` 可配 (首末 true, 中间 false)。
2. **spawn 层**: 两点配置 spawn 恰好 2 task (领料+产出), 中间 3 道免报工序 0 task; 逐道配置同链 spawn 全 5。过滤 `!Boolean.FALSE.equals(reportingRequired)` 真生效。
3. **操作员视角**: 报工任务列表只见 领料(首道) + 产出(末道), 油炸等中间道不出现 = 转录"中间全部省掉了"。
4. **出成率/成本层**: 两点报工下 yield 干净两点算 (首道IN→末道OUT), 无 NPE。
5. **向后兼容**: 逐道对照产品 (全 true) 行为完全不变。

这正是 Steve 纠正后要的"报工过程"。

---

## 证据命令链 (可复跑)

```bash
# DB 断言 (47 服务器 localhost)
PGPASSWORD=cretas123 psql -h 127.0.0.1 -U cretas_user -d cretas_db \
  -c "SELECT t.process_order, wp.process_name FROM work_process_tasks t JOIN work_processes wp ON wp.id=t.work_process_id WHERE t.production_batch_id=1917 ORDER BY t.process_order;"
# → 只 2 行 (化冻, 气调)

# live API spawn (47 上 curl localhost:10011, f006_admin/123456)
curl -X POST http://localhost:10011/api/mobile/F006/production/batches/1917/spawn-tasks \
  -H "Authorization: Bearer $TOKEN" -d '{"productTypeId":"DEMO-690-TWOPOINT"}'
# → data 数组 2 条

# 出成率两点算
curl http://localhost:10011/api/mobile/F006/production/batches/1917/yield -H "Authorization: Bearer $TOKEN"
# → steps 2 条, firstStepInput 1000 / lastStepOutput 540, 无报错
```

## DEMO 数据清单 (test, DEMO- 前缀)

| 类型 | 标识 |
|---|---|
| product_types | DEMO-690-TWOPOINT, DEMO-690-PERSTEP |
| product_work_processes | 上述 2 产品各 5 行 (10 行) |
| production_batches | DEMO-690-2PT-B1 (id 1917), DEMO-690-5PT-B1 (id 1918) |
| work_process_tasks | batch 1917: id 105,106 (2 条); batch 1918: id 107-111 (5 条) |
| production_reports | batch 1917: id 575 (化冻 INPUT), 576 (气调 OUTPUT) |

均在 test cretas_db, 不影响 prod。
