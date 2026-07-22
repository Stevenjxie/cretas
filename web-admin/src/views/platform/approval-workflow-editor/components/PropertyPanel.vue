<template>
  <div class="property-panel">
    <!-- Node properties -->
    <template v-if="kind === 'node'">
      <h4>{{ nodeTypeLabel }} 属性</h4>
      <el-form label-position="top" size="small" @submit.prevent>
        <!-- Common: label -->
        <el-form-item label="显示名">
          <el-input v-model="localData.label" @change="emitUpdate" placeholder="节点标签" />
        </el-form-item>

        <!-- approval — 审批节点 -->
        <template v-if="nodeType === 'approval'">
          <el-form-item label="审批人角色">
            <el-select
              v-model="approverRoles"
              multiple filterable allow-create
              placeholder="选择或输入审批角色"
              style="width: 100%"
              @change="syncConfig({ approverRoles })"
            >
              <el-option v-for="r in ROLE_OPTIONS" :key="r.value" :label="r.label" :value="r.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="审批人 userId (可选)">
            <el-input
              :model-value="(approverUserIds || []).join(',')"
              placeholder="逗号分隔 e.g. 100,101"
              @change="(v: string | number) => syncConfig({ approverUserIds: String(v).split(',').map(s => s.trim()).filter(Boolean) })"
            />
          </el-form-item>
          <el-form-item label="必需审批人数 (≥2 即会签)">
            <el-input-number
              v-model="requiredApprovers"
              :min="1" :max="20"
              @change="(v: number | undefined) => syncConfig({ requiredApprovers: v ?? 1 })"
            />
          </el-form-item>
          <el-form-item label="超时 (分钟, 0 = 不超时)">
            <el-input-number
              v-model="timeoutMinutes"
              :min="0" :step="30"
              @change="(v: number | undefined) => syncConfig({ timeoutMinutes: v ?? 0 })"
            />
          </el-form-item>
          <!-- Phase 1 B.5 Task 1: 部门选择器 (可选) -->
          <el-form-item label="部门 (可选)">
            <el-select
              v-model="departmentIds"
              multiple filterable clearable
              placeholder="选择审批所属部门 (限定审批人范围)"
              :loading="deptLoading"
              style="width: 100%"
              @change="(v: number[]) => syncConfig({ departmentIds: v })"
            >
              <el-option
                v-for="dept in deptOptions"
                :key="dept.id"
                :label="dept.name"
                :value="dept.id"
              />
            </el-select>
            <div class="hint">仅这些部门下的角色用户可审批; 留空 = 全工厂范围</div>
          </el-form-item>
          <!-- Phase 1 B.5 Task 2: 委托人 (可选) -->
          <el-form-item label="委托人 (可选 — 主审超时后转派)">
            <el-input
              v-model="delegateUserId"
              placeholder="userId (如: 100)"
              @change="syncConfig({ delegateUserId })"
            />
            <div class="hint">主审 SLA 超时后, 自动转派给该 userId; Sprint 4 B.4 后端会消费此字段</div>
          </el-form-item>
          <el-form-item label="自动通过条件 (SpEL)">
            <el-input
              v-model="autoApproveCondition"
              placeholder="如: #trusted == true"
              @change="syncConfig({ autoApproveCondition })"
            />
            <div class="hint">满足时跳过审批人, 直接通过</div>
          </el-form-item>
          <el-form-item label="自动拒绝条件 (SpEL)">
            <el-input
              v-model="autoRejectCondition"
              placeholder="如: #amount > 1000000"
              @change="syncConfig({ autoRejectCondition })"
            />
            <div class="hint">满足时直接拒绝整个工作流</div>
          </el-form-item>
        </template>

        <!-- condition — 条件分叉 -->
        <template v-if="nodeType === 'condition'">
          <el-form-item label="说明">
            <el-input
              v-model="description"
              type="textarea" :rows="2"
              placeholder="如: 按金额阈值分流"
              @change="syncConfig({ description })"
            />
            <div v-if="isSalesOrderDecision" class="hint">销售订单分支只使用运行引擎读取的连线条件，不使用独立 WorkflowRule。</div>
            <div v-else class="hint">分支条件可走两种配置: (1) 结构化 WorkflowRule (推荐); (2) edge.condition (raw SpEL)</div>
          </el-form-item>
          <el-form-item v-if="!isSalesOrderDecision">
            <el-button type="primary" plain size="small" @click="$emit('manage-rules')">
              管理流转规则 (WorkflowRule)
            </el-button>
            <div class="hint">Sprint 4 Wave 1 (C-WF-RULE-1) — 金额/部门/角色/SpEL 4 类规则, executor 优先评估</div>
          </el-form-item>
          <el-alert
            v-else
            type="info"
            :closable="false"
            show-icon
            title="销售订单金额分流由连线条件决定。请选择通向审批节点的连线设置金额阈值。"
          />
        </template>

        <!-- parallel — 并行 -->
        <template v-if="nodeType === 'parallel'">
          <el-form-item label="说明">
            <el-input
              v-model="description"
              type="textarea" :rows="2"
              placeholder="如: 启动质检和采购同时审批"
              @change="syncConfig({ description })"
            />
            <div class="hint">所有 outgoing 分支同时启动, 必须配套 join 节点汇聚</div>
          </el-form-item>
        </template>

        <!-- join — 汇聚 -->
        <template v-if="nodeType === 'join'">
          <el-form-item label="汇聚模式">
            <el-select v-model="joinMode" @change="syncConfig({ mode: joinMode })">
              <el-option label="ALL — 所有分支到达" value="ALL" />
              <el-option label="N_OF_M — N 个分支到达" value="N_OF_M" />
              <el-option label="ANY — 任一分支到达" value="ANY" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="joinMode === 'N_OF_M'" label="N (需要到达的分支数)">
            <el-input-number
              v-model="joinN"
              :min="2"
              @change="(v: number | undefined) => syncConfig({ n: v ?? 2 })"
            />
          </el-form-item>
        </template>

        <!-- notify — 通知 -->
        <template v-if="nodeType === 'notify'">
          <el-form-item label="通知对象">
            <el-select
              v-model="notifyRoles"
              multiple filterable allow-create
              placeholder="选择角色"
              style="width: 100%"
              @change="syncConfig({ notifyRoles })"
            >
              <el-option v-for="r in ROLE_OPTIONS" :key="r.value" :label="r.label" :value="r.value" />
            </el-select>
          </el-form-item>
          <!-- Phase 1 B.5 Task 3: 通知渠道 (微信 / 钉钉 / 邮件) -->
          <el-form-item label="通知渠道">
            <el-checkbox-group
              v-model="notifyChannels"
              @change="(v: string[]) => syncConfig({ channels: v })"
            >
 <el-checkbox label="wechat"> 微信</el-checkbox>
 <el-checkbox label="dingtalk"> 钉钉</el-checkbox>
 <el-checkbox label="email"> 邮件</el-checkbox>
            </el-checkbox-group>
            <div v-if="notifyChannels.length === 0" class="hint warn">
 未选渠道, 通知不会发送
            </div>
            <div v-else class="hint">已选 {{ notifyChannels.length }} 个渠道</div>
          </el-form-item>
          <el-form-item label="通知模板 (可选)">
            <el-input
              v-model="notifyTemplate"
              placeholder="预留 Sprint 4 NotificationTemplate id"
              @change="syncConfig({ notifyTemplate })"
            />
          </el-form-item>
        </template>

        <!-- end — 结束 -->
        <template v-if="nodeType === 'end'">
          <el-form-item label="终态结果">
            <el-select v-model="outcome" @change="syncConfig({ outcome })">
              <el-option label="APPROVED — 通过" value="APPROVED" />
              <el-option label="REJECTED — 拒绝" value="REJECTED" />
              <el-option label="TIMEOUT — 超时" value="TIMEOUT" />
              <el-option label="CANCELLED — 取消" value="CANCELLED" />
            </el-select>
          </el-form-item>
        </template>

        <!-- start — 入口 -->
        <template v-if="nodeType === 'start'">
          <el-alert type="info" :closable="false" title="入口节点无额外配置" />
        </template>
      </el-form>

      <el-divider />
      <el-button v-if="nodeType !== 'start'" type="danger" size="small" text @click="$emit('delete')">
        删除节点
      </el-button>
    </template>

    <!-- Edge properties -->
    <template v-if="kind === 'edge'">
      <h4>边属性</h4>
      <el-form label-position="top" size="small">
        <el-form-item label="标签">
          <el-input v-model="localData.label" @change="emitUpdate" placeholder="如: >10000 / DEFAULT" />
          <div class="hint">设 label=DEFAULT 时, condition 节点优先评估其他 edge 后兜底走这里</div>
        </el-form-item>
        <el-form-item v-if="isSalesAmountThresholdEdge" label="销售订单金额阈值（元）">
          <el-input-number
            :model-value="salesAmountThreshold"
            :min="0"
            :precision="2"
            :step="100"
            :disabled="hasCustomSalesCondition"
            controls-position="right"
            style="width: 100%"
            placeholder="请输入进入该分支的金额阈值"
            @change="updateSalesAmountThreshold"
          />
          <div class="hint">订单金额大于该值时进入此分支；保存后直接写入运行引擎读取的连线条件。</div>
          <div v-if="hasCustomSalesCondition" class="hint warn">
            当前连线包含自定义条件，数字输入不会自动覆盖它。
            <el-button link type="warning" @click="clearCustomSalesCondition">改用金额阈值</el-button>
          </div>
        </el-form-item>
        <el-form-item v-else-if="!isSalesOrderDecision" label="条件 (SpEL)">
          <el-input
            :model-value="(localData.condition as string) ?? ''"
            placeholder="如: #amount > 10000"
            @change="(v: string | number) => updateEdgeCondition(String(v))"
          />
          <div class="hint">空 = 总是走 (无条件)</div>
        </el-form-item>
        <el-form-item label="优先级 (数值越小越优先)">
          <el-input-number
            :model-value="Number(localData.priority ?? 0)"
            :min="0"
            @change="(v: number | undefined) => updateEdgePriority(v ?? 0)"
          />
        </el-form-item>
      </el-form>
      <el-divider />
      <el-button type="danger" size="small" text @click="$emit('delete')">删除边</el-button>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type { DecisionType, NodeType } from '@/api/approvalWorkflow'
