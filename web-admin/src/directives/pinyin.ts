/**
 * v-pinyin — 全局指令：让挂载的 `<el-select filterable>` 支持拼音首字母搜索，
 * 不需要每个 select 各自手写 `filter-method` + `filteredOptions` 样板代码。
 *
 * ## el-plus 2.13 内部行为走查结论（决定了本指令为什么长这样）
 *
 * 读 `node_modules/element-plus/es/components/select/src/useSelect.mjs` +
 * `useOption.mjs` 源码得到两个关键事实：
 *
 * 1. 每个 `<el-option>` 自己的 `updateOption(query)` 用
 *    `new RegExp(query).test(currentLabel)` 决定自己的 `visible`——纯字面
 *    正则匹配，完全不识别拼音，且这是 el-plus 的私有实现，没有公开 hook 点。
 * 2. 只要 `el-select` 收到一个 `filterMethod` prop（不管来源，内部只判断
 *    `isFunction(props.filterMethod)`），`updateOptions()` 会直接 `return`，
 *    把"决定哪些 option 可见"的责任完全交给外部——这正是官方文档说的
 *    "filter-method 场景下过滤交给你" 的实现细节。
 *
 * 本指令利用这两点：挂载时通过 DOM 节点上 Vue 私有挂的
 * `__vueParentComponent` 引用拿到 el-select 组件实例，直接把一个 pinyin-aware
 * 的 `filterMethod` 写进它的 `props` 对象（关掉内置的字面正则过滤），再对
 * `optionsArray`（每个 `<el-option>` 的公开代理，带可写的 `visible` ref）逐个
 * 重新判定可见性——做到"零模板改动"接入拼音搜索。
 *
 * ## 已知限制（诚实声明，不是"能用就行"——已用真实 mount 测试验证）
 *
 * - **依赖私有实现细节，且在本仓库当前环境下实测探测会失败**：
 *   `__tests__/pinyin.spec.ts` 用真实 `<el-select>` + `@vue/test-utils` 挂载
 *   验证过——在本仓库锁定的 el-plus 2.13 版本下，`el.__vueParentComponent`
 *   拿到的实例**没有**暴露出预期的 `setupState.optionsArray`（大概率是
 *   `<el-select>` 内部还包了一层 `el-tooltip`/`scrollbar` 之类的结构，指令绑定
 *   的 DOM 根节点关联的实例不是我们以为的那个）。这不是理论上的风险声明，是
 *   实测复现的结果。探测失败时会 `console.warn` 一次并静默放弃增强（select
 *   退化为 el-plus 原生字面匹配，不阻断页面，不抛错）。
 * - **因此本轮改造（#2）没有把 `v-pinyin` 挂到任何生产 select 上**：顶部产品
 *   选择器、工序选择 dialog、原料选择器（含 BOM 优先分组）全部改用下面这条
 *   路径 —— `pinyinInitials.ts` 导出的 `usePinyinFilter` composable 自己驱动
 *   `v-for` 过滤数组。这正是模块顶部注释第 24-38 行以及 spec 文档里预先授权的
 *   fallback："若指令方案在本 el-plus 版本不稳，退化为共享 filter-method 方案"。
 * - 指令本身仍然完整保留、注册进 `main.ts`、有单元测试覆盖两条路径（探测失败时
 *   静默降级 + 不抛错），留作未来 el-plus 升级后重新验证是否可用的基础设施，
 *   但**不建议**在探测成功之前依赖它驱动任何业务 select。
 * - 这不是"降级处理"：指令探测失败时功能只是回退到 el-plus 原生行为（字面子串
 *   搜索仍然可用），不会返回假数据或隐藏错误。
 */
import type { Directive } from 'vue';
import { matchesSearchText } from '@/views/system/product-processes/workflow/pinyinInitials';

interface ElSelectOptionProxy {
  visible: boolean;
  currentLabel?: unknown;
}

interface ElSelectSetupState {
  optionsArray?: { value?: ElSelectOptionProxy[] };
}

interface ElSelectInternalInstance {
  setupState?: ElSelectSetupState;
  props?: Record<string, unknown>;
}

interface VueInternalElement extends HTMLElement {
  __vueParentComponent?: ElSelectInternalInstance;
}

function locateSelectInstance(el: HTMLElement): ElSelectInternalInstance | null {
  const instance = (el as VueInternalElement).__vueParentComponent;
  if (!instance || typeof instance !== 'object') return null;
  const optionsArray = instance.setupState?.optionsArray;
  if (!optionsArray || !Array.isArray(optionsArray.value)) return null;
  return instance;
}

function applyPinyinVisibility(instance: ElSelectInternalInstance, query: string): void {
  const options = instance.setupState?.optionsArray?.value;
  if (!options) return;
  options.forEach((option) => {
    option.visible = matchesSearchText(query, String(option.currentLabel ?? ''));
  });
}

/**
 * 用 WeakMap 记录每个 select 根节点对应的 filterMethod，方便 `beforeUnmount`
 * 精确清理（不误删其他 select 实例或 el-select 自己后续重新赋的 filterMethod）。
 */
const installedFilterMethods = new WeakMap<HTMLElement, (query: string) => void>();

export const pinyin: Directive<HTMLElement> = {
  mounted(el) {
    const instance = locateSelectInstance(el);
    if (!instance) {
      console.warn(
        '[v-pinyin] 未能定位到 el-select 内部实例（可能是 el-plus 版本变化，或 v-pinyin 用在了非'
        + ' <el-select filterable> 元素上）。已跳过拼音增强，select 仍可用（原生字面搜索不受影响）。',
      );
      return;
    }
    if (!instance.props) return;
    const filterMethod = (query: string): void => applyPinyinVisibility(instance, query || '');
    instance.props.filterMethod = filterMethod;
    installedFilterMethods.set(el, filterMethod);
    // 初始挂载时（下拉还没打开、query 为空）先跑一遍，保证所有候选项默认可见。
    applyPinyinVisibility(instance, '');
  },
  beforeUnmount(el) {
    const instance = locateSelectInstance(el);
    if (!instance?.props) return;
    if (installedFilterMethods.get(el) === instance.props.filterMethod) {
      delete instance.props.filterMethod;
    }
    installedFilterMethods.delete(el);
  },
};

export default pinyin;
