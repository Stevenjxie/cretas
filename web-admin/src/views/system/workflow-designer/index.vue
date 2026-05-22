<template>
  <div class="workflow-designer">
    <!-- Header -->
    <el-card shadow="never" class="header-card">
      <div class="header-row">
        <div class="header-left">
          <h2>工作流设计器</h2>
          <el-tag v-if="currentVersion" :type="publishStatusType" size="small">
            {{ publishStatusLabel }} v{{ currentVersion }}
          </el-tag>
          <el-tag v-if="validationErrors.length" type="danger" size="small">
            {{ validationErrors.length }} 个问题
          </el-tag>
        </div>
        <div class="header-actions">
          <el-select v-model="entityType" placeholder="实体类型" style="width: 200px" @change="loadStateMachine">
            <el-option label="生产工作流" value="PRODUCTION_WORKFLOW" />
            <el-option label="原材料批次" value="MATERIAL_BATCH" />
            <el-option label="质检单" value="QUALITY_INSPECTION" />
          </el-select>
          <el-button :icon="Clock" @click="showVersionHistory = true">版本历史</el-button>
          <el-button type="info" text @click="toggleSimulation">
            {{ simulationMode ? '退出模拟' : '模拟运行' }}
          </el-button>
          <el-button type="warning" text @click="validateWorkflow">校验</el-button>
          <el-button type="primary" :icon="Download" @click="handleSave" :loading="saving">
            保存草稿
          </el-button>
          <el-button type="success" :icon="Upload" @click="handlePublish" :disabled="!canPublish">
            发布
          </el-button>
        </div>
      </div>
    </el-card>

    <div class="designer-body">
      <!-- Left Panel: Node Palette -->
      <div class="node-palette">
        <h4>节点类型</h4>
        <div v-for="(catNodes, category) in groupedSchemas" :key="category" class="category-group">
          <div class="category-label">{{ category }}</div>
          <div
            v-for="schema in catNodes"
            :key="schema.nodeType"
            class="palette-node"
            draggable="true"
            @dragstart="onDragStart($event, schema)"
          >
            <span class="node-icon" :style="{ backgroundColor: schema.color }">
              {{ nodeIcon(schema.category) }}
            </span>
            <div class="palette-info">
              <span class="palette-name">{{ schema.displayName }}</span>
              <span class="palette-desc">{{ schema.description?.substring(0, 20) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Center: Vue Flow Canvas -->
      <div class="canvas-container" @drop="onDrop" @dragover.prevent>
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :default-viewport="{ zoom: 0.9, x: 50, y: 50 }"
          fit-view-on-init
          @node-click="onNodeClick"
          @edge-click="onEdgeClick"
          @connect="onConnect"
        >
          <Background />
          <Controls />

          <!-- Custom node rendering with type-specific visuals -->
          <template #node-workflow="{ data }">
            <div
              class="workflow-node"
              :class="[`node-cat-${data.nodeCategory || 'default'}`, { 'node-initial': data.stateType === 'initial', 'node-final': data.stateType === 'final' }]"
              :style="{ borderColor: data.color || '#409EFF', borderLeftColor: data.color || '#409EFF' }"
            >
              <div class="node-header" :style="{ backgroundColor: data.color || '#409EFF' }">
                <span class="node-header-icon">{{ nodeIcon(data.nodeCategory) }}</span>
                {{ data.label }}
              </div>
              <div class="node-body">
                <div class="node-status">{{ data.stateCode || '' }}</div>
                <div v-if="data.stateType" class="node-type-badge">
                  {{ stateTypeLabel(data.stateType) }}
                </div>
                <div v-if="data.assignedRoles?.length" class="node-roles-badge">
                  {{ data.assignedRoles.length }} 角色
                </div>
                <div v-if="data.timeLimitMinutes" class="node-sla-badge">
                  {{ data.timeLimitMinutes }}min
                </div>
              </div>
            </div>
          </template>
        </VueFlow>
      </div>

      <!-- Right Panel: Properties (wider, with tabs) -->
      <div class="properties-panel" v-if="selectedElement">
        <h4>属性配置</h4>

        <!-- Node properties -->
        <template v-if="selectedElement.type === 'node'">
          <el-tabs v-model="propTab" stretch>
            <el-tab-pane label="基础" name="basic">
              <el-form label-position="top" size="small">
                <el-form-item label="状态名称">
                  <el-input v-model="selectedElement.data.label" @change="syncNodeData" />
                </el-form-item>
                <el-form-item label="状态代码">
                  <el-input v-model="selectedElement.data.stateCode" @change="syncNodeData" />
                </el-form-item>
                <el-form-item label="节点类型">
                  <el-tag :color="selectedElement.data.color as string" size="small" style="color:#fff">
                    {{ getSchemaDisplayName(selectedElement.data.nodeSchemaType as string) }}
                  </el-tag>
                </el-form-item>
                <el-form-item label="状态类型">
                  <el-select v-model="selectedElement.data.stateType" @change="syncNodeData">
                    <el-option label="初始状态 ●" value="initial" />
                    <el-option label="普通状态" value="normal" />
                    <el-option label="终态 ■" value="final" />
                  </el-select>
                </el-form-item>
                <el-form-item label="颜色">
                  <el-color-picker v-model="selectedElement.data.color" @change="syncNodeData" />
                </el-form-item>
                <el-form-item label="描述">
                  <el-input v-model="selectedElement.data.description" type="textarea" :rows="2" @change="syncNodeData" />
                </el-form-item>
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="配置" name="config">
              <el-form label-position="top" size="small">
                <!-- Dynamic config fields from nodeSchema.configSchema -->
                <template v-if="selectedNodeSchema">
                  <div class="config-section-title">{{ selectedNodeSchema.displayName }} 专属配置</div>
                  <template v-for="(fieldDef, fieldKey) in selectedNodeSchema.configSchema?.properties" :key="fieldKey">
                    <el-form-item :label="fieldDef.description || String(fieldKey)">
                      <!-- Boolean -->
                      <el-switch
                        v-if="fieldDef.type === 'boolean'"
                        v-model="nodeConfig[String(fieldKey)]"
                        @change="syncNodeData"
                      />
                      <!-- Enum/select -->
                      <el-select
                        v-else-if="fieldDef.enum"
                        v-model="nodeConfig[String(fieldKey)]"
                        @change="syncNodeData"
                      >
                        <el-option v-for="opt in fieldDef.enum" :key="opt" :label="opt" :value="opt" />
                      </el-select>
                      <!-- Number -->
                      <el-input-number
                        v-else-if="fieldDef.type === 'integer' || fieldDef.type === 'number'"
                        v-model="nodeConfig[String(fieldKey)]"
                        :min="0"
                        @change="syncNodeData"
                      />
                      <!-- String -->
                      <el-input
                        v-else
                        v-model="nodeConfig[String(fieldKey)]"
                        @change="syncNodeData"
                      />
                    </el-form-item>
                  </template>
                  <el-empty v-if="!selectedNodeSchema.configSchema?.properties || Object.keys(selectedNodeSchema.configSchema.properties).length === 0" description="该节点无专属配置" :image-size="40" />
                </template>
                <el-empty v-else description="请选择有类型的节点" :image-size="40" />
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="高级" name="advanced">
              <el-form label-position="top" size="small">
                <el-form-item label="指派角色（谁执行此步骤）">
                  <el-select v-model="selectedElement.data.assignedRoles" multiple placeholder="选择角色" @change="syncNodeData">
                    <el-option label="工厂管理员" value="factory_admin" />
                    <el-option label="车间主管" value="workshop_supervisor" />
                    <el-option label="调度员" value="dispatcher" />
                    <el-option label="质检员" value="quality_inspector" />
                    <el-option label="仓管员" value="warehouse_manager" />
                    <el-option label="操作员/工人" value="worker" />
                  </el-select>
                </el-form-item>
                <el-form-item label="SLA时间限制（分钟）">
                  <el-input-number v-model="selectedElement.data.timeLimitMinutes" :min="0" :step="30" placeholder="0=不限" @change="syncNodeData" />
                  <div class="field-hint">超过此时长未完成将触发预警</div>
                </el-form-item>
                <el-form-item label="超时自动升级">
                  <el-switch v-model="selectedElement.data.autoEscalate" @change="syncNodeData" />
                  <div class="field-hint">超时后自动通知上级角色</div>
                </el-form-item>
                <el-form-item label="进入条件 (SpEL)">
                  <el-input v-model="selectedElement.data.entryGuard" placeholder="如: #isEquipmentOnline(id)" @change="syncNodeData" />
                  <div class="field-hint">进入此节点前必须满足的条件</div>
                </el-form-item>
                <el-form-item label="进入动作">
                  <el-input v-model="selectedElement.data.onEnterAction" placeholder="如: action:notify_supervisor" @change="syncNodeData" />
                </el-form-item>
                <el-form-item label="退出动作">
                  <el-input v-model="selectedElement.data.onExitAction" placeholder="如: action:log_completion" @change="syncNodeData" />
                </el-form-item>
              </el-form>
            </el-tab-pane>
          </el-tabs>
          <el-divider />
          <el-button type="danger" size="small" text @click="deleteSelected">删除节点</el-button>
        </template>

        <!-- Edge properties -->
        <template v-if="selectedElement.type === 'edge'">
          <el-form label-position="top" size="small">
            <el-form-item label="转换事件名称">
              <el-input v-model="selectedElement.data.event" placeholder="如: approve, reject, complete" @change="syncEdgeData" />
            </el-form-item>
            <el-form-item label="Guard 条件 (SpEL)">
              <el-select v-model="selectedElement.data.guard" clearable filterable allow-create placeholder="选择或输入守卫条件" @change="syncEdgeData">
                <el-option-group label="数量相关">
                  <el-option label="#isCompletedGtePlanned(id)" value="#isCompletedGtePlanned(id)" />
                  <el-option label="#hasNoPendingSupplements(id)" value="#hasNoPendingSupplements(id)" />
                </el-option-group>
                <el-option-group label="质量相关">
                  <el-option label="#isQualityPassed(qualityStatus)" value="#isQualityPassed(qualityStatus)" />
                  <el-option label="#canReleaseWithQuality(factoryId, id)" value="#canReleaseWithQuality(factoryId, id)" />
                  <el-option label="#requiresQualityApproval(factoryId, id)" value="#requiresQualityApproval(factoryId, id)" />
                </el-option-group>
                <el-option-group label="权限相关">
                  <el-option label="#hasPermission(factoryId, role)" value="#hasPermission(factoryId, role)" />
                  <el-option label="#isBusinessHours()" value="#isBusinessHours()" />
                </el-option-group>
                <el-option-group v-if="customGuards.length" label="自定义">
                  <el-option v-for="g in customGuards" :key="g" :label="g" :value="g" />
                </el-option-group>
              </el-select>
              <div class="field-hint">支持SpEL表达式，可自由输入</div>
            </el-form-item>
            <el-form-item label="Action (Drools 规则组)">
              <el-input v-model="selectedElement.data.action" placeholder="如: action:enter_supplementing" @change="syncEdgeData" />
            </el-form-item>
            <el-form-item label="允许触发此转换的角色">
              <el-select v-model="selectedElement.data.roles" multiple placeholder="选择角色（空=不限）" @change="syncEdgeData">
                <el-option label="工厂管理员" value="factory_admin" />
                <el-option label="车间主管" value="workshop_supervisor" />
                <el-option label="调度员" value="dispatcher" />
                <el-option label="质检员" value="quality_inspector" />
                <el-option label="仓管员" value="warehouse_manager" />
                <el-option label="操作员/工人" value="worker" />
                <el-option label="系统自动" value="system" />
              </el-select>
            </el-form-item>
            <el-form-item label="转换描述">
              <el-input v-model="selectedElement.data.description" type="textarea" :rows="2" placeholder="说明此转换的业务含义" @change="syncEdgeData" />
            </el-form-item>
            <el-form-item label="通知配置">
              <el-select v-model="selectedElement.data.notifyRoles" multiple placeholder="转换触发后通知哪些角色" @change="syncEdgeData">
                <el-option label="工厂管理员" value="factory_admin" />
                <el-option label="车间主管" value="workshop_supervisor" />
                <el-option label="质检员" value="quality_inspector" />
              </el-select>
            </el-form-item>
            <el-divider />
            <el-button type="danger" size="small" text @click="deleteSelected">删除连线</el-button>
          </el-form>
        </template>
      </div>

      <!-- Empty state -->
      <div class="properties-panel empty" v-else>
        <div class="empty-hint">
          <el-icon :size="32"><InfoFilled /></el-icon>
          <p>点击节点或连线查看属性</p>
          <p>从左侧拖拽节点到画布添加</p>
        </div>
      </div>
    </div>

    <!-- Simulation Bar -->
    <div v-if="simulationMode" class="simulation-bar">
      <div class="sim-info">
        <el-tag type="info" size="small">模拟模式</el-tag>
        <span>当前: <strong>{{ simCurrentNode?.data?.label || '未开始' }}</strong></span>
        <span v-if="simPath.length">路径: {{ simPath.join(' → ') }}</span>
      </div>
      <div class="sim-actions">
        <el-select v-model="simNextEvent" placeholder="选择事件" size="small" style="width:160px">
          <el-option v-for="t in simAvailableTransitions" :key="t.event" :label="`${t.event} → ${t.targetLabel}`" :value="t.event" />
        </el-select>
        <el-button size="small" type="primary" @click="simStep" :disabled="!simNextEvent">执行</el-button>
        <el-button size="small" @click="simReset">重置</el-button>
      </div>
    </div>

    <!-- Validation Errors Dialog -->
    <el-dialog v-model="showValidationDialog" title="工作流校验结果" width="500px">
      <el-result v-if="!validationErrors.length" icon="success" title="校验通过" sub-title="工作流配置正确，可以发布" />
      <div v-else>
        <el-alert v-for="(err, i) in validationErrors" :key="i" :title="err" type="error" :closable="false" style="margin-bottom: 8px" />
      </div>
    </el-dialog>

    <!-- Version History Dialog -->
    <el-dialog v-model="showVersionHistory" title="版本历史" width="700px">
      <el-table :data="versionHistory" v-loading="loadingHistory" stripe>
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column prop="publishStatus" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.publishStatus === 'published' ? 'success' : row.publishStatus === 'draft' ? 'warning' : 'info'" size="small">
              {{ row.publishStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" />
        <el-table-column label="节点/连线" width="100">
          <template #default="{ row }">
            {{ getVersionStats(row).nodes }}/{{ getVersionStats(row).edges }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="loadVersion(row)">加载</el-button>
            <el-button size="small" text type="info" @click="showDiff(row)">对比当前</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- Diff view -->
      <div v-if="diffResult" class="diff-view">
        <el-divider>v{{ diffResult.version }} vs 当前画布</el-divider>
        <div class="diff-grid">
          <div v-if="diffResult.addedNodes.length" class="diff-section">
            <div class="diff-label added">+ 新增节点 ({{ diffResult.addedNodes.length }})</div>
            <div v-for="n in diffResult.addedNodes" :key="n" class="diff-item added">{{ n }}</div>
          </div>
          <div v-if="diffResult.removedNodes.length" class="diff-section">
            <div class="diff-label removed">- 删除节点 ({{ diffResult.removedNodes.length }})</div>
            <div v-for="n in diffResult.removedNodes" :key="n" class="diff-item removed">{{ n }}</div>
          </div>
          <div v-if="diffResult.addedEdges.length" class="diff-section">
            <div class="diff-label added">+ 新增连线 ({{ diffResult.addedEdges.length }})</div>
            <div v-for="e in diffResult.addedEdges" :key="e" class="diff-item added">{{ e }}</div>
          </div>
          <div v-if="diffResult.removedEdges.length" class="diff-section">
            <div class="diff-label removed">- 删除连线 ({{ diffResult.removedEdges.length }})</div>
            <div v-for="e in diffResult.removedEdges" :key="e" class="diff-item removed">{{ e }}</div>
          </div>
          <div v-if="!diffResult.addedNodes.length && !diffResult.removedNodes.length && !diffResult.addedEdges.length && !diffResult.removedEdges.length" class="diff-none">
            无差异
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive, watch } from 'vue'
import { VueFlow, type Node, type Edge, type Connection } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { Download, Upload, InfoFilled, Clock } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/store/modules/auth'
import {
  getNodeSchemas,
  getPublishedStateMachine,
  getVersionHistory as fetchVersionHistory,
  saveStateMachine,
  publishDraft,
  type NodeSchema,
  type SMState,
  type SMTransition,
  type StateMachineConfig,
} from '@/api/workflow'

import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

const authStore = useAuthStore()
const factoryId = computed(() => authStore.factoryId)

// State
const entityType = ref('PRODUCTION_WORKFLOW')
const nodeSchemas = ref<NodeSchema[]>([])
const nodes = ref<Node[]>([])
const edges = ref<Edge[]>([])
const saving = ref(false)
const currentVersion = ref(0)
const currentPublishStatus = ref('')
const currentSmId = ref<number | null>(null)
const propTab = ref('basic')
const nodeConfig = reactive<Record<string, unknown>>({})
const validationErrors = ref<string[]>([])
const showValidationDialog = ref(false)
const showVersionHistory = ref(false)
const versionHistory = ref<StateMachineConfig[]>([])
const loadingHistory = ref(false)

const selectedElement = ref<{
  type: 'node' | 'edge'
  id: string
  data: Record<string, unknown>
} | null>(null)

// Computed
const groupedSchemas = computed(() => {
  const groups: Record<string, NodeSchema[]> = {}
  for (const schema of nodeSchemas.value) {
    const cat = schema.category || '其他'
    if (!groups[cat]) groups[cat] = []
    groups[cat].push(schema)
  }
  return groups
})

const selectedNodeSchema = computed(() => {
  if (!selectedElement.value || selectedElement.value.type !== 'node') return null
  const schemaType = selectedElement.value.data.nodeSchemaType as string
  return nodeSchemas.value.find(s => s.nodeType === schemaType) || null
})

const customGuards = computed(() => {
  const guards = new Set<string>()
  for (const schema of nodeSchemas.value) {
    if (schema.availableGuards) {
      schema.availableGuards.forEach(g => guards.add(g))
    }
  }
  return Array.from(guards)
})

const publishStatusType = computed(() => {
  const map: Record<string, string> = { published: 'success', draft: 'warning', archived: 'info' }
  return (map[currentPublishStatus.value] || 'info') as 'success' | 'warning' | 'info'
})

const publishStatusLabel = computed(() => {
  const map: Record<string, string> = { published: '已发布', draft: '草稿', archived: '已归档' }
  return map[currentPublishStatus.value] || currentPublishStatus.value
})

const canPublish = computed(() => currentPublishStatus.value === 'draft' && currentSmId.value != null)

// Watch node selection to populate nodeConfig
watch(selectedElement, (el) => {
  if (el?.type === 'node') {
    const config = (el.data.nodeConfig as Record<string, unknown>) || {}
    Object.keys(nodeConfig).forEach(k => delete nodeConfig[k])
    Object.assign(nodeConfig, config)
    // Also populate defaults from schema
    if (selectedNodeSchema.value?.defaultConfig) {
      for (const [k, v] of Object.entries(selectedNodeSchema.value.defaultConfig)) {
        if (nodeConfig[k] === undefined) nodeConfig[k] = v
      }
    }
  }
}, { immediate: true })

// Sync nodeConfig back to selectedElement.data
watch(nodeConfig, () => {
  if (selectedElement.value?.type === 'node') {
    selectedElement.value.data.nodeConfig = { ...nodeConfig }
    syncNodeData()
  }
}, { deep: true })

// Lifecycle
onMounted(async () => {
  await loadNodeSchemas()
  await loadStateMachine()
})

async function loadNodeSchemas() {
  try {
    const res = await getNodeSchemas()
    if (res.success && res.data) {
      nodeSchemas.value = res.data
    }
  } catch (e) {
    console.error('Failed to load node schemas', e)
  }
}

async function loadStateMachine() {
  if (!factoryId.value) return
  try {
    const res = await getPublishedStateMachine(factoryId.value, entityType.value)
    if (res.success && res.data) {
      const sm = res.data
      currentVersion.value = sm.version
      currentPublishStatus.value = sm.publishStatus
      currentSmId.value = sm.id

      const states: SMState[] = JSON.parse(sm.statesJson || '[]')
      const transitions: SMTransition[] = JSON.parse(sm.transitionsJson || '[]')

      buildFlowFromSM(states, transitions)
    } else {
      nodes.value = []
      edges.value = []
      currentVersion.value = 0
      currentPublishStatus.value = ''
      currentSmId.value = null
    }
  } catch (e) {
    console.error('Failed to load state machine', e)
  }
}

function buildFlowFromSM(states: SMState[], transitions: SMTransition[]) {
  const spacing = 240
  const cols = 3

  nodes.value = states.map((s, i) => ({
    id: s.code,
    type: 'workflow',
    position: { x: (i % cols) * spacing + 50, y: Math.floor(i / cols) * 170 + 50 },
    data: {
      label: s.name,
      stateCode: s.code,
      stateType: s.type || 'normal',
      color: s.color || '#409EFF',
      description: s.description || '',
      nodeSchemaType: s.nodeSchemaType || '',
      nodeCategory: s.nodeCategory || '',
      nodeConfig: s.nodeConfig || {},
      assignedRoles: s.assignedRoles || [],
      timeLimitMinutes: s.timeLimitMinutes || 0,
      autoEscalate: s.autoEscalate || false,
      entryGuard: s.entryGuard || '',
      onEnterAction: s.onEnterAction || '',
      onExitAction: s.onExitAction || '',
    },
  }))

  edges.value = transitions.map((t, i) => ({
    id: `e-${i}`,
    source: t.from,
    target: t.to,
    label: t.event,
    animated: !!t.guard,
    data: {
      event: t.event,
      guard: t.guard || '',
      action: t.action || '',
      roles: t.roles || [],
      description: t.description || '',
      notifyRoles: t.notifyRoles || [],
    },
  }))
}

// Drag & Drop
function onDragStart(event: DragEvent, schema: NodeSchema) {
  if (event.dataTransfer) {
    event.dataTransfer.setData('application/vueflow', JSON.stringify(schema))
    event.dataTransfer.effectAllowed = 'move'
  }
}

function onDrop(event: DragEvent) {
  const data = event.dataTransfer?.getData('application/vueflow')
  if (!data) return

  const schema: NodeSchema = JSON.parse(data)
  const canvasRect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const position = {
    x: event.clientX - canvasRect.left,
    y: event.clientY - canvasRect.top,
  }

  const id = `${schema.nodeType}_${Date.now()}`
  nodes.value.push({
    id,
    type: 'workflow',
    position,
    data: {
      label: schema.displayName,
      stateCode: schema.nodeType,
      stateType: 'normal',
      color: schema.color,
      description: schema.description,
      nodeSchemaType: schema.nodeType,
      nodeCategory: schema.category,
      nodeConfig: { ...schema.defaultConfig },
      assignedRoles: [],
      timeLimitMinutes: 0,
      autoEscalate: false,
      entryGuard: '',
      onEnterAction: '',
      onExitAction: '',
    },
  })
}

// Selection
function onNodeClick({ node }: { node: Node }) {
  selectedElement.value = {
    type: 'node',
    id: node.id,
    data: { ...node.data },
  }
  propTab.value = 'basic'
}

function onEdgeClick({ edge }: { edge: Edge }) {
  selectedElement.value = {
    type: 'edge',
    id: edge.id,
    data: { ...(edge.data || {}) },
  }
}

function onConnect(connection: Connection) {
  const id = `e-${Date.now()}`
  edges.value.push({
    id,
    source: connection.source,
    target: connection.target,
    label: '',
    data: { event: '', guard: '', action: '', roles: [], description: '', notifyRoles: [] },
  })
}

function syncNodeData() {
  if (!selectedElement.value || selectedElement.value.type !== 'node') return
  const idx = nodes.value.findIndex(n => n.id === selectedElement.value!.id)
  if (idx !== -1) {
    nodes.value[idx] = { ...nodes.value[idx], data: { ...selectedElement.value.data } }
  }
}

function syncEdgeData() {
  if (!selectedElement.value || selectedElement.value.type !== 'edge') return
  const idx = edges.value.findIndex(e => e.id === selectedElement.value!.id)
  if (idx !== -1) {
    edges.value[idx] = {
      ...edges.value[idx],
      label: selectedElement.value.data.event as string,
      data: { ...selectedElement.value.data },
    }
  }
}

function deleteSelected() {
  if (!selectedElement.value) return
  if (selectedElement.value.type === 'node') {
    const nodeId = selectedElement.value.id
    nodes.value = nodes.value.filter(n => n.id !== nodeId)
    edges.value = edges.value.filter(e => e.source !== nodeId && e.target !== nodeId)
  } else {
    edges.value = edges.value.filter(e => e.id !== selectedElement.value!.id)
  }
  selectedElement.value = null
}

// Validation
function validateWorkflow(): string[] {
  const errors: string[] = []
  const initialNodes = nodes.value.filter(n => n.data.stateType === 'initial')
  const finalNodes = nodes.value.filter(n => n.data.stateType === 'final')

  if (nodes.value.length === 0) errors.push('画布为空，请添加至少一个节点')
  if (initialNodes.length === 0) errors.push('缺少初始状态节点（需要至少一个）')
  if (initialNodes.length > 1) errors.push(`有 ${initialNodes.length} 个初始状态，应该只有1个`)
  if (finalNodes.length === 0) errors.push('缺少终态节点（需要至少一个）')

  // Check orphan nodes (no incoming and no outgoing edges, except initial)
  for (const node of nodes.value) {
    if (node.data.stateType === 'initial') continue
    const hasIncoming = edges.value.some(e => e.target === node.id)
    if (!hasIncoming) errors.push(`节点「${node.data.label}」没有入边（无法到达）`)
  }

  // Check edges without events
  for (const edge of edges.value) {
    if (!edge.data?.event) {
      const src = nodes.value.find(n => n.id === edge.source)
      const tgt = nodes.value.find(n => n.id === edge.target)
      errors.push(`连线 ${src?.data.label} → ${tgt?.data.label} 缺少事件名称`)
    }
  }

  // Check duplicate state codes
  const codes = nodes.value.map(n => n.data.stateCode as string).filter(Boolean)
  const dupes = codes.filter((c, i) => codes.indexOf(c) !== i)
  if (dupes.length) errors.push(`状态代码重复: ${[...new Set(dupes)].join(', ')}`)

  validationErrors.value = errors
  showValidationDialog.value = true
  return errors
}

// Save & Publish
function buildSMJson() {
  const states = nodes.value.map(n => ({
    code: (n.data.stateCode as string) || n.id,
    name: n.data.label as string,
    type: (n.data.stateType as 'initial' | 'normal' | 'final') || 'normal',
    description: (n.data.description as string) || '',
    color: (n.data.color as string) || '#409EFF',
    nodeSchemaType: (n.data.nodeSchemaType as string) || '',
    nodeCategory: (n.data.nodeCategory as string) || '',
    nodeConfig: n.data.nodeConfig || {},
    assignedRoles: n.data.assignedRoles || [],
    timeLimitMinutes: n.data.timeLimitMinutes || 0,
    autoEscalate: n.data.autoEscalate || false,
    entryGuard: n.data.entryGuard || '',
    onEnterAction: n.data.onEnterAction || '',
    onExitAction: n.data.onExitAction || '',
  }))

  const transitions = edges.value.map(e => ({
    from: e.source,
    to: e.target,
    event: (e.data?.event as string) || '',
    guard: (e.data?.guard as string) || undefined,
    action: (e.data?.action as string) || undefined,
    roles: (e.data?.roles as string[]) || [],
    description: (e.data?.description as string) || '',
    notifyRoles: (e.data?.notifyRoles as string[]) || [],
  }))

  return { states, transitions }
}

async function handleSave() {
  if (!factoryId.value) return
  saving.value = true
  try {
    const { states, transitions } = buildSMJson()
    const payload = {
      factoryId: factoryId.value,
      entityType: entityType.value,
      publishStatus: 'draft',
      statesJson: JSON.stringify(states),
      transitionsJson: JSON.stringify(transitions),
    }

    const res = await saveStateMachine(factoryId.value, payload)
    if (res.success && res.data) {
      currentSmId.value = res.data.id
      currentVersion.value = res.data.version
      currentPublishStatus.value = 'draft'
      ElMessage.success('草稿已保存')
    } else {
      ElMessage.error(res.message || '保存失败')
    }
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  } finally {
    saving.value = false
  }
}

async function handlePublish() {
  if (!factoryId.value || !currentSmId.value) return

  // Validate first
  const errors = validateWorkflow()
  if (errors.length > 0) {
    ElMessage.warning('请先修复校验问题再发布')
    return
  }

  try {
    await ElMessageBox.confirm(
      '发布后将替换当前生效的工作流版本，进行中的任务不受影响。确认发布？',
      '发布确认',
      { type: 'warning' }
    )
    const res = await publishDraft(factoryId.value, entityType.value, currentSmId.value)
    if (res.success) {
      currentPublishStatus.value = 'published'
      currentVersion.value = res.data?.version || currentVersion.value
      ElMessage.success('工作流已发布')
    } else {
      ElMessage.error(res.message || '发布失败')
    }
  } catch {
    // User cancelled
  }
}

// Version History
watch(showVersionHistory, async (show) => {
  if (show && factoryId.value) {
    loadingHistory.value = true
    try {
      const res = await fetchVersionHistory(factoryId.value, entityType.value)
      if (res.success && res.data) versionHistory.value = res.data
    } catch { /* ignore */ } finally {
      loadingHistory.value = false
    }
  }
})

function loadVersion(sm: StateMachineConfig) {
  const states = JSON.parse(sm.statesJson || '[]')
  const transitions = JSON.parse(sm.transitionsJson || '[]')
  buildFlowFromSM(states, transitions)
  currentVersion.value = sm.version
  currentPublishStatus.value = sm.publishStatus
  currentSmId.value = sm.id
  showVersionHistory.value = false
  ElMessage.success(`已加载 v${sm.version}`)
}

// Helpers
function stateTypeLabel(type: string) {
  const map: Record<string, string> = { initial: '● 初始', normal: '普通', final: '■ 终态' }
  return map[type] || type
}

function nodeIcon(category: string) {
  const map: Record<string, string> = {
    '计划': '', '执行': '', '质量': '', '审批': '', '完工': '', '控制': '', default: ''
  }
  return map[category] || map.default
}

// ==================== Simulation Mode ====================
const simulationMode = ref(false)
const simCurrentNodeId = ref<string | null>(null)
const simPath = ref<string[]>([])
const simNextEvent = ref('')

const simCurrentNode = computed(() => {
  if (!simCurrentNodeId.value) return null
  return nodes.value.find(n => n.id === simCurrentNodeId.value) || null
})

const simAvailableTransitions = computed(() => {
  if (!simCurrentNodeId.value) return []
  return edges.value
    .filter(e => e.source === simCurrentNodeId.value)
    .map(e => {
      const target = nodes.value.find(n => n.id === e.target)
      return {
        event: (e.data?.event as string) || '(unnamed)',
        target: e.target,
        targetLabel: (target?.data?.label as string) || e.target,
        guard: (e.data?.guard as string) || '',
      }
    })
})

function toggleSimulation() {
  simulationMode.value = !simulationMode.value
  if (simulationMode.value) simReset()
}

function simReset() {
  const initial = nodes.value.find(n => n.data.stateType === 'initial')
  simCurrentNodeId.value = initial?.id || null
  simPath.value = initial ? [initial.data.label as string] : []
  simNextEvent.value = ''
  // Reset node highlight
  nodes.value.forEach(n => { n.data = { ...n.data, simActive: false } })
  if (initial) {
    const idx = nodes.value.findIndex(n => n.id === initial.id)
    if (idx >= 0) nodes.value[idx] = { ...nodes.value[idx], data: { ...nodes.value[idx].data, simActive: true } }
  }
}

function simStep() {
  if (!simNextEvent.value || !simCurrentNodeId.value) return
  const edge = edges.value.find(e => e.source === simCurrentNodeId.value && (e.data?.event as string) === simNextEvent.value)
  if (!edge) return

  // Move to target
  const target = nodes.value.find(n => n.id === edge.target)
  if (!target) return

  // Update highlight
  nodes.value.forEach(n => { n.data = { ...n.data, simActive: false } })
  const idx = nodes.value.findIndex(n => n.id === edge.target)
  if (idx >= 0) nodes.value[idx] = { ...nodes.value[idx], data: { ...nodes.value[idx].data, simActive: true } }

  simCurrentNodeId.value = edge.target
  simPath.value.push(target.data.label as string)
  simNextEvent.value = ''

  // Check if reached final state
  if (target.data.stateType === 'final') {
    ElMessage.success(`模拟完成！路径: ${simPath.value.join(' → ')}`)
  }
}

// ==================== Version Diff ====================
interface DiffResult {
  version: number
  addedNodes: string[]
  removedNodes: string[]
  addedEdges: string[]
  removedEdges: string[]
}

const diffResult = ref<DiffResult | null>(null)

function getVersionStats(sm: StateMachineConfig) {
  try {
    const states = JSON.parse(sm.statesJson || '[]')
    const transitions = JSON.parse(sm.transitionsJson || '[]')
    return { nodes: states.length, edges: transitions.length }
  } catch { return { nodes: 0, edges: 0 } }
}

function showDiff(sm: StateMachineConfig) {
  try {
    const otherStates: SMState[] = JSON.parse(sm.statesJson || '[]')
    const otherTransitions: SMTransition[] = JSON.parse(sm.transitionsJson || '[]')

    const currentCodes = new Set(nodes.value.map(n => (n.data.stateCode as string) || n.id))
    const otherCodes = new Set(otherStates.map(s => s.code))

    const currentEdgeKeys = new Set(edges.value.map(e => `${e.source}→${e.target}:${e.data?.event || ''}`))
    const otherEdgeKeys = new Set(otherTransitions.map(t => `${t.from}→${t.to}:${t.event}`))

    diffResult.value = {
      version: sm.version,
      addedNodes: [...currentCodes].filter(c => !otherCodes.has(c)),
      removedNodes: [...otherCodes].filter(c => !currentCodes.has(c)),
      addedEdges: [...currentEdgeKeys].filter(k => !otherEdgeKeys.has(k)),
      removedEdges: [...otherEdgeKeys].filter(k => !currentEdgeKeys.has(k)),
    }
  } catch {
    ElMessage.error('解析版本数据失败')
  }
}

function getSchemaDisplayName(nodeType: string) {
  return nodeSchemas.value.find(s => s.nodeType === nodeType)?.displayName || nodeType || '未知'
}
</script>

<style scoped>
.workflow-designer {
  height: calc(100vh - 90px);
  display: flex;
  flex-direction: column;
}

.header-card { margin-bottom: 8px; flex-shrink: 0; }

.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-left { display: flex; align-items: center; gap: 12px; }
.header-left h2 { margin: 0; font-size: 18px; }

.header-actions { display: flex; gap: 8px; align-items: center; }

.designer-body { flex: 1; display: flex; gap: 8px; min-height: 0; }

/* Left palette */
.node-palette {
  width: 190px; flex-shrink: 0; background: #fff;
  border: 1px solid #e4e7ed; border-radius: 4px; padding: 12px; overflow-y: auto;
}
.node-palette h4 { margin: 0 0 12px; font-size: 14px; color: #303133; }
.category-group { margin-bottom: 12px; }
.category-label { font-size: 12px; color: #909399; margin-bottom: 6px; font-weight: 600; }

.palette-node {
  display: flex; align-items: center; gap: 8px;
  padding: 8px; margin-bottom: 4px;
  border: 1px solid #e4e7ed; border-radius: 6px;
  cursor: grab; transition: all 0.2s;
}
.palette-node:hover { border-color: #409EFF; background: #f0f7ff; }

.node-icon {
  width: 28px; height: 28px; border-radius: 6px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 14px; color: white;
}
.palette-info { display: flex; flex-direction: column; min-width: 0; }
.palette-name { font-size: 13px; font-weight: 500; }
.palette-desc { font-size: 11px; color: #909399; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* Center canvas */
.canvas-container {
  flex: 1; background: #fafafa;
  border: 1px solid #e4e7ed; border-radius: 4px; min-height: 400px;
}

/* Custom node style */
.workflow-node {
  background: #fff; border: 2px solid #409EFF; border-radius: 8px;
  min-width: 160px; box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08); border-left-width: 5px;
}
.workflow-node.node-initial { border-style: dashed; border-width: 3px; border-left-width: 6px; }
.workflow-node.node-final { border-radius: 4px; border-width: 3px; border-left-width: 6px; }

.node-header {
  padding: 6px 12px; color: #fff; font-size: 13px; font-weight: 600;
  border-radius: 5px 5px 0 0; display: flex; align-items: center; gap: 4px;
}
.node-header-icon { font-size: 14px; }

.node-body { padding: 6px 12px; font-size: 12px; color: #606266; }
.node-status { font-family: monospace; color: #909399; }
.node-type-badge { margin-top: 2px; font-size: 11px; color: #C0C4CC; }
.node-roles-badge { margin-top: 2px; font-size: 11px; color: #409EFF; }
.node-sla-badge { margin-top: 2px; font-size: 11px; color: #E6A23C; }

/* Right properties - wider */
.properties-panel {
  width: 320px; flex-shrink: 0; background: #fff;
  border: 1px solid #e4e7ed; border-radius: 4px; padding: 12px; overflow-y: auto;
}
.properties-panel h4 { margin: 0 0 8px; font-size: 14px; color: #303133; }
.properties-panel.empty { display: flex; align-items: center; justify-content: center; }

.empty-hint { text-align: center; color: #C0C4CC; }
.empty-hint p { margin: 4px 0; font-size: 13px; }

.config-section-title {
  font-size: 13px; font-weight: 600; color: #409EFF;
  margin-bottom: 12px; padding-bottom: 6px; border-bottom: 1px solid #ebeef5;
}

.field-hint { font-size: 11px; color: #909399; margin-top: 2px; }

/* Simulation bar */
.simulation-bar {
  background: #ecf5ff; border: 1px solid #b3d8ff; border-radius: 4px;
  padding: 8px 16px; margin-bottom: 8px;
  display: flex; justify-content: space-between; align-items: center; gap: 16px;
}
.sim-info { display: flex; align-items: center; gap: 12px; font-size: 13px; flex-wrap: wrap; }
.sim-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }

/* Version diff */
.diff-view { margin-top: 16px; }
.diff-grid { display: flex; flex-wrap: wrap; gap: 12px; }
.diff-section { flex: 1; min-width: 200px; }
.diff-label { font-size: 12px; font-weight: 600; margin-bottom: 4px; padding: 4px 8px; border-radius: 4px; }
.diff-label.added { background: #f0f9eb; color: #67c23a; }
.diff-label.removed { background: #fef0f0; color: #f56c6c; }
.diff-item { font-size: 12px; padding: 2px 8px; font-family: monospace; }
.diff-item.added { color: #67c23a; }
.diff-item.removed { color: #f56c6c; text-decoration: line-through; }
.diff-none { color: #909399; font-size: 13px; text-align: center; padding: 12px; }
</style>
