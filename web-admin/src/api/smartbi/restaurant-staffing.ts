import { pythonFetch } from './common';
import type {
  StaffingAdjustmentPayload,
  StaffingAdjustmentReceipt,
  StaffingDashboard,
  StaffingHorizon,
} from '@/types/restaurant-staffing';

interface ApiEnvelope<T> {
  success: boolean;
  data: T;
  message: string;
}

export async function getRestaurantStaffingDashboard(
  horizon: StaffingHorizon,
  storeId?: number,
): Promise<StaffingDashboard> {
  const params = new URLSearchParams({ horizon });
  if (storeId) params.set('store_id', String(storeId));
  const response = await pythonFetch<ApiEnvelope<StaffingDashboard>>(
    `/api/smartbi/restaurant/staffing/dashboard?${params.toString()}`,
    { timeoutMs: 60000 },
  );
  if (!response.success) throw new Error(response.message || '预测排班读取失败');
  return response.data;
}

export async function applyRestaurantStaffingAdjustment(
  payload: StaffingAdjustmentPayload,
): Promise<StaffingAdjustmentReceipt> {
  const response = await pythonFetch<ApiEnvelope<StaffingAdjustmentReceipt>>(
    '/api/smartbi/restaurant/staffing/adjustments',
    {
      method: 'POST',
      timeoutMs: 60000,
      body: JSON.stringify({
        store_id: payload.storeId,
        target_date: payload.targetDate,
        daypart: payload.daypart,
        role_code: payload.roleCode,
        predicted_guests: payload.predictedGuests,
        policy_version: payload.policyVersion,
        prior_staff: payload.priorStaff,
        recommended_staff: payload.recommendedStaff,
        adjusted_staff: payload.adjustedStaff,
        plan_fingerprint: payload.planFingerprint,
        reason: payload.reason,
        idempotency_key: payload.idempotencyKey,
      }),
    },
  );
  if (!response.success) throw new Error(response.message || '排班调整失败');
  return response.data;
}
