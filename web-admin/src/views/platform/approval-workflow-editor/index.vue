<template>
  <div class="approval-workflow-editor" :class="{ embedded: props.embedded }">
    <el-card shadow="never" class="header-card">
      <div v-if="props.lockDecisionType" class="business-context">
        <el-button link type="primary" @click="emit('exit-context')">返回审批业务</el-button>
        <span class="context-divider" aria-hidden="true" />
        <div>
          <strong>正在配置：{{ selectedDecisionTypeLabel }}</strong>
          <span>当前业务使用独立画布；运行版本和在途审批不会被草稿修改。</span>
        </div>
        <el-tag :type="runtimeApprovalEnabled ? 'success' : 'info'" effect="plain">
          {{ runtimeApprovalEnabled ? '审批已启用' : '无需审批' }}
        </el-tag>
      </div>

      <div class="header-row">
        <div class="header-left">
          <h2 v-if="!props.embedded">审批工作流编辑器</h2>
          <strong v-else>{{ workflowName || selectedDecisionTypeLabel }}</strong>
          <el-tag v-if="currentWorkflow" :type="publishStatusType" size="small">
            {{ publishStatusLabel }} v{{ currentWorkflow.version }}
          </el-tag>
          <span v-if="draftDirty" class="draft-dirty">有未保存修改</span>
        </div>
        <div class="header-actions">
          <el-select
            v-if="!props.lockDecisionType"
            v-model="selectedDecisionType"
            placeholder="选择审批业务"
            style="width: 240px"
            filterable
            :loading="decisionTypeMetaLoading"
            aria-label="审批业务类型"
            @change="onDecisionTypeChange"
          >
            <el-option-group
              v-for="(items, catLabel) in groupedDecisionTypeOptions"
              :key="catLabel"
              :label="catLabel"
            >
              <el-option
                v-for="dt in items"
                :key="dt.decisionType"
                :label="dt.chineseName"
                :value="dt.decisionType"
              />
            </el-option-group>
          </el-select>
          <el-select
            v-if="workflowList.length"
            v-model="selectedWorkflowId"
            placeholder="选择版本"
            style="width: 220px"
            aria-label="审批工作流版本"
            @change="onWorkflowSelectionChange"
          >
            <el-option
              v-for="workflow in workflowList"
              :key="workflow.id"
              :label="workflowVersionLabel(workflow)"
              :value="workflow.id"
            />
          </el-select>
          <el-input
            v-if="!showNoApproval"
            v-model="workflowName"
            placeholder="审批流程名称"
            style="width: 190px"
            :disabled="readOnlyWorkflow"
            aria-label="审批流程名称"
            name="approval-workflow-name"
            autocomplete="off"
            @change="markDraftChanged"
          />
          <el-button
            v-if="readOnlyWorkflow"
            type="primary"
            :loading="saving"
            @click="cloneCurrentAsDraft"
          >
            克隆为新版本
          </el-button>
          <el-button v-if="!showNoApproval" :disabled="nodes.length === 0" @click="handleValidate">
            校验画布
          </el-button>
          <el-button
            v-if="!props.lockDecisionType && !showNoApproval"
            :disabled="nodes.length === 0"
            @click="openSimulator"
          >
            模拟运行
          </el-button>
          <el-button
            v-if="!showNoApproval && !readOnlyWorkflow"
            type="primary"
            :disabled="!canSave"
            :loading="saving"
            @click="handleSave"
          >
            保存草稿
          </el-button>
          <el-button
            v-if="!showNoApproval && !readOnlyWorkflow"
            type="success"
            :disabled="!canPublish"
            @click="handlePublish"
          >
            发布并启用
          </el-button>
          <el-button
            v-if="currentWorkflow?.publishStatus === 'published'"
            type="warning"
            @click="handleArchive"
          >
            停用审批
          </el-button>
          <el-button v-if="currentWorkflow && !readOnlyWorkflow" type="danger" @click="handleDelete">
            删除草稿
          </el-button>
        </div>
      </div>
    </el-card>

    <section v-if="showNoApproval" class="no-approval-state">
      <div class="no-approval-copy">
        <span class="no-approval-label">无需审批</span>
        <h2>{{ selectedDecisionTypeLabel }} 当前不需要审批</h2>
        <p>业务提交后直接进入下一环节。没有空画布、没有隐藏审批人，也不会影响历史单据。</p>
      </div>
      <el-button type="primary" @click="enableApprovalDraft">启用该业务审批</el-button>
    </section>

    <template v-else>
      <div class="editor-body">
        <aside class="palette" aria-label="流程节点">
          <div class="panel-title-row">
            <h4>流程节点</h4>
            <span>{{ nodeSchemas.length }} 类</span>
          </div>
          <p>拖到画布中，或直接选择后添加，再按实际审批顺序连接。</p>
          <button
            v-for="schema in nodeSchemas"
            :key="schema.type"
            type="button"
            class="palette-node"
            :class="{ disabled: !canEditDraft }"
            :disabled="!canEditDraft"
            :draggable="canEditDraft"
            @dragstart="onPaletteDragStart($event, schema)"
            @click="addPaletteNode(schema)"
          >
            <span class="palette-badge" :style="{ backgroundColor: schema.color }">
              {{ schema.shortLabel }}
            </span>
            <div class="palette-info">
              <span class="palette-name">{{ schema.displayName }}</span>
              <span class="palette-desc">{{ schema.description }}</span>
            </div>
          </button>
        </aside>

        <main
          class="canvas-container"
          @drop="onCanvasDrop"
          @dragover.prevent
        >
          <VueFlow
            id="approval-workflow-canvas"
            v-model:nodes="nodes"
            v-model:edges="edges"
            :node-types="nodeTypes"
            :default-viewport="{ zoom: 0.9, x: 50, y: 50 }"
            :min-zoom="0.35"
            :max-zoom="1.8"
            :nodes-draggable="canEditDraft"
            :nodes-connectable="canEditDraft"
            :elements-selectable="true"
            :pan-on-drag="interactionMode === 'PAN' ? true : [1, 2]"
            :selection-key-code="canEditDraft && interactionMode === 'SELECT' ? true : false"
            multi-selection-key-code="Control"
            :selection-mode="SelectionMode.Partial"
            :is-valid-connection="isValidConnection"
            fit-view-on-init
            @node-click="onNodeClick"
            @edge-click="onEdgeClick"
            @pane-click="onPaneClick"
            @connect="onConnect"
            @node-drag-start="captureHistory"
            @node-drag-stop="markDraftChanged"
            @selection-end="onSelectionEnd"
          >
            <Background />
          </VueFlow>

          <div class="canvas-text-tools" aria-label="画布操作">
            <el-button
              :type="interactionMode === 'PAN' ? 'primary' : 'default'"
              @click="interactionMode = 'PAN'"
            >
              拖动画布
            </el-button>
            <el-button
              :type="interactionMode === 'SELECT' ? 'primary' : 'default'"
              @click="interactionMode = 'SELECT'"
            >
              批量选择
            </el-button>
            <el-button :disabled="!canUndo" @click="undo">撤销</el-button>
            <el-button :disabled="!canRedo" @click="redo">重做</el-button>
            <el-button @click="zoomCanvasOut">缩小</el-button>
            <el-button @click="zoomCanvasIn">放大</el-button>
            <el-button @click="fitCanvas">适应画布</el-button>
            <el-button :disabled="!canEditDraft || nodes.length < 2" @click="autoLayout">
              自动布局
            </el-button>
            <el-button
              type="danger"
              :disabled="!canEditDraft || selectedCount === 0"
              @click="deleteSelectedElements"
            >
              删除所选
            </el-button>
          </div>

          <div v-if="interactionMode === 'SELECT'" class="selection-guide">
            空白处拖框选择，按住 Ctrl 可追加；已选 {{ selectedCount }} 项。
          </div>

          <section
            v-if="props.lockDecisionType"
            class="approval-ai-dock"
            :class="{ collapsed: aiCollapsed }"
            aria-label="审批 Workflow AI"
          >
            <div class="ai-dock-header">
              <div>
                <strong>Workflow AI</strong>
                <span>{{ aiContextLabel }}</span>
              </div>
              <el-button text @click="aiCollapsed = !aiCollapsed">
                {{ aiCollapsed ? '展开' : '收起' }}
              </el-button>
            </div>
            <ApprovalWorkflowAIComposer
              v-show="!aiCollapsed"
              :factory-id="factoryId"
              :context="aiContext"
              :context-label="aiContextLabel"
              :disabled="!canEditDraft || directoryLoading || Boolean(directoryError)"
              :apply-spec="applyAiSpec"
            />
          </section>
        </main>

        <aside class="properties-panel" aria-label="属性设置">
          <PropertyPanel
            v-if="selectedElement"
            :key="selectedElement.id"
            :element="selectedElement"
            :decision-type="selectedDecisionType"
            :business-mode="props.lockDecisionType"
            :read-only="!canEditDraft"
            @update="onPropertyUpdate"
            @delete="onDeleteSelected"
            @manage-rules="openRulesPanel"
          />
          <div v-else class="property-empty">
            <strong>属性设置</strong>
            <span>选择一个 Cell 或连线后，在这里配置。</span>
          </div>
        </aside>
      </div>

      <footer class="editor-status">
        <span>{{ canEditDraft ? '当前为可编辑草稿' : '当前版本只读' }}</span>
        <span>{{ nodes.length }} 个 Cell · {{ edges.length }} 条连线</span>
        <span v-if="draftDirty">尚未保存</span>
        <span v-else>已与当前版本同步</span>
      </footer>
    </template>

    <WorkflowSimulator
      v-if="!props.lockDecisionType && simulatorOpen && simulatorInput"
      v-model="simulatorOpen"
      :nodes="simulatorInput.nodes"
      :edges="simulatorInput.edges"
      :start-node-id="simulatorInput.startNodeId"
      @traversal-update="onSimulatorTraversalUpdate"
    />

    <ConditionRulesPanel
      v-if="rulesPanelOpen && rulesPanelNodeId && currentWorkflow && factoryId && selectedDecisionType !== 'SALES_ORDER_APPROVAL'"
      v-model:visible="rulesPanelOpen"
      :factory-id="factoryId"
      :workflow-id="currentWorkflow.id"
      :node-id="rulesPanelNodeId"
      :node-label="rulesPanelNodeLabel"
      :candidate-nodes="rulesPanelCandidateNodes"
      :business-mode="props.lockDecisionType"
    />
  </div>
