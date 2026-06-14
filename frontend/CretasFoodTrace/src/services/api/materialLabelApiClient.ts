import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

export interface ApiResponse<T> {
  success: boolean;
  code?: number;
  message?: string;
  data: T;
}

export interface MaterialBatchLabelScanResponse {
  labelId: string;
  labelCode: string;
  labelStatus: string;
  traceCode?: string | null;
  batchId: string;
  batchNumber: string;
  materialName: string;
  specification?: string | null;
  factoryNumber?: string | null;
  originPlace?: string | null;
  batchCreatedAt?: string | null;
  labelCreatedAt?: string | null;
}

class MaterialLabelApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的, 请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/labels`;
  }

  async scanMaterialBatchLabel(labelCode: string, factoryId?: string): Promise<ApiResponse<MaterialBatchLabelScanResponse>> {
    return await apiClient.get(`${this.getPath(factoryId)}/scan/${encodeURIComponent(labelCode)}`);
  }

  async generateMaterialBatchLabel(batchId: string, factoryId?: string): Promise<ApiResponse<MaterialBatchLabelScanResponse>> {
    return await apiClient.post(`${this.getPath(factoryId)}/material-batch/${encodeURIComponent(batchId)}`);
  }
}

export const materialLabelApiClient = new MaterialLabelApiClient();
