# SP2 · 二次加工 + 整单撤回 — 实施计划

> **生成**: 2026-06-09 Sonnet 4.6 in-harness  
> **依赖**: SP1（SemiFinishedInventoryTransaction）必须先 merge 进 origin/main  
> **号段**: Flyway V20260910_11 ~ V20260910_14  
> **总 worktree**: `git worktree add -b feat/sp2-secondary-reversal ../cretas-sp2 origin/main`

---

## 前置：Flyway 查重纪律

SP2 开工前，从 origin/main worktree 执行：

```bash
git ls-tree origin/main db/flyway --name-only | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
# 期望: 无输出 (无重复号)

# 验证 SP2 号段未被占用:
git ls-tree origin/main db/flyway --name-only | grep 'V20260910_1'
# 期望: 无输出 (11~14 段均未申请)
```

merge 进 main 前再跑一次（SP1 并行可能已占 0x 段）。

---

## 分发总览

| # | 任务 | 推荐模型 | 可否并行 | worktree 分支 | 🔒红线 |
|---|---|---|---|---|---|
| T1 | Flyway 迁移 + 实体 + Repo | Sonnet in-harness | ❌（地基，其余依赖） | feat/sp2-secondary-reversal | |
| T2 | ReportReversalService（守卫+事务） | Sonnet in-harness | ❌（依赖 T1） | feat/sp2-secondary-reversal | 🔒 |
| T3 | WipInventoryService 跨单扣减扩展 | Sonnet in-harness | ❌（依赖 T1） | feat/sp2-secondary-reversal | 🔒 |
| T4 | ProductionPlanService 二次加工独立单 | Sonnet in-harness | ✅（与 T2 并行后） | feat/sp2-secondary-reversal | |
| T5 | web-admin：撤回 dialog + 审批页 | Composer | ✅（依赖 T2 端点） | feat/sp2-ui-web | |
| T6 | RN：领半成品标签页 + ReversalSubmitScreen | Composer | ✅（依赖 T3/T4 端点） | feat/sp2-ui-rn | |
| T7 | 集成测试 + E2E（Sonnet CLI） | Sonnet in-harness | ❌（依赖 T1-T4） | feat/sp2-secondary-reversal | |
| T8 | 🔒 Opus 终审 + merge + 部署 | Opus 本体 | — | main | 🔒 |

---

## Scope-Lock 地图

| 文件 / 目录 | 锁定任务 | 预计解锁 |
|---|---|---|
| `db/flyway/V20260910_11~V20260910_14.sql` | T1 | T1 PR 合并后 |
| `entity/yield/ReportReversalLog.java` | T1 | — |
| `repository/yield/ReportReversalLogRepository.java` | T1 | — |
| `entity/ProductionPlan.java` — planSourceType 字段 | T1 | — |
| `service/yield/ReportReversalService.java` (新接口+Impl) | T2 | — |
| `service/wip/impl/WipInventoryServiceImpl.java` — `deductForSecondaryPlan` | T3 | — |
| `service/impl/ProductionPlanServiceImpl.java` — `createSecondaryPlan` | T4 | — |
| `controller/yield/ProcessingController.java` — 撤回端点 | T2 | — |
| `controller/plan/ProductionPlanController.java` — SECONDARY 参数 | T4 | — |
| `web-admin/src/views/processing/plans/ReversalRequestDialog.vue` | T5 | — |
| `web-admin/src/views/processing/approval/reversal-list.vue` | T5 | — |
| `frontend/CretasFoodTrace/src/screens/processing/YieldBatchSelectScreen.tsx` | T6 | — |
| `frontend/CretasFoodTrace/src/screens/processing/ReversalSubmitScreen.tsx` | T6 | — |

**SP2 不动的文件（禁止碰）：**
- `YieldReportServiceImpl.java` — SP1/SP2/SP9 串行锁，SP2 本体不修此文件（撤回逻辑在新 `ReportReversalService`）
- `SemiFinishedInventory.java` 核心字段 — SP1 管；SP2 只读取
- 任何 SP1 创建的 `SemiFinishedInventoryTransaction` — SP2 只调 Repo 写入

