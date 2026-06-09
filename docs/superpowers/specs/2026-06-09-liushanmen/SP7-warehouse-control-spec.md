# SP7 · 仓库管控设计规范

> **归属**: 六扇门 ERP-lite · 蓝图 §3.4 执行子项。Flyway 号段: `V20260910_60` – `V20260910_69`。
> **生成**: 2026-06-09 Sonnet in-harness。
> **红线**: §8 逐字引自蓝图 §3.4，Opus 终审不可改。

---

## 1. 目标与范围

### 1.1 目标

实现"仓管零自主权"红线：所有库存出入/盘亏盈/报损**必须有单据来源 + 经审批后才动数据**。
- 补建仓库盘点任务状态机（月底发起→财务批→生效）
- 补建报损单双轨（仓库报损→财务批；工厂报损→厂长批）+ 照片强制
- 添加 SALTED（盐化）仓库类型
- 进销存台账（F10）移至 SP11，本子项只收口端点+服务层占位

### 1.2 功能覆盖（对应 F 流）

| F 流 | 内容 | 本子项处理 |
|---|---|---|
| F1 | 四仓体系+生产库零库存 | 加 SALTED 类型；生产库强制隔离守卫 |
| F2 | 扫码入库 | 已存在(WHScanOperationScreen)，minor 调整 |
| F3 | 收货异常 | 已存在(PurchaseReceiveRecord)，超收/少收 SP6 范围 |
| F4 | 入库有单据 | 守卫：无单据来源→拒绝(新 StocktakeService) |
| F5 | 报损双轨 | **新建** WastageReport 实体 + 双轨审批 |
| F6 | 盘点任务 | **新建** FactoryStocktake 实体 + 状态机 |
| F7 | 调拨 | 已存在(InternalTransfer 完整状态机)，minor fix |
| F8 | 退料公式 | 退料=(领用-实耗-损耗)，占位端点 |
| F9 | 库存预警 | 已存在(InventoryAlertScreen)，补采购通知 |
| F10 | 进销存报表 | **移至 SP11**，本子项只建服务层占位接口 |
| F11 | 盐化仓独立扣量 | 加 SALTED WarehouseType + 独立报告端点 |

### 1.3 不含（out of scope）

- 进销存台账完整实现（SP11）
- 通用审批流引擎（SP12）
- 16位分段编码（SP8）
- 财务凭证导出（SP11）

---

## 2. 现有代码复用清单（grep 验证）

| 文件 | 状态 | SP7 用途 |
|---|---|---|
| `entity/factory/FactoryWarehouse.java` | **exists** — 10 WarehouseType 枚举(RAW/WIP/FINISHED/LINESIDE/RETURNS/SCRAP/TEMP/QC/OUTSOURCE/TRANSFER)，无 SALTED | 加 SALTED 枚举值 |
| `entity/inventory/InternalTransfer.java` | **exists** — 完整状态机 DRAFT→REQUESTED→APPROVED→SHIPPED→RECEIVED→CONFIRMED，含 sourceWarehouseId/targetWarehouseId | 复用，仅补仓库维度过滤 |
| `entity/MaterialBatch.java` | **exists** — 含 `warehouse_id` 列(NOT NULL)，含 `idx_material_batch_warehouse` 索引 | 复用，盘点/报损时读取快照 |
| `entity/MaterialBatchAdjustment.java` | **exists** — adjustment_type/quantity_before/after/reason/adjusted_by audit trail | 作为报损生效后的 audit 行来源 |
| `entity/DisposalRecord.java` | **exists** — 质检侧报废记录，含 disposal_type/is_approved/estimated_loss | **不复用为主**；WastageReport 是独立实体(双轨语义不同)。DisposalRecord 留质检流 |
| `entity/restaurant/StocktakingRecord.java` | **exists** — 餐饮侧盘点，IN_PROGRESS→COMPLETED→CANCELLED，含 system_quantity/actual_quantity/difference | **作为模式参考**；新建 FactoryStocktake 复制状态机但扩工厂场景(月底限制/财务审批/多行) |
| `controller/factory/FactoryWarehouseController.java` | **exists** | 加 SALTED 型仓库 CRUD 端点 |
| `controller/inventory/TransferController.java` | **exists** — 完整调拨端点 | 复用，补仓库维度查询参数 |
| `controller/WarehouseInventoryController.java` | **exists** | 补盘点/报损端点路径 |
| RN `WHInventoryCheckScreen.tsx` | **exists** — 573 行，直接调 `materialBatchApiClient.adjust`，含 TODO "后端需要提供库存调整 API" | 重连接到盘点任务状态机端点，移除直接 adjust 调用 |
| RN `WHInventoryTransferScreen.tsx` | **exists** | 复用，补仓库选择字段 |

