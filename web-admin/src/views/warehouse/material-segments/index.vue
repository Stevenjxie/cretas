<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Delete as DeleteIcon, Edit, Plus, Refresh } from '@element-plus/icons-vue';
import { del, get, post, put } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';

type SegmentLevel = 1 | 2 | 3;

interface SegmentNode {
  id: number;
  segmentCode: string;
  segmentLabel: string;
  level: SegmentLevel;
  parentCode: string | null;
  sortOrder: number;
  isActive: boolean;
  children?: SegmentNode[];
}

interface SegmentForm {
  id: number | null;
  level: SegmentLevel;
  segmentCode: string;
  segmentLabel: string;
  parentCode: string | null;
  sortOrder: number;
  isActive: boolean;
}

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse') || permissionStore.canWrite('production'));

const loading = ref(false);
const saving = ref(false);
const dialogVisible = ref(false);
const treeData = ref<SegmentNode[]>([]);
const selectedLevel = ref<SegmentLevel>(1);

const form = reactive<SegmentForm>({
  id: null,
  level: 1,
  segmentCode: '',
  segmentLabel: '',
  parentCode: null,
  sortOrder: 0,
  isActive: true,
});

const levelOptions = [
  { label: 'L1 type', value: 1 },
  { label: 'L2 part', value: 2 },
  { label: 'L3 item', value: 3 },
] as const;

const flatRows = computed(() => {
  const rows: SegmentNode[] = [];
  const walk = (nodes: SegmentNode[]) => {
    nodes.forEach((node) => {
      rows.push(node);
      if (node.children?.length) walk(node.children);
    });
  };
  walk(treeData.value);
  return rows.sort((a, b) => a.level - b.level || a.segmentCode.localeCompare(b.segmentCode));
});

const tableRows = computed(() => flatRows.value.filter((row) => row.level === selectedLevel.value));
const l1Options = computed(() => flatRows.value.filter((row) => row.level === 1));
const l2Options = computed(() => flatRows.value.filter((row) => row.level === 2));
const parentOptions = computed(() => {
  if (form.level === 1) return [];
  return form.level === 2 ? l1Options.value : l2Options.value;
});

function resetForm(level: SegmentLevel = selectedLevel.value) {
  form.id = null;
  form.level = level;
  form.segmentCode = '';
  form.segmentLabel = '';
  form.parentCode = null;
  form.sortOrder = 0;
  form.isActive = true;
}

function showStickyError(message: string) {
  ElMessage({ message, type: 'error', duration: 0, showClose: true });
}

function errorMessage(error: unknown, fallback: string) {
  if (error instanceof Error && error.message) return error.message;
  return fallback;
}

async function loadTree() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get<SegmentNode[]>(`/${factoryId.value}/material-segments/tree`);
    treeData.value = Array.isArray(res.data) ? res.data : [];
  } catch (error) {
    showStickyError(errorMessage(error, 'Segment tree load failed. Refresh and retry.'));
  } finally {
    loading.value = false;
  }
}

function openCreate(level: SegmentLevel = selectedLevel.value) {
  resetForm(level);
  dialogVisible.value = true;
}

function openEdit(row: SegmentNode) {
  form.id = row.id;
  form.level = row.level;
  form.segmentCode = row.segmentCode;
  form.segmentLabel = row.segmentLabel;
  form.parentCode = row.parentCode;
  form.sortOrder = row.sortOrder ?? 0;
  form.isActive = row.isActive !== false;
  dialogVisible.value = true;
}

function validateForm(): boolean {
  const code = form.segmentCode.trim();
  if (!form.segmentLabel.trim()) {
    showStickyError('Segment name is required.');
    return false;
  }
  if (form.level === 1 && !/^\d{3}$/.test(code)) {
    showStickyError('L1 code must be 3 digits, for example 001.');
    return false;
  }
  if (form.level === 2 && !/^\d{6}$/.test(code)) {
    showStickyError('L2 code must be 6 cumulative digits, for example 001001.');
    return false;
  }
  if (form.level === 3 && !/^\d{10}$/.test(code)) {
    showStickyError('L3 code must be 10 cumulative digits, for example 0010010001.');
    return false;
  }
  if (form.level > 1 && !form.parentCode) {
    showStickyError('Parent segment is required.');
    return false;
  }
  return true;
}

