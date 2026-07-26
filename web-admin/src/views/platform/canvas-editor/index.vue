<!-- index.vue — Collapsible 3-panel canvas editor -->
<template>
  <!-- Screen too small warning -->
  <div v-if="screenTooSmall" class="screen-warning">
    <el-result icon="warning" title="请使用更宽的屏幕" sub-title="Canvas 编辑器需要至少 1024px 宽度" />
  </div>

  <!-- Onboarding or Editor -->
  <OnboardingWizard v-else-if="isOnboarding" @complete="isOnboarding = false" />

  <div v-else class="canvas-editor">
    <!-- Header -->
    <CanvasHeader
      @save="saveDraft" @submit-review="submitReview" @approve="showApproveDialog = true"
      @reject="showRejectDialog = true" @publish-now="publishNow"
      @cancel-approval="cancelApproval" @new-draft="newDraft" @rollback="rollback"
    />

    <div class="canvas-body">
      <!-- Left: Module Tree (collapsible) -->
      <aside class="canvas-left" :class="{ collapsed: leftCollapsed }" :style="{ width: leftCollapsed ? '32px' : '180px' }">
        <button
          v-if="leftCollapsed"
          type="button"
          class="collapse-label"
          aria-label="展开模块面板"
          @click="toggleLeft"
        >
          ▶ 模块
        </button>
        <template v-else>
          <div class="panel-header">
            <span class="panel-title">模块</span>
            <button type="button" class="collapse-btn" aria-label="收起模块面板" @click="toggleLeft">◀</button>
          </div>
          <ModuleTree :factory-id="factoryId" :selected-module="selectedModule" @select="selectedModule = $event" />
        </template>
      </aside>

      <!-- Center: Tabs + Content -->
      <main class="canvas-center">
        <PhaseTabBar />
        <CanvasBreadcrumb />

        <div class="canvas-content">
          <!-- Flow tabs (Phase A) -->
          <WorkflowDesigner v-if="activeTab === 'workflow' && selectedModule" :factory-id="factoryId" :module-code="selectedModule" />
          <ApprovalWorkflowEditor
            v-else-if="activeTab === 'approval'"
            :embedded="true"
            :initial-decision-type="initialApprovalDecisionType"
            :initial-workflow-id="initialApprovalWorkflowId"
            :lock-decision-type="approvalBusinessLocked"
            @exit-context="exitApprovalBusiness"
          />
          <TriggerChainDesigner v-else-if="activeTab === 'triggers'" :factory-id="factoryId" />
          <ValidationRulePanel v-else-if="activeTab === 'validation'" :factory-id="factoryId" :module-code="selectedModule" />

          <!-- Detail tabs (Phase B) -->
          <template v-else-if="activeTab === 'fields'">
            <PageEditor v-if="selectedModule" :module-code="selectedModule" :factory-id="factoryId" />
            <el-empty v-else description="请先选择模块" />
          </template>
          <PermissionMatrix v-else-if="activeTab === 'permissions' && selectedModule" :factory-id="factoryId" :module-code="selectedModule" />
          <ModulePermissionMatrix v-else-if="activeTab === 'module-permissions'" :factory-id="factoryId" />
          <UserModuleAccessMatrix v-else-if="activeTab === 'user-module-access'" :factory-id="factoryId" />
          <ToolSkillMatrix v-else-if="activeTab === 'tools'" :factory-id="factoryId" />
          <!-- Round 4 Fix P1-10: Scheduler Panel (legacy v2 config) -->
          <SchedulerPanel v-else-if="activeTab === 'scheduler'" :factory-id="factoryId" />
          <!-- Phase 2 Canvas-Alerts (2026-05-19): AlertRulesEditor Tab -->
          <AlertRulesEditor v-else-if="activeTab === 'alerts'" :embedded="true" :factory-id="factoryId" />
          <!-- Phase 3 Canvas-Notify (2026-05-19): NotifyTemplateEditor -->
          <NotifyTemplateEditor v-else-if="activeTab === 'notify'" :factory-id="factoryId" />
          <!-- Phase 4a (2026-05-18): Business Rules Editor (Canvas-Rules engine) -->
          <BusinessRulesEditor v-else-if="activeTab === 'business-rules'" :factory-id="factoryId" />
          <!-- Phase 4b: Canvas-Pricing 价格策略 Tab -->
          <PricingStrategyTab v-else-if="activeTab === 'pricing'" :factory-id="factoryId" />
          <!-- Phase 5 (2026-05-19): Canvas-Cron Tab (DB-driven DynamicScheduler) -->
          <ScheduledTaskEditor v-else-if="activeTab === 'cron'" :factory-id="factoryId" />

          <!-- Phase A P0 (2026-05-21): 3 new hubs from 六扇门 audit -->
          <!-- Slot for subagent #1 — Thresholds Hub (Flyway V20260823_01) -->
          <ThresholdsHubEditor v-else-if="activeTab === 'thresholds'" :factory-id="factoryId" />
          <!-- Slot for subagent #2 — Food Safety Hub (Flyway V20260823_02 — wraps 15 existing Sprint 8/9 entities) -->
          <FoodSafetyHubEditor v-else-if="activeTab === 'food-safety'" :factory-id="factoryId" />
          <!-- Slot for subagent #3 — Indicator Center (Flyway V20260823_03 — wraps Phase 1 already-shipped backend) -->
          <IndicatorCenterEditor v-else-if="activeTab === 'indicators'" :factory-id="factoryId" />

          <!-- Phase B P1 (2026-05-22) — 客户面差异化卖点 -->
          <FactoryConfigHubEditor v-else-if="activeTab === 'factory-config'" :factory-id="factoryId" />
          <SalesTargetHubEditor v-else-if="activeTab === 'sales-target'" :factory-id="factoryId" />
          <!-- Phase C P2 — 内部防呆 dropdown 集中 -->
          <EnumDictionaryEditor v-else-if="activeTab === 'enum-dictionary'" :factory-id="factoryId" />
          <!-- P3 半-Canvas-ed (entity 已存) -->
          <SupplierAdmissionEditor v-else-if="activeTab === 'supplier-admission'" :factory-id="factoryId" />
          <EncodingRuleEditor v-else-if="activeTab === 'encoding-rule'" :factory-id="factoryId" />
          <HrInsuranceEditor v-else-if="activeTab === 'hr-insurance'" :factory-id="factoryId" />
          <FactorySchedulingEditor v-else-if="activeTab === 'factory-scheduling'" :factory-id="factoryId" />
          <PurchaseOrderApprovalEditor v-else-if="activeTab === 'purchase-order-approval'" :factory-id="factoryId" />

          <!-- Empty state -->
          <div v-else class="empty-state">
            <el-empty description="请在左侧选择模块" />
          </div>
        </div>

        <!-- Diff viewer -->
        <ConfigDiffViewer
          v-if="pendingChanges.length > 0"
          :changes="pendingChanges"
          :show-technical-values="!approvalBusinessLocked"
          @apply="applyChanges"
          @discard="pendingChanges = []"
        />

        <StatusBar
          :is-complete="true"
          :hide-technical-details="approvalBusinessLocked"
          @show-json="showSchemaPreview = true"
          @show-history="showVersionHistory = true"
          @show-publish-window="showPublishWindow = true"
        />
      </main>

      <!-- Right: AI Panel (collapsible) -->
      <aside class="canvas-right" :class="{ collapsed: rightCollapsed }" :style="{ width: rightCollapsed ? '32px' : '300px' }">
        <button
          v-if="rightCollapsed"
          type="button"
          class="collapse-label right"
          aria-label="展开 AI 助手"
          @click="toggleRight"
        >
          ◀ AI
        </button>
        <template v-else>
          <div class="panel-header">
            <span class="panel-title">AI 助手</span>
            <button type="button" class="collapse-btn" aria-label="收起 AI 助手" @click="toggleRight">▶</button>
          </div>
          <AIChatPanel :factory-id="factoryId" :selected-module="selectedModule" @apply-diff="handleAIDiff" />
        </template>
      </aside>
    </div>

    <!-- Dialogs -->
    <ReviewDialog v-if="showApproveDialog" mode="approve" @confirm="doApprove" @cancel="showApproveDialog = false" />
    <ReviewDialog v-if="showRejectDialog" mode="reject" @confirm="doReject" @cancel="showRejectDialog = false" />
    <PublishWindowDialog v-if="showPublishWindow" :factory-id="factoryId" @close="showPublishWindow = false" />
    <el-drawer v-if="!approvalBusinessLocked" v-model="showSchemaPreview" title="JSON 预览" size="500px">
      <SchemaPreview :factory-id="factoryId" :module-code="selectedModule" />
    </el-drawer>
    <el-drawer v-model="showVersionHistory" title="版本历史" size="400px">
      <VersionHistory :factory-id="factoryId" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useCanvasEditor } from './composables/useCanvasEditor'