---

## 3. 数据模型增量

### 3.1 WarehouseType 枚举加 SALTED

```java
// FactoryWarehouse.WarehouseType 加枚举值
/** 盐化仓 - 盐水/腌制工序专属仓库，独立出量记录 */
SALTED
```

Flyway: `V20260910_60` — ALTER TYPE or 枚举值迁移（PostgreSQL 用 `ALTER TYPE ... ADD VALUE`）。

### 3.2 新实体：FactoryStocktake（工厂侧盘点任务）

**表名**: `factory_stocktakes`

```
id              UUID PK
factory_id      VARCHAR(64) NOT NULL
stocktake_no    VARCHAR(50) NOT NULL UNIQUE(factory_id, stocktake_no)
warehouse_id    VARCHAR(64) NOT NULL  -- 被盘仓库
period_month    VARCHAR(7) NOT NULL   -- "2026-06" 月底约束
status          ENUM(INITIATED, COUNTING, PENDING_APPROVAL, APPROVED, APPLIED, REJECTED) NOT NULL
initiated_by    BIGINT NOT NULL
initiated_at    TIMESTAMP NOT NULL
submitted_by    BIGINT
submitted_at    TIMESTAMP
approved_by     BIGINT
approved_at     TIMESTAMP
reject_reason   TEXT
applied_at      TIMESTAMP             -- 生效时间（库存差异正式写入）
notes           TEXT
created_at      TIMESTAMP DEFAULT NOW()
updated_at      TIMESTAMP DEFAULT NOW()
deleted_at      TIMESTAMP
```

状态机：
```
INITIATED → COUNTING → PENDING_APPROVAL → APPROVED → APPLIED
                                        ↘ REJECTED → (可重提 PENDING_APPROVAL)
```

**FactoryStocktakeItem**（盘点明细行）

```
id              UUID PK
stocktake_id    UUID NOT NULL FK factory_stocktakes
material_batch_id VARCHAR(191) NOT NULL
raw_material_type_id VARCHAR(191)      -- 物料品名冗余
system_qty      DECIMAL(12,4) NOT NULL -- 账面库存快照(发起时记录)
actual_qty      DECIMAL(12,4)          -- 仓管填写
difference_qty  DECIMAL(12,4) GENERATED AS (actual_qty - system_qty)
difference_type ENUM(SURPLUS, SHORTAGE, MATCH)
photo_urls      TEXT                   -- JSON 数组，差异照片
notes           TEXT
created_at      TIMESTAMP DEFAULT NOW()
updated_at      TIMESTAMP DEFAULT NOW()
deleted_at      TIMESTAMP
```

索引：`(factory_id, period_month)`, `(warehouse_id)`, `(status)`.

Flyway: `V20260910_61` — CREATE TABLE factory_stocktakes + factory_stocktake_items。

### 3.3 新实体：WastageReport（报损单）

**表名**: `wastage_reports`

