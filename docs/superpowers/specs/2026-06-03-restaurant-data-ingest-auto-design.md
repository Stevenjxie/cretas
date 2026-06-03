# G7 取数自动化 — OCR 配照 + 语音录入 (供应商进货单 + 领料录入)

## 目标

用 DashScope Vision OCR 扫描供应商送货单照片, 自动解析行项写入 `supplier_delivery_notes` / `agg_supplier_price` gold 表; 同时允许员工用语音录入 MaterialRequisition (领料单), 将两条高频人工录入路径的人力成本下降 80%+.

---

## 范围

### MVP In (Tier A — P0)

- OCR 流水: 前端拍照/上传 → Java REST → `DashScopeVisionClient.analyzeImage()` → JSON 解析 → `supplier_delivery_notes` (cretas_db) 持久化 + `agg_supplier_price` (smartbi_db) gold 行追加
- `agg_supplier_price` gold 表解锁 G5 成本卡完整版和 G4 成本侧诊断
- 照片 hash 幂等 (SHA-256, 同照片 409 跳编辑, Rule 4)
- 置信度 < 0.75 时低置信提示 + 重拍引导 (Rule 5, Rule 1)
- 前端: web-admin Vue 页面 + 移动端 RN 补录 (MVP 仅 web-admin)
- 供应商列表下拉 (来自 cretas_db `suppliers` 表, Java API 已有)

### MVP In (Tier B — P1)

- 语音录入 MaterialRequisition: 复用 `IFlytekVoiceService.recognize()` → 后端 NLP 意图解析提取食材/数量/单位 → 自动填写 MaterialRequisition 草稿表单
- 语音结果预填 + 人工确认二段式 (Rule 2, Rule 4 幂等)
- 复用 Tool-Skill 架构: 新建 `RestaurantVoiceRequisitionTool` 调 `MaterialRequisitionRepository`

### Out (Tier C — defer Phase 2)

- 秤传感器/摄像头自动抓取 (硬件依赖)
- 语音直接提交 (无人工确认步骤, 当前用二段式草稿更安全)
- 发票 PDF OCR 供应商进货单 (InvoiceRecord 路径已有, 非本特性)
- 移动端 RN 拍照上传 OCR (Phase 2)

---

## 数据模型

### 新表 1 — `supplier_delivery_notes` (cretas_db, Java Flyway)

**版本**: `V20260608_01__supplier_delivery_notes.sql`

```sql
-- PR 前必须检查 origin/main: git ls-tree origin/main db/flyway | grep V20260608
-- 确认 V20260608 下无其他文件再用此版本号

CREATE TABLE IF NOT EXISTS supplier_delivery_notes (
    id                  VARCHAR(191)    NOT NULL PRIMARY KEY,
    factory_id          VARCHAR(100)    NOT NULL,
    -- OCR/人工录入来源
    source_type         VARCHAR(20)     NOT NULL DEFAULT 'OCR',  -- OCR | MANUAL
    -- 照片幂等键 (Rule 4)
    photo_hash          VARCHAR(64)     UNIQUE,                  -- SHA-256 of uploaded bytes
    photo_oss_url       VARCHAR(500),
    -- 供应商信息 (来自 OCR 或下拉选择)
    supplier_id         VARCHAR(191),                            -- FK suppliers.id (nullable for OCR-created)
    supplier_name       VARCHAR(200),
    delivery_date       DATE            NOT NULL,
    note_number         VARCHAR(100),                            -- 送货单号 (OCR 提取或人工)
    -- 汇总
    total_amount        NUMERIC(15,2),
    -- OCR 置信度 (0.000-1.000)
    ocr_confidence      NUMERIC(4,3),
    ocr_raw_json        TEXT,                                    -- LLM raw response for debug
    ocr_error_message   TEXT,
    ocr_parsed_at       TIMESTAMP,
    -- 审核状态
    status              VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',  -- DRAFT | CONFIRMED | REJECTED
    confirmed_by        BIGINT,
    confirmed_at        TIMESTAMP,
    reject_reason_code  VARCHAR(50),                             -- Rule 3 enum
    reject_reason_note  TEXT,
    -- BaseEntity 字段
    created_by          BIGINT,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE TABLE IF NOT EXISTS supplier_delivery_note_lines (
    id                  BIGSERIAL       PRIMARY KEY,
    note_id             VARCHAR(191)    NOT NULL REFERENCES supplier_delivery_notes(id) ON DELETE CASCADE,
    factory_id          VARCHAR(100)    NOT NULL,
    ingredient_name     VARCHAR(200)    NOT NULL,                -- OCR 提取原文
    raw_material_type_id VARCHAR(191),                          -- 匹配到的 cretas_db.raw_material_types.id
    quantity            NUMERIC(14,4),
    unit                VARCHAR(20),
    unit_price          NUMERIC(12,4),
    line_amount         NUMERIC(15,2),
    ocr_confidence      NUMERIC(4,3),                           -- 行级置信度
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sdn_factory_date        ON supplier_delivery_notes (factory_id, delivery_date DESC);
CREATE INDEX IF NOT EXISTS idx_sdn_supplier            ON supplier_delivery_notes (factory_id, supplier_id);
CREATE INDEX IF NOT EXISTS idx_sdn_status              ON supplier_delivery_notes (factory_id, status);
CREATE INDEX IF NOT EXISTS idx_sdnl_note_id            ON supplier_delivery_note_lines (note_id);
CREATE INDEX IF NOT EXISTS idx_sdnl_factory_material   ON supplier_delivery_note_lines (factory_id, raw_material_type_id);
```

