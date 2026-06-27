<script setup lang="ts">
/**
 * ECN 工程变更通知 — Sprint 6 W4-C paginated list + impact report.
 *
 * Backend: EcnController (M-BOM-VER-1).
 * - GET /bom/ecns                  — paginated list (Sprint 6 W4-C 新)
 * - GET /bom/ecns/{id}             — single detail
 * - POST /bom/ecns                 — create DRAFT
 * - POST /bom/ecns/{id}/submit     — DRAFT → SUBMITTED
 * - POST /bom/ecns/{id}/approve    — SUBMITTED → APPROVED (cascade BomVersion)
 * - POST /bom/ecns/{id}/reject     — SUBMITTED → REJECTED
 * - POST /bom/ecns/calculate-impact — read-only impact analysis
 *
 * Permissions: write 需 production:read_write / rd:read_write / finance:read_write.
 *
 * 防呆 (per fool-proof-design.md):
 * - Rule 2 context: dialog header "ECN-{number} — {title} (影响 N 个 BomVersion)"
 * - Rule 3 reason dropdown (5 标准选项)
 * - Rule 5 dead-end: 无 ECN 工作流配置时 ElMessageBox.confirm → 跳工作流设计器
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Check, Close, View, DataAnalysis } from '@element-plus/icons-vue';
import {
  ecnApi,
  ECN_REASON_LABEL,
  ECN_STATUS_LABEL,
  type EngineeringChangeNotice,
  type EcnReason,
  type EcnStatus,
  type EcnImpactReport,
} from '@/api/ecn';

const router = useRouter();

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

// Sprint 6 W4-C — paginated list state
const listLoading = ref(false);
const listData = ref<EngineeringChangeNotice[]>([]);
const totalElements = ref(0);
const currentPage = ref(1);
const pageSize = ref(20);
const filterStatus = ref<EcnStatus | undefined>(undefined);
const filterReason = ref<EcnReason | undefined>(undefined);
const filterBomRecipeId = ref('');

// Sprint 6 W4-C — impact report dialog state
const impactDialogVisible = ref(false);
const impactReport = ref<EcnImpactReport | null>(null);
const impactLoading = ref(false);
const impactSourceEcn = ref<EngineeringChangeNotice | null>(null);

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
      inputValidator: (v: string): boolean | string => (v && v.trim().length > 0) || '原因不能为空',
    },
  ).catch((): null => null);
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

// ============== Sprint 6 W4-C — Paginated list ==============

async function loadList() {
  if (!factoryId.value) {
    ElMessage.warning('未识别 factoryId, 请重新登录');
    return;
  }
  listLoading.value = true;
  try {
    const r = await ecnApi.list(factoryId.value, {
      page: currentPage.value,
      size: pageSize.value,
      status: filterStatus.value,
      reason: filterReason.value,
      bomRecipeId: filterBomRecipeId.value || undefined,
    });
    if (r.success && r.data) {
      listData.value = r.data.content ?? [];
      totalElements.value = r.data.totalElements ?? 0;
    } else {
      listData.value = [];
      totalElements.value = 0;
    }
  } catch (e) {
    const err = e as { actionHint?: string } | undefined;
    if (!err?.actionHint) ElMessage.error('加载 ECN 列表失败');
  } finally {
    listLoading.value = false;
  }
}

function onPageChange(page: number) {
  currentPage.value = page;
  loadList();
}

function onSizeChange(size: number) {
  pageSize.value = size;
  currentPage.value = 1;
  loadList();
}

function resetFilters() {
  filterStatus.value = undefined;
  filterReason.value = undefined;
  filterBomRecipeId.value = '';
  currentPage.value = 1;
  loadList();
}

async function viewDetailFromList(row: EngineeringChangeNotice) {
  queryEcnId.value = row.id;
  ecnDetail.value = row;
}

// ============== Sprint 6 W4-C — Impact report dialog ==============

async function openImpactReport(ecn: EngineeringChangeNotice) {
  if (!factoryId.value) return;
  impactSourceEcn.value = ecn;
  impactLoading.value = true;
  impactDialogVisible.value = true;
  impactReport.value = null;
  try {
    const r = await ecnApi.calculateImpact(factoryId.value, ecn.bomRecipeId, ecn.changeContext ?? {});
    if (r.success && r.data) {
      impactReport.value = r.data;
    }
  } catch (e) {
    const err = e as { actionHint?: string } | undefined;
    if (!err?.actionHint) ElMessage.error('影响分析失败');
  } finally {
    impactLoading.value = false;
  }
}

/** 防呆 Rule 5 — workflow 未配置 → 跳工作流设计器, 不让用户卡死. */
function gotoWorkflowConfig() {
  ElMessageBox.confirm(
    'ECN 审批工作流未配置, 是否前去工作流设计器配置?',
    '工作流未配置',
    {
      confirmButtonText: '去配置',
      cancelButtonText: '取消',
      type: 'warning',
    },
  )
    .then(() => {
      router.push('/workflow/admin?entityType=ECN');
    })
    .catch(() => {
      // user cancelled - noop
    });
}

onMounted(() => {
  loadList();
});
</script>

