<!--
  HrInsuranceEditor — Canvas Phase BCP3 (2026-05-22).

  P3 半-Canvas-ed: 包装 HrInsuranceConfig entity (#833 follow-up).

  Backend: /api/mobile/{factoryId}/canvas-hr-insurance
  防呆 (Rule 1+2+5):
    - rate 字段范围 [0, 0.30] 显示在 input 旁
    - factory 最多 1 条 ACTIVE (新建会自动 ARCHIVED 旧)
    - 不能删 ACTIVE (UI 直接 disable + tooltip)
-->
<template>
  <div class="hr-insurance-editor">
    <div class="panel-header">
      <h3>五险一金费率</h3>
      <div class="panel-actions">
        <el-tag size="small" type="info">factoryId: {{ factoryId }}</el-tag>
        <el-button size="small" @click="loadList" :disabled="loading">刷新</el-button>
        <el-button type="primary" size="small" @click="onCreate">新建配置</el-button>
      </div>
    </div>

    <el-alert v-if="hasActive" type="success" :closable="false" style="margin-bottom: 12px">
      当前生效配置: 自 {{ activeConfig?.effectiveFrom }} 起
      ({{ activeConfig?.remark || '默认' }})
    </el-alert>
    <el-alert v-else type="warning" :closable="false" style="margin-bottom: 12px">
      尚未配置费率, 请点击 "新建配置" 进行初始化
    </el-alert>

    <el-table v-loading="loading" :data="configs" stripe style="width: 100%"
              empty-text="暂无配置">
      <el-table-column prop="effectiveFrom" label="生效起始月" min-width="120" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
            {{ row.status === 'ACTIVE' ? '当前生效' : '已归档' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="个人养老" width="100">
        <template #default="{ row }">{{ formatPct(row.employeePensionRate) }}</template>
      </el-table-column>
      <el-table-column label="单位养老" width="100">
        <template #default="{ row }">{{ formatPct(row.employerPensionRate) }}</template>
      </el-table-column>
      <el-table-column label="个人医疗" width="100">
        <template #default="{ row }">{{ formatPct(row.employeeMedicalRate) }}</template>
      </el-table-column>
      <el-table-column label="单位医疗" width="100">
        <template #default="{ row }">{{ formatPct(row.employerMedicalRate) }}</template>
      </el-table-column>
      <el-table-column label="个人公积金" width="110">
        <template #default="{ row }">{{ formatPct(row.employeeProvidentFundRate) }}</template>
      </el-table-column>
      <el-table-column label="单位公积金" width="110">
        <template #default="{ row }">{{ formatPct(row.employerProvidentFundRate) }}</template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="onEdit(row)">查看/编辑</el-button>
          <el-tooltip v-if="row.status === 'ACTIVE'" content="不能删除当前生效配置" placement="top">
            <span>
              <el-button size="small" type="danger" link disabled>删除</el-button>
            </span>
          </el-tooltip>
          <el-button v-else size="small" type="danger" link @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Edit / Create Dialog -->
    <el-dialog v-model="dialogVisible"
               :title="dialogMode === 'create' ? '新建费率配置' : '编辑费率配置'"
               width="700px" :close-on-click-modal="false">
      <el-alert v-if="dialogMode === 'create' && hasActive"
                type="warning" :closable="false" style="margin-bottom: 12px">
        新建会把当前生效配置自动改为 ARCHIVED (factory 最多 1 条 ACTIVE)
      </el-alert>
      <el-form :model="editForm" :rules="formRules" ref="formRef" label-width="140px">
        <el-form-item label="生效起始月" prop="effectiveFrom">
          <el-date-picker v-model="editForm.effectiveFrom" type="date" value-format="YYYY-MM-DD"
                          placeholder="选择月初日期" style="width: 100%" />
        </el-form-item>

        <el-divider content-position="left">养老保险 (范围 0-30%)</el-divider>
        <el-form-item label="个人养老" prop="employeePensionRate">
          <el-input-number v-model="editForm.employeePensionRate" :min="0" :max="0.30"
                           :step="0.005" :precision="4" />
          <span class="form-hint">{{ formatPct(editForm.employeePensionRate) }}</span>
        </el-form-item>
        <el-form-item label="单位养老" prop="employerPensionRate">
          <el-input-number v-model="editForm.employerPensionRate" :min="0" :max="0.30"
                           :step="0.005" :precision="4" />
          <span class="form-hint">{{ formatPct(editForm.employerPensionRate) }}</span>
        </el-form-item>

        <el-divider content-position="left">医疗保险 (范围 0-30%)</el-divider>
        <el-form-item label="个人医疗" prop="employeeMedicalRate">
          <el-input-number v-model="editForm.employeeMedicalRate" :min="0" :max="0.30"
                           :step="0.005" :precision="4" />
          <span class="form-hint">{{ formatPct(editForm.employeeMedicalRate) }}</span>
        </el-form-item>
        <el-form-item label="单位医疗" prop="employerMedicalRate">
          <el-input-number v-model="editForm.employerMedicalRate" :min="0" :max="0.30"
                           :step="0.005" :precision="4" />
        </el-form-item>

        <el-divider content-position="left">失业保险</el-divider>
        <el-form-item label="个人失业" prop="employeeUnemploymentRate">
          <el-input-number v-model="editForm.employeeUnemploymentRate" :min="0" :max="0.30"
                           :step="0.001" :precision="4" />
        </el-form-item>
        <el-form-item label="单位失业" prop="employerUnemploymentRate">
          <el-input-number v-model="editForm.employerUnemploymentRate" :min="0" :max="0.30"
                           :step="0.001" :precision="4" />
        </el-form-item>

        <el-divider content-position="left">公积金 (城市 5%-12%)</el-divider>
        <el-form-item label="个人公积金" prop="employeeProvidentFundRate">
          <el-input-number v-model="editForm.employeeProvidentFundRate" :min="0" :max="0.30"
                           :step="0.005" :precision="4" />
          <span class="form-hint">{{ formatPct(editForm.employeeProvidentFundRate) }}</span>
        </el-form-item>
        <el-form-item label="单位公积金" prop="employerProvidentFundRate">
          <el-input-number v-model="editForm.employerProvidentFundRate" :min="0" :max="0.30"
                           :step="0.005" :precision="4" />
        </el-form-item>

        <el-divider content-position="left">缴费基数 (可选)</el-divider>
        <el-form-item label="基数下限">
          <el-input-number v-model="editForm.baseSalaryLowerBound" :min="0" :precision="2"
                           placeholder="留空 = 不限制" />
          <span class="form-hint">通常城市最低工资 60%</span>
        </el-form-item>
        <el-form-item label="基数上限">
          <el-input-number v-model="editForm.baseSalaryUpperBound" :min="0" :precision="2"
                           placeholder="留空 = 不限制" />
          <span class="form-hint">通常城市平均工资 3 倍</span>
        </el-form-item>

        <el-form-item label="备注">
          <el-input v-model="editForm.remark" type="textarea" :rows="2"
                    maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { canvasHrInsuranceApi, type HrInsuranceConfig } from '@/api/canvasHrInsurance'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const loading = ref(false)
const saving = ref(false)
const configs = ref<HrInsuranceConfig[]>([])

const activeConfig = computed(() => configs.value.find(c => c.status === 'ACTIVE'))
const hasActive = computed(() => activeConfig.value !== undefined)

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editForm = ref<Partial<HrInsuranceConfig>>(blankForm())
const currentEditId = ref<string | null>(null)
const currentEditVersion = ref<number | undefined>(undefined)

const formRules: FormRules = {
  effectiveFrom: [{ required: true, message: '请选择生效起始月', trigger: 'change' }],
  employeePensionRate: [{ required: true, message: '必填', trigger: 'change' }],
  employerPensionRate: [{ required: true, message: '必填', trigger: 'change' }],
  employeeMedicalRate: [{ required: true, message: '必填', trigger: 'change' }],
  employerMedicalRate: [{ required: true, message: '必填', trigger: 'change' }],
  employeeUnemploymentRate: [{ required: true, message: '必填', trigger: 'change' }],
  employerUnemploymentRate: [{ required: true, message: '必填', trigger: 'change' }],
  employeeProvidentFundRate: [{ required: true, message: '必填', trigger: 'change' }],
  employerProvidentFundRate: [{ required: true, message: '必填', trigger: 'change' }],
}

function blankForm(): Partial<HrInsuranceConfig> {
  return {
    employeePensionRate: 0.08,
    employerPensionRate: 0.16,
    employeeMedicalRate: 0.02,
    employerMedicalRate: 0.08,
    employeeUnemploymentRate: 0.005,
    employerUnemploymentRate: 0.005,
    employeeProvidentFundRate: 0.08,
    employerProvidentFundRate: 0.08,
    effectiveFrom: '',
    remark: '',
  }
}

function formatPct(v: unknown): string {
  if (v == null || v === '') return ''
  const n = typeof v === 'number' ? v : parseFloat(String(v))
  if (Number.isNaN(n)) return ''
  return (n * 100).toFixed(2) + '%'
}

async function loadList() {
  loading.value = true
  try {
    const resp = await canvasHrInsuranceApi.list(props.factoryId)
    configs.value = resp.data || []
  } catch (e: any) {
    ElMessage({
      message: e?.response?.data?.message || '加载失败',
      type: 'error', duration: 0, showClose: true,
    })
  } finally {
    loading.value = false
  }
}

function onCreate() {
  dialogMode.value = 'create'
  editForm.value = blankForm()
  currentEditId.value = null
  currentEditVersion.value = undefined
  dialogVisible.value = true
}

function onEdit(row: HrInsuranceConfig) {
  dialogMode.value = 'edit'
  editForm.value = {
    ...row,
    employeePensionRate: Number(row.employeePensionRate),
    employerPensionRate: Number(row.employerPensionRate),
    employeeMedicalRate: Number(row.employeeMedicalRate),
    employerMedicalRate: Number(row.employerMedicalRate),
    employeeUnemploymentRate: Number(row.employeeUnemploymentRate),
    employerUnemploymentRate: Number(row.employerUnemploymentRate),
    employeeProvidentFundRate: Number(row.employeeProvidentFundRate),
    employerProvidentFundRate: Number(row.employerProvidentFundRate),
    baseSalaryLowerBound: row.baseSalaryLowerBound ? Number(row.baseSalaryLowerBound) : undefined,
    baseSalaryUpperBound: row.baseSalaryUpperBound ? Number(row.baseSalaryUpperBound) : undefined,
  }
  currentEditId.value = row.id
  currentEditVersion.value = row.optLockVersion
  dialogVisible.value = true
}

async function onSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      if (dialogMode.value === 'create') {
        await canvasHrInsuranceApi.create(props.factoryId, editForm.value)
        ElMessage.success('费率配置已创建')
      } else if (currentEditId.value) {
        await canvasHrInsuranceApi.update(props.factoryId, currentEditId.value, {
          ...editForm.value,
          version: currentEditVersion.value,
        })
        ElMessage.success('费率配置已更新')
      }
      dialogVisible.value = false
      await loadList()
    } catch (e: any) {
      ElMessage({
        message: e?.response?.data?.message || '保存失败',
        type: 'error', duration: 0, showClose: true,
      })
    } finally {
      saving.value = false
    }
  })
}

async function onDelete(row: HrInsuranceConfig) {
  try {
    await ElMessageBox.confirm(
      `确认删除 ${row.effectiveFrom} 生效的归档配置 ?`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await canvasHrInsuranceApi.remove(props.factoryId, row.id)
    ElMessage.success('已删除')
    await loadList()
  } catch (e: any) {
    ElMessage({
      message: e?.response?.data?.message || '删除失败',
      type: 'error', duration: 0, showClose: true,
    })
  }
}

onMounted(loadList)
</script>

<style scoped>
.hr-insurance-editor { padding: 16px; }
.panel-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 16px;
}
.panel-header h3 { margin: 0; }
.panel-actions { display: flex; gap: 8px; align-items: center; }
.form-hint { font-size: 12px; color: #909399; margin-left: 8px; }
</style>
