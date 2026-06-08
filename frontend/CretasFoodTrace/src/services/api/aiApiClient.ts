import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';
import type { IntentRecognizeResponse, IntentExecuteResponse } from '../../types/intent';
import { API_BASE_URL } from '../../constants/config';
import EventSource from 'react-native-sse';
import { StorageService } from '../storage/storageService';

/**
 * AI API客户端
 *
 * 统一的AI接口调用，对应后端 AIController
 * 基础路径: /api/mobile/{factoryId}/ai
 *
 * @version 2.0.0
 * @since 2025-11-04
 */

// ============ 类型定义 ============

/**
 * AI批次成本分析请求
 */
export interface BatchCostAnalysisRequest {
  batchId: number;
  question?: string;
  sessionId?: string;
  analysisType?: 'default' | 'deep' | 'comparison';
  /**
   * 是否启用思考模式（默认true）
   * 思考模式下，AI会先进行深度推理再给出答案
   */
  enableThinking?: boolean;
  /**
   * 思考预算（10-100，默认50）
   * 数值越大，思考越深入
   */
  thinkingBudget?: number;
}

/**
 * AI时间范围成本分析请求
 */
export interface TimeRangeCostAnalysisRequest {
  startDate: string; // ISO 8601 format
  endDate: string;   // ISO 8601 format
  dimension?: 'overall' | 'daily' | 'weekly';
  question?: string;
  /** 是否启用思考模式（默认true） */
  enableThinking?: boolean;
  /** 思考预算（10-100，默认50） */
  thinkingBudget?: number;
}

/**
 * SSE 流式响应事件类型
 */
export type SSEEventType = 'start' | 'progress' | 'thinking' | 'answer' | 'complete' | 'error';

/**
 * SSE 流式响应事件数据
 */
export interface SSEEventData {
  type: SSEEventType;
  message?: string;
  content?: string;
  analysis?: string;
  sessionId?: string;
  responseTimeMs?: number;
}

/**
 * SSE 流式响应回调函数
 */
export interface SSECallbacks {
  onStart?: () => void;
  onProgress?: (message: string) => void;
  onThinking?: (content: string) => void;
  onAnswer?: (content: string) => void;
  onComplete?: (data: { analysis: string; sessionId?: string; responseTimeMs?: number }) => void;
  onError?: (message: string) => void;
}

/**
 * 意图执行 SSE 事件类型
 */
export type IntentSSEEventType =
  | 'start'
  | 'cache_hit'
  | 'cache_miss'
  | 'intent_recognized'
  | 'executing'
  | 'result'
  | 'complete'
  | 'error';

/**
 * 意图执行 SSE 事件数据
 */
export interface IntentSSEEventData {
  type: IntentSSEEventType;
  message?: string;
  latencyMs?: number;
  intentCode?: string;
  intentName?: string;
  confidence?: number;
  cacheType?: 'EXACT' | 'SEMANTIC';
  status?: string;
  /** 执行结果 (JSON 对象) */
  result?: Record<string, unknown>;
  cacheHit?: boolean;
}

/**
 * 意图执行 SSE 回调函数
 */
export interface IntentSSECallbacks {
  /** 开始处理 */
  onStart?: (message: string) => void;
  /** 缓存命中 (可跳过意图识别) */
  onCacheHit?: (data: { latencyMs: number; cacheType: 'EXACT' | 'SEMANTIC' }) => void;
  /** 缓存未命中 */
  onCacheMiss?: (latencyMs: number) => void;
  /** 流式进度（阶段信息） */
  onProgress?: (data: { stage: string; message: string }) => void;
  /** 意图识别完成 */
  onIntentRecognized?: (data: { intentCode: string; intentName: string; confidence: number }) => void;
  /** 开始执行意图 */
  onExecuting?: (intentName: string) => void;
  /** 流式文本 token（D3 对话/咨询直接流式输出） */
  onToken?: (token: string) => void;
  /** 模型元数据（D3 流式模式信息） */
  onMeta?: (data: { model: string; thinking: boolean; questionType: string }) => void;
  /** 执行结果 */
  onResult?: (result: Record<string, unknown>) => void;
  /** 完成 */
  onComplete?: (data: { status: string; cacheHit: boolean; fullContent?: string; totalLatencyMs?: number }) => void;
  /** 错误 */
  onError?: (message: string) => void;
}

/**
 * AI批次对比分析请求
 */
export interface ComparativeCostAnalysisRequest {
  batchIds: number[];
  dimension?: 'cost' | 'efficiency' | 'quality' | 'comprehensive';
  question?: string;
  /** 是否启用思考模式（默认true） */
  enableThinking?: boolean;
  /** 思考预算（10-100，默认50） */
  thinkingBudget?: number;
}