<template>
  <div class="ecn-list">
    <el-card>
      <template #header>
        <div class="header">
          <span>工程变更通知 (ECN)</span>
          <el-tag size="small" type="success">Sprint 6 W4-C 分页+影响报告</el-tag>
        </div>
      </template>

      <!-- 分页 list 过滤栏 -->
      <el-form inline @submit.prevent="loadList">
        <el-form-item label="状态">
          <el-select v-model="filterStatus" placeholder="全部" clearable style="width: 140px">
            <el-option
              v-for="(label, val) in ECN_STATUS_LABEL"
              :key="val"
              :label="label"
              :value="val"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="原因">
          <el-select v-model="filterReason" placeholder="全部" clearable style="width: 140px">
            <el-option
              v-for="(label, val) in ECN_REASON_LABEL"
              :key="val"
              :label="label"
              :value="val"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="BOM Recipe">
          <el-input
            v-model="filterBomRecipeId"
            placeholder="可选过滤"
            clearable
            style="width: 220px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" :loading="listLoading" @click="loadList">
            查询
          </el-button>
          <el-button @click="resetFilters">重置</el-button>
          <el-button v-if="canWrite" :icon="Plus" type="success" @click="openCreate">
            新建 ECN
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 分页 list table -->
      <el-table v-loading="listLoading" :data="listData" stripe @row-click="viewDetailFromList">
        <el-table-column prop="ecnNumber" label="ECN 编号" width="180" />
        <el-table-column prop="bomRecipeId" label="BOM Recipe" min-width="180" show-overflow-tooltip />
        <el-table-column label="原因" width="120">
          <template #default="{ row }">
            <el-tag :type="reasonTag[row.reason as EcnReason]" size="small">
              {{ ECN_REASON_LABEL[row.reason as EcnReason] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag[row.status as EcnStatus]" size="small">
              {{ ECN_STATUS_LABEL[row.status as EcnStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="fromVersion" label="From" width="70" />
        <el-table-column prop="toVersion" label="To" width="70" />
        <el-table-column label="计划生效" width="120">
          <template #default="{ row }">{{ formatDate(row.effectiveDate) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link :icon="View" type="primary" @click.stop="viewDetailFromList(row)">
              详情
            </el-button>
            <el-button
              size="small"
              link
              :icon="DataAnalysis"
              type="warning"
              @click.stop="openImpactReport(row)"
            >
              影响报告
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无 ECN 数据">
            <el-button v-if="canWrite" type="primary" @click="openCreate">新建第一个 ECN</el-button>
          </el-empty>
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="totalElements"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="onPageChange"
        @size-change="onSizeChange"
        style="margin-top: 12px; justify-content: flex-end; display: flex"
      />
    </el-card>

    <!-- 单条详情查询区 (兼容旧用法) -->
    <el-card style="margin-top: 12px">
      <template #header>
        <span>按 ECN ID 查询单条详情</span>
      </template>
      <el-form inline @submit.prevent="loadDetail">
        <el-form-item label="ECN ID">
          <el-input v-model="queryEcnId" placeholder="UUID" clearable style="width: 320px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" :loading="loading" @click="loadDetail">
            查询
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

    <!-- Sprint 6 W4-C — Impact report dialog -->
    <el-dialog
      v-model="impactDialogVisible"
      :title="impactSourceEcn ? `影响报告 — ${impactSourceEcn.ecnNumber}` : '影响报告'"
      width="640px"
    >
      <div v-loading="impactLoading">
        <div v-if="impactSourceEcn" style="margin-bottom: 12px">
          <!-- 防呆 Rule 2: context (ECN 编号 + 标题 + BomRecipe + 影响 N 个) -->
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="ECN">
              <el-tag>{{ impactSourceEcn.ecnNumber }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="BOM Recipe">
              {{ impactSourceEcn.bomRecipeId }}
            </el-descriptions-item>
            <el-descriptions-item label="变更原因">
              <el-tag :type="reasonTag[impactSourceEcn.reason]" size="small">
                {{ ECN_REASON_LABEL[impactSourceEcn.reason] }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="From / To 版本">
              v{{ impactSourceEcn.fromVersion ?? '-' }} → v{{ impactSourceEcn.toVersion ?? '-' }}
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <el-divider>影响范围分析</el-divider>

        <div v-if="impactReport">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="影响物料数">
              {{ impactReport.impactedMaterialCount }}
            </el-descriptions-item>
            <el-descriptions-item label="影响成品 SKU 数">
              {{ impactReport.impactedSkuCount }}
            </el-descriptions-item>
            <el-descriptions-item v-if="impactReport.impactedPurchaseOrderCount != null" label="影响未结 PO 数">
              {{ impactReport.impactedPurchaseOrderCount }}
            </el-descriptions-item>
            <el-descriptions-item v-if="impactReport.estimatedCostDelta != null" label="预估成本差异">
              <span :class="{ 'text-danger': (impactReport.estimatedCostDelta || 0) > 0 }">
                {{ (impactReport.estimatedCostDelta || 0) >= 0 ? '+' : '' }}¥{{ impactReport.estimatedCostDelta }}
              </span>
            </el-descriptions-item>
          </el-descriptions>

          <div v-if="impactReport.details" style="margin-top: 12px">
            <el-collapse>
              <el-collapse-item title="完整 details JSON" name="details">
                <pre style="max-height: 200px; overflow: auto; font-size: 12px">{{ JSON.stringify(impactReport.details, null, 2) }}</pre>
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>

        <el-empty v-else-if="!impactLoading" description="无影响数据" />
      </div>

      <template #footer>
        <el-button @click="impactDialogVisible = false">关闭</el-button>
        <el-button v-if="canWrite" type="warning" @click="gotoWorkflowConfig">
          工作流未配置? 去配置
        </el-button>
      </template>
    </el-dialog>

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
