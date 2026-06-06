/**
 * 语音助手类型定义
 * Voice Assistant Type Definitions
 */

// ============================================
// 语音识别 (STT) 类型
// ============================================

export interface SpeechRecognitionConfig {
  /** 讯飞 APPID */
  appId: string;
  /** 讯飞 API Key */
  apiKey: string;
  /** 讯飞 API Secret */
  apiSecret: string;
  /** 语言 */
  language?: 'zh_cn' | 'en_us';
  /** 方言 */
  accent?: 'mandarin' | 'cantonese';
  /** 采样率 */
  sampleRate?: 16000 | 8000;
}

export interface SpeechRecognitionResult {
  /** 识别文本 */
  text: string;
  /** 是否为最终结果 */
  isFinal: boolean;
  /** 置信度 (0-1) */
  confidence?: number;
  /** 识别耗时 (ms) */
  duration?: number;
  /** 本地录音文件 URI（停止录音后可用于上传 OSS） */
  audioUri?: string;
}

export type SpeechRecognitionStatus =
  | 'idle'        // 空闲
  | 'listening'   // 正在听
  | 'processing'  // 处理中
  | 'error';      // 错误

export interface SpeechRecognitionError {
  code: string;
  message: string;
}

// ============================================
// 语音合成 (TTS) 类型
// ============================================

export interface TextToSpeechConfig {
  /** 语音速度 (0.5 - 2.0, 默认 1.0) */
  rate?: number;
  /** 语音音调 (0.5 - 2.0, 默认 1.0) */
  pitch?: number;
  /** 语言 */
  language?: string;
  /** 发音人 */
  voice?: string;
}

export type TextToSpeechStatus =
  | 'idle'      // 空闲
  | 'speaking'  // 播放中
  | 'paused'    // 暂停
  | 'error';    // 错误

// ============================================
// 语音助手类型
// ============================================

export interface VoiceAssistantConfig {
  /** 是否启用 */
  enabled: boolean;
  /** 检验前语音引导 */
  preInspectionGuide: boolean;
  /** 自动切换下一批 */
  autoNextBatch: boolean;
  /** 语音速度: slow | normal | fast */
  speechRate: 'slow' | 'normal' | 'fast';
  /** 复述确认 */
  repeatConfirmation: boolean;
}

export type VoiceAssistantStatus =
  | 'idle'            // 空闲
  | 'listening'       // 正在听用户说话
  | 'processing'      // AI 处理中
  | 'speaking'        // AI 正在说话
  | 'waiting_confirm' // 等待用户确认
  | 'error';          // 错误

// ============================================
// 质检对话类型
// ============================================

export interface InspectionBatch {
  id: string;
  batchNumber: string;
  productName: string;
  quantity: number;
  unit: string;
  source?: string;
}

export interface InspectionItem {
  name: string;
  maxScore: number;
  currentScore?: number;
  notes?: string[];
  isCompleted: boolean;
}

export interface InspectionData {
  sampleSize?: number;
  appearance?: {
    score: number;
    notes: string[];
  };
  smell?: {
    score: number;
    notes: string[];
  };
  specification?: {
    score: number;
    notes: string[];
  };
  weight?: {
    score: number;
    notes: string[];
  };
  packaging?: {
    score: number;
    notes: string[];
  };
}

export interface ChatMessage {
  id: string;
  role: 'ai' | 'user';
  content: string;
  timestamp: Date;
  /** AI 提取的数据 */
  extractedData?: Partial<InspectionData>;
}

// ============================================
// AI 响应类型
// ============================================

export interface AIExtractionResponse {
  /** 动作类型 */
  action: 'extract' | 'confirm' | 'prompt' | 'complete' | 'error';
  /** 提取的数据 */
  extractedData?: Partial<InspectionData>;
  /** 缺失的检验项 */
  missingItems?: string[];
  /** AI 语音响应文本 */
  speechResponse: string;
  /** 是否完成所有检验 */
  isComplete: boolean;
  /** 总分 */
  totalScore?: number;
  /** 建议等级 */
  suggestedGrade?: 'A' | 'B' | 'C' | 'D';
}

// ============================================
// 讯飞 WebSocket 类型
// ============================================

export interface IFlyTekFrame {
  common?: {
    app_id: string;
  };
  business?: {
    language: string;
    domain: string;
    accent: string;
    vad_eos: number;
    dwa?: string;
  };
  data?: {
    status: number;
    format: string;
    encoding: string;
    audio: string;
  };
}

export interface IFlyTekResult {
  code: number;
  message: string;
  sid?: string;
  data?: {
    result?: {
      ws: Array<{
        cw: Array<{
          w: string;
          wp?: string;
        }>;
      }>;
    };
    status: number;
  };
}
