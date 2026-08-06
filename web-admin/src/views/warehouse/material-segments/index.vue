<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Delete as DeleteIcon, Edit, Plus, QuestionFilled, Refresh } from '@element-plus/icons-vue';
import { del, get, post, put } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  findLabelConflict,
  SEGMENT_LEVEL_DEFINITIONS,
  type SegmentLevel,
} from './materialSegmentRules';

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
/**
 * 系统编码由**服务端**分配。
 *
 * ⛔ 2026-08-06 事故: 原来这里用 `nextSegmentCode(flatRows, ...)` 在前端对**活着的**
 * 兄弟节点取 max+1。而分类是软删除、编码软删后仍被占用(唯一约束
 * `uk_mcs_factory_segment` 不排除软删行, 且有外键指向该编码), 于是把一整层删干净后
 * 分配出来的编码正是被软删行占着的那个 → INSERT 撞约束 → 用户看到「已存在同名分类」。
 * 前端看不到软删行, 这件事只能服务端做。
 *
 * `nextSegmentCode` 纯函数保留(仍有单测覆盖编码形状), 但**不再用于真实分配**。
 */
const serverNextCode = ref('');
const serverNextCodeError = ref('');
const systemGeneratedCode = computed(() => (form.id ? form.segmentCode : serverNextCode.value));

async function refreshServerNextCode(): Promise<void> {
  serverNextCode.value = '';
  serverNextCodeError.value = '';
  if (form.id || !factoryId.value) return;
  if (form.level > 1 && !form.parentCode) return;
  try {
    // ⚠️ get(url, config) 的第二个参数是 axios config —— query 必须放在 params 下。
    const response = await get<{ code: string }>(
      `/${factoryId.value}/material-segments/next-code`,
      { params: { level: form.level, parentCode: form.level === 1 ? undefined : form.parentCode } },
    );
    if (response.success && response.data?.code) {
      serverNextCode.value = response.data.code;
    } else {
      serverNextCodeError.value = response.message || '取系统编码失败，请重试';
    }
  } catch (error) {
    serverNextCodeError.value = error instanceof Error ? error.message : '取系统编码失败，请重试';
  }
}

watch(
  () => [form.id, form.level, form.parentCode] as const,
  () => { void refreshServerNextCode(); },
  { immediate: true },
);

watch(
  () => form.level,
  () => {
    if (!form.id) form.parentCode = null;
  },
);

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
  const code = systemGeneratedCode.value;
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
  const conflict = findLabelConflict(flatRows.value, form.segmentLabel, form.id);
  if (conflict) {
    const samePosition = conflict.level === form.level && conflict.parentCode === form.parentCode;
    if (samePosition) {
      showStickyError(`“${conflict.segmentLabel}”已存在于当前层级（${conflict.segmentCode}），请直接使用已有分类。`);
      return;
    }
    try {
      await ElMessageBox.confirm(
        `“${conflict.segmentLabel}”已属于 L${conflict.level} ${conflict.level === 1 ? '大类' : conflict.level === 2 ? '中类' : '小类'}（${conflict.segmentCode}）。请根据层级定义确认是否仍要在当前层级创建。`,
        '检测到同名分类',
        { type: 'warning', confirmButtonText: '仍按当前层级创建', cancelButtonText: '返回调整' },
      );
    } catch {
      return;
    }
  }
  saving.value = true;
  const payload = {
    level: form.level,
    segmentCode: systemGeneratedCode.value,
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
    await loadTree();
    showStickyError(errorMessage(error, '保存失败，系统已重新检查编码和分类名称，请重试。'));
  } finally {
    saving.value = false;
  }
}

async function deleteSegment(row: SegmentNode) {
  if (!factoryId.value) return;
  try {
    // 删除与停用的区别必须在这里说清楚 —— 用户此前只有「删除」一个按钮,
    // 而删掉的分类界面上看不见也回不来, 编码却继续被占着。
    await ElMessageBox.confirm(
      `确认删除编码分类「${row.segmentCode} ${row.segmentLabel}」？\n\n`
      + '删除后：编码 ' + row.segmentCode + ' 不会被回收（历史物料的编码里含它），'
      + '该分类也不再出现在新建物料的选项里。\n'
      + '如果只是不想再用它建新物料，建议改用「停用」——效果相同，但归属仍可追溯。',
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '仍然删除',
        cancelButtonText: '取消',
        distinguishCancelAndClose: true,
      },
    );
    await del(`/${factoryId.value}/material-segments/${row.id}`);
    ElMessage.success('编码分类已删除。可在「显示已删除」里恢复。');
    await refreshAll();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    showStickyError(errorMessage(error, '删除失败，请先处理下级分类或在用物料，再重试。'));
  }
}

/** 一键停用/启用 —— 把「停用」提到和「删除」同级, 而不是藏在编辑弹窗里。 */
async function toggleActive(row: SegmentNode) {
  if (!factoryId.value) return;
  const nextActive = row.isActive === false;
  try {
    await put(`/${factoryId.value}/material-segments/${row.id}`, { isActive: nextActive });
    ElMessage.success(nextActive ? '已启用。' : '已停用。停用后不再出现在新建物料的选项里，归属仍可追溯。');
    await refreshAll();
  } catch (error) {
    showStickyError(errorMessage(error, nextActive ? '启用失败' : '停用失败'));
  }
}

const showDeleted = ref(false);
const deletedRows = ref<SegmentNode[]>([]);

async function loadDeleted() {
  if (!factoryId.value) return;
  try {
    const res = await get<SegmentNode[]>(`/${factoryId.value}/material-segments/deleted`);
    deletedRows.value = Array.isArray(res.data) ? res.data : [];
  } catch (error) {
    showStickyError(errorMessage(error, '已删除分类加载失败'));
  }
}

