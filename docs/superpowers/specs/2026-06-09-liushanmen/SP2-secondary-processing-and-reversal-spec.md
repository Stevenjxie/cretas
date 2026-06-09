# SP2 · 二次加工(同单 sourceWip + 跨单独立单) + 整单撤回 — 设计 Spec

> **生成**: 2026-06-09 Sonnet 4.6 in-harness  
> **脊梁**: 00-master-blueprint.md § 2.2 / § 3.1 / § 3.2 (不可偏离)  
> **依赖**: SP1(SemiFinishedInventoryTransaction 流水账地基, WIP 产出写库)  
> **号段**: Flyway V20260910_1x

---

## 1. 目标

### 做什么
1. **跨单独立二次加工生产单** — 原料来源 `SEMI_FINISHED`，领用 `SemiFinishedInventory`（走 OUT 流水账），独立于原生产批次新建生产计划 + 批次，全链路成本继承 unitCost。
2. **整单撤回** — 蓝图 § 3.1 三层前置守卫 + 单事务 null-safe 回退（原料回补 + WIP IN 作废 + FG 软删 + 移动均价重放）+ 幂等 `ReportReversalLog`。
3. **撤回审批闭环** — 有报工数据→提交申请→按角色审批；无数据→直撤；过 W0 WriteGuard 确认门。

### 不做什么（本子项不含）
- 三价成本对比报警（SP3）
- 多 SO 合并供单（M-C10，P1）
- 出成率自学习（M-C4，P1）
- BOM 预领量汇总单（M-C7，P1）
- 补录时效硬约束（M-C13，P1）
- RN 二次加工报工 UI 新屏（复用现有 `YieldStepReportScreen` + 扩参数，不新建屏）

---

## 2. 范围

| 层 | 新建 / 修改 |
|---|---|
| **backend** | 新 `ReportReversalLog` 实体 + Repo；新 `ReportReversalService`；扩 `ProductionPlanService.createSecondaryPlan`；扩 `WipInventoryService.deductForSecondaryPlan`；扩 `ProductionPlanController` / `ProcessingController`（撤回端点）；Flyway V20260910_11~V20260910_14 |
| **web-admin** | 新撤回申请 dialog（`plans/ReversalRequestDialog.vue`）；生产计划列表新"二次加工"来源标签；撤回审批列表页（`approval/reversal-list.vue`）|
| **RN** | `YieldStepReportScreen`、`YieldBatchSelectScreen` — 扩 `sourceType=SEMI_FINISHED` 选源半成品入口；新 `ReversalSubmitScreen`（提交整单撤回申请，操作员低输入） |

---

## 3. 现状复用（grep 验证）

| 现有代码 | 复用方式 |
|---|---|
| `SemiFinishedInventory` + `WipInventoryServiceImpl` | 跨单二次加工 OUT 流水账（SP1 建 SemiFinishedInventoryTransaction 后扩） |
| `ProductionReport.sourceWipNo` + `WipInventoryService.validateSourceWip` | 同单 sourceWip 续工序（已 ship，SP2 不改此路径） |
| `ProcessingServiceImpl.cancelProduction` | 整单撤回的**前置引用**；实际撤回逻辑新建 `ReportReversalService`，不改现有 cancel |
| `ProductionReport.reversalOfId` | 撤回记录标记（字段已在位，未启用） |
| `ProductionBatch` / `ProductionPlan` | 跨单独立计划头引用；新增 `planSourceType=SECONDARY` 枚举值 |
| `FinishedGoodsBatch.status` | 整单撤回时置 REVERSED（枚举值新增） |
| `W0 WriteGuardService` | 撤回提交过 WriteGuard 确认门（已 ship，无需改） |
| `@RequireRole` + 请求属性 role | 撤回权限按角色（教训 C1 孪生坑：SecurityContext 永空，用 RequestContextHolder） |

---

## 4. 数据模型增量

### 4.1 新实体：`ReportReversalLog`

