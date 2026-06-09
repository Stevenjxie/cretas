# SP1 · 生产闭环 — 同单双产出 + 半成品库回挂 (实施计划)

> **对应 spec**: `SP1-production-loop-dual-output-spec.md`
> **波次**: 波1 (无依赖, 可立即开始)
> **Flyway 号段**: V20261010_01–09 (V20260910_0x 已被占用, 本 SP 改用 V20261010)
> **Merge-before-worktree**: 无前置 SP · **SP2/SP3 必须等本 SP merge main 后才开 worktree**
> **生成**: 2026-06-09 Sonnet in-harness

---

## 0. 开始前: Flyway 号段查重纪律

```bash
# 每次 fork worktree 前必跑, 确认 V20261010 号段干净:
git fetch origin
git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway \
  | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | grep "^V20261010" | uniq -d
# 输出为空 = 安全, 有输出 = 撞车, 重新选号
```

## 0b. Worktree 启动命令

```bash
git worktree add -b feat/sp1-dual-output ../cretas-sp1-dual-output origin/main
cd ../cretas-sp1-dual-output
```

---

## Task T1 — 新建 `SemiFinishedInventoryTransaction` 实体 + Flyway

**模型**: Sonnet in-harness (规则重后端 Java 实体)
**Effort**: high
**文件级改动**:
- 🆕 `entity/SemiFinishedInventoryTransaction.java`
- 🆕 `repository/SemiFinishedInventoryTransactionRepository.java`
- 🆕 `db/flyway/V20261010_02__semi_finished_inventory_transactions.sql`
- 修改 `entity/SemiFinishedInventory.java` — 无字段改动(本 SP 仅借用现有字段)

**先写测试**:
```java
// SemiFinishedInventoryTransactionRepositoryTest.java
// T1-test-1: save + findBySemiFinishedId 返回有序列表 (createdAt DESC)
// T1-test-2: 写 IN 行 + 查 balanceAfter 正确
// T1-test-3: 写 REVERSE 行 quantity 为负 — 不抛
```

**验收**: `mvn test -pl backend/java/cretas-api -Dtest=SemiFinishedInventoryTransactionRepositoryTest` 全绿

---

### 分发卡 T1 → Sonnet in-harness

```
目标: 新建 SemiFinishedInventoryTransaction 实体+Repository+Flyway 迁移
worktree: (已在 ../cretas-sp1-dual-output)
允许改:
  - backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ (新建文件)
  - backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ (新建文件)
  - backend/java/cretas-api/src/main/resources/db/flyway/ (仅新建 V20261010_02)
禁改: 任何已有 entity 文件 (不得动 SemiFinishedInventory.java)
验收: mvn test -Dtest=SemiFinishedInventoryTransactionRepositoryTest 全绿
并行: ✅ T1 与 T2a 可并行 (T2a 改 ProductionReport entity, T1 改新文件, 无冲突)
交接: PR diff 仅含 3 个新文件; git diff origin/main...HEAD --stat 确认
⛔ 收尾: 只做到实现+自测+PR, 不部署 prod
```

**entity 字段规格**:
```
id               BIGSERIAL PK
factory_id       VARCHAR(50) NOT NULL
semi_finished_id BIGINT NOT NULL FK → semi_finished_inventory(id)
txn_type         VARCHAR(20) NOT NULL  -- IN / OUT / REVERSE / ADJUST
source_type      VARCHAR(40) NOT NULL  -- PRODUCTION_OUTPUT / SECONDARY_CONSUME / REVERSAL / STOCKTAKE
source_ref       VARCHAR(100)          -- intermediateBatchNo / transferId / etc
quantity         NUMERIC(12,6) NOT NULL  -- IN 正; OUT/REVERSE 负
unit_cost_at_txn NUMERIC(14,4)         -- IN: 本次产出单位成本; OUT/REVERSE: 取均价快照
balance_after    NUMERIC(12,6)         -- 写入后余量快照
balance_cost_after NUMERIC(14,4)       -- 写入后均价快照
report_id        BIGINT FK → production_reports(id) nullable
operator_id      BIGINT nullable
created_at       TIMESTAMP NOT NULL DEFAULT NOW()
-- 无 updated_at / deleted_at (流水账不可修改)
```

