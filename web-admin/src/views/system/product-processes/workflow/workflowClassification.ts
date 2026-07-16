export type DerivedWorkflowType = 'INCOMPLETE' | 'PRODUCT' | 'RAW_SPLIT' | 'JOINT_PRODUCTION';

export interface WorkflowTopologyNode {
  id: string;
  kind: string;
  skuId?: string;
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
      .map((node) => node.skuId || node.id),
  );
  const rootInputCount = nodes.filter((node) =>
    node.kind !== 'PROCESS' && !incoming.has(node.id) && outgoing.has(node.id)).length;
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