```java
@Entity @Table(name = "report_reversal_logs")
public class ReportReversalLog extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 被撤回的生产批次 id */
    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 被撤回的生产计划 id */
    @Column(name = "plan_id", length = 191)
    private String planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reversal_scope", nullable = false, length = 20)
    private ReversalScope reversalScope; // WHOLE_ORDER

    /** 提交人 (操作员或厂长) */
    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;

    /** 审批人 (厂长/财务角色，无数据直撤时 = submittedBy) */
    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ReversalStatus status = ReversalStatus.PENDING;
    // PENDING → APPROVED → DONE | REJECTED

    /** 已回退的流水账 id 列表 (JSON 数组) — 幂等防重复回退 */
    @Type(JsonType.class)
    @Column(name = "reverted_txn_ids", columnDefinition = "jsonb")
    private List<Long> revertedTxnIds;

    /** null 安全: 无数据直撤时为 null */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    // created_at/updated_at/deleted_at 来自 BaseEntity
}
```

枚举：
- `ReversalScope { WHOLE_ORDER }`
- `ReversalStatus { PENDING, APPROVED, DONE, REJECTED }`

### 4.2 扩展：`ProductionPlan` 新字段

```sql
-- V20260910_11
ALTER TABLE production_plans
  ADD COLUMN plan_source_type VARCHAR(30) DEFAULT 'NORMAL',
  -- NORMAL | SECONDARY (二次加工独立单, source=SemiFinishedInventory)
  ADD COLUMN secondary_source_wip_id BIGINT REFERENCES semi_finished_inventory(id);
  -- 跨单二次加工: 领用哪条 SemiFinishedInventory 记录
```

### 4.3 新表：`report_reversal_logs`

```sql
-- V20260910_12
CREATE TABLE report_reversal_logs (
    id              BIGSERIAL PRIMARY KEY,
    factory_id      VARCHAR(50)  NOT NULL,
    batch_id        BIGINT       NOT NULL,
    plan_id         VARCHAR(191),
    reversal_scope  VARCHAR(20)  NOT NULL DEFAULT 'WHOLE_ORDER',
    submitted_by    BIGINT       NOT NULL,
    approved_by     BIGINT,
    reason          VARCHAR(500),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reverted_txn_ids JSONB,
    approved_at     TIMESTAMP,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    CONSTRAINT uq_reversal_batch UNIQUE (batch_id, reversal_scope)
    -- 同一批次同一 scope 幂等：已存在 DONE → 返回 already-reverted
);
CREATE INDEX idx_rrl_factory_batch ON report_reversal_logs(factory_id, batch_id);
CREATE INDEX idx_rrl_status ON report_reversal_logs(factory_id, status);
```

### 4.4 扩展：`SemiFinishedInventoryTransaction.txn_type` 新增 `SECONDARY_CONSUME`

（SP1 建此表；SP2 在迁移 V20260910_13 追加枚举值并加注释。若 SP1 未 merge，SP2 需在同文件追加，合并前查重。）

```sql
-- V20260910_13
COMMENT ON COLUMN semi_finished_inventory_transactions.txn_type IS
  'IN=生产产出入库 | OUT=同单续工序领用 | SECONDARY_CONSUME=跨单二次加工领用 | REVERSE=撤回 | ADJUST=盘点调整';
```

### 4.5 扩展：`finished_goods_batches.status` 新增 `REVERSED`

```sql
-- V20260910_14
ALTER TABLE finished_goods_batches
  ADD COLUMN reversal_log_id BIGINT REFERENCES report_reversal_logs(id);
-- REVERSED 枚举值在 Java 层加，DB 列为 VARCHAR 无枚举约束
```

---

## 5. 组件与数据流

### 5.1 跨单独立二次加工 — 创建路径

