<template>
  <div class="approval-ai-composer" data-testid="approval-workflow-ai-composer">
    <el-input
      v-model="input"
      type="textarea"
      :autosize="{ minRows: 1, maxRows: 4 }"
      resize="none"
      :disabled="disabled || loading"
      :placeholder="placeholder"
      name="approval-workflow-ai-request"
      autocomplete="off"
      aria-label="描述要对当前审批草稿进行的修改"
      @keydown.enter.exact.prevent="send"
      @keydown.ctrl.enter.prevent="send"
    />

    <div class="composer-footer">
      <div class="composer-tools">
        <el-popover v-model:visible="historyVisible" placement="top-start" :width="520" trigger="click">
          <template #reference>
            <el-button text>AI 对话</el-button>
          </template>
          <div class="history-panel">
            <strong>审批 Workflow AI</strong>
            <span>只修改当前草稿；运行版本和在途审批不受影响。</span>
            <div class="message-list">
              <div v-for="(message, index) in messages" :key="index" :class="message.role">
                {{ message.content }}
              </div>
            </div>
          </div>
        </el-popover>

        <el-dropdown trigger="click" :disabled="disabled || loading" @command="runQuickPrompt">
          <el-button text>快捷问题</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="prompt in quickPrompts" :key="prompt" :command="prompt">
                {{ prompt }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <span v-if="lastStatus" class="last-status" role="status">{{ lastStatus }}</span>
      </div>

      <el-button
        type="primary"
        :loading="loading"
        :disabled="disabled || !input.trim()"
        @click="send"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import request from '@/api/request'

interface ChatDiff {
  type: string
  params?: Record<string, unknown>
}

interface AIChatResponse {
  reply?: string
  diffs?: ChatDiff[]
}

interface Message {
  role: 'user' | 'assistant'
  content: string
}

const props = withDefaults(defineProps<{
  factoryId: string
  context: Record<string, unknown>
  contextLabel?: string
  disabled?: boolean
  applySpec: (spec: unknown) => Promise<boolean>
}>(), {
  contextLabel: '当前范围：整个审批画布',
  disabled: false,
})

const quickPrompts = [
  '把当前审批节点改为采购主管审批',
  '金额超过 5 万元时由工厂总管理员审批',
  '审批超过 12 小时自动转派并通知仓储主管',
]
const input = ref('')
const loading = ref(false)
const historyVisible = ref(false)
const lastStatus = ref('')
const messages = ref<Message[]>([])
let requestGeneration = 0

const placeholder = computed(() => `输入想要的修改（${props.contextLabel.replace(/^当前范围：/, '')}）`)

watch(() => props.context, () => {
  requestGeneration += 1
}, { deep: false })

async function send() {
  const text = input.value.trim()
  if (!text || props.disabled || loading.value) return
  const generation = ++requestGeneration
  input.value = ''
  lastStatus.value = ''
  messages.value.push({ role: 'user', content: text })
  loading.value = true
  try {
    const response = await request.post<AIChatResponse>(
      `/${props.factoryId}/config/v2/ai/chat`,
      {
        message: text,
        mode: 'plan',
        moduleCode: 'approval_workflow_config',
        params: { context: props.context },
      },
    )
    if (generation !== requestGeneration) return
    const data = response.data ?? {}
    const workflowDiff = data.diffs?.find((diff) => (
      diff.type === 'APPROVAL_WORKFLOW_SPEC'
      && diff.params
      && typeof diff.params === 'object'
    ))
    const spec = workflowDiff?.params?.spec
    if (!spec) {
      const guidance = data.reply || 'AI 暂时无法完成这项修改，请换一种说法重试。'
      messages.value.push({ role: 'assistant', content: guidance })
      lastStatus.value = guidance
      return
    }
    const applied = await props.applySpec(spec)
    if (!applied) {
      const rejected = '这次修改未通过画布校验，草稿保持不变。'
      messages.value.push({ role: 'assistant', content: rejected })
      lastStatus.value = rejected
      return
    }
    const success = '已完成，当前草稿已更新，可用“撤销”恢复。'
    messages.value.push({ role: 'assistant', content: success })
    lastStatus.value = success
  } catch (error) {
    if (generation !== requestGeneration) return
    console.error('[ApprovalWorkflowAIComposer] request failed', error)
    const message = 'AI 服务暂不可用，请稍后重试。'
    messages.value.push({ role: 'assistant', content: message })
    lastStatus.value = message
  } finally {
    if (generation === requestGeneration) {
      loading.value = false
      await nextTick()
    }
  }
}

async function runQuickPrompt(prompt: string) {
  input.value = prompt
  await send()
}
</script>

<style scoped>
.approval-ai-composer {
  width: 100%;
  padding: 10px 10px 8px;
  border: 1px solid #d9e5f2;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 6px 20px rgb(31 62 92 / 16%);
}

.approval-ai-composer:focus-within {
  border-color: #409eff;
}

.composer-footer,
.composer-tools {
  display: flex;
  align-items: center;
}

.composer-footer {
  justify-content: space-between;
  gap: 10px;
  margin-top: 4px;
}

.composer-tools {
  min-width: 0;
  gap: 2px;
}

.last-status {
  overflow: hidden;
  margin-left: 6px;
  color: #5d6879;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-panel {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.history-panel > span {
  color: #7a8599;
  font-size: 12px;
}

.message-list {
  display: flex;
  max-height: 320px;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
  margin-top: 10px;
}

.message-list > div {
  max-width: 92%;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 13px;
}

.message-list .user {
  align-self: flex-end;
  background: #eef6ff;
}

.message-list .assistant {
  align-self: flex-start;
  background: #f4f6f9;
}
</style>
