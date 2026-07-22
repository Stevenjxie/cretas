<script setup lang="ts">
/**
 * Sprint4-H F-AR-1 — 销售单成本与审批进度只读页 (PC).
 *
 * 业务流程:
 *   - 加载订单 + 成本核算 (FinanceCostBreakdown)
 *   - 展示 BOM 标准成本 / 当前预估成本 / 实际成本 / 预估 vs 实际利润对比
 *   - 审批动作统一前往个人 OA，业务页不再调用直批端点
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';
import { ArrowLeft } from '@element-plus/icons-vue';
import {
  getOrderDetail,
  getOrderCostBreakdown,
  getOrderMultiStageCost,
  getProductPriceTrend,
  getActiveQuotes,
  type SalesOrderSummary,
  type FinanceCostBreakdown,
  type LineCostBreakdown,
  type SalesPriceTrendDTO,
  type OperationalQuoteSummary,
  type MultiStageCostBreakdown,
} from '@/api/salesFinanceReview';
import {
  getLaborCostOrderAggregate,
  type LaborEfficiencyOrderAggregateDTO,
} from '@/api/laborEfficiency';
import ThreePriceCostBreakdown from '@/components/ThreePriceCostBreakdown.vue';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const factoryId = computed(() => authStore.factoryId);
const orderId = computed(() => String(route.params.id || ''));

const order = ref<SalesOrderSummary | null>(null);
const breakdown = ref<FinanceCostBreakdown | null>(null);

// 六扇门多段生产成本逐段分配 (两点报工成本拆分核心) — 非阻塞独立加载
const multiStage = ref<MultiStageCostBreakdown | null>(null);
const multiStageLoading = ref(false);
const loading = ref(false);

// B3 售价趋势 — 按产品类型分组，key=productTypeId
const priceTrendMap = ref<Record<string, SalesPriceTrendDTO[]>>({});
const priceTrendLoading = ref(false);

// #734 SP3 M3b: 双口径人工对比聚合 (订单级)
const orderAggregate = ref<LaborEfficiencyOrderAggregateDTO | null>(null);
const orderAggregateLoading = ref(false);

// 三层价格对比: 运营报价 (研发预估价), key=productTypeId
// 数据来源: OperationalQuote (APPROVED + 未过期 + 同客户同产品)
// 展示: 研发预估价(运营报价单价) vs 下单价(SO.unitPrice) vs 实际成本价(LineCostBreakdown.actualCostPerUnit)
const operationalQuoteMap = ref<Record<string, OperationalQuoteSummary | null>>({});
const operationalQuoteLoading = ref(false);

const canReview = computed(
  () => order.value?.status === 'PENDING_FINANCE_REVIEW',
);

/**
 * Issue #778 (P3 customer-gap): "预估成本" 字段暂时隐藏 (feature flag).
 *
 * 客户决策 (May 7 part2 L461-475):
 *   "财务那边可能就说我一定要算出来什么东西什么东西, 这个**暂时先去掉吧**"
 *   "财务那边肯定会比较跳的"
 *   "后期可能我们这边跑起来的可能会用到"
 *
 * 修法: Option A — 前端 v-if feature flag.
 *   后端 estimated_cost 数据保留 (重新启用时不丢值), 仅 UI 暂时遮挡.
 *   未来如要复用: 直接把此常量翻为 true 即可.
 *
 * 影响范围:
 *   - 成本核算 card 的 "当前预估成本" cell
 *   - 成本核算 card 的 "预估利润" cell (依赖 currentEstimatedProfit)
 *   - 历史展示中的预估成本字段
 */
const SHOW_PRE_ESTIMATED_COST = false;

async function load() {
  if (!factoryId.value || !orderId.value) return;
  loading.value = true;
  try {
    const [orderRes, breakdownRes] = await Promise.all([
      getOrderDetail(factoryId.value, orderId.value),
      getOrderCostBreakdown(factoryId.value, orderId.value),
    ]);
    if (orderRes.success && orderRes.data) order.value = orderRes.data;
    if (breakdownRes.success && breakdownRes.data) {
      breakdown.value = breakdownRes.data;
      // B3: 异步加载各产品线的售价趋势 (非阻塞, 独立 loading)
      void loadPriceTrends(breakdownRes.data.lines);
      // 三层价格: 异步加载各产品线的运营报价 (研发预估价), 非阻塞
      if (orderRes.success && orderRes.data?.customerId) {
        void loadOperationalQuotes(breakdownRes.data.lines, orderRes.data.customerId);
      }
    }
    // #734 SP3 M3b: 订单确认后异步加载双口径人工聚合 (非阻塞)
    if (orderRes.success && orderRes.data?.orderDate) {
      void loadOrderAggregate(orderRes.data.orderDate);
    }
    // 六扇门多段成本: 异步加载逐段成本链 (非阻塞)
    void loadMultiStageCost();
  } finally {
    loading.value = false;
  }
}

