<script setup lang="ts">
/**
 * Restaurant free-form chat.
 *
 * Every user turn enters through the unified Java intent orchestrator. It owns
 * query planning, missing-time/store clarification, session continuation,
 * narrow Gold reads and comprehensive-synthesis fallback. Calling Python
 * synthesis directly here would bypass those contracts and make page behavior
 * disagree with the same question sent through the canonical API.
 */
import { ref, nextTick } from 'vue';
import { ElInput, ElButton, ElMessage, ElMessageBox } from 'element-plus';
import ChatBubble from './ChatBubble.vue';
import ChatTypingIndicator from './ChatTypingIndicator.vue';
import GrossMarginDeclineRun from './GrossMarginDeclineRun.vue';
import {
  askRestaurantIntent,
  sendRestaurantAnswerFeedback,
} from '@/api/smartbi/restaurant-synthesis';
import type { RestaurantSynthesisResult } from '@/api/smartbi/restaurant-synthesis';
import type { ChatTurn } from '@/types/restaurant-chat';

// This panel answers over the store's whole real 经营 dataset (backend derives
// the tenant from the JWT). It intentionally does NOT read the dashboard's
// selected upload-Excel — that upload-driven Q&A is a separate surface. The
// former subSector/uploadId props were passed but ignored, so they are dropped
// here (and unbound in the parent) to avoid misleading callers.
const props = defineProps<{
  factoryId: string;
  agentRunEligible: boolean;
  startDate: string;
  endDate: string;
}>();

const turns = ref<ChatTurn[]>([]);
const isTyping = ref(false);
const inputText = ref('');
const chatContainer = ref<HTMLElement | null>(null);
// P2 multi-turn memory: stable session id for the lifetime of this
// conversation, so the backend can resolve "它"/"那家店"/"第三点" follow-ups
// (smartbi.services.chat_session_service.ChatSessionService). Reset on
// clearConversation — a fresh conversation should not inherit old context.
const sessionId = ref<string>(crypto.randomUUID());
// Non-blocking status line shown while the canonical intent route is running.
const streamStatus = ref('');

function pushErrorTurn(errMsg: string) {
  turns.value.push({
    id: crypto.randomUUID(),
    role: 'ai',
    content: `**请求失败**\n\n${errMsg}`,
    timestamp: Date.now(),
    error: errMsg,
  });
  ElMessage.error('聊天请求失败: ' + errMsg);
}

function applyResult(turn: ChatTurn, response: RestaurantSynthesisResult, query: string) {
  turn.content = response.answer || turn.content || '已完成分析';
  turn.charts = response.charts;
  turn.alerts = response.alerts;
  turn.followUpActions = response.followUpActions;
  turn.source = response.source;
  turn.sourceQuery = query;
}

async function sendMessage(text?: string) {
  const query = (text ?? inputText.value).trim();
  if (!query || isTyping.value) return;

  const userTurn: ChatTurn = {
    id: crypto.randomUUID(),
    role: 'user',
    content: query,
    timestamp: Date.now(),
  };
  turns.value.push(userTurn);
  inputText.value = '';
  await scrollToBottom();

  isTyping.value = true;
  streamStatus.value = '正在识别问题并读取经营数据…';

  try {
    const response = await askRestaurantIntent(
      props.factoryId,
      query,
      sessionId.value,
    );
    if (response.success) {
      const aiTurn: ChatTurn = {
        id: crypto.randomUUID(),
        role: 'ai',
        content: '',
        timestamp: Date.now(),
      };
      applyResult(aiTurn, response, query);
      turns.value.push(aiTurn);
    } else {
      pushErrorTurn(response.error || '分析失败，请稍后重试');
    }
  } catch (error: unknown) {
    pushErrorTurn(error instanceof Error ? error.message : String(error));
  } finally {
    isTyping.value = false;
    streamStatus.value = '';
    await scrollToBottom();
  }
}

async function scrollToBottom() {
  await nextTick();
  if (chatContainer.value) {
    chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
  }
}

async function clearConversation() {
  sessionId.value = crypto.randomUUID();
  turns.value = [];
  ElMessage.success('对话已清空');
}

// 👍/👎 反馈 (飞轮断点2, 2026-07-23): 乐观翻转, 失败回滚; 👎 附可选说明。
// 后端按 (租户, 问法原文) 关联最近一条飞轮捕获行, 反馈直接进晋升评审证据链。
async function sendFeedback(turn: ChatTurn, value: 1 | -1) {
  if (!turn.sourceQuery || turn.feedbackPending) return;
  if (turn.feedbackValue === value) return;
  const prevValue = turn.feedbackValue;
  turn.feedbackPending = true;
  turn.feedbackValue = value;
  let comment: string | undefined;
  if (value === -1) {
    const result = await ElMessageBox.prompt('说一下哪里不准确? (可选)', '反馈', {
      confirmButtonText: '提交',
      cancelButtonText: '取消',
      inputValidator: () => true,
    }).catch((): null => null);
    if (result === null) {
      turn.feedbackValue = prevValue;
      turn.feedbackPending = false;
      return;
    }
    comment = (result as { value?: string }).value || undefined;
  }
  const ok = await sendRestaurantAnswerFeedback(turn.sourceQuery, value, comment);
  if (!ok) {
    turn.feedbackValue = prevValue;
    ElMessage.warning('反馈提交失败, 请稍后重试');
  } else {
    ElMessage.success(value === 1 ? '感谢反馈' : '已记录, 我们会改进');
  }
  turn.feedbackPending = false;
}

