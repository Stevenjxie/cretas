# SP7 · 仓库管控实施计划

> **关联 spec**: SP7-warehouse-control-spec.md
> **Flyway 号段**: V20260910_60 – V20260910_69
> **波次**: 波 2（依赖 SP4 地基 MaterialBatch.warehouse_id，已 confirmed 存在）
> **模型路由**: Sonnet in-harness（rule-heavy Java backend）；UI/样式→Composer；🔒红线→Opus 终审

---

## 0. Worktree 设置（所有任务共用）

```bash
git worktree add -b feat/sp7-warehouse-control ../cretas-sp7 origin/main
cd ../cretas-sp7
```

**scope-lock 主文件**（派活前查 ACTIVE.md 无冲突）:
- `entity/factory/FactoryWarehouse.java`（加 SALTED 枚举值）
- 新建 `entity/factory/FactoryStocktake.java` + `FactoryStocktakeItem.java`
- 新建 `entity/inventory/WastageReport.java`
- 新建 `service/factory/FactoryStocktakeService.java` + impl
- 新建 `service/inventory/WastageReportService.java` + impl
- 新建 `controller/factory/StocktakeController.java`
- 新建 `controller/inventory/WastageReportController.java`
- `frontend/CretasFoodTrace/src/screens/warehouse/inventory/WHInventoryCheckScreen.tsx`（重接端点）
- `db/flyway/V20260910_60/61/62.sql`

**🔒 禁改（其他子项已锁）**:
- `entity/MaterialBatch.java`（SP4→SP6 串行锁；SP7 只读不加列）
- `entity/inventory/InternalTransfer.java`（SP7 复用，不改）
- `entity/restaurant/StocktakingRecord.java`（餐饮侧，不动）
- `service/impl/IntentExecutorServiceImpl.java`（意图路由，不动）

---

## 1. TDD 任务分解

### Task T1 — Flyway + 基础实体

**模型**: Sonnet in-harness
**effort**: high（规则重：entity 字段必须 4 处全做）

**先写测试（red）**:
```
src/test/java/com/cretas/aims/entity/factory/FactoryWarehouseWarehouseTypeTest.java
  - test: SALTED 枚举值存在
  - test: FactoryStocktake 继承 BaseEntity，字段完整
  - test: FactoryStocktakeItem 关联完整
  - test: WastageReport 双轨枚举正确

src/test/resources/db/migration/V20260910_60-62_test.sql
  - 在 H2 验证迁移脚本无语法错误（启动时 Flyway 自动跑）
```

**实现文件**:
```
db/flyway/V20260910_60__add_salted_warehouse_type.sql
  ALTER TYPE factory_warehouse_type ADD VALUE 'SALTED';

db/flyway/V20260910_61__create_factory_stocktake.sql
  CREATE TABLE factory_stocktakes (...);
  CREATE TABLE factory_stocktake_items (...);
  -- 索引: (factory_id, period_month), (warehouse_id), (status)

db/flyway/V20260910_62__create_wastage_report.sql
  CREATE TABLE wastage_reports (...);
  -- 索引: (factory_id, track_type, status)

src/main/java/com/cretas/aims/entity/factory/FactoryWarehouse.java
  + SALTED 枚举值（含 javadoc）

src/main/java/com/cretas/aims/entity/factory/FactoryStocktake.java
  + 完整字段（见 spec §3.2）
  + 状态枚举（INITIATED/COUNTING/PENDING_APPROVAL/APPROVED/APPLIED/REJECTED）

src/main/java/com/cretas/aims/entity/factory/FactoryStocktakeItem.java
  + 关联 FactoryStocktake
  + system_qty（快照）/actual_qty/difference_qty

src/main/java/com/cretas/aims/entity/inventory/WastageReport.java
  + track_type 枚举（WAREHOUSE/FACTORY）
  + status 枚举（DRAFT/PENDING_APPROVAL/APPROVED/REJECTED/APPLIED）
  + photo_urls（NOT NULL，JSON TEXT）
  + reason 枚举（EXPIRED/DAMAGED/CONTAMINATED/THEFT/OTHER）

src/main/java/com/cretas/aims/dto/factory/CreateStocktakeRequest.java
src/main/java/com/cretas/aims/dto/inventory/CreateWastageReportRequest.java
```