```
id              UUID PK
factory_id      VARCHAR(64) NOT NULL
report_no       VARCHAR(50) NOT NULL UNIQUE(factory_id, report_no)
track_type      ENUM(WAREHOUSE, FACTORY) NOT NULL
                -- WAREHOUSE: 仓库报损，需财务批
                -- FACTORY:   工厂报损，需厂长批
warehouse_id    VARCHAR(64)                  -- WAREHOUSE 轨必填
material_batch_id VARCHAR(191) NOT NULL
raw_material_type_id VARCHAR(191)            -- 冗余，展示用
wastage_qty     DECIMAL(12,4) NOT NULL
wastage_reason  ENUM(EXPIRED, DAMAGED, CONTAMINATED, THEFT, OTHER) NOT NULL
reason_detail   TEXT                         -- 选 OTHER 时必填
photo_urls      TEXT NOT NULL                -- JSON 数组，照片强制至少 1 张
status          ENUM(DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, APPLIED) NOT NULL
submitted_by    BIGINT
submitted_at    TIMESTAMP
approver_role   VARCHAR(50)                  -- FINANCE / FACTORY_MANAGER
approved_by     BIGINT
approved_at     TIMESTAMP
reject_reason   TEXT
applied_at      TIMESTAMP                    -- 生效时间
notes           TEXT
created_at      TIMESTAMP DEFAULT NOW()
updated_at      TIMESTAMP DEFAULT NOW()
deleted_at      TIMESTAMP
```

**照片强制规则**（fool-proof Rule 3 + 客户要求）: 提交前端校验 `photo_urls.length >= 1`；后端 service 提交门检查。

**双轨审批映射**：
- `WAREHOUSE` track → 财务角色(`FINANCE`)批
- `FACTORY` track → 厂长角色(`FACTORY_MANAGER`)批

Flyway: `V20260910_62` — CREATE TABLE wastage_reports。

### 3.4 MaterialBatch 无新列（SP7 复用现有 warehouse_id）

`MaterialBatch.warehouseId` 已存在且 NOT NULL。SP7 不加新列，盘点快照直接读 `quantity`/`unit`。

### 3.5 无新增：InternalTransfer 无改动

`InternalTransfer` 已有 `sourceWarehouseId`/`targetWarehouseId`。SP7 仅补仓库维度查询过滤，不加列。

---

## 4. 端点设计

### 4.1 盘点任务端点（`/api/mobile/{factoryId}/stocktake`）

| Method | 路径 | 描述 | 角色 |
|---|---|---|---|
| POST | `/` | 发起盘点任务(INITIATED) | WAREHOUSE_MANAGER, FACTORY_MANAGER |
| GET | `/` | 列表(分页+状态过滤) | WAREHOUSE_MANAGER, FINANCE, FACTORY_MANAGER |
| GET | `/{id}` | 盘点详情 + 明细行 | WAREHOUSE_MANAGER, FINANCE |
| PUT | `/{id}/items` | 批量更新明细实盘数量 | WAREHOUSE_MANAGER |
| POST | `/{id}/submit` | 提交审批(→PENDING_APPROVAL) | WAREHOUSE_MANAGER |
| POST | `/{id}/approve` | 财务审批通过(→APPROVED) | FINANCE |
| POST | `/{id}/reject` | 驳回(→REJECTED) | FINANCE |
| POST | `/{id}/apply` | 生效：写入库存调整(→APPLIED) | FINANCE (自动触发) |
| GET | `/{id}/diff-preview` | 差异预览(生效前展示变化) | FINANCE |

**`/apply` 幂等性**（fool-proof Rule 4）: 已 APPLIED → 409 + `{existingAppliedAt}`。

### 4.2 报损单端点（`/api/mobile/{factoryId}/wastage-report`）

