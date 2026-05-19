<!--
  TemplatesList — Phase 3 Canvas-Notify, list view + edit/delete actions.

  fool-proof Rule 2: dialog header 显示 templateCode + 模板名.
  fool-proof Rule 3: channels 多选 checkbox (不让用户自由文本输入).
  fool-proof Rule 4: 创建时 409 提示已存在 + 跳转编辑.

  @since 2026-05-19
-->
<template>
  <div class="templates-list">
    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openCreate">新建模板</el-button>
      <el-input
        v-model="search"
        placeholder="搜索模板编码 / 标题"
        clearable
        style="width: 240px; margin-left: 12px;"
      />
    </div>

    <el-table
      v-loading="loading"
      :data="filteredTemplates"
      class="table"
      empty-text="暂无通知模板, 点击「新建模板」开始配置"
      stripe
    >
      <el-table-column prop="templateCode" label="编码" min-width="180" />
      <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
      <el-table-column label="渠道" min-width="220">
        <template #default="{ row }">
          <el-tag
            v-for="ch in (row.channels || [])"
            :key="ch"
            :type="channelTagType(ch)"
            size="small"
            style="margin-right: 4px; margin-bottom: 2px;"
          >
            {{ channelLabel(ch) }}
          </el-tag>
          <span v-if="!row.channels || row.channels.length === 0" class="muted">未配置</span>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" min-width="170">
        <template #default="{ row }">
          {{ formatDate(row.updatedAt) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openEdit(row)">编辑</el-button>
          <el-button type="primary" link size="small" @click="openTestSend(row)">测试发送</el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <TemplateFormDialog
      v-if="dialogOpen"
      v-model:open="dialogOpen"
      :factory-id="factoryId"
      :template="editingTemplate"
      :mode="dialogMode"
      @saved="onSaved"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  listTemplates,
  deleteTemplate,
  type NotifyTemplate,
  type NotifyChannel,
  NotifyChannelLabels,
} from '@/api/notifyTemplateApi'
import TemplateFormDialog from './TemplateFormDialog.vue'

interface Props {
  factoryId: string
}
const props = defineProps<Props>()

const loading = ref(false)
const templates = ref<NotifyTemplate[]>([])
const search = ref('')

const dialogOpen = ref(false)
const dialogMode = ref<'create' | 'edit' | 'test-send'>('create')
const editingTemplate = ref<NotifyTemplate | null>(null)

const filteredTemplates = computed(() => {
  if (!search.value.trim()) return templates.value
  const kw = search.value.toLowerCase()
  return templates.value.filter(
    (t) =>
      (t.templateCode || '').toLowerCase().includes(kw) ||
      (t.title || '').toLowerCase().includes(kw),
  )
})

async function loadTemplates() {
  if (!props.factoryId) return
  loading.value = true
  try {
    const res = await listTemplates(props.factoryId)
    templates.value = res.success && res.data ? res.data : []
  } catch {
    // Error toast handled by axios interceptor.
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingTemplate.value = null
  dialogMode.value = 'create'
  dialogOpen.value = true
}

function openEdit(row: NotifyTemplate) {
  editingTemplate.value = { ...row }
  dialogMode.value = 'edit'
  dialogOpen.value = true
}

function openTestSend(row: NotifyTemplate) {
  editingTemplate.value = { ...row }
  dialogMode.value = 'test-send'
  dialogOpen.value = true
}

async function handleDelete(row: NotifyTemplate) {
  try {
    await ElMessageBox.confirm(
      `确定删除通知模板 ${row.templateCode}? 删除后已绑定的工作流 notify 节点将无法发送.`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return // User cancelled.
  }
  try {
    const res = await deleteTemplate(props.factoryId, row.id!)
    if (res.success) {
      ElMessage.success('通知模板已删除')
      await loadTemplates()
    }
  } catch {
    // Error toast handled by interceptor.
  }
}

async function onSaved() {
  dialogOpen.value = false
  await loadTemplates()
}

// ==================== Helpers ====================

function channelLabel(ch: NotifyChannel): string {
  return NotifyChannelLabels[ch] || ch
}

function channelTagType(ch: NotifyChannel):
  'primary' | 'success' | 'warning' | 'info' | 'danger' {
  const map: Record<NotifyChannel, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
    WECHAT: 'success',
    DINGTALK: 'primary',
    EMAIL: 'warning',
    SMS: 'danger',
    IN_APP: 'info',
  }
  return map[ch] || 'info'
}

function formatDate(s?: string): string {
  if (!s) return '—'
  try {
    return new Date(s).toLocaleString('zh-CN')
  } catch {
    return s
  }
}

onMounted(loadTemplates)
</script>

<style scoped>
.templates-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.table {
  flex: 1;
}
</style>
