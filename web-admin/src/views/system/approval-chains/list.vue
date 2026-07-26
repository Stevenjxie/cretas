<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Refresh, Search, Setting } from '@element-plus/icons-vue'
import { useAuthStore } from '@/store/modules/auth'
import { usePermissionStore } from '@/store/modules/permission'
import { get } from '@/api/request'
import {
  getAllWorkflows,
  getDecisionTypesMetadata,
  type ApprovalWorkflowDTO,
  type DecisionTypeCategory,
  type DecisionTypeMetadataDTO,
} from '@/api/approvalWorkflow'
import { canConfigureUnifiedOaForRole } from './unifiedOaAccess'
import {
  buildApprovalCatalog,
  buildOaCanvasQuery,
  type ApprovalCatalogItem,
  type ApprovalCatalogStatus,
  type LegacyApprovalChainSummary,
} from './approvalCatalog'

interface LegacyChainRow extends LegacyApprovalChainSummary {
  id?: string
  name?: string
}

const CATEGORY_LABELS: Record<DecisionTypeCategory, string> = {
  PRODUCTION: '生产 / 工序',
  QUALITY_MATERIAL: '质检 / 物料',
  PURCHASE_SUPPLIER: '采购 / 供应商',
  SALES_CUSTOMER: '销售 / 客户',
  FINANCE_VOUCHER: '财务 / 凭证',
  HR_WAGE: '人事 / 工资',
  WAREHOUSE_TRANSFER: '仓储 / 调拨',
  OTHER: '其他',
}

const STATUS_META: Record<ApprovalCatalogStatus, {
  label: string
  type: 'success' | 'warning' | 'info' | 'danger'
}> = {
  active: { label: '运行中', type: 'success' },
  'active-with-draft': { label: '运行中 · 有草稿', type: 'warning' },
  draft: { label: '有草稿', type: 'warning' },
  'published-disabled': { label: '已发布未启用', type: 'info' },
  archived: { label: '仅历史版本', type: 'info' },
  unconfigured: { label: '待配置', type: 'danger' },
}

const authStore = useAuthStore()
const permissionStore = usePermissionStore()
const router = useRouter()
const factoryId = computed(() => authStore.factoryId)
const canConfigure = computed(() => (
  canConfigureUnifiedOaForRole(permissionStore.currentRole)
))

const loading = ref(false)
const loadError = ref('')
const metadata = ref<DecisionTypeMetadataDTO[]>([])
const workflows = ref<ApprovalWorkflowDTO[]>([])
const legacyChains = ref<LegacyChainRow[]>([])
const keyword = ref('')
const categoryFilter = ref<DecisionTypeCategory | ''>('')
const statusFilter = ref<ApprovalCatalogStatus | ''>('')
const wiredFilter = ref<'all' | 'wired' | 'unwired'>('all')

const catalog = computed(() => buildApprovalCatalog(
  metadata.value,
  workflows.value,
  legacyChains.value,
))

const filteredCatalog = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLocaleLowerCase()
  return catalog.value.filter((item) => {
    const matchesKeyword = !normalizedKeyword
      || item.chineseName.toLocaleLowerCase().includes(normalizedKeyword)
      || item.decisionType.toLocaleLowerCase().includes(normalizedKeyword)
      || item.description.toLocaleLowerCase().includes(normalizedKeyword)
    const matchesCategory = !categoryFilter.value || item.category === categoryFilter.value
    const matchesStatus = !statusFilter.value || item.status === statusFilter.value
    const matchesWired = wiredFilter.value === 'all'
      || (wiredFilter.value === 'wired' ? item.wired : !item.wired)
    return matchesKeyword && matchesCategory && matchesStatus && matchesWired
  })
})

const activeCount = computed(() => catalog.value.filter(
  (item) => item.status === 'active' || item.status === 'active-with-draft',
).length)
const draftCount = computed(() => catalog.value.filter(
  (item) => item.status === 'draft' || item.status === 'active-with-draft',
).length)
const unconfiguredCount = computed(() => (
  catalog.value.filter((item) => item.status === 'unconfigured').length
))
const legacyCount = computed(() => legacyChains.value.length)

