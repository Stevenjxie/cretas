# SP8 · 16位分段编码体系 — 实施计划 (Implementation Plan)

**子项**: SP8 · 16位分段编码体系
**蓝图波次**: Wave 3 (依赖 SP4)
**Flyway 号段**: `V20260911_02 ~ V20260911_09`
**Flyway 冲突注意**: `V20260911_01` 已被占用 (`intent_records_shadow_columns.sql`)，SP8从 `V20260911_02` 起
**生成**: 2026-06-09 Sonnet in-harness planner

> ⚠️ **前置条件**: SP4 (V20260910_30~39) 完全 merge 进 main 后才开此 SP 的工作树。SP8 off fresh `origin/main`。
> ⚠️ **客户周五确认**: 若客户说"不要16位分段" → 仅执行 Task 3 + Task 4 (主编码提取+BOM关联), 跳过 Task 1+Task 2+Task 5。

---

## Fleet 现状

- **Codex/GPT**: 暂停, 不在 dispatch 池
- **Composer**: UI/样式/vue 组件 → out-of-harness (需 brief 卡自包含规则摘要)
- **Sonnet in-harness**: 规则重后端 Java/Flyway/业务逻辑 → 首选
- **Opus (本 organizer)**: 红线终审 (SP8 无红线项, Opus 轻量过审即可)

---

## 工作树创建

```bash
# SP4 merge 进 main 后:
git worktree add -b feat/SP8-16digit-coding ../cretas-SP8-coding origin/main
cd ../cretas-SP8-coding
```

---

## Scope-lock 声明

| 文件 / 目录 | SP8 独占 | 冲突 SP | 处理 |
|-----------|---------|--------|------|
| `db/flyway/V20260911_02__*.sql` | ✅ SP8 | — | 新建, 无冲突 |
| `db/flyway/V20260911_03__*.sql` | ✅ SP8 | — | 新建, 无冲突 |
| `db/flyway/V20260911_04__*.sql` | ✅ SP8 | — | 新建, 无冲突 |
| `backend/.../entity/bom/BomRecipeItem.java` | SP8 加 `primaryCodeRef` | SP4 加 `perPortion`/`semiFinishedRefCode` | **串行**: SP4 merge 后 off origin/main |
| `backend/.../entity/RawMaterialType.java` | SP8 加 `primaryCode` | SP4 加 taxRate | **串行**: 同上 |
| `backend/.../service/impl/RawMaterialTypeServiceImpl.java` | SP8 扩展 `generateNextCode` | SP4 改前缀数字对齐 | **串行**: 同上 |
| `web-admin/src/views/warehouse/material-types/` | SP8 加级联UI | SP4 加 taxRate dropdown | **串行**: 同上 |

**Flyway 查重纪律** (merge 前执行):
```bash
git ls-tree origin/main db/flyway/ | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
# 若有重复输出 → 立即重编号再 PR
```

---

## Task 清单

### Task 1 — 物料分段字典后端 (Sonnet in-harness)

**目标**: 新建 `material_code_segments` 表 + Entity + CRUD API + 树形查询端点

**worktree**: 继承 feat/SP8-16digit-coding

**允许改**:
```
db/flyway/V20260911_02__create_material_code_segments.sql  (新建)
backend/java/cretas-api/src/main/java/com/cretas/aims/
  entity/material/MaterialCodeSegment.java             (新建)
  dto/material/MaterialCodeSegmentDTO.java             (新建)
  repository/MaterialCodeSegmentRepository.java         (新建)
  service/MaterialCodeSegmentService.java               (新建接口)
  service/impl/MaterialCodeSegmentServiceImpl.java      (新建)
  controller/MaterialCodeSegmentController.java         (新建)
```

**禁改**: BomRecipeItem / RawMaterialTypeServiceImpl / SP4 相关文件 (Task 2-4 负责)