/**
 * 六扇门多段生产成本逐段分配 — 回溯成品←半成品←原料链。
 * 非阻塞 — 失败只 warn, 不影响财审主流程。
 */
async function loadMultiStageCost() {
  if (!factoryId.value || !orderId.value) return;
  multiStageLoading.value = true;
  try {
    const res = await getOrderMultiStageCost(factoryId.value, orderId.value);
    if (res.success && res.data) {
      multiStage.value = res.data;
    }
  } catch (e) {
    console.warn('[多段成本] 加载失败 (非阻塞):', e);
  } finally {
    multiStageLoading.value = false;
  }
}

/**
 * #734 SP3 M3b: 加载订单级人工双口径聚合 (报价 vs 实际).
 * 以订单日期为中心取 ±90天窗口, 后端会按 source_order_id 分组返回本订单的批次.
 * 非阻塞 — 失败只 warn, 不影响主流程.
 */
async function loadOrderAggregate(orderDate: string) {
  if (!factoryId.value) return;
  orderAggregateLoading.value = true;
  try {
    // 以订单日期为中心取 90 天窗口 (覆盖从下单到完工的周期)
    const d = new Date(orderDate);
    const start = new Date(d);
    start.setDate(start.getDate() - 30);
    const end = new Date(d);
    end.setDate(end.getDate() + 90);
    const fmt = (dt: Date) => dt.toISOString().slice(0, 10);

    const res = await getLaborCostOrderAggregate(factoryId.value, fmt(start), fmt(end));
    if (res.success && Array.isArray(res.data) && res.data.length > 0) {
      // 取第一条 (该窗口内订单级聚合); 若有 orderId 字段可精确匹配
      const match = res.data.find((a: LaborEfficiencyOrderAggregateDTO) =>
        a.salesOrderId === orderId.value
      ) ?? res.data[0];
      orderAggregate.value = match ?? null;
    }
  } catch (e) {
    console.warn('[#734 order-aggregate] load failed, non-critical:', e);
  } finally {
    orderAggregateLoading.value = false;
  }
}

/**
 * B3: 为财审行级明细的每个产品类型加载近期售价趋势.
 * 非阻塞 — 网络错误仅 warn 不影响主流程.
 */
async function loadPriceTrends(lines: LineCostBreakdown[]) {
  if (!factoryId.value || !lines || lines.length === 0) return;
  // 去重 productId (避免同产品多行重复请求)
  const productIds = [...new Set(lines.map((l) => l.productId).filter(Boolean) as string[])];
  if (productIds.length === 0) return;
  priceTrendLoading.value = true;
  try {
    const results = await Promise.allSettled(
      productIds.map((pid) =>
        getProductPriceTrend(factoryId.value, pid, 10).then((res) => ({
          productId: pid,
          rows: res.success && res.data ? res.data : [],
        })),
      ),
    );
    const map: Record<string, SalesPriceTrendDTO[]> = {};
    for (const r of results) {
      if (r.status === 'fulfilled') {
        map[r.value.productId] = r.value.rows;
      }
    }
    priceTrendMap.value = map;
  } catch (e) {
    console.warn('[B3 price-trend] load failed, non-critical:', e);
  } finally {
    priceTrendLoading.value = false;
  }
}

/**
 * 三层价格: 加载各产品线的有效运营报价 (研发预估价).
 * 非阻塞 — 失败只 warn, 不影响主流程.
 * 注意: OperationalQuote.unitPrice 是 @PriceSensitive, 非财务角色后端返回 null.
 * 财审页访问者已具有 finance:read 权限, 所以可以看到绝对值.
 */
