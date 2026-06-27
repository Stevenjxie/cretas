<!-- AIChatPanel.vue — Right panel: 3 AI agent modes -->
<!-- Round 4 Fix P1-20: explicit mode buttons with data-mode attrs for UI tests -->
<template>
  <div class="ai-chat-panel" data-component="ai-chat-panel">
    <div class="mode-selector">
      <button
        v-for="m in modes"
        :key="m.value"
        type="button"
        :data-mode="m.value"
        class="mode-btn"
        :class="{ active: mode === m.value }"
        @click="mode = m.value"
      >
        {{ m.label }}
      </button>
    </div>

    <div class="mode-description">
 <template v-if="mode === 'autopilot'"> 全自动模式: 描述需求，AI 自动完成配置</template>
 <template v-else-if="mode === 'plan'"> 计划模式: AI 生成变更方案，逐项审核后应用</template>
 <template v-else> 操作模式: 手动操作时，AI 实时提示关联影响</template>
    </div>

    <div class="chat-messages" ref="messagesRef">
      <div v-for="(msg, i) in messages" :key="i" :class="['message', msg.role]">
        <div class="message-content">{{ msg.content }}</div>
        <div v-if="msg.diffPreview" class="diff-preview">
          <div v-for="(diff, j) in msg.diffPreview" :key="j" class="diff-item">
            <el-tag :type="diff.type.includes('TOGGLE') ? 'warning' : 'info'" size="small">
              {{ diff.type }}
            </el-tag>
            {{ diff.description }}
            <el-button size="small" type="primary" link @click="applyDiff(diff)">应用</el-button>
          </div>
        </div>
      </div>
    </div>

    <div class="chat-input">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        placeholder="描述你的配置需求..."
        @keydown.enter.ctrl="send"
      />
      <el-button type="primary" :loading="loading" @click="send">发送</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import request from '@/api/request'
import type { AIAgentMode, AIMessage, ConfigDiff } from '@/types/canvas'

const props = defineProps<{
  factoryId: string
  selectedModule?: string
}>()

const emit = defineEmits<{
  applyDiff: [diffs: ConfigDiff[]]
}>()

// Round 4 Fix P1-20: Default to 'plan' mode — safer for new users (review before apply).
// Autopilot mode is now explicit via data-mode="autopilot" button.
const mode = ref<AIAgentMode>('plan')
const modes: { label: string; value: AIAgentMode }[] = [
 { label: ' Autopilot', value: 'autopilot' },
 { label: ' Plan', value: 'plan' },
 { label: ' Action', value: 'action' },
]

const messages = ref<AIMessage[]>([
  { role: 'system', content: '欢迎使用 Canvas AI 助手。选择模式后开始配置。', timestamp: Date.now() },
])
const input = ref('')
const loading = ref(false)
const messagesRef = ref<HTMLElement>()

async function send() {
  if (!input.value.trim()) return
  const userMsg = input.value.trim()
  input.value = ''
  messages.value.push({ role: 'user', content: userMsg, timestamp: Date.now() })

  loading.value = true
  try {
    const res = await request.post(`/${props.factoryId}/config/v2/ai/chat`, {
      message: userMsg,
      mode: mode.value,
      moduleCode: props.selectedModule,
    })
    const data = res.data
    messages.value.push({
      role: 'assistant',
      content: data.reply,
      timestamp: Date.now(),
      diffPreview: data.diffs?.map((d: any): ConfigDiff => ({
        type: d.type as ConfigDiff['type'], path: String(d.tool ?? ''), before: null, after: d.params,
        description: d.description,
      })),
    })
  } catch {
    messages.value.push({ role: 'assistant', content: 'AI 服务暂不可用', timestamp: Date.now() })
  }
  loading.value = false
  await nextTick()
  messagesRef.value?.scrollTo({ top: messagesRef.value.scrollHeight, behavior: 'smooth' })
}

function applyDiff(diff: ConfigDiff) {
  emit('applyDiff', [diff])
}
</script>

<style scoped>
.ai-chat-panel { display: flex; flex-direction: column; height: 100%; padding: 12px; }
.mode-selector { margin-bottom: 8px; display: flex; gap: 4px; }
.mode-btn {
  flex: 1; padding: 6px 8px; font-size: 12px; cursor: pointer;
  border: 1px solid var(--el-border-color); background: var(--el-fill-color-light);
  border-radius: 4px; color: var(--el-text-color-regular);
}
.mode-btn.active {
  background: var(--el-color-primary); color: #fff; border-color: var(--el-color-primary);
}
.mode-btn:hover:not(.active) { background: var(--el-fill-color); }
.mode-description { font-size: 12px; color: #999; margin-bottom: 12px; }
.chat-messages { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 8px; }
.message { padding: 8px 12px; border-radius: 8px; font-size: 13px; max-width: 95%; }
.message.user { background: var(--el-color-primary-light-9); align-self: flex-end; }
.message.assistant { background: var(--el-fill-color-light); align-self: flex-start; }
.message.system { background: var(--el-fill-color-lighter); align-self: center; color: #999; font-size: 12px; }
.diff-preview { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.diff-item { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.chat-input { display: flex; gap: 8px; margin-top: 12px; }
</style>
