<template>
  <!--
    ChartInsight.vue — U2 display component (2026-06-10, updated gold-wire).
    G4 (2026-06-11): 👍/👎 feedback buttons — only for Tier 2 insights that carry input_hash.

    Props:
      insight:    InsightResult | null  — null + !loading → renders nothing (honest-null)
      depth:      'concise' | 'detailed'
                  concise  = finding line only
                  detailed = finding + implication + suggestion (all present)
      loading:    boolean (optional) — when true and no insight yet, shows loading placeholder
      factory-id: string (optional) — required for feedback submission; if absent, buttons hidden

    Badge text:
      source='rules'    → "数据驱动"
      source='template' → "数据驱动·经验模板"
      source='llm'      → "AI生成"
      source='cache'    → "数据驱动·已缓存"
  -->

  <!-- Loading state: no insight yet, Tier 2 in flight -->
  <div v-if="loading && !insight" class="chart-insight-loading">
    <span class="loading-dot" aria-hidden="true"></span>
    AI 洞察生成中…
  </div>

  <!-- Insight present -->
  <div v-else-if="insight" class="chart-insight-container">
    <div class="chart-insight-badge" :class="`badge-source--${insight.source}`">
      <span class="badge-dot" aria-hidden="true"></span>
      {{ badgeText }}
    </div>
    <div class="chart-insight-body">
      <!-- Finding: always shown -->
      <div class="chart-insight-finding">{{ insight.finding }}</div>

      <!-- Detailed mode: implication + suggestion (when present) -->
      <template v-if="depth === 'detailed'">
        <div v-if="insight.implication" class="chart-insight-implication">
          {{ insight.implication }}
        </div>
        <div v-if="insight.suggestion" class="chart-insight-suggestion">
          <span class="suggestion-prefix">建议：</span>{{ insight.suggestion }}
        </div>
      </template>
    </div>

    <!--
      G4: 👍/👎 feedback buttons.
      Only rendered when:
        1. The insight carries an input_hash (Tier 2 corpus row).
        2. factoryId prop is provided.
      Hidden after voting; shows "感谢反馈" confirmation briefly.
    -->
    <div
      v-if="canVote"
      class="chart-insight-feedback"
      role="group"
      aria-label="对此洞察评价"
    >
      <template v-if="voteState === 'idle'">
        <button
          class="feedback-btn feedback-btn--up"
          title="有帮助"
          aria-label="👍 有帮助"
          @click="handleVote('up')"
        >👍</button>
        <button
          class="feedback-btn feedback-btn--down"
          title="无帮助"
          aria-label="👎 无帮助"
          @click="handleVote('down')"
        >👎</button>
      </template>
      <span v-else-if="voteState === 'submitting'" class="feedback-ack">…</span>
      <span v-else-if="voteState === 'done'" class="feedback-ack">感谢反馈</span>
    </div>

    <!--
      Async slot for Tier 2 fill (future: parent passes Tier 2 insight here).
      When Tier 1 is present this slot is still available for richer overlay.
    -->
    <slot name="tier2" />
  </div>

  <!--
    When insight is null and not loading: render nothing (honest-null).
    Async slot still available for parent-managed content.
  -->
  <template v-else>
    <slot name="tier2" />
  </template>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import type { InsightResult } from './chartInsight';
import { submitInsightFeedback } from './chartInsight';

const props = withDefaults(
  defineProps<{
    /** Tier 1/2 insight; null + !loading → renders nothing (honest-null) */
    insight: InsightResult | null;
    /**
     * 'concise' = finding only (default, for inline chart usage)
     * 'detailed' = finding + implication + suggestion
     */
    depth?: 'concise' | 'detailed';
    /**
     * When true and insight is null: show "AI 洞察生成中…" placeholder.
     * Set to true while fetchTier2Insight is in flight.
     */
    loading?: boolean;
    /**
     * G4: JWT-matching factory ID, required for feedback submission.
     * When absent or insight has no input_hash, feedback buttons are hidden.
     */
    factoryId?: string;
  }>(),
  {
    depth: 'concise',
    loading: false,
    factoryId: '',
  },
);