import { aiApplyDiffs, submitForReview, approveConfig, rejectConfig, publishNow as apiPublishNow, cancelApproval as apiCancelApproval } from '@/api/canvasApi'
import { saveModuleConfig } from '@/api/configApi'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ConfigDiff } from '@/types/canvas'
import { isDecisionType, type DecisionType } from '@/api/approvalWorkflow'

// Components
import CanvasHeader from './components/CanvasHeader.vue'
import PhaseTabBar from './components/PhaseTabBar.vue'
import CanvasBreadcrumb from './components/CanvasBreadcrumb.vue'
import StatusBar from './components/StatusBar.vue'
import ModuleTree from './components/ModuleTree.vue'
import WorkflowDesigner from './components/WorkflowDesigner.vue'
import ApprovalWorkflowEditor from '@/views/platform/approval-workflow-editor/index.vue'
import TriggerChainDesigner from './components/TriggerChainDesigner.vue'
import ValidationRulePanel from './components/ValidationRulePanel.vue'
import FieldConfigPanel from './components/FieldConfigPanel.vue'
import PageEditor from './PageEditor.vue'
import PermissionMatrix from './components/PermissionMatrix.vue'
import ModulePermissionMatrix from './components/ModulePermissionMatrix.vue'
import UserModuleAccessMatrix from './components/UserModuleAccessMatrix.vue'
import ToolSkillMatrix from './components/ToolSkillMatrix.vue'
import SchedulerPanel from './components/SchedulerPanel.vue'
import AlertRulesEditor from '@/views/platform/alert-rules-editor/index.vue'
import NotifyTemplateEditor from '@/views/platform/notify-template-editor/index.vue'
import BusinessRulesEditor from '@/views/platform/business-rules-editor/index.vue'
import PricingStrategyTab from '@/views/platform/pricing-strategy-editor/index.vue'
import ScheduledTaskEditor from '@/views/platform/scheduled-task-editor/index.vue'
// Phase A P0 (2026-05-21): 3 new hubs from 六扇门 audit — pre-registered placeholders
// Subagents replace each stub with the real editor in their own worktree branch.
import ThresholdsHubEditor from '@/views/platform/thresholds-hub-editor/index.vue'
import FoodSafetyHubEditor from '@/views/platform/food-safety-hub-editor/index.vue'
import IndicatorCenterEditor from '@/views/platform/indicator-center-editor/index.vue'
// Phase B/C/P3 (2026-05-22): 8 new Tab placeholders — subagents fill stubs
import FactoryConfigHubEditor from '@/views/platform/factory-config-hub-editor/index.vue'
import SalesTargetHubEditor from '@/views/platform/sales-target-hub-editor/index.vue'
import EnumDictionaryEditor from '@/views/platform/enum-dictionary-editor/index.vue'
import SupplierAdmissionEditor from '@/views/platform/supplier-admission-editor/index.vue'
import EncodingRuleEditor from '@/views/platform/encoding-rule-editor/index.vue'
import HrInsuranceEditor from '@/views/platform/hr-insurance-editor/index.vue'
import FactorySchedulingEditor from '@/views/platform/factory-scheduling-editor/index.vue'
import PurchaseOrderApprovalEditor from '@/views/platform/purchase-order-approval-editor/index.vue'
import AIChatPanel from './components/AIChatPanel.vue'
import ConfigDiffViewer from './components/ConfigDiffViewer.vue'
import SchemaPreview from './components/SchemaPreview.vue'
import VersionHistory from './components/VersionHistory.vue'
import ReviewDialog from './components/ReviewDialog.vue'
import PublishWindowDialog from './components/PublishWindowDialog.vue'
import OnboardingWizard from './OnboardingWizard.vue'

