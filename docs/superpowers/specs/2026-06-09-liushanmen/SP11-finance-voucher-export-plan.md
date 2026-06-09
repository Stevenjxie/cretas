# SP11 · 财务凭证表导出+进销存报表 — 实施计划

**子项**: SP11  
**波次**: Wave 4（SP6+SP7 merge 到 main 后开始）  
**Flyway 号段**: V20260911_30 ~ V20260911_39（独占）  
**模型路由**: 规则重后端 → Sonnet in-harness；UI/样式 → Composer；🔒红线 → Opus 终审  
**Fleet 现状**: Codex/GPT 暂停。CLI/E2E/构建 回 Sonnet in-harness；纯 UI 出 Composer 卡。  
**生成**: 2026-06-09 Sonnet in-harness（经 Opus organizer 脊梁约束）

---

## 前置条件检查（开工前 Sonnet 执行）

```bash
# 1. 确认 SP6+SP7 已 merge 到 origin/main
git ls-remote origin main | head -1
git log origin/main --oneline | grep -E "SP6|SP7|settlement_type|stocktake" | head -5

# 2. Flyway 号段 dedup 检查（必须 0 输出）
git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d

# 3. 确认 Apache POI 依赖已在 pom.xml
grep -n "poi-ooxml" backend/java/cretas-api/pom.xml

# 4. 开工时创建 worktree
git worktree add -b feat/sp11-finance-export ../cretas-sp11 origin/main
```

---

## 任务分解（TDD 分阶段）

---

### 阶段 A：数据层 + 进销存服务（后端，纯 Java）

**派发给**: Sonnet in-harness（rule-heavy Java，需 `.claude/rules/database-entity-sync.md` + `fool-proof-design.md`）

---

#### Task A1：新建 3 个实体 + Flyway 迁移

**目标**: `VoucherExportConfig` / `VoucherSubjectMapping` / `InventoryLedgerSnapshot` + SettlementType 枚举  
**worktree**: `../cretas-sp11` (off origin/main)  
**允许改**:
```
db/flyway/V20260911_30__add_voucher_export_config.sql
db/flyway/V20260911_31__add_voucher_subject_mapping.sql
db/flyway/V20260911_32__add_inventory_ledger_snapshot.sql
backend/java/cretas-api/src/main/java/com/cretas/aims/entity/finance/VoucherExportConfig.java
backend/java/cretas-api/src/main/java/com/cretas/aims/entity/finance/VoucherSubjectMapping.java
backend/java/cretas-api/src/main/java/com/cretas/aims/entity/inventory/InventoryLedgerSnapshot.java
backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/SettlementType.java (新建，若SP6未提供)
```
**禁改**: `purchase_orders` 表 DDL（SP6 领域）；任何现有 Flyway 文件  
**先写测试（TDD）**:
```java
// VoucherExportConfigRepositoryTest
// - 保存一条 factoryId="F006", targetSystem=KINGDEE → 能查回
// - 同 factory+targetSystem 第二条 → DataIntegrityViolationException
// InventoryLedgerSnapshotRepositoryTest
// - 保存快照 → 能按 factoryId+periodId+materialTypeId 查回唯一
```
**验收**:
```bash
cd backend/java/cretas-api && mvn test -Dtest="VoucherExportConfigRepositoryTest,VoucherSubjectMappingRepositoryTest,InventoryLedgerSnapshotRepositoryTest" -q
# 期望: BUILD SUCCESS，3 test classes pass
```
**🔒 收尾约束**: 只做到 PR off origin/main；不部署 prod

---

#### Task A2：InventoryLedgerService 进销存聚合逻辑