/**
 * AI对话请求
 */
export interface ConversationRequest {
  sessionId: string;
  message: string;
  contextBatchId?: number;
}

/**
 * AI报告生成请求
 */
export interface ReportGenerationRequest {
  reportType: 'batch' | 'weekly' | 'monthly' | 'custom';
  batchId?: number;
  startDate?: string;
  endDate?: string;
  title?: string;
  dimensions?: string[];
}

/**
 * 通用API响应包装格式
 */
interface ApiResponseWrapper<T> {
  success: boolean;
  code: number;
  message: string;
  data: T;
  timestamp?: string;
}

/**
 * AI成本分析响应 - 后端原始格式
 */
interface AICostAnalysisResponseBackend {
  success: boolean;
  analysis: string;
  session_id?: string;
  messageCount?: number;
  quota?: AIQuotaInfoBackend;
  cacheHit?: boolean;
  processingTimeMs?: number;  // 后端使用 processingTimeMs
  errorMessage?: string;
  generatedAt?: string;
  expiresAt?: string;
}

/**
 * AI成本分析响应 - 前端使用格式
 */
export interface AICostAnalysisResponse {
  success: boolean;
  analysis: string;
  session_id?: string;
  messageCount?: number;
  quota?: AIQuotaInfo;
  cacheHit?: boolean;
  responseTimeMs?: number;  // 前端使用 responseTimeMs
  errorMessage?: string;
  generatedAt?: string;
  expiresAt?: string;
}

/**
 * AI配额信息 - 后端实际返回格式
 */
export interface AIQuotaInfoBackend {
  total: number;
  used: number;
  remaining: number;
  usageRate: number;
  resetDate: string;
  exceeded: boolean;
}

/**
 * AI配额信息 - 前端使用格式
 */
export interface AIQuotaInfo {
  weeklyQuota: number;
  usedQuota: number;
  remainingQuota: number;
  resetDate: string;
  usagePercentage: number;
  status: 'active' | 'warning' | 'exhausted' | 'expired';
}

/**
 * 转换后端配额响应为前端格式
 */
function transformQuotaResponse(backendQuota: AIQuotaInfoBackend | null | undefined): AIQuotaInfo | undefined {
  if (!backendQuota) return undefined;

  // 计算状态
  let status: AIQuotaInfo['status'] = 'active';
  if (backendQuota.exceeded) {
    status = 'exhausted';
  } else if (backendQuota.usageRate > 80) {
    status = 'warning';
  }

  return {
    weeklyQuota: backendQuota.total ?? 100,
    usedQuota: backendQuota.used ?? 0,
    remainingQuota: backendQuota.remaining ?? 100,
    resetDate: backendQuota.resetDate ?? '',
    usagePercentage: backendQuota.usageRate ?? 0,
    status,
  };
}

/**
 * 转换后端分析响应为前端格式
 * 加强空值处理，防止后端响应格式不符时崩溃
 */
function transformAnalysisResponse(backendResponse: AICostAnalysisResponseBackend | null | undefined): AICostAnalysisResponse {
  // 空值保护
  if (!backendResponse) {
    return {
      success: false,
      analysis: '',
      errorMessage: '服务响应格式错误',
    };
  }

  return {
    success: backendResponse.success ?? false,
    analysis: backendResponse.analysis ?? '',
    session_id: backendResponse.session_id,
    messageCount: backendResponse.messageCount,
    quota: transformQuotaResponse(backendResponse.quota),
    cacheHit: backendResponse.cacheHit,
    responseTimeMs: backendResponse.processingTimeMs, // 字段名映射
    errorMessage: backendResponse.errorMessage,
    generatedAt: backendResponse.generatedAt,
    expiresAt: backendResponse.expiresAt,
  };
}

/**
 * AI对话响应
 */
export interface ConversationResponse {
  sessionId: string;
  messages: ConversationMessage[];
  createdAt: string;
  updatedAt: string;
  status: 'active' | 'closed';
  contextBatchId?: number;
}

/**
 * 多轮对话启动响应
 *
 * 对应后端 ConversationService.ConversationResponse
 */
