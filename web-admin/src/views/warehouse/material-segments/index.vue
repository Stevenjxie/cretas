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

// A-FP-3: 汉化标签（与编码值/数字无关，仅改显示文案）
const levelOptions = [
  { label: 'L1 大类（3位）', value: 1 },
  { label: 'L2 中类（6位）', value: 2 },
  { label: 'L3 小类（10位）', value: 3 },
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
    showStickyError('名称不能为空，请填写分类名称。');
    return false;
  }
  if (form.level === 1 && !/^\d{3}$/.test(code)) {
    showStickyError('L1大类编码必须为3位数字，例如 001。');
    return false;
  }
  if (form.level === 2 && !/^\d{6}$/.test(code)) {
    showStickyError('L2中类编码必须为6位累积数字，例如 001001。');
    return false;
  }
  if (form.level === 3 && !/^\d{10}$/.test(code)) {
    showStickyError('L3小类编码必须为10位累积数字，例如 0010010001。');
    return false;
  }
  if (form.level > 1 && !form.parentCode) {
    showStickyError('请选择上级分类。');
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
      ElMessage.success('编码分类已更新。');
    } else {
      await post(`/${factoryId.value}/material-segments`, payload);
      ElMessage.success('编码分类已创建。');
    }
    dialogVisible.value = false;
    await loadTree();
  } catch (error) {
    showStickyError(errorMessage(error, '保存失败，请检查编码是否重复，然后重试。'));
  } finally {
    saving.value = false;
  }
}

async function deleteSegment(row: SegmentNode) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(
      `确认删除编码分类「${row.segmentCode} ${row.segmentLabel}」？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
    await del(`/${factoryId.value}/material-segments/${row.id}`);
    ElMessage.success('编码分类已删除。');
    await loadTree();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    showStickyError(errorMessage(error, '删除失败，请先删除下级分类，再重试。'));
  }
}

onMounted(loadTree);
</script>

<template>
  <div class="page-wrapper">
    <el-card shadow="never">
      <!-- A-FP-3: 全页面汉化，低文化素质仓管/配置员可读 -->
      <template #header>
        <div class="card-header">
          <div>
            <div class="page-title">16位物料编码字典</div>
            <div class="page-subtitle">L1 大类（3位） · L2 中类（6位） · L3 小类（10位）</div>
          </div>
          <div class="header-actions">
            <el-button :icon="Refresh" :loading="loading" @click="loadTree">刷新</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate()">新增分类</el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="treeData.length === 0 && !loading"
        title="该工厂暂未配置物料编码字典，点击「新增分类」开始配置。"
        type="warning"
        :closable="false"
        show-icon
        class="empty-alert"
      />

      <el-segmented v-model="selectedLevel" :options="levelOptions" class="level-tabs" />

      <el-table v-loading="loading" :data="tableRows" stripe row-key="id" empty-text="暂无数据">
        <el-table-column prop="segmentCode" label="编码" width="150" />
        <el-table-column prop="segmentLabel" label="名称" min-width="180" />
        <el-table-column prop="parentCode" label="上级编码" width="140">
          <template #default="{ row }">{{ row.parentCode || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.isActive === false ? 'info' : 'success'" size="small">
              {{ row.isActive === false ? '停用' : '启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" :icon="DeleteIcon" @click="deleteSegment(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑编码分类' : '新增编码分类'" width="520px" destroy-on-close>
      <el-form :model="form" label-width="112px">
        <el-form-item label="层级" required>
          <el-select v-model="form.level" :disabled="!!form.id" style="width: 100%">
            <el-option label="L1 大类（3位，如 001）" :value="1" />
            <el-option label="L2 中类（6位，如 001001）" :value="2" />
            <el-option label="L3 小类（10位，如 0010010001）" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.level > 1" label="上级分类" required>
          <el-select v-model="form.parentCode" filterable style="width: 100%">
            <el-option
              v-for="item in parentOptions"
              :key="item.segmentCode"
              :label="`${item.segmentCode} ${item.segmentLabel}`"
              :value="item.segmentCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="编码" required>
          <el-input v-model="form.segmentCode" maxlength="10" placeholder="如：001 / 001001 / 0010010001" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.segmentLabel" maxlength="100" placeholder="如：牛肉类 / 牛腱 / 卤牛腱" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="是否启用">
          <el-switch v-model="form.isActive" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveSegment">保存</el-button>
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
