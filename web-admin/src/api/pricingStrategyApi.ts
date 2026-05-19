// Canvas-Pricing Phase 4b — 价格策略 API client (2026-05-18).
//
// Backend: PricingStrategyController @RequestMapping "/api/mobile/{factoryId}/pricing"
//   GET    /strategies                       — list (含已禁用)
//   POST   /strategies                       — create
//   PUT    /strategies/{id}                  — update
//   POST   /strategies/{id}/toggle           — toggle enabled
//   DELETE /strategies/{id}                  — soft delete
//   POST   /strategies/simulate              — preview pricing (NOT 写日志)
//   GET    /logs                             — application logs (Page)
//
// axios baseURL = '/api/mobile' so caller-side URLs start at "/{factoryId}/...".
import { get, post, put, del } from './request';

// ==================== Types ====================

/**
 * 价格策略 5 种类型 (per spec §1).
 *
 * - TIERED:    阶梯定价 — `{tiers: [{minQty, maxQty?, discountPct}, ...]}`
 * - PROMOTION: 促销满减 — `{thresholdAmount, discountAmount? | discountRate? | discountPct?, validFrom?, validTo?}`
 * - MEMBER:    会员折扣 — `{membershipTier: "VIP", discountPct, customerRatingFilter?}` OR `{tierDiscounts: {VIP: 5, A: 3, ...}}`
 * - BUNDLE:    套餐价   — `{items: [{productId, qty}, ...], bundlePrice}` OR `{productIds: [...], discountPct | discountAmount}`
 * - CYCLE:     跨周期返点 — `{cycle: "MONTH", tiers: [{minAmount, rebatePct}, ...]}`
 */
export type PricingStrategyType = 'TIERED' | 'PROMOTION' | 'MEMBER' | 'BUNDLE' | 'CYCLE';

/** scope_filter_json shape — 控制策略适用范围 (empty = 全部适用). */
export interface ScopeFilter {
  /** Product master 的 product_category 字段必须 ∈ 此列表 */
  productCategories?: string[];
  /** Customer.importance / customerGroup 必须 ∈ 此列表 */
  customerGroups?: string[];
  /** 区域过滤 */
  regions?: string[];
}

/** 阶梯定价规则 — TIERED type rules_json shape. */
export interface TieredTier {
  /** 最小数量 (含) */
  minQty: number;
  /** 最大数量 (含, NULL = 无上限) */
  maxQty?: number | null;
  /** 折扣百分比 (e.g. 5 表示 5% off) */
  discountPct: number;
}

/** PROMOTION type rules_json shape. */
export interface PromotionRules {
  /** 满减门槛金额 (订单总额 >= 此值才生效) */
  thresholdAmount: number;
  /** 满减金额 (固定减额). 与 discountRate / discountPct 互斥 */
  discountAmount?: number;
  /** 折扣率 (0.0-1.0). 与 discountAmount / discountPct 互斥 */
  discountRate?: number;
  /** 折扣百分比 (0-100). 与 discountAmount / discountRate 互斥 */
  discountPct?: number;
}

/** MEMBER type rules_json shape — 支持 single-tier 和 multi-tier 两种格式. */
export interface MemberRules {
  /** Single-tier: 会员等级 (e.g. "VIP"). 与 tierDiscounts 互斥 */
  membershipTier?: string;
  /** Single-tier: 折扣百分比 */
  discountPct?: number;
  /** Single-tier: 客户评级过滤 (e.g. rating >= "A") */
  customerRatingFilter?: string;
  /** Multi-tier: 等级→折扣% 映射 (e.g. {VIP: 5, A: 3, B: 1}) */
  tierDiscounts?: Record<string, number>;
}

/** BUNDLE type rules_json shape. */
export interface BundleRules {
  /** 套餐组成 (按 product + qty) — exact match */
  items?: Array<{ productId: string; qty: number }>;
  /** 简化版: 只匹配产品集合, 不限 qty */
  productIds?: string[];
  /** 套餐统一价 (替代各 line 的 unitPrice). 与 discountPct/discountAmount 互斥 */
  bundlePrice?: number;
  /** 套餐折扣百分比. 与 bundlePrice/discountAmount 互斥 */
  discountPct?: number;
  /** 套餐折扣金额. 与 bundlePrice/discountPct 互斥 */
  discountAmount?: number;
}

/** CYCLE type rules_json shape. */
export interface CycleRules {
  /** 结算周期 (MONTH / QUARTER / YEAR) */
  cycle: 'MONTH' | 'QUARTER' | 'YEAR';
  /** 阶梯返点配置 */
  tiers: Array<{
    /** 周期内累计金额阈值 */
    minAmount: number;
    /** 返点率 (0-100) */
    rebatePct: number;
  }>;
}

/** Strategy-type-specific rules union type. */
export type StrategyRulesJson = TieredTier[] | PromotionRules | MemberRules | BundleRules | CycleRules | Record<string, unknown>;

