/**
 * AI意图配置 API 客户端
 *
 * 对应后端 AIIntentConfigController
 * 基础路径: /api/mobile/{factoryId}/ai-intents
 *
 * 功能：
 * - 意图配置的CRUD操作
 * - 意图识别测试
 * - 分类和权限查询
 * - 缓存管理
 *
 * @version 1.0.0
 * @since 2026-01-02
 */

import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

// ============ 类型定义 ============

/**
 * 敏感度级别
 */
export type SensitivityLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

/**
 * 意图分类
 */
export type IntentCategory = 'ANALYSIS' | 'DATA_OP' | 'FORM' | 'SCHEDULE' | 'SYSTEM';

/**
 * 意图配置范围
 */
export type IntentScope = 'GLOBAL' | 'FACTORY';

/**
 * AI意图配置
 */
export interface AIIntentConfig {
  /** 主键ID */
  id?: string;
  /** 意图代码 (唯一标识) */
  intentCode: string;
  /** 意图名称 */
  intentName: string;
  /** 意图分类 */
  intentCategory: IntentCategory;
  /** 描述 */
  description?: string;
  /** 关键词列表 */
  keywords: string[];
  /** 正则表达式列表 */
  regexPatterns: string[];
  /** 优先级 (1-100, 越大越优先) */
  priority: number;
  /** 敏感度级别 */
  sensitivityLevel: SensitivityLevel;
  /** 是否启用 */
  enabled: boolean;
  /** 配额消耗 */
  quotaCost?: number;
  /** 需要审批 */
  requiresApproval?: boolean;
  /** 允许的角色列表 */
  allowedRoles?: string[];
  /** 配置范围 (GLOBAL/FACTORY) */
  scope?: IntentScope;
  /** 工厂ID (仅 scope=FACTORY 时有值) */
  factoryId?: string;
  /** 创建时间 */
  createdAt?: string;
  /** 更新时间 */
  updatedAt?: string;
}

/**
 * 意图识别请求
 */
export interface IntentRecognitionRequest {
  /** 用户输入文本 */
  userInput: string;
}

/**
 * 意图识别结果
 */
export interface IntentRecognitionResult {
  /** 用户输入 */
  userInput: string;
  /** 是否匹配成功 */
  matched: boolean;
  /** 匹配的意图代码 */
  intentCode?: string;
  /** 意图名称 */
  intentName?: string;
  /** 意图分类 */
  category?: string;
  /** 敏感度级别 */
  sensitivityLevel?: string;
  /** 配额消耗 */
  quotaCost?: number;
  /** 需要审批 */
  requiresApproval?: boolean;
}

/**
 * 权限检查结果
 */
export interface PermissionCheckResult {
  /** 意图代码 */
  intentCode: string;
  /** 用户角色 */
  userRole: string;
  /** 是否有权限 */
  hasPermission: boolean;
  /** 需要审批 */
  requiresApproval: boolean;
  /** 配额消耗 */
  quotaCost: number;
}

/**
 * 意图执行请求
 */
export interface IntentExecuteRequest {
  /** 用户输入 */
  userInput: string;
  /** 上下文参数 */
  context?: Record<string, unknown>;
}

/**
 * Parameter-bound confirmation evidence. The opaque token is transported only in
 * X-Cretas-Confirmation-Token; the remaining fields form the fixed JSON contract.
 */
export interface IntentConfirmationProof {
  readonly confirmToken: string;
  readonly commandDigest: string;
  readonly expiresAt: string;
  /** Reserved for future Gateway correlation; not yet persisted by the backend. */
  readonly requestId: string;
  /** Reserved for a future Gateway idempotency store; atomic token claim is authoritative today. */
  readonly idempotencyKey: string;
}

export interface ConfirmableAction extends IntentConfirmationProof {
  readonly description?: string;
  readonly expiresInSeconds?: number;
  readonly previewData?: unknown;
}

/**
 * 意图执行响应
 */
export interface IntentExecuteResponse {
  /** 是否成功 */
  success: boolean;
  /** 匹配的意图代码 */
  intentCode?: string;
  /** 意图名称 */
  intentName?: string;
  /** 执行结果类型 */
  resultType?: 'data' | 'message' | 'navigation' | 'action' | 'preview' | 'pending_confirm';
  /** 返回数据 */
  resultData?: unknown;
  /** 消息文本 */
  message?: string;
  /** 导航目标 */
  navigationTarget?: string;
  /** 确认 Token (预览模式需要) */
  confirmableAction?: ConfirmableAction;
  /** 预览数据 */
  previewData?: unknown;
  /** 错误信息 */
  errorMessage?: string;
}

