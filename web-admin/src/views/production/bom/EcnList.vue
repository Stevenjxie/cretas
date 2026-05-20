<script setup lang="ts">
/**
 * ECN 工程变更通知 — Sprint 5 Track-H H-2 MVP stub.
 *
 * Backend: EcnController (M-BOM-VER-1, Sprint 3 Track-H ship PR #694).
 *
 * MVP scope: 单条 ECN 查询 + 创建 dialog. 后端没有 list/page endpoint, Sprint 6
 * 需补 GET /ecns (paginated + filter) + cascade BomVersion 审批 UI + 影响报告
 * dialog.
 *
 * Permissions: write 需 production:read_write / rd:read_write / finance:read_write.
 */
import { ref, computed } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Check, Close } from '@element-plus/icons-vue';
import {
  ecnApi,
  ECN_REASON_LABEL,
  ECN_STATUS_LABEL,
  type EngineeringChangeNotice,
  type EcnReason,
  type EcnStatus,
} from '@/api/ecn';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const userId = computed<number | null>(() => authStore.user?.id ?? null);
const canWrite = computed(
  () =>
    permissionStore.canWrite('production') ||
    permissionStore.canWrite('rd') ||
    permissionStore.canWrite('finance'),
);

const loading = ref(false);
const queryEcnId = ref('');
const ecnDetail = ref<EngineeringChangeNotice | null>(null);

const statusTag: Record<EcnStatus, '' | 'success' | 'warning' | 'info' | 'danger'> = {
  DRAFT: 'info',
  SUBMITTED: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  EFFECTIVE: 'success',
};

const reasonTag: Record<EcnReason, '' | 'success' | 'warning' | 'info' | 'danger'> = {
  CUSTOMER_REQUEST: 'warning',
  MATERIAL_DISCONTINUED: 'danger',
  COST_OPTIMIZATION: 'success',
  QUALITY_DEFECT: 'danger',
  PROCESS_IMPROVEMENT: 'info',
};

// Create dialog state
const createDialogVisible = ref(false);
const createForm = ref<{
  bomRecipeId: string;
  reason: EcnReason;
  title: string;
  description: string;
  effectiveDate: string;
}>({
  bomRecipeId: '',
  reason: 'CUSTOMER_REQUEST',
  title: '',
  description: '',
  effectiveDate: '',
});
const createLoading = ref(false);

async function loadDetail() {
  if (!factoryId.value || !queryEcnId.value) {
    ElMessage.warning('请输入 ECN ID');
    return;
  }
  loading.value = true;
  try {
    const r = await ecnApi.getById(factoryId.value, queryEcnId.value);
    ecnDetail.value = r.success ? (r.data as EngineeringChangeNotice) : null;
    if (!r.success) ElMessage.error(r.message || '未找到 ECN');
  } catch (e) {
    const err = e as { actionHint?: string } | undefined;
    if (!err?.actionHint) ElMessage.error('加载失败');
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  if (!canWrite.value) {
    ElMessage.warning('权限不足: 需要 production/rd/finance 写权限');
    return;
  }
  createForm.value = {
    bomRecipeId: '',
    reason: 'CUSTOMER_REQUEST',
    title: '',
    description: '',
    effectiveDate: '',
  };
  createDialogVisible.value = true;
}

async function submitCreate() {
  if (!factoryId.value || !userId.value) {
    ElMessage.warning('未登录');
    return;
  }
  const f = createForm.value;
  if (!f.bomRecipeId || !f.title || !f.reason) {
    ElMessage.warning('BOM Recipe / 标题 / 原因 必填');
    return;
  }
  createLoading.value = true;
  try {
    const r = await ecnApi.create(factoryId.value, {
      bomRecipeId: f.bomRecipeId,
      reason: f.reason,
      title: f.title,
      description: f.description || undefined,
      effectiveDate: f.effectiveDate || undefined,
      createdBy: userId.value,
    });
    if (r.success && r.data) {
      ElMessage.success(`已创建 ECN: ${r.data.ecnNumber}`);
      queryEcnId.value = r.data.id;
      ecnDetail.value = r.data;
      createDialogVisible.value = false;
    }
  } catch (e) {
    const err = e as { actionHint?: string } | undefined;
    if (!err?.actionHint) ElMessage.error('创建失败');
  } finally {
    createLoading.value = false;
  }
}

async function submitForApproval() {
  if (!ecnDetail.value || !factoryId.value) return;
  await ElMessageBox.confirm(
    `提交 ECN ${ecnDetail.value.ecnNumber} 进入审批流?`,
    '确认提交',
    { confirmButtonText: '提交', cancelButtonText: '取消', type: 'info' },
  );
  try {
    const r = await ecnApi.submitForApproval(factoryId.value, ecnDetail.value.id);
    if (r.success) {
      ElMessage.success('已提交审批');
      loadDetail();
    }
  } catch (e) {
    const err = e as { actionHint?: string } | undefined;
    if (!err?.actionHint) ElMessage.error('提交失败');
  }
}

async function approve() {
  if (!ecnDetail.value || !factoryId.value || !userId.value) return;
  await ElMessageBox.confirm(
    `审批通过 ECN ${ecnDetail.value.ecnNumber}? 将 cascade approve 关联的 BomVersion DRAFT.`,
    '确认审批通过',
    { confirmButtonText: '通过', cancelButtonText: '取消', type: 'success' },
  );
  try {
    const r = await ecnApi.approve(factoryId.value, ecnDetail.value.id, userId.value);
    if (r.success) {
      ElMessage.success('已审批通过');
      loadDetail();
    }
  } catch (e) {
    const err = e as { actionHint?: string } | undefined;
    if (!err?.actionHint) ElMessage.error('审批失败');
  }
}

async function reject() {
  if (!ecnDetail.value || !factoryId.value || !userId.value) return;
  const reason = await ElMessageBox.prompt(
    `拒绝 ECN ${ecnDetail.value.ecnNumber}, 请输入原因:`,
    '拒绝',
    {
      confirmButtonText: '拒绝',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim().length > 0) || '原因不能为空',
    },
  ).catch(() => null);
  if (!reason) return;
  try {
    const r = await ecnApi.reject(
      factoryId.value,
      ecnDetail.value.id,
      userId.value,
      reason.value,
    );
    if (r.success) {
      ElMessage.success('已拒绝');
      loadDetail();
    }
  } catch (e) {
    const err = e as { actionHint?: string } | undefined;
    if (!err?.actionHint) ElMessage.error('操作失败');
  }
}

