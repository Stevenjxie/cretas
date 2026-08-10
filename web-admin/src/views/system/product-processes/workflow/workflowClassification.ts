export type DerivedWorkflowType = 'INCOMPLETE' | 'PRODUCT' | 'RAW_SPLIT' | 'JOINT_PRODUCTION';

export interface WorkflowTopologyNode {
  id: string;
  kind: string;
  skuId?: string;
  /**
   * 这个产出是副产 —— **不计入终端产出**。副产是「附带出来的物料」不是「要生产的成品」,
   * 与后端 WorkflowTopologyClassifier#isByproduct 同口径(刻意没有 kind:'BYPRODUCT',
   * 副产是与材质分类正交的标记)。
   */
  isByproduct?: boolean;
  /**
   * 本原料是哪个原料的替代料(值 = 被替代原料的 node id)。
   * 互为替代的根原料算**一个**逻辑投入 —— 二选一, 不是同时都要。
   * 与后端 WorkflowTopologyClassifier#logicalRootCount 同一个字段、同一套并查集。
   */
  substituteOfNodeId?: string | null;
}

export interface WorkflowTopologyEdge {
  source: string;
  target: string;
}

export interface WorkflowClassification {
  type: DerivedWorkflowType;
  label: string;
  rootInputCount: number;
  terminalOutputCount: number;
}

/** Workflow 类型只由画布拓扑派生，关联原料/成品不参与判定。 */
export function classifyWorkflowTopology(
  nodes: readonly WorkflowTopologyNode[],
  edges: readonly WorkflowTopologyEdge[],
): WorkflowClassification {
  const incoming = new Set(edges.map((edge) => edge.target));
  const outgoing = new Set(edges.map((edge) => edge.source));
  const terminalOutputIds = new Set(
    nodes
      .filter((node) => node.kind === 'FINISHED_GOOD' && !outgoing.has(node.id))
      .filter((node) => node.isByproduct !== true)
      .map((node) => node.skuId || node.id),
  );
  const rootNodes = nodes.filter((node) =>
    node.kind !== 'PROCESS' && !incoming.has(node.id) && outgoing.has(node.id));
  const rootInputCount = logicalRootCount(rootNodes);
  const terminalOutputCount = terminalOutputIds.size;

  if (terminalOutputCount === 0) {
    return { type: 'INCOMPLETE', label: '待完善画布', rootInputCount, terminalOutputCount };
  }
  if (terminalOutputCount === 1) {
    return { type: 'PRODUCT', label: '产品 Workflow', rootInputCount, terminalOutputCount };
  }
  if (rootInputCount <= 1) {
    return { type: 'RAW_SPLIT', label: '原料分流 Workflow', rootInputCount, terminalOutputCount };
  }
  return { type: 'JOINT_PRODUCTION', label: '联产 Workflow', rootInputCount, terminalOutputCount };
}

/**
 * 互为替代的根原料合成一个逻辑投入。
 *
 * ⚠️ 这是**第二份实现** —— 权威在后端 WorkflowTopologyClassifier#logicalRootCount。
 * 两份必须同口径, 否则画布顶部的「系统研判」标签会和后端归属结论相反。
 * 合法性(自引用/悬空/成链)由后端 ProductProcessWorkflowValidator 保证, 这里只做合并。
 */
function logicalRootCount(rootNodes: readonly WorkflowTopologyNode[]): number {
  if (rootNodes.length === 0) return 0;
  const rootIds = new Set(rootNodes.map((node) => node.id));
  const parent = new Map<string, string>(rootNodes.map((node) => [node.id, node.id]));
  const find = (value: string): string => {
    let current = value;
    while (parent.get(current) !== current) current = parent.get(current) as string;
    parent.set(value, current);
    return current;
  };
  rootNodes.forEach((node) => {
    const target = node.substituteOfNodeId;
    if (!target || !rootIds.has(target)) return;
    const left = find(target);
    const right = find(node.id);
    if (left !== right) parent.set(right, left);
  });
  return new Set(rootNodes.map((node) => find(node.id))).size;
}
