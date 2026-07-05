<script setup lang="ts">
import { ref, nextTick } from 'vue';
import { ElInput, ElButton, ElMessage } from 'element-plus';
import ChatBubble from './ChatBubble.vue';
import ChatTypingIndicator from './ChatTypingIndicator.vue';
import SectionCardRenderer from './SectionCardRenderer.vue';
import { askRestaurantQuestion } from '@/api/smartbi/restaurant-chat';
import type { ChatTurn } from '@/types/restaurant-chat';
import { useAuthStore } from '@/store/modules/auth';

const props = defineProps<{
  factoryId: string;
  subSector?: string;
  uploadId?: string;
}>();

const auth = useAuthStore();
const turns = ref<ChatTurn[]>([]);
const isTyping = ref(false);
const inputText = ref('');
const chatContainer = ref<HTMLElement | null>(null);
const sessionId = ref<string | null>(null);
const ownerActionScenario = ref<string | null>(null);

/**
 * Get current user ID.
 * auth.user has top-level `id` field (set from data.userId on login).
 * Falls back through username → 'anon' for unauthenticated preview mode.
 */
function getUserId(): string {
  const u = auth.user;
  if (!u) return 'anon';
  // User interface: { id, username, factoryUser: { factoryId, role, ... }, ... }
  return String(u.id ?? u.username ?? 'anon');
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
  try {
    const response = await askRestaurantQuestion({
      query,
      factoryId: props.factoryId,
      userId: getUserId(),
      subSector: props.subSector,
      uploadId: props.uploadId,
      sessionId: sessionId.value ?? undefined,
      ownerActionScenario: ownerActionScenario.value ?? undefined,
    });
    sessionId.value = response.sessionId ?? sessionId.value;
    ownerActionScenario.value = response.ownerActionScenario ?? ownerActionScenario.value;

    const aiTurn: ChatTurn = {
      id: crypto.randomUUID(),
      role: 'ai',
      content: response.message ?? '已完成分析',
      timestamp: Date.now(),
      intentCode: response.intentCode,
      toolName: response.toolName,
      skillName: response.skillName,
      sections: response.sections ?? [],
      followUpChips: response.followUpChips ?? [],
    };
    turns.value.push(aiTurn);
  } catch (error: unknown) {
    const errMsg = error instanceof Error ? error.message : String(error);
    turns.value.push({
      id: crypto.randomUUID(),
      role: 'ai',
      content: '抱歉, 查询失败',
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
  sessionId.value = null;
  ownerActionScenario.value = null;
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

    <div ref="chatContainer" class="chat-body">
      <div v-if="turns.length === 0" class="chat-empty">
        <div class="chat-empty-icon">&#9660;</div>
        <div class="chat-empty-text">
          问问我 — 例如: "帮我分析成本刚性" / "哪些菜该砍" / "17 家店哪家最差"
        </div>
      </div>

      <ChatBubble v-for="turn in turns" :key="turn.id" :turn="turn">
        <template #sections>
          <SectionCardRenderer
            v-for="(section, idx) in turn.sections ?? []"
            :key="idx"
            :section="section"
          />
        </template>
        <template #followups>
          <div
            v-if="turn.followUpChips && turn.followUpChips.length"
            class="followup-chips"
          >
            <button
              v-for="chip in turn.followUpChips"
              :key="chip"
              class="followup-chip"
              @click="sendMessage(chip)"
            >
              {{ chip }}
            </button>
          </div>
        </template>
      </ChatBubble>

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
.followup-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dotted #d4cdb8;
}
.followup-chip {
  padding: 6px 12px;
  border: 1px solid #d4cdb8;
  border-radius: 16px;
  background: #fefcf6;
  color: #3d3d3d;
  font-family: 'Noto Serif SC', serif;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.followup-chip:hover {
  border-color: #2d4a3e;
  color: #2d4a3e;
}
.chat-input {
  padding: 14px 20px;
  border-top: 1px solid #d4cdb8;
  display: flex;
  gap: 10px;
}
.sections-placeholder {
  padding: 10px;
  margin-top: 12px;
  background: #f2ece0;
  border: 1px dashed #a8a29e;
  font-family: monospace;
  font-size: 11px;
  color: #6b6b6b;
}
</style>
