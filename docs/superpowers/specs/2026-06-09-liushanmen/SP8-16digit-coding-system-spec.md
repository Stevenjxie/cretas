# SP8 · 16位分段编码体系 — 设计规范 (Design Spec)

**子项**: SP8 · 16位分段编码体系
**蓝图波次**: Wave 3 (依赖 SP4)
**Flyway 号段**: `V20260911_02 ~ V20260911_09`
**Scope-lock 主文件**: 编码生成器(`RawMaterialTypeServiceImpl.generateNextCode`), `BomRecipeItem`(主编码关联键)
**生成**: 2026-06-09 Sonnet in-harness architect

> ⚠️ **周五客户确认项**: 客户老板原话"编码规则到底怎样都无所谓, 到时候你们能看到能搜索到就可以了" — 16位分段体系是 **P1 可选升级**, 非 P0 阻塞。若周五客户不坚持严格16位 → 本 SP 可缩范围为"前缀对齐+主编码查询API"(约2个小时工作量)。当前 spec 按完整 16 位方案设计, 执行时按客户周五反馈裁量。

---

## 1. 目标

六扇门食品工厂 P1 编码体系升级, 实现客户要求的**16位分段编码结构**:

- 前10位固定段 = 用户不手敲, 级联下拉自动拼装 (类型3位 + 部位/品类3位 + 品名4位)
- 后6位 = 系统自动生成流水序号, 零填充
- **BOM关联键 = 前三位主编码** (类型段), 客户原话: "前三位肯定是规定好的, BOM关联只关联前三位的主编码"
- 扫编码/搜编码能快速检索到对应物料

**SP8 必须等 SP4 完成后开工** (SP4 已完成编码前缀数字对齐 001/002/003 并落 `generateNextCode` 改动; SP8 在其之上叠加分段语义)。

---

## 2. 范围

### 2.1 本子项做什么

| 编号 | 功能 | 层 | P级 |
|------|------|----|-----|
| SP8-T01 | 物料分段字典表 (`material_code_segments`) + CRUD API | backend | P1 |
| SP8-T02 | 16位编码生成器 (扩展现有 `generateNextCode`) | backend | P1 |
| SP8-T03 | `RawMaterialType.primaryCode` 冗余前三位字段 | backend | P1 |
| SP8-T04 | `BomRecipeItem.primaryCodeRef` 关联键 (可选补充 materialTypeId 的编码搜索维度) | backend | P1 |
| SP8-T05 | 级联下拉 UI — 建档时: 选类型→选部位/品类→自动填入编码前缀 | web-admin | P1 |
| SP8-T06 | 编码搜索端点: 输入前缀或编码片段, 返回对应物料 | backend | P1 |
| SP8-缩小版 | 若客户周五说"不要严格16位": 只做T03+T04+T06 (主编码提取+BOM前三位关联+搜索) | backend | P0-可切 |

### 2.2 不做什么

- 已有物料编码的历史数据批量迁移 (存量编码保持原值, 新建才走16位; 迁移风险高, 客户也不需要存量改)
- RN-app 操作员端编码管理 (操作员只是领料/报工, 编码管理在 web-admin)
- 条码打印格式变更 (由 SP4 负责)
- 编码冲突的跨工厂防重 (工厂内唯一已由 `raw_material_types` 唯一约束保证)

---

## 3. 现状复用 (grep 验证)

