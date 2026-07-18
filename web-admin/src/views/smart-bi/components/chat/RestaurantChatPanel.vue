<script setup lang="ts">
/**
 * P2 web-admin/mobile synthesis parity (2026-07-11):
 *
 * This panel now calls the Python comprehensive-synthesis engine directly
 * (askRestaurantSynthesis → POST /api/smartbi/synthesis/comprehensive) —
 * the SAME endpoint mobile-rest-ai/ uses — instead of the Java intent
 * executor (askRestaurantQuestion). That path never reached the synthesis
 * engine, so 成本率/供应商价格异常(反回扣)/同比环比/加权毛利率 questions
 * returned empty data on web-admin (verified) even though mobile answered
 * them correctly. The Java-intent path (askRestaurantQuestion, owner-action
 * scenario chaining, sections/followUpChips) is untouched in
 * `@/api/smartbi/restaurant-chat` and still covered by its own tests — this
 * panel just no longer calls it as the primary Q&A path.
 *
 * Tenant scope is derived by the backend from the JWT (request.state.factory_id
 * via auth_middleware) — `props.factoryId` is not sent explicitly.
 */
import { ref, nextTick } from 'vue';
import { ElInput, ElButton, ElMessage } from 'element-plus';
import ChatBubble from './ChatBubble.vue';
import ChatTypingIndicator from './ChatTypingIndicator.vue';
import GrossMarginDeclineRun from './GrossMarginDeclineRun.vue';
import { askRestaurantSynthesis } from '@/api/smartbi/restaurant-synthesis';
import type { ChatTurn } from '@/types/restaurant-chat';

// This panel answers over the store's whole real 经营 dataset (backend derives
// the tenant from the JWT). It intentionally does NOT read the dashboard's
// selected upload-Excel — that upload-driven Q&A is a separate surface. The
// former subSector/uploadId props were passed but ignored, so they are dropped
// here (and unbound in the parent) to avoid misleading callers. factoryId is
// kept for potential display use; it is not sent to the API.
defineProps<{
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
  try {
    const response = await askRestaurantSynthesis(query, sessionId.value);

    if (!response.success) {
      // Honest failure: show the real backend/network message, never a
      // silently-degraded fake answer (禁止降级处理 / api-response-handling).
      const errMsg = response.error || '分析失败，请稍后重试';
      turns.value.push({
        id: crypto.randomUUID(),
        role: 'ai',
        content: `**请求失败**\n\n${errMsg}`,
        timestamp: Date.now(),
        error: errMsg,
      });
      ElMessage.error('聊天请求失败: ' + errMsg);
    } else {
      turns.value.push({
        id: crypto.randomUUID(),
        role: 'ai',
        content: response.answer || '已完成分析',
        timestamp: Date.now(),
        charts: response.charts,
        alerts: response.alerts,
        source: response.source,
      });
    }
  } catch (error: unknown) {
    // askRestaurantSynthesis catches internally and returns {success:false};
    // this is belt-and-suspenders for anything unexpected in rendering.
    const errMsg = error instanceof Error ? error.message : String(error);
    turns.value.push({
      id: crypto.randomUUID(),
      role: 'ai',
      content: `**请求失败**\n\n${errMsg}`,
      timestamp: Date.now(),
      error: errMsg,
    });
    ElMessage.error('聊天请求失败: ' + errMsg);
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

async function clearConversation() {
  sessionId.value = crypto.randomUUID();
  turns.value = [];
  ElMessage.success('对话已清空');
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

      <ChatBubble v-for="turn in turns" :key="turn.id" :turn="turn" />

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
</style>
