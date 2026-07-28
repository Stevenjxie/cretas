/**
 * SKU 配置语音服务
 *
 * 整合语音识别、AI 解析、语音合成，实现 SKU 配置的语音交互流程
 *
 * @version 1.0.0
 * @since 2026-01-08
 */

import { ttsService } from './TextToSpeechService';
import { speechRecognitionService } from './SpeechRecognitionService';
import { TTS_RATE_MAP } from './config';
import { apiClient } from '../api/apiClient';

/** 后端 GenericChatResponse 的响应信封 (apiClient 拦截器已解包到 axios 的 response.data)。 */
interface GenericChatEnvelope {
  success: boolean;
  message?: string;
  data?: { content?: string };
}
import {
  SKU_CONFIG_SYSTEM_PROMPT,
  buildUserPrompt,
  parseAIResponse,
  buildContext,
  validateExtractedConfig,
  type SkuConfigAIResponse,
  type ExtractedSkuConfig,
} from '../ai/SkuConfigAIPrompt';
import { useSkuConfigVoiceStore } from '../../store/skuConfigVoiceStore';

// ==================== 类型定义 ====================

export type SkuVoiceServiceStatus =
  | 'idle'
  | 'listening'
  | 'processing'
  | 'speaking'
  | 'confirming'
  | 'error';

export interface SkuVoiceServiceConfig {
  /** 是否启用 TTS 播报 */
  enableTTS: boolean;
  /** TTS 语速 */
  speechRate: 'slow' | 'normal' | 'fast';
  /** AI 温度 (0-1) */
  aiTemperature: number;
  /** AI 最大 tokens */
  aiMaxTokens: number;
}

type StatusCallback = (status: SkuVoiceServiceStatus) => void;
type ConfigCallback = (config: ExtractedSkuConfig) => void;
type ErrorCallback = (error: Error) => void;

// ==================== 默认配置 ====================

const DEFAULT_CONFIG: SkuVoiceServiceConfig = {
  enableTTS: true,
  speechRate: 'normal',
  aiTemperature: 0.3,
  aiMaxTokens: 1500,
};

// ==================== 服务实现 ====================

class SkuConfigVoiceService {
  private config: SkuVoiceServiceConfig = DEFAULT_CONFIG;
  private status: SkuVoiceServiceStatus = 'idle';
  private conversationHistory: Array<{ role: 'system' | 'user' | 'assistant'; content: string }> = [];
  private accumulatedConfig: ExtractedSkuConfig | null = null;
  private editModeContext: string | null = null;

  private statusListeners: StatusCallback[] = [];
  private configListeners: ConfigCallback[] = [];
  private errorListeners: ErrorCallback[] = [];

  /**
   * 更新服务配置
   */
  setConfig(config: Partial<SkuVoiceServiceConfig>): void {
    this.config = { ...this.config, ...config };

    if (config.speechRate) {
      ttsService.setRate(config.speechRate);
    }
  }

  /**
   * 获取当前配置
   */
  getConfig(): SkuVoiceServiceConfig {
    return { ...this.config };
  }

  /**
   * 开始语音配置会话
   */
  async startSession(options?: {
    isEditMode?: boolean;
    currentWorkHours?: number;
    currentComplexity?: number;
    currentStepsCount?: number;
  }): Promise<void> {
    // 重置状态
    this.conversationHistory = [];
    this.accumulatedConfig = null;

    // 构建上下文
    if (options) {
      this.editModeContext = buildContext(options);
    } else {
      this.editModeContext = null;
    }

    // 初始化对话
    this.conversationHistory.push({
      role: 'system',
      content: SKU_CONFIG_SYSTEM_PROMPT,
    });

    // 播放欢迎语
    const welcomeMessage = options?.isEditMode
      ? '请说出您想修改的配置内容，比如"把工时改成5小时"或"添加一个蒸煮环节"。'
      : '请描述您的产品类型和加工配置，比如"这是一个海鲜预制菜，需要解冻、清洗、切割、调味、烹饪、包装这几个环节，工时大概4小时"。';

    if (this.config.enableTTS) {
      this.updateStatus('speaking');
      await ttsService.speak(welcomeMessage, {
        rate: TTS_RATE_MAP[this.config.speechRate],
      });
    }

    this.updateStatus('idle');
  }