### 新表 2 — `agg_supplier_price` (smartbi_db, Python migration)

**文件**: `backend/python/smartbi/database/migrations/V20260608_01__agg_supplier_price.sql`

```sql
-- 供应商进价 gold 表 — 每次 OCR 确认写一行, 支持进价趋势分析 + G5 成本卡
CREATE TABLE IF NOT EXISTS agg_supplier_price (
    id                  BIGSERIAL       PRIMARY KEY,
    factory_id          VARCHAR(50)     NOT NULL,
    source_note_id      VARCHAR(191),                           -- 来源 supplier_delivery_notes.id
    supplier_id         VARCHAR(191),
    supplier_name       VARCHAR(200),
    ingredient_name     VARCHAR(200)    NOT NULL,
    normalized_name     VARCHAR(200)    NOT NULL,               -- _normalize_name() 一致
    raw_material_type_id VARCHAR(191),                          -- cretas_db FK (denormalized)
    ingredient_id       BIGINT,                                 -- FK dim_ingredient.ingredient_id (nullable, ETL 填)
    delivery_date       DATE            NOT NULL,
    unit_price          NUMERIC(12,4)   NOT NULL,
    quantity            NUMERIC(14,4),
    unit                VARCHAR(20),
    line_amount         NUMERIC(15,2),
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW()
);

ALTER TABLE agg_supplier_price ENABLE ROW LEVEL SECURITY;
ALTER TABLE agg_supplier_price FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_supplier_price;
CREATE POLICY tenant_isolation ON agg_supplier_price FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

-- 历史进价趋势: (factory, ingredient) → date ASC
CREATE INDEX IF NOT EXISTS idx_agg_sp_factory_ingredient ON agg_supplier_price (factory_id, normalized_name, delivery_date DESC);
CREATE INDEX IF NOT EXISTS idx_agg_sp_factory_supplier   ON agg_supplier_price (factory_id, supplier_id, delivery_date DESC);
CREATE INDEX IF NOT EXISTS idx_agg_sp_factory_date       ON agg_supplier_price (factory_id, delivery_date DESC);

-- GRANT — 历史复发 2 次的陷阱, 必须显式给 smartbi_user DML
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_supplier_price TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE agg_supplier_price_id_seq TO smartbi_user;
```

### 已有复用 gold 表 (无修改)

- `dim_ingredient` (`smartbi_db`) — `ingredient_id BIGSERIAL PK`, `source_pk`, `name`, `normalized_name`, `unit_price`, `factory_id`; upsert 由 `restaurant_ops_etl.sync_dim_ingredient()` 维护
- `fact_restaurant_requisition` (`smartbi_db`) — 领料记录; 已由 ETL 从 cretas_db `material_requisitions` 同步

---

## 后端组件

### Tier A — OCR 供应商单

#### A1. Java 实体

**新建**: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/restaurant/SupplierDeliveryNote.java`

```java
@Entity @Table(name = "supplier_delivery_notes") @Where(clause = "deleted_at IS NULL")
// @Data @EqualsAndHashCode(callSuper=true) @NoArgsConstructor @AllArgsConstructor
// UUID @PrePersist; extends BaseEntity
// 字段: id, factoryId, sourceType(enum OCR/MANUAL), photoHash, photoOssUrl,
//        supplierId, supplierName, deliveryDate, noteNumber, totalAmount,
//        ocrConfidence(BigDecimal), ocrRawJson(TEXT), ocrErrorMessage, ocrParsedAt,
//        status(enum DRAFT/CONFIRMED/REJECTED), confirmedBy, confirmedAt,
//        rejectReasonCode, rejectReasonNote, createdBy
// @OneToMany(cascade=ALL, orphanRemoval=true, mappedBy="note") lines: List<SupplierDeliveryNoteLine>
```

**新建**: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/restaurant/SupplierDeliveryNoteLine.java`

```java
@Entity @Table(name = "supplier_delivery_note_lines")
// 字段: id(Long BIGSERIAL), noteId(FK VARCHAR 191), factoryId, ingredientName,
//        rawMaterialTypeId, quantity, unit, unitPrice, lineAmount, ocrConfidence
```

