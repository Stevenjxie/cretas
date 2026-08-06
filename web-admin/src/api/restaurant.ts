/**
 * 餐饮模块 API
 * 配方、领料、盘点、损耗
 */
import { get, post, put, del } from './request';

// ==================== 配方管理 ====================

export const getRecipes = (factoryId: string, params?: Record<string, unknown>) =>
  get(`/${factoryId}/restaurant/recipes`, { params });

export const getRecipe = (factoryId: string, recipeId: string) =>
  get(`/${factoryId}/restaurant/recipes/${recipeId}`);

export const createRecipe = (factoryId: string, data: Record<string, unknown>) =>
  post(`/${factoryId}/restaurant/recipes`, data);

export const updateRecipe = (factoryId: string, recipeId: string, data: Record<string, unknown>) =>
  put(`/${factoryId}/restaurant/recipes/${recipeId}`, data);

export const deleteRecipe = (factoryId: string, recipeId: string) =>
  del(`/${factoryId}/restaurant/recipes/${recipeId}`);

export const getRecipeSummary = (factoryId: string) =>
  get(`/${factoryId}/restaurant/recipes/summary`);

export const calculateRecipeIngredients = (factoryId: string, productTypeId: string, quantity: number = 1) =>
  get(`/${factoryId}/restaurant/recipes/by-dish/${productTypeId}/calculate`, { params: { quantity } });

// ==================== 成本卡 / 出菜反推 (#57) ====================

/**
 * 菜品成本卡: 逐料食材成本拆解 + 毛利率 (按份数缩放)。
 * 价权字段 (totalIngredientCost / sellPrice / grossMargin / 行 unitPrice/itemCost)
 * 对无价权角色由后端 PriceFieldResponseAdvice 自动剥离为 null。
 */
export const getDishCostCard = (factoryId: string, productTypeId: string, portions: number = 1) =>
  get(`/${factoryId}/restaurant/dishes/${productTypeId}/cost-card`, { params: { portions } });

// ==================== 领料管理 ====================

export const getRequisitions = (factoryId: string, params?: Record<string, unknown>) =>
  get(`/${factoryId}/restaurant/requisitions`, { params });

export const getRequisition = (factoryId: string, id: string) =>
  get(`/${factoryId}/restaurant/requisitions/${id}`);

export const createRequisition = (factoryId: string, data: Record<string, unknown>) =>
  post(`/${factoryId}/restaurant/requisitions`, data);

export const submitRequisition = (factoryId: string, id: string) =>
  post(`/${factoryId}/restaurant/requisitions/${id}/submit`);

export const approveRequisition = (factoryId: string, id: string, data?: Record<string, unknown>) =>
  post(`/${factoryId}/restaurant/requisitions/${id}/approve`, data);

export const rejectRequisition = (factoryId: string, id: string, data: { reason: string }) =>
  post(`/${factoryId}/restaurant/requisitions/${id}/reject`, data);

export const getRequisitionStatistics = (factoryId: string) =>
  get<{ totalRequisitions: number; pendingApproval: number; approved: number }>(`/${factoryId}/restaurant/requisitions/statistics`);

// ==================== 盘点管理 ====================

export const getStocktakingRecords = (factoryId: string, params?: Record<string, unknown>) =>
  get(`/${factoryId}/restaurant/stocktaking`, { params });

export const getStocktakingRecord = (factoryId: string, id: string) =>
  get(`/${factoryId}/restaurant/stocktaking/${id}`);

export const createStocktakingRecord = (factoryId: string, data: Record<string, unknown>) =>
  post(`/${factoryId}/restaurant/stocktaking`, data);

export const completeStocktaking = (factoryId: string, id: string, data: Record<string, unknown>) =>
  post(`/${factoryId}/restaurant/stocktaking/${id}/complete`, data);

export const cancelStocktaking = (factoryId: string, id: string) =>
  post(`/${factoryId}/restaurant/stocktaking/${id}/cancel`);

export const getStocktakingSummary = (factoryId: string) =>
  get(`/${factoryId}/restaurant/stocktaking/latest-summary`);

// ==================== 损耗管理 ====================