  /**
   * 开始录音
   */
  async startListening(): Promise<void> {
    try {
      this.updateStatus('listening');
      await speechRecognitionService.startListening();
    } catch (error) {
      this.handleError(error instanceof Error ? error : new Error('启动语音识别失败'));
      throw error;
    }
  }

  /**
   * 停止录音并处理
   */
  async stopListening(): Promise<SkuConfigAIResponse | null> {
    try {
      this.updateStatus('processing');

      // 获取语音识别结果
      const result = await speechRecognitionService.stopListening();

      if (result.text && result.text.trim()) {
        // 处理用户输入
        return await this.processUserInput(result.text);
      } else {
        // 没有识别到内容
        const noInputMessage = '抱歉，没有听清楚，请再说一次。';

        if (this.config.enableTTS) {
          this.updateStatus('speaking');
          await ttsService.speak(noInputMessage);
        }

        this.updateStatus('idle');
        return null;
      }
    } catch (error) {
      this.handleError(error instanceof Error ? error : new Error('处理语音失败'));
      throw error;
    }
  }

  /**
   * 处理文本输入 (支持手动输入)
   */
  async processTextInput(text: string): Promise<SkuConfigAIResponse | null> {
    return await this.processUserInput(text);
  }

  /**
   * 处理用户输入
   */
  private async processUserInput(userText: string): Promise<SkuConfigAIResponse | null> {
    try {
      this.updateStatus('processing');

      // 构建用户 prompt
      const userPrompt = buildUserPrompt(userText, this.editModeContext || undefined);

      // 添加到对话历史
      this.conversationHistory.push({
        role: 'user',
        content: userPrompt,
      });

      // 调用 AI
      const aiResponse = await this.callAI();

      if (aiResponse) {
        // 添加 AI 响应到历史
        this.conversationHistory.push({
          role: 'assistant',
          content: JSON.stringify(aiResponse),
        });

        // 合并配置
        if (aiResponse.skuConfig) {
          this.mergeConfig(aiResponse.skuConfig);
        }

        // 播报 AI 响应
        if (this.config.enableTTS && aiResponse.message) {
          this.updateStatus('speaking');
          await ttsService.speak(aiResponse.message, {
            rate: TTS_RATE_MAP[this.config.speechRate],
          });
        }

        // 根据 action 更新状态
        this.updateStatusFromAction(aiResponse.action);

        return aiResponse;
      }

      this.updateStatus('idle');
      return null;
    } catch (error) {
      this.handleError(error instanceof Error ? error : new Error('处理失败'));
      throw error;
    }
  }

  /**
   * 调用 AI API
   */
  private async callAI(): Promise<SkuConfigAIResponse | null> {
    try {
      // 2026-07-29: 从裸 fetch 改走 apiClient —— 它的拦截器注入 Bearer token 并处理
      // 401 刷新。原来不带任何凭证也能通, 是因为 /api/mobile/ai/chat 当时压根不校验登录
      // (JwtAuthInterceptor 只对能解析出 factoryId 的路径做 401)。那个洞已经堵上,
      // 不带 token 的调用从此 401。
      const data = await apiClient.post<GenericChatEnvelope>('/api/mobile/ai/chat', {
        messages: this.conversationHistory,
        temperature: this.config.aiTemperature,
        maxTokens: this.config.aiMaxTokens,
      });

      if (data?.success && data.data?.content) {
        return parseAIResponse(data.data.content);
      }

      throw new Error(data?.message || 'AI 响应解析失败');
    } catch (error) {
      console.error('AI 调用失败:', error);

      // 返回错误响应
      return {
        action: 'error',
        message: '抱歉，AI 服务暂时不可用，请稍后重试或手动输入配置。',
      };
    }
  }

