<script setup lang="ts">
/**
 * AnalysisChatPanel — reusable "整页 AI 分析面板" for analytics pages
 * (2026-07-12, member-analysis is the first consumer).
 *
 * Free-form questions use the unified Java intent orchestrator first. The
 * server then decides whether the turn is a narrow Gold read, a clarification,
 * a trusted context continuation or comprehensive synthesis. This prevents
 * analytics-page context from bypassing QueryPlan and Answer Contract.
 *
 * The focused chart context is sent in a dedicated `page_context` field rather
 * than prepended to `question`. This keeps display copy out of routing, date
 * parsing and semantic-cache matching while preserving chart-aware answers.
 *
 * Markdown + chart rendering is reused as-is from the existing chat UI
 * (ChatBubble.vue + ChatTypingIndicator.vue, same components
 * RestaurantChatPanel.vue uses) rather than re-implemented here — this
 * panel is a slimmer, page-agnostic shell around them (no restaurant-themed
 * styling, adds the 聚焦 selector + context-embedding on top).
 */
import { computed, nextTick, ref } from 'vue';
import { ElButton, ElInput, ElMessage, ElRadioButton, ElRadioGroup } from 'element-plus';
import ChatBubble from '../chat/ChatBubble.vue';
import ChatTypingIndicator from '../chat/ChatTypingIndicator.vue';
import { askRestaurantIntent } from '@/api/smartbi/restaurant-synthesis';
import type { ChatTurn } from '@/types/restaurant-chat';
import type { AnalysisChartContext } from './analysisBullets';

const props = defineProps<{
  /** Authenticated tenant used by the unified Java intent endpoint. */
  factoryId: string;
  /** One entry per chart on the page. Order is preserved in the 聚焦 selector. */
  contexts: AnalysisChartContext[];
  /** Page title, used as the "全页" focus label + fallback context title. */
  pageTitle: string;
}>();

const ALL_FOCUS = '__all__';
const focusKey = ref<string>(ALL_FOCUS);

const turns = ref<ChatTurn[]>([]);
const isTyping = ref(false);
const inputText = ref('');
const chatContainer = ref<HTMLElement | null>(null);
// Multi-turn memory scoped to the CURRENT focus — a focus switch starts a
// fresh session/conversation so the LLM doesn't carry over stale scope.
const sessionId = ref<string>(crypto.randomUUID());

/** Resolved focus target: either the whole page (all contexts concatenated) or one chart. */
interface FocusedAnalysisContext {
  title: string;
  dataSummary: string;
  dataScope: string;
  unavailableMetrics: string[];
  dimensionHints: string[];
}

const focusedContext = computed<FocusedAnalysisContext>(() => {
  if (focusKey.value === ALL_FOCUS) {
    const combined = props.contexts
      .filter((c) => c.dataSummary)
      .map((c) => `【${c.title}】${c.dataSummary}`)
      .join('\n');
    const scopes = props.contexts.map((c) => c.dataScope).filter((v): v is string => Boolean(v));
    const unavailableMetrics = [...new Set(props.contexts.flatMap((c) => c.unavailableMetrics ?? []))];
    return {
      title: props.pageTitle,
      dataSummary: combined,
      dataScope: scopes.join('；'),
      unavailableMetrics,
      dimensionHints: props.contexts.map((c) => c.key),
    };
  }
  const ctx = props.contexts.find((c) => c.key === focusKey.value);
  return ctx
    ? {
        title: ctx.title,
        dataSummary: ctx.dataSummary,
        dataScope: ctx.dataScope ?? '',
        unavailableMetrics: ctx.unavailableMetrics ?? [],
        dimensionHints: [ctx.key],
      }
    : {
        title: props.pageTitle,
        dataSummary: '',
        dataScope: '',
        unavailableMetrics: [],
        dimensionHints: [],
      };
});

const focusLabel = computed(() =>
  focusKey.value === ALL_FOCUS ? `整页 ${props.pageTitle}` : `「${focusedContext.value.title}」`,
);

function resetConversation() {
  sessionId.value = crypto.randomUUID();
  turns.value = [];
}

