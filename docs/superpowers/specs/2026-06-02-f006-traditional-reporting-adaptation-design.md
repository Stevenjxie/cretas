# F006 传统报工适配实现设计 (图片证据 + 多时段工时 + 副产物损耗 + 留样 + 称重计算器)

> 来源: `docs/audits/2026-06-02-f006-traditional-reporting-adaptation-analysis.md` (六扇门传统报工格式)
> Steve 决策: 全做 5 增量; 图片走阿里云 OSS **后端中转** (复用 OssService.uploadImage)
> 日期: 2026-06-02 · worktree cretas-p0p1 (off origin/main) · 接 P0+P1 (已 merge+部署 prod)
> 迁移号: V20260910_* 起 (最新已占 20260909.03; PR 前必 fetch 复核)

---

## 总览: 5 增量

| 增量 | 内容 | 后端 | RN | web-admin | 迁移 |
|---|---|---|---|---|---|
| 1 图片证据 | 逐道报工传照片(产品+秤+盒数) | OSS中转端点+photos存储+DTO透出 | 拍照上传 | 缩略图+大图 | 无(复用photos) |
| 2 多时段工时 | 时段×人数 person-hours (修M2) | computeLaborCost改+jsonb | 多段录入 | 段展示 | V20260910_01 |
| 3 副产物损耗 | 料头/肥油/骨头+损耗+守恒校验 | byproducts/waste+软告警 | 录入 | 列 | V20260910_01 |
| 4 留样 | 末道留样盒数 | sample_retain+入库扣减 | 录入 | 列 | V20260910_01 |
| 5 称重计算器 | 毛-皮多托净重防呆 | — | 计算模式 | — | 无 |

不破坏已上线: 全部新增 nullable/可选; laborSegments 空→回退单一 workerCount 路径; photos 已存在列。

---

## 增量 1 — 逐道报工图片证据 (后端中转)

### 1.1 后端上传端点 (复用 OssService)
`controller/FileUploadController.java` 加 (镜像 line 57 signature-photo):
```java
@PostMapping("/yield-evidence")
public ApiResponse<Map<String,String>> uploadYieldEvidence(
        @PathVariable String factoryId, @RequestParam("file") MultipartFile file) {
    // 校验 ≤5MB JPEG/PNG (同 signature-photo)
    String url = ossService.uploadImage(file, "yield-evidence", factoryId);
    return ApiResponse.success("上传成功", Map.of("url", url));
}
```
完整路径 `POST /api/mobile/{factoryId}/upload/yield-evidence`。

### 1.2 存储 (复用 ProductionReport.photos 已存在 jsonb List<String>)
- `YieldReportRequest` 加 `List<String> evidenceImages`。
- `submitReport`: `.photos(req.getEvidenceImages())` (首条建行写; 累加报工后续条不覆盖, 同 materialBatchRefs 继承逻辑)。
- **无迁移** (photos 列已存在)。

### 1.3 DTO 透出
- `StepYieldDTO` 加 `List<String> photos` (该道所有报工的 photos 合并去重)。
- `calculateSteps`: 每 task 组合并 photos。

### 1.4 RN (镜像 PhotoEvidenceCapture.tsx 但走后端中转端点)
- `YieldStepReportScreen.tsx`: 加"拍照/选图"区 (expo-image-picker launchCameraAsync/launchImageLibraryAsync + expo-image-manipulator 压缩 resize 1024/compress 0.7)。
- 每张 multipart POST 到 `/upload/yield-evidence` → 收 url → 累积 evidenceImages[] → submitReport 带上。
- 显示已传缩略图 + 删除。低文化操作工: 大按钮"拍照留证"。

### 1.5 web-admin (batches/detail.vue 逐道表格)
- 逐道 steps 表加"证据"列: el-image 缩略图 (photos[0]) + :preview-src-list=photos 点开大图廊。null→"—"。

---

## 增量 2 — 多时段×人数工时 (person-hours, 修 M2)

### 2.1 Request + 实体
- `YieldReportRequest` 加 `List<LaborSegment> laborSegments` (inner: `{String startTime; String endTime; Integer headcount; String note}`)。保留 workMinutes/workerCount 向后兼容。
- `ProductionReport` 加 `labor_segments` jsonb (`@Type(JsonType.class) List<Map<String,Object>>`, 镜像 hourEntries line 104)。迁移 V20260910_01。

### 2.2 成本算法 (computeLaborCost 改)
`YieldReportServiceImpl.computeLaborCost` (line 598) 改:
```java
// 优先 laborSegments: Σ(headcount × durationMin)/60 × rate
private BigDecimal computeLaborCost(List<LaborSegment> segs, Integer workerCount, Integer workMinutes, BigDecimal rate) {
    if (rate == null) return null;
    if (segs != null && !segs.isEmpty()) {
        BigDecimal personMin = segs.stream()
            .filter(s -> s.getHeadcount()!=null && s.getDurationMinutes()!=null)
            .map(s -> BigDecimal.valueOf((long)s.getHeadcount() * s.getDurationMinutes()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (personMin.signum()==0) return null;
        return personMin.divide(BD_60, 6, HALF_UP).multiply(rate).setScale(2, HALF_UP);
    }
    // 回退单一路径 (向后兼容)
    if (workerCount==null || workMinutes==null) return null;
    return BigDecimal.valueOf((long)workerCount*workMinutes).divide(BD_60,6,HALF_UP).multiply(rate).setScale(2,HALF_UP);
}
```
durationMinutes 从 startTime/endTime 算 (HH:mm 差; 跨夜+1440)。

