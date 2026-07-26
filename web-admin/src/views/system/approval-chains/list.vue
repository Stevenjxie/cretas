<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/modules/auth'
import { usePermissionStore } from '@/store/modules/permission'
import {
  getApprovalCutoverReadiness,
  getAllWorkflows,
  getDecisionTypesMetadata,
  type ApprovalCutoverReadinessDTO,
  type ApprovalWorkflowDTO,
  type DecisionTypeCategory,
  type DecisionTypeMetadataDTO,
} from '@/api/approvalWorkflow'
import { canConfigureUnifiedOaForRole } from './unifiedOaAccess'
import {
  buildApprovalCatalog,
  buildOaCanvasQuery,
  type ApprovalCatalogItem,
} from './approvalCatalog'

type ApprovalFilter = 'all' | 'enabled' | 'no-approval'

const DEPARTMENT_LABELS: Record<DecisionTypeCategory, string> = {
  PRODUCTION: '生产部',
  QUALITY_MATERIAL: '质量部',
  PURCHASE_SUPPLIER: '采购部',
  SALES_CUSTOMER: '销售部',
  FINANCE_VOUCHER: '财务部',
  HR_WAGE: '人事部',
  WAREHOUSE_TRANSFER: '仓储部',
  OTHER: '其他业务',
}

const authStore = useAuthStore()
const permissionStore = usePermissionStore()
const router = useRouter()
const factoryId = computed(() => authStore.factoryId)
const canConfigure = computed(() => canConfigureUnifiedOaForRole(permissionStore.currentRole))

const loading = ref(false)
const loadError = ref('')
const metadata = ref<DecisionTypeMetadataDTO[]>([])
const workflows = ref<ApprovalWorkflowDTO[]>([])
const readiness = ref<ApprovalCutoverReadinessDTO[]>([])
const keyword = ref('')
const approvalFilter = ref<ApprovalFilter>('all')

const catalog = computed(() => buildApprovalCatalog(
  metadata.value,
  workflows.value,
  readiness.value,
))

const filteredCatalog = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLocaleLowerCase()
  return catalog.value.filter((item) => {
    const matchesKeyword = !normalizedKeyword
      || item.chineseName.toLocaleLowerCase().includes(normalizedKeyword)
      || item.description.toLocaleLowerCase().includes(normalizedKeyword)
      || DEPARTMENT_LABELS[item.category].toLocaleLowerCase().includes(normalizedKeyword)
    const matchesStatus = approvalFilter.value === 'all'
      || (approvalFilter.value === 'enabled'
        ? item.approvalEnabled
        : !item.approvalEnabled && item.status !== 'legacy-migration-required')
    return matchesKeyword && matchesStatus
  })
})

const enabledCount = computed(() => catalog.value.filter((item) => item.approvalEnabled).length)
const migrationCount = computed(() => catalog.value.filter(
  (item) => item.status === 'legacy-migration-required',
).length)
const noApprovalCount = computed(() => catalog.value.filter(
  (item) => !item.approvalEnabled && item.status !== 'legacy-migration-required',
).length)
const draftCount = computed(() => catalog.value.filter((item) => item.hasDraft).length)

async function loadData() {
  if (!factoryId.value) return
  loading.value = true
  loadError.value = ''
  try {
    const [metadataResponse, workflowResponse, readinessResponse] = await Promise.all([
      getDecisionTypesMetadata(factoryId.value),
      getAllWorkflows(factoryId.value),
      getApprovalCutoverReadiness(factoryId.value),
    ])
    if (!metadataResponse.success || !Array.isArray(metadataResponse.data)) {
      throw new Error(metadataResponse.message || '审批业务加载失败')
    }
    if (!workflowResponse.success || !Array.isArray(workflowResponse.data)) {
      throw new Error(workflowResponse.message || '审批流程加载失败')
    }
    if (!readinessResponse.success || !Array.isArray(readinessResponse.data)) {
      throw new Error(readinessResponse.message || '审批切换状态加载失败')
    }
    metadata.value = metadataResponse.data
    workflows.value = workflowResponse.data
    readiness.value = readinessResponse.data
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error)
  } finally {
    loading.value = false
  }
}

function canvasHref(item: ApprovalCatalogItem) {
  return router.resolve({
    name: 'CanvasEditor',
    query: buildOaCanvasQuery(item),
  }).href
}

function departmentLabel(category: DecisionTypeCategory) {
  return DEPARTMENT_LABELS[category]
}

