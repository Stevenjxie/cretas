/**
 * SmartBI 类型定义
 *
 * 智能商业分析系统的前端类型定义
 * 包括：经营驾驶舱、销售分析、部门分析、区域分析、财务分析等
 */

import { NativeStackScreenProps } from '@react-navigation/native-stack';

// ==================== 导航类型 ====================

/**
 * SmartBI Stack 导航参数
 */
export type SmartBIStackParamList = {
  // 基础模块
  SmartBIHome: undefined;
  ExecutiveDashboard: undefined;
  SalesAnalysis: { department?: string; region?: string };
  FinanceAnalysis: { analysisType?: string };
  ExcelUpload: undefined;
  SmartBIDataAnalysis: undefined;  // 智能数据分析
  DynamicAnalysis: { uploadId?: number };  // 动态多Sheet分析
  NLQuery: { initialQuery?: string };
  DrillDown: { dimension: string; value: string; parentContext?: Record<string, unknown> };

  // 生产与质量分析
  ProductionDashboard: { dateRange?: string };
  QualityDashboard: { productLine?: string };

  // 库存与采购分析
  InventoryDashboard: { category?: string };
  ProcurementDashboard: { supplierId?: string };

  // 销售与客户分析
  SalesFunnel: { period?: string };
  CustomerRFM: { segment?: string };

  // 财务深度分析
  CashFlow: { period?: string };
  FinancialRatios: { ratioType?: string };

  // 人效分析
  EfficiencyDashboard: { period?: string };
};

/**
 * SmartBI 屏幕 Props
 */
export type SmartBIScreenProps<T extends keyof SmartBIStackParamList> =
  NativeStackScreenProps<SmartBIStackParamList, T>;

// ==================== 分析参数 ====================

/**
 * 通用分析参数
 */
export interface AnalysisParams {
  startDate: string;  // YYYY-MM-DD
  endDate: string;
  department?: string;
  region?: string;
  dimension?: string;
  granularity?: 'day' | 'week' | 'month';
  factoryId?: string;
  analysisType?: string;
}

// ==================== KPI 卡片 ====================

/**
 * KPI 卡片数据
 */
export interface KPICard {
  key: string;
  title: string;
  value: string;
  rawValue: number;
  unit?: string;
  change?: number;
  changeRate?: number;
  trend: 'up' | 'down' | 'flat';
  status: 'green' | 'yellow' | 'red';
}

// ==================== 排名数据 ====================

/**
 * 排名项
 */
export interface RankingItem {
  rank: number;
  id: string;
  name: string;
  value: number;
  displayValue: string;
  change?: number;
  status?: string;
}

/**
 * 指标结果
 */
export interface MetricResult {
  metricCode: string;
  metricName: string;
  formattedValue: string;
  unit?: string;
  changePercent?: number;
  changeDirection?: 'UP' | 'DOWN' | 'STABLE';
}

// ==================== 图表配置 ====================

/**
 * 图表类型
 */
export type ChartType =
  | 'line' | 'bar' | 'pie' | 'area'
  | 'LINE' | 'BAR' | 'BAR_HORIZONTAL' | 'PIE' | 'DONUT' | 'AREA'
  | 'GAUGE' | 'RADAR' | 'FUNNEL' | 'WATERFALL'
  | 'COMBINATION' | 'DUAL_AXIS'
  | 'SCATTER' | 'HEATMAP' | 'MATRIX_HEATMAP' | 'TREEMAP'
  | 'SANKEY' | 'SUNBURST' | 'PARETO' | 'BULLET' | 'SLOPE' | 'NESTED_DONUT';

/**
 * 图表配置
 */
export interface ChartConfig {
  type: ChartType;
  title: string;
  data: ChartData;
  options?: ChartOptions;
}

/**
 * 图表数据
 */
export interface ChartData {
  labels?: string[];
  datasets?: ChartDataset[];
  [key: string]: unknown;
}

/**
 * 图表数据集
 */
