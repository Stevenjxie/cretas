/**
 * BOM API — includes:
 *   - BOM Recipe lifecycle (BomRecipeController): activate DRAFT → ACTIVE
 *   - BOM Yield Estimate (BomYieldEstimateController): Phase B/C endpoints
 *
 * Base URL is /api/mobile (set in request.ts baseURL).
 * Endpoint prefix: /{factoryId}/bom/recipes/{recipeId}/...
 */
import { get, post, put } from './request';

// =========================================================================
// BOM Recipe — status types + activate API
// =========================================================================

/** BomRecipe.Status enum values (mirrors backend BomRecipe.Status) */
export type BomRecipeStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';

/**
 * Minimal BomRecipe shape returned by the list/activate endpoints.
 * Fields match BomRecipe entity JSON serialization (camelCase via Jackson).
 */
export interface BomRecipeSummary {
  id: string;
  factoryId: string;
  recipeCode: string;
  productTypeId: string;
  productName: string;
  version: number;
  isCurrent: boolean;
  status: BomRecipeStatus;
  overallYieldRate?: number | null;
  outputQuantityPerUnit?: number | null;
  outputUnit?: string | null;
  notes?: string | null;
  activatedAt?: string | null;
  activatedBy?: number | null;
  totalCost?: number | null;
  totalMaterialCost?: number | null;
  totalLaborCost?: number | null;
  totalOverheadCost?: number | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface BomRecipeItemPayload {
  materialTypeId: string;
  standardQuantity: number;
  yieldRate?: number | null;
  unit: string;
  unitPrice?: number | null;
  taxRate?: number | null;
  materialCategory?: string | null;
  sortOrder?: number | null;
  isOptional?: boolean | null;
  substituteGroup?: string | null;
  remark?: string | null;
  perPortion?: boolean | null;
  semiFinishedRefCode?: string | null;
  subProductTypeId?: string | null;
}

export interface CreateBomRecipeRequest {
  productTypeId: string;
  productName?: string | null;
  overallYieldRate?: number | null;
  outputQuantityPerUnit: number;
  outputUnit: string;
  sourceType?: 'MANUAL' | 'SAMPLE_AUTOGEN' | 'AI_GENERATED' | 'IMPORTED';
  sourceSampleId?: string | null;
  items: BomRecipeItemPayload[];
  notes?: string | null;
}

export interface UpdateBomRecipeRequest {
  productName?: string | null;
  overallYieldRate?: number | null;
  outputQuantityPerUnit?: number | null;
  outputUnit?: string | null;
  items?: BomRecipeItemPayload[];
  notes?: string | null;
}

const recipeBase = (factoryId: string) => `/${factoryId}/bom/recipes`;

export const bomRecipeApi = {
  /**
   * 分页查询 BOM 配方列表, 可按 status 过滤.
   * 对应: GET /api/mobile/{factoryId}/bom/recipes
   */
  listRecipes: (
    factoryId: string,
    options?: { status?: BomRecipeStatus; page?: number; size?: number },
  ) =>
    get<{ content: BomRecipeSummary[]; totalElements: number; totalPages: number }>(
      recipeBase(factoryId),
      { params: { ...options } },
    ),

  /**
   * 创建 BOM 配方草稿.
   * 对应: POST /api/mobile/{factoryId}/bom/recipes
   */
  create: (factoryId: string, req: CreateBomRecipeRequest) =>
    post<BomRecipeSummary>(recipeBase(factoryId), req),

  /**
   * 更新 BOM 配方草稿.
   * 对应: PUT /api/mobile/{factoryId}/bom/recipes/{recipeId}
   */
  update: (factoryId: string, recipeId: string, req: UpdateBomRecipeRequest) =>
    put<BomRecipeSummary>(`${recipeBase(factoryId)}/${recipeId}`, req),

  /**
   * 激活 BOM 配方 (DRAFT → ACTIVE).
   * 同产品其他 is_current 配方自动降级。
   * 对应: POST /api/mobile/{factoryId}/bom/recipes/{recipeId}/activate
   */
  activate: (factoryId: string, recipeId: string, operatorId?: number | null) =>
    post<BomRecipeSummary>(
      `${recipeBase(factoryId)}/${recipeId}/activate`,
      null,
      { params: operatorId != null ? { operatorId } : {} },
    ),
};

/** 单个 BOM Item 出成率评估响应 */
export interface YieldEstimateResponse {
  productTypeId: string;
  materialCategory: string;
  /** 建议的成品含量 (克), null = 无法推断 */
  suggestedStandardQuantity: number | null;
  /** 建议出成率 (百分比, 如 61.5 表示 61.5%), null = 无数据 */
  suggestedYieldRate: number | null;
  /** 参考批次数量 */
  sampleCount: number;
  /** 最近 N 批次的出成率最低值 */
  yieldMin: number | null;
  /** 最近 N 批次的出成率最高值 */
  yieldMax: number | null;
  /** 数据来源 */
  source: 'BATCH_REPORTING' | 'STANDARD_WEIGHT_ONLY' | 'NONE';
  /**
   * null = 无异常
   * INSUFFICIENT_SAMPLES = 样本不足
   * NO_GRAMS_PER_UNIT = 产品未配置标准克重
   */
  reason: 'INSUFFICIENT_SAMPLES' | 'NO_GRAMS_PER_UNIT' | null;
  /** 操作提示 (可含跳转指引) */
  actionHint: string | null;
}

/** 一键重算预览 — 单行 */
export interface RecalculatePreviewRow {
  productTypeId: string;
  productName: string;
  bomItemId: number;
  materialName: string;
  /** 当前配置的出成率, null 表示未填 */
  currentYieldRate: number | null;
  /** 建议出成率, null 表示数据不足 */
  suggestedYieldRate: number | null;
  sampleCount: number;
  /**
   * UPDATABLE          = 有足够样本可更新
   * INSUFFICIENT_SAMPLES = 样本不足, 不可更新
   * SKIP               = 跳过 (无主原料 / 无批次报工等)
   */
  status: 'UPDATABLE' | 'INSUFFICIENT_SAMPLES' | 'SKIP';
}

/** 一键重算应用请求体 — 单行 */
export interface RecalculateApplyItem {
  bomItemId: number;
  yieldRate: number;
  /** 乐观锁: 预览时拿到的当前出成率, null 表示预览时未填 (原值也为 null) */
  expectedCurrentYieldRate: number | null;
}

/** 一键重算应用响应 */
export interface RecalculateApplyResponse {
  applied: number;
  changeLogIds: string[];
}

/**
 * 409 Conflict 响应体 — 数据已变化 (乐观锁冲突)
 * 后端在应用时发现 DB 值已被其他操作修改时返回此结构。
 */
export interface RecalculateApplyStaleResponse {
  staleRows: Array<{
    bomItemId: number;
    dbCurrent: number | null;
    expected: number | null;
  }>;
  message: string;
}

const base = (factoryId: string) => `/${factoryId}/bom/yield-estimate`;

export const bomYieldEstimateApi = {
  /**
   * 获取单个产品的出成率评估建议.
   * @param productTypeId  产品 ID
   * @param materialCategory  物料类别 (RAW | AUXILIARY | PACKAGING), 通常传 'RAW'
   */
  getEstimate: (
    factoryId: string,
    productTypeId: string,
    materialCategory: string = 'RAW',
  ) =>
    get<YieldEstimateResponse>(base(factoryId), {
      params: { productTypeId, materialCategory },
    }),

  /**
   * 一键重算预览 — 返回所有可更新 BOM 的建议出成率, 不写数据库.
   * @param productTypeIds  可选: 仅针对指定产品, 不传则全工厂扫描
   */
  recalculatePreview: (
    factoryId: string,
    productTypeIds?: string[],
  ) =>
    post<RecalculatePreviewRow[]>(`${base(factoryId)}/recalculate-preview`, {
      ...(productTypeIds ? { productTypeIds } : {}),
    }),

  /**
   * 一键重算应用 — 写入用户勾选的行, 生成变更日志.
   * @param items  bomItemId + yieldRate 列表
   */
  recalculateApply: (
    factoryId: string,
    items: RecalculateApplyItem[],
  ) =>
    post<RecalculateApplyResponse>(`${base(factoryId)}/recalculate-apply`, items),
};
