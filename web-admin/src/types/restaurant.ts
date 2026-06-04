/**
 * 餐饮模块实体类型
 */

export interface RecipeItem {
  id: string;
  productTypeId: string;
  rawMaterialTypeId: string;
  standardQuantity: number;
  unit: string;
  netYieldRate?: number;
  isMainIngredient: boolean;
  isActive: boolean;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface RequisitionItem {
  id: string;
  requisitionNumber?: string;
  requisitionDate?: string;
  type: 'PRODUCTION' | 'MANUAL';
  status: 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';
  productTypeId?: string;
  dishQuantity?: number;
  rawMaterialTypeId: string;
  requestedQuantity: number;
  actualQuantity?: number;
  unit: string;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface StocktakingRecord {
  id: string;
  stocktakingNumber?: string;
  stocktakingDate?: string;
  status: 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  rawMaterialTypeId: string;
  unit: string;
  systemQuantity: number;
  actualQuantity?: number;
  differenceType?: 'SURPLUS' | 'SHORTAGE' | 'MATCH';
  differenceQuantity?: number;
  /** Monetary value of the count difference (computed server-side). */
  differenceAmount?: number;
  adjustmentReason?: string;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface WastageRecord {
  id: string;
  wastageNumber?: string;
  wastageDate?: string;
  type: 'EXPIRED' | 'DAMAGED' | 'SPOILED' | 'PROCESSING' | 'OTHER';
  status: 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';
  rawMaterialTypeId: string;
  quantity: number;
  unit: string;
  estimatedCost?: number;
  reason?: string;
  notes?: string;
  // Wave2 损耗按人/档口责任制
  operatorId?: number | null;
  sectionCode?: 'SEAFOOD' | 'COLD_DISH' | 'HOT_DISH' | 'FRONT_HOUSE' | 'OTHER' | null;
  createdAt?: string;
  updatedAt?: string;
}

/** Wave2 损耗责任制汇总（按责任人 + 档口） */
export interface WastageAccountability {
  startDate: string;
  endDate: string;
  totalCost?: number | null;
  totalCount: number;
  byOperator: WastageByOperator[];
  bySection: WastageBySection[];
}

export interface WastageByOperator {
  operatorId?: number | null;
  operatorName: string;
  count: number;
  totalQuantity?: number | null;
  totalCost?: number | null;
}

export interface WastageBySection {
  sectionCode?: string | null;
  sectionName: string;
  count: number;
  totalQuantity?: number | null;
  totalCost?: number | null;
}
