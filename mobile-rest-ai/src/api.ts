import type { ChartPayload, DemoLoginResponse, SynthesisResponse } from './types'
import { parseSseFrame, splitSseFrames } from './sse'

const TOKEN_STORAGE_KEY = 'cretas_rest_ai_token'

let memoryToken = sessionStorage.getItem(TOKEN_STORAGE_KEY) || ''

export class RequestTimeoutError extends Error {
  constructor() {
    super('分析超时，请重试')
    this.name = 'RequestTimeoutError'
  }
}

export class StreamEventError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'StreamEventError'
  }
}

export interface SynthesisStreamCallbacks {
  onStatus?: (text: string) => void
  onChunk?: (text: string) => void
  onCharts?: (charts: ChartPayload[]) => void
  onDone?: (payload: SynthesisResponse) => void
  onError?: (message: string) => void
}

function signalWithTimeout(userSignal: AbortSignal | undefined, ms: number): {
  signal: AbortSignal
  cleanup: () => void
  didTimeout: () => boolean
} {
  const controller = new AbortController()
  let timedOut = false
  const abortFromUser = () => controller.abort(userSignal?.reason)
  const timer = window.setTimeout(() => {
    timedOut = true
    controller.abort()
  }, ms)

  if (userSignal?.aborted) {
    abortFromUser()
  } else {
    userSignal?.addEventListener('abort', abortFromUser, { once: true })
  }

  return {
    signal: controller.signal,
    cleanup: () => {
      window.clearTimeout(timer)
      userSignal?.removeEventListener('abort', abortFromUser)
    },
    didTimeout: () => timedOut,
  }
}

function signalWithIdleTimeout(userSignal: AbortSignal | undefined, ms: number): {
  signal: AbortSignal
  reset: () => void
  cleanup: () => void
  didTimeout: () => boolean
} {
  const controller = new AbortController()
  let timedOut = false
  let timer: number | undefined
  const abortFromUser = () => controller.abort(userSignal?.reason)
  const reset = () => {
    if (timer !== undefined) window.clearTimeout(timer)
    timer = window.setTimeout(() => {
      timedOut = true
      controller.abort()
    }, ms)
  }

  if (userSignal?.aborted) {
    abortFromUser()
  } else {
    userSignal?.addEventListener('abort', abortFromUser, { once: true })
  }
  reset()

  return {
    signal: controller.signal,
    reset,
    cleanup: () => {
      if (timer !== undefined) window.clearTimeout(timer)
      userSignal?.removeEventListener('abort', abortFromUser)
    },
    didTimeout: () => timedOut,
  }
}

function readErrorMessage(payload: unknown, fallback: string): string {
  if (payload && typeof payload === 'object') {
    const record = payload as Record<string, unknown>
    if (typeof record.message === 'string' && record.message.trim()) {
      return record.message
    }
    if (typeof record.error === 'string' && record.error.trim()) {
      return record.error
    }
  }
  return fallback
}

async function parseJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) return {}

  try {
    return JSON.parse(text)
  } catch {
    return { message: text }
  }
}