**TDD 顺序**:
1. 先写 `MaterialCodeSegmentServiceTest`: 树形查询/工厂隔离/软删除/父子节点级联检查
2. 实现 Service + Repository
3. 写 `MaterialCodeSegmentControllerTest`: GET /tree / GET?level=1 / POST / PUT / DELETE
4. 实现 Controller

**关键实现注意**:
- Entity 必须继承 `BaseEntity` (created_at/updated_at/deleted_at)
- 软删除: `@Where(clause = "deleted_at IS NULL")`
- `segment_code` 用累积段 (L1=3位, L2=6位, L3=10位), 便于 `LIKE '001%'` 前缀搜索
- 工厂隔离: 所有查询必须带 `factory_id` 条件
- `findByFactoryIdTree()`: 单次查询全3层, 前端级联一次 fetch 用; 树形拼装在 Service 层 (不在 SQL)

**验收**:
```bash
./mvnw test -pl backend/java/cretas-api \
  -Dtest="MaterialCodeSegmentServiceTest,MaterialCodeSegmentControllerTest" \
  -DfailIfNoTests=false
# 期望: 全绿, 覆盖 CRUD + 树形 + 工厂隔离
```

---

### Task 2 — 16位编码生成器扩展 (Sonnet in-harness)

**目标**: 扩展 `generateNextCode` 支持16位分段编码 (有字典走16位, 无字典 fallback SP4扁平)

**依赖**: Task 1 (需要 `MaterialCodeSegmentRepository` 注入)

**允许改**:
```
backend/java/cretas-api/src/main/java/com/cretas/aims/
  service/impl/RawMaterialTypeServiceImpl.java           (扩展 generateNextCode)
  dto/material/RawMaterialTypeDTO.java                   (加 segmentCode L3字段, 用于前端传递级联选择结果)
```

**TDD 顺序**:
1. 先写 `RawMaterialTypeServiceImplCodegenTest`:
   - `testGenerateCode_withSegmentDict_returns16Digits()` — 有L3节点时返回16位
   - `testGenerateCode_withoutSegmentDict_fallbackFlat()` — 无字典时fallback
   - `testGenerateCode_sequenceIncrement()` — 同前缀第二次+1
   - `testGenerateCode_conflict_throws409()` — 编码已存在时409
2. 实现扩展逻辑:

```java
// 核心逻辑骨架 (实现时完善)
String generateNextCode(String factoryId, String category, String segmentCode) {
    // segmentCode: 前端级联选择结果, L3累积段(10位) 或 null
    if (segmentCode != null && segmentCode.length() == 10) {
        // 16位路径: segmentCode(10位) + 序号(6位)
        long seq = getNextSequenceForSegment(factoryId, segmentCode);
        return String.format("%s%06d", segmentCode, seq);
    }
    // fallback: SP4扁平方案
    return generateNextCodeFlat(factoryId, category);
}

long getNextSequenceForSegment(String factoryId, String segmentCode) {
    // LIKE 'segmentCode%' 找同前缀已有编码的最大序号
    List<String> existing = materialTypeRepository
        .findCodesByFactoryIdAndCodePrefix(factoryId, segmentCode);
    // 过滤前10位=segmentCode的, 取后6位max+1
    ...
}
```

**验收**:
```bash
./mvnw test -pl backend/java/cretas-api \
  -Dtest="RawMaterialTypeServiceImplCodegenTest" \
  -DfailIfNoTests=false
```

---

### Task 3 — `RawMaterialType.primaryCode` + DTO 四点 (Sonnet in-harness)

**目标**: 加 `primary_code` 冗余列, DTO 四点完整同步

**Flyway**: `V20260911_03__add_primary_code_to_raw_material_types.sql`

