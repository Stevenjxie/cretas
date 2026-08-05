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
