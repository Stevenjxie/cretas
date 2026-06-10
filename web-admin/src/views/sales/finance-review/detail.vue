<script setup lang="ts">
/**
 * Sprint4-H F-AR-1 — 销售单财务审核详情页 (PC).
 *
 * 业务流程:
 *   - 加载订单 + 成本核算 (FinanceCostBreakdown)
 *   - 展示 BOM 标准成本 / 当前预估成本 / 实际成本 / 预估 vs 实际利润对比
 *   - 财务可选录入 estimatedCost 覆盖现值 → approve
 *   - reject 必填 notes
 *
 * RBAC: v-if="canFinanceWrite" 隐藏 approve/reject 按钮.
 * 后端 @RequirePermission("finance:read_write") 强制 — UI 仅是友好遮挡.
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft } from '@element-plus/icons-vue';
import {
  getOrderDetail,
  getOrderCostBreakdown,
  financeApprove,
  financeReject,
  getProductPriceTrend,
  type SalesOrderSummary,
  type FinanceCostBreakdown,
  type LineCostBreakdown,
  type SalesPriceTrendDTO,
} from '@/api/salesFinanceReview';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const orderId = computed(() => String(route.params.id || ''));

/** 后端 @RequirePermission("finance:read_write") — UI 镜像. */
const canFinanceWrite = computed(() => permissionStore.canWrite('finance'));

const order = ref<SalesOrderSummary | null>(null);
const breakdown = ref<FinanceCostBreakdown | null>(null);
const notes = ref('');
const overrideEstimatedCost = ref<number | null>(null);
const loading = ref(false);
const submitting = ref(false);

// B3 售价趋势 — 按产品类型分组，key=productTypeId
const priceTrendMap = ref<Record<string, SalesPriceTrendDTO[]>>({});
const priceTrendLoading = ref(false);

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
 *   - 审核操作 form 的 "预估成本 覆盖输入" item
 *   - approve payload 的 estimatedCost 字段 (隐藏时不发送 override)
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
      // 预填 current estimated cost 到 override 输入框
      if (breakdownRes.data.currentEstimatedCost != null) {
        overrideEstimatedCost.value = breakdownRes.data.currentEstimatedCost;
      }
      // B3: 异步加载各产品线的售价趋势 (非阻塞, 独立 loading)
      void loadPriceTrends(breakdownRes.data.lines);
    }
  } finally {
    loading.value = false;
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

async function handleApprove() {
  try {
    await ElMessageBox.confirm(
      `确认通过销售单 ${order.value?.orderNumber} 的财务审核?`,
      '财务审核通过',
      { type: 'success', confirmButtonText: '确认通过', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    // Issue #778: feature flag — disabled state 不发送 estimatedCost override,
    // 保留后端 service 计算的当前预估值不变.
    const res = await financeApprove(factoryId.value, orderId.value, {
      notes: notes.value || undefined,
      estimatedCost: SHOW_PRE_ESTIMATED_COST
        ? (overrideEstimatedCost.value ?? undefined)
        : undefined,
    });
    if (res.success) {
      ElMessage.success('财务审核已通过');
      router.back();
    }
  } finally {
    submitting.value = false;
  }
}

async function handleReject() {
  if (!notes.value.trim()) {
    ElMessage.warning('驳回必须填写备注说明原因');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认驳回销售单 ${order.value?.orderNumber}? 备注: ${notes.value}`,
      '财务驳回',
      { type: 'warning', confirmButtonText: '确认驳回', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    const res = await financeReject(factoryId.value, orderId.value, notes.value);
    if (res.success) {
      ElMessage.success('已驳回, 订单退回销售员');
      router.back();
    }
  } finally {
    submitting.value = false;
  }
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

    <!-- 审核操作 -->
    <el-card v-if="canReview && canFinanceWrite" shadow="never" class="action-card">
      <template #header>
        <span class="card-title">审核意见</span>
      </template>
      <el-form label-position="top">
        <!-- Issue #778: 预估成本输入暂时隐藏 (feature flag SHOW_PRE_ESTIMATED_COST). -->
        <el-form-item v-if="SHOW_PRE_ESTIMATED_COST" label="预估成本 (财务核定, 覆盖系统当前值)">
          <el-input-number
            v-model="overrideEstimatedCost"
            :min="0"
            :precision="2"
            :step="100"
            placeholder="留空则保持当前值不变"
            style="width: 240px"
            :disabled="submitting"
          />
        </el-form-item>
        <el-form-item label="审核备注 (驳回必填, 通过可选)">
          <el-input
            v-model="notes"
            type="textarea"
            :rows="3"
            placeholder="驳回必填, 通过可选"
            maxlength="500"
            show-word-limit
            :disabled="submitting"
          />
        </el-form-item>
      </el-form>
      <div class="action-row">
        <el-button
          plain
          type="danger"
          :loading="submitting"
          @click="handleReject"
        >
          驳回
        </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          @click="handleApprove"
        >
          通过
        </el-button>
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

    <!-- 非审核可见但当前角色无权写: 提示 -->
    <el-alert
      v-if="canReview && !canFinanceWrite"
      type="warning"
      :closable="false"
      title="当前角色无财务写权限, 仅可查看"
      description="approve/reject 需要 finance:read_write (后端 @RequirePermission 强制)"
      style="margin-top: 12px"
    />
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
</style>
