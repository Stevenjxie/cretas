<!--
  EncodingRulesPanel — Phase B Factory Config Hub sub-tab 5.

  业务单据编号规则 CRUD.
  Pattern placeholders: {PREFIX}, {FACTORY}, {YYYY}, {MM}, {DD}, {SEQ:N}
  Example: MB-{FACTORY}-{YYYYMMDD}-{SEQ:4} → MB-F001-20260522-0001
-->
<template>
  <div class="encoding-rules-panel">
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增规则</el-button>
      <el-button @click="loadData">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" stripe>
      <el-table-column prop="entityType" label="实体类型" width="160" />
      <el-table-column prop="ruleName" label="规则名称" width="140" />
      <el-table-column prop="encodingPattern" label="编码模板" min-width="240" show-overflow-tooltip />
      <el-table-column prop="prefix" label="前缀" width="80" />
      <el-table-column prop="sequenceLength" label="序号长度" width="100" />
      <el-table-column prop="resetCycle" label="重置周期" width="100">
        <template #default="{ row }">
          {{ RESET_CYCLE_LABELS[row.resetCycle as ResetCycle] || row.resetCycle }}
        </template>
      </el-table-column>
      <el-table-column prop="currentSequence" label="当前序号" width="100" />
      <el-table-column prop="version" label="业务版本" width="90">
        <template #default="{ row }">v{{ row.version }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogOpen" :title="dialogTitle" width="600px">
      <el-form :model="form" label-width="140px">
        <el-form-item label="实体类型" required>
          <el-input v-model="form.entityType" :disabled="editingId != null"
                    placeholder="例: MATERIAL_BATCH / PROCESSING_BATCH / SHIPMENT" />
        </el-form-item>
        <el-form-item label="规则名称" required>
          <el-input v-model="form.ruleName" placeholder="例: 原材料批次编号" />
        </el-form-item>
        <el-form-item label="规则描述">
          <el-input v-model="form.ruleDescription" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="编码模板" required>
          <el-input v-model="form.encodingPattern" placeholder="MB-{FACTORY}-{YYYYMMDD}-{SEQ:4}" />
        </el-form-item>
        <el-form-item>
          <el-text type="info" size="small">
            占位符: {{ '{PREFIX} {FACTORY} {YYYY} {MM} {DD} {SEQ:N}' }}
          </el-text>
        </el-form-item>
        <el-form-item label="前缀">
          <el-input v-model="form.prefix" placeholder="MB / SH / QI / ..." />
        </el-form-item>
        <el-form-item label="序号长度">
          <el-input-number v-model="form.sequenceLength" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="重置周期" required>
          <el-select v-model="form.resetCycle">
            <el-option
              v-for="r in RESET_CYCLE_OPTIONS"
              :key="r.value"
              :label="r.label"
              :value="r.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="分隔符">
          <el-input v-model="form.separator" placeholder="-" maxlength="5" />
        </el-form-item>
        <el-form-item label="包含工厂代码">
          <el-switch v-model="form.includeFactoryCode" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
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
  listEncodingRules,
  createEncodingRule,
  updateEncodingRule,
  RESET_CYCLE_LABELS,
  type EncodingRule,
  type ResetCycle,
} from '@/api/factoryConfigHub'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const RESET_CYCLE_OPTIONS = [
  { value: 'DAILY', label: '每日重置' },
  { value: 'MONTHLY', label: '每月重置' },
  { value: 'YEARLY', label: '每年重置' },
  { value: 'NEVER', label: '不重置' },
]

const loading = ref(false)
const saving = ref(false)
const rows = ref<EncodingRule[]>([])
const dialogOpen = ref(false)
const editingId = ref<string | null>(null)
const editingLockVersion = ref<number>(0)
const dialogTitle = computed(() => editingId.value == null ? '新增编码规则' : '编辑编码规则')

const form = reactive<Partial<EncodingRule>>({
  enabled: true,
  includeFactoryCode: true,
  separator: '-',
  resetCycle: 'DAILY',
  sequenceLength: 4,
})

async function loadData() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listEncodingRules(props.factoryId)
    if (res.success && res.data) rows.value = res.data
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  editingLockVersion.value = 0
  Object.assign(form, {
    entityType: '',
    ruleName: '',
    ruleDescription: '',
    encodingPattern: '',
    prefix: '',
    sequenceLength: 4,
    resetCycle: 'DAILY' as ResetCycle,
    separator: '-',
    includeFactoryCode: true,
    enabled: true,
  })
  dialogOpen.value = true
}

function openEdit(row: EncodingRule) {
  editingId.value = row.id ?? null
  editingLockVersion.value = row.lockVersion
  Object.assign(form, {
    entityType: row.entityType,
    ruleName: row.ruleName,
    ruleDescription: row.ruleDescription,
    encodingPattern: row.encodingPattern,
    prefix: row.prefix,
    sequenceLength: row.sequenceLength,
    resetCycle: row.resetCycle,
    separator: row.separator,
    includeFactoryCode: row.includeFactoryCode,
    enabled: row.enabled,
  })
  dialogOpen.value = true
}

async function onSave() {
  if (!form.entityType || !form.ruleName || !form.encodingPattern) {
    ElMessage.warning('实体类型 / 规则名 / 编码模板 均必填')
    return
  }
  saving.value = true
  try {
    if (editingId.value == null) {
      await createEncodingRule(props.factoryId, form)
      ElMessage.success('规则已创建')
    } else {
      await updateEncodingRule(props.factoryId, editingId.value, {
        ...form,
        lockVersion: editingLockVersion.value,
      })
      ElMessage.success('规则已更新, 业务版本递增')
    }
    dialogOpen.value = false
    await loadData()
  } catch (e: any) {
    const errorCode = e?.response?.data?.errorCode
    if (errorCode === 'VERSION_CONFLICT') {
      ElMessage.warning('规则被他人修改, 刷新后重试')
      await loadData()
      dialogOpen.value = false
    }
  } finally {
    saving.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.encoding-rules-panel {
  padding: 8px 0;
}
.toolbar {
  margin-bottom: 12px;
  display: flex;
  gap: 8px;
}
</style>
