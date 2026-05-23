<!--
  WagePoliciesPanel — Phase B Factory Config Hub sub-tab 4.

  工资模式 (PIECE_RATE 计件 / HOURLY 计时 / MIXED 混合) — factory default + per-employee override.
-->
<template>
  <div class="wage-policies-panel">
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增策略</el-button>
      <el-button @click="loadData">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column label="范围" width="180">
        <template #default="{ row }">
          <el-tag v-if="row.employeeId == null" type="warning">工厂默认</el-tag>
          <el-tag v-else type="success">员工 #{{ row.employeeId }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="模式" width="120">
        <template #default="{ row }">
          <el-tag :type="modeTagType(row.mode)">{{ MODE_LABELS[row.mode as WageMode] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="mixedFormulaHint" label="MIXED 公式提示" min-width="180" show-overflow-tooltip />
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.isActive" type="success">启用</el-tag>
          <el-tag v-else type="info">停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="notes" label="备注" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button
            size="small"
            :type="row.isActive ? 'danger' : 'success'"
            @click="toggleActive(row)"
          >
            {{ row.isActive ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogOpen" :title="dialogTitle" width="520px">
      <el-form :model="form" label-width="140px">
        <el-form-item label="员工 ID">
          <el-input-number v-model="form.employeeId" :min="0" />
          <el-text type="info" size="small" style="margin-left: 8px">(留空 = 工厂默认)</el-text>
        </el-form-item>
        <el-form-item label="工资模式" required>
          <el-select v-model="form.mode" placeholder="选择计酬方式">
            <el-option
              v-for="m in MODE_OPTIONS"
              :key="m.value"
              :label="m.label"
              :value="m.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.mode === 'MIXED'" label="混合公式提示">
          <el-input
            v-model="form.mixedFormulaHint"
            placeholder="例: 基础工时 + 80% 计件提成 (UI 提示, 计算用 HOURLY + PIECE_RATE 全额相加)"
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.isActive" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.notes" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listWagePolicies,
  createWagePolicy,
  updateWagePolicy,
  WAGE_MODE_LABELS,
  type WagePolicy,
  type WageMode,
} from '@/api/factoryConfigHub'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const MODE_LABELS = WAGE_MODE_LABELS
const MODE_OPTIONS = [
  { value: 'PIECE_RATE', label: '计件 (PIECE_RATE)' },
  { value: 'HOURLY', label: '计时 (HOURLY)' },
  { value: 'MIXED', label: '混合 (MIXED)' },
]

function modeTagType(mode: WageMode): 'success' | 'warning' | 'info' {
  if (mode === 'HOURLY') return 'success'
  if (mode === 'MIXED') return 'warning'
  return 'info'
}

const loading = ref(false)
const saving = ref(false)
const rows = ref<WagePolicy[]>([])
const dialogOpen = ref(false)
const editingId = ref<number | null>(null)
const dialogTitle = computed(() => editingId.value == null ? '新增工资策略' : '编辑工资策略')

const form = reactive<Partial<WagePolicy>>({
  mode: 'PIECE_RATE',
  isActive: true,
})

async function loadData() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listWagePolicies(props.factoryId)
    if (res.success && res.data) rows.value = res.data
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.employeeId = undefined
  form.mode = 'PIECE_RATE'
  form.mixedFormulaHint = ''
  form.isActive = true
  form.notes = ''
  dialogOpen.value = true
}

function openEdit(row: WagePolicy) {
  editingId.value = row.id ?? null
  form.employeeId = row.employeeId ?? undefined
  form.mode = row.mode
  form.mixedFormulaHint = row.mixedFormulaHint
  form.isActive = row.isActive
  form.notes = row.notes
  ;(form as any).version = row.version
  dialogOpen.value = true
}

async function onSave() {
  if (!form.mode) {
    ElMessage.warning('请选择工资模式')
    return
  }
  saving.value = true
  try {
    if (editingId.value == null) {
      await createWagePolicy(props.factoryId, form)
      ElMessage.success('策略已创建')
    } else {
      await updateWagePolicy(props.factoryId, editingId.value, form)
      ElMessage.success('策略已更新')
    }
    dialogOpen.value = false
    await loadData()
  } catch (e: any) {
    const errorCode = e?.response?.data?.errorCode
    if (errorCode === 'VERSION_CONFLICT') {
      ElMessage.warning('策略被他人修改, 刷新后重试')
      await loadData()
      dialogOpen.value = false
    }
  } finally {
    saving.value = false
  }
}

async function toggleActive(row: WagePolicy) {
  if (!row.id) return
  try {
    await updateWagePolicy(props.factoryId, row.id, {
      version: row.version,
      isActive: !row.isActive,
    })
    ElMessage.success(row.isActive ? '已停用' : '已启用')
    await loadData()
  } catch (e: any) {
    const errorCode = e?.response?.data?.errorCode
    if (errorCode === 'VERSION_CONFLICT') {
      ElMessage.warning('策略被他人修改, 刷新后重试')
      await loadData()
    }
  }
}

onMounted(loadData)
</script>

<style scoped>
.wage-policies-panel {
  padding: 8px 0;
}
.toolbar {
  margin-bottom: 12px;
  display: flex;
  gap: 8px;
}
</style>