---

## Task T2a — `ProductionReport` 新增 outputKind 字段 + Flyway

**模型**: Sonnet in-harness
**Effort**: high
**文件级改动**:
- `entity/ProductionReport.java` — 新增 3 个字段
- `dto/yield/SubmitYieldReportRequest.java` — 新增对应 DTO 字段 (DTO 4点往返)
- `dto/yield/YieldReportDTO.java` — convertToDTO 映射
- `db/flyway/V20261010_01__production_report_output_kind.sql`

**先写测试**:
```java
// ProductionReportDtoRoundtripTest.java
// T2a-test-1: outputKind=BOTH 提交 DTO → entity 持久化 → convertToDTO 返回 BOTH (4点往返)
// T2a-test-2: outputKind=null 旧式报工 → getOutputKind() 返回 null (向后兼容)
// T2a-test-3: semiOutputQuantity=null 但 outputKind=SEMI → 业务层抛 400
```

**Flyway SQL**:
```sql
-- V20261010_01__production_report_output_kind.sql
ALTER TABLE production_reports
  ADD COLUMN output_kind         VARCHAR(10)    DEFAULT NULL,
  ADD COLUMN semi_output_quantity NUMERIC(12,2) DEFAULT NULL,
  ADD COLUMN semi_output_unit    VARCHAR(16)    DEFAULT NULL,
  ADD COLUMN semi_code           VARCHAR(50)    DEFAULT NULL;
COMMENT ON COLUMN production_reports.output_kind IS 'FINISHED/SEMI/BOTH; NULL=旧式兼容';
COMMENT ON COLUMN production_reports.semi_code   IS '半成品 SKU code, 对应 SemiFinishedInventory';
```

**DTO 4点往返检查清单**:
- [ ] `ProductionReport` 新增 4 个字段 + `@Column` 注解
- [ ] `SubmitYieldReportRequest` 新增 `outputKind / semiOutputQuantity / semiOutputUnit / semiCode`
- [ ] `YieldReportServiceImpl.buildReport()` 在 create 时 `report.setOutputKind(req.getOutputKind())` 等
- [ ] `YieldReportDTO` convertToDTO 映射全 4 个字段
- [ ] Update null-guard: `if (req.getSemiOutputQuantity() != null) report.setSemiOutputQuantity(...)`

---

### 分发卡 T2a → Sonnet in-harness

```
目标: ProductionReport 新增 outputKind/semiOutputQuantity/semiOutputUnit/semiCode 4字段
  + Flyway V20261010_01 + DTO 4点往返
worktree: ../cretas-sp1-dual-output (同 T1 分支, 但与 T1 无文件冲突可并行)
允许改:
  - entity/ProductionReport.java
  - dto/yield/SubmitYieldReportRequest.java
  - dto/yield/YieldReportDTO.java (或对应 convertToDTO 方法)
  - service/yield/impl/YieldReportServiceImpl.java (仅 buildReport/convertToDTO 段)
  - db/flyway/V20261010_01__*.sql (新建)
禁改: WipInventoryServiceImpl.java (T3 专属)
验收: mvn test -Dtest=ProductionReportDtoRoundtripTest 全绿
      + 确认 DTO 4点往返 (entity→create set→update null-guard→convertToDTO)
并行: ✅ 与 T1 并行 (不改同一文件)
⛔ 收尾约束: PR only
```

---

## Task T2b — `WorkProcess` 新增 `semiFinishedOutputCode` 可选字段 + Flyway

