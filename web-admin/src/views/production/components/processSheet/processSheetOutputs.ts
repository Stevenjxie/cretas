/**
 * 报工产出行的类型与视图模型。
 *
 * 抽出来的原因: 产出块在 ProcessDataTable.vue 里卡片模式和表格模式各写了一遍, 两份已经漂移 ——
 * 表格模式(默认视图)漏掉了 `*` 必填标识和跨单位出成率说明条。两边改用同一个子组件后,
 * 这类漂移不会再发生; 而子组件要拿到的类型不能定义在 `<script setup>` 里(不可导出), 故落到本模块。
 */

/**
 * 画布绑定的一条副产: SKU 由 Workflow 画布上的副产 Cell 定死, 用户只填「产出多少」和
 * 「回收单价多少」。
 *
 * 名字来自端口而不是硬编码的 '副产' —— 后端 `ByproductBatchMaterializer` 按名字匹配
 * BOM 里的副产声明, 喂它一个真实 SKU 名比喂一个占位串能匹配到更多情况。
 */
export interface BoundByproductLine {
  workflowPortId: string;
  materialNodeId: string;
  productTypeId: string;
  /** 只读展示品名 (画布绑定)。 */
  materialName: string;
  unit: string;
  quantity: number | null;
  unitPrice: number | null;
}

// -------------------------------------------------------------------------
// 2B.2 多产出 (fan-out): 一个产出端口条目。产品/端口由 workflow 产出端口固定 (只读, fool-proof
// Rule 2/3 — 操作员不能自由选产品), 只填数量。batchNumber 保存/重载后填充 (只读展示)。
// -------------------------------------------------------------------------
export interface MultiOutputLine {
  workflowPortId: string;
  /** 端口身份: workflow 物料 Cell 节点 id (随请求发给后端记录)。 */
  materialNodeId: string;
  productTypeId: string;
  /** 只读展示品名; 端口 SKU 已失效时兜底显 productTypeId, 不崩溃。 */
  materialName: string;
  unit: string;
  /** SKU 单位净重；计数型成品缺失时只展示明确错误，不猜重量。 */
  gramsPerUnit: number | null;
  finished: boolean;
  required: boolean;
  selected: boolean;
  quantity: number | null;
  /** 一条产出对应一段开始/结束时间；总工时由这两个值即时计算。 */
  startTime: string;
  endTime: string;
  workerCount: number;
  /**
   * 画布上这个物料 Cell 被标记成了副产。
   *
   * ⚠️ 与 `finished` 正交 —— 副产节点的 kind 仍是 SEMI_FINISHED, 所以 `finished` 一个人
   * 回答不了「这是不是副产」。在这个字段出现之前, 标签只按 `finished` 二选一渲染
   * 成品/半成品, 于是画布上标成副产的物料在报工单上显示成「半成品」。
   */
  isByproduct: boolean;
  /**
   * 画布已经绑定好的副产 —— SKU 是配置出来的, 用户只填数量和回收单价。
   *
   * 非空时**取代**下面那三个手填字段 (二选一, 见 `byproductEntryMode`): 同一个事实
   * 不能既由画布绑定又由人手填, 两套并存必然漂。
   */
  boundByproducts: BoundByproductLine[];
  /** 副产是该产出的附属事实，单位固定只读。仅在**没有**画布绑定副产时使用。 */
  byproductQuantity: number | null;
  byproductUnit: string;
  byproductUnitPrice: number | null;
  /** 仅产出维度无法统一时显示并提交，值域 (0, 100]。 */
  costAllocationRatio: number | null;
  /** 保存后系统生成的产出批次号 (重载回显); 未保存为 null。 */
  batchNumber: string | null;
}

/**
 * 一条产出在界面上要显示的全部派生值。
 *
 * 全部在父组件算好再传进来 —— 子组件只负责排版, 不持有任何业务判断,
 * 这样卡片/表格两种视图必然显示同一套结果。
 */
export interface OutputLineView {
  /** 同一个响应式对象; 子组件里的 v-model 直接写回它。 */
  line: MultiOutputLine;
  /** 端口有替代关系时才给「选用」复选框 (showPortSelector)。 */
  selectorVisible: boolean;
  selectorDisabled: boolean;
  /** 用户配的单位写法 (displayProcessUnit 折算后)。 */
  unitLabel: string;
  byproductUnitLabel: string;
  quantityPrecision: number;
  /** 已格式化的出成率; 算不出时是 '—'。 */
  yieldText: string;
  /** 出成率算不出的原因; 能算出来时为 null。 */
  blocker: string | null;
  /** 副产录入走画布绑定还是手填 —— 二选一, 界面据此只渲染其中一套。 */
  byproductMode: 'BOUND' | 'MANUAL';
  totalHoursText: string;
  /** 仅成品显示; 缺单位净重时是明确的错误说明而不是猜的重量。 */
  weightHint: string | null;
  /** Workflow 已配置单位净重时显示的规格，如 800g/盒；缺数据时不猜。 */
  specLabel: string | null;
}

