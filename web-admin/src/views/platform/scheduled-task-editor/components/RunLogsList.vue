<!--
  RunLogsList.vue — Paginated scheduled-task run history.

  Backend endpoint: GET /api/mobile/scheduled-tasks/{id}/logs?page=&size=
  (Spring Page shape returned.)

  Phase 5 scope: must pick a task to view logs. To list ALL logs across tasks
  the user clicks 查看历史 in TasksList which sets focusTaskId. If no task
  focused, show a task picker.
-->
<template>
  <div class="run-logs-list">
    <div class="filters">
      <el-select
        v-model="selectedTaskId"
        placeholder="选择任务"
        filterable
        clearable
        :loading="loadingTasks"
        style="min-width: 320px"
        @change="onTaskChange"
      >
        <el-option
          v-for="t in tasks"
          :key="t.id"
          :label="`${t.taskCode}${t.taskName ? ' — ' + t.taskName : ''}`"
          :value="t.id || ''"
        />
      </el-select>
      <el-select v-model="filterStatus" placeholder="状态" size="default" clearable style="width: 130px">
        <el-option label="全部" :value="null" />
        <el-option label="SUCCESS" :value="TaskRunStatus.SUCCESS" />
        <el-option label="FAILED" :value="TaskRunStatus.FAILED" />
        <el-option label="RUNNING" :value="TaskRunStatus.RUNNING" />
        <el-option label="SKIPPED" :value="TaskRunStatus.SKIPPED" />
      </el-select>
      <el-button @click="loadLogs" :loading="loading" :disabled="!selectedTaskId">刷新</el-button>
      <el-button v-if="props.focusTaskId" link type="primary" @click="$emit('clear-focus')">
        清除任务焦点
      </el-button>
    </div>

    <el-empty
      v-if="!selectedTaskId"
      description="请先选择一个任务查看执行历史"
      :image-size="120"
    />

    <template v-else>
      <el-table
        :data="filteredLogs"
        v-loading="loading"
        empty-text="该任务暂无执行记录"
        stripe
        border
        size="small"
        style="margin-top: 12px"
      >
        <el-table-column label="开始时间" min-width="170">
          <template #default="{ row }">
            {{ formatDateTime(row.startedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="结束时间" min-width="170">
          <template #default="{ row }">
            {{ row.finishedAt ? formatDateTime(row.finishedAt) : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">
            {{ row.durationMs != null ? row.durationMs + ' ms' : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="错误信息" min-width="240">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.errorMsg"
              :content="row.errorMsg"
              placement="top"
              effect="dark"
            >
              <span class="error-msg-trunc">{{ truncate(row.errorMsg, 80) }}</span>
            </el-tooltip>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > 0"
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
        small
        style="margin-top: 12px; justify-content: flex-end;"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import {
  listScheduledTasks,
  listScheduledTaskLogs,
  TaskRunStatus,
  type ScheduledTask,
  type ScheduledTaskRunLog,
} from '@/api/scheduledTaskApi'

const props = defineProps<{
  factoryId: string
  focusTaskId: string | null
}>()

defineEmits<{
  (e: 'clear-focus'): void
}>()

const tasks = ref<ScheduledTask[]>([])
const loadingTasks = ref(false)
const loading = ref(false)
const logs = ref<ScheduledTaskRunLog[]>([])
const selectedTaskId = ref<string>('')
const filterStatus = ref<TaskRunStatus | null>(null)
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

const filteredLogs = computed(() => {
  if (filterStatus.value === null) return logs.value
  return logs.value.filter(l => l.status === filterStatus.value)
})

async function loadTasks() {
  loadingTasks.value = true
  try {
    const res = await listScheduledTasks()
    tasks.value = res.data || []
    // Apply incoming focus from parent (e.g. user clicked 查看历史).
    if (props.focusTaskId && tasks.value.some(t => t.id === props.focusTaskId)) {
      selectedTaskId.value = props.focusTaskId
    }
  } catch (e) {
    console.error('[RunLogsList] loadTasks failed', e)
  } finally {
    loadingTasks.value = false
  }
}

async function loadLogs() {
  if (!selectedTaskId.value) {
    logs.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    // Backend Page is 0-based, UI 1-based.
    const res = await listScheduledTaskLogs(
      selectedTaskId.value,
      currentPage.value - 1,
      pageSize.value,
    )
    const page = res.data
    logs.value = page?.content || []
    total.value = page?.totalElements ?? 0
  } catch (e) {
    console.error('[RunLogsList] loadLogs failed', e)
  } finally {
    loading.value = false
  }
}

function onTaskChange() {
  currentPage.value = 1
  loadLogs()
}

function onPageChange(p: number) {
  currentPage.value = p
  loadLogs()
}

function onSizeChange(s: number) {
  pageSize.value = s
  currentPage.value = 1
  loadLogs()
}

watch(
  () => props.focusTaskId,
  (id) => {
    if (id && tasks.value.some(t => t.id === id)) {
      selectedTaskId.value = id
      currentPage.value = 1
      loadLogs()
    }
  },
)

// ----------------------------------------------------------------------------
// Display helpers
// ----------------------------------------------------------------------------

function formatDateTime(iso?: string | null): string {
  if (!iso) return '—'
  try {
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return iso
    const yy = d.getFullYear()
    const mm = String(d.getMonth() + 1).padStart(2, '0')
    const dd = String(d.getDate()).padStart(2, '0')
    const hh = String(d.getHours()).padStart(2, '0')
    const mn = String(d.getMinutes()).padStart(2, '0')
    const ss = String(d.getSeconds()).padStart(2, '0')
    return `${yy}-${mm}-${dd} ${hh}:${mn}:${ss}`
  } catch {
    return iso
  }
}

function statusTagType(status: TaskRunStatus): 'success' | 'danger' | 'warning' | 'info' {
  switch (status) {
    case TaskRunStatus.SUCCESS: return 'success'
    case TaskRunStatus.FAILED:  return 'danger'
    case TaskRunStatus.RUNNING: return 'warning'
    case TaskRunStatus.SKIPPED: return 'info'
    default: return 'info'
  }
}

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) + '…' : s
}

onMounted(async () => {
  await loadTasks()
  if (selectedTaskId.value) await loadLogs()
})
</script>

<style scoped>
.run-logs-list { width: 100%; }
.filters {
  display: flex; gap: 8px; align-items: center; flex-wrap: wrap;
}
.error-msg-trunc {
  font-family: 'Courier New', monospace;
  font-size: 12px;
  color: var(--el-color-danger);
  cursor: help;
}
.muted { color: var(--el-text-color-placeholder); }
</style>