**模型**: Sonnet in-harness
**Effort**: high (规则重, DTO 4点往返)
**文件级改动**:
- `entity/WorkProcess.java` — 新增 `semiFinishedOutputCode VARCHAR(50)` 可选字段
- `dto/process/WorkProcessDTO.java` — 映射新字段
- `db/flyway/V20261010_03__work_process_semi_code.sql`

**用途**: 报工时 `output-options` 端点从工序配置读取 `semiFinishedOutputCode`, 由 RN 展示在 semiCode Picker 中, 操作员无需手填 code。

**先写测试**:
```java
// T2b-test-1: WorkProcess.semiFinishedOutputCode=null → 向后兼容旧工序
// T2b-test-2: WorkProcess.semiFinishedOutputCode 设为 "ZSH-001" → output-options 端点返回该 code
```

**Flyway SQL**:
```sql
-- V20261010_03__work_process_semi_code.sql
ALTER TABLE work_processes
  ADD COLUMN semi_finished_output_code VARCHAR(50) DEFAULT NULL;
COMMENT ON COLUMN work_processes.semi_finished_output_code
  IS '末道或中间道产出半成品时的 SKU code; null=仅产成品';
```

---

## Task T3 — `WipInventoryService` 扩展: Txn IN 写入 + 移动均价

**模型**: Sonnet in-harness
**Effort**: high
**文件级改动**:
- `service/wip/impl/WipInventoryServiceImpl.java`
  - `postApprovedOutput()` 末尾追加: 计算移动均价 → UPDATE SemiFinishedInventory → INSERT Txn(IN)
  - 全部在同一 `@Transactional` 内
- `service/wip/WipInventoryService.java` (接口) — 可能不变, 仅 impl 扩展
- 🆕 `service/wip/SemiFinishedInventoryTransactionService.java` + impl (封装 Txn 写入逻辑)

**先写测试** (红灯→绿灯):
```java
// WipInventoryServiceImplTest — 扩展
// T3-test-1: postApprovedOutput with inCost=150, qty=10kg → 验证:
//   SemiFinishedInventory.unitCost = 150 (首次 IN)
//   Txn 表有 1 行: txnType=IN, quantity=10, unitCostAtTxn=150, balanceAfter=10, balanceCostAfter=150
// T3-test-2: 已有 100kg unitCost=120 → 追加 IN 50kg inCost=180 →
//   新 unitCost = (100×120 + 50×180) / 150 = 140 (ROUND_HALF_UP scale-4)
// T3-test-3: inCost=null → unitCost 不变; Txn.unitCostAtTxn = null (诚实 null)
// T3-test-4: 并发 2 线程同时 IN → SELECT FOR UPDATE 保证 balanceAfter 无竞争
```

**实现关键点**:
```java
// WipInventoryServiceImpl.postApprovedOutput (追加段):

// 1. 先调现有 WIP upsert 逻辑
SemiFinishedInventory sfi = semiFinishedInventoryRepo
    .findByFactoryIdAndIntermediateBatchNoForUpdate(factoryId, intermediateBatchNo); // FOR UPDATE
if (sfi == null) { ... } // 同现有 upsert

// 2. 移动均价
BigDecimal newUnitCost = computeMovingAvgUnitCost(
    sfi.getAvailableQuantity(), sfi.getUnitCost(), inQty, inCost); // 见 §4.5

// 3. UPDATE sfi (已在同一事务锁下)
sfi.setProducedQuantity(sfi.getProducedQuantity().add(inQty));
sfi.setAvailableQuantity(sfi.getAvailableQuantity().add(inQty));
if (inCost != null) {
    sfi.setUnitCost(newUnitCost);
    sfi.setAccumulatedCost(sfi.getAccumulatedCost().add(inQty.multiply(inCost)));
}
semiFinishedInventoryRepo.save(sfi);

// 4. INSERT Txn
SemiFinishedInventoryTransaction txn = new SemiFinishedInventoryTransaction();
txn.setFactoryId(factoryId);
txn.setSemiFinishedId(sfi.getId());
txn.setTxnType("IN");
txn.setSourceType("PRODUCTION_OUTPUT");
txn.setSourceRef(intermediateBatchNo);
txn.setQuantity(inQty);
txn.setUnitCostAtTxn(inCost);  // null 诚实传
txn.setBalanceAfter(sfi.getAvailableQuantity());
txn.setBalanceCostAfter(newUnitCost);
txn.setReportId(report.getId());
sfiTxnRepo.save(txn);
```

