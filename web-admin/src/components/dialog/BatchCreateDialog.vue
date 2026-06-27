<script setup lang="ts" generic="T extends Record<string, any>">
import { ref, watch } from 'vue';
import { Plus, Delete } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';

/**
 * U-NEW-1 batch mode (二维表格) — generic multi-row create dialog.
 *
 * The parent supplies:
 *   - columns: column definitions (prop, label, width, slot type)
 *   - rowFactory: () => T  (called when adding a new blank row)
 *   - submit: (rows: T[]) => Promise<void>
 *
 * The dialog handles add/remove rows + bulk submit. Validation hooks
 * are caller-side (parent validates inside submit and throws to abort).
 */
interface BatchColumn<T> {
  prop: keyof T;
  label: string;
  width?: number | string;
  /** Slot name to override the default plain input. */
  slotName?: string;
  required?: boolean;
}

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    title?: string;
    columns: BatchColumn<T>[];
    rowFactory: () => T;
    submit: (rows: T[]) => Promise<void>;
    /** Initial blank row count. */
    initialRows?: number;
  }>(),
  {
    title: '批量新建',
    initialRows: 3,
  }
);

const emit = defineEmits<{
  (e: 'update:modelValue', open: boolean): void;
  (e: 'submitted', rows: T[]): void;
}>();

const rows = ref<Record<string, any>[]>([]);
const submitting = ref(false);

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      rows.value = Array.from({ length: props.initialRows }, () => props.rowFactory() as Record<string, any>);
    }
  },
  { immediate: true }
);

function addRow(): void {
  rows.value.push(props.rowFactory() as any);
}

function removeRow(idx: number): void {
  if (rows.value.length > 1) {
    rows.value.splice(idx, 1);
  }
}

async function handleSubmit(): Promise<void> {
  const validRows = rows.value.filter((r) =>
    props.columns.some((c) => {
      const key = String(c.prop);
      return c.required ? r[key] != null && r[key] !== '' : true;
    })
  );
  if (!validRows.length) {
    ElMessage.warning('请至少填写一行');
    return;
  }
  submitting.value = true;
  try {
    await props.submit(validRows as T[]);
    emit('submitted', validRows as T[]);
    emit('update:modelValue', false);
    ElMessage.success(`成功创建 ${validRows.length} 条记录`);
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '批量创建失败';
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
    width="90%"
    top="6vh"
    :show-close="!submitting"
    :close-on-click-modal="false"
    @update:model-value="close"
  >
    <el-table :data="rows" border style="width: 100%" max-height="60vh">
      <el-table-column
        v-for="col in columns"
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
          />
          <el-input v-else v-model="row[String(col.prop)]" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ $index }">
          <el-button
            link
            type="danger"
            :icon="Delete"
            :disabled="rows.length <= 1"
            @click="removeRow($index)"
          />
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top: 12px; display: flex; gap: 8px; align-items: center">
      <el-button :icon="Plus" size="small" @click="addRow">新增行</el-button>
      <span style="color: var(--el-text-color-secondary); font-size: 12px">
        当前 {{ rows.length }} 行，提交时会过滤空行
      </span>
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        批量提交
      </el-button>
    </template>
  </el-dialog>
</template>
