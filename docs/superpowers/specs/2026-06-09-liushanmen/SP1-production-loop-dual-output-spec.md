# SP1 · 生产闭环 — 同单双产出 + 半成品库回挂 (Spec)

> **子项编号**: SP1 · **执行波次**: 波1(地基, 无依赖可立即开始) · **Flyway 号段**: V20261010_01–09
> **生成**: 2026-06-09 Sonnet in-harness · **红线归属**: Opus 终审
> **脊梁**: `00-master-blueprint.md` §2.1 · §3 · §4

---

## 1. 目标

让一张生产单(批次)能够同时产出两类产物:

| 产出类型 | 去向 | 已有基础 |
|---|---|---|
| **FINISHED** (成品) | `FinishedGoodsBatch` (既有 `completeProduction`) | ✅ 已 ship |
| **SEMI** (半成品) | `SemiFinishedInventory` (既有 WIP 库) + 🆕 `SemiFinishedInventoryTransaction` 流水账 | 🟡 WIP 库已有, 流水账缺 |

同时新建 **`SemiFinishedInventoryTransaction`** 流水账实体, 支持:
- 移动均价精确计算 (IN)
- 撤回回退 (REVERSE)
- 盘点调整 (ADJUST)
- 二次加工 OUT lineage (SP2 复用)

---

## 2. 范围

### 本 SP 做

- 新实体 `SemiFinishedInventoryTransaction` (流水账)
- `ProductionReport` 新增 `output_kind` 字段 (枚举 `FINISHED` / `SEMI` / `BOTH`)
- `YieldReportServiceImpl.submitReport` OUTPUT 阶段: 根据 `outputKind` 路由产出到 FG 或 SemiFinishedInventory + 同步写 Txn 流水账
- `SemiFinishedInventory.unitCost` 在每次 IN 时用移动均价公式更新 (SELECT FOR UPDATE 并发锁)
- RN `YieldStepReportScreen` OUTPUT 阶段新增产出类型选择 (FINISHED / SEMI / BOTH)
- web-admin 批次详情页展示双产物 (成品数 + 半成品数量及 unitCost)
- backend 端点: `GET /api/mobile/{factoryId}/processing/batches/{batchId}/output-options` 返回可选产出类型 (工序是否末道, 是否有 SemiFinishedInventory SKU code 配置)
- Flyway V20261010_01 - V20261010_03

### 本 SP 不做 (defer 到 SP2/SP3)

- 整单撤回审批闭环 (SP2 红线)
- 移动均价回退算法 (SP2 复用本 SP Txn 流水账)
- 三价成本对比报警 (SP3)
- 二次加工跨单独立单 (SP2)

---

## 3. 现状复用 (grep 验证)

| 复用模块 | 文件路径 | 复用方式 |
|---|---|---|
| `SemiFinishedInventory` | `entity/SemiFinishedInventory.java` | 已有 produced/consumed/available/unitCost/accumulatedCost/materialBatchRefs + 乐观锁 `version`; 本 SP 不改结构, 仅通过 `WipInventoryService` 写入 |
| `WipInventoryService.postApprovedOutput` | `service/wip/impl/WipInventoryServiceImpl.java` | 已处理 OUTPUT 报工→WIP upsert(幂等键 intermediateBatchNo)+ 消耗源 WIP + 移动均价 unitCost 滚动; **本 SP 在其 IN 路径后追加写 Txn 流水账** |
| `YieldReportServiceImpl.submitReport` | `service/yield/impl/YieldReportServiceImpl.java` | 三阶段报工 INPUT/SEGMENT/OUTPUT 已 ship; OUTPUT 阶段调 `postApprovedOutput`; 本 SP 在 OUTPUT 阶段扩 `outputKind` 路由 |
| `ProductionReport` | `entity/ProductionReport.java` | 已有 `reportKind`/`reversalOfId`/`intermediBatchNo`/`sourceWipNo` 等; 新增 `output_kind` 列 |
| `completeProduction` | `service/impl/ProcessingServiceImpl.java` | 末道 OUTPUT 若 outputKind=FINISHED/BOTH → 仍调此方法建 FG; SEMI-only 不调 |
| `CostRollupUtil` | `service/shared/CostRollupUtil.java` | scale-6 数量 / scale-4 成本 / ROUND_HALF_UP; 本 SP 移动均价 IN 成本计算遵循 |
| `@PriceSensitive` | annotation | unitCost 脱敏遵循既有 |
| RBAC `@RequireRole` + 请求属性 `role` | `utils/ReportAuthGuard` | OUTPUT 阶段产出类型选择权限: 操作员 + 主管均可; 成本可见性依 `canViewPrice` |

