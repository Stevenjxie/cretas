/**
 * 语音助手核心服务
 * Voice Assistant Core Service
 *
 * 整合语音识别、语音合成和 AI 对话功能
 */

import { v4 as uuidv4 } from 'uuid';
import { ttsService } from './TextToSpeechService';
import { speechRecognitionService } from './SpeechRecognitionService';
import {
  VoiceAssistantConfig,
  VoiceAssistantStatus,
  InspectionBatch,
  InspectionData,
  ChatMessage,
  AIExtractionResponse,
} from './types';
import { DEFAULT_VOICE_ASSISTANT_CONFIG, TTS_RATE_MAP } from './config';
// calculateTotalScore / generateCompletionSummary / getMissingItems / generateErrorResponse
// 之前只被已删掉的 generateMockResponse 用（generateErrorResponse 更早就没人用了），
// 一并从 import 里摘掉；它们在 QualityInspectionAIPrompt 里仍然导出，将来要用再取。
import {
  QUALITY_INSPECTION_SYSTEM_PROMPT,
  generateStartPrompt,
  generateUserContext,
  parseAIResponse,
  isInspectionComplete,
} from './QualityInspectionAIPrompt';
import { apiClient } from '../api/apiClient';

/** 后端 GenericChatResponse 的响应信封 (apiClient 拦截器已解包到 axios 的 response.data)。 */
interface GenericChatEnvelope {
  success: boolean;
  message?: string;
  data?: { content?: string };
}

type StatusCallback = (status: VoiceAssistantStatus) => void;
type MessageCallback = (message: ChatMessage) => void;
type DataCallback = (data: Partial<InspectionData>) => void;
type ErrorCallback = (error: Error) => void;

class VoiceAssistantService {
  private config: VoiceAssistantConfig = DEFAULT_VOICE_ASSISTANT_CONFIG;
  private status: VoiceAssistantStatus = 'idle';
  private currentBatch: InspectionBatch | null = null;
  private inspectionData: Partial<InspectionData> = {};
  private chatHistory: ChatMessage[] = [];

  private statusListeners: StatusCallback[] = [];
  private messageListeners: MessageCallback[] = [];
  private dataListeners: DataCallback[] = [];
  private errorListeners: ErrorCallback[] = [];

  /**
   * 更新配置
   */
  setConfig(config: Partial<VoiceAssistantConfig>): void {
    this.config = { ...this.config, ...config };

    // 同步 TTS 语速设置
    if (config.speechRate) {
      ttsService.setRate(config.speechRate);
    }
  }

  /**
   * 获取配置
   */
  getConfig(): VoiceAssistantConfig {
    return { ...this.config };
  }

  /**
   * 开始质检会话
   */
  async startInspection(batch: InspectionBatch): Promise<void> {
    if (!this.config.enabled) {
      throw new Error('语音助手未启用');
    }

    // 重置状态
    this.currentBatch = batch;
    this.inspectionData = {};
    this.chatHistory = [];

    // 生成开场白
    const startPrompt = generateStartPrompt(batch);

    // 添加 AI 消息
    this.addAIMessage(startPrompt);

    // 朗读开场白（如果启用语音引导）
    if (this.config.preInspectionGuide) {
      this.updateStatus('speaking');
      await ttsService.speak(startPrompt, {
        rate: TTS_RATE_MAP[this.config.speechRate],
      });
    }

    this.updateStatus('idle');
  }

  /**
   * 开始聆听用户输入
   */
  async startListening(): Promise<void> {
    if (!this.currentBatch) {
      throw new Error('请先开始质检会话');
    }

    try {
      this.updateStatus('listening');
      await speechRecognitionService.startListening();
    } catch (error) {
      this.handleError(error instanceof Error ? error : new Error('启动语音识别失败'));
      throw error;
    }
  }