```
web-admin 生产计划创建 (plan_source_type=SECONDARY)
  → ProductionPlanController.createPlan (新增 secondary 参数)
  → ProductionPlanService.createSecondaryPlan(factoryId, req)
      ├─ 查 SemiFinishedInventory(id=req.secondarySourceWipId) 锁行 FOR UPDATE
      ├─ 校验 available >= req.plannedQuantity (fool-proof Rule 1: 预显可用量)
      ├─ 写 ProductionPlan (planSourceType=SECONDARY, secondarySourceWipId=xxx)
      └─ 不扣减 available（开工时扣）
  → 开工 (startProduction)
      → WipInventoryService.deductForSecondaryPlan(wipId, qty)
          ├─ SELECT ... FOR UPDATE（悲观锁，蓝图 § 3.2）
          ├─ 写 SemiFinishedInventoryTransaction (txn_type=SECONDARY_CONSUME)
          └─ 更新 available / status
```

### 5.2 RN 同单 sourceWip 续工序（现有路径，SP2 不改）

```
YieldBatchSelectScreen → 选 sourceWipNo → YieldStepReportScreen
  → submitReport → WipInventoryService.validateSourceWip (已有)
  → WipInventoryService.consumeSourceWip (已有)
```

扩展：`YieldBatchSelectScreen` 增加"领半成品"标签页（source=SEMI_FINISHED），从 `/api/mobile/{factoryId}/wip/available` 取可用半成品列表（新端点）。

### 5.3 整单撤回

```
触发: web-admin 生产批次详情 → "申请撤回" 按钮
      RN OperatorAssignedProcessScreen → "提交撤回" (操作员)

  → POST /api/mobile/{factoryId}/batches/{batchId}/reversal
  → ReportReversalController.submitReversal(factoryId, batchId, req)

Step 1 · 三层前置守卫 (fail-closed → 409 + actionHint)
  ① SELECT COUNT FROM semi_finished_inventory_transactions
      WHERE source_ref=batchId AND txn_type IN ('OUT','SECONDARY_CONSUME')
    > 0 → 409 "批次 {X} 产出的半成品已被下游单 {Y} 领用，请先撤销下游"
         + actionHint: 跳转到下游批次 (fool-proof Rule 5)
  ② SELECT FROM shipments WHERE production_batch_id=batchId AND status != 'CANCELLED'
    > 0 → 409 "成品已出库/发货 ({Z})，不可撤回"
  ③ 检查有无报工数据 (COUNT production_reports WHERE batch_id AND deleted_at IS NULL)
    = 0 → hasData=false（直撤，跳审批）
    > 0 → hasData=true（需审批）

Step 2 · 有数据 → 创建 ReportReversalLog (status=PENDING)
        无数据 → 跳到 Step 3

Step 3 · 审批通过（或无数据直撤）→ ReportReversalService.executeReversal(logId)
  单事务 @Transactional（全部在一个事务内，null 安全不抛）:
  a. 幂等检查: log.status == DONE → 返回 already-reverted
  b. 原料回补: ProductionReport 里的 materialBatchRefs
     → MaterialBatch.usedQuantity -= report.inputQuantity (null-safe: 已 null 不减)
     → 每条写 SemiFinishedInventoryTransaction (REVERSE)
  c. 作废本单产出的 SemiFinishedInventory:
     → 写 SemiFinishedInventoryTransaction (REVERSE, txn_type=REVERSE)
     → SemiFinishedInventory.status = REVERSED
       available = 0, accumulatedCost = 0
  d. FG 软删: FinishedGoodsBatch.status = REVERSED, reversalLogId = logId
  e. 移动均价回退（该 wip.code 按蓝图 § 3.1 算法）:
     → 查该 code 全部 SemiFinishedInventoryTransaction 排除被撤 IN，按时序重放
     → 重算 balance + unit_cost 后写回 SemiFinishedInventory.unitCost
     → 若无其余 IN，unit_cost → null（诚实空，对齐 § 3.2 hasNullPrice 规则）
  f. ProductionBatch.status = CANCELLED; ProductionBatch.notes 追加撤回原因
  g. ReportReversalLog.status = DONE, approvedAt = now, revertedTxnIds = [...]
  h. ProductionReport 均 soft-delete (deleted_at = now)

⛔ 事务铁律: 无 fail-soft try/catch 吞内层；若有真正独立副作用（通知）用 REQUIRES_NEW。
```

### 5.4 撤回审批 API

