import { apiClient } from './apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';

// ============ 后端统一响应信封 (拦截器已解包 axios 一层, 见 apiClient.ts:51-55) ============
export interface ApiResponse<T> {
  success: boolean;
  code: number;
  message: string;
  data: T;
}

export interface PageResponse<T> {
  content: T[];
  totalElements?: number;
  totalPages?: number;
  page?: number;
  size?: number;
}

// ============ 工序任务状态 (mirror WorkProcessTask.Status) ============
export type WorkProcessTaskStatus =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'SKIPPED'
  | 'CANCELLED';

// ============ WorkProcessTaskDTO (mirror backend dto/WorkProcessTaskDTO.java:23-71 + Task 0 两字段) ============
// 注: 后端 types/workProcess.ts 无此 interface (只有 WorkProcess/ProductWorkProcess), 故在此定义.
export interface WorkProcessTask {
  id: number;                       // = workProcessTaskId (报工 req 用这个)
  factoryId: string;
  productionBatchId: number;        // 注意: 不是 batchId
  productWorkProcessId: number;
  workProcessId: string;            // String (非 number)
  productTypeId: string;
  processOrder: number;             // 第几道 (升序)
  status: WorkProcessTaskStatus;
  plannedQuantity: number | null;   // BigDecimal → number; 计划数量 (Rule 1 max + Rule 2)
  plannedUnit: string | null;       // 计划单位 (非 unit)
  estimatedMinutes?: number | null;
  actualQuantity?: number | null;   // 累计产出 (Phase A 双写)
  assignedTo?: number | null;
  assignedToName?: string | null;   // T142: 责任人姓名 (join 透出; null=未分配/已删除)
  completedBy?: number | null;
  notes?: string | null;
  processName?: string | null;      // 工序名 (焯水/卤制..., join 提供, 选填) — 卡片头用
  processCategory?: string | null;
  productTypeName?: string | null;  // T157: 产品名 (list 路径 join 透出; null=批次/产品已删除) — 跨批次选择屏分组用
  batchNumber?: string | null;      // T157: 批次号 (list 路径 join 透出; null=批次已删除)
  standardYieldMin?: number | null; // Task 0 透出: A7 标准出成率下限 (null=未配)
  standardYieldMax?: number | null; // Task 0 透出: A7 标准出成率上限 (null=未配)
  outputUnit?: string | null;       // P0-2: 工序产出单位 (kg→份/盒, join 透出; null=沿用投入单位)
  plannedStartAt?: string | null;
  plannedEndAt?: string | null;
  actualStartAt?: string | null;
  actualEndAt?: string | null;
  actualMinutes?: number | null;
  completedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

// ============ BatchYieldDTO (mirror backend dto/yield/BatchYieldDTO.java:16-28) ============
export interface StepYieldDTO {
  workProcessTaskId: number;
  processOrder: number;
  processName: string | null;
  totalInput: number | null;        // Σ input (BigDecimal → number)
  totalOutput: number | null;       // Σ output — 下道预填用这个
  inputUnit: string | null;
  outputUnit: string | null;
  yieldRate: number | null;         // Σoutput/Σinput; 量纲不可比时 null
  unitComparable: boolean | null;   // false → 只展示量
  carryover: number | null;         // 上道产出 − 本道投入 (>0 结转)
  yieldAlert: 'BELOW_MIN' | 'ABOVE_MAX' | null;  // A7 越界软告警
  totalWorkMinutes: number | null;  // P1-3 (G4): Σ 本道工时(分钟); 全 null → null
  totalWorkers: number | null;      // P1-3 (G4): Σ 本道人数(人次); 全 null → null
  // ── A.6 逐道成本 (BigDecimal → number); null = 无法计算 (未配工价 / 无原料单价), 展示 "—" 非 ¥0 ──
  laborCost: number | null;         // 本道人工成本
  materialCost: number | null;      // 本道材料成本
  stepCost: number | null;          // 本道小计 = laborCost + materialCost (两者全 null → null)
  // ── 适配单元3: 传统报工证据/工时段/副产物/损耗/留样 (本道各次报工合并; 无则 null) ──
  photos?: string[] | null;         // 证据图片 URL (本道各次报工 photos 合并去重)
  laborSegments?: Array<Record<string, unknown>> | null;  // 多时段×人数工时明细 (startTime/endTime/headcount/note)
  processedQuantity?: number | null;
  processedUnit?: string | null;
  stageOutputQuantity?: number | null;
  stageOutputUnit?: string | null;
  segmentWasteQuantity?: number | null;
  segmentWasteUnit?: string | null;
  byproducts?: Array<Record<string, unknown>> | null;     // 副产物明细 (name/quantity/unit)
  wasteQuantity?: number | null;    // Σ 本道损耗量
  sampleRetainQuantity?: number | null;  // Σ 本道留样数 (盒/份)
  // ── 三阶段报工 (单元1): 阶段推断 + 照片按 reportKind 分组 ──
  /** 本道阶段: AWAITING_INPUT (无投入) / IN_PRODUCTION (有投入无产出) / COMPLETED (有产出)。
   *  旧式报工 (reportKind null) 按 input/output 有无推断, 与三阶段语义一致。 */
  phase?: 'AWAITING_INPUT' | 'IN_PRODUCTION' | 'COMPLETED' | null;
  /** 投入阶段照片 (reportKind ∈ {INPUT, SEGMENT}; 旧式 null reportKind 也归此组); 无则 null */
  inputPhotos?: string[] | null;
  /** 产出阶段照片 (reportKind == OUTPUT); 无则 null */
  outputPhotos?: string[] | null;
  /** T161 per-photo annotation parallel to inputPhotos; null = no annotations */
  inputPhotoAnnotations?: Array<{ url: string; label?: string | null; note?: string | null }> | null;
  /** T161 per-photo annotation parallel to outputPhotos; null = no annotations */
  outputPhotoAnnotations?: Array<{ url: string; label?: string | null; note?: string | null }> | null;
}

export interface BatchYieldDTO {
  batchId: number;
  batchNumber: string;
  firstStepInput: number | null;    // 998
  lastStepOutput: number | null;    // 382.08 或 3184 盒
  firstStepInputUnit: string | null;
  lastStepOutputUnit: string | null;
  cumulativeYieldRate: number | null;  // 0.3828
  steps: StepYieldDTO[];
  complete: boolean | null;         // 每道都有 input+output 才 true
  totalWorkMinutes: number | null;  // P1-3 (G4): Σ 所有道工时(分钟); 全 null → null
  totalWorkers: number | null;      // P1-3 (G4): Σ 所有道人数(总人次); 全 null → null
  // ── A.6 整批逐道成本汇总 (BigDecimal → number); null = 无法计算, 展示 "—" 非 ¥0 ──
  totalLaborCost: number | null;    // Σ 所有道人工成本
  totalMaterialCost: number | null; // Σ 所有道材料成本
  totalCost: number | null;         // 整批总成本 = totalLaborCost + totalMaterialCost (两者全 null → null)
  // ── G8 Wave 4 (C): 进行中标注 (展示层防呆; cumulativeYieldRate 始终是 A 完工口径) ──
  inProgress?: boolean | null;             // true → 旁标"进行中, 含 X 在制半成品未计入成品"
  wipInProgressQuantity?: number | null;   // Σ AVAILABLE WIP 余额 (在制中间品总量); inProgress=false 时 0
  wipInProgressUnit?: string | null;       // 在制半成品单位 (无在制则 null)
  asOfYieldRate?: number | null;           // 进行中参考数 (候选 B); 当前与 A 口径同源
}

// ============ SP1 OutputOptionsResponse (mirror backend dto/yield/OutputOptionsResponse.java) ============
// GET /processing/batches/{batchId}/output-options — 本工序是否允许产出半成品
export interface OutputOptionItem {
  taskId: number;
  processName: string;
  semiCode: string;       // 半成品编码; 非空时前端显示"剩余转半成品"栏
  processOrder: number;
}
export interface OutputOptionsResponse {
  items: OutputOptionItem[];
}

// ============ SP2 SemiFinishedInventory (mirror backend entity/SemiFinishedInventory.java) ============
// GET /wip/available — 可用于二次加工领料的半成品库存列表
export interface SemiFinishedInventoryItem {
  id: number;
  factoryId: string;
  intermediateBatchNo: string;
  productTypeId: string | null;
  productTypeName: string | null;   // join; 用于显示品名
  availableQuantity: number;
  unit: string | null;
  status: 'AVAILABLE' | 'DEPLETED' | 'RETURNED' | string;
}

// ============ WipRowDTO (mirror backend dto/yield/WipRowDTO.java) ============
// GET /wip — 该批次每道工序半成品库存 (产出/已领/余额/状态)
export interface WipRowDTO {
  intermediateBatchNo: string;             // 工序批次号 (报工领用 sourceWipNo 传回)
  sourceWorkProcessTaskId: number | null;  // 哪道任务产出
  processOrder: number | null;             // 第几道
  processName: string | null;              // 工序名 (join; null fallback)
  productTypeId: string | null;
  producedQuantity: number | null;         // 该道总产出
  consumedQuantity: number | null;         // 已被下道领走
  availableQuantity: number | null;        // 余额 = produced − consumed (下道 :max)
  unit: string | null;
  status: 'AVAILABLE' | 'DEPLETED' | 'RETURNED' | string;
}

// ============ 请求 DTO ============
// mirror backend dto/yield/YieldReportRequest.java:11-26
export interface YieldReportRequest {
  workProcessTaskId: number;
  inputQuantity: number;            // 本道投入 (前端预填上道产出, 可改)
  inputUnit?: string;
  outputQuantity: number;           // 本道产出 (必填)
  outputUnit?: string;
  /**
   * 三阶段报工标记 (单元1): INPUT (投入) / SEGMENT (生产中时段) / OUTPUT (完工出成)。
   * 后端按 reportKind 隔离字段 (INPUT 忽略 output, OUTPUT 忽略 input, SEGMENT 只取 laborSegments)。
   * null/缺省 = 旧式整合报工 (向后兼容, 全字段生效)。
   */
  reportKind?: 'INPUT' | 'SEGMENT' | 'OUTPUT';
  /**
   * 同单未完结续报 (部分产出/继续产出): 控制 OUTPUT 报工后是否把工序任务置完工。
   * 客户场景 (六扇门): "先出 300 盒成品单子留着, 明天再出 200 盒"。
   * - undefined/不传 = 向后兼容: OUTPUT/legacy 报工后立即完工 (历史行为, 零回归)。
   * - false = 部分产出, 留单继续: 累加产出但任务保持进行中, 可后续再报。
   * - true = 显式标记完工: 累加产出后完工 (出成率锁定)。
   */
  markComplete?: boolean;
  workMinutes?: number;             // 本道工时(分钟), 选填 (后端 Integer)
  workerCount?: number;             // P1-3 (G4): 本道人数, 选填 (后端 Integer)
  forceSubmit?: boolean;            // A4 超收软告警后强制提交
  sourceBatchRefs?: Array<Record<string, unknown>>;  // A3 跨批来源 (Phase D 默认不传)
  reporterName?: string;
  targetWorkerId?: number;          // 代报工 (Phase D operator 自报, 默认不传)
  /** A2b: 首道领料批次引用 (随报工单一起提交, 不再单独调用 recordMaterialInput) */
  materialBatchRefs?: { materialBatchId: string; quantity: number; unit?: string }[];
  /**
   * G7 Wave 4 部分领用: 本道领用哪个上道 WIP (semi_finished_inventory.intermediate_batch_no)。
   * 非空 → 后端扣减该 WIP available_quantity (防呆 inputQuantity ≤ available);
   * null 走旧路径 (首道领原料 / 老批次, 向后兼容)。
   */
  sourceWipNo?: string;
  sourceWipQuantity?: number;