async function loadOperationalQuotes(lines: LineCostBreakdown[], customerId: string) {
  if (!factoryId.value || !lines || lines.length === 0 || !customerId) return;
  const productIds = [...new Set(lines.map((l) => l.productId).filter(Boolean) as string[])];
  if (productIds.length === 0) return;
  operationalQuoteLoading.value = true;
  try {
    const results = await Promise.allSettled(
      productIds.map((pid) =>
        getActiveQuotes(factoryId.value, customerId, pid).then((res) => ({
          productId: pid,
          // 取最新有效报价 (列表按 createdAt 降序, 第一条最新)
          quote: res.success && res.data && res.data.length > 0 ? res.data[0] : null,
        })),
      ),
    );
    const map: Record<string, OperationalQuoteSummary | null> = {};
    for (const r of results) {
      if (r.status === 'fulfilled') {
        map[r.value.productId] = r.value.quote;
      }
    }
    operationalQuoteMap.value = map;
  } catch (e) {
    console.warn('[三层价格] loadOperationalQuotes failed, non-critical:', e);
  } finally {
    operationalQuoteLoading.value = false;
  }
}

/**
 * B3: 计算某产品在历史记录中的平均单价 (null 表示无数据).
 */
function avgPrice(productTypeId: string | null): number | null {
  if (!productTypeId) return null;
  const rows = priceTrendMap.value[productTypeId];
  if (!rows || rows.length === 0) return null;
  const prices = rows.map((r) => r.unitPrice).filter((p): p is number => p != null);
  if (prices.length === 0) return null;
  return prices.reduce((a, b) => a + b, 0) / prices.length;
}

/**
 * B3: 本单价格偏差百分比 vs 历史均值.
 * 正值=偏高(高于历史均价), 负值=偏低.
 */
function priceDiff(currentPrice: number | null, productTypeId: string | null): number | null {
  const avg = avgPrice(productTypeId);
  if (avg == null || !currentPrice || avg === 0) return null;
  return ((currentPrice - avg) / avg) * 100;
}

