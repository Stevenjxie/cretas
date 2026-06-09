# SP5 · 销售到开票 — 实施计划

**子项**: SP5 · 销售到开票 (含未税税率 + 毛利红线 + 开票传票)
**Flyway 号段**: V20260910_40 ~ V20260910_49
**Wave**: Wave 3 (SP3 + SP4 完成后启动)
**Fleet 现状**: Codex/GPT 暂停，out-of-harness 只有 **Composer**；CLI/E2E/规则重后端任务走 **Sonnet in-harness**

---

## 总体结构

```
SP3 + SP4 完成 (提供 standardCost + taxRate 字段)
        ↓
Wave3: SP5 任务并行执行
 T1 (Sonnet): DB Migration + Entity 增量
 T2 (Sonnet): GrossMarginRedlineService [🔒 Opus 终审]
 T3 (Sonnet): SalesServiceImpl 激活 costEstimate
 T4 (Sonnet): SalesFinanceApproveVoucherListener
 T5 (Sonnet): CommissionRuleService.previewByGrossMargin
 T6 (Composer): 前端含税/未税双列 + 预警展示
 T7 (Opus自留): 🔒 红线 spec 终审 + merge + 部署
```

---

## 分发总览

| # | 任务 | 推荐模型 | 可否并行 | worktree 分支 | 🔒红线 |
|---|---|---|---|---|---|
| T1 | DB Migration + Entity 增量 | **Sonnet in-harness** | ✅ | feat/sp5-t1-migration | |
| T2 | GrossMarginRedlineService 实现 | **Sonnet in-harness** | 依赖 T1 | feat/sp5-t2-redline | 🔒 |
| T3 | SalesServiceImpl 激活 costEstimate | **Sonnet in-harness** | 依赖 T1+T2 | feat/sp5-t3-activate | 🔒 |
| T4 | SalesFinanceApproveVoucherListener | **Sonnet in-harness** | ✅ (与T1并行) | feat/sp5-t4-voucher | 🔒 |
| T5 | CommissionRuleService.previewByGrossMargin | **Sonnet in-harness** | ✅ (与T1并行) | feat/sp5-t5-commission | |
| T6 | 前端含税/未税 + 预警 + 提成展示 | **Composer** | 依赖 T2 API | feat/sp5-t6-frontend | |
| T7 | 🔒 红线终审 + merge + 部署 | **Opus 自留** | — | main | 🔒 |

---

## 详细任务 Brief 卡

---

### 卡T1 → Sonnet in-harness

**目标**: 添加 3 个 Flyway migration + Entity 字段

**worktree**:
```bash
git worktree add -b feat/sp5-t1-migration ../cretas-sp5-t1 origin/main
```

**先写测试**:
- `MigrationSanityTest.java` — 验证迁移后字段存在 (schema 断言)

**文件级改动**:
1. `db/flyway/V20260910_40__sales_order_commission_preview.sql`
2. `db/flyway/V20260910_41__production_plan_source_order_ids.sql`
3. `db/flyway/V20260910_42__factory_gross_margin_config.sql`
4. `entity/inventory/SalesOrder.java` — 加 `commissionPreview`, `commissionRatePct` (均 `@PriceSensitive`)
5. `entity/ProductionPlan.java` — 加 `sourceOrderIds` JSONB list (JsonBinaryType)
6. 新建 `entity/pricing/FactoryGrossMarginConfig.java` (BaseEntity 继承，含 id/factoryId/productTypeId/targetGrossMargin/effectiveFrom/effectiveTo)
7. 新建 `repository/pricing/FactoryGrossMarginConfigRepository.java`

**DTO 4处全套** (commissionPreview 字段):
- `CreateSalesOrderRequest.java` — 不需要（下单不传提成，由后端算）
- `convertToDTO` / 响应 DTO — 确认 commissionPreview 出现在 SalesOrder JSON 响应

