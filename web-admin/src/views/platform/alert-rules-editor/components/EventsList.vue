<!--
  EventsList.vue — 告警事件历史 (paginated).

  Features:
    - status filter chips: OPEN / ACKNOWLEDGED / RESOLVED / 全部
    - el-pagination 分页 (server-side, page 0-indexed)
    - 单事件 ack 按钮 (OPEN → ACKNOWLEDGED)
    - 单事件 resolve 按钮 (OPEN/ACKNOWLEDGED → RESOLVED)
    - 显示触发源 entityType + entityId (Rule 2 上下文)

  Wire (per docs/superpowers/specs/2026-05-18-canvas-alerts-phase2-spec.md §6).
-->
<template>
  <div class="events-list">
    <!-- Toolbar -->
    <div class="toolbar">
      <div class="filter-chips">
        <el-tag
          :type="statusFilter === undefined ? 'primary' : 'info'"
          :effect="statusFilter === undefined ? 'dark' : 'plain'"
          class="chip"
          @click="setFilter(undefined)"
        >
          全部
        </el-tag>
        <el-tag
          v-for="s in (['OPEN', 'ACKNOWLEDGED', 'RESOLVED'] as AlertEventStatus[])"
          :key="s"
          :type="statusFilter === s ? 'primary' : 'info'"
          :effect="statusFilter === s ? 'dark' : 'plain'"
          class="chip"
          @click="setFilter(s)"
        >
          {{ ALERT_EVENT_STATUS_LABELS[s] }}
        </el-tag>
      </div>

      <el-button :icon="Refresh" circle plain @click="loadEvents" :loading="loading" />
    </div>

    <!-- Table -->
    <el-table
      :data="events"
      v-loading="loading"
      empty-text="暂无告警事件"
      style="margin-top: 12px"
      stripe
    >
      <el-table-column prop="createdAt" label="触发时间" width="170">
        <template #default="{ row }">
          <span>{{ formatTime(row.createdAt) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag
            :type="ALERT_EVENT_STATUS_TYPES[row.status as AlertEventStatus]"
            size="small"
          >
            {{ ALERT_EVENT_STATUS_LABELS[row.status as AlertEventStatus] || row.status }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="严重度" width="80">
        <template #default="{ row }">
          <el-tag
            :type="ALERT_SEVERITY_TYPES[row.severity as AlertSeverity]"
            size="small"
          >
            {{ ALERT_SEVERITY_LABELS[row.severity as AlertSeverity] || row.severity }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column prop="message" label="事件描述" min-width="240">
        <template #default="{ row }">
          <div>{{ row.message }}</div>
          <div v-if="row.businessEntityType" class="entity-line">
            <code>{{ row.businessEntityType }}</code>
            <code v-if="row.businessEntityId" class="entity-id">{{ row.businessEntityId }}</code>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="处理人" width="160">
        <template #default="{ row }">
          <div v-if="row.ackedByUserId" class="actor-line">
            <span class="muted">确认:</span> user#{{ row.ackedByUserId }}
            <div v-if="row.ackedAt" class="actor-time">{{ formatTime(row.ackedAt) }}</div>
          </div>
          <div v-if="row.resolvedByUserId" class="actor-line">
            <span class="muted">解决:</span> user#{{ row.resolvedByUserId }}
            <div v-if="row.resolvedAt" class="actor-time">{{ formatTime(row.resolvedAt) }}</div>
          </div>
          <span v-if="!row.ackedByUserId && !row.resolvedByUserId" class="muted">—</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'OPEN'"
            type="primary"
            link
            size="small"
            :icon="Check"
            :loading="actionId === row.id"
            @click="onAck(row)"
          >
            确认
          </el-button>
          <el-button
            v-if="row.status === 'OPEN' || row.status === 'ACKNOWLEDGED'"
            type="success"
            link
            size="small"
            :icon="CircleCheck"
            :loading="actionId === row.id"
            @click="onResolve(row)"
          >
            解决
          </el-button>
          <span v-if="row.status === 'RESOLVED'" class="muted">已完结</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- Pagination -->
    <div class="pagination-bar">
      <el-pagination
        v-model:current-page="pageOneBased"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="totalElements"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="loadEvents"
        @current-change="loadEvents"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, CircleCheck, Refresh } from '@element-plus/icons-vue'
import {
  listAlertEvents,
  acknowledgeAlertEvent,
  resolveAlertEvent,
  ALERT_EVENT_STATUS_LABELS,
  ALERT_EVENT_STATUS_TYPES,
  ALERT_SEVERITY_LABELS,
  ALERT_SEVERITY_TYPES,
  type AlertEvent,
  type AlertEventStatus,
  type AlertSeverity,
} from '@/api/alertRuleApi'

// ==================== Props ====================

const props = defineProps<{
  factoryId: string
}>()

// ==================== State ====================

const events = ref<AlertEvent[]>([])
const loading = ref(false)
const statusFilter = ref<AlertEventStatus | undefined>('OPEN') // 默认显示 OPEN 待处理
const pageSize = ref(20)
const totalElements = ref(0)
// Server is 0-indexed; el-pagination is 1-indexed.
const pageOneBased = ref(1)
const actionId = ref<string | null>(null)

const pageZeroBased = computed(() => Math.max(0, pageOneBased.value - 1))

// ==================== Actions ====================

function setFilter(s: AlertEventStatus | undefined) {
  if (statusFilter.value === s) return
  statusFilter.value = s
  pageOneBased.value = 1
  loadEvents()
}

async function loadEvents() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listAlertEvents(
      props.factoryId,
      statusFilter.value,
      pageZeroBased.value,
      pageSize.value,
    )
    if (res.success && res.data) {
      events.value = res.data.content || []
      totalElements.value = res.data.totalElements || 0
    }
  } catch (e) {
    console.error('[EventsList] loadEvents failed:', e)
  } finally {
    loading.value = false
  }
}

async function onAck(event: AlertEvent) {
  try {
    await ElMessageBox.confirm(
      `确认告警 "${event.message}" ?\n\n` +
        `确认后状态变为 ACKNOWLEDGED, 表示你已看到此告警, 后续处理.`,
      '确认告警',
      { type: 'info', confirmButtonText: '确认', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  actionId.value = event.id
  try {
    const res = await acknowledgeAlertEvent(props.factoryId, event.id)
    if (res.success) {
      ElMessage.success('已确认')
      await loadEvents()
    }
  } catch (e) {
    console.error('[EventsList] ack failed:', e)
  } finally {
    actionId.value = null
  }
}

async function onResolve(event: AlertEvent) {
  try {
    await ElMessageBox.confirm(
      `标记告警 "${event.message}" 为已解决?\n\n` +
        `解决后告警将归档, 不再出现在 OPEN 列表.`,
      '解决告警',
      { type: 'success', confirmButtonText: '已解决', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  actionId.value = event.id
  try {
    const res = await resolveAlertEvent(props.factoryId, event.id)
    if (res.success) {
      ElMessage.success('已解决')
      await loadEvents()
    }
  } catch (e) {
    console.error('[EventsList] resolve failed:', e)
  } finally {
    actionId.value = null
  }
}

// ==================== Helpers ====================

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  try {
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return iso
    return d.toLocaleString('zh-CN', { hour12: false })
  } catch {
    return iso
  }
}

// ==================== Lifecycle ====================

onMounted(loadEvents)

watch(
  () => props.factoryId,
  () => loadEvents(),
)
</script>

<style scoped>
.events-list {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-chips {
  display: flex;
  gap: 6px;
  flex: 1;
}

.chip {
  cursor: pointer;
  user-select: none;
}

.entity-line {
  margin-top: 4px;
  font-size: 11px;
  display: flex;
  gap: 4px;
}

.entity-line code {
  background: var(--el-fill-color-light);
  padding: 1px 5px;
  border-radius: 3px;
  font-family: 'Consolas', 'Monaco', monospace;
}

.entity-id {
  color: var(--el-text-color-secondary);
}

.actor-line {
  font-size: 12px;
  line-height: 1.4;
}

.actor-time {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.muted {
  color: var(--el-text-color-placeholder);
}

.pagination-bar {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
}
</style>
