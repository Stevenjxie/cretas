import type { ProductProcessNodeKind, WorkflowPosition } from './types';
import type {
  AuxiliaryCellData,
  AuxiliaryCellRow,
  PackagingCellData,
  PackagingCellRow,
} from './bomOverlayTypes';

/**
 * BOM 浮层节点 —— 辅料 / 包材 cell。**两类都是「投入」。**
 *
 * ⛔ 这些节点【不是工艺定义的一部分】。设计要求「改辅料克数只动 BOM 草稿，
 * 不产生新工艺版本」，而工艺节点一改就会改 revision hash，导致所有钉了旧修订
 * 的 BOM 需要重新对齐。所以浮层节点必须在序列化回工艺定义时被滤掉
 * （见 ProductProcessWorkflowEditor.vue 的 serializeFlowNode 调用点）。
 * (阶段 3 会推翻这条 —— 届时 materialBindings 进工艺定义、纳入 revisionHash。)
 *
 * ## 副产为什么不在这里(2026-08-07 阶段 2)
 * 副产曾经也是浮层(`bom-overlay:byp:*`)，现已改成**真实产出节点**：
 * 它是「产出」不是「投入」，与半成品同性质，只需指 SKU、数量报工时填。
 * 做成真实节点后它自动获得图校验(SKU_REQUIRED)、拓扑、连边，一行特例都不用开。
 *
 * ⚠️ 顺带说明当初那条浮层边的下场，作为「浮层是脆的」的实证：
 * 副产浮层的 `bom-byp-out` sourceHandle **在 WorkflowMaterialNode.vue 里从来没有
 * 对应的 <Handle>**（包材的 `bom-pack-out` 有）。按下面这段注释预言的失败模式，
 * 那条投影连线不报错、直接不渲染 —— 副产 cell 一直是飘在成品下方、没有连线的。
 * 真实节点走真实边，不存在这种"两边字符串对不齐就静默失效"的耦合。
 */
export const BOM_OVERLAY_PREFIX = 'bom-overlay:';

export function isBomOverlayNode(node: { id: string }): boolean {
  return node.id.startsWith(BOM_OVERLAY_PREFIX);
}

export function isBomOverlayEdge(edge: { id: string; source?: string; target?: string }): boolean {
  return edge.id.startsWith(`${BOM_OVERLAY_PREFIX}edge:`)
    || (edge.source != null && isBomOverlayNode({ id: edge.source }))
    || (edge.target != null && isBomOverlayNode({ id: edge.target }));
}

export function stripBomOverlay<T extends { id: string }>(nodes: T[]): T[] {
  return nodes.filter((node) => !isBomOverlayNode(node));
}

/**
 * 浮层边(辅料 cell → 工序 / 产出 → 包材 cell 的投影连接)同样不属于工艺定义。
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
 * 包材 cell 挂在它服务的终端产出右侧（x + PACK_OFFSET_X）。投影连线使用与
 * 普通 Workflow 一致的蓝色实线和箭头；它仍只表达归属，不代表执行物料流。
 */
/**
 * 辅料 Cell 距离工序 Cell 顶部的留白。
 *
 * ⚠️ 2026-08-11: 旧实现是 `y - 220` 的**固定偏移**, 但工序 Cell 的高度随内容变化很大
 * (投入/产出/单位关系/副产几段全展开时能到 600px+), 220 根本不够 —— 辅料 Cell 直接
 * 压在工序 Cell 上面, 自动布局一跑就是一团 (F006 拓扑成品C/D 实撞)。
 * 现在改成 `y - (工序实测高度 + AUX_GAP_Y)`; 拿不到实测高度时退回
 * AUX_FALLBACK_HEIGHT, 保证首帧(还没测量)也不会重叠。
 */
const AUX_GAP_Y = 64;
const AUX_FALLBACK_HEIGHT = 360;
// 成品 Cell 的实际盒模型宽度约 236px。旧值 220 会让包材 Cell 与成品 Cell
// 重叠，两个 handle 几乎落在同一点，视觉上像“没有线”。
const PACK_OFFSET_X = 300;
/**
 * 浮层连线的线型 —— **必须与主流程连线一致**。
 * 旧值是 'smoothstep'(直角折线), 而主流程边不设 type 走 vue-flow 默认贝塞尔曲线,
 * 于是同一张画布上两种线型并存, 辅料/包材那两根看着像是别的系统画的。
 * 统一成默认曲线(不设 type), 只保留同样的蓝色实线 + 箭头。
 */