</template>

<script setup lang="ts">
import { markRaw, ref, computed, nextTick, onMounted, watch } from 'vue'
import {
  VueFlow,
  SelectionMode,
  useVueFlow,
  type Connection,
  type Node,
  type Edge,
  type NodeTypesObject,
} from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/store/modules/auth'
import StartNode from './components/nodes/StartNode.vue'
import ApprovalNode from './components/nodes/ApprovalNode.vue'
import ConditionNode from './components/nodes/ConditionNode.vue'
import ParallelNode from './components/nodes/ParallelNode.vue'
import JoinNode from './components/nodes/JoinNode.vue'
import NotifyNode from './components/nodes/NotifyNode.vue'
import EndNode from './components/nodes/EndNode.vue'
import PropertyPanel from './components/PropertyPanel.vue'
import ApprovalWorkflowAIComposer from './components/ApprovalWorkflowAIComposer.vue'
import WorkflowSimulator from './components/WorkflowSimulator.vue'
import ConditionRulesPanel from './components/ConditionRulesPanel.vue'
import { parseSalesApprovalAmountThreshold } from './lib/salesApprovalCondition'
import { compileApprovalWorkflowAiDraft } from './lib/approvalWorkflowAi'
import type { SimulatorInput } from './composables/useSimulator'
import {
  getApprovalDirectory,
  getDecisionTypes,
  getDecisionTypesMetadata,
  getWorkflowsByDecisionType,
  getWorkflowById,
  createWorkflow,
  cloneWorkflowDraft,
  updateWorkflow,
  deleteWorkflow,
  publishWorkflow,
  archiveWorkflow,
  validateWorkflow,
  type ApprovalWorkflowDTO,
  type ApprovalDirectory,
  type ApprovalWorkflowNode,
  type ApprovalWorkflowEdge as ApiEdge,
  type CreateWorkflowRequest,
  type DecisionType,
  type DecisionTypeMetadataDTO,
  type NodeType,
} from '@/api/approvalWorkflow'

import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'

// ==================== Props ====================

// embedded=true: 当此组件被 canvas-editor 作为 Tab 内嵌时, 隐藏 h2 title 并
// 调整高度以适应 canvas-editor 的 tab 容器 (Canvas 已有自己的 header + breadcrumb).
const props = defineProps<{
  embedded?: boolean
  initialDecisionType?: DecisionType
  initialWorkflowId?: string
  lockDecisionType?: boolean
}>()
const emit = defineEmits<{
  (event: 'exit-context'): void
}>()

// ==================== State ====================

const authStore = useAuthStore()
const factoryId = computed(() => authStore.factoryId)
const { fitView, zoomIn, zoomOut, screenToFlowCoordinate } = useVueFlow('approval-workflow-canvas')

const selectedDecisionType = ref<DecisionType>(props.initialDecisionType ?? 'QUALITY_RELEASE')
const workflowName = ref('')
const saving = ref(false)

const nodes = ref<Node[]>([])
const edges = ref<Edge[]>([])
const currentWorkflow = ref<ApprovalWorkflowDTO | null>(null)
const workflowList = ref<ApprovalWorkflowDTO[]>([])
const selectedWorkflowId = ref<string | undefined>(undefined)
const approvalSetupStarted = ref(false)
const draftDirty = ref(false)
const interactionMode = ref<'PAN' | 'SELECT'>('PAN')
const aiCollapsed = ref(false)
const approvalDirectory = ref<ApprovalDirectory>({ roles: [], users: [] })
const directoryLoading = ref(false)
const directoryError = ref('')

interface EditorSnapshot {
  workflowName: string
  nodes: Node[]
  edges: Edge[]
}

const undoHistory = ref<EditorSnapshot[]>([])
const redoHistory = ref<EditorSnapshot[]>([])
const canUndo = computed(() => undoHistory.value.length > 0)
const canRedo = computed(() => redoHistory.value.length > 0)

// Simulator modal state
const simulatorOpen = ref(false)
const simulatorInput = ref<SimulatorInput | null>(null)

// Phase 1 B.5 Task 4: DAG path highlighting state
// (populated via @traversal-update from <WorkflowSimulator>)
const simTraversedNodeIds = ref<string[]>([])
const simTraversedEdgeIds = ref<string[]>([])
const simActiveNodeIds = ref<string[]>([])

function onSimulatorTraversalUpdate(payload: {
  traversedNodeIds: string[]
  traversedEdgeIds: string[]
  activeNodeIds: string[]
}) {
  simTraversedNodeIds.value = payload.traversedNodeIds
  simTraversedEdgeIds.value = payload.traversedEdgeIds
  simActiveNodeIds.value = payload.activeNodeIds
  // Re-apply class injection by updating nodes/edges arrays
  syncSimClasses()
}

