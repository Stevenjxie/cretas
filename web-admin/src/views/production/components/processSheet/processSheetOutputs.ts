/**
 * 报工产出行的类型与视图模型。
 *
 * 抽出来的原因: 产出块在 ProcessDataTable.vue 里卡片模式和表格模式各写了一遍, 两份已经漂移 ——
 * 表格模式(默认视图)漏掉了 `*` 必填标识和跨单位出成率说明条。两边改用同一个子组件后,
 * 这类漂移不会再发生; 而子组件要拿到的类型不能定义在 `<script setup>` 里(不可导出), 故落到本模块。
 */

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
  /** 副产是该产出的附属事实，单位固定只读。 */
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
  totalHoursText: string;
  /** 仅成品显示; 缺单位净重时是明确的错误说明而不是猜的重量。 */
  weightHint: string | null;
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