| 可复用组件 | 文件路径 | 现状描述 | SP8复用方式 |
|-----------|---------|----------|------------|
| `generateNextCode(factoryId, category)` | `service/impl/RawMaterialTypeServiceImpl.java:614` | 扁平 `prefix+3位序号`, 前缀 YL/RL/BC/WL; SP4改为数字对齐 | SP8 扩展为: 若查到分段字典则走16位生成, 否则fallback SP4扁平方案(向后兼容) |
| `getMaterialCategoryPrefix(category)` | 同上:597 | 静态 switch, SP4 增数字映射 | SP8 替换为查 `material_code_segments` 字典 |
| `EncodingRuleServiceImpl` + `EncodingRule` 实体 | `service/impl/EncodingRuleServiceImpl.java`, `entity/config/EncodingRule.java` | 通用编码规则引擎, 支持 `{SEQ:N}`, `{PREFIX}` 等占位符; 现有 `encoding_rules` 表 | **不复用** — 该引擎针对单据流水号(MB-F001-...), 与物料分类编码语义不同; SP8 新建专用分段字典表 |
| `BomRecipeItem.materialTypeId` | `entity/bom/BomRecipeItem.java:54` | UUID 外键硬关联 `raw_material_types.id` | 保持不变; SP8 **新增** `primary_code_ref` VARCHAR(3) 冗余列, 方便"按类型搜BOM" |
| `RawMaterialType.code` | `entity/RawMaterialType.java:127` | VARCHAR(50), 工厂内唯一 | SP8 填充16位编码; 字段无需扩展(50够装16位) |
| `warehouse/material-types/list.vue` | web-admin | 已有 `categoryOptions` 字典下拉 | 在编码段下拉 UI 上叠加级联(类型→部位→品名) |
| `production/bom/index.vue` | web-admin | BOM配方编辑 UI | 增加"按主编码搜索物料"筛选栏 |

**关键发现**: `EncodingRule` + `EncodingRuleServiceImpl` 虽然存在, 但该引擎是给批次/发货单等**流水单号**设计的(含日期/工厂/重置周期), **不适合物料分类编码**。SP8 新建轻量 `material_code_segments` 字典表独立管理分段。

---

## 4. 数据模型增量

### 4.1 新表: `material_code_segments` (分段字典)

**Flyway**: `V20260911_02__create_material_code_segments.sql`

```sql
-- SP8: 16位分段编码字典
-- level: 1=类型(3位), 2=部位/品类(3位), 3=品名(4位)
CREATE TABLE material_code_segments (
    id              BIGSERIAL       PRIMARY KEY,
    factory_id      VARCHAR(50)     NOT NULL,
    level           SMALLINT        NOT NULL CHECK (level IN (1, 2, 3)),
    segment_code    VARCHAR(10)     NOT NULL,          -- 例: 001 / 001001 / 0010010001
    segment_label   VARCHAR(100)    NOT NULL,          -- 例: 原料 / 牛腱 / 牛腱(A级)
    parent_code     VARCHAR(10),                       -- level>=2 时指向上级 segment_code
    sort_order      INT             NOT NULL DEFAULT 0,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    CONSTRAINT uk_segment UNIQUE (factory_id, segment_code),
    CONSTRAINT fk_segment_factory FOREIGN KEY (factory_id) REFERENCES factories(id)
);

CREATE INDEX idx_mcs_factory_level  ON material_code_segments (factory_id, level);
CREATE INDEX idx_mcs_parent         ON material_code_segments (factory_id, parent_code);

COMMENT ON TABLE material_code_segments IS 'SP8: 物料16位分段编码字典 (类型/部位/品名三级)';
COMMENT ON COLUMN material_code_segments.segment_code IS '累积段编码: L1=3位(001), L2=6位(001001), L3=10位(0010010001)';

-- 种子数据: 六扇门通用类型
INSERT INTO material_code_segments (factory_id, level, segment_code, segment_label, parent_code, sort_order)
SELECT f.id, 1, '001', '原料', NULL, 1 FROM factories f WHERE f.id = 'F006'
ON CONFLICT (factory_id, segment_code) DO NOTHING;

INSERT INTO material_code_segments (factory_id, level, segment_code, segment_label, parent_code, sort_order)
SELECT f.id, 1, '002', '包材', NULL, 2 FROM factories f WHERE f.id = 'F006'
ON CONFLICT (factory_id, segment_code) DO NOTHING;

INSERT INTO material_code_segments (factory_id, level, segment_code, segment_label, parent_code, sort_order)
SELECT f.id, 1, '003', '辅料', NULL, 3 FROM factories f WHERE f.id = 'F006'
ON CONFLICT (factory_id, segment_code) DO NOTHING;
```