export interface ConversationStartResponse {
  /** 对话状态: COMPLETED-意图已识别可执行, ACTIVE-对话进行中, CONVERSATION_CONTINUE-需要继续对话, NEED_CLARIFICATION-需要澄清 */
  status: 'COMPLETED' | 'ACTIVE' | 'CONVERSATION_CONTINUE' | 'NEED_CLARIFICATION' | 'FAILED';
  /** AI回复消息 */
  message: string;
  /** 识别到的意图代码 (当 status=COMPLETED 时) */
  intentCode?: string;
  /** 意图识别置信度 (0-1) */
  confidence?: number;
  /** 会话ID (用于继续对话) */
  sessionId?: string;
  /** 候选意图列表 */
  candidates?: Array<{
    intentCode: string;
    intentName: string;
    confidence: number;
    matchReason?: string;
  }>;
  /** 建议操作 (当需要澄清时) */
  suggestedActions?: Array<{
    actionName: string;
    actionCode: string;
    description: string;
    endpoint?: string;
  }>;
  /** 执行结果 (当 status=COMPLETED 并自动执行时) */
  executionResult?: Record<string, unknown>;
  /** 元数据 */
  metadata?: {
    conversationMessage?: string;
    sessionId?: string;
    [key: string]: unknown;
  };
}

/**
 * 对话消息
 */
export interface ConversationMessage {
  id: number;
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
  tokens?: number;
}

/**
 * AI报告列表响应
 */
export interface ReportListResponse {
  reports: ReportSummary[];
  total: number;
  page?: number;
  pageSize?: number;
}

/**
 * 报告摘要
 */
export interface ReportSummary {
  reportId: number;
  reportType: 'batch' | 'weekly' | 'monthly' | 'custom';
  title: string;
  createdAt: string;
  batchId?: number;
  batchNumber?: string;
  startDate?: string;
  endDate?: string;
  totalCost?: number;
  keyFindingsCount?: number;
  suggestionsCount?: number;
}

/**
 * AI健康检查响应
 */
export interface HealthCheckResponse {
  status: 'healthy' | 'degraded' | 'unavailable';
  llmAvailable: boolean;
  responseTime: number;
  lastCheckTime: string;
  errorMessage?: string;
}

// ============ API客户端类 ============

/**
 * AI API客户端
 */