**DTO 往返 4 处检查**（所有新实体必须）:
- [ ] Entity 字段定义
- [ ] create service 方法 set
- [ ] update service 方法 null-guard set
- [ ] convertToDTO map

**验收**:
```bash
./mvnw test -pl backend/java/cretas-api -Dtest="FactoryWarehouseWarehouseTypeTest,FactoryStocktakeTest,WastageReportTest"
# 全部绿
```

---

### Task T2 — StocktakeService（盘点任务服务层）

**模型**: Sonnet in-harness
**effort**: high（月底约束/状态机/apply 原子事务/幂等）

**先写测试（red）**:
```
src/test/java/com/cretas/aims/service/factory/FactoryStocktakeServiceTest.java
  - test_initiate_before_month_end_rejected(): 28日发起 → IllegalStateException "盘点任务只能在月底"
  - test_initiate_on_29th_ok(): 29日发起 → INITIATED 状态
  - test_submit_transitions_to_pending(): submit → PENDING_APPROVAL
  - test_approve_transitions_to_approved(): approve → APPROVED
  - test_apply_writes_adjustments(): apply → MaterialBatchAdjustment x N 行 + status=APPLIED
  - test_apply_idempotent(): 已 APPLIED 再 apply → BusinessException 409
  - test_reject_and_resubmit(): reject → REJECTED → 可重提 PENDING_APPROVAL
  - test_apply_zero_diff_skipped(): 差异为 0 的行不写 adjustment
```

**实现文件**:
```
src/main/java/com/cretas/aims/service/factory/FactoryStocktakeService.java（接口）
src/main/java/com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImpl.java
  - initiate(): 月底检查 + snapshot system_qty（从 MaterialBatch 读）+ INITIATED
  - updateItems(): 批量 upsert items，actualQty + 自动算 differenceQty
  - submit(): COUNTING/INITIATED → PENDING_APPROVAL
  - approve(): PENDING_APPROVAL → APPROVED（角色：FINANCE，用 request attr "role"）
  - apply(): APPROVED → APPLIED，原子写 MaterialBatchAdjustment（悲观锁 FOR UPDATE）
  - previewDiff(): 只读，返回差异预览 DTO

src/main/java/com/cretas/aims/repository/factory/FactoryStocktakeRepository.java
src/main/java/com/cretas/aims/repository/factory/FactoryStocktakeItemRepository.java
```

**关键代码约束**（rule-aware）:
- `apply()` 用 `@Transactional`；内层不能 fail-soft try/catch 吞异常（教训 feedback_failsoft_catch_cannot_save_doomed_tx）
- 角色检查用 `HttpServletRequest.getAttribute("role")` 非 SecurityContext（C1孪生坑）
- 幂等检查：`if (task.getStatus() == APPLIED) throw new BusinessException(409, ...)`

**验收**:
```bash
./mvnw test -Dtest="FactoryStocktakeServiceTest"
# 8 tests green
```

---

### Task T3 — WastageReportService（报损服务层）

**模型**: Sonnet in-harness
**effort**: high（双轨路由/照片强制/approve 写 adjustment）

**先写测试（red）**:
```
src/test/java/com/cretas/aims/service/inventory/WastageReportServiceTest.java
  - test_create_without_photos_fails(): photo_urls 空 → 422 "必须上传照片"
  - test_warehouse_track_requires_finance_approval(): WAREHOUSE 轨用 OPERATOR role approve → 403
  - test_factory_track_requires_factory_manager(): FACTORY 轨用 FINANCE role approve → 403
  - test_warehouse_track_approve_by_finance_ok(): FINANCE 审批 WAREHOUSE 轨 → APPLIED
  - test_factory_track_approve_by_factory_manager_ok(): FACTORY_MANAGER 审批 FACTORY 轨 → APPLIED
  - test_approve_writes_material_batch_adjustment(): approve → MaterialBatchAdjustment 写入
  - test_reject_transitions_to_rejected(): reject → REJECTED
  - test_idempotent_apply(): 已 APPLIED → 409
```

