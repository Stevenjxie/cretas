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
import { Refresh, Check, Close, Document, Edit, Plus, Delete, Switch } from '@element-plus/icons-vue';
import {
  bomVersionApi,
  bomBatchApi,
  type BomBatchEcnReason,
  type BomVersion,
  type BomVersionStatus,
  type BatchResult,
} from '@/api/bomVersion';
import { bomRecipeApi, type BomRecipeSummary } from '@/api/bom';
import { get } from '@/api/request';

// Rule 3: material type + BOM recipe options for UUID inputs → el-select
interface MaterialTypeOption { id: string; name: string; code?: string | null }
interface BomRecipeOption { id: string; label: string }

const materialTypeOptions = ref<MaterialTypeOption[]>([]);
const bomRecipeOptions = ref<BomRecipeOption[]>([]);
const materialOptionsLoading = ref(false);
const recipeOptionsLoading = ref(false);

async function loadMaterialTypeOptions() {
  if (!factoryId.value || materialTypeOptions.value.length > 0) return;
  materialOptionsLoading.value = true;
  try {
    const res = await get<{ content: MaterialTypeOption[] }>(`/${factoryId.value}/raw-material-types`, { params: { size: 300 } });
    if (res.success && res.data) {
      materialTypeOptions.value = (res.data.content ?? (res.data as unknown as MaterialTypeOption[]));
    }
  } catch { /* optional, inputs remain as text fallback */ }
  finally { materialOptionsLoading.value = false; }
}

async function loadBomRecipeOptions() {
  if (!factoryId.value || bomRecipeOptions.value.length > 0) return;
  recipeOptionsLoading.value = true;
  try {
    const res = await bomRecipeApi.listRecipes(factoryId.value, { size: 200 });
    if (res.success && res.data) {
      const rows: BomRecipeSummary[] = res.data.content ?? [];
      bomRecipeOptions.value = rows.map((r) => ({
        id: r.id,
        label: `${r.productName || r.recipeCode} (${r.recipeCode})`,
      }));
    }
  } catch { /* optional */ }
  finally { recipeOptionsLoading.value = false; }
}

/** Sprint 6 W4-C — 防呆 Rule 3 reason dropdown (5 标准 + "其他"). */
const ECN_REASON_OPTIONS: Array<{ value: BomBatchEcnReason; label: string }> = [
  { value: 'CUSTOMER_REQUEST', label: '客户要求' },
  { value: 'MATERIAL_DISCONTINUED', label: '物料停产' },
  { value: 'COST_OPTIMIZATION', label: '成本优化' },
  { value: 'QUALITY_DEFECT', label: '质量缺陷' },
  { value: 'PROCESS_IMPROVEMENT', label: '工艺改进' },
];

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
  // Rule 3: 改为 el-select dropdown dialog (Sprint 6)
  openRejectDialog(row);
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

// ================== Sprint 6 W4-C — 4 批量操作 dialog ==================

/** 选中的 row (V4 接收 selection-change). */
const selectedRows = ref<BomVersion[]>([]);

/** 4 批量 dialog 通用 state. */
type BatchType = 'MODIFY' | 'REPLACE' | 'DELETE' | 'ADD' | null;
const batchDialogType = ref<BatchType>(null);
const batchSubmitLoading = ref(false);
const batchResultDialog = ref<BatchResult | null>(null);

// Rule 3: handleReject 原因 dialog state
const rejectDialogVisible = ref(false);
const rejectingRow = ref<BomVersion | null>(null);
const rejectReasonSelect = ref<BomBatchEcnReason | ''>('');
const rejectOtherText = ref('');

async function openRejectDialog(row: BomVersion) {
  if (!factoryId.value || !userId.value) return;
  rejectingRow.value = row;
  rejectReasonSelect.value = '';
  rejectOtherText.value = '';
  rejectDialogVisible.value = true;
}

async function submitReject() {
  if (!rejectReasonSelect.value) {
    ElMessage.warning('请选择拒绝原因');
    return;
  }
  const row = rejectingRow.value;
  if (!row || !factoryId.value || !userId.value) return;
  rejectDialogVisible.value = false;
  try {
    await bomVersionApi.reject(factoryId.value, row.id, {
      approverId: userId.value,
      rejectionReason: rejectReasonSelect.value,
      ecnReasonDetail: rejectOtherText.value.trim() || undefined,
    });
    ElMessage.success('已拒绝');
    loadHistory();
  } catch (e) {
    const err = e as { actionHint?: string; status?: number } | undefined;
    if (!err?.actionHint) ElMessage.error('操作失败');
  }
}

