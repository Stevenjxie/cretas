<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 20 业务员变更 history + 变更 dialog.
  Backend: GET /api/mobile/{factoryId}/customer-sales-user-history (Phase A A5)
         POST /api/mobile/{factoryId}/customers/{id}/assigned-sales-user (Phase A A5)

  防呆全套:
   R1 边界预显: dialog 大字明示「当前业务员: X」, newSalesUserId 选同 user 时 disable submit
   R2 context: dialog header 含 客户名 + 编号
   R3 dropdown: 变更原因 6 标准 + "其他" 才显 textarea
   R4 idempotent: 后端 5min dedup → 409 + actionHint, 前端 MessageBox confirm + jump
   4 位一体: error sticky toast + 后端 message + actionHint
-->
<template>
  <div class="sales-user-hist-tab">
    <div class="toolbar">
      <div>
        <span class="title">业务员变更历史</span>
        <el-tag size="small" class="current-tag" v-if="currentName">
          当前业务员: {{ currentName }}
        </el-tag>
        <el-tag size="small" type="info" class="current-tag" v-else>
          当前未分配业务员
        </el-tag>
      </div>
      <div>
        <el-button :icon="Refresh" @click="fetchList" :loading="state === 'loading'">刷新</el-button>
        <el-button type="primary" :icon="UserFilled" @click="openChangeDialog">变更业务员</el-button>
      </div>
    </div>

    <el-skeleton v-if="state === 'loading'" :rows="5" animated />
    <el-empty v-else-if="state === 'empty'" description="暂无业务员变更记录" :image-size="80" />
    <div v-else-if="state === 'error'" class="error-panel">
      <el-result icon="error" :title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="fetchList">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else>
      <el-table :data="history" border stripe size="small">
        <el-table-column label="变更时间" prop="changedAt" width="170">
          <template #default="{ row }">{{ formatDate(row.changedAt) }}</template>
        </el-table-column>
        <el-table-column label="原业务员" prop="previousSalesUserId" width="140">
          <template #default="{ row }">
            {{ row.previousSalesUserId ? `User #${row.previousSalesUserId}` : '未分配' }}
          </template>
        </el-table-column>
        <el-table-column label="新业务员" prop="newSalesUserId" width="140">
          <template #default="{ row }">
            {{ row.newSalesUserId ? `User #${row.newSalesUserId}` : '未分配' }}
          </template>
        </el-table-column>
        <el-table-column label="变更人" prop="changedBy" width="120">
          <template #default="{ row }">{{ row.changedBy ? `User #${row.changedBy}` : '—' }}</template>
        </el-table-column>
        <el-table-column label="变更原因" prop="reason" min-width="200" show-overflow-tooltip />
      </el-table>

      <el-pagination
        class="pagination"
        background
        layout="prev, pager, next, total"
        :total="totalElements"
        :page-size="pageSize"
        :current-page="currentPage"
        @current-change="onPageChange"
      />
    </template>

    <!-- 防呆 R2 dialog header 客户名 + 编号 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="600px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <!-- R1 边界预显 -->
      <el-alert
        type="info"
        :closable="false"
        show-icon
        :title="`当前业务员: ${currentName || '未分配'}`"
        style="margin-bottom: 16px"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="新业务员" prop="newSalesUserId">
          <el-input-number
            v-model="form.newSalesUserId"
            :min="1"
            placeholder="输入新业务员 user id"
            style="width: 100%"
          />
          <div v-if="sameAsCurrent" class="warn-text">
            与当前业务员相同, 无需变更
          </div>
        </el-form-item>

        <!-- R3 dropdown 6 标准选项 + 其他 -->
        <el-form-item label="变更原因" prop="reasonCode">
          <el-select v-model="form.reasonCode" placeholder="请选择原因" style="width: 100%">
            <el-option
              v-for="r in CHANGE_REASONS"
              :key="r.value"
              :value="r.value"
              :label="r.label"
            />
          </el-select>
        </el-form-item>

        <!-- 选「其他」才显 textarea (R3) -->
        <el-form-item
          v-if="form.reasonCode === 'OTHER'"
          label="详细说明"
          prop="reasonText"
        >
          <el-input
            v-model="form.reasonText"
            type="textarea"
            :rows="3"
            placeholder="请输入详细变更原因"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitLoading"
          :disabled="submitDisabled"
          @click="submitChange"
        >
          确认变更
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh, UserFilled } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import {
  listHistory,
  type CustomerSalesUserHistory,
} from '@/api/customerSalesUserHistory';
import { updateAssignedSalesUser, type Customer } from '@/api/customer';

const CHANGE_REASONS = [
  { value: 'RESIGNATION', label: '离职交接' },
  { value: 'TERRITORY', label: '区域调整' },
  { value: 'CUSTOMER_REQUEST', label: '客户要求' },
  { value: 'PERFORMANCE', label: '业绩重分配' },
  { value: 'PROBATION_END', label: '试用期到期' },
  { value: 'OTHER', label: '其他' },
];

const props = defineProps<{
  customerId: string;
  customer: Customer | null;
}>();

const emit = defineEmits<{
  (e: 'changed'): void;
}>();