function unwrapData(payload: unknown): unknown {
  if (payload && typeof payload === 'object' && 'data' in payload) {
    return (payload as { data: unknown }).data
  }
  return payload
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function hasRenderableOption(option: Record<string, unknown>): boolean {
  return ['series', 'xAxis', 'yAxis', 'dataset', 'radar', 'calendar'].some((key) => key in option)
}

function needsValueAxis(option: Record<string, unknown>): boolean {
  if (!('xAxis' in option) || 'yAxis' in option) return false
  const series = option.series
  if (!Array.isArray(series)) return false
  return series.some((item) => {
    return isRecord(item) && (item.type === 'bar' || item.type === 'line')
  })
}

function normalizeOption(raw: Record<string, unknown>): Record<string, unknown> | null {
  const candidate = isRecord(raw.option)
    ? { ...raw.option }
    : Object.fromEntries(
        Object.entries(raw).filter(([key]) => !['chartType', 'title', 'type'].includes(key)),
      )

  if (needsValueAxis(candidate)) {
    candidate.yAxis = { type: 'value' }
  }

  return hasRenderableOption(candidate) ? candidate : null
}

function normalizeCharts(rawCharts: unknown): ChartPayload[] {
  if (!Array.isArray(rawCharts)) return []

  return rawCharts.flatMap((rawChart) => {
    if (!isRecord(rawChart)) return []
    const option = normalizeOption(rawChart)
    if (!option) return []
    return [{
      type: typeof rawChart.chartType === 'string'
        ? rawChart.chartType
        : typeof rawChart.type === 'string'
          ? rawChart.type
          : undefined,
      title: typeof rawChart.title === 'string' ? rawChart.title : undefined,
      option,
    }]
  })
}

function parseJsonPayload(payload: string): unknown {
  if (!payload) return null
  try {
    return JSON.parse(payload)
  } catch {
    return null
  }
}

function normalizeSynthesisResponse(payload: unknown): SynthesisResponse {
  const data = unwrapData(payload) as Partial<SynthesisResponse>
  return {
    success: data.success,
    answer: typeof data.answer === 'string' ? data.answer : '',
    charts: normalizeCharts(data.charts),
    source: data.source,
    tokens: data.tokens,
    plan: data.plan,
    fact_check: data.fact_check,
    processingTimeMs: data.processingTimeMs,
  }
}

export function getToken(): string {
  return memoryToken
}

export function clearToken(): void {
  memoryToken = ''
  sessionStorage.removeItem(TOKEN_STORAGE_KEY)
}

export async function demoLogin(): Promise<DemoLoginResponse> {
  const response = await fetch('/api/mobile/auth/demo-login?tenant=rest', {
    method: 'POST',
    credentials: 'include',
  })
  const payload = await parseJson(response)

  if (!response.ok) {
    throw new Error(readErrorMessage(payload, '演示登录失败，请稍后重试。'))
  }

  const data = unwrapData(payload) as Partial<DemoLoginResponse> & { accessToken?: string }
  const token = data.token || data.accessToken
  if (!token) {
    throw new Error('演示登录成功但未返回 token。')
  }

  memoryToken = token
  sessionStorage.setItem(TOKEN_STORAGE_KEY, token)
  return { ...data, token }
}

export async function askSynthesis(
  question: string,
  sessionId: string,
  userSignal?: AbortSignal,
): Promise<SynthesisResponse> {
  const token = getToken()
  if (!token) {
    throw new Error('登录已失效，请重试。')
  }

  const requestSignal = signalWithTimeout(userSignal, 20_000)
  let response: Response
  try {
    response = await fetch('/api/smartbi/synthesis/comprehensive', {
      method: 'POST',
      credentials: 'include',
      signal: requestSignal.signal,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        question,
        session_id: sessionId,
      }),
    })
  } catch (error) {
    if (requestSignal.didTimeout()) {
      throw new RequestTimeoutError()
    }
    throw error
  } finally {
    requestSignal.cleanup()
  }
  const payload = await parseJson(response)

  if (!response.ok) {
    throw new Error(readErrorMessage(payload, '分析失败，请稍后重试。'))
  }

  const data = unwrapData(payload) as Partial<SynthesisResponse>
  if (typeof data.answer !== 'string') {
    throw new Error('后端响应缺少 answer 字段。')
  }

  return {
    answer: data.answer,
    charts: normalizeCharts(data.charts),
    source: data.source,
    tokens: data.tokens,
    plan: data.plan,
    fact_check: data.fact_check,
  }
}

