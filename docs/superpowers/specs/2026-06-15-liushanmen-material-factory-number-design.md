# 六扇门 — 原料厂号结构化 设计 spec

**日期**: 2026-06-15
**来源**: 六扇门 ERP「主动建版本」剩余项 ①（handoff `docs/dispatch/2026-06-15-handoff-liushanmen-remaining-proactive-build.md`）
**转录依据**: `docs/meetings/2026-06-09-liushanmen/transcript-2b.txt` [111:41–113:20]
**scope 决策**: Steve 2026-06-15 拍板「结构化登记表 + 收货录入 + picker选」+「厂号=独立批次属性，不进16位编码段」+「RN picker 归属待协调，本卡只做后端 + web-admin」

---

## 1. 背景与问题

六扇门同一原料（如膜 50CL）来自不同厂家，**厂号是最细的批次区分维度**（转录 [112:50]「厂号是最细的这个」）。生产人员领料/报工时要**选实际用的厂号**（[112:48]「生产人员去选择有哪个厂号」），不同厂号 = 不同批次、可能不同价格，直接影响成本溯源。

客户明确反对自由文本：[113:00-113:04]「回归到唯一编码的问题，你不能用文字去学」「文字越读越识错」。

### verify-first 现状（已对 origin/main `cacd518e7` 核实）

| 事实 | 证据 |
|---|---|
| `MaterialBatch.factoryNumber`（自由文本 VARCHAR100）**已存在** | `entity/MaterialBatch.java:133-137`（`@Column(name="factory_number", length=100)`），迁移 `db/flyway/V20261002_01__sp4_add_factory_number_origin_place_to_material_batches.sql` |
| `MaterialBatch.originPlace`（产地 VARCHAR200）**已存在** | 同实体 + 同迁移 |
| 厂号已全链显示（创建/更新 DTO、mapper、RN picker、web-admin、扫码响应） | `CreateMaterialBatchRequest` / `MaterialBatchDTO` / `MaterialBatchMapper` / `MaterialBatchPicker.tsx:555` / `list.vue:409` / `MaterialBatchLabelScanResponse` |
| MaterialCodeSegment 只 L1-3（类型/部位/品名），**无产地/厂号 level** | `entity/material/MaterialCodeSegment.java` |
| **厂号目前是自由文本**，无结构化/编码/登记表 | grep 全仓无 ManufacturerRegistry / manufacturer 主数据 |
| **PO 收货路径不原子捕获厂号** | `service/inventory/impl/PurchaseServiceImpl.java#createMaterialBatchFromReceiveItem` 不 set factoryNumber；现状是 `WHReceiptCreateScreen` 收货后另发 `updateBatch` patch（非原子，[line307]「厂号/产地写入失败不影响入库主流程」） |
| picker 只**显示**厂号灰字，**不能按厂号筛选/选** | `MaterialBatchPicker.tsx`（扁平列表） |

### 真缺口（本 spec 范围）

1. **结构化厂号**：自由文本 → 工厂级厂商登记表（编码 + 名称），收货时下拉选，落库存编码。
2. **PO 收货原子录入厂号**：把厂号纳入收货请求 → 在 `createMaterialBatchFromReceiveItem` 内 set（修根，不靠 post-create patch）。
3. **web-admin**：收货表单 + 物料列表用厂商**下拉**（防呆 Rule 3：下拉非自由文本），并提供登记表 CRUD 页。

### 明确不做（本卡）

- **RN 领料/报工 picker 按厂号筛选/选**：归属待协调（handoff 注「RN 领料屏部分已派别 chat」）。本卡只做后端 + web-admin。批次仍带 `factoryNumber`，那个 chat 的 picker 照常工作；后续它接结构化厂号过滤。
- **不碰 16 位物料编码**：厂号是批次属性，不是 MaterialCodeSegment level（转录 [113:10] 倾向「编码唯一，厂号不同只是批次问题」；加 L4 会让同一原料出现多编码，矛盾）。

---

## 2. 架构

### 2.1 新实体 `ManufacturerRegistry`（厂商登记表，工厂级主数据）

