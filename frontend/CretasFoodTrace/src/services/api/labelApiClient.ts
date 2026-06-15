/**
 * 标签管理 API 客户端
 * 路径: /api/mobile/{factoryId}/labels/*
 *
 * SP4-T5: 物料批次标签生成 + 扫码查询 + 打印记录
 */
import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

export interface LabelInfo {
  id: string;
  factoryId: string;
  labelCode: string;
  traceCode?: string | null;
  status: 'ACTIVE' | 'PRINTED' | 'APPLIED' | 'VOIDED';
  batchType?: string | null;
  batchId?: string | null;
  productName?: string | null;
  specification?: string | null;
  printCount?: number;
  createdAt?: string;
}

/** 扫描物料标签响应 (SP4-T5 + A5 数量补缺) */
export interface MaterialBatchLabelScanResponse {
  labelId: string;
  labelCode: string;
  labelStatus: string;
  traceCode?: string | null;
  batchId?: string | null;
  batchNumber?: string | null;
  materialName?: string | null;
  specification?: string | null;     // 数量单位 e.g. "kg" / "箱"
  factoryNumber?: string | null;
  originPlace?: string | null;
  receiptQuantity?: number | null;   // 入库数量
  remainingQuantity?: number | null; // 剩余可用数量
  quantityUnit?: string | null;      // 数量单位
  batchCreatedAt?: string | null;
  labelCreatedAt?: string | null;
}

class LabelApiClient {
  private getPath(factoryId?: string): string {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) throw new Error('factoryId 未设置');
    return `/api/mobile/${fid}/labels`;
  }

  /** SP4-T5: 为物料批次生成 MATERIAL 标签 (防呆: 已有活跃标签时 400) */
  async generateMaterialBatchLabel(
    batchId: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: LabelInfo; message: string }> {
    const base = this.getPath(factoryId);
    return apiClient.post<{ success: boolean; data: LabelInfo; message: string }>(
      `${base}/material-batch/${batchId}`,
    );
  }

  /** SP4-T5: 扫描标签 → 批次信息 */
  async scanLabel(
    labelCode: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: MaterialBatchLabelScanResponse }> {
    const base = this.getPath(factoryId);
    return apiClient.get<{ success: boolean; data: MaterialBatchLabelScanResponse }>(
      `${base}/scan/${encodeURIComponent(labelCode)}`,
    );
  }

  /** 记录打印操作 (labelId + userId via JWT) */
  async recordPrint(
    labelId: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: LabelInfo; message: string }> {
    const base = this.getPath(factoryId);
    return apiClient.post<{ success: boolean; data: LabelInfo; message: string }>(
      `${base}/${labelId}/print`,
    );
  }

  /** 查询批次的所有标签 */
  async getLabelsByBatchId(
    batchId: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: LabelInfo[] }> {
    const base = this.getPath(factoryId);
    return apiClient.get<{ success: boolean; data: LabelInfo[] }>(`${base}/batch/${batchId}`);
  }
}

export const labelApiClient = new LabelApiClient();
