<!--
  Sprint 4 W1 S-CUSTOMER-TAB-1: tab 1 跟踪记录 (CRUD).
  防呆 R2: 新增/编辑 dialog header 含 客户名 + 编号.
  Backend: /api/mobile/{factoryId}/sales/customer-tracking?customerId= (Phase A A6, R4 dedup on POST).
  4 位一体 error: 409 (R4 dedup) MessageBox.confirm jump; other errors sticky toast with backend message.
-->
<template>
  <div class="tracking-tab">
    <div class="toolbar">
      <span class="title">跟踪记录</span>
      <div class="actions">
        <el-button :icon="Refresh" @click="fetchList" :loading="state === 'loading'">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">新增跟踪</el-button>
      </div>
    </div>

    <el-skeleton v-if="state === 'loading'" :rows="5" animated />

    <el-empty v-else-if="state === 'empty'" description="暂无跟踪记录" :image-size="80">
      <el-button type="primary" @click="openCreateDialog">立即新增第一条</el-button>
    </el-empty>

    <div v-else-if="state === 'error'" class="error-panel">
      <el-result icon="error" :title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="fetchList">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else>
      <el-table :data="records" border stripe size="small">
        <el-table-column label="时间" prop="recordTime" width="170">
          <template #default="{ row }">{{ formatDate(row.recordTime) }}</template>
        </el-table-column>
        <el-table-column label="类型" prop="trackingType" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.trackingType" size="small" :type="typeTagType(row.trackingType)">
              {{ trackingTypeLabel(row.trackingType) }}
            </el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="跟踪人" prop="recorderName" width="120" />
        <el-table-column label="内容" prop="content" min-width="240" show-overflow-tooltip />
        <el-table-column label="联系人" prop="contactPerson" width="100" />
        <el-table-column label="电话" prop="contactPhone" width="130" />
        <el-table-column label="备注" prop="remark" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
            <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
          </template>
        </el-table-column>
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

    <!-- R2: dialog header 含 客户名 + 编号 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="600px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <!-- 防呆 R3 dropdown — 6 options enforced, no free text -->
        <el-form-item label="跟踪类型" prop="trackingType">
          <el-select v-model="form.trackingType" placeholder="请选择跟踪类型" style="width: 100%">
            <el-option
              v-for="t in TRACKING_TYPES"
              :key="t.value"
              :value="t.value"
              :label="t.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="跟踪内容" prop="content">
          <el-input v-model="form.content" type="textarea" :rows="4" placeholder="请输入跟踪记录内容" />
        </el-form-item>
        <el-form-item label="联系人" prop="contactPerson">
          <el-input v-model="form.contactPerson" placeholder="对方联系人" />
        </el-form-item>
        <el-form-item label="电话" prop="contactPhone">
          <el-input v-model="form.contactPhone" placeholder="联系电话" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="form.address" placeholder="拜访/沟通地址" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitForm">
          {{ editingId ? '保存修改' : '提交' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Refresh, Plus } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { storeToRefs } from 'pinia';
import {
  listTrackingRecords,
  createTrackingRecord,
  updateTrackingRecord,
  deleteTrackingRecord,
  TRACKING_TYPES,
  trackingTypeLabel,
  type CustomerTrackingRecord,
  type CreateTrackingRecordRequest,
  type UpdateTrackingRecordRequest,
  type TrackingType,
} from '@/api/customerTracking';
import type { Customer } from '@/api/customer';

const props = defineProps<{
  customerId: string;
  customer: Customer | null;
}>();

const router = useRouter();
const authStore = useAuthStore();
const { factoryId } = storeToRefs(authStore);

const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
const errorMsg = ref('');
const records = ref<CustomerTrackingRecord[]>([]);
const totalElements = ref(0);
const currentPage = ref(1);
const pageSize = 20;

const dialogVisible = ref(false);
const editingId = ref<number | null>(null);
const submitLoading = ref(false);
const formRef = ref<FormInstance | null>(null);
const form = ref<CreateTrackingRecordRequest>({
  customerId: props.customerId,
  content: '',
  contactPerson: '',
  contactPhone: '',
  address: '',
  remark: '',
  trackingType: 'PHONE',
});

const rules: FormRules = {
  content: [{ required: true, message: '请输入跟踪内容', trigger: 'blur' }],
  trackingType: [{ required: true, message: '请选择跟踪类型', trigger: 'change' }],
};

const dialogTitle = computed(() => {
  const action = editingId.value ? '编辑跟踪' : '新增跟踪';
  const name = props.customer?.name || '';
  const code = props.customer?.customerCode || '';
  // 防呆 R2: header 含 客户名 + 编号
  return name && code ? `${action} — ${name} (${code})` : action;
});

function formatDate(s?: string): string {
  if (!s) return '—';
  return new Date(s).toLocaleString('zh-CN', { hour12: false });
}

function typeTagType(t?: string): 'success' | 'warning' | 'info' | 'primary' | 'danger' {
  switch (t) {
    case 'PHONE': return 'primary';
    case 'WECHAT': return 'success';
    case 'EMAIL': return 'info';
    case 'VISIT': return 'warning';
    case 'VIDEO': return 'primary';
    default: return 'info';
  }
}

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    const page = await listTrackingRecords(factoryId.value, {
      customerId: props.customerId,
      page: currentPage.value - 1,
      size: pageSize,
    });
    records.value = page.content;
    totalElements.value = page.totalElements;
    state.value = records.value.length === 0 ? 'empty' : 'ready';
  } catch (e: any) {
    handleError(e);
  }
}

