<script setup lang="ts">
import { ref, computed, onMounted, reactive, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Warning } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import WorkProcessAIChatPanel from './WorkProcessAIChatPanel.vue';
import UnitSelect from '@/components/common/UnitSelect.vue';
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
const allData = ref<WorkProcessItem[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const searchKeyword = ref('');

// 筛选 (实时按当前数据里实际存在的 类别/单位/默认产出类型 生成选项, 非固定枚举)
const filterCategory = ref('');
const filterUnit = ref('');
const filterOutputKind = ref<'' | 'SEMI' | 'FINISHED'>('');

const categoryOptions = computed(() => {
  const set = new Set(allData.value.map(r => r.processCategory).filter((v): v is string => !!v));
  return Array.from(set).sort();
});
const unitOptions = computed(() => {
  const set = new Set(allData.value.map(r => r.unit).filter((v): v is string => !!v));
  return Array.from(set).sort();
});
// 产出类型本质二元 (半成品/成品), 但仍按"当前数据里实际存在哪种"决定要不要显示该选项
const outputKindOptions = computed(() => {
  const opts: Array<{ value: 'SEMI' | 'FINISHED'; label: string }> = [];
  const hasSemi = allData.value.some(r => usesSemiFinishedCode(normalizeOutputMaterialKind(r.defaultOutputMaterialKind)));
  const hasFinished = allData.value.some(r => !usesSemiFinishedCode(normalizeOutputMaterialKind(r.defaultOutputMaterialKind)));
  if (hasSemi) opts.push({ value: 'SEMI', label: '半成品' });
  if (hasFinished) opts.push({ value: 'FINISHED', label: '成品' });
  return opts;
});

const filteredData = computed(() => allData.value.filter(row => {
  if (filterCategory.value && row.processCategory !== filterCategory.value) return false;
  if (filterUnit.value && row.unit !== filterUnit.value) return false;
  if (filterOutputKind.value) {
    const isSemi = usesSemiFinishedCode(normalizeOutputMaterialKind(row.defaultOutputMaterialKind));
    if (filterOutputKind.value === 'SEMI' && !isSemi) return false;
    if (filterOutputKind.value === 'FINISHED' && isSemi) return false;
  }
  return true;
}));

const tableData = computed(() => {
  const start = (pagination.value.page - 1) * pagination.value.size;
  return filteredData.value.slice(start, start + pagination.value.size);
});

function handleFilterChange() {
  pagination.value.page = 1;
}

function handleFilterReset() {
  filterCategory.value = '';
  filterUnit.value = '';
  filterOutputKind.value = '';
  pagination.value.page = 1;
}

// Dialog
const dialogVisible = ref(false);
const dialogTitle = ref('新增工序');
const isEditing = ref(false);
const formRef = ref();
const submitting = ref(false);

const CATEGORIES = [
  '前处理', '加工', '熟制', '注射', '包装', '灭菌', '质检', '存储', '配送', '其他'
];

const processCategoryOptions = computed(() => Array.from(new Set([
  ...CATEGORIES,
  ...allData.value.map((item) => item.processCategory).filter((value): value is string => Boolean(value)),
])).sort());

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
const outputUnitManuallyEdited = ref(false);
const advancedSettings = ref<string[]>([]);
const exactNameDuplicate = computed(() => {
  const name = formData.processName?.trim().toLocaleLowerCase();
  if (!name) return null;
  return allData.value.find((item) =>
    item.id !== formData.id && item.processName.trim().toLocaleLowerCase() === name,
  ) ?? null;
});

watch(() => formData.unit, (unit) => {
  if (!outputUnitManuallyEdited.value) formData.outputUnit = unit || '';
});

function queryProcessNames(query: string, callback: (items: Array<{ value: string }>) => void): void {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const names = new Set(
    allData.value
      .filter((item) => !formData.processCategory || item.processCategory === formData.processCategory)
      .map((item) => item.processName.trim())
      .filter(Boolean),
  );
  callback(Array.from(names)
    .filter((name) => !normalizedQuery || name.toLocaleLowerCase().includes(normalizedQuery))
    .map((value) => ({ value })));
}

function queryProcessCategories(query: string, callback: (items: Array<{ value: string }>) => void): void {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  callback(processCategoryOptions.value
    .filter((category) => !normalizedQuery || category.toLocaleLowerCase().includes(normalizedQuery))
    .map((value) => ({ value })));
}

function markOutputUnitEdited(): void {
  outputUnitManuallyEdited.value = true;
}

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
    { required: true, message: '请选择投入单位', trigger: 'change' }
  ],
  processCategory: [
    { required: true, message: '请选择或输入工序类别', trigger: ['blur', 'change'] }
  ],
  outputUnit: [
    { required: true, message: '请选择产出单位', trigger: 'change' }
  ],
  defaultOutputMaterialKind: [
    { required: true, message: '请选择默认产出类型', trigger: 'change' }
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

onMounted(() => void loadData());

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    // 全量拉取 (工序目录条目数有限, 非高基数表) 供筛选下拉的"当前有的"选项 + 客户端筛选/分页用。
    const response = await getWorkProcesses(factoryId.value, {
      page: 1,
      size: 1000,
      sortBy: 'sortOrder',
      sortDirection: 'ASC'
    });
    if (response.success && response.data) {
      allData.value = response.data.content || [];
      pagination.value.page = 1;
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
    standardYieldMin: null, standardYieldMax: null, needsInput: true, outputUnit: 'kg',
    defaultOutputMaterialKind: normalizeOutputMaterialKind(undefined),
    semiFinishedOutputCode: null,
    standardHourlyRate: null
  });
  outputUnitManuallyEdited.value = false;
  advancedSettings.value = [];
  dialogVisible.value = true;
}