async function restoreSegment(row: SegmentNode) {
  if (!factoryId.value) return;
  try {
    await post(`/${factoryId.value}/material-segments/${row.id}/restore`, {});
    ElMessage.success(`「${row.segmentCode} ${row.segmentLabel}」已恢复，编码不变。`);
    await refreshAll();
  } catch (error) {
    showStickyError(errorMessage(error, '恢复失败'));
  }
}

async function refreshAll() {
  await loadTree();
  if (showDeleted.value) await loadDeleted();
}

watch(showDeleted, (on) => { if (on) void loadDeleted(); });

/** 当前层级下已删除的行 —— 与主表同一个层级过滤, 免得看串。 */
const deletedRowsForLevel = computed(
  () => deletedRows.value.filter((row) => row.level === selectedLevel.value),
);

onMounted(loadTree);
</script>

<template>
  <div class="page-wrapper">
    <el-card shadow="never">
      <!-- A-FP-3: 全页面汉化，低文化素质仓管/配置员可读 -->
      <template #header>
        <div class="card-header">
          <div>
            <div class="page-title-row">
              <div class="page-title">16位物料编码字典</div>
              <el-popover placement="bottom-start" :width="420" trigger="click">
                <template #reference>
                  <el-button class="definition-help" link :icon="QuestionFilled" aria-label="查看分类层级定义" />
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
            <div class="page-subtitle">L1 大类（3位） · L2 中类（6位） · L3 小类（10位）；编码由系统自动生成</div>
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
        <el-table-column v-if="canWrite" label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <!-- 停用与删除同级: 绝大多数「不想再用」的诉求应该走停用, 而不是删除 -->
            <el-button link type="warning" @click="toggleActive(row)">
              {{ row.isActive === false ? '启用' : '停用' }}
            </el-button>
            <el-button link type="danger" :icon="DeleteIcon" @click="deleteSegment(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!--
        ⛔ 2026-08-06: 删除是软删除, 但此前界面上**看不到也回不来** —— 于是误删/重组
        之后唯一的出路是新建, 而新建又会撞上被删行仍占着的编码(唯一约束含软删)。
        客户 08-04 删掉的 226 条 L3 其实原封不动躺在库里, 恢复比重建正确得多:
        编码不变 → 历史物料的 16 位码仍然指得回它的分类。
      -->
      <div class="deleted-section">
        <el-switch v-model="showDeleted" active-text="显示已删除的分类" />
        <span class="deleted-hint">
          删除是软删除：行还在，编码也仍被它占着（不会回收）。可随时恢复。
        </span>
      </div>

      <el-table
        v-if="showDeleted"
        :data="deletedRowsForLevel"
        stripe
        row-key="id"
        empty-text="该层级没有已删除的分类"
        class="deleted-table"
      >
        <el-table-column prop="segmentCode" label="编码" width="150" />
        <el-table-column prop="segmentLabel" label="名称" min-width="180" />
        <el-table-column prop="parentCode" label="上级编码" width="140">
          <template #default="{ row }">{{ row.parentCode || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default>
            <el-tag type="danger" size="small">已删除</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="restoreSegment(row)">恢复</el-button>
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
          <el-select v-model="form.parentCode" filterable :disabled="!!form.id" style="width: 100%">
            <el-option
              v-for="item in parentOptions"
              :key="item.segmentCode"
              :label="`${item.segmentCode} ${item.segmentLabel}`"
              :value="item.segmentCode"
            />
          </el-select>
          <!--
            编码把父级前缀焊在里面了(L3 码 = L2 码 + 4 位), 换父级就必须换编码,
            而编码已经被历史物料的 16 位码引用, 不能换 —— 所以「移动分类」做不到。
            以前这个下拉在编辑态是可选的, 选了也只会得到一句「父节点层级、状态或编码前缀无效」。
            与其让人白试一次, 不如直接说清楚。
          -->
          <div v-if="form.id" class="field-hint">
            分类建好后不能换上级：编码里含上级前缀（{{ form.segmentCode }}），而历史物料的编码引用了它。
            需要重新归类时，请在此层级新建一个分类，旧的改为「停用」。
          </div>
        </el-form-item>
        <el-form-item label="系统编码" required>
          <el-input :model-value="systemGeneratedCode" disabled placeholder="选择层级和上级后自动生成" />
          <div v-if="!serverNextCodeError" class="field-hint">
            由服务端分配，已跳过被历史（含已删除）分类占用的编码，用户无需填写或记忆。
          </div>
          <div v-else class="field-hint field-hint--error">{{ serverNextCodeError }}</div>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.segmentLabel" maxlength="100" placeholder="如：牛肉类 / 牛腱 / 卤牛腱" />
          <!-- 说清楚改名是安全的, 否则用户会用「删了重建」来达到改名的目的 -->
          <div v-if="form.id" class="field-hint">
            改名只改显示名称：编码不变，历史物料的归属也不受影响。想换个叫法直接改这里即可，不必删了重建。
          </div>
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

.page-title-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.definition-help {
  color: #909399;
}

.definition-list {
  display: grid;
  gap: 12px;
  line-height: 1.55;
  color: #606266;
}

.definition-item strong {
  color: #303133;
}

.definition-example,
.field-hint {
  color: #909399;
  font-size: 12px;
}

.field-hint--error {
  color: #f56c6c;
}

.deleted-section {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.deleted-hint {
  color: #909399;
  font-size: 12px;
}

.deleted-table {
  margin-top: 12px;
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
