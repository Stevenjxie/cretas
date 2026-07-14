<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Plus } from '@element-plus/icons-vue';
import {
  listDeductions,
  createDeduction,
  updateDeduction,
  changeDeductionStatus,
  deleteDeduction,
  previewDeductionForMonth,
  type SalarySpecialDeduction,
  type DeductionType,
  type DeductionStatus,
  type DeductionMonthPreview,
} from '@/api/specialDeduction';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('hr'));

// ============= constants =============
// R3 防呆: deductionType 走 dropdown (6 enum 值, 不允许自由文本)
const TYPE_OPTIONS: { label: string; value: DeductionType; defaultAmount: number; hint: string }[] = [
  { label: '子女教育', value: 'CHILD_EDUCATION', defaultAmount: 1000, hint: '¥1000/月/孩 (3岁起)' },
  { label: '继续教育', value: 'CONTINUING_EDUCATION', defaultAmount: 400, hint: '¥400/月 (学历) / ¥3600/年 (职业资格)' },
  { label: '大病医疗', value: 'SERIOUS_ILLNESS', defaultAmount: 0, hint: '自付 ≥¥15000/年, 限额 ¥80000/年 (年度汇算)' },
  { label: '住房贷款利息', value: 'HOUSING_LOAN', defaultAmount: 1000, hint: '¥1000/月 (首套, ≤20年)' },
  { label: '住房租金', value: 'HOUSING_RENT', defaultAmount: 1500, hint: '¥800/¥1100/¥1500 (按城市档位)' },
  { label: '赡养老人', value: 'ELDER_SUPPORT', defaultAmount: 2000, hint: '独生子女 ¥2000/月, 非独分摊' },
];
const TYPE_LABEL: Record<DeductionType, string> = TYPE_OPTIONS.reduce(
  (acc, opt) => ({ ...acc, [opt.value]: opt.label }),
  {} as Record<DeductionType, string>
);

const STATUS_LABEL: Record<DeductionStatus, string> = {
  ACTIVE: '生效中',
  EXPIRED: '已过期',
  CANCELLED: '已撤销',
};
const STATUS_TAG_TYPE: Record<DeductionStatus, '' | 'success' | 'warning' | 'info'> = {
  ACTIVE: 'success',
  EXPIRED: 'info',
  CANCELLED: 'warning',
};

// ============= state =============
const loading = ref(false);
const tableData = ref<SalarySpecialDeduction[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });

// 列表过滤
const filterUserId = ref<number | null>(null);
const filterStatus = ref<DeductionStatus | ''>('');
const filterType = ref<DeductionType | ''>('');

// 2026-07-14 web-admin filter audit (对齐 leave-requests/list.vue 已完成的 filter-bar 模式):
// 重置按钮显式重置 page + reload (watch([filterUserId, filterStatus, filterType]) 通常也会
// 触发同一逻辑, 但若 filter 已处于默认值则 watch 不会 fire, 这里显式调用保底).
function handleFilterReset() {
  filterUserId.value = null;
  filterStatus.value = '';
  filterType.value = '';
  pagination.value.page = 1;
  loadList();
}

// ============= dialog: create / edit =============
const dialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const dialogSubmitting = ref(false);
const formEditingId = ref<string | null>(null);

const formData = ref({
  userId: 0,
  deductionType: 'CHILD_EDUCATION' as DeductionType,
  monthlyAmount: 1000,
  validFrom: currentDate(),
  validTo: '' as string,    // 空字符串 = 长期有效
  notes: '',
});

// R1 防呆: 该 user 当月已有的 ACTIVE 扣除明细 + 合计 (打开 dialog 时拉, userId 改变时再拉)
const monthPreview = ref<DeductionMonthPreview | null>(null);
const previewLoading = ref(false);
const previewYearMonth = ref<string>(currentYearMonth());