defineExpose({
  sendMessage,
  clearConversation,
});
</script>

<template>
  <div class="restaurant-chat-panel">
    <div class="chat-header">
      <div class="chat-title">
        <span class="chat-title-dot"></span>
        SmartBI · 餐饮诊断助手
      </div>
      <el-button size="small" link @click="clearConversation">清空对话</el-button>
    </div>

    <div class="chat-scope-note">
      本问答基于全店真实经营数据（评价 / 财务 / 成本率 / 供应商价格），不读取上方所选的上传 Excel。
    </div>

    <GrossMarginDeclineRun
      :factory-id="factoryId"
      :eligible="agentRunEligible"
      :start-date="startDate"
      :end-date="endDate"
    />

    <div ref="chatContainer" class="chat-body">
      <div v-if="turns.length === 0" class="chat-empty">
        <div class="chat-empty-icon">&#9660;</div>
        <div class="chat-empty-text">
          问问我 — 例如: "这两个月赚钱没利润率多少" / "领料成本率涨了吗" / "有没有供应商偷偷涨价"
        </div>
      </div>

      <template v-for="turn in turns" :key="turn.id">
        <ChatBubble
          v-if="turn.role !== 'ai' || turn.content || turn.error || (turn.alerts && turn.alerts.length)"
          :turn="turn"
        >
          <template #followups>
            <div
              v-if="turn.role === 'ai' && turn.followUpActions?.length"
              class="chat-followups"
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
        <div
          v-if="turn.role === 'ai' && !turn.error && turn.sourceQuery"
          class="chat-feedback-row"
        >
          <button
            class="chat-feedback-btn"
            :class="{ active: turn.feedbackValue === 1 }"
            :disabled="turn.feedbackPending"
            title="答得准"
            @click="sendFeedback(turn, 1)"
          >&#128077;</button>
          <button
            class="chat-feedback-btn"
            :class="{ active: turn.feedbackValue === -1 }"
            :disabled="turn.feedbackPending"
            title="不准确"
            @click="sendFeedback(turn, -1)"
          >&#128078;</button>
        </div>
      </template>

      <div v-if="isTyping && streamStatus" class="chat-stream-status" data-testid="chat-stream-status">
        {{ streamStatus }}
      </div>
      <ChatTypingIndicator v-if="isTyping" />
    </div>

    <div class="chat-input">
      <el-input
        v-model="inputText"
        placeholder="输入问题, 回车发送..."
        :disabled="isTyping"
        @keyup.enter="sendMessage()"
      />
      <el-button
        type="primary"
        :loading="isTyping"
        @click="sendMessage()"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.restaurant-chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #faf7f0;
}
.chat-header {
  padding: 14px 20px;
  border-bottom: 1px solid #d4cdb8;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.chat-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: 'Playfair Display', 'Noto Serif SC', serif;
  font-weight: 700;
  font-size: 16px;
  color: #2d4a3e;
}
.chat-title-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #c9a66b;
  box-shadow: 0 0 8px #c9a66b;
}
.chat-scope-note {
  padding: 8px 20px;
  background: #f2ece0;
  border-bottom: 1px solid #e8e1cc;
  font-family: 'Noto Serif SC', serif;
  font-size: 12px;
  line-height: 1.5;
  color: #6b6b6b;
}
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
}
.chat-empty {
  text-align: center;
  padding: 60px 20px;
  color: #a8a29e;
}
.chat-empty-icon {
  font-size: 40px;
  color: #c9a66b;
  margin-bottom: 14px;
  animation: bob 2s ease-in-out infinite;
}
.chat-empty-text {
  font-family: 'Noto Serif SC', serif;
  font-size: 14px;
  font-style: italic;
}
@keyframes bob {
  0%, 100% {
    transform: translateY(0);
    opacity: 0.6;
  }
  50% {
    transform: translateY(6px);
    opacity: 1;
  }
}
.chat-input {
  padding: 14px 20px;
  border-top: 1px solid #d4cdb8;
  display: flex;
  gap: 10px;
}
.chat-stream-status {
  font-family: 'Noto Serif SC', serif;
  font-size: 12px;
  font-style: italic;
  color: #8a8378;
  padding: 2px 4px 8px;
}
.chat-followups {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.chat-feedback-row {
  display: flex;
  gap: 6px;
  margin: -6px 0 12px 4px;
}
.chat-feedback-btn {
  border: 1px solid #d4cdb8;
  background: #fdfbf5;
  border-radius: 12px;
  padding: 2px 10px;
  font-size: 13px;
  cursor: pointer;
  opacity: 0.55;
  transition: opacity 0.15s, border-color 0.15s;
}
.chat-feedback-btn:hover:not(:disabled) {
  opacity: 1;
}
.chat-feedback-btn.active {
  opacity: 1;
  border-color: #c9a66b;
  background: #f2ece0;
}
.chat-feedback-btn:disabled {
  cursor: default;
}
</style>
