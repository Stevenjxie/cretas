<template>
  <el-card class="work-process-ai-card" shadow="never">
    <template #header>
      <div class="ai-card-header">
        <div>
          <div class="ai-title">AI 工序助手</div>
          <div class="ai-subtitle">用自然语言新增或修改工序主数据；投入/产出单位由 SKU 与 Workflow 端口确定。</div>
        </div>
        <el-segmented v-model="mode" :options="modeOptions" size="small" />
      </div>
    </template>

    <el-alert
      class="mode-hint"
      type="info"
      :closable="false"
      :title="modeHint"
      show-icon
    />

    <div ref="messagesRef" class="chat-messages">
      <div v-for="(message, index) in messages" :key="index" :class="['chat-message', message.role]">
        <div class="message-content">{{ message.content }}</div>
        <div v-if="message.diffs?.length" class="diff-list">
          <div v-for="(diff, diffIndex) in message.diffs" :key="diffIndex" class="diff-item">
            <el-tag size="small" :type="isWorkProcessDiff(diff) ? 'primary' : 'info'">
              {{ diff.type || 'FIELD_CHANGE' }}
            </el-tag>
            <span class="diff-desc">{{ diff.description || diff.tool }}</span>
            <el-button
              v-if="message.mode !== 'action' && isWorkProcessDiff(diff)"
              type="primary"
              link
              size="small"
              :loading="applyingDiffKey === diffKey(diff)"
              :disabled="appliedDiffKeys.has(diffKey(diff))"
              @click="applyDiff(diff, message.mode)"
            >
              {{ appliedDiffKeys.has(diffKey(diff))
                ? '已执行'
                : message.mode === 'autopilot' ? '确认并执行' : '应用' }}
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <div class="chat-input">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        placeholder="例如：新建焯水工序，加工类，标准时薪25"
        @keydown.ctrl.enter.prevent="send"
      />
      <el-button type="primary" :loading="loading" @click="send">发送</el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { isAxiosError } from 'axios';
import { ElMessage } from 'element-plus';
import { aiApplyDiffs, aiChat } from '@/api/canvasApi';
import type { AIAgentMode } from '@/types/canvas';
import type { ApiResponse } from '@/types/api';

const MODULE_CODE = 'work_process_catalog';
const WORK_PROCESS_TOOL = 'canvas_work_process_catalog';

interface CanvasDiff {
  type?: string;
  tool: string;
  params: Record<string, unknown>;
  description?: string;
}

interface AIResponse {
  reply: string;
  diffs?: CanvasDiff[];
  applied: boolean;
  receipt?: { id?: string; writeCount?: number; operationId?: string } | null;
}

interface ChatMessage {
  role: 'system' | 'user' | 'assistant';
  content: string;
  diffs?: CanvasDiff[];
  mode?: AIAgentMode;
}

const props = defineProps<{
  factoryId: string;
}>();

const emit = defineEmits<{
  applied: [];
}>();

const mode = ref<AIAgentMode>('plan');
const modeOptions = [
  { label: '计划', value: 'plan' },
  { label: '自动', value: 'autopilot' },
  { label: '建议', value: 'action' },
];
const input = ref('');
const loading = ref(false);
const applyingDiffKey = ref('');
const appliedDiffKeys = ref(new Set<string>());
const messagesRef = ref<HTMLElement>();
const messages = ref<ChatMessage[]>([
  {
    role: 'system',
    content: '请描述要新增或修改的工序。计划模式会先生成方案，点击“应用”后才写入。',
  },
]);
const modeHint = computed(() => ({
  plan: '计划模式：只生成候选方案；点击“应用”后才会写入。',
  autopilot: '自动模式：首次只生成候选预览；必须点击“确认并执行”后才会写入。',
  action: '建议模式：只分析影响并给出建议，不提供执行入口，也不会写入。',
}[mode.value]));

function isWorkProcessDiff(diff: CanvasDiff): boolean {
  return diff.tool === WORK_PROCESS_TOOL;
}

function diffKey(diff: CanvasDiff): string {
  return JSON.stringify({ tool: diff.tool, params: diff.params });
}

