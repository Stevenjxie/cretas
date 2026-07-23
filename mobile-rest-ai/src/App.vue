<template>
  <main class="app-shell" @click="handleShellClick">
    <section v-if="authStatus === 'error'" class="auth-error">
      <div class="brand-line">白垩纪AI示范餐厅 · 经营助手</div>
      <h1>演示登录暂时不可用</h1>
      <p>{{ authError }}</p>
      <button class="primary-button" type="button" @click="retryLogin">重试</button>
    </section>

    <template v-else>
      <header class="app-header">
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

      <section v-if="messages.length === 0 && !isAsking" class="suggestions" aria-label="推荐提问">
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
          @contextmenu.prevent="openMessageMenu($event, message)"
          @pointercancel="clearMessagePressTimer"
          @pointerdown="startMessagePress($event, message)"
          @pointerleave="clearMessagePressTimer"
          @pointerup="clearMessagePressTimer"
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
                <span>{{ message.status || (message.isStreaming ? '正在分析…' : '已完成分析') }}</span>
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
                v-if="message.isStreaming"
                class="streaming-body"
                aria-live="polite"
              >
                <span>{{ message.content }}</span><span class="stream-cursor" aria-hidden="true" />
              </div>
              <div
                v-else
                class="markdown-body"
                @click="handleMarkdownClick"
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
                <button
                  type="button"
                  aria-label="有帮助"
                  :class="{ 'feedback-active': message.feedbackValue === 1 }"
                  :disabled="message.feedbackPending || message.isStreaming"
                  @click="sendFeedback(message, 1)"
                >
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M7 10v11" />
                    <path d="M15 6.5 14 10h5.2a2 2 0 0 1 2 2.3l-1 6.5A2.6 2.6 0 0 1 17.6 21H7" />
                    <path d="M7 10H4a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1h3" />
                    <path d="M14 10V4.7a2 2 0 0 0-2-2L8 10" />
                  </svg>
                  {{ message.feedbackValue === 1 ? '已反馈' : '有帮助' }}
                </button>
                <button
                  type="button"
                  aria-label="没帮助"
                  :class="{ 'feedback-active': message.feedbackValue === -1 }"
                  :disabled="message.feedbackPending || message.isStreaming"
                  @click="sendFeedback(message, -1)"
                >
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M17 14V3" />
                    <path d="m9 17.5 1-3.5H4.8a2 2 0 0 1-2-2.3l1-6.5A2.6 2.6 0 0 1 6.4 3H17" />
                    <path d="M17 14h3a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1h-3" />
                    <path d="M10 14v5.3a2 2 0 0 0 2 2l4-7.3" />
                  </svg>
                  {{ message.feedbackValue === -1 ? '已记录' : '没帮助' }}
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

        <article v-if="isAsking && !hasStreamingAssistant" class="message-row assistant">
          <div class="assistant-card loading-card">
            <div class="loading-status" aria-live="polite">
              <span>{{ isSlowLoading ? '正在生成中' : '正在分析' }}</span>
              <span class="typing-dots" aria-hidden="true">
                <i />
                <i />
                <i />
              </span>
            </div>
            <div class="skeleton-bubble" aria-hidden="true">
              <div class="skeleton-line wide" />
              <div class="skeleton-line medium" />
              <div class="skeleton-line short" />
            </div>
          </div>
        </article>
      </section>

      <p v-if="threadError" class="thread-error">{{ threadError }}</p>

      <form class="composer" @submit.prevent="sendCurrentInput">
        <div class="composer-box">
          <button class="attach-button" type="button" aria-label="附件">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="m21 11.5-8.9 8.9a6 6 0 0 1-8.5-8.5l9.6-9.6a4 4 0 0 1 5.7 5.7l-9.8 9.8a2 2 0 0 1-2.8-2.8l8.8-8.8" />
            </svg>
          </button>
          <label class="sr-only" for="question-input">输入经营问题</label>
          <textarea
            id="question-input"
            ref="composerInputRef"
            v-model="draft"
            maxlength="500"
            rows="1"
            placeholder="请输入你的问题，例如：哪道菜毛利最高？"
            :disabled="!isReady || isAsking"
            @input="resizeComposerInput"
            @compositionstart="isComposing = true"
            @compositionend="handleCompositionEnd"
            @keydown="handleComposerKeydown"
          />
          <button class="send-button" type="submit" :disabled="!canSend" aria-label="发送">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M22 2 11 13" />
              <path d="m22 2-7 20-4-9-9-4Z" />
            </svg>
            <span class="sr-only">发送</span>
          </button>
        </div>
      </form>

      <div
        v-if="messageMenu"
        class="message-action-menu"
        :style="{ left: `${messageMenu.x}px`, top: `${messageMenu.y}px` }"
        @click.stop
      >
        <button type="button" @click="copyMessageFromMenu">复制</button>
      </div>

      <p v-if="toastMessage" class="toast" role="status">{{ toastMessage }}</p>
    </template>
  </main>
</template>

