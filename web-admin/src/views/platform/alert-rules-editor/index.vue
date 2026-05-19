<!--
  Canvas-Alerts Phase 2 — 预警规则 Tab 容器.

  挂载点: Canvas Editor 第 N 个 tab "预警规则" (PhaseTabBar.vue 注册 key='alerts').
  也可独立作为页面挂载 (e.g. /platform/canvas/alerts router 入口, Phase 2 follow-up).

  Tab 切换:
    - rules    : 规则列表 (RulesList) + 新建/编辑 dialog (RuleFormDialog)
    - events   : 事件历史 (EventsList) + ack/resolve action

  Per spec docs/superpowers/specs/2026-05-18-canvas-alerts-phase2-spec.md §4.
-->
<template>
  <div class="alert-rules-editor" :class="{ embedded: props.embedded }">
    <div class="header-card">
      <h2 v-if="!props.embedded">预警规则</h2>
      <div v-if="!hasFactoryId" class="no-factory">
        <el-alert type="warning" :closable="false" show-icon>
          <template #title>
            未找到工厂 ID — 请先登录工厂账号 (factory_super_admin / permission_admin)
          </template>
        </el-alert>
      </div>
    </div>

    <el-tabs v-if="hasFactoryId" v-model="innerTab" class="alerts-tabs">
      <el-tab-pane label="规则列表" name="rules">
        <RulesList
          v-if="innerTab === 'rules'"
          :factory-id="effectiveFactoryId"
          :refresh-key="rulesRefreshKey"
          @create="openCreateDialog"
          @edit="openEditDialog"
        />
      </el-tab-pane>
      <el-tab-pane label="事件历史" name="events">
        <EventsList
          v-if="innerTab === 'events'"
          :factory-id="effectiveFactoryId"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- Create / Edit form dialog (shared between RulesList and toolbar 新建 button) -->
    <RuleFormDialog
      v-if="hasFactoryId"
      v-model:visible="formDialogVisible"
      :factory-id="effectiveFactoryId"
      :editing-rule="editingRule"
      @saved="onRuleSaved"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useAuthStore } from '@/store/modules/auth'
import RulesList from './components/RulesList.vue'
import EventsList from './components/EventsList.vue'
import RuleFormDialog from './components/RuleFormDialog.vue'
import type { AlertRule } from '@/api/alertRuleApi'

// ==================== Props ====================

const props = defineProps<{
  /** Canvas-editor 内嵌时传 true → 隐藏 h2, 减小 padding. */
  embedded?: boolean
  /** Canvas-editor 透传 factoryId. 独立挂载时从 authStore 取. */
  factoryId?: string
}>()

// ==================== State ====================

const authStore = useAuthStore()
const effectiveFactoryId = computed(() => props.factoryId || authStore.factoryId || '')
const hasFactoryId = computed(() => Boolean(effectiveFactoryId.value))

const innerTab = ref<'rules' | 'events'>('rules')

// Form dialog state
const formDialogVisible = ref(false)
const editingRule = ref<AlertRule | null>(null)

// Bus key — incrementing this triggers RulesList to reload (watch).
const rulesRefreshKey = ref(0)

function openCreateDialog() {
  editingRule.value = null
  formDialogVisible.value = true
}

function openEditDialog(rule: AlertRule) {
  editingRule.value = rule
  formDialogVisible.value = true
}

// Bus event from RuleFormDialog: when a rule is created/updated,
// close dialog + bump refresh key → RulesList auto-reload.
function onRuleSaved() {
  formDialogVisible.value = false
  editingRule.value = null
  rulesRefreshKey.value++
}

// When dialog closes via x button, clear editing state so next "新建" starts clean.
watch(formDialogVisible, (open) => {
  if (!open) editingRule.value = null
})
</script>

<style scoped>
.alert-rules-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 12px;
  box-sizing: border-box;
}

/* 当 canvas-editor 嵌入时, 父容器 (.canvas-content) 已提供 flex:1 + 滚动 */
.alert-rules-editor.embedded {
  padding: 8px;
}

.header-card {
  margin-bottom: 8px;
  flex-shrink: 0;
}

.header-card h2 {
  margin: 0 0 8px;
  font-size: 18px;
  color: var(--el-text-color-primary);
}

.no-factory {
  margin-top: 8px;
}

.alerts-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

:deep(.alerts-tabs .el-tabs__content) {
  flex: 1;
  overflow: auto;
}

:deep(.alerts-tabs .el-tab-pane) {
  height: 100%;
}
</style>
