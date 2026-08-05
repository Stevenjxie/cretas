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