function currentDate(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function currentYearMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

onMounted(() => {
  loadList();
});

watch([filterUserId, filterStatus, filterType], () => {
  pagination.value.page = 1;
  loadList();
});

// R1 防呆: dialog 中 userId 变化时刷新 preview
watch(() => formData.value.userId, async (val) => {
  if (!dialogVisible.value) return;
  if (!val || val <= 0) {
    monthPreview.value = null;
    return;
  }
  await refreshPreview();
});

// R3 防呆 联动: 选 type 时自动填默认金额
watch(() => formData.value.deductionType, (val) => {
  if (!dialogVisible.value || dialogMode.value === 'edit') return;
  const opt = TYPE_OPTIONS.find(o => o.value === val);
  if (opt && formData.value.monthlyAmount === 0) {
    formData.value.monthlyAmount = opt.defaultAmount;
  }
});

async function refreshPreview() {
  if (!factoryId.value || !formData.value.userId) return;
  previewLoading.value = true;
  try {
    const res = await previewDeductionForMonth(
      factoryId.value,
      formData.value.userId,
      previewYearMonth.value
    );
    if (res.success && res.data) {
      monthPreview.value = res.data;
    }
  } catch (e) {
    console.error('preview 失败:', e);
  } finally {
    previewLoading.value = false;
  }
}

// ============= list load =============
async function loadList() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listDeductions(factoryId.value, {
      userId: filterUserId.value || undefined,
      status: filterStatus.value || undefined,
      deductionType: filterType.value || undefined,
      page: pagination.value.page - 1,
      size: pagination.value.size,
    });
    if (res.success && res.data) {
      tableData.value = res.data.content || [];
      pagination.value.total = res.data.totalElements || 0;
    } else {
      tableData.value = [];
      pagination.value.total = 0;
    }
  } catch (e) {
    console.error('加载专项扣除失败:', e);
    tableData.value = [];
    pagination.value.total = 0;
  } finally {
    loading.value = false;
  }
}

// ============= dialog actions =============
function openCreate() {
  dialogMode.value = 'create';
  formEditingId.value = null;
  formData.value = {
    userId: 0,
    deductionType: 'CHILD_EDUCATION',
    monthlyAmount: 1000,
    validFrom: currentDate(),
    validTo: '',
    notes: '',
  };
  monthPreview.value = null;
  dialogVisible.value = true;
}

function openEdit(row: SalarySpecialDeduction) {
  if (row.status !== 'ACTIVE') {
    ElMessage.warning(`仅 ACTIVE 状态可编辑, 当前=${STATUS_LABEL[row.status]}`);
    return;
  }
  dialogMode.value = 'edit';
  formEditingId.value = row.id;
  formData.value = {
    userId: row.userId,
    deductionType: row.deductionType,
    monthlyAmount: Number(row.monthlyAmount),
    validFrom: row.validFrom,
    validTo: row.validTo || '',
    notes: row.notes || '',
  };
  // 立即触发 preview
  refreshPreview();
  dialogVisible.value = true;
}

async function handleSubmit() {
  if (!factoryId.value) return;
  if (!formData.value.userId || formData.value.userId <= 0) {
    ElMessage.error('请填写员工 userId');
    return;
  }
  if (formData.value.monthlyAmount < 0) {
    ElMessage.error('月扣金额必须 ≥ 0');
    return;
  }
  if (!formData.value.validFrom || !/^\d{4}-\d{2}-\d{2}$/.test(formData.value.validFrom)) {
    ElMessage.error('validFrom 格式必须为 YYYY-MM-DD');
    return;
  }
  if (formData.value.validTo && !/^\d{4}-\d{2}-\d{2}$/.test(formData.value.validTo)) {
    ElMessage.error('validTo 格式必须为 YYYY-MM-DD (留空 = 长期有效)');
    return;
  }

  dialogSubmitting.value = true;
  try {
    if (dialogMode.value === 'create') {
      const res = await createDeduction(factoryId.value, {
        userId: formData.value.userId,
        deductionType: formData.value.deductionType,
        monthlyAmount: formData.value.monthlyAmount,
        validFrom: formData.value.validFrom,
        validTo: formData.value.validTo || null,
        notes: formData.value.notes || undefined,
      });
      if (res.success) {
        ElMessage.success(res.message || '已创建');
        dialogVisible.value = false;
        await loadList();
      }
    } else if (formEditingId.value) {
      const res = await updateDeduction(factoryId.value, formEditingId.value, {
        monthlyAmount: formData.value.monthlyAmount,
        validTo: formData.value.validTo || null,
        notes: formData.value.notes || undefined,
      });
      if (res.success) {
        ElMessage.success(res.message || '已更新');
        dialogVisible.value = false;
        await loadList();
      }
    }
  } catch (e) {
    // 4位一体: 全局 axios interceptor 已 sticky display backend message (含 R4 409)
    console.error('提交失败:', e);
  } finally {
    dialogSubmitting.value = false;
  }
}