---

## T1 — Flyway 迁移 + 实体 + Repo

**模型**: Sonnet in-harness  
**Effort**: high  
**Worktree**: `feat/sp2-secondary-reversal`（全 T1~T4/T7 共享此分支）

### 允许改
- `db/flyway/` 新建 V20260910_11~V20260910_14
- `entity/yield/ReportReversalLog.java`（新建）
- `entity/ProductionPlan.java`（加 planSourceType/secondarySourceWipId 字段）
- `entity/inventory/FinishedGoodsBatch.java`（加 REVERSED 枚举 + reversalLogId 字段）
- `repository/yield/ReportReversalLogRepository.java`（新建）

### 禁改
- `YieldReportServiceImpl.java`
- `SemiFinishedInventoryTransaction`（SP1 拥有）
- 任何已 ship 的 controller

### 文件级变更

**V20260910_11__production_plan_secondary_source_type.sql**
```sql
ALTER TABLE production_plans
  ADD COLUMN plan_source_type VARCHAR(30) NOT NULL DEFAULT 'NORMAL',
  ADD COLUMN secondary_source_wip_id BIGINT
      REFERENCES semi_finished_inventory(id) ON DELETE SET NULL;
COMMENT ON COLUMN production_plans.plan_source_type IS 'NORMAL|SECONDARY';
```

**V20260910_12__report_reversal_logs.sql**
```sql
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
);
CREATE INDEX idx_rrl_factory_batch ON report_reversal_logs(factory_id, batch_id);
CREATE INDEX idx_rrl_status ON report_reversal_logs(factory_id, status) WHERE deleted_at IS NULL;
```

**V20260910_13__semi_finished_txn_type_comment.sql**
```sql
-- SP1 建此表；SP2 扩注释并声明 SECONDARY_CONSUME/REVERSE 枚举含义
COMMENT ON COLUMN semi_finished_inventory_transactions.txn_type IS
  'IN=产出入库 | OUT=同单续工序领用 | SECONDARY_CONSUME=跨单二次加工领用 | REVERSE=撤回 | ADJUST=调整';
```

**V20260910_14__finished_goods_reversed.sql**
```sql
ALTER TABLE finished_goods_batches
  ADD COLUMN reversal_log_id BIGINT REFERENCES report_reversal_logs(id);
-- REVERSED 状态在 Java 层添加 FinishedGoodsBatchStatus 枚举，DB 列 VARCHAR 无约束
```

**实体类 (TDD 先写测试)**

```java
// ReportReversalLog.java
// 测试: ReportReversalLogTest 验证 UNIQUE(batch_id,reversal_scope) 约束
// 测试: 字段 null-safe (reason/approvedBy/revertedTxnIds 均可 null)
```

### 测试命令
```bash
cd backend/java/cretas-api
./mvnw test -Dtest="ReportReversalLogTest,ProductionPlanEntityTest" -Dspring.profiles.active=test
```

### 验收
- `git diff origin/main...HEAD --stat` 仅含上述 5 个迁移文件 + 3 个实体/Repo 文件
- 本地 `./mvnw flyway:migrate` 无报错
- `FlywayMigrationValidationTest` 绿

---

## T2 — ReportReversalService（守卫 + 事务） 🔒

**模型**: Sonnet in-harness  
**Effort**: high  
**⛔ 收尾约束**: 只做到"实现 + 自测 + PR"，不许自部署 prod，Opus 终审

### Brief（自包含）

**目标**: 实现整单撤回的三层前置守卫 + 单事务 null-safe 回退。

