<template>
  <div class="property-panel">
    <!-- Node properties -->
    <template v-if="kind === 'node'">
      <h4>{{ nodeTypeLabel }} 属性</h4>
      <el-form label-position="top" size="small" :disabled="props.readOnly" @submit.prevent>
        <div
          v-if="directoryError && (nodeType === 'approval' || nodeType === 'notify')"
          class="directory-error"
        >
          <el-alert
            type="error"
            :closable="false"
            show-icon
            title="审批人员目录加载失败"
            description="暂时不能选择审批角色或人员。请重试；系统不会回退为手工填写角色代码或用户 ID。"
          />
          <el-button size="small" plain @click="loadApprovalDirectory(true)">重新加载</el-button>
        </div>
        <div class="section-heading">基础配置</div>
        <el-form-item label="显示名称">
          <el-input v-model="localData.label" @change="emitUpdate" placeholder="节点标签" />
        </el-form-item>

        <!-- approval — 审批节点 -->
        <template v-if="nodeType === 'approval'">
          <el-form-item label="审批人角色">
            <el-select
              v-model="approverRoles"
              multiple filterable clearable
              :loading="directoryLoading"
              :disabled="Boolean(directoryError)"
              placeholder="请选择审批角色…"
              style="width: 100%"
            >
              <el-option
                v-for="role in approverRoleOptions"
                :key="role.value"
                :label="role.label"
                :value="role.value"
                :disabled="role.disabled"
              >
                <div class="directory-option">
                  <span>{{ role.label }}</span>
                  <small v-if="role.description">{{ role.description }}</small>
                </div>
              </el-option>
            </el-select>
            <div class="hint">从当前工厂角色目录选择，不能手工输入角色代码。</div>
          </el-form-item>
          <el-form-item label="指定审批人（可选）">
            <el-select-v2
              v-model="approverUserIds"
              multiple filterable clearable
              :options="approverUserOptions"
              :loading="directoryLoading"
              :disabled="Boolean(directoryError)"
              placeholder="按姓名或账号搜索…"
              style="width: 100%"
            />
            <div class="hint">明确指定人员优先用于精确路由；未指定时按上方角色匹配。</div>
          </el-form-item>
          <el-form-item label="必需审批人数">
            <el-input-number
              v-model="requiredApprovers"
              :min="1" :max="20"
              @change="(v: number | undefined) => syncConfig({ requiredApprovers: v ?? 1 })"
            />
            <div class="hint">填写 2 人及以上时按会签处理。</div>
          </el-form-item>

          <button
            type="button"
            class="advanced-toggle"
            :aria-expanded="advancedOpen"
            @click="advancedOpen = !advancedOpen"
          >
            {{ advancedOpen ? '收起高级配置' : '展开高级配置' }}
          </button>
          <div v-show="advancedOpen" class="advanced-section">
            <div class="section-heading">高级配置</div>
            <el-alert
              class="self-approval-note"
              type="info"
              :closable="false"
              title="自审规则"
              description="只有“指定审批人”明确包含发起人本人时才允许自审；仅角色相同不会放开自审。"
            />
            <el-form-item label="审批时限（分钟）">
              <el-input-number
                v-model="timeoutMinutes"
                :min="0" :step="30"
                @change="(v: number | undefined) => syncConfig({ timeoutMinutes: v ?? 0 })"
              />
              <div class="hint">填写 0 表示不设置超时。</div>
            </el-form-item>
            <el-form-item label="限定部门（可选）">
              <el-select
                v-model="departmentIds"
                multiple filterable clearable
                placeholder="选择审批人所属部门"
                :loading="deptLoading"
                :disabled="Boolean(deptError) || props.readOnly"
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
              <div class="hint">留空表示全工厂范围。</div>
              <div v-if="deptError" class="hint warn">
                部门目录加载失败。
                <el-button link type="warning" @click="loadDepartments">重新加载</el-button>
              </div>
            </el-form-item>
            <el-form-item label="超时转派（可选）">
              <el-select-v2
                v-model="delegateUserId"
                filterable clearable
                :options="delegateUserOptions"
                :loading="directoryLoading"
                :disabled="Boolean(directoryError) || props.readOnly"
                placeholder="请选择转派人员…"
                style="width: 100%"
              />
              <div class="hint">审批超时后转派给当前工厂的有效人员。</div>
            </el-form-item>
            <template v-if="!props.businessMode">
              <el-form-item label="自动通过条件">
                <el-input
                  v-model="autoApproveCondition"
                  @change="syncConfig({ autoApproveCondition })"
                />
              </el-form-item>
              <el-form-item label="自动拒绝条件">
                <el-input
                  v-model="autoRejectCondition"
                  @change="syncConfig({ autoRejectCondition })"
                />
              </el-form-item>
            </template>
            <el-alert
              v-else-if="hasAdvancedApprovalConditions"
              type="warning"
              :closable="false"
              title="此节点包含平台级自动判断，业务页面仅保留不展示。"
            />
          </div>
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
            <div v-else-if="props.businessMode" class="hint">选择对应连线设置业务分流条件。</div>
            <div v-else class="hint">平台管理员可配置结构化规则或高级表达式。</div>
          </el-form-item>
          <el-form-item v-if="!isSalesOrderDecision && !props.businessMode">
            <el-button type="primary" plain size="small" @click="$emit('manage-rules')">
              管理流转规则
            </el-button>
            <div class="hint">配置金额、部门、角色和平台级规则。</div>
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
              multiple filterable clearable
              :loading="directoryLoading"
              :disabled="Boolean(directoryError)"
              placeholder="请选择通知角色…"
              style="width: 100%"
            >
              <el-option
                v-for="role in notifyRoleOptions"
                :key="role.value"
                :label="role.label"
                :value="role.value"
                :disabled="role.disabled"
              />
            </el-select>
            <div class="hint">通知对象来自当前工厂角色目录，不能手工新增。</div>
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
          <button
            type="button"
            class="advanced-toggle"
            :aria-expanded="advancedOpen"
            @click="advancedOpen = !advancedOpen"
          >
            {{ advancedOpen ? '收起高级配置' : '展开高级配置' }}
          </button>
          <div v-show="advancedOpen" class="advanced-section">
            <div class="section-heading">高级配置</div>
            <el-form-item label="通知模板（可选）">
              <el-input
                v-model="notifyTemplate"
                placeholder="选择或填写通知模板"
                @change="syncConfig({ notifyTemplate })"
              />
            </el-form-item>
          </div>
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
      <el-button
        v-if="nodeType !== 'start' && !props.readOnly"
        type="danger"
        size="small"
        text
        @click="$emit('delete')"
      >
        删除节点
      </el-button>
    </template>

    <!-- Edge properties -->
    <template v-if="kind === 'edge'">
      <h4>边属性</h4>
      <el-form label-position="top" size="small" :disabled="props.readOnly">
        <div class="section-heading">基础配置</div>
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
        <el-form-item v-else-if="!isSalesOrderDecision && !props.businessMode" label="条件 (SpEL)">
          <el-input
            :model-value="(localData.condition as string) ?? ''"
            placeholder="如: #amount > 10000"
            @change="(v: string | number) => updateEdgeCondition(String(v))"
          />
          <div class="hint">空 = 总是走 (无条件)</div>
        </el-form-item>
        <el-alert
          v-else-if="!isSalesOrderDecision && hasAdvancedEdgeCondition"
          type="warning"
          :closable="false"
          show-icon
          title="此连线包含高级判断条件"
          description="业务配置页不会显示表达式内容。需要调整时请由平台管理员在高级模式处理。"
        />
        <button
          type="button"
          class="advanced-toggle"
          :aria-expanded="advancedOpen"
          @click="advancedOpen = !advancedOpen"
        >
          {{ advancedOpen ? '收起高级配置' : '展开高级配置' }}
        </button>
        <div v-show="advancedOpen" class="advanced-section">
          <div class="section-heading">高级配置</div>
          <el-form-item label="分支优先级">
            <el-input-number
              :model-value="Number(localData.priority ?? 0)"
              :min="0"
              @change="(v: number | undefined) => updateEdgePriority(v ?? 0)"
            />
            <div class="hint">数字越小越先判断。</div>
          </el-form-item>
        </div>
      </el-form>
      <el-divider />
      <el-button v-if="!props.readOnly" type="danger" size="small" text @click="$emit('delete')">
        删除连线
      </el-button>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  getApprovalDirectory,
  type ApprovalRoleDirectoryItem,
  type ApprovalUserDirectoryItem,
  type DecisionType,
  type NodeType,
} from '@/api/approvalWorkflow'
import { useAuthStore } from '@/store/modules/auth'
import { get } from '@/api/request'
import {
  buildRoleOptions,
  buildUserOptions,
  labelsForValues,
  type DirectoryOption,
} from '../lib/approvalDirectory'
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

