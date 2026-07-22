<template>
  <div class="approval-workflow-editor" :class="{ embedded: props.embedded }">
    <!-- Toolbar (h2 title 隐藏当 Canvas 内嵌入 — Canvas 已有 header; 但工具栏 buttons 保留) -->
    <el-card shadow="never" class="header-card">
      <div class="header-row">
        <div class="header-left">
          <h2 v-if="!props.embedded">审批工作流编辑器</h2>
          <el-tag v-if="currentWorkflow" :type="publishStatusType" size="small">
            {{ publishStatusLabel }} v{{ currentWorkflow.version }}
          </el-tag>
        </div>
        <div class="header-actions">
          <el-select
            v-model="selectedDecisionType"
            placeholder="选择决策类型"
            style="width: 260px"
            filterable
            :loading="decisionTypeMetaLoading"
            @change="onDecisionTypeChange"
          >
            <!-- Sprint 6 W3-B (2026-05-19): 改 dynamic 32 enum, 按 category group + wired badge -->
            <el-option-group
              v-for="(items, catLabel) in groupedDecisionTypeOptions"
              :key="catLabel"
              :label="catLabel"
            >
              <el-option
                v-for="dt in items"
                :key="dt.decisionType"
                :label="`${dt.chineseName} (${dt.decisionType})`"
                :value="dt.decisionType"
              >
                <span style="float: left">{{ dt.chineseName }}</span>
                <span
                  style="float: right; font-size: 11px; margin-left: 8px"
                  :style="{ color: dt.wired ? '#67C23A' : '#909399' }"
                >
                  {{ dt.wired ? '已接入' : '未接入' }}
                </span>
              </el-option>
            </el-option-group>
          </el-select>
          <el-select
            v-model="selectedWorkflowId"
            placeholder="选择工作流"
            clearable
            :disabled="workflowList.length === 0"
            style="width: 220px"
            @change="onWorkflowSelectionChange"
          >
            <el-option
              v-for="w in workflowList"
              :key="w.id"
              :label="`${w.name} (v${w.version} · ${w.publishStatus})`"
              :value="w.id"
            />
          </el-select>
          <el-input
            v-model="workflowName"
            placeholder="工作流名称"
            style="width: 200px"
          />
          <el-button :icon="Plus" @click="resetEditor">新建</el-button>
          <el-button :icon="View" @click="handleValidate" :disabled="nodes.length === 0">校验</el-button>
          <el-button :icon="VideoPlay" :disabled="nodes.length === 0" @click="openSimulator">模拟</el-button>
          <el-button
            type="primary"
            :icon="Download"
            :disabled="!canSave"
            :loading="saving"
            @click="handleSave"
          >
            {{ currentWorkflow ? '保存' : '保存草稿' }}
          </el-button>
          <el-button
            type="success"
            :icon="Upload"
            :disabled="!canPublish"
            @click="handlePublish"
          >
            发布
          </el-button>
          <el-button
            v-if="currentWorkflow && currentWorkflow.publishStatus === 'published'"
            type="warning"
            @click="handleArchive"
          >
            归档
          </el-button>
          <el-button
            v-if="currentWorkflow"
            type="danger"
            @click="handleDelete"
          >
            删除
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 3-pane body: palette | canvas | properties -->
    <div class="editor-body">
      <!-- Left: Node palette (Day 6 fills in real nodes) -->
      <div class="palette">
        <h4>节点类型</h4>
        <div
          v-for="schema in nodeSchemas"
          :key="schema.type"
          class="palette-node"
          draggable="true"
          @dragstart="onPaletteDragStart($event, schema)"
        >
          <span class="palette-icon" :style="{ backgroundColor: schema.color }">
            {{ schema.icon }}
          </span>
          <div class="palette-info">
            <span class="palette-name">{{ schema.displayName }}</span>
            <span class="palette-desc">{{ schema.description }}</span>
          </div>
        </div>
      </div>

      <!-- Center: VueFlow canvas -->
      <div class="canvas-container" @drop="onCanvasDrop" @dragover.prevent>
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :node-types="nodeTypes"
          :default-viewport="{ zoom: 0.9, x: 50, y: 50 }"
          fit-view-on-init
          @node-click="onNodeClick"
          @edge-click="onEdgeClick"
          @connect="onConnect"
        >
          <Background />
          <Controls />
        </VueFlow>
      </div>

      <!-- Right: Property panel -->
      <div class="properties-panel">
        <PropertyPanel
          v-if="selectedElement"
          :key="selectedElement.id"
          :element="selectedElement"
          :decision-type="selectedDecisionType"
          @update="onPropertyUpdate"
          @delete="onDeleteSelected"
          @manage-rules="openRulesPanel"
        />
        <el-empty v-else description="点击节点或边查看属性" :image-size="80" />
      </div>
    </div>

    <!-- Simulator modal -->
    <WorkflowSimulator
      v-if="simulatorOpen && simulatorInput"
      v-model="simulatorOpen"
      :nodes="simulatorInput.nodes"
      :edges="simulatorInput.edges"
      :start-node-id="simulatorInput.startNodeId"
      @traversal-update="onSimulatorTraversalUpdate"
    />

    <!-- Sprint 4 Wave 1 (C-WF-RULE-1): WorkflowRule 管理 drawer -->
    <ConditionRulesPanel
      v-if="rulesPanelOpen && rulesPanelNodeId && currentWorkflow && factoryId && selectedDecisionType !== 'SALES_ORDER_APPROVAL'"
      v-model:visible="rulesPanelOpen"
      :factory-id="factoryId"
      :workflow-id="currentWorkflow.id"
      :node-id="rulesPanelNodeId"
      :node-label="rulesPanelNodeLabel"
      :candidate-nodes="rulesPanelCandidateNodes"
    />
  </div>
