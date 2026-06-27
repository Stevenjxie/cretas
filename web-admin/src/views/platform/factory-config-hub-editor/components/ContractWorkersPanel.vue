<!--
  TempWorkersPanel — Phase B Factory Config Hub sub-tab 2.

  List + create + convert-to-permanent temp worker records.
-->
<template>
  <div class="temp-workers-panel">
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增临时工</el-button>
      <el-button @click="loadData">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="workerId" label="员工ID" width="100" />
      <el-table-column prop="hireDate" label="入职日期" width="120" />
      <el-table-column prop="expectedEndDate" label="预计结束" width="120" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.convertedToPermanent" type="success">已转正</el-tag>
          <el-tag v-else type="warning">临时工</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="currentSkillLevel" label="技能等级" width="100" />
      <el-table-column prop="avgEfficiency" label="平均效率" width="100">
        <template #default="{ row }">
          {{ (row.avgEfficiency * 100).toFixed(1) }}%
        </template>
      </el-table-column>
      <el-table-column prop="reliabilityScore" label="可靠性" width="100">
        <template #default="{ row }">
          {{ (row.reliabilityScore * 100).toFixed(1) }}%
        </template>
      </el-table-column>
      <el-table-column prop="daysEmployed" label="在职天" width="100" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="!row.convertedToPermanent"
            type="success"
            size="small"
            @click="onConvert(row)"
          >
            转正
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createOpen" title="新增临时工记录" width="500px">
      <el-form :model="createForm" label-width="120px">
        <el-form-item label="员工 ID" required>
          <el-input-number v-model="createForm.workerId" :min="1" />
        </el-form-item>
        <el-form-item label="入职日期" required>
          <el-date-picker v-model="createForm.hireDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="预计结束日期">
          <el-date-picker v-model="createForm.expectedEndDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="初始技能等级">
          <el-input-number v-model="createForm.initialSkillLevel" :min="1" :max="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listTempWorkers,
  createTempWorker,
  updateTempWorker,
  type TempWorker,
} from '@/api/factoryConfigHub'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const loading = ref(false)
const creating = ref(false)
const rows = ref<TempWorker[]>([])
const createOpen = ref(false)
const createForm = reactive<{
  workerId?: number
  hireDate?: string
  expectedEndDate?: string
  initialSkillLevel?: number
}>({})

async function loadData() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listTempWorkers(props.factoryId)
    if (res.success && res.data) rows.value = res.data
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createForm.workerId = undefined
  createForm.hireDate = new Date().toISOString().slice(0, 10)
  createForm.expectedEndDate = undefined
  createForm.initialSkillLevel = 1
  createOpen.value = true
}

async function onCreate() {
  if (!createForm.workerId) {
    ElMessage.warning('请填写员工 ID')
    return
  }
  if (!createForm.hireDate) {
    ElMessage.warning('请选择入职日期')
    return
  }
  creating.value = true
  try {
    const res = await createTempWorker(props.factoryId, createForm)
    if (res.success) {
      ElMessage.success('已新增临时工记录')
      createOpen.value = false
      await loadData()
    }
  } finally {
    creating.value = false
  }
}

async function onConvert(row: TempWorker) {
  await ElMessageBox.confirm(
    `确认将员工 ${row.workerId} 转为正式工?`,
    '转正确认',
    { type: 'warning' },
  ).catch((): null => null).then(async (confirmed: unknown): Promise<void> => {
    if (!confirmed) return
    if (!row.id) return
    try {
      const today = new Date().toISOString().slice(0, 10)
      await updateTempWorker(props.factoryId, row.id, {
        version: row.version,
        convertedToPermanent: true,
        conversionDate: today,
      })
      ElMessage.success('已转正')
      await loadData()
    } catch (e: any) {
      const errorCode = e?.response?.data?.errorCode
      if (errorCode === 'VERSION_CONFLICT') {
        ElMessage.warning('记录被他人修改, 刷新后重试')
        await loadData()
      }
    }
  })
}

onMounted(loadData)
</script>

<style scoped>
.temp-workers-panel {
  padding: 8px 0;
}
.toolbar {
  margin-bottom: 12px;
  display: flex;
  gap: 8px;
}
</style>