```
manufacturer_registry
  id            VARCHAR(191) PK
  factory_id    VARCHAR(191) NOT NULL          -- 多租户隔离
  code          VARCHAR(64)  NOT NULL          -- 厂号编码（结构化，如 "321" / "SC-321"）
  name          VARCHAR(200) NOT NULL          -- 厂商全称（如 "上海某某食品有限公司"）
  origin_place  VARCHAR(200) NULL              -- 默认产地（可选，选厂号时带出产地默认值）
  is_active     BOOLEAN NOT NULL DEFAULT TRUE
  remark        VARCHAR(500) NULL
  created_at / updated_at / deleted_at         -- BaseEntity 标准审计字段
  UNIQUE (factory_id, code) WHERE deleted_at IS NULL   -- 同工厂厂号编码唯一
```

- 继承 `BaseEntity`（per `.claude/rules/database-entity-sync.md`：created_at/updated_at/deleted_at 必备）。
- **不在 `material_batches` 上加硬 FK**：`material_batches.factory_number` 继续存**所选厂号的 code 字符串**（保留与历史自由文本值的向后兼容，避免 schema 收紧迁移）。登记表是查找型主数据，批次存所选 code。
- `origin_place` 在登记表上是「该厂商默认产地」，选厂号时前端可带出填充批次 `originPlace`（便利，非强制）。

### 2.2 Flyway 迁移

- **`V20261024_16__manufacturer_registry.sql`**（预分配号；origin/main 当前最大 = V20261024_15，**Codex 开工前必再 `git ls-tree origin/main -- backend/java/cretas-api/src/main/resources/db/flyway` 复查防撞**）。
- 建表 + 唯一索引（partial unique on `(factory_id, code) WHERE deleted_at IS NULL`，PostgreSQL 语法）。
- **不 seed 数据**（六扇门厂商由 web-admin 录入；若需 demo 数据另起 seed 卡）。

### 2.3 后端 service / controller

- `ManufacturerRegistryService` + `Impl`：标准 CRUD（list by factory + active 过滤 / create / update / soft-delete）。
- `ManufacturerRegistryController`：`/api/mobile/{factoryId}/manufacturers`（GET 列表[支持 active-only param] / POST / PUT / DELETE）。权限：`material:read` 读、`material:read_write` 写（对齐现有物料主数据权限；Codex 须 grep 现有 `MaterialBatchController` / `RawMaterialTypeController` 的 `@RequirePermission` 取准确权限码）。
- 创建/更新 `code` 唯一性校验：撞已存在 active code → `BusinessException(409)` 含明确 message（防呆 Rule 4 幂等：「厂号编码 321 已存在：上海某某食品」+ existingId）。

### 2.4 PO 收货原子录入厂号

- `CreateReceiveRecordRequest`（`dto/inventory/CreateReceiveRecordRequest.java`）的**收货行**结构增加 `factoryNumber`（厂号 code）+ 可选 `originPlace`。
- `PurchaseServiceImpl.createMaterialBatchFromReceiveItem`：创建批次时 `batch.setFactoryNumber(item.getFactoryNumber())` + `batch.setOriginPlace(...)`，**事务内原子**（替换现状非原子 post-create patch）。
- 校验：若传了 `factoryNumber` 但不在该工厂 active 登记表 → 决策点（见 §5 开放问题 O1）。**默认**：宽松（接受任意 code 字符串，登记表只是录入辅助），保留对历史/手录值兼容；**收紧**（必须是登记表已存在 code）留 O1。
- 诚实 null：收货未选厂号 → `factoryNumber` 留 null（不编造）。

### 2.5 web-admin（防呆 Rule 3）

- **收货录入页**：厂号字段从自由文本 input → `el-select`（filterable，选项来自 `GET /manufacturers?active=true`）+「+ 新增厂商」内联入口（跳登记表或弹窗快建）。选厂号自动带出登记表 `origin_place` 填产地（可改）。
- **物料批次列表 `list.vue`**：厂号列保持显示（已有 `prop="factoryNumber"`）；编辑表单同样换下拉。
- **厂商登记表 CRUD 页**：新建 `web-admin/src/views/warehouse/manufacturers/`（或并入物料主数据菜单）—— 列表 + 新建/编辑 dialog（code/name/origin_place/active）+ 停用（soft-delete）。错误 toast sticky（`fool-proof-design` 四位一体）。

