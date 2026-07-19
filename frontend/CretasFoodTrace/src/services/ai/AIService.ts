/**
 * 集中式 AI 服务 - 核心类
 *
 * 所有 AI 调用的统一入口，自动检测分析模式
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */

import { aiApiClient } from '../api/aiApiClient';
import { formAssistantApiClient } from '../api/formAssistantApiClient';
import type { EntityType } from '../api/formTemplateApiClient';
import { detectAnalysisMode, createModeConfig } from './modeDetector';
import type {
  AnalysisMode,
  AnalysisModeResult,
  AIResult,
  IntentExecuteOptions,
  IntentExecuteRequest,
  IntentExecuteResponse,
  CostAnalysisRequest,
  CostAnalysisResponse,
  BatchCostAnalysisRequest,
  BatchComparisonRequest,
  SSECallbacks,
  FormAssistRequest,
  FormAssistResponse,
  AIServiceConfig,
} from './types';
import type { SSECallbacks as ApiSSECallbacks } from '../api/aiApiClient';

/**
 * Helper function to extract modeReason from mode result
 * Handles both full AnalysisModeResult and partial mode config
 */
function getModeReason(modeResult: AnalysisModeResult | Pick<AnalysisModeResult, 'enableThinking' | 'thinkingBudget' | 'mode'>): string {
  if ('reason' in modeResult && typeof modeResult.reason === 'string') {
    return modeResult.reason;
  }
  return `${modeResult.mode} mode`;
}

// ============ 默认配置 ============

const DEFAULT_CONFIG: AIServiceConfig = {
  timeout: 60000,
  enableLogging: __DEV__,
  defaultMode: 'quick',
};

// ============ AI 服务类 ============

/**
 * 集中式 AI 服务
 *
 * 特性：
 * - 自动检测分析模式（快速/深度）
 * - 统一响应格式
 * - 响应时间追踪
 * - 调试日志
 *
 * @example
 * // 意图执行
 * const result = await aiService.executeIntent('查询原料库存');
 *
 * // 成本分析
 * const analysis = await aiService.analyzeCost({
 *   startDate: '2025-01-01',
 *   endDate: '2025-01-31',
 *   question: '为什么成本上升了？',
 * });
 */
export class AIService {
  private config: AIServiceConfig;

  constructor(config?: Partial<AIServiceConfig>) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  // ============ 意图执行 ============

