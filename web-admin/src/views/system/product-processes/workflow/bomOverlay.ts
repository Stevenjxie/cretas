import type { ProductProcessNodeKind, WorkflowPosition } from './types';
import type {
  AuxiliaryCellData,
  AuxiliaryCellRow,
  PackagingCellData,
  PackagingCellRow,
  ByproductCellRow,
  ByproductCellData,
} from './bomOverlayTypes';

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
// 副产挂在产出下方, 与包材(右侧)分开, 免得两个 cell 叠在一起。
const BYP_OFFSET_Y = 200;

/**
 * 派生边挂靠的 handle id —— 必须与组件里 <Handle> 的 id 字面量一致
 * (WorkflowAuxiliaryNode.vue / WorkflowPackagingNode.vue / WorkflowMaterialNode.vue
 * 的 FINISHED_GOOD 分支)。两边任一改了字符串而没同步改另一边, 连线不报错、
 * 直接不渲染 —— 所以两侧都从这两个常量读, 不允许各自手写字面量。
 */
export const AUX_OVERLAY_SOURCE_HANDLE = 'bom-aux-out';
export const PACK_OVERLAY_SOURCE_HANDLE = 'bom-pack-out';
export const PACK_OVERLAY_TARGET_HANDLE = 'bom-pack-in';
export const BYP_OVERLAY_SOURCE_HANDLE = 'bom-byp-out';
export const BYP_OVERLAY_TARGET_HANDLE = 'bom-byp-in';

/**
 * deriveBomOverlay 只需要节点的 id/kind/position, 加一份【展示用】的最小数据子集
 * (工序名 / 产出名 / 基本单位) —— 不要求完整的 ProcessNodeData/MaterialNodeData
 * 载荷(那些字段这里用不上, 强制调用方补全只会制造无关的类型摩擦)。
 */
export interface BomOverlaySourceNodeData {
  /** PROCESS 节点的工序名, 用作辅料 cell 标题。 */
  processName?: string;
  /** FINISHED_GOOD 节点的产出名, 用作包材 cell 标题。 */
  name?: string;
  /** FINISHED_GOOD 节点的产出 SKU 基本单位 —— 包材 cell 分母的权威来源。 */
  baseUnit?: string;
}

export interface BomOverlaySourceNode {
  id: string;
  kind: ProductProcessNodeKind;
  position: WorkflowPosition;
  data: BomOverlaySourceNodeData;
}

/**
 * 一道工序的辅料浮层输入 —— usageSupported 是 BOM 概念, 画布节点本身不携带, 必须外部传入。
 *
 * 存在这个 input 记录本身就代表调用方"确认过"这道工序 —— 所以这里的 usageSupported
 * 仍是纯 boolean(true/false 都是确认结论)。deriveBomOverlay 才是引入第三态 `null`
 * 的地方: 一道工序压根没有对应 input 记录(auxiliaryByProcess 里找不到 key)时，
 * 那是"不知道"而不是"确认为 false" —— 见 bomOverlayTypes.ts 的 AuxiliaryCellData 注释。
 */
export interface BomOverlayAuxiliaryInput {
  usageSupported: boolean;
  rows: AuxiliaryCellRow[];
  /** 联合生产(多产出共享同一工序节点)时是否有 >1 份配方绑定了这道工序 —— 见
   *  bomOverlayTypes.ts AuxiliaryCellData.sharedAcrossRecipes 的完整说明。 */
  sharedAcrossRecipes?: boolean;
  /** 仅当 sharedAcrossRecipes 为 true 时有意义: 当前实际生效(先到先得)的那份配方所属产出名。 */
  recipeOutputName?: string | null;
}

/** 一个终端产出的包材浮层输入。outputName/baseUnit 直接读该节点自己的 data, 不在这里重复传。 */
export interface BomOverlayPackagingInput {
  rows: PackagingCellRow[];
}

/** 一个终端产出的副产浮层输入。副产是产出声明, 与包材同挂产出节点。 */
export interface BomOverlayByproductInput {
  rows: ByproductCellRow[];
}