#### A2. Repository

**新建**: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/restaurant/SupplierDeliveryNoteRepository.java`

```java
public interface SupplierDeliveryNoteRepository extends JpaRepository<SupplierDeliveryNote, String> {
    Optional<SupplierDeliveryNote> findByPhotoHash(String photoHash);
    Page<SupplierDeliveryNote> findByFactoryIdAndStatusAndDeletedAtIsNull(
        String factoryId, String status, Pageable pageable);
    Page<SupplierDeliveryNote> findByFactoryIdAndDeliveryDateBetweenAndDeletedAtIsNull(
        String factoryId, LocalDate from, LocalDate to, Pageable pageable);
}
```

#### A3. Java Service

**新建**: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/restaurant/SupplierDeliveryNoteService.java` (interface + `impl/SupplierDeliveryNoteServiceImpl.java`)

关键方法签名:

```java
// OCR 解析并持久化草稿 (Rule 4 幂等: photoHash 已存在返回已有 note)
SupplierDeliveryNote parseAndDraft(String factoryId, Long userId,
    byte[] photoBytes, String photoContentType,
    LocalDate deliveryDate, String supplierId);

// 人工确认草稿 → CONFIRMED + 写 agg_supplier_price (调 Python)
SupplierDeliveryNote confirmNote(String factoryId, String noteId, Long userId);

// 拒绝草稿
SupplierDeliveryNote rejectNote(String factoryId, String noteId,
    String rejectReasonCode, String rejectReasonNote, Long userId);

// 获取限制 (Rule 1): 当月已录 N 条, 可继续添加 (无业务上限, 返回 count)
Map<String, Object> getLimits(String factoryId, LocalDate month);
```

**`parseAndDraft` 内部流程**:
1. `SHA-256(photoBytes)` → 查 `findByPhotoHash` → 已存在 → 抛 `BusinessException("已有草稿 SDN-XXX", 409, existingId)` (Rule 4)
2. 上传照片到 OSS → `photoOssUrl`
3. 调 `DashScopeVisionClient.analyzeImage(ossUrl, SUPPLIER_NOTE_OCR_PROMPT)` → `InvoiceParseResult`-style 结构解析
4. OCR 置信度 < 0.75 → status=DRAFT, `ocrConfidence` 写入, 前端由置信度判断是否提示重拍 (Rule 5)
5. 解析行项: 对每行调 `RawMaterialTypeRepository.findByFactoryIdAndNameLike()` 做软匹配 → 填 `rawMaterialTypeId`
6. 持久化 `SupplierDeliveryNote` + lines

**`confirmNote` 内部流程**:
1. `findById` + 校验 status==DRAFT
2. status → CONFIRMED, confirmedBy/At
3. `save(note)` → 同步调 Python `/api/smartbi/gold/supplier-price/batch-upsert` 写 `agg_supplier_price`
4. 同步触发 `restaurant_ops_etl.sync_dim_ingredient` (via `POST /api/smartbi/gold/etl/trigger`) 以更新 `dim_ingredient.unit_price` — 用 `REQUIRES_NEW` 隔离, fail-soft 不污染主事务

#### A4. OCR Prompt

```java
// SupplierDeliveryNoteService 内静态常量
private static final String SUPPLIER_NOTE_OCR_PROMPT = """
    你是中餐厅供应链单据识别专家。请识别这张供应商送货单/进货单图片。
    严格以 JSON 返回(无其他文字):
    {
      "note_number": "送货单号(无则null)",
      "delivery_date": "YYYY-MM-DD(无则今日)",
      "supplier_name": "供应商名称",
      "items": [
        {
          "ingredient_name": "食材名称",
          "quantity": 数量数值,
          "unit": "单位(kg/斤/个/包等)",
          "unit_price": 单价数值或null,
          "line_amount": 金额数值或null
        }
      ],
      "total_amount": 合计金额数值或null,
      "confidence": 0.0-1.0整体置信度
    }
    要点:1.数量/金额为纯数字无单位 2.日期无法识别返回null 3.confidence<0.5时items返回空数组
    """;
```

#### A5. Java REST Controller

**新建**: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/restaurant/SupplierDeliveryNoteController.java`

```
@RequestMapping("/api/mobile/{factoryId}/restaurant/supplier-delivery-notes")
```

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST /ocr-parse` | multipart: `photo`, `deliveryDate`, `supplierId?` | 调 `parseAndDraft`, 返回 draft note + 置信度 |
| `GET /limits` | `?month=YYYY-MM` | Rule 1, 返回 `{month, confirmedCount, draftCount}` |
| `GET` | `?status=&page=&size=` | 分页列表 |
| `GET /{id}` | - | 详情含 lines |
| `PUT /{id}/confirm` | - | 确认 → CONFIRMED + 写 gold |
| `PUT /{id}/reject` | body: `{rejectReasonCode, rejectReasonNote}` | Rule 3 |
| `PUT /{id}/lines` | body: `List<LineDTO>` | 人工编辑解析行项 |
| `DELETE /{id}` | - | 软删除 (status==DRAFT only) |

