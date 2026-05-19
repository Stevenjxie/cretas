<!--
  ExecutionLogsList.vue — Canvas-Rules Phase 4a
  Paged history of rule_execution_logs filtered by rule_id (required) + date range.

  Per fool-proof Rule 5 (dead-end → nav): if no ruleId selected, prompts user to
  click "日志" button on RulesList row to filter.
-->
<template>
  <div class="execution-logs-list">
    <!-- Filter bar -->
    <div class="filter-bar">
      <el-select
        v-model="selectedRuleId"
        placeholder="选择要查看的规则"
        filterable
        clearable
        style="width: 320px"
        @change="onRuleChange"
      >
        <el-option
          v-for="r in allRules"
          :key="r.id!"
          :label="`${r.ruleCode} - ${r.ruleName || '(未命名)'}`"
          :value="r.id!"
        />
      </el-select>
      <el-button size="small" :disabled="!selectedRuleId" @click="load">刷新</el-button>
      <span v-if="selectedRule" class="rule-info">
        Scope: <strong>{{ selectedRule.scope }}</strong> | 动作: <strong>{{ selectedRule.actionType }}</strong>
        | 优先级: {{ selectedRule.priority }}
      </span>
    </div>

    <!-- Empty / not selected -->
    <div v-if="!selectedRuleId" class="empty-area">
      <el-empty description="请先选择一条规则以查看执行日志" />
    </div>

    <!-- Table -->
    <template v-else>
      <el-table
        v-loading="loading"
        :data="logs"
        border
        size="small"
        stripe
        empty-text="此规则尚无执行历史"
      >
        <el-table-column label="执行时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.executedAt || row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="触发事件" prop="triggerEvent" min-width="200" />
        <el-table-column label="结果" min-width="220">
          <template #default="{ row }">
            <pre class="json-cell">{{ formatJson(row.resultJson) }}</pre>
          </template>
        </el-table-column>
        <el-table-column label="输入快照" min-width="220">
          <template #default="{ row }">
            <el-button link size="small" @click="showInputDetail(row)">查看输入</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- Pagination -->
      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="load"
        />
      </div>
    </template>

    <!-- Input detail dialog -->
    <el-dialog v-model="inputDialogVisible" title="输入快照 (input_json)" width="640px">
      <pre class="json-detail">{{ inputDialogContent }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import {
  listBusinessRules,
  listBusinessRuleLogs,
} from '@/api/businessRuleApi'
import type {
  BusinessRule,
  RuleExecutionLog,
} from '@/api/businessRuleApi'
import type { PageResponse } from '@/types/api'

const props = defineProps<{
  factoryId: string
  defaultRuleId?: string
}>()

const allRules = ref<BusinessRule[]>([])
const logs = ref<RuleExecutionLog[]>([])
const selectedRuleId = ref<string>('')
const loading = ref(false)

const page = ref(1) // el-pagination is 1-based; backend expects 0-based
const size = ref(20)
const total = ref(0)

const inputDialogVisible = ref(false)
const inputDialogContent = ref('')

const selectedRule = computed<BusinessRule | null>(() => {
  if (!selectedRuleId.value) return null
  return allRules.value.find((r) => r.id === selectedRuleId.value) ?? null
})

async function loadRules() {
  if (!props.factoryId) return
  try {
    const res = await listBusinessRules(props.factoryId)
    allRules.value = (res.data || []) as BusinessRule[]
  } catch (e) {
    console.error('[listBusinessRules]', e)
  }
}

async function load() {
  if (!props.factoryId || !selectedRuleId.value) {
    logs.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await listBusinessRuleLogs(
      props.factoryId,
      selectedRuleId.value,
      page.value - 1, // convert to 0-based
      size.value,
    )
    const data = res.data as PageResponse<RuleExecutionLog>
    if (data && Array.isArray((data as PageResponse<RuleExecutionLog>).content)) {
      logs.value = (data as PageResponse<RuleExecutionLog>).content
      total.value = (data as PageResponse<RuleExecutionLog>).totalElements ?? 0
    } else if (Array.isArray(data)) {
      // Defensive fallback: backend may return plain list
      logs.value = data as RuleExecutionLog[]
      total.value = logs.value.length
    } else {
      logs.value = []
      total.value = 0
    }
  } catch (e) {
    console.error('[listBusinessRuleLogs]', e)
  } finally {
    loading.value = false
  }
}

function onRuleChange() {
  page.value = 1
  load()
}

function formatTime(s: string | undefined): string {
  if (!s) return '—'
  try {
    return new Date(s).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return s
  }
}

function formatJson(v: unknown): string {
  if (!v) return '—'
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}

function showInputDetail(row: RuleExecutionLog) {
  inputDialogContent.value = formatJson(row.inputJson)
  inputDialogVisible.value = true
}

// React to defaultRuleId from parent (jump-from-rules-list flow)
watch(
  () => props.defaultRuleId,
  (id) => {
    if (id) {
      selectedRuleId.value = id
      page.value = 1
      load()
    }
  },
  { immediate: false },
)

onMounted(async () => {
  await loadRules()
  if (props.defaultRuleId) {
    selectedRuleId.value = props.defaultRuleId
    await load()
  }
})
</script>

<style scoped>
.execution-logs-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.rule-info {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.empty-area {
  padding: 40px 0;
}
.json-cell {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 11px;
  margin: 0;
  max-height: 100px;
  overflow: auto;
  background: var(--el-fill-color-light);
  padding: 4px 6px;
  border-radius: 3px;
  white-space: pre-wrap;
  word-break: break-all;
}
.json-detail {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
  max-height: 500px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.pagination-bar {
  display: flex;
  justify-content: flex-end;
}
</style>
