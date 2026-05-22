<!--
  ThresholdsHubEditor — Canvas-Thresholds Phase A 实现 (2026-05-21).

  覆盖范围: 6 类 / ~19 阈值 (per docs/audits/2026-05-21-liushanmen-canvas-process-design.md §P0-1)
    - INVENTORY   库存健康       (11 阈值: turnover/expiry/loss/aging/expiry days)
    - IOT         冷链/环境      (5 阈值: cold-chain/normal temp/humidity)
    - CREDIT      客户信用       (1 阈值: warning_ratio)
    - BOM         BOM 递归       (1 阈值: max_depth)
    - SHORTAGE    缺料分析       (1 阈值: production_lead_days)
    - AI          AI 复杂度路由  (3 阈值: fast/analysis/multi_agent)

  Backend: /api/mobile/{factoryId}/canvas-thresholds
  防呆 (Rule 1+2+5): 显示当前值/默认值/单位/描述 — 编辑 dialog 携带 min/max 校验, 防止存非法值.
-->
<template>
  <div class="thresholds-hub-editor">
    <div class="panel-header">
      <h3>阈值参数中心</h3>
      <div class="panel-actions">
        <el-tag size="small" type="info">factoryId: {{ factoryId }}</el-tag>
        <el-button type="warning" size="small" @click="onResetCurrentCategory" :disabled="loading">
          重置当前类别为默认
        </el-button>
        <el-button size="small" @click="loadCurrent" :disabled="loading">刷新</el-button>
      </div>
    </div>

    <el-tabs v-model="activeCategory" class="category-tabs" @tab-change="loadCurrent">
      <el-tab-pane
        v-for="cat in CATEGORY_ORDER"
        :key="cat"
        :label="ThresholdCategoryLabels[cat]"
        :name="cat"
      >
        <el-table
          v-loading="loading"
          :data="rows"
          stripe
          style="width: 100%"
          empty-text="此类别尚无阈值配置 (按需新增, 或全局默认 '*' 中已有)"
        >
          <el-table-column prop="thresholdKey" label="键 (key)" min-width="220">
            <template #default="{ row }">
              <code class="key-cell">{{ row.thresholdKey }}</code>
            </template>
          </el-table-column>
          <el-table-column label="当前值" min-width="120">
            <template #default="{ row }">
              <span :class="{ 'value-overridden': row.thresholdValue !== row.defaultValue }">
                {{ row.thresholdValue }}
              </span>
              <span class="unit-text" v-if="row.unit"> {{ row.unit }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="defaultValue" label="默认值" min-width="100">
            <template #default="{ row }">
              <span class="default-text">{{ row.defaultValue }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="valueType" label="类型" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="valueTypeTagType(row.valueType)">
                {{ row.valueType }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="180" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
                {{ row.enabled ? '启用' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="openEditDialog(row)">
                编辑
              </el-button>
              <el-button
                size="small"
                type="warning"
                link
                @click="onResetRow(row)"
                :disabled="row.thresholdValue === row.defaultValue"
              >
                重置
              </el-button>
              <el-button size="small" type="danger" link @click="onDeleteRow(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- Edit Dialog -->
    <el-dialog
      v-model="editDialogVisible"
      :title="`编辑阈值: ${editingRow?.thresholdKey ?? ''}`"
      width="540px"
      :close-on-click-modal="false"
    >
      <el-form
        v-if="editingRow"
        ref="editFormRef"
        :model="editForm"
        :rules="editRules"
        label-width="110px"
      >
        <el-form-item label="thresholdKey">
          <el-input :value="editingRow.thresholdKey" disabled />
        </el-form-item>
        <el-form-item label="分类">
          <el-tag>{{ ThresholdCategoryLabels[editingRow.category] }}</el-tag>
        </el-form-item>
        <el-form-item label="类型">
          <el-tag :type="valueTypeTagType(editingRow.valueType)">
            {{ editingRow.valueType }}
          </el-tag>
        </el-form-item>
        <el-form-item label="当前值" prop="thresholdValue">
          <el-input
            v-model="editForm.thresholdValue"
            :placeholder="`默认 ${editingRow.defaultValue}${editingRow.unit ? ' ' + editingRow.unit : ''}`"
          >
            <template v-if="editingRow.unit" #append>{{ editingRow.unit }}</template>
          </el-input>
          <div class="form-hint" v-if="editingRow.minValue || editingRow.maxValue">
            合法范围:
            <span v-if="editingRow.minValue">≥ {{ editingRow.minValue }}</span>
            <span v-if="editingRow.minValue && editingRow.maxValue">, </span>
            <span v-if="editingRow.maxValue">≤ {{ editingRow.maxValue }}</span>
          </div>
          <div class="form-hint" v-else>无范围限制</div>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editForm.enabled" />
          <span class="form-hint" style="margin-left: 12px;">
            禁用时此阈值不生效, service 回退到全局默认或 hard-coded fallback.
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import {
  listThresholds,
  updateThreshold,
  deleteThreshold,
  resetCategoryToDefault,
  ThresholdCategoryLabels,
  type FactoryThreshold,
  type ThresholdCategory,
  type ThresholdValueType,
  type UpdateThresholdRequest,
} from '@/api/thresholdsHub';

const props = defineProps<{
  factoryId: string;
}>();

const CATEGORY_ORDER: ThresholdCategory[] = ['INVENTORY', 'IOT', 'CREDIT', 'BOM', 'SHORTAGE', 'AI'];
const activeCategory = ref<ThresholdCategory>('INVENTORY');
const rows = ref<FactoryThreshold[]>([]);
const loading = ref(false);
const saving = ref(false);

// Edit dialog state
const editDialogVisible = ref(false);
const editingRow = ref<FactoryThreshold | null>(null);
const editForm = reactive<{ thresholdValue: string; description: string; enabled: boolean }>({
  thresholdValue: '',
  description: '',
  enabled: true,
});
const editFormRef = ref<FormInstance | null>(null);

const editRules: FormRules = {
  thresholdValue: [
    { required: true, message: '请输入阈值', trigger: 'blur' },
    { validator: validateValueByType, trigger: 'blur' },
    { validator: validateRange, trigger: 'blur' },
  ],
};

function validateValueByType(_rule: unknown, value: string, callback: (err?: Error) => void) {
  if (!value || !editingRow.value) return callback();
  const type = editingRow.value.valueType;
  if (type === 'INTEGER') {
    if (!/^-?\d+$/.test(value)) return callback(new Error('必须是整数'));
  } else if (type === 'DECIMAL' || type === 'DOUBLE') {
    if (!/^-?\d+(\.\d+)?$/.test(value)) return callback(new Error(`必须是 ${type === 'DECIMAL' ? '十进制数' : '浮点数'}`));
  }
  callback();
}

function validateRange(_rule: unknown, value: string, callback: (err?: Error) => void) {
  if (!value || !editingRow.value) return callback();
  const row = editingRow.value;
  if (row.valueType === 'STRING') return callback();
  const num = Number(value);
  if (isNaN(num)) return callback(); // already caught by validateValueByType
  if (row.minValue && num < Number(row.minValue)) {
    return callback(new Error(`不能小于 ${row.minValue}`));
  }
  if (row.maxValue && num > Number(row.maxValue)) {
    return callback(new Error(`不能大于 ${row.maxValue}`));
  }
  callback();
}

function valueTypeTagType(t: ThresholdValueType): 'success' | 'warning' | 'info' | 'primary' {
  switch (t) {
    case 'INTEGER': return 'primary';
    case 'DECIMAL': return 'success';
    case 'DOUBLE': return 'warning';
    default: return 'info';
  }
}

async function loadCurrent() {
  if (!props.factoryId) return;
  loading.value = true;
  try {
    rows.value = await listThresholds(props.factoryId, activeCategory.value);
  } catch (e) {
    console.error('[ThresholdsHub] load failed:', e);
  } finally {
    loading.value = false;
  }
}

function openEditDialog(row: FactoryThreshold) {
  editingRow.value = row;
  editForm.thresholdValue = row.thresholdValue;
  editForm.description = row.description ?? '';
  editForm.enabled = row.enabled;
  editDialogVisible.value = true;
}

async function onSaveEdit() {
  if (!editingRow.value || !editFormRef.value) return;
  const valid = await editFormRef.value.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    const payload: UpdateThresholdRequest = {
      thresholdValue: editForm.thresholdValue,
      description: editForm.description,
      enabled: editForm.enabled,
      version: editingRow.value.version,
    };
    await updateThreshold(props.factoryId, editingRow.value.id, payload);
    ElMessage.success('阈值已更新');
    editDialogVisible.value = false;
    await loadCurrent();
  } catch (e) {
    // request interceptor 会展示 sticky error toast; here just log.
    console.error('[ThresholdsHub] update failed:', e);
  } finally {
    saving.value = false;
  }
}

async function onResetRow(row: FactoryThreshold) {
  try {
    await ElMessageBox.confirm(
      `将 "${row.thresholdKey}" 重置为默认值 ${row.defaultValue}${row.unit ?? ''}?`,
      '确认重置',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    await updateThreshold(props.factoryId, row.id, {
      thresholdValue: row.defaultValue,
      version: row.version,
    });
    ElMessage.success('已重置到默认值');
    await loadCurrent();
  } catch (e) {
    console.error('[ThresholdsHub] reset row failed:', e);
  }
}

async function onResetCurrentCategory() {
  const label = ThresholdCategoryLabels[activeCategory.value];
  try {
    await ElMessageBox.confirm(
      `将 [${label}] 类别下所有阈值重置为默认值? 此操作不可撤销.`,
      '批量重置确认',
      { type: 'warning', confirmButtonText: '批量重置', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    const updated = await resetCategoryToDefault(props.factoryId, activeCategory.value);
    ElMessage.success(`已重置 ${updated} 个阈值`);
    await loadCurrent();
  } catch (e) {
    console.error('[ThresholdsHub] batch reset failed:', e);
  }
}

async function onDeleteRow(row: FactoryThreshold) {
  try {
    await ElMessageBox.confirm(
      `删除阈值 "${row.thresholdKey}"? 删除后 service 将回退到全局默认或 hard-coded fallback.`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    await deleteThreshold(props.factoryId, row.id);
    ElMessage.success('阈值已删除');
    await loadCurrent();
  } catch (e) {
    console.error('[ThresholdsHub] delete failed:', e);
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
.thresholds-hub-editor {
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
.category-tabs {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.category-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow: auto;
}
.key-cell {
  font-family: var(--el-font-family-mono, ui-monospace, monospace);
  font-size: 12px;
  background: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 3px;
}
.value-overridden {
  font-weight: 600;
  color: var(--el-color-warning);
}
.default-text {
  color: var(--el-text-color-secondary);
}
.unit-text {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-left: 4px;
}
.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
</style>
