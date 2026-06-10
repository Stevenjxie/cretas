/**
 * SP7 盘点 + 报损 API 客户端
 * 路径：
 *   /api/mobile/{factoryId}/stocktakes/*    (盘点任务)
 *   /api/mobile/{factoryId}/wastage-reports/* (报损单)
 */
import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

// ===================== 盘点 DTO =====================

export interface StocktakeItemDTO {
  id: string;
  stocktakeId: string;
  materialBatchId: string;
  rawMaterialTypeId?: string;
  systemQty: number;
  actualQty?: number;
  differenceQty?: number;
  differenceType?: 'SURPLUS' | 'SHORTAGE' | 'MATCH';
  photoUrls?: string;
  notes?: string;
}

export interface StocktakeDTO {
  id: string;
  factoryId: string;
  stocktakeNo: string;
  warehouseId: string;
  periodMonth: string;
  status: 'INITIATED' | 'COUNTING' | 'PENDING_APPROVAL' | 'APPROVED' | 'APPLIED' | 'REJECTED';
  initiatedBy?: number;
  initiatedAt?: string;
  submittedBy?: number;
  submittedAt?: string;
  approvedBy?: number;
  approvedAt?: string;
  rejectReason?: string;
  appliedAt?: string;
  notes?: string;
  createdAt?: string;
  items?: StocktakeItemDTO[];
}

export interface CreateStocktakeRequest {
  warehouseId: string;
  periodMonth: string;
  notes?: string;
}

/** 工厂仓库（盘点发起时选择目标仓库） */
export interface FactoryWarehouseDTO {
  id: string;
  factoryId: string;
  code: string;
  name: string;
  type?: string;
}

export interface StocktakeItemUpdateDTO {
  itemId: string;
  actualQty: number;
  photoUrls?: string;
  notes?: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ===================== 报损 DTO =====================

export type WastageTrackType = 'WAREHOUSE' | 'FACTORY';
export type WastageStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'APPLIED';
export type WastageReason = 'EXPIRED' | 'DAMAGED' | 'CONTAMINATED' | 'THEFT' | 'OTHER';

export interface WastageReportDTO {
  id: string;
  factoryId: string;
  reportNo: string;
  trackType: WastageTrackType;
  warehouseId?: string;
  materialBatchId: string;
  rawMaterialTypeId?: string;
  wastageQty: number;
  wastageReason: WastageReason;
  reasonDetail?: string;
  photoUrls: string;
  status: WastageStatus;
  submittedBy?: number;
  submittedAt?: string;
  approverRole?: string;
  approvedBy?: number;
  approvedAt?: string;
  rejectReason?: string;
  appliedAt?: string;
  notes?: string;
  createdAt?: string;
}

export interface CreateWastageReportRequest {
  trackType: WastageTrackType;
  warehouseId?: string;
  materialBatchId: string;
  rawMaterialTypeId?: string;
  wastageQty: number;
  wastageReason: WastageReason;
  reasonDetail?: string;
  /** JSON 数组字符串 ["url1","url2"] */
  photoUrls: string;
  notes?: string;
}

// ===================== 盘点 API 客户端 =====================

class StocktakeApiClient {
  private getPath(factoryId?: string) {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) throw new Error('factoryId 是必需的，请先登录');
    return `/api/mobile/${fid}/stocktakes`;
  }

  /** 发起盘点任务 */
  async initiate(data: CreateStocktakeRequest, factoryId?: string): Promise<{ success: boolean; data: StocktakeDTO; message?: string }> {
    return apiClient.post(this.getPath(factoryId), data);
  }

  /** 盘点任务列表 */
  async list(params?: { status?: string; page?: number; size?: number }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<StocktakeDTO> }> {
    return apiClient.get(this.getPath(factoryId), { params });
  }

  /** 盘点任务详情（含明细行） */
  async getDetail(stocktakeId: string, factoryId?: string): Promise<{ success: boolean; data: StocktakeDTO }> {
    return apiClient.get(`${this.getPath(factoryId)}/${stocktakeId}`);
  }

  /** 批量更新明细实盘数量（可多次调用，幂等） */
  async updateItems(stocktakeId: string, items: StocktakeItemUpdateDTO[], factoryId?: string): Promise<{ success: boolean; data: null }> {
    return apiClient.put(`${this.getPath(factoryId)}/${stocktakeId}/items`, items);
  }

  /** 提交盘点审批 */
  async submit(stocktakeId: string, factoryId?: string): Promise<{ success: boolean; data: null }> {
    return apiClient.post(`${this.getPath(factoryId)}/${stocktakeId}/submit`);
  }

  /** 工厂仓库列表（盘点发起时选择目标仓库） */
  async listWarehouses(factoryId?: string): Promise<{ success: boolean; data: FactoryWarehouseDTO[] }> {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) throw new Error('factoryId 是必需的，请先登录');
    return apiClient.get(`/api/mobile/${fid}/factory/warehouses`);
  }
}

// ===================== 报损 API 客户端 =====================

class WastageReportApiClient {
  private getPath(factoryId?: string) {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) throw new Error('factoryId 是必需的，请先登录');
    return `/api/mobile/${fid}/wastage-reports`;
  }

  /** 创建报损单 (DRAFT) */
  async create(data: CreateWastageReportRequest, factoryId?: string): Promise<{ success: boolean; data: WastageReportDTO; message?: string }> {
    return apiClient.post(this.getPath(factoryId), data);
  }

  /** 报损单列表 */
  async list(params?: { trackType?: WastageTrackType; status?: WastageStatus; page?: number; size?: number }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<WastageReportDTO> }> {
    return apiClient.get(this.getPath(factoryId), { params });
  }

  /** 报损单详情 */
  async getDetail(reportId: string, factoryId?: string): Promise<{ success: boolean; data: WastageReportDTO }> {
    return apiClient.get(`${this.getPath(factoryId)}/${reportId}`);
  }

  /** 提交报损单审批 */
  async submit(reportId: string, factoryId?: string): Promise<{ success: boolean; data: null }> {
    return apiClient.post(`${this.getPath(factoryId)}/${reportId}/submit`);
  }
}

export const stocktakeApiClient = new StocktakeApiClient();
export const wastageReportApiClient = new WastageReportApiClient();
