<!--
  IndicatorOverviewPane — Tab 1: 基本信息 + CRUD (PATCH semantics)。

  显示 + 编辑指标的基础字段, 含停用按钮 + 触发立即重算。
-->
<template>
  <div class="pane">
    <el-descriptions :column="2" border>
      <el-descriptions-item label="编码">
        <code>{{ detail.code }}</code>
      </el-descriptions-item>
      <el-descriptions-item label="名称">{{ detail.name }}</el-descriptions-item>
      <el-descriptions-item label="分类">
        <el-tag>{{ INDICATOR_CATEGORY_LABELS[detail.category] }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="单位">{{ detail.unit || '—' }}</el-descriptions-item>
      <el-descriptions-item label="计算策略">
        {{ COMPUTE_STRATEGY_LABELS[detail.computeStrategy] }}
      </el-descriptions-item>
      <el-descriptions-item label="缓存 TTL">
        {{ detail.cacheTtlSeconds || 0 }} 秒
      </el-descriptions-item>
      <el-descriptions-item label="状态">
        <el-tag :type="detail.isActive ? 'success' : 'info'">
          {{ detail.isActive ? '启用' : '已停用' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="显示顺序">{{ detail.displayOrder ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="最新值" :span="2">
        <strong style="color: var(--el-color-primary)">
          {{ detail.lastValue ?? '—' }} {{ detail.unit || '' }}
        </strong>
        <span class="ts" v-if="detail.lastComputedAt">
          ({{ formatTs(detail.lastComputedAt) }})
        </span>
      </el-descriptions-item>
      <el-descriptions-item label="描述" :span="2">
        <span class="desc">{{ detail.description || '—' }}</span>
      </el-descriptions-item>
    </el-descriptions>

    <div class="actions">
      <el-button @click="onEditClick" :icon="Edit">编辑</el-button>
      <el-button type="primary" @click="onRecompute" :icon="Refresh" :loading="recomputing">
        立即重算
      </el-button>
      <el-popconfirm
        :title="`确认停用指标 ${detail.name}? 历史 snapshot 不会删除。`"
        @confirm="onDeactivate"
      >
        <template #reference>
          <el-button type="danger" :icon="Delete" :disabled="!detail.isActive">
            停用
          </el-button>
        </template>
      </el-popconfirm>
    </div>

    <!-- Edit dialog -->
    <el-dialog v-model="editVisible" :title="`编辑指标: ${detail.name}`" width="600px">
      <el-form ref="formRef" :model="editForm" label-width="100px">
        <el-form-item label="名称" prop="name" required>
          <el-input v-model="editForm.name" maxlength="200" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="editForm.category" style="width: 100%">
            <el-option
              v-for="c in INDICATOR_CATEGORIES"
              :key="c"
              :label="INDICATOR_CATEGORY_LABELS[c]"
              :value="c"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="editForm.unit" maxlength="30" />
        </el-form-item>
        <el-form-item label="计算策略">
          <el-radio-group v-model="editForm.computeStrategy">
            <el-radio value="REALTIME">{{ COMPUTE_STRATEGY_LABELS.REALTIME }}</el-radio>
            <el-radio value="CACHED">{{ COMPUTE_STRATEGY_LABELS.CACHED }}</el-radio>
            <el-radio value="PRECOMPUTED">{{ COMPUTE_STRATEGY_LABELS.PRECOMPUTED }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="缓存 TTL (秒)">
          <el-input-number v-model="editForm.cacheTtlSeconds" :min="0" :step="60" />
        </el-form-item>
        <el-form-item label="显示顺序">
          <el-input-number v-model="editForm.displayOrder" :min="0" :step="10" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Edit, Refresh } from '@element-plus/icons-vue'
import {
  indicatorsApi,
  type IndicatorCategory,
  type IndicatorDetail,
  type ComputeStrategy,
  INDICATOR_CATEGORIES,
  INDICATOR_CATEGORY_LABELS,
  COMPUTE_STRATEGY_LABELS,
} from '@/api/canvasIndicators'

interface Props {
  factoryId: string
  detail: IndicatorDetail
}
const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'updated'): void
  (e: 'deactivated'): void
}>()

const editVisible = ref(false)
const saving = ref(false)
const recomputing = ref(false)
const editForm = ref({
  name: '',
  category: 'FACTORY' as IndicatorCategory,
  unit: '',
  computeStrategy: 'CACHED' as ComputeStrategy,
  cacheTtlSeconds: 3600,
  displayOrder: 0,
  description: '',
})

function onEditClick() {
  editForm.value.name = props.detail.name
  editForm.value.category = props.detail.category
  editForm.value.unit = props.detail.unit ?? ''
  editForm.value.computeStrategy = props.detail.computeStrategy
  editForm.value.cacheTtlSeconds = props.detail.cacheTtlSeconds ?? 3600
  editForm.value.displayOrder = props.detail.displayOrder ?? 0
  editForm.value.description = props.detail.description ?? ''
  editVisible.value = true
}

async function onSave() {
  if (!editForm.value.name?.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    await indicatorsApi.update(props.factoryId, props.detail.id, {
      name: editForm.value.name.trim(),
      category: editForm.value.category,
      unit: editForm.value.unit?.trim() || undefined,
      computeStrategy: editForm.value.computeStrategy,
      cacheTtlSeconds: editForm.value.cacheTtlSeconds,
      displayOrder: editForm.value.displayOrder,
      description: editForm.value.description?.trim() || undefined,
    })
    editVisible.value = false
    emit('updated')
  } catch (err) {
    console.error('save failed', err)
  } finally {
    saving.value = false
  }
}

async function onRecompute() {
  recomputing.value = true
  try {
    const resp = await indicatorsApi.recompute(props.factoryId, props.detail.id)
    const payload = resp.data
    ElMessage.success(
      `重算完成 value=${payload?.value ?? '—'}${payload?.note ? ` (${payload.note})` : ''}`,
    )
    emit('updated')
  } catch (err) {
    console.error('recompute failed', err)
  } finally {
    recomputing.value = false
  }
}

async function onDeactivate() {
  try {
    await indicatorsApi.deactivate(props.factoryId, props.detail.id)
    emit('deactivated')
  } catch (err) {
    console.error('deactivate failed', err)
  }
}

function formatTs(ts: string): string {
  try {
    return new Date(ts).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return ts
  }
}
</script>

<style scoped>
.pane {
  padding: 8px 0;
}

.actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}

.ts {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.desc {
  white-space: pre-wrap;
  color: var(--el-text-color-regular);
}
</style>
