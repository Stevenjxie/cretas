# 报工体系统一 — Phase D RN 设计（operator 逐道工序双量报工）

**日期**: 2026-06-01
**分支**: `feat/yield-rn-phase-d`（off main，已含 Phase A 后端 PR #350）
**作者**: Claude (spec 起草，待 Steve 审)
**前置**: Phase A 后端 5 端点已 LIVE on prod（`YieldReportController`）
**范围**: 只新建 RN operator 逐道报工界面接 Phase A 出成率 API。本文档不含 RN 实现代码。

---

## 0. 一句话 + 背景

**给产线工人（operator）一个"一道工序一张全屏卡片、填投入量+产出量双量、下道投入预填上道产出"的逐道报工界面**，把已 LIVE 的 Phase A 出成率后端（`production_reports` 报工事实层 + 出成率派生）真正落到工人手上。

背景：Phase A（PR #350，已 LIVE prod）上线了报工事实层（`production_reports` 加 13 列：投入/产出双量 + 出库/投料双量 + 中间批次号 + carryover + 结清标记）+ 出成率配置（`work_processes` 加 4 列：`standardYieldMin/Max` 标准出成率区间 + `needsInput` + `outputUnit` 单位换算）+ 出成率派生服务（`YieldCalculationService`，金标准 猪舌 998kg → 382.08kg，端到端累计出成率 0.3828）+ 5 个 REST 端点（`YieldReportController`）。**命门**是"逐道双量"：每道填投入量 + 产出量，下道投入预填上道产出，工人确认或改成实际投了多少（焯水产 998 只投 360 的场景）。Phase A 只有后端 + 单测，工人无界面录入 → 本期补 operator RN 界面。

---

## 1. 现状（为什么要做 Phase D）

### 1.1 RN 现有报工界面的局限

| 现有屏 | 文件 | 局限 |
|---|---|---|
| 主管代报审批流 | `frontend/CretasFoodTrace/src/screens/processing/ProcessTaskReportScreen.tsx`、`ThreeStepReportScreen.tsx`、`TeamBatchReportScreen.tsx`、`DynamicReportScreen.tsx` | 主管/班长视角，**只采产出量（outputQuantity）**，走 `process-work-reporting/normal` 审批流，无"投入量"概念 |
| operator 个人扫码报工 | `frontend/CretasFoodTrace/src/screens/processing/ScanReportScreen.tsx`（OperatorNavigator 报工 Tab 唯一入口） | 扫码 → 单值产出，**无逐道链 + 无投入量 + 不接 Phase A yield 端点** |
| 主管审批 | `WorkReportApprovalScreen.tsx`、`ProcessTaskApprovalScreen.tsx` | 纯审批，非录入 |

**结论**: 现有报工屏一律只采"产出"单量，无"投入量"，无"下道预填上道产出"，无出成率链路。operator 在 `OperatorNavigator`（4 tab：考勤/报工/工作/我的）报工 Tab 里只有 `ScanReportScreen` 个人扫码报工，看不到"按工序逐道、双量、出成率"的报工流程。

### 1.2 Phase A 后端命门（已 LIVE，本期前端落地）

- **逐道双量**: 每道报工 `POST /reports` body 必带 `inputQuantity` + `outputQuantity` 双量。
- **下道预填上道产出**: `GET /yield` 返回 `steps[]`，每个 `StepYieldDTO.totalOutput` 是上道产出 → 下道 dialog 预填这个值，工人确认或改实际投料量。
- **A7 出成率越界软告警**: `StepYieldDTO.yieldAlert` = `"BELOW_MIN"` / `"ABOVE_MAX"` / null（在区间内或未配区间）。软告警不硬拦。
- **A4 超收软告警**: 设计 §3.5 定义"超收软告警 + 强制提交 + 容差 30% 可配"，`YieldReportRequest` 已有 `forceSubmit: Boolean` 字段（`YieldReportRequest.java:18`）。**⚠️ 核实纠正: Phase A `YieldReportServiceImpl.java` 当前 NOT 实现 A4** — `submitReport` 不读 `forceSubmit`、不检查超收、不抛超收错（只校验 workProcessTaskId/outputQuantity 必填）。所以 A4 超收拦截/强制流程**后端尚未生效**，前端 A4 交互（§3.3）按"设计意图"写，但实跑时后端不会拒绝超收（仅 Phase A 现状）。前端可先实现 forceSubmit 透传 + 客户端软提示（基于 `plannedQuantity`），后端补 A4 后无缝生效（见 §附开放问题）。
- **carryover 结转**: `StepYieldDTO.carryover` = 上道产出 − 本道投入（>0 说明有结转/未投完）。
- **量纲不可比**: `StepYieldDTO.unitComparable=false`（如 kg → 盒）时 `yieldRate=null`，只展示量。

---

## 2. 设计原则（Steve 拍板 + 防呆）

> 以下 5 条决策由 Steve 拍板，spec 照此执行，不推翻。

1. **布局 = 一道一卡片（全屏单工序）**。一次只显示一道工序占满全屏，大字 + 大输入框 + 大按钮，报完滑/点到下一道。顶部小进度条（`报工 3/7 ●●●○○○○`）。**不是**整批一屏看全链。客户张权口径：仓管/产线工人年纪大文化低，一次只盯一件事。
2. **全新建屏**，不改造老 `ProcessTaskReportScreen.tsx` / `ThreeStepReportScreen.tsx`（主管代报审批流，只采产出量，保留并存）。Phase A 已明确并存。
3. **命门交互 = 投入量 + 产出量双量**。下道投入**预填上道产出**（从 `GET /yield` 的 `steps[i-1].totalOutput`），工人**确认或改成实际投料量**。预填值显著标注"← 上道产出，请确认实际投了多少"。
4. **防呆**（`.claude/rules/fool-proof-design.md`）：Rule 1 预先显示边界（A7 出成率越界软告警，提交后据后端 alert 显，不硬拦）、Rule 2 上下文必带（卡片头显示 品名 + 批次号 + 工序名 + 计划数量）、大按钮、错误 toast sticky。
5. **operator 角色新增工作台入口**。现状见 §3.4 修正：operator 走专属 `OperatorNavigator`（不是通用 HomeScreen），逐道报工入口加在该 navigator 的报工 Tab。

### 2.1 防呆 4 位一体（任何写操作错误必满足）