function syncSimClasses() {
  const activeSet = new Set(simActiveNodeIds.value)
  const traversedNodeSet = new Set(simTraversedNodeIds.value)
  const traversedEdgeSet = new Set(simTraversedEdgeIds.value)
  nodes.value = nodes.value.map(n => ({
    ...n,
    class: activeSet.has(n.id)
      ? 'sim-active'
      : traversedNodeSet.has(n.id)
        ? 'sim-traversed'
        : '',
  }))
  edges.value = edges.value.map(e => ({
    ...e,
    class: traversedEdgeSet.has(e.id) ? 'sim-traversed' : '',
  }))
}

interface SelectedElement {
  kind: 'node' | 'edge'
  id: string
  type?: NodeType
  data: Record<string, unknown>
}
const selectedElement = ref<SelectedElement | null>(null)

// Sprint 4 Wave 1 (C-WF-RULE-1): WorkflowRule 管理 drawer state
const rulesPanelOpen = ref(false)
const rulesPanelNodeId = ref<string>('')
const rulesPanelNodeLabel = ref<string>('')
const rulesPanelCandidateNodes = ref<ApprovalWorkflowNode[]>([])

function openRulesPanel() {
  const sel = selectedElement.value
  if (!sel || sel.kind !== 'node' || sel.type !== 'condition') {
    ElMessage.warning('仅 condition 节点支持流转规则')
    return
  }
  if (selectedDecisionType.value === 'SALES_ORDER_APPROVAL') {
    ElMessage.info('销售订单金额分流请选中通向审批节点的连线，在右侧填写金额阈值')
    return
  }
  if (!currentWorkflow.value?.id) {
    // R5 (fool-proof): 不要 dead-end. 提示用户去保存草稿后再回来.
    ElMessageBox.confirm(
      '配置流转规则需要先保存工作流草稿. 是否立即保存当前编辑内容为草稿?',
      '未保存工作流',
      { confirmButtonText: '保存草稿并继续', cancelButtonText: '取消', type: 'info' },
    )
      .then(() => handleSave())
      .catch(() => {})
    return
  }
  rulesPanelNodeId.value = sel.id
  rulesPanelNodeLabel.value = (sel.data.label as string) || sel.id
  // 候选目标节点 = 全图 nodes (排除 start)
  rulesPanelCandidateNodes.value = nodes.value
    .filter((n) => n.type !== 'start')
    .map((n) => ({
      id: n.id,
      type: (n.type ?? 'approval') as NodeType,
      label: (n.data?.label as string) ?? n.id,
    }))
  rulesPanelOpen.value = true
}

// Custom node type registry — markRaw avoids Vue making components reactive.
const nodeTypes = {
  start: markRaw(StartNode),
  approval: markRaw(ApprovalNode),
  condition: markRaw(ConditionNode),
  parallel: markRaw(ParallelNode),
  join: markRaw(JoinNode),
  notify: markRaw(NotifyNode),
  end: markRaw(EndNode),
} as unknown as NodeTypesObject

/** Default config per node type — used when dragging from palette. */
function defaultConfigFor(type: NodeType): Record<string, unknown> {
  switch (type) {
    case 'approval':
      return { approverRoles: [], requiredApprovers: 1, timeoutMinutes: 0 }
    case 'condition':
      return { description: '' }
    case 'parallel':
      return {}
    case 'join':
      return { mode: 'ALL' }
    case 'notify':
      // Phase 1 B.5 Task 3: default channels empty (warns user to pick one)
      return { notifyRoles: [], channels: [] }
    case 'end':
      return { outcome: 'APPROVED' }
    default:
      return {}
  }
}

// Static node palette schemas (Day 6 will refine each)
interface PaletteSchema {
  type: NodeType
  displayName: string
  description: string
  shortLabel: string
  color: string
}

const nodeSchemas: PaletteSchema[] = [
  { type: 'start', displayName: '开始', description: '流程入口', shortLabel: '始', color: '#2FA66A' },
  { type: 'approval', displayName: '审批', description: '角色或指定人员', shortLabel: '审', color: '#409EFF' },
  { type: 'condition', displayName: '条件', description: '金额、部门等分流', shortLabel: '判', color: '#D88900' },
  { type: 'parallel', displayName: '并行审批', description: '多部门同时处理', shortLabel: '并', color: '#7657C8' },
  { type: 'join', displayName: '汇聚', description: '等待分支完成', shortLabel: '汇', color: '#6B7788' },
  { type: 'notify', displayName: '通知', description: '微信、钉钉或邮件', shortLabel: '通', color: '#4C6F8C' },
  { type: 'end', displayName: '结束', description: '通过、拒绝或取消', shortLabel: '终', color: '#4A5568' },
]

/**
 * Sprint 6 W3-B (2026-05-19): DecisionType dropdown 从后端 metadata endpoint 动态加载,
 * 取代之前 hardcode 的 12 个选项 (Sprint 5 PR #55 H 扩 enum 14→32 后, hardcode 漏 20 项).
 *
 * onMounted 时 fetch 一次, 各 decisionType 按 category 分组渲染. wired flag 用 badge 显示.
 */
const decisionTypeMetadata = ref<DecisionTypeMetadataDTO[]>([])
const decisionTypeMetaLoading = ref(false)

/** 后端 Category enum → 中文 group label */
const CATEGORY_LABELS: Record<string, string> = {
  PRODUCTION:         '生产 / 工序',
  QUALITY_MATERIAL:   '质量 / 物料',
  PURCHASE_SUPPLIER:  '采购 / 供应商',
  SALES_CUSTOMER:     '销售 / 客户',
  FINANCE_VOUCHER:    '财务 / 凭证',
  HR_WAGE:            '人事 / 工资',
  WAREHOUSE_TRANSFER: '仓储 / 调拨',
  OTHER:              '其他',
}

/** 按 category group, label 为中文 group 名, value 为该组 DecisionTypeMetadataDTO[] */
const groupedDecisionTypeOptions = computed<Record<string, DecisionTypeMetadataDTO[]>>(() => {
  const grouped: Record<string, DecisionTypeMetadataDTO[]> = {}
  for (const dt of decisionTypeMetadata.value) {
    const catLabel = CATEGORY_LABELS[dt.category] ?? dt.category
    if (!grouped[catLabel]) grouped[catLabel] = []
    grouped[catLabel].push(dt)
  }
  return grouped
})

// ==================== Computed ====================

const selectedDecisionTypeLabel = computed(() => (
  decisionTypeMetadata.value.find(
    (item) => item.decisionType === selectedDecisionType.value,
  )?.chineseName ?? selectedDecisionType.value
))

