/**
 * BOM Yield Estimate API — Phase B/C endpoints (2026-06-07).
 *
 * 对应后端 BomYieldEstimateController:
 *   GET  /{factoryId}/bom/yield-estimate
 *   POST /{factoryId}/bom/yield-estimate/recalculate-preview
 *   POST /{factoryId}/bom/yield-estimate/recalculate-apply
 *
 * Base URL is /api/mobile (set in request.ts baseURL).
 */
import { get, post } from './request';

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
