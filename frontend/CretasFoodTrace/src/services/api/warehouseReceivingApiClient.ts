import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

export type UnorderedInboundReason = 'CUSTOMER_MATERIAL' | 'GIFT' | 'OTHER';

export interface CustomerMaterialArrivalTask {
  taskId: string;
  sourceType: 'CUSTOMER_MATERIAL_ARRIVAL';
  sourceId: string;
  sourceNumber: string;
  inboundReason: UnorderedInboundReason;
  customerId?: string | null;
  customerName?: string | null;
  expectedArrivalAt?: string | null;
  status: 'OPEN' | 'PARTIALLY_RECEIVED';
  statusLabel: string;
  warehouseId?: string | null;
  warehouseName?: string | null;
  responsibleName?: string | null;
  activeReceiptId?: string | null;
  activeReceiptNumber?: string | null;
  activeReceiptCount: number;
  receiptConflict: boolean;
  items: unknown[];
}

export interface WarehouseReceivingTaskFilters {
  arrivalNoticeId?: string;
  sourceType?: 'CUSTOMER_MATERIAL_ARRIVAL';
}

export interface CreateCustomerMaterialArrivalReceiptRequest {
  idempotencyKey: string;
  materialTypeId: string;
  warehouseId: string;
  receivedQuantity: number;
  unit: string;
  externalBatchNumber?: string;
  notes?: string;
  completeNotice: boolean;
}

export interface MaterialBatchReceiptResult {
  id: string;
  batchNumber?: string | null;
  quantity?: number | null;
  currentQuantity?: number | null;
  unit?: string | null;
}

interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
}

class WarehouseReceivingApiClient {
  private getPath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录');
    }
    return `/api/mobile/${currentFactoryId}/warehouse/receiving`;
  }

  async listCustomerMaterialArrivalTasks(
    filters?: WarehouseReceivingTaskFilters,
    factoryId?: string,
  ): Promise<ApiResponse<CustomerMaterialArrivalTask[]>> {
    return apiClient.get<ApiResponse<CustomerMaterialArrivalTask[]>>(
      `${this.getPath(factoryId)}/tasks`,
      {
        params: {
          arrivalNoticeId: filters?.arrivalNoticeId || undefined,
          sourceType: 'CUSTOMER_MATERIAL_ARRIVAL',
        },
      },
    );
  }

  async receiveCustomerMaterialArrival(
    noticeId: string,
    payload: CreateCustomerMaterialArrivalReceiptRequest,
    factoryId?: string,
  ): Promise<ApiResponse<MaterialBatchReceiptResult>> {
    return apiClient.post<ApiResponse<MaterialBatchReceiptResult>>(
      `${this.getPath(factoryId)}/arrival-notices/${noticeId}/receipts`,
      payload,
    );
  }
}

export const warehouseReceivingApiClient = new WarehouseReceivingApiClient();
export default warehouseReceivingApiClient;