const showNoApproval = computed(() => (
  Boolean(props.lockDecisionType)
  && workflowList.value.length === 0
  && !approvalSetupStarted.value
))
const runtimeApprovalEnabled = computed(() => workflowList.value.some((workflow) => (
  workflow.publishStatus === 'published' && workflow.enabled
)))
const readOnlyWorkflow = computed(() => (
  currentWorkflow.value?.publishStatus === 'published'
  || currentWorkflow.value?.publishStatus === 'archived'
))
const canEditDraft = computed(() => !showNoApproval.value && !readOnlyWorkflow.value)
const canSave = computed(() => Boolean(
  canEditDraft.value
  && workflowName.value
  && selectedDecisionType.value
  && nodes.value.length > 0,
))
const canPublish = computed(() => currentWorkflow.value?.publishStatus === 'draft')
const selectedNodes = computed(() => nodes.value.filter((node) => (
  Boolean((node as Node & { selected?: boolean }).selected)
)))
const selectedEdges = computed(() => edges.value.filter((edge) => (
  Boolean((edge as Edge & { selected?: boolean }).selected)
)))
const selectedCount = computed(() => selectedNodes.value.length + selectedEdges.value.length)
const aiContextLabel = computed(() => {
  if (selectedNodes.value.length === 1 && selectedEdges.value.length === 0) {
    return `当前范围：${String(selectedNodes.value[0].data?.label ?? '已选 Cell')}`
  }
  if (selectedCount.value > 0) {
    return `当前范围：已选 ${selectedCount.value} 项`
  }
  return `当前范围：${selectedDecisionTypeLabel.value} 整个画布`
})
const aiContext = computed<Record<string, unknown>>(() => ({
  decisionType: selectedDecisionType.value,
  selectedNodeIds: selectedNodes.value.map((node) => node.id),
  selectedEdgeIds: selectedEdges.value.map((edge) => edge.id),
  workflow: {
    name: workflowName.value,
    startNodeId: nodes.value.find((node) => node.type === 'start')?.id ?? '',
    nodes: nodes.value.map((node) => ({
      id: node.id,
      type: node.type,
      label: node.data?.label,
      config: node.data?.config ?? {},
    })),
    edges: edges.value.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: edge.label ?? '',
      priority: edge.data?.priority ?? 0,
      condition: edge.data?.condition ?? '',
    })),
  },
  roles: approvalDirectory.value.roles.map((role) => ({
    code: role.name,
    label: role.displayName,
  })),
  users: approvalDirectory.value.users.map((user) => ({
    id: String(user.id),
    username: user.username,
    name: user.fullName || user.realName || user.username,
  })),
}))

const publishStatusType = computed<'success' | 'warning' | 'info'>(() => {
  if (!currentWorkflow.value) return 'info'
  const map: Record<string, 'success' | 'warning' | 'info'> = {
    published: 'success',
    draft: 'warning',
    archived: 'info',
  }
  return map[currentWorkflow.value.publishStatus] ?? 'info'
})

const publishStatusLabel = computed(() => {
  if (!currentWorkflow.value) return ''
  const map: Record<string, string> = { published: '已发布', draft: '草稿', archived: '已归档' }
  return map[currentWorkflow.value.publishStatus] ?? currentWorkflow.value.publishStatus
})

function workflowVersionLabel(workflow: ApprovalWorkflowDTO) {
  const status = workflow.publishStatus === 'draft'
    ? '草稿'
    : workflow.publishStatus === 'published' && workflow.enabled
      ? '运行中'
      : workflow.publishStatus === 'published'
        ? '已停用'
        : '历史版本'
  return `${workflow.name} · v${workflow.version} · ${status}`
}

function cloneValue<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function currentSnapshot(): EditorSnapshot {
  return {
    workflowName: workflowName.value,
    nodes: cloneValue(nodes.value),
    edges: cloneValue(edges.value),
  }
}

function restoreSnapshot(snapshot: EditorSnapshot) {
  workflowName.value = snapshot.workflowName
  nodes.value = cloneValue(snapshot.nodes)
  edges.value = cloneValue(snapshot.edges)
  selectedElement.value = null
  draftDirty.value = true
}

function captureHistory() {
  if (!canEditDraft.value) return
  const snapshot = currentSnapshot()
  const serialized = JSON.stringify(snapshot)
  const last = undoHistory.value.at(-1)
  if (!last || JSON.stringify(last) !== serialized) {
    undoHistory.value.push(snapshot)
    if (undoHistory.value.length > 50) undoHistory.value.shift()
  }
  redoHistory.value = []
}

function markDraftChanged() {
  if (canEditDraft.value) draftDirty.value = true
}

function undo() {
  const previous = undoHistory.value.pop()
  if (!previous) return
  redoHistory.value.push(currentSnapshot())
  restoreSnapshot(previous)
}

function redo() {
  const next = redoHistory.value.pop()
  if (!next) return
  undoHistory.value.push(currentSnapshot())
  restoreSnapshot(next)
}

async function zoomCanvasIn() {
  await zoomIn({ duration: 180 })
}

async function zoomCanvasOut() {
  await zoomOut({ duration: 180 })
}

async function fitCanvas() {
  await fitView({ padding: 0.16, duration: 260, maxZoom: 1.1 })
}

async function autoLayout() {
  if (!canEditDraft.value || nodes.value.length < 2) return
  captureHistory()
  const indegree = new Map(nodes.value.map((node) => [node.id, 0]))
  const outgoing = new Map(nodes.value.map((node) => [node.id, [] as string[]]))
  edges.value.forEach((edge) => {
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1)
    outgoing.get(edge.source)?.push(edge.target)
  })
  const queue = [...indegree.entries()].filter(([, degree]) => degree === 0).map(([id]) => id)
  const level = new Map<string, number>()
  queue.forEach((id) => level.set(id, 0))
  while (queue.length) {
    const id = queue.shift()!
    outgoing.get(id)?.forEach((target) => {
      level.set(target, Math.max(level.get(target) ?? 0, (level.get(id) ?? 0) + 1))
      const nextDegree = (indegree.get(target) ?? 0) - 1
      indegree.set(target, nextDegree)
      if (nextDegree === 0) queue.push(target)
    })
  }
  const byLevel = new Map<number, Node[]>()
  nodes.value.forEach((node) => {
    const nodeLevel = level.get(node.id) ?? 0
    const group = byLevel.get(nodeLevel) ?? []
    group.push(node)
    byLevel.set(nodeLevel, group)
  })
  nodes.value = nodes.value.map((node) => {
    const nodeLevel = level.get(node.id) ?? 0
    const group = byLevel.get(nodeLevel) ?? []
    const index = group.findIndex((candidate) => candidate.id === node.id)
    const width = Math.max(1, group.length - 1) * 240
    return {
      ...node,
      position: {
        x: 420 + index * 240 - width / 2,
        y: 60 + nodeLevel * 150,
      },
    }
  })
  draftDirty.value = true
  await nextTick()
  await fitCanvas()
}

function hasPath(from: string, to: string): boolean {
  const outgoing = new Map<string, string[]>()
  edges.value.forEach((edge) => {
    const targets = outgoing.get(edge.source) ?? []
    targets.push(edge.target)
    outgoing.set(edge.source, targets)
  })
  const stack = [from]
  const visited = new Set<string>()
  while (stack.length) {
    const id = stack.pop()!
    if (id === to) return true
    if (visited.has(id)) continue
    visited.add(id)
    stack.push(...(outgoing.get(id) ?? []))
  }
  return false
}

function isValidConnection(connection: Connection): boolean {
  if (!canEditDraft.value || !connection.source || !connection.target) return false
  if (connection.source === connection.target) return false
  // VueFlow also calls this hook while normalizing an existing batch of
  // edges. Ignore that edge itself, while continuing to reject a second
  // manual connection between the same pair of cells.
  const connectionId = 'id' in connection ? String(connection.id ?? '') : ''
  if (edges.value.some((edge) => (
    edge.id !== connectionId
    && edge.source === connection.source
    && edge.target === connection.target
  ))) return false
  const sourceNode = nodes.value.find((node) => node.id === connection.source)
  const targetNode = nodes.value.find((node) => node.id === connection.target)
  if (sourceNode?.type === 'end' || targetNode?.type === 'start') return false
  return !hasPath(connection.target, connection.source)
}

