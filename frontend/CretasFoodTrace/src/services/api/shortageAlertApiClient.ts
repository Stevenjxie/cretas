/**
 * 开工缺料预警 API Client — N1b (Lane B)
 *
 * 复用后端已有接口:
 *  - GET /api/mobile/{factoryId}/bom/tree/{productTypeId}?quantity=X
 *    → BomTreeResult (shortfallLeafCount + leaf nodes with shortfallQuantity)
 *
 * 调用方通过 WorkProcessTask.productTypeId + plannedQuantity 得到缺料快照。
 * 纯只读、不阻断开工 (fool-proof Rule 5: 可见但不 dead-end)。
 */

import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

// ── 镜像后端 BomTreeNode.java ──
export interface BomTreeNode {
  level: number;
  typeId: string;
  name: string;
  requiredQuantity: number | null;
  unit: string | null;
  wastageRate: number | null;
  leaf: boolean;
  cycleDetected: boolean;
  availableQuantity: number | null;
  shortfallQuantity: number | null;
  children: BomTreeNode[];
}

// ── 镜像后端 BomTreeResult.java ──
export interface BomTreeResult {
  rootProductTypeId: string;
  rootQuantity: number;
  maxDepth: number;
  leafCount: number;
  shortfallLeafCount: number;
  cycleDetected: boolean;
  cycleTypeIds: string[];
  root: BomTreeNode;
}

/** 缺料叶子节点（扁平化后用于列表展示） */
export interface ShortageItem {
  materialName: string;
  requiredQuantity: number;
  availableQuantity: number;
  shortfallQuantity: number;
  unit: string;
}

interface ApiEnvelope<T> {
  success: boolean;
  code: number;
  message: string;
  data: T;
}

/** 递归从 BomTreeNode 树中提取所有缺料叶子节点 */
function extractShortageLeaves(node: BomTreeNode): ShortageItem[] {
  const items: ShortageItem[] = [];
  if (node.leaf) {
    const shortfall = node.shortfallQuantity ?? 0;
    if (shortfall > 0) {
      items.push({
        materialName: node.name,
        requiredQuantity: node.requiredQuantity ?? 0,
        availableQuantity: node.availableQuantity ?? 0,
        shortfallQuantity: shortfall,
        unit: node.unit ?? '',
      });
    }
    return items;
  }
  for (const child of node.children ?? []) {
    items.push(...extractShortageLeaves(child));
  }
  return items;
}

class ShortageAlertApiClient {
  private base(factoryId?: string): string {
    const id = getCurrentFactoryId(factoryId);
    if (!id) throw new Error('factoryId 是必需的，请先登录');
    return `/api/mobile/${id}/bom`;
  }

  /**
   * 拉取产品 BOM 缺料快照。
   * @param productTypeId  产品类型 ID
   * @param quantity       计划生产数量（来自 WorkProcessTask.plannedQuantity）
   * @param factoryId      可选，默认取当前工厂
   */
  async fetchBomShortage(
    productTypeId: string,
    quantity: number,
    factoryId?: string,
  ): Promise<{ result: BomTreeResult; shortageItems: ShortageItem[] }> {
    const res = await apiClient.get<ApiEnvelope<BomTreeResult>>(
      `${this.base(factoryId)}/tree/${encodeURIComponent(productTypeId)}`,
      { params: { quantity } },
    );
    const result: BomTreeResult = res.data;
    const shortageItems = result.root ? extractShortageLeaves(result.root) : [];
    return { result, shortageItems };
  }
}

export const shortageAlertApiClient = new ShortageAlertApiClient();