  /**
   * 停止聆听并处理结果
   */
  async stopListening(): Promise<void> {
    try {
      this.updateStatus('processing');

      // 获取语音识别结果
      const result = await speechRecognitionService.stopListening();

      if (result.text && result.text.trim()) {
        // 添加用户消息
        this.addUserMessage(result.text);

        // 处理用户输入
        await this.processUserInput(result.text);
      } else {
        // 没有识别到内容
        const noInputMessage = '抱歉，没有听清楚，请再说一次。';
        this.addAIMessage(noInputMessage);

        if (this.config.repeatConfirmation) {
          this.updateStatus('speaking');
          await ttsService.speak(noInputMessage);
        }

        this.updateStatus('idle');
      }
    } catch (error) {
      this.handleError(error instanceof Error ? error : new Error('处理语音失败'));
    }
  }

  /**
   * 处理文本输入（支持手动输入）
   */
  async processTextInput(text: string): Promise<void> {
    if (!this.currentBatch) {
      throw new Error('请先开始质检会话');
    }

    // 添加用户消息
    this.addUserMessage(text);

    // 处理输入
    await this.processUserInput(text);
  }

  /**
   * 处理用户输入
   */
  private async processUserInput(userText: string): Promise<void> {
    try {
      this.updateStatus('processing');

      // 调用 AI 进行分析
      const aiResponse = await this.callAI(userText);

      if (aiResponse) {
        // 更新检验数据
        if (aiResponse.extractedData) {
          this.mergeInspectionData(aiResponse.extractedData);
        }

        // 添加 AI 消息
        this.addAIMessage(aiResponse.speechResponse, aiResponse.extractedData);

        // 朗读 AI 响应
        if (this.config.repeatConfirmation) {
          this.updateStatus('speaking');
          await ttsService.speak(aiResponse.speechResponse, {
            rate: TTS_RATE_MAP[this.config.speechRate],
          });
        }

        // 检查是否完成
        if (aiResponse.isComplete) {
          this.updateStatus('waiting_confirm');
        } else {
          this.updateStatus('idle');
        }
      } else {
        // AI 解析失败
        const errorMessage = '抱歉，我没有理解您说的内容，请再说一次。';
        this.addAIMessage(errorMessage);

        if (this.config.repeatConfirmation) {
          this.updateStatus('speaking');
          await ttsService.speak(errorMessage);
        }

        this.updateStatus('idle');
      }
    } catch (error) {
      this.handleError(error instanceof Error ? error : new Error('处理失败'));
    }
  }

  /**
   * 调用 AI 进行分析
   */
  private async callAI(userText: string): Promise<AIExtractionResponse | null> {
    if (!this.currentBatch) return null;

    try {
      // 构建上下文
      const userContext = generateUserContext(
        this.currentBatch,
        this.inspectionData,
        userText
      );

      // 2026-07-29: 从裸 fetch 改走 apiClient —— 它的拦截器注入 Bearer token 并处理
      // 401 刷新。原来不带任何凭证也能通, 是因为 /api/mobile/ai/chat 当时压根不校验登录
      // (JwtAuthInterceptor 只对能解析出 factoryId 的路径做 401)。那个洞已经堵上,
      // 不带 token 的调用从此 401。
      const data = await apiClient.post<GenericChatEnvelope>('/api/mobile/ai/chat', {
        messages: [
          { role: 'system', content: QUALITY_INSPECTION_SYSTEM_PROMPT },
          { role: 'user', content: userContext },
        ],
        temperature: 0.3,
        maxTokens: 1000,
      });

      if (data?.success && data.data?.content) {
        return parseAIResponse(data.data.content);
      }

      throw new Error(data?.message || 'AI 响应解析失败');
    } catch (error) {
      // ⛔ 这里曾经 `return this.generateMockResponse(userText)` —— AI 一挂就按关键词
      // 编出「外观 18 分 / 气味 20 分」之类的质检评分喂回业务流。质检数据造假比功能
      // 不可用严重得多, 且违反项目「禁止降级处理」原则。现在如实往上抛,
      // 由 processUserInput 的 catch → handleError 走错误通道让用户看见。
      console.error('AI 调用失败:', error);
      throw error instanceof Error ? error : new Error('AI 服务调用失败');
    }
  }


