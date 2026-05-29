<template>
  <div class="b2b-real-section">
    <!-- 老板能用度: 简洁说明数据来源, 无技术术语 (per fool-proof-design + 无 emoji) -->
    <el-alert
      type="success"
      :closable="false"
      show-icon
      class="big-banner"
    >
      <template #title>
        <span class="big-banner-title">B2B 销售实时数据</span>
      </template>
      <template #default>
        <span class="big-banner-detail">
          下方卡片是贵厂销售订单的实时统计，反映真实经营数据，并非示例。
        </span>
      </template>
    </el-alert>

    <!-- B2B 真业务数据 section -->
    <el-card class="b2b-section-card" shadow="never">
      <template #header>
        <div class="section-header">
          <div class="section-title-block">
            <el-icon class="section-icon"><DataAnalysis /></el-icon>
            <div>
              <h3 class="section-title">B2B 销售真业务数据</h3>
              <p class="section-subtitle">
                来源: 销售订单 ·
                <el-tag size="small" type="success" effect="plain">实时统计</el-tag>
              </p>
            </div>
          </div>
          <el-button :icon="Refresh" :loading="loading" size="small" @click="loadData">
            刷新
          </el-button>
        </div>
      </template>

      <div v-if="loading" class="loading-block">
        <el-skeleton :rows="3" animated />
      </div>

      <el-empty
        v-else-if="orders.length === 0"
        :image-size="100"
        description="暂无销售订单数据 — 请联系销售员录入 sales_orders"
      >
        <el-button type="primary" @click="goSalesOrders">前往销售订单</el-button>
      </el-empty>

      <div v-else>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12" :md="8">
            <div class="b2b-card b2b-card-blue">
              <div class="b2b-card-label">订单总数</div>
              <div class="b2b-card-value">{{ stats.count }}</div>
              <div class="b2b-card-unit">单</div>
              <div class="b2b-card-source">销售订单数量</div>
            </div>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <div class="b2b-card b2b-card-green">
              <div class="b2b-card-label">平均订单金额 (B2B)</div>
              <div class="b2b-card-value">¥ {{ formatMoney(stats.avgAmount) }}</div>
              <div class="b2b-card-unit">元/单</div>
              <!-- #270: 样本小时均值波动大, 提示老板谨慎解读 -->
              <div v-if="stats.count < 30" class="b2b-card-source b2b-card-warn">
                样本较小 (n={{ stats.count }})，单笔大单会明显影响均值
              </div>
              <div v-else class="b2b-card-source">销售总额 / 订单数</div>
            </div>
          </el-col>
          <el-col :xs="24" :sm="12" :md="8">
            <div class="b2b-card b2b-card-orange">
              <div class="b2b-card-label">销售总额 (累计)</div>
              <div class="b2b-card-value">¥ {{ formatMoney(stats.totalAmount) }}</div>
              <div class="b2b-card-unit">元</div>
              <div class="b2b-card-source">全部订单金额累计</div>
            </div>
          </el-col>
        </el-row>

        <div class="data-footer">
          <span>数据时间: {{ lastLoadedAt }}</span>
          <span class="footer-divider">|</span>
          <span>样本: 最近 {{ orders.length }} 个销售订单</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