---

## 3. 数据流

```
录入厂商主数据 (web-admin 登记表 CRUD)
        │  ManufacturerRegistry {factory_id, code, name, origin_place}
        ▼
采购收货 (web-admin 收货页)
   选厂号下拉 ──► CreateReceiveRecordRequest.item.factoryNumber (code)
        │
        ▼
PurchaseServiceImpl.createMaterialBatchFromReceiveItem
   batch.setFactoryNumber(code) + setOriginPlace  ← 事务内原子
        │
        ▼
MaterialBatch.factory_number (code 字符串)
        │  已全链透出 (DTO/mapper/scan)
        ▼
RN picker / web 列表 显示厂号 (后续 RN 按厂号过滤=别 chat)
```

---

## 4. 错误处理 / 测试

- **错误处理**（`.claude/rules/api-response-handling.md` + `fool-proof-design.md`）：
  - 厂号 code 重复创建 → 409 + message 含已存在厂商名 + existingId（Rule 4）。
  - 删除被引用厂商：soft-delete 不级联（批次已存 code 字符串，不受影响）；停用后收货下拉不再出现，历史批次照常显示。
  - 收货厂号校验失败（若 O1 选收紧）→ 400 + 明确 message + actionHint「请先在厂商登记表录入该厂号」。
- **测试**（后端 JUnit，Codex 须跑**全量** `./mvnw.cmd test` 非 `-Dtest`，per memory `feedback_subagent_full_suite_not_dtest`）：
  - 登记表 CRUD + 同工厂 code 唯一（不同工厂同 code 允许）。
  - 收货创建批次 set factoryNumber 原子（断言批次落库带 code）。
  - 收货未选厂号 → 批次 factoryNumber null（诚实 null）。
  - 多租户隔离：A 工厂 list 不含 B 工厂厂商。
- web-admin：`npm run build` + type-check 绿（Codex 自测）。

---

## 5. 开放问题（实现时决策，非阻塞）

- **O1 收货厂号校验严/松**：默认**松**（接受任意 code，登记表仅辅助）保兼容；若客户要强约束（厂号必须登记）改**严**。本 spec 取**松**（长远可加开关，但别现在 over-engineer）。
- **O2 厂号下拉是否带 origin_place 自动填充**：取**是**（便利，可改）。
- **O3 登记表菜单位置**：并入「仓储/物料主数据」菜单下；Codex 按 web-admin 现有路由结构定。

---

## 6. 关键文件指针（Codex 自包含用）

| 用途 | 路径（origin/main） |
|---|---|
| 批次实体（factory_number 已有） | `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/MaterialBatch.java:133-137` |
| 收货请求 DTO | `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/inventory/CreateReceiveRecordRequest.java` |
| 收货建批次方法 | `backend/java/cretas-api/src/main/java/com/cretas/aims/service/inventory/impl/PurchaseServiceImpl.java#createMaterialBatchFromReceiveItem` |
| 物料权限参考 | `controller/MaterialBatchController.java` / `RawMaterialTypeServiceImpl`（取 @RequirePermission 权限码） |
| Flyway 目录（真·活跃，**非 db/migration**） | `backend/java/cretas-api/src/main/resources/db/flyway/`（最大 V20261024_15） |
| web-admin 物料列表 | `web-admin/src/views/warehouse/materials/list.vue:409`（厂号列） |
| RN picker（**本卡不改**） | `frontend/CretasFoodTrace/src/components/processing/MaterialBatchPicker.tsx:555`（仅参考） |

---

## 7. 验收

- web-admin 录厂商 → 收货下拉选厂号 → 批次落库带 code（非自由文本手输）→ 物料列表/批次详情显示厂号。
- 后端全量测试绿；多租户隔离断言通过。
- Flyway `V20261024_16` apply 成功（test → prod blue-green）。
- **诚实 null**：未选厂号批次 factoryNumber null，不报假成功。
