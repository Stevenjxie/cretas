<script setup lang="ts" generic="P extends Record<string, any>, C extends Record<string, any>">
import { ref, watch, computed } from 'vue';
import { Histogram, Plus, Delete, RefreshRight, InfoFilled } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';

/**
 * U-NEW-1 bom mode (BOM 展开) — parent + nested-children create dialog.
 *
 * Use-case: 创建一张销售/采购/领料单, 通过选模板 (BOM/配方 ID), 自动展开
 * 子明细行 (材料/数量/单位). 用户再微调数量, 一次提交 父+子.
 *
 * Parent supplies:
 *   - parentFactory:    () => P            blank parent
 *   - childrenFactory:  () => C[]          blank children if no template
 *   - expandTemplate:   (templateId: string, parent: P) => Promise<C[]>
 *       called when user picks a BOM template; returns expanded children list.
 *   - submit:           (parent: P, children: C[]) => Promise<void>
 *   - childColumns:     ChildColumn<C>[]   table columns for child rows
 *   - parentFieldsSlot: optional named slot 'parent-fields' for the parent form
 *
 * 防呆 R1: dialog footer shows "已展开 X 项明细 / 上限 N 项"
 * 防呆 R2: header shows template name + parent context after expansion
 * 防呆 R5: empty children table shows "+ 选择模板" or "+ 添加首行" CTA
 */
interface ChildColumn<C> {
  prop: keyof C;
  label: string;
  width?: number | string;
  slotName?: string;
  /** Hint shown below empty cells. */
  placeholder?: string;
}

interface BomTemplate {
  id: string;
  name: string;
  /** Optional short description e.g. "卤猪蹄 200g / 3 种食材" */
  description?: string;
}

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    title?: string;
    /** Entity label, e.g. "领料单" / "销售订单". */
    entityLabel?: string;
    /** List of BOM templates user can pick from. */
    templates: BomTemplate[];
    /** Empty parent factory. */
    parentFactory: () => P;
    /** Initial blank children if no template chosen yet. */
    childrenFactory?: () => C[];
    /** Called when a template is selected — returns child rows. */
    expandTemplate: (templateId: string, parent: P) => Promise<C[]>;
    /** Bulk submit. */
    submit: (parent: P, children: C[]) => Promise<void>;
    /** Column definitions for children grid. */
    childColumns: ChildColumn<C>[];
    /** Max allowed child rows (R1). Default 100. */
    maxChildren?: number;
  }>(),
  {
    title: 'BOM 展开新建',
    entityLabel: '单据',
    childrenFactory: () => [] as C[],
    maxChildren: 100,
  }
);

const emit = defineEmits<{
  (e: 'update:modelValue', open: boolean): void;
  (e: 'submitted', parent: P, children: C[]): void;
}>();

const parent = ref<Record<string, any>>(props.parentFactory() as Record<string, any>);
const children = ref<Record<string, any>[]>([]);
const selectedTemplateId = ref<string>('');
const expanding = ref(false);
const submitting = ref(false);

const selectedTemplate = computed<BomTemplate | null>(() =>
  props.templates.find((t) => t.id === selectedTemplateId.value) || null
);

const remainingSlots = computed(() => Math.max(0, props.maxChildren - children.value.length));

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      parent.value = props.parentFactory() as Record<string, any>;
      children.value = (props.childrenFactory?.() || []) as Record<string, any>[];
      selectedTemplateId.value = '';
    }
  },
  { immediate: true }
);

async function onTemplateChange(templateId: string): Promise<void> {
  if (!templateId) {
    children.value = [];
    return;
  }
  expanding.value = true;
  try {
    const expanded = await props.expandTemplate(templateId, parent.value as P);
    if (!Array.isArray(expanded) || expanded.length === 0) {
      ElMessage.warning('该模板暂未配置 BOM 明细');
      children.value = [];
      return;
    }
    if (expanded.length > props.maxChildren) {
      ElMessage.warning(`模板含 ${expanded.length} 项明细，超过上限 ${props.maxChildren}，已截取前 ${props.maxChildren} 项`);
      children.value = expanded.slice(0, props.maxChildren) as Record<string, any>[];
    } else {
      children.value = expanded as Record<string, any>[];
    }
    ElMessage.success(`已展开 ${children.value.length} 项明细`);
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '模板展开失败';
    ElMessage.error(msg);
    children.value = [];
  } finally {
    expanding.value = false;
  }
}

function reExpand(): void {
  if (selectedTemplateId.value) {
    void onTemplateChange(selectedTemplateId.value);
  }
}

function addBlankChild(): void {
  if (remainingSlots.value <= 0) {
    ElMessage.warning(`已达上限 ${props.maxChildren} 项明细`);
    return;
  }
  // Push an empty object — caller's columns will render inputs against it.
  children.value.push({} as C);
}

function removeChild(idx: number): void {
  children.value.splice(idx, 1);
}

async function handleSubmit(): Promise<void> {
  if (!children.value.length) {
    ElMessage.warning('请至少展开或添加 1 项明细');
    return;
  }
  submitting.value = true;
  try {
    await props.submit(parent.value as P, children.value as C[]);
    emit('submitted', parent.value as P, children.value as C[]);
    emit('update:modelValue', false);
    ElMessage.success(`成功创建 ${props.entityLabel}（含 ${children.value.length} 项明细）`);
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '提交失败';
    ElMessage.error(msg);
  } finally {
    submitting.value = false;
  }
}

