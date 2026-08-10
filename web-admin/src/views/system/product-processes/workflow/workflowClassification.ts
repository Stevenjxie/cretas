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
  /**
   * 这张图的终端产出 skuId(副产已剔除), **升序**。
   *
   * 与后端 WorkflowTopology#terminalOutputSkuIds 同口径 —— 后端用 TreeSet 收集, 所以是升序;
   * 这里跟着排序, 两侧列出来的产出顺序才一致。顶部「本图产出：A、B」用它, 归属对象不参与。
   */
  terminalOutputSkuIds: string[];
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
  const terminalOutputSkuIds = [...terminalOutputIds].sort();
  const base = { rootInputCount, terminalOutputCount, terminalOutputSkuIds };

  if (terminalOutputCount === 0) {
    return { type: 'INCOMPLETE', label: '待完善画布', ...base };
  }
  if (terminalOutputCount === 1) {
    return { type: 'PRODUCT', label: '产品 Workflow', ...base };
  }
  if (rootInputCount <= 1) {
    return { type: 'RAW_SPLIT', label: '原料分流 Workflow', ...base };
  }
  return { type: 'JOINT_PRODUCTION', label: '联产 Workflow', ...base };
}

/** Vue Flow 节点在研判这条路上真正用得到的部分。 */
export interface CanvasNodeLike {
  id: string;
  data?: Record<string, unknown> | null;
}

export interface CanvasEdgeLike {
  source: string;
  target: string;
}

/**
 * 画布节点/边 → 研判结论。**这一步是真正的断层所在。**
 *
 * ⚠️ 分类器「认得」某个字段 ≠ 画布「传得到」那个字段。2026-08-10 就栽在这里:
 * isByproduct / substituteOfNodeId 两条规则写进了 classifyWorkflowTopology 也写了单测,
 * 而真实画布的 mapper 没把这两个字段传下去 —— 规则测试全绿, 真实路径一次都没生效。
 * 直接构造 WorkflowTopologyNode 的用例照不出这种缺陷, 只有走本函数(吃真实的
 * `node.data`)的用例才照得出。所以 mapper 放在这里而不是 .vue 里。
 *
 * ⛔ 调用方必须先剥掉 BOM 浮层节点**和边**(stripBomOverlay / stripBomOverlayEdges),
 * 否则包材浮层边会把真实产出算进 outgoing, 结构完整的图被研判成 INCOMPLETE。
 */
export function classifyCanvasTopology(
  canvasNodes: readonly CanvasNodeLike[],
  canvasEdges: readonly CanvasEdgeLike[],
): WorkflowClassification {
  return classifyWorkflowTopology(
    canvasNodes.map((node) => {
      const data = (node.data ?? {}) as Record<string, unknown>;
      return {
        id: node.id,
        kind: typeof data.kind === 'string' ? data.kind : '',
        skuId: typeof data.skuId === 'string' ? data.skuId : undefined,
        isByproduct: data.isByproduct === true,
        substituteOfNodeId: typeof data.substituteOfNodeId === 'string'
          ? data.substituteOfNodeId
          : undefined,
      };
    }),
    canvasEdges.map((edge) => ({ source: edge.source, target: edge.target })),
  );
}

/**
 * 终端产出的可读名字, 顺序与 {@link WorkflowClassification.terminalOutputSkuIds} 一致。
 * 名字取自画布节点自己的 data.name —— 顶部「本图产出：A、B」显示的就是画布上写着的东西,
 * 不再经过归属对象(那只是存放位置)。
 */
export function terminalOutputLabels(
  canvasNodes: readonly CanvasNodeLike[],
  classification: WorkflowClassification,
): string[] {
  const nameBySku = new Map<string, string>();
  canvasNodes.forEach((node) => {
    const data = (node.data ?? {}) as Record<string, unknown>;
    if (typeof data.skuId !== 'string' || !data.skuId) return;
    const name = typeof data.name === 'string' && data.name.trim() ? data.name.trim() : data.skuId;
    if (!nameBySku.has(data.skuId)) nameBySku.set(data.skuId, name);
  });
  return classification.terminalOutputSkuIds.map((sku) => nameBySku.get(sku) ?? sku);
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