<script setup lang="ts">
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed, defineComponent, h, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { RequestTimeoutError, askSynthesis, askSynthesisStream, clearToken, demoLogin, sendAnswerFeedback } from './api'
import ChartBlock from './components/ChartBlock.vue'
import type { ChartPayload, ChatMessage, SynthesisResponse } from './types'

type AuthStatus = 'loading' | 'ready' | 'error'
type MessageActionMenu = {
  id: string
  content: string
  x: number
  y: number
}

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
const isSlowLoading = ref(false)
const isComposing = ref(false)
const threadError = ref('')
const showDebug = ref(false)
const feedRef = ref<HTMLElement | null>(null)
const composerInputRef = ref<HTMLTextAreaElement | null>(null)
const sessionId = ref(createSessionId())
let slowLoadingTimer: number | undefined
let currentAbort: AbortController | null = null
let genCounter = 0
const messageMenu = ref<MessageActionMenu | null>(null)
const toastMessage = ref('')
const suppressNextShellClick = ref(false)
let messagePressTimer: number | undefined
let toastTimer: number | undefined

const isReady = computed(() => authStatus.value === 'ready')
const hasStreamingAssistant = computed(() => {
  return messages.value.some((message) => message.role === 'assistant' && message.isStreaming)
})
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

// 👍/👎 反馈 (飞轮断点2, 2026-07-23): 乐观翻转, 失败回滚。此前两个按钮是
// 纯装饰 (无 handler) — 现在真写飞轮捕获表, 给晋升评审加证据。
async function sendFeedback(message: ChatMessage, value: 1 | -1): Promise<void> {
  if (!message.sourceQuery || message.feedbackPending || message.isStreaming) return
  if (message.feedbackValue === value) return
  const prevValue = message.feedbackValue
  message.feedbackPending = true
  message.feedbackValue = value
  const ok = await sendAnswerFeedback(message.sourceQuery, value)
  if (!ok) {
    message.feedbackValue = prevValue
  }
  message.feedbackPending = false
}

function renderMarkdown(markdown: string): string {
  const html = marked.parse(markdown, {
    breaks: true,
    gfm: true,
  }) as string
  return DOMPurify.sanitize(html)
}

function isFeedNearBottom(feed: HTMLElement): boolean {
  return feed.scrollHeight - feed.scrollTop - feed.clientHeight < 120
}

async function scrollToBottom(force = false): Promise<void> {
  await nextTick()
  const feed = feedRef.value
  if (!feed) return
  if (!force && !isFeedNearBottom(feed)) return
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

function clearSlowLoadingTimer(): void {
  if (slowLoadingTimer === undefined) return
  window.clearTimeout(slowLoadingTimer)
  slowLoadingTimer = undefined
}

function clearMessagePressTimer(): void {
  if (messagePressTimer === undefined) return
  window.clearTimeout(messagePressTimer)
  messagePressTimer = undefined
}

function clearToastTimer(): void {
  if (toastTimer === undefined) return
  window.clearTimeout(toastTimer)
  toastTimer = undefined
}

function showToast(message: string): void {
  clearToastTimer()
  toastMessage.value = message
  toastTimer = window.setTimeout(() => {
    toastMessage.value = ''
    toastTimer = undefined
  }, 3000)
}

function isMessageActionTarget(target: EventTarget | null): boolean {
  return target instanceof Element
    && !target.closest('a, button, textarea, input, pre, code')
}

function openMessageMenu(event: MouseEvent | PointerEvent, message: ChatMessage): void {
  if (!isMessageActionTarget(event.target)) return
  clearMessagePressTimer()
  suppressNextShellClick.value = true
  messageMenu.value = {
    id: message.id,
    content: message.content,
    x: Math.min(event.clientX, window.innerWidth - 92),
    y: Math.max(56, event.clientY - 48),
  }
  if ('vibrate' in navigator) navigator.vibrate(8)
}

function handleShellClick(): void {
  if (suppressNextShellClick.value) {
    suppressNextShellClick.value = false
    return
  }
  messageMenu.value = null
}

function startMessagePress(event: PointerEvent, message: ChatMessage): void {
  if (!isMessageActionTarget(event.target)) return
  if (event.pointerType === 'mouse' && event.button !== 0) return
  clearMessagePressTimer()
  messagePressTimer = window.setTimeout(() => {
    openMessageMenu(event, message)
  }, 450)
}

async function copyText(text: string, successMessage: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    showToast(successMessage)
  } catch {
    showToast('复制失败')
  }
}

function copyMessageFromMenu(): void {
  const activeMenu = messageMenu.value
  if (!activeMenu) return
  messageMenu.value = null
  void copyText(activeMenu.content, '已复制')
}

function handleMarkdownClick(event: MouseEvent): void {
  const target = event.target
  if (!(target instanceof Element)) return
  const codeBlock = target.closest('pre')
  if (!codeBlock?.textContent) return
  void copyText(codeBlock.textContent, '代码已复制')
}

function startNewConversation(): void {
  genCounter += 1
  currentAbort?.abort()
  currentAbort = null
  clearSlowLoadingTimer()
  clearMessagePressTimer()
  sessionId.value = createSessionId()
  messages.value = []
  draft.value = ''
  threadError.value = ''
  isAsking.value = false
  isSlowLoading.value = false
  messageMenu.value = null
  void nextTick(resizeComposerInput)
}

