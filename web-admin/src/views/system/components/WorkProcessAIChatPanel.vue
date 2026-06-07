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
          <template v-for="(diff, diffIndex) in msg.diffPreview" :key="diffIndex">
            <!-- PRODUCT_WORK_PROCESS_DRAFT: render individual step cards -->
            <template v-if="diff.type === 'PRODUCT_WORK_PROCESS_DRAFT' && isDraftParams(diff.params)">
              <div class="draft-step-list">
                <div
                  v-for="(step, stepIndex) in getDraftSteps(diff.params)"
                  :key="stepIndex"
                  class="draft-step-card"
                >
                  <span class="step-order">{{ step.processOrder }}</span>
                  <span class="step-name">{{ step.processName }}</span>
                  <el-tag v-if="step.processCategory" size="small" type="info">
                    {{ step.processCategory }}
                  </el-tag>
                  <span v-if="step.responsibleWorkerName" class="step-assignee">
                    {{ step.responsibleWorkerName }}
                  </span>
                  <el-tag size="small" :type="step.operation === 'update' ? 'warning' : 'success'">
                    {{ step.operation === 'update' ? '更新' : '新建' }}
                  </el-tag>
                </div>
                <div v-if="getMissingProcesses(diff.params).length" class="missing-warning">
                  <el-alert
                    :title="`${getMissingProcesses(diff.params).length} 个工序未匹配，请先在工序管理中新建`"
                    type="warning"
                    :closable="false"
                    show-icon
                  />
                </div>
                <el-button
                  class="apply-draft-btn"
                  size="small"
                  type="primary"
                  @click="emitDraft(diff.params)"
                >
                  应用 {{ getDraftSteps(diff.params).length }} 道工序到草稿
                </el-button>
              </div>
            </template>
            <!-- Generic diff: single-line display -->
            <template v-else>
              <div class="diff-item">
                <div class="diff-text">
                  <el-tag size="small" type="warning">{{ diff.type }}</el-tag>
                  <span>{{ diff.description }}</span>
                </div>
                <el-button size="small" type="primary" link @click="emitDraft(diff.params)">
                  应用到草稿
                </el-button>
              </div>
            </template>
          </template>
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
    // D1 B-1: when diffs are empty (tool failure surfaced as error reply), show guidance text
    const hasDiffs = Array.isArray(data.diffs) && data.diffs.length > 0;
    messages.value.push({
      role: 'assistant',
      content: data.reply || (hasDiffs ? '已生成草稿，请审核后应用。' : '无法生成草稿，请检查工序描述'),
      diffPreview: data.diffs || [],
    });
  } catch (e) {
    console.error('[WorkProcessAIChatPanel] send failed:', e);
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

interface DraftStep {
  operation: string;
  productWorkProcessId: unknown;
  workProcessId: string;
  processName: string;
  processCategory: string | null;
  unit: string | null;
  processOrder: number;
  responsibleWorkerId: number | null;
  responsibleWorkerName: string | null;
}

interface MissingProcess {
  name: string;
  reason: string;
}

function isDraftParams(params: Record<string, unknown>): boolean {
  return Array.isArray(params.draft);
}

function getDraftSteps(params: Record<string, unknown>): DraftStep[] {
  if (!Array.isArray(params.draft)) return [];
  return params.draft as DraftStep[];
}

function getMissingProcesses(params: Record<string, unknown>): MissingProcess[] {
  if (!Array.isArray(params.missingProcesses)) return [];
  return params.missingProcesses as MissingProcess[];
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
  max-height: 400px;
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

/* PRODUCT_WORK_PROCESS_DRAFT step card renderer */
.draft-step-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 6px;
}

.draft-step-card {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-bg-color);
  font-size: 12px;
}

.step-order {
  min-width: 20px;
  font-weight: 700;
  color: var(--el-color-primary);
  text-align: center;
}

.step-name {
  flex: 1;
  font-weight: 500;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-assignee {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.missing-warning {
  margin-top: 4px;
}

.apply-draft-btn {
  margin-top: 8px;
  align-self: flex-start;
}

.chat-input {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