async function saveSegment() {
  if (!factoryId.value || !validateForm()) return;
  saving.value = true;
  const payload = {
    level: form.level,
    segmentCode: form.segmentCode.trim(),
    segmentLabel: form.segmentLabel.trim(),
    parentCode: form.level === 1 ? null : form.parentCode,
    sortOrder: Number(form.sortOrder || 0),
    isActive: form.isActive,
  };
  try {
    if (form.id) {
      await put(`/${factoryId.value}/material-segments/${form.id}`, payload);
      ElMessage.success('Segment updated.');
    } else {
      await post(`/${factoryId.value}/material-segments`, payload);
      ElMessage.success('Segment created.');
    }
    dialogVisible.value = false;
    await loadTree();
  } catch (error) {
    showStickyError(errorMessage(error, 'Segment save failed. Check the code path and retry.'));
  } finally {
    saving.value = false;
  }
}

async function deleteSegment(row: SegmentNode) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(
      `Delete segment ${row.segmentCode} ${row.segmentLabel}?`,
      'Delete segment',
      { type: 'warning', confirmButtonText: 'Delete', cancelButtonText: 'Cancel' },
    );
    await del(`/${factoryId.value}/material-segments/${row.id}`);
    ElMessage.success('Segment deleted.');
    await loadTree();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    showStickyError(errorMessage(error, 'Segment delete failed. Remove child segments first, then retry.'));
  }
}

onMounted(loadTree);
</script>

<template>
  <div class="page-wrapper">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div>
            <div class="page-title">Material Code Segments</div>
            <div class="page-subtitle">L1 type, L2 part, L3 item</div>
          </div>
          <div class="header-actions">
            <el-button :icon="Refresh" :loading="loading" @click="loadTree">Refresh</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate()">New</el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="treeData.length === 0 && !loading"
        title="No segment dictionary is configured for this factory."
        type="warning"
        :closable="false"
        show-icon
        class="empty-alert"
      />

      <el-segmented v-model="selectedLevel" :options="levelOptions" class="level-tabs" />

      <el-table v-loading="loading" :data="tableRows" stripe row-key="id" empty-text="No segments">
        <el-table-column prop="segmentCode" label="Code" width="150" />
        <el-table-column prop="segmentLabel" label="Name" min-width="180" />
        <el-table-column prop="parentCode" label="Parent" width="140">
          <template #default="{ row }">{{ row.parentCode || '-' }}</template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="Sort" width="100" />
        <el-table-column label="Status" width="100">
          <template #default="{ row }">
            <el-tag :type="row.isActive === false ? 'info' : 'success'" size="small">
              {{ row.isActive === false ? 'Inactive' : 'Active' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="Actions" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="openEdit(row)">Edit</el-button>
            <el-button link type="danger" :icon="DeleteIcon" @click="deleteSegment(row)">Delete</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? 'Edit segment' : 'New segment'" width="520px" destroy-on-close>
      <el-form :model="form" label-width="112px">
        <el-form-item label="Level" required>
          <el-select v-model="form.level" :disabled="!!form.id" style="width: 100%">
            <el-option label="L1 type (3)" :value="1" />
            <el-option label="L2 part (6)" :value="2" />
            <el-option label="L3 item (10)" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.level > 1" label="Parent" required>
          <el-select v-model="form.parentCode" filterable style="width: 100%">
            <el-option
              v-for="item in parentOptions"
              :key="item.segmentCode"
              :label="`${item.segmentCode} ${item.segmentLabel}`"
              :value="item.segmentCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="Code" required>
          <el-input v-model="form.segmentCode" maxlength="10" placeholder="001 / 001001 / 0010010001" />
        </el-form-item>
        <el-form-item label="Name" required>
          <el-input v-model="form.segmentLabel" maxlength="100" placeholder="Raw material / Beef / Beef shank" />
        </el-form-item>
        <el-form-item label="Sort">
          <el-input-number v-model="form.sortOrder" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="Active">
          <el-switch v-model="form.isActive" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">Cancel</el-button>
        <el-button type="primary" :loading="saving" @click="saveSegment">Save</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-wrapper {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.page-subtitle {
  margin-top: 4px;
  color: #606266;
  font-size: 13px;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.empty-alert {
  margin-bottom: 14px;
}

.level-tabs {
  margin-bottom: 14px;
}
</style>