| 端点 | 方法 | 说明 |
|---|---|---|
| `POST /api/mobile/{factoryId}/batches/{batchId}/reversal` | 提交撤回申请 | 操作员/厂长均可，W0 WriteGuard 门 |
| `GET /api/mobile/{factoryId}/reversals?status=PENDING` | 待审批列表 | 厂长/财务角色 |
| `PUT /api/mobile/{factoryId}/reversals/{logId}/approve` | 审批通过 → 触发 executeReversal | 🔒 厂长/财务 |
| `PUT /api/mobile/{factoryId}/reversals/{logId}/reject` | 审批驳回 | 厂长/财务 |
| `GET /api/mobile/{factoryId}/wip/available` | 可用半成品列表 | 新端点，二次加工选源 |

---

## 6. 错误处理 — fool-proof 4 位一体

| 场景 | 错误码 | 前端行为 |
|---|---|---|
| 下游已领用 | `409 DOWNSTREAM_CONSUMED` | sticky toast + "查看下游批次" 按钮跳转 |
| 成品已出库 | `409 FG_SHIPPED` | sticky toast + "查看发货单" 按钮 |
| 二次加工可用量不足 | `409 WIP_INSUFFICIENT` | dialog 内实时显示 `max=availableQty` |
| 撤回已完成(幂等) | `200 already-reverted` | ElMessage "已撤回，无需重复操作" |
| 审批驳回 | `200 REJECTED` | 推送通知给申请人 |
| 单位不一致 | `409 WIP_UNIT_MISMATCH` | 现有机制（复用） |

所有 `error toast`: duration:0 + showClose (已在 request.ts 落地)，后端真实 message 直传前端。

---

## 7. UX Flow Analysis（低技术素养操作员屏幕）

> 按 `ux-flow` skill Phase 1 规范逐屏分析。

### 7.1 触发屏幕
- `YieldBatchSelectScreen` 扩展（操作员选源半成品）
- 新增 `ReversalSubmitScreen`（操作员提交撤回）

### 7.2 UX Flow Analysis

**YieldBatchSelectScreen（扩展：领半成品标签页）**

| 维度 | 分析 |
|---|---|
| 角色 | 操作员（仓管/小组长，低文化素质）|
| 核心动作 | 选择"从哪条半成品领料开始二次加工" |
| 防呆 Rule 1 | 列表每行显示可用量 `{available}kg`，选择后 dialog 预显"最多可用 {available}kg" + input `:max` |
| 防呆 Rule 2 | 列表显示产品名 + 工序批次号 + 产出日期 + 可用量，不让用户猜 |
| 防呆 Rule 3 | 可用量=0 的行置灰 + 提示"已被领走" |
| 防呆 Rule 5 | 无可用半成品时显示"暂无可用半成品，请联系厂长确认生产状态" + "返回" button |
| 最少输入 | 只需选一行 + 确认，数量在报工屏填（复用现有 YieldStepReportScreen） |
| Dead-end | 无可用 WIP → 引导返回，不卡死 |

**ReversalSubmitScreen（新建，操作员提交撤回申请）**

| 维度 | 分析 |
|---|---|
| 角色 | 操作员 or 小组长（低输入） |
| 核心动作 | 提交"我要撤回这个批次"申请 |
| 防呆 Rule 2 | 屏幕顶部强制显示：产品名 + 批次号 + 计划量 + 已产出（不让操作员搞混批次） |
| 防呆 Rule 3 | 撤回原因用 dropdown（录入错误/产品变更/质量问题/其他） + 选"其他"才显 textarea |
| 防呆 Rule 4 | 同批次已有 PENDING/DONE reversal → 提示"已提交撤回申请 {X}，请等待审批" + 跳详情 |
| 防呆 Rule 1 | 若无报工数据：显示"无已提交报工数据，将直接撤回（无需审批）" 一键确认 |
| 防呆 Rule 5 | 成功后显示"撤回申请已提交，请等待 {角色} 审批" + "返回任务列表" button |
| 最少输入 | dropdown 选原因（默认"录入错误"）+ 确认按钮，最多填备注 |