export const getWastageRecords = (factoryId: string, params?: Record<string, unknown>) =>
  get(`/${factoryId}/restaurant/wastage`, { params });

export const getWastageRecord = (factoryId: string, id: string) =>
  get(`/${factoryId}/restaurant/wastage/${id}`);

export const createWastageRecord = (factoryId: string, data: Record<string, unknown>) =>
  post(`/${factoryId}/restaurant/wastage`, data);

export const submitWastage = (factoryId: string, id: string) =>
  post(`/${factoryId}/restaurant/wastage/${id}/submit`);

export const approveWastage = (factoryId: string, id: string) =>
  post(`/${factoryId}/restaurant/wastage/${id}/approve`);

export const rejectWastage = (factoryId: string, id: string, data: { reason: string }) =>
  post(`/${factoryId}/restaurant/wastage/${id}/reject`, data);

export const getWastageStatistics = (factoryId: string, params?: Record<string, unknown>) =>
  get(`/${factoryId}/restaurant/wastage/statistics`, { params });

// Wave2 损耗责任制汇总（按责任人 + 档口）
export const getWastageAccountability = (factoryId: string, params?: { startDate?: string; endDate?: string }) =>
  get(`/${factoryId}/restaurant/wastage/accountability`, { params });

// Wave2: 责任人下拉（工厂员工列表，dropdown 选择责任人，非自由文本 — 防呆 Rule 3）
export const getFactoryUsersForSelect = (factoryId: string) =>
  get<{ content?: { id: number; username: string; realName?: string }[] } | { id: number; username: string; realName?: string }[]>(
    `/${factoryId}/users`, { params: { size: 500 } });

// ==================== 基础数据 (选择器用) ====================

export const getProductTypesActive = (factoryId: string) =>
  get<{ id: string; name: string; code?: string }[]>(`/${factoryId}/product-types/active`);

export const getRawMaterialTypes = (factoryId: string) =>
  get<{ id: string; name: string; code?: string }[]>(`/${factoryId}/raw-material-types/active`);

// ==================== Dashboard 聚合 ====================

export const getRestaurantDashboardSummary = (factoryId: string) =>
  get<{
    todayRequisitionCount: number;
    pendingApprovalCount: number;
    thisMonthWastageCost: number;
    latestStocktakingDate: string | null;
  }>(`/${factoryId}/restaurant-dashboard/summary`);

// ==================== 发现层（主动出口） ====================

export interface RestaurantFinding {
  code: string;
  domain: string;
  severity: 'CRITICAL' | 'WARNING' | 'INFO';
  actionability: number;
  subjectId: string;
  subjectName: string;
  facts: Record<string, unknown>;
}

export interface RestaurantSkippedRule {
  ruleName: string;
  reason: string;
}

/**
 * 三个桶必须分开消费，不要合成一个 boolean：
 * - findings 空 + checkedRules 非空 = 都正常
 * - skippedRules 非空 = 数据不足，判不了（不是正常，也不是失败）
 * - failedRules 非空 / complete=false = 查询失败
 */
export interface RestaurantFindingsResponse {
  domain: string;
  findings: RestaurantFinding[];
  findingsText: string;
  /**
   * 🔴 渲染卡片用这个，不要拿 findings[].facts 自己拼句子：后端的
   * PriceFieldResponseAdvice 会把 facts.cost / facts.totalCost 置 null
   * （含 "cost" 的数字标量一律抹除，本是给 Excel 财务表用的，对餐饮损耗金额
   * 是误伤），自己拼会渲染出空的「¥ 」。
   */
  digestLines: string[];
  totalCount: number;
  checkedRules: string[];
  skippedRules: RestaurantSkippedRule[];
  failedRules: string[];
  complete: boolean;
}

// ⚠️ query 必须放在 axios config 的 `params` 下。直接传 { domain } 会被当成
// axios config 的未知字段静默丢掉 —— 同一个坑 2026-08-06 刚在 PR#2332
// (material-segments next-code) 修过一次。
export const getRestaurantFindings = (factoryId: string) =>
  get<RestaurantFindingsResponse>(`/${factoryId}/findings`, {
    params: { domain: 'restaurant' },
  });