// B-FP-5: BOM 单位白名单 — 与后端 CreateBomRecipeRequest @Pattern 同步
// TODO: 是否补"斤"（转录牛腱按斤）待产品确认后决策
const BOM_UNIT_OPTIONS = ['g', 'kg', 'mg', 'ml', 'L', '个', '袋', '箱', '瓶', '盒'] as const;

/** Batch form. 全 mode 共用 — 不同 type 不同字段 enabled. */
const batchForm = ref({
  materialId: '',
  newStandardQuantity: 0,
  newUnit: '',
  oldMaterialId: '',
  newMaterialId: '',
  preserveQuantity: true,
  standardQuantity: 0,
  yieldRate: 100,
  unit: '',
  materialCategory: 'RAW',
  skipIfAlreadyPresent: true,
  ecnReason: 'CUSTOMER_REQUEST' as BomBatchEcnReason,
  ecnReasonDetail: '',
  effectiveDate: '',
});

const selectedRecipeIds = computed(() =>
  // 4 批量是按 recipe 而不是按 version, 但 UI 走 BomVersion 行选;
  // 取 versions 对应的 distinct bomRecipeId.
  Array.from(new Set(selectedRows.value.map((v) => v.bomRecipeId))),
);

function openBatchDialog(type: Exclude<BatchType, null>) {
  if (!canWrite.value) {
    ElMessage.warning('权限不足: 需要 production:read_write / rd:read_write / finance:read_write');
    return;
  }
  if (selectedRecipeIds.value.length === 0) {
    ElMessage.warning('请先选中至少一行 BomVersion (对应 N 个 BomRecipe)');
    return;
  }
  // Rule 3: 懒加载下拉选项
  loadMaterialTypeOptions();
  if (type === 'REPLACE') loadBomRecipeOptions(); // inputBomRecipeId uses BOM recipes
  // Reset form
  batchForm.value = {
    materialId: '',
    newStandardQuantity: 0,
    newUnit: '',
    oldMaterialId: '',
    newMaterialId: '',
    preserveQuantity: true,
    standardQuantity: 0,
    yieldRate: 100,
    unit: '',
    materialCategory: 'RAW',
    skipIfAlreadyPresent: true,
    ecnReason: 'CUSTOMER_REQUEST',
    ecnReasonDetail: '',
    effectiveDate: '',
  };
  batchDialogType.value = type;
}

const batchDialogTitle = computed(() => {
  switch (batchDialogType.value) {
    case 'MODIFY': return `批量修改 — 影响 ${selectedRecipeIds.value.length} 个 BomRecipe`;
    case 'REPLACE': return `批量替换 — 影响 ${selectedRecipeIds.value.length} 个 BomRecipe`;
    case 'DELETE': return `批量删除 — 影响 ${selectedRecipeIds.value.length} 个 BomRecipe`;
    case 'ADD': return `批量新增 — 影响 ${selectedRecipeIds.value.length} 个 BomRecipe`;
    default: return '';
  }
});