function formatAmount(v: number | null | undefined): string {
  if (v == null) return '-';
  return `¥${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatPercent(v: number | null | undefined): string {
  if (v == null) return '-';
  return `${v.toFixed(2)}%`;
}

function profitClass(p: number | null | undefined): string {
  if (p == null) return '';
  if (p > 0) return 'profit-positive';
  if (p < 0) return 'profit-negative';
  return '';
}

/**
 * SP3: 超支 → red, 正常 → green, null/不足数据 → 默认色
 * belowThreshold: true=正常, false=超支, null=数据不足
 */
function varianceClass(belowThreshold: boolean | null | undefined): string {
  if (belowThreshold === false) return 'profit-negative';
  if (belowThreshold === true) return 'profit-positive';
  return '';
}

function lineRowClassName({ row }: { row: LineCostBreakdown }): string {
  // SP3: 后端已计算方差阈值，belowThreshold===false → 超支红标行
  if (row.belowThreshold === false) return 'cost-overbudget-row';
  // 旧逻辑兜底: BOM 标准与实际偏差 > 15% 时标黄 (财务关注偏差大的行)
  if (row.bomStandardLineCost != null && row.actualLineCost != null) {
    const std = row.bomStandardLineCost;
    if (std > 0) {
      const dev = Math.abs((row.actualLineCost - std) / std);
      if (dev > 0.15) return 'cost-deviation-row';
    }
  }
  return '';
}

async function goToUnifiedOa() {
  await router.push({
    name: 'WorkflowPending',
    query: { moduleCode: 'SALES_ORDER' },
  });
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="finance-review-detail">
    <div class="page-header">
      <el-button :icon="ArrowLeft" link @click="router.back()">返回</el-button>
      <h2 class="title">销售财务审核 · {{ order?.orderNumber || '—' }}</h2>
    </div>

    <!-- 摘要 -->
    <el-card v-if="order" shadow="never" class="summary-card">
      <div class="summary-grid">
        <div>
          <div class="label">订单号</div>
          <div class="value">{{ order.orderNumber }}</div>
        </div>
        <div>
          <div class="label">客户</div>
          <div class="value">{{ order.customerName || order.customerId }}</div>
        </div>
        <div>
          <div class="label">总金额</div>
          <div class="value">{{ formatAmount(order.totalAmount) }}</div>
        </div>
        <div>
          <div class="label">下单日期</div>
          <div class="value">{{ order.orderDate }}</div>
        </div>
        <div>
          <div class="label">业务员</div>
          <div class="value">{{ order.salesperson || '-' }}</div>
        </div>
        <div>
          <div class="label">状态</div>
          <div class="value">
            <el-tag :type="canReview ? 'warning' : 'info'">{{ order.status }}</el-tag>
          </div>
        </div>
      </div>
      <el-alert
        v-if="!canReview"
        type="info"
        :closable="false"
        :title="`仅 PENDING_FINANCE_REVIEW 状态可审核 (当前: ${order.status})`"
        style="margin-top: 12px"
      />
    </el-card>

    <!-- SP3: 超支告警横幅 (sticky error banner) -->
    <el-alert
      v-if="breakdown?.alarmMessage"
      type="error"
      :closable="false"
      :title="breakdown.alarmMessage"
      style="margin-bottom: 12px"
      show-icon
    />

    <!-- 成本核算汇总 -->
    <el-card v-if="breakdown" shadow="never" class="summary-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">成本核算</span>
          <span v-if="breakdown.dataSourceHint" class="card-subtitle">
            {{ breakdown.dataSourceHint }}
          </span>
        </div>
      </template>
      <div class="cost-grid">
        <div class="cost-cell">
          <div class="label">订单总额</div>
          <div class="value-lg">{{ formatAmount(breakdown.totalAmount) }}</div>
        </div>
        <div class="cost-cell">
          <div class="label">BOM 标准成本</div>
          <div class="value-lg">{{ formatAmount(breakdown.bomStandardCost) }}</div>
        </div>
        <!-- Issue #778: 预估成本暂时隐藏 (feature flag SHOW_PRE_ESTIMATED_COST). -->
        <div v-if="SHOW_PRE_ESTIMATED_COST" class="cost-cell">
          <div class="label">当前预估成本</div>
          <div class="value-lg">{{ formatAmount(breakdown.currentEstimatedCost) }}</div>
        </div>
        <div class="cost-cell">
          <div class="label">实际成本</div>
          <div class="value-lg">{{ formatAmount(breakdown.actualCost) }}</div>
        </div>
        <!-- P1 #32: 委外加工费独立科目 — 仅在有数据时显示 (当前 WorkProcess 无委外费用数据 → 不显示占位行) -->
        <div v-if="breakdown.processingFee != null" class="cost-cell">
          <div class="label">委外加工费</div>
          <div class="value-lg">{{ formatAmount(breakdown.processingFee) }}</div>
        </div>
        <!-- SP3: 三价对比 — 成本偏差率 + 超支红标 -->
        <div class="cost-cell">
          <div class="label">成本偏差率 (实际 vs BOM)</div>
          <div class="value-lg">
            <span :class="varianceClass(breakdown.belowThreshold)">
              {{ formatPercent(breakdown.variancePct) }}
            </span>
            <el-tag
              v-if="breakdown.belowThreshold === false"
              type="danger"
              size="small"
              style="margin-left: 8px"
            >超支</el-tag>
            <el-tag
              v-else-if="breakdown.belowThreshold === true"
              type="success"
              size="small"
              style="margin-left: 8px"
            >正常</el-tag>
          </div>
          <div v-if="breakdown.varianceAbsolute != null" class="variance-abs">
            绝对偏差: {{ formatAmount(breakdown.varianceAbsolute) }}
          </div>
        </div>
        <div v-if="SHOW_PRE_ESTIMATED_COST" class="cost-cell">
          <div class="label">预估利润</div>
          <div :class="['value-lg', profitClass(breakdown.currentEstimatedProfit)]">
            {{ formatAmount(breakdown.currentEstimatedProfit) }}
            <span class="margin-pct">({{ formatPercent(breakdown.profitMarginEstimated) }})</span>
          </div>
        </div>
        <div class="cost-cell">
          <div class="label">实际利润</div>
          <div :class="['value-lg', profitClass(breakdown.actualProfit)]">
            {{ formatAmount(breakdown.actualProfit) }}
            <span class="margin-pct">({{ formatPercent(breakdown.profitMarginActual) }})</span>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 行级成本明细 -->
    <el-card v-if="breakdown && breakdown.lines.length > 0" shadow="never" class="comparison-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">行级成本明细</span>
          <span class="card-subtitle">{{ breakdown.lines.length }} 项</span>
        </div>
      </template>
      <el-table
        :data="breakdown.lines"
        :row-class-name="lineRowClassName"
        stripe
        empty-text="无明细"
      >
        <el-table-column prop="productName" label="产品" min-width="180">
          <template #default="{ row }">
            {{ row.productName || row.productId || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="数量" align="right" min-width="100">
          <template #default="{ row }">{{ row.quantity ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="销售单价" align="right" min-width="120">
          <template #default="{ row }">{{ formatAmount(row.unitPrice) }}</template>
        </el-table-column>
        <el-table-column label="销售小计" align="right" min-width="130">
          <template #default="{ row }">{{ formatAmount(row.lineAmount) }}</template>
        </el-table-column>
        <el-table-column label="BOM 标准单位成本" align="right" min-width="150">
          <template #default="{ row }">{{ formatAmount(row.bomStandardUnitCost) }}</template>
        </el-table-column>
        <el-table-column label="BOM 标准行成本" align="right" min-width="150">
          <template #default="{ row }">{{ formatAmount(row.bomStandardLineCost) }}</template>
        </el-table-column>
        <el-table-column label="实际行成本" align="right" min-width="130">
          <template #default="{ row }">{{ formatAmount(row.actualLineCost) }}</template>
        </el-table-column>
        <!-- SP3: 行级成本偏差率 + 超支红标 -->
        <el-table-column label="偏差率" align="right" min-width="120">
          <template #default="{ row }">
            <span :class="varianceClass(row.belowThreshold)">
              {{ formatPercent(row.variancePct) }}
            </span>
            <el-tag
              v-if="row.belowThreshold === false"
              type="danger"
              size="small"
              style="margin-left: 4px"
            >超</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 六扇门多段生产成本逐段分配 (两点报工成本拆分核心) ─────────────────
         回溯成品←半成品←原料链, 每段=一次两点报工转化 (一行 SemiFinishedInventory)。
         逐段拆 料/人工/制费 + 半成品 unitCost 逐段累积 (#713 移动均价) + 每盒贡献。
         契合六扇门两点报工 (无逐道工序数据); 人工"登下一期"未结时该段诚实 null。
         金额 @PriceSensitive — 非财务角色后端脱敏为 null 显示 "—"。
    ────────────────────────────────────────────────────────────── -->
    <el-card
      v-if="multiStage && (multiStage.stageCount ?? 0) > 0"
      v-loading="multiStageLoading"
      shadow="never"
      class="summary-card"
      style="margin-bottom: 16px"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">多段成本链 (逐段分配)</span>
          <span class="card-subtitle">
            原料 → 半成品 → 成品 · 每段料/人工/制费 + 半成品移动均价逐段累积
          </span>
        </div>
      </template>

      <el-alert
        v-if="multiStage.dataSourceHint"
        :title="multiStage.dataSourceHint"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />

      <el-table :data="multiStage.stages" border stripe size="small" empty-text="无段记录">
        <el-table-column label="段序" align="center" width="64">
          <template #default="{ row }">{{ row.stageOrder ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="段 / 半成品" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <div>{{ row.stageName || row.semiCode || '—' }}</div>
            <div style="color: #909399; font-size: 12px">{{ row.semiCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="产出量" align="right" min-width="110">
          <template #default="{ row }">
            {{ row.producedQuantity != null
              ? `${row.producedQuantity} ${row.producedUnit ?? ''}`.trim()
              : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="材料" align="right" min-width="110">
          <template #default="{ row }">{{ formatAmount(row.materialCost) }}</template>
        </el-table-column>
        <el-table-column label="人工" align="right" min-width="130">
          <template #default="{ row }">
            <template v-if="row.laborCost != null">{{ formatAmount(row.laborCost) }}</template>
            <el-tooltip v-else :content="row.laborHint || '人工登下一期'" placement="top">
              <span style="color: #e6a23c">登下一期</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="制费" align="right" min-width="100">
          <template #default="{ row }">{{ formatAmount(row.overheadCost) }}</template>
        </el-table-column>
        <el-table-column label="段小计" align="right" min-width="120">
          <template #default="{ row }">
            <strong>{{ formatAmount(row.stageSubtotal) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="半成品移动均价" align="right" min-width="140">
          <template #default="{ row }">{{ formatAmount(row.outputUnitCost) }}</template>
        </el-table-column>
        <el-table-column label="累积成本" align="right" min-width="120">
          <template #default="{ row }">{{ formatAmount(row.accumulatedCost) }}</template>
        </el-table-column>
        <el-table-column label="折每盒贡献" align="right" min-width="120">
          <template #default="{ row }">{{ formatAmount(row.contributionPerBox) }}</template>
        </el-table-column>
      </el-table>

      <div class="multi-stage-summary">
        <span>
          全链段消耗合计:
          <strong>{{ formatAmount(multiStage.totalChainCost) }}</strong>
        </span>
        <span v-if="multiStage.finalOutputBoxes != null">
          成品产出:
          <strong>{{ multiStage.finalOutputBoxes }} {{ multiStage.finalOutputUnit ?? '' }}</strong>
        </span>
        <span v-if="multiStage.totalCostPerBox != null">
          折每盒成本合计:
          <strong>{{ formatAmount(multiStage.totalCostPerBox) }}</strong>
        </span>
      </div>
    </el-card>

    <!-- 三层价格同屏对比 (转录[15:51-16:08]) ─────────────────────────
         Layer ① 研发预估价  = OperationalQuote.unitPrice (运营报价审批后)
         Layer ② 下单价      = SalesOrderItem.unitPrice
         Layer ③ 实际成本价  = LineCostBreakdown.actualCostPerUnit
         数据均为 @PriceSensitive — 财审页财务角色可见, 非财务角色后端返回 null 显示 "—"
         无运营报价则 Layer ① 显示"暂无报价"; 无实际成本则 Layer ③ 显示"待生产"诚实占位.
    ────────────────────────────────────────────────────────────── -->
    <el-card
      v-if="breakdown && breakdown.lines.length > 0"
      v-loading="operationalQuoteLoading"
      shadow="never"
      class="comparison-card"
      style="margin-bottom: 16px"
    >
      <template #header>
        <div class="card-header">
          <span class="card-title">三层价格同屏对比</span>
          <span class="card-subtitle">研发预估价 / 下单价 / 实际成本价</span>
        </div>
      </template>
      <el-table :data="breakdown.lines" border stripe size="small" empty-text="无行项目">
        <el-table-column prop="productName" label="产品" min-width="160">
          <template #default="{ row }">{{ row.productName || row.productId || '—' }}</template>
        </el-table-column>
        <el-table-column label="数量" align="right" width="90">
          <template #default="{ row }">{{ row.quantity ?? '—' }}</template>
        </el-table-column>

        <!-- ① 研发预估价 (OperationalQuote 运营报价审批价) -->
        <el-table-column label="① 研发预估价" align="right" min-width="140">
          <template #default="{ row }">
            <template v-if="row.productId && operationalQuoteMap[row.productId] !== undefined">
              <template v-if="operationalQuoteMap[row.productId]">
                <span style="font-weight: 600">
                  {{ operationalQuoteMap[row.productId]!.unitPrice != null
                      ? formatAmount(operationalQuoteMap[row.productId]!.unitPrice)
                      : '—' }}
                </span>
                <div style="font-size: 11px; color: #909399; margin-top: 2px">
                  {{ operationalQuoteMap[row.productId]!.quoteNo }}
                </div>
              </template>
              <el-tag v-else size="small" type="info">暂无报价</el-tag>
            </template>
            <span v-else style="color: #c0c4cc">加载中…</span>
          </template>
        </el-table-column>

        <!-- ② 下单价 (SalesOrderItem.unitPrice) -->
        <el-table-column label="② 下单价" align="right" min-width="130">
          <template #default="{ row }">
            <span v-if="row.unitPrice != null" style="font-weight: 600">{{ formatAmount(row.unitPrice) }}</span>
            <span v-else>—</span>
            <!-- 与研发预估价对比 -->
            <template v-if="row.productId && operationalQuoteMap[row.productId]?.unitPrice != null && row.unitPrice != null">
              <div style="font-size: 11px; margin-top: 2px">
                <span :class="row.unitPrice < operationalQuoteMap[row.productId]!.unitPrice! ? 'price-below' : 'price-above'">
                  {{ row.unitPrice < operationalQuoteMap[row.productId]!.unitPrice!
                      ? '▼ 低于预估'
                      : row.unitPrice > operationalQuoteMap[row.productId]!.unitPrice!
                        ? '▲ 高于预估'
                        : '= 等于预估' }}
                  ({{ (((row.unitPrice - operationalQuoteMap[row.productId]!.unitPrice!) / operationalQuoteMap[row.productId]!.unitPrice!) * 100).toFixed(1) }}%)
                </span>
              </div>
            </template>
          </template>
        </el-table-column>

        <!-- ③ 实际成本价 (LineCostBreakdown.actualCostPerUnit) -->
        <el-table-column label="③ 实际成本价" align="right" min-width="140">
          <template #default="{ row }">
            <span v-if="row.actualCostPerUnit != null" style="font-weight: 600">{{ formatAmount(row.actualCostPerUnit) }}</span>
            <el-tag v-else size="small" type="warning">待生产</el-tag>
            <!-- 毛利率 = (下单价 - 实际成本) / 下单价 -->
            <template v-if="row.actualCostPerUnit != null && row.unitPrice != null && row.unitPrice > 0">
              <div style="font-size: 11px; margin-top: 2px">
                <span :class="((row.unitPrice - row.actualCostPerUnit) / row.unitPrice) > 0.1 ? 'price-above' : 'price-below'">
                  毛利率 {{ (((row.unitPrice - row.actualCostPerUnit) / row.unitPrice) * 100).toFixed(1) }}%
                </span>
              </div>
            </template>
          </template>
        </el-table-column>

        <!-- 三价总评 -->
        <el-table-column label="三价差异概览" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.unitPrice != null">
              <div class="price-diff-summary">
                <span v-if="row.productId && operationalQuoteMap[row.productId]?.unitPrice != null">
                  <el-tag
                    size="small"
                    :type="row.unitPrice < operationalQuoteMap[row.productId]!.unitPrice! ? 'danger' : 'success'"
                  >
                    下单{{ row.unitPrice < operationalQuoteMap[row.productId]!.unitPrice! ? '低' : '≥' }}预估
                  </el-tag>
                </span>
                <span v-if="row.actualCostPerUnit != null" style="margin-left: 4px">
                  <el-tag
                    size="small"
                    :type="row.unitPrice > row.actualCostPerUnit ? 'success' : 'danger'"
                  >
                    {{ row.unitPrice > row.actualCostPerUnit ? '有毛利' : '亏损' }}
                  </el-tag>
                </span>
                <span v-if="row.actualCostPerUnit == null" style="margin-left: 4px">
                  <el-tag size="small" type="info">未生产</el-tag>
                </span>
              </div>
            </template>
            <span v-else style="color: #c0c4cc">—</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 三层价格说明 -->
      <div class="three-price-legend">
        <span>① 研发预估价 = 运营报价单审批价 (OperationalQuote, 最新有效)</span>
        <span>② 下单价 = 本单销售单价</span>
        <span>③ 实际成本价 = 完工后回填的移动均价单位成本</span>
        <span v-if="!Object.values(operationalQuoteMap).some(q => q?.unitPrice != null)" style="color: #e6a23c">
          注: 若研发预估价显示 "—", 表示该产品运营报价未对财务角色开放或无有效报价数据。
        </span>
      </div>
    </el-card>

    <!-- B3 售价趋势 / 价格对比 (每产品一卡) -->
    <template v-if="breakdown && breakdown.lines.length > 0">
      <el-card
        v-for="line in breakdown.lines"
        :key="line.productId ?? line.productName ?? 'unknown'"
        v-loading="priceTrendLoading"
        shadow="never"
        class="comparison-card"
      >
        <template #header>
          <div class="card-header">
            <span class="card-title">
              售价趋势 — {{ line.productName || line.productId || '—' }}
            </span>
            <span class="card-subtitle">
              本单单价 {{ formatAmount(line.unitPrice) }}
              <template v-if="priceDiff(line.unitPrice, line.productId) != null">
                <el-tag
                  :type="
                    (priceDiff(line.unitPrice, line.productId) ?? 0) > 10
                      ? 'danger'
                      : (priceDiff(line.unitPrice, line.productId) ?? 0) < -10
                        ? 'warning'
                        : 'success'
                  "
                  size="small"
                  style="margin-left: 8px"
                >
                  {{ (priceDiff(line.unitPrice, line.productId) ?? 0) > 0 ? '+' : '' }}{{
                    (priceDiff(line.unitPrice, line.productId) ?? 0).toFixed(1)
                  }}% vs 历史均价
                </el-tag>
              </template>
            </span>
          </div>
        </template>

        <template v-if="line.productId && priceTrendMap[line.productId]?.length">
          <el-table
            :data="priceTrendMap[line.productId]"
            size="small"
            stripe
            empty-text="暂无历史价格记录"
          >
            <el-table-column prop="orderDate" label="下单日期" min-width="110" />
            <el-table-column prop="orderNumber" label="订单号" min-width="160" />
            <el-table-column label="单价" align="right" min-width="110">
              <template #default="{ row }">
                <span
                  :style="{
                    fontWeight: '600',
                    color:
                      line.unitPrice != null && row.unitPrice != null
                        ? line.unitPrice > row.unitPrice * 1.1
                          ? '#e6a23c'
                          : line.unitPrice < row.unitPrice * 0.9
                            ? '#909399'
                            : '#303133'
                        : '#303133',
                  }"
                >{{ formatAmount(row.unitPrice) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="数量" align="right" min-width="100">
              <template #default="{ row }">
                {{ row.quantity != null ? `${row.quantity} ${row.unit ?? ''}`.trim() : '-' }}
              </template>
            </el-table-column>
          </el-table>
          <!-- 均价摘要 -->
          <div class="trend-summary" v-if="avgPrice(line.productId) != null">
            <span class="label">近期均价:</span>
            <span class="value">{{ formatAmount(avgPrice(line.productId)) }}</span>
            <span class="label" style="margin-left: 16px">本单:</span>
            <span
              class="value"
              :class="profitClass(priceDiff(line.unitPrice, line.productId))"
            >
              {{ formatAmount(line.unitPrice) }}
            </span>
          </div>
        </template>
        <el-empty v-else description="暂无历史价格记录" :image-size="60" />
      </el-card>
    </template>

    <!-- #734 SP3 M3b: 三价成本拆解 + 双口径人工对比 (转录[36:38]"报价vs实际人工是最重要的点") -->
    <ThreePriceCostBreakdown
      v-if="breakdown || orderAggregate"
      :cost-breakdown="breakdown"
      :order-aggregate="orderAggregate"
      :loading="orderAggregateLoading"
      style="margin-bottom: 16px"
    />

    <!-- 旧财审详情仅保留成本与历史信息；审批动作统一进入个人 OA。 -->
    <el-card v-if="canReview" shadow="never" class="action-card">
      <template #header>
        <span class="card-title">OA 审批</span>
      </template>
      <el-alert
        type="info"
        :closable="false"
        title="销售订单审批已迁移至统一 OA 审批中心"
        description="此页面仅展示成本核算和历史信息；通过、驳回等审批动作请在个人 OA 中处理。"
        show-icon
      />
      <div class="action-row">
        <el-button type="primary" @click="goToUnifiedOa">前往统一 OA</el-button>
      </div>
    </el-card>

    <!-- 非审核状态: 显示历史审核 -->
    <el-card
      v-if="!canReview && order?.financeReviewNotes"
      shadow="never"
      class="action-card"
    >
      <template #header>
        <span class="card-title">历史审核意见</span>
      </template>
      <p class="history-notes">{{ order.financeReviewNotes }}</p>
      <p v-if="order.financeReviewedAt" class="history-meta">
        审核于 {{ order.financeReviewedAt }}
      </p>
    </el-card>

  </div>
</template>

<style scoped>
.finance-review-detail {
  padding: 20px;
}
.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
  color: #303133;
}
.summary-card,
.comparison-card,
.action-card {
  margin-bottom: 16px;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
}
.multi-stage-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #dcdfe6;
  font-size: 14px;
  color: #606266;
}
.multi-stage-summary strong {
  color: #303133;
}
.cost-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}
.cost-cell {
  padding: 12px;
  background: #fafafa;
  border-radius: 6px;
}
.summary-grid .label,
.cost-cell .label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
.summary-grid .value {
  font-size: 15px;
  color: #303133;
  font-weight: 500;
}
.value-lg {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}
.margin-pct {
  font-size: 13px;
  font-weight: 400;
  color: #909399;
  margin-left: 6px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.card-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.card-subtitle {
  font-size: 12px;
  color: #909399;
}
.action-row {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}
.history-notes {
  margin: 0;
  color: #303133;
  white-space: pre-wrap;
}
.history-meta {
  margin: 8px 0 0;
  font-size: 12px;
  color: #909399;
}
.profit-positive {
  color: #67c23a;
}
.profit-negative {
  color: #c62828;
}
:deep(.cost-deviation-row) {
  background-color: #fff7e6 !important;
}
:deep(.cost-deviation-row td) {
  background-color: #fff7e6 !important;
}
/* SP3: 超支行红色高亮 (优先级高于偏差黄) */
:deep(.cost-overbudget-row) {
  background-color: #fff0f0 !important;
}
:deep(.cost-overbudget-row td) {
  background-color: #fff0f0 !important;
}
.variance-abs {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
/* B3 trend summary bar */
.trend-summary {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 6px;
  font-size: 13px;
}
.trend-summary .label {
  color: #909399;
}
.trend-summary .value {
  font-weight: 600;
  color: #303133;
}
/* 三层价格同屏对比 */
.price-above {
  color: #67c23a;
  font-weight: 500;
}
.price-below {
  color: #c62828;
  font-weight: 500;
}
.price-diff-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}
.three-price-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  margin-top: 12px;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 6px;
  font-size: 11px;
  color: #909399;
}
</style>