import { useAuthStore } from '@/store/modules/auth'
import { get } from '@/api/request'
import {
  buildSalesApprovalAmountCondition,
  parseSalesApprovalAmountThreshold,
} from '../lib/salesApprovalCondition'

interface SelectedElement {
  kind: 'node' | 'edge'
  id: string
  type?: NodeType
  data: Record<string, unknown>
}

const props = defineProps<{ element: SelectedElement; decisionType?: DecisionType }>()

const emit = defineEmits<{
  update: [data: Record<string, unknown>]
  delete: []
  'manage-rules': []
}>()

const kind = computed(() => props.element.kind)
const nodeType = computed<NodeType | undefined>(() => props.element.type)
const isSalesOrderDecision = computed(() => props.decisionType === 'SALES_ORDER_APPROVAL')

const NODE_TYPE_LABELS: Record<NodeType, string> = {
  start: '开始',
  approval: '审批',
  condition: '条件',
  parallel: '并行',
  join: '汇聚',
  notify: '通知',
  end: '结束',
}
const nodeTypeLabel = computed(() => (nodeType.value ? NODE_TYPE_LABELS[nodeType.value] : ''))

const ROLE_OPTIONS = [
  { value: 'factory_super_admin', label: '工厂管理员 (factory_super_admin)' },
  { value: 'factory_admin', label: '工厂 admin (factory_admin)' },
  { value: 'department_admin', label: '部门管理员 (department_admin)' },
  { value: 'workshop_supervisor', label: '车间主管 (workshop_supervisor)' },
  { value: 'quality_inspector', label: '质检员 (quality_inspector)' },
  { value: 'finance_manager', label: '财务主管 (finance_manager)' },
  { value: 'warehouse_manager', label: '仓管员 (warehouse_manager)' },
  { value: 'permission_admin', label: '权限管理员 (permission_admin)' },
]

