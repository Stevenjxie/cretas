<!--
  TemplatesList — Phase 3 Canvas-Notify, list view + edit/delete actions.

  fool-proof Rule 2: dialog header 显示 templateCode + 模板名.
  fool-proof Rule 3: channels 多选 checkbox (不让用户自由文本输入).
  fool-proof Rule 4: 创建时 409 提示已存在 + 跳转编辑.

  @since 2026-05-19
  @updated 2026-05-19 (B-N1/B-N2 hotfix) — disable create/edit/delete buttons until
  Phase 3 backend CRUD ready. NotifyTemplateController POST/PUT/DELETE currently
  return 501 stubs. List GET + test-send + logs DO work, so keep those visible.
-->
<template>
  <div class="templates-list">
    <el-alert
      title="通知模板 CRUD 功能开发中"
      type="info"
      :closable="false"
      show-icon
      class="phase3-banner"
    >
      <template #default>
        Phase 3 即将上线完整的「新建 / 编辑 / 删除」功能 (5 渠道: 企业微信 / 钉钉 /
        邮件 / 短信 / 站内信). 当前仅支持<strong>查看模板列表</strong>、<strong>测试发送</strong>
        与<strong>发送日志审计</strong>.
      </template>
    </el-alert>

    <div class="toolbar">
      <!-- UX polish (2026-05-20): wrap disabled button in el-tooltip so users get
           a styled hint on hover/focus instead of relying on browser native title
           (which has delayed/inconsistent behavior across browsers + no styling). -->
      <el-tooltip
        content="Phase 3 上线后开放新建; 当前可使用「测试发送」验证已存在模板"
        placement="top"
        :show-after="200"
      >
        <!-- el-tooltip needs a non-disabled wrapper to receive hover events on
             a disabled child element. Use span. -->
        <span>
          <el-button type="primary" :icon="Plus" disabled>
            新建模板
          </el-button>
        </span>
      </el-tooltip>
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
      empty-text="暂无通知模板 (Phase 3 上线后将开放新建)"
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
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openTestSend(row)">
            测试发送
          </el-button>
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
import { Plus } from '@element-plus/icons-vue'
import {
  listTemplates,
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

// Dialog still mounted for test-send only — create/edit/delete disabled per
// B-N1/B-N2 (NotifyTemplateController POST/PUT/DELETE return 501 until Phase 3).
const dialogOpen = ref(false)
const dialogMode = ref<'create' | 'edit' | 'test-send'>('test-send')
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

function openTestSend(row: NotifyTemplate) {
  editingTemplate.value = { ...row }
  dialogMode.value = 'test-send'
  dialogOpen.value = true
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

.phase3-banner {
  margin-bottom: 4px;
}

.phase3-banner :deep(strong) {
  color: var(--el-color-info-dark-2);
  font-weight: 600;
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
