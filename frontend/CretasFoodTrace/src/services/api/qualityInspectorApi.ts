/**
 * 质检模块 API 客户端
 * Quality Inspector API Client
 */

import { apiClient } from './apiClient';
import {
  QIBatch,
  QualityRecord,
  QualityStatistics,
  QualityTrendData,
  QualityInspectionForm,
  ClockRecord,
  QINotification,
  QIApiResponse,
  QIPagedResponse,
} from '../../types/qualityInspector';

type QINotificationWire = Omit<QINotification, 'id' | 'type' | 'read'> & {
  id: string | number;
  type: QINotification['type'] | 'ALERT' | 'INFO' | 'WARNING' | 'SUCCESS' | 'SYSTEM';
  read?: boolean;
  isRead?: boolean;
};

const normalizeNotification = (notification: QINotificationWire): QINotification => {
  const normalizedType: QINotification['type'] = (() => {
    switch (notification.type) {
      case 'ALERT':
      case 'WARNING':
        return 'urgent';
      case 'SUCCESS':
        return 'review_result';
      case 'SYSTEM':
        return 'system';
      case 'INFO':
        return 'new_batch';
      default:
        return notification.type;
    }
  })();

  return {
    ...notification,
    id: String(notification.id),
    type: normalizedType,
    read: notification.read ?? notification.isRead ?? false,
  };
};

/**
 * 质检 API 客户端
 */
class QualityInspectorApiClient {
  private factoryId: string = '';

  /**
   * 设置工厂ID
   */
  setFactoryId(factoryId: string): void {
    this.factoryId = factoryId;
  }

  /**
   * 获取基础路径
   */
  private getBasePath(): string {
    if (!this.factoryId) {
      throw new Error('Factory ID not set. Call setFactoryId first.');
    }
    return `/api/mobile/${this.factoryId}`;
  }

  // ============================================
  // 批次相关 API
  // ============================================

  /**
   * 获取待检批次列表
   */
  async getPendingBatches(params?: {
    page?: number;
    size?: number;
    status?: string;
    keyword?: string;
  }): Promise<QIPagedResponse<QIBatch>> {
    const response = await apiClient.get<QIApiResponse<QIPagedResponse<QIBatch>>>(
      `${this.getBasePath()}/processing/batches`,
      {
        params: {
          page: params?.page ?? 1,
          size: params?.size ?? 20,
          status: params?.status ?? 'COMPLETED',
          keyword: params?.keyword,
        },
      }
    );
    return response.data;
  }

  /**
   * 获取批次详情
   */
  async getBatchDetail(batchId: string): Promise<QIBatch> {
    const response = await apiClient.get<QIApiResponse<QIBatch>>(
      `${this.getBasePath()}/processing/batches/${batchId}`
    );
    return response.data;
  }

  /**
   * 通过二维码获取批次
   */
  async getBatchByQRCode(qrData: string): Promise<QIBatch> {
    const response = await apiClient.post<QIApiResponse<QIBatch>>(
      `${this.getBasePath()}/processing/batches/scan`,
      { qrCode: qrData }
    );
    return response.data;
  }

  // ============================================
  // 质检记录相关 API
  // ============================================

  /**
   * 提交质检记录
   */
  async submitInspection(
    batchId: string,
    data: QualityInspectionForm
  ): Promise<QualityRecord> {
    const response = await apiClient.post<QIApiResponse<QualityRecord>>(
      `${this.getBasePath()}/processing/quality/inspections?batchId=${batchId}`,
      data
    );
    return response.data;
  }

  /**
   * 获取质检记录
   */
  async getInspectionRecord(batchId: string): Promise<QualityRecord | null> {
    try {
      const response = await apiClient.get<QIApiResponse<QualityRecord>>(
        `${this.getBasePath()}/processing/quality/inspections?batchId=${batchId}`
      );
      return response.data;
    } catch (error) {
      return null;
    }
  }

  /**
   * 更新质检记录
   */
  async updateInspection(
    batchId: string,
    data: Partial<QualityInspectionForm>
  ): Promise<QualityRecord> {
    const response = await apiClient.put<QIApiResponse<QualityRecord>>(
      `${this.getBasePath()}/processing/quality/inspections?batchId=${batchId}`,
      data
    );
    return response.data;
  }