**允许改**: `db/flyway/V20260910_4x*.sql`, `entity/inventory/SalesOrder.java`, `entity/ProductionPlan.java`, `entity/pricing/`, `repository/pricing/`

**禁改**: `SalesOrderItem.java`, `PriceFieldResponseAdvice.java`, `SalesServiceImpl.java`, `CommissionRule.java`

**验收**:
```bash
cd backend/java/cretas-api && mvn clean test -Dtest="*Migration*,*SalesOrderEntityTest*" -pl .
# 预期: 0 FAIL
```

**并行**: ✅ 与 T4、T5 独立，可同时跑

**交接**: PR off origin/main → `git diff origin/main...HEAD --stat` 仅含 migration SQL + entity 字段新增

---

### 卡T2 → Sonnet in-harness (🔒 Opus 终审)

**目标**: 实现 `GrossMarginRedlineService` — 毛利红线公式核心，脱敏边界

**worktree**:
```bash
git worktree add -b feat/sp5-t2-redline ../cretas-sp5-t2 origin/main
```

**先写测试**:
```java
// GrossMarginRedlineServiceTest.java
@Test void cost8_margin10pct_threshold_8_8() {
    // standardCost=8.00, targetGrossMargin=0.10 → minPrice=8.80
    // belowRedline(8.50)=true, belowRedline(9.00)=false
}
@Test void checkResult_serialization_noLeakCostOrMinPrice() {
    // 序列化 GrossMarginCheckResult → JSON 不含 standardCost / minPrice 字段
}
@Test void resolvePriority_productLevel_overrides_factory() {
    // ProductType.targetGrossMargin=0.15 → 优先于 FactoryGrossMarginConfig=0.10
}
```

**文件级改动**:
1. `service/pricing/GrossMarginRedlineService.java` (接口)
2. `service/pricing/impl/GrossMarginRedlineServiceImpl.java`
3. `dto/pricing/GrossMarginCheckResult.java` — 只含 `{belowRedline: Boolean, warningMessage: String}` (无 minPrice/standardCost)
4. 新 API 端点放 `controller/inventory/SalesController.java`：`POST /{fid}/sales/check-margin`

**🔒 红线约束**:
- `GrossMarginCheckResult` **不能**有 minPrice / standardCost / targetGrossMargin 字段
- `warningMessage` 由后端生成，不含任何成本数字 (文案模板: `"当前单价低于毛利红线，请向上级确认"`)
- `standardCost` 只通过 `@PriceSensitive` 注解的字段读，不在 Service 里另外暴露
- `check-margin` 端点加 `@RequireRole({"factory_super_admin","sales_manager","finance_manager"})`

**内联规则摘要** (out-of-harness 用不到，但 in-harness Sonnet 自动加载):
- `.claude/rules/fool-proof-design.md` Rule 1: 预先显示边界，不事后报错
- `.claude/rules/database-entity-sync.md`: BaseEntity 必须字段 + CAST null

**允许改**: `service/pricing/Gross*`, `dto/pricing/Gross*`, `SalesController.java` (仅加 check-margin 端点)

**禁改**: `PriceFieldResponseAdvice.java`, `SalesServiceImpl.java`, `SalesOrder.java`

**验收**:
```bash
mvn test -Dtest="GrossMarginRedlineServiceTest" -pl .
# 序列化测试：确认 JSON 无 minPrice 字段
```

**⛔ 收尾约束**: 只做到"实现+自测+PR"，不许自部署 prod → 回 main 由 Opus 终审

**并行**: ❌ 依赖 T1 (需要 FactoryGrossMarginConfigRepository)

---

### 卡T3 → Sonnet in-harness (🔒 Opus 终审)

**目标**: 激活 `SalesServiceImpl.costEstimate` — 将 `null` 替换为 `productType.standardCost`

**worktree**:
```bash
git worktree add -b feat/sp5-t3-activate ../cretas-sp5-t3 origin/main
```