</template>

<script setup lang="ts">
import { markRaw, ref, computed, onMounted } from 'vue'
import { VueFlow, type Connection, type Node, type Edge, type NodeTypesObject } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { Download, Plus, Upload, View, VideoPlay } from '@element-plus/icons-vue'
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
import WorkflowSimulator from './components/WorkflowSimulator.vue'
import ConditionRulesPanel from './components/ConditionRulesPanel.vue'
import { parseSalesApprovalAmountThreshold } from './lib/salesApprovalCondition'
import type { SimulatorInput } from './composables/useSimulator'
import {
  getDecisionTypes,
  getDecisionTypesMetadata,
  getWorkflowsByDecisionType,
  getWorkflowById,
  createWorkflow,
  updateWorkflow,
  deleteWorkflow,
  publishWorkflow,
  archiveWorkflow,
  validateWorkflow,
  type ApprovalWorkflowDTO,
  type ApprovalWorkflowNode,
  type ApprovalWorkflowEdge as ApiEdge,
  type CreateWorkflowRequest,
  type DecisionType,
  type DecisionTypeMetadataDTO,
  type NodeType,
} from '@/api/approvalWorkflow'

import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

// ==================== Props ====================

// embedded=true: 当此组件被 canvas-editor 作为 Tab 内嵌时, 隐藏 h2 title 并
// 调整高度以适应 canvas-editor 的 tab 容器 (Canvas 已有自己的 header + breadcrumb).
const props = defineProps<{
  embedded?: boolean
  initialDecisionType?: DecisionType
}>()

// ==================== State ====================

const authStore = useAuthStore()
const factoryId = computed(() => authStore.factoryId)

const selectedDecisionType = ref<DecisionType>(props.initialDecisionType ?? 'QUALITY_RELEASE')
const workflowName = ref('')
const saving = ref(false)

const nodes = ref<Node[]>([])
const edges = ref<Edge[]>([])
const currentWorkflow = ref<ApprovalWorkflowDTO | null>(null)
const workflowList = ref<ApprovalWorkflowDTO[]>([])
const selectedWorkflowId = ref<string | undefined>(undefined)

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
  icon: string
  color: string
}