| Method | 路径 | 描述 | 角色 |
|---|---|---|---|
| POST | `/` | 创建报损单(DRAFT) | WAREHOUSE_MANAGER, OPERATOR |
| GET | `/` | 列表(分页+track_type/status过滤) | WAREHOUSE_MANAGER, FINANCE, FACTORY_MANAGER |
| GET | `/{id}` | 报损单详情 | 同上 |
| POST | `/{id}/submit` | 提交审批(→PENDING_APPROVAL) | WAREHOUSE_MANAGER, OPERATOR |
| POST | `/{id}/approve` | 审批通过(→APPROVED→APPLIED) | FINANCE(WAREHOUSE轨) / FACTORY_MANAGER(FACTORY轨) |
| POST | `/{id}/reject` | 驳回(→REJECTED) | 同上 |
| GET | `/pending` | 待审批列表(按角色过滤轨道) | FINANCE, FACTORY_MANAGER |

**生效动作**（approve 时原子触发）:
- 调 `MaterialBatchAdjustment` 写一条 adjustment 行（type=`WASTAGE`, quantity=-wastage_qty）
- `MaterialBatch.quantity` -= wastage_qty
- WAREHOUSE 轨生效后，同步更新仓库库存报表（占位，SP11 补全）

### 4.3 仓库类型补充端点（已存在 FactoryWarehouseController）

无新端点，SALTED 类型通过枚举值扩展后自动可用。

### 4.4 生产库隔离守卫端点

无独立端点；在 `WarehouseInventoryController` 现有调拨/领料路径加守卫：
- 目标仓库 type = `PRODUCTION` → 仅允许 `SEMI_FINISHED` 物料入库，其他物料返回 409 + `"生产库只存半成品，请选择原料仓"`

---

## 5. 服务层设计

### 5.1 FactoryStocktakeService

```
interface FactoryStocktakeService {
  FactoryStocktake initiate(String factoryId, String warehouseId, String periodMonth, Long userId);
  void updateItems(String stocktakeId, List<StocktakeItemUpdateDTO> items, Long userId);
  void submit(String stocktakeId, Long userId);
  void approve(String stocktakeId, Long approverId);       // FINANCE only
  void reject(String stocktakeId, String reason, Long userId);
  void apply(String stocktakeId, Long userId);             // 写差异到 MaterialBatch + audit
  StocktakeDiffPreviewDTO previewDiff(String stocktakeId);
}
```

**月底约束检查**（initiate 时）: `period_month` 对应月份最后 3 天才允许发起（可配，默认第 29-31 日）。违反 → 409 + `"盘点任务只能在月底发起(29日后)"`（fool-proof Rule 5 — dead-end 给出 next date）。

**apply 原子事务**:
```java
@Transactional
void apply(String stocktakeId, Long userId) {
    FactoryStocktake task = getForUpdate(stocktakeId); // 悲观锁
    assertStatus(task, APPROVED, "生效");
    for (FactoryStocktakeItem item : task.getItems()) {
        if (item.getDifferenceQty() == null || 
            item.getDifferenceQty().compareTo(BigDecimal.ZERO) == 0) continue;
        // 写 MaterialBatchAdjustment
        MaterialBatchAdjustment adj = buildAdjustment(item, userId);
        materialBatchAdjustmentRepo.save(adj);
        // 更新 MaterialBatch.quantity (null safe)
        materialBatchService.applyAdjustment(item.getMaterialBatchId(), item.getDifferenceQty());
    }
    task.setStatus(APPLIED);
    task.setAppliedAt(LocalDateTime.now());
}
```

### 5.2 WastageReportService

```
interface WastageReportService {
  WastageReport create(String factoryId, CreateWastageReportRequest req, Long userId);
  void submit(String reportId, Long userId);
  void approve(String reportId, Long approverId, String approverRole);
  void reject(String reportId, String reason, Long approverId);
  Page<WastageReport> listPendingByApproverRole(String factoryId, String role, Pageable pageable);
}
```

**审批路由**（approve 时）:
```java
void routeApproval(WastageReport report, Long approverId, String requestRole) {
    if (report.getTrackType() == WAREHOUSE && !"FINANCE".equals(requestRole)) {
        throw new BusinessException(403, "仓库报损需财务审批");
    }
    if (report.getTrackType() == FACTORY && !"FACTORY_MANAGER".equals(requestRole)) {
        throw new BusinessException(403, "工厂报损需厂长审批");
    }
    // 审批通过后原子写 adjustment
    applyWastageToInventory(report, approverId);
}
```