| # | 检查 | 本屏落地 |
|---|---|---|
| a | 网络 `response.message` 具体 | Phase A 后端 `BusinessException` 带 message + hint（如 `"缺少必填字段: outputQuantity"` + hint `"请填写本道产出量"`，`YieldReportServiceImpl.java:50-52`），原样透传。A4 超收 message 待后端补（见 §3.3 ⚠️） |
| b | UI toast = 后端 message | catch `error.response.data.message`，不吞用 fallback |
| c | toast sticky | error toast 用 `Alert.alert` 或常驻 banner（RN 无 ElMessage，用持久 Alert/inline banner，见 §3.3） |
| d | 含 next action 提示 | A4 拒绝 → "如确认请按【确认超收提交】" 按钮（带 forceSubmit）；空状态 → EmptyState actionText 跳配置 |

---

## 3. 详细设计

### 3.1 新 API service `yieldReportApi.ts`

**新建**: `frontend/CretasFoodTrace/src/services/api/yieldReportApi.ts`
照 `apiClient` 模式（`apiClient.ts:135-158`：拦截器已统一 `return response.data`，注入 JWT，401 自动刷新）。参照 `processTaskApiClient.ts:100-104` 的 `getBase()` + `requireFactoryId()` 模式（`utils/factoryIdHelper.ts:7`）。

> ⚠️ Phase A 后端 base path = `/api/mobile/{factoryId}/production/batches/{batchId}`（`YieldReportController.java:22`，`batchId` 是 `Long`）。**注意** 与老 `process-tasks`（`ProcessTask` 实体）不同：本流程的工序任务用 `WorkProcessTask` 实体，列表端点 = `/api/mobile/{factoryId}/production/batches/{batchId}/work-process-tasks`（`WorkProcessTaskController.java:93-99`）。

#### TS 类型（1:1 mirror Phase A DTO + 容忍 Map 响应）

```ts
// mirror BatchYieldDTO.java (BigDecimal → number, Jackson NON_NULL 不强制 → 字段可选)
export interface BatchYieldDTO {
  batchId: number;
  batchNumber: string;
  firstStepInput: number | null;        // 998
  lastStepOutput: number | null;        // 382.08 或 3184 盒
  firstStepInputUnit: string | null;
  lastStepOutputUnit: string | null;
  cumulativeYieldRate: number | null;   // 0.3828
  steps: StepYieldDTO[];
  complete: boolean | null;             // 每道都有 input+output 才 true
}

// mirror StepYieldDTO.java
export interface StepYieldDTO {
  workProcessTaskId: number;
  processOrder: number;
  processName: string | null;
  totalInput: number | null;            // Σ input
  totalOutput: number | null;           // Σ output (下道预填用这个)
  inputUnit: string | null;
  outputUnit: string | null;
  yieldRate: number | null;             // Σoutput/Σinput; 量纲不可比时 null
  unitComparable: boolean | null;       // false → 只展示量
  carryover: number | null;             // 上道产出 − 本道投入 (>0 结转)
  yieldAlert: 'BELOW_MIN' | 'ABOVE_MAX' | null;  // A7 越界软告警
}

// mirror YieldReportRequest.java (POST /reports body)
export interface YieldReportRequest {
  workProcessTaskId: number;
  inputQuantity: number;                // 本道投入 (前端预填上道产出, 可改)
  inputUnit?: string;
  outputQuantity: number;               // 本道产出
  outputUnit?: string;
  workMinutes?: number;                 // 选填工时 (后端 Integer)
  forceSubmit?: boolean;                // A4 超收软告警后强制提交
  sourceBatchRefs?: Array<Record<string, unknown>>;  // 跨批来源 (A3, Phase D 默认不传)
  reporterName?: string;
  targetWorkerId?: number;              // 代报工 (Phase D operator 自报, 默认不传)
}

// mirror MaterialInputRequest.java (PUT /material-input body)
export interface MaterialInputRequest {
  workProcessTaskId: number;
  warehouseOutQuantity: number;         // 出库量 998
  feedInQuantity: number;               // 投料量 935.5
  inputUnit?: string;
}

// submitReport / recordMaterialInput / settleDay 后端返回 Map<String,Object> (非 typed DTO)
// — 用宽松类型, 按后端实际 put 的 key 取值 (核实 YieldReportServiceImpl.java)
//
// ⚠️ 核实结果 (YieldReportServiceImpl.java:95-106): submitReport 返回 Map 只 put 3 个 key —
//    reportId (always), yieldRate (always, 量纲不可比时 null), alert (仅 alert!=null 才 put).
//    没有 intermediateBatchNo / overReceiveAlert / cumulativeYieldRate (那些是 GET /yield 才有).
export interface YieldReportResult {
  reportId: number;
  yieldRate: number | null;                       // 本道 output/input (input==output unit 且 input>0 才算, 否则 null)
  alert?: 'BELOW_MIN' | 'ABOVE_MAX';              // A7 越界软告警 (仅越界时存在; null 时 key 不出现)
}

// recordMaterialInput 返回 Map 只 put { reportId } (YieldReportServiceImpl.java:160-162)
export interface MaterialInputResult {
  reportId: number;
}

// settleDay 返回 Map: { settledCount, batchYield (=BatchYieldDTO), completed } (YieldReportServiceImpl.java:182-196)
export interface SettleDayResult {
  settledCount: number;
  batchYield: BatchYieldDTO;
  completed: boolean;        // triggerComplete=true 且末道产出>0 才回写批次 → true
}
```

#### 5 个方法（mirror `YieldReportController` 5 端点）

```ts
class YieldReportApi {
  private getBase(batchId: number, factoryId?: string) {
    const fid = requireFactoryId(factoryId);
    return `/api/mobile/${fid}/production/batches/${batchId}`;
  }

  // 1. POST /reports — 逐道报工 (投入+产出双量)
  //    注意: workerId 后端从 @RequestAttribute("userId") JWT 取, body 不传 workerId
  async submitReport(batchId: number, req: YieldReportRequest, factoryId?: string):
      Promise<ApiResponse<YieldReportResult>> {
    return apiClient.post(`${this.getBase(batchId, factoryId)}/reports`, req);
  }

  // 2. PUT /material-input — 首道领料双量 (出库量+投料量)
  async recordMaterialInput(batchId: number, req: MaterialInputRequest, factoryId?: string):
      Promise<ApiResponse<MaterialInputResult>> {
    return apiClient.put(`${this.getBase(batchId, factoryId)}/material-input`, req);
  }

  // 3. GET /yield — 整批+单工序出成率 (派生, 下道预填用 steps)
  async getYield(batchId: number, factoryId?: string):
      Promise<ApiResponse<BatchYieldDTO>> {
    return apiClient.get(`${this.getBase(batchId, factoryId)}/yield`);
  }

  // 4. POST /settle-day?date=&triggerComplete= — 每日结清
  async settleDay(batchId: number, opts: { date?: string; triggerComplete?: boolean } = {},
      factoryId?: string): Promise<ApiResponse<SettleDayResult>> {
    const params = new URLSearchParams();
    if (opts.date) params.append('date', opts.date);
    if (opts.triggerComplete != null) params.append('triggerComplete', String(opts.triggerComplete));
    const qs = params.toString();
    return apiClient.post(`${this.getBase(batchId, factoryId)}/settle-day${qs ? `?${qs}` : ''}`);
  }

  // 5. GET /reports — 报工流水 (当前后端复用 getYield 的 steps 聚合视图, 返回 BatchYieldDTO)
  async listReports(batchId: number, factoryId?: string):
      Promise<ApiResponse<BatchYieldDTO>> {
    return apiClient.get(`${this.getBase(batchId, factoryId)}/reports`);
  }
}
export const yieldReportApi = new YieldReportApi();
```

