import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';
import {
  LabelQcReviewTaskRequest,
  LabelQcTaskDetail,
  LabelQcTaskPage,
  LabelQcTaskStatus,
} from '../../types/labelQc';

interface ApiEnvelope<T> {
  success: boolean;
  code: number;
  message: string;
  data: T;
}

class LabelQcApi {
  private base(factoryId?: string): string {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) throw new Error('factoryId 是必需的，请先登录');
    return `/api/mobile/${fid}/label-qc`;
  }

  async createTask(
    request: {
      productTypeId: string;
      batchNumber: string;
      productionDate: string;
      idempotencyKey: string;
    },
    factoryId?: string,
  ): Promise<LabelQcTaskDetail> {
    const response = await apiClient.post<ApiEnvelope<LabelQcTaskDetail>>(
      `${this.base(factoryId)}/tasks`,
      request,
    );
    return response.data;
  }

  async addPhoto(
    taskId: string,
    request: {
      attachmentId: string;
      orderIndex: number;
      imageWidth: number;
      imageHeight: number;
    },
    factoryId?: string,
  ): Promise<void> {
    await apiClient.post<ApiEnvelope<unknown>>(
      `${this.base(factoryId)}/tasks/${taskId}/photos`,
      request,
    );
  }

  async submitTask(taskId: string, factoryId?: string): Promise<LabelQcTaskDetail> {
    const response = await apiClient.post<ApiEnvelope<LabelQcTaskDetail>>(
      `${this.base(factoryId)}/tasks/${taskId}/submit`,
    );
    return response.data;
  }

  async getTask(taskId: string, factoryId?: string): Promise<LabelQcTaskDetail> {
    const response = await apiClient.get<ApiEnvelope<LabelQcTaskDetail>>(
      `${this.base(factoryId)}/tasks/${taskId}`,
    );
    return response.data;
  }

  async listTasks(
    options: {
      statuses?: LabelQcTaskStatus[];
      page?: number;
      size?: number;
    } = {},
    factoryId?: string,
  ): Promise<LabelQcTaskPage> {
    const response = await apiClient.get<ApiEnvelope<LabelQcTaskPage>>(
      `${this.base(factoryId)}/tasks`,
      {
        params: {
          statuses: options.statuses?.join(','),
          page: options.page ?? 1,
          size: options.size ?? 20,
        },
      },
    );
    return response.data;
  }

  async reviewTask(
    taskId: string,
    request: LabelQcReviewTaskRequest,
    factoryId?: string,
  ): Promise<LabelQcTaskDetail> {
    const response = await apiClient.put<ApiEnvelope<LabelQcTaskDetail>>(
      `${this.base(factoryId)}/tasks/${taskId}/review`,
      request,
    );
    return response.data;
  }
}

export const labelQcApi = new LabelQcApi();