**先写测试**:
```java
// SalesServiceImplTest.java (扩展现有)
@Test void createOrder_withStandardCost_belowRedline_appendsWarning() {
    // productType.standardCost=8.0, targetGrossMargin=0.10
    // 下单 unitPrice=7.5 → SalesOrder 创建成功，但 warnings 非空
    // 确认: orderCreated=true, pricingWarnings 存在
}
@Test void createOrder_standardCostNull_noWarning() {
    // SP3 未就绪时 standardCost=null → warnings 空（null guard 不 NPE）
}
```

**文件级改动**:
1. `service/inventory/impl/SalesServiceImpl.java` — 仅修改 line 362 的 `costEstimate(null)` 为：
   ```java
   // SP5: 激活毛利红线 — standardCost from SP3
   BigDecimal standardCost = productTypeOpt
       .map(ProductType::getStandardCost)
       .orElse(null);  // null = 静默跳过 (SP3 未就绪时兼容)
   ...
   .costEstimate(standardCost)
   ```

**允许改**: `SalesServiceImpl.java` (仅 costEstimate 一处赋值)

**禁改**: `PricingEngineImpl.java`, `GrossMarginRedlineService.java`, `SalesOrder.java`

**SP3 依赖说明**: `ProductType.getStandardCost()` 由 SP3 migration 添加，SP5 T3 先写代码，`null` guard 保证 SP3 未到时不报错，E2E 需等 SP3 merge。

**验收**:
```bash
mvn test -Dtest="SalesServiceImplTest" -pl .
# 两个新测试绿
```

**⛔ 收尾约束**: 只到 PR，Opus 终审 costEstimate 激活 + null guard 正确性

**并行**: ❌ 依赖 T2 (GrossMarginRedlineService 编译)

---

### 卡T4 → Sonnet in-harness (🔒 Opus 终审)

**目标**: 新建 `SalesFinanceApproveVoucherListener` — FinanceApproved 事件触发传票

**worktree**:
```bash
git worktree add -b feat/sp5-t4-voucher ../cretas-sp5-t4 origin/main
```

**先写测试**:
```java
// SalesFinanceApproveVoucherListenerTest.java
@Test void onFinanceApproved_uncreated_triggersVoucher() {
    // vflag=UNCREATED → 调 voucherService.createFromBusiness → vflag=CREATED
}
@Test void onFinanceApproved_alreadyCreated_skips() {
    // vflag=CREATED → 不重复调 voucherService
}
@Test void onFinanceApproved_voucherFails_flaggedFailed_noRollback() {
    // voucherService 抛异常 → vflag=FAILED；financeApprove 主事务不回滚
}
```

**文件级改动**:
1. 新建 `listener/voucher/SalesFinanceApproveVoucherListener.java`
   - 监听 `SalesOrderFinanceApprovedEvent`
   - `@TransactionalEventListener(phase = AFTER_COMMIT) @Async`
   - 逻辑 100% 复用现有 `SalesOrderVoucherListener.handleVoucherGeneration` 模式

**注意**:
- `@Async` 需 `@EnableAsync` — 已有 `SalesOrderVoucherListener` 用了 `@Async`，确认已开
- 不动 `SalesOrderVoucherListener`（它监听 Confirmed，本 listener 监听 FinanceApproved，不冲突）
- 幂等：同一 SO 两次触发 → 第二次 vflag != UNCREATED → 跳过

**允许改**: `listener/voucher/SalesFinanceApproveVoucherListener.java` (新建只)

**禁改**: `SalesOrderVoucherListener.java`, `SalesReceiptVoucherGenerator.java`, `SalesServiceImpl.java`

**验收**:
```bash
mvn test -Dtest="SalesFinanceApproveVoucherListenerTest" -pl .
# 3 个测试全绿
```

**⛔ 收尾约束**: 只到 PR；Opus 终审 vflag 幂等 + AFTER_COMMIT 不污染主事务

**并行**: ✅ 与 T1、T5 独立，可同时启动

---

### 卡T5 → Sonnet in-harness

**目标**: 扩展 `CommissionRuleService` — 根据毛利金额算提成预览，写回 SalesOrder