**目标**: 实现 `InventoryLedgerService.getLedger(factoryId, startDate, endDate, materialTypeId)` — 返回 `List<InventoryLedgerDTO>` 含期初/入库/出库/调拨/盘盈损/期末  
**依赖**: A1 完成（实体存在）  
**允许改**:
```
backend/.../service/inventory/InventoryLedgerService.java (interface, 新建)
backend/.../service/inventory/impl/InventoryLedgerServiceImpl.java
backend/.../dto/inventory/InventoryLedgerDTO.java
backend/.../dto/inventory/InventoryLedgerLineDTO.java
backend/.../repository/InventoryLedgerSnapshotRepository.java
```
**禁改**: `MaterialBatch` / `PurchaseReceiveRecord` 等现有实体字段  
**先写测试（TDD）**:
```java
// InventoryLedgerServiceTest
// testGetLedger_withSnapshot_useSnapshotAsOpening():
//   - 插入 InventoryLedgerSnapshot for period 5 末 → 期初 = 快照值
//   - 插入 3 PurchaseReceiveItem 入库 100kg = 200kg = 50kg（共 350kg）
//   - 插入 1 SalesDeliveryItem 出库 100kg
//   - 期末 = 快照qty + 350 - 100
//
// testGetLedger_noSnapshot_aggregateFromBeginning():
//   - 无快照 → 聚合全量 MaterialBatch 在 startDate 前的净入库作为期初
//
// testGetLedger_amountHalfUpPrecision():
//   - unitPrice = 3.3333(4位), qty = 3.000000 → amount = 9.9999 → ROUND_HALF_UP scale-2 → 10.00
//   - 断言 closingAmount == 10.00
//
// testGetLedger_priceSensitive_amountNullForWarehouseRole():
//   - PriceSensitiveContext.setRole(WAREHOUSE_MANAGER) → DTO 中 openingAmount 为 null
```
**验收**:
```bash
mvn test -Dtest="InventoryLedgerServiceTest" -q
# 期望: ≥ 4 test methods pass
```
**🔒 收尾约束**: 只做到 PR；不部署

---

#### Task A3：VoucherSubjectMappingService CRUD

**目标**: Service + Repository 实现结算属性→科目映射 CRUD；含 per-factory 初始化（首次查询时从 `__default__` 种子 copy）  
**允许改**:
```
backend/.../service/finance/VoucherSubjectMappingService.java
backend/.../service/finance/impl/VoucherSubjectMappingServiceImpl.java
backend/.../repository/VoucherSubjectMappingRepository.java
backend/.../dto/finance/VoucherSubjectMappingDTO.java
```
**先写测试**:
```java
// VoucherSubjectMappingServiceTest
// testGetMappings_noPerFactoryConfig_returnsDefaultSeedCopy():
//   - 空 DB → 调 getOrInit(factoryId) → 返回 6 条（从 __default__ 复制）
//   - 再次调用 → 不再插入（幂等）
//
// testGetMapping_bySettlementType():
//   - 配置 PREPAY → 查 PREPAY → 返回正确借贷科目
//
// testUpdateMapping_nullGuard():
//   - update 时 debitSubjectCode = null → 保留原值（null-guard）
```
**验收**:
```bash
mvn test -Dtest="VoucherSubjectMappingServiceTest" -q
```

---

### 阶段 B：凭证导出服务（后端，依赖阶段 A）

**派发给**: Sonnet in-harness

---

#### Task B1：VoucherExportService 序时账 + 科目余额表

**目标**: 实现 `exportSequentialLedger()` + `exportSubjectBalance()` 两个方法；生成 `.xlsx`；列名从 `VoucherExportConfig` 读取  
**前置**: A1 完成；pom.xml 含 `poi-ooxml`  
**允许改**:
```
backend/.../service/finance/VoucherExportService.java
backend/.../service/finance/impl/VoucherExportServiceImpl.java
backend/java/cretas-api/pom.xml (如需添加 poi-ooxml)
```
**先写测试**:
```java
// VoucherExportServiceTest
// testExportSequentialLedger_columnNamesFromConfig():
//   - 插 VoucherExportConfig(col_debit="借方发生额")
//   - 插 2 个 Voucher + entries
//   - 调 exportSequentialLedger → byte[]
//   - Apache POI 读 xlsx: row[0].cell[5].stringCellValue = "借方发生额"
//
// testExportSequentialLedger_emptyRange_returnsHeaderOnly():
//   - 无凭证 → xlsx 有表头行，数据行 = 0；不抛异常
//
// testExportSubjectBalance_aggregatesCorrectly():
//   - 插 VoucherEntry debit=1000, credit=500 for subjectCode="1405"
//   - exportSubjectBalance → xlsx debit cell = 1000, credit cell = 500
//
// testExport_priceSensitive_notLeakedInWorkerThread():
//   - 异步导出线程中 PriceSensitiveContext must carry finance role → 金额不为 null
//   - WAREHOUSE_MANAGER 发起导出 → 金额列为空字符串（红线 R2）
```
**验收**:
```bash
mvn test -Dtest="VoucherExportServiceTest" -q
# 期望: ≥ 4 test methods pass
```

---

#### Task B2：InventoryLedger + VoucherExport Controller

