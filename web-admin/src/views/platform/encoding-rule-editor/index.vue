<!--
  EncodingRuleEditor — Canvas Phase BCP3 (2026-05-22).

  P3 半-Canvas-ed: 包装已存的 EncodingRule entity, 提供 CRUD UI.

  Backend: /api/mobile/{factoryId}/canvas-encoding-rule
  防呆 (Rule 1+2+4+5): 已存 entityType 防重 (409 actionHint), AUD-4 P1 乐观锁.
-->
<template>
  <div class="encoding-rule-editor">
    <div class="panel-header">
      <h3>编码规则</h3>
      <div class="panel-actions">
        <el-tag size="small" type="info">factoryId: {{ factoryId }}</el-tag>
        <el-button size="small" @click="loadList" :disabled="loading">刷新</el-button>
        <el-button type="primary" size="small" @click="onCreate">新建规则</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rules" stripe style="width: 100%"
              empty-text="暂无编码规则">
      <el-table-column prop="entityType" label="实体类型" min-width="160">
        <template #default="{ row }">
          <code class="key-cell">{{ row.entityType }}</code>
          <el-tag v-if="!row.factoryId" size="small" type="info" style="margin-left: 6px">
            系统默认
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="ruleName" label="规则名称" min-width="140" />
      <el-table-column prop="encodingPattern" label="编码模板" min-width="220">
        <template #default="{ row }">
          <code class="pattern-cell">{{ row.encodingPattern }}</code>
        </template>
      </el-table-column>
      <el-table-column prop="sequenceLength" label="序列长度" width="100" />
      <el-table-column prop="resetCycle" label="重置周期" width="120">
        <template #default="{ row }">
          <el-tag size="small">{{ RESET_CYCLE_LABELS[row.resetCycle as ResetCycle] || row.resetCycle }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="currentSequence" label="当前序号" width="100" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
            {{ row.enabled ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link :disabled="!row.factoryId"
                     @click="onEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" link :disabled="!row.factoryId"
                     @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Edit / Create Dialog -->
    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建编码规则' : '编辑编码规则'"
               width="600px" :close-on-click-modal="false">
      <el-form :model="editForm" :rules="formRules" ref="formRef" label-width="120px">
        <el-form-item label="实体类型" prop="entityType">
          <el-input v-model="editForm.entityType" :disabled="dialogMode === 'edit'"
                    placeholder="MATERIAL_BATCH / PROCESSING_BATCH / SHIPMENT" />
        </el-form-item>
        <el-form-item label="规则名称" prop="ruleName">
          <el-input v-model="editForm.ruleName" placeholder="如: 原材料批次编码" />
        </el-form-item>
        <el-form-item label="编码模板" prop="encodingPattern">
          <el-input v-model="editForm.encodingPattern"
                    placeholder="MB-{FACTORY}-{YYYYMMDD}-{SEQ:4}" />
          <div class="form-hint">
            占位符: {PREFIX}, {FACTORY}, {YYYY}, {MM}, {DD}, {SEQ:N}
          </div>
        </el-form-item>
        <el-form-item label="序列长度" prop="sequenceLength">
          <el-input-number v-model="editForm.sequenceLength" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="重置周期" prop="resetCycle">
          <el-select v-model="editForm.resetCycle" style="width: 100%">
            <el-option v-for="rc in RESET_CYCLES" :key="rc" :label="RESET_CYCLE_LABELS[rc]" :value="rc" />
          </el-select>
        </el-form-item>
        <el-form-item label="规则描述">
          <el-input v-model="editForm.ruleDescription" type="textarea" :rows="2"
                    maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editForm.enabled" />
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
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { canvasEncodingRuleApi, RESET_CYCLE_LABELS, type EncodingRule, type ResetCycle } from '@/api/canvasEncodingRule'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const RESET_CYCLES: ResetCycle[] = ['DAILY', 'MONTHLY', 'YEARLY', 'NEVER']

const loading = ref(false)
const saving = ref(false)
const rules = ref<EncodingRule[]>([])

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editForm = ref<Partial<EncodingRule>>({
  entityType: '',
  ruleName: '',
  encodingPattern: '',
  sequenceLength: 4,
  resetCycle: 'DAILY',
  enabled: true,
})
const currentEditId = ref<string | null>(null)
const currentEditVersion = ref<number | undefined>(undefined)

const formRules: FormRules = {
  entityType: [{ required: true, message: '请填写实体类型', trigger: 'blur' }],
  ruleName: [{ required: true, message: '请填写规则名称', trigger: 'blur' }],
  encodingPattern: [{ required: true, message: '请填写编码模板', trigger: 'blur' }],
}

async function loadList() {
  loading.value = true
  try {
    const resp = await canvasEncodingRuleApi.list(props.factoryId)
    rules.value = resp.data || []
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
  editForm.value = {
    entityType: '',
    ruleName: '',
    encodingPattern: '',
    sequenceLength: 4,
    resetCycle: 'DAILY',
    enabled: true,
  }
  currentEditId.value = null
  currentEditVersion.value = undefined
  dialogVisible.value = true
}

function onEdit(row: EncodingRule) {
  dialogMode.value = 'edit'
  editForm.value = { ...row }
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
        await canvasEncodingRuleApi.create(props.factoryId, editForm.value)
        ElMessage.success('编码规则已创建')
      } else if (currentEditId.value) {
        await canvasEncodingRuleApi.update(props.factoryId, currentEditId.value, {
          ...editForm.value,
          version: currentEditVersion.value,
        })
        ElMessage.success('编码规则已更新')
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

async function onDelete(row: EncodingRule) {
  try {
    await ElMessageBox.confirm(
      `确认删除编码规则 "${row.entityType}" ?`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await canvasEncodingRuleApi.remove(props.factoryId, row.id)
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
.encoding-rule-editor { padding: 16px; }
.panel-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 16px;
}
.panel-header h3 { margin: 0; }
.panel-actions { display: flex; gap: 8px; align-items: center; }
.key-cell, .pattern-cell {
  font-family: 'Consolas', monospace; background: #f3f4f6;
  padding: 2px 6px; border-radius: 3px; font-size: 12px;
}
.form-hint { font-size: 12px; color: #909399; margin-top: 4px; }
</style>