> 同时复用现有 `workProcessTaskApi`（见 §3.1.1）拿"该批次的工序任务列表"。

#### `ApiResponse<T>` 包装类型

照 `workReportingApiClient.ts:13-18`：
```ts
interface ApiResponse<T> { success: boolean; code: number; message: string; data: T; }
```
> 拦截器 `apiClient.ts:55` 已 `return response.data`（解包 axios 一层），所以 `apiClient.get<...>()` 拿到的是后端整个 `{success,code,message,data}` 信封。consumer 读 `res.data` 取业务数据。

#### 3.1.1 工序任务列表（已有端点，是否新建 service 待定）

后端端点 = `GET /api/mobile/{factoryId}/production/batches/{batchId}/work-process-tasks` → `ApiResponse<List<WorkProcessTaskDTO>>`，按 `processOrder` 升序（`WorkProcessTaskController.java:93-99`）。

**默认选择**: 在 `yieldReportApi.ts` 内加一个 `listWorkProcessTasks(batchId, factoryId?)` 方法（同 base path 下，逻辑内聚），返回 `ApiResponse<WorkProcessTask[]>`（复用 `types/workProcess.ts` 的 `WorkProcessTask`）。

```ts
// yieldReportApi.ts 内
async listWorkProcessTasks(batchId: number, factoryId?: string):
    Promise<ApiResponse<WorkProcessTask[]>> {
  const fid = requireFactoryId(factoryId);
  return apiClient.get(`/api/mobile/${fid}/production/batches/${batchId}/work-process-tasks`);
}
```

`WorkProcessTaskDTO` 形状（**已逐字核实** `WorkProcessTaskDTO.java:25-71`，对应前端已有 `types/workProcess.ts:19-45` 的 `WorkProcessTask`，**RN 端直接 import 复用，不在本 service 重定义**）：
```ts
// 复用 frontend/CretasFoodTrace/src/types/workProcess.ts:19 的 WorkProcessTask (已存在, import 即可)
export interface WorkProcessTask {
  id: number;                  // = workProcessTaskId (报工 req 用这个)
  factoryId: string;
  productionBatchId: number;   // 注意: 不是 batchId
  productWorkProcessId: number;
  workProcessId: string;       // String (非 number)
  productTypeId: string;
  processOrder: number;        // 第几道
  status: WorkProcessTaskStatus; // PENDING/IN_PROGRESS/COMPLETED/SKIPPED
  plannedQuantity: number;     // 计划数量 (防呆 Rule 1 max + Rule 2 context)
  plannedUnit: string;         // 计划单位 (非 unit)
  actualQuantity?: number;     // 累计产出 (Phase A 双写)
  assignedTo?: number;
  processName?: string;        // 工序名 (焯水/卤制...) 卡片头用 (后端 join 提供, 选填)
  processCategory?: string;
  estimatedMinutes?: number; actualMinutes?: number; notes?: string;
  // ...时间字段省略
}
```

> ⚠️ **核实纠正**（与初稿假设不同，重要）: `WorkProcessTaskDTO` **没有** `batchNumber` / `productName` / `productSpec` / `inputQuantity` / `standardYieldMin/Max` / `assignedToName` 字段。逐道卡片头所需 context **需两个 API 合并**：
> - **品名 + 批次号**: 从 `processingApiClient.getBatchById(String(batchId), factoryId)` → `ApiResponse<ProcessingBatch>`，`ProcessingBatch { batchNumber, productType, targetQuantity, status, actualQuantity }`（`processingApiClient.ts:62-90,174`，**无独立 BatchDetailResponse 文件**，类型即 `ProcessingBatch`）。⚠️ 品名字段是 **`productType`**（string，产品名，如"卤猪蹄"），**不是** `productName`；**无 `productSpec`（规格）字段** → 卡片头只能显 `productType`，mockup 里的"200g"规格当前后端不返，需后端补字段或省略（见 §附开放问题）。
> - **工序名 + 计划数量 + 单位**: 从 `listWorkProcessTasks` 的每个 `WorkProcessTask`（`processName` / `plannedQuantity` / `plannedUnit`）。
> - **A7 标准出成率区间** (`standardYieldMin/Max`): 在 `WorkProcess` 实体上（`WorkProcess.java:138-141`），**未** 透出到 `WorkProcessTaskDTO`。→ **客户端无法预先估算 A7 越界**，只能用 `submitReport` 返回的 `alert`（后端算）。mockup 里"标准区间 85%–95%"展示需后端在 task DTO 加这两列才能显（见 §附开放问题）。
> - `totalOutput`（下道预填）: 从 `getYield` 的 `steps[i-1].totalOutput`。
>
> 即：每道卡片 = `getBatchById`(品名/批次号) + `listWorkProcessTasks`(工序名/计划量/单位) + `getYield.steps`(预填上道产出 + 提交后的 yieldRate/carryover/yieldAlert)，三处合并。

---

### 3.2 屏幕流

#### ASCII 屏幕流图