---

## 4. 数据模型增量

### 4.1 新实体 `SemiFinishedInventoryTransaction` (🆕)

```java
// 文件: entity/SemiFinishedInventoryTransaction.java
// 表名: semi_finished_inventory_transactions
// 说明: 半成品流水账。支撑移动均价回放、撤回回退、盘点调整。

@Entity
@Table(name = "semi_finished_inventory_transactions")
public class SemiFinishedInventoryTransaction extends BaseEntity {
    Long id (PK, identity)
    String factoryId (NOT NULL, len=50)
    Long semiFinishedId (FK → semi_finished_inventory.id, NOT NULL)
    String txnType (NOT NULL, len=20)
        // 枚举: IN(产出入库) / OUT(下道领用/二次加工) / REVERSE(撤回冲销) / ADJUST(盘点调整)
    String sourceType (NOT NULL, len=40)
        // IN: PRODUCTION_OUTPUT / SECONDARY_INPUT
        // OUT: SECONDARY_CONSUME / TRANSFER_OUT
        // REVERSE: REVERSAL
        // ADJUST: STOCKTAKE
    String sourceRef (len=100)
        // IN: intermediateBatchNo 或 productionBatch.batchNumber
        // OUT: 下道 intermediateBatchNo 或 transferId
        // REVERSE: ReportReversalLog.id (SP2)
        // ADJUST: StocktakeTask.id (SP7)
    BigDecimal quantity (precision=12, scale=6, NOT NULL)
        // IN: 正数; OUT/REVERSE: 负数; ADJUST: 可正可负
    BigDecimal unitCostAtTxn (precision=14, scale=4)
        // IN 时填当次单位成本(本道产出成本 / 本道产出量); OUT/REVERSE/ADJUST 时填 IN 时均价(快照)
    BigDecimal balanceAfter (precision=12, scale=6)
        // 写入后的余额快照 (available_quantity)
    BigDecimal balanceCostAfter (precision=14, scale=4)
        // 写入后的 unitCost 快照 (移动均价)
    Long reportId (FK → production_reports.id, nullable)
        // 产生此流水的报工 ID
    Long operatorId (nullable)
    LocalDateTime createdAt
    // 注意: 无 updated_at / deleted_at (流水账不可修改, 只能 REVERSE 冲销)
}
```

**索引**: `idx_sfit_semi_id` (semi_finished_id), `idx_sfit_factory_source` (factory_id, source_ref), `idx_sfit_factory_type` (factory_id, txn_type)

### 4.2 `ProductionReport` 新增字段

```sql
-- V20261010_01
ALTER TABLE production_reports
  ADD COLUMN output_kind VARCHAR(10) DEFAULT NULL;
  -- 枚举: FINISHED(仅成品) / SEMI(仅半成品) / BOTH(成品+半成品)
  -- NULL = 旧式报工(向后兼容, 行为等同 FINISHED)
```

Java entity 新增:
```java
@Column(name = "output_kind", length = 10)
private String outputKind;  // FINISHED / SEMI / BOTH; null = 旧式兼容
```

### 4.3 `ProductionReport` 新增半成品产出量字段

```sql
-- V20261010_01 (同迁移)
ALTER TABLE production_reports
  ADD COLUMN semi_output_quantity NUMERIC(12,2) DEFAULT NULL,
  ADD COLUMN semi_output_unit VARCHAR(16) DEFAULT NULL,
  ADD COLUMN semi_code VARCHAR(50) DEFAULT NULL;
  -- semi_code: 半成品 SKU code (如 "ZSH-001", 与 SemiFinishedInventory 的 productTypeId/intermediateBatchNo 关联)
  -- outputQuantity 继续用于 FINISHED 成品数量
```

### 4.4 新建 `SemiFinishedInventoryTransaction` 表