/**
 * 启用状态请求
 */
export interface ActiveStatusRequest {
  /** 是否启用 */
  active: boolean;
}

/**
 * 意图分类配置（从 system_enums 获取）
 */
export interface IntentCategoryConfig {
  /** 枚举ID */
  id: string;
  /** 分类代码 */
  enumCode: string;
  /** 显示标签 */
  enumLabel: string;
  /** 描述 */
  enumDescription?: string;
  /** 排序 */
  sortOrder: number;
  /** 图标 */
  icon?: string;
  /** 颜色 */
  color?: string;
  /** 元数据（包含 subCategories 等） */
  metadata?: {
    subCategories?: string[];
    [key: string]: unknown;
  };
}

/**
 * 意图规则配置
 */
export interface IntentRule {
  /** 规则ID */
  id?: string;
  /** 规则名称 */
  ruleName: string;
  /** 规则类型 */
  ruleType: 'REGEX' | 'KEYWORD' | 'CONDITION' | 'SCRIPT';
  /** 规则表达式/内容 */
  ruleExpression: string;
  /** 优先级 */
  priority: number;
  /** 是否启用 */
  enabled: boolean;
  /** 描述 */
  description?: string;
  /** 创建时间 */
  createdAt?: string;
  /** 更新时间 */
  updatedAt?: string;
}

/**
 * 参数数据类型
 */
export type ParameterDataType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'DATE' | 'DATETIME' | 'ENUM' | 'ARRAY' | 'OBJECT';

/**
 * 意图参数配置
 */
export interface IntentParameter {
  /** 参数ID */
  id?: string;
  /** 参数名称 */
  parameterName: string;
  /** 显示名称 */
  displayName: string;
  /** 数据类型 */
  dataType: ParameterDataType;
  /** 是否必需 */
  required: boolean;
  /** 默认值 */
  defaultValue?: string;
  /** 参数描述 */
  description?: string;
  /** 验证规则 */
  validationRule?: string;
  /** 枚举值（当 dataType 为 ENUM 时） */
  enumValues?: string[];
  /** 排序顺序 */
  sortOrder: number;
  /** 是否启用 */
  enabled: boolean;
  /** 创建时间 */
  createdAt?: string;
  /** 更新时间 */
  updatedAt?: string;
}

/**
 * 意图测试结果
 */
export interface IntentTestResult {
  /** 是否匹配成功 */
  matched: boolean;
  /** 匹配置信度 (0-1) */
  confidence: number;
  /** 匹配方法 */
  matchMethod: 'REGEX' | 'KEYWORD' | 'LLM' | 'NONE';
  /** 匹配到的关键词 */
  matchedKeywords: string[];
  /** 提取的参数 */
  extractedParameters: Record<string, unknown>;
  /** 执行耗时(ms) */
  executionTimeMs: number;
  /** 执行结果 */
  executionResult?: {
    success: boolean;
    resultType?: string;
    message?: string;
    data?: unknown;
  };
  /** 错误信息 */
  errorMessage?: string;
}

/**
 * 通用API响应包装
 */
interface ApiResponseWrapper<T> {
  success: boolean;
  code?: number;
  message: string;
  data: T;
}

// ============ API客户端类 ============

/**
 * 意图配置 API 客户端
 */