async function submitBatch() {
  if (!factoryId.value || !userId.value) {
    ElMessage.warning('未登录');
    return;
  }
  const type = batchDialogType.value;
  if (!type) return;

  const f = batchForm.value;
  const common = {
    recipeIds: selectedRecipeIds.value,
    allInFactory: false,
    ecnReason: f.ecnReason,
    ecnReasonDetail: f.ecnReasonDetail || undefined,
    effectiveDate: f.effectiveDate || undefined,
    createdBy: userId.value,
  };

  // 防呆 Rule 1 / 3: 各 mode 必填校验
  try {
    batchSubmitLoading.value = true;
    let result;
    if (type === 'MODIFY') {
      if (!f.materialId || !f.newStandardQuantity) {
        ElMessage.warning('物料 ID + 新数量 必填');
        return;
      }
      const r = await bomBatchApi.batchModify(factoryId.value, {
        ...common,
        materialId: f.materialId,
        newStandardQuantity: f.newStandardQuantity,
        newUnit: f.newUnit || undefined,
      });
      result = r;
    } else if (type === 'REPLACE') {
      if (!f.oldMaterialId || !f.newMaterialId) {
        ElMessage.warning('旧物料 + 新物料 必填');
        return;
      }
      const r = await bomBatchApi.batchReplace(factoryId.value, {
        ...common,
        oldMaterialId: f.oldMaterialId,
        newMaterialId: f.newMaterialId,
        preserveQuantity: f.preserveQuantity,
      });
      result = r;
    } else if (type === 'DELETE') {
      if (!f.materialId) {
        ElMessage.warning('物料 ID 必填');
        return;
      }
      const r = await bomBatchApi.batchDelete(factoryId.value, {
        ...common,
        materialId: f.materialId,
      });
      result = r;
    } else if (type === 'ADD') {
      if (!f.materialId || !f.standardQuantity || !f.unit) {
        ElMessage.warning('物料 + 数量 + 单位 必填');
        return;
      }
      const r = await bomBatchApi.batchAdd(factoryId.value, {
        ...common,
        materialId: f.materialId,
        standardQuantity: f.standardQuantity,
        yieldRate: f.yieldRate,
        unit: f.unit,
        materialCategory: f.materialCategory,
        skipIfAlreadyPresent: f.skipIfAlreadyPresent,
      });
      result = r;
    }
    if (result && result.success && result.data) {
      ElMessage.success(`已创建 ECN ${result.data.ecnNumber} + ${Object.keys(result.data.draftVersionIds).length} 个 BomVersion DRAFT`);
      batchResultDialog.value = result.data;
      batchDialogType.value = null;
      loadHistory();
    }
  } catch (e) {
    const err = e as { actionHint?: string } | undefined;
    if (!err?.actionHint) ElMessage.error('批量操作失败');
  } finally {
    batchSubmitLoading.value = false;
  }
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
        <el-form-item label="BOM Recipe">
          <!-- Rule 3: UUID → el-select by product name + recipeCode (lazily loaded) -->
          <el-select
            v-model="inputBomRecipeId"
            filterable
            clearable
            :loading="recipeOptionsLoading"
            placeholder="按产品名/配方编码搜索"
            style="width: 320px"
            @focus="loadBomRecipeOptions"
          >
            <el-option
              v-for="r in bomRecipeOptions"
              :key="r.id"
              :label="r.label"
              :value="r.id"
            />
          </el-select>
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

    <!-- Sprint 6 W4-C — 4 批量操作工具栏 -->
    <el-card v-if="canWrite" style="margin-top: 12px">
      <template #header>
        <span>批量操作 (Sprint 6 W4-C)</span>
      </template>
      <el-alert
        type="info"
        :closable="false"
        title="先勾选下方版本行 (按 BomRecipe 分组), 然后选择批量操作. 每个操作产生 1 个 ECN DRAFT + N 个 BomVersion DRAFT, 走审批后才落地."
        style="margin-bottom: 12px"
      />
      <div class="batch-toolbar">
        <el-tag type="info">已选 {{ selectedRecipeIds.length }} 个 BomRecipe</el-tag>
        <el-button
          type="primary"
          :icon="Edit"
          :disabled="selectedRecipeIds.length === 0"
          @click="openBatchDialog('MODIFY')"
        >
          批量修改
        </el-button>
        <el-button
          type="warning"
          :icon="Switch"
          :disabled="selectedRecipeIds.length === 0"
          @click="openBatchDialog('REPLACE')"
        >
          批量替换
        </el-button>
        <el-button
          type="danger"
          :icon="Delete"
          :disabled="selectedRecipeIds.length === 0"
          @click="openBatchDialog('DELETE')"
        >
          批量删除
        </el-button>
        <el-button
          type="success"
          :icon="Plus"
          :disabled="selectedRecipeIds.length === 0"
          @click="openBatchDialog('ADD')"
        >
          批量新增
        </el-button>
      </div>
    </el-card>

    <el-card style="margin-top: 12px">
      <template #header>
        <span>历史版本 ({{ versions.length }} 条)</span>
      </template>
      <el-table
        v-loading="loading"
        :data="versions"
        stripe
        @selection-change="(rows: BomVersion[]) => (selectedRows = rows)"
      >
        <el-table-column v-if="canWrite" type="selection" width="48" />
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

    <!-- Sprint 6 W4-C — 批量操作 dialog (4 modes 共用) -->
    <el-dialog
      v-model="batchDialogType"
      :title="batchDialogTitle"
      width="640px"
      :close-on-click-modal="false"
      :before-close="(done: () => void) => { batchDialogType = null; done(); }"
    >
      <el-form :model="batchForm" label-width="140px">
        <!-- MODIFY: materialId + newStandardQuantity + newUnit -->
        <template v-if="batchDialogType === 'MODIFY'">
          <!-- Rule 3: UUID → el-select by material name -->
          <el-form-item label="目标物料" required>
            <el-select
              v-model="batchForm.materialId"
              filterable
              :loading="materialOptionsLoading"
              placeholder="按名称搜索物料"
              style="width: 100%"
            >
              <el-option
                v-for="m in materialTypeOptions"
                :key="m.id"
                :label="`${m.name}${m.code ? ' (' + m.code + ')' : ''}`"
                :value="m.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="新数量 (per 单位)" required>
            <el-input-number v-model="batchForm.newStandardQuantity" :min="0" :precision="4" />
          </el-form-item>
          <!-- B-FP-5 Rule3: 改 el-select，同步后端 @Pattern 白名单，避免手填"斤"/"KG"触发400 -->
          <!-- TODO: 白名单缺"斤"（转录牛腱按斤），是否补充待产品确认 -->
          <el-form-item label="新单位 (可选)">
            <el-select v-model="batchForm.newUnit" clearable placeholder="不改单位则留空" style="width: 100%">
              <el-option v-for="u in BOM_UNIT_OPTIONS" :key="u" :label="u" :value="u" />
            </el-select>
          </el-form-item>
        </template>

        <!-- REPLACE: oldMaterialId + newMaterialId + preserveQuantity -->
        <template v-else-if="batchDialogType === 'REPLACE'">
          <!-- Rule 3: UUID → el-select -->
          <el-form-item label="旧物料" required>
            <el-select
              v-model="batchForm.oldMaterialId"
              filterable
              :loading="materialOptionsLoading"
              placeholder="按名称搜索被替换物料"
              style="width: 100%"
            >
              <el-option
                v-for="m in materialTypeOptions"
                :key="m.id"
                :label="`${m.name}${m.code ? ' (' + m.code + ')' : ''}`"
                :value="m.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="新物料" required>
            <el-select
              v-model="batchForm.newMaterialId"
              filterable
              :loading="materialOptionsLoading"
              placeholder="按名称搜索替换后物料"
              style="width: 100%"
            >
              <el-option
                v-for="m in materialTypeOptions"
                :key="m.id"
                :label="`${m.name}${m.code ? ' (' + m.code + ')' : ''}`"
                :value="m.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="保留原数量/单位">
            <el-switch v-model="batchForm.preserveQuantity" />
            <span class="form-hint">默认开 — 仅切换 material 引用, 不改 quantity/unit</span>
          </el-form-item>
        </template>

        <!-- DELETE: materialId -->
        <template v-else-if="batchDialogType === 'DELETE'">
          <!-- Rule 3: UUID → el-select -->
          <el-form-item label="将删除的物料" required>
            <el-select
              v-model="batchForm.materialId"
              filterable
              :loading="materialOptionsLoading"
              placeholder="按名称搜索物料"
              style="width: 100%"
            >
              <el-option
                v-for="m in materialTypeOptions"
                :key="m.id"
                :label="`${m.name}${m.code ? ' (' + m.code + ')' : ''}`"
                :value="m.id"
              />
            </el-select>
          </el-form-item>
          <el-alert
            type="warning"
            :closable="false"
            title="批量删除会从所选 BOM 移除此物料 item. 走 ECN 审批后才真删, draft 阶段仅 snapshot."
          />
        </template>

        <!-- ADD: materialId + standardQuantity + yieldRate + unit + materialCategory + skipIfAlreadyPresent -->
        <template v-else-if="batchDialogType === 'ADD'">
          <!-- Rule 3: UUID → el-select -->
          <el-form-item label="将新增的物料" required>
            <el-select
              v-model="batchForm.materialId"
              filterable
              :loading="materialOptionsLoading"
              placeholder="按名称搜索物料"
              style="width: 100%"
            >
              <el-option
                v-for="m in materialTypeOptions"
                :key="m.id"
                :label="`${m.name}${m.code ? ' (' + m.code + ')' : ''}`"
                :value="m.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="数量 (per 单位)" required>
            <el-input-number v-model="batchForm.standardQuantity" :min="0" :precision="4" />
          </el-form-item>
          <!-- B-FP-5 Rule3: 改 el-select，同步后端 @Pattern 白名单，避免手填"斤"/"KG"触发400 -->
          <!-- TODO: 白名单缺"斤"（转录牛腱按斤），是否补充待产品确认 -->
          <el-form-item label="单位" required>
            <el-select v-model="batchForm.unit" placeholder="请选择单位" style="width: 100%">
              <el-option v-for="u in BOM_UNIT_OPTIONS" :key="u" :label="u" :value="u" />
            </el-select>
          </el-form-item>
          <el-form-item label="出成率 (%)">
            <el-input-number v-model="batchForm.yieldRate" :min="0" :max="100" :precision="2" />
          </el-form-item>
          <el-form-item label="物料类型">
            <el-select v-model="batchForm.materialCategory" style="width: 100%">
              <el-option label="原料 (RAW)" value="RAW" />
              <el-option label="辅料 (AUXILIARY)" value="AUXILIARY" />
              <el-option label="包装 (PACKAGING)" value="PACKAGING" />
            </el-select>
          </el-form-item>
          <el-form-item label="已有此物料时跳过">
            <el-switch v-model="batchForm.skipIfAlreadyPresent" />
            <span class="form-hint">默认开 — 防误重复加</span>
          </el-form-item>
        </template>

        <!-- Common: ecnReason / ecnReasonDetail / effectiveDate -->
        <el-divider>ECN 字段 (自动 create DRAFT)</el-divider>
        <el-form-item label="变更原因" required>
          <el-select v-model="batchForm.ecnReason" style="width: 100%">
            <el-option
              v-for="opt in ECN_REASON_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="原因详情">
          <el-input
            v-model="batchForm.ecnReasonDetail"
            type="textarea"
            :rows="3"
            placeholder="(可选) 补充说明"
          />
        </el-form-item>
        <el-form-item label="计划生效日">
          <el-date-picker
            v-model="batchForm.effectiveDate"
            type="date"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchDialogType = null">取消</el-button>
        <el-button type="primary" :loading="batchSubmitLoading" @click="submitBatch">
          创建 ECN + DRAFTs
        </el-button>
      </template>
    </el-dialog>

    <!-- Sprint 6 W4-C — 批量结果 dialog -->
    <el-dialog
      v-model="batchResultDialog"
      :title="batchResultDialog ? `批量 ${batchResultDialog.operation} 完成` : ''"
      width="640px"
    >
      <div v-if="batchResultDialog">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="ECN 编号">
            <el-tag type="success">{{ batchResultDialog.ecnNumber }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="影响 BomRecipe">
            {{ batchResultDialog.affectedRecipeIds.length }} 个
          </el-descriptions-item>
          <el-descriptions-item label="生成 DRAFT 版本">
            {{ Object.keys(batchResultDialog.draftVersionIds).length }} 个
          </el-descriptions-item>
          <el-descriptions-item label="跳过 recipe">
            {{ Object.keys(batchResultDialog.skippedRecipes).length }} 个
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="Object.keys(batchResultDialog.skippedRecipes).length > 0" style="margin-top: 12px">
          <el-alert type="warning" :closable="false">
            <template #title>跳过明细</template>
            <ul>
              <li v-for="(reason, rid) in batchResultDialog.skippedRecipes" :key="rid">
                {{ rid }}: {{ reason }}
              </li>
            </ul>
          </el-alert>
        </div>
        <div v-if="batchResultDialog.warnings.length > 0" style="margin-top: 12px">
          <el-alert type="warning" :closable="false">
            <template #title>警告</template>
            <ul>
              <li v-for="(w, i) in batchResultDialog.warnings" :key="i">{{ w }}</li>
            </ul>
          </el-alert>
        </div>
      </div>
      <template #footer>
        <el-button @click="batchResultDialog = null">关闭</el-button>
      </template>
    </el-dialog>

    <!-- Rule 3: 拒绝审批原因 dialog (Sprint 6 升级: prompt → dropdown) -->
    <el-dialog
      v-model="rejectDialogVisible"
      :title="`拒绝版本 ${rejectingRow?.versionNumber || ''} — 请选择原因`"
      width="420px"
      destroy-on-close
    >
      <el-form label-width="90px">
        <el-form-item label="拒绝原因" required>
          <el-select
            v-model="rejectReasonSelect"
            placeholder="请选择拒绝原因"
            style="width: 100%"
          >
            <el-option
              v-for="opt in ECN_REASON_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="补充说明">
          <el-input
            v-model="rejectOtherText"
            type="textarea"
            :rows="2"
            placeholder="可选：填写具体说明"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :disabled="!rejectReasonSelect"
          @click="submitReject"
        >
          确认拒绝
        </el-button>
      </template>
    </el-dialog>
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
.batch-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.form-hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}
</style>
