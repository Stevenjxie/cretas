<!--
  HaccpMonitoringList — Sub-tab 2 / 8.

  HACCP 监控记录只读视图. 默认显示偏离记录 (deviations_only=true),
  food safety triage 用. 可按 batchNumber 过滤查特定批次全部记录.

  fool-proof: row.isDeviation = true 用 danger tag 高亮.
-->
<template>
  <div class="haccp-monitoring-list">
    <el-alert
      title="HACCP 监控记录只读"
      type="info"
      :closable="false"
      show-icon
      class="info-banner"
    >
      监控记录由生产端实时录入 (移动端 / IoT). 此页面用于食品安全主管追溯偏离事件.
    </el-alert>

    <div class="toolbar">
      <el-input
        v-model="batchNumber"
        placeholder="按批次号查询全部监控 (留空则只看偏离)"
        clearable
        style="width: 280px;"
        @clear="reload"
        @keyup.enter="reload"
      />
      <el-button type="primary" @click="reload" style="margin-left: 12px;">
        查询
      </el-button>
      <el-text type="info" size="small" style="margin-left: 12px;">
        共 {{ rows.length }} 条
      </el-text>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      class="table"
      empty-text="暂无监控记录"
      stripe
    >
      <el-table-column prop="batchNumber" label="批次号" min-width="160" show-overflow-tooltip />
      <el-table-column prop="checkpointId" label="CCP ID" width="100" />
      <el-table-column label="监控时间" width="170">
        <template #default="{ row }">
          {{ formatDate(row.monitoringTime) }}
        </template>
      </el-table-column>
      <el-table-column label="实测值" width="120">
        <template #default="{ row }">
          {{ row.measuredValue }}
        </template>
      </el-table-column>
      <el-table-column label="偏离" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.isDeviation" type="danger" size="small">偏离</el-tag>
          <el-tag v-else type="success" size="small">合格</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="operatorUserId" label="操作员 ID" width="120" />
      <el-table-column label="录入时间" width="170">
        <template #default="{ row }">
          {{ formatDate(row.createdAt) }}
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { listMonitoring, type HaccpMonitoringRecord } from '@/api/foodSafetyHub'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const loading = ref(false)
const rows = ref<HaccpMonitoringRecord[]>([])
const batchNumber = ref('')

function formatDate(s?: string) {
  if (!s) return ''
  return s.replace('T', ' ').substring(0, 19)
}

async function reload() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const params: { batchNumber?: string; deviationsOnly?: boolean } = {}
    if (batchNumber.value && batchNumber.value.trim()) {
      params.batchNumber = batchNumber.value.trim()
    } else {
      params.deviationsOnly = true
    }
    const res = await listMonitoring(props.factoryId, params)
    rows.value = res.success && res.data ? res.data : []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void reload()
})
</script>

<style scoped>
.haccp-monitoring-list {
  padding: 0;
}
.info-banner {
  margin-bottom: 12px;
}
.toolbar {
  margin-bottom: 12px;
  display: flex;
  align-items: center;
}
.table {
  margin-top: 8px;
}
</style>
