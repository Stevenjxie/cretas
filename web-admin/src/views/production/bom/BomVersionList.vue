<script setup lang="ts">
/**
 * BomVersion 历史版本列表 — Sprint 5 Track-H H-2 MVP frontend follow-up.
 *
 * MVP slice (per dispatch §H H-2):
 *   - 列出某 BomRecipe 的全部历史版本 (newest first)
 *   - 显示状态机 (DRAFT / PENDING_APPROVAL / APPROVED / OBSOLETE / REJECTED)
 *   - 显示生效区间 (effective_from ~ effective_to)
 *   - ECN 关联 chip
 *   - 单条 approve/reject 按钮 (RBAC sensitive)
 *
 * NOT in MVP (Sprint 6 follow-up per §K):
 *   - 4 批量按钮 (per Round 11 §E.1)
 *   - ECN 编辑器 dialog
 *   - 反查 UI (某物料挂哪些 BomVersion)
 *
 * Permissions: read 公开 (snapshotJson cost 字段会被 strip), write 需
 * production:read_write / rd:read_write / finance:read_write.
 */
import { ref, computed, onMounted, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Check, Close, Document, Edit } from '@element-plus/icons-vue';
import {
  bomVersionApi,
  type BomVersion,
  type BomVersionStatus,
} from '@/api/bomVersion';

const props = defineProps<{
  /** Optional initial bomRecipeId. If absent, user must input it. */
  bomRecipeId?: string;
}>();

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
const inputBomRecipeId = ref(props.bomRecipeId ?? '');
const versions = ref<BomVersion[]>([]);
const currentVersion = ref<BomVersion | null>(null);

const statusTagType: Record<BomVersionStatus, '' | 'success' | 'warning' | 'info' | 'danger'> = {
  DRAFT: 'info',
  PENDING_APPROVAL: 'warning',
  APPROVED: 'success',
  OBSOLETE: '',
  REJECTED: 'danger',
};

const statusLabel: Record<BomVersionStatus, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已生效',
  OBSOLETE: '已替换',
  REJECTED: '已拒绝',
};

watch(
  () => props.bomRecipeId,
  (v) => {
    if (v && v !== inputBomRecipeId.value) {
      inputBomRecipeId.value = v;
      loadHistory();
    }
  },
);

onMounted(() => {
  if (inputBomRecipeId.value) loadHistory();
});

async function loadHistory() {
  if (!factoryId.value) {
    ElMessage.warning('未识别 factoryId, 请重新登录');
    return;
  }
  if (!inputBomRecipeId.value) {
    ElMessage.warning('请输入 BOM Recipe ID');
    return;
  }
  loading.value = true;
  try {
    const [hist, cur] = await Promise.all([
      bomVersionApi.getHistory(factoryId.value, inputBomRecipeId.value),
      bomVersionApi
        .getCurrent(factoryId.value, inputBomRecipeId.value)
        .catch(() => ({ success: false, data: null })),
    ]);
    if (hist.success) versions.value = hist.data ?? [];
    currentVersion.value = cur.success ? (cur.data as BomVersion) : null;
  } catch (e) {
    // axios interceptor already shows the error toast (rich actionHint), 不重复.
    const err = e as { actionHint?: string; status?: number } | undefined;
    if (!err?.actionHint) ElMessage.error('加载版本历史失败');
  } finally {
    loading.value = false;
  }
}

async function handleSubmit(row: BomVersion) {
  if (!factoryId.value) return;
  await ElMessageBox.confirm(
    `提交版本 ${row.versionNumber} 进入审批流?`,
    '确认提交审批',
    { confirmButtonText: '提交', cancelButtonText: '取消', type: 'info' },
  );
  try {
    await bomVersionApi.submitForApproval(factoryId.value, row.id);
    ElMessage.success('已提交审批');
    loadHistory();
  } catch (e) {
    const err = e as { actionHint?: string; status?: number } | undefined;
    if (!err?.actionHint) ElMessage.error('提交失败');
  }
}

async function handleApprove(row: BomVersion) {
  if (!factoryId.value || !userId.value) return;
  await ElMessageBox.confirm(
    `审批通过版本 ${row.versionNumber}? 旧版会自动失效 (OBSOLETE)`,
    '确认审批通过',
    { confirmButtonText: '通过', cancelButtonText: '取消', type: 'success' },
  );
  try {
    await bomVersionApi.approve(factoryId.value, row.id, {
      approverId: userId.value,
    });
    ElMessage.success('已审批通过');
    loadHistory();
  } catch (e) {
    const err = e as { actionHint?: string; status?: number } | undefined;
    if (!err?.actionHint) ElMessage.error('审批失败');
  }
}

async function handleReject(row: BomVersion) {
  if (!factoryId.value || !userId.value) return;
  // Rule 3 fool-proof: 拒绝原因目前用 prompt 暂时方案, Sprint 6 改 dropdown + textarea
  const reason = await ElMessageBox.prompt(
    `拒绝版本 ${row.versionNumber}, 请输入原因:`,
    '拒绝审批',
    {
      confirmButtonText: '拒绝',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim().length > 0) || '原因不能为空',
    },
  ).catch(() => null);
  if (!reason) return;
  try {
    await bomVersionApi.reject(factoryId.value, row.id, {
      approverId: userId.value,
      rejectionReason: reason.value,
    });
    ElMessage.success('已拒绝');
    loadHistory();
  } catch (e) {
    const err = e as { actionHint?: string; status?: number } | undefined;
    if (!err?.actionHint) ElMessage.error('操作失败');
  }
}

