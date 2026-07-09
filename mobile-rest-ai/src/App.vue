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
        <div class="status-bar" aria-hidden="true">
          <span>9:41</span>
          <span class="phone-status">
            <span class="signal-bars"><i /><i /><i /></span>
            <span class="wifi-dot" />
            <span class="battery" />
          </span>
        </div>

        <div class="title-bar">
          <button class="ghost-icon left-spacer" type="button" aria-hidden="true" tabindex="-1" />
          <h1>白垩纪AI示范餐厅 · 经营助手</h1>
          <button class="history-button" type="button" title="新对话" @click="startNewConversation">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M3 12a9 9 0 1 0 3-6.7" />
              <path d="M3 4.8v5.4h5.4" />
              <path d="M12 7v5l3.2 2" />
            </svg>
          </button>
        </div>
      </header>

      <section class="suggestions" aria-label="推荐提问">
        <button
          v-for="item in suggestedQuestions"
          :key="item.text"
          class="suggestion-card"
          type="button"
          :disabled="!isReady || isAsking"
          @click="sendQuestion(item.text)"
        >
          <QuestionIcon :name="item.icon" />
          <span>{{ item.text }}</span>
        </button>
      </section>

      <section ref="feedRef" class="message-feed" aria-live="polite">
        <div v-if="messages.length === 0 && !isAsking" class="empty-state">
          <div>
            <h2>问一句经营问题，马上看真实合成分析。</h2>
            <p>比如利润率、分店拖累、天气影响、VIP 满意度或最近差评。</p>
          </div>
        </div>

        <article
          v-for="message in messages"
          :key="message.id"
          class="message-row"
          :class="message.role"
        >
          <template v-if="message.role === 'user'">
            <div class="user-bubble">
              <p>{{ message.content }}</p>
            </div>
          </template>

          <template v-else>
            <div class="assistant-card">
              <div class="analysis-status">
                <span class="status-check">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M20 6 9 17l-5-5" />
                  </svg>
                </span>
                <span>已完成分析</span>
                <button
                  class="collapse-button"
                  type="button"
                  aria-label="收起分析"
                >
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="m6 9 6 6 6-6" />
                  </svg>
                </button>
              </div>

              <div
                class="markdown-body"
                v-html="renderMarkdown(message.content)"
              />

              <div v-if="message.charts?.length" class="chart-stack">
                <ChartBlock
                  v-for="(chart, index) in message.charts"
                  :key="`${message.id}-${index}`"
                  :chart="chart"
                />
              </div>

              <div class="answer-footer">
                <span>数据更新于：2025-06-02 09:30</span>
                <button type="button" aria-label="有帮助">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M7 10v11" />
                    <path d="M15 6.5 14 10h5.2a2 2 0 0 1 2 2.3l-1 6.5A2.6 2.6 0 0 1 17.6 21H7" />
                    <path d="M7 10H4a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1h3" />
                    <path d="M14 10V4.7a2 2 0 0 0-2-2L8 10" />
                  </svg>
                  有帮助
                </button>
                <button type="button" aria-label="没帮助">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M17 14V3" />
                    <path d="m9 17.5 1-3.5H4.8a2 2 0 0 1-2-2.3l1-6.5A2.6 2.6 0 0 1 6.4 3H17" />
                    <path d="M17 14h3a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1h-3" />
                    <path d="M10 14v5.3a2 2 0 0 0 2 2l4-7.3" />
                  </svg>
                  没帮助
                </button>
                <button class="source-toggle" type="button" @click="showDebug = !showDebug">
                  {{ showDebug ? '隐藏 source' : 'source' }}
                </button>
              </div>

              <details v-if="showDebug" class="debug-line" open>
                <summary>source</summary>
                <span>{{ message.source || 'unknown' }}</span>
                <span v-if="typeof message.tokens === 'number'"> · tokens {{ message.tokens }}</span>
                <span> · session {{ sessionId }}</span>
              </details>
            </div>
          </template>
        </article>

        <article v-if="isAsking" class="message-row assistant">
          <div class="assistant-card loading-card">
            <div class="analysis-status">
              <span class="spinner" aria-hidden="true" />
              <span>正在分析…</span>
            </div>
            <div class="skeleton-line wide" />
            <div class="skeleton-line" />
            <div class="skeleton-chart" />
          </div>
        </article>
      </section>

      <p v-if="threadError" class="thread-error">{{ threadError }}</p>

      <form class="composer" @submit.prevent="sendCurrentInput">
        <button class="attach-button" type="button" aria-label="附件">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="m21 11.5-8.9 8.9a6 6 0 0 1-8.5-8.5l9.6-9.6a4 4 0 0 1 5.7 5.7l-9.8 9.8a2 2 0 0 1-2.8-2.8l8.8-8.8" />
          </svg>
        </button>
        <label class="sr-only" for="question-input">输入经营问题</label>
        <textarea
          id="question-input"
          v-model="draft"
          maxlength="500"
          rows="1"
          placeholder="请输入你的问题，例如：哪道菜毛利最高？"
          :disabled="!isReady || isAsking"
          @keydown.enter.exact.prevent="sendCurrentInput"
        />
        <button class="send-button" type="submit" :disabled="!canSend" aria-label="发送">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M22 2 11 13" />
            <path d="m22 2-7 20-4-9-9-4Z" />
          </svg>
          <span>发送</span>
        </button>
      </form>

    </template>
  </main>
</template>

<script setup lang="ts">
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed, defineComponent, h, nextTick, onMounted, ref, watch } from 'vue'
import { askSynthesis, clearToken, demoLogin } from './api'
import ChartBlock from './components/ChartBlock.vue'
import type { ChatMessage } from './types'

type AuthStatus = 'loading' | 'ready' | 'error'

const suggestedQuestions = [
  { text: '这两个月赚钱没，利润率多少', icon: 'trend' },
  { text: '哪家分店最拖后腿', icon: 'store' },
  { text: '下雨天生意会差吗', icon: 'rain' },
  { text: 'VIP 客人满意度怎么样', icon: 'crown' },
  { text: '最近差评多不多', icon: 'review' },
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

const QuestionIcon = defineComponent({
  props: {
    name: { type: String, required: true },
  },
  setup(props) {
    const pathByName: Record<string, string[]> = {
      trend: ['M4 17 10 11l4 4 6-8', 'M15 7h5v5'],
      store: ['M5 10h14l-1.2-4H6.2L5 10Z', 'M6 10v9h12v-9', 'M9 19v-5h6v5'],
      rain: ['M8 13a5 5 0 0 1 9.6-2.2A3.5 3.5 0 1 1 18 18H8a4 4 0 0 1 0-8', 'M8 21l-1 2', 'M13 21l-1 2', 'M18 21l-1 2'],
      crown: ['M4 18h16', 'M6 18 5 8l5 4 4-6 4 6 5-4-1 10', 'M7 22h10'],
      review: ['M5 5h14v11H9l-4 4V5Z', 'M8 9h8', 'M8 13h5'],
    }

    return () => h('svg', { viewBox: '0 0 24 24', 'aria-hidden': 'true' },
      (pathByName[props.name] || pathByName.review).map((d) => h('path', { d })),
    )
  },
})

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