export interface ChartDataset {
  label?: string;
  data: number[];
  backgroundColor?: string | string[];
  borderColor?: string | string[];
  [key: string]: unknown;
}

/**
 * 图表选项
 */
export interface ChartOptions {
  responsive?: boolean;
  maintainAspectRatio?: boolean;
  [key: string]: unknown;
}

// ==================== AI 洞察 ====================

/**
 * AI 洞察
 */
export interface AIInsight {
  id: string;
  level: 'green' | 'yellow' | 'red';
  category: string;
  text: string;
}

// ==================== 预警 ====================

/**
 * 预警级别
 */
export type AlertLevel = 'green' | 'yellow' | 'red' | 'critical';

/**
 * 预警信息
 */
export interface SmartBIAlert {
  id: string;
  level: AlertLevel;
  category: string;
  title: string;
  message: string;
  suggestion?: string;
  createdAt?: string;
}

// ==================== 建议 ====================

/**
 * 建议
 */
export interface Recommendation {
  id: string;
  type: string;
  title: string;
  description: string;
  priority: number;
  actionItems: string[];
}

// ==================== 仪表盘响应 ====================

/**
 * 经营驾驶舱 KPI 数据
 */
export interface ExecutiveKPIData {
  sales: number;
  salesChange: number;
  orders: number;
  ordersChange: number;
  completionRate: number;
  completionRateChange: number;
  profit: number;
  profitChange: number;
}

/**
 * 经营驾驶舱排行项
 */
export interface ExecutiveRankingItem {
  rank: number;
  name: string;
  value: number;
  change: number;
}

/**
 * 经营驾驶舱 AI 洞察
 */
export interface ExecutiveAIInsight {
  id: string;
  type: 'opportunity' | 'warning' | 'trend' | 'anomaly';
  title: string;
  description: string;
  priority: 'high' | 'medium' | 'low';
}

/**
 * 快捷问题
 */
export interface QuickQuestion {
  id: string;
  text: string;
}

/**
 * 经营驾驶舱数据（前端使用）
 */
export interface ExecutiveDashboardData {
  kpi: ExecutiveKPIData;
  departmentRanking: ExecutiveRankingItem[];
  regionRanking: ExecutiveRankingItem[];
  aiInsights: ExecutiveAIInsight[];
  quickQuestions: QuickQuestion[];
  trendData: Array<{ date: string; value: number }>;
}

/**
 * 经营驾驶舱响应（后端返回格式）
 */
export interface DashboardResponse {
  period: string;
  startDate: string;
  endDate: string;
  kpiCards: KPICard[];
  rankings: Record<string, RankingItem[]>;
  charts: Record<string, ChartConfig>;
  aiInsights: AIInsight[];
  alerts: SmartBIAlert[];
  recommendations: Recommendation[];
}

// ==================== 自然语言查询 ====================

/**
 * 自然语言查询请求
 */
export interface NLQueryRequest {
  query: string;
  sessionId?: string;
  context?: Record<string, unknown>;
  factoryId?: string;
}

/**
 * 自然语言查询响应中的图表数据（用于前端渲染）
 */
export interface NLQueryChartData {
  type: 'bar' | 'line' | 'pie';
  title: string;
  data: Array<{ label: string; value: number }>;
}

/**
 * 自然语言查询响应
 */
export interface NLQueryResponse {
  intent: string;
  confidence: number;
  responseText: string;
  data?: unknown;
  chartConfig?: ChartConfig;
  /** 简化的图表数据（前端直接使用） */
  chartData?: NLQueryChartData;
  suggestions?: string[];
}

// ==================== 数据下钻 ====================

/**
 * 数据下钻请求
 */
export interface DrillDownRequest {
  dimension: string;
  filterValue: string;
  parentDimension?: string;
  parentValue?: string;
  startDate?: string;
  endDate?: string;
}

/**
 * 数据下钻响应
 */
export interface DrillDownResponse {
  dimension: string;
  data: RankingItem[];
  chartConfig?: ChartConfig;
  breadcrumb: DrillDownBreadcrumb[];
}

