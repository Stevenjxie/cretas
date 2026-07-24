/**
 * Minimal SSE frame parsing for fetch-ReadableStream consumption.
 *
 * Ported from mobile-rest-ai/src/sse.ts (2026-07-24, web-admin 餐饮诊断抽屉
 * 真流式) so both restaurant chat surfaces consume the SAME
 * `/api/smartbi/synthesis/comprehensive-stream` framing with identical
 * parsing semantics. Keep the two files in sync if the backend framing
 * ever changes.
 */
export interface SseEvent {
  event: string;
  data: string;
}

export function splitSseFrames(input: string): { frames: string[]; rest: string } {
  const normalized = input.replace(/\r\n/g, '\n');
  const frames: string[] = [];
  let start = 0;
  let index = normalized.indexOf('\n\n');

  while (index !== -1) {
    const frame = normalized.slice(start, index);
    if (frame.trim()) frames.push(frame);
    start = index + 2;
    index = normalized.indexOf('\n\n', start);
  }

  return {
    frames,
    rest: normalized.slice(start),
  };
}

export function parseSseFrame(frame: string): SseEvent | null {
  let event = 'message';
  const data: string[] = [];

  for (const line of frame.split('\n')) {
    if (!line || line.startsWith(':')) continue;
    const separator = line.indexOf(':');
    const field = separator === -1 ? line : line.slice(0, separator);
    let value = separator === -1 ? '' : line.slice(separator + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    if (field === 'event') {
      event = value || 'message';
    } else if (field === 'data') {
      data.push(value);
    }
  }

  if (!data.length && event === 'message') return null;
  return {
    event,
    data: data.join('\n'),
  };
}