**worktree**:
```bash
git worktree add -b feat/sp5-t5-commission ../cretas-sp5-t5 origin/main
```

**先写测试**:
```java
// CommissionRuleServicePreviewTest.java
@Test void tierConfig_range_match() {
    // tierConfig = [{min:0,max:100000,rate:5},{min:100000,max:null,rate:7}]
    // grossProfit=80000 → commissionRate=5%, commissionAmount=4000
}
@Test void noRule_returns_null() {
    // 无匹配规则 → commissionPreview=null（不报错）
}
@Test void flat_percentage_fallback() {
    // tierConfig=null → 用 CommissionRule.percentage 计算
}
```

**文件级改动**:
1. `service/CommissionService.java` — 新增 `previewByGrossMargin(factoryId, salespersonId, grossProfitAmount, orderDate)` 方法
2. `dto/sales/CommissionPreviewDTO.java` — `{commissionRate, commissionAmount}` (`@PriceSensitive`)
3. `listener/SalesOrderConfirmedCommissionListener.java` — 监听 `SalesOrderConfirmedEvent`，调 preview，写回 `SalesOrder.commissionPreview`

**算法**:
```
grossMarginAmount = estimatedCost != null ? totalAmount - estimatedCost : null
if (grossMarginAmount == null) → skip
CommissionRule rule = findApplicableRule(factoryId, salespersonId, orderDate)
tier = tierConfig != null ? matchTier(grossMarginAmount) : {rate: rule.percentage}
commissionAmount = grossMarginAmount * tier.rate / 100
```

**允许改**: `service/CommissionService.java`, `dto/sales/CommissionPreviewDTO.java`, `listener/SalesOrderConfirmedCommissionListener.java` (新建)

**禁改**: `CommissionRule.java`, `SalesOrder.java`, `SalesServiceImpl.java`

**验收**:
```bash
mvn test -Dtest="CommissionRuleServicePreviewTest,SalesOrderConfirmedCommissionListenerTest" -pl .
```

**并行**: ✅ 与 T1、T4 独立

---

### 卡T6 → Composer (out-of-harness)

**目标**: 前端含税/未税双列 + 毛利预警 sticky + 提成预览展示

**worktree**:
```bash
git worktree add -b feat/sp5-t6-frontend ../cretas-sp5-t6 origin/main
# 在 web-admin 目录
cd web-admin && npm install --prefer-offline --legacy-peer-deps
```

**文件级改动**:
1. `web-admin/src/views/sales/orders/detail.vue`
   - 订单行表格新增含税/未税双列：
     - 含税单价：`item.unitPrice`（已有列，标题改为"含税单价"）
     - 未税单价：计算 `item.unitPrice / (1 + item.taxRate/100)`，精度 2 位，tax_rate 为 0 时降级显示同含税
   - 下单填价时实时调 `checkMargin()` API (防抖 500ms)
   - 毛利预警展示：`if (checkResult.belowRedline) ElMessage({ type:'warning', message: checkResult.warningMessage, duration: 0, showClose: true })`
   - **不在前端做任何成本计算**，只展示后端返回的 `belowRedline`/`warningMessage`

2. `web-admin/src/views/sales/orders/list.vue`
   - 新增「提成预览」列 `commissionPreview`（仅 factory_super_admin / finance_manager 角色可见，用 `v-if="canViewPrices"`）

3. `web-admin/src/views/sales/finance-review/detail.vue`
   - 展示 `commissionPreview` + `commissionRatePct` 字段

**内联规则摘要** (Composer 无 .claude/rules 上下文，规则自包含在此):

> **fool-proof Rule 1**: 单价输入框下方预警，先显示限制，不事后报错
> **fool-proof 4位一体**: sticky (duration:0+showClose) + 后端真实 message + next action提示 + 文案 = 后端文案
> **API格式**: `{ success: bool, data: { belowRedline: bool, warningMessage: string } }`
> **@PriceSensitive 规则**: commissionPreview 对无价格权限角色返回 null，前端用 `v-if="item.commissionPreview != null"` 判断