/**
 * 下钻面包屑
 */
export interface DrillDownBreadcrumb {
  dimension: string;
  value: string;
  label: string;
}

// ==================== 激励方案 ====================

/**
 * 激励方案
 */
export interface IncentivePlan {
  targetType: string;
  targetId: string;
  targetName: string;
  currentPerformance: PerformanceMetric[];
  suggestions: IncentiveSuggestion[];
}

/**
 * 绩效指标
 */
export interface PerformanceMetric {
  key: string;
  name: string;
  value: number;
  target: number;
  unit?: string;
  achievement: number;
}

/**
 * 激励建议
 */
export interface IncentiveSuggestion {
  type: string;
  title: string;
  description: string;
  expectedImpact: string;
}

// ==================== Excel 上传 ====================

/**
 * Excel 数据类型
 */
export type ExcelDataType = 'sales' | 'finance' | 'department';

/**
 * Excel 上传请求参数
 */
export interface ExcelUploadRequest {
  file: {
    uri: string;
    name: string;
    size?: number;
    mimeType?: string;
  };
  dataType: ExcelDataType;
  factoryId?: string;
}

/**
 * Excel 上传响应
 */
export interface ExcelUploadResponse {
  success: boolean;
  recordsProcessed: number;
  recordsFailed: number;
  errors?: ExcelUploadError[];
}

/**
 * Excel 上传错误
 */
export interface ExcelUploadError {
  row: number;
  column?: string;
  message: string;
}

/**
 * Excel 上传并分析请求参数
 */
export interface ExcelUploadAndAnalyzeRequest {
  file: {
    uri: string;
    name: string;
    size?: number;
    mimeType?: string;
  };
  dataType?: string;
  sheetIndex?: number;
  headerRow?: number;
  autoConfirm?: boolean;
  factoryId?: string;
}

/**
 * 模板图表配置 (用于 Excel 分析结果)
 */
export interface TemplateChartConfig {
  chartType: string;
  templateCode: string;
  title: string;
  options: Record<string, unknown>;
  dataMapping?: Record<string, unknown>;
  data?: Record<string, unknown>;
  layout?: Record<string, unknown>;
  aiAnalysis?: string;
  analysisType?: string;
}

/**
 * 字段映射结果
 */
export interface FieldMappingResult {
  originalColumn: string;
  columnIndex: number;
  standardField: string;
  standardFieldLabel: string;
  dataType: string;
  confidence: number;
  mappingSource: string;
  requiresConfirmation: boolean;
}

/**
 * Excel 上传并分析响应
 */
export interface ExcelUploadAndAnalyzeResponse {
  success: boolean;
  message: string;
  requiresConfirmation: boolean;
  detectedDataType: string;
  recommendedChartType: string;
  uploadId?: number;
  chartConfig?: TemplateChartConfig;
  aiAnalysis?: string;
  recommendedTemplates?: Array<{
    templateCode: string;
    name: string;
    description: string;
  }>;
  parseResult?: {
    headers: string[];
    rowCount: number;
    columnCount: number;
    fieldMappings: FieldMappingResult[];
  };
}

// ==================== 销售分析 ====================

/**
 * 销售 KPI 数据
 */
export interface SalesKPI {
  totalSales: number;
  totalOrders: number;
  avgOrderValue: number;
  conversionRate: number;
  salesChange: number;
  ordersChange: number;
  avgOrderValueChange: number;
  conversionRateChange: number;
}

/**
 * 销售排行项
 */
export interface SalesRankingItem {
  id: string;
  rank: number;
  name: string;
  sales: number;
  orders: number;
  change: number;
  avatar?: string;
}

/**
 * 销售趋势点
 */
export interface SalesTrendPoint {
  date: string;
  sales: number;
  orders: number;
}

/**
 * 销售分布项
 */
export interface SalesDistributionItem {
  id: string;
  name: string;
  value: number;
  percentage: number;
}