async function handleCreateDraft() {
  if (!factoryId.value || !userId.value) {
    ElMessage.warning('未识别 factoryId/userId');
    return;
  }
  if (!inputBomRecipeId.value) {
    ElMessage.warning('请先输入 BOM Recipe ID');
    return;
  }
  await ElMessageBox.confirm(
    `为 ${inputBomRecipeId.value} 创建新版本草稿? Snapshot 来自当前 BomRecipe.`,
    '创建新版本',
    { confirmButtonText: '创建', cancelButtonText: '取消', type: 'info' },
  );
  try {
    await bomVersionApi.createDraft(factoryId.value, {
      bomRecipeId: inputBomRecipeId.value,
      createdBy: userId.value,
    });
    ElMessage.success('已创建草稿');
    loadHistory();
  } catch (e) {
    const err = e as { actionHint?: string; status?: number } | undefined;
    if (!err?.actionHint) ElMessage.error('创建失败');
  }
}

function formatDate(d?: string | null): string {
  if (!d) return '-';
  return d.length > 10 ? d.substring(0, 10) : d;
}

function snapshotPreview(snapshot: Record<string, unknown>): string {
  if (!snapshot) return '-';
  const itemsField = (snapshot.items as unknown[]) || (snapshot.recipeItems as unknown[]);
  const recipeName = (snapshot.recipeName as string) || (snapshot.productName as string) || '';
  const count = Array.isArray(itemsField) ? itemsField.length : 0;
  return `${recipeName}${recipeName ? ' / ' : ''}${count} 项物料`;
}
</script>

<template>
  <div class="bom-version-list">
    <el-card>
      <template #header>
        <div class="header">
          <span>BOM 版本管理 (M-BOM-VER-1)</span>
          <el-tag size="small" type="info">Sprint 5 H-2 MVP</el-tag>
        </div>
      </template>

      <el-form inline @submit.prevent="loadHistory">
        <el-form-item label="BOM Recipe ID">
          <el-input
            v-model="inputBomRecipeId"
            placeholder="UUID 或自定义 ID"
            clearable
            style="width: 320px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Refresh" @click="loadHistory">
            加载历史
          </el-button>
          <el-button
            v-if="canWrite"
            type="success"
            :icon="Edit"
            :disabled="!inputBomRecipeId"
            @click="handleCreateDraft"
          >
            新建草稿
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="currentVersion" style="margin-top: 12px">
      <template #header>
        <span>当前生效版本</span>
      </template>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="版本号">v{{ currentVersion.versionNumber }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType[currentVersion.status]" size="small">
            {{ statusLabel[currentVersion.status] }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="生效日期">
          {{ formatDate(currentVersion.effectiveFrom) }}
        </el-descriptions-item>
        <el-descriptions-item label="ECN 关联">
          {{ currentVersion.ecnId || '手动版本' }}
        </el-descriptions-item>
        <el-descriptions-item label="审批人">{{ currentVersion.approvedBy ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="审批时间">
          {{ formatDate(currentVersion.approvedAt) }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card style="margin-top: 12px">
      <template #header>
        <span>历史版本 ({{ versions.length }} 条)</span>
      </template>
      <el-table v-loading="loading" :data="versions" stripe>
        <el-table-column prop="versionNumber" label="版本号" width="80">
          <template #default="{ row }">v{{ row.versionNumber }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType[row.status as BomVersionStatus]" size="small">
              {{ statusLabel[row.status as BomVersionStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生效区间" min-width="170">
          <template #default="{ row }">
            {{ formatDate(row.effectiveFrom) }} ~ {{ row.effectiveTo ? formatDate(row.effectiveTo) : '至今' }}
          </template>
        </el-table-column>
        <el-table-column label="Snapshot 概况" min-width="200">
          <template #default="{ row }">
            <span class="snapshot-preview">
              <el-icon><Document /></el-icon>
              {{ snapshotPreview(row.snapshotJson) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="ECN" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.ecnId" size="small">{{ row.ecnId.substring(0, 8) }}…</el-tag>
            <span v-else class="text-muted">手动</span>
          </template>
        </el-table-column>
        <el-table-column label="审批" min-width="140">
          <template #default="{ row }">
            <div class="meta">
              <div>by {{ row.approvedBy ?? row.createdBy ?? '-' }}</div>
              <div class="text-muted">{{ formatDate(row.approvedAt ?? row.createdAt) }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="拒绝原因" min-width="160">
          <template #default="{ row }">
            <span v-if="row.status === 'REJECTED'" class="text-danger">
              {{ row.rejectionReason || '-' }}
            </span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button-group v-if="canWrite">
              <el-button
                v-if="row.status === 'DRAFT'"
                size="small"
                type="primary"
                @click="handleSubmit(row)"
              >
                提交审批
              </el-button>
              <el-button
                v-if="row.status === 'PENDING_APPROVAL' || row.status === 'DRAFT'"
                size="small"
                type="success"
                :icon="Check"
                @click="handleApprove(row)"
              >
                通过
              </el-button>
              <el-button
                v-if="row.status === 'PENDING_APPROVAL'"
                size="small"
                type="danger"
                :icon="Close"
                @click="handleReject(row)"
              >
                拒绝
              </el-button>
            </el-button-group>
            <span v-else class="text-muted">仅读</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无版本数据 — 请先输入 BOM Recipe ID 并加载历史" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.bom-version-list {
  padding: 16px;
}
.header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.snapshot-preview {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.meta {
  display: flex;
  flex-direction: column;
  font-size: 12px;
}
.text-muted {
  color: var(--el-text-color-secondary, #909399);
}
.text-danger {
  color: var(--el-color-danger, #f56c6c);
}
</style>
