/**
 * 工厂物料需求单 API 客户端 (只读)
 *
 * 2026-07-30 客户反馈 (f006_production_mgr): "没有物料需求单模块"。
 *
 * 排查结论: 后端 (FactoryMaterialRequisitionController) 与 web-admin
 * (/production/material-requisitions) 早已实现完整功能 —— 生成/备料/调拨/签收/
 * 关单/取消共 7 个写操作 + 完整状态机 (PENDING → PICKING → TRANSFERRED → ISSUED
 * → IN_USE → CLOSED / CANCELLED)。RN 端从未实现过此模块的任何界面。
 *
 * 把整套写操作workflow 照搬到移动端不是一夜能做完的工作 (对照 web-admin/
 * src/views/factory/material-requisitions/list.vue，仅这一个列表页就有 8 种
 * 状态流转按钮 + 明细表)。本次只落地【只读】查看 —— 让生产经理至少能在手机上
 * 看到需求单状态/明细，不再是完全空白。生成/备料/调拨/签收/关单等写操作仍需
 * 通过管理后台完成，详见报告里的工作量评估。
 *
 * 路径: /api/mobile/{factoryId}/material-requisitions/* (仅消费 GET，不做任何写操作)
 */
import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

export type MaterialRequisitionStatus =
  | 'DRAFT'
  | 'PENDING'
  | 'PICKING'
  | 'TRANSFERRED'
  | 'ISSUED'
  | 'IN_USE'
  | 'CLOSED'
  | 'CANCELLED';

export interface MaterialRequisitionItemDTO {
  id: string;
  materialTypeId: string;
  materialName?: string;
  materialCategory?: 'RAW' | 'AUXILIARY' | 'PACKAGING' | string;
  requiredQty?: number;
  pickedQty?: number;
  issuedQty?: number;
  consumedQty?: number;
  wastageQty?: number;
  returnedQty?: number;
}

export interface MaterialRequisitionDTO {
  id: string;
  factoryId: string;
  requisitionNo: string;
  productionPlanId: string;
  productionPlanNumber?: string;
  productName?: string;
  sourceWarehouseId?: string;
  targetWarehouseId?: string;
  status: MaterialRequisitionStatus;
  requiredDate?: string;
  requestedBy?: number;
  pickedAt?: string;
  transferredAt?: string;
  receivedAt?: string;
  closedAt?: string;
  remarks?: string;
  items?: MaterialRequisitionItemDTO[];
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages?: number;
  size?: number;
  number?: number;
}

class MaterialRequisitionApiClient {
  private getPath(factoryId?: string) {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) throw new Error('factoryId 是必需的，请先登录');
    return `/api/mobile/${fid}/material-requisitions`;
  }

  /** 需求单列表（只读，支持状态过滤） */
  async list(
    params?: { status?: MaterialRequisitionStatus; page?: number; size?: number },
    factoryId?: string,
  ): Promise<{ success: boolean; data: PageResponse<MaterialRequisitionDTO> }> {
    return apiClient.get(this.getPath(factoryId), { params });
  }

  /** 需求单详情（含明细行，只读） */
  async getDetail(
    requisitionId: string,
    factoryId?: string,
  ): Promise<{ success: boolean; data: MaterialRequisitionDTO }> {
    return apiClient.get(`${this.getPath(factoryId)}/${requisitionId}`);
  }
}

export const materialRequisitionApiClient = new MaterialRequisitionApiClient();