**web-admin 撤回审批页（管理员低风险屏，非低素养场景，正常设计）**

- 列表显示：申请人 + 产品名 + 批次号 + 已产出量 + 申请原因 + 申请时间
- 审批 dialog：显示批次完整信息 + 报工明细摘要（几道工序，几笔报工）
- 审批通过 → 触发后端 executeReversal → sticky toast 结果

---

## 8. 测试策略

### 8.1 Backend 单元/集成测试（先红后绿 TDD）

| 测试类 | 覆盖 |
|---|---|
| `ReportReversalServiceTest` | 三层前置守卫各返 409；有数据→PENDING；无数据→DONE；幂等（二次调用返 already-reverted） |
| `ReportReversalServiceTest_MovingAvgRollback` | 移动均价重放：单次 IN→撤回后 unitCost=null；多次 IN/OUT 交错→重放算法正确 |
| `WipInventoryServiceTest_Secondary` | deductForSecondaryPlan：正常扣减；超量→409；并发（两线程同时扣超量，只一个成功） |
| `ProductionPlanServiceTest_Secondary` | createSecondaryPlan：planSourceType=SECONDARY 正确落库；available 未提前扣 |

### 8.2 Flyway 迁移测试

```bash
# 本地跑迁移后验证表结构
./mvnw test -pl backend/java/cretas-api \
  -Dtest=FlywayMigrationValidationTest -Dspring.profiles.active=test
```

### 8.3 Web E2E（headed，zh-CN）

```bash
PLAYWRIGHT_PORT=9222 PLAYWRIGHT_CHAT_ID=sp2 \
  npx playwright test tests/sp2-reversal.spec.ts --headed
```

覆盖：
1. 创建跨单二次加工计划 → 开工 → 报工 → 完工
2. 提交整单撤回申请（有报工数据路径）→ 审批通过 → 验证 WIP/FG 状态
3. 无数据直撤（批次无报工记录）
4. 撤回幂等验证（重复提交返回 already-reverted）

### 8.4 RN 手工验证

- 操作员账号登录 → 选批次 → 领半成品标签页（显示可用量）
- ReversalSubmitScreen：选原因 → 提交 → 返回任务列表

---

## 9. 依赖

| 依赖 | 说明 |
|---|---|
| **SP1（必须先 merge）** | `SemiFinishedInventoryTransaction` 实体 + Repo + IN/OUT 写入逻辑（SP2 的 REVERSE/SECONDARY_CONSUME 行依赖此表存在）；若 SP1 未 merge 则 SP2 需在同 worktree 包含 SP1 相关迁移（串行协调）|
| RBAC（现有） | @RequireRole 厂长/财务审批；操作员提交——用请求属性 role（非 SecurityContext）|
| W0 WriteGuardService（已 ship） | 撤回端点过 WriteGuard，无需改 |

---

## 10. 🔒 红线设计章（照蓝图 § 3.1 逐字落地）

> **执行者只到 PR + 自测，Opus 终审 diff + merge + 从 main 部署。**

### 10.1 前置守卫（fail-closed）

实现位置：`ReportReversalService.checkPreConditions(factoryId, batchId)`  
- ① 查 `semi_finished_inventory_transactions` 是否有 `source_ref=batchId AND txn_type IN ('OUT','SECONDARY_CONSUME')` → 409  
- ② 查 `shipments` 是否有非 CANCELLED 行引用本批次 → 409  
- ③ COUNT `production_reports` WHERE `batch_id=X AND deleted_at IS NULL` → 返回 hasData bool

**⛔ 守卫必须在撤回事务外执行**（pre-check 层），不得吞掉任何 409 异常。

### 10.2 回退事务（单事务 null-safe）