/**
 * Badge text mapped from insight source:
 *   rules    → "数据驱动"
 *   template → "数据驱动·经验模板"
 *   llm      → "AI生成"
 *   cache    → "数据驱动·已缓存"
 */
const badgeText = computed<string>(() => {
  if (!props.insight) return '数据驱动';
  switch (props.insight.source) {
    case 'rules':    return '数据驱动';
    case 'cache':    return '数据驱动·已缓存';
    case 'template': return '数据驱动·经验模板';
    case 'llm':      return 'AI生成';
    default:         return '数据驱动';
  }
});

// G4: feedback state machine — idle → submitting → done
type VoteState = 'idle' | 'submitting' | 'done';
const voteState = ref<VoteState>('idle');

/** Only show feedback buttons when the insight has a corpus key and we have a factoryId */
const canVote = computed<boolean>(
  () => !!(props.insight?.input_hash && props.factoryId),
);

async function handleVote(vote: 'up' | 'down'): Promise<void> {
  if (voteState.value !== 'idle') return;  // prevent double-submit
  if (!props.insight?.input_hash || !props.factoryId) return;

  voteState.value = 'submitting';
  await submitInsightFeedback(props.insight.input_hash, vote, props.factoryId);
  // Always show confirmation even on network failure (honest-null principle —
  // user feedback was noted; backend failure is non-blocking)
  voteState.value = 'done';
}
</script>

<style scoped>
/* Loading placeholder */
.chart-insight-loading {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  margin-top: 4px;
  font-size: 11px;
  color: #909399;
  font-style: italic;
}

.loading-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: #c0c4cc;
  display: inline-block;
  animation: blink 1.2s infinite;
}

@keyframes blink {
  0%, 80%, 100% { opacity: 1; }
  40% { opacity: 0.2; }
}

/* Insight container */
.chart-insight-container {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 10px;
  margin-top: 4px;
  background: linear-gradient(135deg, #f0f7ff 0%, #f6f9ff 100%);
  border-left: 3px solid #409eff;
  border-radius: 0 6px 6px 0;
  font-size: 12px;
  line-height: 1.5;
  color: #4a5568;
}

/* LLM-generated insight uses a warmer accent */
.chart-insight-container:has(.badge-source--llm) {
  background: linear-gradient(135deg, #fff7f0 0%, #fffaf6 100%);
  border-left-color: #e6a23c;
}

/* Template insight uses a teal accent */
.chart-insight-container:has(.badge-source--template) {
  background: linear-gradient(135deg, #f0faf5 0%, #f6fff9 100%);
  border-left-color: #67c23a;
}

.chart-insight-badge {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 600;
  color: #409eff;
  white-space: nowrap;
  padding-top: 1px;
}

.badge-source--llm {
  color: #e6a23c;
}

.badge-source--template {
  color: #67c23a;
}

.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: currentColor;
  display: inline-block;
}

.chart-insight-body {
  flex: 1;
  min-width: 0;
}

.chart-insight-finding {
  color: #2d3748;
  font-weight: 500;
}

.chart-insight-implication {
  margin-top: 3px;
  color: #4a5568;
  font-size: 11px;
}

.chart-insight-suggestion {
  margin-top: 3px;
  color: #718096;
  font-size: 11px;
}

.suggestion-prefix {
  font-weight: 600;
  color: #4a9f6e;
}

/* G4: feedback buttons */
.chart-insight-feedback {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  flex-shrink: 0;
}

.feedback-btn {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 13px;
  line-height: 1;
  padding: 2px 3px;
  border-radius: 4px;
  opacity: 0.55;
  transition: opacity 0.15s, background 0.15s;
}

.feedback-btn:hover {
  opacity: 1;
  background: rgba(0, 0, 0, 0.06);
}

.feedback-ack {
  font-size: 10px;
  color: #909399;
  white-space: nowrap;
}
</style>