**允许改**:
```
db/flyway/V20260911_03__add_primary_code_to_raw_material_types.sql  (新建)
backend/java/cretas-api/src/main/java/com/cretas/aims/
  entity/RawMaterialType.java                (加 primaryCode 字段)
  dto/material/RawMaterialTypeDTO.java        (加 primaryCode — DTO四点之1)
  service/impl/RawMaterialTypeServiceImpl.java (create set + update null-guard + auto-extract — DTO四点之2,3)
  service/impl/RawMaterialTypeServiceImpl.java (convertToDTO 映射 — DTO四点之4)
```

**DTO 四点检查单** (per `feedback_dto_roundtrip_silent_drop`):
- [ ] `RawMaterialTypeDTO.primaryCode` 字段声明
- [ ] `createMaterialType`: `materialType.setPrimaryCode(...)` — 若 dto.primaryCode 为空则从 dto.code 前3位自动提取
- [ ] `updateMaterialType`: `if (dto.getPrimaryCode() != null) materialType.setPrimaryCode(...)`
- [ ] `convertToDTO`: `dto.setPrimaryCode(materialType.getPrimaryCode())`

**TDD**:
1. `RawMaterialTypePrimaryCodeTest.testAutoExtractPrimaryCode()` — 创建时 primaryCode 自动从 code 前三位提取
2. `testPrimaryCodeNullSafe()` — 历史数据 primaryCode 可空, 更新不会覆盖为 null

**验收**:
```bash
./mvnw test -pl backend/java/cretas-api \
  -Dtest="RawMaterialTypePrimaryCodeTest" \
  -DfailIfNoTests=false
```

---

### Task 4 — `BomRecipeItem.primaryCodeRef` + DTO 四点 (Sonnet in-harness)

**目标**: BOM 关联键 — 加 `primary_code_ref` 冗余列, DTO 四点同步

**Flyway**: `V20260911_04__add_primary_code_ref_to_bom_recipe_items.sql`

**依赖**: Task 3 完成后 (需从 `RawMaterialType.primaryCode` 回填)

**允许改**:
```
db/flyway/V20260911_04__add_primary_code_ref_to_bom_recipe_items.sql  (新建)
backend/java/cretas-api/src/main/java/com/cretas/aims/
  entity/bom/BomRecipeItem.java              (加 primaryCodeRef 字段)
  dto/bom/BomRecipeItemDTO.java              (加 primaryCodeRef — DTO四点之1)
  service/impl/BomRecipeServiceImpl.java      (create set + update null-guard + auto-backfill — DTO四点之2,3)
  service/impl/BomRecipeServiceImpl.java      (convertToDTO 映射 — DTO四点之4)
```

**DTO 四点检查单**:
- [ ] `BomRecipeItemDTO.primaryCodeRef` 字段声明
- [ ] create: `item.setPrimaryCodeRef(dto.getPrimaryCodeRef())` — 若空且有关联 `materialTypeId` 则查 `RawMaterialType.primaryCode` 回填
- [ ] update: null-guard set
- [ ] `convertToDTO`: 映射

**关键**: `primaryCodeRef` **不替代** `materialTypeId` UUID 外键 — 主关联不变, 新列仅补充搜索维度。

**TDD**:
1. `BomRecipeItemPrimaryCodeTest.testAutoBackfillFromMaterialType()` — 创建BOM行时自动从关联物料回填前三位
2. `testPrimaryCodeRefPreserved()` — 更新时 null 不覆盖已有值

**验收**:
```bash
./mvnw test -pl backend/java/cretas-api \
  -Dtest="BomRecipeItemPrimaryCodeTest" \
  -DfailIfNoTests=false
```

---

### Task 5 — 级联下拉 UI (web-admin Composer) [仅客户确认16位时执行]

**目标**: 物料建档对话框加3级级联下拉 (类型→部位/品类→品名), 选择后自动填入编码前缀

**模型**: Composer (UI/Vue 组件)