function onPaneClick() {
  selectedElement.value = null
  nodes.value = nodes.value.map((node) => ({ ...node, selected: false }))
  edges.value = edges.value.map((edge) => ({ ...edge, selected: false }))
}

function onSelectionEnd() {
  if (selectedNodes.value.length === 1 && selectedEdges.value.length === 0) {
    onNodeClick({ node: selectedNodes.value[0] })
  } else if (selectedEdges.value.length === 1 && selectedNodes.value.length === 0) {
    onEdgeClick({ edge: selectedEdges.value[0] })
  } else if (selectedCount.value !== 1) {
    selectedElement.value = null
  }
}

function deleteSelectedElements() {
  if (!canEditDraft.value || selectedCount.value === 0) return
  captureHistory()
  const nodeIds = new Set(selectedNodes.value.map((node) => node.id))
  const edgeIds = new Set(selectedEdges.value.map((edge) => edge.id))
  nodes.value = nodes.value.filter((node) => node.type === 'start' || !nodeIds.has(node.id))
  edges.value = edges.value.filter((edge) => (
    !edgeIds.has(edge.id) && !nodeIds.has(edge.source) && !nodeIds.has(edge.target)
  ))
  selectedElement.value = null
  draftDirty.value = true
}

function createDefaultDraft() {
  const defaultRoles = decisionTypeMetadata.value.find(
    (item) => item.decisionType === selectedDecisionType.value,
  )?.defaultApproverRoles ?? []
  const timestamp = Date.now()
  nodes.value = [
    {
      id: `start_${timestamp}`,
      type: 'start',
      position: { x: 420, y: 40 },
      data: { label: '开始', nodeType: 'start', config: {} },
    },
    {
      id: `approval_${timestamp}`,
      type: 'approval',
      position: { x: 420, y: 200 },
      data: {
        label: '主管审批',
        nodeType: 'approval',
        config: { approverRoles: defaultRoles, requiredApprovers: 1, timeoutMinutes: 0 },
      },
    },
    {
      id: `end_${timestamp}`,
      type: 'end',
      position: { x: 420, y: 360 },
      data: { label: '审批通过', nodeType: 'end', config: { outcome: 'APPROVED' } },
    },
  ]
  edges.value = [
    {
      id: `edge_start_${timestamp}`,
      source: `start_${timestamp}`,
      target: `approval_${timestamp}`,
      data: { priority: 0 },
    },
    {
      id: `edge_end_${timestamp}`,
      source: `approval_${timestamp}`,
      target: `end_${timestamp}`,
      data: { priority: 0 },
    },
  ]
  workflowName.value = `${selectedDecisionTypeLabel.value}流程`
  currentWorkflow.value = null
  selectedWorkflowId.value = undefined
  selectedElement.value = null
  undoHistory.value = []
  redoHistory.value = []
  draftDirty.value = true
}

function enableApprovalDraft() {
  approvalSetupStarted.value = true
  createDefaultDraft()
}

async function cloneCurrentAsDraft() {
  if (!factoryId.value || !currentWorkflow.value?.id || saving.value) return
  saving.value = true
  try {
    const response = await cloneWorkflowDraft(factoryId.value, currentWorkflow.value.id)
    if (!response.success || !response.data) {
      ElMessage.error(response.message || '克隆草稿失败')
      return
    }
    approvalSetupStarted.value = true
    workflowList.value = [
      response.data,
      ...workflowList.value.filter((workflow) => workflow.id !== response.data?.id),
    ]
    selectedWorkflowId.value = response.data.id
    await loadWorkflow(response.data.id, true)
    ElMessage.success('已克隆为独立草稿，运行版本不受影响')
  } catch (error) {
    console.error('[clone approval workflow draft failed]', error)
    ElMessage.error('克隆草稿失败')
  } finally {
    saving.value = false
  }
}

async function loadApprovalAiDirectory() {
  if (!factoryId.value || !props.lockDecisionType) return
  directoryLoading.value = true
  directoryError.value = ''
  try {
    approvalDirectory.value = await getApprovalDirectory(factoryId.value)
  } catch (error) {
    directoryError.value = error instanceof Error ? error.message : '审批目录加载失败'
  } finally {
    directoryLoading.value = false
  }
}

async function applyAiSpec(spec: unknown): Promise<boolean> {
  if (!canEditDraft.value) return false
  try {
    const draft = compileApprovalWorkflowAiDraft({
      spec,
      currentName: workflowName.value,
      currentNodes: nodes.value.map((node) => ({
        id: node.id,
        type: (node.type ?? 'approval') as NodeType,
        label: String(node.data?.label ?? node.id),
        position: { x: node.position.x, y: node.position.y },
        config: (node.data?.config as Record<string, unknown>) ?? {},
      })),
      currentEdges: edges.value.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        label: edge.label ? String(edge.label) : undefined,
        condition: (edge.data?.condition as string) || undefined,
        priority: Number(edge.data?.priority ?? 0),
      })),
      decisionType: selectedDecisionType.value,
      directory: approvalDirectory.value,
    })
    captureHistory()
    workflowName.value = draft.name
    // VueFlow validates edge endpoints as soon as the edge model changes.
    // When an AI edit replaces stable IDs across the whole graph, register
    // the new cells first; otherwise edges are checked against the previous
    // node set and silently discarded as dangling connections.
    edges.value = []
    nodes.value = draft.nodes.map((node) => ({
      id: node.id,
      type: node.type,
      position: { x: node.position?.x ?? 0, y: node.position?.y ?? 0 },
      data: {
        label: node.label ?? node.id,
        nodeType: node.type,
        config: node.config ?? {},
      },
    }))
    await nextTick()
    edges.value = draft.edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: edge.label ?? '',
      data: { condition: edge.condition ?? '', priority: edge.priority ?? 0 },
    }))
    selectedElement.value = null
    draftDirty.value = true
    await nextTick()
    await autoLayoutWithoutHistory()
    return true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'AI 草稿无法应用')
    return false
  }
}

async function autoLayoutWithoutHistory() {
  const previousUndo = undoHistory.value
  const beforeLength = previousUndo.length
  await autoLayout()
  if (undoHistory.value.length > beforeLength) {
    undoHistory.value.splice(beforeLength, 1)
  }
}

// ==================== Handlers ====================

async function onDecisionTypeChange() {
  resetEditor()
  await refreshWorkflowList()
}

function onPaletteDragStart(event: DragEvent, schema: PaletteSchema) {
  if (!canEditDraft.value) {
    event.preventDefault()
    return
  }
  if (event.dataTransfer) {
    event.dataTransfer.setData('application/vueflow', JSON.stringify(schema))
    event.dataTransfer.effectAllowed = 'move'
  }
}

function onCanvasDrop(event: DragEvent) {
  if (!canEditDraft.value) return
  const raw = event.dataTransfer?.getData('application/vueflow')
  if (!raw) return
  const schema: PaletteSchema = JSON.parse(raw)
  const position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  addPaletteNode(schema, position)
}

function addPaletteNode(schema: PaletteSchema, position?: { x: number; y: number }) {
  if (!canEditDraft.value) return
  // Enforce singleton for start (only one entry node allowed).
  if (schema.type === 'start' && nodes.value.some(n => n.type === 'start')) {
    ElMessage.warning('工作流只能有一个 start 节点')
    return
  }
  captureHistory()
  const id = `${schema.type}_${Date.now()}`
  nodes.value.push({
    id,
    type: schema.type,
    position: position ?? {
      x: 320 + (nodes.value.length % 2) * 240,
      y: 100 + Math.floor(nodes.value.length / 2) * 140,
    },
    data: {
      label: schema.displayName,
      nodeType: schema.type,
      config: defaultConfigFor(schema.type),
    },
  })
  draftDirty.value = true
}