  /**
   * 执行用户意图
   *
   * 自动检测问题复杂度，选择快速或深度模式
   *
   * @param userInput 用户输入
   * @param options 可选配置
   * @returns 意图执行结果
   *
   * @example
   * // 简单查询 → 快速模式
   * await aiService.executeIntent('今天的库存是多少？');
   *
   * // 复杂分析 → 深度模式
   * await aiService.executeIntent('为什么这周产量下降了？有什么改进建议？');
   *
   * // 强制指定模式
   * await aiService.executeIntent('分析成本', { forceMode: 'deep' });
   */
  async executeIntent(
    userInput: string,
    options?: IntentExecuteOptions
  ): Promise<AIResult<IntentExecuteResponse>> {
    const startTime = Date.now();

    // 检测或使用强制模式
    const modeResult = options?.forceMode
      ? createModeConfig(options.forceMode)
      : detectAnalysisMode(userInput);

    this.log('executeIntent', { userInput, mode: modeResult.mode, options });

    try {
      // 构建请求
      const request: IntentExecuteRequest = {
        userInput,
        intentCode: options?.intentCode,
        entityType: options?.entityType,
        entityId: options?.entityId,
        context: options?.context,
        previewOnly: options?.previewOnly,
        forceExecute: options?.forceExecute,
        sessionId: options?.sessionId,
        enableThinking: modeResult.enableThinking,
        thinkingBudget: modeResult.thinkingBudget,
      };

      // 调用 API
      // 注意：intentCode 可以为空，后端会通过 userInput 进行意图识别
      const response = await aiApiClient.executeIntent(
        request.intentCode || '',  // 不传 userInput 作为 intentCode
        {
          ...request.context,
          userInput: request.userInput,
          entityType: request.entityType,
          entityId: request.entityId,
          previewOnly: request.previewOnly,
          forceExecute: request.forceExecute,
          sessionId: request.sessionId,
          enableThinking: request.enableThinking,
          thinkingBudget: request.thinkingBudget,
        }
      );

      return {
        success: response.success,
        data: response,
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: Date.now() - startTime,
      };
    } catch (error) {
      this.logError('executeIntent', error);
      return {
        success: false,
        data: {
          success: false,
          message: error instanceof Error ? error.message : 'AI 服务调用失败',
        },
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: Date.now() - startTime,
        errorMessage: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  // ============ 多轮对话（LLM意图识别）============

  /**
   * 使用多轮对话进行意图识别
   *
   * 相比 executeIntent，此方法使用 LLM 进行意图识别，准确率更高。
   * 当用户输入模糊时，会返回需要澄清的状态和建议选项。
   *
   * @param userInput 用户输入
   * @param options 选项
   * @returns AI响应结果
   *
   * @example
   * // 新对话
   * await aiService.chatWithConversation('最近一周的生产成本如何？');
   *
   * // 继续对话
   * await aiService.chatWithConversation('我选择总体成本', { sessionId: 'xxx' });
   */
  async chatWithConversation(
    userInput: string,
    options?: { sessionId?: string }
  ): Promise<AIResult<IntentExecuteResponse>> {
    const startTime = Date.now();

    // 检测分析模式
    const modeResult = detectAnalysisMode(userInput);
    this.log('chatWithConversation', { userInput, mode: modeResult.mode, options });

    try {
      let response;

      if (options?.sessionId) {
        // 继续已有对话
        response = await aiApiClient.replyConversation(options.sessionId, userInput);
      } else {
        // 启动新对话
        response = await aiApiClient.startConversation(userInput);
      }

      this.log('chatWithConversation response', response);

      // 转换为统一的 IntentExecuteResponse 格式
      // 后端 ConversationService 返回的状态:
      // - ACTIVE: 对话进行中，需要更多输入
      // - COMPLETED: 对话完成，意图已识别
      // - FAILED: 对话失败
      const executeResponse: IntentExecuteResponse = {
        success: response.status === 'COMPLETED' || response.status === 'CONVERSATION_CONTINUE' || response.status === 'NEED_CLARIFICATION' || response.status === 'ACTIVE',
        message: '操作成功',
        data: {
          status: response.status,
          message: response.message,
          intentCode: response.intentCode,
          intentRecognized: !!response.intentCode,
          suggestedActions: response.suggestedActions,
          candidates: response.candidates,
          metadata: {
            ...response.metadata,
            sessionId: response.sessionId,
            confidence: response.confidence,
          },
        },
      };

      // 如果有执行结果，合并到响应中
      if (response.executionResult) {
        // Type assertion needed because data is typed as unknown in IntentExecuteResponse
        const currentData = executeResponse.data as Record<string, unknown> | undefined;
        executeResponse.data = {
          ...(currentData ?? {}),
          ...response.executionResult,
        };
      }

      return {
        success: executeResponse.success,
        data: executeResponse,
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: Date.now() - startTime,
      };
    } catch (error) {
      this.logError('chatWithConversation', error);
      return {
        success: false,
        data: {
          success: false,
          message: error instanceof Error ? error.message : 'AI 服务调用失败',
        },
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: Date.now() - startTime,
        errorMessage: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  // ============ 成本分析 ============

  /**
   * 时间范围成本分析
   *
   * @param request 分析请求
   * @returns 分析结果
   *
   * @example
   * // 简单概况 → 快速模式
   * await aiService.analyzeCost({
   *   startDate: '2025-01-01',
   *   endDate: '2025-01-31',
   * });
   *
   * // 深度分析
   * await aiService.analyzeCost({
   *   startDate: '2025-01-01',
   *   endDate: '2025-01-31',
   *   question: '为什么人工成本上升了？有什么优化建议？',
   * });
   */
  async analyzeCost(
    request: CostAnalysisRequest
  ): Promise<AIResult<CostAnalysisResponse>> {
    const startTime = Date.now();

    // 检测或使用强制模式
    const modeResult = request.forceMode
      ? createModeConfig(request.forceMode)
      : detectAnalysisMode(request.question, request.dimension);

    this.log('analyzeCost', { request, mode: modeResult.mode });

    try {
      const response = await aiApiClient.analyzeTimeRangeCost({
        startDate: request.startDate,
        endDate: request.endDate,
        dimension: request.dimension,
        question: request.question,
        enableThinking: modeResult.enableThinking,
        thinkingBudget: modeResult.thinkingBudget,
      });

      return {
        success: response.success,
        data: {
          success: response.success,
          analysis: response.analysis,
          message: response.errorMessage,
        },
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: response.responseTimeMs || (Date.now() - startTime),
      };
    } catch (error) {
      this.logError('analyzeCost', error);
      return {
        success: false,
        data: {
          success: false,
          message: error instanceof Error ? error.message : 'AI 分析失败',
        },
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: Date.now() - startTime,
        errorMessage: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * 批次成本分析
   */
  async analyzeBatchCost(
    request: BatchCostAnalysisRequest
  ): Promise<AIResult<CostAnalysisResponse>> {
    const startTime = Date.now();

    const modeResult = request.forceMode
      ? createModeConfig(request.forceMode)
      : detectAnalysisMode(request.question);

    this.log('analyzeBatchCost', { request, mode: modeResult.mode });

    try {
      const response = await aiApiClient.analyzeBatchCost({
        batchId: typeof request.batchId === 'string'
          ? parseInt(request.batchId, 10)
          : request.batchId,
        question: request.question,
        sessionId: request.sessionId,
        analysisType: request.analysisType,
        enableThinking: modeResult.enableThinking,
        thinkingBudget: modeResult.thinkingBudget,
      });

      return {
        success: response.success,
        data: {
          success: response.success,
          analysis: response.analysis,
          message: response.errorMessage,
        },
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: response.responseTimeMs || (Date.now() - startTime),
      };
    } catch (error) {
      this.logError('analyzeBatchCost', error);
      return {
        success: false,
        data: {
          success: false,
          message: error instanceof Error ? error.message : 'AI 分析失败',
        },
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: Date.now() - startTime,
        errorMessage: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * 批次对比分析
   */
  async compareBatches(
    request: BatchComparisonRequest
  ): Promise<AIResult<CostAnalysisResponse>> {
    const startTime = Date.now();

    const modeResult = request.forceMode
      ? createModeConfig(request.forceMode)
      : detectAnalysisMode(request.question);

    this.log('compareBatches', { request, mode: modeResult.mode });

    try {
      const response = await aiApiClient.compareBatchCosts({
        batchIds: request.batchIds.map((id) =>
          typeof id === 'string' ? parseInt(id, 10) : id
        ),
        dimension: request.dimension,
        question: request.question,
        enableThinking: modeResult.enableThinking,
        thinkingBudget: modeResult.thinkingBudget,
      });

      return {
        success: response.success,
        data: {
          success: response.success,
          analysis: response.analysis,
          message: response.errorMessage,
        },
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: response.responseTimeMs || (Date.now() - startTime),
      };
    } catch (error) {
      this.logError('compareBatches', error);
      return {
        success: false,
        data: {
          success: false,
          message: error instanceof Error ? error.message : 'AI 对比分析失败',
        },
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: Date.now() - startTime,
        errorMessage: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  // ============ 流式分析 (SSE) ============

  /**
   * 流式成本分析
   *
   * 实时返回 AI 分析过程，适用于需要展示进度的场景
   *
   * @param request 分析请求
   * @param callbacks SSE 回调函数
   *
   * @example
   * await aiService.analyzeCostStream(
   *   { startDate: '2025-01-01', endDate: '2025-01-31', question: '分析趋势' },
   *   {
   *     onStart: () => setLoading(true),
   *     onThinking: (text) => setThinking(prev => prev + text),
   *     onContent: (text) => setContent(prev => prev + text),
   *     onDone: () => setLoading(false),
   *     onError: (err) => showError(err),
   *   }
   * );
   */
  async analyzeCostStream(
    request: CostAnalysisRequest,
    callbacks: SSECallbacks
  ): Promise<{ mode: AnalysisMode; modeReason: string }> {
    // 检测或使用强制模式
    const modeResult = request.forceMode
      ? createModeConfig(request.forceMode)
      : detectAnalysisMode(request.question, request.dimension);

    this.log('analyzeCostStream', { request, mode: modeResult.mode });

    // 触发开始回调
    callbacks.onStart?.({ mode: modeResult.mode, thinkingBudget: modeResult.thinkingBudget });

    // 转换回调格式
    const apiCallbacks: ApiSSECallbacks = {
      onStart: () => {
        // 已在上面触发
      },
      onProgress: (message) => {
        // 可选：转发进度消息
        this.log('SSE progress', message);
      },
      onThinking: (content) => {
        callbacks.onThinking?.(content);
      },
      onAnswer: (content) => {
        callbacks.onContent?.(content);
      },
      onComplete: (data) => {
        callbacks.onDone?.(data.analysis);
      },
      onError: (message) => {
        callbacks.onError?.(message);
      },
    };

    try {
      await aiApiClient.analyzeTimeRangeCostStream(
        {
          startDate: request.startDate,
          endDate: request.endDate,
          dimension: request.dimension,
          question: request.question,
          enableThinking: modeResult.enableThinking,
          thinkingBudget: modeResult.thinkingBudget,
        },
        apiCallbacks
      );
    } catch (error) {
      this.logError('analyzeCostStream', error);
      callbacks.onError?.(error instanceof Error ? error.message : '流式分析失败');
    }

    return {
      mode: modeResult.mode,
      modeReason: getModeReason(modeResult),
    };
  }

  // ============ 表单助手 ============

  /**
   * AI 表单助手 - Schema 生成
   *
   * 根据用户描述生成表单字段 Schema
   *
   * @param request 请求参数
   * @returns 生成结果
   *
   * @example
   * await aiService.formAssist({
   *   userInput: '添加一个运输温度字段，-30到30度',
   *   entityType: 'MATERIAL_BATCH',
   *   existingFields: ['quantity', 'materialType'],
   * });
   */
  async formAssist(
    request: FormAssistRequest
  ): Promise<AIResult<FormAssistResponse>> {
    const startTime = Date.now();

    // 检测或使用强制模式
    const modeResult = request.forceMode
      ? createModeConfig(request.forceMode)
      : detectAnalysisMode(request.userInput);

    this.log('formAssist', { request, mode: modeResult.mode });

    try {
      const response = await formAssistantApiClient.generateSchema({
        userInput: request.userInput,
        // Cast FormEntityType to EntityType - they share common values
        entityType: request.entityType as EntityType,
        existingFields: request.existingFields,
        // TODO: 后端需要支持 enableThinking/thinkingBudget
      });

      return {
        success: response.success,
        data: {
          success: response.success,
          schema: response.fields ? { properties: this.fieldsToSchema(response.fields) } : undefined,
          suggestions: response.fields?.map((f) => ({
            fieldName: f.name,
            fieldType: f.type as 'string' | 'number' | 'boolean' | 'date' | 'select' | 'array',
            label: f.title,
            required: f.required,
          })),
          explanation: response.suggestions?.join('; '),
          message: response.message,
        },
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: Date.now() - startTime,
      };
    } catch (error) {
      this.logError('formAssist', error);
      return {
        success: false,
        data: {
          success: false,
          message: error instanceof Error ? error.message : '表单助手调用失败',
        },
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: Date.now() - startTime,
        errorMessage: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * AI 表单解析 - 文本输入
   *
   * 将用户输入解析为表单字段值
   */
  async parseFormInput(
    userInput: string,
    entityType: FormAssistRequest['entityType'],
    context?: Record<string, unknown>,
    formFields?: Array<{
      name: string;
      title: string;
      type: string;
      description?: string;
      required?: boolean;
    }>
  ): Promise<AIResult<{
    fieldValues: Record<string, unknown>;
    confidence: number;
    unparsedText?: string;
    missingRequiredFields?: string[];
    suggestedQuestions?: string[];
    followUpQuestion?: string;
  }>> {
    const startTime = Date.now();
    const modeResult = detectAnalysisMode(userInput);

    this.log('parseFormInput', { userInput, entityType, mode: modeResult.mode });

    try {
      const response = await formAssistantApiClient.parseFormInput({
        userInput,
        // Cast FormEntityType to EntityType - they share common values
        entityType: entityType as EntityType,
        context,
        formFields: formFields as Parameters<typeof formAssistantApiClient.parseFormInput>[0]['formFields'],
      });

      return {
        success: response.success,
        data: {
          fieldValues: response.fieldValues,
          confidence: response.confidence,
          unparsedText: response.unparsedText,
          missingRequiredFields: response.missingRequiredFields,
          suggestedQuestions: response.suggestedQuestions,
          followUpQuestion: response.followUpQuestion,
        },
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: Date.now() - startTime,
      };
    } catch (error) {
      this.logError('parseFormInput', error);
      return {
        success: false,
        data: {
          fieldValues: {},
          confidence: 0,
        },
        mode: modeResult.mode,
        modeReason: getModeReason(modeResult),
        responseTimeMs: Date.now() - startTime,
        errorMessage: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * AI 表单解析 - OCR 图片输入
   *
   * 从图片中提取文字并解析为表单字段值
   */
  async parseFormOCR(
    imageBase64: string,
    entityType: FormAssistRequest['entityType'],
    formFields?: Array<{
      name: string;
      title: string;
      type: string;
      description?: string;
      required?: boolean;
    }>
  ): Promise<AIResult<{
    fieldValues: Record<string, unknown>;
    confidence: number;
    extractedText?: string;
  }>> {
    const startTime = Date.now();
    // OCR 通常是简单的数据提取，使用快速模式
    const modeResult = createModeConfig('quick');

    this.log('parseFormOCR', { entityType, mode: modeResult.mode });

    try {
      const response = await formAssistantApiClient.parseFormOCR({
        imageBase64,
        // Cast FormEntityType to EntityType - they share common values
        entityType: entityType as EntityType,
        formFields: formFields as Parameters<typeof formAssistantApiClient.parseFormOCR>[0]['formFields'],
      });

      return {
        success: response.success,
        data: {
          fieldValues: response.fieldValues,
          confidence: response.confidence,
          extractedText: response.extractedText,
        },
        mode: modeResult.mode,
        modeReason: 'OCR 数据提取使用快速模式',
        responseTimeMs: Date.now() - startTime,
      };
    } catch (error) {
      this.logError('parseFormOCR', error);
      return {
        success: false,
        data: {
          fieldValues: {},
          confidence: 0,
        },
        mode: modeResult.mode,
        modeReason: 'OCR 数据提取使用快速模式',
        responseTimeMs: Date.now() - startTime,
        errorMessage: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * AI 校验反馈 - 获取修正建议
   *
   * 当表单校验失败时，将错误信息反馈给 AI 获取修正建议
   */
  async handleValidationFeedback(
    request: {
      entityType: FormAssistRequest['entityType'];
      submittedValues: Record<string, unknown>;
      validationErrors: Array<{
        field: string;
        message: string;
        rule?: string;
        currentValue?: unknown;
      }>;
      formFields?: Array<{
        name: string;
        title: string;
        type: string;
        description?: string;
        required?: boolean;
      }>;
      sessionId?: string;
      userInstruction?: string;
    }
  ): Promise<AIResult<{
    correctionHints?: Record<string, string>;
    correctedValues?: Record<string, unknown>;
    explanation?: string;
    confidence: number;
    sessionId?: string;
  }>> {
    const startTime = Date.now();
    // 校验反馈需要分析错误原因，使用深度模式
    const modeResult = createModeConfig('deep', 30);

    this.log('handleValidationFeedback', { entityType: request.entityType, mode: modeResult.mode });

    try {
      const response = await formAssistantApiClient.submitValidationFeedback({
        // Cast FormEntityType to EntityType - they share common values
        entityType: request.entityType as EntityType,
        submittedValues: request.submittedValues,
        validationErrors: request.validationErrors,
        formFields: request.formFields as Parameters<typeof formAssistantApiClient.submitValidationFeedback>[0]['formFields'],
        sessionId: request.sessionId,
        userInstruction: request.userInstruction,
      });

      return {
        success: response.success,
        data: {
          correctionHints: response.correctionHints,
          correctedValues: response.correctedValues,
          explanation: response.explanation,
          confidence: response.confidence,
          sessionId: response.sessionId,
        },
        mode: modeResult.mode,
        modeReason: '校验修正需要深度分析',
        responseTimeMs: Date.now() - startTime,
      };
    } catch (error) {
      this.logError('handleValidationFeedback', error);
      return {
        success: false,
        data: {
          confidence: 0,
        },
        mode: modeResult.mode,
        modeReason: '校验修正需要深度分析',
        responseTimeMs: Date.now() - startTime,
        errorMessage: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * AI Schema 分析
   *
   * 分析现有表单 Schema 配置，返回优化建议
   */
  async analyzeSchema(
    request: {
      entityType: FormAssistRequest['entityType'];
      schemaJson: string;
      focusAreas?: ('usability' | 'validation' | 'performance' | 'completeness')[];
      businessContext?: string;
    }
  ): Promise<AIResult<{
    overallScore: number;
    dimensionScores: {
      usability: number;
      validation: number;
      completeness: number;
      structure: number;
    };
    suggestions: Array<{
      id: string;
      type: string;
      fieldName?: string;
      title: string;
      description: string;
      priority: 'high' | 'medium' | 'low';
      suggestedChange?: Record<string, unknown>;
      expectedBenefit?: string;
    }>;
    summary: string;
  }>> {
    const startTime = Date.now();
    // Schema 分析是深度任务
    const modeResult = createModeConfig('deep', 50);

    this.log('analyzeSchema', { entityType: request.entityType, mode: modeResult.mode });

    try {
      const response = await formAssistantApiClient.analyzeSchema({
        // Cast FormEntityType to EntityType - they share common values
        entityType: request.entityType as EntityType,
        schemaJson: request.schemaJson,
        focusAreas: request.focusAreas,
        businessContext: request.businessContext,
      });

      return {
        success: response.success,
        data: {
          overallScore: response.overallScore,
          dimensionScores: response.dimensionScores,
          suggestions: response.suggestions,
          summary: response.summary,
        },
        mode: modeResult.mode,
        modeReason: 'Schema 分析需要深度评估',
        responseTimeMs: Date.now() - startTime,
      };
    } catch (error) {
      this.logError('analyzeSchema', error);
      return {
        success: false,
        data: {
          overallScore: 0,
          dimensionScores: { usability: 0, validation: 0, completeness: 0, structure: 0 },
          suggestions: [],
          summary: '',
        },
        mode: modeResult.mode,
        modeReason: 'Schema 分析需要深度评估',
        responseTimeMs: Date.now() - startTime,
        errorMessage: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * AI Schema 优化
   *
   * 根据选中的优化建议或用户指令，自动优化 Schema 配置
   */
  async optimizeSchema(
    request: {
      entityType: FormAssistRequest['entityType'];
      schemaJson: string;
      suggestionIds?: string[];
      userInstruction?: string;
    }
  ): Promise<AIResult<{
    fields: Array<{
      name: string;
      title: string;
      type: string;
      'x-component': string;
      required?: boolean;
      description?: string;
    }>;
    appliedCount: number;
    changeSummary: string;
    optimizedSchema?: string;
  }>> {
    const startTime = Date.now();
    // Schema 优化是深度任务
    const modeResult = createModeConfig('deep', 40);

    this.log('optimizeSchema', { entityType: request.entityType, mode: modeResult.mode });

    try {
      const response = await formAssistantApiClient.optimizeSchema({
        // Cast FormEntityType to EntityType - they share common values
        entityType: request.entityType as EntityType,
        schemaJson: request.schemaJson,
        suggestionIds: request.suggestionIds,
        userInstruction: request.userInstruction,
      });

      return {
        success: response.success,
        data: {
          fields: response.fields,
          appliedCount: response.appliedCount,
          changeSummary: response.changeSummary,
          optimizedSchema: response.optimizedSchema,
        },
        mode: modeResult.mode,
        modeReason: 'Schema 优化需要深度分析',
        responseTimeMs: Date.now() - startTime,
      };
    } catch (error) {
      this.logError('optimizeSchema', error);
      return {
        success: false,
        data: {
          fields: [],
          appliedCount: 0,
          changeSummary: '',
        },
        mode: modeResult.mode,
        modeReason: 'Schema 优化需要深度分析',
        responseTimeMs: Date.now() - startTime,
        errorMessage: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * AI 表单助手健康检查
   */
  async checkFormAssistantHealth(): Promise<{
    available: boolean;
    serviceUrl: string;
    error?: string;
  }> {
    this.log('checkFormAssistantHealth', {});

    try {
      return await formAssistantApiClient.checkHealth();
    } catch (error) {
      this.logError('checkFormAssistantHealth', error);
      return {
        available: false,
        serviceUrl: '',
        error: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  // ============ 工具方法 ============

  /**
   * 从 Formily Schema 提取字段定义
   *
   * 便于发送给 AI 服务进行解析
   */
  extractFieldsFromSchema(schema: {
    properties?: Record<string, {
      type?: string;
      title?: string;
      description?: string;
      required?: boolean;
      enum?: Array<{ label: string; value: string }>;
    }>;
  }): Array<{
    name: string;
    title: string;
    type: string;
    description?: string;
    required?: boolean;
  }> {
    return formAssistantApiClient.extractFieldsFromSchema(schema);
  }

  /**
   * 获取当前检测到的模式（不执行 API 调用）
   */
  detectMode(userInput: string, dimension?: string): AnalysisModeResult {
    return detectAnalysisMode(userInput, dimension);
  }

  /**
   * 更新配置
   */
  updateConfig(config: Partial<AIServiceConfig>): void {
    this.config = { ...this.config, ...config };
  }

  // ============ 私有方法 ============

  private fieldsToSchema(
    fields: Array<{
      name: string;
      title: string;
      type: string;
      description?: string;
      required?: boolean;
    }>
  ): Record<string, unknown> {
    const properties: Record<string, unknown> = {};
    for (const field of fields) {
      properties[field.name] = {
        type: field.type,
        title: field.title,
        description: field.description,
        required: field.required,
      };
    }
    return properties;
  }

  private log(method: string, data: unknown): void {
    if (this.config.enableLogging) {
      console.log(`[AIService.${method}]`, data);
    }
  }

  private logError(method: string, error: unknown): void {
    console.error(`[AIService.${method}] Error:`, error);
  }
}

// ============ 导出单例 ============

/**
 * AI 服务单例
 *
 * 使用此单例进行所有 AI 调用，自动处理模式检测
 */
export const aiService = new AIService();

/**
 * 创建自定义配置的 AI 服务实例
 */
export function createAIService(config?: Partial<AIServiceConfig>): AIService {
  return new AIService(config);
}

export default aiService;