  // ==================== 传统报工适配 (适配单元4; mirror backend YieldReportRequest.java:34-49) ====================
  /** 图片证据 URL 列表 (先传 OSS 拿 URL, 存入 ProductionReport.photos). 六扇门: 产品+电子秤+盒数照. */
  evidenceImages?: string[];
  /** T161 per-photo annotation; parallel to evidenceImages, same order.
   *  null or absent = no annotations (backward-compat). */
  photoAnnotations?: Array<{ url: string; label?: string | null; note?: string | null }>;
  /** 多时段×人数工时 (张权 多段开工/收工). startTime/endTime = "HH:mm". */
  laborSegments?: Array<{
    startTime: string;
    endTime: string;
    headcount: number;
    note?: string;
    processedQuantity?: number;
    processedUnit?: string;
    stageOutputQuantity?: number;
    stageOutputUnit?: string;
    segmentWasteQuantity?: number;
    segmentWasteUnit?: string;
    byproducts?: { name: string; quantity: number; unit?: string }[];
  }>;
  /** 副产物明细 (料头/肥油/骨头). */
  byproducts?: { name: string; quantity: number; unit?: string }[];
  /** 损耗量; 选填. */
  wasteQuantity?: number;
  /** 留样数量 (盒/份, 末道装盒); 选填. 后端字段名 sampleRetainQuantity (Integer). */
  sampleRetainQuantity?: number;