async function loadData() {
  if (!factoryId.value) return
  loading.value = true
  loadError.value = ''
  try {
    const [metadataResponse, workflowResponse, legacyResponse] = await Promise.all([
      getDecisionTypesMetadata(factoryId.value),
      getAllWorkflows(factoryId.value),
      get<LegacyChainRow[]>(`/${factoryId.value}/approval-chains`),
    ])
    if (!metadataResponse.success || !Array.isArray(metadataResponse.data)) {
      throw new Error(metadataResponse.message || '审批业务目录加载失败')
    }
    if (!workflowResponse.success || !Array.isArray(workflowResponse.data)) {
      throw new Error(workflowResponse.message || 'OA 画布流程加载失败')
    }
    metadata.value = metadataResponse.data
    workflows.value = workflowResponse.data
    legacyChains.value = legacyResponse.success && Array.isArray(legacyResponse.data)
      ? legacyResponse.data
      : []
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

function statusLabel(status: ApprovalCatalogStatus) {
  return STATUS_META[status].label
}

function categoryLabel(category: DecisionTypeCategory) {
  return CATEGORY_LABELS[category] ?? category
}

function statusType(status: ApprovalCatalogStatus) {
  return STATUS_META[status].type
}

function resetFilters() {
  keyword.value = ''
  categoryFilter.value = ''
  statusFilter.value = ''
  wiredFilter.value = 'all'
}

onMounted(loadData)
</script>

<template>
  <div class="approval-catalog-page">
    <section class="page-heading">
      <div>
        <div class="eyebrow">统一 OA</div>
        <h1>审批流程配置</h1>
        <p>
          按业务进入对应的 OA 画布。系统会锁定审批业务并自动打开当前流程，
          无需在大型设计器中再次查找。
        </p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
    </section>

    <el-alert
      v-if="loadError"
      class="load-error"
      type="error"
      :closable="false"
      show-icon
      title="审批配置加载失败"
    >
      <template #default>
        <span>{{ loadError }}</span>
        <el-button link type="primary" @click="loadData">重新加载</el-button>
      </template>
    </el-alert>

    <section class="summary-grid" aria-label="审批配置概况">
      <div class="summary-card">
        <span>运行中的画布</span>
        <strong>{{ activeCount }}</strong>
      </div>
      <div class="summary-card">
        <span>待发布草稿</span>
        <strong>{{ draftCount }}</strong>
      </div>
      <div class="summary-card">
        <span>待配置业务</span>
        <strong>{{ unconfiguredCount }}</strong>
      </div>
      <div class="summary-card legacy">
        <span>旧 OA 兼容记录</span>
        <strong>{{ legacyCount }}</strong>
        <small>只读保留，不影响在途审批</small>
      </div>
    </section>

    <el-card shadow="never" class="catalog-card">
      <template #header>
        <div class="card-heading">
          <div>
            <h2>审批业务目录</h2>
            <span>共 {{ catalog.length }} 类，当前显示 {{ filteredCatalog.length }} 类</span>
          </div>
          <el-tag type="success" effect="plain">Canvas 为新配置入口</el-tag>
        </div>
      </template>

      <div class="filters" role="search">
        <el-input
          v-model="keyword"
          clearable
          :prefix-icon="Search"
          placeholder="搜索业务名称或编码…"
          aria-label="搜索审批业务"
          name="approval-business-search"
          autocomplete="off"
        />
        <el-select
          v-model="categoryFilter"
          clearable
          placeholder="全部业务分类"
          aria-label="按业务分类筛选"
        >
          <el-option
            v-for="(label, value) in CATEGORY_LABELS"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <el-select
          v-model="statusFilter"
          clearable
          placeholder="全部配置状态"
          aria-label="按配置状态筛选"
        >
          <el-option
            v-for="(meta, value) in STATUS_META"
            :key="value"
            :label="meta.label"
            :value="value"
          />
        </el-select>
        <el-select
          v-model="wiredFilter"
          placeholder="全部接入状态"
          aria-label="按业务接入状态筛选"
        >
          <el-option label="全部接入状态" value="all" />
          <el-option label="已接入业务" value="wired" />
          <el-option label="尚未接入业务" value="unwired" />
        </el-select>
        <el-button @click="resetFilters">重置</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="filteredCatalog"
        border
        stripe
        row-key="decisionType"
        class="catalog-table"
        empty-text="没有符合条件的审批业务"
      >
        <el-table-column prop="chineseName" label="审批业务" min-width="250" sortable>
          <template #default="{ row }">
            <div class="business-cell">
              <strong>{{ row.chineseName }}</strong>
              <span>{{ row.decisionType }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="业务分类" width="150" sortable>
          <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="画布状态" width="145" sortable>
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前 OA 流程" min-width="240">
          <template #default="{ row }">
            <div v-if="row.preferredWorkflowId" class="workflow-cell">
              <strong>{{ row.preferredWorkflowName }}</strong>
              <span>v{{ row.preferredWorkflowVersion }} · 共 {{ row.workflowCount }} 个版本</span>
            </div>
            <span v-else class="muted">尚未创建，进入后新建草稿</span>
          </template>
        </el-table-column>
        <el-table-column prop="wired" label="业务接入" width="120" sortable>
          <template #default="{ row }">
            <el-tag :type="row.wired ? 'success' : 'info'" effect="plain">
              {{ row.wired ? '已接入' : '尚未接入' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="legacyCount" label="旧 OA 兼容" width="130" sortable>
          <template #default="{ row }">
            <span v-if="row.legacyCount">{{ row.legacyCount }} 条（只读）</span>
            <span v-else class="muted">无</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="!canConfigure"
              type="primary"
              :icon="row.preferredWorkflowId ? ArrowRight : Setting"
              disabled
            >
              {{ row.preferredWorkflowId ? '进入画布' : '配置画布' }}
            </el-button>
            <el-button
              v-else
              tag="a"
              type="primary"
              :icon="row.preferredWorkflowId ? ArrowRight : Setting"
              :href="canvasHref(row)"
              :aria-label="`${row.chineseName}：${row.preferredWorkflowId ? '进入画布' : '配置画布'}`"
            >
              {{ row.preferredWorkflowId ? '进入画布' : '配置画布' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="compatibility-note">
        <strong>无缝衔接说明</strong>
        <span>
          新配置统一进入 Canvas；旧 OA 配置继续只读保留，用于历史和回退。
          本页面不会修改或删除旧配置，也不会改变正在审批中的实例。
        </span>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.approval-catalog-page {
  padding: 20px;
  background: var(--el-bg-color-page);
  min-height: 100%;
}

.page-heading,
.card-heading,
.filters,
.compatibility-note {
  display: flex;
  align-items: center;
}

.page-heading {
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 16px;
}

.eyebrow {
  margin-bottom: 4px;
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

h1,
h2,
p {
  margin: 0;
}

h1 {
  color: var(--el-text-color-primary);
  font-size: 24px;
  line-height: 32px;
}

.page-heading p {
  max-width: 760px;
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  line-height: 22px;
}

.load-error {
  margin-bottom: 16px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.summary-card {
  min-height: 92px;
  padding: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: var(--el-bg-color);
}

.summary-card span,
.summary-card small {
  display: block;
  color: var(--el-text-color-secondary);
}

.summary-card strong {
  display: block;
  margin-top: 6px;
  color: var(--el-text-color-primary);
  font-size: 26px;
  font-variant-numeric: tabular-nums;
  line-height: 32px;
}

.summary-card small {
  margin-top: 2px;
  font-size: 12px;
}

.summary-card.legacy {
  border-style: dashed;
}

.catalog-card {
  border-radius: 10px;
}

.card-heading {
  justify-content: space-between;
  gap: 16px;
}

.card-heading h2 {
  color: var(--el-text-color-primary);
  font-size: 18px;
}

.card-heading span {
  display: block;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.filters {
  gap: 10px;
  margin-bottom: 14px;
}

.filters .el-input {
  width: 280px;
}

.filters .el-select {
  width: 180px;
}

.catalog-table :deep(th.el-table__cell) {
  height: 44px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
}

.catalog-table :deep(td.el-table__cell) {
  height: 58px;
}

.business-cell,
.workflow-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.business-cell strong,
.workflow-cell strong {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.business-cell span,
.workflow-cell span,
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.compatibility-note {
  gap: 10px;
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 20px;
}

.compatibility-note strong {
  flex: 0 0 auto;
  color: var(--el-text-color-primary);
}

@media (max-width: 1100px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .filters {
    flex-wrap: wrap;
  }
}

@media (max-width: 720px) {
  .approval-catalog-page {
    padding: 12px;
  }

  .page-heading {
    align-items: flex-start;
  }

  .summary-grid {
    grid-template-columns: 1fr;
  }

  .filters .el-input,
  .filters .el-select {
    width: 100%;
  }
}
</style>