#### A6. Python gold endpoint

**修改**: `backend/python/smartbi/gold/restaurant_finance_etl.py` (或新文件 `supplier_price_etl.py`)

```python
async def upsert_supplier_price_batch(
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
    rows: list[dict],   # [{ingredient_name, normalized_name, raw_material_type_id,
                        #   supplier_id, supplier_name, delivery_date, unit_price,
                        #   quantity, unit, line_amount, source_note_id}]
) -> int:
    """Bulk-upsert confirmed OCR lines into agg_supplier_price.
    No ON CONFLICT key — each confirmed delivery = new history row (append-only).
    Returns count inserted."""
```

**新增 REST endpoint**: `backend/python/smartbi/api/supplier_price.py`

```
POST /api/smartbi/gold/supplier-price/batch-upsert
  Body: {factoryId, noteId, lines: [...]}
  → calls upsert_supplier_price_batch
  → returns {success, count}
```

**注册**: `backend/python/main.py` — `app.include_router(supplier_price_router, prefix="/api/smartbi/gold", tags=["SupplierPrice"])`

#### A7. Python gold query (为 G5/G4 解锁)

**新增**: `backend/python/smartbi/gold/queries.py` 内补充 (或新函数文件)

```python
async def get_supplier_price_trend(
    pool: asyncpg.Pool, factory_id: str,
    ingredient_name: str,            # normalized match
    days: int = 90
) -> list[dict]:
    """Return [{delivery_date, unit_price, supplier_name, quantity}] for trend chart."""

async def get_latest_ingredient_prices(
    pool: asyncpg.Pool, factory_id: str
) -> list[dict]:
    """Latest unit_price per ingredient for G5 cost card."""
```

---

### Tier B — 语音录入 MaterialRequisition

#### B1. Java Tool (Tool-Skill 架构, 禁止 IntentHandler)

**新建**: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/RestaurantVoiceRequisitionTool.java`

```java
@Slf4j @Component
public class RestaurantVoiceRequisitionTool extends AbstractBusinessTool {

    @Override public String getToolName() { return "restaurant_voice_requisition"; }
    @Override public String getDescription() {
        return "通过语音识别文本创建领料单草稿, 提取食材名称/数量/单位";
    }

    // Parameters: voiceText(string, required), requisitionDate(string, optional YYYY-MM-DD)
    // doExecute: 调 NLP 提取 → 匹配 raw_material_types → 创建 MaterialRequisition(status=DRAFT)
    // 返回: {draftId, ingredientName, quantity, unit, matchConfidence, message}
    // 不直接提交 (Rule 2 二段式: 返回草稿供人工确认)
}
```

**意图配置** (新 Flyway `V20260608_02__voice_requisition_intent.sql`):

```sql
INSERT INTO ai_intent_config (id, intent_code, intent_name, intent_category,
  tool_name, keywords, is_active, sensitivity_level)
VALUES (gen_random_uuid(), 'RESTAURANT_VOICE_REQUISITION', '语音录入领料单', 'DATA_OPERATION',
  'restaurant_voice_requisition',
  '["领料","要料","拿料","进料","用料","备料"]', true, 'LOW')
ON CONFLICT (intent_code) DO NOTHING;
```

#### B2. 语音录入 NLP 解析 Service

**新建**: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/restaurant/VoiceRequisitionParserService.java`

```java
public interface VoiceRequisitionParserService {
    /**
     * 从语音文本解析领料信息.
     * 使用 LLM slot-filling: 输入 "要五斤猪肉" → {ingredient:"猪肉", qty:5, unit:"斤"}
     */
    VoiceRequisitionSlot parse(String factoryId, String voiceText);
}

@Data @Builder public class VoiceRequisitionSlot {
    private String ingredientName;   // 提取的食材名称
    private BigDecimal quantity;     // 数量
    private String unit;             // 单位
    private String matchedMaterialTypeId;  // 模糊匹配 raw_material_types
    private double matchConfidence;  // 0-1
    private String rawText;
}
```

**实现策略**: 调 `PythonLLMClient.chat()` with prompt, 解析 JSON slot 结构. 匹配用 `RawMaterialTypeRepository.findByFactoryIdAndNameContaining()`.

#### B3. 前端语音录入流程 (web-admin)

复用已有 `VoiceRecognitionController` — 前端先调:
```
POST /api/mobile/{factoryId}/voice/recognize
body: {audioData: <base64>, format, sampleRate, language: "zh_cn"}
→ {recognizedText: "要五斤猪肉"}
```

