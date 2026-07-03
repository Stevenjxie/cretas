/**
 * SmartBI API Client
 * 智能商业分析 API 调用
 *
 * 注意：所有方法都需要 factoryId
 * - 工厂用户：自动从登录信息获取
 * - 平台管理员：必须显式提供 factoryId 参数
 */

import { apiClient } from './apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';
import type {
  AnalysisParams,
  DashboardResponse,
  ExecutiveDashboardData,
  NLQueryRequest,
  NLQueryResponse,
  DrillDownRequest,
  DrillDownResponse,
  SmartBIAlert,
  Recommendation,
  IncentivePlan,
  ExcelUploadRequest,
  ExcelUploadResponse,
  ExcelUploadAndAnalyzeRequest,
  ExcelUploadAndAnalyzeResponse,
  SalesAnalysisResponse,
  DepartmentAnalysisResponse,
  RegionAnalysisResponse,
  FinanceAnalysisResponse,
  ExcelUploadRecord,
  MultiSheetAnalysisResult,
  DynamicChartConfig,
} from '../../types/smartbi';

/**
 * 标准 API 响应格式
 */
interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
}

/**
 * SmartBI API Client
 */
export const smartBIApi = {
  // ==================== Excel 上传 ====================

  /**
   * 上传 Excel 文件
   * @param request - 上传请求参数（包含文件、数据类型、factoryId）
   */
  uploadExcel: async (
    request: ExcelUploadRequest
  ): Promise<ApiResponse<ExcelUploadResponse>> => {
    const currentFactoryId = requireFactoryId(request.factoryId);

    // 创建 FormData
    const formData = new FormData();
    formData.append('file', {
      uri: request.file.uri,
      name: request.file.name,
      type: request.file.mimeType || 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    } as unknown as Blob);
    formData.append('dataType', request.dataType);

    return apiClient.post<ApiResponse<ExcelUploadResponse>>(
      `/api/mobile/${currentFactoryId}/smart-bi/upload`,
      formData,
      {
        headers: { 'Content-Type': 'multipart/form-data' },
      }
    );
  },

  /**
   * 上传 Excel 文件并自动分析生成图表
   * @param request - 上传请求参数
   * @returns 包含图表配置和 AI 分析的响应
   */
  uploadAndAnalyze: async (
    request: ExcelUploadAndAnalyzeRequest
  ): Promise<ApiResponse<ExcelUploadAndAnalyzeResponse>> => {
    const currentFactoryId = requireFactoryId(request.factoryId);

    // 创建 FormData
    const formData = new FormData();
    formData.append('file', {
      uri: request.file.uri,
      name: request.file.name,
      type: request.file.mimeType || 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    } as unknown as Blob);

    // 添加可选参数
    if (request.dataType) {
      formData.append('dataType', request.dataType);
    }
    if (request.sheetIndex !== undefined) {
      formData.append('sheetIndex', String(request.sheetIndex));
    }
    if (request.headerRow !== undefined) {
      formData.append('headerRow', String(request.headerRow));
    }
    // 默认启用 autoConfirm
    formData.append('autoConfirm', String(request.autoConfirm ?? true));

    return apiClient.post<ApiResponse<ExcelUploadAndAnalyzeResponse>>(
      `/api/mobile/${currentFactoryId}/smart-bi/upload-and-analyze`,
      formData,
      {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 180000, // 3 分钟超时（AI 分析可能需要较长时间）
      }
    );
  },

  // ==================== 经营驾驶舱 ====================

  /**
   * 时间周期类型（前端使用）
   */
  // 'today' 会映射到 'day' 发送给后端

  /**
   * 获取经营驾驶舱数据
   * @param period - 时间周期: today, day, week, month, quarter, year
   * @param factoryId - 工厂ID（可选，工厂用户自动获取；平台管理员必须提供）
   */
  getExecutiveDashboard: async (
    period: 'today' | 'day' | 'week' | 'month' | 'quarter' | 'year' = 'month',
    factoryId?: string
  ): Promise<ApiResponse<ExecutiveDashboardData>> => {
    const currentFactoryId = requireFactoryId(factoryId);
    // 'today' 映射为 'day'
    const apiPeriod = period === 'today' ? 'day' : period;
    return apiClient.get<ApiResponse<ExecutiveDashboardData>>(
      `/api/mobile/${currentFactoryId}/smart-bi/dashboard/executive`,
      {
        params: { period: apiPeriod },
      }
    );
  },

  // ==================== 销售分析 ====================

  /**
   * 获取销售分析数据
   * @param params - 分析参数（可包含 factoryId）
   */
  getSalesAnalysis: async (
    params: AnalysisParams
  ): Promise<ApiResponse<SalesAnalysisResponse>> => {
    const { factoryId, ...queryParams } = params;
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<SalesAnalysisResponse>>(
      `/api/mobile/${currentFactoryId}/smart-bi/analysis/sales`,
      { params: queryParams }
    );
  },

  // ==================== 部门分析 ====================

  /**
   * 获取部门分析数据
   * @param params - 分析参数（可包含 factoryId）
   */
  getDepartmentAnalysis: async (
    params: AnalysisParams
  ): Promise<ApiResponse<DepartmentAnalysisResponse>> => {
    const { factoryId, ...queryParams } = params;
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<DepartmentAnalysisResponse>>(
      `/api/mobile/${currentFactoryId}/smart-bi/analysis/department`,
      { params: queryParams }
    );
  },

  // ==================== 区域分析 ====================

  /**
   * 获取区域分析数据
   * @param params - 分析参数（可包含 factoryId）
   */
  getRegionAnalysis: async (
    params: AnalysisParams
  ): Promise<ApiResponse<RegionAnalysisResponse>> => {
    const { factoryId, ...queryParams } = params;
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<RegionAnalysisResponse>>(
      `/api/mobile/${currentFactoryId}/smart-bi/analysis/region`,
      { params: queryParams }
    );
  },

  // ==================== 财务分析 ====================

  /**
   * 获取财务分析数据
   * @param params - 分析参数（可包含 factoryId、analysisType）
   */
  getFinanceAnalysis: async (
    params: AnalysisParams
  ): Promise<ApiResponse<FinanceAnalysisResponse>> => {
    const { factoryId, ...queryParams } = params;
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<FinanceAnalysisResponse>>(
      `/api/mobile/${currentFactoryId}/smart-bi/analysis/finance`,
      { params: queryParams }
    );
  },

  // ==================== 自然语言问答 ====================

  /**
   * 自然语言查询
   * @param request - 查询请求（可包含 factoryId）
   */
  query: async (
    request: NLQueryRequest
  ): Promise<ApiResponse<NLQueryResponse>> => {
    const { factoryId, ...queryBody } = request;
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.post<ApiResponse<NLQueryResponse>>(
      `/api/mobile/${currentFactoryId}/smart-bi/query`,
      queryBody
    );
  },

  // ==================== 数据下钻 ====================

  /**
   * 数据下钻
   * @param request - 下钻请求
   * @param factoryId - 工厂ID（可选，工厂用户自动获取；平台管理员必须提供）
   */
  drillDown: async (
    request: DrillDownRequest,
    factoryId?: string
  ): Promise<ApiResponse<DrillDownResponse>> => {
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.post<ApiResponse<DrillDownResponse>>(
      `/api/mobile/${currentFactoryId}/smart-bi/drill-down`,
      request
    );
  },

  // ==================== 预警管理 ====================

  /**
   * 获取预警列表
   * @param category - 预警类别（可选）
   * @param factoryId - 工厂ID（可选，工厂用户自动获取；平台管理员必须提供）
   */
  getAlerts: async (
    category?: string,
    factoryId?: string
  ): Promise<ApiResponse<SmartBIAlert[]>> => {
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<SmartBIAlert[]>>(
      `/api/mobile/${currentFactoryId}/smart-bi/alerts`,
      {
        params: category ? { category } : undefined,
      }
    );
  },

  // ==================== 建议管理 ====================

  /**
   * 获取建议列表
   * @param analysisType - 分析类型（可选）
   * @param factoryId - 工厂ID（可选，工厂用户自动获取；平台管理员必须提供）
   */
  getRecommendations: async (
    analysisType?: string,
    factoryId?: string
  ): Promise<ApiResponse<Recommendation[]>> => {
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<Recommendation[]>>(
      `/api/mobile/${currentFactoryId}/smart-bi/recommendations`,
      {
        params: analysisType ? { analysisType } : undefined,
      }
    );
  },

  // ==================== 激励方案 ====================

  /**
   * 获取激励方案
   * @param targetType - 目标类型: department, employee, region
   * @param targetId - 目标ID
   * @param factoryId - 工厂ID（可选，工厂用户自动获取；平台管理员必须提供）
   */
  getIncentivePlan: async (
    targetType: 'department' | 'employee' | 'region',
    targetId: string,
    factoryId?: string
  ): Promise<ApiResponse<IncentivePlan>> => {
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<IncentivePlan>>(
      `/api/mobile/${currentFactoryId}/smart-bi/incentive-plan/${targetType}/${targetId}`
    );
  },

  // ==================== 多Sheet分析 ====================

  /**
   * 获取数据集列表（已上传的 Excel 文件）
   * @param factoryId - 工厂ID
   */
  getDatasets: async (
    factoryId?: string
  ): Promise<ApiResponse<ExcelUploadRecord[]>> => {
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<ExcelUploadRecord[]>>(
      `/api/mobile/${currentFactoryId}/smart-bi/datasets`
    );
  },

  /**
   * 分析所有 Sheet（多Sheet并行分析）
   * @param uploadId - 上传记录ID
   * @param factoryId - 工厂ID
   */
  analyzeAllSheets: async (
    uploadId: number,
    factoryId?: string
  ): Promise<ApiResponse<MultiSheetAnalysisResult>> => {
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.post<ApiResponse<MultiSheetAnalysisResult>>(
      `/api/mobile/${currentFactoryId}/smart-bi/analyze-all-sheets/${uploadId}`
    );
  },

  /**
   * 切换图表维度
   * @param uploadId - 上传记录ID
   * @param sheetIndex - Sheet索引
   * @param chartIndex - 图表索引
   * @param dimension - 新维度
   * @param factoryId - 工厂ID
   */
  switchChartDimension: async (
    uploadId: number,
    sheetIndex: number,
    chartIndex: number,
    dimension: string,
    factoryId?: string
  ): Promise<ApiResponse<DynamicChartConfig>> => {
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.post<ApiResponse<DynamicChartConfig>>(
      `/api/mobile/${currentFactoryId}/smart-bi/switch-dimension`,
      {
        uploadId,
        sheetIndex,
        chartIndex,
        dimension,
      }
    );
  },

  // ==================== 人效分析 ====================

  /**
   * 获取人效汇总数据
   * @param period - 时间周期: day, week, month
   * @param factoryId - 工厂ID
   */
  async getEfficiencySummary(period: string, factoryId?: string): Promise<ApiResponse<any>> {
    const { getEfficiencyDashboard } = await import('./productionAnalyticsApiClient');
    return getEfficiencyDashboard({ startDate: period }, factoryId);
  },

  /**
   * 获取工人效率排名
   * @param date - 日期
   * @param limit - 返回条数
   * @param factoryId - 工厂ID
   */
  async getEfficiencyRanking(date: string, limit: number, factoryId?: string): Promise<ApiResponse<any>> {
    const { getWorkerRanking } = await import('./productionAnalyticsApiClient');
    return getWorkerRanking({ startDate: date }, factoryId);
  },

  // ==================== 现金流分析 ====================
  getCashFlowAnalysis: async (
    params: AnalysisParams
  ): Promise<ApiResponse<any>> => {
    const { factoryId, ...queryParams } = params;
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<any>>(
      `/api/mobile/${currentFactoryId}/smart-bi/analysis/finance`,
      { params: { ...queryParams, analysisType: 'cashflow' } }
    );
  },

  // ==================== 财务比率分析 ====================
  getFinancialRatios: async (
    params: AnalysisParams
  ): Promise<ApiResponse<any>> => {
    const { factoryId, ...queryParams } = params;
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<any>>(
      `/api/mobile/${currentFactoryId}/smart-bi/analysis/finance`,
      { params: { ...queryParams, analysisType: 'ratios' } }
    );
  },

  // ==================== 客户RFM分析 ====================
  getRFMAnalysis: async (
    factoryId?: string
  ): Promise<ApiResponse<any>> => {
    const currentFactoryId = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<any>>(
      `/api/mobile/${currentFactoryId}/smart-bi/analysis/finance`,
      { params: { analysisType: 'rfm' } }
    );
  },
};

// 别名导出，兼容旧代码
export const smartBIApiClient = smartBIApi;

export default smartBIApi;
