<!--
  Canvas-Cron Tab container (Phase 5 frontend, 2026-05-19).

  Sub-tabs:
    - tasks: TasksList + TaskFormDialog (CRUD + toggle + run-now)
    - logs : RunLogsList (paginated run history, all tasks or filtered)

  Backend: ScheduledTaskController (/api/mobile/scheduled-tasks/*).
  Roles: factory_super_admin / permission_admin only (enforced backend-side).
-->
<template>
  <div class="scheduled-task-editor">
    <el-tabs v-model="activeSubTab" class="task-subtabs">
      <el-tab-pane label="任务列表" name="tasks">
        <TasksList
          ref="tasksListRef"
          :factory-id="factoryId"
          @view-logs="onViewLogs"
        />
      </el-tab-pane>
      <el-tab-pane label="执行历史" name="logs">
        <RunLogsList
          :factory-id="factoryId"
          :focus-task-id="focusedTaskId"
          @clear-focus="focusedTaskId = null"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import TasksList from './components/TasksList.vue'
import RunLogsList from './components/RunLogsList.vue'

defineProps<{ factoryId: string }>()

const activeSubTab = ref<'tasks' | 'logs'>('tasks')
const focusedTaskId = ref<string | null>(null)
const tasksListRef = ref<InstanceType<typeof TasksList> | null>(null)

/** Switch to logs sub-tab and filter to a single task. */
function onViewLogs(taskId: string) {
  focusedTaskId.value = taskId
  activeSubTab.value = 'logs'
}
</script>

<style scoped>
.scheduled-task-editor {
  padding: 12px 16px;
  height: 100%;
  overflow: auto;
}
.task-subtabs :deep(.el-tabs__content) {
  padding-top: 8px;
}
</style>