**out-of-harness brief 必须自包含以下规则摘要**:
```
规则摘要 (Composer 无 .claude/rules):
1. API 响应格式: { success, data, message } — 前端 if (!response.success) throw new Error(response.message)
2. TypeScript: 禁止 as any
3. Element Plus 错误 toast: duration: 0, showClose: true (sticky)
4. fool-proof Rule 1: 打开对话框立即 fetch 级联字典数据
5. fool-proof Rule 5: 若 /material-segments/tree 返空 → EmptyState 组件 + 跳转配置页按钮
6. 字段命名 camelCase (前端), snake_case 仅在 API 请求体中
```

**允许改**:
```
web-admin/src/views/warehouse/material-types/
  components/MaterialCodeCascader.vue       (新建: 级联下拉组件)
  list.vue                                  (在建档 dialog 中嵌入级联组件)
```

**禁改**: 后端 Java 文件 / Flyway SQL (仅改 vue 层)

**Composer Brief 卡** (即贴即用):

---

> **目标**: 在 web-admin 物料类型建档对话框 (`list.vue`) 中加入3级级联下拉, 让用户选择 类型→部位/品类→品名, 选完后自动填入编码前缀字段 (前10位)。
>
> **文件**:
> - 新建 `web-admin/src/views/warehouse/material-types/components/MaterialCodeCascader.vue`
> - 修改 `web-admin/src/views/warehouse/material-types/list.vue`
>
> **API**: `GET /api/mobile/{factoryId}/material-segments/tree` → `{ success: true, data: [{ segmentCode, segmentLabel, children: [{...}] }] }`
>
> **交互逻辑**:
> 1. 打开建档 dialog 时立即 fetch tree (1次请求, 全3层)
> 2. L1 下拉选类型 → L2 下拉 options 更新 → L3 下拉 options 更新
> 3. 选完L3后 `form.segmentCode = selectedL3.segmentCode` (10位), `form.codePrefixDisplay = selectedL3.segmentLabel` (展示给用户看的文字, 不是裸编码)
> 4. 提交时把 `segmentCode` (10位) 传给后端 `POST /api/mobile/{factoryId}/material-types`, 后端自动生成后6位序号
> 5. 若 tree 为空 → 显示: "该工厂尚未配置编码字典" + el-button 跳转 `/system/material-code-config`
>
> **规则摘要 (无 .claude/rules, 必读)**:
> - API 响应格式: `{ success, data, message }` — 失败时 `throw new Error(response.message)`
> - error toast: `ElMessage({ message, type: 'error', duration: 0, showClose: true })` (sticky, 不自动关)
> - TypeScript 禁止 `as any`
> - 编码前缀展示给用户看类别名, 不展示裸编码 (如显示 "牛腱" 而非 "001001")
>
> **验收**: `npx vue-tsc --noEmit` 无错 + 页面能正常级联选择并填入

---

**验收** (任务完成后):
```bash
cd web-admin && npx vue-tsc --noEmit
# 期望: 0 errors
```

---

### Task 6 — 编码前缀搜索 API (Sonnet in-harness)

**目标**: `GET /{factoryId}/material-types/search-by-code?q={prefix}` — 按编码前缀/片段快速搜物料

**允许改**:
```
backend/java/cretas-api/src/main/java/com/cretas/aims/
  repository/RawMaterialTypeRepository.java     (加 findByFactoryIdAndCodeStartingWith)
  controller/RawMaterialTypeController.java      (加 GET /material-types/search-by-code)
```

**实现要点**:
- 查询: `SELECT * FROM raw_material_types WHERE factory_id = :fid AND code LIKE :q% AND deleted_at IS NULL`
- 返回 `List<RawMaterialTypeDTO>` 最多50条 (性能保护)
- 权限: `@RequireRole({"FACTORY_SUPER_ADMIN", "ADMIN", "PURCHASING_STAFF", "WAREHOUSE_MANAGER"})` (看 SP4 已有 RBAC 定义)

**TDD**: `RawMaterialTypeSearchByCodeTest` — 前缀匹配 / 空结果 / 工厂隔离

