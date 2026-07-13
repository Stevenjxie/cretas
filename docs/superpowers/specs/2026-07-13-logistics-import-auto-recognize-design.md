# 一加物流 · 第一步导入订单 — 任意 Excel 自动识别 + 复制粘贴

**日期**: 2026-07-13
**分支**: `feat/logistics-import-auto-recognize`
**背景**: 客户上传的真实 Excel 表头五花八门(「客户」而非「门店名称」、「数量」而非「件数」…),现有导入用 `normKey` 去空白后**精确匹配**固定中文表头,匹配不上直接抛「表头无法识别」。客户需要像餐饮 SmartBI 那样「任意 Excel 自动识别字段」,以及「从 Excel 复制一段直接粘贴进对话框上传」。

---

## 目标

1. **任意 Excel 字段自动识别**:客户表头用常见别名也能自动映射到我们的字段。
2. **复制粘贴导入**:从 Excel 连表头复制一段 → 粘贴进文本框 → 自动识别 → 导入。

两个入口共用同一条「解析 → 识别 → 确认映射 → 预览 → 提交」管线。「手动录入」模式不改。

## 非目标 (YAGNI)

- **不引入 LLM**。物流订单字段集小且固定(7 个),同义词字典 + 人工确认映射足够,确定性/免费/防呆。(SmartBI 那套 rules+LLM 是为开放分析 schema,这里用不上。)
- 不改 `commit` / geocode / 批次幂等逻辑。
- 不改手动录入可编辑表格。

---

## 目标字段词汇表 (canonical fields)

沿用 `LogisticsOrderImportRow` 的 13 列 schema。识别面板对用户暴露的目标字段:

| 字段 | 规范表头 | 必填 |
|---|---|---|
| storeCode | 订单号 | 否(空则 `SM-{date}-{行号}` 自动生成) |
| storeName | 门店名称 | **是** |
| address | 配送地址 | **是** |
| pieces | 件数 | 件数/箱数 至少一个 |
| boxes | 箱数 | 件数/箱数 至少一个 |
| weightKg | 重量kg | **是**(>0) |
| volumeCbm | 体积m³ | **是**(>0) |
| businessDate | 业务日期 | 否(默认今天) |
| windowStart | 配送开始时间 | 否 |
| windowEnd | 配送结束时间 | 否 |
| longitude | 经度 | 否(与纬度成对) |
| latitude | 纬度 | 否 |
| areaCode | 区域 | 否 |

**必填覆盖判定**:storeName + address + weightKg + volumeCbm + (pieces 或 boxes)。

---

## 1. 识别引擎(后端,纯函数、可测)

新增 `LogisticsHeaderMatcher`(package `com.cretas.aims.logistics.service.importjob`,或作为 `LogisticsOrderImportServiceImpl` 的静态工具)。

**归一化** `normalizeHeader(String)`:在现有 `normKey`(去所有空白 `[\s　]+`)基础上再:
- 去成对单位/说明括号后缀:`(kg)`、`（含套餐）`、`/kg`、`/箱` 等 → 只用于匹配,不改显示。
- 英文转小写。

**同义词字典** `FIELD_ALIASES: Map<field, List<归一化别名>>`(规范表头本身也在别名里,保证零回归):

- storeName ← 门店名称/门店/店名/门店名/客户/客户名称/客户名/收货方/收货客户/收货门店/网点/店铺/终端
- address ← 配送地址/地址/收货地址/送货地址/详细地址/门店地址/收货地址明细/送货地址明细
- pieces ← 件数/件/pcs/总件数
- boxes ← 箱数/箱/纸箱数/总箱数/箱子
- weightKg ← 重量kg/重量/毛重/净重/总重/公斤/kg/重量kg
- volumeCbm ← 体积m³/体积/方数/立方/总体积/m3/cbm/体积m3
- storeCode ← 订单号/单号/订单编号/订单no/编号/订单号码
- areaCode ← 区域/片区/配送区域/大区/区
- businessDate ← 业务日期/日期/配送日期/送货日期
- windowStart ← 配送开始时间/送达开始/时间窗开始/最早送达
- windowEnd ← 配送结束时间/送达结束/时间窗结束/最晚送达
- longitude ← 经度/lng/longitude ; latitude ← 纬度/lat/latitude

**匹配算法**(每个源列):
1. 精确别名命中(归一化后 `equals`)→ 置信度 1.0。
2. 子串别名命中(源表头归一化后包含别名或反之)→ 置信度 0.7,标记 `ambiguous` 若命中多个字段。
3. 无命中 → 未映射。

**产出** `HeaderMappingResult`:
```
columns: [{ index, header(原文), mappedField(可空), confidence, ambiguous }]
unmappedRequiredFields: [field...]   // 必填里没被任何列覆盖的
autoConfident: boolean               // 全部必填覆盖 & 无 ambiguous → 前端可一键确认
```

**不再抛「表头无法识别」**:识别不全时返回部分映射,由前端确认面板兜。

**覆盖映射**:`applyMapping(table, override?)` — 若传入 `override`(列索引→字段,来自用户确认),用它;否则用自动识别结果。同一字段被映射到多列时,取第一列(并在校验里可提示)。

---

## 2. 后端接口