```
 OperatorNavigator(报工 Tab)
        │
        ▼
 ┌──────────────────────┐   选业态/有生产能力工厂才显
 │ 报工 Tab 落地页       │   (新加 stack screen, 见 §3.5)
 │  [扫码个人报工]  老   │
 │  [逐道工序报工]  新★  │ ← 新入口
 └──────────────────────┘
        │ 点"逐道工序报工"
        ▼
 ┌──────────────────────┐   YieldBatchSelectScreen (新)
 │ 选批次屏              │   listByBatch 反查: 用 work-process-tasks
 │  搜索/扫码 批次号     │   列表 (status≠COMPLETED 的批次) + 可扫码
 │  ┌────────────────┐  │   每行: 批次号 + 品名 + 进度(已报 N/M 道)
 │  │ B-2026..猪舌    │  │
 │  │ 卤猪蹄  3/7      │  │
 │  └────────────────┘  │
 └──────────────────────┘
        │ 选批次 → 传 batchId
        ▼
 ┌──────────────────────┐   YieldStepReportScreen (新, 一道一卡片)
 │  报工 3/7 ●●●○○○○     │ ← 顶部进度条
 │ ┌──────────────────┐ │
 │ │ 卤猪蹄            │ │ ← 卡片头: 品名 (规格无字段)
 │ │ B-2026.. / 焯水   │ │ ← 批次号 + 工序名
 │ │ 计划 998 kg       │ │ ← 计划数量 (Rule 2)
 │ ├──────────────────┤ │
 │ │ 投入量            │ │
 │ │ [  935.5  ] kg    │ │ ← 预填上道产出, 可改
 │ │ ← 上道产出998,请确认│ │ ← 预填标注 (命门)
 │ │ 实际投了多少       │ │
 │ ├──────────────────┤ │
 │ │ 产出量            │ │
 │ │ [  998.0  ] kg    │ │ ← 工人填本道产出
 │ ├──────────────────┤ │
 │ │⚠出成率107%超上限95%│ │ ← A7 软告警 banner (不拦)
 │ ├──────────────────┤ │
 │ │ [ 提交下一道 ▶ ]  │ │ ← 大按钮
 │ └──────────────────┘ │
 └──────────────────────┘
        │ 提交 (POST /reports) → 刷新 getYield → 下一道预填
        ▼ (滑到下一道, 进度 4/7)
        │  ... 末道提交后
        ▼
 ┌──────────────────────┐   完成态 (同屏切换或 YieldDoneScreen)
 │  ✔ 7/7 道全部报完     │
 │  累计出成率 38.28%    │ ← cumulativeYieldRate
 │  998kg → 382.08kg     │ ← firstStepInput → lastStepOutput
 │  [ 标记今日结清 ]     │ ← settle-day (默认 operator 端简单做, 见 §4)
 │  [ 返回选批次 ]       │
 └──────────────────────┘
```

#### 一道一卡片 mockup（全屏单工序，大字大按钮）

```
╔════════════════════════════════════╗
║  报工  3 / 7   ●●●○○○○              ║  ← 进度条 (ProgressBar 或自绘点)
╠════════════════════════════════════╣
║                                    ║
║   卤猪蹄                            ║  ← 品名 (ProcessingBatch.productType, 来自 getBatchById) 大字 22pt; 规格 200g 当前无字段
║   B-20260601-0007  ·  焯水          ║  ← 批次号 (ProcessingBatch.batchNumber) · 工序名 (task.processName)
║   计划数量  998 kg                  ║  ← task.plannedQuantity + plannedUnit (灰字, Rule 2)
║                                    ║
║  ┌──────────────────────────────┐  ║
║  │  投入量                       │  ║
║  │  ┌───┐ ┌──────────┐ ┌───┐    │  ║  ← YieldQuantityInput (新建, − [935.5] kg +)
║  │  │ − │ │  935.5   │kg│ + │    │  ║     value 预填 上道 totalOutput
║  │  └───┘ └──────────┘ └───┘    │  ║
║  │  ← 上道产出 998 kg            │  ║  ← 预填标注 (橙色高亮, 命门)
║  │     请确认实际投了多少         │  ║
║  └──────────────────────────────┘  ║
║                                    ║
║  ┌──────────────────────────────┐  ║
║  │  产出量                       │  ║
║  │  ┌───┐ ┌──────────┐ ┌───┐    │  ║  ← YieldQuantityInput
║  │  │ − │ │  998.0   │kg│ + │    │  ║
║  │  └───┘ └──────────┘ └───┘    │  ║
║  └──────────────────────────────┘  ║
║                                    ║
║  ┌──────────────────────────────┐  ║
║  │ ⚠ 出成率偏高, 请核对           │  ║  ← A7 软告警 banner (提交后据后端 alert, 不拦)
║  │   (具体区间数字待后端透出)      │  ║     submitReport.alert = ABOVE_MAX
║  └──────────────────────────────┘  ║
║                                    ║
║  ┌──────────────────────────────┐  ║
║  │      提交  ·  下一道  ▶        │  ║  ← 大按钮 (高 56pt, Button title)
║  └──────────────────────────────┘  ║
╚════════════════════════════════════╝
```

> 首道（processOrder 最小）无"上道产出"，投入量不预填，提示改"本道领料投入量"（首道领料双量屏不做，见 §4，首道投入直接当普通投入填，或后续接 `material-input`）。
>
> ⚠️ mockup 里的 "卤猪蹄 200g" 规格、"标准区间 85%–95%" 均为示意：当前 `ProcessingBatch` 无 `productSpec`（规格），`WorkProcessTaskDTO` 无 `standardYieldMin/Max` → 这两处实际渲染需后端补字段（见 §附开放问题），本期卡片头品名只显 `ProcessingBatch.productType`（如"卤猪蹄"），A7 banner 只在提交后据后端 `alert` 显（不带具体区间数字，文案为"出成率偏高/偏低，请核对"）。

---

### 3.3 防呆落地（4 位一体）

| 规则 | 落地 |
|---|---|
| **Rule 1 预先显示边界** | (a) 数量输入（新建 `YieldQuantityInput`, 见 §3.7）投入量软上限 `max ≈ plannedQuantity × 1.3`（A4 容差 30%），不硬 clamp（允许输入中间值），**实时**显示"计划 998 kg，可投上限约 1297 (含 30% 超收)"。(b) **A7 限制**: `standardYieldMin/Max` 在 `WorkProcess` 实体上，**未透出到 task DTO** → 客户端**无法**预先本地估算越界。故 A7 软告警**只能在提交后**用 `submitReport` 返回的 `alert`（`BELOW_MIN`/`ABOVE_MAX`，后端算）显示，**或** 后端在 task DTO 加 `standardYieldMin/Max` 后客户端才能预估（见 §附开放问题）。本期默认：提交后显 A7 banner。 |
| **Rule 2 上下文必带** | 卡片头固定显示：品名（`ProcessingBatch.productType`，来自 `getBatchById`）+ `batchNumber`（批次号，来自 `getBatchById`）+ `processName`（工序名，来自 task）+ `plannedQuantity plannedUnit`（计划数量，来自 task）。⚠️ 注意分两个 API 拿（`getBatchById` 取品名/批次号，`listWorkProcessTasks` 取工序名/计划量），**非**一个 API 拿全（核实纠正，见 §3.1 ⚠️）。规格（200g）当前无字段，省略。 |
| **Rule 3 自由文本改约束** | 本屏无 reason 字段（纯数量录入），不涉及。 |
| **Rule 4 幂等防重复** | 提交按钮提交中 `disabled`（loading）防双击。Phase A 后端 A6 中间批次号仅首条生成（C1 已修），同任务分次报工不撞唯一约束，**业务上允许同任务多次报工**（分次产出），所以前端不做"已报过就拦"，只防 double-tap。 |
| **Rule 5 dead-end 改导航** | 选批次屏空列表 → `EmptyStateCard`（`components/common/EmptyStateCard.tsx`，props `icon/title/message/actionLabel/onAction`）显示"暂无待报工批次"+ actionLabel "去创建生产批次" 跳批次创建（或提示找主管）。批次无工序任务 → "该批次未生成工序，请联系主管 spawn 工序" + 跳/提示。 |