**实现文件**:
```
src/main/java/com/cretas/aims/service/inventory/WastageReportService.java（接口）
src/main/java/com/cretas/aims/service/inventory/impl/WastageReportServiceImpl.java
  - create(): 照片校验 + DRAFT
  - submit(): DRAFT → PENDING_APPROVAL
  - approve(): 双轨路由（WAREHOUSE→FINANCE，FACTORY→FACTORY_MANAGER）
              → APPROVED → 原子写 adjustment + APPLIED
  - reject(): PENDING_APPROVAL → REJECTED
  - listPendingByApproverRole(): 按 track_type + role 过滤待审批

src/main/java/com/cretas/aims/repository/inventory/WastageReportRepository.java
  - findByFactoryIdAndStatusAndTrackType(...)
  - findPendingByApproverRole(role) - JPQL 按 track_type 映射 role
```

**Decimal / null 约束**:
- `wastage_qty` 用 `ROUND_HALF_UP scale-4`（对齐 CostRollupUtil 规范）
- `if (wasteQty == null || wasteQty.compareTo(BigDecimal.ZERO) <= 0)` → 422

**验收**:
```bash
./mvnw test -Dtest="WastageReportServiceTest"
# 8 tests green
```

---

### Task T4 — Controller 层 + WarehouseInventoryGuard

**模型**: Sonnet in-harness
**effort**: high（端点设计/守卫/RBAC）

**先写测试（red）**:
```
src/test/java/com/cretas/aims/controller/factory/StocktakeControllerTest.java（MockMvc）
  - test_initiate_stocktake_201()
  - test_submit_stocktake_200()
  - test_approve_stocktake_as_finance_200()
  - test_approve_stocktake_as_operator_403()

src/test/java/com/cretas/aims/controller/inventory/WastageReportControllerTest.java
  - test_create_wastage_report_201()
  - test_submit_without_photo_422()
  - test_approve_wrong_role_403()

src/test/java/com/cretas/aims/service/factory/WarehouseInventoryGuardServiceTest.java
  - test_production_warehouse_rejects_raw_material()
  - test_production_warehouse_allows_semi_finished()
  - test_raw_warehouse_allows_all()
```

**实现文件**:
```
src/main/java/com/cretas/aims/controller/factory/StocktakeController.java
  @RequestMapping("/api/mobile/{factoryId}/stocktake")
  - POST /          → initiate
  - GET /           → list (分页 + 状态过滤)
  - GET /{id}       → detail + items
  - PUT /{id}/items → updateItems
  - POST /{id}/submit
  - POST /{id}/approve    @RequireRole("FINANCE")
  - POST /{id}/reject     @RequireRole("FINANCE")
  - GET /{id}/diff-preview

src/main/java/com/cretas/aims/controller/inventory/WastageReportController.java
  @RequestMapping("/api/mobile/{factoryId}/wastage-report")
  - POST /
  - GET /
  - GET /{id}
  - POST /{id}/submit
  - POST /{id}/approve    @RequireRole({"FINANCE","FACTORY_MANAGER"})
  - POST /{id}/reject
  - GET /pending

src/main/java/com/cretas/aims/service/factory/WarehouseInventoryGuardService.java
  - validateInbound(warehouseId, materialTypeId): 生产库拒非 SEMI_FINISHED
  + 注入到 WarehouseInventoryController（已存在）的领料/入库路径
```

**验收**:
```bash
./mvnw test -Dtest="StocktakeControllerTest,WastageReportControllerTest,WarehouseInventoryGuardServiceTest"
# 全部绿
./mvnw clean package -DskipTests   # 构建通过
```

---

### Task T5 — RN WHInventoryCheckScreen 重接状态机

**模型**: Composer 2.5（UI改接端点，无 Java 规则依赖）
**effort**: default

**先检查现状（已读）**: WHInventoryCheckScreen.tsx 573 行，含 TODO `materialBatchApiClient.adjust`。

