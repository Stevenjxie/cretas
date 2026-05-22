<!--
  HaccpCheckpointsList — Sub-tab 1 / 8.

  HACCP 关键控制点 (Critical Control Point) 完整 CRUD.
  fool-proof Rule 2: dialog header 显示 checkpointCode + name.
  fool-proof Rule 3: hazardType 用 dropdown.
  fool-proof Rule 4: 重复 code 创建 → 409 + 跳编辑.
  AUD-4: PUT 携带 version 字段, 后端做乐观锁检查.
-->
<template>
  <div class="haccp-checkpoints-list">
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新建 CCP</el-button>
      <el-checkbox v-model="activeOnly" @change="reload" style="margin-left: 16px;">
        仅显示启用
      </el-checkbox>
      <el-input
        v-model="search"
        placeholder="搜索编码 / 名称"
        clearable
        style="width: 240px; margin-left: 12px;"
      />
    </div>

    <el-table
      v-loading="loading"
      :data="filteredRows"
      class="table"
      empty-text="暂无 HACCP 关键控制点 (CCP) 配置"
      stripe
    >
      <el-table-column prop="checkpointCode" label="编码" width="120" />
      <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
      <el-table-column label="危害类型" width="140">
        <template #default="{ row }">
          <el-tag :type="hazardTagType(row.hazardType)" size="small">
            {{ hazardLabel(row.hazardType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="临界限值" width="180">
        <template #default="{ row }">
          {{ row.criticalLimitMin }} ~ {{ row.criticalLimitMax }} {{ row.unit }}
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.active" type="success" size="small">启用</el-tag>
          <el-tag v-else type="info" size="small">已停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="170">
        <template #default="{ row }">
          {{ formatDate(row.updatedAt) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openEdit(row)">
            编辑
          </el-button>
          <el-button type="danger" link size="small" @click="confirmDelete(row)">
            停用
          </el-button>
        </template>
      </el-table-column>
    </el-table>

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
        <el-form-item label="CCP 编码" prop="checkpointCode">
          <el-input
            v-model="form.checkpointCode"
            placeholder="e.g. CCP-01"
            :disabled="dialogMode === 'edit'"
          />
        </el-form-item>
        <el-form-item label="CCP 名称" prop="name">
          <el-input v-model="form.name" placeholder="e.g. 中心温度 / 冷却时间" />
        </el-form-item>
        <el-form-item label="危害类型" prop="hazardType">
          <el-select v-model="form.hazardType" style="width: 100%">
            <el-option
              v-for="t in HAZARD_TYPES"
              :key="t.value"
              :label="t.label"
              :value="t.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="临界限值下限" prop="criticalLimitMin">
          <el-input-number v-model="form.criticalLimitMin" :precision="4" />
        </el-form-item>
        <el-form-item label="临界限值上限" prop="criticalLimitMax">
          <el-input-number v-model="form.criticalLimitMax" :precision="4" />
        </el-form-item>
        <el-form-item label="单位" prop="unit">
          <el-input v-model="form.unit" placeholder="e.g. ℃ / min / mg/kg" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="监控程序">
          <el-input
            v-model="form.monitoringProcedure"
            type="textarea"
            :rows="2"
            placeholder="e.g. 每批次产品出锅前用红外测温计测量中心位置"
          />
        </el-form-item>
        <el-form-item label="纠偏措施">
          <el-input
            v-model="form.correctiveAction"
            type="textarea"
            :rows="2"
            placeholder="e.g. 中心温度不达标 → 继续加热 5 min 重测"
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.active" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  listCheckpoints,
  createCheckpoint,
  updateCheckpoint,
  deleteCheckpoint,
  type HaccpCheckpoint,
  type HazardType,
  HazardTypeLabels,
} from '@/api/foodSafetyHub'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const loading = ref(false)
const saving = ref(false)
const rows = ref<HaccpCheckpoint[]>([])
const search = ref('')
const activeOnly = ref(false)

const dialogOpen = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const editingVersion = ref<number | undefined>(undefined)

const HAZARD_TYPES = [
  { value: 'BIOLOGICAL', label: '生物性危害' },
  { value: 'CHEMICAL', label: '化学性危害' },
  { value: 'PHYSICAL', label: '物理性危害' },
] as const

const blankForm = (): HaccpCheckpoint => ({
  checkpointCode: '',
  name: '',
  hazardType: 'BIOLOGICAL',
  description: '',
  criticalLimitMin: 0,
  criticalLimitMax: 0,
  unit: '',
  monitoringProcedure: '',
  correctiveAction: '',
  verificationProcedure: '',
  recordKeeping: '',
  active: true,
})
const form = ref<HaccpCheckpoint>(blankForm())
const formRef = ref()
const formRules = {
  checkpointCode: [{ required: true, message: '请填写 CCP 编码', trigger: 'blur' }],
  name: [{ required: true, message: '请填写 CCP 名称', trigger: 'blur' }],
  hazardType: [{ required: true, message: '请选择危害类型', trigger: 'change' }],
  unit: [{ required: true, message: '请填写单位', trigger: 'blur' }],
}

const dialogTitle = computed(() => {
  if (dialogMode.value === 'create') return '新建 HACCP 关键控制点 (CCP)'
  // fool-proof Rule 2: dialog header 含 entity 身份信息
  return `编辑 — ${form.value.checkpointCode || ''} ${form.value.name || ''}`
})

const filteredRows = computed(() => {
  if (!search.value.trim()) return rows.value
  const kw = search.value.toLowerCase()
  return rows.value.filter(
    (r) =>
      (r.checkpointCode || '').toLowerCase().includes(kw) ||
      (r.name || '').toLowerCase().includes(kw),
  )
})

function hazardLabel(t?: string) {
  return t ? HazardTypeLabels[t as HazardType] || t : ''
}
function hazardTagType(t?: string) {
  if (t === 'BIOLOGICAL') return 'danger'
  if (t === 'CHEMICAL') return 'warning'
  return 'info'
}
function formatDate(s?: string) {
  if (!s) return ''
  return s.replace('T', ' ').substring(0, 19)
}

async function reload() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listCheckpoints(props.factoryId, activeOnly.value || undefined)
    rows.value = res.success && res.data ? res.data : []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  editingVersion.value = undefined
  form.value = blankForm()
  dialogOpen.value = true
}

function openEdit(row: HaccpCheckpoint) {
  dialogMode.value = 'edit'
  editingId.value = row.id ?? null
  editingVersion.value = row.version
  form.value = { ...row }
  dialogOpen.value = true
}

async function onSave() {
  await formRef.value?.validate()
  saving.value = true
  try {
    if (dialogMode.value === 'create') {
      const res = await createCheckpoint(props.factoryId, form.value)
      if (res.success) {
        dialogOpen.value = false
        await reload()
      }
    } else if (editingId.value != null) {
      // AUD-4: include version field for optimistic lock
      const payload = { ...form.value, version: editingVersion.value }
      const res = await updateCheckpoint(props.factoryId, editingId.value, payload)
      if (res.success) {
        dialogOpen.value = false
        await reload()
      }
    }
  } finally {
    saving.value = false
  }
}

async function confirmDelete(row: HaccpCheckpoint) {
  // fool-proof Rule 2: confirm dialog 显示 entity 身份
  await ElMessageBox.confirm(
    `确定停用 ${row.checkpointCode} ${row.name}? 历史监控记录会保留, 但新批次将无法使用此 CCP.`,
    '停用确认',
    { confirmButtonText: '确定停用', cancelButtonText: '取消', type: 'warning' },
  )
  if (row.id == null) return
  await deleteCheckpoint(props.factoryId, row.id)
  await reload()
}

onMounted(() => {
  void reload()
})
</script>

<style scoped>
.haccp-checkpoints-list {
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
</style>
