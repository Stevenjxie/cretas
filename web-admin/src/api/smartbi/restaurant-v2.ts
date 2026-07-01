/**
 * SmartBI Restaurant V2 API Client (Week 3.5 + Week 4.6)
 *
 * 调用后端 /api/smartbi/restaurant-analytics-v2/{upload_id} endpoint
 * 对应 services/restaurant/analyzer.py (RestaurantAnalyzerV2)
 *
 * V2 Unified Report Schema (11 sections as of Week 4.6):
 *   Week 2-3:
 *     - menuNormalization        菜品命名归一
 *     - channelMargin            渠道毛利率 (4 层 COGS)
 *     - financialMetrics         财务指标 + cost_rigidity
 *     - diagnostics              诊断引擎输出 (list)
 *     - benchmarkAlerts          对标预警 (list, 含年度影响)
 *   Week 4 新增:
 *     - storePnlOnePager         单店 P&L 一页纸 (Week 4.1)
 *     - diningHeatmap            营业时段热力图 (Week 4.2)
 *     - storedValueDependency    充卡依赖度 (Week 4.3a)
 *     - longTailSku              长尾 SKU 识别 (Week 4.3b)
 *     - reviewAnalysis           大众点评评论 (Week 4.5)
 *     - bomLayerStatus           BOM Layer 2+3 精度状态 (Week 4.4)
 */
import { pythonFetch } from './common';

// ── Type definitions ────────────────────────────────────────

export interface FinancialMetricsPeriod {
  revenue: number;
  food_cost?: number;
  labor_cost?: number;
  rent?: number;
  other_cost?: number;
  net_profit?: number;
  stored_value_giveaway?: number;  // Week 4.3
  stored_value_charge?: number;
}

export interface FinancialData {
  current: FinancialMetricsPeriod;
  previous?: FinancialMetricsPeriod;
  monthly_revenue?: number;
}

// Week 4.5 — 大众点评评论
export interface ReviewInput {
  id?: number | string;
  rating: number;       // 1-5
  content: string;
  created_at: string;   // ISO date
  platform?: string;
  reviewer?: string;
}

// Week 4.4 — SKU form (Layer 2)
export interface SkuFormIngredientInput {
  name: string;
  cost: number;
  weightG?: number;
  unitPricePerKg?: number;
}

export interface SkuFormInput {
  skuName: string;
  category: string;
  totalCogsAmount: number;
  sellingPrice?: number;
  monthlySalesQuantity?: number;
  ingredients?: SkuFormIngredientInput[];
  uploadedBy?: string;
  notes?: string;
}

// Week 4.4 — Monthly purchase (Layer 3)
export interface MonthlyPurchaseInput {
  period: string;           // "2026-02"
  totalPurchase: number;
  totalRevenue: number;
  categoryBreakdown?: Record<string, number>;
  storeId?: string;
  notes?: string;
}

export interface V2AnalyzePayload {
  sub_sector?: string;
  store_id?: string;
  store_name?: string;
  period?: string;
  financial_data?: FinancialData;
  // Week 4 新增 inputs
  reviews?: ReviewInput[];
  sku_forms?: SkuFormInput[];
  monthly_purchases?: MonthlyPurchaseInput[];
  // W5.4 inputs
  members?: Array<{
    member_id: string;
    last_order_days_ago: number;
    order_count: number;
    total_amount: number;
  }>;
  // W5.5 — LLM-based review analysis (default: false → regex)
  use_llm_reviews?: boolean;
}

// ── Result types ────────────────────────────────────────────

export interface MenuNormalization {
  originalUniqueCount: number;
  normalizedUniqueCount: number;
  reduction: number;
  reductionPct: number;
  appliedAliasTable: string;
  note: string;
}

export interface ChannelMarginRow {
  channel: string;
  revenue: number;
  orderCount: number;
  avgTicket: number;
  commissionRate: number;
  commissionAmount: number;
  deliveryFee: number;
  packagingCost: number;
  cogs: number;
  cogsSource: string;
  cogsConfidence: string;
  cogsWarning?: string;
  netRevenue: number;
  grossProfit: number;
  grossMarginPct: number;
  expectedAccuracyPp: number;
  commissionSource: string;
}

export interface ChannelMargin {
  factoryId: string;
  subSector: string;
  period: string;
  totalRevenue: number;
  totalGrossProfit: number;
  overallGrossMarginPct: number;
  rows: ChannelMarginRow[];
  cogsSourceSummary: Record<string, number>;
  adviceZh: string[];
}