/** Programmatic focus switch (called by a chart's own 「聚焦」 button via ref). */
function setFocus(key: string) {
  const nextKey = key || ALL_FOCUS;
  if (nextKey === focusKey.value) return;
  focusKey.value = nextKey;
  resetConversation();
  ElMessage.success(`已聚焦到 ${nextKey === ALL_FOCUS ? '全页' : focusedContext.value.title}`);
}

function onFocusChange() {
  resetConversation();
}

/** Build bounded display context; the raw user question remains untouched. */
function buildPageContext(): string {
  const { title, dataSummary, dataScope, unavailableMetrics } = focusedContext.value;
  const lines = [
    `【页面焦点】${focusKey.value === ALL_FOCUS ? `整页「${title}」` : `图表「${title}」`}`,
    `【页面展示数据】${dataSummary || '暂无数据'}`,
  ];
  if (dataScope) lines.push(`【数据口径】${dataScope}`);
  if (unavailableMetrics.length) {
    lines.push(
      `【页面摘要未提供】${unavailableMetrics.join('、')}。未提供不等于 0；不得仅凭页面摘要推算这些指标。`,
    );
  }
  const context = lines.join('\n');
  return context.length <= 1900
    ? context
    : `${context.slice(0, 1860)}\n【提示】页面上下文过长，已安全截断`;
}

async function sendMessage(text?: string) {
  const question = (text ?? inputText.value).trim();
  if (!question || isTyping.value) return;

  const userTurn: ChatTurn = {
    id: crypto.randomUUID(),
    role: 'user',
    content: question,
    timestamp: Date.now(),
  };
  turns.value.push(userTurn);
  inputText.value = '';
  await scrollToBottom();

  isTyping.value = true;
  try {
    const response = await askRestaurantIntent(props.factoryId, question, sessionId.value, {
      pageContext: buildPageContext(),
      dimensionHints: focusedContext.value.dimensionHints,
    });

    if (!response.success) {
      // Honest failure: real backend/network message, never a fabricated answer.
      const errMsg = response.error || '分析失败，请稍后重试';
      turns.value.push({
        id: crypto.randomUUID(),
        role: 'ai',
        content: `**请求失败**\n\n${errMsg}`,
        timestamp: Date.now(),
        error: errMsg,
      });
      ElMessage.error('分析请求失败: ' + errMsg);
    } else {
      turns.value.push({
        id: crypto.randomUUID(),
        role: 'ai',
        content: response.answer || '已完成分析',
        timestamp: Date.now(),
        charts: response.charts,
        alerts: response.alerts,
        followUpActions: response.followUpActions,
        source: response.source,
      });
    }
  } catch (error: unknown) {
    const errMsg = error instanceof Error ? error.message : String(error);
    turns.value.push({
      id: crypto.randomUUID(),
      role: 'ai',
      content: `**请求失败**\n\n${errMsg}`,
      timestamp: Date.now(),
      error: errMsg,
    });
    ElMessage.error('分析请求失败: ' + errMsg);
  } finally {
    isTyping.value = false;
    await scrollToBottom();
  }
}

async function scrollToBottom() {
  await nextTick();
  if (chatContainer.value) {
    chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
  }
}

function clearConversation() {
  resetConversation();
  ElMessage.success('对话已清空');
}

/** Honest degraded-source note shown under a turn (never hidden — fool-proof 4位一体). */
function sourceNote(turn: ChatTurn): string | null {
  if (turn.role !== 'ai' || turn.error) return null;
  if (turn.source === 'degraded') return 'AI 分析当前降级（额度或服务暂不可用），以上为系统提示，非分析结果';
  return null;
}

defineExpose({ setFocus, sendMessage, clearConversation });
</script>