  /**
   * 根据 AI action 更新状态
   */
  private updateStatusFromAction(action: string): void {
    switch (action) {
      case 'extract':
      case 'confirm':
      case 'complete':
        this.updateStatus('confirming');
        break;
      case 'prompt':
        this.updateStatus('idle');
        break;
      case 'error':
        this.updateStatus('error');
        break;
      default:
        this.updateStatus('idle');
    }
  }

  /**
   * 合并配置
   */
  private mergeConfig(newConfig: ExtractedSkuConfig): void {
    if (!this.accumulatedConfig) {
      this.accumulatedConfig = newConfig;
    } else {
      this.accumulatedConfig = {
        ...this.accumulatedConfig,
        ...newConfig,
        processingSteps: newConfig.processingSteps || this.accumulatedConfig.processingSteps,
        skillRequirements: {
          minLevel: newConfig.skillRequirements?.minLevel ?? this.accumulatedConfig.skillRequirements?.minLevel ?? 1,
          preferredLevel: newConfig.skillRequirements?.preferredLevel ?? this.accumulatedConfig.skillRequirements?.preferredLevel ?? 1,
          specialSkills: [
            ...(this.accumulatedConfig.skillRequirements?.specialSkills || []),
            ...(newConfig.skillRequirements?.specialSkills || []),
          ].filter((v, i, a) => a.indexOf(v) === i),
        },
      };
    }

    // 通知配置更新
    if (this.accumulatedConfig) {
      this.configListeners.forEach((cb) => cb(this.accumulatedConfig!));
    }
  }

  /**
   * 获取累积的配置
   */
  getAccumulatedConfig(): ExtractedSkuConfig | null {
    return this.accumulatedConfig;
  }

  /**
   * 验证当前配置是否完整
   */
  validateConfig(): { isValid: boolean; missingFields: string[] } {
    if (!this.accumulatedConfig) {
      return { isValid: false, missingFields: ['所有配置'] };
    }
    return validateExtractedConfig(this.accumulatedConfig);
  }

  /**
   * 确认配置
   */
  async confirmConfig(): Promise<ExtractedSkuConfig | null> {
    const config = this.accumulatedConfig;

    if (config && this.config.enableTTS) {
      await ttsService.speak('配置已确认，正在应用到产品类型。');
    }

    this.updateStatus('idle');
    return config;
  }

  /**
   * 取消配置
   */
  async cancel(): Promise<void> {
    await speechRecognitionService.cancel();
    await ttsService.stop();

    this.accumulatedConfig = null;
    this.conversationHistory = [];
    this.editModeContext = null;
    this.updateStatus('idle');
  }

  /**
   * 重新开始
   */
  async retry(): Promise<void> {
    await ttsService.stop();
    this.updateStatus('idle');
  }

  /**
   * 获取当前状态
   */
  getStatus(): SkuVoiceServiceStatus {
    return this.status;
  }

  /**
   * 添加状态监听器
   */
  addStatusListener(callback: StatusCallback): () => void {
    this.statusListeners.push(callback);
    return () => {
      this.statusListeners = this.statusListeners.filter((cb) => cb !== callback);
    };
  }

  /**
   * 添加配置监听器
   */
  addConfigListener(callback: ConfigCallback): () => void {
    this.configListeners.push(callback);
    return () => {
      this.configListeners = this.configListeners.filter((cb) => cb !== callback);
    };
  }

  /**
   * 添加错误监听器
   */
  addErrorListener(callback: ErrorCallback): () => void {
    this.errorListeners.push(callback);
    return () => {
      this.errorListeners = this.errorListeners.filter((cb) => cb !== callback);
    };
  }

  /**
   * 更新状态
   */
  private updateStatus(status: SkuVoiceServiceStatus): void {
    this.status = status;
    this.statusListeners.forEach((cb) => cb(status));
  }

  /**
   * 处理错误
   */
  private handleError(error: Error): void {
    console.error('SKU 语音配置错误:', error);
    this.updateStatus('error');
    this.errorListeners.forEach((cb) => cb(error));
  }
}

// 导出单例
export const skuConfigVoiceService = new SkuConfigVoiceService();
export default skuConfigVoiceService;
