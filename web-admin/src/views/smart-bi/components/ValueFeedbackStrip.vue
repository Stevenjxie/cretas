<template>
  <!--
    #56 价值可视化回馈回路 — 本月价值回馈条 (2026-06-04)
    把诊断引擎已算出的省钱/改善金额按月度 + 年化两口径 (D3) 呈现给门店经理/老板,
    解决"门店看不到价值 → 配合度崩塌"死因。
    业态门控: 仅 RESTAURANT 渲染 (父组件 v-if)。
    诚实: null 金额显"暂无数据" (灰), 不填 ¥0; 无快照显"前往上传"空态。
  -->
  <section v-if="show" class="vfs-section">
    <header class="vfs-header">
      <el-icon class="vfs-icon"><TrendCharts /></el-icon>
      <span class="vfs-title">本月价值回馈</span>
      <span class="vfs-subtitle">{{ subtitle }}</span>
      <!-- 期间切换器 (本地切, 不二次请求 — D3) -->
      <el-radio-group v-if="summary" v-model="period" size="small" class="vfs-period">
        <el-radio-button label="month">本月</el-radio-button>
        <el-radio-button label="annual">年化</el-radio-button>
      </el-radio-group>
      <el-button v-if="!loading" size="small" text @click="load">
        <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
      </el-button>
    </header>

    <!-- 加载中 -->
    <el-skeleton v-if="loading" :rows="2" animated />

    <!-- 加载失败 (禁降级假数据) -->
    <div v-else-if="loadError" class="vfs-error">
      加载价值回馈失败: {{ loadError }}
    </div>

    <!-- 空态: 无快照 (诚实 — R5 next action) -->
    <div v-else-if="!summary" class="vfs-empty">
      <span>暂无价值快照 — 上传月度经营数据后系统将自动算出可节省金额。</span>
      <el-button size="small" type="primary" plain @click="goUpload">前往上传</el-button>
    </div>

    <!-- 有数据 -->
    <div v-else class="vfs-body">
      <!-- 顶部合计大数字 -->
      <div class="vfs-total" :style="{ borderColor: KIND_COLORS[totalKindVal] }">
        <div class="vfs-total-label">
          {{ period === 'month' ? '本月可优化/节省空间' : '年化可优化/节省空间' }}
          <el-tag size="small" :color="KIND_COLORS[totalKindVal]" effect="dark" class="vfs-kind-tag">
            {{ KIND_LABELS[totalKindVal] }}
          </el-tag>
        </div>
        <div class="vfs-total-amount" :class="{ 'vfs-amount-none': totalVal == null }">
          {{ formatValueAmount(totalVal) }}
        </div>
        <div class="vfs-meta">
          含 {{ summary.criticalCount }} 项严重指标 · {{ summary.rxActionCount }} 条改善处方
        </div>
      </div>

      <!-- 分项 chips -->
      <div class="vfs-chips">
        <div
          v-for="chip in chips"
          :key="chip.label"
          class="vfs-chip"
          :style="{ borderLeftColor: KIND_COLORS[chip.kind] }"
        >
          <div class="vfs-chip-head">
            <span class="vfs-chip-label">{{ chip.label }}</span>
            <el-tag size="small" :color="KIND_COLORS[chip.kind]" effect="plain" class="vfs-kind-tag">
              {{ KIND_LABELS[chip.kind] }}
            </el-tag>
          </div>
          <div class="vfs-chip-amount" :class="{ 'vfs-amount-none': chip.amount == null }">
            {{ formatValueAmount(chip.amount) }}<span v-if="chip.amount != null" class="vfs-suffix">{{ chip.suffix }}</span>
          </div>
        </div>
      </div>

      <div v-if="summary.confidenceNote" class="vfs-note">
        <el-icon><InfoFilled /></el-icon><span>{{ summary.confidenceNote }}</span>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useRouter } from 'vue-router';
