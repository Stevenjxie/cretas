<script setup lang="ts">
/**
 * ChartInsights — per-chart hybrid bullet-point analysis (2026-07-12).
 *
 * Two tiers, both shown under a chart:
 *   1. Deterministic bullets (props.bullets) — always rendered, 0 LLM, computed
 *      by the page from its own real refs (see analysisBullets.ts builders).
 *   2. On-demand AI 解读 — user clicks 「🤖 AI 解读」 → sends a fixed,
 *      non-free-form chart-interpretation task directly to governed synthesis,
 *      embedding this chart's `dataSummary` (real numbers) so the answer is
 *      grounded in what's on screen. Free-form chat does NOT use this path; it
 *      enters through the unified Java intent orchestrator.
 *
 * Also emits `focus` so the page can point its AnalysisChatPanel at this
 * specific chart (multi-turn 聚焦 conversation) — see member-analysis/index.vue.
 *
 * Page-agnostic: takes plain props, no page-specific imports. Reusable by
 * any analytics page that has computed deterministic bullets + a dataSummary
 * string for its chart.
 */
import { ref } from 'vue';
import { ElButton } from 'element-plus';
import { askRestaurantSynthesis } from '@/api/smartbi/restaurant-synthesis';
import { splitMarkdownBullets } from './analysisBullets';

const props = defineProps<{
  /** Deterministic fact bullets — always shown, may be empty (renders nothing for this tier). */
  bullets: string[];
  /** Stable identifier for this chart (matches an AnalysisChartContext.key). */
  chartKey: string;
  /** Human title, used in the AI 解读 prompt + emitted with focus. */
  chartTitle: string;
  /** Compact real-number summary embedded into the AI 解读 query (grounding trick). */
  dataSummary: string;
}>();

const emit = defineEmits<{ focus: [key: string] }>();

const aiBullets = ref<string[] | null>(null);
const aiLoading = ref(false);
const aiError = ref('');
const hasAskedOnce = ref(false);

async function loadAiInsight() {
  if (aiLoading.value) return;
  aiLoading.value = true;
  aiError.value = '';
  hasAskedOnce.value = true;
  try {
    const query =
      `[${props.chartTitle}数据] ${props.dataSummary}\n\n` +
      `请用不超过3条bullet要点解读这张「${props.chartTitle}」图的数据含义，并给出可执行的营销/运营建议。`;
    // One-off ask — not part of the page's multi-turn conversation, so a
    // fresh sessionId each time (no cross-chart memory bleed).
    const res = await askRestaurantSynthesis(query, crypto.randomUUID());

    if (!res.success) {
      aiError.value = res.error || 'AI 解读失败，请稍后重试';
      aiBullets.value = null;
      return;
    }
    if (res.source === 'degraded') {
      // Honest: backend explicitly degraded (budget/service) — show that
      // plainly, never fabricate an interpretation.
      aiError.value = res.answer || 'AI 分析当前不可用（额度或服务降级）';
      aiBullets.value = null;
      return;
    }
    aiBullets.value = splitMarkdownBullets(res.answer).slice(0, 4);
  } catch (e) {
    aiError.value = e instanceof Error ? e.message : 'AI 解读失败';
    aiBullets.value = null;
  } finally {
    aiLoading.value = false;
  }
}
</script>

<template>
  <div class="ci-wrap">
    <ul v-if="bullets.length" class="ci-bullets">
      <li v-for="(b, i) in bullets" :key="i">{{ b }}</li>
    </ul>

    <div class="ci-actions">
      <el-button size="small" text :loading="aiLoading" @click="loadAiInsight">
        {{ aiBullets ? '🤖 重新解读' : '🤖 AI 解读' }}
      </el-button>
      <el-button size="small" text @click="emit('focus', chartKey)">聚焦到 AI 分析面板</el-button>
    </div>

    <div v-if="aiError" class="ci-ai-error">{{ aiError }}</div>
    <ul v-else-if="aiBullets && aiBullets.length" class="ci-ai-bullets">
      <li v-for="(b, i) in aiBullets" :key="i">{{ b }}</li>
    </ul>
    <div v-else-if="hasAskedOnce && !aiLoading" class="ci-ai-empty">AI 未返回可解析的解读内容</div>
  </div>
</template>

<style scoped>
.ci-wrap {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--el-border-color-lighter, #ebeef5);
}
.ci-bullets,
.ci-ai-bullets {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-regular, #606266);
}
.ci-ai-bullets {
  margin-top: 8px;
  padding: 8px 8px 8px 26px;
  background: linear-gradient(135deg, #fff7f0 0%, #fffaf6 100%);
  border-left: 3px solid #e6a23c;
  border-radius: 0 6px 6px 0;
  color: var(--el-text-color-primary, #303133);
}
.ci-actions {
  display: flex;
  gap: 4px;
  margin-top: 6px;
}
.ci-ai-error {
  margin-top: 8px;
  font-size: 12px;
  color: #f56c6c;
}
.ci-ai-empty {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
}
</style>