---

### 分发卡 T3 → Sonnet in-harness

```
目标: WipInventoryServiceImpl.postApprovedOutput 追加移动均价 IN + Txn 写入
worktree: ../cretas-sp1-dual-output (off origin/main)
允许改:
  - service/wip/impl/WipInventoryServiceImpl.java
  - service/wip/WipInventoryService.java (仅 interface, 如需新增方法签名)
  - 新建 service/wip/SemiFinishedInventoryTransactionService.java + impl (Txn 封装)
  - repository/SemiFinishedInventoryRepository.java (添加 FOR UPDATE query)
禁改:
  - entity/ (T1/T2a/T2b 已有分工)
  - YieldReportServiceImpl.java (T4 专属)
上下文(规则):
  - 并发: SELECT FOR UPDATE (悲观行锁); 短事务 <100ms
  - 精度: 数量 scale-6 ROUND_HALF_UP; 成本 scale-4 ROUND_HALF_UP (CostRollupUtil 已有)
  - null 诚实: inCost=null → unitCost 不变, Txn.unitCostAtTxn=null (不填 0)
  - 同一 @Transactional: SFI UPDATE + Txn INSERT 在同一事务内; 禁 fail-soft try/catch 吞内层异常
  - 幂等守卫: 检查 source_ref(intermediateBatchNo)+txnType=IN 已存在 → 409 "已记录产出" + existingTxnId
验收:
  mvn test -Dtest=WipInventoryServiceImplTest 全绿 (含 T3-test-1..4)
  手动验证: FOR UPDATE query 存在 (JPQL 或 @Lock(PESSIMISTIC_WRITE))
并行: ❌ 依赖 T1 (需要 SemiFinishedInventoryTransaction entity + repository)
⛔ 收尾约束: PR only, 不部署 prod
```

---

## Task T4 — `YieldReportServiceImpl` OUTPUT 路由扩展 (outputKind)

**模型**: Sonnet in-harness
**Effort**: high
**文件级改动**:
- `service/yield/impl/YieldReportServiceImpl.java` — OUTPUT 阶段扩展
- `controller/processing/ProductionReportController.java` — 新增 `output-options` 端点

**先写测试**:
```java
// YieldReportServiceImplTest — 扩展
// T4-test-1: outputKind=FINISHED → FG 路径走, Txn 不写, WIP upsert 不调
// T4-test-2: outputKind=SEMI → WIP upsert 走 + Txn IN 写, FG 不建
// T4-test-3: outputKind=BOTH → WIP upsert + Txn + FG 同一事务全走
// T4-test-4: outputKind=null → 等同 FINISHED (向后兼容)
// T4-test-5: outputKind=SEMI 但 semiOutputQuantity=null → 400
// T4-test-6: outputKind=SEMI 但 semiCode=null → 400
```

**实现要点**:
```java
// YieldReportServiceImpl.submitReport — OUTPUT 阶段末尾
String kind = req.getOutputKind() != null ? req.getOutputKind() : "FINISHED";

boolean doFG   = "FINISHED".equals(kind) || "BOTH".equals(kind);
boolean doSemi = "SEMI".equals(kind)     || "BOTH".equals(kind);

// 校验 SEMI 必填
if (doSemi) {
    if (req.getSemiOutputQuantity() == null)
        throw new BusinessException("半成品产出量必填", "SEMI_QUANTITY_REQUIRED");
    if (req.getSemiCode() == null || req.getSemiCode().isBlank())
        throw new BusinessException("请选择半成品品类 code", "SEMI_CODE_REQUIRED");
}

if (doFG) {
    // 已有路径: completeProduction(末道) 或 仅记录(非末道)
    postFinishedOutput(report, batch, req);
}

if (doSemi) {
    // 调已扩展的 WipInventoryService (T3)
    wipInventoryService.postApprovedOutput(
        factoryId, report, req.getSemiOutputQuantity(), req.getSemiCode());
}
```