function onConnect(connection: Connection) {
  if (!isValidConnection(connection)) {
    ElMessage.warning('这条连线会重复、回到自身或形成循环')
    return
  }
  captureHistory()
  const id = `e_${Date.now()}`
  edges.value.push({
    id,
    source: connection.source,
    target: connection.target,
    sourceHandle: connection.sourceHandle ?? undefined,
    targetHandle: connection.targetHandle ?? undefined,
    label: '',
    data: { condition: '', priority: 0 },
  })
  draftDirty.value = true
}

function onNodeClick({ node }: { node: Node }) {
  selectedElement.value = {
    kind: 'node',
    id: node.id,
    type: node.type as NodeType,
    data: { label: String(node.data?.label ?? ''), config: { ...(node.data?.config as Record<string, unknown> ?? {}) } },
  }
}

function onEdgeClick({ edge }: { edge: Edge }) {
  const sourceNode = nodes.value.find(node => node.id === edge.source)
  const targetNode = nodes.value.find(node => node.id === edge.target)
  const condition = (edge.data?.condition as string) ?? ''
  selectedElement.value = {
    kind: 'edge',
    id: edge.id,
    data: {
      label: edge.label ? String(edge.label) : '',
      condition,
      priority: Number(edge.data?.priority ?? 0),
      salesAmountThresholdEligible: (
        (sourceNode?.type === 'condition' && targetNode?.type === 'approval')
        || parseSalesApprovalAmountThreshold(condition) !== null
      ),
    },
  }
}

/** Property panel emits new data — apply to nodes/edges array. */
function onPropertyUpdate(newData: Record<string, unknown>) {
  if (!selectedElement.value || !canEditDraft.value) return
  captureHistory()
  const sel = selectedElement.value
  if (sel.kind === 'node') {
    const idx = nodes.value.findIndex(n => n.id === sel.id)
    if (idx !== -1) {
      const node = nodes.value[idx]
      nodes.value[idx] = {
        ...node,
        data: {
          ...node.data,
          label: newData.label,
          config: { ...(newData.config as Record<string, unknown>) },
        },
      }
    }
  } else {
    const idx = edges.value.findIndex(e => e.id === sel.id)
    if (idx !== -1) {
      const edge = edges.value[idx]
      edges.value[idx] = {
        ...edge,
        label: String(newData.label ?? ''),
        data: { condition: newData.condition, priority: newData.priority },
      }
    }
  }
  sel.data = { ...newData }
  draftDirty.value = true
}

function onDeleteSelected() {
  if (!selectedElement.value || !canEditDraft.value) return
  const sel = selectedElement.value
  if (sel.kind === 'node') {
    if (sel.type === 'start') {
      ElMessage.warning('start 节点不可删除 (工作流入口)')
      return
    }
    captureHistory()
    nodes.value = nodes.value.filter(n => n.id !== sel.id)
    // 删除所有以此 node 为端点的边
    edges.value = edges.value.filter(e => e.source !== sel.id && e.target !== sel.id)
  } else {
    captureHistory()
    edges.value = edges.value.filter(e => e.id !== sel.id)
  }
  selectedElement.value = null
  draftDirty.value = true
}

/** Serialize current VueFlow graph state → CreateWorkflowRequest wire shape. */
function serializeGraph(): CreateWorkflowRequest | null {
  const startNode = nodes.value.find(n => n.type === 'start')
  if (!startNode) {
    ElMessage.warning('工作流必须包含一个 start 节点')
    return null
  }
  const wireNodes: ApprovalWorkflowNode[] = nodes.value.map(n => ({
    id: n.id,
    type: (n.type as NodeType) ?? 'approval',
    label: String(n.data?.label ?? n.id),
    position: { x: n.position.x, y: n.position.y },
    config: (n.data?.config as Record<string, unknown>) ?? {},
  }))
  const wireEdges: ApiEdge[] = edges.value.map(e => ({
    id: e.id,
    source: e.source,
    target: e.target,
    condition: (e.data?.condition as string) || undefined,
    label: e.label ? String(e.label) : undefined,
    priority: Number(e.data?.priority ?? 0),
  }))
  return {
    decisionType: selectedDecisionType.value,
    name: workflowName.value,
    nodes: wireNodes,
    edges: wireEdges,
    startNodeId: startNode.id,
  }
}

/** Deserialize a backend ApprovalWorkflowDTO → VueFlow nodes/edges arrays. */
function deserializeGraph(dto: ApprovalWorkflowDTO) {
  const parsedNodes: ApprovalWorkflowNode[] = JSON.parse(dto.nodesJson || '[]')
  const parsedEdges: ApiEdge[] = JSON.parse(dto.edgesJson || '[]')
  nodes.value = parsedNodes.map(n => ({
    id: n.id,
    type: n.type,
    position: { x: n.position?.x ?? 0, y: n.position?.y ?? 0 },
    data: { label: n.label ?? n.id, config: n.config ?? {} },
  }))
  edges.value = parsedEdges.map(e => ({
    id: e.id,
    source: e.source,
    target: e.target,
    label: e.label ?? '',
    data: { condition: e.condition ?? '', priority: e.priority ?? 0 },
  }))
}

async function handleSave() {
  if (!factoryId.value || !canSave.value) return
  saving.value = true
  try {
    const payload = serializeGraph()
    if (!payload) return

    if (currentWorkflow.value?.id) {
      // Update existing
      const res = await updateWorkflow(factoryId.value, currentWorkflow.value.id, payload)
      if (res.success && res.data) {
        currentWorkflow.value = res.data
        draftDirty.value = false
        ElMessage.success('草稿已保存')
        await refreshWorkflowList()
      } else {
        ElMessage.error(res.message ?? '保存失败')
      }
    } else {
      // Create new
      const res = await createWorkflow(factoryId.value, payload)
      if (res.success && res.data) {
        currentWorkflow.value = res.data
        selectedWorkflowId.value = res.data.id
        draftDirty.value = false
        ElMessage.success('草稿已创建')
        await refreshWorkflowList()
      } else {
        ElMessage.error(res.message ?? '保存失败')
      }
    }
  } catch (e) {
    console.error('[save failed]', e)
  } finally {
    saving.value = false
  }
}

