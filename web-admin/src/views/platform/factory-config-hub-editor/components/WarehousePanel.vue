<!--
  WarehousePanel — Factory Config Hub sub-tab: 仓库管理.

  超级管理员（factory_super_admin / permission_admin）专用面板：
    - 新建仓库（code 必填，per-factory 唯一；duplicate → 409 sticky toast）
    - 编辑仓库（code 不可改，可改 name/type/isActive）
    - 停用仓库（软删除）
    - 套用模板（六扇门含盐化7仓 preset / 自选仓型勾选建仓）

  Backend: FactoryWarehouseController @ /api/mobile/{factoryId}/factory/warehouses
  Role gate: factory_super_admin / permission_admin (backend @RequireRole)

  Fool-proof 防呆：
    - 提交前 validate()，必填项红点提示
    - 套模板预览"将创建 N 仓（已存在跳过）"
    - 409 duplicate → ElMessage.error sticky (duration:0 + showClose)
    - 停用前 ElMessageBox confirm
-->
<template>
  <div class="warehouse-panel">
    <!-- 工具栏 -->
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新建仓库</el-button>
      <el-button @click="openTemplate">套用模板</el-button>
      <el-button @click="loadData">刷新</el-button>
    </div>

    <!-- 仓库列表 -->
    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="code" label="仓库编码" width="140" />
      <el-table-column prop="name" label="仓库名称" min-width="140" />
      <el-table-column prop="type" label="仓库类型" width="140">
        <template #default="{ row }">
          {{ WAREHOUSE_TYPE_LABELS[row.type as WarehouseType] || row.type }}
        </template>
      </el-table-column>
      <el-table-column prop="isActive" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.isActive ? 'success' : 'info'" size="small">
            {{ row.isActive ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" plain @click="onDelete(row)">停用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建 / 编辑 Dialog -->
    <el-dialog v-model="dialogOpen" :title="dialogTitle" width="480px">
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="仓库编码" prop="code">
          <el-input
            v-model="form.code"
            :disabled="editingId != null"
            placeholder="例: WH-RAW / SALTED-01"
            maxlength="32"
          />
        </el-form-item>
        <el-form-item label="仓库名称" prop="name">
          <el-input v-model="form.name" placeholder="例: 原料仓 / 盐化仓代加工" maxlength="100" />
        </el-form-item>
        <el-form-item label="仓库类型" prop="type">
          <el-select v-model="form.type" placeholder="选择仓库类型" style="width: 100%">
            <el-option
              v-for="(label, val) in WAREHOUSE_TYPE_LABELS"
              :key="val"
              :label="label"
              :value="val"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.isActive" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remarks" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 套模板 Dialog -->
    <el-dialog v-model="templateDialogOpen" title="套用仓库模板" width="560px">
      <div class="template-options">
        <!-- 选项 1：六扇门含盐化代加工预设 -->
        <el-card
          :class="['template-card', { selected: templateMode === 'preset' }]"
          shadow="hover"
          @click="templateMode = 'preset'"
        >
          <template #header>
            <div class="template-card-header">
              <el-radio v-model="templateMode" label="preset" />
              <strong>含盐化代加工预设 (7 仓)</strong>
            </div>
          </template>
          <el-table :data="LIUSHANMEN_SALTED_TEMPLATE" size="small" :show-header="false">
            <el-table-column prop="code" width="120" />
            <el-table-column prop="name" />
            <el-table-column width="120">
              <template #default="{ row }">
                <el-text type="info" size="small">{{ WAREHOUSE_TYPE_LABELS[row.type] }}</el-text>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- 选项 2：自选仓型 -->
        <el-card
          :class="['template-card', { selected: templateMode === 'custom' }]"
          shadow="hover"
          @click="templateMode = 'custom'"
        >
          <template #header>
            <div class="template-card-header">
              <el-radio v-model="templateMode" label="custom" />
              <strong>全仓型按需勾选</strong>
            </div>
          </template>
          <div class="custom-types">
            <div
              v-for="(defaults, typeKey) in WAREHOUSE_TYPE_DEFAULTS"
              :key="typeKey"
              class="custom-type-row"
            >
              <el-checkbox
                v-model="customSelected[typeKey as WarehouseType]"
                :disabled="templateMode !== 'custom'"
              >
                <span class="type-label">{{ WAREHOUSE_TYPE_LABELS[typeKey as WarehouseType] }}</span>
              </el-checkbox>
              <template v-if="customSelected[typeKey as WarehouseType] && templateMode === 'custom'">
                <el-input
                  v-model="customEdits[typeKey as WarehouseType].code"
                  size="small"
                  placeholder="编码"
                  style="width: 100px; margin-left: 8px"
                  maxlength="32"
                />
                <el-input
                  v-model="customEdits[typeKey as WarehouseType].name"
                  size="small"
                  placeholder="名称"
                  style="width: 120px; margin-left: 6px"
                  maxlength="100"
                />
              </template>
            </div>
          </div>
        </el-card>
      </div>

      <!-- 预览行 -->
      <div class="template-preview">
        <el-text type="info">
          将创建 <strong>{{ previewCount }}</strong> 个仓库（已存在的自动跳过）
        </el-text>
      </div>

      <template #footer>
        <el-button @click="templateDialogOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="applyingTemplate"
          :disabled="previewCount === 0"
          @click="onApplyTemplate"
        >
          确认套用
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  listWarehouses,
  createWarehouse,
  updateWarehouse,
  deleteWarehouse,
  applyWarehouseTemplate,
  WAREHOUSE_TYPE_LABELS,
  WAREHOUSE_TYPE_DEFAULTS,
  LIUSHANMEN_SALTED_TEMPLATE,
  type FactoryWarehouse,
  type WarehouseType,
  type WarehouseSpec,
} from '@/api/factoryWarehouse'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

// ==================== List state ====================

const loading = ref(false)
const rows = ref<FactoryWarehouse[]>([])

async function loadData() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listWarehouses(props.factoryId)
    if (res.success && res.data) rows.value = res.data
  } finally {
    loading.value = false
  }
}

