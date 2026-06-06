import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

export type PurchaseRequisitionStatus =
  | 'DRAFT'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'CONVERTED_TO_PO'
  | 'REJECTED';

export interface RequisitionItem {
  materialTypeId: string;
  materialName?: string;
  quantity: number;
  unit: string;
  suggestedSupplierId?: string;
  remark?: string;
}

export interface PurchaseRequisition {
  id: string;
  factoryId: string;
  requisitionNumber: string;
  requesterId: number;
  requesterDeptId?: string;
  requestedItems: RequisitionItem[];
  status: PurchaseRequisitionStatus;
  expectedDate?: string;
  reason?: string;
  approverId?: number;
  approvedAt?: string;
  rejectionReason?: string;
  convertedPoId?: string;
  convertedAt?: string;
  remark?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CreatePurchaseRequisitionRequest {
  requesterDeptId?: string;
  requestedItems: RequisitionItem[];
  expectedDate?: string;
  reason?: string;
  remark?: string;
}

export const REQUISITION_STATUS_LABEL: Record<PurchaseRequisitionStatus, string> = {
  DRAFT: '待采购',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已联系供应商',
  CONVERTED_TO_PO: '待送货',
  REJECTED: '已驳回',
};

class PurchaseRequisitionApiClient {
  private basePath(factoryId?: string) {
    const id = getCurrentFactoryId(factoryId);
    if (!id) throw new Error('factoryId is required');
    return `/api/mobile/${id}/purchase-requisitions`;
  }

  async list(params?: {
    status?: PurchaseRequisitionStatus;
    requesterId?: number;
    page?: number;
    size?: number;
    factoryId?: string;
  }) {
    const { factoryId, ...query } = params || {};
    const res = await apiClient.get<any>(this.basePath(factoryId), { params: query });
    const payload = res?.data ?? res;
    const content = payload?.content ?? payload?.data?.content ?? [];
    const totalElements = payload?.totalElements ?? payload?.data?.totalElements ?? 0;
    return { data: Array.isArray(content) ? content : [], totalElements };
  }

  async getOne(id: string, factoryId?: string): Promise<PurchaseRequisition> {
    const res = await apiClient.get<any>(`${this.basePath(factoryId)}/${id}`);
    return res?.data ?? res;
  }

  async create(data: CreatePurchaseRequisitionRequest, factoryId?: string): Promise<PurchaseRequisition> {
    const res = await apiClient.post<any>(this.basePath(factoryId), data);
    return res?.data ?? res;
  }

  async submit(id: string, factoryId?: string): Promise<PurchaseRequisition> {
    const res = await apiClient.post<any>(`${this.basePath(factoryId)}/${id}/submit`);
    return res?.data ?? res;
  }
}

export const purchaseRequisitionApiClient = new PurchaseRequisitionApiClient();
