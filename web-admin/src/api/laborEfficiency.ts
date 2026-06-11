/**
 * SP9/SP3 人效对比 API — LaborEfficiencyController
 *
 * 后端基础路径: /api/mobile/{factoryId}/labor-efficiency
 * 端点:
 *   GET /compare?startDate=&endDate=&productTypeId=         (SP9-M3 批次级)
 *   GET /compare/order-aggregate?startDate=&endDate=&...    (SP3-M3b 订单级聚合)
 *   GET /achievement-summary?startDate=&endDate=&...       (M4: 达成率汇总)
 *   GET /batches/{batchId}/step-breakdown                  (step-breakdown)
 *
 * @PriceSensitive: 所有成本/金额字段对非财务角色为 null，前端必须展示 "—"，
 * 严禁在 JS 端重新计算成本。偏差率 % / 偏差状态 / 批次数量不脱敏。
 */
import { get } from './request';
import type { ApiResponse } from '@/types/api';

// ============================================================================
// 类型
// ============================================================================

export type LaborVarianceStatus = 'NORMAL' | 'WARNING' | 'CRITICAL';
export type LaborAchievementAlert = 'BELOW_ALERT' | 'ABOVE_ALERT' | 'OK';

// ─── M4: 达成率汇总 ──────────────────────────────────────────────────────────

/** M4 批次级达成率明细 */
export interface BatchAchievementItemDTO {
  batchNumber: string;
  productName: string;
  actualWorkMinutes: number | null;
  plannedWorkMinutes: number | null;
  achievementRatePct: number | null;
  achievementAlert: LaborAchievementAlert | null;
}

/** M4 产品维度达成率汇总 */
export interface LaborAchievementSummaryDTO {
  productTypeId: string | null;
  productName: string;
  totalActualWorkMinutes: number | null;
  totalPlannedWorkMinutes: number | null;
  /** 达成率 %; null=无计划工时配置 */
  achievementRatePct: number | null;
  achievementAlert: LaborAchievementAlert | null;
  batchCount: number;
  batchItems: BatchAchievementItemDTO[];
}

// ─── step-breakdown ──────────────────────────────────────────────────────────

/** 逐工序拆分 — 单工序明细 */
export interface StepBreakdownItem {
  processOrder: number | null;
  processName: string;
  actualWorkMinutes: number | null;
  actualWorkers: number | null;
  plannedWorkMinutes: number | null;
  achievementRatePct: number | null;
  achievementAlert: LaborAchievementAlert | null;
  /** 工序人工成本 (@PriceSensitive) */
  laborCost: number | null;
  /** 工序每箱人工成本 (@PriceSensitive) */
  laborCostPerBox: number | null;
}

/** step-breakdown 批次结果 */
export interface LaborStepBreakdownDTO {
  batchId: number;
  batchNumber: string;
  productName: string;
  totalActualWorkMinutes: number | null;
  totalPlannedWorkMinutes: number | null;
  overallAchievementRatePct: number | null;
  overallAchievementAlert: LaborAchievementAlert | null;
  steps: StepBreakdownItem[];
}

/** 工序级人效明细 */
export interface LaborVarianceItemDTO {
  processName: string;
  totalWorkMinutes: number | null;
  totalWorkers: number | null;
  /** 工序人工成本 (@PriceSensitive) */
  laborCost: number | null;
  /** 每箱人工成本 (@PriceSensitive) */
  laborCostPerBox: number | null;
  /**
   * 工序达成率 (0~100+)，null 表示未设置标准工时
   * @PriceSensitive
   */
  achievementRate: number | null;
  /** null=正常/未设置，BELOW=低于标准，ABOVE=高于标准 */
  achievementAlert: LaborAchievementAlert | null;
}

/** 批次级人效对比汇总 */
export interface LaborEfficiencyCompareDTO {
  batchNumber: string;
  productName: string;
  /** 报价人工成本/kg (@PriceSensitive) */
  quotedLaborCostPerKg: number | null;
  /** 实际人工成本/kg (@PriceSensitive) */
  actualLaborCostPerKg: number | null;
  /** 报价人工成本/箱 (@PriceSensitive) */
  quotedLaborCostPerBox: number | null;
  /** 实际人工成本/箱 (@PriceSensitive) */
  actualLaborCostPerBox: number | null;
  /** 成本偏差率 (实际 vs 报价，正值=超支，@PriceSensitive) */
  varianceRate: number | null;
  varianceStatus: LaborVarianceStatus;
  stepDetails: LaborVarianceItemDTO[];
}

// ============================================================================
// SP3 P1 #39: 订单级聚合类型
// ============================================================================