  // ==================== SP1 双产出 (mirror backend YieldReportRequest.java:42-59) ====================
  /**
   * SP1 产出种类: FINISHED / SEMI / BOTH / null(旧式兼容).
   * null → 旧路径 (普通 WIP 产出); SEMI/BOTH → 同时写 SemiFinishedInventory 台账。
   */
  outputKind?: 'FINISHED' | 'SEMI' | 'BOTH';
  /** SP1 半成品产出量 (outputKind=SEMI/BOTH 时填写) */
  semiOutputQuantity?: number;
  /** SP1 半成品编码 (来自 output-options 接口返回; 不许前端自由输入, F7 防呆) */
  semiCode?: string;

  /** CALC-003 成本类别 RAW_MATERIAL/SEASONING/AUXILIARY/PACKAGING/OTHER; null=按工序顺序启发式分类 (通常由工序配置派生, 不由操作员手填) */
  costCategory?: 'RAW_MATERIAL' | 'SEASONING' | 'AUXILIARY' | 'PACKAGING' | 'OTHER';

  /** AUDIT-002 包装明细 [{name,cost}] (膜/气体/标签/其他); 通常仅包装道; null=未拆 */
  packagingDetail?: Array<{ name: string; cost: number }>;
}

// mirror backend dto/yield/MaterialInputRequest.java:9-14
export interface MaterialInputRequest {
  workProcessTaskId: number;
  warehouseOutQuantity: number;     // 出库量 998
  feedInQuantity: number;           // 投料量 935.5
  inputUnit?: string;
  /** A2b: 本批次领料批次引用 (1 or N). materialBatchId is String (UUID/varchar). */
  materialBatchRefs?: { materialBatchId: string; quantity: number; unit?: string }[];
}

// ============ YieldLimitsDTO (mirror backend dto/yield/YieldLimitsDTO.java) ============
// GET /yield/limits?workProcessTaskId=&inputQuantity=
export interface YieldLimitsDTO {
  targetQuantity: number | null;      // inputQuantity × standardYieldMax; null when no base
  standardYieldMax: number | null;    // WorkProcess.standardYieldMax (null if unconfigured)
  unit: string | null;
  alreadyReported: number | null;     // Σ outputQuantity already reported for this task
  toleranceRate: number | null;       // e.g. 0.30 (30%)
  maxAllowed: number | null;          // targetQuantity × (1 + toleranceRate); null when no base
  remaining: number | null;           // maxAllowed − alreadyReported; null when no base
  // ── G7 Wave 4 部分领用防呆 ──
  wipAvailable?: number | null;       // 本道可领的上道 WIP 余额 (RN 投入 :max); null=首道; 0=领空
  wipAvailableUnit?: string | null;   // 源 WIP 余额单位 (=上道 outputUnit); banner/:max 用它而非本道 unit; null=无源 WIP
  sourceWipNo?: string | null;        // 上道唯一可领 WIP 工序批次号 (报工 sourceWipNo 回传); null=首道/歧义
  message: string;                    // human-readable summary
}

// ============ Map 响应结果类型 (后端返 Map<String,Object>, 见 YieldReportServiceImpl.java) ============
// submitReport: 只 put reportId(always) + yieldRate(always, null if 量纲不可比) + alert(仅越界 put) — :95-106
export interface YieldReportResult {
  reportId: number;
  yieldRate: number | null;
  alert?: 'BELOW_MIN' | 'ABOVE_MAX';   // null 时 key 不出现
  /** 同单未完结续报: 任务是否已完工 (仅 OUTPUT/legacy 报工返回); markComplete=false 时为 false。 */
  taskCompleted?: boolean;
  /** 同单未完结续报: Σ该任务全部 OUTPUT 累计产出 (含本次, 仅 OUTPUT/legacy 报工返回)。 */
  cumulativeOutput?: number | null;
  /**
   * 守恒软校验告警 (适配单元3 Part C): 仅 legacy 整合报工偏差 >15% 且单位可比时出现。
   * 示例: "物料平衡偏差 30% (投入 100, 产出 70, ...) — 请核对, 系统不阻塞"
   * 三阶段 INPUT/SEGMENT/OUTPUT 各只带单边量, 此字段不会出现 (由后端注释明确)。
   */
  balanceWarning?: string;
}

// recordMaterialInput: 只 put reportId — :160-162
export interface MaterialInputResult {
  reportId: number;
}

// settleDay: settledCount + batchYield(BatchYieldDTO) + completed — :182-196
export interface SettleDayResult {
  settledCount: number;
  batchYield: BatchYieldDTO;
  completed: boolean;
  completeError?: string;   // P1-1: 完工失败原因 (结清成功但批次未完工时, 如"批次未开始生产")
  // ── D3 Wave 4: 完工后若仍有在制 WIP 结余 → 诚实提示退回总仓 (不阻塞完工) ──
  wipRemaining?: number;    // Σ AVAILABLE WIP 结余总量
  wipRemainingUnit?: string | null;
  wipRemainingHint?: string;  // "结余 X kg 半成品待退回总仓 (建调拨单退库), 完工不受影响"
}

// ============ API Client ============
class YieldReportApi {
  private getBase(batchId: number, factoryId?: string): string {
    const fid = requireFactoryId(factoryId);
    return `/api/mobile/${fid}/production/batches/${batchId}`;
  }

