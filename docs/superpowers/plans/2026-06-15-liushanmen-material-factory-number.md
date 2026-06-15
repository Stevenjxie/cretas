# 原料厂号结构化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **配套 spec**（必读，含完整背景/字段/防呆/验收）: `docs/superpowers/specs/2026-06-15-liushanmen-material-factory-number-design.md`

**Goal:** 把原料厂号从自由文本升级为工厂级厂商登记表（结构化编码），收货时下拉选厂号原子落库，web-admin 提供登记表 CRUD —— 满足客户「回归唯一编码、领料按厂号选批次」诉求。

**Architecture:** 新增 `ManufacturerRegistry` 主数据实体（工厂级，code+name，soft-delete，唯一 (factory_id, code)）。`material_batches.factory_number`（已存在 VARCHAR100）继续存所选厂号 code 字符串，**不加硬 FK**（保历史自由文本兼容）。PO 收货请求携带 factoryNumber，在 `createMaterialBatchFromReceiveItem` 事务内原子 set。web-admin 收货 + 物料列表用厂商下拉（防呆 Rule 3）+ 登记表 CRUD 页。**RN picker 不在本计划范围**（待协调）。

**Tech Stack:** Java 21 / Spring Boot 3.2 / JPA(Hibernate 6) / PostgreSQL / Flyway；web-admin Vue 3 + Element Plus + TypeScript。

---

## ⚠️ 开工前（必做）

- [ ] **worktree off origin/main**：`git worktree add -b feat/liushanmen-factory-number ../cretas-factory-number origin/main`
- [ ] **Flyway 查号防撞**（本季踩 3 次）：`git ls-tree origin/main -- backend/java/cretas-api/src/main/resources/db/flyway | grep -oE "V[0-9]{8}_[0-9]+" | sort | tail -3`。预期最大 `V20261024_15` → 本计划用 **`V20261024_16`**。若已被占用，顺延到下一个未用号并更新本计划所有引用。
- [ ] **web-admin 依赖**：`cd web-admin && npm install --prefer-offline --legacy-peer-deps`（⛔ 禁 `mklink /J` 共享 node_modules）。

## 文件结构

| 动作 | 文件 | 职责 |
|---|---|---|
| Create | `entity/material/ManufacturerRegistry.java` | 厂商登记表实体（继承 BaseEntity） |
| Create | `repository/material/ManufacturerRegistryRepository.java` | 数据访问（by factory + active；唯一 code 查询） |
| Create | `dto/material/ManufacturerRegistryDTO.java` + `CreateManufacturerRequest.java` + `UpdateManufacturerRequest.java` | 传输对象 |
| Create | `service/material/ManufacturerRegistryService.java` + `impl/ManufacturerRegistryServiceImpl.java` | CRUD 业务逻辑 + code 唯一校验 |
| Create | `controller/ManufacturerRegistryController.java` | `/api/mobile/{factoryId}/manufacturers` |
| Create | `db/flyway/V20261024_16__manufacturer_registry.sql` | 建表 + partial unique index |
| Modify | `dto/inventory/CreateReceiveRecordRequest.java` | 收货行加 `factoryNumber` + `originPlace` |
| Modify | `service/inventory/impl/PurchaseServiceImpl.java#createMaterialBatchFromReceiveItem` | 原子 set factoryNumber/originPlace |
| Create | `web-admin/src/views/warehouse/manufacturers/index.vue` | 登记表 CRUD 页 |
| Create | `web-admin/src/api/manufacturer.ts` | 前端 API client |
| Modify | `web-admin/src/views/warehouse/materials/list.vue` | 厂号列编辑/收货 → 厂商下拉 |

---

## Task 1: ManufacturerRegistry 实体 + 迁移

**Files:** Create `entity/material/ManufacturerRegistry.java`, `db/flyway/V20261024_16__manufacturer_registry.sql`

- [ ] **Step 1: 写迁移**（PostgreSQL；字段见 spec §2.1；partial unique）