function handleError(e: any) {
  const status = e?.response?.status || e?.code;
  const backendMsg = e?.response?.data?.message || e?.message;
  if (status === 403) {
    state.value = 'error';
    errorMsg.value = '无权查看此客户的跟踪记录';
  } else {
    state.value = 'error';
    errorMsg.value = backendMsg || '加载失败';
    // 4 位一体: sticky + 后端 message 原文 + showClose
    ElMessage({ message: errorMsg.value, type: 'error', duration: 0, showClose: true });
  }
  console.error('[CustomerDetail/TrackingTab] fetch failed:', e);
}

function onPageChange(p: number) {
  currentPage.value = p;
  fetchList();
}

function openCreateDialog() {
  editingId.value = null;
  form.value = {
    customerId: props.customerId,
    content: '',
    contactPerson: '',
    contactPhone: '',
    address: '',
    remark: '',
    trackingType: 'PHONE',
  };
  dialogVisible.value = true;
}

function openEditDialog(row: CustomerTrackingRecord) {
  editingId.value = row.id;
  form.value = {
    customerId: row.customerId,
    content: row.content,
    contactPerson: row.contactPerson || '',
    contactPhone: row.contactPhone || '',
    address: row.address || '',
    remark: row.remark || '',
    trackingType: row.trackingType || 'PHONE',
  };
  dialogVisible.value = true;
}

async function submitForm() {
  if (!formRef.value || !factoryId.value) return;
  await formRef.value.validate(async (valid) => {
    if (!valid) return;
    submitLoading.value = true;
    try {
      if (editingId.value) {
        const body: UpdateTrackingRecordRequest = {
          content: form.value.content,
          contactPerson: form.value.contactPerson,
          contactPhone: form.value.contactPhone,
          address: form.value.address,
          remark: form.value.remark,
        };
        await updateTrackingRecord(factoryId.value, editingId.value, body);
        ElMessage.success('已更新');
      } else {
        await createTrackingRecord(factoryId.value, form.value);
        ElMessage.success('跟踪记录已创建');
      }
      dialogVisible.value = false;
      await fetchList();
    } catch (e: any) {
      // 防呆 R4 dedup: 后端 409 with actionHint URL → confirm + navigate
      const status = e?.response?.status || e?.code;
      const backendMsg = e?.response?.data?.message || e?.message || '操作失败';
      const actionHint = e?.response?.data?.actionHint;
      if (status === 409 && actionHint) {
        try {
          await ElMessageBox.confirm(
            `${backendMsg} 是否查看?`,
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
      console.error('[CustomerDetail/TrackingTab] submit failed:', e);
    } finally {
      submitLoading.value = false;
    }
  });
}

async function confirmDelete(row: CustomerTrackingRecord) {
  // 防呆 R2: confirm 含 客户名 + 内容片段
  const preview = (row.content || '').substring(0, 30);
  try {
    await ElMessageBox.confirm(
      `确定删除「${props.customer?.name || '客户'}」的跟踪记录?\n\n内容: ${preview}${row.content.length > 30 ? '...' : ''}`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  if (!factoryId.value) return;
  try {
    await deleteTrackingRecord(factoryId.value, row.id);
    ElMessage.success('已删除');
    await fetchList();
  } catch (e: any) {
    const backendMsg = e?.response?.data?.message || e?.message || '删除失败';
    ElMessage({ message: backendMsg, type: 'error', duration: 0, showClose: true });
  }
}

onMounted(fetchList);
</script>

<style scoped>
.tracking-tab {
  padding: 8px 0;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.toolbar .title {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
  display: flex;
}
.error-panel {
  padding: 24px 0;
}
</style>
