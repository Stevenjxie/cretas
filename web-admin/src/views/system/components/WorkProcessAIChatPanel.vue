<template>
  <div class="work-process-ai-chat-panel">
    <div class="panel-header">
      <div>
        <div class="panel-title">{{ title }}</div>
        <div class="panel-subtitle">输入自然语言，AI 生成可审核草稿</div>
      </div>
      <el-tag size="small" type="info">{{ modeLabel }}</el-tag>
    </div>

    <div ref="messagesRef" class="chat-messages">
      <div v-for="(msg, index) in messages" :key="index" :class="['message', msg.role]">
        <div class="message-content">{{ msg.content }}</div>
        <div v-if="msg.diffPreview?.length" class="diff-preview">
          <div v-for="(diff, diffIndex) in msg.diffPreview" :key="diffIndex" class="diff-item">
            <div class="diff-text">
              <el-tag size="small" type="warning">{{ diff.type }}</el-tag>
              <span>{{ diff.description }}</span>
            </div>
            <el-button size="small" type="primary" link @click="emitDraft(diff.params)">
              应用到草稿
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <div class="chat-input">
      <el-input
        v-model="input"
        type="textarea"
        :rows="3"
        :disabled="disabled || loading"
        :placeholder="placeholder"
        @keydown.ctrl.enter.prevent="send"
      />
      <el-button type="primary" :loading="loading" :disabled="disabled || !input.trim()" @click="send">
        生成草稿
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import request from '@/api/request';

type ChatRole = 'system' | 'user' | 'assistant';

interface ChatDiff {
  type: string;
  tool: string;
  params: Record<string, unknown>;
  description: string;
}

interface ChatMessage {
  role: ChatRole;
  content: string;
  diffPreview?: ChatDiff[];
}

interface AIChatResponse {
  reply?: string;
  diffs?: ChatDiff[];
  applied?: boolean;
}

const props = withDefaults(defineProps<{
  factoryId: string;
  productTypeId?: string;
  endpoint: string;
  moduleCode: string;
  title?: string;
  disabled?: boolean;
}>(), {
  title: 'AI 配工序',
  productTypeId: '',
  disabled: false,
});

const emit = defineEmits<{
  applyDraft: [draft: Record<string, unknown>];
}>();

const modeLabel = computed(() => 'Plan');
const placeholder = computed(() => (
  props.productTypeId
    ? '例：第一步修油，滚揉交给莫云，第三步焯水。Ctrl+Enter 发送'
    : '请先选择产品'
));

const input = ref('');
const loading = ref(false);
const messagesRef = ref<HTMLElement>();
const messages = ref<ChatMessage[]>([
  { role: 'system', content: '选择产品后，描述工序顺序和责任小组长，AI 会生成草稿供你保存。' },
]);

async function send(): Promise<void> {
  const text = input.value.trim();
  if (!text || props.disabled || !props.productTypeId) {
    return;
  }

  input.value = '';
  messages.value.push({ role: 'user', content: text });
  loading.value = true;
  try {
    const res = await request.post<AIChatResponse>(props.endpoint, {
      message: text,
      mode: 'plan',
      moduleCode: props.moduleCode,
      params: {
        productTypeId: props.productTypeId,
      },
    });
    const data = res.data ?? {};
    messages.value.push({
      role: 'assistant',
      content: data.reply || '已生成草稿，请审核后应用。',
      diffPreview: data.diffs || [],
    });
  } catch {
    messages.value.push({
      role: 'assistant',
      content: 'AI 服务暂不可用，请稍后重试。',
    });
  } finally {
    loading.value = false;
    await nextTick();
    scrollMessagesToBottom();
  }
}

function scrollMessagesToBottom(): void {
  const messageContainer = messagesRef.value;
  messageContainer?.scrollTo({ top: messageContainer.scrollHeight, behavior: 'smooth' });
}

function emitDraft(draft: Record<string, unknown>): void {
  emit('applyDraft', draft);
}
</script>

<style scoped>
.work-process-ai-chat-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 360px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-light);
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.panel-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.panel-subtitle {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.chat-messages {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  max-height: 220px;
  overflow-y: auto;
}

.message {
  max-width: 95%;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.5;
}

.message.system {
  align-self: center;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-lighter);
}

.message.user {
  align-self: flex-end;
  background: var(--el-color-primary-light-9);
}

.message.assistant {
  align-self: flex-start;
  background: var(--el-fill-color-light);
}

.diff-preview {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}

.diff-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px;
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
}

.diff-text {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.chat-input {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