**RBAC 使用 requestRole**（C1 孪生坑教训）:
```java
// ❌ 禁止 — SecurityContext 在拦截器层永为空
SecurityUtils.hasAnyRole("FINANCE");
// ✅ 正确 — JwtAuthInterceptor 已写入 request 属性
String role = (String) request.getAttribute("role");
```

### 5.3 WarehouseInventoryGuardService（生产库隔离）

```java
@Service
public class WarehouseInventoryGuardService {
    /**
     * 验证物料是否可入指定仓库。
     * 生产库(PRODUCTION)只允许 SEMI_FINISHED 类物料。
     */
    public void validateInbound(String warehouseId, String materialTypeId) {
        FactoryWarehouse wh = warehouseRepo.findById(warehouseId).orElseThrow(...);
        if (wh.getType() == WarehouseType.PRODUCTION) {
            RawMaterialType mat = materialTypeRepo.findById(materialTypeId).orElseThrow(...);
            if (!"SEMI_FINISHED".equals(mat.getCategory())) {
                throw new BusinessException(409, 
                    "生产库只存半成品，请选择原料仓", "SELECT_RAW_WAREHOUSE");
            }
        }
    }
}
```

---

## 6. 错误处理（Fool-Proof 4 位一体）

所有写操作 API 错误必须满足：

| # | 规则 | 实现 |
|---|---|---|
| a | **后端真实 message** | service 层抛 `BusinessException(code, 具体中文message, actionHint)` |
| b | **前端 toast = 后端 message** | RN/web 直接显示 `e.response.data.message`，不用 fallback |
| c | **toast sticky** | `ElMessage({ duration: 0, showClose: true })` (web); RN `Alert.alert` |
| d | **含 next action** | message 包含"请先 X"或后端返回 `actionHint` 字段 |

**典型错误场景**：

| 场景 | HTTP | message | actionHint |
|---|---|---|---|
| 盘点任务未到月底 | 409 | "盘点任务只能在月底（29日后）发起，当前是 {date}" | "等到29日再发起" |
| 报损单无照片提交 | 422 | "报损单必须上传至少一张照片作为凭证" | "请拍照后再提交" |
| 非财务角色审批仓库报损 | 403 | "仓库报损单需财务角色审批，当前角色：{role}" | "请联系财务审批" |
| 盘点任务重复生效 | 409 | "盘点任务已于 {appliedAt} 生效，请勿重复操作" | "查看已生效记录" |
| 生产库入非半成品 | 409 | "生产库只存半成品，原料/辅料请入原料仓" | "SELECT_RAW_WAREHOUSE" |
| 调拨无审批直接出库 | 403 | "调拨单尚未审批，无法出库" | "提交审批后再操作" |

---

## 7. UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像

**仓管员（warehouse_worker）** — 年纪偏大、文化素质参差（客户原话："做仓管的他年纪都比较大文化素质很低"）— 主要场景：月底盘点录入实盘数量、发现残损货拍照上报、接收调拨单出/入货。

### 用户旅程（月底盘点场景）

| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | 首页"本月盘点" badge 亮 | 点击进入 | 显示本月待盘点任务 |
| 2 | 盘点任务卡：仓库名 + 品名列表 + 账面数 | — | 清楚知道要盘什么 |
| 3 | 每行：品名 + 账面数量 + 大字号输入框 | 填写实盘数量 | 自动计算差异（红/绿提示） |
| 4 | 有差异行标红，差异量显示 | 核实后点"确认差异" | 差异锁定，等待提交 |
| 5 | 提交按钮（大型，底部固定） | 点提交 | 弹确认弹窗："确认提交盘点？差异 X 件将报给财务" |
| 6 | 财务在 web-admin 收到待审批 | — | 仓管等待结果推送 |