// Local mutable copy of selected element data — emitted on change
const localData = reactive<Record<string, unknown>>({ ...props.element.data })
const isDefaultEdge = computed(() => String(localData.label ?? '').trim().toUpperCase() === 'DEFAULT')
const isSalesAmountThresholdEdge = computed(() => (
  isSalesOrderDecision.value
  && Boolean(localData.salesAmountThresholdEligible)
  && !isDefaultEdge.value
))
const salesAmountThreshold = computed(() => parseSalesApprovalAmountThreshold(localData.condition))
const hasCustomSalesCondition = computed(() => (
  isSalesAmountThresholdEdge.value
  && Boolean(String(localData.condition ?? '').trim())
  && salesAmountThreshold.value === null
))

watch(
  () => props.element,
  (next) => {
    Object.keys(localData).forEach(k => delete localData[k])
    Object.assign(localData, next.data)
  },
)

// Per-node-type config getters mapped through localData.config map
const config = computed(() => {
  const c = (localData.config as Record<string, unknown>) ?? {}
  return c
})
const approverRoles = computed({
  get: () => (config.value.approverRoles as string[]) ?? [],
  set: (v: string[]) => syncConfig({ approverRoles: v }),
})
const approverUserIds = computed(() => (config.value.approverUserIds as string[]) ?? [])
const requiredApprovers = computed({
  get: () => Number(config.value.requiredApprovers ?? 1),
  set: (v: number) => syncConfig({ requiredApprovers: v }),
})
const timeoutMinutes = computed({
  get: () => Number(config.value.timeoutMinutes ?? 0),
  set: (v: number) => syncConfig({ timeoutMinutes: v }),
})
const autoApproveCondition = computed({
  get: () => String(config.value.autoApproveCondition ?? ''),
  set: (v: string) => syncConfig({ autoApproveCondition: v }),
})
const autoRejectCondition = computed({
  get: () => String(config.value.autoRejectCondition ?? ''),
  set: (v: string) => syncConfig({ autoRejectCondition: v }),
})
const description = computed({
  get: () => String(config.value.description ?? ''),
  set: (v: string) => syncConfig({ description: v }),
})
const joinMode = computed({
  get: () => String(config.value.mode ?? 'ALL'),
  set: (v: string) => syncConfig({ mode: v }),
})
const joinN = computed({
  get: () => Number(config.value.n ?? 2),
  set: (v: number) => syncConfig({ n: v }),
})
const notifyRoles = computed({
  get: () => (config.value.notifyRoles as string[]) ?? [],
  set: (v: string[]) => syncConfig({ notifyRoles: v }),
})
const notifyTemplate = computed({
  get: () => String(config.value.notifyTemplate ?? ''),
  set: (v: string) => syncConfig({ notifyTemplate: v }),
})
// Phase 1 B.5 Task 3: notify channels (wechat / dingtalk / email)
const notifyChannels = computed({
  get: () => (config.value.channels as string[]) ?? [],
  set: (v: string[]) => syncConfig({ channels: v }),
})
const outcome = computed({
  get: () => String(config.value.outcome ?? 'APPROVED'),
  set: (v: string) => syncConfig({ outcome: v }),
})