```sql
-- V20261010_02
CREATE TABLE semi_finished_inventory_transactions (
    id                 BIGSERIAL PRIMARY KEY,
    factory_id         VARCHAR(50)     NOT NULL,
    semi_finished_id   BIGINT          NOT NULL REFERENCES semi_finished_inventory(id),
    txn_type           VARCHAR(20)     NOT NULL,  -- IN/OUT/REVERSE/ADJUST
    source_type        VARCHAR(40)     NOT NULL,
    source_ref         VARCHAR(100),
    quantity           NUMERIC(12,6)   NOT NULL,
    unit_cost_at_txn   NUMERIC(14,4),
    balance_after      NUMERIC(12,6),
    balance_cost_after NUMERIC(14,4),
    report_id          BIGINT REFERENCES production_reports(id),
    operator_id        BIGINT,
    created_at         TIMESTAMP DEFAULT NOW() NOT NULL
);

CREATE INDEX idx_sfit_semi_id          ON semi_finished_inventory_transactions(semi_finished_id);
CREATE INDEX idx_sfit_factory_source   ON semi_finished_inventory_transactions(factory_id, source_ref);
CREATE INDEX idx_sfit_factory_txn_type ON semi_finished_inventory_transactions(factory_id, txn_type);
CREATE INDEX idx_sfit_report_id        ON semi_finished_inventory_transactions(report_id);
```

### 4.5 移动均价公式 (SP1 实现)

```
新 IN: inQty, inCost (= 本道总成本 / 本道产出量; 诚实 null 传播)
oldQty = SemiFinishedInventory.availableQuantity (FOR UPDATE 悲观锁)
oldCost = SemiFinishedInventory.unitCost

新 unitCost:
  if (inCost == null) → unitCost 不变 (null 不更新)
  else if (oldQty == 0 || oldCost == null) → unitCost = inCost
  else → unitCost = (oldQty × oldCost + inQty × inCost) / (oldQty + inQty)
            precision: BigDecimal scale-4, ROUND_HALF_UP

同步写 Txn:
  txnType = IN, sourceType = PRODUCTION_OUTPUT, quantity = +inQty,
  unitCostAtTxn = inCost, balanceAfter = oldQty + inQty, balanceCostAfter = 新 unitCost
```

---

## 5. 组件与数据流

```
RN YieldStepReportScreen (OUTPUT 阶段)
  └─ 产出类型选择 (FINISHED / SEMI / BOTH)
       └─ SEMI / BOTH: 追加输入半成品数量 + semiCode 选择
  └─ POST /api/mobile/{factoryId}/processing/batches/{batchId}/reports
       { reportKind:"OUTPUT", outputKind:"SEMI"|"FINISHED"|"BOTH",
         outputQuantity:N, semiOutputQuantity:M, semiCode:"ZSH-001", ... }

YieldReportServiceImpl.submitReport (OUTPUT 阶段扩展)
  ├─ 解析 outputKind (null→FINISHED 向后兼容)
  ├─ if FINISHED or BOTH → 保持原 completeProduction 路径
  │     (末道: 建 FinishedGoodsBatch; 非末道: 仅记录)
  ├─ if SEMI or BOTH → 调 WipInventoryService.postApprovedOutput (已有)
  │     WipInventoryService (扩展):
  │       ① SELECT FOR UPDATE semi_finished_inventory WHERE intermediateBatchNo = ?
  │       ② 计算新 unitCost (移动均价公式, §4.5)
  │       ③ UPDATE semi_finished_inventory (produced/available/accumulatedCost/unitCost)
  │       ④ INSERT semi_finished_inventory_transactions (IN 行)
  └─ 全部在同一 @Transactional 内, null 安全不抛 (§红线)

web-admin 批次详情 (views/production/batches/detail.vue)
  └─ 展示: 成品产出 (FG 数量/单位) + 半成品产出 (SKU code/数量/单位/unitCost @PriceSensitive)
  └─ 半成品流水账入口: 点击查看 SemiFinishedInventoryTransaction 列表

GET /api/mobile/{factoryId}/processing/batches/{batchId}/output-options
  └─ 返回: isLastStep (是否末道), availableSemiCodes (工序已配的半成品 code 列表)
```

---

## 6. 端归属

