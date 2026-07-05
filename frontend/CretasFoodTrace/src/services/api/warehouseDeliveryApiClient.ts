import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 仓库发货确认 API 客户端 — 真发货 (DLV-*) 流程.
 *
 * 背景 (Issue #740, 六扇门 May10): 销售员创建发货单 (SalesDeliveryRecord, DLV-*) 后,
 * 由仓库确认实际发货数量, 系统据此扣减成品库存 (FinishedGoodsBatch) 并转 SHIPPED + 生成应收.
 * 这是 **真正扣库存** 的流程, 区别于老 shipmentApiClient (SH-*, /shipments) —— 后者只翻转
 * 一个出货记录状态, 零库存移动.
 *
 * 路径:
 *   GET  /api/mobile/{factoryId}/warehouse/deliveries/pending   — 待仓库确认队列 (1-based 分页)
 *   POST /api/mobile/{factoryId}/warehouse/deliveries/{id}/confirm — 确认实发数量 + 扣库存 → SHIPPED
 *   GET  /api/mobile/{factoryId}/sales/deliveries/{id}          — 发货单明细 (含 items[], 销售端只读, 无对应仓库端读接口)
 *
 * 权限: pending 列表 warehouse:read / read_write; confirm 需 warehouse:read_write. 销售角色不可调用.
 */

// ========== 类型定义 (镜像后端 SalesDeliveryRecord / SalesDeliveryItem 序列化字段) ==========

/** 发货单状态 (后端 SalesDeliveryStatus 枚举名). 待确认队列只出现前三种. */
export type SalesDeliveryStatus =
  | 'DRAFT'
  | 'PENDING_WAREHOUSE_CONFIRM'
  | 'PICKED'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'RETURNED';

/** 发货明细行 (sales_delivery_items). id 是 Long, 序列化为 number. */
export interface WarehouseDeliveryItem {
  /** 明细行 id — 即 confirm 请求 actualQuantities 的 key. */
  id: number;
  deliveryRecordId?: string;
  productTypeId?: string;
  /** 冗余显示名 (可能为空, 空则回退 产品-{productTypeId}). */
  productName?: string;
  /** 计划发货数量 (BigDecimal). confirm 时被实发数量覆盖. */
  deliveredQuantity: number;
  unit?: string;
  unitPrice?: number;
  finishedGoodsBatchId?: string;
  sourceWarehouseCode?: string;
  remark?: string;
}

/** 发货单记录 (sales_delivery_records). */
export interface WarehouseDeliveryRecord {
  id: string;
  factoryId?: string;
  /** DLV-* 单号. */
  deliveryNumber: string;
  salesOrderId?: string | null;
  /** @Formula 派生的销售订单号. */
  orderNumber?: string | null;
  customerId?: string;
  /** @Formula 派生的客户名. */
  customerName?: string | null;
  deliveryDate?: string;
  deliveryAddress?: string | null;
  logisticsCompany?: string | null;
  trackingNumber?: string | null;
  status: SalesDeliveryStatus | string;
  totalAmount?: number;
  remark?: string | null;
  items?: WarehouseDeliveryItem[];
  createdAt?: string;
  updatedAt?: string;
}

/** Spring 分页响应 (后端 PageResponse). */
export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  /** page - 1 (0-based 别名). */
  number: number;
}

// ========== API 客户端类 ==========

class WarehouseDeliveryApiClient {
  private warehousePath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/warehouse/deliveries`;
  }

  private salesPath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/sales/deliveries`;
  }

  /**
   * 待仓库确认的发货单队列 (status ∈ DRAFT / PENDING_WAREHOUSE_CONFIRM / PICKED).
   * 注意: 后端分页是 **1-based** (defaultValue="1"), 与老 /shipments (0-based) 不同.
   */
  async getPendingDeliveries(
    params?: { page?: number; size?: number },
    factoryId?: string,
  ): Promise<{ success: boolean; data: PageResponse<WarehouseDeliveryRecord>; message?: string }> {
    return apiClient.get(this.warehousePath(factoryId) + '/pending', {
      params: { page: params?.page ?? 1, size: params?.size ?? 20 },
    });
  }

  /**
   * 发货单明细 (含 items[]). 用于 confirm 前拉取各行计划数量.
   * 走销售端只读接口 (无对应仓库端读接口).
   */
  async getDeliveryDetail(
    deliveryId: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: WarehouseDeliveryRecord; message?: string }> {
    return apiClient.get(this.salesPath(factoryId) + `/${deliveryId}`);
  }

  /**
   * 确认发货 —— 提交每行实际发货数量, 后端扣减成品库存 (FIFO/FEFO) + 转 SHIPPED + 生成应收.
   *
   * @param actualQuantities key = 明细行 id (字符串), value = 实发数量.
   *        省略某行则沿用销售计划数量. 空对象 = 全部按计划数量发货.
   *
   * 前置条件: 每行必须已完成批次分配 (在"发货记录"页点"分配批次"), 否则后端返 409
   *          "发货行 … 未完成批次分配，无法确认发货". 修改数量后需重新分配保证总量匹配.
   */
  async confirmDelivery(
    deliveryId: string,
    actualQuantities: Record<string, number>,
    factoryId?: string,
  ): Promise<{ success: boolean; data: WarehouseDeliveryRecord; message?: string }> {
    return apiClient.post(this.warehousePath(factoryId) + `/${deliveryId}/confirm`, {
      actualQuantities,
    });
  }
}

export const warehouseDeliveryApiClient = new WarehouseDeliveryApiClient();
