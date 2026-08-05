import type { BomRowMarker } from './bomOverlayMarkers';
import type { ProductProcessNodeKind, WorkflowPosition } from './types';

/**
 * BOM 浮层节点 —— 辅料 / 包材 cell。
 *
 * ⛔ 这些节点【不是工艺定义的一部分】。设计要求「改辅料克数只动 BOM 草稿，
 * 不产生新工艺版本」，而工艺节点一改就会改 revision hash，导致所有钉了旧修订
 * 的 BOM 需要重新对齐。所以浮层节点必须在序列化回工艺定义时被滤掉
 * （见 ProductProcessWorkflowEditor.vue 的 serializeFlowNode 调用点）。
 */
export const BOM_OVERLAY_PREFIX = 'bom-overlay:';

export function isBomOverlayNode(node: { id: string }): boolean {
  return node.id.startsWith(BOM_OVERLAY_PREFIX);
}

export function stripBomOverlay<T extends { id: string }>(nodes: T[]): T[] {
  return nodes.filter((node) => !isBomOverlayNode(node));
}

/**
 * 浮层边(辅料 cell → 工序 / 产出 → 包材 cell 的虚线连接)同样不属于工艺定义。
 * 一条浮层边永远只有一端是浮层节点(另一端是真实工艺节点), 所以 source/target
 * 都要判——只查 source 会漏掉「产出 → 包材」方向(浮层 id 在 target 侧)。
 */
export function stripBomOverlayEdges<T extends { source: string; target: string }>(edges: T[]): T[] {
  return edges.filter(
    (edge) => !isBomOverlayNode({ id: edge.source }) && !isBomOverlayNode({ id: edge.target }),
  );
}

/**
 * 从 BOM 数据派生浮层节点/连线 —— deriveBomOverlay。
 *
 * 布局规则（原型已定）：辅料 cell 挂在它服务的工序正上方（y - AUX_OFFSET_Y）；
 * 包材 cell 挂在它服务的终端产出右侧（x + PACK_OFFSET_X）。连线一律虚线，
 * 不表达真实物料流向 —— 只是「这个 cell 归属于哪个工艺节点」的视觉指向。
 */
const AUX_OFFSET_Y = 220;
const PACK_OFFSET_X = 220;

/** 一行辅料/包材 —— markers 由 Task 2 的 markersForAuxiliaryRow / markersForPackagingRow 预先算好, 这里只负责摆放。 */
export interface BomOverlayRow {
  materialName: string;
  dosageText: string;
  markers: BomRowMarker[];
}

/** deriveBomOverlay 只需要节点的 id/kind/position —— 不要求完整的 ProductProcessWorkflowNode 载荷。 */
export interface BomOverlaySourceNode {
  id: string;
  kind: ProductProcessNodeKind;
  position: WorkflowPosition;
}

export interface BomOverlayInput {
  workflowNodes: BomOverlaySourceNode[];
  auxiliaryByProcess: Record<string, BomOverlayRow[]>;
  packagingByOutput: Record<string, BomOverlayRow[]>;
}

export interface OverlayNodeData {
  rows: BomOverlayRow[];
}

export interface OverlayNode {
  id: string;
  type: 'bomAuxiliary' | 'bomPackaging';
  position: WorkflowPosition;
  data: OverlayNodeData;
}

export interface OverlayEdge {
  id: string;
  source: string;
  target: string;
  style: { strokeDasharray: string };
}

export interface BomOverlayResult {
  nodes: OverlayNode[];
  edges: OverlayEdge[];
}

/**
 * 只对 kind === 'PROCESS' 派生辅料 cell, 只对 kind === 'FINISHED_GOOD' 派生包材 cell。
 * 原料/半成品节点不派生任何浮层。
 *
 * ⛔ 没有辅料的工序仍然派生一个空 rows 的 cell —— 这是防呆要求, 不是可优化掉的
 * 冗余: 用户必须能在画布上看到「这道工序没配辅料」, 而不是「这道工序没有 cell」。
 * 两者传达的信息完全不同, 静默省略会把它们混为一谈。
 */
export function deriveBomOverlay(input: BomOverlayInput): BomOverlayResult {
  const nodes: OverlayNode[] = [];
  const edges: OverlayEdge[] = [];

  for (const node of input.workflowNodes) {
    if (node.kind === 'PROCESS') {
      const auxId = `${BOM_OVERLAY_PREFIX}aux:${node.id}`;
      const rows = input.auxiliaryByProcess[node.id] ?? [];
      nodes.push({
        id: auxId,
        type: 'bomAuxiliary',
        position: { x: node.position.x, y: node.position.y - AUX_OFFSET_Y },
        data: { rows },
      });
      edges.push({
        id: `${BOM_OVERLAY_PREFIX}edge:aux:${node.id}`,
        source: auxId,
        target: node.id,
        style: { strokeDasharray: '5 4' },
      });
    }

    if (node.kind === 'FINISHED_GOOD') {
      const packId = `${BOM_OVERLAY_PREFIX}pack:${node.id}`;
      const rows = input.packagingByOutput[node.id] ?? [];
      nodes.push({
        id: packId,
        type: 'bomPackaging',
        position: { x: node.position.x + PACK_OFFSET_X, y: node.position.y },
        data: { rows },
      });
      edges.push({
        id: `${BOM_OVERLAY_PREFIX}edge:pack:${node.id}`,
        source: node.id,
        target: packId,
        style: { strokeDasharray: '5 4' },
      });
    }
  }

  return { nodes, edges };
}