const {
  factoryId, selectedModule, activeTab, dirtyCount,
  leftCollapsed, rightCollapsed, isOnboarding,
  inFlightAction,  // Round 7a: single-flight action lock
  toggleLeft, toggleRight, enterFocusMode, exitFocusMode,
  loadVersion, applyResponsive, clearDirty,
} = useCanvasEditor()
const route = useRoute()
const router = useRouter()

const initialApprovalDecisionType = computed<DecisionType | undefined>(() => {
  const value = Array.isArray(route.query.decisionType)
    ? route.query.decisionType[0]
    : route.query.decisionType
  return isDecisionType(value) ? value : undefined
})

const initialApprovalWorkflowId = computed<string | undefined>(() => {
  const value = Array.isArray(route.query.workflowId)
    ? route.query.workflowId[0]
    : route.query.workflowId
  return typeof value === 'string' && value.trim() ? value : undefined
})

const approvalBusinessLocked = computed(() => (
  initialApprovalDecisionType.value !== undefined
  && route.query.source === 'approval-chains'
))

async function exitApprovalBusiness() {
  await router.push({ name: 'SystemApprovalChains' })
}

function applyRouteDeepLink() {
  const tab = Array.isArray(route.query.tab) ? route.query.tab[0] : route.query.tab
  if (tab === 'approval') {
    activeTab.value = 'approval'
    isOnboarding.value = false
  } else if (tab === 'workflow' || tab == null) {
    activeTab.value = 'workflow'
  }
}