/**
 * 销售分析响应（前端使用）
 */
export interface SalesAnalysisResponse {
  kpi: SalesKPI;
  ranking: SalesRankingItem[];
  trends: SalesTrendPoint[];
  distribution: SalesDistributionItem[];
}

/**
 * 销售概要（后端原始格式）
 */
export interface SalesSummary {
  totalRevenue: number;
  totalOrders: number;
  avgOrderValue: number;
  revenueGrowth: number;
  orderGrowth: number;
}

/**
 * 销售趋势（后端原始格式）
 */
export interface SalesTrend {
  date: string;
  revenue: number;
  orders: number;
}

/**
 * 销售明细（后端原始格式）
 */
export interface SalesBreakdown {
  category: string;
  revenue: number;
  orders: number;
  percentage: number;
}

// ==================== 部门分析 ====================

/**
 * 部门分析响应
 */
export interface DepartmentAnalysisResponse {
  period: string;
  departments: DepartmentMetric[];
  comparison: DepartmentComparison[];
}

/**
 * 部门指标
 */
export interface DepartmentMetric {
  id: string;
  name: string;
  revenue: number;
  cost: number;
  profit: number;
  profitMargin: number;
  headcount: number;
  productivity: number;
}

/**
 * 部门对比
 */
export interface DepartmentComparison {
  metric: string;
  departments: Record<string, number>;
}

// ==================== 区域分析 ====================

/**
 * 区域分析响应
 */
export interface RegionAnalysisResponse {
  period: string;
  regions: RegionMetric[];
  heatmapData: HeatmapDataPoint[];
}

/**
 * 区域指标
 */
export interface RegionMetric {
  id: string;
  name: string;
  revenue: number;
  orders: number;
  customers: number;
  growth: number;
}

/**
 * 热力图数据点
 */
export interface HeatmapDataPoint {
  region: string;
  latitude?: number;
  longitude?: number;
  value: number;
}

// ==================== 财务分析 ====================

/**
 * 财务 KPI 数据
 */
export interface FinanceKPI {
  revenue: number;
  cost: number;
  grossProfit: number;
  netProfit: number;
  grossMargin: number;
  netMargin: number;
  revenueChange: number;
  costChange: number;
  profitChange: number;
}

/**
 * 成本明细项
 */
export interface CostBreakdownItem {
  id: string;
  category: string;
  amount: number;
  percentage: number;
  change: number;
}

/**
 * 财务趋势点
 */
export interface FinanceTrendPoint {
  date: string;
  revenue: number;
  cost: number;
  profit: number;
}

/**
 * 财务预警
 */
export interface FinanceAlert {
  id: string;
  level: 'warning' | 'danger' | 'info';
  title: string;
  message: string;
  amount?: number;
  suggestion?: string;
}

/**
 * 财务分析响应（前端使用）
 */
export interface FinanceAnalysisResponse {
  kpi: FinanceKPI;
  costBreakdown: CostBreakdownItem[];
  trends: FinanceTrendPoint[];
  alerts: FinanceAlert[];
}

/**
 * 财务概要（后端原始格式）
 */
export interface FinanceSummary {
  totalRevenue: number;
  totalCost: number;
  grossProfit: number;
  netProfit: number;
  grossMargin: number;
  netMargin: number;
}

/**
 * 利润表项目（后端原始格式）
 */
export interface IncomeStatementItem {
  category: string;
  amount: number;
  percentage: number;
  change: number;
}

/**
 * 现金流项目
 */
export interface CashFlowItem {
  category: string;
  inflow: number;
  outflow: number;
  net: number;
}

/**
 * 财务比率
 */
export interface FinancialRatio {
  name: string;
  value: number;
  benchmark?: number;
  status: 'good' | 'warning' | 'critical';
  trend: 'up' | 'down' | 'flat';
}

// ==================== 生产分析 ====================

/**
 * OEE 指标数据
 */
export interface OEEMetrics {
  oee: number;
  availability: number;
  performance: number;
  quality: number;
  target: number;
}