function resizeComposerInput(): void {
  const input = composerInputRef.value
  if (!input) return
  input.style.height = 'auto'
  input.style.height = `${Math.min(input.scrollHeight, 120)}px`
}

function handleCompositionEnd(): void {
  isComposing.value = false
  resizeComposerInput()
}

function shouldSendOnEnter(event: KeyboardEvent): boolean {
  if (isComposing.value || event.isComposing) return false
  if (event.key !== 'Enter' || event.shiftKey) return false
  if (event.metaKey || event.ctrlKey) return true
  return window.matchMedia('(pointer: coarse)').matches
}

function handleComposerKeydown(event: KeyboardEvent): void {
  if (!shouldSendOnEnter(event)) return
  event.preventDefault()
  sendCurrentInput()
}

async function sendQuestion(question: string): Promise<void> {
  const normalized = question.trim().slice(0, 500)
  if (!normalized || !isReady.value || isAsking.value) return

  const myGen = ++genCounter
  const controller = new AbortController()
  currentAbort?.abort()
  currentAbort = controller

  threadError.value = ''
  messages.value.push(createMessage('user', normalized))
  const assistantMessage = createMessage('assistant', '')
  assistantMessage.sourceQuery = normalized
  assistantMessage.isStreaming = true
  assistantMessage.status = '正在连接…'
  messages.value.push(assistantMessage)
  draft.value = ''
  void nextTick(resizeComposerInput)
  isAsking.value = true
  isSlowLoading.value = false
  clearSlowLoadingTimer()
  slowLoadingTimer = window.setTimeout(() => {
    if (isAsking.value) isSlowLoading.value = true
  }, 3000)
  await scrollToBottom(true)

  let sawStreamPayload = false
  let streamErrorMessage = ''
  const stillCurrent = () => myGen === genCounter && currentAbort === controller
  const finishAssistant = (payload: SynthesisResponse): void => {
    if (!stillCurrent()) return
    assistantMessage.content = payload.answer
    assistantMessage.charts = payload.charts
    assistantMessage.source = payload.source
    assistantMessage.tokens = payload.tokens
    assistantMessage.isStreaming = false
    assistantMessage.status = '已完成分析'
  }

  try {
    await askSynthesisStream(normalized, sessionId.value, {
      onStatus(text) {
        if (!stillCurrent()) return
        sawStreamPayload = true
        assistantMessage.status = text || '正在分析…'
      },
      onChunk(text) {
        if (!stillCurrent()) return
        sawStreamPayload = true
        assistantMessage.content += text
        assistantMessage.status = '正在生成中'
        void scrollToBottom()
      },
      onCharts(charts: ChartPayload[]) {
        if (!stillCurrent()) return
        sawStreamPayload = true
        assistantMessage.charts = charts
      },
      onDone(payload) {
        sawStreamPayload = true
        finishAssistant(payload)
      },
      onError(message) {
        streamErrorMessage = message
        if (!stillCurrent() || !sawStreamPayload) return
        threadError.value = message
        assistantMessage.content = `**请求失败**\n\n${message}`
        assistantMessage.isStreaming = false
        assistantMessage.status = '分析失败'
      },
    }, controller.signal)
  } catch (error) {
    if (myGen !== genCounter || controller.signal.aborted) return
    if (!(error instanceof RequestTimeoutError) && !sawStreamPayload) {
      try {
        assistantMessage.status = '正在切换普通分析…'
        assistantMessage.content = ''
        assistantMessage.isStreaming = true
        const response = await askSynthesis(normalized, sessionId.value, controller.signal)
        finishAssistant(response)
        return
      } catch (fallbackError) {
        if (myGen !== genCounter || controller.signal.aborted) return
        const message = fallbackError instanceof RequestTimeoutError
          ? '分析超时，请重试'
          : fallbackError instanceof Error ? fallbackError.message : streamErrorMessage || '网络失败，请稍后重试。'
        threadError.value = message
        assistantMessage.content = `**请求失败**\n\n${message}`
        assistantMessage.isStreaming = false
        assistantMessage.status = '分析失败'
        return
      }
    }
    const message = error instanceof RequestTimeoutError
      ? '分析超时，请重试'
      : error instanceof Error ? error.message : streamErrorMessage || '网络失败，请稍后重试。'
    threadError.value = message
    assistantMessage.content = `**请求失败**\n\n${message}`
    assistantMessage.isStreaming = false
    assistantMessage.status = '分析失败'
  } finally {
    if (currentAbort === controller) {
      currentAbort = null
    }
    if (myGen === genCounter) {
      clearSlowLoadingTimer()
      isAsking.value = false
      isSlowLoading.value = false
      await scrollToBottom()
    }
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

onUnmounted(() => {
  genCounter += 1
  currentAbort?.abort()
  currentAbort = null
  clearSlowLoadingTimer()
  clearMessagePressTimer()
  clearToastTimer()
})
</script>
