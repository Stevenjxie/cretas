<script setup lang="ts">
/**
 * AI 工作台 —— 回答老板那句「这个 AI 到底有没有用」。
 *
 * 三条刻意的诚实约束，改这个页面前先读：
 *
 * 1. **不显示金额**。后端 `costInYuan` 恒为 null，因为系统没有 token 单价配置。
 *    不要在前端乘一个费率把它算出来 —— 那会得到一个看起来精确的假数字。
 *    要提就提 `costUnavailableReason`。
 *
 * 2. **不显示「省了多少钱」**。缺反事实（不提醒会损失多少）和因果（人是否因它
 *    行动）。用告警三段计数（触发 / 确认 / 解决）替代，每条可点开看到批次号。
 *
 * 3. **零是零，不美化**。真实工厂目前 AI 调用为 0 —— 页面必须明说「还没有人用过」
 *    并给出下一步，而不是画一堆空图表假装热闹。把零渲染成「运行正常」就是把
 *    「没发生」说成「一切良好」。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { fetchAiValueSummary, type AiValueSummary } from '@/api/aiValueSummary';
import { useAuthStore } from '@/store/modules/auth';

const authStore = useAuthStore();

const loading = ref(false);
const loadError = ref<string | null>(null);
const summary = ref<AiValueSummary | null>(null);
const days = ref(30);

const dayOptions = [
  { label: '最近 7 天', value: 7 },
  { label: '最近 30 天', value: 30 },
  { label: '最近 90 天', value: 90 }
];

/** 什么都没发生 —— 与「加载失败」是两件完全不同的事，UI 上必须分开。 */
const isGenuinelyEmpty = computed(
  () => !!summary.value && summary.value.aiCalls === 0 && summary.value.alertsTotal === 0
);

const statusCount = (key: string) => summary.value?.alertsByStatus?.[key] ?? 0;

async function load() {
  const factoryId = authStore.factoryId;
  if (!factoryId) {
    loadError.value = '当前账号没有工厂上下文，无法查询';
    return;
  }
  loading.value = true;
  loadError.value = null;
  try {
    const res = await fetchAiValueSummary(factoryId, days.value);
    summary.value = res.data;
  } catch (error: unknown) {
    // 加载失败不得渲染成 0 —— 那等于把「查不到」说成「什么都没发生」。
    summary.value = null;
    loadError.value = error instanceof Error ? error.message : '加载失败';
    ElMessage.error(loadError.value);
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="ai-value-summary" v-loading="loading">
    <div class="header">
      <h2>AI 工作台</h2>
      <el-select v-model="days" style="width: 140px" @change="load">
        <el-option
          v-for="opt in dayOptions"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
    </div>

    <!-- 失败：明确报错，绝不退化成一片 0 -->
    <el-alert
      v-if="loadError"
      type="error"
      show-icon
      :closable="false"
      title="数据加载失败"
      :description="`${loadError}。以下不显示任何数字 —— 查不到和没发生是两件事。`"
    />

    <template v-else-if="summary">
      <!-- 真的什么都没发生：明说，并给下一步，而不是画空图表 -->
      <el-alert
        v-if="isGenuinelyEmpty"
        type="info"
        show-icon
        :closable="false"
        :title="`最近 ${summary.windowDays} 天，还没有人用过 AI`"
        description="这不是故障：系统在正常运行，只是没有人向它提过问题，也没有触发过预警。想让它有内容，先在任意页面用一次 AI 问答。"
      />

      <el-row :gutter="16" class="cards">
        <el-col :span="8">
          <el-card shadow="never">
            <div class="label">AI 被调用</div>
            <div class="value">{{ summary.aiCalls }}<span class="unit">次</span></div>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card shadow="never">
            <div class="label">消耗 token</div>
            <div class="value">{{ summary.totalTokens }}</div>
            <!-- 这行是刻意的：说明为什么这里没有「元」 -->
            <div class="note">{{ summary.costUnavailableReason }}</div>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card shadow="never">
            <div class="label">触发预警</div>
            <div class="value">{{ summary.alertsTotal }}<span class="unit">条</span></div>
            <div class="note">
              已确认 {{ statusCount('ACKNOWLEDGED') }} · 已解决 {{ statusCount('RESOLVED') }} ·
              仍敞开 {{ statusCount('OPEN') }}
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-card v-if="summary.alertDetails.length" shadow="never" class="detail">
        <template #header>预警明细（可核对到具体批次）</template>
        <el-table :data="summary.alertDetails" size="small">
          <el-table-column prop="businessEntityType" label="对象类型" width="160" />
          <el-table-column prop="businessEntityId" label="批次/单据号" width="180" />
          <el-table-column prop="severity" label="级别" width="100" />
          <el-table-column prop="status" label="状态" width="120" />
          <el-table-column prop="message" label="说明" />
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.ai-value-summary {
  padding: 16px;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.header h2 {
  margin: 0;
  font-size: 18px;
}
.cards {
  margin-top: 16px;
}
.label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.value {
  margin-top: 8px;
  font-size: 28px;
  font-weight: 600;
}
.unit {
  margin-left: 4px;
  font-size: 14px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.note {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
.detail {
  margin-top: 16px;
}
</style>