再调:
```
POST /api/mobile/{factoryId}/restaurant/requisitions/voice-draft
body: {voiceText, requisitionDate}
→ {draftId, ingredientName, quantity, unit, matchConfidence}
```

人工在表单确认后:
```
POST /api/mobile/{factoryId}/restaurant/requisitions
(已有接口) body: {rawMaterialTypeId, requestedQuantity, unit, ...}
```

**新建 endpoint** 在 `MaterialRequisitionController`:
```java
// POST /{factoryId}/restaurant/requisitions/voice-draft
@PostMapping("/voice-draft")
public ResponseEntity<ApiResponse<VoiceRequisitionSlot>> createVoiceDraft(...)
```

---

## 前端组件

### Tier A — web-admin OCR 上传

**新建**: `web-admin/src/views/restaurant/supplier-delivery/SupplierDeliveryNoteList.vue`

- el-table 列表: 日期/供应商/金额/状态/置信度/操作
- "上传送货单" 按钮 → 弹 UploadDialog

**新建**: `web-admin/src/views/restaurant/supplier-delivery/SupplierDeliveryNoteUploadDialog.vue`

Rule 1 — Dialog 打开即调 `GET /limits?month=YYYY-MM`, header 显示:
```
本月已录 X 张 (确认 Y / 草稿 Z)
```

Rule 2 — 上传区上方显示供应商选择下拉 (从现有供应商 API 加载) + 日期选择器, 作为 context header.

Rule 3 — 拒绝时 `el-select` 标准原因:
- `IMAGE_BLUR` / `LOW_LIGHT` / `WRONG_DOCUMENT` / `SUPPLIER_NOT_FOUND` / `OTHER`
- 选 OTHER 才显 textarea

Rule 4 — 后端 409 时: `ElMessageBox.confirm("已有草稿 SDN-XXX, 是否前往查看?")` → router.push 详情页

Rule 5 — 置信度 < 0.75 时显橙色提示条:
```
"识别置信度较低 (X%), 建议重拍: 确保单据平整、光线充足、文字清晰"
+ [重新上传] 按钮
```

el-upload 模式: `action=""` + 手动触发 → `POST /ocr-parse` (multipart).

**新建**: `web-admin/src/views/restaurant/supplier-delivery/SupplierDeliveryNoteDetail.vue`

- 行项可编辑 table (ingredient_name, quantity, unit, unit_price, line_amount)
- 数量/金额联动自动计算 `line_amount = qty * unit_price` (Rule 3 数字联动)
- "确认" / "拒绝" 按钮 + sticky error toast

**新建**: `web-admin/src/api/restaurant/supplierDeliveryNote.ts`

```typescript
export function ocrParseNote(formData: FormData): Promise<ApiResponse<SupplierDeliveryNoteDto>>
export function confirmNote(factoryId: string, noteId: string): Promise<ApiResponse<void>>
export function rejectNote(factoryId: string, noteId: string, body: RejectBody): Promise<ApiResponse<void>>
export function getNoteLimits(factoryId: string, month: string): Promise<ApiResponse<NoteLimits>>
export function getNoteList(factoryId: string, params: NoteListParams): Promise<ApiResponse<PageResult<SupplierDeliveryNoteDto>>>
export function updateNoteLines(factoryId: string, noteId: string, lines: NoteLineDto[]): Promise<ApiResponse<void>>
```

**路由注册**: `web-admin/src/router/index.ts` — 在餐饮运营 IA v2 的"日常录入"组下追加:
```
/restaurant/supplier-delivery  →  SupplierDeliveryNoteList
```

**侧边栏**: `web-admin/src/layout/components/Sidebar/` — 在"日常录入"section 追加"供应商进货录入"菜单项

### Tier B — web-admin 语音录入领料

**修改**: `web-admin/src/views/restaurant/requisitions/` 已有表单 → 增加麦克风按钮 (复用已有语音组件模式).

**新建**: `web-admin/src/components/restaurant/VoiceRequisitionCapture.vue`

- 录音 → base64 → `POST /voice/recognize` → 显示识别文字
- 识别文字 → `POST /requisitions/voice-draft` → 回填 ingredientName/quantity/unit 字段
- 复用 Rule 2: dialog header 显示"领料录入 — {today} 当班" context

---

## 数据流

### Tier A OCR 流