  /** 1. POST /reports — 逐道报工 (投入+产出双量). workerId 后端从 JWT @RequestAttribute("userId") 取, body 不传. */
  async submitReport(
    batchId: number,
    req: YieldReportRequest,
    factoryId?: string,
  ): Promise<ApiResponse<YieldReportResult>> {
    return apiClient.post<ApiResponse<YieldReportResult>>(
      `${this.getBase(batchId, factoryId)}/reports`,
      req,
    );
  }

  /** 2. PUT /material-input — 首道领料双量 (出库量+投料量). Phase D 暂不在 UI 调用 (spec §4). */
  async recordMaterialInput(
    batchId: number,
    req: MaterialInputRequest,
    factoryId?: string,
  ): Promise<ApiResponse<MaterialInputResult>> {
    return apiClient.put<ApiResponse<MaterialInputResult>>(
      `${this.getBase(batchId, factoryId)}/material-input`,
      req,
    );
  }

  /** 3. GET /yield — 整批+单工序出成率 (派生, 下道预填用 steps[i-1].totalOutput). */
  async getYield(batchId: number, factoryId?: string): Promise<ApiResponse<BatchYieldDTO>> {
    return apiClient.get<ApiResponse<BatchYieldDTO>>(
      `${this.getBase(batchId, factoryId)}/yield`,
    );
  }