> **设计决策**: `segment_code` 用累积段 (L1=`001`, L2=`001001`, L3=`0010010001`) 而非相对段, 便于前缀搜索(`LIKE '001%'`)和16位最终拼装(后6位=`%06d`)。

### 4.2 `RawMaterialType` 增量字段

**Flyway**: `V20260911_03__add_primary_code_to_raw_material_types.sql`

```sql
-- SP8: 前三位主编码冗余列 (从 code 前三位提取/存储, 供 BOM 关联和快速搜索)
ALTER TABLE raw_material_types
    ADD COLUMN primary_code VARCHAR(3);

COMMENT ON COLUMN raw_material_types.primary_code IS
    'SP8: 16位编码前3位(类型段), 如 001=原料/002=包材/003=辅料; 历史数据可空';

CREATE INDEX idx_rmt_primary_code ON raw_material_types (factory_id, primary_code)
    WHERE primary_code IS NOT NULL;
```

**Entity 增量** (`RawMaterialType.java`):
```java
/** SP8: 16位编码前三位主编码 (类型段), 如 001/002/003; 历史数据可为空 */
@Column(name = "primary_code", length = 3)
private String primaryCode;
```

**DTO 四点原则** (per `feedback_dto_roundtrip_silent_drop`):
1. `RawMaterialTypeDTO.java` — 增 `primaryCode` String 字段
2. Service create: `materialType.setPrimaryCode(dto.getPrimaryCode())`; 若 `primaryCode == null && code != null && code.length() >= 3` 则自动提取 `code.substring(0, 3)`
3. Service update: null-guard `if (dto.getPrimaryCode() != null) materialType.setPrimaryCode(dto.getPrimaryCode())`
4. `convertToDTO`: `dto.setPrimaryCode(materialType.getPrimaryCode())`

### 4.3 `BomRecipeItem` 增量字段

**Flyway**: `V20260911_04__add_primary_code_ref_to_bom_recipe_items.sql`

```sql
-- SP8: BOM关联前三位主编码 (冗余; 主键关联仍是 material_type_id)
ALTER TABLE bom_recipe_items
    ADD COLUMN primary_code_ref VARCHAR(3);

COMMENT ON COLUMN bom_recipe_items.primary_code_ref IS
    'SP8: 物料前三位主编码冗余列; BOM按类型搜索/统计用; 与 material_type_id 不替代';

CREATE INDEX idx_bri_primary_code ON bom_recipe_items (factory_id, primary_code_ref)
    WHERE primary_code_ref IS NOT NULL;
```

**Entity 增量** (`BomRecipeItem.java`):
```java
/** SP8: 物料前三位主编码冗余(类型段); BOM按类型聚合/搜索; 不替代 materialTypeId 外键 */
@Column(name = "primary_code_ref", length = 3)
private String primaryCodeRef;
```

**DTO 四点原则**:
1. `BomRecipeItemDTO.java` — 增 `primaryCodeRef` String
2. Service create: `item.setPrimaryCodeRef(dto.getPrimaryCodeRef())` — 若空则从关联 `RawMaterialType.primaryCode` 回填
3. Service update: null-guard set
4. `convertToDTO`: 映射 `item.getPrimaryCodeRef()`

**注意**: `BomRecipeItem` 主键关联 `materialTypeId`(UUID 硬外键) **不变**。`primaryCodeRef` 是冗余列, 仅用于"按原料类型搜 BOM"和统计报表。

---

## 5. 组件与数据流

### 5.1 后端服务扩展

#### `MaterialCodeSegmentService` (新建)

