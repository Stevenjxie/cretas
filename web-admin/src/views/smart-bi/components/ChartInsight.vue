<template>
  <!--
    ChartInsight.vue — U2 display component (2026-06-10).

    Props:
      insight:  InsightResult | null  — null → renders nothing (honest-null per spec §5)
      depth:    'concise' | 'detailed'
                concise  = finding line only
                detailed = finding + implication + suggestion (all present)

    Badge:
      "数据驱动" — neutral label per spec (NOT "已蒸馏" which inflates trust)
      Async slot for Tier 2 fill (when Tier 1 returns null or caller wants richer insight)
  -->
  <div v-if="insight" class="chart-insight-container">
    <div class="chart-insight-badge">
      <span class="badge-dot" aria-hidden="true"></span>
      数据驱动
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
      Async slot for Tier 2 fill (future: parent passes Tier 2 insight here).
      When Tier 1 is present this slot is still available for richer overlay.
    -->
    <slot name="tier2" />
  </div>

  <!--
    When insight is null: render the async slot only (allows parent to show
    a Tier 2 loading placeholder or nothing at all).
  -->
  <template v-else>
    <slot name="tier2" />
  </template>
</template>

<script setup lang="ts">
import type { InsightResult } from './chartInsight';

const props = withDefaults(
  defineProps<{
    /** Tier 1 insight from buildChartInsight(); null → renders nothing */
    insight: InsightResult | null;
    /**
     * 'concise' = finding only (default, for inline chart usage)
     * 'detailed' = finding + implication + suggestion
     */
    depth?: 'concise' | 'detailed';
  }>(),
  {
    depth: 'concise',
  },
);

// Expose depth for template — satisfies vue-tsc (props is referenced in template)
void props;
</script>

<style scoped>
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

.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: #409eff;
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
</style>
