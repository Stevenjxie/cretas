<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Warning } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import WorkProcessAIChatPanel from './WorkProcessAIChatPanel.vue';
import {
  getWorkProcesses, createWorkProcess, updateWorkProcess,
  deleteWorkProcess, toggleWorkProcessStatus, getWorkProcessDuplicates,
  type WorkProcessItem, type WorkProcessDuplicateGroup,
  type WorkProcessOutputMaterialKind
} from '@/api/processProduction';
import {
  WORK_PROCESS_OUTPUT_KIND_OPTIONS,
  normalizeOutputMaterialKind,
  usesSemiFinishedCode,
} from './workProcessOutputKind';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

const loading = ref(false);
const tableData = ref<WorkProcessItem[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const searchKeyword = ref('');

// Dialog
const dialogVisible = ref(false);
const dialogTitle = ref('新增工序');
const isEditing = ref(false);
const formRef = ref();
const submitting = ref(false);

const CATEGORIES = [
  '前处理', '加工', '熟制', '注射', '包装', '灭菌', '质检', '存储', '配送', '其他'
];

type WorkProcessForm = Partial<WorkProcessItem>;

const formData = reactive<WorkProcessForm>({
  id: '',
  processName: '',
  processCategory: '',
  unit: 'kg',
  estimatedMinutes: null,
  sortOrder: 0,
  standardYieldMin: null,
  standardYieldMax: null,
  needsInput: true,
  outputUnit: '',
  defaultOutputMaterialKind: normalizeOutputMaterialKind(undefined),
  semiFinishedOutputCode: null,
  standardHourlyRate: null
});

const showSemiFinishedCode = computed(() => usesSemiFinishedCode(
  normalizeOutputMaterialKind(formData.defaultOutputMaterialKind),
));

// P0-3: 百分比 ↔ 小数转换 (表单按百分比录入, payload 存小数 0.0001..99.9999)
const minPct = computed<number | null>({
  get: () => formData.standardYieldMin != null ? +(formData.standardYieldMin * 100).toFixed(2) : null,
  set: (v) => { formData.standardYieldMin = v != null ? +(v / 100).toFixed(4) : null; }
});
const maxPct = computed<number | null>({
  get: () => formData.standardYieldMax != null ? +(formData.standardYieldMax * 100).toFixed(2) : null,
  set: (v) => { formData.standardYieldMax = v != null ? +(v / 100).toFixed(4) : null; }
});

const formRules = {
  processName: [
    { required: true, message: '请输入工序名称', trigger: 'blur' },
    { max: 100, message: '不能超过100个字符', trigger: 'blur' }
  ],
  unit: [
    { required: true, message: '请输入单位', trigger: 'blur' }
  ],
  standardYieldMax: [
    {
      validator: (_r: unknown, _v: unknown, cb: (e?: Error) => void) => {
        if (formData.standardYieldMin != null && formData.standardYieldMax != null
          && formData.standardYieldMax <= formData.standardYieldMin) {
          cb(new Error('上限须大于下限'));
        } else {
          cb();
        }
      },
      trigger: 'blur'
    }
  ],
  semiFinishedOutputCode: [
    {
      validator: (_r: unknown, value: string | null, cb: (e?: Error) => void) => {
        if (value && value.length > 50) {
          cb(new Error('不能超过50个字符'));
          return;
        }
        cb();
      },
      trigger: 'blur'
    }
  ]
};

// C5: duplicate detection
const dupGroups = ref<WorkProcessDuplicateGroup[]>([]);
const dupLoading = ref(false);
const dupPanelVisible = ref(false);

async function handleDetectDuplicates() {
  if (!factoryId.value) return;
  dupLoading.value = true;
  dupPanelVisible.value = true;
  try {
    const response = await getWorkProcessDuplicates(factoryId.value);
    if (response.success && response.data) {
      dupGroups.value = response.data;
      if (dupGroups.value.length === 0) {
        ElMessage.success('未检测到重复工序');
        dupPanelVisible.value = false;
      }
    }
  } catch (e) {
    handleCatchError(e, '检测重复工序失败');
    dupPanelVisible.value = false;
  } finally {
    dupLoading.value = false;
  }
}

async function handleDupToggle(member: WorkProcessItem) {
  if (!factoryId.value) return;
  const action = member.isActive ? '停用' : '启用';
  try {
    await ElMessageBox.confirm(
      `确定${action}重复工序「${member.processName}」（ID: ${member.id}）？`,
      `${action}确认`,
      { type: 'warning' }
    );
    await toggleWorkProcessStatus(factoryId.value, member.id);
    ElMessage.success(`已${action}`);
    // Refresh both main list and dup panel
    await loadData();
    await handleDetectDuplicates();
  } catch (e) {
    if (e !== 'cancel') handleCatchError(e, `${action}失败`);
  }
}

async function handleAiApplied(): Promise<void> {
  await loadData();
  if (dupPanelVisible.value) {
    await handleDetectDuplicates();
  }
}

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await getWorkProcesses(factoryId.value, {
      page: pagination.value.page,
      size: pagination.value.size,
      sortBy: 'sortOrder',
      sortDirection: 'ASC'
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载工序数据失败');
  } finally {
    loading.value = false;
  }
}

function handleAdd() {
  dialogTitle.value = '新增工序';
  isEditing.value = false;
  Object.assign(formData, {
    id: '', processName: '', processCategory: '',
    unit: 'kg', estimatedMinutes: null, sortOrder: 0,
    standardYieldMin: null, standardYieldMax: null, needsInput: true, outputUnit: '',
    defaultOutputMaterialKind: normalizeOutputMaterialKind(undefined),
    semiFinishedOutputCode: null,
    standardHourlyRate: null
  });
  dialogVisible.value = true;
}

function handleEdit(row: WorkProcessItem) {
  dialogTitle.value = '编辑工序';
  isEditing.value = true;
  const outputKind = normalizeOutputMaterialKind(row.defaultOutputMaterialKind);
  Object.assign(formData, {
    ...row,
    defaultOutputMaterialKind: outputKind,
    semiFinishedOutputCode: usesSemiFinishedCode(outputKind) ? semiOutputCodeOf(row) : null,
  });
  dialogVisible.value = true;
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid || !factoryId.value) return;

  submitting.value = true;
  try {
    const outputKind = normalizeOutputMaterialKind(formData.defaultOutputMaterialKind);
    const semiCode = usesSemiFinishedCode(outputKind)
      ? normalizeSemiOutputCode(formData.semiFinishedOutputCode)
      : null;
    const payload = {
      ...formData,
      defaultOutputMaterialKind: outputKind,
      semiFinishedOutputCode: semiCode,
    };
    const outputKindLabel = usesSemiFinishedCode(outputKind) ? '半成品' : '成品';
    if (isEditing.value && formData.id) {
      await updateWorkProcess(factoryId.value, formData.id, payload);
      ElMessage.success(`工序已更新，默认产出类型：${outputKindLabel}`);
    } else {
      await createWorkProcess(factoryId.value, payload);
      ElMessage.success(`工序已创建，默认产出类型：${outputKindLabel}`);
    }
    dialogVisible.value = false;
    loadData();
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[操作失败]', e);
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(row: WorkProcessItem) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(`确定删除工序「${row.processName}」？`, '删除确认', {
      type: 'warning'
    });
    await deleteWorkProcess(factoryId.value, row.id);
    ElMessage.success('已删除');
    loadData();
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    if (e !== 'cancel') console.error('[失败]', e);
  }
}

