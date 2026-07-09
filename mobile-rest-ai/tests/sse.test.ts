import test from 'node:test'
import assert from 'node:assert/strict'

import { parseSseFrame, splitSseFrames } from '../src/sse.ts'

test('splitSseFrames returns complete frames and keeps the trailing partial frame', () => {
  const out = splitSseFrames('event: status\ndata: 正在分析\n\nevent: chunk\ndata: 半截')

  assert.deepEqual(out.frames, ['event: status\ndata: 正在分析'])
  assert.equal(out.rest, 'event: chunk\ndata: 半截')
})

test('parseSseFrame parses event and joins multiple data lines', () => {
  const out = parseSseFrame('event: chunk\ndata: 第一段\ndata: 第二段')

  assert.deepEqual(out, {
    event: 'chunk',
    data: '第一段\n第二段',
  })
})

test('parseSseFrame ignores comments and defaults to message event', () => {
  const out = parseSseFrame(': keepalive\ndata: payload')

  assert.deepEqual(out, {
    event: 'message',
    data: 'payload',
  })
})