  /**
   * 将后端质检记录映射为前端 QualityRecord 格式
   * 后端返回: { id, productionBatchId, inspectorId, inspectionDate, sampleSize, passCount, failCount, passRate, result, qualityGrade, ... }
   * 前端期望: { id, batchId, batchNumber, productName, grade, passed, inspectedAt, inspector, totalScore, ... }
   */
  private mapInspectionToRecord(raw: Record<string, unknown>): QualityRecord {
    const passRate = Number(raw.passRate ?? 0);
    return {
      id: String(raw.id ?? ''),
      batchId: String(raw.productionBatchId ?? ''),
      batchNumber: String(raw.batchNumber || `B-${raw.productionBatchId ?? ''}`),
      productName: String(raw.productName || raw.notes || '质检记录'),
      sampleSize: Number(raw.sampleSize ?? 0),
      appearance: { score: 0, notes: [] },
      smell: { score: 0, notes: [] },
      specification: { score: 0, notes: [] },
      weight: { score: 0, notes: [] },
      packaging: { score: 0, notes: [] },
      totalScore: passRate,
      grade: (raw.qualityGrade as QualityRecord['grade']) ?? 'D',
      passed: ['PASS', 'PASSED', 'QUALIFIED', 'CONDITIONAL'].includes(String(raw.result ?? '').toUpperCase()),
      photos: [],
      inspector: {
        id: Number(raw.inspectorId ?? 0),
        name: String(raw.inspectorName || ''),
      },
      inspectedAt: raw.inspectionDate
        ? `${raw.inspectionDate}T00:00:00`
        : new Date().toISOString(),
    };
  }

  /**
   * 获取质检记录列表
   */
  async getInspectionRecords(params?: {
    page?: number;
    size?: number;
    startDate?: string;
    endDate?: string;
    grade?: string;
    inspectorId?: number;
  }): Promise<QIPagedResponse<QualityRecord>> {
    const response = await apiClient.get<QIApiResponse<QIPagedResponse<Record<string, unknown>>>>(
      `${this.getBasePath()}/processing/quality/inspections`,
      { params }
    );
    const data = response.data;
    return {
      ...data,
      content: (data.content || []).map((item) => this.mapInspectionToRecord(item)),
    };
  }

  /**
   * 获取质检记录详情
   */
  async getRecordDetail(recordId: string): Promise<QualityRecord> {
    const response = await apiClient.get<QIApiResponse<Record<string, unknown>>>(
      `${this.getBasePath()}/processing/quality/inspections/${recordId}`
    );
    return this.mapInspectionToRecord(response.data);
  }

  // ============================================
  // 统计相关 API
  // ============================================

  /**
   * 获取质检统计数据
   */
  async getStatistics(): Promise<QualityStatistics> {
    const endDate = new Date().toISOString().split('T')[0];
    const startDate = new Date(Date.now() - 30 * 86400000).toISOString().split('T')[0];
    const response = await apiClient.get<QIApiResponse<QualityStatistics>>(
      `${this.getBasePath()}/processing/quality/statistics`,
      { params: { startDate, endDate } }
    );
    return response.data;
  }

  /**
   * 获取质检趋势数据
   */
  async getTrends(period: 'week' | 'month' | 'quarter' = 'week'): Promise<QualityTrendData> {
    const response = await apiClient.get<QIApiResponse<QualityTrendData>>(
      `${this.getBasePath()}/processing/quality/trends`,
      { params: { period } }
    );
    return response.data;
  }

  /**
   * 获取分析数据 (用于分析概览页)
   */
  async getAnalysisData(period: 'week' | 'month' | 'quarter' = 'week'): Promise<any> {
    const response = await apiClient.get<QIApiResponse<any>>(
      `${this.getBasePath()}/reports/dashboard/quality`,
      { params: { period } }
    );
    return response.data;
  }

  /**
   * 获取趋势数据 (用于趋势分析页)
   */
  async getTrendData(period: '7d' | '30d' | '90d' = '7d'): Promise<any> {
    const days = period === '7d' ? 7 : period === '30d' ? 30 : 90;
    const response = await apiClient.get<QIApiResponse<any>>(
      `${this.getBasePath()}/reports/dashboard/trends`,
      { params: { days } }
    );
    return response.data;
  }

  /**
   * 生成质检报告
   */
  async generateReport(options: {
    type: 'daily' | 'weekly' | 'monthly' | 'custom';
    format: 'pdf' | 'excel';
    options: string[];
    startDate?: string;
    endDate?: string;
  }): Promise<{ url: string; filename: string }> {
    const response = await apiClient.post<QIApiResponse<{ url: string; filename: string }>>(
      `${this.getBasePath()}/reports/quality/generate`,
      options
    );
    return response.data;
  }

  // ============================================
  // 考勤相关 API
  // ============================================

  /**
   * 获取打卡状态
   */
  async getClockStatus(): Promise<ClockRecord> {
    const response = await apiClient.get<QIApiResponse<ClockRecord>>(
      `${this.getBasePath()}/timeclock/status`
    );
    return response.data;
  }

  /**
   * 上班打卡
   */
  async clockIn(location?: { latitude: number; longitude: number }): Promise<ClockRecord> {
    const response = await apiClient.post<QIApiResponse<ClockRecord>>(
      `${this.getBasePath()}/timeclock/clock-in`,
      { location }
    );
    return response.data;
  }

  /**
   * 下班打卡
   */
  async clockOut(location?: { latitude: number; longitude: number }): Promise<ClockRecord> {
    const response = await apiClient.post<QIApiResponse<ClockRecord>>(
      `${this.getBasePath()}/timeclock/clock-out`,
      { location }
    );
    return response.data;
  }