### 用户旅程（报损拍照场景）

| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | "报损" 快捷入口（首页大按钮） | 点击 | 进入报损填写屏 |
| 2 | 标题："仓库报损单" + 仓库名 | 扫码或搜索批次 | 批次名称 + 数量自动回填 |
| 3 | 报损数量（大字号，数字键盘） | 填数量 | 实时预显限额："当前库存 X，最多报损 X" |
| 4 | 报损原因：下拉菜单（变质/破损/污染/盗损/其他） | 选原因 | 选"其他"时展开文本框 |
| 5 | **照片区（红框强制）**："必须拍照，至少1张" | 拍照/选相册 | 照片缩略图显示 |
| 6 | 提交按钮仅在有照片时 enabled | 点提交 | 进入财务/厂长审批队列，显示"已提交，等待审批" |

### 摩擦点清单

| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | WHInventoryCheckScreen 当前直接调 `materialBatchApiClient.adjust`，绕过盘点任务状态机，仓管可任意改库存 | HIGH | 蓝图 §3.4 红线 |
| F2 | 报损原因用自由文本 textarea，老仓管写不清楚，无统计价值 | HIGH | fool-proof Rule 3 |
| F3 | 照片不强制，现有 DisposalRecord 照片是 optional | HIGH | 客户硬性要求 |
| F4 | 盘点表无账面数字回显，仓管不知道账面数是多少就开始填 | MED | fool-proof Rule 1 |
| F5 | 月底时间约束不可见，仓管随时点"发起盘点"会被后端拒绝但看不到原因 | MED | fool-proof Rule 5 |
| F6 | 差异较大时无追责提示，仓管不知道盘亏要留责任人 | MED | 需求 F6 |
| F7 | 数量输入用小字号 | LOW | ux-flow 内联规则 |

### 每个摩擦点的设计回应

- F1 → WHInventoryCheckScreen 改连接 `/stocktake/{id}/items` 端点，移除对 `materialBatchApiClient.adjust` 的直接调用；提交走 `/stocktake/{id}/submit`（需财务批才生效）
- F2 → 报损原因改为 Picker/Select（变质/破损/污染/盗损/其他），选"其他"才展开 TextInput
- F3 → 照片区用红色边框 + 文案"必须拍照"；提交按钮在 `photos.length < 1` 时 disabled + tooltip "请先拍照"
- F4 → 盘点行显示"账面：XX {单位}"大号，输入框紧邻，差异实时计算绿/红色展示
- F5 → 发起盘点前检查当前日期，未到月底 29 日显示"月底盘点功能将在 {nextDate} 开放"并显示倒计时（不静默 409）
- F6 → 提交弹窗中盘亏行显示"注：盘亏将记录您的工号作为责任方，请确认数据正确"
- F7 → 所有数量输入 fontSize: 24，`keyboardType="numeric"`，TextInput Paper 组件

---

## 8. 🔒 红线章节（原文引自蓝图 §3.4，Opus 终审，不可改）

> 以下为蓝图 §3.4 权限矩阵 + 仓库零自主权完整原文，任何子项实现均不得绕过：

**仓库无自主改库存权**: 所有 出入库/盘亏盈/报损 必须有单据来源(采购入库单/退货单/调拨单/盘点任务/报损单)+ 经审批后才动数据。仓管=执行操作员，不发起。

角色：仓管/厂长(车间主任)/小组长/operator(纯报工)/财务/出纳/采购员/销售员/品控。复用现有 RBAC + @RequireRole + 请求属性 role(非空 SecurityContext，教训 C1孪生坑)。缺的角色(出纳/品控)增量加。

所有审批/库存变动 audit 留痕(who/when/node/before-after)。

### 红线实现守卫（必须全部落地，Opus 终审核对）