const nodeSchemas: PaletteSchema[] = [
  { type: 'start',     displayName: '开始',   description: '工作流入口',            icon: '▶', color: '#67C23A' },
 { type: 'approval', displayName: '审批', description: '单签 / 会签节点', icon: '', color: '#409EFF' },
  { type: 'condition', displayName: '条件',   description: 'SpEL 分支判断',          icon: '?', color: '#E6A23C' },
  { type: 'parallel',  displayName: '并行',   description: '同时启动多分支',         icon: '⇉', color: '#909399' },
  { type: 'join',      displayName: '汇聚',   description: 'ALL / N_OF_M / ANY',     icon: '⇇', color: '#909399' },
 { type: 'notify', displayName: '通知', description: '推 InAppNotification', icon: '', color: '#909399' },
  { type: 'end',       displayName: '结束',   description: 'APPROVED / REJECTED',    icon: '■', color: '#F56C6C' },
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

const canSave = computed(() => Boolean(workflowName.value && selectedDecisionType.value && nodes.value.length > 0))
const canPublish = computed(() => currentWorkflow.value?.publishStatus === 'draft')

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

// ==================== Handlers ====================

async function onDecisionTypeChange() {
  resetEditor()
  await refreshWorkflowList()
}

function onPaletteDragStart(event: DragEvent, schema: PaletteSchema) {
  if (event.dataTransfer) {
    event.dataTransfer.setData('application/vueflow', JSON.stringify(schema))
    event.dataTransfer.effectAllowed = 'move'
  }
}

function onCanvasDrop(event: DragEvent) {
  const raw = event.dataTransfer?.getData('application/vueflow')
  if (!raw) return
  const schema: PaletteSchema = JSON.parse(raw)
  const canvasRect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const position = {
    x: event.clientX - canvasRect.left,
    y: event.clientY - canvasRect.top,
  }
  // Enforce singleton for start (only one entry node allowed).
  if (schema.type === 'start' && nodes.value.some(n => n.type === 'start')) {
    ElMessage.warning('工作流只能有一个 start 节点')
    return
  }
  const id = `${schema.type}_${Date.now()}`
  nodes.value.push({
    id,
    type: schema.type,
    position,
    data: {
      label: schema.displayName,
      nodeType: schema.type,
      config: defaultConfigFor(schema.type),
    },
  })
}

function onConnect(connection: Connection) {
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
  if (!selectedElement.value) return
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
}

function onDeleteSelected() {
  if (!selectedElement.value) return
  const sel = selectedElement.value
  if (sel.kind === 'node') {
    if (sel.type === 'start') {
      ElMessage.warning('start 节点不可删除 (工作流入口)')
      return
    }
    nodes.value = nodes.value.filter(n => n.id !== sel.id)
    // 删除所有以此 node 为端点的边
    edges.value = edges.value.filter(e => e.source !== sel.id && e.target !== sel.id)
  } else {
    edges.value = edges.value.filter(e => e.id !== sel.id)
  }
  selectedElement.value = null
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
        ElMessage.success('已保存 (草稿)')
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
      '发布后, 同 decisionType 的新审批实例将走这个 graph workflow. 确认?',
      '发布工作流',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const res = await publishWorkflow(factoryId.value, currentWorkflow.value.id)
    if (res.success && res.data) {
      currentWorkflow.value = res.data
      ElMessage.success('工作流已发布')
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
      '归档后, 该工作流不再被 executor 选中. 已运行实例不受影响. 确认?',
      '归档工作流',
      { type: 'warning' },
    )
  } catch {
    return
  }
  const res = await archiveWorkflow(factoryId.value, currentWorkflow.value.id)
  if (res.success && res.data) {
    currentWorkflow.value = res.data
    ElMessage.success('已归档')
    await refreshWorkflowList()
  } else {
    ElMessage.error(res.message ?? '归档失败')
  }
}

async function handleDelete() {
  if (!factoryId.value || !currentWorkflow.value?.id) return
  try {
    await ElMessageBox.confirm(
      `确定删除工作流 "${currentWorkflow.value.name}" ? (软删除可恢复, 但 UI 上不再显示)`,
      '删除工作流',
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
}

/** Load list of workflows for the currently selected decisionType. */
async function refreshWorkflowList() {
  if (!factoryId.value) return
  try {
    const res = await getWorkflowsByDecisionType(factoryId.value, selectedDecisionType.value)
    if (res.success && res.data) {
      workflowList.value = res.data
    }
  } catch (e) {
    console.warn('[refreshWorkflowList failed]', e)
  }
}

async function onWorkflowSelectionChange(id: string | undefined) {
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
      ElMessage.success(`已加载: ${res.data.name} v${res.data.version}`)
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
  padding: 8px; margin-bottom: 4px;
  border: 1px solid #e4e7ed; border-radius: 6px;
  cursor: grab; transition: all 0.2s;
}
.palette-node:hover { border-color: #409EFF; background: #f0f7ff; }
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
</style>