export interface FinancialMetrics {
  revenue: number;
  foodCost?: number;
  laborCost?: number;
  rent?: number;
  otherCost?: number;
  netProfit?: number;
  foodCostRatio?: number;
  laborCostRatio?: number;
  rentRatio?: number;
  restaurantNetMargin?: number;
  costRigidity?: number;
  revenueChangePct?: number;
  laborCostChangePct?: number;
  foodCostChangePct?: number;
}

export interface Diagnosis {
  metricKey: string;
  metricNameZh: string;
  actualValue: number;
  benchmarkMedian?: number;
  benchmarkRange?: number[];
  thresholdSource: string;
  higherIsWorse: boolean;
  status: string;
  severity: 'critical' | 'warning' | 'info';
  deltaPp: number;
  deltaPct: number;
  descriptionZh: string;
  suggestionZh: string[];
  playbookId?: string;
  playbookUrlZh?: string;
  subSectorNotes: string[];
  formulaZh?: string;
}

export interface BenchmarkAlert {
  metricKey: string;
  metricNameZh: string;
  storeName?: string;
  actualValue: number;
  median: number;
  rangeLow: number;
  rangeHigh: number;
  deltaPpFromMedian: number;
  deltaPpFromHigh: number;
  severity: 'red' | 'yellow' | 'info';
  higherIsWorse: boolean;
  estimatedYearlyImpact?: number;
  messageZh: string;
  actionHint: string;
  source: string;
}

// ── Week 4 section types ────────────────────────────────────

// Week 4.1 — Store P&L One Pager
export interface StorePnlOnePager {
  storeName: string;
  period: string;
  subSector: string;
  headline: string;
  headlineColor: 'green' | 'yellow' | 'red';
  emoji?: string;
  pnlLines?: Array<{
    label: string;
    current?: number;
    previous?: number;
    deltaPct?: number;
    pctOfRevenue?: number;
  }>;
  diagnosticBriefs?: Array<{
    severity: string;
    message: string;
  }>;
  channelSummary?: Array<{
    channel: string;
    revenue: number;
    grossMarginPct: number;
  }>;
  topInsights?: string[];
  topRecommendations?: string[];
}

// Week 4.2 — Dining Period Heatmap
export interface HeatmapCell {
  dayOfWeek: number;
  dayLabel: string;
  hour: number;
  revenue: number;
  orderCount: number;
  avgTicket: number;
}

export interface MealPeriodStats {
  period: string;
  hourRange: number[];
  revenue: number;
  orderCount: number;
  revenuePct: number;
  avgTicket: number;
}

export interface PeakPeriod {
  dayLabel: string;
  hour: number;
  revenue: number;
  orderCount: number;
  emoji: string;
}

export interface DiningHeatmap {
  cells: HeatmapCell[];
  mealPeriods: MealPeriodStats[];
  topPeakHours: PeakPeriod[];
  bottomOffPeakHours: PeakPeriod[];
  totalRevenue: number;
  totalOrders: number;
  avgHourlyRevenue: number;
  peakOffPeakRatio: number;
  insights: string[];
  recommendations: string[];
}

// Week 4.3a — Stored Value Dependency
export interface StoredValueDependency {
  storedValueGiveaway: number;
  storedValueCharge?: number;
  revenue: number;
  dependencyPct: number;
  chargeToRevenueRatio?: number;
  severity: 'info' | 'warning' | 'critical';
  messageZh: string;
  warnings: string[];
  recommendations: string[];
}

// Week 4.3b — Long Tail SKU
export interface LowEfficiencySku {
  name: string;
  quantity: number;
  revenue: number;
  unitPrice: number;
  quantityPercentile: number;
  revenuePercentile: number;
  score: number;
  recommendation: string;
  reason: string;
}

export interface LongTailSku {
  totalSkuCount: number;
  top20PctSkusContribute: number;
  lowEfficiencySkus: LowEfficiencySku[];
  seasonalExcluded: string[];
  recommendedDelistCount: number;
  estimatedCostSaving: number;
  insights: string[];
  recommendations: string[];
}

// Week 4.5 — Review Analysis
export interface DishMention {
  dishName: string;
  mentionCount: number;
  positiveCount: number;
  negativeCount: number;
  neutralCount: number;
  positiveRate: number;
  netSentiment: number;
  avgReviewRating: number;
  exampleQuotes: string[];
}