**关键规则（in-harness 自动加载，复述确认）：**
1. 禁止 fail-soft `try/catch` 吞内层异常，多次复发 (`feedback_failsoft_catch_cannot_save_doomed_tx`)
2. REQUIRED 事务内层抛异常 → 父事务立即标记 rollback-only，catch 救不了
3. 真正独立副作用（如推送通知）必须 `@Transactional(REQUIRES_NEW)` 物理隔离
4. null-safe：任一字段 null → skip 该步骤 + log WARN，不抛 NPE
5. 幂等：同 batchId + DONE → 返 already-reverted (HTTP 200)，不重跑

### 允许改
- 新建 `service/yield/ReportReversalService.java` 接口
- 新建 `service/yield/impl/ReportReversalServiceImpl.java`
- 扩 `controller/yield/ProcessingController.java`（新增撤回相关端点）

### 禁改
- `YieldReportServiceImpl.java`（SP1 串行锁）
- `WipInventoryServiceImpl.java`（T3 拥有）
- `ProcessingServiceImpl.cancelProduction`（现有逻辑不动）

### 文件级变更

**接口：`ReportReversalService`**
```java
public interface ReportReversalService {
    /** 提交撤回申请；无数据则直撤并返回 DONE */
    ReversalSubmitResult submitReversal(String factoryId, Long batchId, ReversalRequest req);
    /** 审批通过执行撤回（单事务 null-safe） */
    ReversalResult executeReversal(String factoryId, Long logId);
    /** 审批驳回 */
    void rejectReversal(String factoryId, Long logId, String note);
    /** 获取待审批列表 */
    Page<ReportReversalLog> listPending(String factoryId, Pageable pageable);
}
```

**实现：`ReportReversalServiceImpl`**
- `checkPreConditions(factoryId, batchId)` — 三层守卫（查询在事务外执行）
- `executeReversal(factoryId, logId)` — `@Transactional`，步骤严格按 spec § 5.3 Step 3 a-h

**端点（扩 ProcessingController）**:
```
POST /api/mobile/{factoryId}/batches/{batchId}/reversal
GET  /api/mobile/{factoryId}/reversals?status=PENDING
PUT  /api/mobile/{factoryId}/reversals/{logId}/approve
PUT  /api/mobile/{factoryId}/reversals/{logId}/reject
```

权限检查方式（使用请求 role 属性，NOT SecurityContext）：
```java
// ⛔ 不能用 SecurityUtils.hasAnyRole() — SecurityContext 永空 (C1 孪生坑)
String role = (String) request.getAttribute("role");
if (!"factory_super_admin".equals(role) && !"factory_finance".equals(role)) {
    throw new ForbiddenException("需要厂长或财务权限");
}
```

### 测试（先红后绿）
```java
// ReportReversalServiceTest.java
@Test void submitReversal_withDownstreamConsumption_returns409()
@Test void submitReversal_withShippedFG_returns409()
@Test void submitReversal_noData_executesDirect_returnsDone()
@Test void submitReversal_withData_createsPendingLog()
@Test void executeReversal_idempotent_alreadyDone_returnsExisting()
@Test void executeReversal_nullSafe_missingMaterialBatchRef_skips()
@Test void executeReversal_movingAvgRollback_singleIn_unitCostBecomesNull()
@Test void executeReversal_movingAvgRollback_multipleInOut_correctReplay()
```

### 测试命令
```bash
./mvnw test -Dtest="ReportReversalServiceTest" -Dspring.profiles.active=test
```

### 验收
- 8 个测试全绿
- `ProcessingController` 端点被 Spring 正确注册（`./mvnw spring-boot:run` 后 `/api/mobile/health` 正常）

---

## T3 — WipInventoryService 跨单扣减扩展 🔒

**模型**: Sonnet in-harness  
**Effort**: high

### Brief（自包含）

**目标**: 在 `WipInventoryServiceImpl` 新增 `deductForSecondaryPlan` 方法，处理跨单二次加工对 `SemiFinishedInventory` 的 OUT 扣减 + 写 `SemiFinishedInventoryTransaction(SECONDARY_CONSUME)`。

