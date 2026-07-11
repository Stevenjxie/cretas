<script setup lang="ts">
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import { ElAlert } from 'element-plus';
import type { ChatTurn } from '@/types/restaurant-chat';
import RestaurantChartBlock from './RestaurantChartBlock.vue';

defineProps<{
  turn: ChatTurn;
}>();

/**
 * Synthesis answers are markdown (mirrors mobile-rest-ai's renderMarkdown /
 * AIQuery.vue's renderMarkdown). Plain Java-intent turns just render as text
 * before this feature — marked+DOMPurify is a superset (plain text passes
 * through unchanged), so this is safe for both turn shapes.
 */
function renderMarkdown(text: string): string {
  if (!text) return '';
  try {
    return DOMPurify.sanitize(marked.parse(text, { breaks: true, gfm: true }) as string);
  } catch {
    return text;
  }
}

/** el-alert `type` from a 反回扣 alert's honest severity level. */
function alertType(level: string): 'error' | 'warning' {
  return level === 'high' ? 'error' : 'warning';
}
</script>

<template>
  <div class="chat-bubble" :class="`bubble-${turn.role}`">
    <div v-if="turn.role === 'ai'" class="bubble-avatar">&#8478;</div>
    <div class="bubble-body">
      <div v-if="turn.role === 'ai' && turn.toolName" class="bubble-label">
        &#9658; {{ turn.toolName }}
      </div>

      <!-- 🔒 反回扣(anti-kickback) alerts — shown ABOVE the answer (fool-proof
           Rule 1/2: visible before the narrative, never buried). -->
      <div v-if="turn.alerts && turn.alerts.length" class="bubble-alerts">
        <el-alert
          v-for="(alert, idx) in turn.alerts"
          :key="idx"
          :type="alertType(alert.level)"
          :title="alert.title"
          :description="alert.detail"
          :closable="false"
          show-icon
          class="bubble-alert-item"
        />
      </div>

      <div
        v-if="turn.role === 'ai'"
        class="bubble-content bubble-content-markdown"
        v-html="renderMarkdown(turn.content)"
      />
      <div v-else class="bubble-content">{{ turn.content }}</div>

      <div v-if="turn.charts && turn.charts.length" class="bubble-charts">
        <RestaurantChartBlock
          v-for="(chart, idx) in turn.charts"
          :key="idx"
          :chart="chart"
        />
      </div>

      <slot name="sections" />
      <slot name="followups" />
      <div v-if="turn.error" class="bubble-error">{{ turn.error }}</div>
    </div>
  </div>
</template>

<style scoped>
.chat-bubble {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}
.bubble-user {
  justify-content: flex-end;
}
.bubble-user .bubble-body {
  background: #2d4a3e;
  color: #faf7f0;
  max-width: 72%;
  padding: 12px 16px;
  border-radius: 14px 14px 4px 14px;
  font-family: 'Noto Serif SC', serif;
}
.bubble-ai {
  justify-content: flex-start;
}
.bubble-ai .bubble-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #fefcf6;
  border: 1px solid #c9a66b;
  color: #a68449;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: 'Playfair Display', serif;
  font-weight: 900;
  font-size: 16px;
  flex-shrink: 0;
}
.bubble-ai .bubble-body {
  flex: 1;
  background: #fefcf6;
  border: 1px solid #d4cdb8;
  border-left: 4px solid #c9a66b;
  border-radius: 4px 14px 14px 14px;
  padding: 16px 20px;
}
.bubble-label {
  font-family: 'Space Mono', monospace;
  font-size: 10px;
  letter-spacing: 1.5px;
  color: #a68449;
  text-transform: uppercase;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e8e1cc;
}
.bubble-content {
  font-family: 'Noto Serif SC', serif;
  font-size: 14px;
  line-height: 1.7;
  color: #3d3d3d;
}
.bubble-content-markdown {
  :deep(p) { margin: 0.4em 0; }
  :deep(h1), :deep(h2), :deep(h3) { margin: 0.6em 0 0.3em; font-weight: 600; }
  :deep(h3) { font-size: 15px; }
  :deep(ul), :deep(ol) { padding-left: 1.5em; margin: 0.3em 0; }
  :deep(li) { margin: 0.15em 0; }
  :deep(strong) { font-weight: 600; color: #2d4a3e; }
  :deep(code) { background: rgba(0, 0, 0, 0.06); padding: 2px 4px; border-radius: 3px; font-size: 13px; }
  :deep(pre) { background: rgba(0, 0, 0, 0.04); padding: 8px 12px; border-radius: 6px; overflow-x: auto; }
  :deep(blockquote) { border-left: 3px solid #c9a66b; padding-left: 12px; margin: 0.4em 0; color: #6b6b6b; }
  :deep(table) { border-collapse: collapse; margin: 0.5em 0; width: 100%; }
  :deep(th), :deep(td) { border: 1px solid #d4cdb8; padding: 4px 8px; font-size: 13px; }
  :deep(th) { background: #f2ece0; }
}
.bubble-alerts {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}
.bubble-alert-item :deep(.el-alert__title) {
  font-family: 'Noto Serif SC', serif;
  font-weight: 600;
}
.bubble-alert-item :deep(.el-alert__description) {
  font-family: 'Noto Serif SC', serif;
  font-size: 12px;
  margin-top: 2px;
}
.bubble-charts {
  display: flex;
  flex-direction: column;
}
.bubble-error {
  color: #8b1a1a;
  font-size: 12px;
  margin-top: 6px;
}
</style>
