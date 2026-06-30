<script setup lang="ts">
import { computed } from 'vue';

interface ProviderInfo {
  provider: string;
  bestUse?: string;
  accessMode?: string;
  availableFields?: string[];
}

interface Recommendation {
  priority?: string;
  action: string;
  why?: string;
  metricTrigger?: string;
  expectedImpact?: string;
}

interface AdvancedTrafficPersonaData {
  moduleName?: string;
  requiresEnablement?: boolean;
  demoMode?: boolean;
  dataNote?: string;
  enablement?: {
    status?: string;
    scope?: string;
  };
  providers?: ProviderInfo[];
  simulatedMetrics?: Record<string, number>;
  analysis?: {
    headline?: string;
    recommendations?: Recommendation[];
    risks?: string[];
    opportunities?: string[];
  };
}

const props = defineProps<{ data: AdvancedTrafficPersonaData | Record<string, unknown> }>();

const payload = computed(() => props.data as AdvancedTrafficPersonaData);
const providers = computed(() => payload.value.providers ?? []);
const metrics = computed(() => payload.value.simulatedMetrics ?? {});
const recommendations = computed(() => payload.value.analysis?.recommendations ?? []);
const statusLabel = computed(() => payload.value.enablement?.status ?? '需额外开通');

function fmtNumber(value: unknown): string {
  if (typeof value !== 'number' || Number.isNaN(value)) return '-';
  return value.toLocaleString('zh-CN');
}

function fmtPercent(value: unknown): string {
  if (typeof value !== 'number' || Number.isNaN(value)) return '-';
  return `${(value * 100).toFixed(1)}%`;
}
</script>

<template>
  <section class="advanced-traffic-card">
    <div class="traffic-header">
      <div>
        <div class="eyebrow">Premium Module</div>
        <h4>{{ payload.moduleName ?? '高级客流画像分析' }}</h4>
      </div>
      <span data-test="premium-status" class="status-chip">{{ statusLabel }}</span>
    </div>

    <p v-if="payload.dataNote" class="data-note">{{ payload.dataNote }}</p>

    <div class="metric-grid">
      <div class="metric-tile">
        <span class="metric-label">日均路过客流</span>
        <strong>{{ fmtNumber(metrics.dailyFootfall) }}</strong>
      </div>
      <div class="metric-tile">
        <span class="metric-label">估算到店机会</span>
        <strong>{{ fmtNumber(metrics.estimatedStoreVisits) }}</strong>
      </div>
      <div class="metric-tile">
        <span class="metric-label">周末提升</span>
        <strong>{{ fmtPercent((metrics.weekdayWeekendLift ?? 1) - 1) }}</strong>
      </div>
      <div class="metric-tile">
        <span class="metric-label">消费力指数</span>
        <strong>{{ fmtNumber(metrics.consumptionPowerIndex) }}</strong>
      </div>
      <div class="metric-tile">
        <span class="metric-label">竞品重叠</span>
        <strong>{{ fmtPercent(metrics.competitorOverlapIndex) }}</strong>
      </div>
    </div>

    <div v-if="payload.analysis?.headline" class="headline">
      {{ payload.analysis.headline }}
    </div>

    <div class="provider-grid">
      <div
        v-for="provider in providers"
        :key="provider.provider"
        data-test="provider"
        class="provider-cell"
      >
        <div class="provider-name">{{ provider.provider }}</div>
        <div class="provider-use">{{ provider.bestUse }}</div>
      </div>
    </div>

    <div v-if="recommendations.length" class="recommendations">
      <div class="section-label">建议动作</div>
      <div
        v-for="(item, index) in recommendations.slice(0, 5)"
        :key="`${item.priority}-${index}`"
        class="recommendation-row"
      >
        <span class="priority">{{ item.priority ?? 'P1' }}</span>
        <div>
          <strong>{{ item.action }}</strong>
          <p v-if="item.why">{{ item.why }}</p>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.advanced-traffic-card {
  padding: 14px;
  border: 1px solid #d9e2ec;
  border-radius: 6px;
  background: #fbfdff;
}

.traffic-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.eyebrow {
  font-size: 11px;
  color: #64748b;
  text-transform: uppercase;
}

h4 {
  margin: 2px 0 0;
  color: #1f2937;
  font-size: 16px;
}

.status-chip {
  flex-shrink: 0;
  padding: 4px 8px;
  border-radius: 4px;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  color: #c2410c;
  font-size: 12px;
  font-weight: 600;
}

.data-note {
  margin: 0 0 12px;
  padding: 8px 10px;
  border-left: 3px solid #f59e0b;
  background: #fffbeb;
  color: #78350f;
  font-size: 12px;
  line-height: 1.6;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(116px, 1fr));
  gap: 8px;
}

.metric-tile {
  min-height: 68px;
  padding: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #ffffff;
}

.metric-label {
  display: block;
  margin-bottom: 6px;
  color: #64748b;
  font-size: 12px;
}

.metric-tile strong {
  color: #111827;
  font-size: 18px;
}

.headline {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #eef6ff;
  color: #1e3a8a;
  font-size: 13px;
  line-height: 1.6;
}

.provider-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;
}

.provider-cell {
  padding: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #ffffff;
}

.provider-name {
  margin-bottom: 4px;
  color: #1f2937;
  font-weight: 600;
  font-size: 13px;
}

.provider-use {
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
}

.recommendations {
  margin-top: 12px;
}

.section-label {
  margin-bottom: 8px;
  color: #475569;
  font-size: 12px;
  font-weight: 700;
}

.recommendation-row {
  display: grid;
  grid-template-columns: 42px 1fr;
  gap: 8px;
  padding: 10px 0;
  border-top: 1px solid #e5e7eb;
}

.priority {
  align-self: start;
  width: 34px;
  padding: 3px 0;
  border-radius: 4px;
  background: #e0f2fe;
  color: #0369a1;
  text-align: center;
  font-size: 11px;
  font-weight: 700;
}

.recommendation-row strong {
  display: block;
  color: #111827;
  font-size: 13px;
  line-height: 1.5;
}

.recommendation-row p {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
}

@media (max-width: 900px) {
  .metric-grid,
  .provider-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .traffic-header {
    flex-direction: column;
  }

  .metric-grid,
  .provider-grid {
    grid-template-columns: 1fr;
  }
}
</style>