**关键规则：**
1. 悲观行锁：`SELECT ... FOR UPDATE`（蓝图 § 3.2，现有 `validateSourceWip` 同模式）
2. 扣减后 `available < 0` → 抛 `409 WIP_INSUFFICIENT`（non-transactional 前检，事务内再断言）
3. 单位不一致 → 抛 `409 WIP_UNIT_MISMATCH`（教训：复用现有 unit 校验模式）
4. 写 `SemiFinishedInventoryTransaction` 时：`txn_type=SECONDARY_CONSUME`, `source_ref=planId`

### 允许改
- `service/wip/impl/WipInventoryServiceImpl.java` — 新增 `deductForSecondaryPlan` 方法
- `service/wip/WipInventoryService.java` — 扩接口

### 禁改
- 现有 `postApprovedOutput`、`validateSourceWip`、`consumeSourceWip` 方法（不改不拆）

### 新增方法签名
```java
/**
 * 跨单二次加工领用：悲观锁扣减 SemiFinishedInventory + 写 SECONDARY_CONSUME 流水
 * @throws WipInsufficientException (409) 可用量不足
 * @throws WipUnitMismatchException (409) 单位不一致
 */
void deductForSecondaryPlan(String factoryId, Long wipId, BigDecimal quantity,
                             String unit, String sourcePlanId);

/** 获取可用半成品列表（跨单领用选源） */
List<SemiFinishedInventoryDto> listAvailable(String factoryId);
```

### 新增端点（扩 WipInventoryController 或 ProcessingController）
```
GET /api/mobile/{factoryId}/wip/available
```

### 测试
```java
// WipInventoryServiceTest_Secondary.java
@Test void deductForSecondaryPlan_normal_decreasesAvailable()
@Test void deductForSecondaryPlan_insufficient_throws409()
@Test void deductForSecondaryPlan_unitMismatch_throws409()
@Test void deductForSecondaryPlan_concurrent_onlyOneSucceeds()  // 两线程同时扣超量
@Test void listAvailable_returnsOnlyAvailableStatus()
```

### 测试命令
```bash
./mvnw test -Dtest="WipInventoryServiceTest_Secondary" -Dspring.profiles.active=test
```

---

## T4 — ProductionPlanService 二次加工独立单

**模型**: Sonnet in-harness  
**Effort**: high

### 目标
`createSecondaryPlan`：接 `planSourceType=SECONDARY + secondarySourceWipId`，写 `ProductionPlan`，开工时调 `WipInventoryService.deductForSecondaryPlan`（非创建时扣）。

### 允许改
- `service/impl/ProductionPlanServiceImpl.java` — 新增 `createSecondaryPlan`，扩 `startProduction` 处理 SECONDARY 来源
- `controller/plan/ProductionPlanController.java` — 扩创建接口接收 `planSourceType` 参数
- `dto/plan/CreateProductionPlanRequest.java` — 加 `planSourceType`、`secondarySourceWipId` 字段

### 禁改
- 现有 `createProductionPlan` 普通路径（不改签名）

### fool-proof 预显可用量
创建 SECONDARY 计划前，前端调 `GET /wip/available` 取 `availableQuantity`，创建 dialog 显示"最多可用 {avail}kg"并限制 input `:max="avail"`。

### 测试
```java
// ProductionPlanServiceTest_Secondary.java
@Test void createSecondaryPlan_setsCorrectPlanSourceType()
@Test void createSecondaryPlan_doesNotDeductWipAtCreation()
@Test void startSecondaryPlan_deductsWipAndWritesTxn()
@Test void startSecondaryPlan_wipInsufficient_throws409AndRollsBack()
```

---

## T5 — web-admin UI（Composer dispatch 卡）

**模型**: Composer 2.5  
**Effort**: default  
**Worktree**: `feat/sp2-ui-web` off origin/main  
**⛔ out-of-harness，brief 必须自包含**

### Brief（复制给 Composer）

**目标**: 实现两个 web-admin 前端功能：
1. `ReversalRequestDialog.vue` — 生产批次详情页的"申请撤回"对话框
2. `reversal-list.vue` — 撤回审批列表页（厂长/财务角色可见）