| 功能 | 端 | 说明 |
|---|---|---|
| `SemiFinishedInventoryTransaction` 实体 + Repository + Service 方法 | **backend** | 纯后端 |
| `ProductionReport.outputKind / semiOutputQuantity / semiCode` 新字段 | **backend** | Entity + DTO + Flyway |
| `YieldReportServiceImpl` OUTPUT 路由扩展 | **backend** | 核心逻辑 |
| `WipInventoryService.postApprovedOutput` 追加 Txn 写入 | **backend** | 复用扩展 |
| 移动均价 IN 算法 + FOR UPDATE | **backend** | 并发安全 |
| `output-options` 端点 | **backend** | 辅助查询 |
| RN OUTPUT 阶段产出类型选择 | **RN-app** | 低输入防呆 (§8 UX Flow) |
| web 批次详情双产物展示 | **web-admin** | 展示层 |

---

## 7. 错误处理 (fool-proof 4位一体)

| 场景 | 处理 |
|---|---|
| OUTPUT 阶段 `outputKind=SEMI` 但 `semiOutputQuantity` 为 null | 400 "半成品产出量必填" + hintTarget="semiOutputQuantity" |
| OUTPUT 阶段 `outputKind=SEMI/BOTH` 但 `semiCode` 为空 | 400 "请选择半成品品类 code" + hintTarget="semiCode" |
| semiCode 在该工厂不存在对应 SemiFinishedInventory row | 自动 upsert (intermediateBatchNo 幂等键已处理) |
| 并发 IN 导致 optimistic lock 冲突 | 409 "半成品库存并发冲突, 请稍后重试" (乐观锁 `@Version`) |
| unitCost IN 成本为 null | 诚实保留 null, 不传播 0; error toast sticky `duration:0 showClose:true` + message "该道缺少成本信息, unitCost 留空" |
| 重复提交同一 intermediateBatchNo OUTPUT | WIP upsert 幂等 (existing) + Txn 去重(检查 source_ref = intermediateBatchNo 已有 IN 行) |

---

## 8. UX Flow Analysis (操作员低输入屏)

> 触发: 角色词=operator/操作员/小组长; 路径=screens/processing; 功能=报工/完工产出

### 受影响屏幕

**`YieldStepReportScreen.tsx` — OUTPUT 阶段产出类型选择** (新功能)

**防呆设计 (Rule 1-5)**:

| Rule | 落地方式 |
|---|---|
| Rule 1 (预先显示边界) | OUTPUT 阶段打开时即调 `output-options` 端点, 预加载可选的半成品 code 列表 + 是否末道; 不选完不展示 submit |
| Rule 2 (上下文) | 屏幕顶部显示: "{品名} · 第N道 · 批次{batchNumber}"; 产出类型描述: "成品入库" / "半成品挂库 ({semiCode})" |
| Rule 3 (约束选择) | 产出类型: Radio/Segment 三选一 `[仅成品] [仅半成品] [成品+半成品]`; semiCode 用 Picker (下拉选已配品类, 不手填) |
| Rule 4 (幂等) | 重复点提交: 后端 WIP upsert 已幂等; 前端提交中 disable Submit 按钮 |
| Rule 5 (dead-end 跳转) | 若 availableSemiCodes 为空 ("本工序未配半成品品类") → 跳转到工序配置页提示 "请在工序配置中添加半成品产出品类" |

**最少输入原则**: 操作员仅需:
1. 选产出类型 (3选1)
2. 若含 SEMI: 输入半成品数量 (数字键盘, 带 max 提示)
3. semiCode 从 Picker 选 (不手填)

---

## 9. 测试策略

### Backend (TDD 先红后绿)

| 测试类 | 测试内容 |
|---|---|
| `SemiFinishedInventoryTransactionRepositoryTest` | CRUD + 按 semi_finished_id 查询 |
| `WipInventoryServiceImplTest` (扩展) | postApprovedOutput 后 Txn 有 IN 行; 移动均价计算 3 case: 首次IN/叠加IN/null成本不更新 |
| `YieldReportServiceImplTest` (扩展) | outputKind=FINISHED 不写 Txn; outputKind=SEMI 写 Txn; outputKind=BOTH 写 FG + Txn; null outputKind 行为等同 FINISHED |
| `YieldReportServiceImplTest` 并发 | 两个 OUTPUT 并发→只有一笔余额超领触发 409 |

### Frontend