**共享服务核心**(重构提取,DRY):
```
PreviewResultDto previewFromTable(
    String factoryId, List<List<String>> table,
    String businessDate, Map<Integer,String> mappingOverride)
```
内部:`HeaderMappingResult` → 按映射把每行 2D → `LogisticsOrderImportRow` → 复用现有 `buildPreviewFromRawRows`。`PreviewResultDto` 增字段 `columnMapping`(即 `HeaderMappingResult`,回给前端渲染确认面板)。

**接口**:
- **增强** `POST /order-import/preview`(multipart `file` + `businessDate` + 可选 `columnMapping` JSON):`parseRows(file)` → `previewFromTable(..., override)`。返回体带 `columnMapping`。
- **新增** `POST /order-import/preview-paste`(JSON `{ rawText, businessDate, columnMapping? }`):
  - `parsePastedText(rawText)`:按行 split(`\r?\n`),自动判定分隔符——**含 Tab 用 Tab(Excel 剪贴板默认),否则逗号**(复用 CSV 的 RFC-4180 `splitCsv`)。产出 2D 表格。
  - → `previewFromTable(...)`。
- `commit` / 其余不变。

**DTO 变更**:
- `PreviewResultDto` + `HeaderMappingResult columnMapping`。
- 新 `PastePreviewRequest { String rawText; String businessDate; Map<Integer,String> columnMapping; }`。
- `columnMapping` 覆盖入参:`Map<Integer,String>`(列索引→字段名)。

---

## 3. 前端(`web-admin/src/views/scheduling/logistics/components/OrderImportStep.vue`)

**模式 tab**:文件导入(任意 Excel) / **粘贴导入(新)** / 手动录入(不变)。

**粘贴 tab**:
- `el-input type="textarea"` 大文本框,placeholder「从 Excel 选中含表头的区域,Ctrl+C 复制,粘贴到这里」。
- 输入即本地统计行数(按换行)提示「检测到 N 行」。
- 「识别并预览」→ `previewOrderImportPaste({ rawText, businessDate })` → 拿到 `columnMapping` + preview。

**映射确认面板(文件 + 粘贴共用组件 `ColumnMappingConfirm`)**:
- 渲染 `columnMapping.columns`:每行「源列表头(+首行样例值)→ el-select 目标字段(选项含各字段 + 「忽略此列」)」,预填识别结果,ambiguous 列高亮。
- 顶部覆盖状态条:必填字段 chip,已覆盖=绿,未覆盖=红。
- **`autoConfident === true`**(全必填覆盖、无歧义):面板折叠成一行摘要「已自动识别 7 列,✅ 一键确认」+「展开核对」链接 →**一键确认**直接预览。
- `autoConfident === false`:面板展开,「确认并预览」按钮 `:disabled="unmappedRequiredFields.length > 0"`(防呆 Rule 1)。
- 用户改了下拉 → 重新 `preview`(文件:重发 File + `columnMapping`;粘贴:重发 rawText + `columnMapping`)。
- 确认后 → preview 落进现有可编辑表格(`loadPreviewIntoTable`,现有逻辑)→ 提交(现有)。

**API 客户端**(`web-admin/src/api/logistics.ts`):
- `previewOrderImport` 增 `columnMapping?` 参数(multipart form 追加字段)。
- 新 `previewOrderImportPaste(payload)`。
- 类型 `PreviewResult` + `columnMapping`,新 `ColumnMapping` 类型。

**wiring**:`workbench/index.vue` + `useLogisticsScheduling.ts` 加粘贴预览 handler(复用现有 commit)。

---

## 4. 防呆合规(fool-proof-design)

- **Rule 1(预先显示边界)**:映射确认面板先显示「识别到什么 / 缺哪个必填」,必填未覆盖阻断「确认并预览」。
- **Rule 2(上下文)**:每列显示源表头名 + 首行样例值,用户知道在映射什么。
- **错误 toast**:沿用现有 sticky(`duration:0 + showClose`);粘贴解析失败(空/列数不齐)给具体信息「第 3 行只有 2 列,应有 ≥N 列」。

---

## 5. 测试

- **`LogisticsHeaderMatcherTest`**(单测,核心):
  - 别名命中(客户→storeName、数量→pieces、方数→volumeCbm)。
  - 单位括号剥离(`重量(kg)`→weightKg、`体积/m³`→volumeCbm)。
  - 歧义列标记(既像件数又像箱数)。
  - 必填未覆盖 → `autoConfident=false` + `unmappedRequiredFields`。
  - 全中 → `autoConfident=true`。
  - 覆盖映射优先于自动识别。
  - 规范表头零回归(现有模板 6 列仍全中)。
- **服务测**:`parsePastedText` Tab/逗号判定 + 空行/短行错误;`previewFromTable` 走 `buildPreviewFromRawRows` 产出正确 preview。
- **前端**:`OrderImportStep` 粘贴 tab 渲染 + autoConfident 一键确认路径(现有测试若有则扩;无则轻量加)。

---

## 交付与隔离

- 分支 `feat/logistics-import-auto-recognize` off `origin/main`,worktree `C:\Users\Steve\cretas-logi`。
- 后端跑测试前 mv 掉不编译的 workflow 测试(`ProductProcessWorkflowServiceImplTest`、`ProductProcessWorkflowPostgresIntegrationTest`、`InventoryPostingIntegrityTest`),跑完 mv 回。
- PR 前 `git diff origin/main...HEAD --stat` 核对 scope 干净。
- 部署走 main;蓝绿核对 active service;jar marker 用 ASCII 方法名(如 `previewFromTable` / `parsePastedText`)。
