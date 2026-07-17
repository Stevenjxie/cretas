<template>
  <div ref="pickerRootRef" class="workflow-sku-picker-shell nodrag nowheel" @wheel.stop>
  <el-cascader
    ref="cascaderRef"
    class="nodrag nowheel workflow-sku-picker"
    :data-testid="testId"
    :model-value="modelValue"
    :options="cascaderOptions"
    :props="cascaderProps"
    :placeholder="placeholder || '先选半成品/成品，再选 SKU'"
    filterable
    :filter-method="filterNode"
    :disabled="disabled"
    :size="size || 'small'"
    :teleported="false"
    popper-class="workflow-sku-picker-popper nowheel nodrag"
    @change="handleChange"
    @wheel.stop
    @keydown.esc.stop="closeDropdown"
  >
    <template #default="{ data }">
      <div
        v-if="data.isSearch"
        class="submenu-search"
        @pointerdown.stop
        @mousedown.stop
        @click.stop
        @keydown.stop
        @wheel.stop
      >
        <el-input
          :model-value="searchQuery(data.searchKind)"
          size="small"
          clearable
          :placeholder="`搜索${data.searchKind === 'SEMI' ? '半成品' : '成品'}名称 / 编码 / 拼音`"
          @update:model-value="setSearchQuery(data.searchKind, String($event || ''))"
        />
      </div>
      <span v-else>{{ data.label }}</span>
    </template>
  </el-cascader>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { matchesSearchText } from './pinyinInitials';

/**
 * 半成品/成品「真·两级」产品选择器，供「物料 Cell」和「工序 Cell 产出行」共用一套组件。
 *
 * 交互（Steve 要求）：下拉一级只显示「半成品 / 成品」两个入口，点「半成品」才展开该分类
 * 下的所有半成品（含现场创建入口），点「成品」展开所有成品 —— 不是把两组平铺在一屏。
 *
 * 为什么用 el-cascader + `emitPath: false`（而非分组 el-select）：
 * - `emitPath: false` 让 v-model 值仍是**扁平 skuId（string）**，跟 data.skuId / port.skuId
 *   绑定方式一致；cascader 内部自动按 skuId 反解出它在「半成品/成品」哪个分支来还原选中路径，
 *   不需要外部做 [level1, level2] <-> skuId 的手工转换（这正是当初担心的点，emitPath:false 已解决）。
 * - 分组 el-select 会把半成品组和成品组同屏平铺（看着像混在一起）；cascader 是先选分类再选项，
 *   符合「先选半成品/成品，再选 SKU」的需求。
 * - 拼音首字母搜索用 cascader 的 filter-method + matchesSearchText 实现，行为与全局一致。
 */

export interface WorkflowSkuPickerOption {
  id: string;
  name: string;
  unit?: string;
  code?: string;
}

interface SkuCascaderNode {
  value: string;
  label: string;
  name?: string;
  code?: string;
  isCreate?: boolean;
  isSearch?: boolean;
  searchKind?: 'SEMI' | 'FINISHED';
  children?: SkuCascaderNode[];
}

const props = defineProps<{
  modelValue: string;
  semiOptions: WorkflowSkuPickerOption[];
  finishedOptions: WorkflowSkuPickerOption[];
  disabled?: boolean;
  size?: 'small' | 'default' | 'large';
  placeholder?: string;
  testId?: string;
}>();

const emit = defineEmits<{
  change: [skuId: string];
}>();

interface CascaderExpose {
  togglePopperVisible: (visible?: boolean) => void;
}

const cascaderRef = ref<CascaderExpose | null>(null);
const pickerRootRef = ref<HTMLElement | null>(null);
const semiSearch = ref('');
const finishedSearch = ref('');

function closeDropdown(): void {
  cascaderRef.value?.togglePopperVisible(false);
}

function handleCloseAllDropdowns(): void {
  closeDropdown();
}

function handleDocumentPointerDown(event: PointerEvent): void {
  const target = event.target;
  if (target instanceof Node && pickerRootRef.value?.contains(target)) return;
  closeDropdown();
}