实现位置：`ReportReversalService.executeReversal(logId)` 加 `@Transactional`  
- **禁止** fail-soft `try/catch` 吞内层异常（教训 `feedback_failsoft_catch_cannot_save_doomed_tx`，已复发 3 次）  
- null-safe 路径：任一字段 null → skip 该步骤（不抛 NPE），log WARN，继续下一步
- `MaterialBatch.usedQuantity -= inputQty`：先检查 `usedQuantity != null`，若 null 跳过（不减）
- FG 软删：`FinishedGoodsBatch.deletedAt = now()`，`status = REVERSED`
- 移动均价重放：查该 wip 全量 Txn 排除被撤 IN，按时序 `sorted(Comparator.comparing(created_at))` 重算；若重放后无有效 IN → unitCost = null（诚实空）

### 10.3 移动均价重放算法（蓝图 § 3.1 + § 3.2）

```java
// 伪代码（Sonnet 实现时严格按此）
List<SemiFinishedInventoryTxn> txns = repo.findByWipIdAndDeletedAtIsNullOrderByCreatedAt(wipId);
txns = txns.stream()
    .filter(t -> !(t.getId().equals(cancelledInTxnId)))  // 排除被撤 IN
    .collect(toList());

BigDecimal balance = ZERO;
BigDecimal balanceCost = ZERO;
for (var t : txns) {
    if (IN or SECONDARY_CONSUME_IN) {
        // 移动均价公式
        BigDecimal newQty = balance.add(t.getQuantity());
        BigDecimal newCost = balanceCost.add(t.getQuantity().multiply(t.getUnitCost() != null ? t.getUnitCost() : ZERO));
        balance = newQty;
        balanceCost = newCost;
    } else if (OUT or SECONDARY_CONSUME) {
        balance = balance.subtract(t.getQuantity());
        // OUT 不改单价（移动均价特性）
    } else if (REVERSE) {
        // 回退 IN：排除（已在 filter）；回退 OUT：加回
    }
}
BigDecimal newUnitCost = balance.compareTo(ZERO) > 0
    ? balanceCost.divide(balance, 4, ROUND_HALF_UP)
    : null;  // 诚实空
wip.setUnitCost(newUnitCost);
wip.setAccumulatedCost(balanceCost);
wip.setAvailableQuantity(balance);
```

### 10.4 并发控制

- 二次加工 OUT 扣减：`SELECT ... FOR UPDATE`（悲观行锁，短事务）
- 撤回 executeReversal：同一 batchId + DONE 幂等检查先于一切

### 10.5 精度约定（蓝图 § 3.2）

- 数量 scale-6，成本 scale-4，`ROUND_HALF_UP`，对齐 `CostRollupUtil`

---

## ⚠️ 跨子项依赖 / 风险

1. **SP1 串行依赖（最高风险）**: `SemiFinishedInventoryTransaction` 实体由 SP1 建；SP2 的 REVERSE/SECONDARY_CONSUME 行写入依赖该表。SP2 worktree 必须等 SP1 PR merge 进 main 后再 off origin/main 开工，或 SP2 worktree 包含 SP1 内容（协调好不重编 Flyway 号）。
2. **YieldReportServiceImpl 串行锁（SP1→SP2→SP9 串行）**: 蓝图 § 4 明确该文件为 scope-lock 串行点。SP2 修改该文件时必须 SP1 已 merge。
3. **Flyway 号段查重**: SP2 使用 V20260910_11~V20260910_14；merge 前必须 `git ls-tree origin/main db/flyway | grep V20260910` 确认 SP1 没把 11~14 占走（SP1 使用 0x 段，理论不冲突，但仍需查）。
4. **FinishedGoodsBatch.status 枚举扩展（SP1/SP3 可能同改）**: SP1 的 completeProduction 可能已在 FG 实体加字段；SP2 的 REVERSED 枚举值需避免与 SP1 加的值冲突。协调：SP1 只加 FG 写入路径，SP2 只加 REVERSED 状态，不重叠。
5. **W0 WriteGuard 路径覆盖**: 撤回端点 `POST /batches/{id}/reversal` 必须在 W0 WriteGuardService 覆盖范围内（isWriteIntent 匹配 reversal 动词后缀）。核实 W0 的动词后缀列表是否已覆盖 `_reversal` 或 `reversal`，若未覆盖需 SP2 扩。