// R2 防呆: 状态变更 dialog 含 user + type + amount context
async function handleChangeStatus(row: SalarySpecialDeduction, target: DeductionStatus) {
  const targetLabel = STATUS_LABEL[target];
  try {
    await ElMessageBox.confirm(
      `将 [用户 ${row.userId} / ${TYPE_LABEL[row.deductionType]} / 月扣 ¥${row.monthlyAmount}] 状态改为 [${targetLabel}] ?`,
      `变更为 ${targetLabel}`,
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' }
    );
    if (!factoryId.value) return;
    const res = await changeDeductionStatus(factoryId.value, row.id, target);
    if (res.success) {
      ElMessage.success(res.message || `已变更为 ${targetLabel}`);
      await loadList();
    }
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') console.error(e);
  }
}

async function handleDelete(row: SalarySpecialDeduction) {
  try {
    await ElMessageBox.confirm(
      `永久删除 [用户 ${row.userId} / ${TYPE_LABEL[row.deductionType]} / 月扣 ¥${row.monthlyAmount} / ${STATUS_LABEL[row.status]}] ?`,
      '删除确认',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    );
    if (!factoryId.value) return;
    const res = await deleteDeduction(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success('已删除');
      await loadList();
    }
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') console.error(e);
  }
}
</script>