/**
 * 生产 KPI 数据
 */
export interface ProductionKPI {
  totalOutput: number;
  outputChange: number;
  downtimeHours: number;
  downtimeChange: number;
  equipmentUtilization: number;
  utilizationChange: number;
  oee: number;
  oeeChange: number;
}

/**
 * 产线排行项
 */
export interface ProductionLineRanking {
  id: string;
  rank: number;
  name: string;
  oee: number;
  output: number;
  status: 'green' | 'yellow' | 'red';
}

// ==================== 质量分析 ====================

/**
 * 质量 KPI 数据
 */
export interface QualityKPI {
  fpy: number;
  fpyChange: number;
  defectRate: number;
  defectRateChange: number;
  reworkCost: number;
  reworkCostChange: number;
  scrapCost: number;
  scrapCostChange: number;
}

/**
 * 缺陷项（帕累托分析）
 */
export interface DefectItem {
  type: string;
  count: number;
  percentage: number;
  cumulative: number;
}

/**
 * 质量等级分布
 */
export interface QualityGradeDistribution {
  grade: 'A' | 'B' | 'C' | 'D';
  count: number;
  percentage: number;
}

// ==================== 库存分析 ====================

/**
 * 库存健康度 KPI
 */
export interface InventoryHealthKPI {
  inventoryValue: number;
  valueChange: number;
  turnoverRate: number;
  turnoverChange: number;
  inventoryDays: number;
  daysChange: number;
  expiryRiskRate: number;
  riskChange: number;
  lossRate: number;
  lossChange: number;
}

/**
 * 库存账龄分布项
 */
export interface InventoryAgingItem {
  range: string;  // "0-30天", "31-60天", etc.
  value: number;
  percentage: number;
}

/**
 * 临期风险项
 */
export interface ExpiryRiskItem {
  id: string;
  batchNumber: string;
  materialName: string;
  quantity: number;
  unit: string;
  expiryDate: string;
  daysToExpiry: number;
  status: 'critical' | 'warning' | 'safe';
}

// ==================== 采购分析 ====================

/**
 * 采购 KPI 数据
 */
export interface ProcurementKPI {
  totalAmount: number;
  amountChange: number;
  batchCount: number;
  countChange: number;
  onTimeRate: number;
  onTimeChange: number;
  qualityPassRate: number;
  passRateChange: number;
}

/**
 * 供应商评估数据
 */
export interface SupplierEvaluation {
  supplierId: string;
  supplierName: string;
  dimensions: {
    price: number;
    quality: number;
    delivery: number;
    service: number;
    stability: number;
  };
  overallScore: number;
  rank: number;
}

/**
 * 采购品类分布
 */
export interface ProcurementCategory {
  category: string;
  amount: number;
  percentage: number;
  count: number;
}

// ==================== 销售漏斗 ====================

/**
 * 销售漏斗阶段
 */
export interface SalesFunnelStage {
  stage: string;
  count: number;
  value: number;
  percentage: number;
  conversionRate: number;
}

/**
 * 客户RFM分群
 */
export interface CustomerRFMSegment {
  segment: string;
  description: string;
  count: number;
  percentage: number;
  avgValue: number;
  strategy: string;
}

/**
 * 产品ABC分类项
 */
export interface ProductABCItem {
  category: 'A' | 'B' | 'C';
  productCount: number;
  revenue: number;
  revenuePercentage: number;
}

// ==================== 财务深度分析 ====================

/**
 * 现金流数据
 */
export interface CashFlowData {
  period: string;
  operating: number;
  investing: number;
  financing: number;
  netChange: number;
}

/**
 * 财务比率详情（含趋势）
 */
export interface FinancialRatioDetail {
  name: string;
  value: number;
  benchmark?: number;
  status: 'good' | 'warning' | 'critical';
  trend: 'up' | 'down' | 'flat';
  category: 'profitability' | 'liquidity' | 'efficiency' | 'leverage';
  description?: string;
}

