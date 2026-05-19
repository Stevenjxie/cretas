<!--
  RulesList.vue — 规则列表 table.

  Features:
    - 8 个 alert_type filter chips (含 "全部")
    - 规则列: name / type / severity / enabled / created / 操作
    - inline enabled 开关 (调 toggleAlertRule)
    - 编辑 / 删除 按钮
    - fool-proof Rule 2: 删除 confirm 带规则名 + 类型

  Wire (per docs/superpowers/specs/2026-05-18-canvas-alerts-phase2-spec.md §6).
-->
<template>
  <div class="rules-list">
    <!-- Toolbar: 新建 button + filter chips -->
    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="$emit('create')">
        新建规则
      </el-button>

      <div class="filter-chips">
        <el-tag
          :type="!typeFilter ? 'primary' : 'info'"
          :effect="!typeFilter ? 'dark' : 'plain'"
          class="chip"
          @click="typeFilter = null"
        >
          全部 ({{ rules.length }})
        </el-tag>
        <el-tag
          v-for="t in ALERT_TYPES"
          :key="t"
          :type="typeFilter === t ? 'primary' : 'info'"
          :effect="typeFilter === t ? 'dark' : 'plain'"
          class="chip"
          @click="typeFilter = typeFilter === t ? null : t"
        >
          {{ ALERT_TYPE_LABELS[t] }} ({{ countByType[t] || 0 }})
        </el-tag>
      </div>

      <el-button :icon="Refresh" circle plain @click="loadRules" :loading="loading" />
    </div>

    <!-- Table -->
    <el-table
      :data="filteredRules"
      v-loading="loading"
      empty-text="暂无告警规则 — 点击右上角「新建规则」配置第一条"
      style="margin-top: 12px"
      stripe
    >
      <el-table-column prop="ruleName" label="规则名称" min-width="180">
        <template #default="{ row }">
          <strong>{{ row.ruleName }}</strong>
        </template>
      </el-table-column>

      <el-table-column prop="alertType" label="类型" width="140">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">
            {{ ALERT_TYPE_LABELS[row.alertType as AlertType] || row.alertType }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column prop="severity" label="严重度" width="80">
        <template #default="{ row }">
          <el-tag
            :type="ALERT_SEVERITY_TYPES[row.severity as AlertSeverity]"
            size="small"
          >
            {{ ALERT_SEVERITY_LABELS[row.severity as AlertSeverity] || row.severity }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="触发条件" min-width="220">
        <template #default="{ row }">
          <code v-if="row.triggerConditionSpel" class="spel-snippet">
            {{ row.triggerConditionSpel }}
          </code>
          <span v-else class="muted">（业务事件命中即触发）</span>
        </template>
      </el-table-column>

      <el-table-column label="通知" min-width="140">
        <template #default="{ row }">
          <div class="notify-row">
            <el-tag
              v-for="ch in row.notifyChannels"
              :key="ch"
              size="small"
              effect="plain"
              type="info"
            >
              {{ NOTIFY_CHANNEL_LABELS[ch as NotifyChannel] || ch }}
            </el-tag>
            <span v-if="!row.notifyChannels || row.notifyChannels.length === 0" class="muted">
              —
            </span>
          </div>
          <div class="notify-roles">
            <span v-if="row.notifyRoles && row.notifyRoles.length > 0">
              角色: {{ row.notifyRoles.join(', ') }}
            </span>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="启用" width="70" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            size="small"
            :loading="togglingId === row.id"
            @click.stop
            @change="onToggle(row)"
          />
        </template>
      </el-table-column>

      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            size="small"
            :icon="Edit"
            @click="$emit('edit', row)"
          >
            编辑
          </el-button>
          <el-button
            type="danger"
            link
            size="small"
            :icon="Delete"
            @click="onDelete(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Refresh } from '@element-plus/icons-vue'
import {
  listAlertRules,
  toggleAlertRule,
  deleteAlertRule,
  ALERT_TYPES,
  ALERT_TYPE_LABELS,
  ALERT_SEVERITY_LABELS,
  ALERT_SEVERITY_TYPES,
  NOTIFY_CHANNEL_LABELS,
  type AlertRule,
  type AlertType,
  type AlertSeverity,
  type NotifyChannel,
} from '@/api/alertRuleApi'

// ==================== Props / Emits ====================

const props = defineProps<{
  factoryId: string
  /** 父组件递增此 key 触发重新加载 (e.g. 表单 dialog 保存后). */
  refreshKey?: number
}>()

const emit = defineEmits<{
  create: []
  edit: [rule: AlertRule]
}>()

// ==================== State ====================

const rules = ref<AlertRule[]>([])
const loading = ref(false)
const typeFilter = ref<AlertType | null>(null)
const togglingId = ref<string | null>(null)

// ==================== Computed ====================

const countByType = computed<Record<string, number>>(() => {
  const out: Record<string, number> = {}
  for (const r of rules.value) {
    out[r.alertType] = (out[r.alertType] || 0) + 1
  }
  return out
})

const filteredRules = computed(() => {
  if (!typeFilter.value) return rules.value
  return rules.value.filter((r) => r.alertType === typeFilter.value)
})

// ==================== Actions ====================

async function loadRules() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listAlertRules(props.factoryId)
    if (res.success && res.data) {
      rules.value = res.data
    }
  } catch (e) {
    // request.ts 已经显示了 toast — 这里只清空 state 防止旧数据卡住
    console.error('[RulesList] loadRules failed:', e)
  } finally {
    loading.value = false
  }
}