**允许改**: `views/sales/orders/detail.vue`, `views/sales/orders/list.vue`, `views/sales/finance-review/detail.vue`

**禁改**: `PriceFieldResponseAdvice.java` 及所有后端文件

**验收**:
```bash
cd web-admin && npm run type-check && npm run build
# 0 type errors, 0 build errors
# headed playwright (可选): 打开订单详情，填低价 → warning sticky 出现
```

**并行**: ❌ 依赖 T2 的 `check-margin` API 可调（建议 T2 PR merged 后再启动 T6）

---

### 卡T7 → Opus 自留 (🔒 终审 + 部署)

**目标**: 终审 T2/T3/T4 红线实现，merge 进 main，部署

**终审清单**:
- [ ] `GrossMarginCheckResult` JSON 序列化无 minPrice/standardCost 字段 (T2)
- [ ] `warningMessage` 文案无任何成本数字 (T2)
- [ ] `costEstimate` null guard 不 NPE (T3)
- [ ] `SalesFinanceApproveVoucherListener` 幂等 + AFTER_COMMIT 隔离 (T4)
- [ ] `check-margin` 端点门控不可被 operator 调用 (T2)
- [ ] 所有新 `@PriceSensitive` 字段在 SalesOrder/FactoryGrossMarginConfig 正确标注 (T1)
- [ ] Flyway 号段无重复 (`git ls-tree origin/main db/flyway | grep V20260910_4 | uniq -d` = 空)

**部署**:
```bash
git checkout main && git pull origin main
./scripts/deploy/deploy-backend.sh --env prod
```

---

## Flyway 查重纪律

每次 PR 前执行：
```bash
git fetch origin
git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway \
  | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
```
输出空 = 无冲突，方可提 PR。

当前已占号：V20260910_01 / _02 / _03

SP5 使用：V20260910_40 / _41 / _42

---

## Scope 锁地图

| 文件 / 目录 | 锁定 task | 预计解锁 |
|---|---|---|
| `entity/inventory/SalesOrder.java` | T1 (仅加字段) | T1 PR 合并后 |
| `entity/ProductionPlan.java` | T1 (仅加 sourceOrderIds) | T1 PR 合并后 |
| `service/inventory/impl/SalesServiceImpl.java` | T3 (仅改 costEstimate 一行) | T3 PR 合并后 |
| `listener/voucher/` | T4 (新建) | T4 PR 合并后 |
| `service/pricing/GrossMarginRedline*` | T2 (新建) | T2 PR 合并后 |
| `views/sales/orders/detail.vue` | T6 | T6 PR 合并后 |
| `views/sales/orders/list.vue` | T6 | T6 PR 合并后 |

**SP5 独占文件**（不得被其他 SP 并发修改）：
- `SalesOrder.java`, `SalesOrderItem.java`（蓝图 §4 scope-lock）
- `PriceFieldResponseAdvice.java`（任何 SP 改此文件需过 Opus 终审）
- `sales/*.vue`（与 SP9 协调，SP9 需要 invoice/payment 的 vue 变更时提前告知）

---

## 依赖等待矩阵

```
origin/main
    ↓
T1 ─────────────────────────────── (独立可启动)
T4 ─────────────────────────────── (独立可启动)
T5 ─────────────────────────────── (独立可启动)
         ↓ T1 merge
T2 ────────────── (需 T1: FactoryGrossMarginConfigRepo)
         ↓ T2 merge
T3 ─────────────────────── (需 T2: GrossMarginRedlineService 编译)
T6 ─────────────────────── (需 T2 API 可用)
                    ↓ T2+T3+T4 PR ready
T7 (Opus 终审) ────────────────────────────── → merge + prod
```

**首批可立即启动**: T1、T4、T5（三个 worktree 同时开）
**第二批**: T2 (等 T1)
**第三批**: T3、T6 (等 T2)