class IntentConfigApiClient {
  /**
   * 获取基础路径
   */
  private getBasePath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/ai-intents`;
  }

  // ==================== 意图查询 ====================

  /**
   * 获取所有意图配置
   *
   * @param factoryId 工厂ID（可选）
   * @returns 意图配置列表
   */
  async getAllIntents(factoryId?: string): Promise<AIIntentConfig[]> {
    const response = await apiClient.get<ApiResponseWrapper<AIIntentConfig[]>>(
      this.getBasePath(factoryId)
    );
    return response.data ?? [];
  }

  /**
   * 按分类获取意图
   *
   * @param category 意图分类
   * @param factoryId 工厂ID（可选）
   * @returns 意图配置列表
   */
  async getIntentsByCategory(
    category: IntentCategory | string,
    factoryId?: string
  ): Promise<AIIntentConfig[]> {
    const response = await apiClient.get<ApiResponseWrapper<AIIntentConfig[]>>(
      `${this.getBasePath(factoryId)}/category/${category}`
    );
    return response.data ?? [];
  }

  /**
   * 获取所有分类
   *
   * @param factoryId 工厂ID（可选）
   * @returns 分类列表
   */
  async getAllCategories(factoryId?: string): Promise<string[]> {
    const response = await apiClient.get<ApiResponseWrapper<string[]>>(
      `${this.getBasePath(factoryId)}/categories`
    );
    return response.data ?? [];
  }

  /**
   * 按敏感度获取意图
   *
   * @param level 敏感度级别
   * @param factoryId 工厂ID（可选）
   * @returns 意图配置列表
   */
  async getIntentsBySensitivity(
    level: SensitivityLevel,
    factoryId?: string
  ): Promise<AIIntentConfig[]> {
    const response = await apiClient.get<ApiResponseWrapper<AIIntentConfig[]>>(
      `${this.getBasePath(factoryId)}/sensitivity/${level}`
    );
    return response.data ?? [];
  }

  /**
   * 获取单个意图
   *
   * @param intentCode 意图代码
   * @param factoryId 工厂ID（可选）
   * @returns 意图配置
   */
  async getIntent(
    intentCode: string,
    factoryId?: string
  ): Promise<AIIntentConfig | null> {
    const response = await apiClient.get<ApiResponseWrapper<AIIntentConfig>>(
      `${this.getBasePath(factoryId)}/${intentCode}`
    );
    return response.data ?? null;
  }

  // ==================== 意图识别 ====================

  /**
   * 测试意图识别
   *
   * @param userInput 用户输入文本
   * @param factoryId 工厂ID（可选）
   * @returns 识别结果
   */
  async recognizeIntent(
    userInput: string,
    factoryId?: string
  ): Promise<IntentRecognitionResult> {
    const response = await apiClient.post<ApiResponseWrapper<IntentRecognitionResult>>(
      `${this.getBasePath(factoryId)}/recognize`,
      { userInput }
    );
    return response.data;
  }

  /**
   * 识别所有匹配意图
   *
   * @param userInput 用户输入文本
   * @param factoryId 工厂ID（可选）
   * @returns 所有匹配的意图列表
   */
  async recognizeAllIntents(
    userInput: string,
    factoryId?: string
  ): Promise<AIIntentConfig[]> {
    const response = await apiClient.post<ApiResponseWrapper<AIIntentConfig[]>>(
      `${this.getBasePath(factoryId)}/recognize-all`,
      { userInput }
    );
    return response.data ?? [];
  }

  // ==================== 意图执行 ====================

  /**
   * 执行AI意图
   *
   * @param request 执行请求
   * @param factoryId 工厂ID（可选）
   * @returns 执行结果
   */
  async executeIntent(
    request: IntentExecuteRequest,
    factoryId?: string
  ): Promise<IntentExecuteResponse> {
    const response = await apiClient.post<ApiResponseWrapper<IntentExecuteResponse>>(
      `${this.getBasePath(factoryId)}/execute`,
      request
    );
    return response.data;
  }

  /**
   * 预览AI意图执行结果
   *
   * @param request 执行请求
   * @param factoryId 工厂ID（可选）
   * @returns 预览结果
   */
  async previewIntent(
    request: IntentExecuteRequest,
    factoryId?: string
  ): Promise<IntentExecuteResponse> {
    const response = await apiClient.post<ApiResponseWrapper<IntentExecuteResponse>>(
      `${this.getBasePath(factoryId)}/preview`,
      request
    );
    return response.data;
  }

  /**
   * 确认执行预览的意图
   *
   * @param proof 完整的参数绑定确认凭证
   * @param factoryId 工厂ID（可选）
   * @returns 执行结果
   */
  async confirmIntent(
    proof: IntentConfirmationProof,
    factoryId?: string
  ): Promise<IntentExecuteResponse> {
    const response = await apiClient.post<ApiResponseWrapper<IntentExecuteResponse>>(
      `${this.getBasePath(factoryId)}/confirm`,
      {
        commandDigest: proof.commandDigest,
        expiresAt: proof.expiresAt,
        requestId: proof.requestId,
        idempotencyKey: proof.idempotencyKey,
      },
      {
        headers: {
          'X-Cretas-Confirmation-Token': proof.confirmToken,
        },
      }
    );
    return response.data;
  }

  // ==================== 权限查询 ====================

  /**
   * 检查意图权限
   *
   * @param intentCode 意图代码
   * @param userRole 用户角色
   * @param factoryId 工厂ID（可选）
   * @returns 权限检查结果
   */
  async checkPermission(
    intentCode: string,
    userRole: string,
    factoryId?: string
  ): Promise<PermissionCheckResult> {
    const response = await apiClient.get<ApiResponseWrapper<PermissionCheckResult>>(
      `${this.getBasePath(factoryId)}/${intentCode}/permission`,
      { params: { userRole } }
    );
    return response.data;
  }

  // ==================== 意图管理 ====================

  /**
   * 创建意图配置
   *
   * @param intentConfig 意图配置
   * @param factoryId 工厂ID（可选）
   * @returns 创建的意图配置
   */
  async createIntent(
    intentConfig: AIIntentConfig,
    factoryId?: string
  ): Promise<AIIntentConfig> {
    const response = await apiClient.post<ApiResponseWrapper<AIIntentConfig>>(
      this.getBasePath(factoryId),
      intentConfig
    );
    return response.data;
  }

  /**
   * 更新意图配置
   *
   * @param intentCode 意图代码
   * @param intentConfig 更新的意图配置
   * @param factoryId 工厂ID（可选）
   * @returns 更新后的意图配置
   */
  async updateIntent(
    intentCode: string,
    intentConfig: Partial<AIIntentConfig>,
    factoryId?: string
  ): Promise<AIIntentConfig> {
    const response = await apiClient.put<ApiResponseWrapper<AIIntentConfig>>(
      `${this.getBasePath(factoryId)}/${intentCode}`,
      { ...intentConfig, intentCode }
    );
    return response.data;
  }

  /**
   * 启用/禁用意图
   *
   * @param intentCode 意图代码
   * @param active 是否启用
   * @param factoryId 工厂ID（可选）
   */
  async setIntentActive(
    intentCode: string,
    active: boolean,
    factoryId?: string
  ): Promise<void> {
    await apiClient.patch(
      `${this.getBasePath(factoryId)}/${intentCode}/active`,
      { active }
    );
  }

  /**
   * 删除意图配置
   *
   * @param intentCode 意图代码
   * @param factoryId 工厂ID（可选）
   */
  async deleteIntent(
    intentCode: string,
    factoryId?: string
  ): Promise<void> {
    await apiClient.delete(
      `${this.getBasePath(factoryId)}/${intentCode}`
    );
  }

  // ==================== 关键词管理 ====================

  /**
   * 添加关键词到意图
   *
   * @param intentCode 意图代码
   * @param keywords 要添加的关键词
   * @param factoryId 工厂ID（可选）
   * @returns 更新后的意图配置
   */
  async addKeywords(
    intentCode: string,
    keywords: string[],
    factoryId?: string
  ): Promise<AIIntentConfig> {
    // 先获取现有配置
    const existing = await this.getIntent(intentCode, factoryId);
    if (!existing) {
      throw new Error(`意图配置不存在: ${intentCode}`);
    }

    // 合并关键词（去重）
    const existingKeywords = existing.keywords ?? [];
    const mergedKeywords = [...new Set([...existingKeywords, ...keywords])];

    // 更新配置
    return this.updateIntent(intentCode, { keywords: mergedKeywords }, factoryId);
  }

  /**
   * 移除意图中的关键词
   *
   * @param intentCode 意图代码
   * @param keywordsToRemove 要移除的关键词
   * @param factoryId 工厂ID（可选）
   * @returns 更新后的意图配置
   */
  async removeKeywords(
    intentCode: string,
    keywordsToRemove: string[],
    factoryId?: string
  ): Promise<AIIntentConfig> {
    // 先获取现有配置
    const existing = await this.getIntent(intentCode, factoryId);
    if (!existing) {
      throw new Error(`意图配置不存在: ${intentCode}`);
    }

    // 过滤掉要移除的关键词
    const existingKeywords = existing.keywords ?? [];
    const filteredKeywords = existingKeywords.filter(
      (kw) => !keywordsToRemove.includes(kw)
    );

    // 更新配置
    return this.updateIntent(intentCode, { keywords: filteredKeywords }, factoryId);
  }

  // ==================== 缓存管理 ====================

  /**
   * 刷新意图缓存
   *
   * @param factoryId 工厂ID（可选）
   */
  async refreshCache(factoryId?: string): Promise<void> {
    await apiClient.post(`${this.getBasePath(factoryId)}/cache/refresh`);
  }

  /**
   * 清除意图缓存
   *
   * @param factoryId 工厂ID（可选）
   */
  async clearCache(factoryId?: string): Promise<void> {
    await apiClient.post(`${this.getBasePath(factoryId)}/cache/clear`);
  }

  // ==================== 分类配置 ====================

  /**
   * 获取意图分类配置（从 system_enums 动态加载）
   *
   * @param factoryId 工厂ID（可选）
   * @returns 分类配置列表
   */
  async getIntentCategoryConfigs(factoryId?: string): Promise<IntentCategoryConfig[]> {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的');
    }
    const response = await apiClient.get<ApiResponseWrapper<IntentCategoryConfig[]>>(
      `/api/mobile/${currentFactoryId}/system-config/enums/INTENT_CATEGORY`
    );
    return response.data ?? [];
  }

  // ==================== 规则管理 ====================

  /**
   * 获取意图的规则配置
   *
   * @param intentCode 意图代码
   * @param factoryId 工厂ID（可选）
   * @returns 规则配置列表
   */
  async getIntentRules(
    intentCode: string,
    factoryId?: string
  ): Promise<IntentRule[]> {
    const response = await apiClient.get<ApiResponseWrapper<IntentRule[]>>(
      `${this.getBasePath(factoryId)}/${intentCode}/rules`
    );
    return response.data ?? [];
  }

  /**
   * 更新意图的规则配置
   *
   * @param intentCode 意图代码
   * @param rules 规则配置列表
   * @param factoryId 工厂ID（可选）
   * @returns 更新后的规则配置列表
   */
  async updateIntentRules(
    intentCode: string,
    rules: IntentRule[],
    factoryId?: string
  ): Promise<IntentRule[]> {
    const response = await apiClient.put<ApiResponseWrapper<IntentRule[]>>(
      `${this.getBasePath(factoryId)}/${intentCode}/rules`,
      rules
    );
    return response.data ?? [];
  }

  // ==================== 参数管理 ====================

  /**
   * 获取意图的参数配置
   *
   * @param intentCode 意图代码
   * @param factoryId 工厂ID（可选）
   * @returns 参数配置列表
   */
  async getIntentParameters(
    intentCode: string,
    factoryId?: string
  ): Promise<IntentParameter[]> {
    const response = await apiClient.get<ApiResponseWrapper<IntentParameter[]>>(
      `${this.getBasePath(factoryId)}/${intentCode}/parameters`
    );
    return response.data ?? [];
  }

  /**
   * 更新意图的参数配置
   *
   * @param intentCode 意图代码
   * @param parameters 参数配置列表
   * @param factoryId 工厂ID（可选）
   * @returns 更新后的参数配置列表
   */
  async updateIntentParameters(
    intentCode: string,
    parameters: IntentParameter[],
    factoryId?: string
  ): Promise<IntentParameter[]> {
    const response = await apiClient.put<ApiResponseWrapper<IntentParameter[]>>(
      `${this.getBasePath(factoryId)}/${intentCode}/parameters`,
      parameters
    );
    return response.data ?? [];
  }

  // ==================== 测试执行 ====================

  /**
   * 测试意图执行
   *
   * @param intentCode 意图代码
   * @param testInput 测试输入
   * @param factoryId 工厂ID（可选）
   * @returns 测试结果
   */
  async testIntent(
    intentCode: string,
    testInput: string,
    factoryId?: string
  ): Promise<IntentTestResult> {
    const response = await apiClient.post<ApiResponseWrapper<IntentTestResult>>(
      `${this.getBasePath(factoryId)}/${intentCode}/test`,
      { userInput: testInput }
    );
    return response.data;
  }
}

// ============ 导出 ============

/**
 * 意图配置 API 客户端单例
 */
export const intentConfigApiClient = new IntentConfigApiClient();

/**
 * 默认导出
 */
export default intentConfigApiClient;