---

### 分发卡 T4 → Sonnet in-harness

```
目标: YieldReportServiceImpl OUTPUT 阶段根据 outputKind 路由到 FG / Semi / Both;
  新增 /output-options 端点
worktree: ../cretas-sp1-dual-output
允许改:
  - service/yield/impl/YieldReportServiceImpl.java
  - controller/processing/ProductionReportController.java (新增 output-options 端点)
  - dto/yield/ (如需新增 OutputOptionsResponse DTO)
禁改:
  - WipInventoryServiceImpl.java (T3 已改, merge 后才可继续)
  - 其余 entity 文件
上下文:
  - output-options 端点: GET /api/mobile/{factoryId}/processing/batches/{batchId}/output-options
    返回: { isLastStep: bool, availableSemiCodes: List<String> }
    逻辑: 查 WorkProcessTask 当前道是否末道; 从 WorkProcess.semiFinishedOutputCode 取 code 列表
  - DTO 4点往返: 新字段已在 T2a 完成, T4 仅调用
  - @PriceSensitive: unitCost 在 output-options 端点不返回 (仅返回 code 列表)
验收:
  mvn test -Dtest=YieldReportServiceImplTest 全绿 (含 T4-test-1..6)
  curl 测试 output-options 端点 200 返回
并行: ❌ 依赖 T2a (DTO 字段) + T3 (WipInventoryService 扩展)
⛔ 收尾约束: PR only
```

---

## Task T5 — RN `YieldStepReportScreen` OUTPUT 阶段 UI

**模型**: Sonnet in-harness (因涉及 UX Flow Gate 规则 + CLAUDE.md 低素养操作员规则)
**Effort**: high
**文件级改动**:
- `frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx`
- `frontend/CretasFoodTrace/src/services/api/productionReportApi.ts` — 新增 `fetchOutputOptions` 方法

**先写测试** (手工验收 + tsc 通过):
```
T5-验收-1: tsc --noEmit 无 type error
T5-验收-2: OUTPUT 阶段打开 → 自动调 output-options 端点 → 显示可选类型
T5-验收-3: 选"仅半成品" → 显示 semiCode Picker + 半成品数量输入 (数字键盘)
T5-验收-4: 选"成品+半成品" → 额外显示半成品数量 + semiCode, 原 outputQuantity 保留
T5-验收-5: availableSemiCodes 为空 → 显示提示 + 跳转按钮 (Rule 5 dead-end)
T5-验收-6: semiCode Picker 列表 = output-options 返回值 (不手填)
T5-验收-7: 提交中 Submit 按钮 disabled
```

**UX Flow 防呆实现**:
```tsx
// OUTPUT 阶段顶部添加:
// 1. fetchOutputOptions() on mount → isLastStep + availableSemiCodes
// 2. Segment/RadioGroup: [仅成品 | 仅半成品 | 成品+半成品]
//    默认选 "仅成品" (向后兼容)
// 3. outputKind === 'SEMI' || 'BOTH' 时展开:
//    - semiCode Picker (从 availableSemiCodes 生成选项)
//    - semiOutputQuantity TextInput (keyboardType="numeric", max 来自上游 WIP available)
// 4. availableSemiCodes.length === 0:
//    <Text>本工序未配半成品品类</Text>
//    <TouchableOpacity onPress={() => navigate('WorkProcessConfig')}>
//      前往工序配置
//    </TouchableOpacity>
```

