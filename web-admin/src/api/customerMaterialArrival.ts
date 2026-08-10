import { get, post } from './request';

export type CustomerMaterialArrivalStatus =
  | 'PENDING_APPROVAL'
  | 'OPEN'
  | 'PARTIALLY_RECEIVED'
  | 'RECEIVED'
  | 'REJECTED'
  | 'CANCELLED';

export type UnorderedInboundReason = 'CUSTOMER_MATERIAL' | 'GIFT' | 'OTHER';

export interface CustomerMaterialArrivalNotice {
  id: string;
  factoryId: string;
  noticeNumber: string;
  reason: UnorderedInboundReason;
  customerId?: string;
  customerName?: string;
  expectedArrivalAt?: string;
  contactName?: string;
  contactPhone?: string;
  remark?: string;
  status: CustomerMaterialArrivalStatus;
  reviewedBy?: number;
  reviewedAt?: string;
  reviewRemark?: string;
  receiptCount: number;
  lastReceivedAt?: string;
  createdAt?: string;
}

export interface CreateCustomerMaterialArrivalNotice {
  reason: UnorderedInboundReason;
  customerId?: string;
  expectedArrivalAt?: string;
  contactName?: string;
  contactPhone?: string;
  remark?: string;
}

export async function listCustomerMaterialArrivals(factoryId: string, openOnly = false) {
  return get<CustomerMaterialArrivalNotice[]>(
    `/${factoryId}/operations/customer-material-arrivals`,
    { params: { openOnly } },
  );
}

export async function createCustomerMaterialArrival(
  factoryId: string,
  payload: CreateCustomerMaterialArrivalNotice,
) {
  return post<CustomerMaterialArrivalNotice>(
    `/${factoryId}/operations/customer-material-arrivals`,
    payload as unknown as Record<string, unknown>,
  );
}

export async function cancelCustomerMaterialArrival(factoryId: string, noticeId: string) {
  return post<CustomerMaterialArrivalNotice>(
    `/${factoryId}/operations/customer-material-arrivals/${noticeId}/cancel`,
    {},
  );
}

export async function approveCustomerMaterialArrival(
  factoryId: string,
  noticeId: string,
  remark?: string,
) {
  return post<CustomerMaterialArrivalNotice>(
    `/${factoryId}/operations/customer-material-arrivals/${noticeId}/approve`,
    { remark: remark?.trim() || undefined },
  );
}

export async function rejectCustomerMaterialArrival(
  factoryId: string,
  noticeId: string,
  remark?: string,
) {
  return post<CustomerMaterialArrivalNotice>(
    `/${factoryId}/operations/customer-material-arrivals/${noticeId}/reject`,
    { remark: remark?.trim() || undefined },
  );
}
