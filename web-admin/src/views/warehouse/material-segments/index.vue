<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Delete as DeleteIcon, Edit, Plus, QuestionFilled, Refresh } from '@element-plus/icons-vue';
import { del, get, post, put } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { findLabelConflict, SEGMENT_LEVEL_DEFINITIONS, type SegmentLevel } from './materialSegmentRules';

interface ClassificationNode {
  id: number;
  segmentLabel: string;
  level: SegmentLevel;
  parentId: number | null;
  sortOrder: number;
  isActive: boolean;
  children?: ClassificationNode[];
}

interface ClassificationForm {
  id: number | null;
  level: SegmentLevel;
  segmentLabel: string;
  parentId: number | null;
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
const treeData = ref<ClassificationNode[]>([]);
const selectedLevel = ref<SegmentLevel>(1);
const showDeleted = ref(false);
const deletedRows = ref<ClassificationNode[]>([]);

const form = reactive<ClassificationForm>({
  id: null,
  level: 1,
  segmentLabel: '',
  parentId: null,
  sortOrder: 0,
  isActive: true,
});

const levelOptions = [
  { label: '一级分类', value: 1 },
  { label: '二级分类', value: 2 },
  { label: '三级分类', value: 3 },
] as const;

const flatRows = computed(() => {
  const rows: ClassificationNode[] = [];
  const walk = (nodes: ClassificationNode[]) => {
    nodes.forEach((node) => {
      rows.push(node);
      if (node.children?.length) walk(node.children);
    });
  };
  walk(treeData.value);
  return rows;
});

const tableRows = computed(() => flatRows.value.filter((row) => row.level === selectedLevel.value));
const parentOptions = computed(() => {
  const parentLevel = form.level - 1;
  return flatRows.value.filter((row) => row.level === parentLevel && row.isActive !== false);
});
const deletedRowsForLevel = computed(
  () => deletedRows.value.filter((row) => row.level === selectedLevel.value),
);

function levelLabel(level: SegmentLevel): string {
  return level === 1 ? '一级分类' : level === 2 ? '二级分类' : '三级分类';
}

function parentName(parentId: number | null): string {
  if (parentId == null) return '-';
  return flatRows.value.find((row) => row.id === parentId)?.segmentLabel || `分类 #${parentId}`;
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
    const response = await get<ClassificationNode[]>(`/${factoryId.value}/material-segments/tree`);
    treeData.value = Array.isArray(response.data) ? response.data : [];
  } catch (error) {
    showStickyError(errorMessage(error, '分类加载失败，请刷新重试。'));
  } finally {
    loading.value = false;
  }
}

async function loadDeleted() {
  if (!factoryId.value) return;
  try {
    const response = await get<ClassificationNode[]>(`/${factoryId.value}/material-segments/deleted`);
    deletedRows.value = Array.isArray(response.data) ? response.data : [];
  } catch (error) {
    showStickyError(errorMessage(error, '已删除分类加载失败。'));
  }
}

function resetForm(level: SegmentLevel = selectedLevel.value) {
  form.id = null;
  form.level = level;
  form.segmentLabel = '';
  form.parentId = null;
  form.sortOrder = 0;
  form.isActive = true;
}

function openCreate(level: SegmentLevel = selectedLevel.value) {
  resetForm(level);
  dialogVisible.value = true;
}

function openEdit(row: ClassificationNode) {
  form.id = row.id;
  form.level = row.level;
  form.segmentLabel = row.segmentLabel;
  form.parentId = row.parentId;
  form.sortOrder = row.sortOrder ?? 0;
  form.isActive = row.isActive !== false;
  dialogVisible.value = true;
}

function validateForm(): boolean {
  if (!form.segmentLabel.trim()) {
    showStickyError('请填写分类名称。');
    return false;
  }
  if (form.level > 1 && form.parentId == null) {
    showStickyError('请选择上级分类。');
    return false;
  }
  return true;
}