#### A4 超收软告警交互（Rule 1 + 4 位一体）

> ⚠️ **现状**: Phase A 后端 `YieldReportServiceImpl` 尚未实现 A4 超收检查（见 §1.2 / §3.1 ⚠️）。下面是**目标交互**，分两段落地：

**本期可做（不依赖后端 A4）— 客户端软提示**:
1. 投入量 > `plannedQuantity × 1.3` 时（用 task DTO 的 `plannedQuantity` 本地判断），输入框下方实时显黄色 inline 提示"超过计划上限约 X，确认实际投了这么多？"（不拦提交）。
2. 仍正常提交（forceSubmit 默认不传 / 传 true 均可，后端当前不校验）。

**后端补 A4 后（无缝升级）— 拒绝 + 强制**:
1. 投入量超容差 → 后端抛 `BusinessException`（message 由后端定，如"投入量 X 超过计划上限 Y..."）。
2. 前端 catch `error.response.data.message`，**原样**用持久 `Alert.alert('超收确认', <后端message>, [{取消}, {确认超收提交 → 重发 forceSubmit:true}])`。
3. Alert 是 RN 原生持久弹窗（等价 web sticky），含 next action 按钮（确认超收提交）。

> 前端**先实现** `forceSubmit` 透传 + 客户端软提示，后端补 A4 后自动生效，无需改前端。

> **toast sticky in RN**: RN 无 element-plus ElMessage。错误用 `Alert.alert`（模态持久，用户必须主动关），或屏内常驻 error banner（红色 inline，带关闭按钮）。**不要**用会自动消失的 toast。本屏 A7 软告警用 inline banner（常驻直到值变化），A4 超收/网络错用 `Alert.alert`（模态 + next action 按钮）。现有报工屏（`ScanReportScreen.tsx:71-78`）已是 `Alert.alert` + `handleError` pattern，复用。

---

### 3.7 组件复用 / 新建

> ⚠️ **核实纠正**: RN 端**没有** `QuantityInput` / `EmptyState` / `ProgressBar` / `Button` / `Card` 这些组件（初稿假设有，已纠正）。实际可用 + 需新建如下：

| 组件 | 现状 | 本期 |
|---|---|---|
| 大数字输入（+/− stepper + max 软上限 + 小数） | **无现成**。`components/formily/components/NumberInput.tsx` 是 formily 表单绑定用，不适合独立大字交互；`ScanReportScreen` 用裸 `TextInput` + `parseInt`（无 stepper/防呆 max） | **新建** `components/processing/YieldQuantityInput.tsx`：label + 大字 value(`TextInput keyboardType="decimal-pad"`) + `−`/`+` 步进按钮 + unit 后缀 + `max`(软上限不硬 clamp) + 预填标注 slot。防呆 Rule 1 落点 |
| 按钮 | `components/ui/NeoButton.tsx`（`title/onPress/variant/size('large')/disabled/loading/fullWidth/icon`）已有 | **复用** NeoButton size="large" fullWidth 作"提交·下一道"大按钮 |
| 卡片容器 | `components/ui/NeoCard.tsx`、`components/ui/ScreenWrapper.tsx` 已有 | **复用** NeoCard 作工序卡片外框、ScreenWrapper 作屏容器 |
| 空状态 | `components/common/EmptyStateCard.tsx`（`icon/title/message/actionLabel/onAction`，注意是 `EmptyStateCard` 不是 `EmptyState`，prop 名 `actionLabel` 不是 `actionText`） | **复用** EmptyStateCard 作选批次屏空态（防呆 Rule 5） |
| 进度条 `报工 3/7 ●●●○○○○` | **无现成 ProgressBar** | **自绘**（一行 `View` map 出 N 个圆点 + 文字"报工 i/N"），简单不值得新建组件 |
| 扫码（选批次可选扫码） | `components/processing/BarcodeScannerModal.tsx` 已有（`ScanReportScreen` 在用） | **复用** BarcodeScannerModal 作选批次屏扫码入口 |

> `YieldQuantityInput` 是本期唯一需新建的组件，约 80-120 行。其余全复用现有 ui/common/processing 组件。

---

### 3.4 operator 工作台入口（角色门控，加在哪）

#### 现状修正（核实结果，与 brief 假设不同）

> brief 第 5 条"operator 登录落通用 HomeScreen 看不到逐道报工" **不完全准确**。实测 `AppNavigator.tsx:113-116`：`userRole === "operator"` 走专属 `<OperatorNavigator />`（4 tab：考勤/报工/工作/我的），**不是** `MainNavigator` 的通用 `HomeScreen`。`getUserRole` 取 `user.factoryUser.role`（`authStore.ts:104-106`），operator 的 role 值 = `"operator"`（字符串，非 `factory_operator`）。

所以入口不是"在 HomeScreen 加卡片"，而是**在 `OperatorNavigator` 的报工 Tab 里加逐道报工入口**。

#### 默认方案: 报工 Tab 加落地页（二选一入口）

`OperatorNavigator.tsx:21-29` 的 `OperatorReportStackNavigator` 当前只有 `ScanReport / ScanReportSuccess / DraftReports` 3 屏，默认初始屏 = `ScanReport`（直接扫码）。

改为：报工 Tab 落地到一个**入口选择页**（或在现有 ScanReport 顶部加按钮），提供两个入口：
- **[个人扫码报工]**（老 `ScanReportScreen`，保留）
- **[逐道工序报工]**（新 `YieldBatchSelectScreen` → `YieldStepReportScreen`）★

stack 内新增：
```tsx
// OperatorReportStackNavigator 新增 (OperatorNavigator.tsx:23-27 内)
<ReportStack.Screen name="YieldBatchSelect" component={YieldBatchSelectScreen} />
<ReportStack.Screen name="YieldStepReport" component={YieldStepReportScreen} />
```