// Phase 1 B.5 Task 1: 部门选择器
interface DeptOption { id: number; name: string }
const deptOptions = ref<DeptOption[]>([])
const deptLoading = ref(false)
const authStore = useAuthStore()
async function loadDepartments() {
  const factoryId = authStore.factoryId
  if (!factoryId) return
  deptLoading.value = true
  try {
    const res = await get(`/${factoryId}/departments/active`)
    if (res.success && Array.isArray(res.data)) {
      deptOptions.value = (res.data as Array<{ id: number; name: string }>).map(d => ({ id: d.id, name: d.name }))
    }
  } catch (e) {
    // silent — fall back to empty list (selector still usable for manual ids)
    console.warn('[PropertyPanel] load departments failed', e)
  } finally {
    deptLoading.value = false
  }
}
onMounted(() => { loadDepartments() })

const departmentIds = computed({
  get: () => (config.value.departmentIds as number[]) ?? [],
  set: (v: number[]) => syncConfig({ departmentIds: v }),
})

// Phase 1 B.5 Task 2: 委托人 userId (string for input v-model; backend can parse)
const delegateUserId = computed({
  get: () => String(config.value.delegateUserId ?? ''),
  set: (v: string) => syncConfig({ delegateUserId: v }),
})

function syncConfig(patch: Record<string, unknown>) {
  const merged = { ...(localData.config as Record<string, unknown>), ...patch }
  // strip empty-string keys for cleanliness
  Object.keys(merged).forEach(k => {
    if (merged[k] === '' || merged[k] == null) delete merged[k]
  })
  localData.config = merged
  emitUpdate()
}

function updateEdgeCondition(v: string) {
  localData.condition = v
  emitUpdate()
}
function updateSalesAmountThreshold(v: number | undefined) {
  if (v == null || hasCustomSalesCondition.value) return
  localData.condition = buildSalesApprovalAmountCondition(v)
  localData.label = `金额大于 ${v} 元`
  emitUpdate()
}
function clearCustomSalesCondition() {
  localData.condition = ''
  emitUpdate()
}
function updateEdgePriority(v: number) {
  localData.priority = v
  emitUpdate()
}

function emitUpdate() {
  emit('update', { ...localData })
}
</script>

<style scoped>
.property-panel { padding: 12px; }
h4 { margin: 0 0 12px; font-size: 14px; color: #303133; }
.hint { font-size: 11px; color: #909399; margin-top: 2px; }
.hint.warn { color: #e6a23c; font-weight: 500; }
</style>