**实现文件**:
```
frontend/CretasFoodTrace/src/screens/warehouse/inventory/WHInventoryCheckScreen.tsx
  改动点:
  1. 移除对 materialBatchApiClient.adjust 的直接调用
  2. 增加 stocktakeApiClient（新建或在 materialBatchApiClient 中扩展）
  3. 页面改为：
     a. 进入时 GET /stocktake?status=COUNTING 查询当前盘点任务
     b. 若无活跃任务 → 显示"本月无盘点任务，请等待仓库主管发起"（fool-proof Rule 5）
     c. 有任务 → 显示账面数量（大号字体，系统快照）
     d. 填写实盘数量 → 实时差异计算（红/绿）
     e. 提交走 POST /stocktake/{id}/submit（需财务批才生效，不直接改库存）
  4. 差异行显示"注：盘亏将记录您的工号，请确认数据正确"（Rule 6 / 追责）

frontend/CretasFoodTrace/src/screens/warehouse/inventory/WHWastageReportScreen.tsx（新建）
  - 仓管报损入口
  - 扫码/搜索批次 → 回填品名+最大可报损量
  - 数量输入 keyboardType="numeric" fontSize:24
  - Picker 原因（EXPIRED/DAMAGED/CONTAMINATED/THEFT/OTHER）
  - 照片区（红框必填，使用 ImagePicker/Camera）
  - 提交按钮 disabled when photos.length < 1
  - 提交 → POST /wastage-report

frontend/CretasFoodTrace/src/services/api/stocktakeApiClient.ts（新建）
frontend/CretasFoodTrace/src/services/api/wastageReportApiClient.ts（新建）
```

**UX 约束（ux-flow gate 落地）**:
- 触摸目标 ≥ 44×44pt（TouchableRipple）
- 数量输入 fontSize: 24
- 账面数量前置大号显示
- 差异色标：盘盈绿色，盘亏红色
- 提交弹窗含差异汇总
- 照片区 disabled 提交 + 红色提示

**验收**:
```bash
npx tsc --noEmit   # 在 frontend/CretasFoodTrace 下运行，无类型错误
```

---

### Task T6 — 🔒 Opus 终审（红线守卫验证）

**模型**: Opus（本体，终审专用）
**effort**: xhigh
**orchestration**: 单 subagent read-only diff

**终审 checklist**（Opus 执行）:

```bash
# 1. 远端 PR diff 验证（教训：gate 必须验远端非本地 worktree）
gh pr diff <PR_NUMBER>

# 2. scope 干净检查
git diff origin/main...HEAD --stat   # 只有 SP7 文件，无 sister 夹带

# 3. 红线守卫验证
grep -rn "materialBatchRepository.save\|quantityField" \
  backend/java/cretas-api/src/main/java --include="*.java" | \
  grep -v "materialBatchAdjustment\|StocktakeService\|WastageReport" | \
  head -20
# 应无绕过 audit 的直接 save

# 4. 角色检查方式验证（无 SecurityContext）
grep -rn "SecurityContextHolder\|SecurityUtils.hasAnyRole" \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImpl.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/WastageReportServiceImpl.java
# 应无命中（正确方式是 request.getAttribute("role")）

# 5. 照片强制
grep -rn "photo_urls\|photos.length" \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/WastageReportServiceImpl.java
# 应有 isEmpty/size<1 检查

# 6. 幂等检查
grep -rn "APPLIED.*BusinessException\|409" \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/factory/impl/FactoryStocktakeServiceImpl.java
# 应有幂等防重

# 7. Flyway 号段查重（merge 后必查）
git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
# 应无重复

# 8. 运行 jar 含修复（部署后）
ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar \
  'BOOT-INF/classes/com/cretas/aims/entity/factory/FactoryStocktake.class' > /dev/null 2>&1 && echo OK || echo MISSING"
```

**Opus 终审通过条件**（全部满足才 merge + 部署）:
- [ ] scope 干净（无 sister 文件）
- [ ] 无 `materialBatchRepository.save()` 绕过 audit
- [ ] 无 `SecurityContextHolder` 用于角色检查
- [ ] 照片强制校验存在
- [ ] 幂等防重存在
- [ ] Flyway 号段无重复
- [ ] 所有测试通过（mvnw test + tsc）

---

## 2. 任务依赖图

```
T1 (实体+Flyway) ─────┬──→ T2 (StocktakeService)  ──┬──→ T4 (Controller+Guard)
                      └──→ T3 (WastageService)    ──┘
                                                       ↓
                      T5 (RN UI, 并行)                T6 (🔒 Opus终审, 串行最后)
```

