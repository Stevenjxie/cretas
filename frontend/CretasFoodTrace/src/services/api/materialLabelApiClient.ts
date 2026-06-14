import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

export interface ApiResponse<T> {
  success: boolean;
  code?: number;
  message?: string;
  data: T;
}

/**
 * SP4-T5 扫码响应 — 匹配后端 MaterialBatchLabelScanResponse DTO.
 * A5 补缺: 包含入库量/剩余量/单位, 满足六扇门仓管扫码弹窗需求.
 */
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
  // A5 补缺: 数量/重量字段 — 缺值为 null，不会补 0
  receiptQuantity?: number | null;
  remainingQuantity?: number | null;
  quantityUnit?: string | null;
  batchCreatedAt?: string | null;
  labelCreatedAt?: string | null;
}

/**
 * 生成标签响应 — 匹配后端 Label 实体 (LabelController.generateMaterialBatchLabel 返回
 * Label entity, 非 MaterialBatchLabelScanResponse DTO).
 */
export interface LabelResponse {
  id: string;
  factoryId: string;
  labelCode: string;
  labelType?: string | null;
  batchType?: string | null;
  batchId?: string | null;
  productionBatchId?: number | null;
  traceCode?: string | null;
  qrContent?: string | null;
  status: string;
  printCount?: number | null;
  lastPrintedAt?: string | null;
  printedBy?: number | null;
  appliedAt?: string | null;
  appliedBy?: number | null;
  productName?: string | null;
  specification?: string | null;
  productionDate?: string | null;
  shelfLifeDays?: number | null;
  expiryDate?: string | null;
  extraData?: string | null;
  createdBy?: number | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

class MaterialLabelApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的, 请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/labels`;
  }

  /** 扫码查询 — GET /labels/scan/{labelCode} */
  async scanMaterialBatchLabel(labelCode: string, factoryId?: string): Promise<ApiResponse<MaterialBatchLabelScanResponse>> {
    return await apiClient.get(`${this.getPath(factoryId)}/scan/${encodeURIComponent(labelCode)}`);
  }

  /**
   * 生成物料批次标签 — POST /labels/material-batch/{batchId}.
   * 后端返回 Label 实体 (非 ScanResponse DTO).
   */
  async generateMaterialBatchLabel(batchId: string, factoryId?: string): Promise<ApiResponse<LabelResponse>> {
    return await apiClient.post(`${this.getPath(factoryId)}/material-batch/${encodeURIComponent(batchId)}`);
  }
}

export const materialLabelApiClient = new MaterialLabelApiClient();