**目标**: REST 端点 + DTO 校验 + 导出响应头  
**允许改**:
```
backend/.../controller/inventory/InventoryLedgerController.java
backend/.../controller/finance/VoucherExportController.java
backend/.../controller/finance/VoucherSubjectMappingController.java
backend/.../controller/finance/VoucherExportConfigController.java
backend/.../dto/inventory/InventoryLedgerQueryDTO.java
backend/.../dto/finance/VoucherExportRequestDTO.java
```
**先写测试**:
```java
// InventoryLedgerControllerTest (MockMvc)
// - GET /inventory/ledger?startDate=2026-06-01&endDate=2026-06-30 → 200 success
// - 无 startDate → 400 validation error
// - WAREHOUSE_MANAGER token → 200 但金额字段 null
//
// VoucherExportControllerTest
// - POST /finance/voucher-export with FINANCE role → 200, Content-Type xlsx
// - POST without FINANCE role → 403
// - 同参数 5min 内二次 POST → 409 + existingExportRecord
```
**验收**:
```bash
mvn test -Dtest="InventoryLedgerControllerTest,VoucherExportControllerTest" -q
```

---

### 阶段 C：Web Admin 前端

**派发给**: Composer 2.5（纯 Vue UI；可独立并行于阶段 B 的 Controller，但需 mock API）

**卡 C1 → 贴给 Composer 2.5**

---

**目标**: 进销存报表页 + 凭证导出页 + 科目映射配置页（3 个 Vue 页面）  
**worktree**: 同 `../cretas-sp11`（与阶段 B 并行，但 scope 不重叠——B 改 backend，C 改 web-admin）  
**允许改**:
```
web-admin/src/views/finance/InventoryLedger.vue  (新建)
web-admin/src/views/finance/VoucherExport.vue    (新建)
web-admin/src/views/finance/SubjectMapping.vue   (新建)
web-admin/src/router/finance.ts                   (加路由)
web-admin/src/api/finance.ts                      (加 API 调用)
```
**禁改**: 任何 backend 文件；任何现有 Vue 页面  

**InventoryLedger.vue 规格**:
- 筛选栏：日期范围 picker（必填）+ 物料名称/编码搜索（选填）+ 查询按钮
- 表格列：物料编码 | 物料名称 | 单位 | 期初数量 | 入库数量 | 出库数量 | 调拨 | 盘盈损 | 期末数量 | 期末金额（财务角色可见，仓管显示"—"）
- 工具栏：导出 Excel 按钮（带 loading 状态；成功后 `<a download>` 触发）
- **fool-proof Rule 1**: 日期必填，空时查询按钮 disabled；提示"请选择时间范围"
- **fool-proof Rule 5**: 空数据状态 `<EmptyState description="所选时间段无出入库记录" action-text="查看凭证列表" />`

**VoucherExport.vue 规格**:
- Tab 1: 凭证序时账导出（选日期 + 目标系统 KINGDEE/YONYOU + 导出按钮）
- Tab 2: 科目余额表导出（选会计期间 dropdown + 导出按钮）
- 两个导出按钮共享 loading 状态（防双击，Rule 4）
- 导出成功：`ElMessage({ message: '导出成功', type: 'success', duration: 3000 })`
- 导出失败（409）：`ElMessageBox.confirm("已有此时段导出记录，是否下载？")` + 下载链接

**SubjectMapping.vue 规格**:
- 表格：结算属性 | 业务类型 | 借方科目编码 | 借方科目名称 | 贷方科目编码 | 贷方科目名称 | 备注 | 操作
- 行内编辑（el-table 行内 el-input，保存/取消按钮）
- 新增行按钮（底部），保存校验：借贷科目不得为空
- **fool-proof Rule 2**: 编辑 dialog 标题显示"编辑 [结算属性名称] 科目映射"

**验收（Composer 完成后）**:
```bash
cd web-admin && npm run build  # TypeScript type check + vite build 无报错
# 目测验收（截图为证）：
# 1. InventoryLedger 页面：日期空时查询禁用；有数据时表格正常渲染
# 2. VoucherExport 页面：两个 Tab 切换正常；导出按钮 loading 状态
# 3. SubjectMapping 页面：6 行初始数据；行内编辑保存/取消
```
**🔒 收尾约束**: 只做到 PR；Opus 终审财务角色数据可见性（红线 R1/R2）

---

### 阶段 D：月结触发快照 + 集成收尾（后端，依赖阶段 A+B）

**派发给**: Sonnet in-harness

---

#### Task D1：MonthCloseServiceImpl 触发 InventoryLedgerSnapshot