#### 角色 / 业态门控

- **角色**: 仅 `OperatorNavigator`（role=operator）出现该入口 → 角色门控天然由 navigator 隔离。**额外**: 主管/车间主任（`workshop_supervisor` 走 `WorkshopSupervisorNavigator`）若也要逐道报工，可后续在其 navigator 加同样 stack screen（Phase D 默认只给 operator）。
- **后端模块门控**: `YieldReportController` 有 `@RequireModule("production_report")`（注意：与 `WorkProcessTaskController` 的 `@RequireModule("production_plan")` 不同模块）。前端入口可选地用 `useFactoryFeatureStore` 检查 `production_report` 模块开启才显（默认：显示入口，后端 403 时 catch 显友好提示 → 防呆 Rule 5）。
- **业态门控**: 餐饮业态无生产工序，逐道报工只对有生产能力工厂有意义。参照 `MainNavigator.tsx:194` 的 `hasProductionCapability(user)`（`utils/factoryType.ts`）门控生产 Tab。默认：入口处加 `hasProductionCapability(user)` 判断，餐饮 operator 不显逐道报工入口（只显扫码）。

---

### 3.5 导航注册（新屏加哪）

| 新屏 | 注册位置 | 文件:行 |
|---|---|---|
| `YieldBatchSelectScreen` | `OperatorReportStackNavigator`（operator 报工 stack） | `OperatorNavigator.tsx:23-27` 内 add `<ReportStack.Screen name="YieldBatchSelect" .../>` |
| `YieldStepReportScreen` | 同上 | `OperatorNavigator.tsx:23-27` 内 add `<ReportStack.Screen name="YieldStepReport" .../>` |
| (可选) 完成态 `YieldDoneScreen` | 同上，或在 `YieldStepReportScreen` 内部状态切换 | 默认: 不新建独立屏，`YieldStepReportScreen` 内部 `phase: 'reporting' | 'done'` 状态切换 |

新屏文件放在 `frontend/CretasFoodTrace/src/screens/processing/`（与现有报工屏同目录）：
- `frontend/CretasFoodTrace/src/screens/processing/YieldBatchSelectScreen.tsx`
- `frontend/CretasFoodTrace/src/screens/processing/YieldStepReportScreen.tsx`

导航类型：`OperatorNavigator` 用 `createNativeStackNavigator<any>()`（`OperatorNavigator.tsx:15`，已是 `any`），新屏 route params 用局部 `RouteProp` 定义（避免 `useRoute<any>`，符合 `typescript-type-safety.md`）：
```ts
// YieldStepReportScreen route params
type YieldStepReportParams = { batchId: number; batchNumber?: string };
```

> 现有 `OperatorReportStackNavigator` 第一屏是 `ScanReport`（`OperatorNavigator.tsx:24`）。若改成入口选择页落地，调整首屏 = 新入口页；或保留 `ScanReport` 首屏，在其顶部加"逐道工序报工"按钮 `navigation.navigate('YieldBatchSelect')`。**默认**: 保留 `ScanReport` 首屏 + 顶部加入口按钮（改动最小，不破坏现有扫码默认行为）。

---

### 3.6 状态管理

| 状态 | 方案 |
|---|---|
| 选中的批次（batchId/batchNumber） | route params 传递（`YieldBatchSelect` → `YieldStepReport`），**不用全局 store** |
| 当前第几道（currentStepIndex） | `YieldStepReportScreen` 组件本地 `useState`（一屏内的临时 UI 状态） |
| 工序任务列表 + 出成率链（tasks / yield steps） | 组件本地 `useState` + `useEffect` 拉取（`listWorkProcessTasks` + `getYield`），提交后 re-fetch `getYield` 刷新预填值 |
| 当前道的投入/产出输入值 | 组件本地 `useState`（`inputQty` / `outputQty` 字符串态，照 `ScanReportScreen.tsx:49-51` 的 string form pattern），切道时 reset + 用 `steps[i-1].totalOutput` 预填投入 |
| factoryId | 不存本地，API service 内 `requireFactoryId()` 自动从 `authStore` 取（`factoryIdHelper.ts:7`） |

**默认: 全部用组件本地 `useState`，不新建 zustand store。** 理由：逐道报工是一次性会话流程（选批次→逐道→完成），数据生命周期 = 屏幕生命周期，无跨屏/跨会话持久需求。route params 传 batchId 已足够。

> 例外（YAGNI 边界）: 若未来要"中途退出后恢复到第 N 道"，再引入 `draftReportStore`（已存在 `store/draftReportStore.ts`）。Phase D 不做（见 §4）。

---

## 4. 不做什么（YAGNI）

1. **不改老审批屏**: `ProcessTaskReportScreen.tsx` / `ThreeStepReportScreen.tsx` / `TeamBatchReportScreen.tsx` / `DynamicReportScreen.tsx`（主管代报审批流，只采产出量）保留并存，零改动。
2. **离线报工**: 断网缓存 + 重连同步 → Phase E+。Phase D 假设在线（报工时有网）。
3. **领料双量屏（首道 `material-input`）**: 首道领料记"出库量 + 投料量"是班长/仓库职责（`PUT /material-input`）。Phase D **先做 operator 逐道报工主流程**（`POST /reports`），首道投入直接当普通投入量在逐道卡片填。`material-input` 独立屏可后续或简化（默认：Phase D 不建独立领料屏，`yieldReportApi.recordMaterialInput` 方法写好但不在本期 UI 调用，留给后续）。
4. **settle-day（每日结清）UI**: 末道结清回写批次完成（`triggerComplete=true`）更适合放管理端/班长。Phase D operator 端**简单做**：完成态显示"标记今日结清"按钮调 `settleDay`（不带 triggerComplete，仅汇总当日产出），`triggerComplete` 回写批次完成留管理端。默认：operator 端只做"标记今日结清"汇总，不做批次完成回写。
5. **跨批来源（A3 sourceBatchRefs）录入 UI**: 后端支持，但录入 UI 复杂（选多个来源批次）。Phase D 不做，`YieldReportRequest.sourceBatchRefs` 默认不传。
6. **代报工（targetWorkerId）**: operator 自报自，不传 `targetWorkerId`（后端 fallback 用 JWT workerId）。代报工是主管能力，Phase D operator 端不做。
7. **报工明细 append-only 流水屏**: 后端 `GET /reports` 当前复用 `getYield` 的 steps 聚合视图（`YieldReportController.java:79`）。Phase D 不建独立明细流水屏，完成态展示聚合即可。