async function saveSegment() {
  if (!factoryId.value || !validateForm()) return;
  const conflict = findLabelConflict(flatRows.value, form.segmentLabel, form.id);
  if (conflict) {
    const samePosition = conflict.level === form.level && conflict.parentId === form.parentId;
    if (samePosition) {
      showStickyError(`当前上级下已存在分类“${conflict.segmentLabel}”，请直接使用。`);
      return;
    }
    try {
      await ElMessageBox.confirm(
        `“${conflict.segmentLabel}”已存在于${levelLabel(conflict.level)}。确认仍在当前位置创建同名分类吗？`,
        '检测到同名分类',
        { type: 'warning', confirmButtonText: '仍然创建', cancelButtonText: '返回调整' },
      );
    } catch {
      return;
    }
  }

  saving.value = true;
  const payload = {
    level: form.level,
    segmentLabel: form.segmentLabel.trim(),
    parentId: form.level === 1 ? null : form.parentId,
    sortOrder: Number(form.sortOrder || 0),
    isActive: form.isActive,
  };
  try {
    if (form.id) {
      await put(`/${factoryId.value}/material-segments/${form.id}`, payload);
      ElMessage.success('分类已更新。');
    } else {
      await post(`/${factoryId.value}/material-segments`, payload);
      ElMessage.success('分类已创建。');
    }
    dialogVisible.value = false;
    await loadTree();
  } catch (error) {
    await loadTree();
    showStickyError(errorMessage(error, '保存失败，请检查分类名称和上级分类。'));
  } finally {
    saving.value = false;
  }
}

async function toggleActive(row: ClassificationNode) {
  if (!factoryId.value) return;
  const nextActive = row.isActive === false;
  try {
    await put(`/${factoryId.value}/material-segments/${row.id}`, { isActive: nextActive });
    ElMessage.success(nextActive ? '已启用。' : '已停用。新建物料时不再提供此分类。');
    await loadTree();
  } catch (error) {
    showStickyError(errorMessage(error, nextActive ? '启用失败。' : '停用失败。'));
  }
}