export interface PeriodRating {
  period: string;
  avgRating: number;
  reviewCount: number;
  positiveRate: number;
}

export interface RatingTrend {
  periods: PeriodRating[];
  direction: 'rising' | 'stable' | 'declining' | 'sharp_decline';
  totalDelta: number;
  latestAvg: number;
  earliestAvg: number;
  maxPeriodDrop: number;
}

export interface ReviewAnalysis {
  totalReviews: number;
  avgRating: number;
  ratingTrend?: RatingTrend;
  dishTags: DishMention[];
  topPraisedDishes: DishMention[];
  topComplainedDishes: DishMention[];
  hiddenGems: DishMention[];
  riskAlerts: string[];
  insights: string[];
  recommendations: string[];
}

// Week 4.4 — BOM Layer Status
export interface BomLayerStatus {
  currentLayer: string;             // "Layer 1" / "Layer 2" / "Layer 3"
  currentAccuracyPp: number;        // ±X%
  layer2SkuCount: number;
  layer3PeriodCount: number;
  upgradeHint: string;
}

// W5.4 — Member RFM
export interface MemberRfmInput {
  memberId?: string;  // or member_id
  member_id?: string;
  lastOrderDaysAgo?: number;
  last_order_days_ago?: number;
  orderCount?: number;
  order_count?: number;
  totalAmount?: number;
  total_amount?: number;
}

export interface MemberScore {
  memberId: string;
  recencyDays: number;
  frequency: number;
  monetary: number;
  avgTicket: number;
  rScore: number;
  fScore: number;
  mScore: number;
  segment: string;
}

export interface MemberRfm {
  totalMembers: number;
  analyzedMembers: number;
  asOfDate: string;
  segmentCounts: Record<string, number>;
  segmentRevenue: Record<string, number>;
  topChampions: MemberScore[];
  atRiskMembers: MemberScore[];
  avgRecency: number;
  avgFrequency: number;
  avgMonetary: number;
  insights: string[];
  recommendations: string[];
}

// W5.7 — Calibration History (Layer A 月度校准报告)
export interface CalibrationSnapshot {
  period: string;
  totalPurchase: number;
  totalRevenue: number;
  overallRatio: number;
  categoryRatios: Record<string, number>;
  factorVsBaseline: number;
}

export interface CategoryVolatility {
  category: string;
  meanRatio: number;
  stddevRatio: number;
  cvPct: number;
  peakPeriod: string;
  troughPeriod: string;
}

export interface CalibrationAnomaly {
  period: string;
  category?: string | null;
  actualRatio: number;
  expectedRatio: number;
  deltaPct: number;
  severity: 'info' | 'warning' | 'critical';
}

export interface CalibrationHistoryReport {
  factoryId: string;
  storeId?: string | null;
  totalPeriods: number;
  periods: CalibrationSnapshot[];
  factorTrend: Array<{ period: string; factor: number; ratio: number }>;
  categoryVolatility: CategoryVolatility[];
  anomalies: CalibrationAnomaly[];
  summary: string;
  insights: string[];
  recommendations: string[];
}

// W6.4 — Multi-Store Comparison
export interface StoreProfile {
  storeName: string;
  totalRevenue: number;
  orderCount: number;
  uniqueSkuCount: number;
  avgTicket: number;
  rank: number;
  vsAvgRevenuePct: number;
}

export interface StoreAnomaly {
  storeName: string;
  metric: string;
  value: number;
  chainAvg: number;
  deltaPct: number;
  severity: 'warning' | 'critical';
}

export interface MultiStoreComparison {
  storeCount: number;
  storeRankings: StoreProfile[];
  topStore: StoreProfile;
  bottomStore: StoreProfile;
  anomalies: StoreAnomaly[];
  revenueSpreadRatio: number;
  insights: string[];
  recommendations: string[];
}

// Premium demo module: external traffic/persona data requires extra enablement.
export interface AdvancedTrafficPersonaProvider {
  provider: string;
  accessMode?: string;
  bestUse?: string;
  availableFields?: string[];
}

export interface AdvancedTrafficPersonaRecommendation {
  priority: string;
  action: string;
  why?: string;
  metricTrigger?: string;
  expectedImpact?: string;
}

export interface AdvancedTrafficPersonaPlainAnalysis {
  bottomLine: string;
  whatItMeans: string[];
  whyWeThinkSo: string[];
}

export interface AdvancedTrafficPersonaSolutionStep {
  step: string;
  action: string;
  owner: string;
  expectedOutcome: string;
}

