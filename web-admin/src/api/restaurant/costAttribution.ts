import { get } from '../request';
import type { ApiResponse } from '@/types/api';

export interface RestaurantCostAttributionBucket {
  key: string;
  label: string;
  count: number;
  totalQuantity?: number | null;
  totalCost?: number | null;
}

export interface RestaurantCostAttributionSummary {
  startDate: string;
  endDate: string;
  totalCost?: number | null;
  totalCount: number;
  bySource: RestaurantCostAttributionBucket[];
  bySection: RestaurantCostAttributionBucket[];
  byStall: RestaurantCostAttributionBucket[];
  byPerson: RestaurantCostAttributionBucket[];
  byChef: RestaurantCostAttributionBucket[];
}

const base = (factoryId: string) => `/${factoryId}/restaurant/cost-attribution`;

export function getRestaurantCostAttributionSummary(
  factoryId: string,
  params: { startDate?: string; endDate?: string },
): Promise<ApiResponse<RestaurantCostAttributionSummary>> {
  return get(`${base(factoryId)}/summary`, { params });
}