async function handleToggle(row: WorkProcessItem) {
  if (!factoryId.value) return;
  try {
    await toggleWorkProcessStatus(factoryId.value, row.id);
    ElMessage.success(row.isActive ? '已禁用' : '已启用');
    loadData();
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[操作失败]', e);
  }
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function semiOutputCodeOf(row: WorkProcessItem): string | null {
  return normalizeSemiOutputCode(row.semiFinishedOutputCode);
}

function outputKindOf(row: WorkProcessItem): WorkProcessOutputMaterialKind {
  return normalizeOutputMaterialKind(row.defaultOutputMaterialKind);
}

function handleOutputKindChange(kind: WorkProcessOutputMaterialKind): void {
  const normalizedKind = normalizeOutputMaterialKind(kind);
  formData.defaultOutputMaterialKind = normalizedKind;
  if (!usesSemiFinishedCode(normalizedKind)) {
    formData.semiFinishedOutputCode = null;
  }
}

function normalizeSemiOutputCode(value?: string | null): string | null {
  const trimmed = value?.trim();
  return trimmed || null;
}

</script>

<template>
  <div class="page-container">
    <el-card>
      <div class="toolbar">
        <div class="toolbar-left">
          <h2 style="margin: 0">工序管理</h2>
          <el-tag type="info">{{ factoryId }}</el-tag>
        </div>
        <div class="toolbar-right">
          <el-button :icon="Refresh" @click="loadData" />
          <!-- C5: duplicate detection -->
          <el-button :icon="Warning" :loading="dupLoading" @click="handleDetectDuplicates">
            检测重复工序
          </el-button>
          <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleAdd">
            新增工序
          </el-button>
        </div>
      </div>
    </el-card>

    <WorkProcessAIChatPanel
      v-if="canWrite && factoryId"
      :factory-id="factoryId"
      @applied="handleAiApplied"
    />

    <!-- C5: Duplicate clusters panel -->
    <el-card v-if="dupPanelVisible" style="margin-top: 16px; border: 1px solid #faad14">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span style="font-weight: 600; color: #e6a23c">
            <el-icon style="vertical-align: middle; margin-right: 4px"><Warning /></el-icon>
            检测到 {{ dupGroups.length }} 组重复工序（同名称+类别+单位）
          </span>
          <el-button text size="small" @click="dupPanelVisible = false">关闭</el-button>
        </div>
      </template>

      <div v-for="(group, gi) in dupGroups" :key="gi" class="dup-group">
        <div class="dup-group-title">
          重复组 #{{ gi + 1 }}：
          <el-tag size="small">{{ group.processName }}</el-tag>
          <el-tag v-if="group.processCategory" size="small" type="info" style="margin-left: 4px">
            {{ group.processCategory }}
          </el-tag>
          <el-tag size="small" type="warning" style="margin-left: 4px">{{ group.unit }}</el-tag>
          <span class="text-muted" style="margin-left: 8px; font-size: 12px">
            {{ group.members.length }} 条记录 — 请保留 1 条，停用其余
          </span>
        </div>
        <el-table :data="group.members" size="small" style="margin-top: 8px">
          <el-table-column prop="id" label="ID" width="220" />
          <el-table-column prop="processName" label="名称" min-width="100" />
          <el-table-column prop="processCategory" label="类别" width="90" />
          <el-table-column prop="unit" label="单位" width="70" />
          <el-table-column prop="sortOrder" label="排序" width="60" />
          <el-table-column prop="isActive" label="状态" width="70">
            <template #default="{ row }">
              <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
                {{ row.isActive ? '启用' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" v-if="canWrite">
            <template #default="{ row }">
              <el-button type="warning" text size="small" @click="handleDupToggle(row)">
                {{ row.isActive ? '停用' : '启用' }}
              </el-button>
              <el-button type="primary" text size="small" @click="handleEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <el-card style="margin-top: 16px">
      <el-table :data="tableData" v-loading="loading" stripe>
        <el-table-column prop="processName" label="工序名称" min-width="120" />
        <el-table-column prop="processCategory" label="类别" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.processCategory" size="small">{{ row.processCategory }}</el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column label="默认产出类型" width="130">
          <template #default="{ row }">
            <el-tag v-if="usesSemiFinishedCode(outputKindOf(row))" type="success" size="small">半成品</el-tag>
            <el-tag v-else size="small">成品</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标准出成率" width="130">
          <template #default="{ row }">
            <span v-if="row.standardYieldMin != null && row.standardYieldMax != null">
              {{ (row.standardYieldMin * 100).toFixed(0) }}%~{{ (row.standardYieldMax * 100).toFixed(0) }}%
            </span>
            <el-tag v-else type="warning" size="small">未配置</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="estimatedMinutes" label="预估工时(分钟)" width="130">
          <template #default="{ row }">
            {{ row.estimatedMinutes ?? '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="70" />
        <el-table-column prop="isActive" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
              {{ row.isActive ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right" v-if="canWrite">
          <template #default="{ row }">
            <el-button type="primary" text size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button type="warning" text size="small" @click="handleToggle(row)">
              {{ row.isActive ? '禁用' : '启用' }}
            </el-button>
            <el-button type="danger" text size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="pagination.total > 0"
        style="margin-top: 16px; justify-content: flex-end"
        :current-page="pagination.page"
        :page-size="pagination.size"
        :total="pagination.total"
        layout="total, prev, pager, next"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- Form Dialog -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px">
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="130px">
        <el-form-item label="工序名称" prop="processName">
          <el-input v-model="formData.processName" placeholder="如：拆箱、挂晒、卤制" />
        </el-form-item>
        <el-form-item label="工序类别" prop="processCategory">
          <el-select v-model="formData.processCategory" placeholder="选择类别" clearable style="width: 100%">
            <el-option v-for="cat in CATEGORIES" :key="cat" :label="cat" :value="cat" />
          </el-select>
        </el-form-item>
        <el-form-item label="计量单位" prop="unit">
          <el-input v-model="formData.unit" placeholder="如：箱、车、框、kg" />
        </el-form-item>
        <el-form-item label="预估工时">
          <el-input-number v-model="formData.estimatedMinutes" :min="1" placeholder="分钟" style="width: 100%" />
        </el-form-item>
        <el-form-item label="标准出成率下限" prop="standardYieldMin">
          <!-- 防呆 Rule 1: :min=0.01 (映射后端最小有效值 0.0001), 禁止输 0 → 后端 @DecimalMin(0.0001) 会拒.
               想"不校验"请清空 (留空=null), 这是唯一的低于阈值路径。 -->
          <el-input-number v-model="minPct" :min="0.01" :max="999.99" :step="5" :precision="2"
            placeholder="如 30 (留空=不校验)" style="width: 100%" />
          <span class="form-hint">%（焯水约 30~60，滚揉保水 100~135；装盒/检验类留空。输 0 无效，不校验请清空）</span>
        </el-form-item>
        <el-form-item label="标准出成率上限" prop="standardYieldMax">
          <!-- 防呆 Rule 1: 同上, :min=0.01 禁 0; 清空=不校验 -->
          <el-input-number v-model="maxPct" :min="0.01" :max="999.99" :step="5" :precision="2"
            placeholder="如 60 (留空=不校验)" style="width: 100%" />
          <span class="form-hint">%（超收预检以此为基准 × 投入量 × 1.3 容差）</span>
        </el-form-item>
        <el-form-item label="需录投入量">
          <el-switch v-model="formData.needsInput" />
          <span class="form-hint">纯包装/检验类可关闭</span>
        </el-form-item>
        <el-form-item label="产出单位">
          <el-input v-model="formData.outputUnit" placeholder="与投入单位不同时填，如 盒/份；留空则同投入单位" />
        </el-form-item>
        <el-form-item label="默认产出类型" prop="defaultOutputMaterialKind">
          <el-select
            v-model="formData.defaultOutputMaterialKind"
            style="width: 100%"
            @change="handleOutputKindChange"
          >
            <el-option
              v-for="option in WORK_PROCESS_OUTPUT_KIND_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <span class="form-hint">该工序加入产品 Workflow 后，系统会据此生成半成品或成品 Cell；默认为半成品，报工人员不可修改。</span>
        </el-form-item>
        <el-form-item v-if="showSemiFinishedCode" label="半成品产出编码" prop="semiFinishedOutputCode">
          <el-input
            v-model="formData.semiFinishedOutputCode"
            maxlength="50"
            show-word-limit
            placeholder="如 LU-ZHI-ZHU-TI 或 熟制猪蹄-WIP"
          />
          <span class="form-hint">可选；建议按产品/工序命名，供后续半成品识别与领用</span>
        </el-form-item>
        <el-form-item label="标准时薪(元/小时)" prop="standardHourlyRate">
          <el-input-number v-model="formData.standardHourlyRate" :min="0" :step="1" :precision="2"
            placeholder="如 25.00；留空表示未配置(不计算人工成本)" style="width: 100%" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="formData.sortOrder" :min="0" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-container { padding: 20px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; }
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.toolbar-right { display: flex; gap: 8px; }
.text-muted { color: #909399; }
.form-hint { font-size: 12px; color: #909399; margin-left: 4px; }
/* C5: duplicate panel */
.dup-group { margin-bottom: 20px; padding-bottom: 16px; border-bottom: 1px solid #f0f0f0; }
.dup-group:last-child { border-bottom: none; margin-bottom: 0; }
.dup-group-title { font-size: 13px; font-weight: 500; }
</style>