// ==================== Create / Edit dialog ====================

const dialogOpen = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()

const dialogTitle = computed(() => editingId.value == null ? '新建仓库' : '编辑仓库')

const form = reactive<{
  code: string
  name: string
  type: WarehouseType | ''
  isActive: boolean
  remarks: string
}>({
  code: '',
  name: '',
  type: '',
  isActive: true,
  remarks: '',
})

const formRules: FormRules = {
  code: [{ required: true, message: '仓库编码不能为空', trigger: 'blur' }],
  name: [{ required: true, message: '仓库名称不能为空', trigger: 'blur' }],
  type: [{ required: true, message: '请选择仓库类型', trigger: 'change' }],
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { code: '', name: '', type: '' as WarehouseType, isActive: true, remarks: '' })
  dialogOpen.value = true
  // reset validation state after dialog opens
}

function openEdit(row: FactoryWarehouse) {
  editingId.value = row.id
  Object.assign(form, {
    code: row.code,
    name: row.name,
    type: row.type,
    isActive: row.isActive,
    remarks: row.remarks || '',
  })
  dialogOpen.value = true
}

async function onSave() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    if (editingId.value == null) {
      await createWarehouse(props.factoryId, {
        code: form.code,
        name: form.name,
        type: form.type as WarehouseType,
        isActive: form.isActive,
        remarks: form.remarks || undefined,
      })
      ElMessage.success('仓库已创建')
    } else {
      await updateWarehouse(props.factoryId, editingId.value, {
        name: form.name,
        type: form.type as WarehouseType,
        isActive: form.isActive,
        remarks: form.remarks || undefined,
      })
      ElMessage.success('仓库已更新')
    }
    dialogOpen.value = false
    await loadData()
  } catch (e: any) {
    const errCode = e?.response?.data?.errorCode
    const msg = e?.response?.data?.message || '操作失败'
    if (errCode === 'WAREHOUSE_CODE_DUPLICATE' || e?.response?.status === 409) {
      ElMessage({ message: msg, type: 'error', duration: 0, showClose: true })
    }
    // other errors handled by axios interceptor
  } finally {
    saving.value = false
  }
}

