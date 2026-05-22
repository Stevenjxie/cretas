<!--
  RecallEventsList — Sub-tab 4 / 8.

  食品召回事件主表 — 食品安全法第 63 条 强制召回闭环.
  status 流转: INVESTIGATING → NOTIFYING → FROZEN → REPORTED → COMPLETED.

  fool-proof Rule 2: dialog 含 eventCode + 涉事产品类别.
  fool-proof Rule 3: status / triggerReason 用 dropdown (枚举 + 标准选项).
  fool-proof Rule 5: 列表行有 "查看行动" 按钮跳转 timeline.
  AUD-4: PUT 携带 version.
-->
<template>
  <div class="recall-events-list">
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新建召回事件</el-button>
      <el-select
        v-model="statusFilter"
        placeholder="按状态过滤"
        clearable
        style="width: 200px; margin-left: 16px;"
      >
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, key) in RecallStatusLabels"
          :key="key"
          :label="label"
          :value="key"
        />
      </el-select>
      <el-text type="info" size="small" style="margin-left: 12px;">
        共 {{ filteredRows.length }} 起
      </el-text>
    </div>

    <el-table
      v-loading="loading"
      :data="filteredRows"
      class="table"
      empty-text="暂无召回事件"
      stripe
    >
      <el-table-column prop="eventCode" label="召回编号" width="180" />
      <el-table-column prop="affectedProductCategory" label="涉事产品" width="140" show-overflow-tooltip />
      <el-table-column label="触发时间" width="170">
        <template #default="{ row }">
          {{ formatDate(row.triggerTime) }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <el-tag :type="recallTagType(row.status)" size="small">
            {{ recallStatusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="预估损失" width="140">
        <template #default="{ row }">
          <span v-if="row.estimatedLoss != null">¥ {{ row.estimatedLoss }}</span>
          <span v-else class="muted">未填</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openEdit(row)">
            编辑
          </el-button>
          <el-button type="primary" link size="small" @click="openActions(row)">
            查看行动
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Create / Edit dialog -->
    <el-dialog
      v-model="dialogOpen"
      :title="dialogTitle"
      width="640px"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        label-width="120px"
        :rules="formRules"
      >
        <el-form-item label="召回编号" prop="eventCode">
          <el-input
            v-model="form.eventCode"
            placeholder="e.g. RECALL-20260801-001"
            :disabled="dialogMode === 'edit'"
          />
        </el-form-item>
        <el-form-item label="涉事产品" prop="affectedProductCategory">
          <el-input v-model="form.affectedProductCategory" placeholder="e.g. 卤猪蹄" />
        </el-form-item>
        <el-form-item label="触发原因" prop="triggerReason">
          <!-- fool-proof Rule 3: 常见原因 dropdown + 自定义 -->
          <el-select
            v-model="triggerReasonPreset"
            placeholder="选择原因或填写"
            clearable
            style="width: 100%; margin-bottom: 8px;"
            @change="onTriggerReasonPreset"
          >
            <el-option label="客户投诉" value="客户投诉" />
            <el-option label="内部巡检发现" value="内部巡检发现" />
            <el-option label="监管部门通知" value="监管部门通知" />
            <el-option label="供应商主动报告" value="供应商主动报告" />
            <el-option label="HACCP 偏离触发" value="HACCP 偏离触发" />
            <el-option label="其他" value="OTHER" />
          </el-select>
          <el-input
            v-model="form.triggerReason"
            type="textarea"
            :rows="3"
            placeholder="详细描述触发原因 / 已知影响"
          />
        </el-form-item>
        <el-form-item label="状态" prop="status" v-if="dialogMode === 'edit'">
          <el-select v-model="form.status" style="width: 100%">
            <el-option
              v-for="(label, key) in RecallStatusLabels"
              :key="key"
              :label="label"
              :value="key"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="触发用户 ID" prop="triggeredByUserId" v-if="dialogMode === 'create'">
          <el-input-number v-model="form.triggeredByUserId" :min="1" />
        </el-form-item>
        <el-form-item label="预估损失 (元)">
          <el-input-number v-model="form.estimatedLoss" :precision="2" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- Actions timeline dialog -->
    <el-dialog
      v-model="actionsOpen"
      :title="`召回行动 — ${actionsRow?.eventCode || ''}`"
      width="560px"
    >
      <el-timeline v-if="actions.length > 0">
        <el-timeline-item
          v-for="a in actions"
          :key="a.id"
          :timestamp="formatDate(a.createdAt)"
        >
          行动 #{{ a.id }} (事件 #{{ a.recallEventId }})
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="此召回事件暂无行动记录" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  listRecalls,
  createRecall,
  updateRecall,
  listRecallActions,
  type RecallEvent,
  type RecallAction,
  type RecallStatus,
  RecallStatusLabels,
  RecallStatusTagType,
} from '@/api/foodSafetyHub'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const loading = ref(false)
const saving = ref(false)
const rows = ref<RecallEvent[]>([])
const statusFilter = ref<RecallStatus | ''>('')

const dialogOpen = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const editingVersion = ref<number | undefined>(undefined)
const triggerReasonPreset = ref('')

const actionsOpen = ref(false)
const actionsRow = ref<RecallEvent | null>(null)
const actions = ref<RecallAction[]>([])

const blankForm = (): RecallEvent => ({
  eventCode: '',
  triggerReason: '',
  affectedProductCategory: '',
  triggeredByUserId: 1,
  status: 'INVESTIGATING',
  estimatedLoss: null,
})
const form = ref<RecallEvent>(blankForm())
const formRef = ref()
const formRules = {
  eventCode: [{ required: true, message: '请填写召回编号', trigger: 'blur' }],
  affectedProductCategory: [{ required: true, message: '请填写涉事产品类别', trigger: 'blur' }],
  triggerReason: [{ required: true, message: '请填写触发原因', trigger: 'blur' }],
  triggeredByUserId: [{ required: true, message: '请填写触发用户 ID', trigger: 'blur' }],
}

const dialogTitle = computed(() => {
  if (dialogMode.value === 'create') return '新建召回事件'
  // fool-proof Rule 2: 编辑 dialog 标题含 entity 身份
  return `编辑召回 — ${form.value.eventCode || ''} (${form.value.affectedProductCategory || ''})`
})

const filteredRows = computed(() => {
  if (!statusFilter.value) return rows.value
  return rows.value.filter((r) => r.status === statusFilter.value)
})

function recallStatusLabel(s?: string) {
  return s ? RecallStatusLabels[s as RecallStatus] || s : ''
}
function recallTagType(s?: string) {
  return s ? RecallStatusTagType[s as RecallStatus] || 'info' : 'info'
}
function formatDate(s?: string) {
  if (!s) return ''
  return s.replace('T', ' ').substring(0, 19)
}

function onTriggerReasonPreset(v: string) {
  if (v === 'OTHER' || !v) return
  // Prefill textarea (user can extend)
  if (!form.value.triggerReason) form.value.triggerReason = v
}

async function reload() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listRecalls(props.factoryId)
    rows.value = res.success && res.data ? res.data : []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  editingVersion.value = undefined
  triggerReasonPreset.value = ''
  form.value = blankForm()
  dialogOpen.value = true
}

function openEdit(row: RecallEvent) {
  dialogMode.value = 'edit'
  editingId.value = row.id ?? null
  editingVersion.value = row.version
  triggerReasonPreset.value = ''
  form.value = { ...row }
  dialogOpen.value = true
}

async function openActions(row: RecallEvent) {
  if (row.id == null) return
  actionsRow.value = row
  const res = await listRecallActions(props.factoryId, row.id)
  actions.value = res.success && res.data ? res.data : []
  actionsOpen.value = true
}

async function onSave() {
  await formRef.value?.validate()
  saving.value = true
  try {
    if (dialogMode.value === 'create') {
      const res = await createRecall(props.factoryId, form.value)
      if (res.success) {
        dialogOpen.value = false
        await reload()
      }
    } else if (editingId.value != null) {
      const payload = { ...form.value, version: editingVersion.value }
      const res = await updateRecall(props.factoryId, editingId.value, payload)
      if (res.success) {
        dialogOpen.value = false
        await reload()
      }
    }
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void reload()
})
</script>

<style scoped>
.recall-events-list {
  padding: 0;
}
.toolbar {
  margin-bottom: 12px;
  display: flex;
  align-items: center;
}
.table {
  margin-top: 8px;
}
.muted {
  color: #909399;
}
</style>
