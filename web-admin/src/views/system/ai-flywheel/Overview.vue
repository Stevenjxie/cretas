<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import FlywheelHeader from './components/FlywheelHeader.vue';
import { useFlywheelDomain } from './composables/useFlywheelDomain';
import { flywheelApi, type FlywheelOverview } from '@/api/smartbi/ai-flywheel';

const { domain } = useFlywheelDomain();
const loading = ref(false);
const data = ref<FlywheelOverview | null>(null);

const WINDOWS: Array<{ key: 'today' | 'd7' | 'd30'; label: string }> = [
  { key: 'today', label: '今日' },
  { key: 'd7', label: '近 7 日' },
  { key: 'd30', label: '近 30 日' },
];

async function load() {
  loading.value = true;
  try {
    data.value = await flywheelApi.overview(domain.value, 30);
  } catch (e) {
    ElMessage.error('加载总览看板失败: ' + (e instanceof Error ? e.message : String(e)));
  } finally {
    loading.value = false;
  }
}

function fmtPct(n?: number): string {
  if (n === undefined || n === null) return '-';
  return (n * 100).toFixed(1) + '%';
}
function fmtNum(n?: number): string {
  if (n === undefined || n === null) return '-';
  return n.toLocaleString();
}
function fmtTok(n?: number): string {
  if (n === undefined || n === null) return '-';
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return String(n);
}
function feedbackRatio(up?: number, down?: number): string {
  const u = up || 0;
  const d = down || 0;
  if (u + d === 0) return '-';
  return `${((u / (u + d)) * 100).toFixed(0)}% 👍`;
}
function tierTagType(tier: string): 'success' | 'warning' | 'danger' {
  if (tier === 'T1') return 'success';
  if (tier === 'T2') return 'warning';
  return 'danger';
}

onMounted(load);
watch(domain, load);
</script>

<template>
  <div class="page-container">
    <FlywheelHeader v-model:domain="domain" />

    <div class="controls-row">
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <span v-if="data" class="generated-at">数据截至 {{ new Date(data.generated_at).toLocaleString('zh-CN') }}</span>
    </div>

    <el-row :gutter="16" v-loading="loading">
      <el-col v-for="w in WINDOWS" :key="w.key" :xs="24" :sm="24" :md="8">
        <el-card class="window-card" shadow="hover">
          <template #header>
            <div class="window-card-header">{{ w.label }}</div>
          </template>
          <template v-if="data">
            <div class="metric-grid">
              <div class="metric-item">
                <div class="metric-label">问答量</div>
                <div class="metric-value">{{ fmtNum(data[w.key].qa_count) }}</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">LLM 调用</div>
                <div class="metric-value">{{ fmtNum(data[w.key].llm_calls) }}</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">缓存命中率</div>
                <div class="metric-value">{{ fmtPct(data[w.key].cache_hit_rate) }}</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">晋升命中率</div>
                <div class="metric-value">{{ fmtPct(data[w.key].promotion_hit_rate) }}</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">Token 估算</div>
                <div class="metric-value">{{ fmtTok(data[w.key].token_estimate) }}</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">契约失败率</div>
                <div class="metric-value" :class="{ warn: data[w.key].contract_fail_rate > 0.03 }">
                  {{ fmtPct(data[w.key].contract_fail_rate) }}
                </div>
              </div>
              <div class="metric-item">
                <div class="metric-label">澄清率</div>
                <div class="metric-value">{{ fmtPct(data[w.key].clarify_rate) }}</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">反馈</div>
                <div class="metric-value">
                  {{ feedbackRatio(data[w.key].thumbs_up, data[w.key].thumbs_down) }}
                  <span class="feedback-sub">({{ data[w.key].thumbs_up }}👍/{{ data[w.key].thumbs_down }}👎)</span>
                </div>
              </div>
            </div>
          </template>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="section-card" v-loading="loading">
      <template #header>
        <div class="section-header">
          <span>LLM 档位分布 (近 30 日)</span>
          <el-tag size="small" type="info">T1 关键词直答 / T2 向量晋升 / T3 LLM 兜底 — 档位越低成本越省</el-tag>
        </div>
      </template>
      <div v-if="data" class="tier-list">
        <div v-for="tier in data.tier_distribution" :key="tier.tier" class="tier-row">
          <el-tag :type="tierTagType(tier.tier)" size="small" class="tier-tag">{{ tier.tier }}</el-tag>
          <span class="tier-label">{{ tier.label }}</span>
          <el-progress :percentage="tier.pct" :stroke-width="14" class="tier-progress" />
          <span class="tier-count">{{ fmtNum(tier.count) }} 次</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.page-container {
  padding: 20px;
}
.controls-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.generated-at {
  color: #909399;
  font-size: 12px;
}
.window-card {
  margin-bottom: 16px;
}
.window-card-header {
  font-weight: 600;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
}
.metric-item {
  min-width: 0;
}
.metric-label {
  color: #909399;
  font-size: 12px;
  margin-bottom: 4px;
}
.metric-value {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}
.metric-value.warn {
  color: #f56c6c;
}
.feedback-sub {
  font-size: 11px;
  font-weight: 400;
  color: #909399;
}
.section-card {
  margin-bottom: 16px;
}
.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.tier-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.tier-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.tier-tag {
  width: 36px;
  text-align: center;
}
.tier-label {
  width: 140px;
  flex-shrink: 0;
  color: #606266;
  font-size: 13px;
}
.tier-progress {
  flex: 1;
  min-width: 100px;
}
.tier-count {
  width: 80px;
  text-align: right;
  color: #606266;
  font-size: 13px;
  flex-shrink: 0;
}

@media (max-width: 768px) {
  .page-container {
    padding: 12px;
  }
  .metric-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }
  .tier-row {
    flex-wrap: wrap;
  }
  .tier-label {
    width: auto;
  }
  .tier-progress {
    width: 100%;
    order: 3;
  }
}
</style>