import { TrendCharts, Refresh, InfoFilled } from '@element-plus/icons-vue';
import {
  getValueSummary,
  type ValueSummary,
} from '@/api/smartbi/restaurantValueApi';
import {
  buildValueChips,
  totalForPeriod,
  totalKind,
  formatValueAmount,
  KIND_COLORS,
  KIND_LABELS,
} from './valueFeedbackFormat';

const props = defineProps<{
  factoryId: string;
  /** 业态门控: 父组件传 true (RESTAURANT) 才渲染。 */
  show?: boolean;
}>();

const router = useRouter();
const loading = ref(false);
const loadError = ref('');
const summary = ref<ValueSummary | null>(null);
const period = ref<'month' | 'annual'>('month');

const show = computed(() => props.show !== false);

const chips = computed(() => (summary.value ? buildValueChips(summary.value, period.value) : []));
const totalVal = computed(() => (summary.value ? totalForPeriod(summary.value, period.value) : null));
const totalKindVal = computed(() => (summary.value ? totalKind(summary.value, period.value) : 'none'));

const subtitle = computed(() => {
  if (!summary.value) return '基于诊断引擎已算出的可节省金额';
  return `${summary.value.periodMonth} · 区分预估/实测, 数据来自上传月度经营数据`;
});

function goUpload() {
  router.push('/smart-bi/upload');
}

async function load() {
  if (!props.factoryId || !show.value) return;
  loading.value = true;
  loadError.value = '';
  try {
    const env = await getValueSummary({});
    // 诚实空态: data:null = 无快照 (success 仍 true)。
    summary.value = env.success ? env.data : null;
    if (!env.success) {
      loadError.value = env.message || '加载失败';
    }
  } catch (e) {
    // 禁降级假数据: 失败显式报错, 不伪造金额
    loadError.value = e instanceof Error ? e.message : '请求失败';
    summary.value = null;
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.factoryId, show.value],
  () => { void load(); },
  { immediate: true },
);
</script>

<style scoped>
.vfs-section {
  margin-top: 16px;
  padding: 16px;
  background: linear-gradient(135deg, #fffaf3 0%, #f3fbf7 100%);
  border: 1px solid #ffe3c2;
  border-radius: 10px;
}
.vfs-header { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.vfs-icon { color: #FF9800; font-size: 18px; }
.vfs-title { font-size: 16px; font-weight: 600; }
.vfs-subtitle { font-size: 12px; color: #909399; flex: 1; }
.vfs-period { margin-right: 8px; }
.vfs-error { color: #f56c6c; padding: 12px; background: #fef0f0; border-radius: 6px; }
.vfs-empty {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; padding: 14px; font-size: 13px; color: #909399;
  background: #fafafa; border-radius: 6px;
}
.vfs-body { display: flex; flex-direction: column; gap: 14px; }
.vfs-total {
  padding: 14px 18px; background: #fff; border-radius: 8px;
  border-left: 4px solid #FF9800;
}
.vfs-total-label { font-size: 13px; color: #606266; display: flex; align-items: center; gap: 8px; }
.vfs-total-amount { font-size: 30px; font-weight: 700; color: #1f2d3d; margin-top: 6px; }
.vfs-meta { font-size: 12px; color: #909399; margin-top: 6px; }
.vfs-amount-none { color: #c0c4cc; font-size: 18px; font-weight: 500; }
.vfs-kind-tag { color: #fff; border: none; }
.vfs-chips { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; }
.vfs-chip {
  padding: 10px 14px; background: #fff; border-radius: 6px;
  border-left: 3px solid #9E9E9E;
}
.vfs-chip-head { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.vfs-chip-label { font-size: 13px; color: #606266; }
.vfs-chip-amount { font-size: 18px; font-weight: 600; color: #1f2d3d; margin-top: 4px; }
.vfs-suffix { font-size: 12px; color: #909399; font-weight: 400; margin-left: 2px; }
.vfs-note {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; color: #909399; padding: 6px 0;
}
</style>