**目标**: 在月结完成时（`AccountingPeriodServiceImpl.closeCurrentPeriod()`）追加写入 `InventoryLedgerSnapshot`  
**允许改**:
```
backend/.../service/finance/impl/AccountingPeriodServiceImpl.java (末尾加 snapshot trigger)
backend/.../service/inventory/InventoryLedgerSnapshotService.java
backend/.../service/inventory/impl/InventoryLedgerSnapshotServiceImpl.java
```
**禁改**: `MonthCloseServiceImpl` 的核心关账逻辑（不改现有月结流程，只**追加** hook）  
**先写测试**:
```java
// InventoryLedgerSnapshotServiceTest
// testCreateSnapshot_afterMonthClose():
//   - mock AccountingPeriod closed for Jun 2026
//   - 插 3 种物料的 MaterialBatch
//   - 调 snapshotService.createForPeriod(factoryId, period)
//   - 断言 3 条快照记录存在，closingQty 正确
//
// testCreateSnapshot_idempotent():
//   - 二次调用 createForPeriod → 不重复插入（UPSERT）
```

---

#### Task D2：完整 Java Build + Test

```bash
cd backend/java/cretas-api
mvn clean test -q 2>&1 | tail -20
# 期望: BUILD SUCCESS；新增测试 ≥ 20 个
```

---

## 分发总览

| # | 任务 | 推荐模型 | 可否并行 | 分支 | 🔒红线 |
|---|---|---|---|---|---|
| A1 | 3 实体 + Flyway 迁移 | Sonnet in-harness | ✅ 独立 | feat/sp11-finance-export | 🔒 |
| A2 | InventoryLedgerService | Sonnet in-harness | ❌ 依赖 A1 | 同上 | 🔒 |
| A3 | VoucherSubjectMappingService | Sonnet in-harness | ✅ 与 A2 并行 | 同上 | |
| B1 | VoucherExportService (xlsx) | Sonnet in-harness | ❌ 依赖 A1 | 同上 | 🔒 |
| B2 | Controllers | Sonnet in-harness | ❌ 依赖 A2+A3+B1 | 同上 | 🔒 |
| C1 | 3 Vue 页面 | Composer 2.5 | ✅ 与 B 并行（mock API） | 同上（web-admin 子目录） | 🔒 |
| D1 | MonthClose→Snapshot hook | Sonnet in-harness | ❌ 依赖 A1+A2 | 同上 | 🔒 |
| D2 | 全量 Build + Test | Sonnet in-harness | ❌ 依赖全部 | 同上 | |

---

## Sonnet in-harness Brief 模板（各任务复用）

```markdown
## 卡 Ax/Bx/Dx → 贴给 Sonnet in-harness

**目标**: [具体任务目标]
**worktree**: git worktree add -b feat/sp11-finance-export ../cretas-sp11 origin/main
**规则上下文**: .claude/rules 自动可见（database-entity-sync.md + fool-proof-design.md + concurrent-edit-safety.md）
**关键规则提醒**:
- 新实体继承 BaseEntity → 表必须有 created_at/updated_at/deleted_at
- DTO 4位一体：Entity 字段 + create set + update null-guard + convertToDTO map
- BigDecimal 精度：qty scale-6，unitPrice scale-4，amount scale-2，ROUND_HALF_UP
- @PriceSensitive 标注 amount/unitPrice 字段（参见 PriceSensitiveContext）
- Flyway 文件名必须在 V20260911_30~39 范围内
**允许改**: [见各任务]
**禁改**: [见各任务]
**验收**: [见各任务测试命令]
**⛔ 收尾约束**: 只做到 PR off origin/main；不部署 prod；Opus 终审红线项
**交接**: 完成 → PR → `git diff origin/main...HEAD --stat` 确认 scope 干净
```

---

## Composer 2.5 Brief：卡 C1（自包含）