  /**
   * 合并检验数据
   */
  private mergeInspectionData(newData: Partial<InspectionData>): void {
    this.inspectionData = {
      ...this.inspectionData,
      ...newData,
    };

    // 通知数据更新
    this.dataListeners.forEach((cb) => cb(this.inspectionData));
  }

  /**
   * 确认提交
   */
  async confirmSubmit(): Promise<InspectionData> {
    if (!isInspectionComplete(this.inspectionData)) {
      throw new Error('检验未完成，还有未填写的项目');
    }

    const finalData = this.inspectionData as InspectionData;

    // 朗读确认
    const confirmMessage = '检验记录已提交成功！';
    this.addAIMessage(confirmMessage);

    if (this.config.repeatConfirmation) {
      await ttsService.speak(confirmMessage);
    }

    return finalData;
  }

  /**
   * 重新检验
   */
  async resetInspection(): Promise<void> {
    if (!this.currentBatch) {
      throw new Error('当前没有进行中的质检');
    }

    this.inspectionData = {};
    this.chatHistory = [];

    // 重新开始
    await this.startInspection(this.currentBatch);
  }

  /**
   * 取消并返回
   */
  async cancel(): Promise<void> {
    await speechRecognitionService.cancel();
    await ttsService.stop();

    this.currentBatch = null;
    this.inspectionData = {};
    this.chatHistory = [];
    this.updateStatus('idle');
  }

  /**
   * 添加 AI 消息
   */
  private addAIMessage(content: string, extractedData?: Partial<InspectionData>): void {
    const message: ChatMessage = {
      id: uuidv4(),
      role: 'ai',
      content,
      timestamp: new Date(),
      extractedData,
    };

    this.chatHistory.push(message);
    this.messageListeners.forEach((cb) => cb(message));
  }

  /**
   * 添加用户消息
   */
  private addUserMessage(content: string): void {
    const message: ChatMessage = {
      id: uuidv4(),
      role: 'user',
      content,
      timestamp: new Date(),
    };

    this.chatHistory.push(message);
    this.messageListeners.forEach((cb) => cb(message));
  }

  /**
   * 获取聊天历史
   */
  getChatHistory(): ChatMessage[] {
    return [...this.chatHistory];
  }

  /**
   * 获取当前检验数据
   */
  getInspectionData(): Partial<InspectionData> {
    return { ...this.inspectionData };
  }

  /**
   * 获取当前状态
   */
  getStatus(): VoiceAssistantStatus {
    return this.status;
  }

  /**
   * 获取当前批次
   */
  getCurrentBatch(): InspectionBatch | null {
    return this.currentBatch;
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
   * 添加消息监听器
   */
  addMessageListener(callback: MessageCallback): () => void {
    this.messageListeners.push(callback);
    return () => {
      this.messageListeners = this.messageListeners.filter((cb) => cb !== callback);
    };
  }

  /**
   * 添加数据监听器
   */
  addDataListener(callback: DataCallback): () => void {
    this.dataListeners.push(callback);
    return () => {
      this.dataListeners = this.dataListeners.filter((cb) => cb !== callback);
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
  private updateStatus(status: VoiceAssistantStatus): void {
    this.status = status;
    this.statusListeners.forEach((cb) => cb(status));
  }

  /**
   * 处理错误
   */
  private handleError(error: Error): void {
    console.error('语音助手错误:', error);
    this.updateStatus('error');
    this.errorListeners.forEach((cb) => cb(error));
  }
}

// 导出单例
export const voiceAssistantService = new VoiceAssistantService();
export default voiceAssistantService;