**允许改**:
- 新建 `web-admin/src/views/processing/plans/ReversalRequestDialog.vue`
- 新建 `web-admin/src/views/processing/approval/reversal-list.vue`
- 扩 `web-admin/src/views/processing/plans/batch-detail.vue`（加"申请撤回"按钮）
- 扩路由配置 `web-admin/src/router/index.js`（加审批列表页路由）

**禁改**: `web-admin/src/api/request.ts`、`web-admin/src/store/`、任何已有的 dialog 文件

**API 端点**:
```
POST /api/mobile/{factoryId}/batches/{batchId}/reversal
  请求: { reason: string, note?: string }
  响应: { success, data: { logId, status: 'PENDING'|'DONE' } }

GET  /api/mobile/{factoryId}/reversals?status=PENDING&page=0&size=20
PUT  /api/mobile/{factoryId}/reversals/{logId}/approve
PUT  /api/mobile/{factoryId}/reversals/{logId}/reject   请求: { note: string }
```

**fool-proof 规则（必须遵守）**:
- `ReversalRequestDialog`：顶部显示产品名 + 批次号 + 计划量（不让用户搞混批次，Rule 2）
- 撤回原因用 `el-select`（录入错误/产品变更/质量问题/其他），选"其他"才显 textarea（Rule 3）
- 已存在 PENDING → 提示"已提交申请 {logId}，是否查看" + 跳审批列表（Rule 4 幂等）
- 确认按钮显示 loading + 禁止重复点击

**Error toast（4 位一体）**:
```typescript
// 必须用 duration:0 + showClose (已在 request.ts 落地)
// toast 内容必须是后端 response.data.message，不用 fallback "操作失败"
```

**验收命令**:
```bash
cd web-admin
npm run type-check   # 0 错误
npm run build        # 0 error
```

**交接**: 完成 → PR off origin/main → `git diff origin/main...HEAD --stat` 确认 scope 只有上述文件

---

## T6 — RN UI（Composer dispatch 卡）

**模型**: Composer 2.5  
**Effort**: default  
**Worktree**: `feat/sp2-ui-rn` off origin/main  
**⛔ out-of-harness，brief 必须自包含**

### Brief（复制给 Composer）

**目标**: 两处 RN UI 变更：
1. `YieldBatchSelectScreen.tsx`：新增"领半成品"标签页（sourceType=SEMI_FINISHED 选源）
2. 新建 `ReversalSubmitScreen.tsx`：操作员提交整单撤回申请（低输入，防呆优先）

**允许改**:
- `frontend/CretasFoodTrace/src/screens/processing/YieldBatchSelectScreen.tsx`（加标签页）
- 新建 `frontend/CretasFoodTrace/src/screens/processing/ReversalSubmitScreen.tsx`
- 扩导航 `frontend/CretasFoodTrace/src/navigation/ProcessingStack.tsx`（加 ReversalSubmit 路由）

**禁改**: `YieldStepReportScreen.tsx`、`YieldReportServiceImpl`（后端文件）、`AppDialog.tsx`

**API 端点（RN 用）**:
```
GET /api/mobile/{factoryId}/wip/available
  响应: [{ wipId, intermediateBatchNo, productTypeName, availableQuantity, unit, producedAt }]

POST /api/mobile/{factoryId}/batches/{batchId}/reversal
  请求: { reason: 'ENTRY_ERROR'|'PRODUCT_CHANGE'|'QUALITY_ISSUE'|'OTHER', note?: string }
  响应: { success, data: { logId, status: 'PENDING'|'DONE', message: string } }
```

**UX Flow 规则（低素养操作员，必须遵守）**:

`YieldBatchSelectScreen` 标签页扩展：
- 列表每行：产品名 + 批次号 + 可用量 `{avail}kg` + 产出日期
- 可用量=0 的行：置灰 + 提示"已被领走"（不出错 toast）
- 无可用半成品：EmptyState "暂无可用半成品，请联系厂长" + "返回" button（不卡死）