```
[拍照/上传 JPEG]
  ↓  multipart POST /ocr-parse
SupplierDeliveryNoteController.ocrParse()
  ↓  SHA-256 幂等检查 → 409 if dup (Rule 4)
  ↓  OSS upload → photoOssUrl
  ↓  DashScopeVisionClient.analyzeImage(ossUrl, SUPPLIER_NOTE_OCR_PROMPT)
     → delegate.visionChat(bytes, "image/jpeg", prompt)
     → JSON parse → {note_number, delivery_date, supplier_name, items[], confidence}
  ↓  confidence < 0.75 → status=DRAFT, ocrConfidence 写入
  ↓  per-line soft-match raw_material_types
  ↓  SupplierDeliveryNoteRepository.save() (cretas_db)
  ↓  返回 {note, ocrConfidence, lines, lowConfidenceWarning?}
  ↓
[前端展示草稿, 人工校对行项]
  ↓  PUT /{id}/lines (编辑)
  ↓  PUT /{id}/confirm
SupplierDeliveryNoteService.confirmNote()
  ↓  status → CONFIRMED
  ↓  POST /api/smartbi/gold/supplier-price/batch-upsert
     → smartbi_db.agg_supplier_price (append rows)
  ↓  触发 ETL sync_dim_ingredient 更新 dim_ingredient.unit_price (fail-soft REQUIRES_NEW)
  ↓  返回 {success, noteId}
```

### Tier B 语音流

```
[录音 PCM/RAW]
  ↓  POST /voice/recognize (IFlytekVoiceService WebSocket)
  ↓  {recognizedText: "要五斤猪肉"}
  ↓  POST /restaurant/requisitions/voice-draft
VoiceRequisitionParserService.parse(voiceText)
  ↓  LLM slot-fill → {ingredient:"猪肉", qty:5, unit:"斤"}
  ↓  RawMaterialTypeRepository.findByFactoryIdAndNameContaining("猪肉")
  ↓  matchedMaterialTypeId, matchConfidence
  ↓  返回 VoiceRequisitionSlot (草稿, 未写库)
  ↓
[前端回填表单, 人工确认]
  ↓  POST /restaurant/requisitions (已有接口)
MaterialRequisitionRepository.save()
  ↓  VoiceRecognitionService.saveHistory(businessScene="MATERIAL_REQUISITION")
```

---

## 防呆设计 (逐条 .claude/rules/fool-proof-design.md 五规则应用)

**Rule 1 — 预先显示边界**:
- UploadDialog 打开时调 `GET /limits`, 显示"本月已录 X 张 (确认 Y / 草稿 Z)"
- 每行 line_amount = qty × unit_price 实时联动计算, 防止总额和分项对不上
- 低置信度阈值 0.75 在前端 props 可配置, 不硬编码 UI 文字

**Rule 2 — 上下文 context header**:
- OCR dialog: "供应商进货录入 — [供应商下拉]  [日期选择]", 二者必填才能上传
- 拒绝 dialog: "拒绝 SDN-YYYYMMDD-XXX (供应商名, 金额 ¥XX)"
- 语音草稿回填: dialog 标题 "领料录入 — 识别: '五斤猪肉' → {匹配食材} {qty} {unit}"

**Rule 3 — 自由文本改约束选择**:
- 拒绝原因: el-select 5 标准码 (IMAGE_BLUR / LOW_LIGHT / WRONG_DOCUMENT / SUPPLIER_NOT_FOUND / OTHER) + 选 OTHER 才显 textarea
- 语音识别失败原因 dropdown: BACKGROUND_NOISE / DIALECT / UNCLEAR / OTHER

**Rule 4 — 幂等防重复**:
- 照片 SHA-256 唯一索引; 409 → `ElMessageBox.confirm("已有草稿 SDN-XXX, 是否查看?")` + router.push
- 语音草稿仅返回 slot 不写库, 需人工 POST 确认才落库, 天然防重复点击

**Rule 5 — 死路改导航**:
- ocrConfidence < 0.75 → 橙色提示条 + [重新上传] 按钮 + 提示文字"建议: 正对单据/充足光线/减少阴影"
- `DashScopeVisionClient.isAvailable()` == false → 显"视觉服务暂不可用, 请[手动录入]" + router.push 手录页
- 供应商列表为空 → "还未添加供应商, [前往供应商管理]" + link

**4 位一体 (error toast)**:
- response.message 含具体原因 (e.g. "图片清晰度不足, OCR 置信度 62%, 建议重拍")
- 前端原样 display `e.response.data.message`
- error toast `duration:0, showClose:true`
- message 含 next action (重拍 / 手动录入 / 跳供应商管理)

---

## 错误处理