// -------------------------------------------------------------------------
// 副产端口的归位 —— 纯函数, 没有 Vue/DOM 依赖, 测试直接调它。
//
// 缺陷背景 (产品负责人 2026-08-17 当面指出): 画布上标成「副产」的物料到了报工界面
// 变成了「半成品」, 而且**独立成一行**。正确形态是: 副产不独立成行, 它填进主产出行
// 下方那块「副产数量 + 副产回收单价」——  SKU 由画布带出, 用户只填数量和回收单价。
// -------------------------------------------------------------------------

/** partitionOutputPorts 只需要端口的这几个字段; 用最小结构约束, 免得测试要造整个 DTO。 */
export interface ByproductPartitionablePort {
  workflowPortId: string;
  /** 后端 PortDescriptor.byproduct —— 画布物料节点 data.isByproduct 的投影。 */
  byproduct?: boolean | null;
  materialName?: string | null;
}

export interface OutputPortPartition<T> {
  /** 独立成行的产出端口。 */
  rowPorts: T[];
  /** 不独立成行、填进主产出行副产区的端口。 */
  inlineByproductPorts: T[];
  /** 副产区挂在哪条产出行下面; 没有画布绑定副产时为 null。 */
  hostPortId: string | null;
  /**
   * 只有副产、没有主产出时的说明。
   *
   * ⛔ 这一态**不能静默** —— 没有主产出行就没有地方挂副产区, 如果这时把副产丢掉,
   * 用户会看到一张空的产出表却不知道为什么。所以此时把副产提升成行并把原因说出来。
   */
  orphanNotice: string | null;
}

function isByproductPort(port: ByproductPartitionablePort): boolean {
  return port.byproduct === true;
}

/**
 * 把本工序的产出端口分成「独立成行的」和「填进副产区的」。
 *
 * 多个副产 Cell 时: **全部**进 `inlineByproductPorts`, 副产区逐条渲染。
 * ⛔ 不取第一个了事 —— 静默丢掉用户在画布上配的东西是本仓明令禁止的形状。
 *
 * 多个主产出时: 副产挂在**第一条**主产出行下面。副产是**工序级**的事实
 * (画布上它挂在工序节点的产出端口上, 不属于某一个主产出), 所以挂哪条都一样;
 * 选第一条是为了位置稳定, 界面上那块区域的标题会写明「本工序副产」。
 */
export function partitionOutputPorts<T extends ByproductPartitionablePort>(
  ports: T[],
): OutputPortPartition<T> {
  const byproductPorts = ports.filter(isByproductPort);
  const mainPorts = ports.filter((p) => !isByproductPort(p));

  if (byproductPorts.length === 0) {
    return { rowPorts: mainPorts, inlineByproductPorts: [], hostPortId: null, orphanNotice: null };
  }

  if (mainPorts.length === 0) {
    const names = byproductPorts.map((p) => p.materialName || p.workflowPortId).join('、');
    return {
      rowPorts: byproductPorts,
      inlineByproductPorts: [],
      hostPortId: null,
      orphanNotice: `本工序在画布上只配了副产产出（${names}），没有主产出。`
        + '副产已按独立产出行展示；请回 Workflow 画布为本工序补一个主产出 Cell。',
    };
  }

  return {
    rowPorts: mainPorts,
    inlineByproductPorts: byproductPorts,
    hostPortId: mainPorts[0].workflowPortId,
    orphanNotice: null,
  };
}

/**
 * 副产录入走哪一套 —— 二选一, 不并存。
 *
 * `BOUND`  : 画布上绑了副产 Cell, SKU 已知, 逐条填数量/回收单价。
 * `MANUAL` : 画布上没绑, 沿用原来那对手填的「副产数量 / 副产回收单价」。
 */
export function byproductEntryMode(line: Pick<MultiOutputLine, 'boundByproducts'>): 'BOUND' | 'MANUAL' {
  return line.boundByproducts.length > 0 ? 'BOUND' : 'MANUAL';
}

/** 产出表的列宽契约。选用列只在真有可选端口时占位, 否则整列不存在。 */
export function outputGridTemplate(withSelector: boolean): string {
  return [
    withSelector ? '56px' : null,
    'minmax(180px, 1.6fr)',
    '156px',
    '96px',
    '120px',
    '120px',
    '92px',
    '86px',
    '72px',
  ].filter(Boolean).join(' ');
}