export async function askSynthesisStream(
  question: string,
  sessionId: string,
  callbacks: SynthesisStreamCallbacks,
  userSignal?: AbortSignal,
): Promise<void> {
  const token = getToken()
  if (!token) {
    throw new Error('登录已失效，请重试。')
  }

  const requestSignal = signalWithIdleTimeout(userSignal, 20_000)
  let response: Response
  try {
    response = await fetch('/api/smartbi/synthesis/comprehensive-stream', {
      method: 'POST',
      credentials: 'include',
      signal: requestSignal.signal,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        question,
        session_id: sessionId,
      }),
    })
  } catch (error) {
    requestSignal.cleanup()
    if (requestSignal.didTimeout()) {
      throw new RequestTimeoutError()
    }
    throw error
  }

  if (!response.ok) {
    requestSignal.cleanup()
    const payload = await parseJson(response)
    const message = readErrorMessage(payload, '流式分析失败，请重试。')
    callbacks.onError?.(message)
    throw new Error(message)
  }

  const reader = response.body?.getReader()
  if (!reader) {
    requestSignal.cleanup()
    throw new Error('当前浏览器不支持流式响应')
  }

  const decoder = new TextDecoder()
  let buffer = ''
  let sawDone = false

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      requestSignal.reset()
      buffer += decoder.decode(value, { stream: true })
      const split = splitSseFrames(buffer)
      buffer = split.rest

      for (const frame of split.frames) {
        requestSignal.reset()
        const parsed = parseSseFrame(frame)
        if (!parsed) continue

        if (parsed.event === 'status') {
          callbacks.onStatus?.(parsed.data)
        } else if (parsed.event === 'chunk') {
          callbacks.onChunk?.(parsed.data)
        } else if (parsed.event === 'charts') {
          callbacks.onCharts?.(normalizeCharts(parseJsonPayload(parsed.data)))
        } else if (parsed.event === 'done') {
          sawDone = true
          callbacks.onDone?.(normalizeSynthesisResponse(parseJsonPayload(parsed.data)))
        } else if (parsed.event === 'error') {
          const message = parsed.data || '流式分析失败，请重试。'
          callbacks.onError?.(message)
          throw new StreamEventError(message)
        }
      }
    }

    const tail = decoder.decode()
    if (tail) {
      buffer += tail
      const split = splitSseFrames(`${buffer}\n\n`)
      for (const frame of split.frames) {
        const parsed = parseSseFrame(frame)
        if (!parsed) continue
        if (parsed.event === 'status') {
          callbacks.onStatus?.(parsed.data)
        } else if (parsed.event === 'chunk') {
          callbacks.onChunk?.(parsed.data)
        } else if (parsed.event === 'charts') {
          callbacks.onCharts?.(normalizeCharts(parseJsonPayload(parsed.data)))
        } else if (parsed.event === 'done') {
          sawDone = true
          callbacks.onDone?.(normalizeSynthesisResponse(parseJsonPayload(parsed.data)))
        } else if (parsed.event === 'error') {
          const message = parsed.data || '流式分析失败，请重试。'
          callbacks.onError?.(message)
          throw new StreamEventError(message)
        }
      }
    }
    if (!sawDone) {
      throw new Error('流式响应未完整结束，请重试。')
    }
  } catch (error) {
    if (requestSignal.didTimeout()) {
      throw new RequestTimeoutError()
    }
    throw error
  } finally {
    requestSignal.cleanup()
    reader.releaseLock()
  }
}

/**
 * 👍/👎 feedback on an answer (飞轮断点2, 2026-07-23). Backend correlates by
 * (tenant-from-JWT, trim(query)) against the flywheel capture table. Returns
 * false on any failure — callers revert their optimistic UI state.
 */
export async function sendAnswerFeedback(
  query: string,
  value: 1 | -1,
): Promise<boolean> {
  const token = getToken()
  if (!token) return false
  try {
    const response = await fetch('/api/smartbi/restaurant/feedback', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ query, value }),
    })
    return response.ok
  } catch {
    return false
  }
}
