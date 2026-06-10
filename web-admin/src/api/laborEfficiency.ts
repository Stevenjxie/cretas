/**
 * SP9 人效对比 API — LaborEfficiencyController
 *
 * 后端基础路径: /api/mobile/{factoryId}/labor-efficiency
 * 实际存在端点: GET /compare?startDate=&endDate=&productTypeId= (startDate/endDate REQUIRED)
 *
 * @PriceSensitive: 所有成本/金额字段对非财务角色为 null，前端必须展示 "—"，
 * 严禁在 JS 端重新计算成本。
 */
import { get } from './request';
import type { ApiResponse } from '@/types/api';

// ============================================================================
// 类型
// ============================================================================

export type LaborVarianceStatus = 'NORMAL' | 'WARNING' | 'CRITICAL';
export type LaborAchievementAlert = 'BELOW' | 'ABOVE';

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