---

### 分发卡 T5 → Sonnet in-harness

```
目标: RN YieldStepReportScreen OUTPUT 阶段添加产出类型选择 (FINISHED/SEMI/BOTH)
  + semiCode Picker + 防呆 UX
worktree: ../cretas-sp1-dual-output
允许改:
  - frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx
  - frontend/CretasFoodTrace/src/services/api/productionReportApi.ts
禁改: 任何 backend 文件; 其他 RN 屏幕
上下文(规则, 直接内联因 out-of-harness style 但此处 in-harness):
  UX Flow Gate (操作员低素养):
  - Rule 1: OUTPUT 打开即 fetch output-options, 预显可选类型+semiCode 列表; submit disabled 直到加载完
  - Rule 2: 屏幕顶部显示"{品名} · 第N道 · 批次{batchNumber}"; semiCode Picker 显示品类名称
  - Rule 3: outputKind 用 Segment 三选一 (不手填); semiCode 用 Picker (不手填)
  - Rule 4: 提交中 Submit disabled (幂等)
  - Rule 5: availableSemiCodes 为空 → 显示提示 + 跳转 WorkProcessConfig 页按钮
  AppDialog: 所有 Alert 用 AppDialog (已有 util, 见 Jun 4 报工 saga shipped)
  向后兼容: outputKind 默认 'FINISHED'; 原 outputQuantity TextInput 不动
验收:
  npx tsc --noEmit (RN 项目根目录)
  手工验收 T5-验收-1..7
并行: ❌ 依赖 T4 (output-options 端点) + T2a (semiCode DTO 字段)
⛔ 收尾约束: PR only
```

---

## Task T6 — web-admin 批次详情双产物展示

**模型**: Composer 2.5 (UI/样式, 纯展示层)
**Effort**: default
**文件级改动**:
- `web-admin/src/views/production/batches/detail.vue` (或对应详情页)
- `web-admin/src/api/productionApi.ts` — 新增 `fetchSemiFinishedTransactions(batchId)` 方法

**验收**:
- `cd web-admin && vue-tsc --noEmit`
- 批次详情页: 若有 SEMI/BOTH 报工 → 显示半成品产出行 (SKU code / 数量 / 单位 / unitCost @PriceSensitive)
- 点击"查看流水"→ 展示 SemiFinishedInventoryTransaction 列表 (created_at / txnType / quantity / balanceAfter)

---

### 分发卡 T6 → Composer 2.5

```
目标: web-admin 批次详情页展示双产出 (成品+半成品) + SFI 流水账列表
worktree: ../cretas-sp1-dual-output
允许改:
  - web-admin/src/views/production/batches/detail.vue (仅扩展, 不改已有布局)
  - web-admin/src/api/productionApi.ts
禁改: 任何 backend 文件; 其他 vue 文件
验收:
  cd web-admin && vue-tsc --noEmit 无 error
  批次详情: SEMI/BOTH 报工展示半成品行 (unitCost 仅 canViewPrice 可见)
  SFI 流水账抽屉/表格: 列表展示 (txnType/quantity/balanceAfter/createdAt)
并行: ✅ 与 T5 可并行 (不改同一文件); ❌ 依赖 T4 (API 端点)
⛔ 收尾约束: PR only
```

---

## 任务顺序与并行图

```
T1 (实体+Flyway) ─────────────────────────┐
T2a (ProductionReport DTO 4点往返) ────────┤
T2b (WorkProcess semiCode 字段) ───────────┤
                                            ↓
                                   T3 (WipInventoryService IN 扩展)
                                            ↓
                                   T4 (YieldReportService 路由 + output-options)
                                   ├────────↓──────────┐
                                   T5 (RN UI)       T6 (web-admin 展示)
```

- T1 / T2a / T2b 可并行开始 (不改同一文件)
- T3 依赖 T1 (Txn entity)
- T4 依赖 T2a (DTO) + T3 (WipService)
- T5 / T6 依赖 T4 (API 端点)