### 2.3 totalWorkers/totalWorkMinutes 口径 (修 M2)
submitReport 有 laborSegments 时: `totalWorkMinutes = Σ durationMinutes`, `totalWorkers = MAX headcount` (峰值人数, 非 SUM — 修 final review M2 膨胀)。
calculateSteps 跨多报工聚合: totalWorkMinutes SUM (工时累加正确), totalWorkers MAX (峰值)。

### 2.4 DTO + UI
- StepYieldDTO/BatchYieldDTO 加 `laborSegments`(透出) — 已有 totalWorkers/totalWorkMinutes。
- RN: 多段录入 (时段开始 HH:mm + 结束 + 人数 + 备注可选), 加段/删段。
- web-admin: 逐道展开显示工时段明细。

---

## 增量 3 — 副产物 / 损耗

### 3.1 Request + 实体 + 迁移
- `YieldReportRequest` 加 `List<Byproduct> byproducts` (`{String name; BigDecimal quantity; String unit}`) + `BigDecimal wasteQuantity`。
- `ProductionReport` 加 `byproducts` jsonb + `waste_quantity` NUMERIC。迁移 V20260910_01 (同批)。

### 3.2 守恒软校验 (容忍非守恒, per G9)
submitReport 算: `理论平衡 = inputQuantity - outputQuantity - Σbyproducts.quantity - wasteQuantity - (产出WIP结余?)`。偏差/投入 > 阈值(如 15%, 同单位才算) → response 加 `balanceWarning` 软告警 (不阻塞提交, 客户自认数据非守恒)。跨单位跳过。

### 3.3 DTO + UI
- StepYieldDTO 加 `byproducts`/`wasteQuantity`/`balanceWarning`。
- RN: 副产物明细 (名称+数量+单位, 加行) + 损耗量输入。
- web-admin: 副产物/损耗 列。

---

## 增量 4 — 留样

### 4.1 Request + 实体 + 迁移
- `YieldReportRequest` 加 `Integer sampleRetainQuantity` (末道装盒用)。
- `ProductionReport` 加 `sample_retain_quantity` INTEGER。迁移 V20260910_01 (同批)。

### 4.2 入库扣减
末道完工入库 (settleDay/triggerComplete 链): `实际入库 = 产出 - 留样 - 剩余WIP`。成品库存按净入库量。

### 4.3 DTO + UI
- StepYieldDTO/BatchYieldDTO 加 `sampleRetainQuantity`。
- RN: 末道留样盒数输入。web-admin: 留样列 + 净入库展示。

---

## 增量 5 — 称重计算器 (纯前端防呆)

### 5.1 YieldQuantityInput 计算模式
`components/processing/YieldQuantityInput.tsx` 加 `calculatorMode?: boolean` prop。开启时单 TextInput → 切换为:
- **多托明细**: 可加多行 (毛重输入), + 单一皮重(托盘), 自动 `净重 = Σ毛重 - N×皮重 - 杂扣`。
- 显示拆解: "毛 294.5+245.5 - 皮 57.5+54 = 净 457.5"。
- 净重 → onChangeText(净重) 回主流程。decimal-pad 不支持 +/-, 故用多行输入非表达式解析。
- 保留外壳 (label/step/maxHint/prefillNote/styles) 不变, 只换 TextInput 核心。

### 5.2 接入
YieldStepReportScreen 投入/产出量输入可切计算模式 (默认关, 仓管点"按托称重"开)。

---

## 实现顺序 (依赖)

迁移合并为 **1 个 V20260910_01** (labor_segments + byproducts + waste_quantity + sample_retain_quantity 一次加, photos 已存在不动)。

1. 单元1 后端地基: ProductionReport 4 新列 + 迁移 V20260910_01 + DTO 字段 (evidenceImages/laborSegments/byproducts/wasteQuantity/sampleRetainQuantity)
2. 单元2 OSS 上传端点 (FileUploadController /yield-evidence)
3. 单元3 算法: computeLaborCost 多段 + totalWorkers MAX (修M2) + 守恒软校验 + photos/byproducts 存储 + calculateSteps 聚合 + DTO 透出
4. 单元4 RN: 图片上传 + 多段工时 + 副产物/损耗 + 留样 + 称重计算器 (YieldStepReportScreen + YieldQuantityInput)
5. 单元5 web-admin: batches/detail.vue 逐道证据缩略图 + 工时段 + 副产物/损耗/留样列

每单元 TDD(后端)/tsc(前端) + 单测。全 merge main 后从 main 部署 prod + headed E2E。

## 不做 (范围外)
- 微信群消息自动抓取 (改 APP 内结构化报工替代)
- 叮咚订单自动导入 (走现有 SmartBI Excel 导入, 另评估)
- 负责人 Excel 1:1 复刻 (by-process + 分订单 + 本次列已覆盖核心)