export interface AdvancedTrafficPersonaDataSufficiency {
  isEnoughForRealDecision: boolean;
  plainVerdict: string;
  why: string[];
  whatCanBeDecidedNow: string[];
  whatCannotBeDecidedYet: string[];
}

export interface AdvancedTrafficPersonaNeededEvidence {
  name: string;
  whyNeeded: string;
  sourceType: string;
  priority: string;
  publicAvailability: string;
  sourceUrl?: string;
}

export interface AdvancedTrafficPersonaAdviceRule {
  situation: string;
  plainDiagnosis: string;
  bossAction: string;
  decisionRule: string;
  neededData: string[];
  sourceBasis: string[];
}

export interface AdvancedTrafficPersonaExternalSignalStatus {
  source: string;
  keyRequired: boolean;
  envVars: string[];
  status: string;
  refreshCadence: string;
  bestUse: string;
  officialUrl?: string | null;
}

export interface AdvancedTrafficPersonaExternalSignal {
  type: string;
  source: string;
  severity: string;
  title: string;
  plainImpact: string;
  actionHint: string;
}

export interface AdvancedTrafficPersonaExternalSignalPipelineStep {
  source: string;
  productionStatus: string;
  refreshCadence: string;
  storesOneApiCall: boolean;
  whatItWrites: string[];
}

export interface AdvancedTrafficPersonaExternalSignalPipeline {
  defaultMode: string;
  whyNotOnPageLoad: string;
  dailyBudgetEnv: Record<string, string>;
  steps: AdvancedTrafficPersonaExternalSignalPipelineStep[];
}

export interface AdvancedTrafficPersonaExternalSignals {
  moduleName: string;
  purpose: string;
  sourceStatuses: AdvancedTrafficPersonaExternalSignalStatus[];
  signals: AdvancedTrafficPersonaExternalSignal[];
  collectionPipeline?: AdvancedTrafficPersonaExternalSignalPipeline;
  plainConclusion: string;
  bossActions: string[];
  dataNeededForProduction: string[];
}

export interface AdvancedTrafficPersona {
  moduleName: string;
  requiresEnablement: boolean;
  demoMode: boolean;
  dataNote: string;
  storeContext: {
    storeName: string;
    city: string;
    businessDistrict: string;
    mallName: string;
    subSector: string;
    period: string;
  };
  enablement: {
    status: string;
    scope: string;
    requirements: string[];
    priceHint?: Record<string, string>;
  };
  providers: AdvancedTrafficPersonaProvider[];
  fieldCatalog: Array<{
    key: string;
    label: string;
    providerCoverage: string[];
    demoValue: unknown;
    businessQuestion: string;
  }>;
  simulatedMetrics: Record<string, number>;
  plainLanguageAnalysis?: AdvancedTrafficPersonaPlainAnalysis;
  solutionPlan?: AdvancedTrafficPersonaSolutionStep[];
  dataSufficiency?: AdvancedTrafficPersonaDataSufficiency;
  neededEvidence?: AdvancedTrafficPersonaNeededEvidence[];
  adviceKnowledgeBase?: AdvancedTrafficPersonaAdviceRule[];
  externalSignals?: AdvancedTrafficPersonaExternalSignals;
  analysis: {
    headline: string;
    comparisonNarrative: string[];
    risks: string[];
    opportunities: string[];
    recommendations: AdvancedTrafficPersonaRecommendation[];
  };
}

// W6.5 — Cross-Chain Benchmark
export interface ChainProfile {
  name: string;
  subSector: string;
  totalRevenue: number;
  skuCount: number;
  storeCount: number;
  avgTicket: number;
  medianPrice: number;
  topCategory: string;
}

export interface CrossChainReport {
  chainCount: number;
  chainProfiles: ChainProfile[];
  priceBandComparison: Array<{chain: string; lowPct: number; midPct: number; highPct: number}>;
  commonDishes: Array<{dish: string; chainCount: number; chains: string[]}>;
  insights: string[];
  recommendations: string[];
}

// W5.6 — Temporal Comparison (同店同比)
export interface TemporalDelta {
  groupName: string;
  metric: string;
  currentValue: number;
  compareValue: number;
  deltaAbs: number;
  deltaPct: number;
  trend: 'up' | 'down' | 'flat';
}

export interface TemporalComparison {
  mode: 'yoy' | 'qoq' | 'mom' | 'insufficient';
  monthsAvailable: number;
  currentPeriod: string;
  comparePeriod: string;
  deltas: TemporalDelta[];
  messageZh: string;
  insufficientReason?: string;
  groupCount: number;
}