```markdown
## 卡 C1 → 贴给 Composer 2.5

**目标**: SP11 财务模块 3 个 Vue 页面（进销存报表、凭证导出、科目映射配置）
**项目**: web-admin (Vue 3 + Element Plus + TypeScript)
**worktree**: 使用已存在的 ../cretas-sp11；进入 web-admin 子目录工作
**代码参考**: 参考 web-admin/src/views/finance/VoucherDetail.vue 的组件风格和 API 调用方式

**允许改**:
  web-admin/src/views/finance/InventoryLedger.vue  (新建)
  web-admin/src/views/finance/VoucherExport.vue    (新建)
  web-admin/src/views/finance/SubjectMapping.vue   (新建)
  web-admin/src/router/finance.ts                  (加 3 条路由)
  web-admin/src/api/finance.ts                     (加 API 函数)
**禁改**: 任何 backend 文件；任何现有 Vue 页面；任何现有路由文件的其他内容

**API 约定（后端尚未上线，用 mock 先）**:
  GET  /api/mobile/{factoryId}/inventory/ledger?startDate=&endDate=&materialTypeId=
  POST /api/mobile/{factoryId}/finance/voucher-export (body: { startDate, endDate, targetSystem })
  GET  /api/mobile/{factoryId}/finance/subject-balance/export?periodId=
  GET/POST/PUT/DELETE /api/mobile/{factoryId}/finance/subject-mappings

**fool-proof 必须实现的规则**（按照客户"仓管文化素质低"的设计原则）:
  Rule 1 (max/必填): 日期范围未填 → 查询/导出按钮 disabled
  Rule 2 (context): 编辑科目映射时 dialog 标题显示"编辑 [结算属性名称] 科目映射"
  Rule 4 (幂等): 导出按钮 loading 状态防重复点击
  Rule 5 (dead-end): 无数据时 EmptyState 带"查看凭证列表"跳转按钮
  4位一体 error toast: duration:0, showClose:true, 含 next action 文字

**财务角色可见性**:
  - 进销存表格"期末金额"列：若 API 返回 null → 显示"—"；tooltip "需要财务权限"
  - 导出按钮：仓管角色隐藏含金额的导出；仅财务/超管角色可见

**验收**:
  cd web-admin && npm run build   # 无 TypeScript 错误，无 vite 构建报错
  # 截图 3 张：InventoryLedger 页 + VoucherExport 页 Tab 切换 + SubjectMapping 编辑行

**⛔ 收尾约束**: 只做到 PR off origin/main；不部署 prod；Opus 终审 R1/R2 财务数据可见性
**交接**: 完成 → PR → git diff origin/main...HEAD --stat 确认只含 web-admin 下 6 个文件
```

---

## Flyway 号段查重纪律

**每次 merge PR 前（不是 PR 创建时）必执行**:

```bash
# 在本地执行（fetch 最新 origin/main 后）
git fetch origin main
git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
# 期望输出: 空（无重复）
# 若有输出: 停止！找到重复的未 apply 迁移文件，重编号后再 merge
```

SP11 已用号：
- `V20260911_30` — voucher_export_configs
- `V20260911_31` — voucher_subject_mappings  
- `V20260911_32` — inventory_ledger_snapshots

预留 `V20260911_33~39` 备用（若后续需要种子数据迁移或表修改）。

---

## 交接协议

```text
Opus 出 SP11 spec+plan
    ↓ (Steve 分发 brief 卡)
Sonnet 阶段 A (实体+服务)
    ‖ (并行)
Composer 阶段 C (Vue 页面，mock API)
    ↓ A 完成后
Sonnet 阶段 B (Controller)
    ↓
Sonnet 阶段 D (MonthClose hook + 全量 test)
    ↓
PR off origin/main → git diff origin/main...HEAD --stat 确认 scope 干净
    ↓
Opus 终审 diff（🔒 R1 @PriceSensitive / R2 导出 xlsx 无旁路 / R3 Flyway 无碰撞）
    ↓
merge 进 main → Opus 从 main 部署 prod
```

---

## 阶段验收证据清单

| 阶段 | 证据 |
|---|---|
| A1 | `mvn test -Dtest="...RepositoryTest"` BUILD SUCCESS |
| A2 | `mvn test -Dtest="InventoryLedgerServiceTest"` ≥ 4 pass |
| A3 | `mvn test -Dtest="VoucherSubjectMappingServiceTest"` ≥ 3 pass |
| B1 | `mvn test -Dtest="VoucherExportServiceTest"` ≥ 4 pass；xlsx 文件可用 POI 打开读列名 |
| B2 | `mvn test -Dtest="...ControllerTest"` ≥ 5 pass；403 角色拦截测试 pass |
| C1 | `npm run build` 无报错；3 张 UI 截图（headed 模式） |
| D1 | `mvn test -Dtest="InventoryLedgerSnapshotServiceTest"` ≥ 2 pass |
| D2 | `mvn clean test -q` 全量 BUILD SUCCESS；新增测试 ≥ 20 |
| Opus 终审 | `grep -rn "@PriceSensitive" InventoryLedgerDTO.java` ≥ 3 hits；xlsx 导出测试中 WAREHOUSE_MANAGER 金额列 = 空 |