/**
 * Inline toggle — fool-proof Rule 2: confirm dialog 显规则名 + 当前/目标状态.
 * 小心 Element Plus el-switch @change 事件: 接收的是新值, 但因为我们用 :model-value
 * 而非 v-model, switch 不会真正切换 UI 直到 reload, 防止"乐观切换 失败回退"的闪烁.
 */
async function onToggle(rule: AlertRule) {
  const targetEnabled = !rule.enabled
  togglingId.value = rule.id
  try {
    const res = await toggleAlertRule(props.factoryId, rule.id)
    if (res.success && res.data) {
      // Optimistically reflect new state without full reload
      const idx = rules.value.findIndex((r) => r.id === rule.id)
      if (idx !== -1) {
        rules.value[idx] = res.data
      }
      ElMessage.success(`规则 "${rule.ruleName}" 已${targetEnabled ? '启用' : '禁用'}`)
    }
  } catch (e) {
    console.error('[RulesList] toggle failed:', e)
  } finally {
    togglingId.value = null
  }
}

/**
 * Delete — fool-proof Rule 2: confirm 文案带规则名 + 类型 + warning 提示.
 * Backend 是软删除, 但 UI 上不再显示 → 用户视角等价于删除.
 */
async function onDelete(rule: AlertRule) {
  try {
    await ElMessageBox.confirm(
      `确认删除告警规则 "${rule.ruleName}" (${ALERT_TYPE_LABELS[rule.alertType] || rule.alertType})?\n\n` +
        `软删除可恢复, 但 UI 上不再显示, 历史事件保留.`,
      '删除告警规则',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return // 用户取消
  }

  try {
    const res = await deleteAlertRule(props.factoryId, rule.id)
    if (res.success) {
      ElMessage.success(`规则 "${rule.ruleName}" 已删除`)
      await loadRules()
    }
  } catch (e) {
    console.error('[RulesList] delete failed:', e)
  }
}

// ==================== Lifecycle ====================

onMounted(loadRules)

// Re-load if factory switches (rare in practice but defensive)
watch(
  () => props.factoryId,
  () => loadRules(),
)

// Re-load when parent bumps refreshKey (e.g. dialog 保存后)
watch(
  () => props.refreshKey,
  (n) => {
    if (n !== undefined && n > 0) loadRules()
  },
)
</script>

<style scoped>
.rules-list {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.filter-chips {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  flex: 1;
}

.chip {
  cursor: pointer;
  user-select: none;
}

.spel-snippet {
  background: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 11px;
  font-family: 'Consolas', 'Monaco', monospace;
  color: var(--el-text-color-primary);
}

.muted {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.notify-row {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.notify-roles {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
</style>