export interface V2UnifiedReport {
  factoryId: string;
  subSector: string;
  storeId?: string;
  storeName?: string;
  period: string;
  sections: {
    // Week 2-3
    menuNormalization?: MenuNormalization;
    channelMargin?: ChannelMargin;
    financialMetrics?: FinancialMetrics;
    diagnostics?: Diagnosis[];
    benchmarkAlerts?: BenchmarkAlert[];
    // Week 4
    storePnlOnePager?: StorePnlOnePager;
    diningHeatmap?: DiningHeatmap;
    storedValueDependency?: StoredValueDependency;
    longTailSku?: LongTailSku;
    reviewAnalysis?: ReviewAnalysis;
    bomLayerStatus?: BomLayerStatus;
    // W5.4 + W5.6 + W5.7
    memberRfm?: MemberRfm;
    temporalComparison?: TemporalComparison;
    calibrationHistory?: CalibrationHistoryReport;
    // W6.4
    multiStoreComparison?: MultiStoreComparison;
    // Premium demo / extra enablement
    advancedTrafficPersona?: AdvancedTrafficPersona;
  };
  warnings: string[];
  executiveSummary: string[];
  summary: {
    sectionsGenerated: string[];
    totalDiagnoses: number;
    totalAlerts: number;
    criticalIssues: number;
    redAlerts: number;
  };
}

export interface V2Response {
  success: boolean;
  cached: boolean;
  data?: V2UnifiedReport;
  performance?: {
    loadSeconds: number;
    computeSeconds: number;
    totalSeconds: number;
    posRows: number;
  };
  warning?: string;
  message?: string;
  code?: string;
  cachedAt?: string;
}

// ── API functions ──────────────────────────────────────────

/**
 * GET /api/smartbi/restaurant-analytics-v2/{upload_id}
 * 读缓存, 没算过返回 {success: false, cached: false}
 */
export async function getRestaurantAnalyticsV2(
  uploadId: number
): Promise<V2Response> {
  return pythonFetch<V2Response>(
    `/api/smartbi/restaurant-analytics-v2/${uploadId}`,
    { method: 'GET' }
  );
}

/**
 * POST /api/smartbi/restaurant-analytics-v2/{upload_id}
 * 触发 V2 分析, 返回 unified report
 */
export async function computeRestaurantAnalyticsV2(
  uploadId: number,
  payload: V2AnalyzePayload = {},
  force: boolean = false
): Promise<V2Response> {
  const url = `/api/smartbi/restaurant-analytics-v2/${uploadId}${force ? '?force=true' : ''}`;
  return pythonFetch<V2Response>(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

// ═══════════════════════════════════════════════════════════
// W5.2 — 客户数据录入 API (SKU 表单 + 月度采购 持久化)
// ═══════════════════════════════════════════════════════════

export interface SkuFormListResponse {
  success: boolean;
  data?: {
    factoryId: string;
    totalCount: number;
    byCategory: Record<string, number>;
    items: Array<{
      skuName: string;
      category: string;
      totalCogsAmount: number;
      sellingPrice?: number;
      cogsPct?: number;
      monthlySalesQuantity?: number;
      ingredients: Array<{ name: string; cost: number; weightG?: number }>;
      uploadedAt?: string;
      uploadedBy?: string;
      notes?: string;
    }>;
  };
  message?: string;
}

export interface SkuFormUploadResponse {
  success: boolean;
  data?: {
    factoryId: string;
    uploaded: number;
    updated: number;
    invalid: string[];
    totalAfterUpload: number;
    byCategory: Record<string, number>;
  };
  message?: string;
}

export interface MonthlyPurchaseListResponse {
  success: boolean;
  data?: {
    factoryId: string;
    totalCount: number;
    items: Array<{
      id: number;
      period: string;
      totalPurchase: number;
      totalRevenue: number;
      overallRatio: number;
      categoryBreakdown: Record<string, number>;
      createdAt: string;
    }>;
    currentCalibration?: {
      sampleSize: number;
      confidence: string;
      overallActualRatio: number;
      periodsUsed: string[];
      warnings: string[];
    };
  };
  message?: string;
}

export interface MonthlyPurchaseUploadResponse {
  success: boolean;
  data?: {
    factoryId: string;
    saved: number;
    countBefore: number;
    countAfter: number;
    errors: string[];
    currentCalibration?: MonthlyPurchaseListResponse['data'] extends infer D
      ? D extends { currentCalibration?: infer C }
        ? C
        : never
      : never;
  };
  message?: string;
}

/** GET 工厂的所有 SKU 表单 */
export async function listSkuForms(factoryId: string): Promise<SkuFormListResponse> {
  return pythonFetch<SkuFormListResponse>(
    `/api/smartbi/restaurant-sku-forms?factory_id=${encodeURIComponent(factoryId)}`,
    { method: 'GET' }
  );
}

/** POST 批量上传/更新 SKU 表单 */
export async function uploadSkuForms(
  factoryId: string,
  entries: SkuFormInput[]
): Promise<SkuFormUploadResponse> {
  return pythonFetch<SkuFormUploadResponse>('/api/smartbi/restaurant-sku-forms', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ factory_id: factoryId, entries }),
  });
}