<template>
  <div class="special-deductions-page" style="padding: 16px;">
    <el-card>
      <div
        style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;"
      >
        <div>
          <h2 style="margin: 0;">个税专项附加扣除</h2>
          <p style="margin: 4px 0 0 0; color: #909399; font-size: 12px;">
            China 个税 6 大附加扣除. 月度计税时自动减去 ACTIVE 项合计.
          </p>
        </div>
        <div>
          <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">
            新增扣除项
          </el-button>
          <el-button :icon="Refresh" @click="loadList">刷新</el-button>
        </div>
      </div>

      <!-- 列表 filter (R3 防呆: 类型/状态复用已有固定业务枚举 dropdown, 不自由文本) -->
      <el-form inline class="filter-bar" style="margin-bottom: 12px;">
        <el-form-item label="员工 userId">
          <el-input-number
            v-model="filterUserId"
            :min="1"
            :precision="0"
            placeholder="(留空看全部)"
            clearable
            style="width: 180px;"
          />
        </el-form-item>
        <el-form-item label="类型">
          <el-select
            v-model="filterType"
            placeholder="全部"
            clearable
            style="width: 160px;"
          >
            <el-option
              v-for="opt in TYPE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filterStatus" placeholder="全部" clearable style="width: 140px;">
            <el-option
              v-for="(label, value) in STATUS_LABEL"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button :icon="Refresh" @click="handleFilterReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="tableData" v-loading="loading" stripe>
        <el-table-column prop="userId" label="员工ID" width="100" />
        <el-table-column prop="deductionType" label="扣除类型" width="160">
          <template #default="{ row }">
            {{ TYPE_LABEL[row.deductionType as DeductionType] || row.deductionType }}
          </template>
        </el-table-column>
        <el-table-column prop="monthlyAmount" label="月扣金额" width="120">
          <template #default="{ row }">
            <strong>¥{{ Number(row.monthlyAmount).toFixed(2) }}</strong>
          </template>
        </el-table-column>
        <el-table-column prop="validFrom" label="生效日" width="120" />
        <el-table-column prop="validTo" label="失效日" width="120">
          <template #default="{ row }">
            <span v-if="row.validTo">{{ row.validTo }}</span>
            <el-tag v-else size="small" type="info">长期</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG_TYPE[row.status as DeductionStatus]">
              {{ STATUS_LABEL[row.status as DeductionStatus] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="notes" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <template v-if="canWrite && row.status === 'ACTIVE'">
              <el-button size="small" @click="openEdit(row)">编辑</el-button>
              <el-button
                size="small"
                type="warning"
                @click="handleChangeStatus(row, 'EXPIRED')"
              >
                结束
              </el-button>
              <el-button
                size="small"
                type="danger"
                @click="handleChangeStatus(row, 'CANCELLED')"
              >
                撤销
              </el-button>
            </template>
            <template v-else-if="canWrite">
              <el-button size="small" type="danger" @click="handleDelete(row)">
                删除
              </el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end;"
        @current-change="loadList"
        @size-change="loadList"
      />
    </el-card>

    <!-- R1 防呆: 创建/编辑对话框, 显示该 user 当月所有 ACTIVE 扣除明细 + 合计 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新增专项扣除' : '编辑专项扣除'"
      width="720px"
    >
      <el-form :model="formData" label-width="120px">
        <el-form-item label="员工 userId" required>
          <el-input-number
            v-model="formData.userId"
            :min="1"
            :precision="0"
            :disabled="dialogMode === 'edit'"
            style="width: 200px;"
          />
        </el-form-item>

        <!-- R3 防呆: 类型走 dropdown (6 enum 值) -->
        <el-form-item label="扣除类型" required>
          <el-select
            v-model="formData.deductionType"
            :disabled="dialogMode === 'edit'"
            style="width: 240px;"
          >
            <el-option
              v-for="opt in TYPE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            >
              <div style="display: flex; justify-content: space-between;">
                <span>{{ opt.label }}</span>
                <span style="color: #909399; font-size: 12px;">{{ opt.hint }}</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item label="月扣金额" required>
          <el-input-number
            v-model="formData.monthlyAmount"
            :min="0"
            :step="100"
            :precision="2"
            style="width: 200px;"
          />
          <span style="margin-left: 12px; color: #909399; font-size: 12px;">
            ¥/月 (MVP 由 HR 输入实际金额, 不强制上限)
          </span>
        </el-form-item>

        <el-form-item label="生效起始日" required>
          <el-input
            v-model="formData.validFrom"
            placeholder="YYYY-MM-DD"
            :disabled="dialogMode === 'edit'"
            style="width: 200px;"
          />
        </el-form-item>

        <el-form-item label="失效日">
          <el-input
            v-model="formData.validTo"
            placeholder="YYYY-MM-DD (留空 = 长期有效)"
            style="width: 240px;"
            clearable
          />
        </el-form-item>

        <el-form-item label="备注">
          <el-input
            v-model="formData.notes"
            type="textarea"
            :rows="2"
            placeholder="可选, 简要说明 (如孩子姓名 / 贷款合同号 / 兄弟姐妹分摊比例)"
          />
        </el-form-item>

        <!-- R1 防呆: 该 user 当月已有的 ACTIVE 扣除合计预览 -->
        <el-alert
          v-if="monthPreview && monthPreview.activeDeductions.length > 0"
          type="info"
          :closable="false"
          show-icon
          style="margin-top: 12px;"
        >
          <template #title>
            <div style="font-weight: 600;">
              用户 {{ monthPreview.userId }} {{ monthPreview.yearMonth }} 当前 ACTIVE 扣除:
              ¥{{ Number(monthPreview.totalMonthlyDeduction).toFixed(2) }}
              ({{ monthPreview.activeDeductions.length }} 项)
            </div>
          </template>
          <template #default>
            <div style="font-size: 13px; line-height: 1.6;">
              <div
                v-for="d in monthPreview.activeDeductions"
                :key="d.id"
                style="display: flex; justify-content: space-between; padding: 2px 0;"
              >
                <span>{{ TYPE_LABEL[d.deductionType] }} ({{ d.validFrom }} 起)</span>
                <span><strong>¥{{ Number(d.monthlyAmount).toFixed(2) }}</strong></span>
              </div>
              <div
                style="margin-top: 4px; padding-top: 4px; border-top: 1px dashed #ddd; color: #909399;"
              >
                月度计税时此合计将从 (base - 个人社保 - 个人公积金 - 5000) 应税收入中减去
              </div>
            </div>
          </template>
        </el-alert>
        <el-alert
          v-else-if="monthPreview && monthPreview.activeDeductions.length === 0"
          type="success"
          :title="`用户 ${monthPreview.userId} ${monthPreview.yearMonth} 暂无 ACTIVE 专项扣除`"
          :closable="false"
          show-icon
          style="margin-top: 12px;"
        />
        <el-alert
          v-else-if="previewLoading"
          type="info"
          title="加载当月扣除明细..."
          :closable="false"
          style="margin-top: 12px;"
        />
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="dialogSubmitting"
          @click="handleSubmit"
        >
          {{ dialogMode === 'create' ? '保存' : '保存修改' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.special-deductions-page {
  min-height: 100%;
}
.filter-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
</style>