  /**
   * 获取打卡记录
   */
  async getClockRecords(params?: {
    startDate?: string;
    endDate?: string;
    page?: number;
    size?: number;
  }): Promise<QIPagedResponse<ClockRecord>> {
    const response = await apiClient.get<QIApiResponse<QIPagedResponse<ClockRecord>>>(
      `${this.getBasePath()}/timeclock/records`,
      { params }
    );
    return response.data;
  }

  // ============================================
  // 通知相关 API
  // ============================================

  /**
   * 获取通知列表
   */
  async getNotifications(params?: {
    page?: number;
    size?: number;
    unreadOnly?: boolean;
    userId?: number;
  }): Promise<QIPagedResponse<QINotification>> {
    const response = await apiClient.get<QIApiResponse<QIPagedResponse<QINotificationWire>>>(
      `${this.getBasePath()}/notifications`,
      {
        params: {
          page: params?.page,
          size: params?.size,
          isRead: params?.unreadOnly ? false : undefined,
          userId: params?.userId,
        },
      }
    );
    return {
      ...response.data,
      content: response.data.content.map(normalizeNotification),
    };
  }

  /**
   * 标记通知已读
   */
  async markNotificationRead(notificationId: string): Promise<void> {
    await apiClient.put(
      `${this.getBasePath()}/notifications/${notificationId}/read`
    );
  }

  /**
   * 标记所有通知已读
   */
  async markAllNotificationsRead(userId: number): Promise<void> {
    await apiClient.put(`${this.getBasePath()}/notifications/read-all`, undefined, {
      params: { userId },
    });
  }

  /**
   * 获取未读通知数量
   */
  async getUnreadCount(userId: number): Promise<number> {
    const response = await apiClient.get<QIApiResponse<{ count: number }>>(
      `${this.getBasePath()}/notifications/unread-count`,
      { params: { userId } }
    );
    return response.data.count;
  }

  // ============================================
  // 用户相关 API
  // ============================================

  /**
   * 获取当前用户信息
   */
  async getCurrentUser(): Promise<{
    id: number;
    username: string;
    name: string;
    role: string;
    department?: string;
    avatar?: string;
  }> {
    const response = await apiClient.get<QIApiResponse<any>>(
      '/api/mobile/auth/me'
    );
    return response.data;
  }

  /**
   * 更新用户设置
   */
  async updateSettings(settings: {
    voiceEnabled?: boolean;
    voiceGuidance?: boolean;
    autoNextBatch?: boolean;
    voiceSpeed?: 'slow' | 'normal' | 'fast';
  }): Promise<void> {
    await apiClient.put(`${this.getBasePath()}/users/settings`, settings);
  }

  /**
   * 获取用户设置
   */
  async getSettings(): Promise<{
    voiceEnabled: boolean;
    voiceGuidance: boolean;
    autoNextBatch: boolean;
    voiceSpeed: 'slow' | 'normal' | 'fast';
  }> {
    const response = await apiClient.get<QIApiResponse<any>>(
      `${this.getBasePath()}/users/settings`
    );
    return response.data;
  }
}

// 导出单例
export const qualityInspectorApi = new QualityInspectorApiClient();
export default qualityInspectorApi;

// ============================================
// 语音识别 API
// ============================================

/**
 * 语音识别请求
 */
export interface VoiceRecognitionRequest {
  audioData: string;        // Base64 编码的音频
  format?: string;          // 音频格式: raw, mp3, speex
  encoding?: string;        // 编码: raw, lame, speex
  sampleRate?: number;      // 采样率: 16000, 8000
  language?: string;        // 语言: zh_cn, en_us
  ptt?: boolean;            // 是否返回标点
}

/**
 * 语音识别响应
 */
export interface VoiceRecognitionResponse {
  code: number;
  message: string;
  text: string;
  sid?: string;
  isFinal: boolean;
}

/**
 * 语音 API 客户端
 */
export const voiceApi = {
  /**
   * 语音识别
   */
  async recognize(request: VoiceRecognitionRequest): Promise<VoiceRecognitionResponse> {
    const response = await apiClient.post<QIApiResponse<VoiceRecognitionResponse>>(
      '/api/mobile/voice/recognize',
      {
        audioData: request.audioData,
        format: request.format ?? 'raw',
        encoding: request.encoding ?? 'raw',
        sampleRate: request.sampleRate ?? 16000,
        language: request.language ?? 'zh_cn',
        ptt: request.ptt ?? true,
      }
    );
    return response.data;
  },

  /**
   * 检查语音服务状态
   */
  async checkHealth(): Promise<{ available: boolean; version: string; provider: string }> {
    const response = await apiClient.get<QIApiResponse<any>>(
      '/api/mobile/voice/health'
    );
    return response.data;
  },

  /**
   * 获取支持的音频格式
   */
  async getSupportedFormats(): Promise<{
    sampleRates: number[];
    formats: string[];
    encodings: string[];
    languages: string[];
    maxDuration: number;
  }> {
    const response = await apiClient.get<QIApiResponse<any>>(
      '/api/mobile/voice/formats'
    );
    return response.data;
  },
};
