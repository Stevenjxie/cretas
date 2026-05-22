<!--
  IndicatorCenterEditor — Canvas Phase A subagent #3 (2026-05-21).

  包装 Phase 1 已 ship 的 7 indicator + 2 lineage entity, 提供 8 sub-tab UI:
    1. 指标定义列表 + 分类筛选
    2. 指标编辑 (CRUD + 公式 dry-run)
    3. 版本历史 (分页 IndicatorVersion)
    4. 趋势 (时间窗 measurements → ECharts)
    5. 阈值配置 (GREEN/YELLOW/RED + 4 operator)
    6. 计算策略 (FORMULA/PYTHON_ENDPOINT/JPA_QUERY)
    7. 指标依赖图 (vue-flow DAG)
    8. 批次溯源 (lineage closure 上游/下游)

  Backend: /api/mobile/{factoryId}/canvas-indicators
  Spec: docs/audits/2026-05-21-liushanmen-canvas-process-design.md §P0-3
-->
<template>
  <div class="indicator-center-editor">
    <!-- Header toolbar -->
    <el-card shadow="never" class="header-card">
      <div class="header-row">
        <div class="header-left">
          <h3 class="hub-title">指标中心</h3>
          <el-tag type="info" size="small">{{ indicators.length }} 个指标</el-tag>
          <el-tag type="success" size="small" v-if="lastRefreshTs">
            最近刷新 {{ lastRefreshTs }}
          </el-tag>
        </div>
        <div class="header-actions">
          <el-select
            v-model="filterCategory"
            placeholder="全部分类"
            clearable
            style="width: 160px"
            @change="loadList"
          >
            <el-option
              v-for="c in INDICATOR_CATEGORIES"
              :key="c"
              :label="INDICATOR_CATEGORY_LABELS[c]"
              :value="c"
            />
          </el-select>
          <el-button :icon="Refresh" @click="loadList">刷新</el-button>
          <el-button type="primary" :icon="Plus" @click="onCreateClick">新建指标</el-button>
        </div>
      </div>
    </el-card>

    <!-- Main split: left list + right tabs -->
    <div class="hub-body">
      <!-- Left: indicator list -->
      <aside class="indicator-list">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索 code / 名称"
          clearable
          :prefix-icon="Search"
          style="margin-bottom: 8px"
        />
        <el-scrollbar height="calc(100vh - 280px)">
          <el-empty
            v-if="filteredIndicators.length === 0"
            description="暂无指标"
            :image-size="80"
          />
          <div
            v-for="ind in filteredIndicators"
            :key="ind.id"
            class="indicator-item"
            :class="{ active: selectedId === ind.id }"
            @click="onSelect(ind.id)"
          >
            <div class="indicator-row1">
              <span class="indicator-name">{{ ind.name }}</span>
              <el-tag size="small" :type="categoryTagType(ind.category)">
                {{ INDICATOR_CATEGORY_LABELS[ind.category] }}
              </el-tag>
            </div>
            <div class="indicator-row2">
              <span class="indicator-code">{{ ind.code }}</span>
              <span class="indicator-value" v-if="ind.lastValue !== null && ind.lastValue !== undefined">
                {{ ind.lastValue }} {{ ind.unit || '' }}
              </span>
            </div>
          </div>
        </el-scrollbar>
      </aside>

      <!-- Right: 8 sub-tab area -->
      <main class="indicator-detail">
        <el-empty v-if="!selectedId" description="请在左侧选择一个指标" />

        <template v-else>
          <el-tabs v-model="activeTab" class="detail-tabs">
            <el-tab-pane label="基本信息" name="overview">
              <IndicatorOverviewPane
                v-if="selectedDetail"
                :factory-id="factoryId"
                :detail="selectedDetail"
                @updated="onIndicatorUpdated"
                @deactivated="onIndicatorDeactivated"
              />
            </el-tab-pane>
            <el-tab-pane label="阈值与告警" name="thresholds">
              <IndicatorThresholdsPane
                v-if="selectedDetail"
                :detail="selectedDetail"
              />
            </el-tab-pane>
            <el-tab-pane label="计算策略" name="computations">
              <IndicatorComputationsPane
                v-if="selectedDetail"
                :detail="selectedDetail"
              />
            </el-tab-pane>
            <el-tab-pane label="公式测试" name="evaluate">
              <IndicatorEvaluatePane
                v-if="selectedDetail"
                :factory-id="factoryId"
                :detail="selectedDetail"
              />
            </el-tab-pane>
            <el-tab-pane label="趋势" name="trend">
              <IndicatorTrendPane
                v-if="selectedDetail"
                :factory-id="factoryId"
                :detail="selectedDetail"
              />
            </el-tab-pane>
            <el-tab-pane label="版本历史" name="versions">
              <IndicatorVersionsPane
                v-if="selectedDetail"
                :factory-id="factoryId"
                :detail="selectedDetail"
              />
            </el-tab-pane>
            <el-tab-pane label="依赖图" name="lineage">
              <IndicatorLineagePane
                v-if="selectedDetail"
                :factory-id="factoryId"
                :detail="selectedDetail"
              />
            </el-tab-pane>
            <el-tab-pane label="批次溯源" name="batch-lineage">
              <BatchLineagePane :factory-id="factoryId" />
            </el-tab-pane>
          </el-tabs>
        </template>
      </main>
    </div>

    <!-- Create dialog -->
    <IndicatorCreateDialog
      v-model="createDialogVisible"
      :factory-id="factoryId"
      @created="onIndicatorCreated"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  indicatorsApi,
  type Indicator,
  type IndicatorDetail,
  type IndicatorCategory,
  INDICATOR_CATEGORIES,
  INDICATOR_CATEGORY_LABELS,
} from '@/api/canvasIndicators'