```
MaterialCodeSegmentController   GET/POST/PUT/DELETE  /api/mobile/{factoryId}/material-segments
MaterialCodeSegmentService      CRUD + 树形列表
MaterialCodeSegmentRepository   extends JpaRepository<MaterialCodeSegment, Long>
MaterialCodeSegment             entity (对应 4.1 表)
MaterialCodeSegmentDTO          outputDTO
```

**端点设计**:
- `GET /{factoryId}/material-segments?level=1` — 返回指定层级列表 (级联下拉一次拉完)
- `GET /{factoryId}/material-segments/tree` — 返回3层完整树(一次 fetch 供前端级联用)
- `POST /{factoryId}/material-segments` — 创建节点
- `GET /{factoryId}/material-types/search-by-code?q={prefix}` — 按编码前缀搜物料

#### `RawMaterialTypeServiceImpl` 扩展 (`generateNextCode` 升级)

```java
// 新逻辑: 如果工厂已配置分段字典, 走16位生成; 否则 fallback 旧扁平方案
String generateNextCode(String factoryId, String category) {
    // 1. 查 material_code_segments 按 segment_label LIKE category 找 L1 节点
    // 2. 若找到且有 L2/L3 子节点: 取 L3(品名)的 segment_code(10位) + "%06d"(流水6位) = 16位
    // 3. 若未配置分段字典 (六扇门初期可能): fallback SP4扁平方案 (001+3位序号)
    // 向后兼容: 已建物料编码不变
}
```

**向后兼容保障**: `generateNextCode` 降级到 SP4 扁平方案的条件: `materialCodeSegmentRepository.countByFactoryIdAndLevel(factoryId, 1) == 0`。不影响已 ship 的 SP4 测试。

### 5.2 数据流

```
用户建档
  ↓ 选类型(L1: 001原料)
  ↓ 选部位/品类(L2: 001001牛腱)
  ↓ 选品名(L3: 0010010001牛腱A级)
  ↓ 前10位自动填入: 0010010001
  ↓ 后端生成后6位序号: 000001
  ↓ 最终编码: 0010010001000001 (16位)
  ↓ primaryCode = "001"
  ↓ 写入 raw_material_types.code + primary_code
```

---

## 6. 端归属

| 功能 | backend | web-admin | RN-app |
|------|---------|-----------|--------|
| 分段字典 CRUD | ✅ | ✅ (物料配置页) | — |
| 级联下拉建档 | ✅ API | ✅ UI | — |
| 编码前缀搜索 | ✅ | ✅ | — |
| BOM主编码关联 | ✅ (冗余列) | ✅ (筛选栏) | — |
| 操作员领料/报工 | — | — | 无新UI(编码显示即可) |

RN-app 不做编码管理 (蓝图 §5: RN=操作员手机, 管理配置在 web-admin)。

---

## 7. 错误处理 (fool-proof 4位一体)

### 7.1 建档编码冲突 (幂等 Rule 4)

```
POST /material-types → 409 CONFLICT
{
  "success": false,
  "message": "编码 0010010001000001 已被「牛腱A级(标准)」使用, 请选择其他编码",
  "code": "CODE_ALREADY_EXISTS",
  "data": { "existingId": "RMT_...", "existingName": "牛腱A级(标准)" },
  "hint": "可在编码列表中查看该物料"
}
```

前端 catch 409 → ElMessageBox.confirm "是否前往查看已有物料 [牛腱A级(标准)]?"

### 7.2 分段字典未配置 (Rule 5 dead-end)

级联下拉无数据 → 空状态组件: "该工厂尚未配置编码字典 — 点击前往「物料编码配置」页面初始化" + 跳转按钮。

### 7.3 BOM关联主编码不存在

新建 BomRecipeItem 时, 如果 `primaryCodeRef` 传入但在 `material_code_segments` 中不存在 L1 节点 → **不报错, 仅告警**日志(主关联仍以 `materialTypeId` 为准)。