const OVERLAY_EDGE_STYLE = { stroke: '#1b65a8', strokeWidth: 2 } as const;

/**
 * 派生边挂靠的 handle id —— 必须与组件里 <Handle> 的 id 字面量一致
 * (WorkflowAuxiliaryNode.vue / WorkflowPackagingNode.vue / WorkflowMaterialNode.vue
 * 的 FINISHED_GOOD 分支)。两边任一改了字符串而没同步改另一边, 连线不报错、
 * 直接不渲染 —— 所以两侧都从这两个常量读, 不允许各自手写字面量。
 */
export const AUX_OVERLAY_SOURCE_HANDLE = 'bom-aux-out';
export const AUX_OVERLAY_TARGET_HANDLE = 'bom-aux-in';
export const PACK_OVERLAY_SOURCE_HANDLE = 'bom-pack-out';
export const PACK_OVERLAY_TARGET_HANDLE = 'bom-pack-in';

export interface BomOverlayConnectionCandidate {
  source: string;
  target: string;
  sourceHandle?: string | null;
  targetHandle?: string | null;
}

/**
 * Vue Flow 在接收 `v-model:edges` 时会把每一条边重新送进页面级
 * `isValidConnection`。浮层边不是用户创建的工艺边，但如果仍按 MATERIAL / PROCESS
 * 的工艺连线规则校验，两端的 `bom-overlay:*` 节点没有 kind，会被静默过滤，最终
 * DOM 中一条线都没有。这里只放行 deriveBomOverlay 能生成的两种精确拓扑；任意
 * handle、方向或节点归属不一致仍然拒绝，不能借此创建真实工艺连接。
 */
export function isDerivedBomOverlayConnection(
  connection: BomOverlayConnectionCandidate,
): boolean {
  const isAuxiliary = connection.source === `${BOM_OVERLAY_PREFIX}aux:${connection.target}`
    && connection.sourceHandle === AUX_OVERLAY_SOURCE_HANDLE
    && connection.targetHandle === AUX_OVERLAY_TARGET_HANDLE;
  if (isAuxiliary) return true;

  return connection.target === `${BOM_OVERLAY_PREFIX}pack:${connection.source}`
    && connection.sourceHandle === PACK_OVERLAY_SOURCE_HANDLE
    && connection.targetHandle === PACK_OVERLAY_TARGET_HANDLE;
}

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

export interface BomOverlayInput {
  workflowNodes: BomOverlaySourceNode[];
  auxiliaryByProcess: Record<string, BomOverlayAuxiliaryInput>;
  packagingByOutput: Record<string, BomOverlayPackagingInput>;
  /**
   * 工序节点的**实测**高度 (nodeId → px)，用来把辅料 Cell 放在工序上方且不重叠。
   * 首帧还没测量时传空即可 —— 派生会退回 AUX_FALLBACK_HEIGHT。
   */
  nodeHeights?: Record<string, number>;
  /** 可选: 老调用方不传时按「没有副产」派生空 cell, 与包材/辅料一致(防呆: 空 cell 也要画)。 */
}

/**
 * OverlayNode 的返回类型直接就是两个 cell 组件的 prop 类型(AuxiliaryCellData /
 * PackagingCellData) —— 这样字段错配(比如漏传 usageSupported)在编译期就红,
 * 不必等到画布上渲染出一片灰态才发现。
 */
export type OverlayNode =
  | { id: string; type: 'bomAuxiliary'; position: WorkflowPosition; data: AuxiliaryCellData }
  | { id: string; type: 'bomPackaging'; position: WorkflowPosition; data: PackagingCellData }
  ;

export interface OverlayEdge {
  id: string;
  source: string;
  sourceHandle?: string;
  target: string;
  targetHandle?: string;
  /** 不设 type —— 与主流程边一样走 vue-flow 默认曲线。 */
  style: { stroke: string; strokeWidth: number };
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
        position: {
          x: node.position.x,
          y: node.position.y
            - ((input.nodeHeights?.[node.id] ?? AUX_FALLBACK_HEIGHT) + AUX_GAP_Y),
        },
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
        targetHandle: AUX_OVERLAY_TARGET_HANDLE,
        style: OVERLAY_EDGE_STYLE,
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
        style: OVERLAY_EDGE_STYLE,
      });
    }
  }

  return { nodes, edges };
}