async function deleteSegment(row: ClassificationNode) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(
      `确认删除分类“${row.segmentLabel}”？历史物料仍需保留归属时，请选择“停用”。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
    await del(`/${factoryId.value}/material-segments/${row.id}`);
    ElMessage.success('分类已删除，可在“显示已删除的分类”中恢复。');
    await refreshAll();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    showStickyError(errorMessage(error, '删除失败，请先处理下级分类或改为停用。'));
  }
}

async function restoreSegment(row: ClassificationNode) {
  if (!factoryId.value) return;
  try {
    await post(`/${factoryId.value}/material-segments/${row.id}/restore`, {});
    ElMessage.success(`分类“${row.segmentLabel}”已恢复。`);
    await refreshAll();
  } catch (error) {
    showStickyError(errorMessage(error, '恢复失败。'));
  }
}

async function refreshAll() {
  await loadTree();
  if (showDeleted.value) await loadDeleted();
}

watch(
  () => form.level,
  () => { if (!form.id) form.parentId = null; },
);
watch(showDeleted, (visible) => { if (visible) void loadDeleted(); });
onMounted(loadTree);
</script>

<template>
  <div class="page-wrapper">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div>
            <div class="page-title-row">
              <div class="page-title">物料分类字典</div>
              <el-popover placement="bottom-start" :width="420" trigger="click">
                <template #reference>
                  <el-button class="definition-help" link :icon="QuestionFilled" aria-label="查看分类层级说明" />
                </template>
                <div class="definition-list">
                  <div v-for="item in SEGMENT_LEVEL_DEFINITIONS" :key="item.level" class="definition-item">
                    <strong>{{ item.title }}</strong>
                    <div>{{ item.description }}</div>
                    <div class="definition-example">例如：{{ item.example }}</div>
                  </div>
                </div>
              </el-popover>
            </div>
            <div class="page-subtitle">这里只维护分类名称和上下级关系；料号在新建原料时单独维护</div>
          </div>
          <div class="header-actions">
            <el-button :icon="Refresh" :loading="loading" @click="loadTree">刷新</el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate()">新增分类</el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="treeData.length === 0 && !loading"
        title="本工厂暂未配置详细物料分类"
        type="info"
        :closable="false"
        show-icon
        class="empty-alert"
      >
        <div>不配置也能新建原料类型，系统会按基本类型建议简短料号。</div>
        <div v-if="canWrite" class="empty-alert__hint">需要更细的归类和筛选时，再新增分类。</div>
      </el-alert>

      <el-segmented v-model="selectedLevel" :options="levelOptions" class="level-tabs" />

      <el-table v-loading="loading" :data="tableRows" stripe row-key="id" empty-text="暂无数据">
        <el-table-column prop="segmentLabel" label="分类名称" min-width="220" />
        <el-table-column label="上级分类" min-width="180">
          <template #default="{ row }">{{ parentName(row.parentId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.isActive === false ? 'info' : 'success'" size="small">
              {{ row.isActive === false ? '停用' : '启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" @click="toggleActive(row)">
              {{ row.isActive === false ? '启用' : '停用' }}
            </el-button>
            <el-button link type="danger" :icon="DeleteIcon" @click="deleteSegment(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="deleted-section">
        <el-switch v-model="showDeleted" active-text="显示已删除的分类" />
        <span class="deleted-hint">删除记录可恢复；日常不再使用时优先选择“停用”。</span>
      </div>

      <el-table
        v-if="showDeleted"
        :data="deletedRowsForLevel"
        stripe
        row-key="id"
        empty-text="该层级没有已删除的分类"
        class="deleted-table"
      >
        <el-table-column prop="segmentLabel" label="分类名称" min-width="220" />
        <el-table-column label="状态" width="100"><template #default><el-tag type="danger" size="small">已删除</el-tag></template></el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="120" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="restoreSegment(row)">恢复</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑分类' : '新增分类'" width="520px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item label="层级" required>
          <el-select v-model="form.level" :disabled="!!form.id" style="width: 100%">
            <el-option label="一级分类" :value="1" />
            <el-option label="二级分类" :value="2" />
            <el-option label="三级分类" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.level > 1" label="上级分类" required>
          <el-select v-model="form.parentId" filterable :disabled="!!form.id" style="width: 100%">
            <el-option v-for="item in parentOptions" :key="item.id" :label="item.segmentLabel" :value="item.id" />
          </el-select>
          <div v-if="form.id" class="field-hint">需要调整上级时，请新建分类并停用旧分类，以免改变历史归属。</div>
        </el-form-item>
        <el-form-item label="分类名称" required>
          <el-input v-model="form.segmentLabel" maxlength="100" placeholder="如：肉类 / 牛肉类 / 牛腱" />
        </el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" style="width: 100%" /></el-form-item>
        <el-form-item label="是否启用"><el-switch v-model="form.isActive" active-text="启用" inactive-text="停用" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveSegment">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-wrapper { padding: 20px; }
.card-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.page-title { font-size: 18px; font-weight: 600; color: #303133; }
.page-title-row { display: flex; align-items: center; gap: 4px; }
.definition-help { color: #909399; }
.definition-list { display: grid; gap: 12px; line-height: 1.55; color: #606266; }
.definition-item strong { color: #303133; }
.definition-example, .field-hint, .deleted-hint { color: #909399; font-size: 12px; }
.page-subtitle { margin-top: 4px; color: #606266; font-size: 13px; }
.header-actions { display: flex; gap: 8px; }
.empty-alert { margin-bottom: 14px; }
.empty-alert__hint { margin-top: 6px; color: var(--el-text-color-secondary); font-size: 13px; }
.level-tabs { margin-bottom: 14px; }
.deleted-section { margin-top: 16px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.deleted-table { margin-top: 12px; }
</style>
