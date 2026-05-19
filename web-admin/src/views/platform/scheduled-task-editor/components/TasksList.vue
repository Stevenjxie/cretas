<!--
  TasksList.vue — Canvas-Cron task table (Phase 5 frontend).

  Columns: task_code / task_name / cron_expression (with human hint) /
           handler / scope / enabled / last_run_at / last_run_status /
           next_run (computed best-effort).
  Filters: enabled (all / enabled-only / disabled-only) + scope (all / global / per-factory).
  Actions: 编辑 / 立即执行 / 启用-禁用 toggle / 删除 / 查看历史.

  Per fool-proof Rule 2: action button labels include task_name in confirm
  dialogs so user knows exactly what they're operating on.
  Per fool-proof Rule 4: 立即执行 / 删除 use ElMessageBox.confirm to prevent
  double-click misfire.
-->
<template>
  <div class="tasks-list">
    <!-- Header -->
    <div class="list-header">
      <div class="filters">
        <el-select v-model="filterEnabled" placeholder="启用状态" size="small" clearable style="width: 130px">
          <el-option label="全部" :value="null" />
          <el-option label="启用" :value="true" />
          <el-option label="禁用" :value="false" />
        </el-select>
        <el-select v-model="filterScope" placeholder="范围" size="small" style="width: 150px">
          <el-option label="全部" value="all" />
          <el-option label="全局任务" value="global" />
          <el-option label="工厂任务" value="factory" />
        </el-select>
        <el-input
          v-model="searchText"
          placeholder="搜索 任务代码 / 名称"
          size="small"
          clearable
          style="width: 220px"
        />
        <el-button size="small" @click="loadTasks" :loading="loading">刷新</el-button>
        <el-button size="small" @click="onRefreshScheduler" :loading="reloading">
          重新加载调度器
        </el-button>
      </div>
      <el-button type="primary" size="small" @click="openCreateDialog">
        + 新建定时任务
      </el-button>
    </div>

    <!-- Table -->
    <el-table
      :data="filteredTasks"
      v-loading="loading"
      empty-text="暂无定时任务"
      stripe
      border
      size="small"
      style="margin-top: 12px"
    >
      <el-table-column prop="taskCode" label="任务代码" min-width="160" show-overflow-tooltip />
      <el-table-column prop="taskName" label="任务名称" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.taskName || '—' }}
        </template>
      </el-table-column>
      <el-table-column label="Cron 表达式" min-width="220">
        <template #default="{ row }">
          <code class="cron-expr">{{ row.cronExpression }}</code>
          <div class="cron-hint">{{ describeCron(row.cronExpression) }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="handlerBeanName" label="处理器 Bean" min-width="180" show-overflow-tooltip />
      <el-table-column label="范围" width="100">
        <template #default="{ row }">
          <el-tag :type="row.factoryId ? 'primary' : 'success'" size="small">
            {{ row.factoryId ? row.factoryId : '全局' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            size="small"
            @change="(v: boolean) => onToggle(row, v)"
          />
        </template>
      </el-table-column>
      <el-table-column label="最近执行" min-width="160">
        <template #default="{ row }">
          <div v-if="row.lastRunAt">
            <div>{{ formatDateTime(row.lastRunAt) }}</div>
            <el-tag
              v-if="row.lastRunStatus"
              :type="statusTagType(row.lastRunStatus)"
              size="small"
              effect="plain"
            >{{ row.lastRunStatus }}</el-tag>
          </div>
          <span v-else class="muted">尚未执行</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openEditDialog(row)">编辑</el-button>
          <el-button type="success" link size="small" @click="onRunNow(row)">立即执行</el-button>
          <el-button type="info" link size="small" @click="$emit('view-logs', row.id)">查看历史</el-button>
          <el-button type="danger" link size="small" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Create/Edit Dialog -->
    <TaskFormDialog
      v-if="showFormDialog"
      :visible="showFormDialog"
      :edit-task="editingTask"
      @close="showFormDialog = false"
      @saved="onSaved"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listScheduledTasks,
  deleteScheduledTask,
  toggleScheduledTask,
  runScheduledTaskNow,
  refreshScheduledTasks,
  TaskRunStatus,
  type ScheduledTask,
} from '@/api/scheduledTaskApi'
import TaskFormDialog from './TaskFormDialog.vue'
import { describeCron } from '../cronHelp'

defineProps<{ factoryId: string }>()
defineEmits<{
  (e: 'view-logs', taskId: string): void
}>()

const loading = ref(false)
const reloading = ref(false)
const tasks = ref<ScheduledTask[]>([])
const filterEnabled = ref<boolean | null>(null)
const filterScope = ref<'all' | 'global' | 'factory'>('all')
const searchText = ref('')
const showFormDialog = ref(false)
const editingTask = ref<ScheduledTask | null>(null)

const filteredTasks = computed(() => {
  let rows = tasks.value
  if (filterEnabled.value !== null) {
    rows = rows.filter(t => t.enabled === filterEnabled.value)
  }
  if (filterScope.value === 'global') {
    rows = rows.filter(t => !t.factoryId)
  } else if (filterScope.value === 'factory') {
    rows = rows.filter(t => !!t.factoryId)
  }
  if (searchText.value.trim()) {
    const q = searchText.value.trim().toLowerCase()
    rows = rows.filter(t =>
      t.taskCode.toLowerCase().includes(q) ||
      (t.taskName || '').toLowerCase().includes(q),
    )
  }
  return rows
})

async function loadTasks() {
  loading.value = true
  try {
    const res = await listScheduledTasks()
    tasks.value = res.data || []
  } catch (e) {
    // Axios interceptor already surfaced the toast.
    console.error('[TasksList] load failed', e)
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  editingTask.value = null
  showFormDialog.value = true
}

function openEditDialog(task: ScheduledTask) {
  editingTask.value = task
  showFormDialog.value = true
}

function onSaved() {
  showFormDialog.value = false
  editingTask.value = null
  loadTasks()
}

async function onToggle(task: ScheduledTask, nextEnabled: boolean) {
  if (!task.id) return
  try {
    await toggleScheduledTask(task.id, nextEnabled)
    task.enabled = nextEnabled
    ElMessage.success(`${task.taskCode} 已${nextEnabled ? '启用' : '禁用'}`)
  } catch (e) {
    console.error('[TasksList] toggle failed', e)
    // Revert UI on failure — interceptor showed error toast.
  }
}

async function onRunNow(task: ScheduledTask) {
  if (!task.id) return
  // Rule 2 + Rule 4: confirm dialog with full task name to prevent misfire.
  try {
    await ElMessageBox.confirm(
      `立即执行定时任务 "${task.taskName || task.taskCode}"？ 此操作将绕过分布式锁，仅本实例执行一次。`,
      '确认立即执行',
      { type: 'warning', confirmButtonText: '立即执行', cancelButtonText: '取消' },
    )
  } catch {
    return // user cancelled
  }
  try {
    const res = await runScheduledTaskNow(task.id)
    const log = res.data
    if (log.status === TaskRunStatus.SUCCESS) {
      ElMessage.success(`执行成功 (耗时 ${log.durationMs ?? 0} ms)`)
    } else {
      ElMessage.warning(`执行结果: ${log.status}${log.errorMsg ? ' — ' + log.errorMsg : ''}`)
    }
    await loadTasks()
  } catch (e) {
    console.error('[TasksList] runNow failed', e)
  }
}

async function onDelete(task: ScheduledTask) {
  if (!task.id) return
  try {
    await ElMessageBox.confirm(
      `删除定时任务 "${task.taskName || task.taskCode}"？ 任务将停止调度（软删除，历史执行记录保留）。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteScheduledTask(task.id)
    ElMessage.success(`${task.taskCode} 已删除`)
    await loadTasks()
  } catch (e) {
    console.error('[TasksList] delete failed', e)
  }
}

async function onRefreshScheduler() {
  reloading.value = true
  try {
    await refreshScheduledTasks()
    ElMessage.success('调度器已重新加载 DB 配置')
    await loadTasks()
  } catch (e) {
    console.error('[TasksList] refresh failed', e)
  } finally {
    reloading.value = false
  }
}

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

function statusTagType(status: TaskRunStatus | null | undefined): 'success' | 'danger' | 'warning' | 'info' {
  switch (status) {
    case TaskRunStatus.SUCCESS: return 'success'
    case TaskRunStatus.FAILED:  return 'danger'
    case TaskRunStatus.RUNNING: return 'warning'
    case TaskRunStatus.SKIPPED: return 'info'
    default: return 'info'
  }
}

defineExpose({ loadTasks })

onMounted(loadTasks)
</script>

<style scoped>
.tasks-list { width: 100%; }
.list-header {
  display: flex; justify-content: space-between; align-items: center;
  gap: 12px; flex-wrap: wrap;
}
.filters { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.cron-expr {
  font-family: 'Courier New', monospace;
  background: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 12px;
}
.cron-hint {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.muted { color: var(--el-text-color-placeholder); }
</style>
