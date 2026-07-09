<template>
  <main class="app-shell">
    <section v-if="authStatus === 'error'" class="auth-error">
      <div class="brand-line">白垩纪AI示范餐厅 · 经营助手</div>
      <h1>演示登录暂时不可用</h1>
      <p>{{ authError }}</p>
      <button class="primary-button" type="button" @click="retryLogin">重试</button>
    </section>

    <template v-else>
      <header class="app-header">
        <div>
          <h1>白垩纪AI示范餐厅 · 经营助手</h1>
          <p v-if="authStatus === 'loading'">正在进入演示餐厅…</p>
          <p v-else>手机端经营问答 · 支持连续追问</p>
        </div>
        <div class="header-actions">
          <button class="icon-button" type="button" @click="showDebug = !showDebug">
            调试
          </button>
          <button class="icon-button" type="button" @click="startNewConversation">
            新对话
          </button>
        </div>
      </header>

      <section class="suggestions" aria-label="推荐提问">
        <button
          v-for="question in suggestedQuestions"
          :key="question"
          class="suggestion-chip"
          type="button"
          :disabled="!isReady || isAsking"
          @click="sendQuestion(question)"
        >
          {{ question }}
        </button>
      </section>

      <section ref="feedRef" class="message-feed" aria-live="polite">
        <div v-if="messages.length === 0 && !isAsking" class="empty-state">
          <h2>问一句经营问题，马上看真实合成分析。</h2>
          <p>比如利润率、分店拖累、天气影响、VIP 满意度或最近差评。</p>
        </div>

        <article
          v-for="message in messages"
          :key="message.id"
          class="message-row"
          :class="message.role"
        >
          <div class="message-bubble">
            <div
              v-if="message.role === 'assistant'"
              class="markdown-body"
              v-html="renderMarkdown(message.content)"
            />
            <p v-else>{{ message.content }}</p>

            <div v-if="message.charts?.length" class="chart-stack">
              <ChartBlock
                v-for="(chart, index) in message.charts"
                :key="`${message.id}-${index}`"
                :chart="chart"
              />
            </div>

            <details v-if="showDebug && message.role === 'assistant'" class="debug-line" open>
              <summary>source</summary>
              <span>{{ message.source || 'unknown' }}</span>
              <span v-if="typeof message.tokens === 'number'"> · tokens {{ message.tokens }}</span>
              <span> · session {{ sessionId }}</span>
            </details>
          </div>
        </article>

        <article v-if="isAsking" class="message-row assistant">
          <div class="message-bubble loading-bubble">
            <span class="spinner" aria-hidden="true" />
            <span>正在分析…</span>
            <div class="skeleton-line wide" />
            <div class="skeleton-line" />
          </div>
        </article>
      </section>

      <p v-if="threadError" class="thread-error">{{ threadError }}</p>

      <form class="composer" @submit.prevent="sendCurrentInput">
        <label class="sr-only" for="question-input">输入经营问题</label>
        <textarea
          id="question-input"
          v-model="draft"
          maxlength="500"
          rows="1"
          placeholder="问问餐厅经营情况…"
          :disabled="!isReady || isAsking"
          @keydown.enter.exact.prevent="sendCurrentInput"
        />
        <button class="send-button" type="submit" :disabled="!canSend">
          发送
        </button>
      </form>
    </template>
  </main>
</template>

<script setup lang="ts">
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { askSynthesis, clearToken, demoLogin } from './api'
import ChartBlock from './components/ChartBlock.vue'
import type { ChatMessage } from './types'

type AuthStatus = 'loading' | 'ready' | 'error'

const suggestedQuestions = [
  '这两个月赚钱没，利润率多少',
  '哪家分店最拖后腿',
  '下雨天生意会差吗',
  'VIP 客人满意度怎么样',
  '最近差评多不多',
]

const authStatus = ref<AuthStatus>('loading')
const authError = ref('')
const draft = ref('')
const messages = ref<ChatMessage[]>([])
const isAsking = ref(false)
const threadError = ref('')
const showDebug = ref(false)
const feedRef = ref<HTMLElement | null>(null)
const sessionId = ref(createSessionId())

const isReady = computed(() => authStatus.value === 'ready')
const canSend = computed(() => {
  return isReady.value && !isAsking.value && draft.value.trim().length > 0
})

function createSessionId(): string {
  return crypto.randomUUID()
}

function createMessage(role: ChatMessage['role'], content: string): ChatMessage {
  return {
    id: crypto.randomUUID(),
    role,
    content,
    createdAt: Date.now(),
  }
}

function renderMarkdown(markdown: string): string {
  const html = marked.parse(markdown, {
    breaks: true,
    gfm: true,
  }) as string
  return DOMPurify.sanitize(html)
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const feed = feedRef.value
  if (!feed) return
  feed.scrollTo({
    top: feed.scrollHeight,
    behavior: 'smooth',
  })
}

async function login(): Promise<void> {
  authStatus.value = 'loading'
  authError.value = ''
  try {
    await demoLogin()
    authStatus.value = 'ready'
  } catch (error) {
    clearToken()
    authStatus.value = 'error'
    authError.value = error instanceof Error ? error.message : '演示登录失败，请稍后重试。'
  }
}

function retryLogin(): void {
  void login()
}

function startNewConversation(): void {
  sessionId.value = createSessionId()
  messages.value = []
  draft.value = ''
  threadError.value = ''
}

async function sendQuestion(question: string): Promise<void> {
  const normalized = question.trim().slice(0, 500)
  if (!normalized || !isReady.value || isAsking.value) return

  threadError.value = ''
  messages.value.push(createMessage('user', normalized))
  draft.value = ''
  isAsking.value = true
  await scrollToBottom()

  try {
    const response = await askSynthesis(normalized, sessionId.value)
    messages.value.push({
      ...createMessage('assistant', response.answer),
      charts: response.charts,
      source: response.source,
      tokens: response.tokens,
    })
  } catch (error) {
    const message = error instanceof Error ? error.message : '网络失败，请稍后重试。'
    threadError.value = message
    messages.value.push(createMessage('assistant', `**请求失败**\n\n${message}`))
  } finally {
    isAsking.value = false
    await scrollToBottom()
  }
}

function sendCurrentInput(): void {
  if (!canSend.value) return
  void sendQuestion(draft.value)
}

watch(
  () => messages.value.length,
  () => {
    void scrollToBottom()
  },
)

onMounted(() => {
  void login()
})
</script>
