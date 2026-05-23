<!--
  InsurancePanel — Phase B Factory Config Hub sub-tab 3.

  五险一金 (社保 / 公积金) 费率配置 — 历史版本表 + 新增 ACTIVE.
-->
<template>
  <div class="insurance-panel">
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增费率版本</el-button>
      <el-button @click="loadData">刷新</el-button>
      <el-text v-if="active" type="success" style="margin-left: 12px">
        当前生效: {{ active.effectiveFrom }} (v{{ active.version }})
      </el-text>
    </div>

    <el-table v-loading="loading" :data="history" stripe>
      <el-table-column prop="effectiveFrom" label="生效日期" width="120" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.status === 'ACTIVE'" type="success">ACTIVE</el-tag>
          <el-tag v-else type="info">已归档</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="个人养老" width="100">
        <template #default="{ row }">{{ (row.employeePensionRate * 100).toFixed(2) }}%</template>
      </el-table-column>
      <el-table-column label="单位养老" width="100">
        <template #default="{ row }">{{ (row.employerPensionRate * 100).toFixed(2) }}%</template>
      </el-table-column>
      <el-table-column label="个人医疗" width="100">
        <template #default="{ row }">{{ (row.employeeMedicalRate * 100).toFixed(2) }}%</template>
      </el-table-column>
      <el-table-column label="单位医疗" width="100">
        <template #default="{ row }">{{ (row.employerMedicalRate * 100).toFixed(2) }}%</template>
      </el-table-column>
      <el-table-column label="个人公积金" width="110">
        <template #default="{ row }">{{ (row.employeeProvidentFundRate * 100).toFixed(2) }}%</template>
      </el-table-column>
      <el-table-column label="单位公积金" width="110">
        <template #default="{ row }">{{ (row.employerProvidentFundRate * 100).toFixed(2) }}%</template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
    </el-table>

    <el-dialog v-model="createOpen" title="新增费率版本" width="640px">
      <el-alert
        type="warning"
        :closable="false"
        title="提交后老 ACTIVE 自动归档"
        description="本次提交的费率立即生效, 老 ACTIVE 版本自动改为 ARCHIVED. 历史月份工资重算按各月份生效费率计算."
        style="margin-bottom: 16px"
      />
      <el-form :model="createForm" label-width="160px">
        <el-form-item label="生效起始月" required>
          <el-date-picker v-model="createForm.effectiveFrom" type="month" value-format="YYYY-MM-01" />
        </el-form-item>
        <el-divider content-position="left">养老</el-divider>
        <el-form-item label="个人养老费率">
          <el-input-number v-model="createForm.employeePensionRate" :min="0" :max="1" :step="0.001" :precision="4" />
        </el-form-item>
        <el-form-item label="单位养老费率">
          <el-input-number v-model="createForm.employerPensionRate" :min="0" :max="1" :step="0.001" :precision="4" />
        </el-form-item>
        <el-divider content-position="left">医疗</el-divider>
        <el-form-item label="个人医疗费率">
          <el-input-number v-model="createForm.employeeMedicalRate" :min="0" :max="1" :step="0.001" :precision="4" />
        </el-form-item>
        <el-form-item label="单位医疗费率">
          <el-input-number v-model="createForm.employerMedicalRate" :min="0" :max="1" :step="0.001" :precision="4" />
        </el-form-item>
        <el-divider content-position="left">失业</el-divider>
        <el-form-item label="个人失业费率">
          <el-input-number v-model="createForm.employeeUnemploymentRate" :min="0" :max="1" :step="0.001" :precision="4" />
        </el-form-item>
        <el-form-item label="单位失业费率">
          <el-input-number v-model="createForm.employerUnemploymentRate" :min="0" :max="1" :step="0.001" :precision="4" />
        </el-form-item>
        <el-divider content-position="left">公积金</el-divider>
        <el-form-item label="个人公积金费率">
          <el-input-number v-model="createForm.employeeProvidentFundRate" :min="0" :max="0.12" :step="0.001" :precision="4" />
          <el-text type="info" size="small" style="margin-left: 8px">(城市范围 5%-12%)</el-text>
        </el-form-item>
        <el-form-item label="单位公积金费率">
          <el-input-number v-model="createForm.employerProvidentFundRate" :min="0" :max="0.12" :step="0.001" :precision="4" />
        </el-form-item>
        <el-divider content-position="left">缴费基数</el-divider>
        <el-form-item label="缴费基数下限">
          <el-input-number v-model="createForm.baseSalaryLowerBound" :min="0" :step="100" />
        </el-form-item>
        <el-form-item label="缴费基数上限">
          <el-input-number v-model="createForm.baseSalaryUpperBound" :min="0" :step="100" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="onCreate">创建并归档老版本</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listInsurance,
  createInsurance,
  type InsuranceConfig,
} from '@/api/factoryConfigHub'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const loading = ref(false)
const creating = ref(false)
const history = ref<InsuranceConfig[]>([])
const active = ref<InsuranceConfig | null>(null)
const createOpen = ref(false)
const createForm = reactive<Partial<InsuranceConfig>>({
  employeePensionRate: 0.08,
  employerPensionRate: 0.16,
  employeeMedicalRate: 0.02,
  employerMedicalRate: 0.08,
  employeeUnemploymentRate: 0.005,
  employerUnemploymentRate: 0.005,
  employeeProvidentFundRate: 0.08,
  employerProvidentFundRate: 0.08,
})

async function loadData() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listInsurance(props.factoryId)
    if (res.success && res.data) {
      history.value = res.data.history || []
      active.value = res.data.active || null
    }
  } finally {
    loading.value = false
  }
}

function openCreate() {
  if (active.value) {
    // Prefill from current ACTIVE
    Object.assign(createForm, {
      employeePensionRate: active.value.employeePensionRate,
      employerPensionRate: active.value.employerPensionRate,
      employeeMedicalRate: active.value.employeeMedicalRate,
      employerMedicalRate: active.value.employerMedicalRate,
      employeeUnemploymentRate: active.value.employeeUnemploymentRate,
      employerUnemploymentRate: active.value.employerUnemploymentRate,
      employeeProvidentFundRate: active.value.employeeProvidentFundRate,
      employerProvidentFundRate: active.value.employerProvidentFundRate,
      baseSalaryLowerBound: active.value.baseSalaryLowerBound,
      baseSalaryUpperBound: active.value.baseSalaryUpperBound,
    })
  }
  createForm.effectiveFrom = new Date().toISOString().slice(0, 8) + '01'
  createForm.remark = ''
  createOpen.value = true
}

async function onCreate() {
  if (!createForm.effectiveFrom) {
    ElMessage.warning('请选择生效起始月')
    return
  }
  creating.value = true
  try {
    const res = await createInsurance(props.factoryId, createForm)
    if (res.success) {
      ElMessage.success('费率版本已新增, 老版本自动归档')
      createOpen.value = false
      await loadData()
    }
  } finally {
    creating.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.insurance-panel {
  padding: 8px 0;
}
.toolbar {
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
