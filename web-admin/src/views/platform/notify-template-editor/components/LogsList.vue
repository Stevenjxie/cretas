<!--
  LogsList — Phase 3 Canvas-Notify, paginated audit log query.

  Filters: channel / status / recipientUserId.
  Read-only (审计目的).

  @since 2026-05-19
-->
<template>
  <div class="logs-list">
    <div class="toolbar">
      <el-select
        v-model="filters.channel"
        placeholder="全部渠道"
        clearable
        style="width: 140px"
        @change="reload"
      >
        <el-option
          v-for="ch in ALL_CHANNELS"
          :key="ch"
          :label="NotifyChannelLabels[ch]"
          :value="ch"
        />
      </el-select>
      <el-select
        v-model="filters.status"
        placeholder="全部状态"
        clearable
        style="width: 120px"
        @change="reload"
      >
        <el-option label="已发送" value="SENT" />
        <el-option label="失败" value="FAILED" />
      </el-select>
      <el-input
        v-model.number="filters.recipientUserIdInput"
        placeholder="收件用户 ID"
        clearable
        style="width: 160px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="logs"
      class="table"
      empty-text="无通知日志记录"
      stripe
    >
      <el-table-column prop="sentAt" label="发送时间" min-width="170">
        <template #default="{ row }">{{ formatDate(row.sentAt) }}</template>
      </el-table-column>
      <el-table-column prop="templateCode" label="模板编码" min-width="180" show-overflow-tooltip />
      <el-table-column prop="recipientUserId" label="收件用户 ID" min-width="120" />
      <el-table-column label="渠道" min-width="120">
        <template #default="{ row }">
          <el-tag size="small">{{ NotifyChannelLabels[String(row.channel) as NotifyChannel] || row.channel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'SENT' ? 'success' : 'danger'" size="small">
            {{ row.status === 'SENT' ? '已发送' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="errorMsg" label="错误信息" min-width="240" show-overflow-tooltip />
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :page-sizes="[10, 20, 50, 100]"
        :total="totalElements"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="reload"
        @size-change="reload"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import {
  listLogs,
  type NotifyLog,
  type NotifyChannel,
  type NotifyStatus,
  NotifyChannelLabels,
  ALL_NOTIFY_CHANNELS,
} from '@/api/notifyTemplateApi'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const ALL_CHANNELS = ALL_NOTIFY_CHANNELS

const loading = ref(false)
const logs = ref<NotifyLog[]>([])
const page = ref(1) // el-pagination 1-based; backend 0-based.
const size = ref(20)
const totalElements = ref(0)

const filters = reactive<{
  channel: NotifyChannel | ''
  status: NotifyStatus | ''
  recipientUserIdInput: number | string | ''
}>({
  channel: '',
  status: '',
  recipientUserIdInput: '',
})

async function reload() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const recipientUserId =
      typeof filters.recipientUserIdInput === 'number'
        ? filters.recipientUserIdInput
        : undefined
    const res = await listLogs(props.factoryId, {
      channel: filters.channel || undefined,
      status: filters.status || undefined,
      recipientUserId,
      page: page.value - 1, // 0-based on backend
      size: size.value,
    })
    if (res.success && res.data) {
      logs.value = res.data.content || []
      totalElements.value = res.data.totalElements || 0
    } else {
      logs.value = []
      totalElements.value = 0
    }
  } catch {
    // Error toast handled by interceptor.
    logs.value = []
    totalElements.value = 0
  } finally {
    loading.value = false
  }
}

function formatDate(s?: string): string {
  if (!s) return '—'
  try {
    return new Date(s).toLocaleString('zh-CN')
  } catch {
    return s
  }
}

onMounted(reload)
</script>

<style scoped>
.logs-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}

.table {
  flex: 1;
}

.pager {
  display: flex;
  justify-content: flex-end;
}
</style>
