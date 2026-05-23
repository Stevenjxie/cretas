<!--
  SchedulingPanel — Phase B Factory Config Hub sub-tab 1.

  Wraps FactorySchedulingConfig CRUD. Supports edit-then-save pattern.
-->
<template>
  <div class="scheduling-panel">
    <el-skeleton v-if="loading" :rows="5" animated />
    <el-form
      v-else
      :model="form"
      label-width="220px"
      class="form"
      :disabled="saving"
    >
      <el-divider content-position="left">基础开关</el-divider>
      <el-form-item label="启用动态调度">
        <el-switch v-model="form.enabled" />
      </el-form-item>
      <el-form-item label="启用多样性调整">
        <el-switch v-model="form.diversityEnabled" />
      </el-form-item>
      <el-form-item label="启用自适应学习">
        <el-switch v-model="form.adaptiveLearningEnabled" />
      </el-form-item>

      <el-divider content-position="left">核心权重 (0-1)</el-divider>
      <el-form-item label="LinUCB 权重">
        <el-input-number v-model="form.linucbWeight" :min="0" :max="1" :step="0.05" :precision="2" />
      </el-form-item>
      <el-form-item label="公平性权重">
        <el-input-number v-model="form.fairnessWeight" :min="0" :max="1" :step="0.05" :precision="2" />
      </el-form-item>
      <el-form-item label="技能维护权重">
        <el-input-number v-model="form.skillMaintenanceWeight" :min="0" :max="1" :step="0.05" :precision="2" />
      </el-form-item>
      <el-form-item label="重复惩罚权重">
        <el-input-number v-model="form.repetitionWeight" :min="0" :max="1" :step="0.05" :precision="2" />
      </el-form-item>

      <el-divider content-position="left">时间参数 (天)</el-divider>
      <el-form-item label="技能遗忘天数">
        <el-input-number v-model="form.skillDecayDays" :min="1" :max="365" />
      </el-form-item>
      <el-form-item label="公平性周期">
        <el-input-number v-model="form.fairnessPeriodDays" :min="1" :max="90" />
      </el-form-item>
      <el-form-item label="重复判定天数">
        <el-input-number v-model="form.repetitionDays" :min="1" :max="30" />
      </el-form-item>
      <el-form-item label="最大同工序连续天">
        <el-input-number v-model="form.maxConsecutiveDays" :min="1" :max="30" />
      </el-form-item>

      <el-divider content-position="left">临时工调整</el-divider>
      <el-form-item label="临时工 LinUCB 因子">
        <el-input-number v-model="form.tempWorkerLinucbFactor" :min="0" :max="2" :step="0.1" :precision="2" />
      </el-form-item>
      <el-form-item label="临时工公平性因子">
        <el-input-number v-model="form.tempWorkerFairnessFactor" :min="0" :max="3" :step="0.1" :precision="2" />
      </el-form-item>
      <el-form-item label="临时工技能遗忘天">
        <el-input-number v-model="form.tempWorkerSkillDecayDays" :min="1" :max="60" />
      </el-form-item>
      <el-form-item label="临时工判定天">
        <el-input-number v-model="form.tempWorkerThresholdDays" :min="1" :max="180" />
      </el-form-item>
      <el-form-item label="每周最低分配数">
        <el-input-number v-model="form.tempWorkerMinAssignments" :min="0" :max="20" />
      </el-form-item>

      <el-divider content-position="left">自适应学习</el-divider>
      <el-form-item label="学习率">
        <el-input-number v-model="form.learningRate" :min="0" :max="1" :step="0.01" :precision="3" />
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="saving" @click="onSave">保存配置</el-button>
        <el-button @click="loadData">刷新</el-button>
        <el-text type="info" size="small" style="margin-left: 12px">
          v{{ form.version ?? 0 }} (并发控制)
        </el-text>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getScheduling, updateScheduling, type SchedulingConfig } from '@/api/factoryConfigHub'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const loading = ref(false)
const saving = ref(false)
const form = reactive<SchedulingConfig>({
  factoryId: props.factoryId,
  enabled: true,
  diversityEnabled: true,
  linucbWeight: 0.6,
  fairnessWeight: 0.15,
  skillMaintenanceWeight: 0.15,
  repetitionWeight: 0.1,
  skillDecayDays: 30,
  fairnessPeriodDays: 14,
  repetitionDays: 3,
  maxConsecutiveDays: 5,
  tempWorkerLinucbFactor: 0.7,
  tempWorkerFairnessFactor: 1.5,
  tempWorkerSkillDecayDays: 14,
  tempWorkerThresholdDays: 30,
  tempWorkerMinAssignments: 3,
  adaptiveLearningEnabled: true,
  learningRate: 0.05,
  efficiencyTarget: 0.85,
  diversityTarget: 0.7,
  version: 0,
})

async function loadData() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await getScheduling(props.factoryId)
    if (res.success && res.data) {
      Object.assign(form, res.data)
    }
  } finally {
    loading.value = false
  }
}

async function onSave() {
  if (!props.factoryId) return
  saving.value = true
  try {
    const res = await updateScheduling(props.factoryId, { ...form })
    if (res.success && res.data) {
      Object.assign(form, res.data)
      ElMessage.success('排班配置已保存')
    }
  } catch (e: any) {
    const errorCode = e?.response?.data?.errorCode
    if (errorCode === 'VERSION_CONFLICT') {
      await ElMessageBox.confirm(
        '配置已被他人修改, 是否加载最新版本?',
        '并发冲突',
        { type: 'warning', confirmButtonText: '加载最新', cancelButtonText: '保留我的编辑' },
      ).then(loadData).catch(() => { /* noop */ })
    }
  } finally {
    saving.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.scheduling-panel {
  padding: 8px 0;
}
.form {
  max-width: 800px;
}
</style>