// ==================== Delete (soft) ====================

async function onDelete(row: FactoryWarehouse) {
  await ElMessageBox.confirm(
    `确认停用仓库「${row.name}（${row.code}）」？停用后不影响现有业务数据，可随时联系管理员恢复。`,
    '停用仓库',
    { confirmButtonText: '确认停用', cancelButtonText: '取消', type: 'warning' },
  )
  try {
    await deleteWarehouse(props.factoryId, row.id)
    ElMessage.success(`仓库 ${row.code} 已停用`)
    await loadData()
  } catch {
    // axios interceptor handles error toast
  }
}

// ==================== Template dialog ====================

const templateDialogOpen = ref(false)
const applyingTemplate = ref(false)
const templateMode = ref<'preset' | 'custom'>('preset')

// Custom mode: checkbox state + editable code/name
const customSelected = reactive<Record<WarehouseType, boolean>>(
  Object.fromEntries(
    Object.keys(WAREHOUSE_TYPE_DEFAULTS).map((k) => [k, false]),
  ) as Record<WarehouseType, boolean>,
)

const customEdits = reactive<Record<WarehouseType, { code: string; name: string }>>(
  Object.fromEntries(
    Object.entries(WAREHOUSE_TYPE_DEFAULTS).map(([k, v]) => [k, { code: v.code, name: v.name }]),
  ) as Record<WarehouseType, { code: string; name: string }>,
)

const previewCount = computed(() => {
  if (templateMode.value === 'preset') return LIUSHANMEN_SALTED_TEMPLATE.length
  return Object.entries(customSelected).filter(([, v]) => v).length
})

function openTemplate() {
  templateMode.value = 'preset'
  // reset custom selections
  for (const k of Object.keys(customSelected)) {
    customSelected[k as WarehouseType] = false
  }
  // reset edits to defaults
  for (const [k, v] of Object.entries(WAREHOUSE_TYPE_DEFAULTS)) {
    customEdits[k as WarehouseType].code = v.code
    customEdits[k as WarehouseType].name = v.name
  }
  templateDialogOpen.value = true
}

async function onApplyTemplate() {
  applyingTemplate.value = true
  try {
    let body: Parameters<typeof applyWarehouseTemplate>[1]

    if (templateMode.value === 'preset') {
      body = { templateKey: 'LIUSHANMEN_SALTED' }
    } else {
      const warehouses: WarehouseSpec[] = Object.entries(customSelected)
        .filter(([, v]) => v)
        .map(([k]) => ({
          code: customEdits[k as WarehouseType].code,
          name: customEdits[k as WarehouseType].name,
          type: k as WarehouseType,
        }))
      body = { templateKey: null, warehouses }
    }

    const res = await applyWarehouseTemplate(props.factoryId, body)
    if (res.success && res.data) {
      const { created, skipped } = res.data
      ElMessage.success(`套模板完成：新建 ${created} 个，跳过 ${skipped} 个（已存在）`)
    }
    templateDialogOpen.value = false
    await loadData()
  } catch {
    // axios interceptor handles error toast
  } finally {
    applyingTemplate.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.warehouse-panel {
  padding: 8px 0;
}
.toolbar {
  margin-bottom: 12px;
  display: flex;
  gap: 8px;
}
.template-options {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.template-card {
  cursor: pointer;
  border: 2px solid transparent;
  transition: border-color 0.2s;
}
.template-card.selected {
  border-color: var(--el-color-primary);
}
.template-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.custom-types {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.custom-type-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}
.type-label {
  min-width: 80px;
}
.template-preview {
  margin-top: 12px;
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
</style>