watch(
  () => [route.query.tab, route.query.decisionType, route.query.workflowId],
  applyRouteDeepLink,
  { immediate: true },
)

// Round 7a: wrap every action handler with an in-flight lock. CanvasHeader's
// emitLocked() already drops duplicate clicks client-side; this ensures that
// if a handler is somehow invoked twice (e.g. keyboard shortcut bypass),
// the second invocation is still a no-op.
async function withLock(code: string, fn: () => Promise<void>) {
  if (inFlightAction.value !== null) return
  inFlightAction.value = code
  try {
    await fn()
  } finally {
    inFlightAction.value = null
  }
}

const pendingChanges = ref<ConfigDiff[]>([])
const showApproveDialog = ref(false)
const showRejectDialog = ref(false)
const showPublishWindow = ref(false)
const showSchemaPreview = ref(false)
const showVersionHistory = ref(false)
const screenTooSmall = ref(false)

function handleAIDiff(diffs: ConfigDiff[]) { pendingChanges.value = diffs }

async function applyChanges() {
  if (!factoryId.value) return
  try {
    const diffs = pendingChanges.value.map(c => ({ type: c.type, tool: c.path, params: c.after, description: c.description }))
    await aiApplyDiffs(factoryId.value, diffs)
    ElMessage.success('变更已应用')
    pendingChanges.value = []
  } catch { /* axios interceptor already displayed error toast */ }
}

