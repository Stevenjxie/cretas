import { ref } from 'vue';
import { sendAiChat } from '@/api/aiChat';
import type { AiEntryConfig, ChatMessage } from '@/components/ai-entry/types';
import type { TableRow } from '@/types/api';
import { buildAiTemporalContext, normalizeAiOrderDates } from '@/utils/aiOrderNormalization';

const FILL_FORM_REGEX = /```json\s*(\{[\s\S]*?"action"\s*:\s*"FILL_FORM"[\s\S]*?\})\s*```/;
const FILL_FORM_INLINE_REGEX = /(\{"action"\s*:\s*"FILL_FORM"[\s\S]*?\})\s*$/;

function extractFillForm(content: string): TableRow | null {
  const match = content.match(FILL_FORM_REGEX) || content.match(FILL_FORM_INLINE_REGEX);
  if (!match) return null;
  try {
    const parsed = JSON.parse(match[1]);
    if (parsed.action === 'FILL_FORM' && parsed.params) {
      return parsed.params;
    }
  } catch {
    // JSON parse failed
  }
  return null;
}

export function useAiChat(config: AiEntryConfig) {
  const messages = ref<ChatMessage[]>([]);
  const loading = ref(false);
  const previewParams = ref<TableRow | null>(null);

  function buildApiMessages(userText: string) {
    const apiMessages: { role: string; content: string }[] = [
      { role: 'system', content: `${config.systemPrompt}\n${buildAiTemporalContext()}` },
    ];
    for (const msg of messages.value) {
      apiMessages.push({ role: msg.role, content: msg.content });
    }
    apiMessages.push({ role: 'user', content: userText });
    return apiMessages;
  }

  async function sendMessage(text: string) {
    const trimmed = text.trim();
    if (!trimmed || loading.value) return;

    messages.value.push({ role: 'user', content: trimmed });
    loading.value = true;

    try {
      const apiMessages = buildApiMessages(trimmed);
      // Remove the last user message from apiMessages since we already added it to messages
      // Actually we built it before pushing — rebuild correctly
      const correctedMessages: { role: string; content: string }[] = [
        { role: 'system', content: `${config.systemPrompt}\n${buildAiTemporalContext()}` },
      ];
      // All messages except the last one (which is the one we just pushed)
      for (let i = 0; i < messages.value.length - 1; i++) {
        correctedMessages.push({ role: messages.value[i].role, content: messages.value[i].content });
      }
      correctedMessages.push({ role: 'user', content: trimmed });

      const res = await sendAiChat(correctedMessages);
      // res.data is GenericChatResponse { content, tokensUsed, model, finishReason }
      const rawData = res.data as unknown;
      const assistantContent = typeof rawData === 'string'
        ? rawData
        : (rawData && typeof rawData === 'object' && 'content' in rawData)
          ? String((rawData as TableRow).content || '处理中...')
          : String(rawData || '处理中...');

      messages.value.push({ role: 'assistant', content: assistantContent });

      // Check for FILL_FORM
      const params = extractFillForm(assistantContent);
      if (params) {
        const userContext = messages.value
          .filter((message) => message.role === 'user')
          .map((message) => message.content)
          .join('\n');
        previewParams.value = normalizeAiOrderDates(config.entityType, params, userContext);
      }
    } catch {
      messages.value.push({ role: 'assistant', content: '抱歉，发生错误。请重试。' });
    } finally {
      loading.value = false;
    }
  }

  function continueEditing() {
    previewParams.value = null;
  }

  function confirmParams(): TableRow {
    const params = previewParams.value || {};
    return { ...params };
  }

  function reset() {
    messages.value = [];
    loading.value = false;
    previewParams.value = null;
  }

  return {
    messages,
    loading,
    previewParams,
    sendMessage,
    continueEditing,
    confirmParams,
    reset,
  };
}