async function handlePublish() {
  if (!factoryId.value || !currentWorkflow.value?.id) return
  try {
    await ElMessageBox.confirm(
      '发布后，新提交的业务将使用此审批流程；正在审批的单据仍按原版本运行。',
      '发布并启用审批',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const res = await publishWorkflow(factoryId.value, currentWorkflow.value.id)
    if (res.success && res.data) {
      currentWorkflow.value = res.data
      draftDirty.value = false
      ElMessage.success('审批流程已发布并启用')
      await refreshWorkflowList()
    } else {
      ElMessage.error(res.message ?? '发布失败')
    }
  } catch (e) {
    console.error('[publish failed]', e)
  }
}

async function handleArchive() {
  if (!factoryId.value || !currentWorkflow.value?.id) return
  try {
    await ElMessageBox.confirm(
      '停用后，新业务将不再进入此审批流程；在途审批不受影响。',
      '停用审批',
      { type: 'warning' },
    )
  } catch {
    return
  }
  const res = await archiveWorkflow(factoryId.value, currentWorkflow.value.id)
  if (res.success && res.data) {
    currentWorkflow.value = res.data
    ElMessage.success('审批已停用')
    await refreshWorkflowList()
  } else {
    ElMessage.error(res.message ?? '归档失败')
  }
}

async function handleDelete() {
  if (!factoryId.value || !currentWorkflow.value?.id) return
  try {
    await ElMessageBox.confirm(
      `确定删除草稿“${currentWorkflow.value.name}”吗？已发布的运行版本不受影响。`,
      '删除草稿',
      { type: 'error' },
    )
  } catch {
    return
  }
  const res = await deleteWorkflow(factoryId.value, currentWorkflow.value.id)
  if (res.success) {
    ElMessage.success('已删除')
    resetEditor()
    await refreshWorkflowList()
  } else {
    ElMessage.error(res.message ?? '删除失败')
  }
}

function openSimulator() {
  const payload = serializeGraph()
  if (!payload) return
  simulatorInput.value = {
    startNodeId: payload.startNodeId,
    nodes: payload.nodes,
    edges: payload.edges,
  }
  simulatorOpen.value = true
}

async function handleValidate() {
  if (!factoryId.value) return
  const payload = serializeGraph()
  if (!payload) return
  const res = await validateWorkflow(factoryId.value, payload)
  if (res.success && res.data) {
    const result = res.data
    if (result.valid) {
      ElMessage.success(`校验通过 ${result.warnings.length ? `(${result.warnings.length} 警告)` : ''}`)
      if (result.warnings.length) {
        ElMessageBox.alert(result.warnings.join('\n'), '校验警告', { type: 'warning' })
      }
    } else {
      ElMessageBox.alert(result.errors.join('\n'), '校验失败', { type: 'error' })
    }
  } else {
    ElMessage.error(res.message ?? '校验请求失败')
  }
}

function resetEditor() {
  nodes.value = []
  edges.value = []
  currentWorkflow.value = null
  selectedWorkflowId.value = undefined
  workflowName.value = ''
  selectedElement.value = null
  undoHistory.value = []
  redoHistory.value = []
  draftDirty.value = false
}

/** Load list of workflows for the currently selected decisionType. */
async function refreshWorkflowList() {
  if (!factoryId.value) return
  try {
    const res = await getWorkflowsByDecisionType(factoryId.value, selectedDecisionType.value)
    if (res.success && res.data) {
      workflowList.value = res.data
      if (res.data.length === 0) {
        resetEditor()
        approvalSetupStarted.value = false
        return
      }
      const requested = props.initialWorkflowId
        ? res.data.find((workflow) => workflow.id === props.initialWorkflowId)
        : undefined
      const preferred = requested ?? (
        props.initialDecisionType === selectedDecisionType.value
          ? selectPreferredWorkflow(res.data)
          : undefined
      )
      if (preferred && preferred.id !== selectedWorkflowId.value) {
        selectedWorkflowId.value = preferred.id
        await loadWorkflow(preferred.id, true)
      }
    }
  } catch (e) {
    console.warn('[refreshWorkflowList failed]', e)
  }
}

watch(
  () => [props.initialDecisionType, props.initialWorkflowId] as const,
  async ([nextDecisionType]) => {
    if (!nextDecisionType) return
    if (
      nextDecisionType === selectedDecisionType.value
      && props.initialWorkflowId === selectedWorkflowId.value
    ) return
    selectedDecisionType.value = nextDecisionType
    resetEditor()
    await refreshWorkflowList()
  },
)

async function onWorkflowSelectionChange(id: string | undefined) {
  await loadWorkflow(id, false)
}

function selectPreferredWorkflow(
  candidates: ApprovalWorkflowDTO[],
): ApprovalWorkflowDTO | undefined {
  return [...candidates].sort((left, right) => {
    const statusRank = (workflow: ApprovalWorkflowDTO) => {
      if (workflow.publishStatus === 'draft') return 0
      if (workflow.publishStatus === 'published' && workflow.enabled) return 1
      if (workflow.publishStatus === 'published') return 2
      return 3
    }
    return statusRank(left) - statusRank(right)
      || right.priority - left.priority
      || right.version - left.version
  })[0]
}

async function loadWorkflow(id: string | undefined, silent: boolean) {
  if (!factoryId.value || !id) {
    resetEditor()
    return
  }
  try {
    const res = await getWorkflowById(factoryId.value, id)
    if (res.success && res.data) {
      currentWorkflow.value = res.data
      workflowName.value = res.data.name
      selectedDecisionType.value = res.data.decisionType
      deserializeGraph(res.data)
      selectedElement.value = null
      approvalSetupStarted.value = true
      undoHistory.value = []
      redoHistory.value = []
      draftDirty.value = false
      if (!silent) {
        ElMessage.success(`已加载: ${res.data.name} v${res.data.version}`)
      }
    }
  } catch (e) {
    console.error('[load workflow failed]', e)
    ElMessage.error('加载工作流失败')
  }
}

// ==================== Lifecycle ====================

/**
 * Sprint 6 W3-B (2026-05-19): 拉取 DecisionType 完整元数据 (32 个), 填充 dropdown.
 * 失败时降级到只显示当前选中的 decisionType (不阻塞编辑器加载, per 防呆 Rule 5 dead-end 改导航).
 */
async function loadDecisionTypeMetadata() {
  if (!factoryId.value) return
  decisionTypeMetaLoading.value = true
  try {
    const res = await getDecisionTypesMetadata(factoryId.value)
    if (res.success && Array.isArray(res.data)) {
      decisionTypeMetadata.value = res.data
    } else {
      console.warn('[approval-workflow-editor] DecisionType metadata 加载失败:', res.message)
      // 防呆 Rule 5: 不 dead-end — 至少给当前 selected 一个 fallback entry, 让 dropdown 不为空.
      decisionTypeMetadata.value = [{
        decisionType: selectedDecisionType.value,
        chineseName: selectedDecisionType.value,
        description: '(后端元数据加载失败, 仅显示当前选中)',
        category: 'OTHER',
        defaultApproverRoles: [],
        moduleCode: null,
        wired: false,
      }]
    }
  } catch (e) {
    console.warn('[approval-workflow-editor] DecisionType metadata 请求异常', e)
  } finally {
    decisionTypeMetaLoading.value = false
  }
}

onMounted(async () => {
  if (!factoryId.value) return
  try {
    // Sprint 6 W3-B: 并行触发 metadata + 旧 enum endpoint (旧 endpoint 仍 ping 一下保 backwards compat)
    await Promise.all([
      loadDecisionTypeMetadata(),
      getDecisionTypes(factoryId.value).catch((): undefined => undefined),
      loadApprovalAiDirectory(),
    ])
    await refreshWorkflowList()
  } catch (e) {
    console.warn('[approval-workflow-editor] 后端未就绪或网络异常', e)
  }
})
</script>

<style scoped>
.approval-workflow-editor {
  height: calc(100vh - 90px);
  display: flex;
  flex-direction: column;
}

.business-context {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
}

.business-context > div {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.business-context strong {
  color: var(--el-text-color-primary);
  font-size: 14px;
}

.business-context span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.business-context .context-divider {
  width: 1px;
  height: 28px;
  background: var(--el-border-color);
}
/* 当 canvas-editor 内嵌时, 父容器 (.canvas-content) 已提供 flex:1 + 滚动 */
.approval-workflow-editor.embedded {
  height: 100%;
}
.header-card { margin-bottom: 8px; flex-shrink: 0; }
.header-row { display: flex; justify-content: space-between; align-items: center; }
.header-left { display: flex; align-items: center; gap: 12px; }
.header-left h2 { margin: 0; font-size: 18px; }
.header-actions { display: flex; gap: 8px; align-items: center; }

.editor-body { flex: 1; display: flex; gap: 8px; min-height: 0; }

.palette {
  width: 220px; flex-shrink: 0; background: #fff;
  border: 1px solid #e4e7ed; border-radius: 4px; padding: 12px; overflow-y: auto;
}
.palette h4 { margin: 0 0 12px; font-size: 14px; color: #303133; }

.palette-node {
  display: flex; align-items: center; gap: 8px;
  width: 100%; text-align: left; background: #fff; color: inherit; font: inherit;
  padding: 8px; margin-bottom: 4px;
  border: 1px solid #e4e7ed; border-radius: 6px;
  cursor: grab; transition: border-color 0.2s, background-color 0.2s;
}
.palette-node:hover { border-color: #409EFF; background: #f0f7ff; }
.palette-node:focus-visible {
  outline: 2px solid #409eff;
  outline-offset: 2px;
}
.palette-node:disabled { cursor: not-allowed; opacity: 0.55; }
.palette-icon {
  width: 28px; height: 28px; border-radius: 6px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: white; font-weight: bold;
}
.palette-info { display: flex; flex-direction: column; min-width: 0; }
.palette-name { font-size: 13px; font-weight: 500; }
.palette-desc { font-size: 11px; color: #909399; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.canvas-container {
  flex: 1; background: #fafafa;
  border: 1px solid #e4e7ed; border-radius: 4px; min-height: 400px;
}

.properties-panel {
  width: 320px; flex-shrink: 0; background: #fff;
  border: 1px solid #e4e7ed; border-radius: 4px; padding: 12px; overflow-y: auto;
}
.properties-panel h4 { margin: 0 0 8px; font-size: 14px; color: #303133; }
.placeholder p { margin: 4px 0; font-size: 13px; }
.placeholder .hint { color: #909399; font-family: monospace; font-size: 11px; }

/* Phase 1 B.5 Task 4: Simulator DAG path highlighting */
:deep(.vue-flow__node.sim-traversed) {
  box-shadow: 0 0 0 2px #67c23a !important; /* green = completed */
  filter: drop-shadow(0 0 4px rgba(103, 194, 58, 0.4));
}
:deep(.vue-flow__node.sim-active) {
  box-shadow: 0 0 0 2px #e6a23c !important; /* yellow = current (waiting) */
  animation: sim-pulse 1.5s infinite;
}
:deep(.vue-flow__edge.sim-traversed .vue-flow__edge-path) {
  stroke: #67c23a !important;
  stroke-width: 3px !important;
}
:deep(.vue-flow__edge.sim-traversed .vue-flow__edge-text) {
  fill: #67c23a !important;
  font-weight: 600 !important;
}
@keyframes sim-pulse {
  0%, 100% { box-shadow: 0 0 0 2px #e6a23c; }
  50% { box-shadow: 0 0 0 6px rgba(230, 162, 60, 0.4); }
}

.approval-workflow-editor {
  min-height: 680px;
  padding: 12px;
  background: #f4f6f9;
  color: #1a2332;
}

.approval-workflow-editor.embedded {
  height: calc(100vh - 60px);
}

.header-card {
  border-color: #edf2f7;
  border-radius: 10px;
}

.business-context {
  margin-bottom: 10px;
  border-color: #d9e5f2;
  background: #f3f8fe;
}

.header-row {
  gap: 12px;
}

.header-left {
  min-width: 200px;
}

.header-left > strong {
  overflow: hidden;
  max-width: 300px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-actions {
  flex: 1;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.draft-dirty {
  color: #d88900;
  font-size: 12px;
}

.no-approval-state {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  min-height: 360px;
  padding: 48px;
  border: 1px solid #edf2f7;
  border-radius: 10px;
  background: #fff;
}

.no-approval-copy {
  max-width: 680px;
}

.no-approval-copy h2 {
  margin: 12px 0 8px;
  font-size: 24px;
}

.no-approval-copy p {
  margin: 0;
  color: #7a8599;
  line-height: 1.7;
}

.no-approval-label {
  display: inline-flex;
  padding: 5px 10px;
  border-radius: 999px;
  background: #eef1f5;
  color: #5d6879;
  font-size: 12px;
  font-weight: 650;
}

.editor-body {
  position: relative;
  gap: 10px;
}

.palette,
.properties-panel {
  border-color: #edf2f7;
  border-radius: 10px;
  box-shadow: 0 3px 10px rgb(31 62 92 / 5%);
}

.palette {
  width: 190px;
}

.palette > p {
  margin: -4px 0 12px;
  color: #7a8599;
  font-size: 12px;
  line-height: 1.5;
}

.panel-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.panel-title-row h4 {
  margin-bottom: 8px;
}

.panel-title-row span {
  color: #7a8599;
  font-size: 12px;
}

.palette-node {
  min-height: 52px;
  margin-bottom: 7px;
  padding: 8px 9px;
  border-color: #edf2f7;
}

.palette-node.disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.palette-badge {
  display: inline-flex;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}

.canvas-container {
  position: relative;
  min-width: 0;
  min-height: 520px;
  overflow: hidden;
  border-color: #dfe7f0;
  border-radius: 10px;
  background: #fbfcfe;
}

.canvas-text-tools {
  position: absolute;
  z-index: 8;
  top: 10px;
  right: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  max-width: calc(100% - 20px);
  padding: 5px;
  border: 1px solid #d9e5f2;
  border-radius: 9px;
  background: rgb(255 255 255 / 96%);
  box-shadow: 0 4px 12px rgb(31 62 92 / 11%);
}

.canvas-text-tools :deep(.el-button + .el-button) {
  margin-left: 0;
}

.selection-guide {
  position: absolute;
  z-index: 7;
  top: 62px;
  right: 12px;
  padding: 6px 9px;
  border-radius: 6px;
  background: rgb(27 101 168 / 88%);
  color: #fff;
  font-size: 12px;
}

.approval-ai-dock {
  position: absolute;
  z-index: 9;
  right: 50%;
  bottom: 14px;
  width: min(720px, calc(100% - 44px));
  transform: translateX(50%);
}

.approval-ai-dock.collapsed {
  width: auto;
}

.ai-dock-header {
  display: flex;
  width: max-content;
  max-width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 0 auto 6px;
  padding: 5px 10px 5px 12px;
  border: 1px solid #d9e5f2;
  border-radius: 999px;
  background: #fff;
  box-shadow: 0 4px 12px rgb(31 62 92 / 12%);
}

.ai-dock-header > div {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.ai-dock-header span {
  overflow: hidden;
  max-width: 360px;
  color: #7a8599;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.properties-panel {
  width: 330px;
  padding: 0;
}

.property-empty {
  display: flex;
  min-height: 180px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 6px;
  padding: 24px;
  text-align: center;
}

.property-empty span {
  color: #7a8599;
  font-size: 12px;
}

.editor-status {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  min-height: 34px;
  margin-top: 8px;
  padding: 0 12px;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  background: #fff;
  color: #7a8599;
  font-size: 12px;
}

:deep(.vue-flow__node.selected) {
  box-shadow: 0 0 0 2px #409eff, 0 4px 12px rgb(31 62 92 / 16%) !important;
}

:deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: #409eff !important;
  stroke-width: 3px !important;
}

@media (max-width: 1360px) {
  .palette {
    width: 168px;
  }

  .properties-panel {
    width: 300px;
  }

  .header-actions {
    justify-content: flex-start;
  }

  .header-row {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