function handleEdit(row: WorkProcessItem) {
  dialogTitle.value = '编辑工序';
  isEditing.value = true;
  const outputKind = normalizeOutputMaterialKind(row.defaultOutputMaterialKind);
  Object.assign(formData, {
    ...row,
    outputUnit: row.outputUnit || row.unit,
    defaultOutputMaterialKind: outputKind,
    semiFinishedOutputCode: usesSemiFinishedCode(outputKind) ? semiOutputCodeOf(row) : null,
  });
  outputUnitManuallyEdited.value = Boolean(row.outputUnit && row.outputUnit !== row.unit);
  advancedSettings.value = [];
  dialogVisible.value = true;
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid || !factoryId.value) return;
  if (exactNameDuplicate.value) {
    ElMessage.error(`已存在同名工序「${exactNameDuplicate.value.processName}」，请直接编辑已有工序`);
    return;
  }

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
      <!-- 筛选: 按当前数据里实际存在的 类别/单位/默认产出类型 生成选项, 非固定枚举 -->
      <div class="filter-bar">
        <el-select
          v-model="filterCategory"
          placeholder="全部类别"
          clearable
          style="width: 140px"
          @change="handleFilterChange"
        >
          <el-option v-for="cat in categoryOptions" :key="cat" :label="cat" :value="cat" />
        </el-select>
        <el-select
          v-model="filterUnit"
          placeholder="全部单位"
          clearable
          style="width: 120px"
          @change="handleFilterChange"
        >
          <el-option v-for="u in unitOptions" :key="u" :label="u" :value="u" />
        </el-select>
        <el-select
          v-model="filterOutputKind"
          placeholder="全部产出类型"
          clearable
          style="width: 140px"
          @change="handleFilterChange"
        >
          <el-option v-for="opt in outputKindOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-button :icon="Refresh" @click="handleFilterReset">重置</el-button>
      </div>

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
        v-if="filteredData.length > 0"
        style="margin-top: 16px; justify-content: flex-end"
        :current-page="pagination.page"
        :page-size="pagination.size"
        :total="filteredData.length"
        layout="total, prev, pager, next"
        @current-change="handlePageChange"
      />
    </el-card>

    <!-- Form Dialog -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="620px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="130px">
        <el-form-item label="工序名称" prop="processName">
          <el-autocomplete
            v-model="formData.processName"
            :fetch-suggestions="queryProcessNames"
            placeholder="如：拆箱、挂晒、卤制"
            clearable
            style="width: 100%"
          />
          <el-alert
            v-if="exactNameDuplicate"
            class="exact-duplicate-alert"
            type="error"
            :closable="false"
            show-icon
            :title="`已存在同名工序「${exactNameDuplicate.processName}」，不能重复创建`"
          />
        </el-form-item>
        <el-form-item label="工序类别" prop="processCategory">
          <el-autocomplete
            v-model="formData.processCategory"
            :fetch-suggestions="queryProcessCategories"
            placeholder="选择或输入历史类别"
            clearable
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="投入单位" prop="unit">
          <UnitSelect
            v-model="formData.unit"
            :factory-id="factoryId"
            placeholder="搜索投入单位；无匹配可新增"
          />
        </el-form-item>
        <el-form-item label="产出单位" prop="outputUnit">
          <UnitSelect
            v-model="formData.outputUnit"
            :factory-id="factoryId"
            placeholder="默认跟随投入单位"
            @change="markOutputUnitEdited"
          />
          <span class="form-hint">新增时自动跟随投入单位；手动选择后不再被投入单位覆盖</span>
        </el-form-item>
        <el-form-item label="默认产出类型" prop="defaultOutputMaterialKind">
          <el-radio-group
            v-model="formData.defaultOutputMaterialKind"
            @change="handleOutputKindChange"
          >
            <el-radio
              v-for="option in WORK_PROCESS_OUTPUT_KIND_OPTIONS"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </el-radio>
          </el-radio-group>
          <span class="form-hint">该工序加入产品 Workflow 后，系统会据此生成半成品或成品 Cell；默认为半成品，报工人员不可修改。</span>
        </el-form-item>

        <el-collapse v-model="advancedSettings" class="process-advanced">
          <el-collapse-item name="advanced" title="高级设置（可选）">
            <el-form-item label="预估工时">
              <el-input-number v-model="formData.estimatedMinutes" :min="1" placeholder="分钟" style="width: 100%" />
            </el-form-item>
            <el-form-item label="标准出成率下限" prop="standardYieldMin">
              <el-input-number v-model="minPct" :min="0.01" :max="999.99" :step="5" :precision="2"
                placeholder="如 30 (留空=不校验)" style="width: 100%" />
              <span class="form-hint">%（留空表示不校验）</span>
            </el-form-item>
            <el-form-item label="标准出成率上限" prop="standardYieldMax">
              <el-input-number v-model="maxPct" :min="0.01" :max="999.99" :step="5" :precision="2"
                placeholder="如 60 (留空=不校验)" style="width: 100%" />
              <span class="form-hint">%（超收预检以此为基准 × 投入量 × 1.3 容差）</span>
            </el-form-item>
            <el-form-item label="需录投入量">
              <el-switch v-model="formData.needsInput" />
              <span class="form-hint">纯包装/检验类可关闭</span>
            </el-form-item>
            <el-form-item v-if="showSemiFinishedCode" label="半成品产出编码" prop="semiFinishedOutputCode">
              <el-input
                v-model="formData.semiFinishedOutputCode"
                maxlength="50"
                show-word-limit
                placeholder="默认留空；需要固定识别码时再配置"
              />
            </el-form-item>
            <el-form-item label="标准时薪(元/小时)" prop="standardHourlyRate">
              <el-input-number v-model="formData.standardHourlyRate" :min="0" :step="1" :precision="2"
                placeholder="留空表示不计算人工成本" style="width: 100%" />
            </el-form-item>
            <el-form-item label="排序">
              <el-input-number v-model="formData.sortOrder" :min="0" style="width: 100%" />
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
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
.filter-bar { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.text-muted { color: #909399; }
.form-hint { font-size: 12px; color: #909399; margin-left: 4px; }
.exact-duplicate-alert { width: 100%; margin-top: 8px; }
.process-advanced { width: 100%; margin-top: 8px; }
/* C5: duplicate panel */
.dup-group { margin-bottom: 20px; padding-bottom: 16px; border-bottom: 1px solid #f0f0f0; }
.dup-group:last-child { border-bottom: none; margin-bottom: 0; }
.dup-group-title { font-size: 13px; font-weight: 500; }
</style>