`ReversalSubmitScreen`：
- 顶部固定显示：产品名 + 批次号 + 计划量 + 已产出（不让操作员搞混）
- 原因选择：4 个 RN Pressable 按钮（录入错误/产品变更/质量问题/其他），选中高亮
- 选"其他"才显 TextInput 备注框
- 同批次已有 PENDING：Alert "已提交撤回申请，请等待审批"，不重复提交
- 无报工数据直撤：Alert "确认？无已提交数据，将立即撤回（无需审批）"
- 成功：提示"已提交，请等待 X 审批" + 返回任务列表 button

**错误提示（RN 版 4 位一体）**:
- 不用 `Alert.alert("操作失败")` — 用 `appAlert`（已 ship 的 AppDialog 封装）
- toast 内容 = 后端 message，不用 catch fallback

**验收命令**:
```bash
cd frontend/CretasFoodTrace
npx tsc --noEmit   # 0 TypeScript 错误
# RN: 手工在 test 设备上验证两个屏幕渲染 + 按钮点击
```

**交接**: 完成 → PR off origin/main → `git diff origin/main...HEAD --stat` 确认 scope 只有上述文件

---

## T7 — 集成测试 + E2E

**模型**: Sonnet in-harness（CLI/测试运行）  
**Effort**: high

### 集成测试
```bash
cd backend/java/cretas-api
./mvnw test -Dtest="ReportReversalServiceTest,WipInventoryServiceTest_Secondary,ProductionPlanServiceTest_Secondary" \
  -Dspring.profiles.active=test
```

期望：所有测试绿，0 skipped。

### Flyway 迁移完整验证
```bash
./mvnw flyway:migrate -Dspring.profiles.active=test
# 确认 4 个 V20260910_1x 迁移全部 applied
./mvnw flyway:info | grep V20260910_1
# 期望: V20260910_11/12/13/14 均显示 success
```

### web-admin 构建验证
```bash
cd web-admin
npm run build 2>&1 | grep -E 'error|ERROR'
# 期望: 0 行输出
```

### E2E（headed，Sonnet in-harness 运行）
```bash
PLAYWRIGHT_PORT=9222 PLAYWRIGHT_CHAT_ID=sp2 \
  npx playwright test tests/sp2-reversal.spec.ts --headed
```

覆盖 4 个场景（详见 spec § 8.3）。

### API smoke test（后端 live）
```bash
# 健康检查
curl -s http://47.100.235.168:10011/api/mobile/health | jq .

# 验证撤回端点存在（401 表示端点已注册，只是无 token）
curl -s -o /dev/null -w "%{http_code}" \
  http://47.100.235.168:10011/api/mobile/F006/batches/9999/reversal \
  -X POST -H "Content-Type: application/json" -d '{"reason":"ENTRY_ERROR"}'
# 期望: 401 (已注册，拦截于 JWT)
```

---

## T8 — 🔒 Opus 终审 + merge + 部署

**执行者**: Opus 本体（organizer chat）

### 终审 checklist
- [ ] `git diff origin/main...HEAD --stat` 确认 scope 仅含 SP2 相关文件（无 sister 文件夹带）
- [ ] 三层撤回守卫均有测试覆盖
- [ ] `executeReversal` 无 fail-soft catch 吞 doomed tx（grep `try {` in ReportReversalServiceImpl）
- [ ] REVERSED 状态未与其他 SP 冲突（grep `REVERSED` in FG 实体）
- [ ] 权限检查用 `request.getAttribute("role")` 非 SecurityContext（grep `SecurityUtils` in T2 新文件）
- [ ] Flyway 查重：`git ls-tree origin/main db/flyway | grep -oE 'V[0-9]+_[0-9]+' | sort | uniq -d` = 0
- [ ] 移动均价重放测试含"多次 IN/OUT 交错→正确重算"场景
- [ ] WriteGuard 动词后缀覆盖 reversal（grep `reversal` in WriteGuardService）
- [ ] W0 confirm door 通过（非静默写入）

