/**
 * 半成品(SFI)/成品(FG)/在制(WIP) 投入行的类型与视图模型。
 *
 * 抽出来的原因与 `processSheetOutputs.ts` 完全一样: 多来源投入块在 ProcessDataTable.vue 里
 * 写了**三遍** (卡片模式 1 份 + 表格模式的熟制/气调各 1 份), 三份必然漂移 —— 该文件历史上
 * 已经因为「同一块 UI 两套模板」漏过必填标识和整个新功能。三处改用同一个子组件后就只剩一份实现。
 *
 * 子组件要拿到的类型不能定义在 `<script setup>` 里 (不可导出), 故落到本模块。
 */
import type { UpstreamRef } from '@/api/processSheet';

/** 界面上的一条上游来源: 后端契约 + 「选用」勾选态 (勾选态不进 payload)。 */
export interface SelectableUpstreamRef extends UpstreamRef {
  selected: boolean;
}

/** 来源类型判别值 —— 与 ProcessDataTable 的 SRC_WIP / SRC_SFI / SRC_FG 同一套字符串。 */
export type UpstreamSourceKind = 'wip' | 'sfi' | 'fg';

/** 一个可选批次 (下拉值是「类型::批次号」复合值, 类型由选项显式携带而非按批号反查)。 */
export interface UpstreamBatchOption {
  kind: UpstreamSourceKind;
  batchNumber: string;
  /** 复合值「类型::批次号」。 */
  value: string;
  label: string;
  /** 余量 <= 0 的批次仍然列出但不可选 —— 藏起来会让操作员以为系统坏了。 */
  disabled: boolean;
}

export interface UpstreamBatchOptionGroup {
  label: string;
  options: UpstreamBatchOption[];
}

/**
 * 一条投入来源在界面上要显示的全部派生值。
 *
 * 全部在父组件算好再传进来 —— 子组件只负责排版, 不持有任何业务判断,
 * 这样卡片/表格两种视图必然显示同一套结果。
 */
export interface InputSourceLineView {
  /** 同一个响应式对象; 子组件里的 v-model 直接写回它。 */
  source: SelectableUpstreamRef;
  /** 在 row.upstreamSources 里的下标 (删除/加同物料批次要用)。 */
  index: number;
  /** 端口物料名 —— 防呆 Rule 2: 每一行都带得起身份, 不出现无名输入框。 */
  materialName: string;
  /** 端口有替代关系时才给「选用」复选框 (showPortSelector)。 */
  selectorVisible: boolean;
  selectorDisabled: boolean;
  /** 用户配的单位写法 (displayProcessUnit 折算后)。 */
  unitLabel: string;
  quantityPlaceholder: string;
  /** 已选批次的复合下拉值; 未选为 ''。 */
  selectKey: string;
  /**
   * 可选批次只有一条时的批次文案; 有第二条候选时为 null。
   *
   * 客户 2026-07-30:「只有一个批次时自动选中，不要让用户多点一次」——
   * 判据与「端口没有替代关系就不给勾选框」(showPortSelector) 是同一条:
   * 没有第二个候选, 那个下拉就是一次假选择。
   */
  soleBatchLabel: string | null;
  optionGroups: UpstreamBatchOptionGroup[];
  /** 已选批次的余量提示 (防呆 Rule 1: 边界摆在录入行上); 未选批次为 ''。 */
  remainingText: string;
  /** 该端口是否允许「同物料再加批次」(workflow 端口才有稳定身份, 且真有第二个批次可加)。 */
  canAddSameMaterial: boolean;
  /**
   * 是否给「清除来源批次」按钮。
   *
   * 唯一候选 + workflow 端口 = 清了会被立刻自动选回同一批, 那个按钮只会让人以为自己点坏了。
   * legacy 手工加的来源行没有端口身份, 清除是真的删掉那一行, 所以照常给。
   */
  canClear: boolean;
}

/**
 * 投入表的列宽契约。选用列只在真有可选端口时占位, 否则整列不存在。
 * 与 outputGridTemplate 同一套观感, 免得同一张卡上下两半长得不像一家。
 */
export function inputSourceGridTemplate(withSelector: boolean): string {
  return [
    withSelector ? '56px' : null,
    'minmax(140px, 1.2fr)',
    '188px',
    'minmax(220px, 1.6fr)',
    '104px',
  ].filter(Boolean).join(' ');
}