---

## Scope-lock 地图

| 文件 / 目录 | 锁定 task | 说明 |
|---|---|---|
| `entity/SemiFinishedInventoryTransaction.java` | T1 | 🆕 新建 |
| `repository/SemiFinishedInventoryTransactionRepository.java` | T1 | 🆕 新建 |
| `db/flyway/V20261010_02__*.sql` | T1 | 🆕 新建 |
| `entity/ProductionReport.java` | T2a | 字段扩展 |
| `dto/yield/SubmitYieldReportRequest.java` | T2a | DTO 扩展 |
| `dto/yield/YieldReportDTO.java` | T2a | convertToDTO |
| `entity/WorkProcess.java` | T2b | 字段扩展 |
| `db/flyway/V20261010_01__*.sql` | T2a | 🆕 新建 |
| `db/flyway/V20261010_03__*.sql` | T2b | 🆕 新建 |
| `service/wip/impl/WipInventoryServiceImpl.java` | T3 | 扩展 postApprovedOutput |
| `service/yield/impl/YieldReportServiceImpl.java` | T4 | 扩展 OUTPUT 路由 |
| `controller/processing/ProductionReportController.java` | T4 | 新增 output-options 端点 |
| `frontend/.../YieldStepReportScreen.tsx` | T5 | RN UI 扩展 |
| `web-admin/.../batches/detail.vue` | T6 | web 展示扩展 |

**SP2/SP3 禁止在本 SP merge 之前 fork worktree 并修改** `WipInventoryServiceImpl` / `YieldReportServiceImpl`。

---

## 验收命令 (完整 PR 前)

```bash
# 1. Backend 单元测试
cd backend/java/cretas-api
mvn test -Dtest="SemiFinishedInventoryTransactionRepositoryTest,ProductionReportDtoRoundtripTest,WipInventoryServiceImplTest,YieldReportServiceImplTest"

# 2. 全量 build + test
mvn clean test

# 3. Flyway 号段查重 (merge 前必跑)
git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway \
  | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | grep "^V20261010" | uniq -d
# 输出为空 = 安全

# 4. Frontend type check
cd frontend/CretasFoodTrace && npx tsc --noEmit
cd web-admin && vue-tsc --noEmit

# 5. scope 干净确认
git diff origin/main...HEAD --stat
# 应只有 SP1 相关文件; 无 SP2/SP3 文件夹带
```

---

## 🔒 红线任务 — Opus 终审门控

以下判断/操作由 **Opus 终审**, 执行者 PR 后停手等待:

1. **移动均价公式 + FOR UPDATE 并发锁 review** — T3 实现是否满足 spec §4.5; SELECT FOR UPDATE query 是否正确; inCost=null 分支是否诚实传播
2. **事务完整性 review** — SFI UPDATE + Txn INSERT 是否在同一 @Transactional; 是否有 fail-soft try/catch 吞内层异常 (教训: feedback_failsoft_catch_cannot_save_doomed_tx)
3. **DTO 4点往返 review** — outputKind / semiOutputQuantity / semiCode 4 字段全 4 处检查
4. **Flyway 号段 merge-before-deploy confirm** — 确认无冲突后 merge main + 从 main 部署 prod
5. **向后兼容 review** — outputKind=null 路径确实等同 FINISHED; 旧式 F006 批次不受影响

---

## 后续 SP 交接 (SP1 merge main 后解锁)

- **SP2 (整单撤回)**: 可 fork worktree, 复用本 SP `SemiFinishedInventoryTransaction` 写入 REVERSE 行
- **SP3 (三价成本)**: 可 fork worktree, 读取 `Txn.unitCostAtTxn` 作为半成品成本来源
- **SP4+ 其他**: 不依赖本 SP 的 `SemiFinishedInventoryTransaction`, 可与 SP1 并行开 worktree