function close(): void {
  if (submitting.value) return;
  emit('update:modelValue', false);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="92%"
    top="5vh"
    :show-close="!submitting"
    :close-on-click-modal="false"
    @update:model-value="close"
  >
    <!-- R2 header: entity + template context. -->
    <div class="bom-header">
      <el-icon class="bom-header-icon"><Histogram /></el-icon>
      <div class="bom-header-text">
        <div class="bom-header-title">
          {{ entityLabel }}
          <span v-if="selectedTemplate" class="bom-header-tpl">— 模板: {{ selectedTemplate.name }}</span>
        </div>
        <div v-if="selectedTemplate?.description" class="bom-header-sub">{{ selectedTemplate.description }}</div>
        <div v-else class="bom-header-sub">选择 BOM/配方模板, 系统自动展开子明细; 也可手动添加行</div>
      </div>
    </div>

    <!-- Parent form (caller-supplied slot). -->
    <div class="bom-section">
      <div class="bom-section-title">
        <span>1. 单据基础信息</span>
      </div>
      <slot name="parent-fields" :parent="parent" />
    </div>

    <!-- Template selector. -->
    <div class="bom-section">
      <div class="bom-section-title">
        <span>2. 选择 BOM / 配方模板</span>
        <el-button
          v-if="selectedTemplateId"
          link
          type="primary"
          :icon="RefreshRight"
          :loading="expanding"
          @click="reExpand"
        >
          重新展开
        </el-button>
      </div>
      <el-select
        v-model="selectedTemplateId"
        placeholder="请选择模板 (可留空, 改为手动添加明细)"
        filterable
        clearable
        style="width: 100%"
        :disabled="expanding || submitting"
        @change="onTemplateChange"
      >
        <el-option
          v-for="t in templates"
          :key="t.id"
          :label="t.name"
          :value="t.id"
        >
          <span style="float: left">{{ t.name }}</span>
          <span v-if="t.description" style="float: right; color: var(--el-text-color-secondary); font-size: 12px">
            {{ t.description }}
          </span>
        </el-option>
      </el-select>
      <div v-if="!templates.length" class="bom-template-empty">
        <el-icon><InfoFilled /></el-icon>
        <span>暂无可用 BOM/配方模板, 请先在 系统设置 → 配方管理 中创建</span>
      </div>
    </div>

    <!-- Children table. -->
    <div class="bom-section">
      <div class="bom-section-title">
        <span>3. 明细行 ({{ children.length }} / {{ maxChildren }})</span>
        <el-button :icon="Plus" size="small" @click="addBlankChild" :disabled="remainingSlots <= 0">
          手动添加行
        </el-button>
      </div>

      <!-- R5 empty state. -->
      <div v-if="!children.length" class="bom-empty-state">
        <el-icon :size="36" color="#909399"><Histogram /></el-icon>
        <div class="bom-empty-title">暂无明细</div>
        <div class="bom-empty-hint">
          点击上方 <strong>选择模板</strong> 自动展开, 或 <strong>手动添加行</strong> 录入
        </div>
      </div>

      <el-table v-else :data="children" border style="width: 100%" max-height="50vh">
        <el-table-column type="index" label="#" width="60" />
        <el-table-column
          v-for="col in childColumns"
          :key="String(col.prop)"
          :label="col.label"
          :width="col.width"
        >
          <template #default="{ row, $index }">
            <slot
              v-if="col.slotName"
              :name="col.slotName"
              :row="row"
              :index="$index"
              :template="selectedTemplate"
            />
            <el-input
              v-else
              v-model="row[col.prop]"
              size="small"
              :placeholder="col.placeholder || ''"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ $index }">
            <el-button
              link
              type="danger"
              :icon="Delete"
              @click="removeChild($index)"
            />
          </template>
        </el-table-column>
      </el-table>

      <!-- R1 counter. -->
      <div class="bom-counter">
        <span>共 <strong>{{ children.length }}</strong> 项明细 / 上限 {{ maxChildren }}</span>
        <span v-if="remainingSlots <= 5 && remainingSlots > 0" class="bom-counter-warn">
          剩余 {{ remainingSlots }} 行可添加
        </span>
        <span v-else-if="remainingSlots === 0" class="bom-counter-stop">
          已达上限
        </span>
      </div>
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="close">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!children.length"
        @click="handleSubmit"
      >
        提交 (含 {{ children.length }} 项明细)
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.bom-header {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin-bottom: 16px;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}
.bom-header-icon {
  font-size: 22px;
  color: var(--el-color-primary);
  margin-top: 2px;
}
.bom-header-text {
  flex: 1;
}
.bom-header-title {
  font-weight: 600;
  font-size: 15px;
  color: var(--el-text-color-primary);
}
.bom-header-tpl {
  font-weight: 500;
  color: var(--el-color-primary);
  margin-left: 4px;
}
.bom-header-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.bom-section {
  margin-bottom: 16px;
}
.bom-section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.bom-template-empty {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  gap: 6px;
  align-items: center;
}
.bom-empty-state {
  padding: 32px 16px;
  text-align: center;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.bom-empty-title {
  margin-top: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}
.bom-empty-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.bom-counter {
  margin-top: 8px;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
}
.bom-counter-warn {
  color: var(--el-color-warning);
  font-weight: 500;
}
.bom-counter-stop {
  color: var(--el-color-danger);
  font-weight: 500;
}
</style>
