<!--
  EnumDictionaryEditor — Canvas-Phase C 实现 (2026-05-22).

  覆盖范围: 8 大类标准 dropdown (防呆 Rule 3 自由文本改约束选择)
    - CANCEL_REASON     取消原因   (5 codes)
    - RETURN_REASON     退货原因   (5 codes)
    - APPROVAL_OPINION  审批意见   (5 codes)
    - DEFECT_SEVERITY   缺陷严重度 (5 codes)
    - NONCONFORM_TYPE   不合格类型 (6 codes)
    - WASTAGE_REASON    损耗原因   (6 codes)
    - RECALL_LEVEL      召回等级   (3 codes)
    - URGENCY_LEVEL     紧急程度   (4 codes)

  Backend: /api/mobile/{factoryId}/canvas-enum-dictionary
  防呆 (Rule 3): 工厂级别配置 dropdown 值, 强制用户选择而非自由文本; 同时支持嵌套 parentCode.
-->
<template>
  <div class="enum-dictionary-editor">
    <div class="panel-header">
      <h3>枚举字典</h3>
      <div class="panel-actions">
        <el-tag size="small" type="info">factoryId: {{ factoryId }}</el-tag>
        <el-button type="primary" size="small" @click="openCreateDialog" :disabled="loading">
          新增枚举值
        </el-button>
        <el-button size="small" @click="loadCurrent" :disabled="loading">刷新</el-button>
      </div>
    </div>

    <div class="filter-bar">
      <el-radio-group v-model="categoryFilter" size="small" @change="loadCurrent">
        <el-radio-button label="">全部</el-radio-button>
        <el-radio-button
          v-for="cat in STANDARD_CATEGORIES"
          :key="cat"
          :label="cat"
        >
          {{ EnumCategoryLabels[cat] || cat }}
        </el-radio-button>
      </el-radio-group>
      <el-input
        v-model="searchKeyword"
        placeholder="按 code/label 搜索..."
        size="small"
        clearable
        style="width: 220px; margin-left: 12px;"
      />
    </div>

    <el-table
      v-loading="loading"
      :data="filteredRows"
      stripe
      style="width: 100%"
      empty-text="暂无枚举值, 点击 '新增枚举值' 创建"
    >
      <el-table-column label="类别" width="160">
        <template #default="{ row }">
          <el-tag size="small">{{ EnumCategoryLabels[row.category] || row.category }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="code" min-width="180">
        <template #default="{ row }">
          <code class="code-cell">{{ row.code }}</code>
          <span v-if="row.parentCode" class="parent-hint"> ↳ {{ row.parentCode }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="label" label="显示文本" min-width="180" />
      <el-table-column prop="displayOrder" label="顺序" width="80" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
            {{ row.enabled ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openEditDialog(row)">编辑</el-button>
          <el-button size="small" type="danger" link @click="onDeleteRow(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Create / Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingRow ? `编辑枚举值: ${editingRow.code}` : '新增枚举值'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="formRules"
        label-width="120px"
      >
        <el-form-item label="类别" prop="category">
          <el-select
            v-model="form.category"
            placeholder="选择标准类别 (或手动输入)"
            filterable
            allow-create
            default-first-option
            :disabled="!!editingRow"
            style="width: 100%"
          >
            <el-option
              v-for="cat in STANDARD_CATEGORIES"
              :key="cat"
              :label="`${cat} - ${EnumCategoryLabels[cat] || ''}`"
              :value="cat"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="code" prop="code">
          <el-input
            v-model="form.code"
            placeholder="例: CUSTOMER_CANCEL (UPPER_SNAKE_CASE)"
            :disabled="!!editingRow"
            maxlength="50"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="显示文本" prop="label">
          <el-input
            v-model="form.label"
            placeholder="例: 客户撤单"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="顺序">
          <el-input-number v-model="form.displayOrder" :min="0" :max="9999" />
          <span class="form-hint" style="margin-left: 12px;">小到大排序, 默认 0</span>
        </el-form-item>
        <el-form-item label="父级 code">
          <el-input
            v-model="form.parentCode"
            placeholder="可选 — 同 category 内嵌套层级"
            clearable
            maxlength="50"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <span class="form-hint" style="margin-left: 12px;">
            禁用时不出现在 dropdown.
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import {
  listEnums,
  createEnum,
  updateEnum,
  deleteEnum,
  EnumCategoryLabels,
  type EnumDictionary,
  type CreateEnumRequest,
  type UpdateEnumRequest,
} from '@/api/enumDictionary';

const props = defineProps<{
  factoryId: string;
}>();

const STANDARD_CATEGORIES = [
  'CANCEL_REASON',
  'RETURN_REASON',
  'APPROVAL_OPINION',
  'DEFECT_SEVERITY',
  'NONCONFORM_TYPE',
  'WASTAGE_REASON',
  'RECALL_LEVEL',
  'URGENCY_LEVEL',
] as const;

const rows = ref<EnumDictionary[]>([]);
const loading = ref(false);
const saving = ref(false);
const categoryFilter = ref<string>('');
const searchKeyword = ref<string>('');

const filteredRows = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase();
  if (!kw) return rows.value;
  return rows.value.filter(
    (r) =>
      r.code.toLowerCase().includes(kw) ||
      r.label.toLowerCase().includes(kw) ||
      (r.description ?? '').toLowerCase().includes(kw),
  );
});

// Dialog state
const dialogVisible = ref(false);
const editingRow = ref<EnumDictionary | null>(null);
const form = reactive<{
  category: string;
  code: string;
  label: string;
  displayOrder: number;
  enabled: boolean;
  parentCode: string;
  description: string;
}>({
  category: '',
  code: '',
  label: '',
  displayOrder: 0,
  enabled: true,
  parentCode: '',
  description: '',
});
const formRef = ref<FormInstance | null>(null);

const formRules: FormRules = {
  category: [
    { required: true, message: '请选择或输入类别', trigger: 'change' },
    {
      pattern: /^[A-Z][A-Z0-9_]{0,49}$/,
      message: 'category 必须是 UPPER_SNAKE_CASE (最长 50)',
      trigger: 'blur',
    },
  ],
  code: [
    { required: true, message: '请输入 code', trigger: 'blur' },
    {
      pattern: /^[A-Z][A-Z0-9_]{0,49}$/,
      message: 'code 必须是 UPPER_SNAKE_CASE (最长 50)',
      trigger: 'blur',
    },
  ],
  label: [
    { required: true, message: '请输入显示文本', trigger: 'blur' },
    { max: 200, message: '最长 200 字符', trigger: 'blur' },
  ],
};

async function loadCurrent() {
  if (!props.factoryId) return;
  loading.value = true;
  try {
    rows.value = await listEnums(
      props.factoryId,
      categoryFilter.value ? categoryFilter.value : undefined,
    );
  } catch (e) {
    console.error('[EnumDictionary] load failed:', e);
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  form.category = categoryFilter.value || '';
  form.code = '';
  form.label = '';
  form.displayOrder = 0;
  form.enabled = true;
  form.parentCode = '';
  form.description = '';
}

function openCreateDialog() {
  editingRow.value = null;
  resetForm();
  dialogVisible.value = true;
}

function openEditDialog(row: EnumDictionary) {
  editingRow.value = row;
  form.category = row.category;
  form.code = row.code;
  form.label = row.label;
  form.displayOrder = row.displayOrder;
  form.enabled = row.enabled;
  form.parentCode = row.parentCode ?? '';
  form.description = row.description ?? '';
  dialogVisible.value = true;
}

async function onSave() {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    if (editingRow.value) {
      const payload: UpdateEnumRequest = {
        label: form.label,
        displayOrder: form.displayOrder,
        enabled: form.enabled,
        parentCode: form.parentCode ? form.parentCode : null,
        description: form.description ? form.description : null,
        version: editingRow.value.version,
      };
      await updateEnum(props.factoryId, editingRow.value.id, payload);
      ElMessage.success('枚举值已更新');
    } else {
      const payload: CreateEnumRequest = {
        category: form.category.toUpperCase(),
        code: form.code.toUpperCase(),
        label: form.label,
        displayOrder: form.displayOrder,
        enabled: form.enabled,
        parentCode: form.parentCode ? form.parentCode : null,
        description: form.description ? form.description : null,
      };
      await createEnum(props.factoryId, payload);
      ElMessage.success('枚举值已创建');
    }
    dialogVisible.value = false;
    await loadCurrent();
  } catch (e) {
    // request interceptor 会展示 sticky error toast
    console.error('[EnumDictionary] save failed:', e);
  } finally {
    saving.value = false;
  }
}

async function onDeleteRow(row: EnumDictionary) {
  try {
    await ElMessageBox.confirm(
      `删除枚举值 "${row.code}" (${row.label})?`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    await deleteEnum(props.factoryId, row.id);
    ElMessage.success('枚举值已删除');
    await loadCurrent();
  } catch (e) {
    console.error('[EnumDictionary] delete failed:', e);
  }
}

watch(() => props.factoryId, () => {
  if (props.factoryId) loadCurrent();
});

onMounted(() => {
  if (props.factoryId) loadCurrent();
});
</script>

<style scoped>
.enum-dictionary-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 12px;
  gap: 12px;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.panel-header h3 {
  margin: 0;
  font-size: 16px;
  color: var(--el-text-color-primary);
}
.panel-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 0;
}
.code-cell {
  font-family: var(--el-font-family-mono, ui-monospace, monospace);
  font-size: 12px;
  background: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 3px;
}
.parent-hint {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  margin-left: 4px;
}
.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