```sql
-- V20261024_16__manufacturer_registry.sql
CREATE TABLE manufacturer_registry (
    id           VARCHAR(191) PRIMARY KEY,
    factory_id   VARCHAR(191) NOT NULL,
    code         VARCHAR(64)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    origin_place VARCHAR(200),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    remark       VARCHAR(500),
    created_at   TIMESTAMP DEFAULT NOW(),
    updated_at   TIMESTAMP DEFAULT NOW(),
    deleted_at   TIMESTAMP NULL
);
CREATE UNIQUE INDEX uq_manufacturer_factory_code
    ON manufacturer_registry (factory_id, code) WHERE deleted_at IS NULL;
CREATE INDEX idx_manufacturer_factory_active
    ON manufacturer_registry (factory_id, is_active) WHERE deleted_at IS NULL;
```

- [ ] **Step 2: 写实体**（继承 BaseEntity，对齐仓内现有实体注解风格；字段名 camelCase、列名 snake_case per `field-naming-convention`）。grep 一个现成实体（如 `entity/MaterialBatch.java`）抄 BaseEntity 继承 + Lombok 注解风格。
- [ ] **Step 3: 编译** `./mvnw.cmd -q -o compile`（预期通过）。
- [ ] **Step 4: Commit** `git commit -m "feat(material): ManufacturerRegistry 实体 + 迁移 V20261024_16" -- <entity> <migration>`

## Task 2: Repository + 唯一 code 查询

**Files:** Create `repository/material/ManufacturerRegistryRepository.java`; Test `service/material/ManufacturerRegistryServiceTest.java`（占位）

- [ ] **Step 1:** 写 Repository：`List<ManufacturerRegistry> findByFactoryIdAndDeletedAtIsNull(String factoryId)`；`...AndIsActiveTrue...`；`Optional<ManufacturerRegistry> findByFactoryIdAndCodeAndDeletedAtIsNull(String factoryId, String code)`。
- [ ] **Step 2: 编译**。
- [ ] **Step 3: Commit**。

## Task 3: Service CRUD + code 唯一校验（TDD）

**Files:** Create service + impl + DTOs；Test `ManufacturerRegistryServiceTest.java`

- [ ] **Step 1: 写失败测试**（用仓内现有 service test 的 Mockito 风格 —— grep 一个 `*ServiceTest.java` 抄 mock repository 模式）：
  - `create_duplicateCodeSameFactory_throws409`：同 factory 同 active code → 抛 `BusinessException`（409 语义）含 existingId。
  - `create_sameCodeDifferentFactory_ok`：不同 factory 同 code 允许。
  - `list_filtersByFactory`：A 工厂 list 不含 B 工厂（多租户）。
  - `softDelete_setsDeletedAt`：删除走 soft-delete。
- [ ] **Step 2: 跑测试确认失败** `./mvnw.cmd -o -Dtest=ManufacturerRegistryServiceTest test`（预期 FAIL：类不存在）。
- [ ] **Step 3: 实现** service+impl+DTO：create 前 `findByFactoryIdAndCodeAndDeletedAtIsNull` 撞则抛 `BusinessException`（message「厂号编码 {code} 已存在：{name}」+ existingId，防呆 Rule 4）；update null-guard set（per `feedback_dto_roundtrip_silent_drop`）；list/active 过滤；soft-delete。
- [ ] **Step 4: 跑测试确认通过**。
- [ ] **Step 5: Commit**。

## Task 4: Controller

**Files:** Create `controller/ManufacturerRegistryController.java`

- [ ] **Step 1:** grep `controller/MaterialBatchController.java` 取 `@RequirePermission` 权限码风格（读=material:read 级、写=material:read_write 级，**以实际现有码为准**）+ `{factoryId}` path 模式 + 统一响应 `{success,data,message}`。
- [ ] **Step 2:** 实现 `GET /manufacturers?active=`（列表）、`POST`（建）、`PUT /{id}`（改）、`DELETE /{id}`（停用）。
- [ ] **Step 3:** 加 Controller 层测试（grep 现有 controller test 抄 MockMvc/standalone 风格）—— 至少建-撞-409 + 多租户 list。
- [ ] **Step 4: 全量测试** `./mvnw.cmd -o test`（**全量非 -Dtest**，per `feedback_subagent_full_suite_not_dtest`）。预期绿。
- [ ] **Step 5: Commit**。

## Task 5: PO 收货原子录入厂号（TDD）

