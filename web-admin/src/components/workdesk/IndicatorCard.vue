<script setup lang="ts">
/**
 * Sprint 11 D7 — IndicatorCard.vue (B-end, no emoji per Steve preference HARD)
 *
 * Compact card rendering a single BI indicator result from INDICATOR_QUERY Tool.
 * Calls the AI intent endpoint with explicit intentCode + indicator_code context.
 *
 * Anti-hallucination: renders backend Tool message verbatim, no LLM client-side.
 * If Tool returns NEED_MORE_INFO / FAILED / null → shows "暂无数据" not fake number.
 */
import { ref, onMounted, computed } from 'vue';
import request from '@/api/request';

const props = defineProps<{
  indicatorCode: string;
  displayName: string;
  unit?: string;
  factoryId: string;
}>();

interface IndicatorResultData {
  currentValue?: number | string | null;
  breachLevel?: 'GREEN' | 'WARNING' | 'ALERT' | 'YELLOW' | 'RED' | string;
  trend?: Array<{ periodStart?: string; value?: number }>;
}

interface IndicatorIntentData {
  status?: string;
  message?: string;
  resultData?: IndicatorResultData | { data?: IndicatorResultData };
}

const loading = ref(false);
const errorMessage = ref('');
const currentValue = ref<string>('');
const breachLevel = ref<string>('');
const trendCount = ref<number>(0);
const lastComputedAt = ref<string>('');
const message = ref<string>('');

const tagType = computed(() => {
  switch (breachLevel.value) {
    case 'GREEN': return 'success';
    case 'WARNING':
    case 'YELLOW': return 'warning';
    case 'ALERT':
    case 'RED': return 'danger';
    default: return 'info';
  }
});

const breachLabel = computed(() => {
  switch (breachLevel.value) {
    case 'GREEN': return '正常';
    case 'WARNING':
    case 'YELLOW': return '黄灯';
    case 'ALERT':
    case 'RED': return '红灯';
    default: return breachLevel.value || '未知';
  }
});

const queryIndicator = async () => {
  loading.value = true;
  errorMessage.value = '';
  try {
    const sessionId = `indicator-card-${props.indicatorCode}-${Date.now()}`;
    const resp: { data?: IndicatorIntentData } = await request.post(
      `/${props.factoryId}/ai-intents/execute`,
      {
        userInput: `查询指标 ${props.indicatorCode}`,
        intentCode: 'INDICATOR_QUERY',
        context: { indicator_code: props.indicatorCode },
        sessionId,
        skipSlotFilling: true,
      },
    );
    // request wrapper unwraps { code, data: { ... } } — resp.data IS the inner data
    const data = (resp.data || resp) as IndicatorIntentData;
    if (data.status !== 'SUCCESS') {
      currentValue.value = '暂无数据';
      message.value = data.message || '';
      return;
    }
    const rawResult = data.resultData;
    const result: IndicatorResultData = rawResult && typeof rawResult === 'object' && 'data' in rawResult
      ? (rawResult as { data?: IndicatorResultData }).data ?? {}
      : (rawResult as IndicatorResultData | undefined) ?? {};
    if (result.currentValue !== null && result.currentValue !== undefined) {
      const numVal = typeof result.currentValue === 'number'
        ? result.currentValue
        : parseFloat(String(result.currentValue));
      currentValue.value = Number.isFinite(numVal)
        ? numVal.toFixed(2)
        : String(result.currentValue);
    } else {
      currentValue.value = '暂无数据';
    }
    breachLevel.value = result.breachLevel || '';
    trendCount.value = Array.isArray(result.trend) ? result.trend.length : 0;
    message.value = data.message || '';
    lastComputedAt.value = new Date().toLocaleTimeString('zh-CN');
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    errorMessage.value = `查询失败: ${msg.slice(0, 60)}`;
    currentValue.value = '暂无数据';
  } finally {
    loading.value = false;
  }
};

defineExpose({ queryIndicator });

onMounted(() => {
  queryIndicator();
});
</script>

<template>
  <el-card class="indicator-card" shadow="hover">
    <div class="card-row">
      <div class="card-name">
        <span class="display-name">{{ displayName }}</span>
        <el-tag size="small" type="info" round>{{ indicatorCode }}</el-tag>
      </div>
      <el-button
        link
        type="primary"
        size="small"
        :loading="loading"
        @click="queryIndicator">
        刷新
      </el-button>
    </div>

    <div v-if="loading" class="loading-row">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>查询中...</span>
    </div>

    <div v-else class="value-row">
      <span class="value" :class="{ 'no-data': currentValue === '暂无数据' }">
        {{ currentValue }}
      </span>
      <span v-if="unit && currentValue !== '暂无数据'" class="unit">{{ unit }}</span>
      <el-tag v-if="breachLevel" :type="tagType" size="small">{{ breachLabel }}</el-tag>
    </div>

    <div v-if="!loading && trendCount > 0" class="meta-row">
      <span class="meta-text">含近 {{ trendCount }} 个数据点</span>
      <span v-if="lastComputedAt" class="meta-text">· {{ lastComputedAt }} 刷新</span>
    </div>

    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      :closable="false"
      show-icon
      class="error-row" />
  </el-card>
</template>

<style scoped>
.indicator-card {
  margin-bottom: 0;
}

.card-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.card-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.display-name {
  font-size: 14px;
  color: var(--el-text-color-regular);
}

.value-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
}

.value {
  font-size: 26px;
  font-weight: 700;
  color: var(--el-color-primary);
  line-height: 1;
}

.value.no-data {
  font-size: 14px;
  color: var(--el-text-color-secondary);
  font-weight: 500;
}

.unit {
  font-size: 14px;
  color: var(--el-text-color-regular);
}

.loading-row {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  padding: 8px 0;
}

.meta-row {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-top: 4px;
}

.meta-text {
  white-space: nowrap;
}

.error-row {
  margin-top: 8px;
}
</style>