---

## 5. 测试策略

### 5.1 Expo Web headed Playwright（per `.claude/rules/playwright-headed-mode.md`）

强制 `headless: false` + viewport 1920×1080（移动 case `page.setViewportSize({width:390,height:844})` 模拟手机）+ `--lang=zh-CN` + `--font-render-hinting=none`。多 chat 共存用 `PLAYWRIGHT_PORT` / `PLAYWRIGHT_CHAT_ID`（本任务建议 `PLAYWRIGHT_CHAT_ID=yield-rn`）。

截图验证点：
1. **入口可达**: operator 登录 → 报工 Tab → 看到"逐道工序报工"入口（中文字体真显示，无方块 □）。
2. **选批次屏**: 批次列表渲染（批次号 + 品名 + 进度 N/M），空态 EmptyState + actionText 按钮。
3. **一道一卡片渲染**: 全屏单工序，卡片头品名+批次+工序+计划数量齐全，进度条 `3/7 ●●●○○○○` 正确。
4. **预填**: 进入第 2 道，投入量输入框预填 = 上道产出值，标注"← 上道产出 X，请确认实际投了多少"可见。
5. **改预填值**: 改投入量（998 → 360），产出量填值，提交。
6. **A7 软告警**: 构造越界数据（工序配了 standardYieldMin/Max 且 yield 越界）→ 提交后 `submitReport.alert` 返回 BELOW_MIN/ABOVE_MAX → 软告警 banner 显示且**不拦**（上一步已提交成功）。
7. **A4 超收（现状有限）**: 投入 > 计划×1.3 → 客户端 inline 软提示显示（不拦）；后端 A4 未实现 → 提交不会被拒（待后端补 A4 后再验 Alert + forceSubmit 重发，见 §3.3 / §7 Q1）。
8. **提交下一道**: 提交后进度 +1，切到下一道，新道投入预填新的上道产出。
9. **完成态**: 末道提交后显示累计出成率 + 首道投入→末道产出。

### 5.2 真机 Maestro（可选）

`e2e-native` skill（Maestro）跑真机：扫码进批次 → 逐道填双量 → 提交链路（验证真键盘 decimal-pad、真 Alert 模态）。Phase D 默认只做 Expo Web headed Playwright，Maestro 真机可选/后续。

### 5.3 Audit doc 必含 Headed Mode Verification block

跑完 spec 后 audit doc 末尾 paste（per playwright-headed-mode.md）：headless:false / viewport / locale zh-CN / chromium 真弹 / 中文无方块 / screenshot fullPage / video webm / PLAYWRIGHT_PORT / PLAYWRIGHT_CHAT_ID。

### 5.4 单元/类型校验

- `yieldReportApi.ts` 类型对齐：`vue-tsc` 等价的 RN `tsc --noEmit` 跑通（无 `as any`，符合 `typescript-type-safety.md`）。
- TS 类型 1:1 mirror Phase A DTO（字段名 camelCase per `field-naming-convention.md`，BigDecimal → number per Jackson）。

---

## 6. 并行工作建议

### Subagent 并行（单 Chat 内）: ✅ 适合

实现阶段可拆 3 个独立 subagent（文件不重叠）：
- **Subagent A**: `yieldReportApi.ts`（纯 API service + TS 类型，无 UI 依赖，可独立写 + 单测）
- **Subagent B**: `YieldBatchSelectScreen.tsx`（选批次屏，依赖 A 的 `listWorkProcessTasks` 类型 signature，可先 mock）
- **Subagent C**: `YieldQuantityInput.tsx`（新数量输入组件）+ `YieldStepReportScreen.tsx`（逐道卡片屏，依赖 A 的类型 + 新建 `YieldQuantityInput` + 复用 `NeoButton`/`NeoCard`/`ScreenWrapper`，自绘进度条）

依赖：B/C 依赖 A 的类型定义 → 先定 A 的 `interface`（10 分钟），再 3 个并行。`YieldQuantityInput` 无外部依赖可与 A 并行先写。导航注册（`OperatorNavigator.tsx`）是共享文件，**由主 chat 单独串行改**（避免并发覆盖，per `concurrent-edit-safety.md` 规则 4）。

### 多 Chat 窗口并行: ⚠️ 谨慎

- 本 spec 全在 `frontend/CretasFoodTrace/` 内，与后端/web-admin 无冲突 → 可与其他 chat（后端/餐饮）并行。
- **冲突风险**: `OperatorNavigator.tsx` 是共享导航文件；若另一 chat 也在改 navigator → 冲突。建议本任务独占 navigator 改动。
- 用 worktree 隔离（已在 `feat/yield-rn-phase-d`），per `worktree-and-main-only-deploy.md`。

### 输出格式
```markdown
## 并行工作建议
### Subagent: ✅ A(api)/B(选批次屏)/C(YieldQuantityInput+逐道屏) 文件不重叠, 先定 A 类型再并行; navigator 注册主 chat 串行改
### 多Chat: ⚠️ 与后端/餐饮 chat 无冲突可并行; OperatorNavigator.tsx 本任务独占
```

---

## 7. 开放问题（需 Steve 定，spec 已给默认选择）

> 探查发现这些 Phase A 现状与 brief 假设有出入，spec 已给默认选择继续推进，列出供 Steve 拍板：