  /** 4. POST /settle-day?date=&triggerComplete= — 每日结清. */
  async settleDay(
    batchId: number,
    opts: { date?: string; triggerComplete?: boolean } = {},
    factoryId?: string,
  ): Promise<ApiResponse<SettleDayResult>> {
    const params = new URLSearchParams();
    if (opts.date) params.append('date', opts.date);
    if (opts.triggerComplete != null) {
      params.append('triggerComplete', String(opts.triggerComplete));
    }
    const qs = params.toString();
    return apiClient.post<ApiResponse<SettleDayResult>>(
      `${this.getBase(batchId, factoryId)}/settle-day${qs ? `?${qs}` : ''}`,
    );
  }

  /** 5. GET /reports — 报工流水汇总 (当前后端复用 getYield 的 steps 聚合视图). */
  async listReports(batchId: number, factoryId?: string): Promise<ApiResponse<BatchYieldDTO>> {
    return apiClient.get<ApiResponse<BatchYieldDTO>>(
      `${this.getBase(batchId, factoryId)}/reports`,
    );
  }

  /** 工序任务列表 (该批次, 按 processOrder 升序). 端点见 WorkProcessTaskController.java:93-99.
   *  assignedTo: 若提供, 后端仅返回该用户的任务 + 未分配 (null) 任务; 省略 → 返回全部 (主管视角). */
  async listWorkProcessTasks(
    batchId: number,
    factoryId?: string,
    assignedTo?: number,
  ): Promise<ApiResponse<WorkProcessTask[]>> {
    const fid = requireFactoryId(factoryId);
    const params = assignedTo != null ? { assignedTo } : undefined;
    return apiClient.get<ApiResponse<WorkProcessTask[]>>(
      `/api/mobile/${fid}/production/batches/${batchId}/work-process-tasks`,
      { params },
    );
  }

  /** Operator 当前工序入口: 按当前账号精确查询已分配 WorkProcessTask, 不包含未分配任务. */
  async listAssignedWorkProcessTasks(
    params: {
      assignedTo: number;
      status?: WorkProcessTaskStatus;
      page?: number;
      size?: number;
    },
    factoryId?: string,
  ): Promise<ApiResponse<PageResponse<WorkProcessTask>>> {
    const fid = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<PageResponse<WorkProcessTask>>>(
      `/api/mobile/${fid}/work-process-tasks`,
      { params },
    );
  }