const router = useRouter();
const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const history = ref<CustomerSalesUserHistory[]>([]);
const totalElements = ref(0);
const currentPage = ref(1);
const pageSize = 20;

const dialogVisible = ref(false);
const submitLoading = ref(false);
const formRef = ref<FormInstance | null>(null);
const form = ref({
  newSalesUserId: undefined as number | undefined,
  reasonCode: '',
  reasonText: '',
});

const rules: FormRules = {
  newSalesUserId: [
    { required: true, message: '请输入新业务员 user id', trigger: 'blur' },
  ],
  reasonCode: [{ required: true, message: '请选择变更原因', trigger: 'change' }],
  reasonText: [
    {
      required: true,
      message: '请填写详细原因',
      trigger: 'blur',
      validator(_rule, _value, callback) {
        if (form.value.reasonCode === 'OTHER' && !form.value.reasonText?.trim()) {
          callback(new Error('请填写详细原因'));
        } else {
          callback();
        }
      },
    },
  ],
};

const currentName = computed(() => {
  // Sprint 4 W1: prefer backend-resolved Customer.assignedSalesUserName, fall back to ID display.
  const fromBackend = props.customer?.assignedSalesUserName;
  if (fromBackend) return fromBackend;
  const id = props.customer?.assignedSalesUserId;
  return id ? `User #${id}` : '';
});

const sameAsCurrent = computed(() => {
  const curr = props.customer?.assignedSalesUserId;
  return curr != null && form.value.newSalesUserId === curr;
});

const submitDisabled = computed(() => {
  if (sameAsCurrent.value) return true;
  if (!form.value.newSalesUserId) return true;
  if (!form.value.reasonCode) return true;
  if (form.value.reasonCode === 'OTHER' && !form.value.reasonText?.trim()) return true;
  return false;
});

const dialogTitle = computed(() => {
  const name = props.customer?.name || '';
  const code = props.customer?.customerCode || '';
  return name && code
    ? `变更业务员 — ${name} (${code})`
    : '变更业务员';
});

function formatDate(s?: string): string {
  if (!s) return '—';
  return new Date(s).toLocaleString('zh-CN', { hour12: false });
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const page = await listHistory(factoryId.value, props.customerId, currentPage.value, pageSize);
    history.value = page.content;
    totalElements.value = page.totalElements;
    state.value = history.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    const status = e?.response?.status || e?.code;
    const backendMsg = e?.response?.data?.message || e?.message;
    if (status === 403) {
      state.value = 'error';
      errorMsg.value = '无权查看此客户的业务员变更记录';
    } else {
      state.value = 'error';
      errorMsg.value = backendMsg || '加载失败';
      ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
    }
    console.error('[CustomerDetail/SalesUserHistoryTab] fetch failed:', e);
  }
}

function onPageChange(p: number) {
  currentPage.value = p;
  fetchList();
}

function openChangeDialog() {
  form.value = {
    newSalesUserId: undefined,
    reasonCode: '',
    reasonText: '',
  };
  dialogVisible.value = true;
}

async function submitChange() {
  if (!formRef.value || !factoryId.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    if (!form.value.newSalesUserId) return;
    submitLoading.value = true;
    const reasonLabel = CHANGE_REASONS.find((r) => r.value === form.value.reasonCode)?.label || form.value.reasonCode;
    const reason =
      form.value.reasonCode === 'OTHER'
        ? `其他: ${form.value.reasonText}`
        : reasonLabel;
    try {
      await updateAssignedSalesUser(factoryId.value, props.customerId, {
        newSalesUserId: form.value.newSalesUserId,
        reason,
      });
      ElMessage.success('业务员变更成功');
      dialogVisible.value = false;
      emit('changed');
      await fetchList();
    } catch (e: any) {
      const status = e?.response?.status || e?.code;
      const backendMsg = e?.response?.data?.message || e?.message || '变更失败';
      const actionHint = e?.response?.data?.actionHint;
      // R4 409: 已 5min 内变更过 → confirm + jump
      if (status === 409 && actionHint) {
        try {
          await ElMessageBox.confirm(
            `${backendMsg} 是否查看已有变更记录?`,
            '操作冲突',
            { type: 'warning', confirmButtonText: '查看', cancelButtonText: '取消' },
          );
          router.push(actionHint);
        } catch {
          // user cancelled
        }
      } else {
        ElMessage({
          message: backendMsg,
          type: 'error',
          duration: 0,
          showClose: true,
        });
      }
      console.error('[CustomerDetail/SalesUserHistoryTab] change failed:', e);
    } finally {
      submitLoading.value = false;
    }
  });
}

onMounted(fetchList);
</script>

<style scoped>
.sales-user-hist-tab { padding: 8px 0; }
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.toolbar .title { font-size: 14px; color: var(--el-text-color-secondary); margin-right: 12px; }
.current-tag { margin-left: 4px; }
.pagination { margin-top: 16px; justify-content: flex-end; display: flex; }
.warn-text { color: var(--el-color-warning); font-size: 12px; margin-top: 4px; }
.error-panel { padding: 24px 0; }
</style>
