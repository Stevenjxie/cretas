// Sprint 4 W1 S-CUSTOMER-TAB-1: customer detail + assigned-sales-user API.
//
// Backend:
//   GET    /api/mobile/{factoryId}/customers/{id}                      (existing)
//   POST   /api/mobile/{factoryId}/customers/{id}/assigned-sales-user  (Phase A A5)
//
// axios baseURL = '/api/mobile' so caller-side paths start with `/{factoryId}/...`
// 4 位一体: backend BusinessException(409).withHint(actionHint) propagates via
// ApiError so caller can ElMessageBox.confirm + router.push(actionHint).
import { get, post } from './request';
import { ApiError } from '@/types/api';

export interface Customer {
  id: string;
  factoryId: string;
  customerCode: string;
  name: string;
  type?: string;
  customerType?: string;
  industry?: string;
  contactPerson?: string;
  contactName?: string;
  phone?: string;
  contactPhone?: string;
  email?: string;
  contactEmail?: string;
  shippingAddress?: string;
  billingAddress?: string;
  taxNumber?: string;
  businessLicense?: string;
  paymentTerms?: string;
  creditLimit?: number;
  currentBalance?: number;
  rating?: number;
  ratingNotes?: string;
  isActive?: boolean;
  notes?: string;
  // Sprint 4 W1 (tab 20)
  assignedSalesUserId?: number | null;
  assignedSalesUserAssignedAt?: string | null;
  /** Backend-resolved display name (full_name or fallback to username). */
  assignedSalesUserName?: string | null;
  // audit
  createdAt?: string;
  updatedAt?: string;
  createdBy?: number;
  createdByName?: string;
  version?: number;
}

export interface UpdateAssignedSalesUserRequest {
  newSalesUserId: number;
  reason: string;
}

export async function getCustomer(factoryId: string, customerId: string): Promise<Customer> {
  const res = await get<Customer>(`/${factoryId}/customers/${customerId}`);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '客户加载失败', res.code);
  }
  return res.data;
}

export async function updateAssignedSalesUser(
  factoryId: string,
  customerId: string,
  body: UpdateAssignedSalesUserRequest,
): Promise<Customer> {
  const res = await post<Customer>(
    `/${factoryId}/customers/${customerId}/assigned-sales-user`,
    body as unknown as Record<string, unknown>,
  );
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '业务员变更失败', res.code);
  }
  return res.data;
}