| 场景 | 后端行为 | 前端处理 |
|---|---|---|
| 照片 hash 重复 | 409 `{existingId, message: "已有草稿 SDN-XXX"}` | ElMessageBox.confirm 跳详情 |
| OCR 置信度 < 0.75 | 200 + `ocrConfidence`, `lowConfidenceWarning: true` | 橙色提示 + 重拍按钮 (非 error) |
| Vision LLM 不可用 | 503 `{message: "视觉服务不可用", hint: "/manual-entry"}` | 提示 + [手动录入] 按钮 |
| 行项解析 JSON 无效 | 200, lines=[], `ocrErrorMessage` 填入 | 显示"解析失败原因", 手动填行 |
| 讯飞 WebSocket 失败 | 500 `{message: "语音识别失败: 原因"}` | sticky error toast |
| gold upsert 失败 | note 已 CONFIRMED; 触发 ETL 失败 fail-soft (REQUIRES_NEW) | 不阻塞确认; 后台重试 |
| 禁止降级 | 任何后端报错明确 message, 不返回假数据 | 严格显示 response.message |

---

## 测试计划

### 单元测试

**新建**: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/restaurant/SupplierDeliveryNoteServiceTest.java`

```
- testOcrParseAndDraft_duplicateHash_returns409()
- testOcrParseAndDraft_lowConfidence_setsDraftStatus()
- testOcrParseAndDraft_validPhoto_parsesLinesCorrectly()  (mock DashScopeVisionClient)
- testConfirmNote_callsPythonGoldUpsert()  (mock PythonSmartBIClient)
- testConfirmNote_goldUpsertFails_doesNotRollbackConfirm()  (REQUIRES_NEW 隔离验证)
- testRejectNote_invalidStatus_throwsBusinessException()
```

**新建**: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/restaurant/VoiceRequisitionParserServiceTest.java`

```
- testParse_validChineseVoice_extractsIngredientQtyUnit()
- testParse_ambiguousText_returnsLowConfidence()
- testParse_noQuantity_returnsNullQty()
```

**新建**: `backend/python/smartbi/gold/tests/test_supplier_price_etl.py`

```
- test_upsert_supplier_price_batch_basic()
- test_upsert_grants_rls_respected()
- test_get_supplier_price_trend_returns_asc_date()
- test_get_latest_ingredient_prices_deduplicates_by_normalized_name()
```

### 真库 E2E 验证 (必做, 单测覆盖不到 grant/RLS)

1. 部署迁移到 test 环境 (`smartbi_db` + `cretas_db`)
2. 上传真实送货单照片 (或 mock base64) → 验证 `supplier_delivery_notes` 写入 + lines 行数
3. 调 confirm API → 验证 `agg_supplier_price` 行数 > 0 (grant 正确)
4. 调 `GET /api/smartbi/gold/supplier-price/trend?ingredient=猪肉` → 非空结果

---

## 文件结构

### Create (新建)

```
backend/java/cretas-api/src/main/resources/db/flyway/
  V20260608_01__supplier_delivery_notes.sql          # cretas_db 新表
  V20260608_02__voice_requisition_intent.sql         # ai_intent_config 新意图

backend/java/cretas-api/src/main/java/com/cretas/aims/
  entity/restaurant/SupplierDeliveryNote.java
  entity/restaurant/SupplierDeliveryNoteLine.java
  entity/restaurant/enums/DeliveryNoteStatus.java    # DRAFT / CONFIRMED / REJECTED
  entity/restaurant/enums/DeliveryNoteSourceType.java # OCR / MANUAL
  repository/restaurant/SupplierDeliveryNoteRepository.java
  repository/restaurant/SupplierDeliveryNoteLineRepository.java
  service/restaurant/SupplierDeliveryNoteService.java
  service/restaurant/impl/SupplierDeliveryNoteServiceImpl.java
  service/restaurant/VoiceRequisitionParserService.java
  service/restaurant/impl/VoiceRequisitionParserServiceImpl.java
  controller/restaurant/SupplierDeliveryNoteController.java
  ai/tool/impl/restaurant/RestaurantVoiceRequisitionTool.java
  dto/restaurant/SupplierDeliveryNoteDto.java         # request/response DTO
  dto/restaurant/VoiceRequisitionSlot.java

backend/python/smartbi/database/migrations/
  V20260608_01__agg_supplier_price.sql               # smartbi_db gold 表 + GRANT

backend/python/smartbi/gold/
  supplier_price_etl.py                              # upsert_supplier_price_batch + trend queries

backend/python/smartbi/api/
  supplier_price.py                                  # FastAPI router POST /batch-upsert + GET /trend

web-admin/src/
  api/restaurant/supplierDeliveryNote.ts
  views/restaurant/supplier-delivery/SupplierDeliveryNoteList.vue
  views/restaurant/supplier-delivery/SupplierDeliveryNoteUploadDialog.vue
  views/restaurant/supplier-delivery/SupplierDeliveryNoteDetail.vue
  components/restaurant/VoiceRequisitionCapture.vue
```

### Modify (修改)