- `vue-tsc --noEmit` (web-admin 批次详情扩展)
- RN `tsc --noEmit`
- Headed E2E (optional, 下一 sprint): zh-CN 1920×1080; 操作员选"仅半成品"→成功产出; 检查批次详情页半成品行

---

## 10. 依赖

- **无上游 SP 依赖** (波1地基, 可立即开始)
- `SemiFinishedInventory` 已存在, 不需迁移 (仅追加 Txn 表)
- 复用 `WipInventoryService` 接口 (不改 interface signature, 只扩 impl)

---

## 11. 🔒 红线设计章 (照蓝图 §3 逐字落地)

> 执行者只到 PR + 自测。Opus 终审 `git diff origin/main...HEAD --stat` → merge main → 从 main 部署。

### 红线 R1: 移动均价 IN — 并发安全

- `SemiFinishedInventory` 同 code 并发 IN/OUT → 悲观行锁 `SELECT ... FOR UPDATE` (短事务, <100ms)
- 精度: 数量 scale-6, 成本 scale-4, ROUND_HALF_UP (对齐 CostRollupUtil)
- 诚实空: inCost=null → unitCost 不变, Txn.unitCostAtTxn=null; 不默认 0

### 红线 R2: 事务完整性

- `WipInventoryService.postApprovedOutput` 内: SemiFinishedInventory UPDATE + Txn INSERT 全在同一 `@Transactional` 内
- 禁止 fail-soft try/catch 吞内层异常 (教训 `feedback_failsoft_catch_cannot_save_doomed_tx`)
- 若 FG 建立和 Txn 写入需跨不同事务上下文 → 用 `REQUIRES_NEW` 真隔离

### 红线 R3: Txn 流水账不可删改

- `SemiFinishedInventoryTransaction` 无 `updated_at` / `deleted_at` — 设计即不可修改
- 冲销走 REVERSE 类型新行 (不修改原 IN 行)
- 幂等守卫: 同一 `source_ref` (intermediateBatchNo) + txnType=IN 已存在 → 409 "已记录产出, 勿重复提交" + 返回 existingTxnId

### 红线 R4: outputKind 向后兼容

- `ProductionReport.outputKind = null` → 行为完全等同 FINISHED (不破坏已有 F006 批次)
- `YieldReportService.submitReport` 在 `outputKind` 解析前加 null-guard: `if (outputKind == null) outputKind = "FINISHED";`

---

## ⚠️ 跨子项依赖 / 风险

1. **SP2 直接依赖本 SP 的 `SemiFinishedInventoryTransaction`**: SP2 撤回回退逻辑需重放本 SP 生成的 Txn 流水账重算均价。SP2 必须串行于 SP1 之后 (波2)。`YieldReportServiceImpl` SP2 会继续扩展, 两 SP 不可并发改同一文件。

2. **SP3 依赖本 SP 的 Txn unitCost 快照**: 三价成本引擎 (SP3) 用 `SemiFinishedInventoryTransaction.unitCostAtTxn` 作为半成品成本来源之一。SP3 需在 SP1 Txn 表建好后才能实现成本卷积。

3. **`WipInventoryService.postApprovedOutput` 扩展冲突**: SP2 也需要扩展此方法 (源 WIP 消耗的 OUT Txn 写入), 确保 SP1 PR merge 后 SP2 才 fork worktree。

4. **Flyway 号段冲突风险**: 蓝图预分配 `V20260910_0x` 但该号段已被 V20260910_01-03 占用 (既有 prod migrations)。本 SP 改用 **V20261010_0x** 号段 (merge 前必查重: `git ls-tree origin/main db/flyway | grep V20261010 | uniq -d`)。

5. **`ProductionReport.semiCode` 与 `SemiFinishedInventory` 主数据对接**: 半成品 code 的主数据 (类似 SKU code) 目前由 `SemiFinishedInventory.productTypeId` 承担, 但六扇门客户需要"焯水猪蹄/熟制猪蹄各一 code"的概念。建议在 `WorkProcess.semiFinishedOutputCode` 新增可选字段 (V20261010_03), 报工时从工序配置带入, 减少操作员手选。

6. **RN MAX_EVIDENCE_VIDEO_BYTES 与 nginx 上传上限**: 当前 RN 上限 50MB, 六扇门 nginx 已改 110m (per 报工 saga)。本 SP 不改视频上限, 继承既有配置。

