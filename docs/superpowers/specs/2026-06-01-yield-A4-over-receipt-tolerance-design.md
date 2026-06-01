# 出成率报工 A4 — 超收软告警 + 30% 容差可配 — 设计

**日期**: 2026-06-01
**状态**: 已审计，可实施
**前置**: 报工体系统一 Phase A (PR #350/#354/#358 merged main), Phase D (PR #360 merged main)
**关联规范**: `fool-proof-design.md` Rule 1 + Rule 4; `ApiResponse.errorWithCode` 已存在

---

## 目标

当仓管员/操作员提交某道工序的产出量时, 若该批次该工序的**累计产出已超过目标量的容差上限**, 系统弹出软告警提示(不硬阻): "已报 X, 目标 Y, 含 30% 超收容差可报至 Z — 确认继续?" 操作员点确认可强制提交 (`forceSubmit=true`). 容差比例工厂级可配, 默认 30%.

这是**防呆 Rule 1** (预先显示边界, 不事后报错) + **Rule 4** (idempotent: 允许分次报工但超限需人工确认) 的组合落地.

---

## 现状分析

### `forceSubmit` 字段

`YieldReportRequest.forceSubmit` 已在 DTO 第 18 行声明 (`// A4 超收软告警后强制提交`), 但 `YieldReportServiceImpl.submitReport` **完全没有读它**, 当前是死字段. 本 spec 在 `submitReport` 的超收检查块中显式读取并使用它.

### 产出量累计方式

`submitReport` 里已有逻辑 (行 90-96): 查该 task 已有 YIELD 报工, 累加 `outputQuantity` 并双写 `t.setActualQuantity(taskTotal)`. `taskTotal` (已有报工之和 + 本次) 即超收判断的**分子**, 与本 spec 设计完全对齐.

### 基准量来源 (P2-1/P2-2 修订: 已确认 plannedQuantity 永远 null)

**验证结论**: `WorkProcessTaskServiceImpl.spawnTasks` (行 87-112) 构建 `WorkProcessTask.builder()` 时**从不设置 `plannedQuantity`**. `ProductWorkProcess` 实体也**没有 `plannedQuantity` 或 `standardYieldMax` 字段** (已读源码确认). 因此草稿版的"spawnTasks 从模板复制 plannedQuantity"描述完全错误 — `WorkProcessTask.plannedQuantity` 在现实代码中永远是 null, 超收检查永远会被跳过.

**Steve 的产品决策**: 基准量 = `thisStep.inputQuantity × WorkProcess.standardYieldMax` (逐道计算).

**技术依据**:
- `WorkProcess` 实体第 57-59 行: `standardYieldMax` (DECIMAL(6,4), 支持 >1 如保水 1.35) 已存在并有正确语义.
- `YieldReportServiceImpl.yieldAlert` (行 114-122) 已用 `processRepo.findById(workProcessId)` 读 `WorkProcess`, 取 `standardYieldMin`/`standardYieldMax` 做告警. 超收检查走**同一路径**.
- `submitReport` 收到的 `req.getInputQuantity()` 即本道投入量 (来自操作员填写).

**计算方式**:
```
target      = req.getInputQuantity() × WorkProcess.standardYieldMax
maxAllowed  = target × (1 + tolerance)
```

**null/跳过条件** (任一满足 → 不做超收检查):
1. `req.getInputQuantity() == null` (未填投入量, 纯包装/检验工序)
2. `WorkProcess.standardYieldMax == null` (该工序未配置出成上限)
3. `target == 0` (投入量为零, 防除零)

**保水工序无误报**: `standardYieldMax = 1.35` (保水) 时 target = input × 1.35, 操作员报保水后产出量正常范围内, 不触发. 这是原生行为, 不需要任何额外判断.

### 容差配置机制

`FactorySettings.productionSettings` 已是 TEXT 列 (行 114), 持久化 `FactorySettingsDTO.ProductionSettings` JSON. 该内嵌类当前有 3 个字段 (行 219-228: `defaultBatchSize`, `qualityCheckFrequency`, `autoApprovalThreshold`). 容差直接加进此 JSON (`yieldOverReceiptTolerance: BigDecimal`). 无 schema migration (TEXT 列, Jackson 加字段向后兼容).

> 无 `productionSettings` 行的工厂 → service 层 default 30%.

---

## 后端设计

### 1. 响应形状 (P1-1 修订: 使用 `ApiResponse.errorWithCode`, 不用 HTTP 200 + requiresConfirmation)

**验证结论**: `ApiResponse.errorWithCode(Integer code, String errorCode, String message, String actionHint, String severity)` (行 147-160) 已存在. 它设 `success=false`, 正好匹配 `request.ts` 拦截器的 `!response.success` 分支.

**草稿版的 `HTTP 200 + requiresConfirmation:true` 设计有致命缺陷**: 前端 `request.ts` 拦截器靠 `response.success` 区分成功/失败; `success=true` 的 200 响应不会进入错误处理分支, 前端无法检测到需要弹确认框的状态.

**修订设计**:
- `forceSubmit=false` 且超限 → 返 `ApiResponse.errorWithCode(409, "OVER_RECEIPT", message, actionHint, "BLOCKING")`, **不保存**, HTTP 状态 409.
- 前端 `request.ts` 拦截器已有 `severity === 'BLOCKING'` 路径 (用 `ElMessageBox.confirm`), 直接复用.
- 用户点"确认超收"→ 前端重发请求附 `forceSubmit: true`.
- `forceSubmit=true` 且超限 → 正常保存 + `log.warn` 记录 + 返正常 `ApiResponse.success({"reportId": ...})`.

**actionHint 内容** (防呆 Rule 1: 含准确数字):
```
"已报 %.2f %s, 目标 %.2f %s (投入 %.2f × 标准上限 %.0f%%), 含 %.0f%% 超收容差最多可报 %.2f %s"
```

### 2. `getLimits` 预检端点 (防呆 Rule 1 核心: dialog 打开即显示边界)

```
GET /api/mobile/{factoryId}/production/batches/{batchId}/yield/limits
    ?workProcessTaskId={id}
    &inputQuantity={quantity}    ← 操作员在 dialog 填写投入量后前端传此值
返回: ApiResponse<YieldLimitsDTO>
```

```java
public class YieldLimitsDTO {
    private Long workProcessTaskId;
    private BigDecimal targetQuantity;   // = inputQuantity × standardYieldMax; null=无基准
    private BigDecimal standardYieldMax; // WorkProcess.standardYieldMax, null=未配置
    private String unit;                 // WorkProcess.unit (产出单位)
    private BigDecimal alreadyReported;  // Σ 已有 YIELD reports 的 outputQuantity
    private BigDecimal toleranceRate;    // 实际生效容差 (0.30 = 30%)
    private BigDecimal maxAllowed;       // = targetQuantity * (1 + toleranceRate); null if no target
    private BigDecimal remaining;        // = maxAllowed - alreadyReported; null if no plan
    private String message;              // "已报 X, 目标 Y, 含30%超收容差最多可报 Z"
}
```

前端报工 dialog 打开、操作员填写投入量后调此端点, 预填 `input maxValue={limits.remaining}` + 显示提示文字. `inputQuantity` 为零或 null 时 `targetQuantity=null`, 前端不显 max 限制.

### 3. `submitReport` 超收检查 (P1-2 修订: 显式读取 forceSubmit)

在 `YieldReportServiceImpl.submitReport` 的 **`reportRepo.save(r)` 之前** 插入检查块. **关键: 显式读 `req.getForceSubmit()`** (draft 里根本没读, 是死字段).

```java
// — 超收检查 (A4) —
// 基准量 = 本道投入 × WorkProcess.standardYieldMax
BigDecimal inputQty = req.getInputQuantity();
if (inputQty != null && inputQty.compareTo(BigDecimal.ZERO) > 0) {
    Optional<WorkProcess> wpOpt = processRepo.findById(t.getWorkProcessId());
    BigDecimal syMax = wpOpt.map(WorkProcess::getStandardYieldMax).orElse(null);
    if (syMax != null) {
        BigDecimal target = inputQty.multiply(syMax);
        if (target.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal tolerance = getToleranceForFactory(factoryId);
            BigDecimal maxAllowed = target.multiply(BigDecimal.ONE.add(tolerance));

            BigDecimal alreadyReported = existingTaskReports.stream()
                    .map(ProductionReport::getOutputQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cumulative = alreadyReported.add(req.getOutputQuantity());

            if (cumulative.compareTo(maxAllowed) > 0) {
                boolean force = Boolean.TRUE.equals(req.getForceSubmit()); // ← 显式读 forceSubmit
                if (!force) {
                    String unit = wpOpt.map(WorkProcess::getUnit).orElse("");
                    String actionHint = String.format(
                        "已报 %.2f %s, 目标 %.2f %s (投入 %.2f × 标准上限 %.0f%%), 含 %.0f%% 超收容差最多可报 %.2f %s",
                        alreadyReported, unit,
                        target, unit,
                        inputQty, syMax.multiply(BigDecimal.valueOf(100)),
                        tolerance.multiply(BigDecimal.valueOf(100)),
                        maxAllowed.setScale(2, RoundingMode.HALF_UP), unit);
                    throw new BusinessException(409, "产出量超过超收容差上限")
                            .withHint(actionHint)
                            .withCode("OVER_RECEIPT")
                            .withSeverity("BLOCKING");
                    // HTTP 409 + success=false + errorCode="OVER_RECEIPT" (per ApiResponse.errorWithCode)
                }
                // forceSubmit=true: 记录告警, 正常保存
                log.warn("[A4-超收] factory={} batch={} task={} target={} cumulative={} maxAllowed={} force=true",
                        factoryId, batchId, t.getId(), target, cumulative, maxAllowed);
            }
        }
    }
}
// 继续正常 save...
```

> **实施注意**: `BusinessException.withCode` / `withSeverity` 若不存在则需补加, 或直接在 controller 捕获 BusinessException 并构建 `ApiResponse.errorWithCode(409, "OVER_RECEIPT", e.getMessage(), e.getHint(), "BLOCKING")`. 查现有 `GlobalExceptionHandler` 确认 409 路径的 body 格式.

### 4. 容差读取 helper

```java
private BigDecimal getToleranceForFactory(String factoryId) {
    try {
        FactorySettings settings = factorySettingsRepo.findByFactoryId(factoryId).orElse(null);
        if (settings == null || settings.getProductionSettings() == null) return DEFAULT_TOLERANCE;
        FactorySettingsDTO.ProductionSettings ps =
            objectMapper.readValue(settings.getProductionSettings(),
                                   FactorySettingsDTO.ProductionSettings.class);
        return ps.getYieldOverReceiptTolerance() != null
               ? ps.getYieldOverReceiptTolerance()
               : DEFAULT_TOLERANCE;
    } catch (Exception e) {
        log.warn("[A4] 读取容差设置失败, 使用默认 30%", e);
        return DEFAULT_TOLERANCE;
    }
}
private static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("0.30");
```

---

## DTO 和配置变更

### `FactorySettingsDTO.ProductionSettings` 加字段

```java
@Schema(description = "出成率报工超收容差 (0.30 = 30%), null 时默认 30%", example = "0.30")
private BigDecimal yieldOverReceiptTolerance;
```

无 schema migration (TEXT 列, Jackson 加字段向后兼容).

### `YieldLimitsDTO` (新建)

路径: `com.cretas.aims.dto.yield.YieldLimitsDTO`, 见上方字段定义.

---

## 无基准量时的处理

三种情形均跳过超收检查:

| 情形 | 条件 | getLimits 返回 | submitReport 行为 |
|---|---|---|---|
| 未填投入量 | `req.inputQuantity == null` | `targetQuantity=null, maxAllowed=null, remaining=null, message="未填投入量, 无超收告警"` | 跳过检查, 正常保存 |
| 工序无出成上限 | `WorkProcess.standardYieldMax == null` | `targetQuantity=null, message="该工序未配置标准出成上限, 无超收告警"` | 跳过检查, 正常保存 |
| 投入量为零 | `inputQty == 0` | `targetQuantity=null` | 跳过检查, 正常保存 |

前端 input 无 `maxValue` 限制, 无弹窗. 这是正确行为: 未配置标准的工序完全透传, 不干扰正常使用.

---

## 保水工序特例

`WorkProcess.standardYieldMax = 1.35` (保水滚揉) 时: 本道 target = inputQty × 1.35. 保水后产出量最高为投入 135%. maxAllowed = target × 1.30 = inputQty × 1.755. 只有产出超过投入 175.5% 才触发告警. 正常保水操作 (≤135%) 完全不触发. 实现不需要任何特殊判断, 是 `standardYieldMax` 语义的自然结果.

---

## 前端设计 (RN App)

### 报工 dialog (`YieldStepReportScreen`)

1. mount 时初始化 limits 为 null (不预取, 因为投入量未知).
2. 操作员填写投入量后 (onBlur / onChange debounce 500ms): 若 `inputQuantity > 0` 则调 `GET /yield/limits?workProcessTaskId=X&inputQuantity=Y`.
3. limits 返回且 `limits.maxAllowed != null`: 显示 "目标: {target} / 已报: {alreadyReported} / 本次最多可报: {remaining}" + `TextInput maxValue={limits.remaining}`.
4. 提交响应处理 (识别 `errorCode === 'OVER_RECEIPT'`):
   - 收到 `success=false && errorCode === 'OVER_RECEIPT'` → `Alert.alert('超收确认', response.actionHint, [{text:'取消'}, {text:'确认超收提交', onPress: () => submitWithForce()}])`
   - `submitWithForce()` 重发请求加 `forceSubmit: true`
   - 正常 `reportId` → 成功关闭

> **request.ts 拦截器**: 现有拦截器对 `success=false` 弹 sticky error toast. 对 `OVER_RECEIPT` 需要前端在调用方 **catch** 住 (不让拦截器自动弹 toast), 改为弹 `Alert.alert` 确认框. 实施时用 `{ skipDefaultErrorHandler: true }` 请求选项 (或等价的 axios 请求级拦截器绕过机制, 查现有 RN API client 实现).

---

## 容差粒度说明 (P3-1)

当前设计: 容差在 `FactorySettings.productionSettings.yieldOverReceiptTolerance` (工厂级, 一个值). 已验证 `FactorySettings.productionSettings` TEXT 列 + `FactorySettingsDTO.ProductionSettings` 内嵌类存在. Phase-B 扩展点: 可在 `WorkProcess` 加 `overReceiptTolerance DECIMAL(5,4)` 列, 实现工序级精细控制 (如保水工序容差 0, 末道容差 30%). 本 Phase 不做.

---

## 文件变更清单

| 操作 | 文件 |
|---|---|
| 修改 | `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/FactorySettingsDTO.java` — `ProductionSettings` 加 `yieldOverReceiptTolerance` |
| 新建 | `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/yield/YieldLimitsDTO.java` |
| 修改 | `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java` — 超收检查块 (读 `processRepo` 取 `standardYieldMax` + 显式读 `req.getForceSubmit()`) + `getToleranceForFactory` helper |
| 修改 | `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/YieldReportService.java` — 新增 `getLimits` 接口方法 |
| 修改 | `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/YieldReportController.java` — GET `/limits` 端点 |
| 修改 | `frontend/CretasFoodTrace/src/screens/yield/YieldStepReportScreen.tsx` — 投入量 onChange 预检 + OVER_RECEIPT errorCode 确认 Alert |
| 修改 | `frontend/CretasFoodTrace/src/services/api/yieldReportApi.ts` — 新增 `getYieldLimits(factoryId, batchId, workProcessTaskId, inputQuantity)` |
| 无改动 | `dto/yield/YieldReportRequest.java` — `forceSubmit` 字段已存在 |
| 无改动 | `entity/FactorySettings.java` — `productionSettings` TEXT 列已存在 |
| 无改动 | `entity/WorkProcess.java` — `standardYieldMax` 字段已存在 |
| 无改动 | `entity/workprocess/WorkProcessTask.java` — `plannedQuantity` 字段已存在但永远 null, 本 spec 完全不用它 |

---

## 测试计划

| 测试 | 场景 | 预期 |
|---|---|---|
| 单测: 无告警路径 | input=100, syMax=0.85, target=85, 已报 0, 本次 80, tolerance=0.30 → maxAllowed=110.5, cumul=80 | 正常保存, 无 OVER_RECEIPT |
| 单测: 软告警路径 | input=100, syMax=1.0, target=100, 已报 100, 本次 31, tolerance=0.30 → maxAllowed=130, cumul=131 > 130, forceSubmit=false | success=false, errorCode="OVER_RECEIPT", actionHint 含准确数字 (100/100/130), 不保存 |
| 单测: 强制提交 | 同上 + forceSubmit=true | 正常保存 reportId, log.warn 含 "[A4-超收]" |
| 单测: 保水工序无误报 | input=100, syMax=1.35, target=135, 已报 130, 本次 5 → cumul=135 ≤ maxAllowed=175.5 | 正常保存, 无告警 |
| 单测: null standardYieldMax | WorkProcess.standardYieldMax=null | 跳过检查, 正常保存 |
| 单测: null inputQuantity | req.inputQuantity=null | 跳过检查, 正常保存 |
| 单测: 自定义容差 | yieldOverReceiptTolerance=0.10 in FactorySettings.productionSettings JSON → maxAllowed=target×1.10 | 精确算, 覆盖默认 30% |
| 单测: 容差读取 fallback | factory 无 productionSettings (null) → DEFAULT_TOLERANCE 0.30 | 不抛异常, 用 0.30 |
| 单测: getLimits 有数据 | input=100, syMax=0.85, 已有 YIELD Σ=60, tolerance=0.30 → target=85, maxAllowed=110.5, remaining=50.5 | message 含"已报 60 / 目标 85 / 最多可报 50.5" |
| 单测: getLimits 无基准 | syMax=null | targetQuantity=null, maxAllowed=null, remaining=null, 无告警 message |

---

## 开放问题

**Q1: `BusinessException.withCode` / `withSeverity` 是否存在?**

实施者需验证现有 `BusinessException` 是否支持 `withCode`/`withSeverity` 链式调用, 且 `GlobalExceptionHandler` 是否把这两个字段映射到 `ApiResponse.errorWithCode` 的参数. 若不支持, controller 层 `@ExceptionHandler(BusinessException.class)` 需补捕获逻辑. 本 spec 不要求实施者修改 BusinessException 设计, 但需核对后决定实施路径.

**Q2: RN API client 是否有 `skipDefaultErrorHandler` 机制?**

OVER_RECEIPT 响应需要前端不走默认 sticky toast, 而是弹 `Alert.alert` 确认框. 实施者查 `frontend/CretasFoodTrace/src/services/api/` 的 axios 拦截器确认是否已有请求级绕过选项, 若无则需加 (常见做法: 请求 config 加 `_skipErrorHandler: true`).

**Q3: 超收是否应有硬上限?**

当前设计是"永远软告警, forceSubmit 可突破". 张权原话"你告诉他这个东西你要收多少就行了"倾向软提示. 若未来主管希望有绝对上限 (如 target × 2 强制阻止), 可在 `FactorySettings.productionSettings` 加 `yieldHardCapMultiplier`. 当前 Phase 不实现.

---

## §9 审计修订记录 (2026-06-01)

审计对 draft 版进行代码核查, 发现 3 个 P1 级缺陷 + 1 个 P2 级问题. 下表记录每项发现与修订结果:

### 已修订的缺陷

| 编号 | 级别 | 草稿错误描述 | 代码核查结论 | 修订方式 |
|---|---|---|---|---|
| P2-1 | P1 | 草稿称 `WorkProcessTask.plannedQuantity` 是"spawnTasks 从 ProductWorkProcess 模板复制"的正确基准量 | `spawnTasks` (行 87-112) 的 `builder()` 链**从不调用 `.plannedQuantity(...)`**; `ProductWorkProcess` 实体**没有** `plannedQuantity` 或 `standardYieldMax` 字段. `plannedQuantity` 永远是 null | 彻底替换基准量来源: 改为 `req.inputQuantity × WorkProcess.standardYieldMax`. 删除所有"从模板复制"表述 |
| P2-2 | P1 | 草稿称"检查的是产出 vs WorkProcessTask.plannedQuantity"; 保水章节说"若计划量设置正确则不误报"(前提根本不成立) | 同 P2-1: `plannedQuantity` 永远 null → 草稿设计中检查永远被跳过 | 同 P2-1. 保水章节改为基于 `standardYieldMax=1.35` 的正确推导 |
| P1-1 | P1 | 草稿设计超限返回 `HTTP 200 + {requiresConfirmation:true}` + 说"HTTP 200 不是 4xx, 这是可确认操作不是错误" | `ApiResponse.success()` 强制 `success=true`; 前端 `request.ts` 拦截器靠 `!response.success` 区分成功/失败; `success=true` 的 200 响应不进错误分支, 前端无法检测到需确认状态. `ApiResponse.errorWithCode(409, "OVER_RECEIPT", msg, actionHint, "BLOCKING")` 已存在 (行 147-160) | 改为: forceSubmit=false 超限 → HTTP 409 + `errorWithCode("OVER_RECEIPT")` + `severity="BLOCKING"`, 不保存. 前端识别 `errorCode==='OVER_RECEIPT'` 弹确认 Alert |
| P1-2 | P1 | 草稿超收检查代码块中写了 `Boolean.TRUE.equals(req.getForceSubmit())` 但说明描述模糊; 实际 `submitReport` 完全没有读 `forceSubmit` 的代码 | `YieldReportServiceImpl.submitReport` (行 50-112) 确认**从不调用 `req.getForceSubmit()`**. `forceSubmit` 在 `YieldReportRequest.java:18` 声明但是死字段 | 规范中明确: 超收检查块必须显式调用 `req.getForceSubmit()` 并按 true/false 分支; 文件变更清单注明此修改 |
| P3-1 | P2 | 草稿提容差粒度问题作为 Q1 开放问题未决 | `FactorySettings.productionSettings` TEXT 列 + `FactorySettingsDTO.ProductionSettings` 内嵌类均已存在并可直接加字段. `WorkProcess` 也有相应字段可扩展 | 明确当前 Phase 用工厂级默认; 在"容差粒度说明"节记录 Phase-B 工序级扩展点. Q1 从开放问题移除 |

### 草稿中保留的正确部分

- 累计产出的计算方式 (existingTaskReports stream + 本次 add) — 与 `submitReport` 行 90-96 完全对齐, 无修改.
- 容差读取 helper 设计 (FactorySettings JSON + DEFAULT_TOLERANCE fallback) — 代码核查确认路径存在, 保留.
- `getLimits` 端点设计思路 — 保留并更新字段 (用 `targetQuantity` 替代 `plannedQuantity`).
- 防呆 Rule 1 核心设计 (dialog 预检 + 明确数字告警) — 保留.
- 保水工序无误报的直觉正确, 但机制改变 (从"计划量正确设置"到"`standardYieldMax` 天然覆盖") — 重写说明.