function versionSummary(item: ApprovalCatalogItem) {
  const parts: string[] = []
  if (item.activeWorkflowVersion) parts.push(`运行 v${item.activeWorkflowVersion}`)
  if (item.draftWorkflowVersion) parts.push(`草稿 v${item.draftWorkflowVersion}`)
  if (!parts.length && item.workflowCount) parts.push('仅保留历史版本')
  return parts.join(' · ') || '直接流转'
}

function formatUpdatedAt(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

function actionLabel(item: ApprovalCatalogItem) {
  if (item.status === 'legacy-migration-required') return '迁移到审批画布'
  return item.approvalEnabled || item.hasDraft ? '配置审批' : '查看设置'
}

function statusLabel(item: ApprovalCatalogItem) {
  if (item.status === 'legacy-migration-required') return '旧配置待迁移'
  return item.approvalEnabled ? '审批已启用' : '无需审批'
}

function statusTagType(item: ApprovalCatalogItem) {
  if (item.status === 'legacy-migration-required') return 'warning'
  return item.approvalEnabled ? 'success' : 'info'
}

onMounted(loadData)
</script>

<template>
  <div class="approval-business-page">
    <section class="page-heading">
      <div>
        <h1>审批业务</h1>
        <p>选择要配置的业务。每个业务使用独立审批画布，不会混用其他部门的流程。</p>
      </div>
      <el-button :loading="loading" @click="loadData">刷新</el-button>
    </section>

    <el-alert
      v-if="loadError"
      class="load-error"
      type="error"
      :closable="false"
      title="审批业务加载失败"
    >
      <template #default>
        <span>{{ loadError }}</span>
        <el-button link type="primary" @click="loadData">重新加载</el-button>
      </template>
    </el-alert>

    <section class="summary-grid" aria-label="审批业务概况">
      <div class="summary-card">
        <span>审批业务</span>
        <strong>{{ catalog.length }}</strong>
      </div>
      <div class="summary-card enabled">
        <span>审批已启用</span>
        <strong>{{ enabledCount }}</strong>
      </div>
      <div class="summary-card no-approval">
        <span>无需审批</span>
        <strong>{{ noApprovalCount }}</strong>
      </div>
      <div class="summary-card draft">
        <span>待发布草稿</span>
        <strong>{{ draftCount }}</strong>
      </div>
      <div v-if="migrationCount" class="summary-card migration">
        <span>旧配置待迁移</span>
        <strong>{{ migrationCount }}</strong>
      </div>
    </section>

    <el-card shadow="never" class="business-card">
      <template #header>
        <div class="card-heading">
          <div>
            <h2>选择审批业务</h2>
            <span>共 {{ catalog.length }} 项，当前显示 {{ filteredCatalog.length }} 项</span>
          </div>
          <span class="running-note">在途审批继续使用原运行版本</span>
        </div>
      </template>

      <div class="filters" role="search">
        <el-input
          v-model="keyword"
          clearable
          placeholder="搜索部门或审批业务…"
          aria-label="搜索部门或审批业务"
          name="approval-business-search"
          autocomplete="off"
        />
        <div class="status-switch" aria-label="按审批状态筛选">
          <button
            v-for="option in [
              { value: 'all', label: '全部' },
              { value: 'enabled', label: '审批已启用' },
              { value: 'no-approval', label: '无需审批' },
            ]"
            :key="option.value"
            type="button"
            :class="{ active: approvalFilter === option.value }"
            @click="approvalFilter = option.value as ApprovalFilter"
          >
            {{ option.label }}
          </button>
        </div>
      </div>

      <el-table
        v-loading="loading"
        :data="filteredCatalog"
        border
        row-key="decisionType"
        class="business-table"
        empty-text="没有符合条件的审批业务"
      >
        <el-table-column prop="category" label="部门" width="140" sortable>
          <template #default="{ row }">
            <strong class="department-name">{{ departmentLabel(row.category) }}</strong>
          </template>
        </el-table-column>
        <el-table-column prop="chineseName" label="审批业务" min-width="260" sortable>
          <template #default="{ row }">
            <div class="business-name">
              <strong>{{ row.chineseName }}</strong>
              <span>{{ row.description }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="审批状态" width="140" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row)" effect="light">
              {{ statusLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本状态" min-width="210">
          <template #default="{ row }">
            <div class="version-cell">
              <strong>{{ versionSummary(row) }}</strong>
              <span v-if="row.status === 'legacy-migration-required'">
                发布审批画布后再停用旧配置，切换期间不会漏审
              </span>
              <span v-else-if="row.hasDraft">草稿修改不会影响当前运行流程</span>
              <span v-else-if="!row.approvalEnabled">业务提交后直接进入下一环节</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最后更新" width="170" sortable>
          <template #default="{ row }">{{ formatUpdatedAt(row.latestUpdatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right" align="center">
          <template #default="{ row }">
            <el-button v-if="!canConfigure" type="primary" disabled>
              {{ actionLabel(row) }}
            </el-button>
            <el-button
              v-else
              tag="a"
              type="primary"
              :href="canvasHref(row)"
              :aria-label="`${row.chineseName}：${actionLabel(row)}`"
            >
              {{ actionLabel(row) }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="continuity-note">
        <strong>无缝衔接</strong>
        <span>
          新画布只影响发布后的新审批；在途单据继续固定原版本。旧配置未迁移时系统会阻止新单据静默放行。
        </span>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.approval-business-page {
  min-height: 100%;
  padding: 20px;
  background: #f4f6f9;
  color: #1a2332;
}

.page-heading,
.card-heading,
.filters,
.continuity-note {
  display: flex;
  align-items: center;
}

.page-heading {
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 16px;
}

h1,
h2,
p {
  margin: 0;
}

h1 {
  font-size: 24px;
  line-height: 32px;
}

.page-heading p {
  margin-top: 6px;
  color: #7a8599;
  font-size: 14px;
}

.load-error,
.summary-grid {
  margin-bottom: 16px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.summary-card {
  min-height: 92px;
  padding: 16px;
  border: 1px solid #edf2f7;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 3px 10px rgb(31 62 92 / 5%);
}

.summary-card span {
  color: #7a8599;
  font-size: 13px;
}

.summary-card strong {
  display: block;
  margin-top: 8px;
  font-size: 28px;
  font-variant-numeric: tabular-nums;
}

.summary-card.enabled { border-top: 3px solid #67c23a; }
.summary-card.no-approval { border-top: 3px solid #a8b1bf; }
.summary-card.draft { border-top: 3px solid #e6a23c; }
.summary-card.migration { border-top: 3px solid #d97706; }

.business-card {
  border-color: #edf2f7;
  border-radius: 10px;
}

.card-heading {
  justify-content: space-between;
  gap: 16px;
}

.card-heading h2 {
  font-size: 18px;
}

.card-heading span,
.running-note {
  color: #7a8599;
  font-size: 13px;
}

.filters {
  gap: 12px;
  justify-content: space-between;
  margin-bottom: 14px;
}

.filters .el-input {
  width: min(420px, 48%);
}

.status-switch {
  display: inline-flex;
  padding: 3px;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  background: #f4f6f9;
}

.status-switch button {
  min-height: 32px;
  padding: 0 14px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #5d6879;
  cursor: pointer;
}

.status-switch button.active {
  background: #fff;
  color: #1b65a8;
  font-weight: 600;
  box-shadow: 0 1px 4px rgb(31 62 92 / 12%);
}

.status-switch button:focus-visible {
  outline: 2px solid #409eff;
  outline-offset: 1px;
}

.business-table :deep(th.el-table__cell) {
  height: 44px;
  background: #f7f9fc;
  color: #5d6879;
}

.business-table :deep(td.el-table__cell) {
  height: 62px;
}

.department-name {
  color: #1a2332;
}

.business-name,
.version-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.business-name strong,
.version-cell strong {
  font-weight: 600;
}

.business-name span,
.version-cell span {
  overflow: hidden;
  color: #7a8599;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.continuity-note {
  gap: 10px;
  margin-top: 14px;
  padding: 12px 14px;
  border-left: 3px solid #1b65a8;
  border-radius: 8px;
  background: #f0f7ff;
  color: #5d6879;
  font-size: 13px;
}

.continuity-note strong {
  flex: 0 0 auto;
  color: #1a2332;
}

@media (max-width: 1080px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .filters {
    align-items: stretch;
    flex-direction: column;
  }

  .filters .el-input {
    width: 100%;
  }

  .status-switch {
    align-self: flex-start;
  }
}

@media (max-width: 720px) {
  .approval-business-page {
    padding: 12px;
  }

  .summary-grid {
    grid-template-columns: 1fr;
  }
}
</style>