// ==================== 人效分析 ====================

/**
 * 工人效率数据
 */
export interface WorkerEfficiencyData {
  workerId: string;
  workerName: string;
  department: string;
  efficiencyScore: number;
  qualityRate: number;
  totalOutput: number;
}

/**
 * 效率汇总数据
 */
export interface EfficiencySummary {
  totalWorkers: number;
  avgEfficiency: number;
  avgQualityRate: number;
  totalOutput: number;
  topPerformers: WorkerEfficiencyData[];
  bottomPerformers: WorkerEfficiencyData[];
}

// ==================== 多Sheet分析 ====================

/**
 * Excel 上传记录（数据集）
 */
export interface ExcelUploadRecord {
  id: number;
  fileName: string;
  fileSize: number;
  sheetCount: number;
  rowCount: number;
  columnCount: number;
  status: 'pending' | 'parsed' | 'analyzed' | 'error';
  factoryId: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * 索引页到报表的映射
 */
export interface IndexSheetMapping {
  index: number;
  sheetName: string;
  reportName: string;
  description: string;
}

/**
 * 索引页元数据
 */
export interface IndexMetadata {
  hasIndex: boolean;
  indexSheetIndex: number;
  indexSheetName: string;
  sheetMappings: IndexSheetMapping[];
}

/**
 * 可选维度/度量
 */
export interface AlternativeDimension {
  fieldName: string;
  displayName: string;
  selected: boolean;
  semanticType?: 'dimension' | 'measure' | 'time';
}

/**
 * 图表系列数据项
 */
export interface ChartSeriesDataItem {
  name: string;
  value: number;
}

/**
 * 图表系列
 */
export interface ChartSeries {
  name?: string;
  data: ChartSeriesDataItem[];
}

/**
 * 图表轴配置
 */
export interface ChartAxisConfig {
  field: string;
  label?: string;
}

/**
 * 动态图表配置
 */
export interface DynamicChartConfig {
  id: string;
  chartType: ChartType;
  title: string;
  subTitle?: string;
  config: Record<string, unknown>;
  dimensions: string[];
  measures: string[];
  currentDimension?: string;
  currentMeasure?: string;
  data?: Array<Record<string, unknown>>;
  series?: ChartSeries[];
  rawData?: Array<Record<string, unknown>>;
  xAxis?: ChartAxisConfig;
  yAxis?: ChartAxisConfig[];
  alternativeXAxis: AlternativeDimension[];
  alternativeMeasures: AlternativeDimension[];
}

/**
 * 指标卡片数据
 */
export interface MetricCardData {
  id: string;
  label: string;
  value: number | string;
  format?: 'number' | 'currency' | 'percent';
  change?: number;
  changeType?: 'positive' | 'negative' | 'neutral';
  unit?: string;
}

/**
 * 场景信息
 */
export interface ScenarioInfo {
  scenarioType: string;
  confidence?: number;
  evidence?: string[];
  /** 编制说明 - 来自索引页的报表描述 */
  description?: string;
  /** 是否有来自索引页的上下文信息 */
  hasIndexContext?: boolean;
}

/**
 * 单个Sheet分析结果
 */
export interface SheetAnalysisResult {
  sheetIndex: number;
  sheetName: string;
  tableType?: 'index' | 'data' | 'summary' | 'metadata' | string;
  rowCount: number;
  columnCount: number;
  charts: DynamicChartConfig[];
  metrics: MetricCardData[];
  insights: AIInsight[];
  fromCache: boolean;
  scenario: ScenarioInfo;
  processingTimeMs: number;
  error?: string;
}

/**
 * 多Sheet分析结果
 */
export interface MultiSheetAnalysisResult {
  success: boolean;
  totalSheets: number;
  successCount: number;
  failedCount: number;
  skippedCount: number;
  cacheHitCount: number;
  sheetResults: SheetAnalysisResult[];
  indexMetadata?: IndexMetadata;
  processingTimeMs: number;
  error?: string;
}