/**
 * B2BRealDataSection — F006 B2B 销售真业务数据 (Sprint 11 临时方案).
 *
 * 直接 fetch /api/mobile/{factoryId}/sales/orders 在前端 reduce 计算
 * avg/sum/count, 绕过 backend IndicatorQueryService (Sprint 11 anti-goal:
 * 不动 backend, 防撞车 sister chats).
 *
 * Sprint 12 backend 重写 IndicatorQueryService 真接业务表后, 此组件应删除,
 * UI 改回统一从 /api/mobile/{factoryId}/indicators/{code}/value 拉取.
 *
 * 参见 docs/sprint-12-backlog/indicator-service-rewrite.md.
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { DataAnalysis, Refresh } from '@element-plus/icons-vue';
import { get } from '@/api/request';

interface Props {
  factoryId: string;
}
const props = defineProps<Props>();

interface SalesOrderRow {
  id: string;
  orderNumber?: string;
  customerName?: string;
  totalAmount?: number | string;
  status?: string;
  createdAt?: string;
}

const router = useRouter();
const orders = ref<SalesOrderRow[]>([]);
const loading = ref(false);
const lastLoadedAt = ref('');

const stats = computed(() => {
  const count = orders.value.length;
  if (count === 0) {
    return { count: 0, avgAmount: 0, totalAmount: 0 };
  }
  const totalAmount = orders.value.reduce((acc, o) => {
    const v = typeof o.totalAmount === 'string' ? Number(o.totalAmount) : (o.totalAmount || 0);
    return acc + (Number.isFinite(v) ? v : 0);
  }, 0);
  const avgAmount = totalAmount / count;
  return { count, avgAmount, totalAmount };
});

function formatMoney(v: number): string {
  if (!Number.isFinite(v) || v === 0) return '0';
  if (Math.abs(v) >= 10000) {
    return v.toLocaleString('zh-CN', { maximumFractionDigits: 0 });
  }
  return v.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

async function loadData() {
  if (!props.factoryId) return;
  loading.value = true;
  try {
    const res = await get(`/${props.factoryId}/sales/orders`, {
      params: { page: 1, size: 200 },
    } as never);
    if (res.success && res.data) {
      const data = res.data as { content?: SalesOrderRow[] };
      orders.value = data.content || [];
      lastLoadedAt.value = new Date().toLocaleString('zh-CN');
    } else {
      orders.value = [];
      ElMessage.warning('销售订单加载失败');
    }
  } catch (e) {
    console.warn('[B2BRealDataSection] sales orders fetch failed:', e);
    orders.value = [];
  } finally {
    loading.value = false;
  }
}

function goSalesOrders() {
  router.push('/sales/orders');
}

onMounted(() => {
  loadData();
});

defineExpose({ loadData });
</script>

<style scoped>
.b2b-real-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.big-banner {
  border: 2px solid var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.big-banner-title {
  font-weight: 700;
  font-size: 18px;
  color: var(--el-color-primary);
}
.big-banner-detail {
  font-size: 14px;
  color: var(--el-text-color-primary);
  line-height: 1.6;
  display: block;
  margin-top: 6px;
}
.big-banner-detail code {
  background: var(--el-fill-color-light);
  padding: 1px 6px;
  border-radius: 3px;
  font-family: var(--el-font-family-monospace, monospace);
  font-size: 13px;
}

.b2b-section-card {
  border: 1px solid var(--el-color-primary-light-5);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.section-title-block {
  display: flex;
  align-items: center;
  gap: 12px;
}

.section-icon {
  font-size: 24px;
  color: var(--el-color-primary);
}

.section-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.section-subtitle {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.section-subtitle code {
  background: var(--el-fill-color-light);
  padding: 1px 4px;
  border-radius: 2px;
  font-family: var(--el-font-family-monospace, monospace);
  margin: 0 4px;
}

.b2b-card {
  padding: 20px 16px;
  border-radius: 8px;
  margin-bottom: 12px;
  border-left: 4px solid;
  background: var(--el-fill-color-blank);
  transition: transform 0.15s, box-shadow 0.15s;
}
.b2b-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}
.b2b-card-blue { border-left-color: var(--el-color-primary); }
.b2b-card-green { border-left-color: var(--el-color-success); }
.b2b-card-orange { border-left-color: var(--el-color-warning); }

.b2b-card-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}

.b2b-card-value {
  font-size: 28px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-primary);
  line-height: 1.2;
}

.b2b-card-unit {
  display: inline-block;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-left: 4px;
}

.b2b-card-source {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-top: 8px;
}

/* #270 样本小提示 — warning 色, 非 monospace, 老板可读 */
.b2b-card-warn {
  color: var(--el-color-warning);
  font-size: 11px;
}

.data-footer {
  margin-top: 8px;
  padding: 8px 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 4px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.footer-divider {
  color: var(--el-border-color);
}

.loading-block {
  padding: 16px;
}
</style>