### 部署
```bash
# 1. merge 进 main
git checkout main && git pull origin main
# 2. test 环境先部
./scripts/deploy/deploy-backend.sh --env test
# 3. smoke test (见 T7 API smoke)
# 4. prod 部署
./scripts/deploy/deploy-backend.sh --env prod
# 5. 核对 prod jar 含 ReportReversalService
ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar \
  'BOOT-INF/classes/com/cretas/aims/service/yield/impl/ReportReversalServiceImpl.class' \
  > /dev/null && echo 'PRESENT' || echo 'MISSING'"
# 期望: PRESENT
```

---

## 跨子项依赖 / 风险（3-5 条）

1. **SP1 串行依赖（阻塞）**: SP2 的 `SemiFinishedInventoryTransaction` 写入（REVERSE/SECONDARY_CONSUME）依赖 SP1 建此表。SP2 worktree 必须在 SP1 PR merge 进 origin/main 后开。若 SP1 延期，SP2 可先完成 `ReportReversalLog` + 守卫层 + ProductionPlan 二次加工字段，但 executeReversal 的移动均价重放步骤暂时 stub（抛 `NotImplementedYet`），等 SP1 merge 后解 stub。

2. **YieldReportServiceImpl 串行锁（SP1→SP2→SP9）**: 蓝图明确此文件全程串行。SP2 不修该文件（撤回逻辑在新 `ReportReversalService`），但 SP9 若要修则必须等 SP2 merge。

3. **Flyway 号段撞车风险（merge 前查重）**: SP1 号段 V20260910_0x，SP2 号段 V20260910_1x，理论不重叠，但 merge 前仍需 `git ls-tree origin/main db/flyway | grep V20260910 | sort | uniq -d` 查重确认（教训 `feedback_flyway_cross_session_dup_collision`，已复发 3 次）。

4. **W0 WriteGuard 动词后缀覆盖确认**: 撤回端点 `POST /batches/{id}/reversal` 是写操作，必须过 WriteGuard 确认门。需验证 W0 的 `isWriteIntent` 动词后缀列表包含 `reversal`（`REVERSAL` 后缀），若未覆盖则 T2 实现者需在 `WriteGuardService` 扩一行，属 W0 范围内的小扩展（非独立 SP，T8 终审核实）。

5. **FinishedGoodsBatch REVERSED 枚举值并发（SP5/SP6 可能同期改 FG 状态）**: SP5（成品入库审核）和 SP6（盘点）可能也在扩 `FinishedGoodsBatchStatus`。协调：各 SP 只新增自己需要的枚举值，Java 枚举追加安全（不改现有值）；DB 列为 VARCHAR 无约束，追加不冲突。

---

## 实施次序 Gantt（参考）

```
Day 1: T1（迁移+实体）→ 绿灯后解锁 T2/T3/T4
Day 2: T2（撤回服务） + T3（WIP扣减）— 可并行于同一 worktree（不同文件）
Day 3: T4（二次加工计划服务）
Day 4: T5（web-admin UI）+ T6（RN UI）— 依赖 T2/T3/T4 端点，可用 mock server 先跑
Day 5: T7（集成测试+E2E）
Day 6: T8（Opus 终审+merge+部署）
```

---

## 并行安全说明

T2 + T3 可在同一 worktree 并行（不同文件：`ReportReversalServiceImpl` vs `WipInventoryServiceImpl`），但 git commit 用 `--only` 模式锁 scope：

```bash
# T2 commit 示例
git commit -m "feat(sp2): ReportReversalService 三层守卫+事务回退" \
  -- service/yield/ReportReversalService.java \
     service/yield/impl/ReportReversalServiceImpl.java \
     controller/yield/ProcessingController.java

# T3 commit 示例
git commit -m "feat(sp2): WipInventoryService 跨单二次加工扣减" \
  -- service/wip/WipInventoryService.java \
     service/wip/impl/WipInventoryServiceImpl.java
```