import IndicatorOverviewPane from './panes/IndicatorOverviewPane.vue'
import IndicatorThresholdsPane from './panes/IndicatorThresholdsPane.vue'
import IndicatorComputationsPane from './panes/IndicatorComputationsPane.vue'
import IndicatorEvaluatePane from './panes/IndicatorEvaluatePane.vue'
import IndicatorTrendPane from './panes/IndicatorTrendPane.vue'
import IndicatorVersionsPane from './panes/IndicatorVersionsPane.vue'
import IndicatorLineagePane from './panes/IndicatorLineagePane.vue'
import BatchLineagePane from './panes/BatchLineagePane.vue'
import IndicatorCreateDialog from './panes/IndicatorCreateDialog.vue'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const indicators = ref<Indicator[]>([])
const selectedId = ref<string | null>(null)
const selectedDetail = ref<IndicatorDetail | null>(null)
const activeTab = ref('overview')
const filterCategory = ref<IndicatorCategory | ''>('')
const searchKeyword = ref('')
const createDialogVisible = ref(false)
const lastRefreshTs = ref<string>('')

const filteredIndicators = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  if (!kw) return indicators.value
  return indicators.value.filter(
    (i) =>
      i.code.toLowerCase().includes(kw) ||
      i.name.toLowerCase().includes(kw),
  )
})

async function loadList() {
  try {
    const resp = await indicatorsApi.list(
      props.factoryId,
      filterCategory.value || undefined,
    )
    indicators.value = (resp.data ?? []) as Indicator[]
    lastRefreshTs.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    // 如果当前选中的指标不在新结果里, 清除选中
    if (selectedId.value && !indicators.value.find((i) => i.id === selectedId.value)) {
      selectedId.value = null
      selectedDetail.value = null
    }
  } catch (err) {
    // request.ts 已经弹错 toast, 此处无需重复
    console.error('loadList failed', err)
  }
}

async function onSelect(id: string) {
  selectedId.value = id
  selectedDetail.value = null
  try {
    const resp = await indicatorsApi.detail(props.factoryId, id)
    selectedDetail.value = (resp.data ?? null) as IndicatorDetail | null
  } catch (err) {
    console.error('detail failed', err)
  }
}

function onCreateClick() {
  createDialogVisible.value = true
}

async function onIndicatorCreated(created: Indicator) {
  ElMessage.success(`指标 ${created.name} 创建成功`)
  createDialogVisible.value = false
  await loadList()
  await onSelect(created.id)
}

async function onIndicatorUpdated() {
  ElMessage.success('指标已更新')
  await loadList()
  if (selectedId.value) await onSelect(selectedId.value)
}

async function onIndicatorDeactivated() {
  ElMessage.success('指标已停用')
  selectedId.value = null
  selectedDetail.value = null
  await loadList()
}

function categoryTagType(category: string): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  switch (category) {
    case 'FACTORY': return 'primary'
    case 'RESTAURANT': return 'warning'
    case 'QUALITY': return 'success'
    case 'FINANCE': return 'info'
    case 'FOOD_SAFETY': return 'danger'
    default: return 'info'
  }
}

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.indicator-center-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  height: 100%;
}

.header-card {
  flex: 0 0 auto;
}

.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.hub-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.hub-body {
  display: flex;
  gap: 12px;
  flex: 1 1 auto;
  min-height: 0;
}

.indicator-list {
  flex: 0 0 280px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 12px;
  overflow: hidden;
}

.indicator-item {
  padding: 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
  transition: background-color 0.15s;
}

.indicator-item:hover {
  background-color: var(--el-fill-color-light);
}

.indicator-item.active {
  background-color: var(--el-color-primary-light-9);
  border-left: 3px solid var(--el-color-primary);
}

.indicator-row1 {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.indicator-name {
  font-weight: 500;
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.indicator-row2 {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.indicator-code {
  font-family: 'Consolas', monospace;
  font-size: 11px;
}

.indicator-value {
  font-weight: 500;
  color: var(--el-color-primary);
}

.indicator-detail {
  flex: 1 1 auto;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 12px;
  overflow: auto;
  min-width: 0;
}

.detail-tabs {
  height: 100%;
}
</style>