class AIApiClient {
  /**
   * 获取基础路径
   * 对应后端 AIController
   */
  private getBasePath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/ai`;
  }

  /**
   * 获取意图接口基础路径
   * 对应后端 AIIntentConfigController
   */
  private getIntentBasePath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/ai-intents`;
  }

  /**
   * 获取对话接口基础路径
   * 对应后端 ConversationController
   */
  private getConversationBasePath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/conversation`;
  }

  // ========== 成本分析接口 ==========

  /**
   * AI批次成本分析
   *
   * 支持三种分析模式：
   * 1. 默认分析（无question）- 首次分析，消耗配额
   * 2. Follow-up对话（有question + sessionId）- 追问，少量消耗配额
   * 3. 历史综合报告（历史批次）- 深度分析，较多消耗配额
   *
   * @param request 批次成本分析请求
   * @param factoryId 工厂ID（可选）
   */
  async analyzeBatchCost(
    request: BatchCostAnalysisRequest,
    factoryId?: string
  ): Promise<AICostAnalysisResponse> {
    // API返回格式: { code, message, data: AICostAnalysisResponseBackend, ... }
    const response = await apiClient.post<ApiResponseWrapper<AICostAnalysisResponseBackend>>(
      `${this.getBasePath(factoryId)}/analysis/cost/batch`,
      request
    );
    // 安全提取数据：优先使用 response.data，兼容直接返回数据的情况
    const data = response?.data ?? (response as unknown as AICostAnalysisResponseBackend);
    return transformAnalysisResponse(data);
  }

  /**
   * AI时间范围成本分析
   *
   * 分析指定时间段内的成本数据
   *
   * @param request 时间范围分析请求
   * @param factoryId 工厂ID（可选）
   */
  async analyzeTimeRangeCost(
    request: TimeRangeCostAnalysisRequest,
    factoryId?: string
  ): Promise<AICostAnalysisResponse> {
    // API返回格式: { code, message, data: AICostAnalysisResponseBackend, ... }
    const response = await apiClient.post<ApiResponseWrapper<AICostAnalysisResponseBackend>>(
      `${this.getBasePath(factoryId)}/analysis/cost/time-range`,
      request
    );

    // DEBUG: 打印原始响应
    console.log('=== aiApiClient 原始响应 ===');
    console.log('response:', JSON.stringify(response, null, 2));
    console.log('response?.data:', JSON.stringify(response?.data, null, 2));

    // 安全提取数据：优先使用 response.data，兼容直接返回数据的情况
    const data = response?.data ?? (response as unknown as AICostAnalysisResponseBackend);
    console.log('data to transform:', JSON.stringify(data, null, 2));

    return transformAnalysisResponse(data);
  }

  /**
   * AI时间范围成本分析 - 流式响应版本 (SSE)
   *
   * 实时返回AI分析过程，包括思考过程和最终答案
   * 适用于需要实时展示分析进度的场景
   *
   * @param request 时间范围分析请求
   * @param callbacks SSE事件回调函数
   * @param factoryId 工厂ID（可选）
   * @returns Promise<void> - 流式处理完成后resolve
   */
  async analyzeTimeRangeCostStream(
    request: TimeRangeCostAnalysisRequest,
    callbacks: SSECallbacks,
    factoryId?: string
  ): Promise<void> {
    const url = `${this.getBasePath(factoryId)}/analysis/cost/time-range/stream`;

    // 获取 token（需要从 apiClient 获取或从 store 获取）
    const token = await this.getAuthToken();

    // 获取完整的 API 基础 URL
    const baseUrl = await this.getBaseUrl();
    const fullUrl = `${baseUrl}${url}`;

    console.log('=== SSE 流式请求开始 ===');
    console.log('URL:', fullUrl);
    console.log('Request:', JSON.stringify(request, null, 2));

    let fullAnalysis = '';
    let fullThinking = '';

    return new Promise<void>((resolve, reject) => {
      const es = new EventSource<'message' | 'error'>(fullUrl, {
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
          'Authorization': `Bearer ${token}`,
        },
        method: 'POST',
        body: JSON.stringify(request),
        pollingInterval: 0, // 禁用轮询，使用真正的SSE
      });

      es.addEventListener('message', (event) => {
        if (!event.data) return;

        try {
          const eventData: SSEEventData = JSON.parse(event.data);
          console.log('SSE Event:', eventData.type, eventData.message || eventData.content?.substring(0, 50));

          switch (eventData.type) {
            case 'start':
              callbacks.onStart?.();
              break;
            case 'progress':
              callbacks.onProgress?.(eventData.message || '处理中...');
              break;
            case 'thinking':
              if (eventData.content) {
                fullThinking += eventData.content;
                callbacks.onThinking?.(eventData.content);
              }
              break;
            case 'answer':
              if (eventData.content) {
                fullAnalysis += eventData.content;
                callbacks.onAnswer?.(eventData.content);
              }
              break;
            case 'complete':
              callbacks.onComplete?.({
                analysis: eventData.analysis || fullAnalysis,
                sessionId: eventData.sessionId,
                responseTimeMs: eventData.responseTimeMs,
              });
              es.close();
              resolve();
              break;
            case 'error':
              callbacks.onError?.(eventData.message || '分析失败');
              es.close();
              reject(new Error(eventData.message || '分析失败'));
              break;
          }
        } catch (parseError) {
          console.warn('SSE 事件解析失败:', event.data, parseError);
        }
      });

      es.addEventListener('error', (event) => {
        console.error('SSE 流式请求失败:', event);
        // Type guard for event.message since error event types vary
        const errorMessage = ('message' in event && typeof event.message === 'string')
          ? event.message
          : '流式请求失败';
        callbacks.onError?.(errorMessage);
        es.close();
        reject(new Error(errorMessage));
      });
    });
  }

  /**
   * 获取认证 token
   */
  private async getAuthToken(): Promise<string> {
    // 使用 StorageService 获取 token（跨平台兼容，web 用 AsyncStorage）
    try {
      const token = await StorageService.getSecureItem('secure_access_token');
      return token || '';
    } catch {
      console.warn('无法获取 token');
      return '';
    }
  }

  /**
   * 获取 API 基础 URL
   */
  private async getBaseUrl(): Promise<string> {
    // 使用统一配置的 API_BASE_URL
    return API_BASE_URL;
  }

  /**
   * AI批次对比分析
   *
   * 对比2-5个批次的成本、效率、质量等指标
   *
   * @param request 批次对比分析请求
   * @param factoryId 工厂ID（可选）
   */
  async compareBatchCosts(
    request: ComparativeCostAnalysisRequest,
    factoryId?: string
  ): Promise<AICostAnalysisResponse> {
    // API返回格式: { code, message, data: AICostAnalysisResponseBackend, ... }
    const response = await apiClient.post<ApiResponseWrapper<AICostAnalysisResponseBackend>>(
      `${this.getBasePath(factoryId)}/analysis/cost/compare`,
      request
    );
    // 安全提取数据：优先使用 response.data，兼容直接返回数据的情况
    const data = response?.data ?? (response as unknown as AICostAnalysisResponseBackend);
    return transformAnalysisResponse(data);
  }

  // ========== 配额管理接口 ==========

  /**
   * 查询AI配额信息
   *
   * 获取工厂的AI配额使用情况、剩余额度、使用记录等
   *
   * @param factoryId 工厂ID（可选）
   */
  async getQuotaInfo(factoryId?: string): Promise<AIQuotaInfo> {
    // API返回格式: { code, message, data: AIQuotaInfoBackend, ... }
    const response = await apiClient.get<ApiResponseWrapper<AIQuotaInfoBackend>>(
      `${this.getBasePath(factoryId)}/quota`
    );
    // 从响应包装中提取实际数据并转换为前端格式
    const transformed = transformQuotaResponse(response.data);
    if (!transformed) {
      throw new Error('无法获取配额信息');
    }
    return transformed;
  }

  /**
   * 更新AI配额（仅供平台管理员使用）
   *
   * @param newQuota 新配额金额
   * @param factoryId 工厂ID（可选）
   */
  async updateQuota(newQuota: number, factoryId?: string): Promise<void> {
    await apiClient.put(
      `${this.getBasePath(factoryId)}/quota`,
      null,
      {
        params: { newQuotaLimit: newQuota }
      }
    );
  }

  // ========== 对话管理接口 ==========

  // ========== 多轮对话（意图识别）接口 ==========

  /**
   * 启动多轮对话
   *
   * 使用 LLM 进行意图识别，比规则匹配更准确。
   * 当意图识别置信度高时返回 COMPLETED，否则进入多轮对话。
   *
   * @param userInput 用户输入
   * @param userId 用户ID（可选）
   * @param factoryId 工厂ID（可选）
   */
  async startConversation(
    userInput: string,
    userId?: number,
    factoryId?: string
  ): Promise<ConversationStartResponse> {
    const response = await apiClient.post<{ success: boolean; data: ConversationStartResponse }>(
      `${this.getConversationBasePath(factoryId)}/start`,
      { userInput, userId }
    );
    // 提取 data 层
    return response.data || (response as unknown as ConversationStartResponse);
  }

  /**
   * 继续多轮对话
   *
   * 用户回复澄清问题后，继续意图识别对话。
   *
   * @param sessionId 会话ID
   * @param userReply 用户回复
   * @param factoryId 工厂ID（可选）
   */
  async replyConversation(
    sessionId: string,
    userReply: string,
    factoryId?: string
  ): Promise<ConversationStartResponse> {
    const response = await apiClient.post<{ success: boolean; data: ConversationStartResponse }>(
      `${this.getConversationBasePath(factoryId)}/${sessionId}/reply`,
      { userReply }
    );
    return response.data || (response as unknown as ConversationStartResponse);
  }

  /**
   * 确认意图
   *
   * 用户从候选列表中选择确认意图。
   *
   * @param sessionId 会话ID
   * @param intentCode 确认的意图代码
   * @param factoryId 工厂ID（可选）
   */
  async confirmConversationIntent(
    sessionId: string,
    intentCode: string,
    factoryId?: string
  ): Promise<{ success: boolean; message: string }> {
    return await apiClient.post(
      `${this.getConversationBasePath(factoryId)}/${sessionId}/confirm`,
      { intentCode }
    );
  }

  /**
   * 获取AI对话历史
   *
   * 获取指定会话的完整对话历史记录
   *
   * @param sessionId 会话ID
   * @param factoryId 工厂ID（可选）
   */
  async getConversation(
    sessionId: string,
    factoryId?: string
  ): Promise<ConversationResponse> {
    const response = await apiClient.get<ApiResponseWrapper<ConversationResponse>>(
      `${this.getConversationBasePath(factoryId)}/${sessionId}`
    );
    return response.data;
  }

  /**
   * 继续AI对话
   *
   * @param request 对话请求
   * @param factoryId 工厂ID（可选）
   */
  async continueConversation(
    request: ConversationRequest,
    factoryId?: string
  ): Promise<AICostAnalysisResponse> {
    // 使用批次成本分析端点的 follow-up 模式（通过 sessionId 关联上下文）
    const response = await apiClient.post<ApiResponseWrapper<AICostAnalysisResponseBackend>>(
      `${this.getBasePath(factoryId)}/analysis/cost/batch`,
      {
        question: request.message,
        sessionId: request.sessionId,
      }
    );
    const data = response?.data ?? (response as unknown as AICostAnalysisResponseBackend);
    return transformAnalysisResponse(data);
  }

  /**
   * 关闭AI对话会话
   *
   * @param sessionId 会话ID
   * @param factoryId 工厂ID（可选）
   */
  async closeConversation(
    sessionId: string,
    factoryId?: string
  ): Promise<void> {
    await apiClient.post(
      `${this.getConversationBasePath(factoryId)}/${sessionId}/cancel`,
      {}
    );
  }

  // ========== 报告管理接口 ==========

  /**
   * 获取AI报告列表
   *
   * 获取工厂的AI成本分析报告列表，支持按类型和时间筛选
   *
   * @param params 查询参数
   * @param factoryId 工厂ID（可选）
   */
  async getReports(
    params?: {
      reportType?: string;
      startDate?: string;
      endDate?: string;
    },
    factoryId?: string
  ): Promise<ReportListResponse> {
    const response = await apiClient.get<any>(
      `${this.getBasePath(factoryId)}/reports`,
      { params }
    );

    // 后端返回格式与前端类型不匹配，需要转换字段名
    const backendData = response.data;
    if (backendData && Array.isArray(backendData.reports)) {
      const transformedReports: ReportSummary[] = backendData.reports.map((report: any) => ({
        reportId: report.id,
        reportType: report.reportType,
        title: report.summaryText?.substring(0, 100) || `${report.reportType === 'weekly' ? '周报' : report.reportType === 'monthly' ? '月报' : '报告'} - ${new Date(report.createdAt).toLocaleDateString('zh-CN')}`,
        createdAt: report.createdAt,
        batchId: report.batchId,
        batchNumber: report.batchNumber,
        startDate: report.periodStart,
        endDate: report.periodEnd,
        totalCost: report.totalCost,
        keyFindingsCount: report.keyFindingsCount,
        suggestionsCount: report.suggestionsCount,
      }));
      return {
        reports: transformedReports,
        total: backendData.total ?? transformedReports.length,
        page: backendData.page,
        pageSize: backendData.pageSize,
      };
    }

    return { reports: [], total: 0 };
  }

  /**
   * 获取AI报告详情
   *
   * @param reportId 报告ID
   * @param factoryId 工厂ID（可选）
   */
  async getReportDetail(
    reportId: number,
    factoryId?: string
  ): Promise<AICostAnalysisResponse> {
    // API返回格式: { code, message, data: AICostAnalysisResponseBackend, ... }
    // apiClient.get 返回的是axios response.data，即整个响应体
    const response = await apiClient.get<ApiResponseWrapper<AICostAnalysisResponseBackend>>(
      `${this.getBasePath(factoryId)}/reports/${reportId}`
    );

    // 安全提取数据：优先使用 response.data，兼容直接返回数据的情况
    const data = response?.data ?? (response as unknown as AICostAnalysisResponseBackend);
    return transformAnalysisResponse(data);
  }

  /**
   * 生成新报告（手动触发）
   *
   * @param request 报告生成请求
   * @param factoryId 工厂ID（可选）
   */
  async generateReport(
    request: ReportGenerationRequest,
    factoryId?: string
  ): Promise<AICostAnalysisResponse> {
    // API返回格式: { code, message, data: AICostAnalysisResponseBackend, ... }
    const response = await apiClient.post<ApiResponseWrapper<AICostAnalysisResponseBackend>>(
      `${this.getBasePath(factoryId)}/reports/generate`,
      request
    );
    // 安全提取数据：优先使用 response.data，兼容直接返回数据的情况
    const data = response?.data ?? (response as unknown as AICostAnalysisResponseBackend);
    return transformAnalysisResponse(data);
  }

  // ========== 意图识别接口 ==========

  /**
   * 识别用户意图
   *
   * 解析用户输入，返回匹配的意图列表和置信度
   * 对应后端 IntentAnalysisController.recognizeIntent
   *
   * @param userInput 用户输入文本
   * @param context 上下文信息（可选）
   * @param factoryId 工厂ID（可选）
   * @returns 意图匹配结果
   */
  async recognizeIntent(
    userInput: string,
    context?: Record<string, unknown>,
    factoryId?: string
  ): Promise<IntentRecognizeResponse> {
    const response = await apiClient.post<ApiResponseWrapper<IntentRecognizeResponse>>(
      `${this.getIntentBasePath(factoryId)}/recognize`,
      { userInput, context }
    );
    return response.data;
  }

  /**
   * 确认用户选择的意图
   *
   * 用于学习机制：记录用户的实际选择，用于优化未来匹配
   *
   * @param matchRecordId 匹配记录ID
   * @param selectedIntentCode 用户选择的意图代码
   * @param factoryId 工厂ID（可选）
   */
  async confirmIntentSelection(
    matchRecordId: string,
    selectedIntentCode: string,
    factoryId?: string
  ): Promise<void> {
    await apiClient.post(
      `${this.getIntentBasePath(factoryId)}/confirm`,
      { matchRecordId, selectedIntentCode }
    );
  }

  /**
   * 执行意图
   *
   * 根据意图代码执行对应操作
   *
   * @param intentCode 意图代码
   * @param parameters 执行参数（会被平铺到请求体中）
   * @param factoryId 工厂ID（可选）
   * @returns 执行结果
   */
  async executeIntent(
    intentCode: string,
    parameters?: Record<string, unknown>,
    factoryId?: string
  ): Promise<IntentExecuteResponse> {
    // 后端 IntentExecuteRequest 期望平铺的字段格式
    // userInput, intentCode, entityType, entityId, context, previewOnly, forceExecute, sessionId, enableThinking, thinkingBudget
    const requestBody = {
      intentCode,
      userInput: parameters?.userInput,
      entityType: parameters?.entityType,
      entityId: parameters?.entityId,
      context: parameters?.context,
      previewOnly: parameters?.previewOnly,
      forceExecute: parameters?.forceExecute,
      sessionId: parameters?.sessionId,
      enableThinking: parameters?.enableThinking,
      thinkingBudget: parameters?.thinkingBudget,
    };

    const response = await apiClient.post<ApiResponseWrapper<IntentExecuteResponse>>(
      `${this.getIntentBasePath(factoryId)}/execute`,
      requestBody
    );
    return response.data;
  }

  /**
   * 确认参数并执行 (Tool preview → 点确认即生成)
   *
   * 用户在 preview 看到 proposedBindings diff 后点"确认", 用 preview 阶段已解析的参数
   * 直接落库 (复用后端 /params/confirm executeAfterConfirm=true, 不重抽 LLM → 0 漂移)。
   * 配套 P1 端到端样板: NL → executeIntent(previewOnly) → status=PREVIEW → confirmParams → 落库。
   *
   * @param intentCode 意图代码 (如 WORK_PROCESS_CONFIG_UPDATE)
   * @param userInput 原始自然语言 (后端用于参数提取规则学习)
   * @param confirmedParams preview 已解析的参数 (如 { productTypeId, workProcessNames })
   * @param factoryId 工厂ID（可选）
   * @returns 执行结果 (executeAfterConfirm=true 时为 doExecute 的结果)
   */
  async confirmParams(
    intentCode: string,
    userInput: string,
    confirmedParams: Record<string, unknown>,
    factoryId?: string
  ): Promise<IntentExecuteResponse> {
    const response = await apiClient.post<ApiResponseWrapper<IntentExecuteResponse>>(
      `${this.getIntentBasePath(factoryId)}/params/confirm`,
      { intentCode, userInput, confirmedParams, executeAfterConfirm: true }
    );
    return response.data;
  }

  /**
   * 流式执行用户意图 (SSE)
   *
   * 通过 Server-Sent Events 实时返回执行进度:
   * 1. start - 开始处理
   * 2. cache_hit / cache_miss - 缓存查询结果
   * 3. intent_recognized - 意图识别完成
   * 4. executing - 开始执行
   * 5. result - 执行结果
   * 6. complete - 完成
   * 7. error - 发生错误
   *
   * @param userInput 用户输入文本
   * @param callbacks SSE事件回调函数
   * @param factoryId 工厂ID（可选）
   * @returns Promise<void> - 流式处理完成后resolve
   */
  async executeIntentStream(
    userInput: string,
    callbacks: IntentSSECallbacks,
    factoryId?: string,
    entityType?: string
  ): Promise<void> {
    const url = `${this.getIntentBasePath(factoryId)}/execute/stream`;

    // 获取 token
    const token = await this.getAuthToken();

    // 获取完整的 API 基础 URL
    const baseUrl = await this.getBaseUrl();
    const fullUrl = `${baseUrl}${url}`;

    console.log('=== 意图执行 SSE 流式请求开始 ===');
    console.log('URL:', fullUrl);
    console.log('UserInput:', userInput);

    // 后端使用 SseEmitter.event().name(eventName).data(json)
    // 对应 SSE 协议: event: eventName\ndata: json\n\n
    // react-native-sse 根据 event 名称分发到对应的 addEventListener
    type SSEEventNames = 'start' | 'meta' | 'token' | 'cache_hit' | 'cache_miss'
      | 'progress' | 'intent_recognized' | 'executing' | 'result' | 'complete' | 'error';

    return new Promise<void>((resolve, reject) => {
      const es = new EventSource<SSEEventNames>(fullUrl, {
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
          'Authorization': `Bearer ${token}`,
        },
        method: 'POST',
        body: JSON.stringify({ userInput, ...(entityType && { entityType }) }),
        pollingInterval: 0,
      });

      const safeParseJSON = (data: string | null | undefined): Record<string, unknown> | null => {
        if (!data) return null;
        try { return JSON.parse(data); } catch { return null; }
      };

      // 1. start — 开始处理
      es.addEventListener('start', (event) => {
        const d = safeParseJSON(event.data);
        callbacks.onStart?.(d?.message as string || '开始处理...');
      });

      // 2. cache_hit — 缓存命中
      es.addEventListener('cache_hit', (event) => {
        const d = safeParseJSON(event.data);
        if (d) {
          callbacks.onCacheHit?.({
            latencyMs: (d.latencyMs as number) || 0,
            cacheType: (d.hitType as 'EXACT' | 'SEMANTIC') || 'EXACT',
          });
        }
      });

      // 3. cache_miss — 缓存未命中
      es.addEventListener('cache_miss', (event) => {
        const d = safeParseJSON(event.data);
        callbacks.onCacheMiss?.((d?.latencyMs as number) || 0);
      });

      // 4. progress — 处理进度（阶段信息）
      es.addEventListener('progress', (event) => {
        const d = safeParseJSON(event.data);
        if (d) {
          callbacks.onProgress?.({
            stage: (d.stage as string) || '',
            message: (d.message as string) || '',
          });
        }
      });

      // 5. intent_recognized — 意图识别完成
      es.addEventListener('intent_recognized', (event) => {
        const d = safeParseJSON(event.data);
        if (d) {
          callbacks.onIntentRecognized?.({
            intentCode: (d.intentCode as string) || '',
            intentName: (d.intentName as string) || '',
            confidence: (d.confidence as number) || 0,
          });
        }
      });

      // 6. executing — 开始执行
      es.addEventListener('executing', (event) => {
        const d = safeParseJSON(event.data);
        callbacks.onExecuting?.((d?.intentName as string) || '');
      });

      // 7. meta — D3 流式模式元数据
      es.addEventListener('meta', (event) => {
        const d = safeParseJSON(event.data);
        if (d) {
          callbacks.onMeta?.({
            model: (d.model as string) || '',
            thinking: (d.thinking as boolean) || false,
            questionType: (d.questionType as string) || '',
          });
        }
      });

      // 8. token — D3 流式文本 token（逐 token 推送）
      es.addEventListener('token', (event) => {
        // token 事件的 data 是 JSON 编码的字符串（带引号），需要解析
        if (event.data) {
          try {
            const tokenText = JSON.parse(event.data);
            if (typeof tokenText === 'string') {
              callbacks.onToken?.(tokenText);
            }
          } catch {
            // 如果不是 JSON，直接作为文本使用
            callbacks.onToken?.(event.data);
          }
        }
      });

      // 9. result — 执行结果
      es.addEventListener('result', (event) => {
        const d = safeParseJSON(event.data);
        if (d) {
          callbacks.onResult?.(d);
        }
      });

      // 10. complete — 处理完成
      es.addEventListener('complete', (event) => {
        const d = safeParseJSON(event.data);
        callbacks.onComplete?.({
          status: (d?.status as string) || 'SUCCESS',
          cacheHit: (d?.cacheHit as boolean) || false,
          fullContent: d?.fullContent as string | undefined,
          totalLatencyMs: d?.totalLatencyMs as number | undefined,
        });
        es.close();
        resolve();
      });

      // 11. error — 错误
      es.addEventListener('error', (event) => {
        // SSE error 事件可能是连接错误（无 data），也可能是后端发送的 error 事件（有 data）
        const d = safeParseJSON('data' in event ? (event as { data?: string }).data : undefined);
        const errorMessage = d?.message as string
          || ('message' in event && typeof event.message === 'string' ? event.message : '流式请求失败');
        callbacks.onError?.(errorMessage);
        es.close();
        reject(new Error(errorMessage));
      });
    });
  }

  // ========== 健康检查接口 ==========

  /**
   * AI服务健康检查
   *
   * 检查AI服务和LLM API的可用性
   *
   * @param factoryId 工厂ID（可选）
   */
  async checkHealth(factoryId?: string): Promise<HealthCheckResponse> {
    const response = await apiClient.get<ApiResponseWrapper<HealthCheckResponse>>(
      `${this.getBasePath(factoryId)}/health`
    );
    return response.data;
  }
}

// ============ 导出 ============

/**
 * AI API客户端单例
 */
export const aiApiClient = new AIApiClient();

/**
 * 默认导出
 */
export default aiApiClient;