```
backend/python/main.py
  # include_router(supplier_price_router, prefix="/api/smartbi/gold", ...)

backend/java/cretas-api/src/main/java/com/cretas/aims/config/PythonSmartBIClient.java (或已有同名)
  # 新增 upsertSupplierPriceBatch(String factoryId, List<Map> lines): void

web-admin/src/router/index.ts
  # 追加 /restaurant/supplier-delivery 路由

web-admin/src/layout/components/Sidebar/...
  # 在"日常录入" section 追加"供应商进货录入"菜单项

backend/java/cretas-api/src/main/java/com/cretas/aims/controller/restaurant/MaterialRequisitionController.java
  # 追加 POST /voice-draft endpoint
```

---

## 待 Steve 拍板的决策

### D1 — 照片存储策略 (默认: OSS 存 90 天自动过期)

**默认假设**: 照片上传到 OSS `cretas-media` bucket, 路径 `restaurant/{factoryId}/delivery-notes/{noteId}.jpg`, 设置 90 天生命周期规则(冷存). 长期审计需要时人工归档.

**选项 B**: 不存 OSS, 仅在 OCR 完成后丢弃字节, 只保留 `ocrRawJson`. 适合成本优先但失去原单据留存.

### D2 — OCR 失败是否允许纯手工录入 (默认: 是)

**默认假设**: OCR 失败/低置信时, 用户可手动填行项 (source_type=MANUAL). `SupplierDeliveryNoteController.createManual()` 单独 endpoint, 不走 vision 路径.

**选项 B**: 仅 OCR 路径, 失败时让用户重拍. 减少实现量但降低容错.

### D3 — 语音录入意图路由: 复用餐饮意图 router 还是新建 (默认: 新建独立 Tool)

**默认假设**: 按 Tool-Skill 规范新建 `RestaurantVoiceRequisitionTool`, 意图 `RESTAURANT_VOICE_REQUISITION`, 独立于现有餐饮分析工具. 语音录入和 AI 问答是不同 UX 路径, 分开更清晰.

**选项 B**: 复用 `restaurant_voice_requisition` 接入已有意图 router, 让 AI 工作台也能接受语音领料指令. 复杂度更高, Phase 2 更合适.

### D4 — DashScope Vision 配额 (默认: 沿用现有 PythonLLMClient visionChat 路径, 按需监控)

**默认假设**: `DashScopeVisionClient` 已委托 `PythonLLMClient.visionChat()`, 费用随现有 DashScope 账号计量. 每张送货单约 1-2 次 vision 调用. qhj 5 区每日进货约 10-30 张 = 10-60 次/天, 预估极低成本. 建议 Steve 确认账号是否设置了 DashScope 月度配额告警.

### D5 — gold 写入失败时的重试策略 (默认: 人工重触发)

**默认假设**: `confirmNote` 时 gold upsert 失败 → note 仍标 CONFIRMED (主流程不回滚), `ocrErrorMessage` 写入"gold sync 失败". 管理员在列表看到异常标记后可点"重新同步"按钮手动触发 gold 写入. 不建议自动重试队列 (增加复杂度).

---

## 依赖与并行

### 硬依赖

- `DashScopeVisionClient.analyzeImage()` + `PythonLLMClient.visionChat()` — 已就绪
- `IFlytekVoiceService.recognize()` — 已就绪, 讯飞 AppID 在服务器 `.env.prod`
- `VoiceRecognitionController POST /recognize` — 已有, 可直接调
- `suppliers` 表 (cretas_db) + 供应商 API — 需确认现有 API 路径 (grep `SupplierController`)
- `raw_material_types` + `RawMaterialTypeRepository` — 已有, 用于软匹配食材名

### 可并行实施

- **Subagent A**: Tier A Java 层 (实体/Repository/Service/Controller + Flyway V20260608_01/02)
- **Subagent B**: Python gold 层 (`V20260608_01__agg_supplier_price.sql` + `supplier_price_etl.py` + `supplier_price.py` + main.py 注册)
- **Subagent C**: 前端 web-admin (Vue 三件套 + API ts + 路由/侧边栏)
- **Subagent D**: Tier B 语音 (Tool + ParserService + MaterialRequisitionController voice-draft + VoiceRequisitionCapture.vue)

**Subagent B 不依赖 A** (smartbi_db 与 cretas_db 分离). A/B 可同时跑.
**Subagent C 依赖 A/B 完成 API 签名确定** (可先按 spec 中签名 mock, A 完成后对齐).
**Subagent D 完全独立** (复用已有语音接口, 不触碰 OCR 路径).

### Flyway 版本号防撞车

PR 合并前必须:
```bash
git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway \
  | grep -oE 'V20260608_[0-9]{2}' | sort | uniq -d
# 应无输出; 有输出 = 版本号冲突 → 重编号
```

当前 origin/main 最高 Java Flyway 版本为 `V20260607_05`. `V20260608_01` 和 `V20260608_02` 安全.
当前 Python smartbi migrations 最高为 `V20260531_01`. `V20260608_01` 安全.