function formatDate(d?: string | null): string {
  if (!d) return '-';
  return d.length > 10 ? d.substring(0, 10) : d;
}
</script>

<template>
  <div class="ecn-list">
    <el-card>
      <template #header>
        <div class="header">
          <span>工程变更通知 (ECN)</span>
          <el-tag size="small" type="info">Sprint 5 H-2 MVP stub</el-tag>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        title="MVP stub: 仅按 ECN ID 查询 + 创建. Sprint 6 follow-up: 分页 list + 影响报告 dialog + cascade BomVersion 审批面板."
        style="margin-bottom: 12px"
      />

      <el-form inline @submit.prevent="loadDetail">
        <el-form-item label="ECN ID">
          <el-input v-model="queryEcnId" placeholder="UUID" clearable style="width: 320px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" :loading="loading" @click="loadDetail">
            查询
          </el-button>
          <el-button v-if="canWrite" :icon="Plus" type="success" @click="openCreate">
            新建 ECN
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="ecnDetail" style="margin-top: 12px" v-loading="loading">
      <template #header>
        <div class="header">
          <span>{{ ecnDetail.ecnNumber }} — {{ ecnDetail.title }}</span>
          <el-tag :type="statusTag[ecnDetail.status]" size="small">
            {{ ECN_STATUS_LABEL[ecnDetail.status] }}
          </el-tag>
          <el-tag :type="reasonTag[ecnDetail.reason]" size="small">
            {{ ECN_REASON_LABEL[ecnDetail.reason] }}
          </el-tag>
        </div>
      </template>

      <el-descriptions :column="2" border>
        <el-descriptions-item label="BOM Recipe">{{ ecnDetail.bomRecipeId }}</el-descriptions-item>
        <el-descriptions-item label="计划生效日">{{ formatDate(ecnDetail.effectiveDate) }}</el-descriptions-item>
        <el-descriptions-item label="创建人">{{ ecnDetail.createdBy ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="审批人">{{ ecnDetail.approvedBy ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="审批时间">{{ formatDate(ecnDetail.approvedAt) }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDate(ecnDetail.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="变更描述" :span="2">
          {{ ecnDetail.description || '-' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="ecnDetail.status === 'REJECTED'" label="拒绝原因" :span="2">
          <span class="text-danger">{{ ecnDetail.rejectionReason || '-' }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="canWrite" class="action-bar">
        <el-button
          v-if="ecnDetail.status === 'DRAFT'"
          type="primary"
          @click="submitForApproval"
        >
          提交审批
        </el-button>
        <el-button
          v-if="ecnDetail.status === 'SUBMITTED'"
          type="success"
          :icon="Check"
          @click="approve"
        >
          通过 (cascade)
        </el-button>
        <el-button
          v-if="ecnDetail.status === 'SUBMITTED'"
          type="danger"
          :icon="Close"
          @click="reject"
        >
          拒绝 (cascade)
        </el-button>
      </div>
    </el-card>

    <el-dialog v-model="createDialogVisible" title="新建 ECN" width="600px">
      <el-form :model="createForm" label-width="120px">
        <el-form-item label="BOM Recipe" required>
          <el-input v-model="createForm.bomRecipeId" placeholder="BomRecipe UUID" />
        </el-form-item>
        <el-form-item label="变更原因" required>
          <el-select v-model="createForm.reason" style="width: 100%">
            <el-option
              v-for="(label, val) in ECN_REASON_LABEL"
              :key="val"
              :label="label"
              :value="val"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="createForm.title" placeholder="简短描述" />
        </el-form-item>
        <el-form-item label="详细描述">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            placeholder="为什么需要变更, 影响范围"
          />
        </el-form-item>
        <el-form-item label="计划生效日">
          <el-date-picker
            v-model="createForm.effectiveDate"
            type="date"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createLoading" @click="submitCreate">
          创建 DRAFT
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ecn-list {
  padding: 16px;
}
.header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.action-bar {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}
.text-danger {
  color: var(--el-color-danger, #f56c6c);
}
</style>