**验收**:
```bash
./mvnw test -pl backend/java/cretas-api \
  -Dtest="RawMaterialTypeSearchByCodeTest" \
  -DfailIfNoTests=false
```

---

## Flyway 查重纪律 (PR 前必做)

```bash
# 在 feat/SP8-16digit-coding worktree 中, PR 前执行:
git fetch origin
git ls-tree origin/main db/flyway/ | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
# 若有任何输出 → 重编号本 SP 的 migration 文件, 再 PR
```

**已知冲突**: `V20260911_01` 已被 `intent_records_shadow_columns.sql` 占用。SP8 实际使用: `02, 03, 04` (最多到09备用)。

---

## 验收流程 (全任务完成后)

### 1. 测试套件

```bash
cd ../cretas-SP8-coding
./mvnw test -pl backend/java/cretas-api \
  -Dtest="MaterialCodeSegment*,*PrimaryCode*,*Codegen*,*SearchByCode*" \
  -DfailIfNoTests=false
# 期望: ALL GREEN

cd web-admin && npx vue-tsc --noEmit
# 期望: 0 errors
```

### 2. Flyway 查重

```bash
git ls-tree origin/main db/flyway/ | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
# 期望: 无输出 (无重复)
```

### 3. PR scope 干净

```bash
git diff origin/main...HEAD --stat
# 期望: 只有 SP8 允许改的文件, 无 SP4/其他子项文件带入
```

### 4. 集成功能验收 (headed, 若已部署 test 环境)

```
场景1: 编码字典配置
  → 进「物料配置」建3级字典: 001原料→001001牛腱→0010010001牛腱A级
  → 保存成功, tree API返回正确层级结构

场景2: 建档级联生成
  → 新建原料: 级联选 001→001001→0010010001
  → 提交后 code="0010010001000001", primaryCode="001"
  → DB验证: raw_material_types.primary_code='001'

场景3: BOM关联
  → 新建BOM配方行, 选该物料
  → DB验证: bom_recipe_items.primary_code_ref='001'

场景4: 编码搜索
  → GET /material-types/search-by-code?q=001
  → 返回所有原料类型物料
```

---

## 分发总览

| # | 任务 | 模型 | 可并行 | scope 锁 |
|---|------|------|--------|---------|
| 1 | 分段字典后端 | Sonnet in-harness | 仅依赖 SP4 merge | `MaterialCodeSegment*` 新文件 |
| 2 | 编码生成器扩展 | Sonnet in-harness | 依赖 Task 1 | `RawMaterialTypeServiceImpl` |
| 3 | primaryCode 字段 | Sonnet in-harness | 与 Task 1 可并行 | `RawMaterialType.java` + DTO |
| 4 | BOM primaryCodeRef | Sonnet in-harness | 依赖 Task 3 | `BomRecipeItem.java` + DTO |
| 5 | 级联下拉 UI | Composer | 依赖 Task 1+2 | `material-types/list.vue` + 新组件 |
| 6 | 编码搜索 API | Sonnet in-harness | 与 Task 3 可并行 | `RawMaterialTypeController` |

**最优路径** (有单个 Sonnet session 时):
```
Task 1 → Task 3 ─┐
                  ├→ Task 4 → Task 5 (Composer)
Task 6 ──────────┘
Task 2 (依赖 Task 1)
```

**缩小版** (客户周五说不要16位分段):
```
Task 3 → Task 4 → Task 6
# 只做3个 migration + 3个任务, 约 1-2小时工作量
# 跳过 Task 1(字典表) / Task 2(16位生成器) / Task 5(级联UI)
```

---

## 交接协议

完成 → PR off `origin/main`:
1. `git diff origin/main...HEAD --stat` 确认 scope 干净
2. Flyway 查重无冲突
3. 全测试套件绿
4. PR 描述含: 任务清单 / Flyway 号 / 验收证据
5. Opus 终审 merge + 部署 (SP8 无红线项, 轻量终审)