T1 → T2, T3 串行（T2/T3 依赖实体）
T2 + T3 完成后 → T4 开工
T5 与 T2/T3/T4 可**并行**（UI 不依赖 Java service，依赖端点路径约定）
T6 必须 T1-T5 全 green 后才开（终审）

---

## 3. 分发总览

| # | 任务 | 推荐模型 | effort | orchestration | 分支 | scope 锁 | 状态 |
|---|---|---|---|---|---|---|---|
| T1 | Flyway + 基础实体 | Sonnet in-harness | high | inline | feat/sp7 | 新实体文件 + Flyway | pending |
| T2 | StocktakeService | Sonnet in-harness | high | inline | feat/sp7 | FactoryStocktakeService* | pending (after T1) |
| T3 | WastageReportService | Sonnet in-harness | high | inline | feat/sp7 | WastageReportService* | pending (after T1) |
| T4 | Controller+Guard | Sonnet in-harness | high | inline | feat/sp7 | Controller 文件 | pending (after T2+T3) |
| T5 | RN UI | Composer 2.5 | default | inline | feat/sp7 | WHInventoryCheckScreen + 新 WHWastageReport | pending (parallel T2-T4) |
| T6 | 🔒 Opus 终审 | Opus(本体) | xhigh | 单 subagent read-only | main | — | pending (after T1-T5 all green) |

---

## 卡 T1 → 贴给 Sonnet in-harness

**目标**: SP7 Flyway 迁移 + 基础实体建立，按 spec §3.1-3.3。
**worktree**: `git worktree add -b feat/sp7-warehouse-control ../cretas-sp7 origin/main`
**允许改**: `db/flyway/V20260910_60-62*.sql` + 新建 `entity/factory/FactoryStocktake*.java` + 新建 `entity/inventory/WastageReport.java` + `entity/factory/FactoryWarehouse.java`（加 SALTED 枚举值）+ 对应 DTO + 对应 Repository
**禁改**: `entity/MaterialBatch.java`（SP4/SP6 锁）; `entity/inventory/InternalTransfer.java`; 任何 SP1-6 文件
**验收**: `./mvnw test -Dtest="FactoryWarehouseWarehouseTypeTest,FactoryStocktakeTest,WastageReportTest"` 全绿
**规则提示（out-of-harness 若派到 Codex 必内联）**:
- entity 加字段必做全 4 处（Entity+create+update+convertToDTO），见 `.claude/rules/database-entity-sync.md`
- 继承 BaseEntity 必有 `created_at/updated_at/deleted_at`
- Flyway 号段：V20260910_60/61/62，只用自己号段，PostgreSQL 用 `ALTER TYPE ... ADD VALUE 'SALTED'`
- 禁止 `SecurityContextHolder`，角色检查用 `request.getAttribute("role")`

---

## 卡 T2 → 贴给 Sonnet in-harness（接 T1）

**目标**: FactoryStocktakeService 状态机实现 + TDD
**允许改**: `service/factory/FactoryStocktakeService*.java` + `repository/factory/FactoryStocktakeRepository.java` + tests
**禁改**: 同 T1
**关键约束**:
- `apply()` 用 `@Transactional`；null 安全不抛（教训 feedback_failsoft_catch_cannot_save_doomed_tx）
- 月底约束：`LocalDate.now().getDayOfMonth() < 29` → 拒绝
- 幂等：已 APPLIED → 409 "已于 {appliedAt} 生效，请勿重复操作"
- 角色用 `request.getAttribute("role")` 非 SecurityContext
**验收**: `./mvnw test -Dtest="FactoryStocktakeServiceTest"` 8 tests green

---

## 卡 T3 → 贴给 Sonnet in-harness（接 T1，与 T2 并行）

**目标**: WastageReportService 双轨报损 + TDD
**允许改**: `service/inventory/WastageReportService*.java` + `repository/inventory/WastageReportRepository.java` + tests
**禁改**: 同 T1
**关键约束**:
- 照片强制：创建时 `photoUrls.isEmpty() → 422 "必须上传至少一张照片"`
- 双轨路由：WAREHOUSE track → 只有 FINANCE 角色可 approve；FACTORY track → 只有 FACTORY_MANAGER
- approve 后原子写 `MaterialBatchAdjustment`（type=WASTAGE, 负数 adjustment）
- Decimal 精度 scale-4 ROUND_HALF_UP
**验收**: `./mvnw test -Dtest="WastageReportServiceTest"` 8 tests green