  /** A4: GET /yield/limits — 投入量预检, 返回超收上限信息.
   *  若 standardYieldMax 未配置, 返回 maxAllowed=null (不触发超收检查). */
  async getYieldLimits(
    batchId: number,
    workProcessTaskId: number,
    inputQuantity: number,
    factoryId?: string,
  ): Promise<ApiResponse<YieldLimitsDTO>> {
    return apiClient.get<ApiResponse<YieldLimitsDTO>>(
      `${this.getBase(batchId, factoryId)}/yield/limits`,
      { params: { workProcessTaskId, inputQuantity } },
    );
  }

  /** G6/G7 Wave 4: GET /wip — 该批次半成品库存 (每道工序产出/已领/余额/状态). 无 WIP → 空数组. */
  async listWip(batchId: number, factoryId?: string): Promise<ApiResponse<WipRowDTO[]>> {
    return apiClient.get<ApiResponse<WipRowDTO[]>>(
      `${this.getBase(batchId, factoryId)}/wip`,
    );
  }

  // ==================== SP1 双产出 ====================

  /**
   * SP1: GET /processing/batches/{batchId}/output-options
   * 查询本工序允许的产出选项 (是否有半成品 semiCode)。
   * 切换到 OUTPUT 阶段时调用; items 为空或无 semiCode 则只显示成品栏。
   */
  async getOutputOptions(
    batchId: number,
    factoryId?: string,
  ): Promise<ApiResponse<OutputOptionsResponse>> {
    const fid = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<OutputOptionsResponse>>(
      `/api/mobile/${fid}/processing/batches/${batchId}/output-options`,
    );
  }

  // ==================== SP2 撤回 + 二次加工 ====================

  /**
   * SP2: POST /processing/batches/{batchId}/reversal
   * 提交整批次报工撤回申请。body = { reason: string }。
   * 409 DOWNSTREAM_CONSUMED → 下游已领用, 不可撤回; 前端显示 sticky 双句错误。
   */
  async postBatchReversal(
    batchId: number,
    reason: string,
    factoryId?: string,
  ): Promise<ApiResponse<unknown>> {
    const fid = requireFactoryId(factoryId);
    return apiClient.post<ApiResponse<unknown>>(
      `/api/mobile/${fid}/processing/batches/${batchId}/reversal`,
      { reason },
    );
  }

  /**
   * SP2: GET /wip/available
   * 查询工厂可用于二次加工领料的半成品库存列表 (status=AVAILABLE, availableQuantity > 0)。
   */
  async listAvailableWip(factoryId?: string): Promise<ApiResponse<SemiFinishedInventoryItem[]>> {
    const fid = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<SemiFinishedInventoryItem[]>>(
      `/api/mobile/${fid}/wip/available`,
    );
  }

  /**
   * 适配单元4: 上传逐道报工图片证据 (产品+电子秤+盒数照) → OSS, 返回公网 URL.
   * 端点 POST /api/mobile/{factoryId}/upload/yield-evidence (consumes multipart/form-data,
   * 见 FileUploadController.java:100). 返回 ApiResponse<{url}>; 拿 url 写入 submitReport 的 evidenceImages.
   * 注: 不在 batch base 路径下 (是工厂级通用上传入口).
   */
  async uploadYieldEvidence(
    fileUri: string,
    options: { fileName?: string; mimeType?: string; factoryId?: string } = {},
  ): Promise<string> {
    const mimeType = options.mimeType || 'image/jpeg';
    const fileName = options.fileName || `yield_evidence_${Date.now()}${mimeType.startsWith('video/') ? '.mp4' : '.jpg'}`;
    const fid = requireFactoryId(options.factoryId);
    const formData = new FormData();
    // React Native FormData 接受 {uri, name, type} 形状 (非 Blob), 见 PhotoEvidenceCapture.tsx:71
    formData.append('file', {
      uri: fileUri,
      name: fileName,
      type: mimeType,
    } as unknown as Blob);
    const res = await apiClient.post<ApiResponse<{ url: string }>>(
      `/api/mobile/${fid}/upload/yield-evidence`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    );
    if (!res.success || !res.data?.url) {
      throw new Error(res.message || '图片上传失败');
    }
    return res.data.url;
  }
}

export const yieldReportApi = new YieldReportApi();