### 7.4 Error toast 4位一体

- toast 文案 = 后端 response.message (不 fallback "操作失败")
- error toast sticky: `duration: 0, showClose: true`
- 含 next action 提示 (hint 字段)
- 后端 message 含具体编码/名称 context (Rule 2)

---

## 8. 测试策略

### 8.1 后端单元测试 (TDD先红后绿)

| 测试类 | 覆盖场景 |
|-------|---------|
| `MaterialCodeSegmentServiceTest` | 树形查询/CRUD/软删除/工厂隔离 |
| `RawMaterialTypeServiceImplCodegenTest` | 16位生成(有分段字典) / fallback扁平(无字典) / 编码冲突409 |
| `BomRecipeItemPrimaryCodeTest` | primaryCodeRef 自动回填 / null安全 / convertToDTO映射 |

**验收命令**: `./mvnw test -pl backend/java/cretas-api -Dtest="MaterialCodeSegment*,*PrimaryCode*" -DfailIfNoTests=false`

### 8.2 前端类型检查

```bash
cd web-admin && npx vue-tsc --noEmit
```

### 8.3 集成验收 (headed, zh-CN)

1. 进入「物料管理 → 编码配置」, 初始化六扇门分段字典 (001原料/002包材/003辅料 + 子节点)
2. 新建原料: 级联选 001→001001→0010010001 → 编码自动显示 `0010010001000001`
3. BOM配方: 添加物料, 验证 `primaryCodeRef=001` 已存储
4. 搜索: 输入 `001` → 返回所有原料类型

---

## 9. 依赖

| 依赖 | 说明 |
|------|------|
| **SP4 (V20260910_30~39)** | SP4 已加 `MaterialBatch.factory_number/origin_place`、`TaxRate` enum、`BomRecipeItem.per_portion`、编码前缀数字对齐; SP8 接续在其之上扩展生成器逻辑 |
| `raw_material_types` (已存在) | SP8 加 `primary_code` 列 |
| `bom_recipe_items` (已存在) | SP8 加 `primary_code_ref` 列; SP4 先加 `per_portion`/`semi_finished_ref_code` |
| `factories` 表 | `material_code_segments.factory_id` 外键 |

**scope-lock 冲突**: SP8 改 `BomRecipeItem` — SP4 也改 `BomRecipeItem`(加 per_portion/semi_finished_ref)。两子项**串行**: SP4 PR merge 后 SP8 才开工(off fresh origin/main)。

---

## ⚠️ 跨子项依赖/风险

1. **SP4 scope-lock 冲突** (`BomRecipeItem`): SP4 和 SP8 都改 `bom_recipe_items` 表和 entity。必须 SP4 merge 进 main 后 SP8 才开工(off origin/main), 否则 PR scope 夹带 SP4 改动。

2. **Flyway 号段冲突**: `V20260911_01` **已被占用** (`V20260911_01__intent_records_shadow_columns.sql`), 实际可用号从 `V20260911_02` 起。SP8 号段为 `V20260911_02 ~ V20260911_09`。

3. **编码长度兼容性**: `RawMaterialType.code` 现为 VARCHAR(50), 存16位无问题。但现有前端若对 code 做任何固定长度假设 → 需排查 `material-types/list.vue` 的 code 输入框 `maxlength` 属性。

4. **向后兼容**: 存量物料编码(YL001/RL001/BC001格式, SP4改为数字后是001001)是历史数据, **不做强制迁移**。`generateNextCode` 降级路径保证新建走16位, 存量走原格式(搜索兼容)。

5. **客户周五确认风险**: 若客户周五说"不需要严格16位" → SP8 可缩减为仅 T03+T04+T06(主编码提取+BOM前三位关联+搜索API), 约去掉 `material_code_segments` 新表和级联UI, Flyway 只有 V20260911_03+V20260911_04 两个小 migration。请在周五演示后立即更新本 spec。
