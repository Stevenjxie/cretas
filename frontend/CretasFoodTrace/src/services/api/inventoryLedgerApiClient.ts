/**
 * SP11: 进销存台账 API 客户端
 * 路径: /api/mobile/{factoryId}/inventory/ledger
 *
 * 对应后端 InventoryLedgerController — getLedger().
 * 金额字段 (@PriceSensitive) 对非财务角色后端自动返 null.
 */
import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

// ============================================================
// Types — mirror InventoryLedgerLineDTO (camelCase)
// ============================================================

export interface InventoryLedgerLineDTO {
  materialTypeId: string;
  materialCode: string | null;
  materialName: string | null;
  unit: string | null;
  // 数量 (全角色可见)
  openingQty: number | null;
  inboundQty: number | null;
  outboundProductionQty: number | null;  // 生产领用
  outboundSalesQty: number | null;        // 销售出货
  transferInQty: number | null;           // 调拨入
  transferOutQty: number | null;          // 调拨出
  adjustQty: number | null;               // 盘盈(正) / 盘损(负)
  closingQty: number | null;
  // 金额 (@PriceSensitive — 仓管/操作员后端返 null)
  openingAmount: number | null;
  inboundAmount: number | null;
  outboundAmount: number | null;
  adjustAmount: number | null;
  closingAmount: number | null;
  /** 移动均价 (period 末), 非财务角色为 null */
  movingAvgUnitPrice: number | null;
}

export interface InventoryLedgerDTO {
  factoryId: string;
  startDate: string;   // yyyy-MM-dd
  endDate: string;     // yyyy-MM-dd
  lines: InventoryLedgerLineDTO[];
}

export interface GetLedgerParams {
  startDate: string;      // yyyy-MM-dd, required
  endDate: string;        // yyyy-MM-dd, required
  materialTypeId?: string; // optional filter
  factoryId?: string;
}

// ============================================================
// API Response wrapper (matches backend ApiResponse<T>)
// ============================================================
interface ApiResponse<T> {
  success: boolean;
  data: T;
  message: string;
}

// ============================================================
// Client
// ============================================================

class InventoryLedgerApiClient {
  private getBasePath(factoryId?: string): string {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${fid}/inventory/ledger`;
  }

  /**
   * 查询进销存台账.
   *
   * @param params.startDate  期间开始 yyyy-MM-dd (必填)
   * @param params.endDate    期间结束 yyyy-MM-dd (必填)
   * @param params.materialTypeId  可选: 物料类型 ID 过滤
   * @returns InventoryLedgerDTO — lines 每种物料一行
   * @throws 网络/认证/业务错误均以 Error 形式抛出 (拦截器已处理 toast)
   */
  async getLedger(params: GetLedgerParams): Promise<InventoryLedgerDTO> {
    const { factoryId, ...queryParams } = params;
    const response = await apiClient.get<ApiResponse<InventoryLedgerDTO>>(
      this.getBasePath(factoryId),
      { params: queryParams }
    );
    if (!response.success || !response.data) {
      throw new Error(response.message || '进销存台账查询失败');
    }
    return response.data;
  }
}

export const inventoryLedgerApiClient = new InventoryLedgerApiClient();