// Round 7a: all action functions now run under withLock() to prevent double-submit.
async function saveDraft() {
  await withLock('save', async () => {
    if (!factoryId.value) { ElMessage.warning('未找到工厂 ID'); return }
    try {
      const moduleToSave = selectedModule.value || 'sales_order'
      await saveModuleConfig(factoryId.value, moduleToSave, { enabled: true })
      await loadVersion()
      clearDirty()
      ElMessage.success('草稿已保存')
    } catch (e) {
      console.error('Save draft failed:', e)
      ElMessage.error('保存失败: ' + (e instanceof Error ? e.message : 'unknown'))
    }
  })
}
async function submitReview() {
  await withLock('submit-review', async () => {
    await submitForReview(factoryId.value)
    ElMessage.success('已提交审核')
    await loadVersion()
  })
}
async function doApprove(notes: string) {
  await withLock('approve', async () => {
    await approveConfig(factoryId.value, notes)
    showApproveDialog.value = false
    ElMessage.success('已审核通过，等待发布窗口')
    await loadVersion()
  })
}
async function doReject(reason: string) {
  await withLock('reject', async () => {
    await rejectConfig(factoryId.value, reason)
    showRejectDialog.value = false
    ElMessage.warning('已驳回')
    await loadVersion()
  })
}
async function publishNow() {
  // Confirm dialog outside the lock so user can cancel without holding the lock.
  try {
    await ElMessageBox.confirm('确定立即发布？将跳过发布窗口等待。', '立即发布', { type: 'warning' })
  } catch { return }
  await withLock('publish-now', async () => {
    await apiPublishNow(factoryId.value)
    ElMessage.success('已发布')
    await loadVersion()
  })
}
async function cancelApproval() {
  await withLock('cancel-approval', async () => {
    await apiCancelApproval(factoryId.value)
    ElMessage.info('已取消，回到草稿')
    await loadVersion()
  })
}
async function newDraft() {
  await withLock('new-draft', async () => {
    if (!factoryId.value) { ElMessage.warning('未找到工厂 ID'); return }
    try {
      const moduleToSave = selectedModule.value || 'sales_order'
      await saveModuleConfig(factoryId.value, moduleToSave, { enabled: true })
      await loadVersion()
      clearDirty()
      ElMessage.success('新草稿已创建')
    } catch (e) {
      ElMessage.error('创建草稿失败: ' + (e instanceof Error ? e.message : 'unknown'))
    }
  })
}
function rollback() { ElMessage.info('回滚到上一版本'); loadVersion() }

// Keyboard shortcuts
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') exitFocusMode()
}

function checkScreen() { screenTooSmall.value = window.innerWidth < 1024 }

onMounted(async () => {
  applyRouteDeepLink()
  await loadVersion()
  // Deep links must open the requested editor even when the generic Canvas
  // onboarding heuristic would otherwise cover the tab.
  applyRouteDeepLink()
  applyResponsive()
  checkScreen()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', checkScreen)
})
onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', checkScreen)
})
</script>

<style scoped>
.screen-warning { display:flex; align-items:center; justify-content:center; height:100vh; }
.canvas-editor { display:flex; flex-direction:column; height:calc(100vh - 60px); }
.canvas-body { display:flex; flex:1; overflow:hidden; }

.canvas-left, .canvas-right {
  border-right:1px solid var(--el-border-color); overflow-y:auto; flex-shrink:0;
  transition: width 0.2s ease;
}
.canvas-right { border-right:none; border-left:1px solid var(--el-border-color); }
.canvas-left.collapsed, .canvas-right.collapsed { overflow:hidden; }

.canvas-center { flex:1; display:flex; flex-direction:column; overflow:hidden; min-width:0; }
.canvas-content { flex:1; overflow:auto; padding:8px; }

.panel-header { display:flex; justify-content:space-between; align-items:center; padding:8px 12px; font-size:12px; }
.panel-title { font-weight:bold; text-transform:uppercase; letter-spacing:1px; color:var(--el-text-color-secondary); font-size:10px; }
.collapse-btn {
  padding: 4px;
  border: 0;
  background: transparent;
  cursor: pointer;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.collapse-btn:hover { color:var(--el-text-color-primary); }
.collapse-btn:focus-visible,
.collapse-label:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}
.collapse-label {
  width: 100%;
  height: 100%;
  padding: 12px 0;
  border: 0;
  background: transparent;
  writing-mode: vertical-lr;
  text-align: center;
  cursor: pointer;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
}
.collapse-label:hover { color:var(--el-text-color-primary); background:var(--el-fill-color-light); }
.collapse-label.right { writing-mode:vertical-rl; }

.empty-state { display:flex; align-items:center; justify-content:center; height:100%; }
</style>