/** 价格策略 (后端 entity 字段对齐). */
export interface PricingStrategy {
  /** UUID 主键 */
  id: string;
  factoryId: string;
  /** 业务唯一键 (per-factory unique) */
  strategyCode: string;
  strategyName?: string;
  strategyType: PricingStrategyType;
  scopeFilterJson?: ScopeFilter;
  rulesJson?: StrategyRulesJson | { tiers?: TieredTier[] } | Record<string, unknown>;
  /** Lower number = higher priority */
  priority: number;
  enabled: boolean;
  /** YYYY-MM-DD or null (无下界) */
  validFrom?: string | null;
  /** YYYY-MM-DD or null (无上界) */
  validTo?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

/** Strategy create/update request — Controller.StrategyRequest 对齐. */
export interface StrategyRequest {
  strategyCode: string;
  strategyName?: string;
  strategyType: PricingStrategyType;
  scopeFilterJson?: ScopeFilter;
  rulesJson?: Record<string, unknown>;
  priority?: number;
  enabled?: boolean;
  validFrom?: string | null;
  validTo?: string | null;
}

/** Simulate 请求 — Controller.SimulateRequest 对齐. */
export interface SimulateRequest {
  productId: string;
  quantity?: number;
  unitPriceList?: number;
  customerId?: number | null;
  /** Post-review I7: 必须传, 否则 MEMBER 策略 + scope_filter customerGroups 不生效 */
  customerGroup?: string | null;
  /** Post-review I7: 必须传, 否则 scope_filter productCategories 不生效 */
  productCategory?: string | null;
  /** Optional cost estimate for fool-proof "below cost" warning */
  costEstimate?: number | null;
  region?: string | null;
}

/** Applied strategy record (1 entry of PricingResult.appliedStrategies). */
export interface AppliedStrategy {
  strategyId: string;
  strategyCode: string;
  strategyType: PricingStrategyType;
  /** Per-unit discount amount applied by this strategy */
  discountApplied: number;
}

/** PricingEngine.simulate result. */
export interface PricingResult {
  originalPrice: number;
  finalPrice: number;
  /** originalPrice - finalPrice (positive = 有折扣) */
  totalDiscount: number;
  appliedStrategies: AppliedStrategy[];
  /** 防呆 warnings (e.g. "final price ¥X below cost ¥Y") — NOT BLOCKING */
  warnings: string[];
}

/** Application log entry (after PricingEngine.calculate). */
export interface PricingApplicationLog {
  id: string;
  strategyId?: string | null;
  factoryId: string;
  businessEntityType?: string;
  businessEntityId?: string;
  originalPrice: number;
  finalPrice: number;
  discount: number;
  appliedStrategies: Array<Record<string, unknown>>;
  appliedAt: string;
  createdAt?: string;
}

/** Spring Page response shape. */
export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// ==================== API methods ====================

/** GET /strategies — list all strategies (含已禁用). */
export async function listStrategies(factoryId: string): Promise<PricingStrategy[]> {
  if (!factoryId) return [];
  const res = await get<PricingStrategy[]>(`/${factoryId}/pricing/strategies`);
  return res.success && Array.isArray(res.data) ? res.data : [];
}

/** POST /strategies — create new strategy. */
export async function createStrategy(
  factoryId: string,
  req: StrategyRequest
): Promise<PricingStrategy> {
  const res = await post<PricingStrategy>(`/${factoryId}/pricing/strategies`, req);
  if (!res.success) {
    throw new Error(res.message || '创建价格策略失败');
  }
  return res.data as PricingStrategy;
}

/** PUT /strategies/{id} — update existing strategy. */
export async function updateStrategy(
  factoryId: string,
  id: string,
  req: StrategyRequest
): Promise<PricingStrategy> {
  const res = await put<PricingStrategy>(`/${factoryId}/pricing/strategies/${id}`, req);
  if (!res.success) {
    throw new Error(res.message || '更新价格策略失败');
  }
  return res.data as PricingStrategy;
}

/** POST /strategies/{id}/toggle — toggle enabled. */
export async function toggleStrategy(
  factoryId: string,
  id: string
): Promise<PricingStrategy> {
  const res = await post<PricingStrategy>(`/${factoryId}/pricing/strategies/${id}/toggle`, {});
  if (!res.success) {
    throw new Error(res.message || '启停策略失败');
  }
  return res.data as PricingStrategy;
}

/** DELETE /strategies/{id} — soft delete. */
export async function deleteStrategy(factoryId: string, id: string): Promise<void> {
  const res = await del<void>(`/${factoryId}/pricing/strategies/${id}`);
  if (!res.success) {
    throw new Error(res.message || '删除策略失败');
  }
}

/** POST /strategies/simulate — preview pricing without persisting log. */
export async function simulateStrategy(
  factoryId: string,
  req: SimulateRequest
): Promise<PricingResult> {
  const res = await post<PricingResult>(`/${factoryId}/pricing/strategies/simulate`, req);
  if (!res.success) {
    throw new Error(res.message || '模拟计算失败');
  }
  return res.data as PricingResult;
}

/** GET /logs — list application logs (optionally filtered by businessEntityId). */
export async function listLogs(
  factoryId: string,
  options: { businessEntityId?: string; page?: number; size?: number } = {}
): Promise<PageResponse<PricingApplicationLog>> {
  const params: Record<string, string | number> = {
    page: options.page ?? 0,
    size: options.size ?? 20,
  };
  if (options.businessEntityId && options.businessEntityId.trim()) {
    params.businessEntityId = options.businessEntityId.trim();
  }
  const res = await get<PageResponse<PricingApplicationLog>>(`/${factoryId}/pricing/logs`, { params });
  if (!res.success || !res.data) {
    return { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
  }
  return res.data;
}