/** DELETE 某个 SKU 表单 */
export async function deleteSkuForm(
  factoryId: string,
  skuName: string
): Promise<{ success: boolean; data?: { deleted: boolean } }> {
  return pythonFetch<{ success: boolean; data?: { deleted: boolean } }>(
    `/api/smartbi/restaurant-sku-forms/${encodeURIComponent(skuName)}?factory_id=${encodeURIComponent(factoryId)}`,
    { method: 'DELETE' }
  );
}

/** GET 工厂的所有月度采购 */
export async function listMonthlyPurchases(
  factoryId: string
): Promise<MonthlyPurchaseListResponse> {
  return pythonFetch<MonthlyPurchaseListResponse>(
    `/api/smartbi/restaurant-monthly-purchases?factory_id=${encodeURIComponent(factoryId)}`,
    { method: 'GET' }
  );
}

/** POST 批量上传/更新月度采购 */
export async function uploadMonthlyPurchases(
  factoryId: string,
  entries: MonthlyPurchaseInput[]
): Promise<MonthlyPurchaseUploadResponse> {
  return pythonFetch<MonthlyPurchaseUploadResponse>(
    '/api/smartbi/restaurant-monthly-purchases',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ factory_id: factoryId, entries }),
    }
  );
}

/** DELETE 某期月度采购 */
export async function deleteMonthlyPurchase(
  factoryId: string,
  period: string
): Promise<{ success: boolean; data?: { deleted: number } }> {
  return pythonFetch<{ success: boolean; data?: { deleted: number } }>(
    `/api/smartbi/restaurant-monthly-purchases/${encodeURIComponent(period)}?factory_id=${encodeURIComponent(factoryId)}`,
    { method: 'DELETE' }
  );
}

// ═══════════════════════════════════════════════════════════
// W6.5 — Cross-Chain Benchmark API (separate endpoint)
// ═══════════════════════════════════════════════════════════

/** POST cross-chain benchmark (dedicated endpoint, not part of V2 response) */
export async function runCrossChainBenchmark(
  _factoryId: string,
  _uploadIds: number[]
): Promise<{ success: boolean; data?: CrossChainReport }> {
  // Endpoint not yet implemented on backend; return stub
  return { success: false, data: undefined };
}

// ═══════════════════════════════════════════════════════════
// W6 — Review Collection Management API
// ═══════════════════════════════════════════════════════════

/** GET 工厂已注册的评论源 */
export async function listReviewSources(
  factoryId: string
): Promise<unknown> {
  return pythonFetch(
    `/api/smartbi/restaurant-review-sources?factory_id=${encodeURIComponent(factoryId)}`,
    { method: 'GET' }
  );
}

/** POST 注册新评论源 (门店+城市+平台) */
export async function registerReviewSource(
  factoryId: string,
  storeName: string,
  city: string,
  platform: string
): Promise<unknown> {
  return pythonFetch('/api/smartbi/restaurant-review-sources', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ factory_id: factoryId, store_name: storeName, city, platform }),
  });
}

/** POST 批量上传评论 */
export async function uploadReviews(
  factoryId: string,
  storeName: string,
  reviews: ReviewInput[]
): Promise<unknown> {
  return pythonFetch('/api/smartbi/restaurant-reviews/upload', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ factory_id: factoryId, store_name: storeName, reviews }),
  });
}

/** GET 评论统计概要 */
export async function getReviewStats(
  factoryId: string
): Promise<unknown> {
  return pythonFetch(
    `/api/smartbi/restaurant-reviews/stats?factory_id=${encodeURIComponent(factoryId)}`,
    { method: 'GET' }
  );
}
