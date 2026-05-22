<!--
  IndicatorVersionsPane — Tab 6: 版本历史 (分页 IndicatorVersion)。

  Append-only audit log, 不允许删除 (entity 标了 @PreRemove guard)。
  支持: 时序倒序浏览 + 点行查看 computeSource 详细信息。
-->
<template>
  <div class="pane">
    <el-alert
      type="info"
      :closable="false"
      title="版本快照"
      description="每次重算落库一行 (append-only)。可用于 趋势分析 / 月报 / 合规审计。"
      show-icon
      style="margin-bottom: 12px"
    />

    <el-table
      :data="versions"
      v-loading="loading"
      border
      style="width: 100%"
    >
      <el-table-column prop="computedAt" label="计算时刻" min-width="170">
        <template #default="{ row }">
          {{ formatTs(row.computedAt) }}
        </template>
      </el-table-column>
      <el-table-column label="业务时间窗" min-width="220">
        <template #default="{ row }">
          {{ row.periodStart }} ~ {{ row.periodEnd }}
        </template>
      </el-table-column>
      <el-table-column prop="value" label="值" min-width="120">
        <template #default="{ row }">
          <strong>{{ row.value }}</strong>
          <span class="unit">{{ detail.unit || '' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="alertLevel" label="告警" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.alertLevel" :type="levelType(row.alertLevel)" size="small">
            {{ levelLabel(row.alertLevel) }}
          </el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="computeSource" label="计算源" min-width="240" show-overflow-tooltip />
    </el-table>

    <el-pagination
      v-if="totalElements > 0"
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :total="totalElements"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 12px; justify-content: flex-end"
      @size-change="loadData"
      @current-change="loadData"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import {
  indicatorsApi,
  type IndicatorDetail,
  type IndicatorVersion,
  type AlertLevel,
} from '@/api/canvasIndicators'

interface Props {
  factoryId: string
  detail: IndicatorDetail
}
const props = defineProps<Props>()

const versions = ref<IndicatorVersion[]>([])
const totalElements = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const loading = ref(false)

async function loadData() {
  loading.value = true
  try {
    const resp = await indicatorsApi.listVersions(
      props.factoryId,
      props.detail.id,
      currentPage.value - 1,
      pageSize.value,
    )
    const page = resp.data
    versions.value = (page?.content ?? []) as IndicatorVersion[]
    totalElements.value = page?.totalElements ?? 0
  } catch (err) {
    console.error('versions failed', err)
  } finally {
    loading.value = false
  }
}

function levelLabel(l: AlertLevel) {
  return l === 'GREEN' ? '正常' : l === 'YELLOW' ? '黄色预警' : '红色预警'
}

function levelType(l: AlertLevel): 'success' | 'warning' | 'danger' {
  return l === 'GREEN' ? 'success' : l === 'YELLOW' ? 'warning' : 'danger'
}

function formatTs(ts: string): string {
  try {
    return new Date(ts).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return ts
  }
}

watch(() => props.detail.id, () => {
  currentPage.value = 1
  loadData()
})

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.pane {
  padding: 8px 0;
}

.unit {
  margin-left: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.muted {
  color: var(--el-text-color-disabled);
}
</style>