export interface BomOverlayInput {
  workflowNodes: BomOverlaySourceNode[];
  auxiliaryByProcess: Record<string, BomOverlayAuxiliaryInput>;
  packagingByOutput: Record<string, BomOverlayPackagingInput>;
  /** 可选: 老调用方不传时按「没有副产」派生空 cell, 与包材/辅料一致(防呆: 空 cell 也要画)。 */
  byproductByOutput?: Record<string, BomOverlayByproductInput>;
}

/**
 * OverlayNode 的返回类型直接就是两个 cell 组件的 prop 类型(AuxiliaryCellData /
 * PackagingCellData) —— 这样字段错配(比如漏传 usageSupported)在编译期就红,
 * 不必等到画布上渲染出一片灰态才发现。
 */
export type OverlayNode =
  | { id: string; type: 'bomAuxiliary'; position: WorkflowPosition; data: AuxiliaryCellData }
  | { id: string; type: 'bomPackaging'; position: WorkflowPosition; data: PackagingCellData }
  | { id: string; type: 'bomByproduct'; position: WorkflowPosition; data: ByproductCellData };

export interface OverlayEdge {
  id: string;
  source: string;
  sourceHandle?: string;
  target: string;
  targetHandle?: string;
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
      const meta = input.auxiliaryByProcess[node.id];
      nodes.push({
        id: auxId,
        type: 'bomAuxiliary',
        position: { x: node.position.x, y: node.position.y - AUX_OFFSET_Y },
        data: {
          processName: node.data.processName ?? '未命名工序',
          // ⛔ 三态, 不能默认成 false: 没有 meta 只代表"没有可确认的结论"(数据未加载/
          // 加载失败/该产品无配方/配方钉的修订节点 id 对不上当前图), 不代表"已确认
          // 不可换算"——那是一个代码给不出证据的具体诊断(禁止降级处理)。
          usageSupported: meta ? meta.usageSupported : null,
          rows: meta?.rows ?? [],
          processNodeId: node.id,
          sharedAcrossRecipes: meta?.sharedAcrossRecipes ?? false,
          recipeOutputName: meta?.recipeOutputName ?? null,
        },
      });
      edges.push({
        id: `${BOM_OVERLAY_PREFIX}edge:aux:${node.id}`,
        source: auxId,
        sourceHandle: AUX_OVERLAY_SOURCE_HANDLE,
        target: node.id,
        style: { strokeDasharray: '5 4' },
      });
    }

    if (node.kind === 'FINISHED_GOOD') {
      const packId = `${BOM_OVERLAY_PREFIX}pack:${node.id}`;
      const meta = input.packagingByOutput[node.id];
      nodes.push({
        id: packId,
        type: 'bomPackaging',
        position: { x: node.position.x + PACK_OFFSET_X, y: node.position.y },
        data: {
          outputName: node.data.name ?? '未命名产出',
          baseUnit: node.data.baseUnit ?? '未配',
          rows: meta?.rows ?? [],
          outputNodeId: node.id,
        },
      });
      edges.push({
        id: `${BOM_OVERLAY_PREFIX}edge:pack:${node.id}`,
        source: node.id,
        sourceHandle: PACK_OVERLAY_SOURCE_HANDLE,
        target: packId,
        targetHandle: PACK_OVERLAY_TARGET_HANDLE,
        style: { strokeDasharray: '5 4' },
      });

      // 副产同样只挂终端产出。与包材一样, 没有副产也派生空 cell ——
      // 「没配副产」和「这里不能配副产」必须能被用户区分开。
      const bypId = `${BOM_OVERLAY_PREFIX}byp:${node.id}`;
      const bypMeta = input.byproductByOutput?.[node.id];
      nodes.push({
        id: bypId,
        type: 'bomByproduct',
        position: { x: node.position.x, y: node.position.y + BYP_OFFSET_Y },
        data: {
          outputName: node.data.name ?? '未命名产出',
          baseUnit: node.data.baseUnit ?? '未配',
          rows: bypMeta?.rows ?? [],
          outputNodeId: node.id,
        },
      });
      edges.push({
        id: `${BOM_OVERLAY_PREFIX}edge:byp:${node.id}`,
        source: node.id,
        sourceHandle: BYP_OVERLAY_SOURCE_HANDLE,
        target: bypId,
        targetHandle: BYP_OVERLAY_TARGET_HANDLE,
        style: { strokeDasharray: '5 4' },
      });
    }
  }

  return { nodes, edges };
}