onMounted(() => {
  window.addEventListener('workflow-close-dropdowns', handleCloseAllDropdowns);
  document.addEventListener('pointerdown', handleDocumentPointerDown, true);
});
onUnmounted(() => {
  window.removeEventListener('workflow-close-dropdowns', handleCloseAllDropdowns);
  document.removeEventListener('pointerdown', handleDocumentPointerDown, true);
});

function handleChange(value: unknown): void {
  const nextValue = String(value ?? '');
  if (nextValue.startsWith('__SEARCH_')) return;
  emit('change', nextValue);
  // 选中新项或再次点击当前项后都应立即收起，避免必须改选其它项才能关闭。
  void nextTick(closeDropdown);
}

// emitPath:false → 只回传叶子值(skuId); expandTrigger:'click' → 点分类才展开(不是 hover)。
const cascaderProps = { emitPath: false, expandTrigger: 'click' as const };

function optionLabel(option: WorkflowSkuPickerOption): string {
  return `${option.name} · ${option.unit || '-'}`;
}

function toNode(option: WorkflowSkuPickerOption): SkuCascaderNode {
  return { value: option.id, label: optionLabel(option), name: option.name, code: option.code };
}

function searchQuery(kind?: 'SEMI' | 'FINISHED'): string {
  return kind === 'SEMI' ? semiSearch.value : finishedSearch.value;
}

function setSearchQuery(kind: 'SEMI' | 'FINISHED' | undefined, value: string): void {
  if (kind === 'SEMI') semiSearch.value = value;
  if (kind === 'FINISHED') finishedSearch.value = value;
}

function matchesOption(option: WorkflowSkuPickerOption, query: string): boolean {
  const keyword = query.trim();
  if (!keyword) return true;
  return matchesSearchText(keyword, option.name)
    || matchesSearchText(keyword, option.id)
    || matchesSearchText(keyword, option.code || '');
}

// 一级 = 半成品 / 成品；二级 = 各自的 SKU。现场创建入口固定在「半成品」二级首位。
const cascaderOptions = computed<SkuCascaderNode[]>(() => [
  {
    value: '__SEMI__',
    label: '半成品',
    children: [
      { value: '__SEARCH_SEMI__', label: '搜索半成品', isSearch: true, searchKind: 'SEMI' },
      { value: '__CREATE__', label: '＋ 现场创建半成品 SKU', isCreate: true },
      ...props.semiOptions.filter((option) => matchesOption(option, semiSearch.value)).map(toNode),
    ],
  },
  {
    value: '__FIN__',
    label: '成品',
    children: [
      { value: '__SEARCH_FINISHED__', label: '搜索成品', isSearch: true, searchKind: 'FINISHED' },
      ...props.finishedOptions.filter((option) => matchesOption(option, finishedSearch.value)).map(toNode),
    ],
  },
]);

/**
 * cascader 搜索过滤：匹配名称/编码/拼音首字母（大小写不限，#2 全局搜索规范）。
 * 现场创建入口在搜索时始终保留（fool-proof Rule 5：找不到匹配也有创建出口，不留死胡同）。
 * el-cascader 的 filter-method 收到的 node 有 .data(挂在叶子上的原始对象) 与 .text(路径文本)。
 */
function filterNode(node: { data?: SkuCascaderNode; text?: string }, keyword: string): boolean {
  const data = node.data;
  if (data?.isSearch) return true;
  if (data?.isCreate) return true;
  const kw = (keyword || '').trim();
  if (!kw) return true;
  const name = data?.name || data?.label || '';
  const code = data?.code || '';
  return matchesSearchText(kw, name)
    || matchesSearchText(kw, code)
    || (node.text || '').toLowerCase().includes(kw.toLowerCase());
}
</script>

<style scoped>
.workflow-sku-picker-shell, .workflow-sku-picker { width: 100%; }
.submenu-search { width: 260px; padding: 2px 0; cursor: text; }
:deep(.el-cascader-node:has(.submenu-search)) { padding: 4px 12px; }
:deep(.el-cascader-menu__item.create-option),
:deep(.el-cascader-node[aria-label*="现场创建"]) { color: #409eff; font-weight: 600; }
</style>