function buildInstruction(userMessage: string): string {
  return `你正在工序管理页处理工序主数据。必须使用工具 ${WORK_PROCESS_TOOL}。

工具参数:
- action: "create" 或 "update"
- id: 修改已有工序时必填
- processName: 工序名称
- processCategory: 工序类别
- estimatedMinutes: 标准工时，单位分钟
- standardYieldMin / standardYieldMax: 标准出成率上下限，用小数表达，例如 30% = 0.30
- needsInput: 是否需要录投入量
- standardHourlyRate: 标准时薪，元/小时

若用户表达“新建/新增/加一道”，使用 action=create。若表达“修改/更新/把某工序改为”，使用 action=update，并尽量从上下文保留或要求用户提供 id。
工序主数据不填写投入/产出单位；单位由 SKU 与 Workflow 端口绑定，禁止生成 unit/outputUnit 参数。
返回 JSON 数组，每项格式:
{"type":"FIELD_CHANGE","tool":"${WORK_PROCESS_TOOL}","params":{...},"description":"中文说明"}

用户需求: ${userMessage}`;
}

async function send(): Promise<void> {
  const text = input.value.trim();
  if (!text || !props.factoryId) return;

  input.value = '';
  const requestMode = mode.value;
  messages.value.push({ role: 'user', content: text, mode: requestMode });
  loading.value = true;

  try {
    const response = await aiChat(props.factoryId, {
      message: buildInstruction(text),
      mode: requestMode,
      moduleCode: MODULE_CODE,
    }) as ApiResponse<AIResponse>;
    const data = response.data;
    messages.value.push({
      role: 'assistant',
      content: data?.reply || 'AI 未返回内容',
      diffs: data?.diffs || [],
      mode: requestMode,
    });
    if (data?.applied) {
      ElMessage.warning('安全契约异常：首次 AI 对话不应直接写入；已刷新列表供核对');
      emit('applied');
    }
  } catch (e) {
    // D3 B-1: surface backend error message instead of generic fallback
    const backendMsg = isAxiosError(e) ? e.response?.data?.message : null;
    const displayMsg = backendMsg || 'AI 处理失败，请查看错误提示后重试。';
    messages.value.push({ role: 'assistant', content: displayMsg });
  } finally {
    loading.value = false;
    await scrollToBottom();
  }
}

async function applyDiff(diff: CanvasDiff, sourceMode?: AIAgentMode): Promise<void> {
  const key = diffKey(diff);
  if (!props.factoryId
    || (sourceMode !== 'plan' && sourceMode !== 'autopilot')
    || !isWorkProcessDiff(diff)
    || appliedDiffKeys.value.has(key)
    || applyingDiffKey.value) return;

  applyingDiffKey.value = key;
  try {
    await aiApplyDiffs(props.factoryId, [diff as unknown as Record<string, unknown>]);
    appliedDiffKeys.value.add(key);
    ElMessage.success('工序变更已应用');
    emit('applied');
  } catch (e) {
    // D3 B-1: request interceptor shows backend toast, but also push a chat message
    // so the failure is visible inline in the conversation (not just a transient toast)
    const backendMsg = isAxiosError(e) ? e.response?.data?.message : null;
    const errorText = backendMsg || '应用工序变更失败，请稍后重试';
    messages.value.push({ role: 'assistant', content: `应用失败: ${errorText}` });
  } finally {
    applyingDiffKey.value = '';
  }
}

async function scrollToBottom(): Promise<void> {
  await nextTick();
  messagesRef.value?.scrollTo({ top: messagesRef.value.scrollHeight, behavior: 'smooth' });
}
</script>

<style scoped>
.work-process-ai-card {
  margin-top: 16px;
}

.ai-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.ai-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.ai-subtitle {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.chat-messages {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 240px;
  overflow-y: auto;
  padding-right: 4px;
}

.mode-hint {
  margin-bottom: 12px;
}

.chat-message {
  max-width: 92%;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.5;
}

.chat-message.system {
  align-self: center;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-secondary);
}

.chat-message.user {
  align-self: flex-end;
  background: var(--el-color-primary-light-9);
}

.chat-message.assistant {
  align-self: flex-start;
  background: var(--el-fill-color-light);
}

.message-content {
  white-space: pre-wrap;
}

.diff-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}

.diff-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.diff-desc {
  flex: 1;
}

.chat-input {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>