<template>
  <div class="acp-panel">
    <div class="acp-header">
      <div class="acp-title">
        <span class="acp-title-dot"></span>
        AI 分析 · {{ pageTitle }}
      </div>
      <el-button size="small" link @click="clearConversation">清空对话</el-button>
    </div>

    <div class="acp-focus-row">
      <span class="acp-focus-label">聚焦:</span>
      <el-radio-group v-model="focusKey" size="small" @change="onFocusChange">
        <el-radio-button :value="ALL_FOCUS">全页</el-radio-button>
        <el-radio-button v-for="ctx in contexts" :key="ctx.key" :value="ctx.key">
          {{ ctx.title }}
        </el-radio-button>
      </el-radio-group>
    </div>

    <div ref="chatContainer" class="acp-body">
      <div v-if="turns.length === 0" class="acp-empty">
        <div class="acp-empty-text">
          当前聚焦 {{ focusLabel }} — 问问我，例如: "用3条bullet解读并给营销建议" / "这个数据说明什么问题"
        </div>
      </div>

      <template v-for="turn in turns" :key="turn.id">
        <ChatBubble :turn="turn">
          <template #followups>
            <div
              v-if="turn.role === 'ai' && turn.followUpActions?.length"
              class="acp-followups"
            >
              <el-button
                v-for="action in turn.followUpActions"
                :key="action.question"
                size="small"
                round
                @click="sendMessage(action.question)"
              >
                {{ action.label }}
              </el-button>
            </div>
          </template>
        </ChatBubble>
        <div v-if="sourceNote(turn)" class="acp-source-note">{{ sourceNote(turn) }}</div>
      </template>

      <ChatTypingIndicator v-if="isTyping" />
    </div>

    <div class="acp-input">
      <el-input
        v-model="inputText"
        placeholder="输入问题, 回车发送..."
        :disabled="isTyping"
        @keyup.enter="sendMessage()"
      />
      <el-button type="primary" :loading="isTyping" @click="sendMessage()">发送</el-button>
    </div>
  </div>
</template>

<style scoped>
.acp-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 420px;
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
  overflow: hidden;
  --el-radio-button-checked-text-color: #fff;
}
.acp-header {
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.acp-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 14px;
  color: var(--el-text-color-primary, #303133);
}
.acp-title-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #409eff;
  box-shadow: 0 0 6px #409eff;
  flex-shrink: 0;
}
.acp-focus-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  flex-wrap: wrap;
}
.acp-focus-label {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  flex-shrink: 0;
}
.acp-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  min-height: 160px;
}
.acp-empty {
  text-align: center;
  padding: 40px 16px;
  color: var(--el-text-color-secondary, #909399);
}
.acp-empty-text {
  font-size: 13px;
  line-height: 1.6;
}
.acp-source-note {
  margin: -12px 0 16px 46px;
  font-size: 11px;
  color: #e6a23c;
}
.acp-followups {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.acp-input {
  padding: 12px 16px;
  border-top: 1px solid var(--el-border-color-lighter, #ebeef5);
  display: flex;
  gap: 8px;
}

:global(:root[data-theme='dark']) .acp-panel {
  background: #1d1e1f;
  border-color: #4c4d4f;
  --el-bg-color: #1d1e1f;
  --el-fill-color-blank: #1d1e1f;
  --el-text-color-primary: #f2f3f5;
  --el-text-color-regular: #dcdfe6;
  --el-text-color-secondary: #b1b3b8;
  --el-border-color: #4c4d4f;
  --el-border-color-lighter: #363637;
}
:global(:root[data-theme='dark']) .acp-panel :deep(.el-radio-button__inner) {
  color: #dcdfe6;
  background: #262727;
  border-color: #4c4d4f;
}
:global(:root[data-theme='dark']) .acp-panel :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  color: #fff;
  background: var(--el-color-primary, #409eff);
  border-color: var(--el-color-primary, #409eff);
}
:global(:root[data-theme='dark']) .acp-panel :deep(.el-radio-button.is-active .el-radio-button__inner) {
  color: #fff;
  background: var(--el-color-primary, #409eff);
  border-color: var(--el-color-primary, #409eff);
}
:global(:root[data-theme='dark']) .acp-panel :deep(.el-input__wrapper) {
  background: #262727;
  box-shadow: 0 0 0 1px #4c4d4f inset;
}
:global(:root[data-theme='dark']) .acp-panel :deep(.el-input__inner) {
  color: #f2f3f5;
}
:global(:root[data-theme='light']) .acp-panel { background: #fff; }
</style>