| # | 问题 | 默认选择（spec 已按此写） | 备选 |
|---|---|---|---|
| Q1 | **A4 超收后端未实现**：`YieldReportServiceImpl` 不校验超收/forceSubmit。brief 假设后端会拒绝超收并要 forceSubmit | 前端先做客户端软提示（基于 task `plannedQuantity`）+ forceSubmit 透传；后端补 A4 后无缝生效 | 等后端先补 A4 再做前端 A4；或本期完全不做 A4 提示 |
| Q2 | **A7 区间不可预估**：`standardYieldMin/Max` 在 WorkProcess 实体但未透出 task DTO → 客户端无法在提交前显"标准区间 85%–95%"+预警，只能提交后用后端 `alert` | 本期 A7 只在提交后显（文案"出成率偏高/偏低，请核对"，不带具体区间数字） | 后端在 `WorkProcessTaskDTO` 加 `standardYieldMin/Max`（小改），客户端即可预估 + 显区间 |
| Q3 | **品名规格不全**：`ProcessingBatch` 品名字段是 `productType`（非 productName），且无 `productSpec`（规格，如"200g"）。mockup 卡片头"卤猪蹄 200g"的 200g 无数据源 | 卡片头只显 `productType`（"卤猪蹄"），省略规格 | 后端 batch detail 加 productSpec 字段后再显 |
| Q4 | **operator 入口位置**：brief 说"operator 落通用 HomeScreen"，实测 operator 走专属 `OperatorNavigator`（4 tab）。入口应加在报工 Tab，不是 HomeScreen 卡片 | 报工 Tab 首屏 `ScanReport` 顶部加"逐道工序报工"按钮（改动最小，不破坏扫码默认） | 报工 Tab 改成入口选择页（扫码 vs 逐道二选一落地页） |
| Q5 | **首道领料双量（material-input）是否本期做**：首道"出库量+投料量"双量是班长/仓库职责 | Phase D 不建独立领料屏，`yieldReportApi.recordMaterialInput` 方法写好但本期 UI 不调用，首道投入直接当普通投入在逐道卡片填 | 本期就做首道领料双量子屏（给 operator 或班长） |
| Q6 | **settle-day（结清）operator 端做到什么程度** | operator 完成态只做"标记今日结清"汇总（不带 triggerComplete，不回写批次完成）；triggerComplete 回写留管理端 | operator 端完全不做结清（全放管理端）；或 operator 端也能 triggerComplete 回写批次 |
| Q7 | **业态门控**：餐饮 operator 是否显逐道报工入口 | 入口处 `hasProductionCapability(user)` 门控，餐饮 operator 不显 | 不门控（所有 operator 都显，餐饮无工序任务时空态提示） |

---

## 附录: 核实的 RN/后端事实（文件:行）

| # | 事实 | 来源 |
|---|---|---|
| Phase A base path | `/api/mobile/{factoryId}/production/batches/{batchId}`（batchId Long） | `YieldReportController.java:22` |
| 报工 5 端点 | POST `/reports`, PUT `/material-input`, GET `/yield`, POST `/settle-day`, GET `/reports` | `YieldReportController.java:31,42,53,62,74` |
| submitReport 返回 | `Map<String,Object>` 只 put: `reportId`(always) + `yieldRate`(always, null if 量纲不可比) + `alert`(仅越界时 put, BELOW_MIN/ABOVE_MAX) | `YieldReportServiceImpl.java:95-106` |
| recordMaterialInput 返回 | `Map` 只 put `reportId` | `YieldReportServiceImpl.java:160-162` |
| settleDay 返回 | `Map`: `settledCount` / `batchYield`(BatchYieldDTO) / `completed` | `YieldReportServiceImpl.java:182-196` |
| **A4 超收 (现状)** | ⚠️ Phase A `submitReport` **未实现** A4 超收检查 — 不读 forceSubmit、不抛超收错（只校验 workProcessTaskId/outputQuantity 必填）。A4 是设计意图待后端补 | `YieldReportServiceImpl.java:45-107`（无超收逻辑）；设计 §3.5 |
| forceSubmit 字段 | `YieldReportRequest.forceSubmit: Boolean`（请求 DTO 有，service 当前不读） | `YieldReportRequest.java:18` |
| workMinutes 类型 | `Integer`（后端落 `totalWorkMinutes`） | `YieldReportRequest.java:16`, `YieldReportServiceImpl.java:71` |
| 工序任务列表端点 | GET `/api/mobile/{factoryId}/production/batches/{batchId}/work-process-tasks` → `List<WorkProcessTaskDTO>` 按 processOrder 升序 | `WorkProcessTaskController.java:93-99` |
| **WorkProcessTaskDTO 真实字段** | id(=workProcessTaskId)/factoryId/productionBatchId/productWorkProcessId/workProcessId(String)/productTypeId/processOrder/status/plannedQuantity/plannedUnit/actualQuantity/assignedTo/processName/processCategory/notes/时间字段。**无** batchNumber/productName/productSpec/inputQuantity/standardYieldMin/Max | `WorkProcessTaskDTO.java:25-71`; 前端 `types/workProcess.ts:19-45` |
| **批次品名/批次号 来源** | `processingApiClient.getBatchById(batchId:string, factoryId?)` → `ApiResponse<ProcessingBatch>`，`ProcessingBatch{ batchNumber, productType(=品名), targetQuantity, status, actualQuantity }`（无独立 BatchDetailResponse 文件; 品名字段是 productType 非 productName）。**无 productSpec(规格)** | `processingApiClient.ts:62-90,174` |
| **A7 标准出成率区间** | `WorkProcess.standardYieldMin/Max/needsInput/outputUnit` 在实体上, **未透出 task DTO** → 客户端不能预估 A7, 只能用 submitReport 返回 alert | `WorkProcess.java:138-145` |
| apiClient 模式 | 拦截器统一 `return response.data`（解包一层）, JWT 从 SecureStore 注入, 401 自动刷新 | `apiClient.ts:55,38-40,60-86,135-158` |
| requireFactoryId | 从 authStore 取 factoryId（REQUIRED 策略） | `factoryIdHelper.ts:312-318` |
| 老报工 API client 模式 | `getPath()`+`requireFactoryId` + `apiClient.post(...); return ...` | `workReportingApiClient.ts:27-41`, `processTaskApiClient.ts:100-104` |
| 导航注册 | operator 走 `OperatorNavigator`（AppNavigator role 分发） | `AppNavigator.tsx:113-116`, `OperatorNavigator.tsx:21-29` |
| operator 角色判断 | `getUserRole(user)` = `user.factoryUser.role` = `"operator"` | `authStore.ts:94-109`, `AppNavigator.tsx:114` |
| **数量输入组件 (无现成)** | 无 QuantityInput；formily `NumberInput.tsx` 表单绑定用不合适；`ScanReportScreen` 用裸 `TextInput`+parseInt → **需新建 `YieldQuantityInput`** | `formily/components/NumberInput.tsx`; `ScanReportScreen.tsx:49-51` |
| 按钮/卡片/容器 | `NeoButton`(title/onPress/variant/size/disabled/loading/fullWidth/icon) / `NeoCard` / `ScreenWrapper` | `components/ui/NeoButton.tsx:14-25`, `NeoCard.tsx`, `ScreenWrapper.tsx` |
| 空状态组件 | `EmptyStateCard`（icon/title/message/**actionLabel**/onAction, 注意非 EmptyState、非 actionText） | `components/common/EmptyStateCard.tsx:6-13` |
| 业态门控 helper | `hasProductionCapability(user)` | `utils/factoryType.ts`（`MainNavigator.tsx:16,194` 引用） |
| 扫码 modal | `BarcodeScannerModal`（`ScanReportScreen` 在用, 选批次屏可复用） | `components/processing/BarcodeScannerModal.tsx`; `ScanReportScreen.tsx:12,53-80` |