const props = defineProps<{
  element: SelectedElement
  decisionType?: DecisionType
  businessMode?: boolean
  readOnly?: boolean
}>()

const emit = defineEmits<{
  update: [data: Record<string, unknown>]
  delete: []
  'manage-rules': []
}>()

const kind = computed(() => props.element.kind)
const nodeType = computed<NodeType | undefined>(() => props.element.type)
const isSalesOrderDecision = computed(() => props.decisionType === 'SALES_ORDER_APPROVAL')
const advancedOpen = ref(false)

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
const hasAdvancedEdgeCondition = computed(() => Boolean(String(localData.condition ?? '').trim()))

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
  set: (v: string[]) => syncConfig({
    approverRoles: v,
    approverRoleLabels: labelsForValues(approverRoleOptions.value, v),
  }),
})
const approverUserIds = computed({
  get: () => ((config.value.approverUserIds as Array<string | number>) ?? []).map(String),
  set: (v: string[]) => syncConfig({
    approverUserIds: v,
    approverUserLabels: labelsForValues(approverUserOptions.value, v),
  }),
})
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
const hasAdvancedApprovalConditions = computed(() => (
  Boolean(autoApproveCondition.value.trim()) || Boolean(autoRejectCondition.value.trim())
))
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
  set: (v: string[]) => syncConfig({
    notifyRoles: v,
    notifyRoleLabels: labelsForValues(notifyRoleOptions.value, v),
  }),
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
const deptError = ref('')
const authStore = useAuthStore()
async function loadDepartments() {
  const factoryId = authStore.factoryId
  if (!factoryId) return
  deptLoading.value = true
  deptError.value = ''
  try {
    const res = await get(`/${factoryId}/departments/active`)
    if (res.success && Array.isArray(res.data)) {
      deptOptions.value = (res.data as Array<{ id: number; name: string }>).map(d => ({ id: d.id, name: d.name }))
    }
  } catch (e) {
    deptError.value = e instanceof Error ? e.message : '部门目录加载失败'
  } finally {
    deptLoading.value = false
  }
}
const directoryLoading = ref(false)
const directoryError = ref('')
const directoryRoles = ref<ApprovalRoleDirectoryItem[]>([])
const directoryUsers = ref<ApprovalUserDirectoryItem[]>([])