---

## 卡 T4 → 贴给 Sonnet in-harness（接 T2+T3）

**目标**: Controller 层 + 生产库隔离守卫
**允许改**: 新建 `controller/factory/StocktakeController.java` + `controller/inventory/WastageReportController.java` + `service/factory/WarehouseInventoryGuardService.java` + 注入到现有 `WarehouseInventoryController.java`
**验收**: `./mvnw test -Dtest="StocktakeControllerTest,WastageReportControllerTest,WarehouseInventoryGuardServiceTest"` + `./mvnw clean package -DskipTests`

---

## 卡 T5 → 贴给 Composer 2.5（与 T2-T4 并行）

**目标**: RN WHInventoryCheckScreen 重接盘点状态机端点 + 新建 WHWastageReportScreen
**允许改**: `frontend/CretasFoodTrace/src/screens/warehouse/inventory/WHInventoryCheckScreen.tsx` + 新建 `WHWastageReportScreen.tsx` + 新建 API clients
**规则（自包含）**:
- 触摸目标 ≥ 44×44pt（TouchableRipple）；数量 fontSize: 24；keyboardType="numeric"
- 照片区：`photos.length < 1` 时 Button disabled
- 不调 `materialBatchApiClient.adjust`（移除 TODO 调用）
- 账面数量展示要大号，差异实时计算红/绿色
- 提交弹窗含差异汇总 + 追责说明（盘亏行）
**验收**: `npx tsc --noEmit` 无错

---

## 4. 接受标准（全部满足才请 Opus 终审）

- [ ] `./mvnw test` 全部通过（含 T1-T4 新增 ~24 测试）
- [ ] `./mvnw clean package -DskipTests` 构建成功
- [ ] `npx tsc --noEmit`（frontend/CretasFoodTrace）无错
- [ ] Flyway V20260910_60/61/62 在启动时自动 apply，无报错
- [ ] `WHInventoryCheckScreen.tsx` 无 `materialBatchApiClient.adjust` 直接调用
- [ ] `WastageReportServiceImpl` 照片校验 + 双轨路由 + audit 写入存在
- [ ] `FactoryStocktakeServiceImpl` 幂等 + 月底约束 + apply 原子事务存在
- [ ] `git diff origin/main...HEAD --stat` 只包含 SP7 scope 文件（无 sister 夹带）

---

## 5. Flyway 排号纪律

```bash
# T1 开工前和 merge 前各查一次
git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
# 应无重复

# merge 后部署前再查
git ls-tree origin/main db/flyway | grep -oE 'V20260910_6[0-9]' | sort
# 应只有 60/61/62
```

**SP7 号段 V20260910_6x 预留**：其他子项禁止使用此号段（ACTIVE.md 已标注）。

---

## 6. commit 规范

```bash
# 并发安全 commit（只 commit SP7 文件，防 husky 夹带其他 session 文件）
git commit -m "feat(sp7): add SALTED warehouse type + FactoryStocktake/WastageReport entities" \
  -- db/flyway/V20260910_60__add_salted_warehouse_type.sql \
     db/flyway/V20260910_61__create_factory_stocktake.sql \
     db/flyway/V20260910_62__create_wastage_report.sql \
     backend/java/cretas-api/src/main/java/com/cretas/aims/entity/factory/FactoryWarehouse.java \
     backend/java/cretas-api/src/main/java/com/cretas/aims/entity/factory/FactoryStocktake.java \
     backend/java/cretas-api/src/main/java/com/cretas/aims/entity/factory/FactoryStocktakeItem.java \
     backend/java/cretas-api/src/main/java/com/cretas/aims/entity/inventory/WastageReport.java
```

---

## 7. 部署（🔒 Opus 终审后执行）

```bash
# 只从 main 部署 prod
git checkout main && git pull origin main
./scripts/deploy/deploy-backend.sh --env prod

# 部署后核对 jar 含修复
ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar \
  'BOOT-INF/classes/com/cretas/aims/entity/factory/FactoryStocktake.class' \
  > /dev/null 2>&1 && echo 'SP7 entities: OK' || echo 'SP7 entities: MISSING'"
```
