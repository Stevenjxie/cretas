import { apiClient } from './apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';

export type TransitLedgerDirection = 'FINISHED_GOODS_RECEIPT' | 'RAW_MATERIAL_RECEIPT' | string;
export type TransitLedgerStatus = 'PENDING_CONFIRMATION' | string;

export interface TransitLedgerItem {
  id: string;
  direction: TransitLedgerDirection;
  status: TransitLedgerStatus;
  sourceNumber?: string;
  productName?: string;
  materialName?: string;
  batchNumber?: string;
  plannedQuantity?: number;
  reportedQuantity?: number;
  receivedQuantity?: number | null;
  toleranceQuantity?: number;
  unit?: string;
  fromLocation?: string;
  toWarehouseName?: string | null;
  submittedBy?: number | string;
  submittedAt?: string;
  note?: string;
}

export interface TransitConfirmPayload {
  receivedQuantity: number;
  note?: string;
}

export interface WarehouseReceiptConfirmResult {
  settlementId?: string;
  productionPlanId: string;
  planNumber?: string;
  productionReportedQuantity?: number;
  warehouseReceivedQuantity?: number;
  varianceQuantity?: number;
  toleranceQuantity?: number;
  quantityUnit?: string;
  postingStatus?: string;
  finishedGoodsBatchId?: string;
  transitLedgerId?: string;
  message?: string;
  warnings?: string[];
}

export interface ApiEnvelope<T> {
  success: boolean;
  data: T;
  message?: string;
  code?: number;
  actionHint?: string;
  severity?: string;
  hintTarget?: string;
  errorCode?: string;
}

class TransitLedgerApiClient {
  private basePath(factoryId?: string): string {
    return `/api/mobile/${requireFactoryId(factoryId)}/warehouse/transit-ledgers`;
  }

  async listPending(factoryId?: string): Promise<ApiEnvelope<TransitLedgerItem[]>> {
    return apiClient.get<ApiEnvelope<TransitLedgerItem[]>>(this.basePath(factoryId), {
      params: { status: 'PENDING_CONFIRMATION' },
    });
  }

  async confirm(
    productionPlanId: string,
    payload: TransitConfirmPayload,
    factoryId?: string,
  ): Promise<ApiEnvelope<WarehouseReceiptConfirmResult>> {
    return apiClient.post<ApiEnvelope<WarehouseReceiptConfirmResult>>(
      `${this.basePath(factoryId)}/${productionPlanId}/confirm`,
      payload,
    );
  }
}

export const transitLedgerApiClient = new TransitLedgerApiClient();