const approverRoleOptions = computed<DirectoryOption[]>(() => (
  buildRoleOptions(directoryRoles.value, approverRoles.value)
))
const notifyRoleOptions = computed<DirectoryOption[]>(() => (
  buildRoleOptions(directoryRoles.value, notifyRoles.value)
))
const approverUserOptions = computed<DirectoryOption[]>(() => (
  buildUserOptions(directoryUsers.value, approverUserIds.value)
))
const delegateUserOptions = computed<DirectoryOption[]>(() => (
  buildUserOptions(
    directoryUsers.value,
    config.value.delegateUserId ? [String(config.value.delegateUserId)] : [],
  )
))

async function loadApprovalDirectory(forceRefresh = false) {
  const factoryId = authStore.factoryId
  if (!factoryId) {
    directoryError.value = '未识别当前工厂，无法加载审批人员目录。'
    return
  }
  directoryLoading.value = true
  directoryError.value = ''
  try {
    const directory = await getApprovalDirectory(factoryId, forceRefresh)
    directoryRoles.value = directory.roles
    directoryUsers.value = directory.users
  } catch (error) {
    directoryError.value = error instanceof Error ? error.message : '目录加载失败'
  } finally {
    directoryLoading.value = false
  }
}

onMounted(() => {
  if (nodeType.value === 'approval') {
    void Promise.all([loadDepartments(), loadApprovalDirectory()])
  } else if (nodeType.value === 'notify') {
    void loadApprovalDirectory()
  }
})

const departmentIds = computed({
  get: () => (config.value.departmentIds as number[]) ?? [],
  set: (v: number[]) => syncConfig({ departmentIds: v }),
})

// Phase 1 B.5 Task 2: 委托人 userId (string for input v-model; backend can parse)
const delegateUserId = computed({
  get: () => String(config.value.delegateUserId ?? ''),
  set: (v: string) => syncConfig({
    delegateUserId: v,
    delegateUserLabel: v
      ? labelsForValues(delegateUserOptions.value, [v])[0]
      : '',
  }),
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
.section-heading {
  margin: 2px 0 10px;
  color: #1a2332;
  font-size: 13px;
  font-weight: 650;
}
.advanced-toggle {
  width: 100%;
  min-height: 34px;
  margin: 4px 0 10px;
  border: 1px solid #d9e5f2;
  border-radius: 7px;
  background: #f7f9fc;
  color: #1b65a8;
  cursor: pointer;
  font-weight: 600;
}
.advanced-toggle:hover {
  border-color: #409eff;
  background: #eef6ff;
}
.advanced-toggle:focus-visible {
  outline: 2px solid #409eff;
  outline-offset: 2px;
}
.advanced-section {
  margin-bottom: 10px;
  padding: 10px;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  background: #fafbfd;
}
.hint { font-size: 11px; color: #909399; margin-top: 2px; }
.hint.warn { color: #e6a23c; font-weight: 500; }
.directory-error,
.self-approval-note { margin-bottom: 12px; }
.directory-error {
  display: grid;
  gap: 8px;
  justify-items: start;
}
.directory-option {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.directory-option span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.directory-option small {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