1. **无单据禁止直接调整**: `MaterialBatch.quantity` 只能由以下路径修改：
   - `PurchaseReceiveService.receive()`（采购入库）
   - `TransferService.confirm()`（调拨确认）
   - `StocktakeService.apply()`（盘点生效，财务批后）
   - `WastageReportService.approve()`（报损审批通过）
   - 生产领料/退料（SP1 范围）
   - **禁止** 直接调用 `materialBatchRepository.save()` 修改 quantity 无 audit 记录

2. **直接调整端点（旧 materialBatchApiClient.adjust）**: WHInventoryCheckScreen 的 TODO 调用**必须删除**，替换为盘点任务流程。

3. **audit 要求**: 每次 quantity 变动必须产生 `MaterialBatchAdjustment` 记录（type, before, after, reason, userId, timestamp）。

4. **角色 requestAttribute 防御**: service 层通过 `HttpServletRequest.getAttribute("role")` 获取角色，**不使用** `SecurityContextHolder`（已知永为空，C1孪生坑）。

---

## 9. 测试策略

### 9.1 后端单元/集成测试

每个 service 方法一对一 TDD：

| 测试类 | 覆盖场景 |
|---|---|
| `FactoryStocktakeServiceTest` | 月底约束/提交状态机/approve/apply 原子性/幂等重复 apply/差异量计算 |
| `WastageReportServiceTest` | 照片强制/双轨路由(WAREHOUSE→FINANCE/FACTORY→FACTORY_MANAGER)/approve 写 adjustment/reject/幂等 |
| `WarehouseInventoryGuardServiceTest` | 生产库拒非半成品/原料仓允许入/SALTED 仓独立 |
| `StocktakeItemTest` | 差异量计算/SURPLUS/SHORTAGE/MATCH |

### 9.2 Flyway 迁移测试

启动时 Spring Boot 跑 Flyway + H2：验证 V20260910_60-62 无语法错误。

### 9.3 RN 端 UI 测试（TypeScript 编译）

WHInventoryCheckScreen 改后 `npx tsc --noEmit` 无类型错误。

### 9.4 Headed E2E（仓管场景）

月底盘点场景：
1. 仓管登录 → 发起盘点 → 填实盘数 → 提交
2. 财务 web-admin 审批 → 观察库存变化

报损场景：
1. 仓管报损 → 不上传照片 → 提交按钮 disabled
2. 上传照片 → 选原因 → 提交 → 进审批队列

---

## 10. 跨子项依赖

| 依赖 | 说明 | 风险 |
|---|---|---|
| SP4（一物一码）先行 | `MaterialBatch.warehouse_id` 已存在（confirmed）；SP4 若加 `factory_number`/`origin_place` 列，SP7 盘点快照需在 SP4 merge 后 | scope-lock 串行：SP4 PR merge → SP7 开工 |
| SP6（采购付款）部分并行 | 两者都改 `MaterialBatch`，但 SP6 加采购相关列，SP7 只读不加列 → **可并行**，scope 无直接冲突 | 注意 SP6 的 Flyway V20260910_5x 号段别冲 SP7 V6x |
| SP11（财务凭证+进销存报表）后行 | F10 进销存台账需 SP7 盘点/报损数据写入完毕 | SP11 依赖 SP7 先 merge |
| SP12（通用审批流）解耦 | SP7 用轻量状态机（蓝图 §3.5），不依赖大引擎 | SP12 P1 落地后迁移适配器（预留 `source_doc_type`/`source_doc_id` 字段可无缝接入） |

---

## 附录：Flyway 号段使用计划

| Flyway 版本 | 内容 | 优先级 |
|---|---|---|
| V20260910_60 | ALTER TYPE factory_warehouse_type ADD VALUE 'SALTED' | P0 |
| V20260910_61 | CREATE TABLE factory_stocktakes + factory_stocktake_items | P0 |
| V20260910_62 | CREATE TABLE wastage_reports | P0 |
| V20260910_63-69 | 预留（字段扩展/索引/后续 minor）| 预留 |
