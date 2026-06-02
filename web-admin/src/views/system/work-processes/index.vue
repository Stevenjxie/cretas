<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Refresh } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import {
  getWorkProcesses, createWorkProcess, updateWorkProcess,
  deleteWorkProcess, toggleWorkProcessStatus,
  type WorkProcessItem
} from '@/api/processProduction';

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
  '前处理', '加工', '包装', '灭菌', '质检', '存储', '配送', '其他'
];

const formData = reactive<Partial<WorkProcessItem>>({
  id: '',
  processName: '',
  processCategory: '',
  unit: 'kg',
  estimatedMinutes: null,
  sortOrder: 0,
  standardYieldMin: null,
  standardYieldMax: null,
  needsInput: true,
  outputUnit: ''
});

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
  ]
};

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
    standardYieldMin: null, standardYieldMax: null, needsInput: true, outputUnit: ''
  });
  dialogVisible.value = true;
}

function handleEdit(row: WorkProcessItem) {
  dialogTitle.value = '编辑工序';
  isEditing.value = true;
  Object.assign(formData, { ...row });
  dialogVisible.value = true;
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid || !factoryId.value) return;

  submitting.value = true;
  try {
    const payload = { ...formData };
    if (isEditing.value && formData.id) {
      await updateWorkProcess(factoryId.value, formData.id, payload);
      ElMessage.success('工序已更新');
    } else {
      await createWorkProcess(factoryId.value, payload);
      ElMessage.success('工序已创建');
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
          <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleAdd">
            新增工序
          </el-button>
        </div>
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
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px">
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="100px">
        <el-form-item label="工序名称" prop="processName">
          <el-input v-model="formData.processName" placeholder="如：拆箱、挂晒、卤制" />
        </el-form-item>
        <el-form-item label="工序类别" prop="processCategory">
          <el-select v-model="formData.processCategory" placeholder="选择类别" clearable style="width: 100%">
            <el-option v-for="cat in CATEGORIES" :key="cat" :label="cat" :value="cat" />
          </el-select>
        </el-form-item>
        <el-form-item label="产出单位" prop="unit">
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
</style>
