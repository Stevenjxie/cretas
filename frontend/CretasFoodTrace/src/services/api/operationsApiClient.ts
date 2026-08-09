import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

export type CustomerMaterialArrivalStatus =
  | 'OPEN'
  | 'PARTIALLY_RECEIVED'
  | 'RECEIVED'
  | 'CANCELLED';

export interface CustomerMaterialArrivalNotice {
  id: string;
  factoryId: string;
  noticeNumber: string;
  customerId: string;
  customerName?: string;
  expectedArrivalAt?: string;
  contactName?: string;
  contactPhone?: string;
  remark?: string;
  status: CustomerMaterialArrivalStatus;
  receiptCount: number;
  lastReceivedAt?: string;
  createdAt?: string;
}

export interface CreateCustomerMaterialArrivalNotice {
  customerId: string;
  expectedArrivalAt?: string;
  contactName?: string;
  contactPhone?: string;
  remark?: string;
}

interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
}

class OperationsApiClient {
  private getPath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录');
    }
    return `/api/mobile/${currentFactoryId}/operations/customer-material-arrivals`;
  }

  async listCustomerMaterialArrivals(
    openOnly: boolean,
    factoryId?: string,
  ): Promise<ApiResponse<CustomerMaterialArrivalNotice[]>> {
    return apiClient.get<ApiResponse<CustomerMaterialArrivalNotice[]>>(
      this.getPath(factoryId),
      { params: { openOnly } },
    );
  }

  async createCustomerMaterialArrival(
    payload: CreateCustomerMaterialArrivalNotice,
    factoryId?: string,
  ): Promise<ApiResponse<CustomerMaterialArrivalNotice>> {
    return apiClient.post<ApiResponse<CustomerMaterialArrivalNotice>>(
      this.getPath(factoryId),
      payload,
    );
  }

  async cancelCustomerMaterialArrival(
    noticeId: string,
    factoryId?: string,
  ): Promise<ApiResponse<CustomerMaterialArrivalNotice>> {
    return apiClient.post<ApiResponse<CustomerMaterialArrivalNotice>>(
      `${this.getPath(factoryId)}/${noticeId}/cancel`,
      {},
    );
  }
}

export const operationsApiClient = new OperationsApiClient();
export default operationsApiClient;