/**
 * SP3 P1: 成本组/公单级人工双口径聚合 DTO.
 *
 * 将同一销售订单下的多批次人工成本归拢，展示：
 * - 研发预估人工成本合计（@PriceSensitive, 无权限显示 —）
 * - 实际人工成本合计（@PriceSensitive）
 * - 偏差率 % / 偏差状态（不脱敏，所有角色可见）
 */
export interface LaborEfficiencyOrderAggregateDTO {
  /** 销售订单 ID（sourceOrderId）, null = 未关联订单的批次 */
  salesOrderId: string | null;
  /** 销售订单编号，null = 未关联 */
  salesOrderNumber: string | null;
  productName: string | null;
  productTypeId: string | null;
  productionPlanId: string | null;
  batchCount: number;
  /** 合计好品产量 (kg), null = 无数据 */
  totalGoodQuantityKg: number | null;
  /** 标准克重 (g/份), null = 未配或多产品 */
  gramsPerUnit: number | null;
  /** 折算产量 (盒), null = 无法折算 */
  totalGoodQuantityBoxes: number | null;
  /** 研发预估人工成本合计 (元) @PriceSensitive */
  totalQuotedLaborCost: number | null;
  /** 实际人工成本合计 (元) @PriceSensitive */
  totalActualLaborCost: number | null;
  /** 人工成本偏差绝对值 (元) @PriceSensitive */
  laborCostVarianceAbsolute: number | null;
  /**
   * 人工成本偏差率 (%)：正数 = 超预算。相对指标，不脱敏。
   * null = quotedLaborCost 未配
   */
  varianceRate: number | null;
  /** 偏差状态: OK / WARNING / CRITICAL / null。不脱敏。 */
  varianceStatus: LaborVarianceStatus | null;
  /** 批次级明细（展开用） */
  batches: LaborEfficiencyCompareDTO[];
}

// ============================================================================
// API 方法
// ============================================================================

/**
 * 批次人效对比列表.
 *
 * @param factoryId      工厂 ID
 * @param startDate      开始日期 (ISO, e.g. "2026-06-01")，REQUIRED
 * @param endDate        结束日期 (ISO, e.g. "2026-06-30")，REQUIRED
 * @param productTypeId  可选产品类型过滤
 */
export function getLaborEfficiencyCompare(
  factoryId: string,
  startDate: string,
  endDate: string,
  productTypeId?: string | null,
): Promise<ApiResponse<LaborEfficiencyCompareDTO[]>> {
  return get<LaborEfficiencyCompareDTO[]>(
    `/${factoryId}/labor-efficiency/compare`,
    {
      params: {
        startDate,
        endDate,
        ...(productTypeId ? { productTypeId } : {}),
      },
    },
  );
}

/**
 * SP3 P1 #39: 成本组/公单级人工双口径聚合列表.
 *
 * 将同一销售订单下的多批次人工成本归拢到订单级。
 * @PriceSensitive 字段对非财务角色为 null。
 *
 * @param factoryId      工厂 ID
 * @param startDate      开始日期 (ISO)，REQUIRED
 * @param endDate        结束日期 (ISO)，REQUIRED
 * @param productTypeId  可选产品类型过滤
 */
export function getLaborCostOrderAggregate(
  factoryId: string,
  startDate: string,
  endDate: string,
  productTypeId?: string | null,
): Promise<ApiResponse<LaborEfficiencyOrderAggregateDTO[]>> {
  return get<LaborEfficiencyOrderAggregateDTO[]>(
    `/${factoryId}/labor-efficiency/compare/order-aggregate`,
    {
      params: {
        startDate,
        endDate,
        ...(productTypeId ? { productTypeId } : {}),
      },
    },
  );
}

/**
 * M4: 人效达成率汇总.
 *
 * @param factoryId      工厂 ID
 * @param startDate      开始日期 (ISO)，REQUIRED
 * @param endDate        结束日期 (ISO)，REQUIRED
 * @param productTypeId  可选产品类型过滤
 */
export function getLaborAchievementSummary(
  factoryId: string,
  startDate: string,
  endDate: string,
  productTypeId?: string | null,
): Promise<ApiResponse<LaborAchievementSummaryDTO[]>> {
  return get<LaborAchievementSummaryDTO[]>(
    `/${factoryId}/labor-efficiency/achievement-summary`,
    {
      params: {
        startDate,
        endDate,
        ...(productTypeId ? { productTypeId } : {}),
      },
    },
  );
}

/**
 * step-breakdown: 批次逐工序人效拆分.
 *
 * @param factoryId  工厂 ID
 * @param batchId    批次 ID
 */
export function getLaborStepBreakdown(
  factoryId: string,
  batchId: number,
): Promise<ApiResponse<LaborStepBreakdownDTO>> {
  return get<LaborStepBreakdownDTO>(
    `/${factoryId}/labor-efficiency/batches/${batchId}/step-breakdown`,
  );
}