**Files:** Modify `dto/inventory/CreateReceiveRecordRequest.java`, `service/inventory/impl/PurchaseServiceImpl.java`

- [ ] **Step 1: 读** `CreateReceiveRecordRequest` 收货行内部结构 + `createMaterialBatchFromReceiveItem` 现状（确认它现在**不** set factoryNumber）。
- [ ] **Step 2: 写失败测试**（grep `PurchaseServiceImpl` 现有 test，加）：
  - `createReceive_withFactoryNumber_setsOnBatch`：收货行带 factoryNumber → 落库批次 `getFactoryNumber()` == 传入值。
  - `createReceive_noFactoryNumber_batchNull`：未传 → 批次 factoryNumber null（诚实 null）。
- [ ] **Step 3: 跑测试确认失败**。
- [ ] **Step 4: 实现**：收货行 DTO 加 `factoryNumber`/`originPlace` 字段；`createMaterialBatchFromReceiveItem` 内 `batch.setFactoryNumber(item.getFactoryNumber())` + originPlace（事务内，替换非原子 post-create patch 思路）。**默认松校验**（不强制 code 在登记表内，spec O1）。
- [ ] **Step 5: 全量测试**绿。
- [ ] **Step 6: Commit**。

## Task 6: web-admin 登记表 CRUD 页 + API client

**Files:** Create `web-admin/src/api/manufacturer.ts`, `web-admin/src/views/warehouse/manufacturers/index.vue`；路由注册（grep web-admin router 现有仓储菜单注册方式）

- [ ] **Step 1:** API client（list/create/update/delete，对齐 web-admin 现有 `src/api/*.ts` 的 request 封装 + `{success,data,message}` 解析）。
- [ ] **Step 2:** CRUD 页：表格（code/name/origin_place/active）+ 新建/编辑 dialog + 停用确认。错误 toast sticky（`fool-proof-design` 四位一体：error duration:0 + showClose + 原样显示后端 message）。
- [ ] **Step 3:** 注册路由进仓储/物料主数据菜单（grep 现有 `materials/list.vue` 的路由注册抄）。
- [ ] **Step 4: build** `npm run build`（或 type-check 脚本）绿。
- [ ] **Step 5: Commit**。

## Task 7: web-admin 收货 + 物料列表厂号下拉（防呆 Rule 3）

**Files:** Modify `web-admin/src/views/warehouse/materials/list.vue`（厂号编辑表单）+ 收货录入页（grep web-admin 收货页路径）

- [ ] **Step 1:** 把厂号自由文本 input → `el-select`（filterable，选项来自 `GET /manufacturers?active=true`）+「+ 新增厂商」内联入口（跳登记表）。
- [ ] **Step 2:** 选厂号自动带出登记表 `origin_place` 填产地（可改，spec O2）。
- [ ] **Step 3:** 收货页同样下拉，提交时把 code 放进 `CreateReceiveRecordRequest.item.factoryNumber`。
- [ ] **Step 4: build** 绿 + 手测下拉渲染（截图）。
- [ ] **Step 5: Commit**。

## Task 8: 收尾验收

- [ ] **Step 1: 全量后端测试** `./mvnw.cmd -o test` 绿。
- [ ] **Step 2: web-admin build** 绿。
- [ ] **Step 3: PR**：`gh pr create --base main --head feat/liushanmen-factory-number`。`git diff origin/main...HEAD --stat` 确认 scope 干净（只本计划文件，无 sister 夹带）。
- [ ] **Step 4: 🔒 停在 PR**：不自部署 prod、不自 merge。回 Opus organizer 终审 + 从 main 部署。

---

## Self-Review（已核 spec 覆盖）

- ManufacturerRegistry 实体/迁移/唯一 → Task 1-3 ✓
- 收货原子录入 → Task 5 ✓
- web-admin 下拉 + 登记表 CRUD → Task 6-7 ✓
- RN picker 不做 → 全程未触 `MaterialBatchPicker.tsx` ✓
- 防呆 Rule 3（下拉）/ Rule 4（幂等 409）/ 诚实 null / 全量测试 / Flyway 防撞 → 各 Task 已嵌 ✓
- 厂号=独立属性、不碰16位编码 → 全程未触 MaterialCodeSegment ✓
